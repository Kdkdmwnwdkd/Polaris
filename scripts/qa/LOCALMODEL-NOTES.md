# Phase 2 · DoD④「本地模型工具暴露降级」实施说明（LOCALMODEL-NOTES）

> 本批独立快照，等推送（已有 8 批排队）。本文件为工程/裁决留档，随代码走。

## 1. 需求回放（路线图 Phase 2 DoD ④）

`local-llama` 下可见工具 ≤7 且能完成一次 `read_file`。问题：项目现有 **25** 个工具
（http/read/write/clipboard/通知 + ui_* 8 + device_* 2 + memory_* 4 + shell_* 2 + app_* 3 + use_skill），
云端大模型吃得下，但**手机上跑的本地小模型（llama.cpp，context 小、instruction following 弱）
会被工具表挤爆**：要么超上下文，要么乱选工具。参照思路（net 室重写，不抄码）：Operit / AiCode
均按执行环境裁剪工具面——本地小模型只给最小必要工具集。

## 2. 文件清单

| 文件 | 类型 | 说明 |
|---|---|---|
| `core/src/main/java/com/beichen/polaris/core/ToolExposurePolicy.kt` | 新增 | 中心化优先级表 + `ToolExposure` 快照 + 纯函数 `exposureFor(...)` |
| `core/src/main/java/com/beichen/polaris/core/agent/RealAgentLoop.kt` | 改造 | 回合级按 provider 裁剪：`runTurn` 算 exposure / 回合级工具视图；`chatOnce` 发 ≤7 工具；`buildSystemPrompt(tools)` 同步裁剪片段；`logExposure` 打 INFO；新增 `forceFullTools` 开关 |
| `core/src/main/java/com/beichen/polaris/core/AgentLoop.kt` | 改造 | 适配层透传 `forceFullTools`（构造参数，默认 false） |
| `scripts/qa/LocalModelVerification.kt` | 新增 | 19 项行为验证 harness |
| `scripts/qa/run-localmodel-verification.sh` | 新增 | 离线编译 + 运行（形态学 run-retry-verification.sh） |
| `scripts/qa/LOCALMODEL-NOTES.md` | 新增 | 本文件 |

**零新增外部依赖；不新增 JUnit；未改 ChatViewModel**（registry 装配不变；开关默认 false = 按 provider 自动）。

## 3. 关键裁决

### 3.1 工具分级：中心化优先级表（而非在 AgentTool 上加字段）
两种实现都在契约层面「可测」，本任务二选一。选**中心化表**的理由：
1. 零侵入——25 个工具实现一个字不改（不引入跨文件 API 变更、不增回归面）；
2. 纯函数——策略完全确定性，harness 可直接断言；
3. 可评审——「哪些工具对本地小模型是核心」是产品决策，集中一处便于评审/调整；
4. 安全侧默认——**新增工具默认「非核心」**，需显式列入才暴露给本地模型，不会因实现者忘标注而悄悄挤进。
   （⚠️ 这是本表格带来的隐性纪律：日后新增核心工具必须回来改 `LOCAL_CORE_TOOLS`。）

### 3.2 核心集：恰好 7 个，按重要度降序（含取舍理由）
| # | 工具 | 为什么留 |
|---|---|---|
| 1 | `read_file` | 任务落地的基础信息通道；**DoD ④ 显式要求能完成一次 read_file** |
| 2 | `write_file` | 产出/落盘，与 read_file 构成「读写」最小闭环 |
| 3 | `http_request` | 联网取数：本地模型无内置知识，外部信息全靠它 |
| 4 | `ui_tree` | 手机自动化的「感知」基础：先看见屏幕结构，再决定下一步 |
| 5 | `ui_tap` | 手机自动化的「动作」基础：把决策变成一次真实点按（最小可执行动作） |
| 6 | `clipboard` | 跨应用搬运文本的最短路径（复制/粘贴一站式） |
| 7 | `use_skill` | 技能扩展入口：不列它，本地模型无法触达任何技能能力 |

**被裁掉的例子（为什么）**：
`ui_swipe`/`ui_long_press`/`ui_input`/`ui_key`/`ui_click`（`ui_tap` 已覆盖「最小点按」，其余属边际手势）；
`memory_*`（跨会话记忆依赖长上下文与稳定指令遵循，本地小模型装了也用不好）；
`shell_*`（高危且指令复杂：会话句柄/命令语法）；`app_*`（可由 ui_tree+ui_tap 间接达成）；
`send_notification`、`ui_screenshot`（本地模型多为纯文本、无视觉）。

> 顺序即「重要度降序」，也是本地暴露列表的顺序（而非注册序）——让本地小模型先看到最该用的工具。

### 3.3 裁剪语义：只裁「暴露给 LLM 的工具面」，同时收口「回合级工具视图」
- `ToolExposure.tools` = 发给 LLM 的 `tools` 参数，也是系统提示拼接 `systemPrompt` 的来源，
  **同源**保证「给的工具」与「教的工具」一致（不会出现「教了 ui_swipe 却没给」或反之）。
- **执行侧**：`scopedRegistry(exposure)` —— 未裁剪时**原样返回原 registry**（零开销、行为完全不变）；
  裁剪时新建仅含暴露工具的 registry。被裁工具在该视图内「查无此工具」→ descriptor 解析为 null →
  走既有 **T02 未知工具路径**（模糊匹配建议 + 可自纠 hint），**绝不抛异常**。
  这样即便本地模型瞎编调用被裁工具，也不会真的执行（harness 用例 4 断言 `executions==0`）。
- **每回合算一次**（`runTurn` 起点）：因系统提示是整个回合固定的（`messages[0]`），工具面必须与之绑定，
  故 exposure 回合作用域化。极端情形——用户在回合进行中切换 provider——本回合仍用旧 provider 的工具面，
  **下一回合**才生效；这与系统提示「回合内固定」的既有语义一致（原实现系统提示也是回合起点算一次）。

### 3.4 可配置开关
`forceFullTools`（默认 `false` = 按 provider 自动）：`RealAgentLoop` 与 `AgentLoop` 适配层构造参数。
置 `true` → 强制全量（开发者/设置项逃生门），本可裁剪的本地路径也放行全量。云端 provider 本就不裁剪，
不受该开关影响。默认路径下 ChatViewModel 零改动即「按 provider 自动」。

### 3.5 日志
裁剪发生时（`exposure.trimmed == true`）打一条 **INFO**，格式：
`按 provider 裁剪工具：provider=local-llama 暴露 7 个，裁剪掉 18 个（暴露：read_file,write_file,...）`
未裁剪（云端 / 强制全量 / 全量本就 ≤核心集）**不打**，避免每回合刷屏。日志经 `AgentLogger.sanitize` 无害化。

### 3.6 边界契约（被裁工具被调用）
必须走既有「未知工具」路径（T02 的 `unknownToolMessage` 模糊匹配 + 可自纠 hint），**不得抛异常**。
实现方式见 3.3（回合级工具视图）。未知工具文案里的「当前可用工具」列表也基于回合级视图，
**只列核心集**——不会再引导本地模型去调被裁工具。

## 4. 自检证据

- 编译：`/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks` → `BUILD SUCCESSFUL`（未新增依赖）。
- 新 harness：`scripts/qa/run-localmodel-verification.sh` → `PASS: 19 FAIL: 0 TOTAL: 19`。
- 回归十套（全绿）：run-verification 32 / run-t01-verification 35 / run-t01-probe 11 /
  run-t01-probe2 7 / run-appcontrol-verification 31 / run-t07t08-verification 35 /
  run-t07t08-adversarial 17 / run-markdown-verification 45 / run-branch-verification 20 /
  run-retry-verification 9。

## 5. 遗留与已知问题

- **run-t01-probe2 R9 用例本身是既有时序竞态**（与本次改动无关）：`onExecute` 一进入就 complete 门闩，
  测试线程「唤醒→stop()」与 loop 线程「批结束→shouldContinueNow()」存在天然竞态，偶发丢 stop。
  连续复跑 3 次均 `7 passed, 0 failed`；本次仅首跑命中一次。**待办：R9 用例可改门闩时机（tool 返回前 slee）**，
  属 QA 用例质量项，非本批产品代码缺陷，未在本批改动 harness（避免扩大改动面）。
- 核心集是产品决策，后续如需增删核心工具，只改 `ToolExposurePolicy.LOCAL_CORE_TOOLS` 一处 + 更新本 NOTES。
- `ui_tap` 在真实注册表带 `needsApproval=true`（L10 审批），本地核心集保留它符合「手机自动化最小动作」取舍；
  审批链路与暴露裁剪正交，未改动。
