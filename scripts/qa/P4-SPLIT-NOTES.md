# P4 结构拆分 · 所有权地图

> 这份笔记回答一个问题：**某个改动该落在哪个文件？**
> 拆分的目的是让答案唯一，而不是把行数摊平。

## 拆分前后

| | 拆前 | 拆后 |
|---|---|---|
| `ChatViewModel.kt` | 4363 行，混着生成控制 / 消息投影 / 本地模型 / 备份 | 3914 行（守卫上限同步从 4363 降到 3914） |
| 新增 | — | `ModelSettings` 260 / `ConversationProjection` 130 / `BackupUseCase` 195 / `GenerationRunController` 160 |
| `ChatViewModel.kt`（3.1 第一批，2026-11-07） | 3868 行，仍混着模型列表拉取 / 插件市场 / Key 池 | 3578 行（守卫上限同步从 3868 降到 3578） |
| 新增（3.1 第一批） | — | `ModelListFacade` 124 / `MarketFacade` 279 / `KeyPoolFacade` 75 |
| `ChatViewModel.kt`（3.1 第二批，2026-11-08） | 3578 行，仍混着运行日志 / 记忆库 / 定时任务 | 3540 行（守卫上限同步从 3578 降到 3540） |
| 新增（3.1 第二批） | — | `LogsFacade` 38 / `MemoryFacade` 66 / `WorkflowFacade` 75 |
| `ChatViewModel.kt`（3.1 第三批，2026-11-08） | 3540 行，仍混着设置开关 / 聊天外观 | 3527 行（守卫上限同步从 3540 降到 3527） |
| 新增（3.1 第三批） | — | `SettingsFacade` 70 / `AppearanceFacade` 38 |
| `ChatViewModel.kt`（3.1 第四批，2026-11-08） | 3527 行，仍混着权限探测 / 调试通道 / 能力就绪度 | 3439 行（守卫上限同步从 3527 降到 3439） |
| 新增（3.1 第四批） | — | `PermissionFacade` 200 / `CapabilityReadiness` 39 |
| `ChatViewModel.kt`（3.1 第五批，2026-11-20） | 3723 行（**已顶格**，加一行就红），仍混着数据契约 / 工具组开关 / 测试连接 | 3413 行（守卫上限同步从 3723 降到 3413） |
| 新增（3.1 第五批） | — | `ChatUiTypes` 103 / `ToolGroupsFacade` 166 / `ConnectionProbe` 169 |
| `ChatViewModel.kt`（3.1 第六批，2026-11-20） | 3413 行，工具装配仍内联（52 个 `register(...)` + 20 项依赖接线），桌宠另写一份 5 个的迷你版 | 3253 行（守卫上限同步从 3413 降到 3253） |
| 新增（3.1 第六批） | — | `tools/ToolAssembler` 295（含 `ToolDeps`、桌宠分支、技能二阶段装配）；`ConfirmRequiredTool` 从 `pet/` 迁到 `tools/` |

## 谁拥有什么

| 问题 | 归谁 | 判据 |
|---|---|---|
| 谁在跑？跑到哪了？草稿攒了多少？ | `GenerationRunController` | 所有与「一轮」有关的状态。VM 只读它的流，写只经 `start()` |
| 「选择模型」弹窗的列表 / 收藏 / 拉取错误 | `ModelListFacade` | 只依赖 configStore + 一个协程作用域，与生成链零交集 |
| 市场源 / 插件列表 / 装 / 卸 | `MarketFacade` | 对 VM 的全部需求是三个注入点：已装列表、装/卸后刷技能快照（回调）、作用域 |
| 备用 Key 存取 / 灌进 ProviderManager | `KeyPoolFacade` | 与生成链唯一接触面是 providerManager 的两个字段，构造注入看得见 |
| 运行日志卡片展示 / 清空 / 导出 | `LogsFacade` | 零依赖：只读写 AgentLogger 的内存缓冲，188 个成员里依赖最少的一域 |
| 记忆列表 / 增删改查 / 检索 | `MemoryFacade` | store 本体留 VM（四个 Memory*Tool + prompt 注入共用），facade 持引用 |
| 定时任务增删改查 / 立即运行 | `WorkflowFacade` | store 本体留 VM（四个 Workflow*Tool 共用）；「送进对话」经 isRunning/runPrompt 两回调注入 |
| 自动化 / 通知 / 计划模式 / 任务模型四个开关 | `SettingsFacade` | 只做「一个状态 + 一次落盘」；读点在工具注册 / 生成链，写点全在 facade。能力重算仍由 VM 转发层调 |
| 聊天背景图 URI + 浓度 | `AppearanceFacade` | 只依赖 configStore；位图不落盘（SAF URI），浓度 clamp 在 store |
| 通知 / 使用情况 / 电池白名单 / Shizuku 三态 / 设备上下文 | `PermissionFacade` | 全是「探测一次系统状态 → 写一条流」；写点全在 facade，读点在权限页 / 控制台 / 就绪卡片。状态变化后的能力重算由 VM 转发层显式调（facade 不认识就绪度） |
| 每条能力走哪条路径、降级到哪、最后提示什么 | `CapabilityReadiness` | 只做「快照 → 裁决」；两份 Android 侧快照（shell / 屏幕）由宿主注入，探测不属于这里 |
| 这条消息上不上屏？写进哪个会话？ | `ConversationProjection` | 显示态与持久态必须同源；落库目标 = owner ?: active |
| 本地模型参数 / 文件 / 下载 / GPU 结论 | `ModelSettings` | 只与 `LocalLlamaSetting` + `GgufModelStore` 打交道，唯一外部动作是「切到本地供应商」 |
| 备份包怎么组装、恢复后谁来刷新 UI | `BackupUseCase` | 六个 store 的快照 ↔ SAF 文件；UI 重载通过 `onRestored` 回调回宿主 |
| 工具属于哪个能力组、暴露面怎么裁、工具表按什么顺序排 | `CapabilityManifest` + `KernelExposure` | 分组 / 成员 / **顺序**都在清单（P3-5）；裁剪算法仍在 KernelExposure（它只读清单） |
| 「本任务开过哪些组」的落盘 / 设置页行 / 用户点击增删 | `ToolGroupsFacade` | 集合实例**在 VM**（loop 的 `activeGroups` 要同一个对象），facade 只借它干活 |
| 这次配置通不通、端点长什么样、失败归哪一类 | `ConnectionProbe` | 只依赖 ProviderManager + 本地模型名，产物是一条 `TestResult` |
| 模型**能用哪些工具**：注册清单、依赖接线、桌宠的能力边界 | `tools/ToolAssembler` | 装配单点。ui / pet 只持有结果，不再决定清单（守卫 [H]）；`register(` 在 ui/、pet/ 目录下为零 |
| 审批弹窗 / 测试卡片 / 附件回执的形状 | `ChatUiTypes` | 六个零逻辑类型；谁都能用，谁都不该再往 ViewModel 里塞新类型 |

## 第六批：工具装配收口的边界

1. **装配器不许造依赖**。`ToolDeps` 的每一项都指向宿主已有的对象；装配器自己 `new` 一个
   （记忆库、终端池、审批回调），主界面与桌宠就会各有一份，写入互相看不见。
2. **会变的依赖必须是函数**（`activeConversationId` / `planModeEnabled` / `llmProvider` /
   `approval`）。快照的失效是静默的：todo 会永远写进启动时那个会话，不报错。
3. **桌宠的能力边界写在同一处**（`ToolAssembler.pet`）。给它复用 `assemble()` 就等于
   给悬浮球 52 个工具（含 shell / 写文件 / 装应用），而它没有界面让人确认。

## 第五批的两条新不变量（守卫 [G] 钉住）

1. **共享集合靠注入**：`ToolGroupsFacade` 不许 `mutableSetOf()` / `ToolActivationStore.load()`。
   自己造一个集合的后果是「设置页显示已关闭，实际每轮仍把 ui 组发给模型」——不报错、不崩溃。
2. **写点唯一**：激活组落盘只剩 facade 一处，测试连接探测只剩 `ConnectionProbe` 一处。
   回到宿主里再写一份，两处顺序/口径迟早打架。

## 三条不变量（改动时别破坏）

1. **`generation.start()` 是唯一开场点**：`owner` / `generating` / 草稿 / `isRunning` 的置位与复位
   只在这里发生。别在别处写 `isRunning.value = ...`——那正是旧代码两套模板分叉的起点。
2. **`ConversationProjection.appendItem()` 是唯一追加点**，且读改写在宿主共享的 `msgLock` 内。
   工具事件最多 5 路并行，无锁拼接会静默丢卡。
3. **清单是真源**：加工具必须改 `CapabilityManifest.capabilities` 的某个组。
   `audit()` 的四条断言会在启动日志与 harness 里同时报警——这是唯一能发现「表与代码不同步」的地方。

## 为什么推翻了原来的「不拆」结论

`TurnRunner` 的旧注释写着：这些字段都是 ViewModel 的私有可变状态，独立类要么暴露 8 个 setter，
要么搬出去再引一堆回调。

那句话在**状态与编排混在一起**时成立。一旦把状态本身搬走，前提就不存在了：
状态有了唯一所有者，setter 一个都不用暴露，只剩一个「收尾时宿主该做什么」的回调。
结论没变的是「不要为了拆而拆」——所以 `send()` 的编排（拼上下文、落盘、出气泡）**留在 VM**，
搬走的只有状态机。

## 没搬的东西（有意为之）

- `draftStore` / `promptStore`：被 harness 断言锁在 ViewModel 内（架构方案 v1 的红线）。
- `send()` 的编排体：它读 `input` / 附件 / 设备上下文 / 工作区规则，搬走要暴露十几个成员，
  换来的只是文件更短。
- `KernelExposure` 的裁剪算法：它已经在读清单，再合并等于把两个职责塞进一个 object。

## 3.5 拆模块（2026-11-08）

3.1 拆的是文件，3.5 拆的是**编译单元**。前者让答案唯一，后者让答案**无法被绕过**：
文件可以被随手 import 回去，模块不能。

| | 拆前 | 拆后 |
|---|---|---|
| Gradle 模块 | `:app` 单模块 | `:core` + `:app` |
| 归属 | 228 个 .kt 全在一个编译单元 | `:core` 174 个文件 / 35,019 行（`core` + `skill` + `terminal` 三个包），`:app` 其余 |
| 分层约束 | arch guard 文本检查「core 不得 import ui/tools」 | Kotlin 编译器：`ui` / `tools` 在另一个模块，import 直接编译失败 |

**为什么是这三个包一起搬**：`core → skill`（ModelConfig / CapabilityRegistry）、
`core → terminal`（TerminalSession），同时 `skill/terminal → core`（AgentLogger /
ToolRegistry / AppScope）。双向引用意味着拆开任何一边都是循环依赖——它们本来就是一个整体。

**搬的两个显式代价**（都已处理，记在这里免得下次重新踩）：

1. `internal` 是模块级可见性。`:core` 独立后，`UiItem.toMsgRecord()` /
   `ConversationRecord.toUiItems()` 这两个被 app 调用的映射函数必须放宽为 `public`。
   其余 51 个 `internal` 声明经全量核对，没有被 app 引用。
2. harness 的 classpath 从 `app/build/tmp/kotlin-classes/debug` 变成 app + core 两个目录
   （`deps.sh` 的 `qa_classpath`）。同时 40 个 runner 的「缺产物 → SKIP」判断从
   `[ ! -d "$CLASSES" ]` 改为 `[ -z "$CLASSES" ]`——冒号拼接后的字符串不是目录。

**哪些判据跟着改了**（都只改扫描范围，断言条数不变）：
`arch guard`（行数与分层扩到 core 模块）、`startup-order`、`permission-guide`、
`appgraph`、`app-scope`、`r03`/`r05`/`r06`/`r07`/`r09`/`r13`/`r19`/`r20`、
`tool-surface`、`http-client-single-source`。改法统一是「扫描根从 app 扩到 app+core」。
