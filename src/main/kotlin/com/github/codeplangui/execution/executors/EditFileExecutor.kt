package com.github.codeplangui.execution.executors

import com.github.codeplangui.execution.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Precise text replacement in files.
 * Returns pendingReview for dispatcher-level approval, then writes.
 */
class EditFileExecutor : ToolExecutor {

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val path = input["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "Missing required parameter: path")
        val search = input["search"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "Missing required parameter: search")
        val replace = input["replace"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(ok = false, output = "Missing required parameter: replace")
        val replaceAll = input["replaceAll"]?.jsonPrimitive?.booleanOrNull ?: false
        val lineNumber = input["line_number"]?.jsonPrimitive?.intOrNull

        val resolvedPath = ReadFileExecutor.resolveToolPath(path, context.cwd)
            ?: return ToolResult(ok = false, output = "Path resolves outside workspace: $path")

        return withContext(Dispatchers.IO) {
            val file = File(resolvedPath)
            if (!file.exists()) {
                return@withContext ToolResult(ok = false, output = "File not found: $path")
            }

            val originalContent = file.readText()
            if (!originalContent.contains(search)) {
                return@withContext ToolResult(ok = false, output = "Search text not found in $path")
            }

            // Count matches and find line numbers
            val matchLines = originalContent.lines().mapIndexedNotNull { idx, line ->
                if (line.contains(search)) idx + 1 else null
            }
            val matchCount = matchLines.size

            if (!replaceAll && matchCount > 1) {
                if (lineNumber == null) {
                    return@withContext ToolResult(
                        ok = false,
                        output = "Found $matchCount matches for the search text in $path. " +
                            "Matching lines: ${matchLines.joinToString(", ")}. " +
                            "Provide 'line_number' parameter to specify which match to replace."
                    )
                }
                val targetLine = lineNumber
                if (targetLine !in matchLines) {
                    return@withContext ToolResult(
                        ok = false,
                        output = "No match found at line $targetLine. Matching lines: ${matchLines.joinToString(", ")}"
                    )
                }
            }

            // Generate new content
            val newContent = if (replaceAll) {
                originalContent.split(search).joinToString(replace)
            } else if (lineNumber != null && matchCount > 1) {
                replaceAtLine(originalContent, search, replace, lineNumber)
            } else {
                originalContent.replaceFirst(search, replace)
            }

            if (newContent == originalContent) {
                return@withContext ToolResult(ok = false, output = "No changes made (replacement text same as search text)")
            }

            // Return pendingReview — dispatcher handles approval + write
            ToolResult(
                ok = true,
                output = "Pending review",
                pendingReview = FileChangeReviewData(
                    path = resolvedPath,
                    originalContent = originalContent,
                    newContent = newContent
                )
            )
        }
    }

    private fun replaceAtLine(content: String, search: String, replace: String, targetLine: Int): String {
        val lines = content.lines().toMutableList()
        if (targetLine < 1 || targetLine > lines.size) return content
        val idx = targetLine - 1
        if (lines[idx].contains(search)) {
            lines[idx] = lines[idx].replaceFirst(search, replace)
        }
        return lines.joinToString("\n")
    }
}
