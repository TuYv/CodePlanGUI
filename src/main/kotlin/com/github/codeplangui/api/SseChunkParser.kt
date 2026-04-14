package com.github.codeplangui.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ToolCallDelta(
    val index: Int,
    val id: String?,            // non-null only on the first chunk
    val functionName: String?,  // non-null only on the first chunk
    val argumentsChunk: String?
)

object SseChunkParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Extracts the text token from an OpenAI-compatible SSE data line.
     * Returns null for [DONE], empty content, or any parse error.
     */
    fun extractToken(data: String): String? {
        if (data.trim() == "[DONE]") return null
        return try {
            val obj = json.parseToJsonElement(data).jsonObject
            val content = obj["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("delta")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
            content?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts a tool_call delta from a streaming SSE chunk.
     * Returns null if the chunk is a regular text delta or is unparseable.
     */
    fun extractToolCallChunk(data: String): ToolCallDelta? {
        return extractToolCallChunks(data).firstOrNull()
    }

    /**
     * Extracts all tool_call deltas from a streaming SSE chunk.
     * Returns an empty list if the chunk is a regular text delta or is unparseable.
     */
    fun extractToolCallChunks(data: String): List<ToolCallDelta> {
        if (data.trim() == "[DONE]") return emptyList()
        return try {
            val obj = json.parseToJsonElement(data).jsonObject
            val toolCallsArray = obj["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("delta")
                ?.jsonObject
                ?.get("tool_calls")
                ?.jsonArray
                ?: return emptyList()

            toolCallsArray.mapNotNull { element ->
                val toolCall = element.jsonObject
                val func = toolCall["function"]?.jsonObject

                // `arguments` may be a JSON string (streaming chunks) or an already-parsed
                // JSON object (some providers return it pre-parsed). Handle both forms.
                val argsElement = func?.get("arguments")
                val argsChunk = when (argsElement) {
                    null -> null
                    is kotlinx.serialization.json.JsonPrimitive -> argsElement.contentOrNull
                    else -> argsElement.toString()
                }

                ToolCallDelta(
                    index = toolCall["index"]?.jsonPrimitive?.intOrNull ?: 0,
                    id = toolCall["id"]?.jsonPrimitive?.contentOrNull,
                    functionName = func?.get("name")?.jsonPrimitive?.contentOrNull,
                    argumentsChunk = argsChunk
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Extracts finish_reason from an SSE chunk.
     * Returns null when finish_reason is absent or JSON-null.
     */
    fun extractFinishReason(data: String): String? {
        if (data.trim() == "[DONE]") return null
        return try {
            val obj = json.parseToJsonElement(data).jsonObject
            obj["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("finish_reason")
                ?.jsonPrimitive
                ?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }
}
