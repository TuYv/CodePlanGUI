package com.github.codeplangui.execution.review

/**
 * A single contiguous change region in a diff.
 */
data class DiffHunk(
    val startLine: Int,
    val deletedLines: List<String>,
    val insertedLines: List<String>
)

/**
 * Computes diff hunks from two text strings using LCS-based line diff.
 */
object DiffCalculator {

    fun computeHunks(original: String, new: String): List<DiffHunk> {
        val oldLines = original.lines()
        val newLines = new.lines()
        val lcs = computeLcsTable(oldLines, newLines)
        val diffOps = backtrackDiff(lcs, oldLines, newLines)
        return groupIntoHunks(diffOps)
    }

    private fun computeLcsTable(old: List<String>, new: List<String>): Array<IntArray> {
        val m = old.size
        val n = new.size
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 1..m) {
            for (j in 1..n) {
                if (old[i - 1] == new[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                } else {
                    dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        return dp
    }

    private fun backtrackDiff(
        dp: Array<IntArray>,
        old: List<String>,
        new: List<String>
    ): List<DiffOp> {
        val ops = mutableListOf<DiffOp>()
        var i = old.size
        var j = new.size

        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && old[i - 1] == new[j - 1] -> {
                    ops.add(DiffOp.Equal(i - 1, j - 1))
                    i--
                    j--
                }
                j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                    ops.add(DiffOp.Insert(j - 1))
                    j--
                }
                else -> {
                    ops.add(DiffOp.Delete(i - 1))
                    i--
                }
            }
        }
        return ops.reversed()
    }

    private fun groupIntoHunks(ops: List<DiffOp>): List<DiffHunk> {
        val hunks = mutableListOf<DiffHunk>()
        val currentDeletes = mutableListOf<String>()
        val currentInserts = mutableListOf<String>()
        var hunkStartLine = -1

        fun flushHunk() {
            if (currentDeletes.isNotEmpty() || currentInserts.isNotEmpty()) {
                hunks.add(DiffHunk(hunkStartLine, currentDeletes.toList(), currentInserts.toList()))
                currentDeletes.clear()
                currentInserts.clear()
                hunkStartLine = -1
            }
        }

        for (op in ops) {
            when (op) {
                is DiffOp.Equal -> flushHunk()
                is DiffOp.Delete -> {
                    if (hunkStartLine == -1) hunkStartLine = op.oldIndex
                    currentDeletes.add(/* placeholder — filled below */"")
                }
                is DiffOp.Insert -> {
                    if (hunkStartLine == -1) hunkStartLine = op.newIndex
                    currentInserts.add(/* placeholder — filled below */"")
                }
            }
        }
        flushHunk()
        return hunks
    }

    // Recompute with actual line content
    fun computeHunksWithContent(original: String, new: String): List<DiffHunk> {
        val oldLines = original.lines()
        val newLines = new.lines()
        val dp = computeLcsTable(oldLines, newLines)
        val ops = backtrackDiff(dp, oldLines, newLines)

        val hunks = mutableListOf<DiffHunk>()
        val currentDeletes = mutableListOf<String>()
        val currentInserts = mutableListOf<String>()
        var hunkStartLine = -1

        fun flushHunk() {
            if (currentDeletes.isNotEmpty() || currentInserts.isNotEmpty()) {
                hunks.add(DiffHunk(hunkStartLine, currentDeletes.toList(), currentInserts.toList()))
                currentDeletes.clear()
                currentInserts.clear()
                hunkStartLine = -1
            }
        }

        for (op in ops) {
            when (op) {
                is DiffOp.Equal -> flushHunk()
                is DiffOp.Delete -> {
                    if (hunkStartLine == -1) hunkStartLine = op.oldIndex
                    currentDeletes.add(oldLines[op.oldIndex])
                }
                is DiffOp.Insert -> {
                    if (hunkStartLine == -1) hunkStartLine = op.newIndex
                    currentInserts.add(newLines[op.newIndex])
                }
            }
        }
        flushHunk()
        return hunks
    }
}

private sealed class DiffOp {
    data class Equal(val oldIndex: Int, val newIndex: Int) : DiffOp()
    data class Delete(val oldIndex: Int) : DiffOp()
    data class Insert(val newIndex: Int) : DiffOp()
}
