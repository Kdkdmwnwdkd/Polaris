package com.beichen.polaris.core.agent.journal

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 回合日志的运行时门面：把 [RunJournal] 的「会抛 IOException」翻译成
 * 「降级、不抛、给出是否放行」。
 *
 * ## 为什么需要这一层
 *
 * [RunJournal] 的契约是**写失败要让你知道**，因为它是恢复的唯一依据。
 * 但「知道」不等于「让整个回合失败」——手机存储满、IO 被限流、系统正在杀进程，
 * 这些在桌面服务器上是「节点该下线了」，在用户手机上却是**日常**。若照搬
 * fail-closed（写不进就不干活），用户会得到「帮我读一下这个文件」都被拒绝的 App。
 *
 * 所以策略是三级，逐级收缩能力而不是整体停摆：
 *
 * | 状态 | 触发 | 行为 |
 * |---|---|---|
 * | `HEALTHY` | 正常 | 全功能 |
 * | 写失败一次 | 磁盘满 / IO 错误 | **写操作拒绝执行**，只读照常 |
 * | 写成功一次 | 空间被清理 | 自动回到 `HEALTHY` |
 *
 * 只读操作在降级下继续执行，代价是「若此刻崩溃，只读调用可能被重放一次」。
 * 浪费一次调用，比拒绝服务或写坏数据都轻——这个取舍写进 ADR-07。
 *
 * ## 与 `ToolCallOutcome` 的分工
 *
 * 本类只回答一个问题：**这次调用能不能安全地越过执行边界**。
 * 「越过了但没结果该怎么办」是 [ToolSettleStatus] 与修复器的事。
 *
 * @param store 落盘实现。
 * @param clock 事件时间来源（测试注入固定值，生产用系统时钟）。
 */
class TurnJournal(
    private val store: RunJournal,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** 日志链路的健康度。降级不阻止纯对话，只拦写操作。 */
    enum class Health {
        HEALTHY,

        /** 已发生写失败。写操作将被拒绝，只读照常。 */
        DEGRADED
    }

    @Volatile
    var health: Health = Health.HEALTHY
        private set

    /** 最近一次写失败的简述，供诊断导出与界面提示使用。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 同一段错误只记一次日志。
     *
     * 磁盘满时每次工具调用都会写失败，不节流的话一个回合能刷两百行——日志被自己淹掉，
     * 真正有用的那条反而找不着。
     */
    @Volatile
    private var degradeLogged = false

    /**
     * 开一个回合并写入 `TurnOpened`。
     *
     * 即使 `TurnOpened` 写失败也返回可用的 [Turn]：回合照常跑，只是这一轮没有恢复依据。
     * 绝不能因为「日志打不开」就让用户发不出消息。
     */
    suspend fun begin(turnId: String): Turn {
        val writer = withContext(Dispatchers.IO) {
            try {
                store.open(turnId)
            } catch (e: Exception) {
                AgentLogger.w(TAG, "回合日志无法打开（$turnId）：${e.message}；本回合不留恢复依据")
                null
            }
        }
        val turn = Turn(writer, turnId)
        turn.turnOpened()
        return turn
    }

    /**
     * 扫描并修复上次进程被杀留下的未闭合回合。
     *
     * 修复事件由 [RunJournal.scanUnclosed] 写回文件；本方法只负责把结果交给调用方。
     * 整个流程包在 `runCatching` 里——启动阶段任何异常都不该阻止 App 起来。
     */
    suspend fun scanAndRepair(lookback: Int = 3): List<RunJournalRepair.RepairResult> =
        withContext(Dispatchers.IO) {
            runCatching { store.scanUnclosed(lookback) }
                .onFailure { AgentLogger.w(TAG, "启动扫描回合日志失败：${it.message}") }
                .getOrDefault(emptyList())
        }

    /** 只保留最近 [keep] 个回合的日志。回合结束时调用，失败无所谓。 */
    suspend fun prune(keep: Int = 20) {
        withContext(Dispatchers.IO) { runCatching { store.prune(keep) } }
    }

    private fun onWriteOk() {
        if (health == Health.DEGRADED) {
            health = Health.HEALTHY
            degradeLogged = false
            AgentLogger.i(TAG, "回合日志写入恢复，写操作解除限制")
        }
    }

    private fun onWriteFailed(e: Exception) {
        lastError = "${e.javaClass.simpleName}: ${e.message}"
        health = Health.DEGRADED
        if (!degradeLogged) {
            degradeLogged = true
            AgentLogger.e(TAG, "回合日志写入失败，降级：写操作将被拒绝（只读照常）", e)
        }
    }

    /**
     * 单个回合的写入句柄。
     *
     * 所有方法都是 suspend + 串行化：一批工具可能并行执行（全只读批走 async），
     * 但日志是**单文件追加**，并行写会交错出半行。串行化的代价只是几次 fsync 排队。
     *
     * 写失败**不抛**：方法返回 `false`，由调用方决定降级行为。
     */
    inner class Turn internal constructor(
        private val writer: RunJournal.Writer?,
        val turnId: String,
        /** 本句柄产生的事件的作用域（R7）。 */
        val scope: String = RunScope.MAIN,
        /** 写锁。派生句柄与主句柄共用同一把锁——同一文件并发写会交错出半行。 */
        private val mutex: Mutex = Mutex(),
        /**
         * 是否为**回合拥有者**（R7）。
         *
         * 只有拥有者能写回合级与步级事件（`TurnOpened` / `AssistantCommitted` /
         * `StepClosed` / `TurnClosed`）。派生句柄（技能脚本、子代理）只写
         * **工具调用事件**（`ToolDispatched` / `ToolSettled`）。
         *
         * ## 为什么这条边界是硬的
         *
         * 修复器是**逐事件**解读日志的，而它对回合级事件的解读是「全局」的：
         *  - 见到 `StepClosed` 就认为「本步的调用都已应答」→ `pending.clear()`；
         *  - 见到 `TurnClosed` 就认为「回合已结束」→ 不再补尾；
         *  - 见到 `AssistantCommitted` 就把其中的 callId 登记为本步的待应答调用。
         *
         * 若子代理也写这些事件，它的一步收口会把**主代理**尚未应答的调用清空——
         * 崩溃恢复因此认为「主代理的写操作都处理过了」，一个可能已生效的写操作
         * 被静默漏掉。这不是显示问题，是**数据损坏级别的漏判**。
         *
         * 所以作用域的作用不只是「标注谁产生的」，还要划清**谁能写哪些事件**。
         */
        private val owner: Boolean = true
    ) {

        /**
         * 派生一个**同文件、不同作用域**的句柄（R7）。
         *
         * 用于「技能脚本 / 子代理」把它们的调用记进主回合日志：
         * 同一个文件、同一把锁、同一个序号序列，只是事件带上了各自的 scope。
         *
         * 不派生新文件是刻意的：崩溃恢复按文件逐个扫描，拆成多个文件会让
         * 「这个回合到底有没有副作用」变成需要跨文件聚合的问题——
         * 而恢复流程必须在**单个文件**内自洽地判定。
         */
        fun scoped(scope: String): Turn =
            Turn(writer?.scoped(scope), turnId, scope, mutex, owner = false)

        /** 本回合是否真的在留日志。false = 一开始就打不开（退回无日志的现状）。 */
        val journaling: Boolean get() = writer != null

        private var closed = false

        suspend fun turnOpened(): Boolean =
            if (!owner) true else write { it.turnOpened(clock()) }

        /**
         * 模型响应已提交（assistant 文本 + 本步声明的调用）。
         *
         * **必须在 [executeBatch] 之前调用**——这是「先落盘再执行」的那一半。
         * 顺序反了，崩溃后无法区分「模型没说完」与「工具跑了但没记」。
         *
         * R7：派生句柄不写这一条——「本步声明了哪些调用」是**回合拥有者**的语义，
         * 技能脚本的调用不是模型声明的，子代理的声明也不该登记进主代理的待应答表。
         */
        suspend fun assistantCommitted(step: Int, calls: List<ToolCall>): Boolean {
            if (!owner) return true
            return write { w ->
                w.assistantCommitted(clock(), step, calls.map { ToolCallRef(it.id, it.name) })
            }
        }

        /**
         * 某次调用**即将越过执行边界**。返回 true 才允许真正调工具。
         *
         * - 本回合没有日志（开不了文件）→ 返回 true，退回改造前行为；
         * - 写失败 → 返回 false，调用方据此拒绝**写操作**（只读仍可放行）。
         */
        suspend fun toolDispatched(step: Int, callId: String): Boolean {
            if (writer == null) return true
            return write { it.toolDispatched(clock(), step, callId) }
        }

        /** 调用有结局。正常、失败、超时、拒绝都走这里。 */
        suspend fun toolSettled(
            step: Int,
            callId: String,
            status: ToolSettleStatus,
            errorCode: String? = null
        ): Boolean = write { it.toolSettled(clock(), step, callId, status, errorCode) }

        /** 该步全部调用都已应答。仅回合拥有者可写（见 [owner]）。 */
        suspend fun stepClosed(step: Int): Boolean =
            if (!owner) true else write { it.stepClosed(clock(), step) }

        /**
         * 回合结束。重复调用只生效一次（取消路径与正常路径可能都想收尾）。
         *
         * @param reason `completed` / `cancelled` / `failed` / `blocked`。
         */
        suspend fun turnClosed(reason: String): Boolean {
            if (!owner) return true
            if (closed) return true
            closed = true
            return write { it.turnClosed(clock(), reason) }
        }

        private suspend fun write(block: (RunJournal.Writer) -> Unit): Boolean {
            val w = writer ?: return true
            return mutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        block(w)
                        onWriteOk()
                        true
                    } catch (e: IOException) {
                        onWriteFailed(e)
                        false
                    } catch (e: Exception) {
                        onWriteFailed(e)
                        false
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "TurnJournal"

        /** 回合结束原因：正常给出最终回答或走满步数。 */
        const val REASON_COMPLETED = "completed"

        /** 回合结束原因：用户点「停止」或协程被取消。 */
        const val REASON_CANCELLED = "cancelled"

        /** 回合结束原因：异常上抛。 */
        const val REASON_FAILED = "failed"
    }
}
