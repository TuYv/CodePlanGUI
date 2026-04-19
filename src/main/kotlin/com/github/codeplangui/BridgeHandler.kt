package com.github.codeplangui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.util.Queue
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
private data class BridgePayload(
    val type: String,
    val text: String = "",
    val includeContext: Boolean = true,
    val requestId: String = "",
    val decision: String = "",
    val addToWhitelist: Boolean = false,
    val current: Int = 0,
    val max: Int = 0
)

internal interface BridgeCommands {
    fun sendMessage(text: String, includeContext: Boolean)
    fun newChat()
    fun openSettings()
    fun onFrontendReady()
    fun approvalResponse(requestId: String, decision: String, addToWhitelist: Boolean)
    fun debugLog(text: String)
    fun cancelStream()
}

internal fun dispatchBridgeRequest(
    type: String,
    text: String,
    includeContext: Boolean,
    requestId: String = "",
    decision: String = "",
    addToWhitelist: Boolean = false,
    commands: BridgeCommands
) {
    when (type) {
        "sendMessage"      -> commands.sendMessage(text, includeContext)
        "newChat"          -> commands.newChat()
        "openSettings"     -> commands.openSettings()
        "frontendReady"    -> commands.onFrontendReady()
        "approvalResponse" -> commands.approvalResponse(requestId, decision, addToWhitelist)
        "debugLog"         -> commands.debugLog(text)
        "cancelStream"     -> commands.cancelStream()
    }
}

internal sealed interface BridgePayloadHandlingResult {
    data object Success : BridgePayloadHandlingResult
    data object MalformedPayload : BridgePayloadHandlingResult
    data class CommandError(val message: String, val cause: Throwable) : BridgePayloadHandlingResult
}

internal fun handleBridgePayload(
    payload: String,
    json: Json,
    commands: BridgeCommands
): BridgePayloadHandlingResult {
    val req = try {
        json.decodeFromString<BridgePayload>(payload)
    } catch (_: Exception) {
        return BridgePayloadHandlingResult.MalformedPayload
    }

    return try {
        dispatchBridgeRequest(req.type, req.text, req.includeContext, req.requestId, req.decision, req.addToWhitelist, commands)
        BridgePayloadHandlingResult.Success
    } catch (e: Exception) {
        BridgePayloadHandlingResult.CommandError(
            message = "发送消息失败：${e.message ?: e.javaClass.simpleName}",
            cause = e
        )
    }
}

@Serializable
data class BridgeStatusPayload(
    val providerName: String = "",
    val model: String = "",
    val connectionState: String = "unconfigured"
)

@Serializable
data class BridgeErrorPayload(
    val type: String,
    val message: String,
    val action: String? = null
)

class BridgeHandler(
    private val browser: JBCefBrowser,
    private val chatService: ChatService
) {
    private val logger = Logger.getInstance(BridgeHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var sendQuery: JBCefJSQuery
    var isReady: Boolean = false
        private set

    private val pendingJs: Queue<String> = ConcurrentLinkedQueue()
    private val flushPending = AtomicBoolean(false)
    private val flushTimer = Timer("bridge-flush", true)

    fun register() {
        sendQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
        sendQuery.addHandler { payload ->
            when (val result = handleBridgePayload(payload, json, object : BridgeCommands {
                    override fun sendMessage(text: String, includeContext: Boolean) {
                        chatService.sendMessage(text, includeContext)
                    }

                    override fun newChat() {
                        chatService.newChat()
                    }

                    override fun openSettings() {
                        chatService.openSettings()
                    }

                    override fun onFrontendReady() {
                        chatService.onFrontendReady()
                    }

                    override fun approvalResponse(requestId: String, decision: String, addToWhitelist: Boolean) {
                        logger.info("[CodePlanGUI Bridge] frontend->ide approvalResponse requestId=$requestId decision=$decision addToWhitelist=$addToWhitelist")
                        chatService.onApprovalResponse(requestId, decision, addToWhitelist)
                    }

                    override fun debugLog(text: String) {
                        logger.info("[CodePlanGUI Frontend] $text")
                    }

                    override fun cancelStream() {
                        chatService.cancelStream()
                    }
                })) {
                BridgePayloadHandlingResult.Success -> null
                BridgePayloadHandlingResult.MalformedPayload -> {
                    logger.warn("Ignoring malformed bridge payload")
                    null
                }
                is BridgePayloadHandlingResult.CommandError -> {
                    logger.warn(result.message, result.cause)
                    notifyError(result.message)
                    null
                }
            }
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    isReady = true
                    chatService.attachBridge(this@BridgeHandler)
                    val js = """
                        window.__bridge = {
                            isReady: true,
                            sendMessage: function(text, includeContext) {
                                ${sendQuery.inject("""JSON.stringify({type:'sendMessage',text:text,includeContext:!!includeContext})""")}
                            },
                            newChat: function() {
                                ${sendQuery.inject("""JSON.stringify({type:'newChat',text:''})""")}
                            },
                            openSettings: function() {
                                ${sendQuery.inject("""JSON.stringify({type:'openSettings',text:''})""")}
                            },
                            cancelStream: function() {
                                ${sendQuery.inject("""JSON.stringify({type:'cancelStream',text:''})""")}
                            },
                            frontendReady: function() {
                                ${sendQuery.inject("""JSON.stringify({type:'frontendReady',text:''})""")}
                            },
                            approvalResponse: function(requestId, decision, addToWhitelist) {
                                ${sendQuery.inject("""JSON.stringify({type:'approvalResponse',text:'',requestId:requestId,decision:decision,addToWhitelist:!!addToWhitelist})""")}
                            },
                            debugLog: function(text) {
                                ${sendQuery.inject("""JSON.stringify({type:'debugLog',text:text})""")}
                            },
                            onStart: function(msgId) {},
                            onToken: function(token) {},
                            onEnd: function(msgId) {},
                            onError: function(message) {},
                            onStatus: function(status) {},
                            onContextFile: function(fileName) {},
                            onTheme: function(theme) {},
                            onApprovalRequest: function(requestId, command, description) {},
                            onExecutionCard: function(requestId, command, description) {},
                            onLog: function(msgId, logLine, type) {},
                            onExecutionStatus: function(requestId, status, result) {},
                            onRestoreMessages: function(messages) {},
                            onStructuredError: function(error) {},
                            onContinuation: function(current, max) {},
                            onRemoveMessage: function(msgId) {}
                        };
                        document.dispatchEvent(new Event('bridge_ready'));
                    """.trimIndent()
                    browser.executeJavaScript(js, "", 0)

                    // Prevent JCEF WebView freeze on Ctrl+C/Cmd+C by intercepting
                    // the keyboard event and using async clipboard API instead of
                    // letting CEF handle it synchronously. Ref: jetbrains-cc-gui #846
                    val clipboardJs = """
                        document.addEventListener('keydown', function(e) {
                            if ((e.ctrlKey || e.metaKey) && e.key === 'c') {
                                var selection = window.getSelection();
                                if (selection && selection.toString().length > 0) {
                                    e.preventDefault();
                                    var text = selection.toString();
                                    if (navigator.clipboard && navigator.clipboard.writeText) {
                                        navigator.clipboard.writeText(text).catch(function() {});
                                    }
                                }
                            }
                        }, true);
                    """.trimIndent()
                    browser.executeJavaScript(clipboardJs, "", 0)
                }
            }
        }, browser.cefBrowser)
    }

    fun notifyStart(msgId: String) = flushAndPush("window.__bridge.onStart(${msgId.quoted()})")

    fun notifyToken(token: String) = enqueueJS("window.__bridge.onToken(${json.encodeToString(token)})")

    fun notifyEnd(msgId: String) = flushAndPush("window.__bridge.onEnd(${msgId.quoted()})")

    fun notifyError(message: String) = flushAndPush("window.__bridge.onError(${json.encodeToString(message)})")

    fun notifyStructuredError(error: BridgeErrorPayload) =
        flushAndPush("window.__bridge.onStructuredError(${json.encodeToString(error)})")

    fun notifyStatus(status: BridgeStatusPayload) =
        flushAndPush("window.__bridge.onStatus(${json.encodeToString(status)})")

    fun notifyContextFile(fileName: String) =
        pushJS("window.__bridge.onContextFile(${json.encodeToString(fileName)})")

    fun notifyTheme(theme: String) =
        pushJS("window.__bridge.onTheme(${json.encodeToString(theme)})")

    fun notifyLog(msgId: String, logLine: String, type: String) =
        enqueueJS(
            "window.__bridge.onLog(" +
            "${json.encodeToString(msgId)}," +
            "${json.encodeToString(logLine)}," +
            "${json.encodeToString(type)})"
        )

    fun notifyExecutionCard(requestId: String, command: String, description: String) =
        flushAndPush(
            "window.__bridge.onExecutionCard(" +
            "${json.encodeToString(requestId)}," +
            "${json.encodeToString(command)}," +
            "${json.encodeToString(description)})"
        )

    fun notifyApprovalRequest(requestId: String, command: String, description: String) =
        flushAndPush(
            "window.__bridge.onApprovalRequest(" +
            "${json.encodeToString(requestId)}," +
            "${json.encodeToString(command)}," +
            "${json.encodeToString(description)})"
        ).also {
            logger.info(
                "[CodePlanGUI Bridge] ide->frontend approvalRequest " +
                    "requestId=$requestId command=${command.summarizeForLog()} description=${description.summarizeForLog()}"
            )
        }

    fun notifyExecutionStatus(requestId: String, status: String, resultJson: String) =
        flushAndPush(
            "window.__bridge.onExecutionStatus(" +
            "${json.encodeToString(requestId)}," +
            "${json.encodeToString(status)}," +
            "${json.encodeToString(resultJson)})"
        ).also {
            logger.info(
                "[CodePlanGUI Bridge] ide->frontend executionStatus " +
                    "requestId=$requestId status=$status result=${resultJson.summarizeForLog(240)}"
            )
        }

    fun notifyRestoreMessages(messages: String) =
        flushAndPush("window.__bridge.onRestoreMessages(${json.encodeToString(messages)})")

    fun notifyContinuation(current: Int, max: Int) =
        pushJS("window.__bridge.onContinuation($current, $max)")

    fun notifyRemoveMessage(msgId: String) =
        flushAndPush("window.__bridge.onRemoveMessage(${msgId.quoted()})")

    /**
     * Enqueue a streamable JS call (token / log line) for batch delivery.
     * A daemon timer flushes the queue every ~16 ms, bypassing the EDT entirely.
     */
    private fun enqueueJS(js: String) {
        if (!isReady) {
            logger.debug("[CodePlanGUI Bridge] enqueueJS discarded (bridge not ready): ${js.take(120)}")
            return
        }
        pendingJs.add(js)
        scheduleFlush()
    }

    /**
     * Flush any buffered streamable calls, then push a non-streamable call immediately.
     * Used for structural events (start, end, error, etc.) where ordering matters.
     */
    private fun flushAndPush(js: String) {
        if (!isReady) {
            logger.debug("[CodePlanGUI Bridge] flushAndPush discarded (bridge not ready): ${js.take(120)}")
            return
        }
        flushPendingBuffer()
        executeJS(js)
    }

    /**
     * Schedule a timer-based flush that bypasses the EDT.
     * The [flushPending] flag ensures at most one timer task is outstanding.
     */
    private fun scheduleFlush() {
        if (flushPending.compareAndSet(false, true)) {
            flushTimer.schedule(object : TimerTask() {
                override fun run() {
                    flushPendingBuffer()
                    flushPending.set(false)
                    if (pendingJs.isNotEmpty()) {
                        scheduleFlush()
                    }
                }
            }, FLUSH_INTERVAL_MS)
        }
    }

    /**
     * Drain the pending queue and execute as a single batched JS call.
     * Called from the flush timer thread or from [flushAndPush].
     */
    internal fun flushPendingBuffer() {
        val batch = mutableListOf<String>()
        while (true) {
            val item = pendingJs.poll() ?: break
            batch.add(item)
        }
        if (batch.isNotEmpty()) {
            executeJS(batch.joinToString(";"))
        }
    }

    private fun pushJS(js: String) {
        if (!isReady) {
            logger.debug("[CodePlanGUI Bridge] pushJS discarded (bridge not ready): ${js.take(120)}")
            return
        }
        executeJS(js)
    }

    /**
     * Execute a JavaScript string in the browser. Called directly without EDT dispatch —
     * CEF's [executeJavaScript] is thread-safe and posts the JS to the renderer process.
     */
    private fun executeJS(js: String) {
        try {
            browser.cefBrowser.executeJavaScript(js, "", 0)
        } catch (e: Exception) {
            logger.debug("[CodePlanGUI Bridge] executeJS failed: ${e.message}")
        }
    }

    companion object {
        /** Flush interval in ms — ~60 fps. */
        internal const val FLUSH_INTERVAL_MS = 16L
    }

    private fun String.quoted() = "'${replace("'", "\\'")}'"

    private fun String.summarizeForLog(maxLength: Int = 120): String {
        val singleLine = replace('\n', ' ').replace('\r', ' ').trim()
        return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength) + "..."
    }
}
