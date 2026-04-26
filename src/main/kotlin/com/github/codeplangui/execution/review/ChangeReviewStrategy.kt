package com.github.codeplangui.execution.review

import com.intellij.openapi.project.Project

/**
 * File change review strategy.
 * DialogReview: current implementation (Messages.showYesNoDialog), kept as fallback.
 * EditorInlineReview: inline diff display inside IntelliJ editor (target implementation).
 */
interface ChangeReviewStrategy {

    /**
     * Review a file modification.
     * @return true = user accepted, false = user rejected or timeout
     */
    suspend fun reviewFileChange(
        project: Project,
        requestId: String,
        path: String,
        originalContent: String,
        newContent: String
    ): Boolean

    /**
     * Review a new file creation.
     * @return true = user confirmed creation, false = user rejected or timeout
     */
    suspend fun reviewNewFile(
        project: Project,
        requestId: String,
        path: String,
        content: String
    ): Boolean

    /** Session trust state (in EditorInline mode, synced with settings). */
    var sessionTrusted: Boolean

    /** Reset session trust. */
    fun resetSessionTrust() {
        sessionTrusted = false
    }
}
