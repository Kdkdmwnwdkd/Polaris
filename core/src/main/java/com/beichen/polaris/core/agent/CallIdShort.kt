package com.beichen.polaris.core.agent

/**
 * 日志里显示调用 id 时保留的长度。
 *
 * ## 为什么不能是 6
 *
 * 真实 id 形如 `call_00_ny3UxcMRXPYSqmrW54W09467`（OpenAI 风格：固定前缀 + 序号 +
 * 随机串）。**前 6 位恰好是 `call_0`**——同一步里并行的三个调用会打印成三行
 * 一模一样的 `call=call_0`，而「区分同批并行调用」正是 callId 被加进日志的唯一目的
 * （P5-1 的原始动机）。截到 6 位等于把这个字段废掉，而且是**静默**废掉：
 * 日志看着有值，只是值全一样。
 *
 * 2026-11-23 真机验证就是这么被误导的——流水里的全 id 证明它们完全唯一
 * （`call_00_` / `call_01_` / `call_02_`），日志里却三家相同。
 *
 * ## 为什么是 10
 *
 * 10 位刚好覆盖 `call_00_` + 2 位随机，足以区分同批调用；又不至于让一行日志里
 * 出现两个 30 位长串而看不出哪行是哪行。真需要全 id 时去流水（ndjson）里取。
 */
internal const val SHORT_CALL_ID_LEN: Int = 10

/**
 * 调用 id 的日志短形式。
 *
 * 抽成顶层函数（而不是 `LoggingTurnObserver` 的私有方法）是为了能被断言：
 * 观察者本身 import 了 `AgentLogger`（协程 + 文件 IO），进不了变异门禁的
 * kotlinc + JUnit 闭包；而这一行判定恰恰是「日志能不能区分并发调用」的唯一依据。
 */
internal fun shortCallId(id: String): String =
    if (id.isEmpty()) "-" else id.take(SHORT_CALL_ID_LEN)
