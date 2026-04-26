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
data class FileListInput(
    val path: String = ".",
    val recursive: Boolean = false,
    val includeHidden: Boolean = false,
)

data class FileListOutput(
    val path: String,
    val entries: List<String>,
    val truncated: Boolean,
)

private val json = Json { ignoreUnknownKeys = true }
private const val FILE_LIST_MAX_ENTRIES = 500

val FileListTool: Tool<FileListInput, FileListOutput> = tool {
    name = "FileList"
    aliases = listOf("list_files")
    description = """
        List files and directories in a given path. Defaults to the project root.
        Set recursive=true to walk subdirectories (capped at $FILE_LIST_MAX_ENTRIES entries).
    """.trimIndent()

    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Directory to list. Defaults to '.' (project root).")
            })
            put("recursive", buildJsonObject {
                put("type", "boolean")
                put("description", "Walk subdirectories. Defaults to false.")
            })
            put("includeHidden", buildJsonObject {
                put("type", "boolean")
                put("description", "Include hidden files/dirs (starting with '.'). Defaults to false.")
            })
        })
    }

    parse { raw: JsonElement -> json.decodeFromJsonElement(FileListInput.serializer(), raw) }

    validate { input, _ ->
        if (input.path.isBlank()) ValidationResult.Failed("path must not be blank", errorCode = 1)
        else ValidationResult.Ok
    }

    checkPermissions { input, ctx ->
        val basePath = ctx.project.basePath
            ?: return@checkPermissions PermissionResult.Deny("Project path unavailable")
        resolveInsideWorkspace(input.path, basePath, ctx.permissionContext.additionalWorkingDirectories)
            ?: return@checkPermissions PermissionResult.Deny("Path resolves outside workspace: ${input.path}")
        PermissionResult.Allow(Json.encodeToJsonElement(FileListInput.serializer(), input))
    }

    call { input, ctx, _ ->
        val basePath = ctx.project.basePath!!
        val resolved = resolveInsideWorkspace(
            input.path, basePath, ctx.permissionContext.additionalWorkingDirectories
        )!!

        withContext(Dispatchers.IO) {
            val dir = File(resolved)
            require(dir.exists()) { "Directory not found: ${input.path}" }
            require(dir.isDirectory) { "Not a directory: ${input.path}" }

            val entries = mutableListOf<String>()
            var truncated = false

            fun collect(f: File, prefix: String) {
                if (entries.size >= FILE_LIST_MAX_ENTRIES) { truncated = true; return }
                if (!input.includeHidden && f.name.startsWith('.')) return
                val line = buildString {
                    append(prefix)
                    append(f.name)
                    if (f.isDirectory) append('/')
                }
                entries.add(line)
                if (input.recursive && f.isDirectory) {
                    f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                        ?.forEach { collect(it, "$prefix  ") }
                }
            }

            dir.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                ?.forEach { collect(it, "") }

            ToolResult(FileListOutput(resolved, entries, truncated))
        }
    }

    mapResult { output, toolUseId ->
        val content = buildString {
            appendLine("path: ${output.path}")
            appendLine()
            output.entries.forEach { appendLine(it) }
            if (output.truncated) appendLine("... (truncated at $FILE_LIST_MAX_ENTRIES entries)")
        }.trimEnd()
        ToolResultBlock(toolUseId, content)
    }

    isConcurrencySafe { true }
    isReadOnly { true }
    isDestructive { false }

    activityDescription { input -> input?.let { "Listing ${it.path}" } }
}
