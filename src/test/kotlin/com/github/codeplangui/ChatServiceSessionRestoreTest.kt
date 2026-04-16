package com.github.codeplangui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatServiceSessionRestoreTest {

    @Test
    fun `buildPromptContextSnapshot uses selected text when available`() {
        val snapshot = buildPromptContextSnapshot(
            fileName = "Main.kt",
            extension = "kt",
            selectedText = "val x = 1",
            documentText = "package com.example\nval x = 1\nval y = 2",
            maxLines = 300
        )
        assertEquals("val x = 1", snapshot.content)
        assert(snapshot.contextLabel.contains("选中"))
    }

    @Test
    fun `buildPromptContextSnapshot uses document text when no selection`() {
        val snapshot = buildPromptContextSnapshot(
            fileName = "Main.kt",
            extension = "kt",
            selectedText = null,
            documentText = "package com.example\nval x = 1",
            maxLines = 300
        )
        assertEquals("package com.example\nval x = 1", snapshot.content)
        assertEquals("Main.kt · 当前文件", snapshot.contextLabel)
    }

    @Test
    fun `buildPromptContextSnapshot truncates to maxLines`() {
        val longDocument = (1..500).joinToString("\n") { "line $it" }
        val snapshot = buildPromptContextSnapshot(
            fileName = "Long.kt",
            extension = "kt",
            selectedText = null,
            documentText = longDocument,
            maxLines = 10
        )
        assertEquals(10, snapshot.content.lines().size)
    }

    @Test
    fun `buildPromptContextSnapshot uses blank selection as no selection`() {
        val snapshot = buildPromptContextSnapshot(
            fileName = "Main.kt",
            extension = "kt",
            selectedText = "   ",
            documentText = "val code = true",
            maxLines = 300
        )
        assertEquals("val code = true", snapshot.content)
        assertEquals("Main.kt · 当前文件", snapshot.contextLabel)
    }

    @Test
    fun `buildPromptContextSnapshot defaults extension to txt`() {
        val snapshot = buildPromptContextSnapshot(
            fileName = "Makefile",
            extension = null,
            selectedText = null,
            documentText = "all: build",
            maxLines = 300
        )
        assertEquals("txt", snapshot.extension)
    }

    @Test
    fun `openSettingsOnEdt enqueues the dialog opener`() {
        var executed = false
        openSettingsOnEdt(
            openDialog = { executed = true },
            enqueue = { action -> action() }
        )
        assertEquals(true, executed)
    }
}
