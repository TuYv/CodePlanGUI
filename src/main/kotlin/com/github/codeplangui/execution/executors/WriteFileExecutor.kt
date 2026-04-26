package com.github.codeplangui.execution.executors

import com.github.codeplangui.execution.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Whole-file write (create or overwrite).
 * Returns pendingReview for dispatcher-level approval, then writes.
 */
class WriteFileExecutor : ToolExecutor {

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val path = input["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "Missing required parameter: path")
        val content = input["content"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "Missing required parameter: content")

        val resolvedPath = ReadFileExecutor.resolveToolPath(path, context.cwd)
            ?: return ToolResult(ok = false, output = "Path resolves outside workspace: $path")

        return withContext(Dispatchers.IO) {
            val file = File(resolvedPath)
            val isNewFile = !file.exists()

            if (isNewFile) {
                // New file: return pendingReview with isNewFile = true
                ToolResult(
                    ok = true,
                    output = "Pending review",
                    pendingReview = FileChangeReviewData(
                        path = resolvedPath,
                        originalContent = "",
                        newContent = content,
                        isNewFile = true,
                        newContentForCreate = content
                    )
                )
            } else {
                // Existing file: compute diff, return pendingReview
                val originalContent = file.readText()
                if (originalContent == content) {
                    return@withContext ToolResult(ok = true, output = "File unchanged: $path")
                }

                ToolResult(
                    ok = true,
                    output = "Pending review",
                    pendingReview = FileChangeReviewData(
                        path = resolvedPath,
                        originalContent = originalContent,
                        newContent = content
                    )
                )
            }
        }
    }
}
