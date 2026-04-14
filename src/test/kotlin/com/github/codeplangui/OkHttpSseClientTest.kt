package com.github.codeplangui

import com.github.codeplangui.api.FunctionDefinition
import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.api.TestResult
import com.github.codeplangui.api.ToolDefinition
import com.github.codeplangui.api.summarizeInterestingSseFrame
import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import com.github.codeplangui.settings.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class OkHttpSseClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `buildRequest targets chat completions endpoint and serializes request body`() {
        val client = OkHttpSseClient()
        val request = client.buildRequest(
            config = ProviderConfig(
                id = "provider-1",
                name = "OpenAI",
                endpoint = "https://api.openai.com/v1/",
                model = "gpt-4o-mini"
            ),
            apiKey = "secret-key",
            messages = listOf(
                Message(MessageRole.SYSTEM, "system prompt"),
                Message(MessageRole.USER, "hello")
            ),
            temperature = 0.25,
            maxTokens = 512,
            stream = true
        )

        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = json.parseToJsonElement(buffer.readUtf8()).jsonObject

        assertEquals("https://api.openai.com/v1/chat/completions", request.url.toString())
        assertEquals("Bearer secret-key", request.header("Authorization"))
        assertEquals("text/event-stream", request.header("Accept"))
        assertEquals("gpt-4o-mini", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("0.25", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("512", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("system", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hello", body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `callSync returns parsed content from successful response`() {
        val client = OkHttpSseClient(
            syncClient = syncClientFor(
                responseCode = 200,
                responseBody = """{"choices":[{"message":{"content":"hello back"}}]}"""
            )
        )

        val result = client.callSync(simpleRequest())

        assertEquals("hello back", result.getOrThrow())
    }

    @Test
    fun `callCommitSync uses extended timeout budget`() {
        var observedReadTimeout = -1
        val client = OkHttpSseClient(
            commitClient = OkHttpClient.Builder()
                .readTimeout(45, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    observedReadTimeout = chain.readTimeoutMillis()
                    responseFor(chain.request(), 200, """{"choices":[{"message":{"content":"commit msg"}}]}""")
                }
                .build()
        )

        val result = client.callCommitSync(simpleRequest())

        assertEquals("commit msg", result.getOrThrow())
        assertEquals(45_000, observedReadTimeout)
    }

    @Test
    fun `callSync falls back to raw body when schema is unexpected`() {
        val rawBody = """{"unexpected":true}"""
        val client = OkHttpSseClient(
            syncClient = syncClientFor(
                responseCode = 200,
                responseBody = rawBody
            )
        )

        val result = client.callSync(simpleRequest())

        assertEquals(rawBody, result.getOrThrow())
    }

    @Test
    fun `testConnection returns http failure details for non success status`() {
        val client = OkHttpSseClient(
            syncClient = syncClientFor(
                responseCode = 401,
                responseBody = "bad key"
            )
        )

        val result = client.testConnection(providerConfig(), "secret-key")

        assertEquals(TestResult.Failure("HTTP 401: bad key"), result)
    }

    @Test
    fun `testConnection maps timeout exceptions to user facing message`() {
        val client = OkHttpSseClient(
            syncClient = syncClientFor(thrown = SocketTimeoutException("timed out"))
        )

        val result = client.testConnection(providerConfig(), "secret-key")

        assertEquals(
            TestResult.Failure("连接超时（5s）：请检查 endpoint 是否可访问"),
            result
        )
    }

    @Test
    fun `testConnection uses five second timeout budget`() {
        var observedReadTimeout = -1
        val syncClient = OkHttpClient.Builder()
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                observedReadTimeout = chain.readTimeoutMillis()
                responseFor(chain.request(), 200, """{"choices":[{"message":{"content":"ok"}}]}""")
            }
            .build()
        val client = OkHttpSseClient(syncClient = syncClient)

        val result = client.testConnection(providerConfig(), "secret-key")

        assertEquals(TestResult.Success, result)
        assertEquals(5_000, observedReadTimeout)
    }

    @Test
    fun `streamChat emits tokens and completes on DONE`() {
        val factory = FakeEventSourceFactory()
        val client = OkHttpSseClient(eventSourceFactory = factory)
        val tokens = mutableListOf<String>()
        var ended = false
        var error: String? = null

        val source = client.streamChat(
            request = simpleRequest(),
            onToken = tokens::add,
            onEnd = { ended = true },
            onError = { error = it }
        )

        factory.listener.onEvent(
            source,
            null,
            null,
            """{"choices":[{"delta":{"content":"Hi"}}]}"""
        )
        factory.listener.onEvent(source, null, null, "[DONE]")

        assertEquals(listOf("Hi"), tokens)
        assertTrue(ended)
        assertNull(error)
    }

    @Test
    fun `streamChat forwards mapped error messages`() {
        val factory = FakeEventSourceFactory()
        val client = OkHttpSseClient(eventSourceFactory = factory)
        var error: String? = null

        val source = client.streamChat(
            request = simpleRequest(),
            onToken = {},
            onEnd = {},
            onError = { error = it }
        )

        factory.listener.onFailure(
            source,
            null,
            responseFor(simpleRequest(), 404, "missing")
        )

        assertEquals("HTTP 404：endpoint 路径不正确（应包含 /v1）", error)
    }

    @Test
    fun `streamChat processes tool delta before tool_calls finish reason in the same chunk`() {
        val factory = FakeEventSourceFactory()
        val client = OkHttpSseClient(eventSourceFactory = factory)
        val callbackOrder = mutableListOf<String>()
        var observedFinishReason: String? = null
        var observedToolCall: com.github.codeplangui.api.ToolCallDelta? = null

        val source = client.streamChat(
            request = simpleRequest(),
            onToken = {},
            onEnd = {},
            onError = {},
            onToolCallChunk = {
                callbackOrder += "tool"
                observedToolCall = it
            },
            onFinishReason = {
                callbackOrder += "finish"
                observedFinishReason = it
            }
        )

        factory.listener.onEvent(
            source,
            null,
            null,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"run_command","arguments":"{\"command\":\"ls -la\"}"}}]},"finish_reason":"tool_calls"}]}"""
        )

        assertEquals(listOf("tool", "finish"), callbackOrder)
        assertEquals("tool_calls", observedFinishReason)
        assertEquals("call_1", observedToolCall?.id)
    }

    @Test
    fun `streamChat emits each tool call delta before finish reason when a chunk contains multiple tool calls`() {
        val factory = FakeEventSourceFactory()
        val client = OkHttpSseClient(eventSourceFactory = factory)
        val callbackOrder = mutableListOf<String>()
        val observedToolCalls = mutableListOf<com.github.codeplangui.api.ToolCallDelta>()
        var observedFinishReason: String? = null

        val source = client.streamChat(
            request = simpleRequest(),
            onToken = {},
            onEnd = {},
            onError = {},
            onToolCallChunk = {
                callbackOrder += "tool:${it.index}"
                observedToolCalls += it
            },
            onFinishReason = {
                callbackOrder += "finish"
                observedFinishReason = it
            }
        )

        factory.listener.onEvent(
            source,
            null,
            null,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"run_command","arguments":"{\"command\":\"ls -la\"}"}},{"index":1,"id":"call_2","function":{"name":"run_command","arguments":"{\"command\":\"pwd\"}"}}]},"finish_reason":"tool_calls"}]}"""
        )

        assertEquals(listOf("tool:0", "tool:1", "finish"), callbackOrder)
        assertEquals("tool_calls", observedFinishReason)
        assertEquals(listOf("call_1", "call_2"), observedToolCalls.map { it.id })
    }

    @Test
    fun `summarizeInterestingSseFrame includes tool call and finish reason details`() {
        val summary = summarizeInterestingSseFrame(
            id = null,
            type = null,
            data = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"run_command","arguments":"{\"command\":\"ls -la\"}"}}]},"finish_reason":"tool_calls"}]}"""
        ) ?: error("expected debug summary")

        assertTrue(summary.contains("finish_reason=tool_calls"))
        assertTrue(summary.contains("tool_call[0].id=call_1"))
        assertTrue(summary.contains("tool_call[0].name=run_command"))
        assertTrue(summary.contains("raw={\"choices\""))
    }

    // region parseBodyError tests

    @Test
    fun `parseBodyError detects GLM style error in 200 response`() {
        val client = OkHttpSseClient()
        val body = """{"code": 500, "msg": "404 NOT_FOUND", "success": false}"""
        val method = OkHttpSseClient::class.java.getDeclaredMethod("parseBodyError", String::class.java)
        method.isAccessible = true
        val result = method.invoke(client, body) as String?

        assertTrue(result != null)
        assertTrue(result!!.contains("404 NOT_FOUND"))
        assertTrue(result.contains("请检查 endpoint 路径是否正确"))
    }

    @Test
    fun `parseBodyError detects OpenAI style error in 200 response`() {
        val client = OkHttpSseClient()
        val body = """{"error": {"message": "invalid api key"}}"""
        val method = OkHttpSseClient::class.java.getDeclaredMethod("parseBodyError", String::class.java)
        method.isAccessible = true
        val result = method.invoke(client, body) as String?

        assertTrue(result != null)
        assertTrue(result!!.contains("invalid api key"))
        assertTrue(result.contains("请检查 API Key 是否正确"))
    }

    @Test
    fun `parseBodyError adds quota guidance for billing errors`() {
        val client = OkHttpSseClient()
        val body = """{"code": 402, "msg": "余额不足", "success": false}"""
        val method = OkHttpSseClient::class.java.getDeclaredMethod("parseBodyError", String::class.java)
        method.isAccessible = true
        val result = method.invoke(client, body) as String?

        assertTrue(result != null)
        assertTrue(result!!.contains("余额不足"))
        assertTrue(result.contains("请检查账户余额或配额是否充足"))
    }

    @Test
    fun `parseBodyError adds retry guidance for busy errors`() {
        val client = OkHttpSseClient()
        val body = """{"error": {"message": "服务繁忙，请稍后重试"}}"""
        val method = OkHttpSseClient::class.java.getDeclaredMethod("parseBodyError", String::class.java)
        method.isAccessible = true
        val result = method.invoke(client, body) as String?

        assertTrue(result != null)
        assertTrue(result!!.contains("服务繁忙"))
        assertTrue(result.contains("请稍后重试"))
    }

    @Test
    fun `parseBodyError adds model guidance for model errors`() {
        val client = OkHttpSseClient()
        val body = """{"error": {"message": "model not found"}}"""
        val method = OkHttpSseClient::class.java.getDeclaredMethod("parseBodyError", String::class.java)
        method.isAccessible = true
        val result = method.invoke(client, body) as String?

        assertTrue(result != null)
        assertTrue(result!!.contains("model not found"))
        assertTrue(result.contains("请检查模型名称是否正确"))
    }

    @Test
    fun `parseBodyError returns null for successful response`() {
        val client = OkHttpSseClient()
        val body = """{"choices":[{"message":{"content":"hello"}}]}"""
        val method = OkHttpSseClient::class.java.getDeclaredMethod("parseBodyError", String::class.java)
        method.isAccessible = true
        val result = method.invoke(client, body) as String?

        assertNull(result)
    }

    @Test
    fun `testConnection detects body error despite 200 status`() {
        val client = OkHttpSseClient(
            syncClient = syncClientFor(
                responseCode = 200,
                responseBody = """{"code": 500, "msg": "404 NOT_FOUND", "success": false}"""
            )
        )

        val result = client.testConnection(providerConfig(), "secret-key")

        assertTrue(result is TestResult.Failure)
        assertTrue((result as TestResult.Failure).message.contains("404 NOT_FOUND"))
    }

    // endregion

    // region retry tests

    @Test
    fun `callSync retries on 429 status`() {
        var attemptCount = 0
        val client = OkHttpSseClient(
            syncClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    attemptCount++
                    if (attemptCount < 3) {
                        responseFor(chain.request(), 429, "too many requests")
                    } else {
                        responseFor(chain.request(), 200, """{"choices":[{"message":{"content":"success"}}]}""")
                    }
                }
                .build()
        )

        val result = client.callSync(simpleRequest())

        assertEquals("success", result.getOrThrow())
        assertEquals(3, attemptCount)
    }

    @Test
    fun `callSync retries on 5xx status`() {
        var attemptCount = 0
        val client = OkHttpSseClient(
            syncClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    attemptCount++
                    if (attemptCount < 2) {
                        responseFor(chain.request(), 503, "service unavailable")
                    } else {
                        responseFor(chain.request(), 200, """{"choices":[{"message":{"content":"success"}}]}""")
                    }
                }
                .build()
        )

        val result = client.callSync(simpleRequest())

        assertEquals("success", result.getOrThrow())
        assertEquals(2, attemptCount)
    }

    @Test
    fun `callSync retries on SocketTimeoutException`() {
        var attemptCount = 0
        val client = OkHttpSseClient(
            syncClient = OkHttpClient.Builder()
                .addInterceptor { _ ->
                    attemptCount++
                    if (attemptCount < 3) {
                        throw SocketTimeoutException("timeout")
                    } else {
                        throw SocketTimeoutException("timeout") // Still fail after max attempts
                    }
                }
                .build()
        )

        val result = client.callSync(simpleRequest())

        assertTrue(result.isFailure)
        assertEquals(3, attemptCount)
    }

    @Test
    fun `callSync does not retry on 401`() {
        var attemptCount = 0
        val client = OkHttpSseClient(
            syncClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    attemptCount++
                    responseFor(chain.request(), 401, "unauthorized")
                }
                .build()
        )

        val result = client.callSync(simpleRequest())

        assertTrue(result.isFailure)
        assertEquals(1, attemptCount)
    }

    // endregion
    private fun providerConfig() = ProviderConfig(
        id = "provider-1",
        name = "OpenAI",
        endpoint = "https://api.openai.com/v1",
        model = "gpt-4o-mini"
    )

    private fun simpleRequest(): Request =
        Request.Builder()
            .url("https://example.com/v1/chat/completions")
            .build()

    private fun syncClientFor(
        responseCode: Int = 200,
        responseBody: String = "",
        thrown: IOException? = null
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                thrown?.let { throw it }
                responseFor(chain.request(), responseCode, responseBody)
            }
            .build()
    }

    private fun responseFor(request: Request, responseCode: Int, responseBody: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(responseCode)
            .message("HTTP $responseCode")
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private class FakeEventSourceFactory : EventSource.Factory {
        lateinit var listener: EventSourceListener

        override fun newEventSource(request: Request, listener: EventSourceListener): EventSource {
            this.listener = listener
            return FakeEventSource(request)
        }
    }

    private class FakeEventSource(private val request: Request) : EventSource {
        override fun request(): Request = request

        override fun cancel() = Unit
    }

    @Test
    fun `buildRequest includes tools array when provided`() {
        val provider = ProviderConfig(
            id = "test",
            name = "Test",
            endpoint = "https://api.example.com/v1",
            model = "gpt-4"
        )
        val tool = ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = "run_command",
                description = "Execute a shell command",
                parameters = buildJsonObject {
                    put("type", "object")
                }
            )
        )
        val client = OkHttpSseClient()
        val request = client.buildRequest(
            config = provider,
            apiKey = "test-key",
            messages = listOf(Message(MessageRole.USER, "hello")),
            temperature = 0.7,
            maxTokens = 100,
            stream = true,
            tools = listOf(tool)
        )
        val body = request.body!!.let {
            val buffer = okio.Buffer()
            it.writeTo(buffer)
            buffer.readUtf8()
        }
        assertTrue(body.contains("\"tools\""), "body should contain tools array")
        assertTrue(body.contains("run_command"), "body should contain tool name")
        assertTrue(body.contains("\"type\":\"function\""), "tool definition must include type field")
    }
}
