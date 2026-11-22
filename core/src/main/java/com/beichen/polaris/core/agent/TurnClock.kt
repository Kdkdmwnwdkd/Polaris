package com.beichen.polaris.core.agent

/**
 * 回合时钟：一条回合的**时间基准 + 事件拼装**（R5 落地物）。
 *
 * 为什么单独成文件：它与 [RealAgentLoop] 的其余部分没有耦合——只读 `turnId` /
 * `scope`，只写 [TurnObserver]。原来作为 `inner class` 住在 1500 行的
 * `RealAgentLoop.kt` 里，既撑高了那个文件，也让「事件信封怎么拼」这件事无法单独验证。
 * 抽出来之后它是纯 JVM 类（零 Android、零协程、零 IO），`TurnClockTest` 可在
 * 变异门禁里逐条打靶。
 *
 * 与 [TurnEndStatus] 的分工：时钟负责**何时发**，状态负责**发什么**。分开是因为
 * 三个出口（正常 / 取消 / 失败）都要写状态，而时钟只该有一个实例。
 */

/**
 * 回合内的时间基准（R5）。
 *
 * 事件里的 [TurnEvent.elapsedMs] 是**相对回合开始**的毫秒数。用相对时间而非
 * 墙钟有两个理由：日志跨设备对照时不会因时区/自动校时错位；回合中途改系统时间
 * 也不会让时间线出现负数或跳跃。
 *
 * 回合状态不在这里：[TurnEndStatus] 只负责「发什么」，由 `runTurn` 的三个出口
 * 写入、finally 统一发事件，保证**有且只有一条** `turn.end`。
 */
internal class TurnClock(
    val turnId: String,
    /** R7：本执行域的作用域名（主代理 = main，子代理 = sub:<标题>）。 */
    val scope: String,
    /** 事件出口。观测方抛异常不得影响回合，故 [emit] 内部吞掉异常。 */
    private val observer: TurnObserver,
) {
    private val startMs = System.currentTimeMillis()
    private var lastStep = 0

    fun elapsed(): Long = System.currentTimeMillis() - startMs

    fun step(n: Int): Int {
        lastStep = n
        return n
    }

    fun currentStep(): Int = lastStep

    /** 发出一个事件。观察者抛异常也不能影响回合——观测不该有副作用能力。 */
    fun emit(event: TurnEvent) {
        runCatching { observer.onEvent(event) }
    }

    /** 发回合结束事件（整个回合唯一一条）。 */
    fun emitTurnEnd(status: String) {
        emit(
            TurnEvent.TurnEnded(
                turnId, currentStep(), elapsed(), status, currentStep() + 1, scope
            )
        )
    }

    // —— 事件构造的便捷入口（P5-1）——
    //
    // 每个发射点原本都要重复五六个信封参数（turnId / step / elapsed / scope），
    // 十处发射点就是八九十行纯样板。搬进来之后，发射点一行一个事件，
    // 「这一回合在什么时机发了哪些事件」能一眼看完——而这件事在排障时
    // 恰恰比「每个事件怎么拼出来的」重要得多。
    fun turnStarted(inputChars: Int, tools: Int) =
        emit(TurnEvent.TurnStarted(turnId, 0, elapsed(), inputChars, tools, scope))

    fun promptPlanned(step: Int, tools: Int, tokens: Int, groups: List<String>) =
        emit(TurnEvent.PromptPlanned(turnId, step, elapsed(), tools, tokens, groups, scope))

    fun llmStarted(step: Int, attempt: Int) =
        emit(TurnEvent.LlmCallStarted(turnId, step, elapsed(), attempt, scope))

    /** @param code 归一化失败类别（见 `failureCodeOf`），成功传 null。 */
    fun llmEnded(
        step: Int, attempt: Int, durMs: Long, ok: Boolean, code: String? = null, calls: Int = 0
    ) = emit(TurnEvent.LlmCallEnded(turnId, step, elapsed(), attempt, durMs, ok, code, calls, scope))

    fun toolStarted(step: Int, tool: String, args: String, callId: String) =
        emit(TurnEvent.ToolCallStarted(turnId, step, elapsed(), tool, args, callId, scope))

    fun toolEnded(
        step: Int, tool: String, code: String, isError: Boolean, durMs: Long, callId: String
    ) = emit(TurnEvent.ToolCallEnded(turnId, step, elapsed(), tool, code, isError, durMs, callId, scope))

    fun stepClosed(step: Int, toolCount: Int) =
        emit(TurnEvent.StepClosed(turnId, step, elapsed(), toolCount, scope))
}

/**
 * 回合结束状态（R5）。
 *
 * 为什么不用一个局部 `var`：`runTurn` 的 finally 与 `runTurnBody` 的多个 return
 * 出口都要写它，跨方法用局部变量需要传出参数，啰嗦且容易漏。封成一个小对象后，
 * 每个出口只调 [set]，最后由 finally 统一发事件。
 *
 * 状态值的意义（排障时能直接看出回合是怎么结束的）：
 * - `completed`：模型正常给完了回答；
 * - `cancelled`：用户点了停止，或协程被取消；
 * - `failed`：异常（模型调用失败、executor 违约等）；
 * - `budget_exceeded`：超出回合总预算，优雅收尾；
 * - `max_steps`：用满步数上限；
 * - `stopped`：中途收到停止请求（优雅停止通道）。
 */
internal class TurnEndStatus {
    @Volatile
    var value: String = "completed"
        private set

    fun set(status: String) {
        value = status
    }
}
