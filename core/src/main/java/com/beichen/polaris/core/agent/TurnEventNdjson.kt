package com.beichen.polaris.core.agent

import com.beichen.polaris.core.telemetry.TraceSink

/**
 * 回合事件的 NDJSON 序列化（P5-1）。
 *
 * ## 为什么不复用 `LoggingTurnObserver` 的格式化
 *
 * 那个函数产出的是**给人看的句子**（`TURN 9f2a 18ms tool.start step=0 tool=...`）。
 * 想从它算「每回合平均步数」得写正则，换个事件名就断。这里产出的是**字段稳定的对象**，
 * 键名是契约：改字段名等于改接口，要过断言。
 *
 * 两份都保留，因为它们回答不同问题：
 * - 文本行：用户把日志发过来，我肉眼看出「最后一个 tool.start 没有 tool.end」；
 * - 流水：`trace-metrics.py` 算出「确定性错误平均白等 38s」这种需要跨回合求和的数。
 *
 * ## 字段顺序也是契约
 *
 * `ev` 打头、`ts` 紧跟，是因为 `grep`/`head` 时最先看到的是「什么事件、什么时候」。
 * 顺序对解析没影响，但对**人**读流水有影响——这份东西最终是排障时被 `tail` 的。
 *
 * ## 信封字段（每个事件都有）
 *
 * | 字段 | 含义 |
 * |---|---|
 * | `ev` | 事件名，与 `LoggingTurnObserver.nameOf` 逐字一致 |
 * | `ts` | 墙钟毫秒（跨回合排序用） |
 * | `turn` | 回合号（与 `TurnJournal` 同源） |
 * | `scope` | `main` / `skill:<id>` / `sub:<title>` |
 * | `step` | 回合内第几步 |
 * | `ms` | 相对回合开始的毫秒（跨设备对照用，不受时区/校时影响） |
 */
fun TurnEvent.toNdjson(ts: Long = System.currentTimeMillis()): String = buildString(192) {
    append("{\"ev\":").append(TraceSink.quote(nameOf()))
    append(",\"ts\":").append(ts)
    append(",\"turn\":").append(TraceSink.quote(turnId))
    append(",\"scope\":").append(TraceSink.quote(scope))
    append(",\"step\":").append(step)
    append(",\"ms\":").append(elapsedMs)
    when (this@toNdjson) {
        is TurnEvent.TurnStarted -> {
            append(",\"chars\":").append(inputChars)
            append(",\"tools\":").append(visibleToolCount)
        }

        is TurnEvent.PromptPlanned -> {
            append(",\"tools\":").append(visibleToolCount)
            append(",\"tokens\":").append(estimatedTokens)
            append(",\"groups\":[")
            groups.forEachIndexed { i, g ->
                if (i > 0) append(',')
                append(TraceSink.quote(g))
            }
            append(']')
        }

        is TurnEvent.LlmCallStarted -> append(",\"attempt\":").append(attempt)

        is TurnEvent.LlmCallEnded -> {
            append(",\"attempt\":").append(attempt)
            append(",\"dur\":").append(durationMs)
            append(",\"ok\":").append(success)
            append(",\"code\":").append(TraceSink.quote(code))
            append(",\"calls\":").append(toolCallCount)
        }

        is TurnEvent.ToolCallStarted -> {
            append(",\"call\":").append(TraceSink.quote(callId))
            append(",\"tool\":").append(TraceSink.quote(tool))
            append(",\"args\":").append(TraceSink.quote(argsSummary))
        }

        is TurnEvent.ToolCallEnded -> {
            // callId 是 P5-1 加的：同一 step 内并行两次 `read_file` 时，
            // 只靠 (step, tool) 配不上 start/end——而这正是「卡住的是哪一个」的答案。
            append(",\"call\":").append(TraceSink.quote(callId))
            append(",\"tool\":").append(TraceSink.quote(tool))
            append(",\"code\":").append(TraceSink.quote(code))
            append(",\"err\":").append(isError)
            append(",\"dur\":").append(durationMs)
        }

        is TurnEvent.StepClosed -> append(",\"tools\":").append(toolCount)

        is TurnEvent.TurnEnded -> {
            append(",\"status\":").append(TraceSink.quote(status))
            append(",\"steps\":").append(steps)
        }
    }
    append('}')
}

/**
 * 事件名。**两条通道共用这一处**——文本行（[LoggingTurnObserver]）与流水
 * （[NdjsonTurnObserver]）都调它。
 *
 * 起初两边各写了一份，理由是「文本行改名算措辞优化、流水改名算破坏契约」。
 * 这个理由站不住：两份映射只会让「改了一边忘了另一边」变成必然事件，
 * 而契约本身已经由 `TraceTelemetryVerification` 的字段快照守住了。
 * 现在改名会同时改两条通道，坏了立刻红。
 */
internal fun TurnEvent.nameOf(): String = when (this) {
    is TurnEvent.TurnStarted -> "turn.start"
    is TurnEvent.PromptPlanned -> "prompt.plan"
    is TurnEvent.LlmCallStarted -> "llm.call"
    is TurnEvent.LlmCallEnded -> "llm.end"
    is TurnEvent.ToolCallStarted -> "tool.start"
    is TurnEvent.ToolCallEnded -> "tool.end"
    is TurnEvent.StepClosed -> "step.close"
    is TurnEvent.TurnEnded -> "turn.end"
}

/**
 * 把回合事件同时写进流水（P5-1）。
 *
 * 与 [LoggingTurnObserver] 并列安装，互不替代：前者供脚本算，后者供人看。
 * 装配在 `ChatViewModel` 的 loop 构造点，`CompositeTurnObserver` 保证两者都收到。
 */
class NdjsonTurnObserver(
    private val emit: (String) -> Unit = TraceSink::emit,
) : TurnObserver {
    override fun onEvent(event: TurnEvent) = emit(event.toNdjson())
}

/**
 * 把多个观察者串成一个（任一个抛异常不影响其余）。
 *
 * 为什么吞异常：观察者是纯观测通道，它挂了不该让回合挂。`TurnClock.emit` 已经
 * 对单个观察者做了 `runCatching`，但复合之后如果前一个抛了，后一个就收不到——
 * 那会让「流水缺一段」这种最难查的故障出现。所以每个都单独兜住。
 */
class CompositeTurnObserver(private vararg val observers: TurnObserver) : TurnObserver {
    override fun onEvent(event: TurnEvent) {
        observers.forEach { runCatching { it.onEvent(event) } }
    }
}
