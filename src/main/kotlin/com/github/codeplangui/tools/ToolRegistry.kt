package com.github.codeplangui.tools

import com.github.codeplangui.tools.bash.BashTool
import com.github.codeplangui.tools.file.FileEditTool
import com.github.codeplangui.tools.file.FileListTool
import com.github.codeplangui.tools.file.FileReadTool
import com.github.codeplangui.tools.file.FileSearchTool
import com.github.codeplangui.tools.file.WriteFileTool

/**
 * Central registry of built-in tools and the plumbing that assembles the pool
 * handed to the LLM on each turn.
 *
 * Mirrors Claude Code's `tools.ts` (L193-389):
 *   - `getAllBaseTools()` — hard-coded list of built-in tools
 *   - `filterToolsByDenyRules()` — prunes tools blocked by permission config so
 *      they never enter the prompt (save tokens + prevent the model from
 *      attempting them)
 *   - `assembleToolPool()` — merges built-in + MCP tools, dedupes by name with
 *      built-in winning, sorts for prompt-cache stability.
 *
 * MVP:
 *   - No feature flags. Every built-in tool is always registered.
 *   - Deny rule format is simple prefix: rule == tool.name matches exactly,
 *     rule == "ToolName(" matches any parametrized variant (future M3).
 *   - MCP tools default to empty list.
 */
object ToolRegistry {

    /**
     * All built-in tools, unfiltered. Order does not matter — `assembleToolPool`
     * sorts by name.
     */
    fun getAllBaseTools(): List<Tool<*, *>> = listOf(
        BashTool,
        FileEditTool,
        FileListTool,
        FileReadTool,
        FileSearchTool,
        WriteFileTool,
    )

    /**
     * Pre-filter tools by the permission context's deny rules. Denied tools are
     * removed from the pool entirely so the LLM never sees them.
     */
    fun filterToolsByDenyRules(
        tools: List<Tool<*, *>>,
        context: ToolExecutionContext,
    ): List<Tool<*, *>> {
        val deny = context.permissionContext.alwaysDeny
        if (deny.isEmpty()) return tools
        return tools.filterNot { tool -> isDenied(tool, deny) }
    }

    /**
     * Build the final tool pool for a request: base ∪ mcp, dedupe by name
     * (built-in wins), sorted for prompt-cache stability.
     */
    fun assembleToolPool(
        baseTools: List<Tool<*, *>> = getAllBaseTools(),
        mcpTools: List<Tool<*, *>> = emptyList(),
    ): List<Tool<*, *>> {
        val seen = mutableSetOf<String>()
        return (baseTools + mcpTools)
            .filter { seen.add(it.name) }
            .sortedBy { it.name }
    }

    /**
     * Look up a tool by name or alias within a pool. Returns null if not found.
     */
    fun findByName(pool: List<Tool<*, *>>, name: String): Tool<*, *>? =
        pool.firstOrNull { it.name == name || name in it.aliases }

    private fun isDenied(tool: Tool<*, *>, denyRules: Set<String>): Boolean =
        denyRules.any { rule ->
            rule == tool.name || rule.startsWith("${tool.name}(")
        }
}
