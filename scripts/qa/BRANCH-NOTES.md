# Phase 2 · DoD② 消息分支与重新生成 · 实现说明（Kou）

> 路线图 Phase 2 DoD ②：「重新生成 3 次可左右切换且**杀进程后仍在**」。
> 本批只写代码与自检，未推送、未触网；产物留在工作区（独立快照等推送）。

## 1. 模型裁定（按产品裁定实现，未造轮子）

采用 **轮次变体 + 游标**，而非完整树形分叉（对齐 `docs/research/refs-digest/rikkahub.md`
§3.2.2 / A2：`MessageNode(messages, selectIndex)`，「扁平 List + 游标 = 树形分支」）：

- 一轮 = `TurnRecord { user: MsgRecord, variants: List<MsgRecord>, selectedIndex: Int, trace: List<MsgRecord> }`
- 会话 = `List<TurnRecord>`（线性顺序）
- **重新生成** = 给当前最后一轮**追加**一个助手变体，游标指向它（旧变体保留、可切回）
- **左右切换** = 游标 ±1，同步把选中变体写回历史（下一次请求用的是选中变体）
- **从中间轮重新发问** = 截断该轮之后的轮次，再新开一轮（裁决见 §4）
- 完整树形（任意节点分叉）**留后续工单**（见 §6 遗留）

字段命名与原需求一致；`trace` 是**新增的兜底字段**（见 §3），不改变裁定语义。

## 2. 触碰文件清单

| 文件 | 行数 | 变更 |
| --- | --- | --- |
| `core/src/main/java/com/beichen/polaris/core/ConversationBranch.kt` | 157 | **新增**：`TurnRecord` + `ConversationBranch` 纯函数引擎（normalize / fromMessages / flatten / appendVariant / select / reaskAfter） |
| `core/src/main/java/com/beichen/polaris/core/ConversationStore.kt` | 506 | `ConversationRecord` 加 `turns`（默认空）；`appendMessage` 维护 turns + 展开 messages；`load()` 旧格式迁移；新增 `selectVariant` / `appendVariant` / `truncateAfter`；新增 `ConversationRecord.toUiItems()`；`ROLE_*` 改 internal |
| `core/src/main/java/com/beichen/polaris/core/AgentLoop.kt` | 126 | 适配层透传 `onTextDelta`（T01 流式打字机，默认 null 行为不变） |
| `app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt` | 1525 | `UiItem.User/Assistant` 加变体游标元数据；`_streamDraft` 流式草稿 + `handleTextDelta`；`regenerate` / `switchVariant` / `reaskFromTurn` / `reloadActiveMessages`；`send` finally 重载以补元数据 |
| `app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt` | 1328 | 助手气泡操作区 `← n/m →` + 「重新生成」；用户气泡「重新发问」入口 + 编辑对话框；流式草稿气泡 |
| `scripts/qa/BranchVerification.kt` | 427 | **新增** harness（20 断言，main + 手写断言） |
| `scripts/qa/run-branch-verification.sh` | 32 | **新增** 离线运行脚本（kotlin-compiler-embeddable + `-Xfriend-paths`） |

**零新增外部依赖**；**未新增 JUnit**；**未改** minSdk/targetSdk/abiFilters；注释/日志简体中文。

## 3. 持久化与向后兼容（关键）

落盘仍是 `conversations.json`，原子写（tmp + rename + .bak）不变。`ConversationRecord`
**同时保留 `messages`（旧字段）与新增 `turns`（默认空）**：

- **旧文件读入**：`turns` 缺省为空 → `load()` 用 `ConversationBranch.fromMessages(messages)` 迁移。
  迁移规则：遇 `user` 开一轮；该轮内**最后一条 assistant** 作为唯一变体（`selectedIndex=0`），
  其余（工具卡、被中途打断了内容的中间助手）按原序进 `trace`。→ 每个旧会话「变体数=1、游标=0」，
  且 `flatten(fromMessages(msgs)) == msgs`（良构旧历史**恒等**，不丢内容、不乱序）。
- **新文件写盘**：`messages` 写为 `flatten(turns)` 缓存，`turns` 写真实结构；旧版本 App 读新文件时
  靠 `ignoreUnknownKeys` 忽略 `turns`、照常读 `messages` 展示（**双向兼容**）。
- **幂等**：无分叉会话多次读写，`messages` 稳定不变；有分叉时 `messages` 恒等于
  `flatten(turns)`（store 每次分支操作后重算写回），反复读写幂等（harness ②⑤ 实测）。
- **异常兜底**：`variants` 为空 / `selectedIndex` 越界 → `normalize` 夹取，`flatten` 用 `getOrNull`
  兜底，绝不崩溃、绝不静默丢 user/trace。

## 4. 关键裁决（写进 KDoc）

1. **「从中间轮重新发问」= 保留该轮（含），截断其后所有轮，再新开一轮。**
   依据需求原文「截断该轮之后的 Turn，新开一轮」+ RikkaHub `regenerateAtMessage` 的
   `subList(0, indexAt+1)` 语义。实现：`ConversationBranch.reaskAfter`
   （`turns.take(turnIndex+1) + TurnRecord(user)`）与 `ConversationStore.truncateAfter`。
   备选语义（丢弃该轮本身、原地替换提问）**未采用**，因其与「截断该轮之后」字面不符；
   如产品要「编辑并重发」，`truncateAfter(turnIndex-1)` 一行即可切换（已在 KDoc 注明）。
2. **重新生成的落库粒度 = 完成后的整条变体**（流式只进 `_streamDraft` 草稿气泡，不进库）；
   与 T01「TextDelta 不进历史、进历史的只有 Completed 整条」一致，避免半截内容入历史。
3. **运行中守卫**：`regenerate` / `switchVariant` / `reaskFromTurn` 在 `isRunning` 时全部 no-op
   （UI 禁用是双保险），避免在途 Agent 事件写串会话/轮次。

## 5. 与既有能力的兼容

- T01 取消（stop）：`stop()` 未改；`send`/`regenerate` 均以协程取消收尾，取消路径重载 `_messages` 与历史一致。
- T05 树尾闭合 / T04 熔断：仅在 `AgentLoop`/`RealAgentLoop` 内部，未触碰。
- Markdown 渲染：助手气泡仍走 `MarkdownText`（流式草稿气泡亦复用同一 `MessageRow`）。
- 会话增删改切：`newConversation`/`switchConversation`/`deleteConversation`/`renameConversation` 未改；
  `load` 改由 `turns` 渲染，等价于原 `messages` 渲染（迁移后两者一致）。

## 6. 遗留 / 已知取舍（记录，非本批阻塞）

- **完整树形分叉**（任意节点分叉）未实现，留后续工单；本批 `Turn` 粒度足够覆盖 DoD②。
- **`trace` 为轮级共享**：一轮内的工具执行痕迹暂不区分变体。重新生成「纯文本回答」时
  `trace` 为空、无影响；若某变体触发工具，其后各变体的工具卡会共享同一 `trace`
  （显示为一组）。DoD② 场景（文本重生成）不受影响；按变体贴 trace 留后续优化。
- **`onTextDelta` 在重试时会重复下发**（继承 T01 既有取舍，见 `RealAgentLoop.chatOnce` KDoc），
  草稿气泡按追加渲染，可接受。

## 7. 自检证据

- 编译：`/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks` → `BUILD SUCCESSFUL`
  （仅历史既有 warning，无 error）。
- 新 harness：`scripts/qa/run-branch-verification.sh` → **20 passed, 0 failed**。
- 八套回归：见回传正文（全绿）。
