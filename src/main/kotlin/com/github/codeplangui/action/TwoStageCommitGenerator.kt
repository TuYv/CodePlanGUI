package com.github.codeplangui.action

import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import com.github.codeplangui.settings.ProviderConfig
import com.github.codeplangui.settings.SettingsState
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class TwoStageCommitGenerator(
    private val client: OkHttpSseClient,
    private val provider: ProviderConfig,
    private val apiKey: String
) {
    companion object {
        // Files <= this threshold skip Stage 1 and go directly to Stage 2 in one API call
        private const val DIRECT_THRESHOLD = 3
    }

    suspend fun generate(
        files: List<CommitPromptFile>,
        settings: SettingsState,
        indicator: ProgressIndicator,
        onToken: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val analyzer = DiffAnalyzer()
        val analysisResult = analyzer.analyze(files, settings)

        indicator.text = "分析文件变更..."

        if (analysisResult.level == DiffAnalyzer.CompressionLevel.STATS) {
            // STATS level: skip Stage 1, generate directly from stats
            return@withContext generateFromStats(analysisResult, settings, indicator, onToken)
        }

        // FULL level: use direct path for small commits, two-stage for larger ones
        val filteredFiles = DiffAnalyzer().filterFiles(analysisResult, settings)
        return@withContext if (filteredFiles.size <= DIRECT_THRESHOLD) {
            generateDirect(filteredFiles, files, settings, indicator, onToken)
        } else {
            generateTwoStage(filteredFiles, files, settings, indicator, onToken)
        }
    }

    private suspend fun generateDirect(
        filteredFiles: List<DiffAnalyzer.FileChange>,
        originalFiles: List<CommitPromptFile>,
        settings: SettingsState,
        indicator: ProgressIndicator,
        onToken: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val fileMap = originalFiles.associateBy { it.path }
        val promptFiles = filteredFiles.mapNotNull { fileMap[it.path] }

        indicator.text = "生成 Commit Message..."

        val diffContent = CommitPromptBuilder.buildDiffPreview(promptFiles)
        val messages = listOf(
            Message(MessageRole.SYSTEM, CommitPromptBuilder.buildStage2Prompt(settings.commitLanguage)),
            Message(MessageRole.USER, CommitPromptBuilder.buildSingleStageUserMessage(diffContent, settings.commitLanguage))
        )

        val request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = messages,
            temperature = 0.3,
            maxTokens = 500,
            stream = true
        )

        return@withContext client.streamCommit(request, onToken)
    }

    private suspend fun generateTwoStage(
        filteredFiles: List<DiffAnalyzer.FileChange>,
        originalFiles: List<CommitPromptFile>,
        settings: SettingsState,
        indicator: ProgressIndicator,
        onToken: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val fileMap = originalFiles.associateBy { it.path }

        val total = filteredFiles.size
        val completed = AtomicInteger(0)
        indicator.text = "Stage 1: 生成文件摘要 (0/$total)..."

        // Stage 1: Generate per-file summaries concurrently
        val summaries = filteredFiles
            .map { file ->
                async {
                    val summary = generateFileSummary(file, fileMap[file.path], settings, indicator)
                    indicator.text = "Stage 1: 生成文件摘要 (${completed.incrementAndGet()}/$total)..."
                    summary
                }
            }
            .awaitAll()
            .filterNotNull()

        if (summaries.isEmpty()) {
            return@withContext Result.failure(Exception("无法生成文件摘要"))
        }

        indicator.text = "Stage 2: 生成 Commit Message..."

        // Stage 2: Generate commit message from summaries
        val stage2Messages = listOf(
            Message(MessageRole.SYSTEM, CommitPromptBuilder.buildStage2Prompt(settings.commitLanguage)),
            Message(MessageRole.USER, CommitPromptBuilder.buildStage1UserMessage(summaries, settings.commitLanguage))
        )

        val stage2Request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = stage2Messages,
            temperature = 0.3,
            maxTokens = 500,
            stream = true
        )

        return@withContext client.streamCommit(stage2Request, onToken)
    }

    private suspend fun generateFromStats(
        result: DiffAnalyzer.AnalysisResult,
        settings: SettingsState,
        indicator: ProgressIndicator,
        onToken: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val filteredFiles = DiffAnalyzer().filterFiles(result, settings)
        val statsSummary = DiffAnalyzer().buildStatsSummary(filteredFiles)

        val messages = listOf(
            Message(MessageRole.SYSTEM, CommitPromptBuilder.buildStage2Prompt(settings.commitLanguage)),
            Message(
                MessageRole.USER,
                CommitPromptBuilder.buildStatsUserMessage(
                    filteredFiles,
                    statsSummary,
                    settings.commitLanguage
                )
            )
        )

        indicator.text = "生成 Commit Message..."

        val request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = messages,
            temperature = 0.3,
            maxTokens = 500,
            stream = true
        )

        return@withContext client.streamCommit(request, onToken)
    }

    private suspend fun generateFileSummary(
        file: DiffAnalyzer.FileChange,
        originalFile: CommitPromptFile?,
        settings: SettingsState,
        indicator: ProgressIndicator
    ): String? = withContext(Dispatchers.IO) {
        if (indicator.isCanceled) return@withContext null

        val userMessage = if (originalFile != null) {
            CommitPromptBuilder.buildSingleFilePrompt(originalFile)
        } else {
            CommitPromptBuilder.buildSingleFilePrompt(file)
        }

        val messages = listOf(
            Message(MessageRole.SYSTEM, CommitPromptBuilder.buildStage1Prompt()),
            Message(MessageRole.USER, userMessage)
        )

        val request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = messages,
            temperature = 0.3,
            maxTokens = 100,
            stream = false
        )

        val result = client.callCommitSync(request)
        result.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }
}
