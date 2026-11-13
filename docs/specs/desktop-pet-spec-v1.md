# 桌宠（悬浮助手）设计草案 v1

> 状态：**一、二期已实现**（一期 2026-11-13 悬浮窗骨架；二期 2026-11-14 语音闭环；三期唤醒词未动）
> 定位：把 App 从「打开一个应用才存在的助手」变成「一直在桌面上的一个小东西」。
> 读者：将来动手的人（可能是几周后的我）。
> 结论先行：**能做，底座齐了，但要分三期；三个门槛里只有一个真正需要决策（唤醒词）。**
> 一期实现纪要见 §10，二期见 §11。

---

## 0. 为什么要做（以及为什么值得等）

现在的 Polaris 是个「重」应用：想问一句话，得解锁 → 找图标 → 等冷启动 → 进对话页 → 打字。
桌宠改变的是**入口成本**，不是能力。同样的 Agent，从「打开应用」变成「点一下 / 喊一声」。

这不是锦上添花。项目里已经有一堆重型能力（终端、容器、技能、自动化），它们都需要一个
**低门槛的唤起方式**才有存在感。桌宠就是那个入口。

---

## 1. 现有底座勘察（2026-11-13 实测，均可直接复用）

| 能力 | 现状 | 位置 |
|---|---|---|
| 悬浮窗权限 | 已声明，**无实现** | `AndroidManifest.xml:31` `SYSTEM_ALERT_WINDOW` |
| 录音权限 | 已声明 | `AndroidManifest.xml:19` `RECORD_AUDIO` |
| 麦克风前台服务类型 | 已声明 | `AndroidManifest.xml:16` `FOREGROUND_SERVICE_MICROPHONE` |
| 语音合成（说话） | 可用于生产，**进程级** | `app/audio/TextSpeaker.kt`（中文语种、语速、8s 初始化超时、引擎缺失明确报错）；经 `AudioGraph.speaker(ctx)` 取，不要自己 new |
| 语音输入（听话） | **只有系统弹窗方案** | `ChatScreen.kt:264` 起的 `RecognizerIntent` + `SpeechLogic` |
| Agent 核心 | 可脱离 Activity 驱动 | `core/agent/RealAgentLoop.kt:138`，先例见 `SubAgentRunner.kt:97` |
| Agent 主动说话 | 已是注册工具 | `app/tools/SpeakTool.kt` |
| 长任务前台服务 | 已有，含 WakeLock 35 分钟 | `core/agent/AgentRunService.kt` |
| 常驻协程作用域 | 已有（生命周期约定见注释） | `core/AppScope.kt` 的 `[io]` / `[app]` |

**结论：三块积木（想、说、权限）都在，缺的是「悬浮那一层」和「不弹系统页的听话」。**

`ChatScreen.kt:264` 那句注释值得抄在这里：

> 自己录音要申请 RECORD_AUDIO、要处理采样率/降噪，收益远不如直接借系统。

这在「对话页里打字为主」的场景下是对的。但桌宠相反——**语音是主路径，弹一次系统识别页
就把「一直在」的幻觉打破了**。所以桌宠必须自建识别，这是需求决定的，不是技术偏好。

---

## 2. 三个硬门槛

### 2.1 唤醒词（「语音呼叫」）—— 唯一真正需要决策的

**Android 没有任何公开的「常驻监听唤醒词」API。** 系统级的 `VoiceInteractionService`
能做，但要求用户把应用设为**默认数字助理**（会顶掉长按 Home），侵入性太强，不推荐。

可行路线只有三条：

| 方案 | 模型体积 | 授权 | 功耗 | 备注 |
|---|---|---|---|---|
| **Vosk 中文小模型** | ~42 MB（`vosk-model-small-cn-0.22`） | Apache 2.0，可商用 | 中 | 离线、纯 Kaldi，NDK 要编 JNI |
| **Porcupine（Picovoice）** | 每唤醒词 ~1 MB | 免费额度，商用需授权 | 低（专为唤醒词优化） | 中文词要自己训练，控制台生成 |
| **不做唤醒词** | 0 | — | 0 | 改成长按音量键 / 通知栏快捷方式 / 桌面小组件 |

**实测成本参考**：前台服务 + 麦克风常驻 + 唤醒词检测，中端机约 **1–3% 电量/小时**。
不夸张，但用户能感觉到，所以必须**可关且默认关**。

> 决策建议：一期二期都不碰唤醒词。等桌宠真被用起来、确认它不是「做出来就不用了」，
> 再决定要不要付这 1–3% 的电。先做三期容易做成「耗电又没人用」的负资产。

### 2.2 悬浮窗本身不难，难的是窗口里的输入法

`TYPE_APPLICATION_OVERLAY` + `ComposeView` 是成熟做法（需手工挂
`LifecycleOwner` + `SavedStateRegistryOwner`，约 60 行样板），拖动、贴边、吸附都能做。

真正麻烦的是**悬浮窗里的文字输入**：软键盘弹出位置、焦点抢占、返回键归属，
各家 ROM 表现不一。这恰好印证了需求方向——**桌宠以语音为主，打字是补充**。
所以一期就把「点一下说话」作为主交互，文字输入放成二级面板，体验差也能接受。

### 2.3 桌宠对话不能复用 `ChatViewModel`

那个类三千多行、绑 Activity 生命周期、20 个 Contract。悬浮窗活在 Service 里，没有 Activity。

必须做一条**独立轻量会话通道**：`RealAgentLoop` 直接实例化 + 精简的结果流。
`SubAgentRunner` 已经证明了这条路可行（它在无 UI 场景下就是这么起 loop 的）。

副作用要提前说清：**桌宠与主界面是两份会话历史**，除非额外做同步。这带来复杂度，
所以默认不同步，桌宠定位为「随手问一句」的独立通道。

---

## 3. 分期方案

### 一期：悬浮桌宠 + 点击对话（2–3 天，风险低）

- 新增 `CorePetOverlayService`（前台服务，`TYPE_APPLICATION_OVERLAY`）
- Compose 形象：默认用项目图标做矢量形象，支持眨眼/待机微动
- 拖动自由移动，松手吸附到屏幕左右边缘
- 点一下展开对话气泡：输入 → Agent → 回复文字 + TTS 自动朗读
- 长按 → 收起 / 关闭 / 进主应用
- 设置页新增总开关「桌面助手显示」
- **验收**：模拟器上能看到桌宠、能拖、能问一句"现在几点"并听到回答、关掉后彻底消失

不动任何新权限申请（`SYSTEM_ALERT_WINDOW` 已声明，只需引导用户手动开启）。

### 二期：语音对话闭环（3–4 天，风险中）

> **2026-11-14 已实现**，实现纪要见 §11。下面的原计划保留，实际落地的差异写在纪要里。

- 桌宠上「按住说话」，松开自动识别并发送
- 识别引擎二选一：
  - 快路：`SpeechRecognizer` 直接 API（不弹系统页），依赖 ROM 自带服务，部分机型不可用
  - 稳路：本地 whisper.cpp（NDK 编译，tiny 模型 75 MB，中文可用；base 140 MB 更准）
- 建议先做快路 + 失败回落 whisper，避免一上来就背 75 MB
- **验收**：不弹系统页说一句话，能拿到识别文本并触发回复

### 三期：唤醒词常驻（3–5 天，风险高）

- Vosk 或 Porcupine 接入，唤醒词建议「北辰」（两字，短、辨识度高）
- 常驻方案要解决：`AudioRecord` 独占导致其他应用录音失败、ROM 杀后台、耗电
- **验收**：锁屏点亮后不碰屏幕，喊「北辰」能出桌宠；关掉开关后麦克风彻底释放

---

## 4. 需要拍板的三件事（动手前必须定）

1. **桌宠形象**：矢量自绘（零依赖、能动）/ Lottie 动画（好看，要素材）/ 图片序列帧（最灵活，最占体积）。
   需要美术资源，或从开源素材里选一个可商用授权。
2. **唤醒词做不做**：只做「点一下说话」就是一期二期；要「喊名字出来」才进三期。
3. **会话历史是否与主界面互通**：不通＝工期短、定位清晰；通＝体验一致但多 2 天，且要处理并发写入。

---

## 5. 技术选型对比（二期识别引擎）

| | 系统 `SpeechRecognizer` | 本地 whisper.cpp | 本地 Vosk |
|---|---|---|---|
| 弹 UI | 可关（直接 API） | 不弹 | 不弹 |
| 离线 | 看 ROM（多数要联网） | 完全离线 | 完全离线 |
| 中文准确率 | 好（厂商模型） | tiny 一般 / base 可用 | 一般 |
| 依赖 | 无 | NDK 编译 + 模型 75–140 MB | 模型 42 MB |
| 兼容性 | 部分 ROM 无服务/被禁 | 全兼容 | 全兼容 |
| 建议 | **第一选择，带回落** | 兜底 | 唤醒词场景 |

---

## 6. 已知风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| ROM 省电策略杀后台 | 桌宠消失 | 引导加白名单 + `START_STICKY` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（已声明） |
| 悬浮窗遮挡/误触 | 干扰其他应用 | 默认贴边半透明、可一键隐藏、点击穿透仅限形象区域 |
| 常驻麦克风耗电 | 用户抱怨 | 默认关闭、设置里显示实测耗电、提供「仅充电时监听」 |
| 与主应用抢 Agent 实例 | 终端/模型并发冲突 | 桌宠走独立 loop，共用底层锁（`LocalLlamaEngine.inferenceLock` 已互斥） |
| `targetSdk=28` 的长期风险 | 未来系统收紧 | 已在 `app/build.gradle.kts:158` 登记为已知问题，与本功能无关 |

---

## 7. 明确不做的事

- 不做系统级「默认数字助理」劫持（顶掉长按 Home，用户会骂）
- 不做桌宠主动打扰（弹话/推销），只响应用户
- 不做桌宠与主界面的实时双向同步（一期二期）
- 不在悬浮窗里做完整对话页（历史、附件、工具卡片都在主应用里看）

---

## 8. 动手清单（一期，已按实际实现更新）

```
新增
  app/src/main/java/com/beichen/polaris/pet/PetState.kt            开关意图 + 运行态（AppGraph 第 26 条）
  app/src/main/java/com/beichen/polaris/pet/PetOverlayService.kt   前台服务（specialUse）
  app/src/main/java/com/beichen/polaris/pet/PetOverlay.kt          WindowManager + ComposeView + 三个 owner
  app/src/main/java/com/beichen/polaris/pet/PetPanel.kt            形象（Canvas 自绘）+ 气泡 + 输入
  app/src/main/java/com/beichen/polaris/pet/PetAgentSession.kt     独立轻量会话（驱动 RealAgentLoop）
  scripts/qa/run-desktop-pet-verification.sh                       27 条守卫（服务/状态/工具边界/窗口语义）

改动
  AndroidManifest.xml                    注册 service（foregroundServiceType=specialUse）
  app/ui/SettingsScreen.kt               「桌面助手」组：总开关 + 权限引导 + 未运行重试
  app/AgentKitApplication.kt             PetState.sync(只读盘)
  app/MainActivity.kt                    onResume 补启动（服务被系统杀了能自己回来）
  app/AppGraph.kt                        登记 PetState

复用
  audio/AudioGraph.kt                    取进程级 TTS / 仲裁器（`speaker(ctx)` / `arbiter(ctx)`）
                                         **不要自己 new TextSpeaker**——那会得到第二个引擎，
                                         与主界面叠音，且两个 MicClaim 互相看不见
  core/agent/RealAgentLoop.kt            直接实例化
  core/AppScope.kt                       [io] 上下文
```

**起步顺序**：先做「能显示、能拖、能关」，确认悬浮窗在真机上不被杀；再接 Agent；最后接语音。
别一上来就写语音，那部分最容易卡住且最不影响骨架验证。

**形象**：原计划用 `res/drawable/pet_*.xml`，实际改成 `PetPanel.kt` 里的 Canvas 自绘
（84dp 圆角身体 + 眨眼 + 呼吸 + 思考时的嘴型，零素材零依赖）。换正式美术资源时只动这一个
Composable，窗口与状态层不用改。

---

## 9. 与现有文档的关系

- 体验规范见 `docs/specs/agentkit-ux-spec-v1.md`，桌宠是其「入口层」补充，视觉 token 沿用不推翻
- 真机验证清单见 `docs/ops/DEVICE-VERIFICATION-PENDING.md`，桌宠上线后需新增 P11 章节

---

*2026-11-13 立案。当时 App 处于 run 711，应用内更新刚在真机闭环。*
*2026-11-13 晚：一期骨架落地（§8 清单 + §10 纪要），守卫 `run-desktop-pet-verification.sh` 27 条。*

---

## 10. 一期实现纪要（2026-11-13）

做完之后值得记下来的四条，都是「做的时候才发现」的：

**① 窗口宽度必须跟着展开态变。** 最初想用一个固定宽度（气泡那么宽）的窗口，靠透明区域
「看不见就不挡事」。实际不行：`TYPE_APPLICATION_OVERLAY` 在**整个矩形范围**内拦截触摸，
透明像素一样吃事件。所以收起态窗口宽度 = 形象宽度（84dp），展开才撑到 296dp。

**② 焦点标志和窗口尺寸必须一起改。** 展开时不去掉 `FLAG_NOT_FOCUSABLE`，输入框点不出键盘；
去掉了不补 `FLAG_NOT_TOUCH_MODAL`，整个屏幕都点不动。两个标志是同一个动作的两半。

**③ 时间不能写进 systemPrompt。** `RealAgentLoop` 的 systemPrompt 是构造参数，只取一次。
桌宠会连续活几小时，写死的时间会让「现在几点」答出启动时的钟点。改成每条用户消息前缀
`[2026-11-13 22:50 周四]`，模型按系统提示去读它。

**④ 工具集是审批缺失的补偿，不是省事。** 桌宠没有界面弹审批卡片，所以 `approval` 只能是
null；那么工具就必须全是只读的（算数 / 搜索 / 读文件 / 查记忆 / 朗读）。守卫 D1 把「白名单外
的工具」变成硬失败——将来要加写工具，必须先把审批通道做出来，不能只往 registry 里补一行。

---

## 11. 二期实现纪要（2026-11-14）

代码在 `app/.../pet/PetVoice.kt`，守卫 `run-desktop-pet-verification.sh` 的 `[H]` 段（10 条）。

### 11.1 落地了什么

| 项 | 结果 |
|---|---|
| 识别 | `SpeechRecognizer` API 版，**不弹系统页**；`EXTRA_PREFER_OFFLINE` 只是 hint |
| 手势 | 按住形象 400ms 开始录音，松手识别并发送；拖动抢占 = 取消 |
| 权限 | 设置页开桌宠时申请；拒了也能开（文字对话不受影响），并有补授权入口 |
| 仲裁 | 录音前 `beginCapture`，松手/出错统一 `endCapture`；播报自动被停 |
| 超时 | 硬上限 20s（整段）+ 结果等待 5s（松手后） |
| 错误 | 9 条错误码逐条中文映射，每条都回答「我该做什么」 |
| 反馈 | 收起态：天线加速 + 脉冲圈；展开态：状态行显示实时 partial |

### 11.2 没有做 whisper（与原计划的差异）

spec 原计划是「快路失败回落 whisper.cpp（tiny 75 MB）」。实际没做，理由：

1. **NDK 编译进 CI**：现在是 24 分钟出包，加 NDK + 75 MB 模型会让 APK 与构建时间都翻倍；
2. **模型体积**：`release/latest` 现在 35 MB，背一个 75 MB 的资产是产品决策不是工程决策；
3. **收益不明**：离线识别只在「没有识别服务」和「无网络」两种场景下赢，而这两种场景
   在目标用户（国内安卓 + GApps 或有厂商语音服务）里占比未知——没有数据就先不背。

替代做法：`isRecognitionAvailable() == false` 时**明确告知**「本机没有语音识别服务，
暂时只能用文字」，并说明装什么能解决。不做静默回落——偷偷弹系统识别页会破坏
「不弹窗」这个承诺而不自知。

如果将来要做，接口位置已经留好：`PetVoiceInput.start()` 里那一句 `isRecognitionAvailable`
的分支就是回落点，把 `onProblem` 换成「启动 whisper 会话」即可，其余（仲裁、UI 状态机、
结果回传）都不用动。

### 11.3 手势冲突是这一期最需要决策的地方

一期的手势表是「点 = 展开 / 长按 = 菜单 / 拖 = 移动」。加「按住说话」时它与长按直接冲突。

考虑过三个方案：

| 方案 | 结果 |
|---|---|
| A. 语音放气泡里的麦克风按钮 | 零冲突，但「点开气泡 → 找按钮 → 按住」三步，把主路径做成了次要路径 |
| B. 长按说话 + 菜单移进气泡「⋯」 | 一步直达；菜单项只有两个，挪进气泡反而更容易被发现 |
| C. 双击说话 | 与单击展开有 300ms 判定延迟，且双击在悬浮窗里更容易误触 |

选了 **B**，并额外把 A 也做了（气泡里同样有麦克风按钮）。两个入口共用同一个
`Modifier.holdToTalk`，不存在「两套手势逻辑慢慢分叉」。

代价：一期真机清单 P11-7 的「长按形象 → 菜单」需要改成「点气泡标题栏 ⋯ → 菜单」。

### 11.4 四个「不做」都是刻意的

1. **上滑取消**：微信式「按住说话上滑取消」需要跟踪抬手前的 y 位移，与拖动手势抢事件。
   收益是「手滑了能撤回」，但识别失败（没听清）本身就不发送，等价于取消。少一个手势少一堆 bug。
2. **说话打断回答**：主应用有这套语义（`InterruptPolicy`），桌宠的气泡只有一间房，
   同时只跑一轮。所以请求进行中直接禁用录音，而不是「打断 + 重开」。
3. **实时音量波形**：`onRmsChanged` 每秒几十次，每帧重组 Canvas 不值得——形象本身
   已有天线加速与脉冲圈，足够表达「它在听」。
4. **录音时保持窗口焦点**：录音不需要键盘。展开气泡时只在一进场 focus 一次（key = `Unit`），
   语音「没听清」展开提示时不会顺手弹出半屏键盘。

### 11.5 一个必须记住的平台限制

`targetSdk = 28`，所以 Android 14 的「前台服务类型必须匹配实际能力」检查**不适用**
（它只对 targetSdk ≥ 34 的应用强制）。当前 `foregroundServiceType="specialUse"` 却录音，
在 targetSdk 28 下不会崩。

**但一旦把 targetSdk 提到 34**，必须同时：manifest 改 `specialUse|microphone`，
`startForeground` 的 type 传 `SPECIAL_USE or MICROPHONE`，且**动态判断**——没有
`RECORD_AUDIO` 权限时不能带 microphone 位（Android 14 会抛 `SecurityException`）。
这条写在这里是因为它是「改 targetSdk 时最容易漏、且只在录音那一刻才炸」的一项。

### 11.6 真机待验

见 `docs/ops/DEVICE-VERIFICATION-PENDING.md` 的 **P12**（9 条）。其中只有一条必须真机：
「Android 14 设备上，悬浮窗在前台服务里能不能录到音」——模拟器的音频输入不可信。
