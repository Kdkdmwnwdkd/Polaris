package com.beichen.polaris.core.agent

import com.beichen.polaris.core.AgentLogger

/**
 * 把回合事件打进日志的观察者（R5 的生产实现）。
 *
 * ## 为什么落日志而不是只留在内存
 * 用户报「卡住了」时，进程可能早就被系统杀了——内存里的时间线随之消失。
 * 日志文件（`agent-<yyyyMMdd>.log`）是进程外的事实，且已有轮转与保留期
 * （[AgentLogger] 管），不需要新造一套。
 *
 * ## 行格式
 * `TURN <turnId> <elapsedMs>ms <event> <载荷>`
 *
 * 例：
 * ```
 * TURN 9f2a-1c3d 0ms turn.start chars=24 tools=12
 * TURN 9f2a-1c3d 18ms tool.start step=0 tool=write_file args={"path":"a.txt"
 * TURN 9f2a-1c3d 6021ms tool.end step=0 tool=write_file code=ok error=false dur=6003
 * ```
 *
 * **载荷不带完整参数与输出**（见 [TurnEvent] 的载荷纪律）。
 *
 * ## 定位「卡住」的用法
 * 取某个回合的最后一条 `tool.start`，若它没有配对的 `tool.end`——卡点就在那里。
 * 这正是 R5 的验收判据。
 */
class LoggingTurnObserver(
    private val tag: String = "Turn",
    /** 是否把完整载荷打进日志。关闭时只打事件名与耗时（更省，但排障信息更少）。 */
    private val verbose: Boolean = true,
) : TurnObserver {

    override fun onEvent(event: TurnEvent) {
        AgentLogger.i(tag, format(event))
    }

    private fun format(event: TurnEvent): String = buildString {
        append("TURN ").append(event.turnId)
        append(' ').append(event.elapsedMs).append("ms ")
        // 事件名与流水共用一处（[TurnEvent.nameOf]）：改名会同时改两条通道，
        // 不存在「文案改了脚本悄悄断掉」的中间态。
        append(event.nameOf())
        if (!verbose) return@buildString
        when (event) {
            is TurnEvent.TurnStarted ->
                append(" chars=").append(event.inputChars)
                    .append(" tools=").append(event.visibleToolCount)

            is TurnEvent.PromptPlanned ->
                append(" tools=").append(event.visibleToolCount)
                    .append(" tokens=").append(event.estimatedTokens)
                    .append(" groups=").append(event.groups.joinToString("+"))

            is TurnEvent.LlmCallStarted ->
                append(" step=").append(event.step)
                    .append(" attempt=").append(event.attempt)

            is TurnEvent.LlmCallEnded ->
                append(" step=").append(event.step)
                    .append(" attempt=").append(event.attempt)
                    .append(" dur=").append(event.durationMs).append("ms")
                    .append(" ok=").append(event.success)
                    .append(" toolCalls=").append(event.toolCallCount)
                    .append(" code=").append(event.code ?: "-")

            is TurnEvent.ToolCallStarted ->
                append(" step=").append(event.step)
                    .append(" tool=").append(event.tool)
                    .append(" call=").append(shortId(event.callId))
                    .append(" args=").append(event.argsSummary)

            is TurnEvent.ToolCallEnded ->
                append(" step=").append(event.step)
                    .append(" tool=").append(event.tool)
                    .append(" call=").append(shortId(event.callId))
                    .append(" code=").append(event.code)
                    .append(" error=").append(event.isError)
                    .append(" dur=").append(event.durationMs).append("ms")

            is TurnEvent.StepClosed ->
                append(" step=").append(event.step)
                    .append(" tools=").append(event.toolCount)

            is TurnEvent.TurnEnded ->
                append(" status=").append(event.status)
                    .append(" steps=").append(event.steps)
        }
    }

    /**
     * 调用 id 的前 6 位。
     *
     * 不打印全长：一行日志里出现两个 36 位 uuid 会让人一眼看不出哪行是哪行，
     * 而前 6 位在同一回合内撞车的概率低到可以忽略。配不上对的时候再用流水查全长。
     */
    private fun shortId(id: String): String = if (id.isEmpty()) "-" else id.take(6)
}
