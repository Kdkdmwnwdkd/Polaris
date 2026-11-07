package com.beichen.polaris.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/**
 * 「向用户提问并等待回答」的中转站（W-14）。
 *
 * 为什么要挂起而不是「返回一句『已提问』」：
 *  - 模型缺参数时（选哪个文件、哪个 Wi-Fi、哪条待办），最省事的是**当场问清楚**；
 *  - 若只回一句「已提问」，模型会继续瞎猜或直接结束任务，用户回答后还得再说一遍上下文。
 *
 * 单问题模型：同一时刻只允许一个待答问题（并发会让人分不清在答哪个）。
 * 超时（默认 180 秒）由工具侧降级为「按最合理假设继续」，不阻塞整轮任务。
 */
object AskUserBroker {

    data class Question(
        val id: String,
        val text: String,
        val options: List<String>
    )

    sealed interface Outcome {
        data class Answered(val choice: String) : Outcome
        data object Timeout : Outcome
        data object Busy : Outcome
    }

    private val lock = Any()
    private var waiter: CompletableDeferred<String>? = null
    private var current: Question? = null

    private val _pending = MutableStateFlow<Question?>(null)

    /** UI 观察：有待答问题即显示选择卡片；回答后归 null。 */
    val pending: StateFlow<Question?> = _pending.asStateFlow()

    /**
     * 丢弃待答问题（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * `waiter?.cancel()` 是必要的，不只是清字段：挂起中的 [ask] 持有那个 Deferred，
     * 只把字段置 null 会让它永远等在那（超时前），harness 里表现为「上一个用例
     * 的协程泄漏」。cancel 之后 [ask] 走 finally 收尾，状态一致。
     *
     * 会向上抛 `CancellationException` 给正在等待的调用方——这是正确语义：
     * 问题被撤回了，不该假装用户回答了某个值。
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
     * 提问并挂起等待回答。
     *
     * @param options 可选项（为空则 UI 显示「输入回答」的自由文本框）
     */
    suspend fun ask(
        question: String,
        options: List<String> = emptyList(),
        timeoutSec: Int = 180
    ): Outcome {
        val deferred: CompletableDeferred<String>
        val q = Question(
            id = "q" + System.currentTimeMillis().toString(36),
            text = question,
            options = options
        )
        synchronized(lock) {
            if (current != null) return Outcome.Busy
            deferred = CompletableDeferred()
            waiter = deferred
            current = q
            _pending.value = q
        }
        return try {
            val answer = withTimeout(timeoutSec * 1000L) { deferred.await() }
            Outcome.Answered(answer)
        } catch (_: TimeoutCancellationException) {
            Outcome.Timeout
        } finally {
            synchronized(lock) {
                current = null
                waiter = null
                _pending.value = null
            }
        }
    }

    /** UI 回调：用户点了某个选项（或提交了自由文本）。id 不匹配说明是过期卡片，直接忽略。 */
    fun answer(id: String, choice: String): Boolean {
        synchronized(lock) {
            if (current?.id != id) return false
            waiter?.complete(choice)
            return true
        }
    }

    /** 用户主动取消当前提问（如点了「停止运行」）。 */
    fun cancel() {
        synchronized(lock) { waiter?.complete("（用户取消了这次提问）") }
    }
}
