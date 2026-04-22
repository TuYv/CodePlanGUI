package com.github.codeplangui.execution.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Fallback strategy: uses Messages.showYesNoDialog with a simple diff summary.
 * Extracted from the original FileChangeReview, behavior unchanged.
 */
class DialogReview : ChangeReviewStrategy {

    override var sessionTrusted: Boolean = false

    override suspend fun reviewFileChange(
        project: Project, requestId: String, path: String,
        originalContent: String, newContent: String
    ): Boolean {
        if (sessionTrusted) return true

        val future = CompletableFuture<Boolean>()

        ApplicationManager.getApplication().invokeAndWait {
            val oldLines = originalContent.lines().size
            val newLines = newContent.lines().size
            val added = (newLines - oldLines).coerceAtLeast(0)
            val removed = (oldLines - newLines).coerceAtLeast(0)

            val message = buildString {
                appendLine("Apply changes to $path?")
                appendLine()
                appendLine("Lines: +$added / -$removed (was $oldLines, now $newLines)")
                appendLine()
                val oldSet = originalContent.lines().toSet()
                val changed = newContent.lines().filter { it !in oldSet }.take(5)
                if (changed.isNotEmpty()) {
                    appendLine("--- New/changed lines (preview) ---")
                    changed.forEach { appendLine(it) }
                }
            }

            val result = Messages.showYesNoDialog(
                project, message, "File Change Review: $path",
                Messages.getQuestionIcon()
            )
            future.complete(result == Messages.YES)
        }

        return future.get(60, TimeUnit.SECONDS)
    }

    override suspend fun reviewNewFile(
        project: Project, requestId: String, path: String, content: String
    ): Boolean {
        if (sessionTrusted) return true

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
}
