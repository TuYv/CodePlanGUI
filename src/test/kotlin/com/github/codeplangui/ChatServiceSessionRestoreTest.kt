package com.github.codeplangui

import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `filterRestorableMessages keeps user and assistant messages`() {
        val messages = listOf(
            Message(MessageRole.USER, "hello", id = "1"),
            Message(MessageRole.ASSISTANT, "hi there", id = "2")
        )
        val result = filterRestorableMessages(messages)
        assertEquals(2, result.size)
    }

    @Test
    fun `filterRestorableMessages filters out system messages`() {
        val messages = listOf(
            Message(MessageRole.SYSTEM, "you are a helper", id = "0"),
            Message(MessageRole.USER, "hello", id = "1"),
            Message(MessageRole.ASSISTANT, "hi", id = "2")
        )
        val result = filterRestorableMessages(messages)
        assertEquals(2, result.size)
        assertTrue(result.none { it.role == MessageRole.SYSTEM })
    }

    @Test
    fun `filterRestorableMessages filters out tool messages`() {
        val messages = listOf(
            Message(MessageRole.USER, "run ls", id = "1"),
            Message(MessageRole.TOOL, "file1.txt", toolCallId = "tc1", id = "2"),
            Message(MessageRole.ASSISTANT, "here are your files", id = "3")
        )
        val result = filterRestorableMessages(messages)
        assertEquals(2, result.size)
        assertTrue(result.none { it.role == MessageRole.TOOL })
    }

    @Test
    fun `filterRestorableMessages filters out blank assistant messages`() {
        val messages = listOf(
            Message(MessageRole.USER, "hello", id = "1"),
            Message(MessageRole.ASSISTANT, "", id = "2"),
            Message(MessageRole.ASSISTANT, "  ", id = "3"),
            Message(MessageRole.ASSISTANT, "real response", id = "4")
        )
        val result = filterRestorableMessages(messages)
        assertEquals(2, result.size)
        assertEquals("hello", result[0].content)
        assertEquals("real response", result[1].content)
    }

    @Test
    fun `filterRestorableMessages returns empty for empty input`() {
        val result = filterRestorableMessages(emptyList())
        assertTrue(result.isEmpty())
    }
}
