package com.github.codeplangui

import com.github.codeplangui.action.CommitPromptFile
import com.github.codeplangui.action.TwoStageCommitGenerator
import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.settings.ProviderConfig
import com.github.codeplangui.settings.SettingsState
import com.intellij.openapi.progress.ProgressIndicator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TwoStageCommitGeneratorTest {

    private val provider = ProviderConfig(
        id = "test", name = "Test", endpoint = "http://localhost/v1",
        model = "test-model"
    )
    private val apiKey = "test-key"
    private val indicator = mockk<ProgressIndicator>(relaxed = true)

    private fun defaultSettings() = SettingsState().apply {
        commitLanguage = "en"
        commitMaxFiles = 20
        commitDiffLineLimit = 500
    }

    private fun makeFile(path: String, content: String = "line1\nline2"): CommitPromptFile =
        CommitPromptFile(
            path = path,
            changeType = "modified",
            beforeContent = "",
            afterContent = content
        )

    @Test
    fun `direct path for small commit with 1 file`() = runBlocking {
        val client = mockk<OkHttpSseClient>()
        every { client.buildRequest(any(), any(), any(), any(), any(), any(), any()) } returns
            Request.Builder().url("http://localhost/v1/chat/completions").build()
        coEvery { client.streamCommit(any(), any()) } returns Result.success("feat: add feature")

        val generator = TwoStageCommitGenerator(client, provider, apiKey)
        val result = generator.generate(
            files = listOf(makeFile("src/Main.kt")),
            settings = defaultSettings(),
            indicator = indicator
        )
        assertTrue(result.isSuccess)
        assertEquals("feat: add feature", result.getOrNull())
    }

    @Test
    fun `returns failure when API returns error`() = runBlocking {
        val client = mockk<OkHttpSseClient>()
        every { client.buildRequest(any(), any(), any(), any(), any(), any(), any()) } returns
            Request.Builder().url("http://localhost/v1/chat/completions").build()
        coEvery { client.streamCommit(any(), any()) } returns Result.failure(Exception("API error"))

        val generator = TwoStageCommitGenerator(client, provider, apiKey)
        val result = generator.generate(
            files = listOf(makeFile("src/Main.kt")),
            settings = defaultSettings(),
            indicator = indicator
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("API error") == true)
    }

    @Test
    fun `two stage path for many files`() = runBlocking {
        val client = mockk<OkHttpSseClient>()
        every { client.buildRequest(any(), any(), any(), any(), any(), any(), any()) } returns
            Request.Builder().url("http://localhost/v1/chat/completions").build()
        // Stage 1: per-file summaries (callCommitSync used for non-streaming)
        every { client.callCommitSync(any()) } returns Result.success("Updated file logic")
        // Stage 2: final commit message (streamCommit)
        coEvery { client.streamCommit(any(), any()) } returns Result.success("feat: update multiple files")

        val generator = TwoStageCommitGenerator(client, provider, apiKey)
        val files = (1..5).map { makeFile("src/File$it.kt", "added line $it\n".repeat(10)) }
        val result = generator.generate(
            files = files,
            settings = defaultSettings(),
            indicator = indicator
        )
        assertTrue(result.isSuccess)
        assertEquals("feat: update multiple files", result.getOrNull())
    }

    @Test
    fun `two stage returns failure when all summaries fail`() = runBlocking {
        val client = mockk<OkHttpSseClient>()
        every { client.buildRequest(any(), any(), any(), any(), any(), any(), any()) } returns
            Request.Builder().url("http://localhost/v1/chat/completions").build()
        // Stage 1: all summaries fail
        every { client.callCommitSync(any()) } returns Result.failure(Exception("timeout"))

        val generator = TwoStageCommitGenerator(client, provider, apiKey)
        val files = (1..5).map { makeFile("src/File$it.kt", "line\n".repeat(10)) }
        val result = generator.generate(
            files = files,
            settings = defaultSettings(),
            indicator = indicator
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("无法生成文件摘要") == true)
    }
}
