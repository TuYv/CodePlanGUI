# Phase2 Tools 重构 Brief

> **项目性质**：架构升级，非用户痛点驱动。
> 对端用户无感；收益在工程可维护性和后续能力扩展。

## 升级动因

1. **统一操作抽象**：phase2 起，所有插件能力对外呈现为 tool，代码库消除"命令 vs 非命令"双轨。
2. **为后续能力铺路**：IDE API 操作、MCP 外接、dry-run 预览——这些在 command-mode 下要么做不了、要么代价极高。
3. **权限/安全解耦**：现有权限机制与 shell 命令文本耦合；tools 架构允许按"执行器家族"独立策略。

## 现架构局限

- **command-mode 本质是 shell 路径**：解析用户/LLM 输入的命令字符串 → spawn 进程。非 shell 操作（IDE action、MCP 代理）无法纳入。
- **生命周期扁平**：validate / permission / execute / result 散落在命令处理链路中，没有可复用的分段抽象。
- **扩展点缺失**：想加 preview、想接 MCP、想让某类操作走 IDE API，都得改 command-mode 主干，影响面大。

## 新架构不变量

1. **Tool 接口与执行器解耦**：handler 可以是 shell、IDE action、MCP 代理，接口层不关心。
2. **权限正交于执行器**：每个 tool 自己声明权限策略（allow / ask / deny + 规则匹配），框架统一调度。
3. **Dry-run 是 lifecycle 阶段**：不是独立 tool 家族，是任意 tool 可选的"预览再执行"两段式。
4. **Tool 定义四字段最小集**：`{name, description, inputSchema, handler}` + 可选 `{validateInput, permission, preview, onProgress}`。
5. **没有隐藏执行路径**：任何对工作区、文件、shell、IDE 的写操作都必须走 Tool 抽象。

## Scope：必须覆盖

### 执行器家族（≥3）
- **Shell executor tool**：替代 command-mode 现有能力
- **IDE action tool**：至少一个（Find Usages / 文件跳转 / PSI 读取，任选一个做 MVP）
- **MCP proxy tool**：路由到用户配置的 MCP server，tool 名按 `mcp__{server}__{tool}` 命名

### 横切能力
- Permission 三态（allow / ask / deny）+ 规则匹配
- Dry-run / preview 钩子（至少一个 tool 真用上，不要只留接口）
- 进度回调（流式输出对应）

## Non-goals

- React/Ink UI 层（Claude Code 有，我们用 IntelliJ UI DSL）
- ML 安全分类器（手写规则即可）
- 跨进程 REPL、Worktree、Skill 加载器等 Claude Code 特色 tool
- 完整复制 Claude Code 25+ 内置 tool（按 phase2 需要加，不追求 parity）
- 向后兼容 command-mode 的用户配置/历史记录（一次性切换，老配置迁移策略另议）

## 迁移策略：并行实现 + 原子切换

```
phase2 分支生命周期
├─ [开发期] command-mode 保持可用（不动）
│           tools/ 新包独立开发，每个 tool 独立可测
├─ [cutover] 单一 PR 完成：
│             - UI 入口切到 tools
│             - 删除 command-mode 代码
│             - 配置/文档更新
└─ [合 master] phase2 → master → release
```

- 新代码落在 `com.github.codeplangui.tools` 包（或 phase2 决定的位置），与 command-mode 代码物理隔离
- cutover 之前，任何时候 phase2 分支都能构建、能运行、command-mode 能用
- cutover commit 独立成 PR，便于 review 和回滚

## 风险与对策

### 1. 接口第一版必错
**Why**：基于 2-3 个设想的 tool 设计接口，往往在第 4-5 个 tool 落地时暴露缺字段（例如 undo 粒度、跨 tool 编排）。越晚改成本越高。
**对策**：
- 开发前 2 周不要堆 tool 数量，先把 `BashTool` + 一个最简单的 IDE action tool 做出来，互相对拍接口
- 接口字段未被至少 2 个 tool 真实使用前，不加入公共抽象层

### 2. Cutover 阶段爆雷集中
**Why**：并行开发没暴露的问题（UI 绑定、消息协议、错误处理路径）会在接线瞬间全部冒出来。
**对策**：
- cutover 前做一份 end-to-end 冒烟测试清单（每个家族至少 1 条真实用例）
- cutover commit 本身独立 PR，与功能开发解耦

### 3. YAGNI / 过度抽象
**Why**：Claude Code 的 Tool 接口有 20+ 字段（7 必选 + 13 可选），完整抄会写很多用不到的代码。
**对策**：
- 初版 Kotlin 接口只含必要字段，其余按需加
- 参考清单记录在 `docs/phase2-tools-design-notes.md`（待开工前写），但实现时走最小化

### 4. 工期
**Why**：一次性切换意味着 MVP tool 清单全部就绪才能发布；任何一个 tool 卡住都会阻塞整个 phase2 发版。
**对策**：
- 锁定 MVP tool 清单（建议 3-5 个，覆盖 3 个家族即可），其余排到 phase2.1/phase2.2 增量发布
- 每个 MVP tool 在 milestone review 前明确 DoD（definition of done）

### 5. 未被问题驱动的抽象选型
**Why**：这是架构升级项目，最大的风险是"造了框架没人用"。
**对策**：
- 每个抽象决策（interface 新字段、新 lifecycle 钩子）必须能列举出 phase2 内至少一个落点
- 不能列举的，进 "post-phase2 考虑" 清单，不进 phase2 scope

## 成功标准（Definition of Done）

- [ ] `command-mode` 代码从仓库中完全删除，没有第二条操作执行路径
- [ ] 至少 3 个家族（shell / IDE action / MCP）各有一个可用 tool，覆盖真实使用场景
- [ ] Permission 层对 3 个家族一视同仁，不是 shell 专属补丁的延伸
- [ ] 至少一个 tool 真实使用了 dry-run / preview 能力（不是只挂接口）
- [ ] phase2 分支 CI 全绿；cutover PR 独立且 review 过
- [ ] 用户侧从 command-mode 到 tools 的切换不需要手动迁移配置（或有明确迁移文档）

## 下一步

1. 写 `docs/phase2-tools-design-notes.md`——把 Tool 接口的 Kotlin 签名、权限模型、生命周期钩子定下来
2. 确定 MVP tool 清单（每个家族选一个）
3. 在 phase2 分支上开第一个 milestone PR（接口骨架 + BashTool）

## 参考

- Claude Code v2.1.88 反编译源码：`~/SourceLib/fishNotExist/claude-code-source/`
  - `src/Tool.ts` — 完整 Tool 接口（20+ 字段）
  - `src/tools.ts` — 注册/装配/过滤
  - `src/tools/BashTool/` — 执行器 + 权限/安全参考
- Claw-code 元数据镜像：`~/SourceLib/fishNotExist/claw-code/src/reference_data/tools_snapshot.json`（仅作清单参考，无执行逻辑）
