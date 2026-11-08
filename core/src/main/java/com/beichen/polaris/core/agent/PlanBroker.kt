package com.beichen.polaris.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/**
 * 「先出计划、用户批准再执行」的中转站（W-25，缺口 #58 / #62）。
 *
 * ## 要解决的问题
 * 长任务（改 5 个文件、点 30 次界面、装 3 个包）现在只能边跑边看——跑歪了要等半天
 * 才发现。解法是"先列计划 → 用户点头 → 再动手"。
 *
 * ## 与 ask_user 的分工
 * - `ask_user`：问**一个**问题、要一个答案（选文件/选应用）；
 * - `plan_propose`：提交**多步计划**、要一个**批准/拒绝**（可能带修改意见）。
 * 两者都挂起等待，但 UI 形态不同（问题卡片 vs 计划卡片），不能互相替代。
 *
 * ## 并发与超时
 * 单计划模型（同 ask_user）：同一时刻只允许一个待批计划，并发返回 [Decision.Busy]。
 * 超时（默认 300 秒）返回 [Decision.Timeout]，由工具侧降级为"按计划继续"还是"中止"——
 * 当前策略：**超时视为未批准**，模型不得擅自执行破坏性步骤（安全侧默认）。
 */
object PlanBroker {

    /** 一个待批计划。 */
    data class Plan(
        val id: String,
        val summary: String,
        val steps: List<String>
    )

    sealed interface Decision {
        /** 用户点了「批准执行」。 */
        data object Approved : Decision

        /** 用户拒绝或要求修改，[reason] 为补充说明（可能为空）。 */
        data class Rejected(val reason: String) : Decision

        /** 等待超时：未获批准（安全侧默认，不得擅自执行）。 */
        data object Timeout : Decision

        /** 已有待批计划，拒绝并发提交。 */
        data object Busy : Decision
    }

    private val lock = Any()
    private var waiter: CompletableDeferred<Decision>? = null
    private var current: Plan? = null

    private val _pending = MutableStateFlow<Plan?>(null)

    /** UI 观察：有待批计划即显示计划卡片；批准/拒绝/超时后归 null。 */
    val pending: StateFlow<Plan?> = _pending.asStateFlow()

    /**
     * 撤回待批计划（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * 与 [AskUserBroker.reset] 同构：cancel 而非仅清字段，否则等待中的 [propose]
     * 会挂到超时。撤回归为「未批准」侧，不给模型任何默认放行。
     */
    fun reset() {
        synchronized(lock) {
            runCatching { waiter?.cancel() }
            waiter = null
            current = null
            _pending.value = null
        }
    }

    /**
     * 提交计划并挂起等待用户决定。
     *
     * @param summary 一句话说明要做什么（用户扫一眼就知道）
     * @param steps 步骤列表（按执行顺序，动词开头）
     */
    suspend fun propose(
        summary: String,
        steps: List<String>,
        timeoutSec: Int = 300
    ): Decision {
        val deferred: CompletableDeferred<Decision>
        val plan = Plan(
            id = "p" + System.currentTimeMillis().toString(36),
            summary = summary,
            steps = steps
        )
        synchronized(lock) {
            if (current != null) return Decision.Busy
            deferred = CompletableDeferred()
            waiter = deferred
            current = plan
            _pending.value = plan
        }
        return try {
            withTimeout(timeoutSec * 1000L) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            Decision.Timeout
        } finally {
            synchronized(lock) {
                // 只有自己仍是当前计划时才清理（防止上一轮 finally 清掉下一轮的状态）
                if (current?.id == plan.id) {
                    current = null
                    waiter = null
                    _pending.value = null
                }
            }
        }
    }

    /** UI：批准指定计划。id 不匹配返回 false（防旧卡片误触）。 */
    fun approve(planId: String): Boolean = finish(planId, Decision.Approved)

    /** UI：拒绝/要求修改指定计划。 */
    fun reject(planId: String, reason: String = ""): Boolean =
        finish(planId, Decision.Rejected(reason.trim()))

    /** 取消当前待批计划（停止按钮 / 离开会话时调用）。 */
    fun cancel(): Boolean {
        val id = current?.id ?: return false
        return finish(id, Decision.Rejected("已取消"))
    }

    private fun finish(planId: String, decision: Decision): Boolean {
        val target: CompletableDeferred<Decision>
        synchronized(lock) {
            val cur = current ?: return false
            if (cur.id != planId) return false
            target = waiter ?: return false
            current = null
            waiter = null
            _pending.value = null
        }
        return target.complete(decision)
    }
}
