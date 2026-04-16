package com.github.codeplangui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatServiceSelectionTest {

    @Test
    fun `buildSelectionContextLabel formats fileName with line count`() {
        val label = buildSelectionContextLabel("Main.kt", 5)
        assertEquals("Main.kt · 选中 5 行", label)
    }

    @Test
    fun `buildSelectionContextLabel handles null fileName`() {
        val label = buildSelectionContextLabel(null, 3)
        assertEquals("选中 3 行", label)
    }

    @Test
    fun `buildSelectionContextLabel handles blank fileName`() {
        val label = buildSelectionContextLabel("", 1)
        assertEquals("选中 1 行", label)
    }

    @Test
    fun `buildSelectionContextLabel coerces zero lines to 1`() {
        val label = buildSelectionContextLabel("Test.kt", 0)
        assertEquals("Test.kt · 选中 1 行", label)
    }

    @Test
    fun `shouldQueuePrompt returns false when both bridge and frontend ready`() {
        assertEquals(false, ChatService.shouldQueuePrompt(bridgeReady = true, frontendReady = true))
    }

    @Test
    fun `shouldQueuePrompt returns true when bridge not ready`() {
        assertEquals(true, ChatService.shouldQueuePrompt(bridgeReady = false, frontendReady = true))
    }

    @Test
    fun `shouldQueuePrompt returns true when frontend not ready`() {
        assertEquals(true, ChatService.shouldQueuePrompt(bridgeReady = true, frontendReady = false))
    }

    @Test
    fun `shouldQueuePrompt returns true when both not ready`() {
        assertEquals(true, ChatService.shouldQueuePrompt(bridgeReady = false, frontendReady = false))
    }
}
