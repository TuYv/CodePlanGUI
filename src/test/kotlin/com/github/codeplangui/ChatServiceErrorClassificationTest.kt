package com.github.codeplangui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatServiceErrorClassificationTest {

    @Test
    fun `deriveConnectionState returns streaming when active`() {
        assertEquals("streaming", deriveConnectionState(
            hasProvider = true, hasApiKey = true, isStreaming = true
        ))
    }

    @Test
    fun `deriveConnectionState returns unconfigured when no provider`() {
        assertEquals("unconfigured", deriveConnectionState(
            hasProvider = false, hasApiKey = false, isStreaming = false
        ))
    }

    @Test
    fun `deriveConnectionState returns error when no api key`() {
        assertEquals("error", deriveConnectionState(
            hasProvider = true, hasApiKey = false, isStreaming = false
        ))
    }

    @Test
    fun `deriveConnectionState returns ready when fully configured`() {
        assertEquals("ready", deriveConnectionState(
            hasProvider = true, hasApiKey = true, isStreaming = false
        ))
    }

    @Test
    fun `formatSystemContent includes memory when provided`() {
        val result = formatSystemContent(
            base = "You are a code assistant.",
            snapshot = null,
            memoryText = "User prefers Kotlin"
        )
        assert(result.contains("[User Memory]"))
        assert(result.contains("User prefers Kotlin"))
    }

    @Test
    fun `formatSystemContent includes context snapshot`() {
        val snapshot = PromptContextSnapshot(
            fileName = "Test.kt",
            extension = "kt",
            content = "fun main() {}",
            contextLabel = "Test.kt · 当前文件"
        )
        val result = formatSystemContent(
            base = "You are a code assistant.",
            snapshot = snapshot
        )
        assert(result.contains("当前文件：Test.kt"))
        assert(result.contains("fun main() {}"))
    }

    @Test
    fun `formatSystemContent without memory or snapshot returns base only`() {
        val result = formatSystemContent(
            base = "You are a code assistant.",
            snapshot = null,
            memoryText = ""
        )
        assertEquals("You are a code assistant.", result)
    }

    @Test
    fun `resolveUiContextLabel prefers override`() {
        val snapshot = PromptContextSnapshot("A.kt", "kt", "code", "A.kt · 当前文件")
        assertEquals("override", resolveUiContextLabel("override", snapshot))
    }

    @Test
    fun `resolveUiContextLabel falls back to snapshot label`() {
        val snapshot = PromptContextSnapshot("A.kt", "kt", "code", "A.kt · 当前文件")
        assertEquals("A.kt · 当前文件", resolveUiContextLabel(null, snapshot))
    }

    @Test
    fun `resolveUiContextLabel returns empty when both null`() {
        assertEquals("", resolveUiContextLabel(null, null))
    }
}
