# CodePlanGUI Roadmap

> 参考来源：当前 git 历史、README 迭代计划、jetbrains-cc-gui 功能集、claw-code 工作流设计

---

## 已完成 (Shipped)

基于 git log，以下功能已合并到 master：

| 功能 | PR/Commit |
|------|-----------|
| React 前端 + JCEF Bridge 基础架构 | `feat: add React frontend with streaming chat UI and JCEF bridge` |
| SSE 流式输出 + 错误处理 | PR #1 (fix/sse-error-handling) |
| 设置面板 — Provider 增删改测 | `feat: add settings UI with provider table` |
| Secure Key Storage (PasswordSafe) | README Feature |
| Ask AI 右键菜单 | README Feature |
| Commit Message 生成（staged diff） | `feat: add GenerateCommitMessageAction` |
| 主题变量重构 + 代码高亮 | `style: 重构顶部栏样式及代码高亮主题` |
| Session 持久化 + AI Memory 注入 | PR #2 (feat/session-persistence-and-memory) |
| Tool Call 状态机 + 审批对话框 | `feat(chat): add tool_call state machine and approval orchestration` |
| Command Execution Service（白名单 + 超时） | `feat(execution): add CommandExecutionService with whitelist and ProcessBuilder` |
| 取消流式输出 + ESC 快捷键 | PR #3 |
| Context-Aware 上下文注入（文件/选区，300行/12000字截断） | README Feature |

---

## Phase 1 — 稳定性与质量基线 🔨

**目标：现有功能零静默失败，错误清晰可区分**

> ✅ Phase 1 完成后即可提交 JetBrains Plugin Marketplace 上架审核

### 工程任务
- [ ] 统一 Bridge 生命周期事件：`ready` / `status` / `theme` / `context`
- [ ] 结构化错误分层：配置错误 vs 运行时错误 vs 网络错误
- [ ] 补充回归测试覆盖：Chat 流、选区流、Settings 持久化、Commit 生成
- [ ] WebView 冻结修复（参考 jetbrains-cc-gui #846：async clipboard 防止 Ctrl+C 卡死）
- [ ] 修复重载/主题切换/Provider 切换后的 UI 状态一致性

### 验收标准
- 主流程无静默失败
- 错误信息能区分「配置问题」和「运行时失败」
- IDE 重启后状态正常恢复

---

## Phase 2 — IDE 原生生产力 ⚡

**目标：让 AI 嵌入日常编码工作流，而非独立工具**

### 核心功能（必须完成）
- [ ] **Inline Completion（内联建议）** ⭐ 核心
  - 光标停留自动触发，Tab 接受，光标移动/ESC 取消
  - 编辑器事件监听 + debounce/cancel 流
  - 不阻塞打字，触发延迟可配置
- [ ] **一键代码插入**：Chat 回复 → 编辑器，支持 undo
- [ ] **Commit 范围优化**：根据选中文件/路径生成，而非全量 diff
- [ ] **快速切换 Provider**：工具栏下拉，不进设置

### 工程任务
- [ ] 编辑器事件监听 + debounce/cancel 流（Inline Completion 基础）
- [ ] 共享 context-summary pipeline（文件、选区、commit-diff）
- [ ] Action 入口复用：Tool Window / Editor Action / Commit UI 共用同一套

### 低优先级（可延后至 Phase 3 之后）
- [ ] 会话历史面板：列表、搜索、还原
- [ ] 会话收藏 & 标签
- [ ] 消息导出（Markdown / JSON）

### 验收标准
- Inline 建议出现自然，不阻塞打字节奏
- 代码插入操作可 undo，不破坏编辑器撤销栈
- Commit 生成反映实际选中范围

---

## Phase 3 — 安全行动面 + 数据洞察 🛡️

**目标：允许 AI 执行 IDE 操作的同时，给用户可观测的使用数据**

### 安全行动
- [ ] **结构化工具调用 UI**：工具名 + 参数预览卡片，取代原始 JSON
- [ ] **操作审批增强**：支持「始终允许」「本次允许」「拒绝并说明原因」
- [ ] **Permission Mode 对齐**：与 Claude Code CLI 的权限模式（auto / manual / plan）行为一致
- [ ] **执行状态时间线**：每步操作显示耗时
- [ ] **失败操作回滚建议**：失败时给出具体恢复路径，而非通用报错

### 多维度统计面板
- [ ] **Token 用量统计**：按 session / 按日 / 按 Provider 分维度展示
- [ ] **费用估算**：基于各 Provider 定价自动换算（可配置单价）
- [ ] **请求成功率**：成功 / 失败 / 取消 比例，定位不稳定 Provider
- [ ] **响应延迟分布**：P50 / P95 延迟，辅助 Provider 选型
- [ ] **功能使用频率**：Chat / Inline / Commit / Ask AI 各入口使用次数
- [ ] **数据导出**：统计数据支持导出 CSV

### 工程任务
- [ ] 对齐 Claude Code CLI 的 permission hooks 语义（参考 jetbrains-cc-gui #845）
- [ ] 工具调用渲染器：结构化 JSON → 可读卡片
- [ ] 执行日志持久化（供审计和统计）
- [ ] 统计数据本地存储模型（轻量 SQLite 或 JSON append-log）

### 验收标准
- 用户在审批前能看到「将执行什么」
- 失败时给出结构化错误而非 AI 代理的模糊描述
- 统计面板能在 IDE 重启后保留历史数据

---

## Phase 4 — Agent 与 MCP 扩展 🤖

**目标：支持长任务、多步工作流和外部工具集成**

### 核心差异化功能：异构多节点 Agent ⭐

> **本插件核心竞争力**：市面上其他工具（Cursor、Continue、jetbrains-cc-gui）即使支持多 agent 或子 agent，也强制所有节点使用同一家模型。本插件允许每个 agent 节点独立绑定不同的 Provider，从而实现「按任务性质分配模型」，大幅节省 token、降低费用。

**设计理念：**
- **主 Agent（Orchestrator）**：绑定高能力模型（如 Claude Opus），负责任务分解、结果综合、推理决策
- **子 Agent（Worker）**：按职责绑定轻量模型或本地模型（Ollama、Qwen、Gemma 等），负责执行无需深度思考的操作
  - 搜索 / 查询 / 过滤 → 本地小模型
  - 代码格式化 / 简单重命名 → 低成本 API 模型
  - 文档摘要 / 关键词提取 → 低价模型
  - 复杂推理 / 方案设计 → 高能力模型

**配置系统（类 claude.md / agent.md）：**
- [ ] 每个 agent 节点支持独立配置文件（如 `.codeplangui/agents/searcher.md`）
  - 定义该 agent 的 Provider、模型、system prompt、权限范围
  - 类似 superpowers skill 的身份定义：`role` / `capabilities` / `constraints`
- [ ] 项目级默认配置（`.codeplangui/config.md`）可被节点级配置覆盖
- [ ] IDE 内可视化配置编辑器：Agent 节点列表 + Provider 绑定面板

**用户可见功能：**
- [ ] **Agent 节点 Provider 独立绑定**：新建 agent 时选择专用 Provider，不继承主会话
- [ ] **Agent 身份模板**：内置常用角色模板（Searcher / Formatter / Summarizer / Reviewer）
- [ ] **任务路由可视化**：显示当前任务分发给了哪个 agent、用了哪个 Provider
- [ ] **费用对比**：主模型 vs 实际混合路由的费用节省估算

### 其他 Agent & MCP 功能
- [ ] **MCP Server 集成**：在设置中管理 MCP servers，AI 自动发现工具
- [ ] **Agent 模式**：多步任务规划 + 执行，支持中途中断
- [ ] **Slash Commands**（`/init` `/review` `/test` 等）
- [ ] **并行 Sub-agent**（参考 claw-code oh-my-codex 多 agent 并发执行模式）

### 工程任务
- [ ] Agent 节点抽象层：每个节点独立的 Provider 实例 + 配置上下文
- [ ] `.codeplangui/agents/*.md` 配置解析器
- [ ] Agent 调度器：任务类型识别 → 路由到对应节点
- [ ] MCP server 生命周期管理（启动、健康检查、降级模式）
- [ ] Agent session 结构化事件和状态模型

### 验收标准
- 同一次任务中，不同子任务可路由到不同 Provider，费用低于全程用主模型
- Agent 配置文件可被项目 git 追踪和共享
- 任务路由对用户透明可见，非黑盒

---

## Phase 5 — 生态扩展 🌐

**目标：扩大用户覆盖，融入更大开发者生态**

> 本插件通过 OpenAI-compatible 接口直连各家官方 API（Anthropic Claude、OpenAI / Codex、Google Gemini、本地 Ollama 等），无需包装任何 CLI 工具，本身即是引擎。Phase 5 聚焦生态扩展而非重复造轮子。

### 规划中
- [ ] **i18n 国际化**：英文/中文切换
- [ ] **Bundled Agent 模板库**：开箱即用的常用 agent 配置（Searcher / Reviewer / DocWriter 等），社区可贡献
- [ ] **配置共享市场**：`.codeplangui/agents/*.md` 模板可发布和订阅

---

## 设计原则

1. **每个 Phase 独立可交付** — Phase N 不依赖 Phase N+1 的实现
2. **不模拟执行** — 插件不伪造命令执行结果，AI 看到的是真实 IDE 状态
3. **用户始终掌控** — 所有非只读操作都有审批或撤销路径
4. **复用而非重建** — 新功能优先扩展现有 Bridge/Provider/Session 抽象
5. **可观测** — 失败有结构化错误，长任务有状态可查

---

## 参考项目

| 项目 | 参考点 |
|------|--------|
| [jetbrains-cc-gui](https://github.com/YourOrg/jetbrains-cc-gui) | Session 管理、双引擎、Slash Commands、Permission Mode、i18n、使用统计 |
| [claw-code](https://github.com/YourOrg/claw-code) | Agent 并发执行模型、Branch/Test Awareness、技能系统设计哲学 |
