package com.beichen.polaris.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 危险操作审批的时限。
 *
 * **单点定义**：UI 的倒计时文案由它派生（见 `ChatScreen.APPROVAL_TIMEOUT_SEC`）。
 * 两边各写一个数字，迟早漂移成「弹窗说 30 秒、实际 15 秒就拒了」。
 */
const val APPROVAL_TIMEOUT_MS: Long = 30_000L

/**
 * 等一次审批决策，**超时等同拒绝**（fail-safe）。
 *
 * ## 为什么超时必须在 ViewModel 侧，而不在弹窗的倒计时里
 *
 * 审批弹窗是 `ChatScreen` 的子组合。用户从对话页切到设置页/日志页时，弹窗连同它的
 * `LaunchedEffect` 一起被卸载，`denyApproval()` 从此再没人调用。于是 `deferred.await()`
 * 永久挂起：`approvalMutex` 被长期持有、后续工具全部排队、Agent 回合永不收尾
 * （发送键一直是灰的）——而用户根本**看不见**有个审批在等。
 *
 * 超时放在这里，就与「UI 是否还在组合树上」无关。
 *
 * ## 为什么取消要往外抛
 *
 * 超时返回 false 是「用户没理我」；取消（用户点停止）是另一回事。若用 `runCatching`
 * 把两者一起吞成 false，一次取消会被记成一次正常的「用户拒绝」，协程继续往下跑。
 * `withTimeoutOrNull` 对**外部取消**抛 `CancellationException` 而非返回 null——
 * 这个区分正是本函数要保住的东西，`P4` 变异专门守它。
 */
suspend fun awaitApprovalDecision(
    decision: CompletableDeferred<Boolean>,
    timeoutMs: Long = APPROVAL_TIMEOUT_MS
): Boolean = withTimeoutOrNull(timeoutMs) { decision.await() } ?: false
