# RETRY-NOTES · Phase 2 DoD③「断网/失败自动恢复且 UI 可见」

> 交付人：寇豆码（Kou）· 工程师 ｜ 范围：仅代码 + 自检，**不推送、不碰网络**。
> 快照独立，等网络队列（当前已有 7 批排在队列）。

---

## 1. 需求回放（路线图 Phase 2 DoD ③）

「断网时 UI 显示『正在恢复（n/N）』」。

现状：`RealAgentLoop.callWithRetry` 已实现 429/5xx/网络异常重试（401/403/400 快败、
取消不重试），但**过程对用户完全不可见**——用户只看到"卡住"。本批让重试过程可观测，
并在 UI 呈现一条轻量横幅，恢复成功即消失，最终失败仍走既有错误气泡。

---

## 2. 改动文件清单

| # | 文件 | 改动性质 |
|---|------|----------|
| 1 | `core/src/main/java/com/beichen/polaris/core/AgentLoop.kt` | 扩展 `AgentEvent`（+2 子类型）|
| 2 | `core/src/main/java/com/beichen/polaris/core/agent/RealAgentLoop.kt` | `callWithRetry` 抛重试事件 + `retryReason` 纯函数 |
| 3 | `app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt` | `RetryState` + `retryState` StateFlow + `when` 补分支 + 收尾兜底清 |
| 4 | `app/src/main/java/com/beichen/polaris/ui/ChatScreen.kt` | `RetryBanner` 横幅 + 接线 |
| 5 | `scripts/qa/RetryVerification.kt` | 新建 harness（9 用例）|
| 6 | `scripts/qa/run-retry-verification.sh` | 新建离线 runner |

---

## 3. 落点裁决：扩展 `AgentEvent`（而非独立 onRetry 回调）

**裁决**：扩展 `AgentEvent` sealed interface，新增两个子类型：

```kotlin
data class RetryStarted(val attempt: Int, val max: Int, val reason: String) : AgentEvent
data object RetryFinished : AgentEvent
```

**理由**：
1. 与既有「过程事件」通道同源——UI 已消费 `AgentEvent`（Hotfix2 起），复用同一条
   回调链，**零新增构造参数**（`AgentLoop` 适配层 / `RealAgentLoop` 的 `onEvent`
   签名不变，无需层层透传新回调）。
2. harness 可对**全量事件流**做断言（顺序、种类、收尾时机），比回调更易验证。
3. 若走独立 `onRetry` 回调，需同时改 `RealAgentLoop` + 适配层 `AgentLoop` +
   `ChatViewModel` 三处构造签名，侵入面更大。

**代价（已知坑，已处理）**：Kotlin 1.9 对 sealed 的 `when` 有**穷尽性硬约束**。
新增子类型后，全仓**唯一**对 `AgentEvent` 做穷尽 `when` 的点是
`ChatViewModel.handleAgentEvent`——已**同步补两个分支**，否则 `compileDebugKotlin`
必失败。（另两处 `when (event)` 在 `RealAgentLoop.chatOnce` 与 `T01Verification.kt`，
均是对 `ChatStreamEvent` 的 `when`，不受影响——已逐一核对。）

---

## 4. 语义设计（`callWithRetry`）

```kotlin
var retrying = false
try {
    repeat(providerRetry + 1) { attempt ->
        try { return block() }
        catch (cancel: CancellationException) { throw cancel }   // 取消绝不重试
        catch (e: Throwable) {
            last = e
            if (!isRetryable(e)) throw e                          // 401/403/400 快败，无事件
            if (attempt < providerRetry) {                        // ← 仅当确实还有下一次尝试
                onEvent?.invoke(AgentEvent.RetryStarted(attempt + 1, providerRetry, retryReason(e)))
                retrying = true
            }
        }
    }
    throw last ?: IllegalStateException("未知错误")
} finally {
    if (retrying) onEvent?.invoke(AgentEvent.RetryFinished)       // 成功/最终失败/取消统一收尾
}
```

关键裁决：
1. **`attempt < providerRetry` 守卫**：只有**确实还会重试**时才抛 `RetryStarted`。
   避免在最后一次尝试失败时抛出 `attempt=max+1` 的序号越界事件。据此：
   - `providerRetry=2`、两次失败后成功 → `RetryStarted(1,2)`、`RetryStarted(2,2)` → 成功
     → `RetryFinished`。总请求 3 次。**与需求示例 (1/2)、(2/2) 完全一致**。
   - `providerRetry=2`、全部失败 → 同样两个 `RetryStarted` → `RetryFinished` → 抛异常。
2. **`finally` 统一收尾**：成功 / 最终失败 / 取消三条路径都恰好抛**一次** `RetryFinished`。
   - 取消路径：`finally` 内的 `onEvent?.invoke(...)` 是**非挂起**调用，取消状态下照常执行
     → **立即清除状态**，满足「取消不算失败但必须立刻清掉正在恢复」（T05 不变量）。
3. **`retrying` 标志**：首次即成功 / 非可重试快败时**从不**抛 `RetryStarted`，收尾也
   不空抛 `RetryFinished`（无状态可清）。
4. **`retryReason` 纯函数**：只依赖异常类型与 HTTP 状态码，**绝不回显**可能夹带 Key 的
   原始 `message`（与既有脱敏纪律一致）。映射：429→"请求过于频繁（429）"；
   5xx→"服务端错误（code）"；超时→"网络超时"；DNS→"网络不可达"；连接→"连接失败"；
   其余 IOException→"网络异常"；兜底→"暂时故障"。

---

## 5. UI 接线

### ChatViewModel
- 新增 `data class RetryState(attempt, max, reason)`（顶层，与 `ApprovalRequest` 并列）。
- 新增 `private val _retryState = MutableStateFlow<RetryState?>(null)` +
  `val retryState: StateFlow<RetryState?>`（非 null = 显示横幅）。
- `handleAgentEvent` 补两分支：
  - `is AgentEvent.RetryStarted -> _retryState.value = RetryState(ev.attempt, ev.max, ev.reason)`
  - `is AgentEvent.RetryFinished -> _retryState.value = null`
- **三处兜底清空**（防御性第二道保险，防事件路径之外的状态残留）：
  - `stop()`：`job.cancel()` 后立即 `_retryState.value = null`（UI 即刻复原，不等协程收到取消）；
  - `send()` 的 `finally`；
  - `regenerate()` 的 `finally`。

### ChatScreen
- `val retryState by vm.retryState.collectAsStateWithLifecycle()`。
- 消息区与输入栏之间插入 `retryState?.let { RetryBanner(it) }`。
- `RetryBanner`：`secondaryContainer` 底色单行横幅 + 小号转圈 + 文本
  `「正在恢复（${attempt}/${max}）· ${reason}」`（单行省略）。

---

## 6. 自检证据

### 6.1 编译
```
/opt/gradle-8.9/bin/gradle assembleDebug --offline --rerun-tasks
→ BUILD SUCCESSFUL in 51s（39 tasks executed）
```
`compileDebugKotlin` 通过即证明：扩展 `AgentEvent` 后，唯一穷尽 `when`
（`ChatViewModel.handleAgentEvent`）已同步补分支，穷尽性满足。

### 6.2 新 harness（`run-retry-verification.sh`）→ 9/9 PASS
```
用例 1：两次 500 后第 3 次成功 → 序列 (1/2),(2/2) + 最终成功 + 结束清状态   PASS
用例 2：401 快速失败 → 请求 1 次、无重试事件、原异常上抛                    PASS
用例 3：重试期间取消 → 立即清状态、CancellationException 上抛不被吞        PASS
用例 4：连续 503 到上限 → 请求 3 次、(1/2)(2/2)、最终失败一次、状态清、上抛 PASS
用例 5：边界 providerRetry=0 → 1 次请求无事件                              PASS
        边界 providerRetry=1 + 首成 → 1 次请求无事件                        PASS
        边界 providerRetry=1 + 首败次成 → 2 次请求、序列 (1/1)              PASS
        网络异常(SocketTimeout) 可重试 + 原因指向网络                       PASS
        429 可重试 + 原因指向限流                                          PASS
PASS: 9  FAIL: 0
```

### 6.3 九套回归（全绿）
| 套件 | 结果 |
|------|------|
| run-verification.sh (T02-T06) | 32 passed, 0 failed |
| run-t01-verification.sh | 35 passed, 0 failed |
| run-t01-probe.sh | 11 passed, 0 failed |
| run-t01-probe2.sh | 7 passed, 0 failed |
| run-appcontrol-verification.sh | 31 passed, 0 failed |
| run-t07t08-verification.sh | 35 passed, 0 failed |
| run-t07t08-adversarial.sh | 17 passed, 0 failed |
| run-markdown-verification.sh | 45 passed, 0 failed |
| run-branch-verification.sh | 20 passed, 0 failed |

> 注：`run-t01-verification.sh` / `run-t01-probe.sh` / `run-t01-probe2.sh` /
> `run-appcontrol-verification.sh` 未置可执行位（历史遗留 `-rw-r--r--`），
> 用 `bash <script>` 调用即通过（不算回归失败）。

---

## 7. 全局一致性 / 不变量核对

1. **跨文件契约**：`AgentEvent` 三消费点（`RealAgentLoop` 生产、`ChatViewModel` 穷尽
   `when`、harness `filterIsInstance`）全部与新增子类型一致；编译通过为证。
2. **适配层零改动**：`core/AgentLoop.kt` 适配层 `onEvent` 透传不变，`ChatViewModel`
   生产路径（`loop = AgentLoop(onEvent = ::handleAgentEvent)`）无需改签名。
3. **T05 不变量「取消不残留正在恢复」**：双保险——`callWithRetry.finally` 抛
   `RetryFinished` + `stop()`/`send().finally` 直接置 null；harness 用例 3 实证。
4. **既有错误气泡路径保持现状**：最终失败仍抛异常 → `send().onFailure` →
   `friendlyMessage` → 错误气泡；恢复期间新横幅与失败气泡**互不重复**（横幅在
   `RetryFinished` 时即消失，失败气泡随后出现）。
5. **零新增外部依赖 / 不新增 JUnit**：仅用既有 `kotlinx-coroutines`、Compose Material3；
   harness 仍是纯 `main()` + 手写断言。

---

## 8. 遗留 / 未纳入本次范围

1. **流式重试的 TextDelta 重复下发**：`chatOnce` 以「整次收集」为重试粒度——流式
   中途遇可重试错误会重新收集，已下发的 `TextDelta` 可能重复（既有 KDoc 已记录该
   取舍，UI 追加渲染可接受）。非本次 DoD 范围，未改。
2. **重试状态不持久化**：`retryState` 是瞬时 UI 态，进程被杀即丢——符合预期（恢复是
   秒级过程，无需跨进程恢复）。
3. **`run-t01-*` 脚本可执行位**：历史遗留，未顺手 `chmod`（避免混入无关变更，留待
   统一整理）。
4. **真机验证**：本地仅离线编译 + JVM harness；真机断网横幅的视觉观感需设备确认
   （本批不推送、不联网，故留待推送批次在 CI/真机侧复核）。
