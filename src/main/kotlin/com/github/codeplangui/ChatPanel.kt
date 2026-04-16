package com.github.codeplangui

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.StartupUiUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import com.intellij.util.messages.Topic

class ChatPanel(project: Project) : JPanel(BorderLayout()), Disposable {

    private var browser: JBCefBrowser? = null
    private var bridge: BridgeHandler? = null
    private var currentContextFile: String = ""

    init {
        if (!JBCefApp.isSupported()) {
            add(
                JLabel(
                    "<html><center>Chat 面板需要 JetBrains Runtime (JBR) 支持<br>" +
                        "请在 Help > Find Action > Switch Boot JDK 中切换至 JBR</center></html>",
                    SwingConstants.CENTER
                ),
                BorderLayout.CENTER
            )
        } else {
            val b = JBCefBrowser()
            browser = b
            val chatService = ChatService.getInstance(project)
            val br = BridgeHandler(b, chatService)
            bridge = br

            br.register()

            val html = javaClass.getResourceAsStream("/webview/index.html")
                ?.bufferedReader()
                ?.readText()
                ?: "<html><body><p>Webview not built. Run: cd webview &amp;&amp; npm run build</p></body></html>"

            // Use a stable URL so JBCefJSQuery handlers register correctly.
            b.loadHTML(html, "http://localhost/")
            add(b.component, BorderLayout.CENTER)

            // Push the current theme and context after the webview binds its callbacks.
            chatService.setOnFrontendReadyCallback {
                val isDark = StartupUiUtil.isUnderDarcula()
                pushTheme(isDark)
                br.notifyContextFile(currentContextFile)
            }

            // Listen for IDE theme changes
            val connection = themeMessageBus(project).connect(this)
            connection.subscribe(LafManagerListener.TOPIC, object : LafManagerListener {
                override fun lookAndFeelChanged(source: LafManager) {
                    val isDark = StartupUiUtil.isUnderDarcula()
                    pushTheme(isDark)
                }
            })
        }
    }

    private fun pushTheme(isDark: Boolean) {
        val br = bridge ?: return
        if (br.isReady) {
            br.notifyTheme(bridgeTheme(isDark))
        }
    }

    fun updateContextFile(fileName: String) {
        currentContextFile = fileName
        val br = bridge ?: return
        if (br.isReady) br.notifyContextFile(fileName)
    }

    override fun dispose() {
        browser?.dispose()
    }
}

internal fun bridgeTheme(isUnderDarcula: Boolean): String = if (isUnderDarcula) "dark" else "light"

internal fun themeTopicRequiresApplicationBus(
    topic: Topic<LafManagerListener> = LafManagerListener.TOPIC
): Boolean = topic.broadcastDirection == Topic.BroadcastDirection.NONE

internal fun themeMessageBus(project: Project) =
    if (themeTopicRequiresApplicationBus()) {
        ApplicationManager.getApplication().messageBus
    } else {
        project.messageBus
    }
