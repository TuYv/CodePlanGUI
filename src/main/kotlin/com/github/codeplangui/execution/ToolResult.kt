package com.github.codeplangui.execution

import kotlinx.serialization.Serializable

/**
 * Unified result type for all tools (built-in and MCP).
 * Every tool returns this, never throws.
 */
data class ToolResult(
    val ok: Boolean,
    val output: String,
    val awaitUser: Boolean = false,
    val backgroundTask: BackgroundTask? = null,
    val truncated: Boolean = false,
    val totalBytes: Int? = null,
    val outputPath: String? = null,
    val pendingReview: FileChangeReviewData? = null
)

/**
 * Carries pending file change data from executor to dispatcher.
 * Dispatcher delegates to ChangeReviewStrategy for approval, then writes.
 */
data class FileChangeReviewData(
    val path: String,
    val originalContent: String,
    val newContent: String,
    val isNewFile: Boolean = false,
    val newContentForCreate: String? = null
)

@Serializable
data class BackgroundTask(
    val id: String,
    val command: String,
    val status: BackgroundTaskStatus
)

@Serializable
enum class BackgroundTaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
}
