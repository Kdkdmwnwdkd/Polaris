# 待真机验证清单

> 规则：**服务器上能验的一律在服务器验掉**（源码断言 / JVM harness / 编译产物反验 / 静态扫描）。
> 只把「必须真实进程、真实生命周期」的项攒到这里，最后一次性验完，不逐项催。

## 当前还剩什么（2026-11-12 晚更新）

| 项 | 状态 | 说明 |
|---|---|---|
| **P7** 发布包不可调试 | ✅ **已在模拟器验完** | `run-as` 报 `not debuggable`，`flags` 无 `DEBUGGABLE`；CI 包 `aapt2` 也确认未声明该属性 |
| **P8** 长命令不被 30 秒掐断 | ✅ **已在模拟器验完** | 90 秒命令退出码 0、`DONE_MARKER_90S`，整轮 126 秒 `completed`，无 `session_desynced` |
| P9 技能沙箱 | 可不验 | 沙箱 fail-closed，最坏是脚本跑不起来；CI 的 R9-1/R9-2 已断言 |
| **P10** 应用内更新 | 🟡 模拟器验到「安装确认页」 | 下载/校验/拉起安装器已验；最后一跳卡在模拟器 ABI（CI 包只带 arm64），真机同签名可覆盖安装 |
| **P1–P3** 容器租约 | ❗需要 arm64 真机 | proot 在 x86 模拟器上无法让 musl 程序 `execve`，只能到「租约就绪」半步 |
| **P4–P6** | 任何设备都能做 | 失败路径 / 诊断包 / 截断提示，不需要 arm64 |

也就是说：**真机上真正剩下的只有 P1–P3（容器三条）+ P4–P6 的界面确认**，约 20 分钟。
P7/P8 这两条原本最需要真机的（一个要看 manifest、一个要跑 90 秒），已经在服务器侧用
模拟器 + mock 端点验掉了。

---

## 一次验收怎么走（约 20 分钟）

1. arm64 手机连 adb（x86 模拟器能到 P4、P5，以及 P1 的「租约登记」半步——见文末 2026-11-12 记录）。
2. 装包：`./gradlew :app:assembleRelease`（**不要**带 `-PnoNative`）→ `adb install -r`。
   要跑 harness 或想留调试器接口时才用 `assembleDebug`——P7 要看 manifest，只能用 release 包。
3. 按 P1 → P3 顺序点，P4/P5 任何设备都能做。
4. 入口位置：**「容器」已不在侧栏**（2026-11 起侧栏只留 5 项），改到 **设置 → 工具 → 容器**。
4. **任何一步不对，不要截图**：日志页 →「诊断包」→ 发我。R10 起这个包里带
   `turns.ndjson`（一行一个事件对象），比截图和 logcat 片段都好定位。

每项都写了「通过标准」，判据是**日志原文或界面文字**，不需要理解实现。

---

## P1 容器租约：成功路径确实借出了租约

| | |
|---|---|
| 阻塞原因 | 跑通 `sh` 需要 arm64。x86_64 模拟器能走到「租约就绪 + proot 原始报错」这一步，但 proot 无法让 musl 程序 execve（见文末 2026-11-12 记录） |
| 代码 | `ContainerScreen.ContainerSessionHolder.acquire` / `ContainerLease.borrow` |

**步骤**：设置 → 工具 → 容器 → 点发行版（等 rootfs 解压完成、出现 shell 提示符）。

**通过标准**
- `adb logcat -d \| grep 容器租约` 出现 `容器租约就绪：container:alpine`；
- 设置 → 资源租约，出现一行 `容器会话 · alpine`，`持有` 为 1，`空闲` 从 0 开始涨。

---

## P2 容器租约：退出归还 + 空闲回收

| | |
|---|---|
| 阻塞原因 | 同上；且要观察真实 proot 进程 |
| 代码 | `release()` 的 1 秒宽限期 / `ContainerLease.releaseIdle` |

**步骤**
1. 从容器页返回，等 2 秒：`adb shell ps -A \| grep -c proot` → 应为 **0**。
2. 再进容器页，1 秒内退回再进（模拟旋屏/误触）：`持有` 应回到 1，**不出现 2**。
3. 可选（要等 30 分钟，或临时把 `LeaseRegistry.idleTimeoutMs` 调成 60_000）：
   放着不动，日志出现 `空闲回收容器会话：container:alpine`，`ps` 里 proot 消失。

**通过标准**：第 1 步为 0；第 2 步恒为 1；第 3 步回收日志与进程消失**同时**成立
（只有日志没有进程消失 = 回收打空，这是 R9 之前那个 bug 的形态）。

---

## P3 容器租约：切换发行版不串会话

| | |
|---|---|
| 阻塞原因 | 需要能真的跑 proot + 两个 rootfs |
| 代码 | `acquire()` 里 `sessionDistroId != distro.id` 分支 |

**步骤**：alpine 里 `echo alpine > /root/who` → 切 debian → `cat /root/who`。

**通过标准**
- `cat` 报 `No such file or directory`（rootfs 独立）；
- 日志有 `切换发行版 alpine → debian，销毁旧容器会话`；
- 不会出现「标题写 debian、shell 还是 alpine」——这是修复前的老 bug。

---

## P4 容器租约：失败路径不借租约（任何设备都能做）

| | |
|---|---|
| 阻塞原因 | 无（x86 模拟器、无 proot 的真机都可） |
| 代码 | R3-15 静态断言对应的运行时行为 |

**步骤**：在没有 proot 的环境进容器页。

**通过标准**
- 页面提示错误（当前文案：`无法创建容器会话（proot 不可用？）`）；
- `adb logcat -d \| grep -E "容器租约就绪\|create 返回 null"` **只有**后者；
- 设置 → 资源租约**不出现**任何 `容器会话` 行（租约为空时整块不渲染）。

这条验的是「借了却拿不到会话」——若出现 `容器租约就绪`，`activeCount` 会永久卡在 1，
空闲回收再也不会触发。

---

## P5 诊断包含回合事件（任何设备都能做）

| | |
|---|---|
| 阻塞原因 | 需真实跑一轮对话产生回合日志 |
| 代码 | `DiagnosticsExporter.exportBundle` / `RunJournal.exportAllNdjson`（R10） |

**步骤**：跑一轮带工具调用的对话（比如让它读一个文件）→ 日志页 →「诊断包」→ 选任意应用发送。

**通过标准**
- 分享面板里是**两个**文件：`agentkit-diagnostics-*.txt` 和 `agentkit-turns-*.ndjson`；
- 回执文字是 `诊断包已生成（含回合事件）`；
- ndjson 每行是一个 JSON 对象，含 `ev` / `ts` / `turn` / `scope`（**字段名以此为准**）。

> **2026-11-11 更正**：本节原来写的是 `seq` / `at` / `type`，并按
> `grep '"type":"settle"'` 计数。实现早已改成 `ev` / `ts`，照旧判据 grep 恒为 0 条，
> 会得出「P5 不通过」的假红。真机样本（模拟器 API 30，389 行）：
>
> ```json
> {"ev":"llm.end","ts":1794039676119,"turn":"mw64v9n8","scope":"main","step":3,"dur":750,"ok":true,"calls":0}
> {"ev":"turn.end","ts":1794039676144,"turn":"mw64v9n8","scope":"main","status":"completed","steps":4}
> {"ev":"store.write","ts":1794039676899,"store":"conversations","bytes":19028,"dur":281,"coalesced":2}
> ```
>
> 计数应改为 `grep '"ev":"settle"' | wc -l`。**落盘这一半已在真机验过**（`files/logs/turns-*.ndjson`
> 存在且逐事件完整）；「诊断包导出的两个文件」这一半仍待验（需要点分享面板）。

没有回合日志时（全新安装、还没聊过），应只分享一个 txt 且回执不说「含回合事件」。

---

## P6 输出被截断时界面要有话说（任何设备都能做）

| | |
|---|---|
| 阻塞原因 | 需真实模型真的撞到输出上限（**不能靠造假数据**，否则验的不是这条链路） |
| 代码 | `RealAgentLoop`（`finishReason=length` → `AgentEvent.OutputTruncated`）→ `ChatViewModel.truncationNotice` → `ChatScreen` 输入栏上方提示条 |

**步骤**：把模型配置里 `max_tokens` 调到很小的值（或用 `deepseek-chat` 让它写一篇长文），
提一个必然写不完的要求（如「详细解释操作系统的进程调度，至少 3000 字」）→ 等它停下。

**通过标准**
- 输入栏上方出现 `⚠️ 回答达到输出上限，内容可能不完整——回复「继续」可接着写`；
- 点「知道了」提示条消失；
- 回复「继续」后它接着写，**且上一条提示条自动消失**（不是叠两条）；
- 正常说完的一轮**不出现**这条提示（这是最容易写错的方向：把 `stop` 也当成截断）。

没有可调的 `max_tokens` 时（OpenAI 兼容路径用的是服务端默认值），
可用 `adb logcat -s AgentKit:V | grep 长度上限` 先确认厂商确实报了 `finish_reason=length`——
日志有、界面没有 = 链路断了；日志没有 = 这一轮压根没截断，换个更长的要求再试。

---

## P7 发布包不是 debuggable ✅ **已在模拟器验证（2026-11-12）**

| | |
|---|---|
| 状态 | **已验证，不需要真机再验** |
| 验证环境 | Android 11 x86_64 模拟器（API 30，userdebug）；CI 发布包 run 704 |
| 代码 | `app/build.gradle.kts` 的 `release` buildType；CI 的 `Verify APK identity` 步骤 |

**实测记录**

```
$ aapt2 dump xmltree --file AndroidManifest.xml app-release.apk | grep -c debuggable
0                                  # release 包未声明该属性 → Android 默认 false

$ adb install app-release.apk && adb shell dumpsys package com.beichen.polaris | grep flags
flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]        # 无 DEBUGGABLE

$ adb shell run-as com.beichen.polaris ls
run-as: package not debuggable: com.beichen.polaris
```

对照修前（同一台模拟器、旧包）：`flags=[ DEBUGGABLE ... ]`，`run-as` 能直接列出
`shared_prefs/`，里面 `api_key` 与 `profiles_json` 都是明文 XML——同机任意应用可读走。

下面是原来的手动步骤，留作**回归用**（换签名、换变体、改构建脚本后跑一遍）。

**背景一句话**：2026-11-12 之前仓库只产 debug 变体，CI 直接把它发 Release。签名是发布证书
（这点没错），但 `android:debuggable="true"` 意味着同机任意应用能 `run-as` 进私有目录——
包括**明文存的 API Key**。装得上、跑得动，只有看 manifest 才看得见。

**步骤**：装 CI 的 `app-release.apk`（或本地 `./gradlew :app:assembleRelease`），然后

```bash
adb shell run-as com.beichen.polaris ls
```

**通过标准**：报 `Package 'com.beichen.polaris' is not debuggable`（或 `unknown package`）。
若命令**成功列出目录**，说明装的是 debug 包——那不是回归，是变体不同；换 release 包再看。

---

## P8 长命令不再第 30 秒被杀 ✅ **已在模拟器验证（2026-11-12）**

| | |
|---|---|
| 状态 | **已验证，不需要真机再验** |
| 验证方式 | 服务器上起一个 OpenAI 兼容的 mock 端点，让 Agent 真跑一次 `shell_run` |
| 代码 | `ShellTool.commandTimeoutMs` → `TerminalSessionPool.run(..., timeoutMs=)` |

**实测记录**（模拟器 API 30，mock 端点两跳：先 `__use_tools(groups=shell)`，再 `shell_run`）

```
03:14:27.267 WARN [Approval] shell_run 允许
03:14:27.504 INFO [ProcessBackend] 本机 user 后端已启动：/system/bin/sh
03:14:27.513 INFO [TerminalSessionPool] 会话已开辟 handle=d452 identity=user pool=user
...（90 秒内没有任何 timeout / session_desynced）
03:16:14.425 INFO [Turn] TURN ... 126972ms turn.end status=completed steps=3

工具返回：
[ok] code=shell_ok
message: 会话 d452（身份 App 身份，池 user） 退出码 0，已开辟新会话
data: DONE_MARKER_90S
```

修前同一条命令会在**第 30 秒**返回「命令在 30000ms 内未完成」，并把会话标脏
（下一条命令报 `session_desynced`）。修后整轮 126 秒（含 17 秒等人点审批）正常收尾。

下面是原来的手动步骤，留作回归用。

**步骤**：对话里让它执行

```bash
for i in $(seq 1 90); do sleep 1; done; echo done
```

（或直接 `shell_run` 跑 `sleep 90 && echo done`）

**通过标准**：约 90 秒后返回 `done`、退出码 0。
修前的表现是**第 30 秒**返回「命令在 30000ms 内未完成」，并且该会话被标脏
（下一个命令报 `session_desynced`，要求 `shell_close` 重开）。
`shell_run` 声明了 20 分钟预算，修前那个值从未传到会话池。

注意：这条同时也是「回合预算」的反向验证——回合还剩不到 90 秒时，它会按剩余时间收手
并报 `turn_budget_exhausted`（**不是** `tool_timeout`），那是正确行为。

---

## P9 技能脚本拿不到宿主类（可选，服务器侧已有断言）

| | |
|---|---|
| 阻塞原因 | 要有一个能改脚本的技能包 |
| 代码 | `RhinoJsEngine.eval`（ClassShutter 恒 false + `initSafeStandardObjects`） |

**步骤**：导入一个含 `main.js` 的技能，正文写

```js
(input, agent) => { return typeof Packages + '/' + typeof java }
```

**通过标准**：返回 `undefined/undefined`。修前返回 `object/object`——那意味着
`Packages.<任意未列包>.<类>.<静态方法>()` 可以执行（已用同版本 Rhino 复现）。

没有可改脚本的技能时跳过：`R7Verification` 的 R9-1 / R9-2 在服务器侧直接断言了这一条
（2026-11-12 CI run 已通过：`typeof Packages` 与 `typeof java` 均为 `undefined`，
`Packages.kotlin.Unit` 被拒）。这条不具备「不验就会出事」的性质——沙箱是 fail-closed，
最坏情况是脚本跑不起来，不会静默放行。

---

## P10 应用内更新：下载不再跳浏览器

| | |
|---|---|
| 阻塞原因 | 最后一跳要真机：CI 包只带 `arm64-v8a`，x86_64 模拟器解压 native 库必失败（`res=-113`） |
| 代码 | `core/update/ApkDownloader.kt`、`core/update/UpdateChecker.kt`、`app/update/UpdateInstaller.kt`、`ui/AboutScreen.kt` |
| 服务器侧断言 | `scripts/qa/run-update-inapp-verification.sh`（23 条结构断言，CI 已跑） |

**修前**：关于页「去下载」＝ `ACTION_VIEW` 打开浏览器。用户要自己等浏览器下载完、
去文件管理器找包、再点安装；国内打开 github release 还常常是空白页。
**顺带修掉一个必然 404**：下载地址写死 `app-debug.apk`，而 CI 在 2026-11-12 已把产物
改成 `app-release.apk` 并删除 debug 资产——点「更新」下到的是一张 404 页面。

**现在的路径**：检查更新 → 「立即更新」→ 应用内流式下载（进度 / 速度 / 可取消）→
校验（Content-Length、ZIP 魔数、包名、versionCode 严格相等）→ `cacheDir/updates/` →
FileProvider `content://` → 系统安装器。

**模拟器已验（2026-11-12，x86_64 / API 30）**：
1. 装 700 的包，关于页显示「当前构建号 700」；
2. 点「检查更新」→「有新版本：构建 707（当前 700）」（真实 `api.github.com`）；
3. 点「立即更新」→ 进度条走到 100%（36.0 MB，1.3–3.2 MB/s），完成后自动拉起安装器；
4. 系统安装页显示「北辰 · 是否更新此现有应用」，点 INSTALL 后进入 Installing；
5. 最终 `App not installed`，logcat 原文：`Failed to extract native libraries, res=-113`
   —— 这是 CI 包只有 arm64 `.so`、模拟器是 x86_64 导致的，与下载/校验/安装链路无关。

**真机怎么验（2 分钟）**：装 CI 的 release 包（`app-release.apk`）→ 关于页 →
「检查更新」→ 出现「有新版本：构建 N+1」→「立即更新」→ 等进度条走完 →
系统安装页点「安装」→ 装完 App 自动重启，关于页版本号变成 `1.N+1`，且原有数据还在。

**通过标准**：
- 全程没有离开过本应用（除了系统安装器那一步，这一步系统不允许应用代劳）；
- 下载中「取消」能立刻停下，再点「立即更新」从头开始（不会留着半个包）；
- 装完 `关于` 页的「当前构建号」等于 Release 标题里的 run 号；
- 手机没开「安装未知应用」时，会跳到该设置页，回来点「继续安装」能接着装。

---

## 观察到再说（不阻塞，先记着）

| # | 项 | 观察什么 | 判据 |
|---|---|---|---|
| O1 | ~~工具预算上限~~ **已由 P8 覆盖**（2026-11-12：`shell_run` 的 20 分钟预算此前从未生效，现按命令传达到会话池） | — | — |
| O2 | 资源租约采样开销 | 设置页停留 5 分钟，其间操作终端 | 页面是否卡顿。`snapshot()` 每 2 秒在 IO 线程采一次，理论上不碰主线程 |
| O3 | 容器空闲策略 | 容器与终端同时开着，各自闲置 | 容器是否比终端先被回收（当前两者都是 30 分钟，无独立策略） |
| O4 | 聚合商换上游重试（R10） | 用 OpenRouter / 硅基流动连续请求，撞到 429 或 402 | 日志应有 `第 N 次调用失败，准备重试`，且 N 最多到 4（官方直连只到 3）。若 402 一次就失败，说明档位没生效 |
| O5 | 长对话不再「忘了前面」（R10） | 接 deepseek（128K，V3.2 的 chat 与 reasoner 同窗口）把上下文预算调到 200k，聊到 70k+ token | 实际预算应被压到 64K（窗口一半）。**注意**：默认预算 24k 本就低于窗口一半，这个收敛只在用户把预算调到 32k 以上时才起作用——别在默认配置下期待看到变化（口径见 R10-16/R10-21） |
| O6 | 第三层归一化有没有乱判（R10） | 任何失败后看日志 | `provider.misclassified` 若频繁出现且 evidence 不相干，说明关键词表需要收窄——这是唯一能发现误判的入口 |
| O7 | 长对话不再越聊越卡（R13） | 一次连续对话拉到 100+ 条消息，全程流式输出 | 滚动与输入应保持跟手。会话写入已改成 400ms 去抖（`ConversationStore.persistCount` 可查写盘次数）：100 条消息的写入次数应在个位数，而不是 100 次 |
| O8 | 工具数组参数在三家都能用（R11） | 用 OpenAI / Claude / Gemini 各发一次「并行查三个文件」触发 `spawn_agents` | 模型应正确产出 `tasks` 数组。修前 schema 里 `items` 是空的，严格实现（Gemini）会拒掉整个工具声明，表现为「这个工具像不存在」 |
| O11 | 升级后 MCP 工具默认不可见（P3-3） | 配一个 MCP 服务器 → 冷启动 → 看工具表；再问一句「用 mcp 工具查一下」 | 预期：默认不在工具表；模型应 `__use_tools("mcp")` 开组后可见。若模型反复报「没有这个工具」且从不试开组，说明组索引的措辞不够明确，需改 `noteOf("mcp")` |
| O10 | 能力清单零偏差（P3-1） | 冷启动后 `adb logcat -s AgentKit` 过滤 `能力清单` | 应**一条都没有**。若出现「能力清单有 N 处不同步」，说明有工具没登记组（会落进 misc），把日志里的工具名补进 `CapabilityManifest` 对应组的 `tools` |
| O12 | 遥测流水真的落了盘（P5-1） | 跑两个回合 → 看 `files/logs/` 下有没有 `turns-<stamp>.ndjson`（导出诊断包时它会被改名成 `agentkit-turns-<stamp>.ndjson`） | 流水文件里应有 `turn.start` / `prompt.plan` / `llm.call` / `llm.end` / `turn.end` / `store.write` 成对行。**若目录里只有 `agent-*.log` 没有 `turns-*.ndjson`**，说明 `TraceSink.init` 失败或队列没 flush——这是三项度量继续「未知」的原因。⚠️ 本行原先写的文件名 `agentkit-events-*.ndjson` **是错的**，实现叫 `turns-*.ndjson`，2026-11-04 更正 |
| O13 | 度量三项符合目标（P5-1） | 把 ndjson 拉回电脑，`python3 scripts/qa/trace-metrics.py <file>` | 期望：确定性错误白等 <1s、每回合会话写入 <1MB、重放 0 次。若白等仍有几十秒，看输出里「N 次还发生了重试」——那说明某类错误没被 `ErrorNormalizer` 判成确定性。⚠️ 「每回合会话写入」若显示 0.0KB **不是达标，是没量到**（2026-11-04 修过一次归属规则，见下节） |
| O9 | 断电不丢会话（R12） | 非必做：写入过程中强杀进程（`am force-stop`）后重进 | 会话要么是完整的一条，要么回到上一版；**不能**是空文件或半截 JSON。空文件说明 fsync 没生效 |
| O14 | 启动不再被判 ANR（2026-11-04 修） | 冷启动 App（**首次**装包或 WebView 被系统回收后再进） | 日志不应出现 `Completed ANR of com.beichen.polaris` / `Force finishing activity ...MainActivity`。旧实现里后台 WebView 的创建排在首帧之前，冷启动要 20~30 秒占着主线程 → 首帧出不来 → 系统判 ANR（`does not have a focused window`）→ 弹回桌面。修后 WebView 改在首帧之后创建，构造耗时会打进日志（`后台 WebView 已就绪（构造 Nms，总计 Nms）`） |

---

## 已经在服务器验过的（不用再验）

| 层 | 手段 | 结果 |
|---|---|---|
| 租约语义 | `run-r3-verification.sh` R3-1~14 | 14/0 |
| 借还顺序 | 同脚本 R3-15（源码顺序断言） | 通过 |
| 回合事件 / 崩溃恢复 | `run-r7-verification.sh` | 20/0 |
| 技能脚本链路 + 能力表/清单对账 | `run-batchb-verification.sh` | 93/0 |
| 工具预算 / 作用域 | `run-runtime-capability-verification.sh` | 46/0 |
| 工具 schema 单点序列化 | `run-r11-verification.sh` | 11/0 |
| 原子写（真文件 / 真并发） | `run-r12-verification.sh` | 13/0 |
| 会话落盘去抖 | `run-r13-verification.sh` | 8/0 |
| 架构守卫（分层 / 行数 / 单点） | `run-arch-guard-verification.sh` | 5/0 |
| 能力清单四条双向断言 | `run-capability-manifest-verification.sh` | 30/0 |
| 生成运行态状态机（含取消/异常） | `run-generation-run-verification.sh` | 34/0 |
| 遥测流水字段契约与落盘 | `run-trace-telemetry-verification.sh` | 30/0 |
| 度量脚本自身的正确性 | `run-trace-metrics-verification.sh` | 14/0 |
| 工具表顺序 = 清单顺序（含乱序输入）+ 清单↔实现双向 | `run-kernel-exposure-verification.sh` 第 9~10 段 | 83/0 |
| 工具面 token 预算（默认 ≤525 / 满载 ≤3500） | `run-tool-surface-verification.sh` | 3/0 |
| 终端仿真器（打印/宽度/SGR/光标/擦除/备用屏/历史/畸形输入/并发/缩放） | `run-terminal-emulator-verification.sh` | 78/0 |
| 审批归组（同类操作同组 + 需审批工具都有中文说明） | `run-approval-grouping-verification.sh` | 9/0 |
| CI 门禁本身（含「全 SKIP 不算绿」） | `run-gate-verification.sh` | 20/0 |
| 启动顺序（首帧先于后台 WebView 创建） | `run-startup-order-verification.sh` | 5/0 |
| 供应商身份接线（R-01，需 AGP 产物） | `run-provider-id-wiring-verification.sh` | 13/0 |
| 输出上限按目录声明（R12） | `run-output-token-limit-verification.sh` | 8/0 |
| HTTP 三预设收口（R13） | `run-http-client-single-source-verification.sh` | 10/0 |
| 协程上下文收口（R14） | `run-app-scope-verification.sh` | 8/0 |
| 启动十步顺序显式化（R15） | `run-boot-sequence-verification.sh` | 20/0 |
| 标题归属发起会话（R-02） | `run-title-owner-verification.sh` | 7/0 |
| 中断后工具卡收口（R-06） | `run-r06-verification.sh` | 24/0 |
| 审批超时与取消语义（R-03） | `run-r03-verification.sh` | 14/0 |
| 消息列表 key 身份（R-05） | `run-r05-verification.sh` | 12/0 |
| run-all 判据（R-04） | `run-runall-verification.sh` | 32/0 |

真机验的是**这些断言覆盖不到的那一层**：真实 proot 进程的生死、真实 Compose 生命周期、
真实分享面板。断言通过不代表真机通过，反之亦然——两边都要，但不用重复。

---

## 2026-11-04 在验证机（x86_64 模拟器）上已验的项

环境：模拟器 API 30 / x86_64 + mock 模型（`http://10.0.2.2:8899/v1`，设备上已配好
「假模型」档位）。**不是 arm64 真机**，所以容器租约（需要 arm64 proot）那一类仍然挂账。

| 项 | 结果 | 依据 |
|---|---|---|
| O10 能力清单零偏差 | ✅ 通过 | 冷启动日志里**没有**「能力清单有 N 处不同步」；审计在 `ChatViewModel` 初始化时、MCP 注册前跑，零偏差即无输出 |
| O12 遥测真的落盘 | ✅ 通过 | `files/logs/turns-20261103.ndjson`（962 字节）出现，内容为 `store.write` / `turn.start` / `prompt.plan` / `llm.call` / `llm.end` / `turn.end` / `store.write` 七行 |
| O13 三项度量 | ✅ 通过 | 白等 0 次 0.0s（目标 <1s）；每回合会话写入 58.3KB（目标 <1MB）；重放 0 次 |

### 这台机器上发现的三个真问题（都已修）

1. **启动被判 ANR，App 闪退**：日志原文
   `Completed ANR of com.beichen.polaris in 30580ms` + `Force finishing activity
   com.beichen.polaris/.MainActivity`，原因 `does not have a focused window`。
   根因是后台 WebView 的创建排在首帧之前，冷启动 20~30 秒占着主线程。修法见
   `MainActivity.onCreate`（首帧的 `OnDrawListener` 里再 attach）。
2. **「每回合会话写入」度量恒为 0**：归属规则要求落盘时间戳落在回合窗口内，而落盘是
   去抖延迟写、真机上恰恰发生在 `turn.end` 之后 → 全被丢掉 → 目标 <1MB 恒成立。
   修法见 `scripts/qa/trace-metrics.py`（归到最后一个已开始的回合，回合外的写单独显示）。
3. **首帧修完之后，App 反而崩了**——这一条是**修 1 时自己引入的**：

   ```
   java.lang.IllegalStateException: Cannot call removeOnDrawListener inside of onDraw
       at android.view.ViewTreeObserver.removeOnDrawListener(ViewTreeObserver.java:745)
       at com.beichen.polaris.MainActivity$onCreate$2.onDraw(MainActivity.kt:61)
   ```

   `ViewTreeObserver` 正在派发 `onDraw` 时不能摘监听。harness 全绿（它只读源码文本），
   真机一装就崩。这条记在这里，是想说明：**「服务器上能验的一律在服务器验掉」不等于
   「服务器上验过就等于对」**——真机项攒起来不是形式主义，是有些缺陷只能在设备上出现。

### 触摸与输入（2026-11-11 更正：旧结论已推翻）

- ~~**触摸设备被禁用**：`dumpsys input` 里每个输入源都是
  `Touch Input Mapper (mode - disabled)`，所以 `adb shell input tap` 完全不生效。~~

  **这条不再成立。** 2026-11-11 在同一类机器（`-no-window -accel off -gpu
  swiftshader_indirect`）上实测：点开导航抽屉、进控制台、点审批弹窗的「允许执行」
  全部成功。旧结论的代价很大——它让过去几轮的所有验证都被迫绕开 UI，只能读日志
  推断「弹窗大概弹了」，而 F16（审批弹窗挂在对话页里）正是这样漏掉的。

  现做法：`adb shell uiautomator dump` 读界面文字与坐标 → `adb shell input tap x y`
  点。坐标从 dump 里取，不要硬编（状态栏/崩溃横幅会把整页往下推）。

- **仍然推荐：用 workflow 通知入口触发回合**（比点 UI 稳，且不受页面状态影响）：

  ```bash
  # 1. 往应用私有目录写一条 workflow（run-as 能写；目录要先 mkdir）
  adb shell 'run-as com.beichen.polaris mkdir -p files/workflow'
  adb push wf.json /data/local/tmp/wf.json
  adb shell 'run-as com.beichen.polaris sh -c "cat /data/local/tmp/wf.json > files/workflow/workflows.json"'
  # 2. 带 workflow_id 启动：MainActivity 的 LaunchedEffect 会自动执行它（prompt 直接当对话输入发出去）
  adb shell am start -n com.beichen.polaris/.MainActivity --es workflow_id w-test
  # 3. 看结果
  adb shell 'run-as com.beichen.polaris tail -20 files/logs/agent-*.log'
  adb shell 'run-as com.beichen.polaris cat files/logs/turns-*.ndjson' > /tmp/turns.ndjson
  ```

  判定「回合真的跑了」的标志：`[Workflow] 从通知进入，准备执行 <id>` →
  `[Chat] 用户发送 <N> 字`（F12 起只记长度，不再记正文）→ `[AgentLoop] 开始运行…`
  → `[Turn] … turn.end status=completed`。

- **看「模型到底收到了什么」**：mock 端点侧把完整请求体落盘最直接。给
  `mock_llm.py` 的 `do_POST` 加一段 `json.dump(req, ...)`，重启后每个请求一个
  `.json`，想验「注入里有没有正文」直接读文件，不用猜。重启要用
  `setsid -f python3 mock_llm.py 8899`——普通 `nohup ... &` 会随 SSH 会话一起被杀。

- **审批弹窗的端到端验证**需要三个条件同时成立：mock 先回 `__use_tools`（渐进式
  工具面下 `device` 组默认没开）、再回目标工具、用户 30 秒内点按钮。少任何一个，
  看到的现象都是「任务失败」，而不是「弹窗没出现」。

---

## 2026-11-04 复测（构建机 native 编译 + 验证机干净复测）

这一轮补掉了两件之前一直挂着的事：**native arm64 首次编译通过**，以及
**三项度量在「不打断进程」的干净环境下复测通过**。

### 补掉 1：native arm64 首次真编译（构建机）

之前构建机上**从来没有 llama.cpp 源码**，所以历来的 APK 都是
`./gradlew :app:assembleDebug -PnoNative=true` 出的（28.2MB，**不含 lib/**）——
它能装进 x86 模拟器只是因为「没有 lib/ 目录 = 任何 ABI 都能装」，
代价是 **JNI 那条路一次都没被编译验证过**。

这次按 `scripts/fetch-llama.sh` 拉了固定 tag `v0.7.0`（commit 校验通过），
再跑不带 `-PnoNative` 的完整构建：

```
> Task :app:configureCMakeDebug[arm64-v8a]
> Task :app:buildCMakeDebug[arm64-v8a]
BUILD SUCCESSFUL
app-debug.apk  35,159,658 字节（29.8MB → 35.2MB）
```

APK 内实测含两个自编 .so：

| 文件 | 大小 |
|---|---|
| `lib/arm64-v8a/libagentkit-llama.so` | 4,471,456 |
| `lib/arm64-v8a/libagentkit-termux.so` | 331,928 |

（另有 `libmlkit_google_ocr_pipeline.so` 等第三方 .so。）

**结论**：llama.cpp v0.7.0 + JNI 的 arm64 交叉编译在构建机上成立。
两个产物各有用途，已留在构建机：

- `/data/home/app-debug-native-arm64.apk`（35.2MB）→ **真机用**（含 lib/）
- `/data/home/app-debug-emulator.apk`（28.2MB，无 lib/）→ **模拟器用**

### 补掉 2：验证机干净复测（三项度量全达标）

上一轮的 O13 是「跑到一半强杀进程」测出来的，`重放 3 次` 其实是**人为制造的
撕裂记录被修复**，不是产品缺陷。这轮改成：全新安装 → 只跑一个**自然结束**的回合
→ 不强杀进程 → 直接取流水。

触发回合的两处配置（模拟器触摸被禁用，只能走文件 + intent 入口）：

```bash
# ① 模型指向 mock LLM：adb reverse 把设备 127.0.0.1:8899 转到宿主机 mock
adb reverse tcp:8899 tcp:8899
adb shell "run-as com.beichen.polaris sh -c 'cat > shared_prefs/agentkit_model_config.xml'" < cfg.xml
# ② 预置工具组：calculator 属于 sys 组，默认不激活，不置的话模型直接调会撞 unknown_tool
adb shell "run-as com.beichen.polaris sh -c 'cat > shared_prefs/tool_activation.xml'" < act.xml
#   → <string name="active_groups">file,sys</string>
# ③ 工作流（prompt 里带 "write" 会触发 write_file，带 "calc" 触发 calculator）
adb shell "run-as com.beichen.polaris sh -c 'cat > files/workflow/workflows.json'" < wf.json
adb shell am start -n com.beichen.polaris/.MainActivity --es workflow_id w-calc
```

⚠️ 装完 App 立刻写 `shared_prefs/` 会失败（**目录还不存在**），
必须先 `adb shell run-as com.beichen.polaris mkdir -p shared_prefs files/workflow`，
否则配置静默丢失，回合会以 `status=failed` 收场。

工具面因此从 5 个涨到 9 个，`calculator` 真跑起来了：

```
[ToolExposure] 工具面裁剪：provider=openai mode=GROUPED 暴露 9 个（含 2 个元工具），裁掉 49 个；已启用组=file,sys
[Turn] tool.start step=0 tool=calculator args={"expression":"(120+80)*0.85"}
[Turn] tool.end   step=0 tool=calculator code=calc_ok error=false dur=315ms
[Turn] turn.end status=completed steps=2
```

官方度量脚本（`scripts/qa/trace-metrics.py`）对这份流水的结果：

| 指标 | 实测 | 目标 | 判定 |
|---|---|---|---|
| 确定性错误白等 | 0 次，均值 0.0s，峰值 0.0s | <1s | ✅ |
| 每回合会话写入 | 均值 3.5KB / 峰值 3.5KB | <1MB | ✅ |
| 崩溃恢复重放 | **0 次** | 0 | ✅ |

退出码 0。事件流 15 行，类型齐全：
`store.write×4 / turn.start / prompt.plan×2 / llm.call×2 / llm.end×2 /
tool.start / tool.end / step.close / turn.end`。

同时确认：冷启动**无 FATAL EXCEPTION、无 ANR、无「能力清单有 N 处不同步」**。

### 本轮新发现的两件事

1. **模型直接调未激活组的工具，报的是「未知工具」**。
   `calculator` 登记在 `sys` 组，默认只激活 `file` 组，模型若不先调 `__find_tools`
   发现它就会拿到 `code=unknown_tool`。工具**确实存在**，但回执把它说成「不存在」。
   这是给模型看的错误文案，建议改成「该工具当前未启用，请先 `__find_tools` 查找」
   ——否则模型会以为是自己编了个不存在的工具，转而去猜别的名字。

2. **x86 软件模拟（`-accel off`）上首帧 37.3 秒**（`Displayed: +37s297ms`）。
   不是回归：`ChatViewModel` 初始化那约 9 秒主线程重活还在，只是没有 KVM 的
   模拟器把它放大了 3~4 倍。**这条仍然挂账**：arm64 真机上的首帧耗时没有数过。

### 仍然挂账（没变的）

- arm64 真机：O1~O9/O11、容器租约（需 arm64 版 proot）、无障碍、真实分享面板。
- **首帧耗时**（arm64 真机未测；`ChatViewModel` 初始化 9 秒主线程重活未处理）。
- P5-4 发布签名缺 secret。

---

## 2026-11-04 第二轮：三条挂账落地（启动可归因 + 工具回执分路 + Crashpad 静默）

上一轮列的三条「说不清」——`unknown_tool` 文案、`ChatViewModel` 9 秒、Crashpad 日志——
这一轮全部落地成**可验证的事实**。详细过程见
`AgentKit-启动可归因与工具回执分路-2026-11-04.md`。

### ✅ 挂账 1：未启用组的工具回执（已修 + 已验）

**改法**：新增失败码 `tool_not_enabled` 与专属文案，与 `unknown_tool` 严格分开；
执行器拿到全量注册表后做二分判定（存在但不在本回合工具视图 → 「未启用」）。

**验证机实测**（`active_groups=file`，`sys` 组不激活，mock LLM 调 `calculator`）：

```
00:31:14.162 INFO [ToolExposure] 工具面裁剪：provider=openai mode=GROUPED 暴露 5 个（含 2 个元工具），裁掉 53 个；已启用组=file
00:31:16.107 WARN  [AgentLoop] 本批含 1 个不在本回合工具视图的调用（分组未启用或名称写错）
00:31:16.257 WARN  [Tool] 工具未启用：calculator（组 sys）——请先 __use_tools 启用该组
00:31:16.417 INFO  [Turn] tool.end step=0 tool=calculator code=tool_not_enabled error=true dur=148ms
00:31:17.748 INFO  [Turn] turn.end status=completed steps=2
```

回执现在说清三件事：**存在但未启用**、**属于哪个组**、**怎么开**（`groups="sys"`）。
真不存在的工具仍然回 `unknown_tool`（两条路径互不干扰，有断言守着）。

### ✅ 挂账 2：`ChatViewModel` 初始化 9 秒（已埋点，数字可归因）

新增纯 Kotlin 的启动分段计时器（`core/StartupTrace.kt`），在 ViewModel 初始化里打 6 个点。
**验证机实测（x86 软件模拟，两次冷启动）**：

```
00:28:35.337 INFO [Startup] ViewModel 初始化分段：skills=680ms, marketSources=13ms, registry=649ms,
                 reconcile=48ms, workflows=162ms, refresh=739ms, total=2291ms
00:29:28.526 INFO [Startup] ViewModel 初始化分段：skills=705ms, marketSources=22ms, registry=561ms,
                 reconcile=80ms, workflows=137ms, refresh=696ms, total=2201ms
```

「9 秒」这句话被数据取代了：这台无 KVM 的软件模拟器上总耗时稳定在 **2.1～2.4 秒**，
重活集中在 `refresh`（696~834ms）、`registry`（561~732ms）、`skills`（445~705ms）。

> arm64 真机首帧仍未测——但现在真机一装上，日志里就会直接给出这些数字。

#### 本轮按这些数字动了一刀：`refresh` 段挪出主线程

**先查清了它到底是什么**：`refresh` 段 = `refreshGrants()`（5 处 IPC：
`Settings.Secure` / `AppOps` / `PowerManager` / `Shizuku.pingBinder` ×2）
+ `refreshLogs()` + `refreshMemories()`。后两个是纯内存操作（毫秒级），
所以 700~1000ms 几乎全在 `refreshGrants()` 的 IPC 上。

佐证：第 4 次启动（进程内热态）`skills` 705→63ms、`registry` 682→116ms
（类已加载，JIT 已热），**唯独 `refresh` 反而涨到 1028ms**——IPC 不吃类热缓存，
这个"反向"变化恰恰证明它不是类加载，是系统调用。

改法：`refreshGrants()` 改成 `viewModelScope.launch(Dispatchers.IO)`，
由 StateFlow 回填（UI 晚几十毫秒看到权限值，首帧不再等它）。

**但第一次改完，`refresh` 从 1115ms 只降到……还是 1115ms。** 说明归因又只对了一半。
查时间线才发现：`refreshGrants` 已不在主线程（`CapabilityChain` 那串日志 47.696→47.753
只花 57ms），可主线程在 47.753→48.391 之间还有 **638ms 空白**没有任何日志——
那正是 `refreshLogs()`：`AgentLogger.recent(200).map { it.format() }`，
200 条日志逐条 `SimpleDateFormat.format()`（还带锁），全在主线程上跑。

于是把 `refreshLogs()`/`refreshMemories()` 也挪到 IO（`refreshLogs()` 本身仍是同步函数，
LOGS 页依赖它同步返回，只改了**启动路径**的调用方式）。

实测（同一台模拟器，两次冷启动）：

```
修复前  refresh=1115ms / 1028ms
修复后  refresh=190ms  / 172ms      ← 降 900ms 左右
```

`ChatViewModel` 行数 **3889**（冻结上限 3890，未超）。

### ✅ 挂账 3：chromium Crashpad E 级日志（已修 + 已验）

创建 WebView 之前先 `mkdirs()` 建出 `cache/WebView`（chromium 的 mkdir 不是递归的）。

**验证机实测（必须全新安装，`-r` 覆盖安装会保留 `cache/`，测不出东西）**：

```
00:28:52.966 INFO [BrowserHost] 开始创建后台 WebView
00:28:52.998 INFO [BrowserHost] 已预建 WebView 缓存目录：/data/user/0/com.beichen.polaris/cache/WebView
00:28:57.635 INFO [BrowserHost] 后台 WebView 已就绪（构造 4541ms，总计 4669ms）
```

logcat 里 **0 条** crashpad 行、**0 条** chromium 的 E 级行。

### 上一轮那条「前台点通知不触发」是**误判**（本轮查实并修掉了真问题）

上一轮记的是：「App 已在前台时 `am start --es workflow_id` 不会触发工作流，
`pendingWorkflowId` 没被 Compose 消费」。

**重测后：工作流其实触发了。** 用与 PendingIntent 完全相同的 flags
（`NEW_TASK|CLEAR_TOP` = `0x14000000`）复现，日志里 `从通知进入，准备执行 w-calc`
正常出现、回合跑完 `turn.end status=completed`。上一轮多半是在 App 尚未稳定时
打了一次就下了结论——这是「一次观测当规律」的典型误判。

但重测时挖出了一个**更严重**的真 bug：

```
点通知前  ViewModel 初始化次数: 4
点通知后  ViewModel 初始化次数: 5      ← 每点一次 +1
Activity 实例: 2 个并存
```

根因：`MainActivity` 在 manifest 里**没有声明 `launchMode`**（默认 `standard`）。
通知的 `CLEAR_TOP` 在 standard 下对已存在实例的语义是「销毁 + 重建」，于是——

1. 每点一次通知，Activity 重建一次；
2. Activity 重建 → ViewModel 重建 → 初始化耗时 1.3~2.6 秒**重跑一遍**；
3. 更糟的是 `onCleared()` 会取消 `viewModelScope`——**正在跑的任务被连带杀掉**。
   用户看到的是「点了个通知，任务没了」。

而 `MainActivity.onNewIntent` 里那段处理逻辑，在此之前是**死路径**：
`standard` 模式下该回调永远不会触发。

修法：manifest 声明 `android:launchMode="singleTop"`。同一 task 顶部已有实例时
改走 `onNewIntent`，Activity 与 ViewModel 都复用，在跑的任务不受影响。

断言：`StartupOrderVerification.kt` 新增 3 条（manifest 声明 singleTop /
onNewIntent 存在且设置 pendingWorkflowId / 调用 setIntent），13/13 通过。
反向变异已验证：删掉 `singleTop` → 断言立刻变红 → 恢复 → 重新变绿。

**设备实测（r5，与真实 PendingIntent 同款 flags `0x14000000`）**：

```
修复前  点通知前 4 次 → 后 5 次         Activity 实例 2 个
修复后  点通知前 8 次 → 后 8 次         Activity 实例 1 个
        日志：从通知进入，准备执行 w-calc
        turn.end status=completed steps=2
```

点通知不再重建，工作流照常跑完。

### 这一轮的验证数字

| 项 | 结果 |
|---|---|
| 全量 harness | `PASS=90 SKIP=0 XFAIL=0 FAIL=0`（新增 2 个 harness） |
| 门禁 | 5/5 全过 |
| 文本断言 | 1373 → **1370**（新断言全改结构化比较，基线同步收紧） |
| 大文件行数 | `ChatViewModel` 3890 / `RealAgentLoop` 1557，**均未超冻结上限** |
| 反向变异 | 「回退注入点 → 必红 → 恢复 → 重新变绿」6/6 通过 |
| 三项度量 | 白等 0 次 / 每回合写入 6.3KB / 重放 0 次（`trace-metrics.py` 退出码 0） |

### 第三轮（本轮）的验证数字

| 项 | 结果 |
|---|---|
| 全量 harness | `PASS=90 SKIP=0 XFAIL=0 FAIL=0` |
| 门禁 | 5/5 全过 |
| 文本断言 | **1370**（未增；新增 3 条用的是 `in` 判定，不计入） |
| 架构守卫 | 5/5 通过（`ChatViewModel` 3889 ≤ 3890） |
| 反向变异 | 删 `singleTop` → 断言必红 → 恢复 → 重新变绿 |
| **点通知重建 ViewModel** | 修复前 4→5（+1）；修复后 **8→8（不变）**，Activity 实例 1 个 |
| **`refresh` 段耗时** | 696~1115ms → **172~190ms**（降约 900ms） |
| 启动总耗时 | 2201~2590ms → **2248~2546ms**（同量级，但主线程少阻塞约 0.9s） |

> 注意最后一行：总耗时没有明显下降，因为 `registry`（1070~1213ms）和 `skills`（718~827ms）
> 成了新的最大项。本轮只是把 `refresh` 从主线程挪走，没有消除这部分工作量——
> 首帧更早出，但初始化总时长仍受这两段约束。它们是下一轮的对象。


## 2026-11-04 第四轮：先把「skills 718ms」这个数字的来源切开

上一轮把 `registry 1070~1213ms` / `skills 718~827ms` 记成了「下一轮的对象」。本轮动手之前
先核了这两个数字的**起点**，发现口径本身不对——不是优化没做，是**还没到能优化的地步**。

### 问题：段的边界按「上一次打点在哪」划，不是按「做了什么」划

`StartupMark` 在 `ChatViewModel` 第 368 行创建（字段区最前面），而 `skills` 段的第一个打点
在第 557 行 `buildInitialSkills()` 里。中间这近 190 行字段初始化——`marketStore`、
`conversationStore`、`bookmarkStore`、`jobStore`、`jobRunner`、终端池……——**全被算进了
「技能装载」**。它们和技能一个字节的关系都没有。

这和上一轮 `refresh` 段的误判是同一类错误：

> 上一轮把 `refresh=1115ms` 归给 `refreshGrants`（5 处 IPC），改异步后仍是 1115ms；
> 查时间线才发现真凶是 `refreshLogs()` 的 200 条 `SimpleDateFormat.format()`（638ms）。

当时的教训是「归因要查到底」；这一轮补上更靠前的一环：**先确认这个数字测的是你以为的那段代码**。

### 修法：加两个分隔打点，把四段拆成六段

| 段名 | 覆盖范围（修口径后） |
|---|---|
| `fields` | `StartupMark` 创建 → 技能装载开始（字段初始化） |
| `skills` | `importRepo.loadImported` + `SkillLoader.loadAll` |
| `preRegistry` | `computeSkillInfos` + 市场/终端等字段 → 45 个 register 之前 |
| `registry` | 45 个 `register(XxxTool(...))` |
| `reconcile` / `workflows` / `refresh` | 不变 |

代价是 `ChatViewModel` 多两行，而它是冻结文件（3890 上限）。所以同轮做了一次减法：
`startup.mark("refresh")` + `startup.logSummary(...)` 合成 `startup.finish("refresh", ...)`
（`StartupMark` 新增 `finish`），净行数仍是 **3890**，没有抬基线。

### 顺带修掉一个会让数字「看着像真的」的缺陷

`buildInitialSkills()` 不只启动时跑：用户导入/卸载技能会经 `reloadSkills()` 再调一次，
于是在 `finish()` 之后又打一对 `fields` / `skills` 点。`snapshot()` 会把这两段算进
**启动**分段——不是崩溃，是静默失真，比崩溃更难发现。

修法：`StartupTrace.seal()`，`StartupMark.finish()` 里封口；封口后 `mark()` 返回 0 且不记录。
harness 加 7 条断言（`[8] 收尾后打点失效`），反向变异「删掉短路」→ **4 条红，全在该组**。

### 字段区里三处「白付」的系统调用

顺着 `fields` 段往下查，发现字段初始化器里有三处实时探测：

```kotlin
private val _shizukuStatus    = MutableStateFlow(ShizukuShell.status())        // binderAlive + hasPermission = 2 次 IPC
private val _shizukuInstalled = MutableStateFlow(ShizukuShell.isInstalled(app))// PackageManager 查询
private val _deviceContext    = MutableStateFlow(DeviceContextStore.snapshot())// 读系统服务
```

关键在于：**`refreshGrants()` 会把这三样原样再刷一遍**（它已经开始 `launch(Dispatchers.IO)`）。
也就是说字段区那次探测算出来的值，除了当初始值给 UI 看一眼，**没有任何消费者**——
而 UI 第一眼看到的时候首帧都还没画，用户看不见。

改成静态默认值（`NOT_RUNNING` / `false` / 空 `DeviceContext`），真值由 `refreshGrants` 在 IO 上填。
`DeviceContext.isEmpty` 有明确语义（"尚无感知数据"），空初值不是假数据。

> 这类改动的收益是**确定但不大**（两次 binder IPC + 一次 PM 查询，几十毫秒级）。
> 值钱的是那条规矩：**首帧之前的主线程上，不许出现跨进程调用**。
> 所以同步加了 3 条断言，把规矩变成会红的检查。

### 断言写法本身踩了一个坑（值得记下来）

第一版判据是「`init {` 之前的部分＝字段区」。跑完全绿，看起来没问题；
按惯例做反向变异——**把探测加回去，仍然是全绿**。

原因：本类第一个方法是 `dismissInterruptedNotice()`（413 行），而 `_shizukuStatus` 在 474 行。
按「第一个 `fun` 之前」切，字段区在 413 行就截断了，**要检查的字段压根不在切片里**。
断言于是变成了「检查一个不包含目标的区域」——永远绿。

改成按**字段块**切（`private val _x ...` 起，按括号深度续行），变异才红，且只红对应的那一条：

| 变异 | 结果 |
|---|---|
| `_shizukuStatus` 改回 `ShizukuShell.status()` | **只有**「字段区不做 Shizuku 探测」红 |
| `_deviceContext` 改回 `DeviceContextStore.snapshot()` | **只有**「字段区不做设备上下文快照」红 |

> 这条教训比改动本身重要：**一个没做过变异的断言，不知道自己在守什么**。
> 它可能绿得理直气壮，而守的是一片空白区域。

### 同时补上「registry 段里到底谁慢」

`StartupMark` 只回答「这一段慢」，不回答「慢在谁身上」。而 `registry` 段的两种成因修法完全相反：

- 少数工具构造真重（构造器里读盘、起引擎）→ 该改那几个工具；
- 均匀摊开（每项 15~25ms）→ 那是 JVM 类加载/校验，改单个工具没有任何用，只能改「什么时候装」。

新增 `ToolLoadProfiler`，接法利用了 Kotlin 的求值顺序：`register(XxxTool(...))` 的实参
**先求值再进函数体**，因此在 `register()` 开头打一个点，「距上次 `register` 的间隔」
就等于本次实参的构造 + 类加载耗时——**45 个调用点一行都不用改**。

```
private val registry = ToolRegistry(startup.toolProfiler).apply { ... }   // 只改这一处
```

汇总时与分段数字一起落日志：

```
I Startup: ViewModel 初始化分段：fields=…, skills=…, preRegistry=…, registry=…, …
I Startup: 工具装配 45 项，合计 …ms；最慢：xxx=…ms, yyy=…ms
```

### 验证（本轮**没有**可上的设备）

| 项 | 结果 |
|---|---|
| 编译 | `:app:compileDebugKotlin` **BUILD SUCCESSFUL**（1m14s） |
| 出包 | `:app:assembleDebug -PnoNative=true` → `app-debug.apk` 28,247,915 字节 |
| 新 harness | `run-tool-load-profiler-verification.sh` **18 条行为断言全过** |
| 启动顺序 harness | **22/22**（13 → 19 接线断言，再 +3 条「字段区不做系统调用」） |
| 启动分段 harness | **32 条**（25 → 32，新增「收尾后打点失效」7 条） |
| 反向变异 | ① 去掉「首样本跳过」→ **8 条红**；② 阈值 `>=` 改 `>` → **只有**边界那条红；③ 断开 profiler 接线 → 只有接线那条红；④ 删掉 `seal` 短路 → **4 条红，全在收尾组**；⑤⑥ 字段区改回 IPC → **各自只红对应那一条** |
| 文本断言 | **1370 未增**（新断言全是行为断言 / `in` 判定，不计入 census） |
| 架构守卫 | 5/5 通过（`ChatViewModel` 3890 ≤ 3890） |
| 全量 harness | **`PASS=91 SKIP=0 XFAIL=0 FAIL=0`**，门禁 5/5（基线 90 → 91） |

> 为什么没有设备数据：本轮的远程环境（构建机 / 验证机）ssh 别名已无法解析，
> 沙箱内无 KVM、起不了模拟器。所以本轮只做到「编译 + 出包 + 纯 JVM 断言」，
> **没有**新的段耗时数字。`fields` / `preRegistry` 的实际毫秒数，以及工具装配的 top-N，
> 都要等下一次上机读日志——这也正是本轮不宣称「启动变快」的原因：
> 没有测量就没有结论，spec 上的数字只能来自真机。


### 补掉 3：全量 harness 两处「第一次真跑」暴露的陈旧断言

这轮把 APK 路径传给 `run-all.sh`、并且 native 包编出来（AGP 产物
`app/build/tmp/kotlin-classes/debug` 就位）之后，**两个一直 SKIP 的 harness 第一次真跑**，
各暴露一条陈旧断言。两条都不是产品缺陷，是断言自己过期了：

| harness | 以前 | 现在暴露的问题 | 处理 |
|---|---|---|---|
| `run-apk-verification.sh` | SKIP（本地默认不传 APK 路径） | 查包内字符串 `READ_ONLY_TOOLS`，但 R4 已把它改名成 `NO_SIDE_EFFECT_TOOLS` | 反验脚本改查新名 |
| `run-localmodel-verification.sh` | SKIP（缺 AGP 编译产物） | 断言写死了**旧注册顺序**的工具表；P3-5 之后顺序由清单决定，数量对（25=25）但顺序不同 | 改成「数量相等 + 成员集合相等 + 顺序＝`CapabilityManifest.sort()`」 |

第二条值得多说一句：它原本断言「全量开关下模型收到**这 25 个、这个顺序**」。
把顺序写死，等于把「注册顺序」当成了不变量——而 P3-5 恰恰把这个不变量废掉了。
现在断言的是**真正的不变量**：「顺序由清单决定」，清单改了断言会跟着改，不会再过期。

> 这也印证了门禁第 5 条（PASS 数不得低于基线）的价值：
> 环境一变，两个 harness 从 SKIP 变 PASS，PASS 数从 86 变 88——
> 如果只看「FAIL=0」，这次会以为没变化；实际上**验得更多了**。
> 反过来，如果这次是环境变差导致大面积 SKIP，基线会立刻报红。

顺带补齐了 CI 的 **Fetch Shizuku APK** 步骤（本地构建默认不走它），
native 包因此与 CI 产物一致，反验里「assets/shizuku.apk 存在且体积合理」也真跑起来了：

```
$ curl -fL "$URL" -o app/src/main/assets/shizuku.apk    # RikkaApps/Shizuku v13.6.0
  2,571,773 bytes
```

### 最终产物

| 产物 | 大小 | 用途 |
|---|---|---|
| `/data/home/app-debug-native-arm64.apk` | 36,948,986 | **arm64 真机**（含 lib/ + shizuku.apk） |
| `/data/home/app-debug-emulator.apk` | 30,031,067 | **x86 模拟器**（无 lib/，含 shizuku.apk） |

用最终 APK 重做的验证机取证（全新安装 → 自然结束的一回合 → 不打断进程）：
冷启动 **0 崩溃 / 0 ANR / 0 能力清单不同步**，`Displayed: +34s760ms`，
`calculator` → `code=calc_ok error=false`，`turn.end status=completed steps=2`，
三项度量 **白等 0 次 / 写入 3.5KB / 重放 0 次**（`trace-metrics.py` 退出码 0）。

---

## 2026-11-05 第五轮：截断从「可查」走到「可见」

上一轮做完了 `finish_reason` 的读取与归一化（三家厂商 → `length`/`stop`），
但那只是**可查**：截断时只落一条 W 级日志，用户界面上一个字都没有。
「回答说到一半停了」和「模型自己说完了」在界面上长得一模一样，用户只会以为模型敷衍。

### 卡点不是设计，是零余量

事件链的下一站是 `RealAgentLoop`（消费者是 `onEvent`）和 `ChatViewModel`（状态）、
`ChatScreen`（渲染）。三个都在架构守卫的冻结清单里，且其中两个**恰好顶满**：

| 文件 | 改前 | 上限 | 余量 |
|---|---|---|---|
| `RealAgentLoop.kt` | 1557 | 1557 | **0** |
| `ChatViewModel.kt` | 3889 | 3890 | 1 |
| `ChatScreen.kt` | 3160 | 3160 | **0** |

规矩是「只减不增」。所以第一步不是写功能，是**从别处腾出空间**——
而腾空间这件事顺手挖出了三类真问题（见下）。

### 腾空间的副产品：三类真问题

**① 三处 KDoc「抢位」——文档描述的函数根本不是它下面那个。**

| 位置 | 现象 |
|---|---|
| `ChatViewModel` | `friendlyMessage` 的文档被贴在 `summarizeServerError` 上面，两段 KDoc 相邻；`friendlyMessage` 本身（3023 行）**没有任何文档** |
| `ChatScreen` | 连续三段 KDoc，只有最后一段属于 `MentionSuggestionBar`；`PlanCard` 与 `AskUserCard` 各自**裸着** |
| `ChatScreen` | 页面级的 KDoc 被 `MenuTarget` 的 KDoc 顶掉——`ChatScreen` 函数自己没文档 |

Kotlin 里两段 KDoc 相邻不报错、后一段生效，所以这类错位**永远不会红**，
只会让下一个读代码的人对着「参数说明里写着 `@param e`」的函数找不到 `e`。
三处都搬回了各自的函数上方，并顺手压掉了冗余表述。

**② 注释里的数字与代码不符。**

类注释的「保留行为」清单写着 `maxSteps=40 / maxParallelTools=5 / providerRetry=3`，
而构造参数的默认值是 **60**（`@param maxSteps 单轮最大步数（默认 60…）`）。
同一份文档里两个数，读者只能猜哪个是真的。按代码为准改正，并删掉那句已经失效的
「与 core/AgentLoop.kt 适配层默认值必须一致」——适配层早就删了，照着去改会找不着。

**③ 我自己写的产物陈旧防护守了一片空白。**

新 harness 里照抄了 `qa_classes_stale` 的调用，但参数传错（把 `.../app/src/main` 当成了仓库根），
它内部拼出 `.../app/src/main/app/src/main`，永远命中空集合、永远返回「不陈旧」。
`touch` 一个源文件实测才发现——修好后正确输出 `exit 3` + `SKIP` 文案。

> 这条和上一轮那个「切片不含目标，变异后仍全绿」是同一个病：
> **断言/守卫必须先证明它会红，才算存在。**

### 链路

```
LLM 响应 finish_reason=length
  → FinishReason.normalize() → "length"            （上一轮已做）
  → RealAgentLoop：整回合第一次见到就发一次事件
  → AgentEvent.OutputTruncated(reason)
  → ChatViewModel._truncationNotice（新状态流）
  → ChatScreen：输入栏上方横幅 +「知道了」
```

四个刻意的取舍：

1. **每回合只报一次**。多步回合里每一步都可能被截断，逐步报会刷屏，
   而刷屏的后果是用户学会无视它。
2. **`stop` / `null` / 空串一律不报**。空集合满足全称命题——
   写 `!= null` 会把「正常说完」也当成截断，那是比不提示更糟的假警报。
3. **不落盘、不进历史**。截断是「这一轮的事实」，不是消息内容；
   塞进 `UiItem.Assistant` 要动存储格式，收益不抵风险。
4. **提示条的清除挂在 `start()` 而不是 `finish()`**。截断事件是在回合内抛的，
   紧接着 `onFinished` 就会跑——挂错地方等于刚显示就清掉。为此给
   `GenerationRunController` 加了 `onStarted` 回调（`send` / `regenerate` 的唯一汇聚点）。

### 验证（本轮**没有**可上的设备，全部在服务器上做）

新增 `scripts/qa/TruncationUiVerification.kt`：7 条断言 / 3 类场景。

| 用例 | 断言 |
|---|---|
| 1 | `length` → 恰好 1 次事件，reason 透传 |
| 2 | `stop` → 0 次 |
| 3 | `null` → 0 次（不伪造） |
| 4 | 空串 → 0 次 |
| 5 | 多步回合每步都截断 → **仍只 1 次** |
| 6 | 重试救回来的请求 → 照样上报（字段不因重试丢失） |
| 7 | `onEvent = null` → 不崩 |

**三次变异，每次都要能红**：

| 变异 | 期望 | 实际 |
|---|---|---|
| `isTruncated` 改成 `reason != null` | 用例 2、4 红 | `PASS=5 FAIL=2`，正是这两条 |
| 去掉 `&& !truncatedNotified` | 只有用例 5 红 | `PASS=6 FAIL=1`，事件数 3 不是 1 |
| 比较原始值 `"max_tokens"`（忘了已归一化） | 用例 1、5、6 红 | `PASS=4 FAIL=3`，三条正例全红 |

第三条最能说明问题：归一化是上一轮做的，如果这一轮有人图省事直接比厂商原始值，
**反例（stop/null/空串）依然全绿**，只有正例会红——没有正例的断言根本发现不了。

### 当前数字

- `run-all.sh`：**PASS=93 SKIP=1 XFAIL=0 FAIL=0**（SKIP 是未传 APK 的静态反验）
- 架构守卫：**5/5**，三个冻结文件均未超限，且都已下调上限
  （`RealAgentLoop` 1557→1553、`ChatViewModel` 3889→3888、`ChatScreen` 3160→3159）
- 文本断言：**1370 条未增**（新断言全部是行为断言，没有 `contains`）

> 顺带记一笔：`R13-1`（新建会话 200ms 内落盘）在满负载的 `run-all` 里偶发红了一次，
> 单独连跑 3 次全绿。那是**时间窗断言在慢机器上的固有脆弱**，不是本轮改动引起，
> 但它是下一个该收紧的地方（改成「等到落盘事件」而不是「等 200 毫秒」）。

---

## 2026-11-05 第六轮：运行层 / 框架层 / DeepSeek 五步收尾

五步清单的最后一步（启动顺序）落地。前四步分别是 R-01（供应商身份接线）、
R12（输出上限）、R13（HTTP 三预设）、R14（协程上下文）。

### 第 5 步：启动顺序从「注释约束」变成「数据结构」

原先十步初始化平铺在 `AgentKitApplication.onCreate`，顺序只存在于语句次序，
唯一表达约束的是一行注释「日志目录就绪——必须早于运行层初始化」。这行注释是真的
（`AgentLogger.init` 曾在 `ChatViewModel` 里，运行层更早跑，那段时间日志全是黑的），
但**注释拦不住重排**：谁把 logger 挪到 runtime 之后，编译过、启动正常、harness 全绿，
只有下次真机排障时才发现日志缺了一段。

改成 `startup/BootSequence.kt`：

| 项 | 设计 |
|---|---|
| 顺序 | `enum class Step` 的**声明序**（`entries` 保序），`describe()` 可打印 |
| 穷尽性 | `when(step)` 无 `else`，新增步骤不实现就编译红 |
| 失败语义 | 每步独立 `runCatching`，一步失败不阻断后续 |
| 跨线程 | 设备画像在新线程里，**内层**另有 `runCatching`（外层抓不到别的线程） |

`AgentKitApplication` 从 135 行降到 **53 行**，只剩 `BootSequence.run(this)` 与前台计数。

### 顺手补掉的两处

1. **租约节拍器的默认参数还在自建 scope**（`LeaseRegistry.start(scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))`）。
   R14 收口时漏了它——因为它是默认参数，不是字段，grep `private val scope` 找不到。
   改成 `AppScope.app`。现在全仓无 `SupervisorJob + Dispatchers.Default` 残留。
2. **`run-local-device-profile-verification.sh` 在 CI 里一直 SKIP**。它只查
   `command -v kotlinc`，而 CI 的 shim 造在 `/tmp/kotlinc/bin` 且**没进 PATH**。
   补上该分支后，这条从 SKIP 变真跑：**22 → 77 条通过**（多验了 55 条，全是行为断言）。

### 跟着搬的断言（不改就会假红）

初始化入口搬家，四处源码级断言必须跟着搬，否则第二天全绿变全红：

| 断言 | 原指向 | 现指向 |
|---|---|---|
| `CrashLogVerification` E2 | Application 含 `CrashLogger.install(this)` | BootSequence 含 `install(app)` |
| `run-local-device-profile` ×3 | Application 的线程/daemon/runCatching | BootSequence 同名片段，`profile(app)` |
| `run-native-crash-memory` | Application 含 `handleStartup(this)` | BootSequence 含 `handleStartup(app)` |
| `run-runtime-capability` ×2 | Application 的 `LocalLlmRuntime.init` / `LeaseRegistry.start()` | BootSequence 的 `init(app)` / `start(AppScope.app)` |

另有一条**不是搬家导致、是 R13 导致**的过期断言：`W46Verification` T1 写死了
`readTimeout(180, TimeUnit.SECONDS)`，而 R13 已把它参数化成 `build(connect, read, write, call)`。
改成断言三个预设值（180 / 30 / 120）+ 参数化构造存在。

### 验证

| 项 | 结果 |
|---|---|
| 全量 harness（沙箱，无 AGP 产物） | `PASS=57 SKIP=42 XFAIL=0 FAIL=0` |
| 架构守卫 | 5/5（`ChatViewModel` 3887，基线同步下调 3888→3887） |
| 文本断言 | **1370 未增** |
| 编译 | `BootSequence.kt` + `AgentKitApplication.kt` 用 kotlinc 1.9.25 + Android 桩编译通过（`BootSequence$WhenMappings` 生成 = when 穷尽） |
| 反向变异 | ① LOGGER 挪到最后 → B1/B3 + 3 条 B4 红；② Application 插一行 `AgentLogger.init` → A2 红；③ 去掉画像线程内 runCatching → C4 红 |

> C4 第一版**假绿**：范围用 `^            }` 收尾，一路吃到 `run()` 里的 `runCatching`，
> 于是无论内层有没有兜都判 PASS。改成卡到 `.start()` 且只认真实调用（不认注释里的
> 「runCatching」字样）之后，变异才红。**没做过变异的断言，不知道它在守什么**——
> 这条已经是本项目第三次踩同一个坑。
>
> 同一轮还踩了它的镜像版本：`LeaseRegistry` 的注释里写了旧写法
> `CoroutineScope(SupervisorJob() + Dispatchers.Default)` 作为「已修掉」的说明，
> D2 的 grep 立刻假红。结论是双向的——**断言不认注释，所以注释里也不要写会被 grep 的字面量**。

### 仍需构建机确认

```bash
./gradlew :app:compileDebugKotlin
bash scripts/qa/run-all.sh
```

沙箱里 `run-provider-id-wiring-verification.sh` / `run-crashlog-verification.sh` 会 SKIP
（缺 `app/build/tmp/kotlin-classes/debug`），这是正常路径；有 AGP 产物后 PASS 数会从 57 涨到 90+。

---

## 2026-11-05 第七轮：R-02（标题写错会话）与 R-04（10 失败判 PASS）

### R-02

`send()` 开头早就捕获了 `val owner = _activeConversationId.value`（hotfix30：在途写按发起会话落盘），
但回合末尾的标题生成又读了一次 `_activeConversationId.value`。生成期间切会话，
标题就 rename 到**新会话**：旧会话永远叫「新对话」，新会话被改成上一轮的问题摘要。
两个会话同时错，且都不报错。

改法一行：`maybeGenerateTitle(owner, text)`。`ChatViewModel` 仍 **3887 行**（注释压回原行数）。

断言守的是「参数贯通」这一层：`maybeGenerateTitle` 函数体内**不出现**全局会话变量，
就不可能写错会话。7 条，含变异验证（改回 `_activeConversationId.value` → A2/A3 精确红）。

### R-04

`run-all.sh` 原来一条正则：`0 failed|FAIL=0|FAIL: 0|0 失败|...`。
`10 失败` 含子串 `0 失败`，直接判 PASS；而这类脚本末尾是 `echo`、没开 `set -e`，rc 也是 0——
两条证据同时失效。

改成两道独立防线：

| 防线 | 作用 | 场景 |
|---|---|---|
| 计数加左边界 | `(^|[^0-9])0 失败` 不再命中 `10 失败` | 只有「10 失败」一行 |
| 失败优先于通过 | 结果行写了非零失败，先判 FAIL | 「子步骤 FAIL=0 + 总结果 3 失败」两行都在 |

第二道只读**最后一行非空输出**，不 grep 全文。

> 第一版按「含汇上下文词的行」判，上线即把 `run-runall-verification` 自己的日志判红：
> 那条自测会打印「场景 15：子步骤 FAIL=0 + 总结果 3 失败」——**描述失败的文字**被当成失败本身。
> 和 `zero_assertions` 当初被「0 通过 / 0 失败（空跑）」骗过是同一种错。
> 自测从 13 个场景扩到 16 个（新增 10 失败、失败优先、不误杀三个），32 条断言全绿。

### 连带修的两条过期断言

- `run-local-inference-perf-verification.sh` 用行号比较「标题生成在本轮之后」，写死了
  `maybeGenerateTitle(_activeConversationId.value, text)`。改成匹配调用行（排除定义行），
  断言的是位置不是参数——参数一改就红的断言，守不住它本来要守的东西。
- `run-all` 自测被新判据误杀，见上面的引用块。

### 验证

| 项 | 结果 |
|---|---|
| 全量 harness | **PASS=58 SKIP=42 FAIL=0** |
| 架构守卫 | 5/5（`ChatViewModel` 3887 未涨） |
| R-02 断言 | 7/0，变异精确命中 |
| R-04 自测 | 32/0，禁用「失败优先」后场景 15 精确红 |

---

## 2026-11-05 第八轮：R-06（中断后残留「执行中」工具卡）

### 症状有两副面孔，登记册写错了一副

| 时刻 | 看到的 | 实际 |
|---|---|---|
| 停止之后（未切换） | 那张卡一直转圈 | 显示态里 `running=true` 没人翻。`regenerate` 的取消路径 `rebuildMessages=false`，列表不重建，于是转到用户切走为止 |
| 重新载入之后 | 「已完成」+ 空结果 | `running` 从来没落盘，`toUiItem` 一律给 `false`，`status()` 落到 `else -> DONE` |

登记册原写「重载永久转圈」，与代码走查不符：重载后**不转圈**，而是对着一条没跑完的调用
说「已完成」——比转圈更糟，转圈至少还在说「我在等」。已按事实改写条目。

### 根因两处

1. **落库被绑在「显示命中」上**（`ChatViewModel:2059`）：
   `if (projection.finishTool(...)) appendToStore(done)`。用户切走后 `finishTool` 返回 false
   （列表里没有这张卡），终态于是**既不换显示也不落库**，空占位永久留在 `conversations.json`。
   上一行注释写的是「切走期间只落盘」——代码做的正好相反。
2. **`running` 只活在内存里**：重载时无法区分「没跑完」和「跑完了没输出」
   （静默命令、grep 无匹配都是空结果）。

### 改法

| 层 | 改动 |
|---|---|
| 存储 | `MsgRecord.pending`（tool 专用，默认 false，旧文件照读）；`settlePendingTools(convId)` 幂等收口 |
| 投影 | `settleInterrupted(convId)` 双写（显示态翻转 + 存储补齐）；`loadOf` 只在 owner 就是该会话时保留转圈 |
| 运行控制器 | 新增 `onSettling(owner)`，在 `owner` 清空**之前**调用；`runCatching` 包住（收口炸了不能连累复位，否则发送键永久灰） |
| 事件 | `ToolFinished` 落库无条件，显示替换独立判断 |
| UI | 第五态「已中断」+ 中性色；颜色与文案合并成一次 `when`（新增状态时不可能漏改一处） |

**为什么不能用「结果为空」判中断**：B1/B2/B3 三条反向用例专门守这个——正常空输出会被
改写成「已中断」，同一轮一真一假时还会多收一条。`pending` 字段的存在理由就是它。

### 变异（5 次全部精确命中）

| 变异 | 变红的用例 |
|---|---|
| `pending` 恒 false（回到改动前） | A1–A5、B2/B3、D1–D3 |
| 按「结果为空」判中断 | B1、B2、B3 |
| 收口只写存储、不翻显示态 | E1 |
| `finish` 里不调 `onSettling` | F1、F3 |
| `loadOf` 不翻「已中断」 | D1、D3 |

### 这条为什么做成了行为 harness，而不是 grep

前面几轮的断言大多是源码级 grep。这一条不行：缺陷的全部内容是「同一张卡在两个时间点被
写成两种状态」，grep 只能证明函数存在，证明不了「切走再切回那张卡显示什么」。所以
`run-r06-verification.sh` 直接编译真实的 `ConversationStore` / `ConversationProjection` /
`GenerationRunController` 跑 18 个用例，**不需要 AGP 产物**，沙箱里也真跑。

> 两次假红都出在用例自己身上，记下来免得下次误诊为产品 bug：
> ① `ConversationStore.flush()` 是**异步**的（把任务插进单线程队列），`flush()` 完立刻
> 读文件必然读不到——第一版 A5 就是这么红的；
> ② 只等「计数变了一次」不够：`create()` 自己也排了一次紧急写，等到的是**别人**那次。
> 改成等到计数静默（不再变化）才稳。

### 数字

| 项 | 结果 |
|---|---|
| R-06 行为用例 | 18/0 |
| 接线断言（G1–G6） | 6/0 |
| 全量 harness | **PASS=59 SKIP=42 FAIL=0** |
| 架构守卫 | 5/0（`ChatScreen` 3159→**3158**，`ChatViewModel` 3887 未涨） |
| 文本断言 | 1370 未增 |

### 仍需构建机确认

```bash
./gradlew :app:compileDebugKotlin
bash scripts/qa/run-all.sh
```

`ChatScreen` 的 Pair 解构与 `ChatViewModel` 的 `onSettling` 接线在沙箱里编不了（拖 Compose
依赖），只有 G1–G6 的静态断言守着；构建机上跑一次编译即可确认。

---

## 2026-11-05 第九轮：R-03（审批超时写在 Composable 里）

### 症状链

```
用户切到设置页
  → ChatScreen 离开组合树 → ApprovalDialog 被卸载
  → 它的 LaunchedEffect 被取消 → vm.denyApproval() 再没人调
  → deferred.await() 永久挂起
  → approvalMutex 被长期持有
  → 后续每个危险工具都在等这把锁 → Agent 回合永不收尾
  → 发送键一直是灰的；而用户**根本看不见**有个审批在等
```

关键点：这不是「超时写得不够长」，是**超时的执行者本身被卸载了**。倒计时挂在
`ApprovalDialog` 的 `LaunchedEffect` 里，而这个 Composable 是 `ChatScreen` 的子组合——
只要用户不在对话页，它就不存在。

### 改法

| 项 | 改动 |
|---|---|
| 语义 | 新增 `core/agent/ApprovalDeadline.kt`：`awaitApprovalDecision(deferred, timeoutMs)` = `withTimeoutOrNull(...) ?: false` |
| 接线 | `requestApproval` 的 `deferred.await()` → `awaitApprovalDecision(deferred)` |
| 单点 | `APPROVAL_TIMEOUT_MS = 30_000L` 定义一处；弹窗的 `APPROVAL_TIMEOUT_SEC` 由它派生 |
| UI | 倒计时循环**只做展示**（进度条 + 无障碍播报），不再自己 `denyApproval()` |

超时从「UI 的倒计时」变成「ViewModel 的等待边界」，与弹窗是否在组合树上无关。

### 为什么取消必须往外抛

`withTimeoutOrNull` 对**外部取消**抛 `CancellationException` 而不是返回 `null`。这个区分
不能用 `runCatching` 抹掉：抹掉之后，用户点「停止」会被记成一次正常的「用户拒绝」，
协程继续往下跑几步。C1/C2 两条用例专门守它——变异（套一层 `runCatching`）后两条精确红。

### 验证

| 项 | 结果 |
|---|---|
| 行为用例（A/B/C/E 四组） | 9/0 |
| 接线断言（G1–G5） | 5/0 |
| 全量 harness | **PASS=60 SKIP=42 FAIL=0** |
| 架构守卫 | 5/0（`ChatViewModel` 3887→**3886**，`ChatScreen` 3159→**3157**，基线同步下调） |
| 文本断言 | 1370 未增 |

| 变异 | 变红的用例 |
|---|---|
| 超时兜底 `?: false` → `?: true`（最危险的方向） | B1、B3 |
| 套 `runCatching` 吞掉取消 | C1、C2 |
| ViewModel 退回裸 `deferred.await()` | G1 |
| 弹窗倒计时里加回 `denyApproval()` | G2 |

> `?: true` 那个变异值得单独说：把兜底方向写反，超时等于**自动放行危险操作**，
> 而所有「函数存在」「有 withTimeoutOrNull」的 grep 断言都还是绿的。
> 这类缺陷只能靠行为用例抓——这也是这一轮没做成纯静态断言的原因。

### 仍需构建机确认

```bash
./gradlew :app:compileDebugKotlin
bash scripts/qa/run-all.sh
```

`ChatViewModel` / `ChatScreen` 的改动在沙箱里编不了（拖 Compose）。类型链是
`CompletableDeferred<Boolean>` → `Boolean`，`APPROVAL_TIMEOUT_MS / 1000` 是 `Long → Int`，
已逐处核对，但仍需一次真编译确认。

---

## 2026-11-05 第十轮：R-05（消息列表无 key）

### 这不是性能问题

`itemsIndexed` 不传 `key` 时，Compose 拿**下标**当身份。工具卡一插入，其后每一项的身份
都换了——整表按新身份重建。用户看到的是：滚动位置跳一下、正在展开的工具卡自己合上、
图片闪一下。登记册写的「整表重排」其实是这些的总和。

### key 从哪来

不另造一套 id，用数据模型自己的主键：

| item | key | 为什么它是稳定的 |
|---|---|---|
| 工具卡 | `t:<call.id>` | 与落盘同一个 id，从 ToolStarted 到终态再到重载都不变 |
| 已归属轮次的气泡 | `u:<turnIndex>` / `a:<turnIndex>` | 每轮恰好一个用户气泡 + 一个选中变体 |
| 未归属的（实时草稿、trace 中间消息） | `u@<index>` / `m@<index>` | 它们本来就没有稳定身份，硬编一个反而会在「草稿转正」时制造一次身份迁移 |

放在 `core/UiItem.kt` 的 `UiItem.uiKey(index)`：key 只由数据字段决定，不含任何 Compose 类型，
所以能被 harness 直接编译、直接跑。

### 修 bug 修出来的白屏风险

第一版直接写 `"t:" + call.id`。历史文件里若有 id 为空的工具卡（手工编辑过、早期版本落盘），
多张卡会撞同一个 `"t:"` —— **Compose 遇到重复 key 直接抛 IllegalArgumentException，整个对话页白屏**。
修一个抖动换来一个白屏，比原 bug 严重得多。

现在空 id 退回下标兜底，C2 守着，变异（去掉兜底）后精确红。

### B3 是 R-05 与 R-06 的交汇点

R-06 让工具卡从「执行中」到终态时**原位替换**。如果 key 里掺了 `result` 或 `running`，
这次替换就变成了「旧的消失、新的出现」——用户刚展开的详情会合上。B3 专门断言这一点。

### 验证

| 项 | 结果 |
|---|---|
| 行为用例（A/B/C 三组） | 10/0 |
| 接线断言（G1–G2） | 2/0 |
| 全量 harness | **PASS=61 SKIP=42 FAIL=0** |
| 架构守卫 | 5/0（`ChatScreen` 3157，**正好卡在冻结线**） |
| 文本断言 | 1370 未增 |

| 变异 | 变红的用例 |
|---|---|
| `uiKey` 全部退回 `"$index"`（等于没修） | A1、A2、A4、B1、B2 |
| 工具卡改用工具名当身份 | A2、C1（20 项塌成 13 个 key） |
| ChatScreen 去掉 `key =` | G1 |
| 空 id 不兜底 | C2 |

> 「用工具名当身份」那个变异很说明问题：一次任务里 `ui_tap` 会用很多次，全部塌成同一个
> `t:ui_tap`。C1 报的是「20 项只剩 13 个 key」——如果不做这个用例，这个改法在真机上
> 的表现是「打着打着白屏」，而不是任何可归因的报错。

### 注意：ChatScreen 的行数余量已经用尽

3157 是冻结线，这一轮为了塞进 key 断言把三处注释压成了一行。**下一轮若还要改 ChatScreen，
必须先还债**（拆组件或删死代码），不能再靠压注释凑。

### 仍需构建机确认

```bash
./gradlew :app:compileDebugKotlin
bash scripts/qa/run-all.sh
```

`itemsIndexed(..., key = ...)` 的 lambda 类型与 `uiKey` 的可见性（core 顶层 public 函数）在
沙箱里编不了，靠 G1 的静态断言 + 类型链人工核对；构建机跑一次编译即可确认。

---

## 2026-11-12 云端 x86_64 模拟器（API 30）实测

背景：两台可 SSH 的云端 Debian 机器，其中一台带完整 Android SDK（build-tools 34 /
platform-34 / NDK 26.1 / cmake 3.22.1）和一台常驻的 Android 11 x86_64 模拟器（KVM 可用）。
用它把「只能在真机做」的项往前推了一格。

### 验到的

| 项 | 结论 | 证据 |
|---|---|---|
| 装包 / 冷启动 | 通过 | `-PnoNative=true` 产出可装；无崩溃、无 ANR。`am start -W` 报 `Status: timeout`，但首帧最终落在 `Displayed +22s191ms`——模拟器无硬件 GPU（`Skipped 775 frames`），不是应用卡死；启动分段日志只有 `ViewModel 初始化 1517ms` + `工具装配 56 项 266ms` |
| 能力清单零偏差（O10） | 通过 | 日志里没有任何 `能力清单有 N 处不同步`；`工具装配 56 项` |
| 启动顺序（O14 判据） | 通过 | 首帧 `16:25:24.520` 早于 `[BrowserHost] 开始创建后台 WebView` `16:25:26.927`——WebView 确实排在首帧之后，修的东西还在 |
| P4 失败路径不借租约 | **通过** | 三件套缺失时日志只有 `ERROR [ProotRunner] assets/bin/x86_64 三件套不齐…`，**没有** `容器租约就绪`；重资源页不出现 `容器会话` 行 |
| P1 租约就绪（半步） | **通过** | 补上 x86 rootfs 与终端 JNI 后点启动，出现 `[ContainerLeases] 容器租约就绪：container:alpine-x86_64`，重资源页出现 `容器会话 · alpine-x86_64` |
| P2 退出归还（半步） | **通过** | 会话退出（`终端会话结束，退出码=1`）后再看重资源页，`容器会话` 行消失、只剩 `本地模型`；`ps -A \| grep -c proot` = 0 |
| P5 诊断包（文件数 + 回执） | **通过** | 点「诊断包」后系统分享面板显示 `polaris-diagnostics-20261107-165549.txt + 1 file`，即两个文件；回执文案见 `LogsScreen.kt:206`「诊断包已生成（含回合事件），选择应用发送」 |

### 没验到的，以及为什么

- **容器跑命令**：proot 报 `execve("/bin/sh"): No such file or directory`。rootfs 是完整的
  （`lib/ld-musl-x86_64.so.1` 649KB 在、`bin/sh -> busybox` 在、busybox 是 x86-64 ELF），
  所以不是解压问题，是 Termux 版 proot 在 x86_64 模拟器内核上的已知边界
  （`fetch-x86-testkit.sh` 开头就写了「仍可能 execve 失败」）。**P1/P2/P3 的「真跑命令」
  那一半仍需 arm64 真机。**
  UI 侧行为是对的：proot 的原始报错直接显示在终端里 + `[进程已退出，退出码 1]`，
  没有假绿成「已启动」。
- **P5 的「多事件 ndjson」**：导出这一步验过了（两个文件、回执文案对），但 ndjson 里只有
  1 条 `store.write`——没跑对话就没有 `turn.start` / `llm.call` / `turn.end` 那一串。
  要验完整流水仍需一次真实对话（模型 Key）。
- **P6 截断提示**：同上，需要真实模型真的撞到 `finish_reason=length`。

### 顺带修掉的两个真问题

1. `scripts/fetch-x86-testkit.sh` 把 proot 包名写死成 `proot_5.1.107.100_x86_64.deb`，
   上游升到 `.102` 后该 URL 404，脚本第一行 curl 就退出（`set -e`），
   表现得很像「网络不通」。改成先列目录取最新、取不到再回退 `.102`。
2. `-PnoNative` 会把所有 `.so` 排掉，终端 JNI 一起没了，于是容器页必然停在
   `libagentkit-termux.so 不可用`，「租约就绪」这一步在 x86 上永远到不了。
   新增 `app/src/main/cpp/termux-only/CMakeLists.txt` 与 gradle 的 `termuxX86` 分支：
   `-PnoNative=true -Px86Rootfs=true` 时只编终端 JNI（只出 x86_64 一个 ABI），llama 仍不编。
   默认路径与 CI 完全不变（`termuxX86` 要求同时给两个属性才为真）。

### 命令行照抄

```bash
bash scripts/fetch-x86-testkit.sh          # 拉 x86 rootfs + proot 三件套（不进 git）
./gradlew :app:assembleDebug -PnoNative=true -Px86Rootfs=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 设置 → 工具 → 容器 → Alpine Linux（x86_64 调试）
```
