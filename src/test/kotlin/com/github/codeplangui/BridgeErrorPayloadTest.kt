package com.github.codeplangui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BridgeErrorPayloadTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `config error serializes with action`() {
        val payload = BridgeErrorPayload(
            type = "config",
            message = "API Key 未设置",
            action = "openSettings"
        )
        val serialized = json.encodeToString(payload)
        val deserialized = json.decodeFromString<BridgeErrorPayload>(serialized)
        assertEquals("config", deserialized.type)
        assertEquals("API Key 未设置", deserialized.message)
        assertEquals("openSettings", deserialized.action)
    }

    @Test
    fun `runtime error serializes without action`() {
        val payload = BridgeErrorPayload(
            type = "runtime",
            message = "AI 返回了无法解析的工具调用"
        )
        val serialized = json.encodeToString(payload)
        val deserialized = json.decodeFromString<BridgeErrorPayload>(serialized)
        assertEquals("runtime", deserialized.type)
        assertNull(deserialized.action)
    }

    @Test
    fun `network error serializes with retry action`() {
        val payload = BridgeErrorPayload(
            type = "network",
            message = "连接超时",
            action = "retry"
        )
        val serialized = json.encodeToString(payload)
        val deserialized = json.decodeFromString<BridgeErrorPayload>(serialized)
        assertEquals("network", deserialized.type)
        assertEquals("retry", deserialized.action)
    }
}
