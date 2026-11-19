package com.beichen.polaris.core.runtime

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 回合来源。枚举而不是字符串：来源是 (session, source) 互斥键的一半，
 * 用字符串会让「Main」与「main」变成两个键，互斥静默失效。
 */
enum class RunSource { MAIN, PET, SUBAGENT, AUTO_RESUME, MCP }

/**
 * 一个回合的配额。
 *
 * 三个字段全部有默认值，但语义上都**不允许「真的不限」**：调用方必须显式表态。
 * 这里给默认值只是为了让第一版（不接 loop 执行体）的 [TaskSpec] 能先落地，
 * 等 loop 接线时会把「无默认值」这条约束提上来（v4 §3.2 的不变量 3）。
 */
data class Quota(
    val maxSteps: Int = 20,
    val maxTokens: Int = 0,     // 0 = 不限 token
    val deadlineMs: Long = 0L,  // 0 = 不限时
)

/**
 * 一个回合任务的描述。第一版只承载「谁、什么来源、跑什么、多少配额」，
 * 不承载执行体——执行体（loop 工厂）在接线轮次接入，这里先把互斥/许可/代次号
 * 这三样**纯机制**立起来，让它们能脱离 Android 在 JVM 上逐条验证。
 */
data class TaskSpec(
    val session: String,
    val source: RunSource,
    val input: String,
    val quota: Quota = Quota(),
)

/**
 * 一次回合所有权的凭证。
 *
 * 关键约定：`generation` 是 [AgentRuntime.acquireTurn] **当时**的快照，不是实时值。
 * 回合执行中每个批次都应拿「自己的 generation」对比 [AgentRuntime.generation]，
 * 不一致就说明这回合已被 cancel——这是「迟到事件按代次丢弃」的判据来源
 * （v4 §3.2：代次号比 `isRunning` 布尔可靠，因为布尔表达不了「第 2 回合已开始、
 * 第 1 回合的迟到事件还在流里」）。
 */
interface TurnLease : AutoCloseable {
    val session: String
    val source: RunSource
    val generation: Long

    /** 归还回合所有权。幂等（重复 close 不得重复归还）。 */
    override fun close()
}

/**
 * 运行时：回答「现在有几个回合在跑、谁该排队、取消后旧事件怎么丢弃」。
 *
 * ## 两级并发，不是一级（v4 判断一）
 *
 * 现状的真实危害不是「并发太高」，而是**没有资源边界**：
 * 两份 loop 同时写同一份 registry、本地推理被两个回合交错 prefill、取消权不在 Task 上。
 * 所以这里拆成两层：
 *
 * | 层 | 管什么 | 粒度 | 默认 |
 * |---|---|---|---|
 * | 队列层 [acquireTurn] | 同一会话/入口的先后 | `(session, source)` 互斥 | — |
 * | 许可层 [withPermit] | 同一后端的资源争用 | `backend` | local=1 / cloud=2 / terminal=1 |
 *
 * 「同一个人不会同时说两句话」由队列层保证；「本地推理交错 prefill 会互相拖死」
 * 由许可层保证。两者正交：不同 (session, source) 不互斥，但若都打同一个 backend，
 * 许可层会拦住第二个。
 */
interface AgentRuntime {

    /**
     * 取得 (session, source) 的回合所有权。同键第二个调用**挂起**，直到第一个
     * [TurnLease.close]；不同键（不同 session 或不同 source）互不阻塞。
     */
    suspend fun acquireTurn(session: String, source: RunSource): TurnLease

    /**
     * 在 backend 的并发许可内执行 [block]，返回其结果。超上限时挂起等许可。
     *
     * 未知 backend 抛 [IllegalArgumentException]：backend 名是**登记过的枚举语义**
     * （local/cloud/terminal），拼错就该当场爆，而不是静默给 1 个许可——
     * 那样「许可层形同虚设」会像前几轮的 fail-open 一样躲过所有断言。
     */
    suspend fun <T> withPermit(backend: String, block: suspend () -> T): T

    /** 会话当前代次号。cancel 自增；初值为 0。 */
    fun generation(session: String): Long

    /**
     * 取消一个会话：代次号 +1，使所有已发出的 [TurnLease] 立即过期。
     * 返回是否真的发生了自增（session 从未被 acquire 过时返回 false）。
     */
    fun cancel(session: String): Boolean

    /** 当前被持有、尚未 close 的回合数（观察面）。 */
    fun activeTurns(): Int

    /** 某 backend 当前还能立即拿到的许可数（观察面，测试/自检用）。 */
    fun availablePermits(backend: String): Int

    companion object {
        fun defaultBackends(): Map<String, Int> =
            mapOf("local" to 1, "cloud" to 2, "terminal" to 1)

        fun of(backends: Map<String, Int> = defaultBackends()): AgentRuntime =
            DefaultAgentRuntime(backends)
    }
}

/**
 * [AgentRuntime] 的默认实现。
 *
 * ## 为什么用 `Semaphore` 而不是自写锁
 * `(session, source)` 互斥与 backend 许可都是「可挂起的计数信号量」，
 * `kotlinx.coroutines.sync.Semaphore` 恰好是它，且 `acquire` 是**协程友好**的挂起
 * （不是线程阻塞）——回合要挂几秒甚至更久，自写 spin/Thread.sleep 会把线程池拖垮。
 */
private class DefaultAgentRuntime(backends: Map<String, Int>) : AgentRuntime {

    /** (session, source) 互斥门。key = `session\u0000source`。 */
    private val turnGates = ConcurrentHashMap<String, Semaphore>()

    /** backend 许可池。 */
    private val permits = ConcurrentHashMap<String, Semaphore>()

    /** session → 代次号。 */
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    /** 未 close 的回合数。 */
    private val active = AtomicInteger(0)

    init {
        for ((backend, limit) in backends) {
            require(limit >= 1) { "backend $backend 的并发上限必须 >= 1，得到 $limit" }
            permits[backend] = Semaphore(limit)
        }
    }

    override suspend fun acquireTurn(session: String, source: RunSource): TurnLease {
        val gate = turnGates.computeIfAbsent(key(session, source)) { Semaphore(1) }
        gate.acquire()  // 同键第二个在这里挂起
        val gen = generation(session)
        active.incrementAndGet()
        return object : TurnLease {
            override val session: String = session
            override val source: RunSource = source
            override val generation: Long = gen

            override fun close() {
                // 幂等：只能归还一次。用 AtomicBoolean 防并发 close（一个在 finally、
                // 一个在超时清理），普通布尔会有竞态。
                if (released.compareAndSet(false, true)) {
                    active.decrementAndGet()
                    gate.release()
                }
            }

            private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        }
    }

    override suspend fun <T> withPermit(backend: String, block: suspend () -> T): T {
        val sem = permits[backend]
            ?: throw IllegalArgumentException(
                "未知 backend：$backend（已登记：${permits.keys.sorted().joinToString()}）")
        return sem.withPermit { block() }
    }

    override fun generation(session: String): Long =
        generations.computeIfAbsent(session) { AtomicLong(0) }.get()

    override fun cancel(session: String): Boolean {
        val gen = generations[session] ?: return false
        gen.incrementAndGet()
        return true
    }

    override fun activeTurns(): Int = active.get()

    override fun availablePermits(backend: String): Int =
        permits[backend]?.availablePermits
            ?: throw IllegalArgumentException("未知 backend：$backend")

    private fun key(session: String, source: RunSource): String = "$session\u0000$source"
}
