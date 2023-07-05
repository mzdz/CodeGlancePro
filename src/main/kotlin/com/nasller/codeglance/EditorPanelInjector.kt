package com.nasller.codeglance

import com.intellij.diff.FrameDiffTool
import com.intellij.diff.editor.DiffRequestProcessorEditor
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.ThreesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileOpenedSyncListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorWithProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DirtyUI
import com.intellij.util.ui.JBSwingUtilities
import com.nasller.codeglance.config.CodeGlanceConfigService
import com.nasller.codeglance.config.SettingsChangeListener
import com.nasller.codeglance.panel.GlancePanel
import com.nasller.codeglance.panel.vcs.MyVcsPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import javax.swing.JPanel

class EditorPanelInjector(private val project: Project) : FileOpenedSyncListener,SettingsChangeListener,LafManagerListener,Disposable {
    private val logger = Logger.getInstance(javaClass)
    private var isFirstSetup = true
    init {
        CodeGlancePlugin.projectMap[project] = this
        ApplicationManager.getApplication().messageBus.connect(this).apply {
            subscribe(LafManagerListener.TOPIC, this@EditorPanelInjector)
            subscribe(SettingsChangeListener.TOPIC, this@EditorPanelInjector)
        }
    }

    /** FileOpenedSyncListener */
    override fun fileOpenedSync(source: FileEditorManager, file: VirtualFile, editorsWithProviders: List<FileEditorWithProvider>) {
        val extension = file.fileType.defaultExtension
        if(extension.isNotBlank() && CodeGlanceConfigService.getConfig().disableLanguageSuffix
            .split(",").toSet().contains(extension)) return
        val process = { info: EditorInfo ->
            val layout = (info.editor.component as? JPanel)?.layout
            if (layout is BorderLayout && layout.getLayoutComponent(info.place) == null) {
                setMyPanel(info).apply { changeOriginScrollBarWidth() }
            }
        }
        source.getAllEditors(file).runAllEditors(process) {
            it.processor.addListener({
                FileEditorManager.getInstance(project).getAllEditors(it.file).runAllEditors(process)
            }, it)
        }
    }

    /** SettingsChangeListener */
    override fun onGlobalChanged() {
        val config = CodeGlanceConfigService.getConfig()
        val disable = config.disableLanguageSuffix.split(",").toSet()
        processAllGlanceEditor { oldGlance, info ->
            val oldGlancePanel = oldGlance?.apply { Disposer.dispose(this) }
            val extension = info.editor.virtualFile.fileType.defaultExtension
            if(extension.isNotBlank() && disable.contains(extension)) {
                oldGlancePanel?.changeOriginScrollBarWidth(false)
            } else {
                setMyPanel(info).apply {
                    oldGlancePanel?.let{ glancePanel -> originalScrollbarWidth = glancePanel.originalScrollbarWidth }
                    changeOriginScrollBarWidth()
                    updateImage()
                }
            }
        }
    }

    /** LafManagerListener */
    override fun lookAndFeelChanged(source: LafManager) = if(isFirstSetup) isFirstSetup = false else {
        processAllGlanceEditor { oldGlance, _ -> oldGlance?.apply{ refresh() } }
    }

    private fun processAllGlanceEditor(action: (oldGlance:GlancePanel?,EditorInfo)->Unit){
        try {
            FileEditorManager.getInstance(project).allEditors.runAllEditors({info: EditorInfo ->
                val layout = (info.editor.component as? JPanel)?.layout
                if (layout is BorderLayout) {
                    action(((layout.getLayoutComponent(BorderLayout.LINE_END) ?:
                    layout.getLayoutComponent(BorderLayout.LINE_START)) as? MyPanel)?.panel, info)
                }
            })
        }catch (e:Exception){
            logger.error(e)
        }
    }

    private fun Array<FileEditor>.runAllEditors(withTextEditor: (EditorInfo) -> Unit, diffEditorAction: ((DiffRequestProcessorEditor) -> Unit)? = null) {
        val config = CodeGlanceConfigService.getConfig()
        val where = if (CodeGlanceConfigService.getConfig().isRightAligned) BorderLayout.LINE_END else BorderLayout.LINE_START
        for (fileEditor in this) {
            if (fileEditor is TextEditor && fileEditor.editor is EditorImpl) {
                withTextEditor(EditorInfo(fileEditor.editor as EditorImpl, where))
            } else if (fileEditor is DiffRequestProcessorEditor) {
                when (val viewer = fileEditor.processor.activeViewer) {
                    is UnifiedDiffViewer -> if(viewer.editor is EditorImpl) {
                        withTextEditor(EditorInfo(viewer.editor as EditorImpl, where, viewer))
                    }
                    is OnesideTextDiffViewer -> if(viewer.editor is EditorImpl) {
                        withTextEditor(EditorInfo(viewer.editor as EditorImpl, where, viewer))
                    }
                    is TwosideTextDiffViewer -> if(config.diffTwoSide) {
                        viewer.editors.filterIsInstance<EditorImpl>().forEachIndexed { index, editor ->
                            withTextEditor(EditorInfo(editor, if (index == 0) BorderLayout.LINE_START else BorderLayout.LINE_END, viewer))
                        }
                    }
                    is ThreesideTextDiffViewer -> if(config.diffThreeSide) {
                        viewer.editors.filterIsInstance<EditorImpl>().forEachIndexed{ index, editor -> if(index != 1 || config.diffThreeSideMiddle)
                            withTextEditor(EditorInfo(editor, if (index == 0) BorderLayout.LINE_START else BorderLayout.LINE_END, viewer))
                        }
                    }
                }
                diffEditorAction?.invoke(fileEditor)
            }
        }
    }

    private fun setMyPanel(info: EditorInfo): GlancePanel {
        val glancePanel = GlancePanel(project, info.editor)
        val placeIndex = if (info.place == BorderLayout.LINE_START) GlancePanel.PlaceIndex.Left else GlancePanel.PlaceIndex.Right
        info.editor.apply{
            putUserData(GlancePanel.CURRENT_GLANCE_PLACE_INDEX, placeIndex)
            putUserData(GlancePanel.CURRENT_GLANCE_DIFF_VIEW, info.diffView)
        }
        info.editor.component.add(MyPanel(glancePanel,placeIndex), info.place)
        glancePanel.hideScrollBarListener.addHideScrollBarListener()
        return glancePanel
    }

    internal class MyPanel(val panel: GlancePanel?,placeIndex: GlancePanel.PlaceIndex): JPanel(BorderLayout()){
        init{
            panel?.let{
                add(it)
                if (CodeGlanceConfigService.getConfig().hideOriginalScrollBar){
                    panel.myVcsPanel = MyVcsPanel(panel)
                    add(panel.myVcsPanel!!, if (placeIndex == GlancePanel.PlaceIndex.Left) BorderLayout.EAST else BorderLayout.WEST)
                }
            }
        }

        override fun getBackground(): Color? = panel?.run { editor.contentComponent.background } ?: super.getBackground()

        @DirtyUI
        override fun getComponentGraphics(graphics: Graphics): Graphics {
            return JBSwingUtilities.runGlobalCGTransform(this, super.getComponentGraphics(graphics))
        }
    }

    override fun dispose() {}

    private data class EditorInfo(val editor: EditorImpl, val place: String, val diffView: FrameDiffTool.DiffViewer? = null)
}