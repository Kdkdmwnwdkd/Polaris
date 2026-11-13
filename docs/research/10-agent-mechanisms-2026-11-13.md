# 10 · 开源 Agent 机制库（2026-11-13）

> 目的：为「加功能」提供一份可直接查阅的机制清单。记录的是**别人怎么做到的**与**我们该怎么写**，不是移植清单。
> 标注约定：**[码]** = clone 后读过源码；**[档]** = 读过官方文档/实现记录；**[述]** = 只读 README，未验证实现。
> 许可证：本文所涉项目多为 MIT/Apache-2.0；`whalegirl-pet` 依赖 Live2D Cubism Core（专有，个人/小微免费），`Operit` 为 LGPL-3.0。**只吸收机制，实现全部重写。**

---

## 0. 扫描方法与筛选口径

| 渠道 | 做法 | 结果 |
|---|---|---|
| GitHub Search API | 6 组关键词 × 星数分段 | 未认证 10 req/min，第 4 次即被限流 |
| 搜索引擎（`site:github.com`） | 中英文关键词 | **主力来源**，绕开 API 限流 |
| GitLab API | `projects?search=` | 20 条，星数普遍个位数，1 条有用 |
| Gitee / Codeberg | 公开 API | 分别返回空 / 封 IP，放弃 |
| HuggingFace | Space 与模型页 | 拿到桌宠自动绑骨流水线的关键一环 |

筛选口径：**必须至少有一个我没在别处见过的机制**；只有功能列表、实现全是常规做法的一律丢弃。

---

## A. 语音：能"喊一声就应"靠什么

### A.1 三条唤醒路线，成本差 10 倍

| 路线 | 代表 | 模型体积 | 认人 | 抗噪 | 工程量 |
|---|---|---|---|---|---|
| 声纹模板 + DTW 比对 | Operit **[码]** | 0（只存用户录音特征） | 强 | 弱 | 中 |
| 开源 KWS 模型 | sherpa-onnx | 1~3 MB/词 | 弱 | 中 | 低 |
| 自训练唤醒词头 | dreamlog **[档]** | 小（分类头） | 中 | 中 | 高（要训练管线） |

**Operit 的做法（最反直觉的一条）**：不训练任何模型。录 3~5 遍唤醒词存成 MFCC 特征序列，运行时对每段语音做 DTW 相似度比对。

防误触靠一组复合判据（源码 debug 日志里全暴露）：

```
best 分数 / secondBest 分数 / 两者 gap / 连续命中 hits/requiredHits
/ 段内 RMS 音量 / 语音时长 / 动态阈值 / 段内自相似度(intraMin/Mean/Std)
```

即：不只看"像不像"，还要看"像的程度是否显著高于第二候选""段内是否稳定（不是噪音串）""音量与时长够不够"。

前面还有一道 VAD（Silero，几十 KB ONNX）先切人声段，否则 DTW 会对空调声持续计算。

**dreamlog 的做法 [档]**：用 `livekit/livekit-wakeword` 训练两个分类头，训练数据是**合成语音 + 噪声**，本人录音只做评估不进训练集。配套 sherpa-onnx 只保留 arm64 的 AAR，并复用其自带的 `libonnxruntime.so`，避免两份 native 运行时。

### A.2 真实功耗数据（唯一一份硬数字）

来自 `Fartown/Movo` 的实测记录（电视端语音 App）：

| 指标 | 实测 |
|---|---|
| sherpa-onnx KWS 模型加载 | 4659 ms |
| 5 秒静音推理 | 15 步 / 2062 ms / 线程 CPU 2058 ms |
| 常驻 PSS | 64.5 MB |

**结论：唤醒不能默认开。** 常驻 64 MB + 冷启动 4.6 s，默认开等于给所有用户装负担。

### A.3 麦克风仲裁（Operit 最工程化的一段）**[码]**

```kotlin
shouldListen = enabled
    && !suspendedForIme                 // 输入法在抢麦克风
    && !suspendedForExternalRecording   // 别的 App 在录音
    && !suspendedForFloatingFullscreen  // 自己全屏浮窗在说话
```

- 外部录音检测：`AudioManager.registerAudioRecordingCallback`（API 29+），并用 uid 排除自己
- 所有入口走同一函数，`wakeStateApplyJob?.cancel()` + `Mutex` 保证只认最新状态
- 唤醒命中后移交识别器：`delay(180)` + `repeat(12){ startRecognition() }`，共约 2.1 s 硬等（系统 SpeechRecognizer 抢麦是异步的，抢不到只返回 false，无回调可等）

---

## B. 形象与桌宠

### B.1 三条渲染路线，工程量差 10 倍

| 路线 | 代表 | 画质 | 工程量 | APK 增量 | 判断 |
|---|---|---|---|---|---|
| 帧动画 / Lottie | 多数产品 | 中（不会真眨眼转头） | 极低 | < 2 MB | 备选 |
| **透明 WebView + Live2D** | whalegirl-pet **[码]** | **高（真 Cubism）** | 低（前端活） | ~5 MB（模型为主） | **首选** |
| 原生 OpenGL + Live2D | NyaDeskPet **[码]** | 高 | 高（C++/JNI/KMP） | 10 MB+ | 不推荐 |
| 三套原生引擎（DB/MMD/FBX） | Operit **[码]** | 高 | 极高（人月级） | 大 | 不做 |

关键事实：**WebView 与原生都是 Cubism Core 在算**，画质几乎一致，差别只在启动慢 100~300 ms、内存略高。

### B.2 5 MB APK 跑真 Live2D 的完整流水线 **[档]**

`7l-ui/whalegirl-pet` 的 `docs/真Live2D-实现记录.md`，全程无人工美术：

```
① AI 生成立绘（透明底 PNG）
② See-through（SIGGRAPH 2026，开源）→ 单图拆 15 层语义 PSD，自动补全被遮挡区域
③ PSD2Live（tsunehimatoi/psd2live）→ 自动绑骨 → .moc3 + model3.json + physics3.json + 动作
④ Android：透明 WebView + pixi-live2d-display + Cubism 4 Core
```

产出物：18 个标准参数、九轴头部形变、头发物理、真眼皮眨眼、idle/blink/nod/shake 四动作。

### B.3 Android 侧七个必踩的坑 **[码]**

| 坑 | 现象 | 解法 |
|---|---|---|
| 未设透明背景 | 黑块 | `setBackgroundColor(TRANSPARENT)` + `LAYER_TYPE_HARDWARE` |
| `file://` 页面 XHR 被禁 | 模型加载不出来 | `setAllowFileAccessFromFileURLs(true)` + `setAllowUniversalAccessFromFileURLs(true)` |
| aapt2 写反斜杠路径 | AssetManager 找不到 | 自写 zip 条目，强制 `/` |
| WebView 首次布局晚 | `innerWidth/Height` 不对 | 多次延迟 refit |
| pixi 的 `MODEL.width` 已含 scale | 适配重复相乘 | 先 `scale.set(1)` 量原始尺寸 |
| 无动作时每帧 `loadParameters()` | 手设参数活不过一帧 | 设完 `saveParameters()` 快照 |
| 调试看不到内部 | 无法排查 | `adb forward` + CDP，websocket 需 `suppress_origin` |

### B.4 桌宠状态机与"AI 驱动表情"

Operit **[码]** 的契约：`AvatarModel`（资产路径）→ `AvatarController`（`StateFlow<AvatarState>`）→ `AvatarRenderer`（Composable 工厂）。状态只有四项：

```kotlin
AvatarState(emotion, currentAnimation, isLooping, playbackNonce)
AvatarEmotion = IDLE / LISTENING / THINKING / HAPPY / SAD / CONFUSED / SURPRISED
```

表情驱动：从回复里抓 `<mood>happy</mood>` → 查表映射动画 → 用 `stripXmlLikeTags()` 从显示文本剥离；没标签则退回关键词匹配（"哈哈"→HAPPY）。

**它没做口型同步**（全仓无 lipSync）。这降低了难度：我们也不需要。

### B.5 回复打断仲裁（60 行，解决最易崩的体验）**[码]**

`NyaDeskPetAPP/agent/ResponseController.kt`：

```kotlin
data class ResponseSession(responseId, priority, hasActiveAudio)

fun shouldAccept(id, priority): Boolean {
    if (current == null) { current = ...; return true }
    return if (priority >= current.priority) { interruptCurrent(); current = ...; true }
           else { discardedIds += id; false }   // 迟到回复直接丢
}
fun interruptCurrent() { if (hasAudio) audioPlayer.stop(); dialogue.hide() }
```

多路回复（用户提问 / 主动搭话 / 定时提醒）必须仲裁，否则两句叠着说、说完还在放上一句音频。

### B.6 主动说话的三条判据 **[码]**

`Project-N-E-K-O/T.T.S` 的 `main_logic/core.py`：

```python
prepare_proactive_delivery(min_idle_secs = 30.0)
  ├─ 用户空闲 < 30 秒 → 不打扰
  ├─ 语音会话进行中   → 不打扰
  └─ 投递中用户动了   → "user active mid-delivery, aborting" 立刻中止
```

另有三套 prompt：正常 / 改写 / 读屏（`PROACTIVE_SCREEN_PROMPTS`）。

**它缺一条："今天已经主动过几次"（防骚扰配额）。** 我们要补。

---

## C. 扩展机制：怎么加功能而不把主流程搅烂

### C.1 Operit 的 13 个 hook 扩展点 **[码]**

`plugins/toolpkg/` 把 AI 流水线切成可插拔段：

| 扩展点 | 能力 |
|---|---|
| ChatInputHook | 改用户输入 |
| ChatViewHook | 往聊天界面插 UI |
| ChatMessageMenuItemHook | 消息长按菜单加按钮 |
| PromptHook | 动态改 system prompt |
| SummaryHook | 接管上下文压缩 |
| MessageProcessingHook | 拦截消息处理 |
| ToolLifecycleHook | 观察/干预工具执行前后 |
| AiProviderRegistry | 注册新模型提供商 |

**本质区别：工具只能"加能力"，hook 能"改行为"。**

### C.2 Operit ToolPkg 包格式 **[档]**

`.toolpkg` = ZIP：`manifest.json`（含 `api_version` / `requires`）+ `main.js` + `packages/*.js` + `ui/*/index.ui.js`（用 Compose DSL 在 JS 里写界面）+ `modules/*.wasm` + `resources/` + `i18n/`。

运行时是自编译 QuickJS（JNI）+ `QuickJsNativeCompatScriptBuilder` 兼容垫片（说明它踩过"不同包语法不一致"的坑）。

### C.3 我们的落点

不做 JS 沙箱、不做市场。只做两件事就能覆盖 80% 价值：

1. `Skill` 目录允许 `ui.js`（**声明式**，不执行任意代码）——给工具加配置/结果面板
2. 给 `RealAgentLoop` 加 3 个 hook 点：`beforeUserMessage` / `beforeToolCall` / `afterAssistantMessage`

钩子语义参考 DeepSeek Harness（见 [`11-deepseek-harness-2026-11-13.md`](11-deepseek-harness-2026-11-13.md)）：**拦截用 waterfall（可包装、可短路），观测用 parallel，审批用 bail。**

---

## D. 执行与安全

### D.1 工具执行流水线（含"单调守卫"）**[档]**

DeepSeek Harness 的设计（详见 11 号文档），核心链：

```
tool/call 落日志
 → tools/pre-execute 瀑布（hook / 权限 / 沙箱）
 → 单调守卫：只能 deny 或弃权，不能 allow（防止后置插件翻案前面插件的拒绝）
 → 审批（一次性，不可答 = 拒绝）
 → tools/execute 瀑布（超时/重试/指标，环绕分发）
 → 工具体 → 文件写意图门（先读后编辑）
 → tools/post-execute 瀑布（接受/阻断/替换/追加上下文）
 → finalizeContent（最后的内容不变式）→ tools/result（冻结的权威结果）
```

### D.2 给 AI 授权手机：SAS 八位码 + 承诺揭示 **[档]**

`nidhoggfgg/PhoneUse`，把手机变成 MCP Server：

```
1. client → POST /pair/request {commitment = hash(nonce)}      // 先不暴露
2. server → 202 {pairing_id, pairing_secret, server_nonce, expires_in:120}
3. client → POST /pair/reveal  {client_nonce}
4. 双方各自独立算出 8 位码 → 人眼比对 → 手机上点批准
5. → token，之后 pinned HTTPS + Bearer
```

文档里那句该写进我们的设计原则：

> **"A remote `approved` response alone is not evidence of a human comparison."**

配套：码与密钥永不进 URL、同源检查、Host 白名单（校验目标地址而非来源 IP）、请求体 64 KiB 上限、凭据可随时在手机端撤销。

### D.3 端上小模型的工具调用：约束解码 **[码]**

`ChenKuanSun/MobileClaw`：把工具 JSON Schema 编译成 grammar，**解码时逐 token 约束**，模型物理上打不出非法 JSON。这是端上 2B~4B 模型能可靠调工具的唯一可行方案（云模型可以靠 prompt 自觉，本地不行）。

同一项目的两个小设计也值得记：
- `SkillInstaller`：从 URL 下载 SKILL.md，**落盘前先安全扫描**
- `ChannelManager`：Telegram / SMS / 通知三通道远程控制 + request/approve 配对

---

## E. 记忆：两种成本完全不同的做法

| | Operit **[码]** | MobileClaw 的「做梦」 **[档]** |
|---|---|---|
| 存储 | ObjectBox 实体 + 关系 | 三个 Markdown 文件 |
| 写入 | 每条都调 LLM 抽实体/去重/合并/关联 | 规则打分，**不调 LLM** |
| 成本 | 每次存记忆多一次调用（钱 + 延迟） | 零额外调用 |
| 效果 | 结构化知识 | 人可读、可 diff |

「做梦」流水线（默认凌晨 3 点）：

```
MEMORY.md            长期事实（每会话加载）
daily/YYYY-MM-DD.md  当天流水（今天+昨天加载）
DREAMS.md            晋升日志（只追加）
.dreams/candidates.json  机器状态：强化计数

Light：去重短期信号，进候选池（不写长期）
REM  ：抽主题，写 DREAMS.md
Deep ：6 信号加权打分 → 过阈值才晋升
       频率 0.24 / 相关 0.30 / 查询多样性 0.15 / 新近 0.15 / 巩固 0.10 / 概念丰富度 0.06
```

**判断**：我们用户对延迟敏感，走「做梦」这条；不做每写一条调一次 LLM。

---

## F. 后台执行：让 AI 在"看不见的第二块屏幕"上干活 **[码]**

`kilolonion/agent-vscreen`，Shizuku（无 root）：

```
Shizuku UserService（shell uid）
 → DisplayManager.createVirtualDisplay(..., imageReader.surface, VIRTUAL_DISPLAY_FLAG_TRUSTED)
 → 因是 shell uid 创建，`am start --display <id>` 才能把 App 拉上去
 → ImageReader（RGBA_8888，3 缓冲）持续抓帧
 → 每次动作后返回屏幕 / 可见文本 / 可点元素 + 截图
 → 事件注入走 shell 的 input
```

配套：内置 PaddleOCR 端上离线读屏；`Watch`（盯屏等条件出现再通知 agent）；对外 CLI / MCP / HTTP 三套接口。

**意义**：AI 干活不占用户的屏幕。比"抢屏自己点"高一个时代。

---

## G. 我们的实现（按优先级）

| 优先级 | 能力 | 依赖 | 成本 | 备注 |
|---|---|---|---|---|
| P1 | 桌宠悬浮窗 + 透明 WebView + 状态机 | `SYSTEM_ALERT_WINDOW`（已声明未实现） | 2~3 天 | 先 Lottie 占位，模型后接 |
| P1 | 回复打断仲裁 | 无 | 半天 | 60 行，先做 |
| P1 | 麦克风仲裁 `MicClaim` | API 29+ 回调 | 1 天 | 换成 `StateFlow` + `collectLatest` |
| P2 | Live2D 接入（透明 WebView） | 模型资产 | 3~5 天 | 见 B.2 流水线 |
| P2 | 3 个 hook 点 | 无 | 1 天 | 语义照 D.1 |
| P3 | 主动说话（含配额） | 桌宠 + 调度 | 2 天 | 三判据 + 每日次数上限 |
| P3 | 记忆"做梦" | 文件存储 | 3 天 | 纯规则，不调 LLM |
| P4 | 虚拟屏幕后台执行 | Shizuku | 1~2 周 | 独立工程，单独立项 |

**明确不做**：三套骨骼引擎、QuickJS 沙箱、插件市场、可视化工作流编辑器（Operit 的触发器 6 种只实现了 2 种，说明这条路它自己也没走通）。

---

## 附：源码/文档索引

| 结论 | 位置 |
|---|---|
| 麦克风四条件状态机 | `Operit/api/chat/AIForegroundService.kt:544-570` |
| DTW 唤醒循环 | `Operit/api/speech/PersonalWakeListener.kt:54-160` |
| 悬浮窗挂载 | `Operit/services/floating/FloatingWindowManager.kt` |
| 情绪 → 表情 | `Operit/ui/floating/ui/pet/AvatarEmotionManager.kt` |
| 13 个 hook | `Operit/plugins/toolpkg/*.kt` |
| 透明 WebView | `whalegirl-pet/source/java/.../Live2DView.java` |
| Live2D 流水线与七坑 | `whalegirl-pet/docs/真Live2D-实现记录.md` |
| 打断仲裁 | `NyaDeskPetAPP/.../agent/ResponseController.kt` |
| 主动说话判据 | `T.T.S/main_logic/core.py:1962` |
| SAS 配对协议 | `PhoneUse/docs/api.md` |
| 虚拟屏幕 | `agent-vscreen/app/.../core/VScreenDisplay.kt` |
| 做梦流水线 | `MobileClaw/docs/memory-dreaming.md` |
