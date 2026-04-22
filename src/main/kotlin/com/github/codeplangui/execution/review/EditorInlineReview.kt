package com.github.codeplangui.execution.review

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * IntelliJ native diff dialog review strategy.
 * Shows a diff viewer using DiffManager with Accept/Reject buttons.
 */
class EditorInlineReview(
    private val project: Project
) : ChangeReviewStrategy {

    override var sessionTrusted: Boolean = false

    override suspend fun reviewFileChange(
        project: Project, requestId: String, path: String,
        originalContent: String, newContent: String
    ): Boolean {
        if (sessionTrusted) return true

        val future = CompletableFuture<Boolean>()

        ApplicationManager.getApplication().invokeAndWait {
            val contentFactory = DiffContentFactory.getInstance()

            val ext = path.substringAfterLast('.', "")
            val fileType = FileTypeManager.getInstance().getFileTypeByExtension(ext)
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(path)

            val content1 = if (virtualFile != null) {
                contentFactory.create(project, originalContent, virtualFile)
            } else {
                contentFactory.create(project, originalContent, fileType)
            }
            val content2 = if (virtualFile != null) {
                contentFactory.create(project, newContent, virtualFile)
            } else {
                contentFactory.create(project, newContent, fileType)
            }

            val fileName = path.substringAfterLast('/')
            val request = SimpleDiffRequest(
                "Review Changes: $fileName",
                content1, content2,
                "Before", "After"
            )

            val dialog = DiffReviewDialog(project, request, future)
            dialog.show()
        }

        return try {
            future.get(120, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun reviewNewFile(
        project: Project, requestId: String, path: String, content: String
    ): Boolean {
        if (sessionTrusted) return true
        return showCreateFileConfirmation(project, path, content)
    }

    private fun showCreateFileConfirmation(
        project: Project, path: String, content: String
    ): Boolean {
        val future = CompletableFuture<Boolean>()

        ApplicationManager.getApplication().invokeAndWait {
            val lineCount = content.lines().size
            val sizeBytes = content.toByteArray().size

            val message = buildString {
                appendLine("Create new file?")
                appendLine()
                appendLine("Path: $path")
                appendLine("Size: ${formatSize(sizeBytes)} / $lineCount lines")
                appendLine()
                appendLine("--- Preview (first 20 lines) ---")
                content.lines().take(20).forEach { appendLine(it) }
                if (lineCount > 20) appendLine("... ($lineCount lines total)")
            }

            val result = Messages.showOkCancelDialog(
                project, message, "Create New File",
                "Create", "Cancel", Messages.getQuestionIcon()
            )
            future.complete(result == Messages.OK)
        }

        return future.get(60, TimeUnit.SECONDS)
    }

    private fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }

    /**
     * Custom dialog wrapping DiffManager's diff viewer with Accept/Reject buttons.
     */
    private class DiffReviewDialog(
        project: Project,
        request: SimpleDiffRequest,
        private val resultFuture: CompletableFuture<Boolean>
    ) : DialogWrapper(project, true) {

        private val diffRequest = request
        private val dialogProject = project

        init {
            title = request.title
            setModal(true)
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            val diffPanel = DiffManager.getInstance().createRequestPanel(dialogProject, this.disposable, null)
            diffPanel.setRequest(diffRequest)
            panel.add(diffPanel.component, BorderLayout.CENTER)
            panel.preferredSize = java.awt.Dimension(900, 600)
            return panel
        }

        override fun createActions(): Array<Action> {
            return arrayOf(
                object : AbstractAction("Accept") {
                    override fun actionPerformed(e: ActionEvent?) {
                        resultFuture.complete(true)
                        close(OK_EXIT_CODE)
                    }
                },
                object : AbstractAction("Reject") {
                    override fun actionPerformed(e: ActionEvent?) {
                        resultFuture.complete(false)
                        close(CANCEL_EXIT_CODE)
                    }
                }
            )
        }

        override fun doCancelAction() {
            resultFuture.complete(false)
            super.doCancelAction()
        }
    }
}
