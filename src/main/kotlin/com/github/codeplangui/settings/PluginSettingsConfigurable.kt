package com.github.codeplangui.settings

import com.intellij.openapi.options.Configurable
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Temporary placeholder so plugin.xml references remain loadable before Task 9.
 */
class PluginSettingsConfigurable : Configurable {
    override fun getDisplayName(): String = "CodePlanGUI"

    override fun createComponent(): JComponent {
        return JPanel(BorderLayout()).apply {
            add(
                JLabel("Settings UI will be implemented in a later task."),
                BorderLayout.CENTER
            )
        }
    }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    override fun reset() = Unit

    override fun disposeUIResources() = Unit
}
