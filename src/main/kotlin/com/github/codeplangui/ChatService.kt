package com.github.codeplangui

import com.github.codeplangui.api.FunctionDefinition
import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.api.ToolCallAccumulator
import com.github.codeplangui.api.ToolCallDelta
import com.github.codeplangui.api.ToolDefinition
import com.github.codeplangui.api.TruncationDecision
import com.github.codeplangui.api.TruncationHandler
import com.github.codeplangui.execution.CommandExecutionService
import com.github.codeplangui.execution.ShellPlatform
import com.github.codeplangui.model.ChatSession
import com.github.codeplangui.model.Message
import com.github.codeplangui.model.MessageRole
import com.github.codeplangui.model.ToolCallRecord
import com.github.codeplangui.settings.ApiKeyStore
import com.github.codeplangui.settings.PluginSettings
import com.github.codeplangui.settings.PluginSettingsConfigurable
import com.github.codeplangui.storage.SessionStore
import com.github.codeplangui.tools.Progress
import com.github.codeplangui.tools.ToolExecutionContext
import com.github.codeplangui.tools.ToolPermissionContext
import com.github.codeplangui.tools.ToolResultBlock
import com.github.codeplangui.tools.ToolUpdate
import com.github.codeplangui.tools.ToolUseBlock
import com.github.codeplangui.tools.runToolUseBatch
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.options.ShowSettingsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import okhttp3.sse.EventSource
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class ChatService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ChatService::class.java)

    private val client = OkHttpSseClient()
    private var session: ChatSession = ChatSession()
    private var activeStream: EventSource? = null
    private var activeMessageId: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingPrompt: PendingPrompt? = null

    var bridgeHandler: BridgeHandler? = null
        private set

    private val sessionStore = SessionStore(SessionStore.projectIdFromPath(project.basePath))

    private var contextFileCallback: ((String) -> Unit)? = null
    private var onFrontendReadyCallback: (() -> Unit)? = null
    private var isFrontendReady: Boolean = false

    // Tool call state machine
    private val toolCallAccumulator = ToolCallAccumulator()

    // Truncation / auto-continuation state
    private val truncationHandler = TruncationHandler()

    // Approval gate: suspended coroutines wait on these futures
    private val pendingApprovals = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    // Maps requestId → command string so onApprovalResponse can update the whitelist
    private val pendingApprovalCommands = ConcurrentHashMap<String, String>()
    // Tracks msgId while tool batch is executing so cancelStream() works during tools
    @Volatile private var runningToolMsgId: String? = null

    // Tracks which msgIds have had notifyStart sent to the frontend
    // When tools are enabled, notifyStart is deferred until the final response round
    // so ExecutionCards appear before the assistant bubble
    private val bridgeNotifiedStart = mutableSetOf<String>()

    fun attachBridge(handler: BridgeHandler) {
        bridgeHandler = handler
        isFrontendReady = false
    }

    /**
     * Bridge lifecycle contract — called when frontend sends "frontendReady":
     *   1. publishStatus()              → push provider/model/connectionState
     *   2. restoreSessionIfNeeded()     → replay persisted messages
     *   3. onFrontendReadyCallback()    → ChatPanel pushes theme + contextFile
     *   4. flush pendingPrompt          → dequeues Ask AI prompts queued before bridge ready
     */
    fun onFrontendReady() {
        isFrontendReady = true
        publishStatus()
        restoreSessionIfNeeded()
        onFrontendReadyCallback?.invoke()
        pendingPrompt?.let { prompt ->
            pendingPrompt = null
            sendMessage(prompt.text, prompt.includeContext, prompt.contextLabel)
        }
    }

    fun setContextFileCallback(callback: (String) -> Unit) {
        contextFileCallback = callback
    }

    fun setOnFrontendReadyCallback(callback: () -> Unit) {
        onFrontendReadyCallback = callback
    }

    fun sendMessage(text: String, includeContext: Boolean, contextLabelOverride: String? = null) {
        activeStream?.cancel()
        activeStream = null
        activeMessageId = null

        val settings = PluginSettings.getInstance()
        val provider = settings.getActiveProvider()
        if (provider == null) {
            publishStatus()
            bridgeHandler?.notifyStructuredError(BridgeErrorPayload(
                type = "config",
                message = "请先在 Settings > Tools > CodePlanGUI 中配置 API Provider",
                action = "openSettings"
            ))
            return
        }

        val apiKey = ApiKeyStore.load(provider.id) ?: ""
        if (apiKey.isBlank()) {
            publishStatus()
            bridgeHandler?.notifyStructuredError(BridgeErrorPayload(
                type = "config",
                message = "API Key 未设置或未保存，请在 Settings 中重新配置并点 Apply/OK",
                action = "openSettings"
            ))
            return
        }

        val settingsState = settings.getState()
        val commandExecutionEnabled = settingsState.commandExecutionEnabled

        val contextSnapshot = if (includeContext && settingsState.contextInjectionEnabled) {
            capturePromptContextSnapshot()
        } else {
            null
        }
        contextFileCallback?.invoke(resolveUiContextLabel(contextLabelOverride, contextSnapshot))
        val systemContent = formatSystemContent(
            base = buildBaseSystemPrompt(commandExecutionEnabled),
            snapshot = contextSnapshot,
            memoryText = settingsState.memoryText
        )
        session.setSystemMessage(systemContent)
        val userMsg = Message(
            role = MessageRole.USER,
            content = text,
            id = UUID.randomUUID().toString(),
            seq = session.nextSeq()
        )
        session.add(userMsg)
        persistSession()

        // Reset state machine before each new user-initiated request
        resetToolCallState()
        truncationHandler.reset()

        val msgId = UUID.randomUUID().toString()
        activeMessageId = msgId
        publishStatus()

        val request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = session.getApiMessages(),
            temperature = settingsState.chatTemperature,
            maxTokens = settingsState.chatMaxTokens,
            stream = true,
            tools = if (commandExecutionEnabled) buildToolDefinitions() else null
        )

        // notifyStart is now sent unconditionally in startStreamingRound() (Phase 2).
        startStreamingRound(msgId, request, toolsEnabled = commandExecutionEnabled)
    }

    fun cancelStream() {
        val msgId = activeMessageId ?: runningToolMsgId
        val wasStreaming = msgId != null
        activeStream?.cancel()
        activeStream = null
        activeMessageId = null
        runningToolMsgId = null
        if (wasStreaming && msgId != null) {
            publishStatus()
            bridgeHandler?.notifyEnd(msgId)
        }
    }

    fun newChat() {
        activeStream?.cancel()
        activeStream = null
        activeMessageId = null
        truncationHandler.reset()
        resetToolCallState()
        session = ChatSession()
        pendingApprovals.values.forEach { it.complete(false) }
        pendingApprovals.clear()
        pendingApprovalCommands.clear()
        runningToolMsgId = null
        sessionStore.clearSession()
        contextFileCallback?.invoke("")
        publishStatus()
    }

    fun askAboutSelection(selection: String, contextLabel: String) {
        val prompt = buildSelectionPrompt(selection)
        if (!shouldQueuePrompt(bridgeHandler?.isReady == true, isFrontendReady)) {
            sendMessage(prompt, false, contextLabel)
        } else {
            pendingPrompt = PendingPrompt(prompt, false, contextLabel)
        }
    }

    fun openSettings() {
        openSettingsOnEdt(
            openDialog = {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginSettingsConfigurable::class.java)
            },
            enqueue = { action ->
                ApplicationManager.getApplication().invokeLater {
                    action()
                }
            }
        )
    }

    fun onApprovalResponse(requestId: String, decision: String, addToWhitelist: Boolean = false) {
        logger.info(
            "[CodePlanGUI Approval] received frontend decision " +
                "requestId=$requestId decision=$decision addToWhitelist=$addToWhitelist hasPending=${pendingApprovals.containsKey(requestId)}"
        )
        if (addToWhitelist && decision == "allow") {
            val command = pendingApprovalCommands[requestId]
            if (command != null) {
                val baseCommand = CommandExecutionService.extractBaseCommand(command)
                val whitelist = PluginSettings.getInstance().getState().commandWhitelist
                if (baseCommand !in whitelist) {
                    whitelist.add(baseCommand)
                    logger.info("[CodePlanGUI Approval] added '$baseCommand' to whitelist")
                }
            }
        }
        pendingApprovalCommands.remove(requestId)
        pendingApprovals[requestId]?.complete(decision == "allow")
    }

    fun refreshBridgeStatus() {
        publishStatus()
    }

    private fun buildToolDefinitions(): List<ToolDefinition> {
        val pool = com.github.codeplangui.tools.ToolRegistry.assembleToolPool()
        return pool.map { tool ->
            ToolDefinition(
                type = "function",
                function = FunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.inputSchema,
                )
            )
        }
    }

    private fun buildPermissionContext(): ToolPermissionContext {
        val state = PluginSettings.getInstance().getState()
        return ToolPermissionContext(
            mode = ToolPermissionContext.Mode.DEFAULT,
            alwaysAllow = state.commandWhitelist.toSet(),
            alwaysDeny = emptySet(),
            alwaysAsk = emptySet(),
            additionalWorkingDirectories = emptySet(),
        )
    }

    private fun handleToolCallChunk(delta: ToolCallDelta) {
        toolCallAccumulator.append(delta)
    }

    private suspend fun handleToolCallComplete(msgId: String, responseBuffer: StringBuilder) {
        runningToolMsgId = msgId

        val accumulatedToolCalls = toolCallAccumulator.snapshot()
        if (accumulatedToolCalls.isEmpty()) {
            runningToolMsgId = null
            abortStream(msgId, "AI sent a tool_calls finish_reason but no tool call deltas were captured")
            return
        }

        val toolUses = accumulatedToolCalls.mapNotNull { accumulated ->
            val toolCallId = accumulated.id ?: run {
                runningToolMsgId = null
                abortStream(msgId, "AI sent a tool_calls finish_reason but tool call index ${accumulated.index} had no id")
                return
            }
            val inputJson = try {
                kotlinx.serialization.json.Json.parseToJsonElement(accumulated.argumentsJson)
            } catch (_: Exception) {
                buildJsonObject {}
            }
            ToolUseBlock(
                toolUseId = toolCallId,
                name = accumulated.functionName ?: ShellPlatform.current().toolName(),
                input = inputJson,
            )
        }

        val pool = com.github.codeplangui.tools.ToolRegistry.assembleToolPool()
        val settingsState = PluginSettings.getInstance().getState()
        val ctx = ToolExecutionContext(
            project = project,
            toolUseId = msgId,
            abortJob = scope.coroutineContext[Job]!!,
            permissionContext = buildPermissionContext(),
            commandTimeoutSeconds = settingsState.commandTimeoutSeconds,
            onPermissionAsked = { event ->
                val bridge = bridgeHandler ?: return@ToolExecutionContext false
                val requestId = event.toolUseId
                val previewSummary = event.preview?.summary ?: event.reason
                // Strip "Run: " prefix to store raw command for whitelist persistence
                pendingApprovalCommands[requestId] = previewSummary.removePrefix("Run: ")
                bridge.notifyApprovalRequest(requestId, previewSummary, event.reason, event.toolName)
                val future = CompletableFuture<Boolean>()
                pendingApprovals[requestId] = future
                try {
                    withContext(Dispatchers.IO) { future.get(60, TimeUnit.SECONDS) }
                } catch (_: Exception) {
                    false
                } finally {
                    pendingApprovals.remove(requestId)
                }
            }
        )

        val startTimes = mutableMapOf<String, Long>()
        val results = mutableMapOf<String, ToolResultBlock>()

        runToolUseBatch(toolUses, pool, ctx).collect { update ->
            val bridge = bridgeHandler
            when (update) {
                is ToolUpdate.Started -> {
                    startTimes[update.toolUseId] = System.currentTimeMillis()
                    bridge?.notifyToolStepStart(msgId, update.toolUseId, update.toolName, update.toolName)
                }
                is ToolUpdate.ProgressEmitted -> {
                    val (line, type) = when (val p = update.progress) {
                        is Progress.Stdout -> p.line to "stdout"
                        is Progress.Stderr -> p.line to "stderr"
                        is Progress.Status -> p.message to "info"
                    }
                    bridge?.notifyLog(update.toolUseId, line, type)
                }
                is ToolUpdate.PermissionAsked -> Unit
                is ToolUpdate.Completed -> {
                    val durationMs = System.currentTimeMillis() - (startTimes[update.toolUseId] ?: 0)
                    results[update.toolUseId] = update.block
                    bridge?.notifyToolStepEnd(msgId, update.toolUseId, !update.block.isError, update.block.content, durationMs)
                }
                is ToolUpdate.Failed -> {
                    val durationMs = System.currentTimeMillis() - (startTimes[update.toolUseId] ?: 0)
                    val errorBlock = ToolResultBlock(update.toolUseId, "[${update.stage}] ${update.message}", isError = true)
                    results[update.toolUseId] = errorBlock
                    bridge?.notifyToolStepEnd(msgId, update.toolUseId, false, update.message, durationMs)
                }
            }
        }

        val toolCallRecords = toolUses.map { tu ->
            ToolCallRecord(
                id = tu.toolUseId,
                functionName = tu.name,
                arguments = tu.input.toString()
            )
        }
        session.add(Message(
            role = MessageRole.ASSISTANT,
            content = responseBuffer.toString(),
            id = UUID.randomUUID().toString(),
            seq = session.nextSeq(),
            toolCalls = toolCallRecords
        ))
        toolUses.forEach { tu ->
            val block = results[tu.toolUseId] ?: ToolResultBlock(tu.toolUseId, "(no result)", isError = true)
            session.add(Message(
                role = MessageRole.TOOL,
                content = block.content,
                toolCallId = tu.toolUseId,
                id = UUID.randomUUID().toString(),
                seq = session.nextSeq()
            ))
        }
        persistSession()

        // Check if cancelled while tools were running
        if (runningToolMsgId != msgId) return
        runningToolMsgId = null

        resetToolCallState()
        responseBuffer.clear()
        activeMessageId = msgId
        sendMessageInternal(msgId)
    }

    private fun sendMessageInternal(msgId: String) {
        val pluginSettings = PluginSettings.getInstance()
        val provider = pluginSettings.getActiveProvider() ?: return
        val apiKey = ApiKeyStore.load(provider.id) ?: return
        val settingsState = pluginSettings.getState()
        val commandExecutionEnabled = settingsState.commandExecutionEnabled
        logger.info("[CodePlanGUI Approval] starting follow-up model round msgId=$msgId")

        val request = client.buildRequest(
            config = provider,
            apiKey = apiKey,
            messages = session.getApiMessages(),
            temperature = settingsState.chatTemperature,
            maxTokens = settingsState.chatMaxTokens,
            stream = true,
            tools = if (commandExecutionEnabled) buildToolDefinitions() else null
        )

        startStreamingRound(msgId, request, toolsEnabled = commandExecutionEnabled)
    }

    private fun capturePromptContextSnapshot(): PromptContextSnapshot? {
        return try {
            ReadAction.compute<PromptContextSnapshot?, RuntimeException> {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@compute null
                val file = editor.virtualFile ?: return@compute null
                buildPromptContextSnapshot(
                    fileName = file.name,
                    extension = file.extension,
                    selectedText = editor.selectionModel.selectedText,
                    documentText = editor.document.text,
                    maxLines = PluginSettings.getInstance().getState().contextMaxLines
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildSelectionPrompt(selection: String): String = """
请分析下面这段选中的代码，并说明它的作用、关键逻辑和潜在风险：

```
$selection
```
    """.trimIndent()

    private fun publishStatus() {
        val provider = PluginSettings.getInstance().getActiveProvider()
        val hasApiKey = provider?.let { !ApiKeyStore.load(it.id).isNullOrBlank() } == true
        val status = BridgeStatusPayload(
            providerName = provider?.name.orEmpty(),
            model = provider?.model.orEmpty(),
            connectionState = deriveConnectionState(
                hasProvider = provider != null,
                hasApiKey = hasApiKey,
                isStreaming = activeMessageId != null
            )
        )
        bridgeHandler?.notifyStatus(status)
    }

    /** Terminates an in-progress stream with an error and resets all state, preventing a permanent stuck spinner. */
    private fun abortStream(msgId: String, errorMessage: String) {
        if (activeMessageId != msgId) return
        logger.warn("[CodePlanGUI Approval] aborting stream msgId=$msgId error=${errorMessage.summarizeForLog(240)}")
        activeStream?.cancel()
        activeStream = null
        activeMessageId = null
        resetToolCallState()
        publishStatus()
        bridgeHandler?.notifyStructuredError(BridgeErrorPayload(
            type = "runtime",
            message = errorMessage
        ))
    }

    private fun startStreamingRound(msgId: String, request: okhttp3.Request, toolsEnabled: Boolean) {
        // Phase 2: notifyStart sent unconditionally — the frontend's groupReducer
        // handles continuation rounds by reusing the existing assistant group.
        bridgeHandler?.notifyStart(msgId)

        val responseBuffer = StringBuilder()
        scope.launch {
            val stream = client.streamChat(
                request = request,
                onToken = { token ->
                    if (activeMessageId == msgId) {
                        responseBuffer.append(token)
                        bridgeHandler?.notifyToken(token)
                    }
                },
                onEnd = {
                    if (activeMessageId == msgId && !truncationHandler.isPendingContinuation) {
                        logger.info("[CodePlanGUI Approval] model round completed msgId=$msgId")
                        session.add(Message(
                            role = MessageRole.ASSISTANT,
                            content = responseBuffer.toString(),
                            id = UUID.randomUUID().toString(),
                            seq = session.nextSeq()
                        ))
                        persistSession()
                        activeStream = null
                        activeMessageId = null
                        publishStatus()
                        bridgeHandler?.notifyEnd(msgId)
                    }
                },
                onError = { message ->
                    if (activeMessageId == msgId) {
                        logger.warn("[CodePlanGUI Approval] model round failed msgId=$msgId error=$message")
                        activeStream = null
                        activeMessageId = null
                        publishStatus()
                        bridgeHandler?.notifyStructuredError(classifyStreamError(message))
                    }
                },
                onToolCallChunk = { delta ->
                    if (toolsEnabled && activeMessageId == msgId) {
                        handleToolCallChunk(delta)
                    }
                },
                onFinishReason = { reason ->
                    if (toolsEnabled && reason == "tool_calls" && activeMessageId == msgId) {
                        // Create the assistant bubble for tool steps only.
                        // Do NOT flush round-1 text — the formal response streams after tools complete.
                        if (msgId !in bridgeNotifiedStart) {
                            bridgeHandler?.notifyStart(msgId)
                            bridgeNotifiedStart.add(msgId)
                        }
                        // Clear activeMessageId to prevent onEnd from finalizing this message
                        // — the follow-up round will continue appending to it.
                        activeMessageId = null
                        val capturedBuffer = responseBuffer
                        scope.launch { handleToolCallComplete(msgId, capturedBuffer) }
                    }
                    if (reason == "length" && activeMessageId == msgId) {
                        val capturedBuffer = StringBuilder(responseBuffer)
                        val hasIncompleteToolCalls = !toolCallAccumulator.isEmpty()
                        when (val decision = truncationHandler.handleFinishReasonLength(responseBuffer, hasIncompleteToolCalls)) {
                            is TruncationDecision.AutoContinue -> {
                                logger.info("[CodePlanGUI Truncation] auto-continuation ${decision.count}/${decision.max} msgId=$msgId")
                                scope.launch { handleLengthTruncation(msgId, capturedBuffer, decision.continuationPrompt) }
                            }
                            is TruncationDecision.Exhausted -> {
                                // Intentionally does NOT suppress onEnd. The marker was appended to
                                // responseBuffer by handleFinishReasonLength. Normal onEnd processing
                                // will flush the buffer (including marker) to the frontend and session.
                                logger.info("[CodePlanGUI Truncation] max continuations reached, appending marker msgId=$msgId")
                            }
                        }
                    }
                }
            )
            if (activeMessageId == msgId) {
                activeStream = stream
            } else {
                stream.cancel()
            }
        }
    }

    private fun resetToolCallState() {
        toolCallAccumulator.clear()
    }

    private suspend fun handleLengthTruncation(msgId: String, partialBuffer: StringBuilder, continuationPrompt: String) {
        if (activeMessageId != msgId) return

        // Clear the pending flag — onEnd in the continuation round should fire normally.
        truncationHandler.clearPendingContinuation()

        // TODO: Each continuation round creates a separate assistant message + hidden user prompt
        // in the session. This inflates context for subsequent API calls. Consider merging all
        // partial assistant content into a single message after the final round completes.
        session.add(Message(
            role = MessageRole.ASSISTANT,
            content = partialBuffer.toString(),
            id = UUID.randomUUID().toString(),
            seq = session.nextSeq()
        ))

        // Discard incomplete tool calls if any
        if (!toolCallAccumulator.isEmpty()) {
            logger.info("[CodePlanGUI Truncation] discarding incomplete tool calls during continuation msgId=$msgId")
            resetToolCallState()
        }

        session.add(Message(
            role = MessageRole.USER,
            content = continuationPrompt,
            id = UUID.randomUUID().toString(),
            seq = session.nextSeq(),
            hidden = true
        ))
        persistSession()

        bridgeHandler?.notifyContinuation(truncationHandler.count, TruncationHandler.MAX_CONTINUATIONS)
        sendMessageInternal(msgId)
    }

    private fun persistSession() {
        sessionStore.saveSession(
            session.threadId,
            session.getMessages().filter { it.role != MessageRole.SYSTEM }
        )
    }

    private fun String.summarizeForLog(maxLength: Int = 160): String {
        val singleLine = replace('\n', ' ').replace('\r', ' ').trim()
        return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength) + "..."
    }

    override fun dispose() {
        activeStream?.cancel()
        pendingApprovals.values.forEach { it.complete(false) }
        pendingApprovals.clear()
        pendingApprovalCommands.clear()
        runningToolMsgId = null
        bridgeNotifiedStart.clear()
        scope.cancel()
    }

    private val bridgeJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun restoreSessionIfNeeded() {
        if (session.getMessages().any { it.role != MessageRole.SYSTEM }) {
            return
        }
        val ttlDays = PluginSettings.getInstance().state.sessionTtlDays
        sessionStore.evictExpiredSessions(ttlDays)
        val data = sessionStore.loadSession() ?: return
        session = ChatSession(data.threadId)
        data.messages.forEach { session.add(it) }
        val restoredMessages = filterRestorableMessages(data.messages)
            .map {
                RestoredMessagePayload(
                    id = it.id,
                    role = it.role.name.lowercase(),
                    content = it.content
                )
            }
        bridgeHandler?.notifyRestoreMessages(
            bridgeJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(RestoredMessagePayload.serializer()),
                restoredMessages
            )
        )
    }

    private fun classifyStreamError(message: String): BridgeErrorPayload {
        val lowerMsg = message.lowercase()
        return when {
            lowerMsg.contains("401") || lowerMsg.contains("403") ||
            lowerMsg.contains("api key") || lowerMsg.contains("unauthorized") ->
                BridgeErrorPayload(type = "config", message = message, action = "openSettings")

            lowerMsg.contains("timeout") || lowerMsg.contains("超时") ||
            lowerMsg.contains("无法连接") || lowerMsg.contains("connectexception") ||
            lowerMsg.contains("http 5") || lowerMsg.contains("http 429") ->
                BridgeErrorPayload(type = "network", message = message, action = "retry")

            else ->
                BridgeErrorPayload(type = "runtime", message = message)
        }
    }

    companion object {
        fun getInstance(project: Project): ChatService = project.getService(ChatService::class.java)

        internal fun shouldQueuePrompt(bridgeReady: Boolean, frontendReady: Boolean): Boolean =
            !(bridgeReady && frontendReady)
    }

    private data class PendingPrompt(
        val text: String,
        val includeContext: Boolean,
        val contextLabel: String? = null
    )

    @kotlinx.serialization.Serializable
    private data class RestoredMessagePayload(
        val id: String,
        val role: String,
        val content: String
    )
}

internal data class PromptContextSnapshot(
    val fileName: String,
    val extension: String,
    val content: String,
    val contextLabel: String
)

internal fun buildPromptContextSnapshot(
    fileName: String,
    extension: String?,
    selectedText: String?,
    documentText: String,
    maxLines: Int
): PromptContextSnapshot {
    val preferredContent = selectedText?.takeIf { it.isNotBlank() } ?: documentText
    val content = preferredContent
        .lines()
        .take(maxLines)
        .joinToString("\n")
        .take(12000)
    val contextLabel = if (selectedText.isNullOrBlank()) {
        "$fileName · 当前文件"
    } else {
        buildSelectionContextLabel(fileName, selectedText.lines().size)
    }

    return PromptContextSnapshot(
        fileName = fileName,
        extension = extension ?: "txt",
        content = content,
        contextLabel = contextLabel
    )
}

internal fun buildSelectionContextLabel(fileName: String?, lineCount: Int): String {
    val lineText = "选中 ${lineCount.coerceAtLeast(1)} 行"
    return if (fileName.isNullOrBlank()) lineText else "$fileName · $lineText"
}

internal fun buildBaseSystemPrompt(commandExecutionEnabled: Boolean = false): String =
    if (commandExecutionEnabled) {
        val platform = ShellPlatform.current()
        """
你是一个代码助手。请简洁准确地回答用户问题。
你拥有 ${platform.toolName()} 工具，可以在用户项目根目录执行命令。
当用户请求运行命令、查看文件、执行构建或测试时，主动调用该工具获取真实结果后再作答。${platform.shellHint()}
        """.trimIndent()
    } else {
        """
你是一个代码助手。请简洁准确地回答用户问题。
你当前没有终端、文件系统或工具调用能力。
不要声称你已经执行命令、读取文件、修改代码或查看了运行结果。
如果用户要求你直接运行命令或检查本地文件，请明确说明当前插件暂不支持该能力，并要求用户粘贴结果或手动提供内容。
        """.trimIndent()
    }

internal fun resolveUiContextLabel(
    contextLabelOverride: String?,
    snapshot: PromptContextSnapshot?
): String = contextLabelOverride ?: snapshot?.contextLabel.orEmpty()

internal fun openSettingsOnEdt(
    openDialog: () -> Unit,
    enqueue: ((() -> Unit) -> Unit)
) {
    enqueue(openDialog)
}

internal fun formatSystemContent(
    base: String,
    snapshot: PromptContextSnapshot?,
    memoryText: String = ""
): String {
    var result = base

    if (memoryText.isNotBlank()) {
        result = """$result

[User Memory]
$memoryText"""
    }

    if (snapshot == null) {
        return result
    }

    return """$result

当前文件：${snapshot.fileName}
```${snapshot.extension}
${snapshot.content}
```"""
}

internal fun deriveConnectionState(
    hasProvider: Boolean,
    hasApiKey: Boolean,
    isStreaming: Boolean
): String = when {
    !hasProvider -> "unconfigured"
    isStreaming -> "streaming"
    !hasApiKey -> "error"
    else -> "ready"
}

internal fun filterRestorableMessages(
    messages: List<Message>
): List<Message> = messages
    .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
    .filterNot { it.hidden }
    .filterNot { it.role == MessageRole.ASSISTANT && it.content.isBlank() }
