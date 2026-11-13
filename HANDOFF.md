# 交接简报：从这一页开始

> 写于 **2026-11-13** ｜ 仓库 `Kdkdmwnwdkd/Polaris` ｜ **基线 = 本文档所在的那次提交**
> （不要记 SHA：文档一经更新 SHA 就变。用 `git log -1 --format=%h` 看当前，用 §1 的命令判断它是否健康。）
> 这份文档写给「接手的人或模型」，**不依赖任何聊天记录**。读完这一页就该能开工。

---

## 0. 三十秒版本

**北辰（Polaris）**是一个跑在 Android 手机上的本地 AI Agent：Kotlin + Jetpack Compose，
两个 Gradle 模块（`:core` 内核 / `:app` 界面装配），782 个提交，约 8.7 万行生产代码，
141 个 QA runner。原创实现，参考开源项目只读思路、不抄代码。

**刚做完的三件事**（都在 `main` 上）：

| 提交 | 内容 |
|---|---|
| `a259d09` | 三个可改写接缝 + 工具守卫链（`AgentSeams` / `ToolGuardChain`） |
| `a1e25e4` | 语音仲裁：麦克风占用（`MicClaim`）+ 打断裁决（`InterruptPolicy`） |
| `1939aa2` | 修两处 Android API 在 SDK stub 下的编译差异（CI 用） |

**当前状态**：全量 QA `PASS=103 SKIP=38 XFAIL=0 FAIL=0`，门禁 6/6 全过。

**下一步**：桌宠一期的**真机验证**（代码已落地，见 §3）。
之后是二期语音输入（`SpeechRecognizer` 快路 + 本地回落），三期唤醒词仍建议不做。

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

**判据**：`exit=0`、汇总行 `PASS=103 SKIP=38 XFAIL=0 FAIL=0`、`GATE PASS`。
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

## 3. 桌宠一期（已落地 2026-11-13，等真机验证）

**完整设计在 [`docs/specs/desktop-pet-spec-v1.md`](docs/specs/desktop-pet-spec-v1.md)，先读它，别重新设计。**
下面的「开工顺序」保留原文（它解释代码为什么长这样）；实际实现清单见 §3.3 的表，
做的时候发现的四个坑见 spec §10。

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
      app/.../pet/PetPanel.kt             形象（Canvas 自绘，无素材）+ 气泡 + 输入
      app/.../pet/PetAgentSession.kt      独立轻量会话（最小只读工具表）
      scripts/qa/run-desktop-pet-verification.sh   26 条守卫

改动  AndroidManifest.xml                 注册 service（foregroundServiceType=specialUse）
      app/ui/SettingsScreen.kt            「桌面助手」组：总开关 + 权限引导 + 未运行重试
      app/AgentKitApplication.kt          PetState.sync（只读盘，不启服务）
      app/MainActivity.kt                 onResume 补启动
      app/AppGraph.kt                     登记 PetState
复用  audio/AudioGraph.kt                 取进程级 TTS（`speaker(ctx)`）；**不要 new TextSpeaker**，
                                       否则桌宠与主界面各有一个引擎，叠音且仲裁失效
      core/agent/RealAgentLoop.kt         直接实例化
      core/AppScope.kt                    [io] 上下文
```

### 3.4 三条必须知道的约束

- **不要复用 `ChatViewModel`**：3625 行、绑 Activity 生命周期。屏幕侧虽然已经被 r14 收窄成
  30 个 `@Stable` 窄接口，但 ViewModel 本身仍活在 Activity 里；悬浮窗活在 Service 里，没有 Activity。
- **桌宠与主界面是两份独立会话历史**。一期不做同步（做同步要多 2 天，还要处理并发写）。
- **`SYSTEM_ALERT_WINDOW` 已声明但无实现**（`AndroidManifest.xml:31`），只需引导用户手动开启，不要新增权限申请。

### 3.5 三个拍板事项的结论（spec §4）

1. **形象**：已定——矢量自绘（`PetPanel.kt` 里 Canvas 画，84dp，零素材）。要换正式美术资源时
   只动那一个 Composable，窗口与状态层不用改。
2. **唤醒词**：一期二期都**不做**。常驻麦克风约 1–3% 电量/小时，先证明有人用。
3. **历史是否互通**：**不通**。桌宠定位「随手问一句」，历史只在内存里，进程死了就没。

---

## 4. 代码红线（碰了会立刻红）

| 红线 | 为什么 | 谁在守 |
|---|---|---|
| `:core` 不许 import `ui` / `tools` / Compose / `MainActivity` | 分层边界 | 编译器 + `run-arch-guard-verification.sh` |
| `arch-baseline.txt` **只减不增** | 加一条等于把已知违规合法化 | 同上。当前上限：`ChatViewModel` 3625、`ChatScreen` 3186、`RealAgentLoop` 1532 |
| `harness-baseline.txt` 只增不减（当前 **141**） | 数字变小说明有大面积 SKIP 被当成通过 | `gate.sh` 门禁 5/6 |
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

---

## 6. 未决事项

| 事项 | 状态 | 怎么办 |
|---|---|---|
| CI 最后一次构建结果 | **未确认**（沙箱到 `api.github.com` 时通时断） | 打开仓库 Actions 页看最新一次构建（应覆盖 `a1e25e4` / `1939aa2`） |
| 之前用过的 PAT | 待用户撤销 | 已从沙箱删除，仍需在 GitHub 设置里 revoke |
| 桌宠形象 / 唤醒词 / 历史互通 | 待拍板 | 见 §3.5 |
| 桌宠真机验证 | 未做 | 上线后在 `docs/ops/DEVICE-VERIFICATION-PENDING.md` 新增 P11 章节 |

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
