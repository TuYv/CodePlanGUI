package com.github.codeplangui.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

/**
 * Temporary placeholder so plugin.xml references remain loadable before Task 10.
 */
class GenerateCommitMessageAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        Messages.showInfoMessage(project, "Commit message generation will be implemented in a later task.", "CodePlanGUI")
    }
}
