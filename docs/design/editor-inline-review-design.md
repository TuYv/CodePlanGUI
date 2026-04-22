# Editor Inline Review — 实现设计

**日期**: 2026-04-22
**状态**: Draft
**前置依赖**: unified-tool-design v3.x，ToolCallDispatcher 已完成
**参考**: intellij-inline-edit-design.md（桌面设计草稿）

---

## 1. 目标

将 `edit_file` / `write_file` 的文件变更审批从当前的 `Messages.showYesNoDialog` 简单弹框，迁移到 **IntelliJ 编辑器内 inline diff 展示**。用户在代码编辑器中直接看到变更（红色删除行 + 绿色新增行），点击 Accept/Reject 完成审批。

### 1.1 体验对比

```
当前实现（YesNo 弹框）：                   目标（Editor Inline）：
┌───────────────────────────┐            ┌──────────────────────────────────┐
│ IntelliJ Dialog            │            │ IntelliJ 编辑器                    │
│                            │            │                                   │
│ Apply changes to Foo.kt?   │            │  1│ fun main() {                 │
│ Lines: +2 / -1             │            │  2│ -   println("old") ← 红色删行 │
│ --- New/changed lines ---  │            │  3│ +   println("new") ← 绿色增行 │
│ println("new")             │            │  4│ }                             │
│                            │            │     ├─ [Accept] [Reject]          │
│ [Yes]  [No]               │            │                                   │
└───────────────────────────┘            └──────────────────────────────────┘
```

### 1.2 设计原则

1. **编辑器原生** — 使用 IntelliJ Editor API，不是 WebView
2. **策略可切换** — 保留降级方案（当前 YesNo 弹框），通过 Settings 切换
3. **零前端改动** — inline 审批完全在 Kotlin 侧完成，不经过 Bridge
4. **渐进式** — 可与现有审批方式共存

---

## 2. 策略抽象

### 2.1 ChangeReviewStrategy 接口

新建文件 `execution/review/ChangeReviewStrategy.kt`：

```kotlin
/**
 * 文件变更审批策略。
 * DialogReview: 当前实现（Messages.showYesNoDialog），作为降级方案保留。
 * EditorInlineReview: 在 IntelliJ 编辑器内 inline 展示 diff（目标方案）。
 */
interface ChangeReviewStrategy {
    /**
     * 审批文件修改。
     * @return true = 用户接受，false = 用户拒绝或超时
     */
    suspend fun reviewFileChange(
        project: Project,
        requestId: String,
        path: String,
        originalContent: String,
        newContent: String
    ): Boolean

    /**
     * 审批新建文件。
     * @return true = 用户确认创建，false = 用户拒绝或超时
     */
    suspend fun reviewNewFile(
        project: Project,
        requestId: String,
        path: String,
        content: String
    ): Boolean

    /** 会话信任状态（EditorInline 模式下同步设置） */
    var sessionTrusted: Boolean

    /** 重置会话信任 */
    fun resetSessionTrust() {
        sessionTrusted = false
    }
}
```

### 2.2 策略选择

在 `ToolCallDispatcher` 构造时根据 Settings 注入：

```kotlin
class ToolCallDispatcher(...) {
    private val reviewStrategy: ChangeReviewStrategy

    init {
        reviewStrategy = when (settings.reviewMode) {
            "editor_inline" -> EditorInlineReview(project)
            "dialog"        -> DialogReview()
            else            -> EditorInlineReview(project) // 默认 inline
        }
    }
}
```

---

## 3. DialogReview — 降级方案（提取现有逻辑）

将 `FileChangeReview.kt` 中现有逻辑包装为策略实现。

新建文件 `execution/review/DialogReview.kt`：

```kotlin
/**
 * 降级方案：使用 Messages.showYesNoDialog 展示简单 diff 摘要。
 * 提取自原 FileChangeReview，行为不变。
 */
class DialogReview : ChangeReviewStrategy {

    override var sessionTrusted: Boolean = false

    override suspend fun reviewFileChange(
        project: Project, requestId: String, path: String,
        originalContent: String, newContent: String
    ): Boolean {
        if (sessionTrusted) return true

        return withContext(Dispatchers.IO) {
            val future = CompletableFuture<Boolean>()
            ApplicationManager.getApplication().invokeAndWait {
                // 原有逻辑：计算 diff stats → 展示预览行 → YesNo 弹框
                val message = buildReviewMessage(path, originalContent, newContent)
                val result = Messages.showYesNoDialog(
                    project, message, "File Change Review: $path",
                    Messages.getQuestionIcon()
                )
                future.complete(result == Messages.YES)
            }
            future.get(60, TimeUnit.SECONDS)
        }
    }

    override suspend fun reviewNewFile(
        project: Project, requestId: String, path: String, content: String
    ): Boolean {
        if (sessionTrusted) return true
        // 原有逻辑：路径 + 大小 + 前 20 行预览 → OkCancel 弹框
        ...
    }

    private fun buildReviewMessage(path: String, old: String, new: String): String { ... }
}
```

---

## 4. EditorInlineReview — 编辑器内实现

### 4.1 核心流程

```
EditorInlineReview.reviewFileChange()
  │
  ├─ 1. 查找或打开目标文件的 Editor
  │     VirtualFileManager.findFile(path)
  │     → FileEditorManager.getEditors()
  │     → 未打开则在后台打开（focusEditor = false）
  │
  ├─ 2. 计算 diff hunks
  │     基于 Myers diff 算法计算变更区域
  │
  ├─ 3. 创建 InlineDiffHandler 并渲染
  │     删除行 → 红色背景 + 删除线 (RangeHighlighter)
  │     新增行 → 绿色背景 (BlockInlayRenderer)
  │     操作按钮 → EditorNotificationPanel [Accept] [Reject]
  │
  ├─ 4. 协程挂起 awaitUserDecision()
  │     用户点击 Accept → resume(true)
  │     用户点击 Reject → resume(false)
  │     60 秒超时 → resume(false) + 自动清理
  │
  └─ 5. 清理渲染（无论接受/拒绝）
```

### 4.2 DiffHunk — Diff 计算结果

新建文件 `execution/review/DiffHunk.kt`：

```kotlin
/**
 * 单个连续变更区域。
 */
data class DiffHunk(
    val startLine: Int,           // 0-based，变更起始行
    val deletedLines: List<String>, // 被删除的行
    val insertedLines: List<String> // 新增的行
)

/**
 * 从两段文本计算 diff hunks。
 * 使用简单的行级 diff（Myers 算法简化版）。
 */
object DiffCalculator {
    fun computeHunks(original: String, new: String): List<DiffHunk> {
        val oldLines = original.lines()
        val newLines = new.lines()
        // 使用 java.diff_utils 或自实现 LCS-based diff
        // 分组连续的增/删行为 hunks
        ...
    }
}
```

**实现选择**：使用 `java-diff-utils` 库（IntelliJ 已内嵌 `com.intellij.diff` 包），或基于 LCS 自实现简化版本。第一版先用 LCS 最长公共子序列做行级 diff。

### 4.3 InlineDiffHandler — 渲染与交互

新建文件 `execution/review/InlineDiffHandler.kt`：

```kotlin
/**
 * 管理单个编辑器内的 inline diff 渲染和交互。
 * 负责高亮、操作按钮、协程挂起等待用户决策、清理。
 */
class InlineDiffHandler(
    private val project: Project,
    private val editor: Editor,
    private val requestId: String
) {
    private val highlighters = mutableListOf<RangeHighlighter>()
    private val inlays = mutableListOf<Inlay<BlockRenderer>>()
    private var notificationPanel: EditorNotificationPanel? = null
    private var continuation: CancellableContinuation<Boolean>? = null
    private var timeoutJob: Job? = null

    /**
     * 在编辑器中渲染 inline diff 并挂起等待用户操作。
     */
    suspend fun showAndWait(hunks: List<DiffHunk>): Boolean {
        return suspendCancellableCoroutine { cont ->
            continuation = cont

            // 在 EDT 上渲染
            ApplicationManager.getApplication().invokeLater {
                renderHunks(hunks)
                renderActionButtons()
            }

            // 60 秒超时
            timeoutJob = CoroutineScope(Dispatchers.Default).launch {
                delay(60_000)
                cont.resume(false) { }
                cleanup()
            }

            cont.invokeOnCancellation {
                timeoutJob?.cancel()
                ApplicationManager.getApplication().invokeLater { cleanup() }
            }
        }
    }

    private fun renderHunks(hunks: List<DiffHunk>) {
        val document = editor.document
        var lineOffset = 0 // 累计新增行导致的偏移

        for (hunk in hunks) {
            val adjustedStart = hunk.startLine + lineOffset

            // 删除行：红色背景 + 删除线
            for ((offset, line) in hunk.deletedLines.withIndex()) {
                val lineIndex = adjustedStart + offset
                if (lineIndex >= document.lineCount) continue

                val start = document.getLineStartOffset(lineIndex)
                val end = document.getLineEndOffset(lineIndex)
                val highlighter = editor.markupModel.addRangeHighlighter(
                    start, end,
                    HighlighterLayer.SELECTION,
                    TextAttributes().apply {
                        backgroundColor = JBColor(Color(255, 235, 235), Color(100, 40, 40))
                        effectType = EffectType.STRIKEOUT
                        effectColor = JBColor.RED
                    },
                    HighlighterTargetArea.EXACT_RANGE
                )
                highlighters.add(highlighter)
            }

            // 新增行：绿色背景，使用 Inlay 插入虚拟行
            val insertLine = adjustedStart + hunk.deletedLines.size
            for ((offset, line) in hunk.insertedLines.withIndex()) {
                val targetOffset = if (insertLine < document.lineCount) {
                    document.getLineStartOffset(insertLine)
                } else {
                    document.textLength
                }
                val renderer = DiffLineInlayRenderer(line)
                val inlay = editor.inlayModel.addBlockElement(
                    targetOffset,
                    true,  // showAbove
                    false, // priority
                    0,     // relatedLineCount
                    renderer
                )
                if (inlay != null) inlays.add(inlay)
            }

            lineOffset += hunk.insertedLines.size - hunk.deletedLines.size
        }
    }

    private fun renderActionButtons() {
        // 使用 EditorNotificationPanel 在编辑器顶部展示 Accept/Reject
        val panel = EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info)
        panel.text = "CodePlanGUI: Review file changes"
        panel.setAction("Accept") { accept() }
        panel.setAction("Reject") { reject() }
        // 挂载到编辑器
        EditorNotifications.getInstance(project).updateNotifications(...)
        notificationPanel = panel
    }

    private fun accept() {
        continuation?.resume(true)
        cleanup()
    }

    private fun reject() {
        continuation?.resume(false)
        cleanup()
    }

    fun cleanup() {
        highlighters.forEach { it.dispose() }
        highlighters.clear()
        inlays.forEach { it.dispose() }
        inlays.clear()
        notificationPanel = null
        timeoutJob?.cancel()
    }
}
```

### 4.4 DiffLineInlayRenderer — 新增行渲染

新建文件 `execution/review/DiffLineInlayRenderer.kt`：

```kotlin
/**
 * 渲染单行新增代码的 BlockRenderer。
 * 绿色背景，显示行内容。
 */
class DiffLineInlayRenderer(private val lineText: String) : BlockRenderer {

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return inlay.editor.lineHeight
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val fontMetrics = inlay.editor.contentComponent.getFontMetrics(inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN))
        return fontMetrics.stringWidth(lineText) + 20 // padding
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle) {
        // 绿色背景
        g.color = JBColor(Color(232, 255, 232), Color(40, 80, 40))
        g.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)

        // 行内容
        g.color = inlay.editor.colorsScheme.getColor(EditorColors.CARET_COLOR) ?: JBColor.BLACK
        g.font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
        g.drawString("+ $lineText", targetRegion.x + 4, targetRegion.y + inlay.editor.ascent)
    }

    override fun toString(): String = "DiffLineInlayRenderer: $lineText"
}
```

### 4.5 EditorInlineReview 完整实现

新建文件 `execution/review/EditorInlineReview.kt`：

```kotlin
/**
 * IntelliJ 编辑器内 inline diff 审批策略。
 */
class EditorInlineReview(
    private val project: Project
) : ChangeReviewStrategy {

    override var sessionTrusted: Boolean = false

    private val activeHandlers = ConcurrentHashMap<String, InlineDiffHandler>()

    override suspend fun reviewFileChange(
        project: Project, requestId: String, path: String,
        originalContent: String, newContent: String
    ): Boolean {
        if (sessionTrusted) return true

        val editor = findOrOpenEditor(path) ?: return false

        val hunks = DiffCalculator.computeHunks(originalContent, newContent)
        if (hunks.isEmpty()) return true // 无变更，直接通过

        val handler = InlineDiffHandler(project, editor, requestId)
        activeHandlers[requestId] = handler

        return try {
            handler.showAndWait(hunks)
        } finally {
            activeHandlers.remove(requestId)
        }
    }

    override suspend fun reviewNewFile(
        project: Project, requestId: String, path: String, content: String
    ): Boolean {
        if (sessionTrusted) return true
        // 新建文件：使用 EditorNotificationPanel 确认（或降级到 DialogReview）
        return showCreateFileConfirmation(path, content)
    }

    private fun findOrOpenEditor(path: String): Editor? {
        val virtualFile = LocalFileSystem.getInstance()
            .findFileByPath(path) ?: return null

        // 查找已打开的编辑器
        val editors = FileEditorManager.getInstance(project)
            .getEditors(virtualFile)
        val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull()
        if (textEditor != null) return textEditor.editor

        // 后台打开（不抢焦点）
        return FileEditorManager.getInstance(project)
            .openFile(virtualFile, false, true)
            .filterIsInstance<TextEditor>()
            .firstOrNull()?.editor
    }

    private suspend fun showCreateFileConfirmation(path: String, content: String): Boolean {
        // 使用 EditorTextField 展示内容预览 + 确认
        // 降级方案：复用 DialogReview 的逻辑
        ...
    }

    fun dispose() {
        activeHandlers.values.forEach { it.cleanup() }
        activeHandlers.clear()
    }
}
```

---

## 5. 与现有代码的集成

### 5.1 重构 EditFileExecutor

当前：`EditFileExecutor` 直接调 `fileChangeReview.reviewFileChange()`，通过后才写入文件。

目标：`EditFileExecutor` 返回 `pendingReview` 数据给 Dispatcher，由 Dispatcher 统一调策略审批。

**关键改动**：

1. `ToolResult` 新增 `pendingReview` 字段：

```kotlin
data class ToolResult(
    val ok: Boolean,
    val output: String,
    val awaitUser: Boolean = false,
    val backgroundTask: BackgroundTask? = null,
    val truncated: Boolean = false,
    val totalBytes: Int? = null,
    val outputPath: String? = null,
    val pendingReview: FileChangeReviewData? = null  // 新增
)

data class FileChangeReviewData(
    val path: String,
    val originalContent: String,
    val newContent: String,
    val isNewFile: Boolean = false,
    val newContentForCreate: String? = null  // 新建文件时使用
)
```

2. `EditFileExecutor.execute()` 改为返回 `pendingReview`：

```kotlin
// Before: 审批 + 写入都在 executor 内完成
val approved = fileChangeReview.reviewFileChange(...)
if (!approved) return ToolResult(ok = false, ...)
writeFileContent(...)

// After: executor 只计算变更，返回 pendingReview
return ToolResult(
    ok = true,
    output = "Pending review",
    pendingReview = FileChangeReviewData(path, originalContent, newContent)
)
```

3. `WriteFileExecutor.execute()` 同理：

```kotlin
// 已有文件 → 返回 pendingReview
// 新建文件 → 返回 pendingReview(isNewFile = true, newContentForCreate = content)
```

### 5.2 ToolCallDispatcher 审批集成

在 `dispatchInternal()` 的**步骤 8 执行**和**步骤 9 截断**之间插入审批处理：

```kotlin
// dispatchInternal() 流程（节选）

// 8. Execute
val startTime = System.currentTimeMillis()
val rawResult = intercepted ?: runWithFileLock(spec, input, toolName)
val durationMs = System.currentTimeMillis() - startTime

// 8.5 文件变更审批（新增）
val finalResult = if (rawResult.pendingReview != null) {
    handlePendingReview(rawResult, requestId, msgId, stepRequestId, bridgeHandler, durationMs)
} else {
    rawResult
}

// 9. Output truncation
val truncatedResult = truncateOutput(finalResult, msgId)
```

```kotlin
private suspend fun handlePendingReview(
    result: ToolResult,
    requestId: String,
    msgId: String,
    stepRequestId: String,
    bridgeHandler: BridgeHandler?,
    durationMs: Long
): ToolResult {
    val review = result.pendingReview!!

    val approved = if (review.isNewFile) {
        reviewStrategy.reviewNewFile(
            project, requestId, review.path,
            review.newContentForCreate ?: ""
        )
    } else {
        reviewStrategy.reviewFileChange(
            project, requestId, review.path,
            review.originalContent, review.newContent
        )
    }

    if (!approved) {
        bridgeHandler?.notifyToolStepEnd(msgId, stepRequestId, false, "User rejected changes", durationMs)
        return ToolResult(ok = false, output = "User rejected changes")
    }

    // 审批通过 → 执行实际写入
    val writeResult = writeFileAfterApproval(review)
    if (!writeResult.ok) return writeResult

    // 写入成功 → 运行 Post-Edit 管线
    val postEditResult = runPostEditPipeline(review.path)

    val output = buildString {
        append("Changes applied: ${review.path}")
        if (postEditResult != null) {
            append("\n\n")
            append(postEditResult)
        }
    }
    return ToolResult(ok = true, output = output)
}
```

### 5.3 FileChangeReview 迁移

| 阶段 | 变更 |
|------|------|
| Step 1 | `FileChangeReview` 拆为 `DialogReview`（策略实现）+ `ChangeReviewStrategy`（接口） |
| Step 2 | `EditFileExecutor` / `WriteFileExecutor` 构造参数从 `FileChangeReview` 改为 `ChangeReviewStrategy` |
| Step 3 | `ToolCallDispatcher` 构造时注入策略（根据 Settings） |
| Step 4 | 原有 `FileChangeReview.kt` 标记 `@Deprecated`，过渡期保留 |
| Step 5 | 确认所有调用方迁移后删除 `FileChangeReview.kt` |

---

## 6. Settings 扩展

### 6.1 新增配置项

`SettingsState` 新增：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `reviewMode` | String | `"editor_inline"` | `"editor_inline"` / `"dialog"` |

### 6.2 Settings UI

```
File Change Review       [ Editor Inline ▼ ]

  ● Editor Inline  — 在编辑器内展示 diff，直接接受/拒绝（推荐）
  ● Simple Dialog  — 使用简单确认弹框

Permission Level         [ Workspace Write ▼ ]
...
```

---

## 7. IntelliJ API 参考

| API | 用途 | 包 |
|-----|------|-----|
| `FileEditorManager` | 查找/打开编辑器 | `com.intellij.openapi.fileEditor` |
| `MarkupModel.addRangeHighlighter()` | 范围高亮（删除行红色） | `com.intellij.openapi.editor.markup` |
| `InlayModel.addBlockElement()` | 插入虚拟行（新增行渲染） | `com.intellij.openapi.editor` |
| `BlockRenderer` | 自定义 inlay 渲染 | `com.intellij.openapi.editor` |
| `EditorNotificationPanel` | 编辑器顶部通知（操作按钮） | `com.intellij.ui` |
| `WriteCommandAction` | 执行文件写入 | `com.intellij.openapi.command` |
| `LocalFileSystem` | 文件查找 | `com.intellij.openapi.vfs` |

### 渲染方案

- **删除行**：`RangeHighlighter` — 红色背景 + `STRIKEOUT` 效果
- **新增行**：`BlockRenderer`（inlay）— 绿色背景，显示行内容
- **操作按钮**：`EditorNotificationPanel` — 编辑器顶部 Accept/Reject 按钮
- **清理**：所有 `RangeHighlighter`、`Inlay` 在用户操作或超时后 `dispose()`

---

## 8. 文件变更清单

### 8.1 新建文件

| 文件 | 说明 |
|------|------|
| `execution/review/ChangeReviewStrategy.kt` | 审批策略接口 |
| `execution/review/DialogReview.kt` | 简单弹框审批（从 FileChangeReview 提取） |
| `execution/review/EditorInlineReview.kt` | 编辑器内 inline 审批（主方案） |
| `execution/review/InlineDiffHandler.kt` | 编辑器 diff 渲染与交互管理 |
| `execution/review/DiffLineInlayRenderer.kt` | 新增行 inlay 渲染器 |
| `execution/review/DiffHunk.kt` | Diff 计算结果数据类 + DiffCalculator |

### 8.2 修改文件

| 文件 | 变更 |
|------|------|
| `ToolResult.kt` | 新增 `pendingReview: FileChangeReviewData?` 字段 + `FileChangeReviewData` 数据类 |
| `EditFileExecutor.kt` | 移除 `fileChangeReview` 参数，返回 `pendingReview` 而非直接写入 |
| `WriteFileExecutor.kt` | 同上 |
| `ToolCallDispatcher.kt` | 注入 `ChangeReviewStrategy`，处理 `pendingReview` |
| `SettingsState` + Settings UI | 新增 `reviewMode` 配置项 |
| `ToolRegistry.kt` | 构造 Executor 时传入策略（或由 Dispatcher 传入） |

### 8.3 删除文件（过渡期后）

| 文件 | 原因 |
|------|------|
| `FileChangeReview.kt` | 逻辑已拆分到 `DialogReview` |
| `InlineChangeHighlighter.kt` | 功能被 `EditorInlineReview` 包含 |

---

## 9. 实施步骤

| 步骤 | 内容 | 依赖 | 复杂度 |
|------|------|------|--------|
| **Step 1** | 定义 `ChangeReviewStrategy` 接口 + `DiffHunk` + `DiffCalculator` + `ToolResult.pendingReview` | 无 | 低 |
| **Step 2** | 实现 `DialogReview`（从 `FileChangeReview` 提取，行为不变） | Step 1 | 低 |
| **Step 3** | 实现 `DiffLineInlayRenderer`（inlay 渲染器） | Step 1 | 中 |
| **Step 4** | 实现 `InlineDiffHandler`（渲染 + 协程挂起 + 操作按钮） | Step 3 | 高 |
| **Step 5** | 实现 `EditorInlineReview`（组合 InlineDiffHandler + 编辑器查找） | Step 4 | 中 |
| **Step 6** | 修改 `EditFileExecutor` / `WriteFileExecutor` 返回 `pendingReview` | Step 1 | 中 |
| **Step 7** | 修改 `ToolCallDispatcher` 注入策略 + 处理 `pendingReview` | Step 2, 5, 6 | 中 |
| **Step 8** | Settings 新增 `reviewMode` 配置 + UI | Step 7 | 低 |
| **Step 9** | 集成测试 & 验证 | Step 8 | 中 |

Step 1-3 可与现有功能并行开发，无破坏性改动。
Step 6-7 是集成点，需要确保不破坏现有的工具执行流程。

---

## 10. 验收标准

### 功能验收

- [ ] `edit_file` 修改已打开文件 → 编辑器内 inline diff 正确渲染
- [ ] `edit_file` 修改未打开文件 → 自动后台打开后渲染 inline diff
- [ ] `write_file` 覆写已有文件 → inline diff 正确渲染
- [ ] `write_file` 新建文件 → 确认对话框正常工作
- [ ] 点击 Accept → 变更写入文件，inline diff 清理
- [ ] 点击 Reject → 变更丢弃，inline diff 清理
- [ ] 60 秒超时 → 自动拒绝，inline diff 清理
- [ ] 会话信任模式正常工作（Accept 后续变更不再弹审批）

### 渲染验收

- [ ] 删除行：红色背景 + 删除线
- [ ] 新增行：绿色背景，内容正确
- [ ] Accept / Reject 按钮可见且可点击
- [ ] 多处 hunk 同时渲染时互不干扰
- [ ] 编辑器关闭时自动清理所有高亮

### 兼容性验收

- [ ] Settings 切换 `reviewMode` 为 `dialog` → 行为回退到 YesNo 弹框
- [ ] Settings 切换 `reviewMode` 为 `editor_inline` → 使用编辑器内 inline
- [ ] 两种模式下工具执行结果一致

### 安全验收

- [ ] 路径穿越攻击被阻止
- [ ] workspace 外路径被拒绝
- [ ] 用户未操作时文件内容不变
- [ ] 插件卸载时所有 inline diff 渲染被清理

---

## 11. 风险与注意事项

### 11.1 IntelliJ Editor API 线程模型

- 所有编辑器操作（渲染、高亮、inlay）**必须在 EDT（Event Dispatch Thread）上执行**
- `suspendCancellableCoroutine` 在 IO 线程上挂起，`invokeLater` 在 EDT 上渲染
- 文件写入必须通过 `WriteCommandAction` + `ApplicationManager.invokeAndWait`

### 11.2 Inlay API 兼容性

- `InlayModel.addBlockElement()` 在 IntelliJ 2023.3+ 可能有 API 变更
- 需要确认项目最低支持的 IntelliJ 版本
- 备选方案：如果 `BlockElement` API 不稳定，可使用 `RangeHighlighter` 在空白行渲染绿色高亮来模拟新增行

### 11.3 并发安全

- 同一文件可能同时有多个 `InlineDiffHandler`（多 tool_call 场景）
- 使用 `activeHandlers: ConcurrentHashMap<requestId, handler>` 管理
- 同文件的多个 handler 串行排队（`FileWriteLock` 已保证写入串行）

### 11.4 EditorNotificationPanel 限制

- `EditorNotificationPanel` 是全宽顶部通知栏，不能定位到具体变更行
- 如果需要行级操作按钮，备选方案：
  - 方案 A：使用 `GutterIconRenderer` 在变更区域左侧 gutter 显示 Accept/Reject 图标
  - 方案 B：使用自定义 `Inlay` 渲染浮动按钮
- 第一版使用 `EditorNotificationPanel`（简单可靠），后续迭代可优化为行级操作
