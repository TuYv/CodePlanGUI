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
data class WriteFileInput(
    val path: String,
    val content: String,
)

data class WriteFileOutput(
    val path: String,
    val bytesWritten: Long,
    val isNewFile: Boolean,
)

private val json = Json { ignoreUnknownKeys = true }

val WriteFileTool: Tool<WriteFileInput, WriteFileOutput> = tool {
    name = "WriteFile"
    description = """
        Write content to a file, creating it if it doesn't exist or overwriting it if it does.
        Prefer FileEdit for targeted in-place edits. Use WriteFile when creating new files or
        when replacing the entire file content. Returns the number of bytes written.
    """.trimIndent()

    inputSchema = buildJsonObject {
        put("type", "object")
        put("required", JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("content"))))
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Absolute path, or path relative to project root.")
            })
            put("content", buildJsonObject {
                put("type", "string")
                put("description", "Full content to write to the file.")
            })
        })
    }

    parse { raw: JsonElement -> json.decodeFromJsonElement(WriteFileInput.serializer(), raw) }

    validate { input, _ ->
        if (input.path.isBlank()) ValidationResult.Failed("path must not be blank", errorCode = 1)
        else ValidationResult.Ok
    }

    checkPermissions { input, ctx ->
        val basePath = ctx.project.basePath
            ?: return@checkPermissions PermissionResult.Deny("Project path unavailable")
        val resolved = resolveInsideWorkspace(input.path, basePath, ctx.permissionContext.additionalWorkingDirectories)
            ?: return@checkPermissions PermissionResult.Deny("Path resolves outside workspace: ${input.path}")

        if (ctx.permissionContext.mode == ToolPermissionContext.Mode.ACCEPT_EDITS ||
            ctx.permissionContext.mode == ToolPermissionContext.Mode.BYPASS) {
            return@checkPermissions PermissionResult.Allow(
                Json.encodeToJsonElement(WriteFileInput.serializer(), input)
            )
        }

        val isNew = !File(resolved).exists()
        val action = if (isNew) "Create" else "Overwrite"
        PermissionResult.Ask(
            reason = "$action ${input.path}",
            preview = PreviewResult(
                summary = "$action file: ${input.path}",
                details = "Content length: ${input.content.length} chars\n" +
                    if (isNew) "(new file)" else "(file will be overwritten)",
                risk = if (isNew) PreviewResult.Risk.LOW else PreviewResult.Risk.HIGH,
            ),
        )
    }

    call { input, ctx, _ ->
        val basePath = ctx.project.basePath!!
        val resolved = resolveInsideWorkspace(
            input.path, basePath, ctx.permissionContext.additionalWorkingDirectories
        )!!

        withContext(Dispatchers.IO) {
            val file = File(resolved)
            val isNew = !file.exists()
            file.parentFile?.mkdirs()
            val bytes = input.content.toByteArray(Charsets.UTF_8)
            file.writeBytes(bytes)
            ToolResult(WriteFileOutput(resolved, bytes.size.toLong(), isNew))
        }
    }

    mapResult { output, toolUseId ->
        val action = if (output.isNewFile) "Created" else "Wrote"
        ToolResultBlock(
            toolUseId = toolUseId,
            content = "$action ${output.path} (${output.bytesWritten} bytes)",
        )
    }

    isConcurrencySafe { false }
    isReadOnly { false }
    isDestructive { true }

    activityDescription { input -> input?.let { "Writing ${it.path}" } }
}
