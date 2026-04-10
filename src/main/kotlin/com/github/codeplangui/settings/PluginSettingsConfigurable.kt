package com.github.codeplangui.settings

import com.github.codeplangui.api.OkHttpSseClient
import com.github.codeplangui.api.TestResult
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

class PluginSettingsConfigurable : Configurable {
    private lateinit var panel: JPanel
    private lateinit var tableModel: ProviderTableModel
    private val client = OkHttpSseClient()

    override fun getDisplayName(): String = "CodePlanGUI"

    override fun createComponent(): JComponent {
        val settings = PluginSettings.getInstance().getState()

        tableModel = ProviderTableModel(settings.providers.toMutableList())
        val table = JBTable(tableModel).apply {
            setDefaultRenderer(String::class.java, object : ColoredTableCellRenderer() {
                override fun customizeCellRenderer(
                    table: JTable,
                    value: Any?,
                    selected: Boolean,
                    hasFocus: Boolean,
                    row: Int,
                    column: Int
                ) {
                    append(value?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            })
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            preferredScrollableViewportSize = Dimension(500, 150)
        }

        val testBtn = JButton("Test Connection").apply {
            addActionListener {
                val row = table.selectedRow
                if (row < 0) {
                    Messages.showInfoMessage("请先选择一个 Provider", "CodePlanGUI")
                    return@addActionListener
                }
                val config = tableModel.providers[row]
                val key = ApiKeyStore.load(config.id) ?: ""
                ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Testing connection...") {
                    override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                        val result = client.testConnection(config, key)
                        SwingUtilities.invokeLater {
                            when (result) {
                                is TestResult.Success -> Messages.showInfoMessage("✓ 连接成功", "CodePlanGUI")
                                is TestResult.Failure -> Messages.showErrorDialog(result.message, "连接失败")
                            }
                        }
                    }
                })
            }
        }

        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction {
                val dialog = ProviderDialog()
                if (dialog.showAndGet()) {
                    val config = dialog.getConfig()
                    ApiKeyStore.save(config.id, dialog.getApiKey())
                    tableModel.providers.add(config)
                    tableModel.fireTableDataChanged()
                }
            }
            .setEditAction {
                val row = table.selectedRow
                if (row < 0) return@setEditAction
                val dialog = ProviderDialog(tableModel.providers[row])
                if (dialog.showAndGet()) {
                    val config = dialog.getConfig()
                    ApiKeyStore.save(config.id, dialog.getApiKey())
                    tableModel.providers[row] = config
                    tableModel.fireTableDataChanged()
                }
            }
            .setRemoveAction {
                val row = table.selectedRow
                if (row < 0) return@setRemoveAction
                val config = tableModel.providers[row]
                ApiKeyStore.delete(config.id)
                tableModel.providers.removeAt(row)
                tableModel.fireTableDataChanged()
            }
            .createPanel()

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel("API Providers:"),
                JPanel(BorderLayout()).apply {
                    add(decorator, BorderLayout.CENTER)
                    add(testBtn, BorderLayout.SOUTH)
                }
            )
            .panel

        return panel
    }

    override fun isModified(): Boolean {
        val settings = PluginSettings.getInstance().getState()
        return tableModel.providers != settings.providers
    }

    override fun apply() {
        val settings = PluginSettings.getInstance().getState()
        settings.providers = tableModel.providers.toMutableList()
        if (settings.activeProviderId == null && settings.providers.isNotEmpty()) {
            settings.activeProviderId = settings.providers[0].id
        }
    }

    override fun reset() {
        val settings = PluginSettings.getInstance().getState()
        tableModel.providers = settings.providers.toMutableList()
        tableModel.fireTableDataChanged()
    }

    override fun disposeUIResources() = Unit

    private class ProviderTableModel(var providers: MutableList<ProviderConfig>) : AbstractTableModel() {
        private val columns = listOf("名称", "Endpoint", "模型")

        override fun getRowCount(): Int = providers.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
            0 -> providers[rowIndex].name
            1 -> providers[rowIndex].endpoint
            2 -> providers[rowIndex].model
            else -> ""
        }
    }
}
