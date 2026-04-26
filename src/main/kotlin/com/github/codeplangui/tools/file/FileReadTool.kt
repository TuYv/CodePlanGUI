package com.github.codeplangui.tools.file

import com.github.codeplangui.tools.PermissionResult
import com.github.codeplangui.tools.Progress
import com.github.codeplangui.tools.Tool
import com.github.codeplangui.tools.ToolExecutionContext
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
data class FileReadInput(
    val path: String,
    val offset: Int? = null,
    val limit: Int? = null,
)

data class FileReadOutput(
    val content: String,
    val path: String,
    val totalLines: Int,
    val returnedLines: Int,
    val truncated: Boolean,
)

private val json = Json { ignoreUnknownKeys = true }

// Hard caps — mirrored to Claude Code's FileReadTool limits. A single tool call
// must not blow out the model context window.
internal const val FILE_READ_MAX_LINES = 10_000
internal const val FILE_READ_MAX_BYTES = 2L * 1024 * 1024  // 2 MiB
internal const val FILE_READ_DEFAULT_LIMIT = 2_000

val FileReadTool: Tool<FileReadInput, FileReadOutput> = tool {
    name = "FileRead"
    description = """
        Read text file contents with optional offset/limit. Returns lines prefixed by
        line numbers (format: "   1→content"). Hard caps: 10000 lines or 2MB per call;
        use offset/limit to page through larger files.
    """.trimIndent()

    inputSchema = buildJsonObject {
        put("type", "object")
        put("required", JsonArray(listOf(JsonPrimitive("path"))))
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Absolute path, or path relative to project root.")
            })
            put("offset", buildJsonObject {
                put("type", "integer")
                put("description", "1-indexed starting line. Defaults to 1.")
            })
            put("limit", buildJsonObject {
                put("type", "integer")
                put("description", "Number of lines to return. Defaults to 2000, max 10000.")
            })
        })
    }

    parse { raw: JsonElement -> json.decodeFromJsonElement(FileReadInput.serializer(), raw) }

    validate { input, _ ->
        when {
            input.path.isBlank() ->
                ValidationResult.Failed("path must not be blank", errorCode = 1)
            input.offset != null && input.offset < 1 ->
                ValidationResult.Failed("offset must be >= 1", errorCode = 2)
            input.limit != null && (input.limit < 1 || input.limit > FILE_READ_MAX_LINES) ->
                ValidationResult.Failed(
                    "limit must be between 1 and $FILE_READ_MAX_LINES",
                    errorCode = 3,
                )
            else -> ValidationResult.Ok
        }
    }

    checkPermissions { input, ctx ->
        val basePath = ctx.project.basePath
            ?: return@checkPermissions PermissionResult.Deny("Project path unavailable")
        val resolved = resolveInsideWorkspace(input.path, basePath, ctx.permissionContext.additionalWorkingDirectories)
        if (resolved == null) {
            PermissionResult.Deny("Path resolves outside workspace: ${input.path}")
        } else {
            PermissionResult.Allow(Json.encodeToJsonElement(FileReadInput.serializer(), input))
        }
    }

    // preview() returns null — read operations have no side effects.

    call { input, ctx, _: (Progress) -> Unit ->
        val basePath = ctx.project.basePath
            ?: error("Project path unavailable; permission layer should have rejected this")
        val resolvedPath = resolveInsideWorkspace(input.path, basePath, ctx.permissionContext.additionalWorkingDirectories)
            ?: error("Path outside workspace; permission layer should have rejected this")

        withContext(Dispatchers.IO) {
            val file = File(resolvedPath)
            require(file.exists()) { "File not found: ${input.path}" }
            require(file.isFile) { "Not a regular file: ${input.path}" }

            val allLines = if (file.length() > FILE_READ_MAX_BYTES) {
                // Stream up to the byte cap; dropping trailing partial line to avoid
                // handing the LLM a half-word.
                val bytes = file.inputStream().buffered().use { it.readNBytes(FILE_READ_MAX_BYTES.toInt()) }
                String(bytes, Charsets.UTF_8).lines().dropLast(1)
            } else {
                file.readText(Charsets.UTF_8).lines()
            }
            // `readText` + `lines()` produces a trailing empty element for files ending
            // in newline. Drop it so totalLines matches intuition.
            val totalLines = if (allLines.isNotEmpty() && allLines.last().isEmpty()) allLines.size - 1 else allLines.size
            val effectiveLines = if (allLines.isNotEmpty() && allLines.last().isEmpty()) allLines.dropLast(1) else allLines

            val startIdx = (input.offset ?: 1) - 1  // 0-indexed
            val limit = input.limit ?: FILE_READ_DEFAULT_LIMIT
            val endIdx = minOf(startIdx + limit, effectiveLines.size)
            val returned = if (startIdx >= effectiveLines.size) emptyList() else effectiveLines.subList(startIdx, endIdx)

            val truncatedByByteCap = file.length() > FILE_READ_MAX_BYTES
            val truncatedByLimit = endIdx < effectiveLines.size
            val truncated = truncatedByByteCap || truncatedByLimit

            val width = maxOf(1, endIdx.toString().length)
            val content = buildString {
                returned.forEachIndexed { i, line ->
                    val ln = (startIdx + i + 1).toString().padStart(width)
                    append(ln).append('→').append(line).append('\n')
                }
            }.trimEnd('\n')

            ToolResult(
                FileReadOutput(
                    content = content,
                    path = resolvedPath,
                    totalLines = totalLines,
                    returnedLines = returned.size,
                    truncated = truncated,
                )
            )
        }
    }

    mapResult { output, toolUseId ->
        val header = buildString {
            appendLine("path: ${output.path}")
            appendLine("total_lines: ${output.totalLines}")
            appendLine("returned_lines: ${output.returnedLines}")
            if (output.truncated) appendLine("truncated: true — use offset/limit to continue")
            appendLine()
        }
        ToolResultBlock(
            toolUseId = toolUseId,
            content = header + output.content,
            isError = false,
        )
    }

    isConcurrencySafe { true }
    isReadOnly { true }
    isDestructive { false }

    activityDescription { input -> input?.let { "Reading ${it.path}" } }
}

/**
 * Resolve a tool-supplied path to an absolute canonical path that is inside the
 * workspace (or one of the explicitly allowed additional directories). Returns
 * null if the path escapes via `..`, symlink, or an unrelated absolute path.
 */
internal fun resolveInsideWorkspace(
    rawPath: String,
    basePath: String,
    additionalWorkingDirs: Set<String> = emptySet(),
): String? {
    val resolved = File(basePath, rawPath).canonicalPath
    val canonicalBase = File(basePath).canonicalPath
    val allowedRoots = buildList {
        add(canonicalBase)
        additionalWorkingDirs.forEach { add(File(it).canonicalPath) }
    }
    return if (allowedRoots.any { root -> resolved == root || resolved.startsWith("$root${File.separator}") }) {
        resolved
    } else {
        null
    }
}
