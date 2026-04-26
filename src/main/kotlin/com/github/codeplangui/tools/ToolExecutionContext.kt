package com.github.codeplangui.tools

import com.intellij.openapi.project.Project
import kotlinx.coroutines.Job

/**
 * Runtime context handed to every `Tool.call()`. Kept narrow for MVP — Claude Code's
 * `ToolUseContext` (Tool.ts:158-300) carries ~30 fields for hooks, MCP clients, app state,
 * notifications. Phase2 adds those as needs emerge. Fields not yet justified stay out.
 */
data class ToolExecutionContext(
    val project: Project,
    val toolUseId: String,
    val abortJob: Job,
    val permissionContext: ToolPermissionContext = ToolPermissionContext.default(),
    /**
     * M3: real permission decision callback. Called when `checkPermissions` returns `Ask`.
     * Returns true to allow, false to deny. Default auto-approves so existing callers
     * that don't supply a callback keep the pre-M3 behavior.
     * M5 wires this to the Bridge approval dialog.
     */
    val onPermissionAsked: suspend (ToolUpdate.PermissionAsked) -> Boolean = { true },
)

/**
 * Global permission configuration. Mirrors Claude Code's `ToolPermissionContext`
 * (Tool.ts:123-138) with the MVP-relevant fields. Rule matching logic lives in
 * individual tools (via `preparePermissionMatcher`, M3).
 */
data class ToolPermissionContext(
    val mode: Mode,
    val alwaysAllow: Set<String>,
    val alwaysDeny: Set<String>,
    val alwaysAsk: Set<String>,
    val additionalWorkingDirectories: Set<String>,
) {
    enum class Mode { DEFAULT, ACCEPT_EDITS, PLAN, BYPASS }

    companion object {
        fun default() = ToolPermissionContext(
            mode = Mode.DEFAULT,
            alwaysAllow = emptySet(),
            alwaysDeny = emptySet(),
            alwaysAsk = emptySet(),
            additionalWorkingDirectories = emptySet(),
        )
    }
}
