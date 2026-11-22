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
 * 等一次审批决策。**三态，不是布尔**：
 *
 * | 返回 | 含义 |
 * |---|---|
 * | `true` | 用户点了允许 |
 * | `false` | 用户点了拒绝 |
 * | `null` | **30 秒内没人回应** |
 *
 * ## 为什么必须把超时和拒绝分开（2026-11-22 真机）
 *
 * 原实现是 `withTimeoutOrNull(...) ?: false`——超时与「用户点拒绝」共用同一个 `false`。
 * 于是三处同时说错话：
 *
 *  - 日志写「拒绝」，事后排查会以为用户手动拒了（真机上用户明确说「我没点拒绝」）；
 *  - 模型收到「用户拒绝了你」，于是换个工具再试——**又弹一张卡**。用户看到的就是
 *    「30 秒后卡片消失，紧接着又来一条」，而他只是没在看屏幕；
 *  - 界面文案说「用户拒绝」，用户什么都没做。
 *
 * 三态之后「没人理」有独立去向：文案说超时、日志记超时、本回合不再弹第二张卡。
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
 * 超时返回 null 是「用户没理我」；取消（用户点停止）是另一回事。若用 `runCatching`
 * 把两者一起吞，一次取消会被记成一次正常的「用户拒绝」，协程继续往下跑。
 * `withTimeoutOrNull` 对**外部取消**抛 `CancellationException` 而非返回 null——
 * 这个区分正是本函数要保住的东西，`R03` 变异专门守它。
 */
suspend fun awaitApprovalDecision(
    decision: CompletableDeferred<Boolean>,
    timeoutMs: Long = APPROVAL_TIMEOUT_MS
): Boolean? = withTimeoutOrNull(timeoutMs) { decision.await() }
