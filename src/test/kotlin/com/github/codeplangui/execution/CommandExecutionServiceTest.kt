package com.github.codeplangui.execution

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommandExecutionServiceTest {

    @Test
    fun `extractBaseCommand returns first word for simple command`() {
        assertEquals("cargo", CommandExecutionService.extractBaseCommand("cargo test --workspace"))
    }

    @Test
    fun `extractBaseCommand strips path prefix`() {
        assertEquals("cargo", CommandExecutionService.extractBaseCommand("/usr/local/bin/cargo test"))
    }

    @Test
    fun `extractBaseCommand returns first word before pipe`() {
        assertEquals("ls", CommandExecutionService.extractBaseCommand("ls src/ | grep kt"))
    }

    @Test
    fun `isWhitelisted returns true when base command matches whitelist entry`() {
        val whitelist = listOf("cargo", "git", "ls")
        assertTrue(CommandExecutionService.isWhitelisted("cargo test --workspace", whitelist))
    }

    @Test
    fun `isWhitelisted returns false when base command is not in whitelist`() {
        val whitelist = listOf("cargo", "git")
        assertFalse(CommandExecutionService.isWhitelisted("rm -rf dist", whitelist))
    }

    @Test
    fun `isWhitelisted returns false for empty whitelist`() {
        assertFalse(CommandExecutionService.isWhitelisted("ls", emptyList()))
    }

    @Test
    fun `hasPathsOutsideWorkspace returns false for relative paths`() {
        assertFalse(CommandExecutionService.hasPathsOutsideWorkspace("ls src/main", "/home/user/project"))
    }

    @Test
    fun `hasPathsOutsideWorkspace returns true for absolute path outside project`() {
        assertTrue(CommandExecutionService.hasPathsOutsideWorkspace("cat /etc/passwd", "/home/user/project"))
    }

    @Test
    fun `hasPathsOutsideWorkspace returns false for absolute path inside project`() {
        assertFalse(CommandExecutionService.hasPathsOutsideWorkspace(
            "cat /home/user/project/src/main.kt",
            "/home/user/project"
        ))
    }

    @Test
    fun `truncateOutput trims output exceeding max chars`() {
        val long = "a".repeat(5000)
        val result = CommandExecutionService.truncateOutput(long, 4000)
        assertEquals(4000, result.length)
    }

    @Test
    fun `truncateOutput returns original when within limit`() {
        val short = "hello"
        assertEquals(short, CommandExecutionService.truncateOutput(short, 4000))
    }
}
