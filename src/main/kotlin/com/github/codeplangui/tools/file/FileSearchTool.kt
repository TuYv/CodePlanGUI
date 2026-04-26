package com.github.codeplangui.tools.file

import com.github.codeplangui.tools.PermissionResult
import com.github.codeplangui.tools.Tool
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
data class FileSearchInput(
    val pattern: String,
    val path: String = ".",
    val glob: String = "*",
    val ignoreCase: Boolean = false,
    val maxResults: Int = 200,
)

data class FileSearchMatch(
    val file: String,
    val line: Int,
    val text: String,
)

data class FileSearchOutput(
    val matches: List<FileSearchMatch>,
    val truncated: Boolean,
)

private val json = Json { ignoreUnknownKeys = true }
private const val FILE_SEARCH_MAX_RESULTS = 500
private const val FILE_SEARCH_MAX_LINE_LEN = 400

val FileSearchTool: Tool<FileSearchInput, FileSearchOutput> = tool {
    name = "FileSearch"
    aliases = listOf("grep_files", "search_files")
    description = """
        Search file contents for a regex or literal pattern. Returns matching lines with
        file path and line number. Defaults to searching the project root recursively.
        Use `glob` to restrict to specific file types (e.g. "*.kt", "*.ts").
    """.trimIndent()

    inputSchema = buildJsonObject {
        put("type", "object")
        put("required", JsonArray(listOf(JsonPrimitive("pattern"))))
        put("properties", buildJsonObject {
            put("pattern", buildJsonObject {
                put("type", "string")
                put("description", "Regex or literal string to search for.")
            })
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Root directory to search. Defaults to '.' (project root).")
            })
            put("glob", buildJsonObject {
                put("type", "string")
                put("description", "Glob pattern to filter files, e.g. '*.kt'. Defaults to '*'.")
            })
            put("ignoreCase", buildJsonObject {
                put("type", "boolean")
                put("description", "Case-insensitive matching. Defaults to false.")
            })
            put("maxResults", buildJsonObject {
                put("type", "integer")
                put("description", "Max matches to return. Defaults to 200, max $FILE_SEARCH_MAX_RESULTS.")
            })
        })
    }

    parse { raw: JsonElement -> json.decodeFromJsonElement(FileSearchInput.serializer(), raw) }

    validate { input, _ ->
        when {
            input.pattern.isBlank() -> ValidationResult.Failed("pattern must not be blank", errorCode = 1)
            input.maxResults !in 1..FILE_SEARCH_MAX_RESULTS ->
                ValidationResult.Failed("maxResults must be between 1 and $FILE_SEARCH_MAX_RESULTS", errorCode = 2)
            runCatching { if (input.ignoreCase) Regex(input.pattern, RegexOption.IGNORE_CASE) else Regex(input.pattern) }.isFailure ->
                ValidationResult.Failed("pattern is not a valid regex", errorCode = 3)
            else -> ValidationResult.Ok
        }
    }

    checkPermissions { input, ctx ->
        val basePath = ctx.project.basePath
            ?: return@checkPermissions PermissionResult.Deny("Project path unavailable")
        resolveInsideWorkspace(input.path, basePath, ctx.permissionContext.additionalWorkingDirectories)
            ?: return@checkPermissions PermissionResult.Deny("Path resolves outside workspace: ${input.path}")
        PermissionResult.Allow(Json.encodeToJsonElement(FileSearchInput.serializer(), input))
    }

    call { input, ctx, _ ->
        val basePath = ctx.project.basePath!!
        val resolved = resolveInsideWorkspace(
            input.path, basePath, ctx.permissionContext.additionalWorkingDirectories
        )!!

        withContext(Dispatchers.IO) {
            val regex = if (input.ignoreCase) Regex(input.pattern, RegexOption.IGNORE_CASE)
                        else Regex(input.pattern)
            val globRegex = globToRegex(input.glob)

            val matches = mutableListOf<FileSearchMatch>()
            var truncated = false

            File(resolved).walkTopDown()
                .filter { it.isFile && globRegex.matches(it.name) }
                .sortedBy { it.path }
                .forEach { file ->
                    if (truncated) return@forEach
                    try {
                        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                            lines.forEachIndexed { idx, raw ->
                                if (truncated) return@forEachIndexed
                                val line = if (raw.length > FILE_SEARCH_MAX_LINE_LEN)
                                    raw.take(FILE_SEARCH_MAX_LINE_LEN) + "…"
                                else raw
                                if (regex.containsMatchIn(line)) {
                                    matches.add(FileSearchMatch(file.path, idx + 1, line))
                                    if (matches.size >= input.maxResults) truncated = true
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Skip unreadable files (binary, permission denied, etc.)
                    }
                }

            ToolResult(FileSearchOutput(matches, truncated))
        }
    }

    mapResult { output, toolUseId ->
        val content = buildString {
            output.matches.forEach { m ->
                appendLine("${m.file}:${m.line}:${m.text}")
            }
            if (output.matches.isEmpty()) appendLine("(no matches)")
            if (output.truncated) appendLine("... (truncated)")
        }.trimEnd()
        ToolResultBlock(toolUseId, content)
    }

    isConcurrencySafe { true }
    isReadOnly { true }
    isDestructive { false }

    activityDescription { input -> input?.let { "Searching for '${it.pattern}' in ${it.path}" } }
}

/** Convert a simple glob pattern (*, ?) to a Regex that matches filenames. */
private fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    for (c in glob) {
        when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append(".")
            '.' -> sb.append("\\.")
            else -> sb.append(Regex.escape(c.toString()))
        }
    }
    sb.append('$')
    return Regex(sb.toString())
}
