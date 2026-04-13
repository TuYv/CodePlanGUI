package com.github.codeplangui.api

import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import com.github.codeplangui.settings.ProviderConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

@Serializable
private data class ApiMessage(val role: String, val content: String)

@Serializable
private data class ChatRequestBody(
    val model: String,
    val messages: List<ApiMessage>,
    val stream: Boolean,
    val temperature: Double,
    val max_tokens: Int
)

sealed class TestResult {
    data object Success : TestResult()
    data class Failure(val message: String) : TestResult()
}

class OkHttpSseClient(
    private val streamClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val syncClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val commitClient: OkHttpClient = syncClient.newBuilder()
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val testClient: OkHttpClient = syncClient.newBuilder()
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val eventSourceFactory: EventSource.Factory = EventSources.createFactory(streamClient)
) {

    fun buildRequest(
        config: ProviderConfig,
        apiKey: String,
        messages: List<Message>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean
    ): Request {
        val endpoint = config.endpoint.trimEnd('/') + "/chat/completions"
        val apiMessages = messages.map {
            ApiMessage(it.role.name.lowercase(), it.content)
        }
        val body = json.encodeToString(
            ChatRequestBody(
                model = config.model,
                messages = apiMessages,
                stream = stream,
                temperature = temperature,
                max_tokens = maxTokens
            )
        )
        return Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .build()
    }

    fun streamChat(
        request: Request,
        onToken: (String) -> Unit,
        onEnd: () -> Unit,
        onError: (String) -> Unit
    ): EventSource {
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val token = SseChunkParser.extractToken(data)
                if (token != null) onToken(token)
                if (data.trim() == "[DONE]") onEnd()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val msg = buildErrorMessage(response, t)
                onError(msg)
            }
        }
        return eventSourceFactory.newEventSource(request, listener)
    }

    fun callSync(request: Request): Result<String> {
        return callWithClient(syncClient, request, "请求超时，请检查网络")
    }

    fun callCommitSync(request: Request): Result<String> {
        return callWithClient(commitClient, request, "请求超时，请检查网络或缩小本次提交范围")
    }

    private fun callWithClient(
        client: OkHttpClient,
        request: Request,
        timeoutMessage: String
    ): Result<String> {
        var attempt = 1
        var lastException: Exception? = null

        while (attempt <= MAX_RETRY_ATTEMPTS) {
            try {
                val result = client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""

                    // Check if we should retry this response
                    if (shouldRetryHttp(response, body) && attempt < MAX_RETRY_ATTEMPTS) {
                        Thread.sleep(calculateBackoff(attempt))
                        return@use null
                    }

                    // Not retrying, process the response
                    if (!response.isSuccessful) {
                        Result.failure<String>(Exception(buildErrorMessage(response, null, timeoutMessage)))
                    } else {
                        val bodyError = parseBodyError(body)
                        if (bodyError != null) {
                            Result.failure<String>(Exception(bodyError))
                        } else {
                            Result.success(extractSyncContent(body))
                        }
                    }
                }

                // null means retry was triggered
                if (result != null) return result
            } catch (e: Exception) {
                lastException = e
                if (shouldRetryException(e) && attempt < MAX_RETRY_ATTEMPTS) {
                    Thread.sleep(calculateBackoff(attempt))
                    attempt++
                    continue
                }
                return Result.failure(Exception(buildErrorMessage(null, e, timeoutMessage), e))
            }
            attempt++
        }

        return Result.failure(Exception(buildErrorMessage(null, lastException, timeoutMessage), lastException))
    }

    fun testConnection(config: ProviderConfig, apiKey: String): TestResult {
        val testMessages = listOf(Message(MessageRole.USER, "hi"))
        val request = buildRequest(config, apiKey, testMessages, 0.0, 1, false)
        return try {
            testClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return TestResult.Failure("HTTP ${response.code}: ${body.take(200)}")
                }
                // Some providers return HTTP 200 with error details in body
                val bodyError = parseBodyError(body)
                if (bodyError != null) {
                    return TestResult.Failure("HTTP 200: ${bodyError.take(200)}")
                }
                TestResult.Success
            }
        } catch (e: java.net.SocketTimeoutException) {
            TestResult.Failure("连接超时（5s）：请检查 endpoint 是否可访问")
        } catch (e: java.net.ConnectException) {
            TestResult.Failure("无法连接：请检查 endpoint URL")
        } catch (e: Exception) {
            TestResult.Failure(e.message ?: "未知错误")
        }
    }

    /**
     * Checks if a response body indicates an error despite HTTP 200.
     * Handles providers like GLM/Doubao/Qianwen that wrap errors as:
     *   {"code": 500, "msg": "404 NOT_FOUND", "success": false}
     * or standard OpenAI error format:
     *   {"error": {"message": "...", "type": "..."}}
     *
     * Returns enhanced error message with user guidance.
     */
    private fun parseBodyError(body: String): String? {
        return parseBodyErrorDetail(body)?.enhancedMessage
    }

    /**
     * Structured error detail parsed from response body.
     */
    private data class BodyErrorDetail(
        val rawMessage: String,
        val errorType: ErrorType,
        val enhancedMessage: String
    )

    /**
     * Error type classification for better user guidance.
     */
    private enum class ErrorType {
        RETRIABLE_BUSY,  // Service busy, rate limiting
        QUOTA,           // Insufficient quota, billing issues
        AUTH,            // Authentication, permission issues
        GENERIC          // Other errors
    }

    /**
     * Determines if an HTTP response should be retried.
     */
    private fun shouldRetryHttp(response: Response, body: String): Boolean {
        if (response.code in RETRIABLE_STATUS_CODES) return true
        val bodyError = parseBodyErrorDetail(body)
        return bodyError?.errorType == ErrorType.RETRIABLE_BUSY
    }

    /**
     * Determines if an exception should be retried.
     */
    private fun shouldRetryException(e: Exception): Boolean {
        return e is java.net.SocketTimeoutException || e is java.net.ConnectException
    }

    /**
     * Calculates exponential backoff delay.
     */
    private fun calculateBackoff(attempt: Int): Long {
        val backoff = RETRY_BASE_DELAY_MS * (2 shl (attempt - 1))
        return minOf(backoff, RETRY_CAP_DELAY_MS)
    }

    /**
     * Parses body and returns structured error detail if an error is found.
     */
    private fun parseBodyErrorDetail(body: String): BodyErrorDetail? {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject

            // GLM / Doubao / Qianwen style: {"code": 500, "msg": "...", "success": false}
            val msg = obj["msg"]?.jsonPrimitive?.contentOrNull

            // OpenAI style: {"error": {"message": "..."}}
            val errorMsg = obj["error"]
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.contentOrNull

            val rawError = msg ?: errorMsg ?: return null
            val errorType = classifyError(rawError)

            BodyErrorDetail(
                rawMessage = rawError,
                errorType = errorType,
                enhancedMessage = enhanceErrorMessage(rawError, errorType)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Classifies error message into error types for better guidance.
     */
    private fun classifyError(msg: String): ErrorType {
        val lowerMsg = msg.lowercase()
        return when {
            QUOTA_PATTERNS.any { it in lowerMsg } -> ErrorType.QUOTA
            AUTH_PATTERNS.any { it in lowerMsg } -> ErrorType.AUTH
            BUSY_PATTERNS.any { it in lowerMsg } -> ErrorType.RETRIABLE_BUSY
            else -> ErrorType.GENERIC
        }
    }

    /**
     * Enhances raw error message with user-friendly guidance.
     */
    private fun enhanceErrorMessage(msg: String, errorType: ErrorType): String {
        return when (errorType) {
            ErrorType.QUOTA ->
                "$msg\n→ 提示：请检查账户余额或配额是否充足"
            ErrorType.AUTH ->
                "$msg\n→ 提示：请检查 API Key 是否正确"
            ErrorType.RETRIABLE_BUSY ->
                "$msg\n→ 提示：服务繁忙，请稍后重试"
            ErrorType.GENERIC -> {
                val lowerMsg = msg.lowercase()
                when {
                    "404" in msg || "not_found" in lowerMsg ->
                        "$msg\n→ 提示：请检查 endpoint 路径是否正确（通常需要包含 /v1）"
                    "model" in lowerMsg && ("not found" in lowerMsg || "invalid" in lowerMsg) ->
                        "$msg\n→ 提示：请检查模型名称是否正确"
                    else -> msg
                }
            }
        }
    }

    private fun buildErrorMessage(
        response: Response?,
        t: Throwable?,
        timeoutMessage: String = "请求超时，请检查网络"
    ): String = when {
        response != null -> when (response.code) {
            401 -> "HTTP 401：API Key 无效或已过期"
            403 -> "HTTP 403：访问被拒绝，请检查 endpoint 和 Key"
            404 -> "HTTP 404：endpoint 路径不正确（应包含 /v1）"
            422, 400 -> "HTTP ${response.code}：请求格式错误，请检查 model 名称"
            429 -> "HTTP 429：已触发限流，请稍候再试"
            in 500..599 -> "HTTP ${response.code}：服务端错误"
            else -> "HTTP ${response.code}: ${response.body?.string()?.take(200)}"
        }
        t is java.net.SocketTimeoutException -> timeoutMessage
        t is java.net.ConnectException -> "无法连接 endpoint，请检查 URL 和网络"
        t != null -> t.message ?: "未知网络错误"
        else -> "未知错误"
    }

    private fun extractSyncContent(body: String): String {
        return try {
            val parsed = json.parseToJsonElement(body).jsonObject
            parsed["choices"]
                ?.let { it as? kotlinx.serialization.json.JsonArray }
                ?.firstOrNull()
                ?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("message")
                ?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("content")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.content ?: body
        } catch (_: Exception) {
            body
        }
    }

    private companion object {
        // Retry configuration constants
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 1000L
        private const val RETRY_CAP_DELAY_MS = 8000L

        // HTTP status codes that are considered retriable
        private val RETRIABLE_STATUS_CODES = setOf(408, 409, 425, 429, 500, 502, 503, 504)

        // Error pattern configurations for classification
        private val BUSY_PATTERNS = listOf(
            "server busy", "temporarily unavailable", "try again later",
            "please retry", "please try again", "overloaded", "high demand",
            "rate limit", "负载较高", "服务繁忙", "稍后重试", "请稍后重试"
        )
        private val QUOTA_PATTERNS = listOf(
            "insufficient_quota", "quota", "billing", "credit", "payment",
            "余额不足", "超出限额", "额度不足", "欠费"
        )
        private val AUTH_PATTERNS = listOf(
            "authentication", "unauthorized", "invalid api key", "invalid_api_key",
            "permission", "forbidden", "access denied", "无权", "未授权"
        )
    }
}
