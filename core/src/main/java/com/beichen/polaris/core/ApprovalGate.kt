package com.beichen.polaris.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次「问用户」的挂起闸门（P1-3 第二阶段）。
 *
 * ## 它解决什么
 *
 * 审批通道有两种完全不同的失败方式，而它们都需要**同一个东西**：一个能挂起、
 * 能被外部解除、且一定会收尾的等待点。
 *
 *  - 界面上没人理：用户把气泡收了、屏幕锁了、App 切后台了。等待必须自己到点收场，
 *    否则协程树里吊着一个永远不返回的 `await`，那一轮对话既不结束也不报错。
 *  - 界面被销毁：Service 停了、Activity 重建了。等待必须被显式解除，且必须是拒绝。
 *
 * 这一层刻意不含任何 UI 概念（没有 StateFlow、没有 Compose、没有 Context）：它只回答
 * 「等到了什么结果」。谁把卡片画在屏幕上、点按钮时调 [settle]，是调用方的事。
 * 分开的收益是**可测**——`run-pet-consent-verification.sh` 不需要模拟器就能验超时、
 * 验取消、验「先点允许再超时不会重复消费」。
 *
 * ## 为什么不是布尔回调
 *
 * `suspend (tool, args) -> Boolean` 这个形状（[Consent.Ask] 的 handler）没法表达
 * 「没人回答」。把它压成 `false` 的后果是：用户在锁屏界面被记成「拒绝了该操作」，
 * 而他从头到尾没看见这张卡。四个结果分开，才能让日志和工具结果说对话。
 */
class ApprovalGate(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    /** 等待的收场方式。刻意不是布尔——「没人回答」与「回答不同意」要说不同的话。 */
    enum class Outcome {
        /** 用户点了允许。 */
        ALLOWED,

        /** 用户点了拒绝。 */
        DENIED,

        /** [timeoutMs] 内没有任何回应。按拒绝执行，但理由不同。 */
        TIMEOUT,

        /** 等待被 [cancelAll] 主动解除（界面已销毁、服务已停）。 */
        CANCELLED
    }

    private val lock = Any()

    /**
     * 当前在等的那个槽位。
     *
     * 只留一个：桌宠的回合是串行的（[com.beichen.polaris.pet.PetAgentSession] 用 Mutex
     * 锁住整轮），同一时刻不可能有两个待确认调用。留成队列反而要处理「队列里的第二条
     * 已经过期」这种没人会遇到的复杂度。
     *
     * 值类型是 [Outcome] 而不是布尔：`cancelAll` 与「用户点拒绝」都会解除等待，
     * 但前者不是用户意见。塞进同一个 `false` 的话，「界面被销毁」会被报告成
     * 「用户拒绝了该操作」——用户根本没看见那张卡。这个区别是本类存在的理由之一。
     */
    private var slot: CompletableDeferred<Outcome>? = null

    /** [cancelAll] 之后置位：之后进来的 [await] 立刻按取消收场，不要等到超时。 */
    private var closed = false

    /**
     * 等一次裁决。调用方拿到 [Outcome] 后自己决定怎么记账。
     *
     * 协程取消照常上抛（用户点了「停」），不吞成 [Outcome.DENIED]——那会把控制流
     * 说成用户意见。`finally` 里清槽位，避免取消后留下一个没人能 settle 的悬空等待。
     */
    suspend fun await(): Outcome {
        val deferred = CompletableDeferred<Outcome>()
        synchronized(lock) {
            if (closed) return Outcome.CANCELLED
            slot = deferred
        }
        try {
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: Outcome.TIMEOUT
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            synchronized(lock) {
                if (slot === deferred) slot = null
            }
        }
    }

    /**
     * 从外部给出答案。返回 false = 没有人在等（卡片已经消失、或已被超时收场）。
     *
     * 返回值不是装饰：UI 侧拿它判断「这一下点击算不算数」，避免用户对着已经消失的
     * 卡片连点两下时把下一轮误放行。
     */
    fun settle(allowed: Boolean): Boolean {
        val deferred = synchronized(lock) { slot } ?: return false
        return deferred.complete(if (allowed) Outcome.ALLOWED else Outcome.DENIED)
    }

    /**
     * 解除所有等待并按取消收场。界面销毁时调用。
     *
     * 置 [closed] 而不是只清槽位：销毁之后仍可能有调用进来（Service 正在收尾，
     * 而模型刚好发起了下一个工具调用），那些调用必须立刻失败，不能重新开一个
     * 永远没人看的等待。
     */
    fun cancelAll() {
        val deferred = synchronized(lock) {
            closed = true
            slot.also { slot = null }
        }
        deferred?.complete(Outcome.CANCELLED)
    }

    /**
     * 重新允许等待。界面重建后调用（桌宠气泡收起再展开不算重建，Service 重启才算）。
     *
     * 没有这一步的话，[cancelAll] 之后这个对象就永久失效了——而桌宠的窗口是可以
     * 在同一个进程里反复出现消失的。
     */
    fun reopen() {
        synchronized(lock) { closed = false }
    }

    /** 当前是否有人在等（UI 侧用来决定要不要画卡片）。 */
    fun isWaiting(): Boolean = synchronized(lock) { slot != null }

    companion object {
        /**
         * 默认 45 秒。
         *
         * 桌宠是「随手问一句」的通道，用户多半是看着气泡的。45 秒足够他读完一句话
         * 并点一下，又不至于让一个已经走开的人把回合吊在那里直到系统杀进程。
         * 主界面那条通道不走这个闸门（它有常驻的审批卡与更长的耐心）。
         */
        const val DEFAULT_TIMEOUT_MS = 45_000L
    }
}
