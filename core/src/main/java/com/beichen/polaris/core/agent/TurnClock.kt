package com.beichen.polaris.core.agent

import java.util.concurrent.ConcurrentHashMap

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

    /**
     * 已派发调用的开始时刻（callId -> 墙钟毫秒）。
     *
     * 为什么要在这里记：取消收尾（`closePendingToolResults`）手里只有 callId，
     * 而 `tool.start` 的真实时刻只有本类知道。没有这张表，取消路径只能写 `dur=0`。
     * 生命周期天然有界——`TurnClock` 是每回合新建的，表随回合对象一起被回收，
     * 不存在跨回合增长。（同一步的多个工具并行执行时会并发写，故用 ConcurrentHashMap。）
     */
    private val startedAt = ConcurrentHashMap<String, Long>()

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

    fun toolStarted(step: Int, tool: String, args: String, callId: String) {
        // 先记时刻再发事件：事件出口可能抛（被 emit 吞掉），但开始时刻不能被它影响。
        startedAt[callId] = System.currentTimeMillis()
        emit(TurnEvent.ToolCallStarted(turnId, step, elapsed(), tool, args, callId, scope))
    }

    /**
     * 该调用从 `tool.start` 到**此刻**的毫秒数。取消路径用它补真实的等待时长。
     *
     * 这条数值是遥测里唯一能回答「用户等了多久才按停止」的东西——真机上两次
     * `shell sleep 30` 分别跑了 2.2s 和 9.7s，全被记成 `dur=0`，等于把这个信号丢了。
     *
     * 没有派发记录时返回 0：批次里排在后面、还没轮到就被取消的调用确实一秒没跑，
     * 0 对它是事实，不是缺失。
     */
    fun elapsedSinceToolStart(callId: String): Long {
        val began = startedAt[callId] ?: return 0L
        return (System.currentTimeMillis() - began).coerceAtLeast(0L)
    }

    fun toolEnded(
        step: Int, tool: String, code: String, isError: Boolean, durMs: Long, callId: String
    ) = emit(TurnEvent.ToolCallEnded(turnId, step, elapsed(), tool, code, isError, durMs, callId, scope))

    fun stepClosed(step: Int, toolCount: Int) =
        emit(TurnEvent.StepClosed(turnId, step, elapsed(), toolCount, scope))
}

/**
 * 回合结束原因（R8）。
 *
 * **为什么收成枚举**：改造前 8 个出口散在 `RealAgentLoop` 里写裸字符串，拼错一个
 * （`max_step`）不报任何错——只会让日志里出现一个没人认识的值，而排障恰恰依赖
 * 「这个值一定在已知集合里」这个前提。收成枚举后，拼错变成编译错误。
 *
 * **另一个更要紧的原因**：改造前 `turnClosed(reason)` 用的是另一个局部变量 `reason`，
 * 它只有 completed / cancelled / failed 三种取值。于是 `max_steps`、`budget_exceeded`、
 * `stopped` 这三个「没做完」的出口，在**回合日志**里全被记成 `completed`——事件通道
 * 说的是实话，日志通道在撒谎，两条记录互相打架。现在两边共用同一个 [TurnEndStatus]，
 * 结构上就没有「说两套话」的可能。
 *
 * [wire] 是写进日志 / NDJSON 的字符串，**必须与历史值逐字一致**：既有日志解析、
 * harness 断言、用户手里的历史文件都认这些字面值。可以改枚举名，不能改 wire。
 */
internal enum class TurnExit(val wire: String) {
    /** 模型正常给完了回答。 */
    Completed("completed"),

    /** 用户点了停止，或协程被取消。 */
    Cancelled("cancelled"),

    /** 异常：模型调用失败、executor 违约等。 */
    Failed("failed"),

    /** 模型直接回答、没派发任何工具（一条消息就结束的轻回合）。 */
    InlineReply("inline_reply"),

    /** 超出回合总预算，优雅收尾。 */
    BudgetExceeded("budget_exceeded"),

    /** 用满步数上限——**没做完**，不是完成。 */
    MaxSteps("max_steps"),

    /** 中途收到停止请求（优雅停止通道）。 */
    Stopped("stopped"),

    /**
     * 没有任何出口认领这一回合——**这是缺陷信号，不是正常状态**。
     *
     * 默认值故意设成它，而不是 `completed`：改造前默认 `completed` 属于 fail-open，
     * 将来谁新加一个 return 出口忘了 `set`，日志里就静默多出一条「完成」，
     * 而「完成」是最不该被伪造的状态。设成 Unknown 之后，同样的疏漏会在日志里
     * 留下 `status=unknown`——不优雅，但一眼能看见，且不会污染完成率统计。
     *
     * 正常回合永远不该出现它；出现了就说明 `runTurnBody` 的某个出口漏了 `set`。
     */
    Unknown("unknown");

    /**
     * 是否算「这一回合把用户交代的事做完了」。
     *
     * 只有 [Completed] 与 [InlineReply] 算：前者走到模型主动收尾，后者本来就不需要动手。
     * [MaxSteps] / [BudgetExceeded] 是**做到一半被制度性截断**——它们要是被当成「完成」，
     * 任何按状态算完成率的报表都会系统性高估，且高估的恰好是最该被看见的那一端
     * （停下来的那些，正是用户觉得「它没干完」的那些）。
     *
     * 今天没有业务代码读它（自动续跑靠 orphan 判定，见 `AutoResumePolicy`），
     * 但它是「诚实记录」这层的判据；等 §11.18.9 的 `Exit` 真需要时有现成依据可用。
     */
    val isCompleted: Boolean get() = this == Completed || this == InlineReply
}

/**
 * 回合结束状态（R5）。
 *
 * 为什么不用一个局部 `var`：`runTurn` 的 finally 与 `runTurnBody` 的多个 return
 * 出口都要写它，跨方法用局部变量需要传出参数，啰嗦且容易漏。封成一个小对象后，
 * 每个出口只调 [set]，最后由 finally 统一发事件。
 *
 * 状态值的意义见 [TurnExit]。
 */
internal class TurnEndStatus {
    /**
     * 初值是 [TurnExit.Unknown] 而**不是** `Completed`：默认值是最危险的代码，
     * 它在所有「忘了写」的路径上生效。默认「完成」等于让疏漏伪装成好消息。
     */
    @Volatile
    var value: TurnExit = TurnExit.Unknown
        private set

    fun set(exit: TurnExit) {
        value = exit
    }
}
