package com.github.codeplangui.tools.file

import com.github.codeplangui.tools.PermissionResult
import com.github.codeplangui.tools.PreviewResult
import com.github.codeplangui.tools.Tool
import com.github.codeplangui.tools.ToolPermissionContext
import com.github.codeplangui.tools.ToolResult
import com.github.codeplangui.tools.ToolResultBlock
import com.github.codeplangui.tools.ValidationResult
import com.github.codeplangui.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

@Serializable
data class FileEditInput(
    val path: String,
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean = false,
)

data class FileEditOutput(
    val path: String,
    val replacements: Int,
    val diff: String,
)

private val json = Json { ignoreUnknownKeys = true }
private const val DIFF_CONTEXT_LINES = 3

val FileEditTool: Tool<FileEditInput, FileEditOutput> = tool {
    name = "FileEdit"
    description = """
        Edit an existing file by replacing exact text. Provide the `path`, the exact
        `oldString` to match (must exist verbatim), and the `newString` to substitute.
        Set `replaceAll` to true to replace all occurrences (default: first only).
        Returns a unified diff of the change. Use FileWrite to create new files.
    """.trimIndent()

    inputSchema = buildJsonObject {
        put("type", "object")
        put("required", JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("oldString"), JsonPrimitive("newString"))))
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Absolute path, or path relative to project root.")
            })
            put("oldString", buildJsonObject {
                put("type", "string")
                put("description", "Exact text to search for. Must exist verbatim in the file.")
            })
            put("newString", buildJsonObject {
                put("type", "string")
                put("description", "Replacement text.")
            })
            put("replaceAll", buildJsonObject {
                put("type", "boolean")
                put("description", "Replace all occurrences. Defaults to false (replace first only).")
            })
        })
    }

    parse { raw: JsonElement -> json.decodeFromJsonElement(FileEditInput.serializer(), raw) }

    validate { input, _ ->
        when {
            input.path.isBlank() ->
                ValidationResult.Failed("path must not be blank", errorCode = 1)
            input.oldString.isEmpty() ->
                ValidationResult.Failed("oldString must not be empty", errorCode = 2)
            input.oldString == input.newString ->
                ValidationResult.Failed("oldString and newString are identical — no change would occur", errorCode = 3)
            else -> ValidationResult.Ok
        }
    }

    checkPermissions { input, ctx ->
        val basePath = ctx.project.basePath
            ?: return@checkPermissions PermissionResult.Deny("Project path unavailable")
        val resolved = resolveInsideWorkspace(input.path, basePath, ctx.permissionContext.additionalWorkingDirectories)
            ?: return@checkPermissions PermissionResult.Deny("Path resolves outside workspace: ${input.path}")

        val content = withContext(Dispatchers.IO) {
            val file = File(resolved)
            if (!file.exists() || !file.isFile) null else file.readText(Charsets.UTF_8)
        } ?: return@checkPermissions PermissionResult.Deny("File not found: ${input.path}")

        val count = content.split(input.oldString).size - 1
        if (count == 0) {
            return@checkPermissions PermissionResult.Deny("oldString not found in ${input.path}")
        }

        // ACCEPT_EDITS / BYPASS modes skip the confirmation dialog
        if (ctx.permissionContext.mode == ToolPermissionContext.Mode.ACCEPT_EDITS ||
            ctx.permissionContext.mode == ToolPermissionContext.Mode.BYPASS) {
            return@checkPermissions PermissionResult.Allow(
                Json.encodeToJsonElement(FileEditInput.serializer(), input)
            )
        }

        val effectiveCount = if (input.replaceAll) count else 1
        val diff = buildEditPreviewDiff(content, input.oldString, input.newString, input.replaceAll, input.path)
        PermissionResult.Ask(
            reason = "Edit ${input.path}: replace $effectiveCount occurrence(s)",
            preview = PreviewResult(
                summary = "Replace $effectiveCount occurrence(s) in ${input.path}",
                details = diff,
                risk = PreviewResult.Risk.MEDIUM,
            ),
        )
    }

    preview { input, ctx ->
        val basePath = ctx.project.basePath ?: return@preview null
        val resolved = resolveInsideWorkspace(
            input.path, basePath, ctx.permissionContext.additionalWorkingDirectories
        ) ?: return@preview null
        val content = withContext(Dispatchers.IO) {
            val f = File(resolved)
            if (!f.exists() || !f.isFile) null else f.readText(Charsets.UTF_8)
        } ?: return@preview null
        val count = content.split(input.oldString).size - 1
        if (count == 0) return@preview null
        val effectiveCount = if (input.replaceAll) count else 1
        val diff = buildEditPreviewDiff(content, input.oldString, input.newString, input.replaceAll, input.path)
        PreviewResult(
            summary = "Replace $effectiveCount occurrence(s) in ${input.path}",
            details = diff,
            risk = PreviewResult.Risk.MEDIUM,
        )
    }

    call { input, ctx, _ ->
        val basePath = ctx.project.basePath!!
        val resolved = resolveInsideWorkspace(
            input.path, basePath, ctx.permissionContext.additionalWorkingDirectories
        )!!

        withContext(Dispatchers.IO) {
            val file = File(resolved)
            require(file.exists() && file.isFile) { "File not found: ${input.path}" }

            val original = file.readText(Charsets.UTF_8)
            val count = original.split(input.oldString).size - 1
            require(count > 0) { "oldString not found in ${input.path}" }

            val effectiveCount = if (input.replaceAll) count else 1
            val modified = if (input.replaceAll) {
                original.replace(input.oldString, input.newString)
            } else {
                original.replaceFirst(input.oldString, input.newString)
            }

            val diff = buildUnifiedDiff(original, modified, input.path)
            file.writeText(modified, Charsets.UTF_8)

            ToolResult(FileEditOutput(resolved, effectiveCount, diff))
        }
    }

    mapResult { output, toolUseId ->
        val content = buildString {
            appendLine("path: ${output.path}")
            appendLine("replacements: ${output.replacements}")
            appendLine()
            append(output.diff)
        }
        ToolResultBlock(toolUseId = toolUseId, content = content)
    }

    isConcurrencySafe { false }
    isReadOnly { false }
    isDestructive { true }

    activityDescription { input -> input?.let { "Editing ${it.path}" } }
}

/** Preview diff for the Ask permission prompt — shows first hunk plus a "N more" note. */
internal fun buildEditPreviewDiff(
    content: String,
    oldString: String,
    newString: String,
    replaceAll: Boolean,
    path: String,
): String {
    val firstHunk = buildUnifiedDiff(content, content.replaceFirst(oldString, newString), path)
    if (!replaceAll) return firstHunk
    val extra = content.split(oldString).size - 2  // total occurrences minus the one already shown
    return if (extra > 0) "$firstHunk\n\n... and $extra more replacement(s)" else firstHunk
}

/**
 * Compute a unified diff between two texts. Shows the changed line block with
 * DIFF_CONTEXT_LINES of surrounding context. Returns "(no line changes)" when
 * the line sequences are identical (e.g. only whitespace within a line changed
 * and the line set is the same — shouldn't occur in practice but handled cleanly).
 */
internal fun buildUnifiedDiff(original: String, modified: String, path: String): String {
    val origLines = original.lines()
    val modLines = modified.lines()

    if (origLines == modLines) return "(no line changes)"

    // Number of leading lines that match (prefix)
    val prefix = origLines.zip(modLines).takeWhile { (a, b) -> a == b }.size

    // Number of trailing lines that match (suffix), capped to avoid overlapping the prefix
    var suffix = 0
    val origTail = origLines.size - prefix
    val modTail = modLines.size - prefix
    while (suffix < origTail && suffix < modTail &&
        origLines[origLines.size - 1 - suffix] == modLines[modLines.size - 1 - suffix]
    ) {
        suffix++
    }

    val lastOrig = origLines.size - 1 - suffix  // last changed line in original (inclusive)
    val lastMod = modLines.size - 1 - suffix    // last changed line in modified (inclusive)

    val ctxStart = maxOf(0, prefix - DIFF_CONTEXT_LINES)
    val ctxEndOrig = minOf(origLines.size, lastOrig + 1 + DIFF_CONTEXT_LINES)
    val ctxEndMod = minOf(modLines.size, lastMod + 1 + DIFF_CONTEXT_LINES)

    return buildString {
        appendLine("--- a/$path")
        appendLine("+++ b/$path")
        appendLine("@@ -${ctxStart + 1},${ctxEndOrig - ctxStart} +${ctxStart + 1},${ctxEndMod - ctxStart} @@")
        for (i in ctxStart until prefix) appendLine(" ${origLines[i]}")
        for (i in prefix..lastOrig) appendLine("-${origLines[i]}")
        for (i in prefix..lastMod) appendLine("+${modLines[i]}")
        for (i in (lastOrig + 1) until ctxEndOrig) appendLine(" ${origLines[i]}")
    }.trimEnd()
}
