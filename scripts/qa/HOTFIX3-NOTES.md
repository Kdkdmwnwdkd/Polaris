# 真机第三轮 Hotfix3 · 工程师（寇豆码）交付说明

> 范围：A 重试退避加长 + 网络恢复即刻重试；B `ui_click` 匹配增强；C 屏幕捕捉日志确认。
> 环境：Kotlin 1.9.25 / AGP 8.5.0 / minSdk 26 / targetSdk 34；**零新增外部依赖**、**零新增 JUnit**。
> 编译：`/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks`。本轮**不推送、不碰网络**。

---

## 1. 文件清单

### 生产代码

| 文件 | 改动 | 说明 |
|---|---|---|
| `core/src/main/java/com/beichen/polaris/core/agent/RealAgentLoop.kt` | 改 | A1 退避序列改可配常量 `3s → 10s → 25s`；新增 `networkRecovery` / `backoffPlan` 两个可注入参数；默认退避钩子改为「网络感知」`RetryBackoffPolicy.networkAware(plan, waiter)`；`callWithRetry` 语义不变 |
| `core/src/main/java/com/beichen/polaris/core/agent/NetworkRecovery.kt` | **新增** | `NetworkRecoveryWaiter`（可注入接口，纯 Kotlin）+ `TIMER_ONLY`（纯定时降级）+ `ConnectivityNetworkRecoveryWaiter`（Android 默认实现，`registerDefaultNetworkCallback`） |
| `app/src/main/java/com/beichen/polaris/automation/AgentAccessibilityService.kt` | 改 | 新增纯函数决策层 `UiNodeMatcher` + `UiNodeSummary` / `UiMatchResult`（internal，纯数据/纯函数）；`clickByText` / `clickByResourceId` 改为「取数据 → 纯函数决策 → 执行动作」；新增只读 `lastClickCandidates` |
| `app/src/main/java/com/beichen/polaris/automation/UiAutomationTools.kt` | 改（6 行） | `ui_click` 失败时把候选写进 `hint`（见 §5 越界说明） |
| `app/src/main/java/com/beichen/polaris/automation/ScreenshotBridge.kt` | 改（≤5 行） | C：确认 `lastError` 已进日志；补统一关键字出口 `logLastError()` 并在 3 处写入点调用 |
| `core/src/main/java/com/beichen/polaris/core/AgentLoop.kt` | 改（3 行） | 透传 `networkRecovery`（见 §5 越界说明） |
| `app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt` | 改（2 行） | 注入 `ConnectivityNetworkRecoveryWaiter(app)`（见 §5 越界说明） |

### QA

| 文件 | 说明 |
|---|---|
| `scripts/qa/Hotfix3Verification.kt` | **新增** harness，23 条断言全绿 |
| `scripts/qa/run-hotfix3-verification.sh` | **新增**（照 `run-verification.sh` 的离线模式：kotlin-compiler-embeddable + `-Xfriend-paths` + main 手写断言） |
| `scripts/qa/HotfixVerification.kt` | 改（Bug1-A 序列期望值 1s/2s/4s → 3s/10s/25s；断言条数不变 = 17） |

---

## 2. A：退避加长 + 网络恢复即刻重试

### 2.1 退避序列（A1）

`RetryBackoffPolicy.BACKOFF_SEQUENCE_MS = [3_000, 10_000, 25_000]`，越界取末项封顶，
非法序号（≤0）按第 1 次处理。`backoffMillis(attempt)` 只查表——**改这一处即可整体调节等待时长**。

**⚠️ 关键裁决（与主理人给的「总计约 38 秒」有出入，请裁决）**：
`providerRetry=2` 时只有 **2 次等待**（第 3 次失败后不再等待），实际总等待 = `3s + 10s = 13s`，
不是 38s；38s 是序列三项之和，需要 `providerRetry=3` 才会走满。
本轮按「3 次尝试总数不变」这条硬约束实现，**未动 providerRetry**。
若主理人认定要真到 ~30s+，二选一（都是一行改动）：
- `BACKOFF_SEQUENCE_MS = [13_000, 25_000, 25_000]`（两次等待 ≈ 38s）；或
- `providerRetry` 默认改 3（会变成 4 次尝试，与「总数不变」冲突）。

### 2.2 网络恢复即刻重试（A2）

- 接口 `NetworkRecoveryWaiter.awaitRecovery(timeoutMs): Boolean`：true = 被网络恢复打断 → 立即重试。
- Android 默认实现 `ConnectivityNetworkRecoveryWaiter`：`ConnectivityManager.registerDefaultNetworkCallback`；
  `onAvailable` → `CompletableDeferred.complete` → `withTimeoutOrNull` 提前返回。
- **只在「开始等待时没有默认网络」才注册回调**：网络本来就通（429/5xx）时若也注册，回调会被
  立刻触发而退化成「不等就重试」，反而打爆限流——故那种情况按序列等满。
- 注册/注销 `try/finally` 严格配对（含取消路径）；拿不到 ConnectivityManager / 注册失败 / 注销失败
  一律 WARN + 降级为纯定时等待，**绝不上抛**。
- `ACCESS_NETWORK_STATE` 权限 Manifest 已有，未新增。

### 2.3 不变量（逐条保持）

| 不变量 | 保障 |
|---|---|
| `RetryStarted` 在等待**之前**抛出 | 顺序未改（先 `onEvent`，后 `retryBackoff`） |
| 等待中取消 → 立即退出、原样上抛 `CancellationException` | `delay`/`await` 可取消 + 钩子返回后 `ensureActive()`；`finally` 注销监听 |
| 401/403/400 零等待 | 不可重试分支直接 throw，不触碰退避与等待器 |
| 总请求数 = providerRetry + 1 | 未改循环 |
| 横幅不残留 | `finally` 恰好一次 `RetryFinished` |

### 2.4 可测性

`networkRecovery` 与 `backoffPlan` 都是构造参数，harness 注入 `FakeNetworkRecovery` + 小值序列 →
整套用例保持毫秒级（**满足「run-retry / run-hotfix 必须注入即时退避」的时长硬要求**；
本轮新增的 hotfix3 也全部用假等待器 + 小值，不真等 3s/10s/25s）。
core 层保持零 Android 依赖（Android 实现单独放 `NetworkRecovery.kt`，`RealAgentLoop` 默认值只用
纯 Kotlin 的 `TIMER_ONLY`），因此 harness 在纯 JVM 下构造 `RealAgentLoop` 不会触发 `NoClassDefFoundError`。

---

## 3. B：`ui_click` 匹配增强

旧实现：`t.equals(text, ignoreCase = true)` 精确相等 → 设置页真实文本带空格/换行/附带字时必然落空，
且失败无候选 → 工具报 `click_not_found` → 模型只能瞎换成 `ui_tap`。

新实现（**全部抽成 internal 纯函数**，输入节点摘要列表 + query，输出命中/候选）：

1. **分级匹配**：① 归一化后精确 → ② 包含 → ③ 前缀（`q.startsWith(nodeText)`，即节点文本是 query 的前缀）。
   归一化 = NFKC（全角↔半角）+ 去所有空白 + 转小写；`text` 与 `contentDescription` **同等参与**（取更优等级）。
   注：三级顺序必须是「精确 → 包含 → 前缀」，否则包含会遮蔽前缀，第三级永远不可达（本轮实测发现并修正）。
2. **多命中取最深/最具体**：等级优先 → 深度大 → 文本短 → 序号小。
3. **不可点击则上溯最近可点击祖先**；一路到根都不可点 → 退回命中节点自身（等价旧行为，绝不返回空）。
4. **未命中给候选**：按包含关系/公共前缀打分，去重后取前 5（≤5，hint 不能太长）；query 为空时兜底给屏幕前几个标签。
5. `clickByResourceId` 同样分级 + 上溯 + 候选（候选为屏幕上的短 id）。

**契约不变**：`ui_click` 仍是 `readOnly = false` / `needsApproval = true`；失败不抛异常；
`ToolResult{ok,code,message,hint,data}` 统一契约不变（只是 `hint` 更长了，多了候选）。
Android 侧只做「取数据（`collectSummaries`）→ 调纯函数 → 执行动作（`tapNodeByIndex`）」，
`UiNodeMatcher` 一行 Android 都不碰，故可在纯 JVM 下逐条断言。

---

## 4. C：屏幕捕捉可观测性（只确认）

确认结论：**`lastError` 已进 AgentLogger**。三处非空写入全部带日志：

| 写入点 | 现有日志 |
|---|---|
| `reportFailure()` | `AgentLogger.e("ScreenCapture", msg)` |
| `startService()` 未授权 | `AgentLogger.w(... "未取得授权就启动捕获服务：$lastError")` |
| `startService()` `startForegroundService` 失败 | `AgentLogger.e(..., failure.cause)` |
| `ScreenCaptureService` 两条失败路径 | 均经 `ScreenshotBridge.reportFailure` → ERROR |

本轮只补了一个**统一关键字**的日志出口 `logLastError()`（3 行）+ 3 处调用，
方便用户/QA 用 `lastError 落库` 这一个关键字 grep 到全部失败原因；**未改启动流程**。
harness 用**源码扫描**断言（不加载 Android 类）：每处非空写入后 4 行内必有日志出口。

---

## 5. 关键裁决 / 越界说明（请主理人知悉）

1. **越界 3 个文件（各 2~6 行，均必要）**：
   - `UiAutomationTools.kt`：候选不进 `hint` 就等于没修（hint 是工具层拼的，服务层够不到），故加了 6 行。
   - `AgentLoop.kt` + `ChatViewModel.kt`：`ConnectivityManager` 需要 `Context`，而 core 层必须零 Android 依赖
     （否则 harness 在纯 JVM 下会 `NoClassDefFoundError`）。只能由持有 `app: Application` 的 ViewModel 注入，
     经薄适配层透传。**若不允许碰这两个文件，A2 在真机上不会生效**（会退化成纯定时退避）。
2. **退避总时长**：见 §2.1，13s vs 38s 请裁决。
3. **网络本来就通时不走「即刻重试」**：为避免 429/5xx 被打成原地冲锋（见 §2.2）。

---

## 6. 自检证据

### 编译

```
/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks   → BUILD SUCCESSFUL
```

### 新 harness（23/23）

```
== 真机第三轮 Hotfix3（A 退避+网络恢复 / B ui_click 匹配 / C 日志）QA 行为验证 ==
== A1：退避序列 3s → 10s → 25s 封顶（可配常量） ==                       PASS
== A2：网络恢复打断等待 → 立刻重试（不干等） ==                          PASS
   （计划 5s×2，网络 120ms 恢复 → 总耗时 < 2s；注册 2 / 注销 2；RetryStarted 先于等待）
== A3：无网络事件 → 按序列等满 ==                                        PASS  （60/120ms 等满）
== A4：退避等待中取消 ==                                                 PASS  （CE 上抛、注销 1、无新请求、无残留）
== A5：401 / 首次成功 → 零等待 ==                                        PASS  ×2
== A6：默认等待器纯定时降级 ==                                           PASS  ×2
== B1：归一化精确 + 多命中取最深 ==                                      PASS  ×4
== B2：包含 / 前缀 ==                                                    PASS  ×2
== B3：上溯最近可点击祖先 ==                                             PASS  ×3
== B4：未命中候选 ≤5（含空 query 兜底） ==                               PASS  ×3
== B5：resource-id 分级 + 上溯 + 候选 ==                                 PASS  ×2
== C：lastError 进日志（源码扫描） ==                                    PASS
PASS: 23  FAIL: 0  TOTAL: 23   HOTFIX3_VERIFICATION: ALL PASS
```

### 回归十三套（全绿）

| 套件 | 结果 |
|---|---|
| run-verification | 32 passed, 0 failed |
| run-t01-verification | 35 passed, 0 failed |
| run-t01-probe | 11 passed, 0 failed |
| run-t01-probe2 | 7 passed, 0 failed（本轮未复现 R9 偶发失败） |
| run-appcontrol-verification | 31 passed, 0 failed |
| run-t07t08-verification | 35 passed, 0 failed |
| run-t07t08-adversarial | 17 passed, 0 failed |
| run-markdown-verification | 45 passed, 0 failed |
| run-branch-verification | 20 passed, 0 failed |
| run-retry-verification | ALL PASS（9，已注入即时退避） |
| run-localmodel-verification | ALL PASS（19） |
| run-fallback-verification | 16 passed, 0 failed |
| run-hotfix-verification | ALL PASS（17，已注入即时退避/小值；序列期望值已同步新常量） |

---

## 7. 遗留

1. **退避总时长裁决**（§2.1）：13s vs 38s，等主理人一句话，改一行即可。
2. **`ConnectivityNetworkRecoveryWaiter` 无法在 JVM harness 覆盖**：注册/注销真机行为只能靠
   `try/finally` 结构保证 + 真机验证；harness 侧用假等待器断言「注册/注销配对」的**调用契约**。
3. **未做真机验证**：本轮只到编译 + harness（按分工，真机由主理人/QA 侧安排）。
4. `AgentAccessibilityService` 编译仍有 `recycle(): Unit is deprecated` 警告（原有代码风格，非本轮引入）。
