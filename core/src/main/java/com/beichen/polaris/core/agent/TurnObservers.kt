package com.beichen.polaris.core.agent

/**
 * 回合事件观察者的装配单点（P5-1）。
 *
 * 单独成文件而不是塞进 `TurnEventNdjson.kt`：那个文件是**纯序列化**，被 harness
 * 单独编译验证（零 Android 依赖、零日志依赖）。装配这里必须引用 `LoggingTurnObserver`，
 * 而它连着 `AgentLogger`——混在一起会让 harness 为了验一个 JSON 格式，
 * 去编译整个日志子系统（R12 三个 harness 就是这么挂的）。
 *
 * ## 为什么要有一个装配单点
 *
 * 生产链路需要**两个**观察者同时存在：文本行（给人看）+ NDJSON（给脚本算）。
 * 「两个都要装」只能靠约定记住，而漏一个的症状是「日志有、流水空」，
 * 双方都不报错——等你发现的时候，那段时间的数据已经没了。收成一处就漏不了。
 */
object TurnObservers {

    /** 文本行（[LoggingTurnObserver]）+ NDJSON 流水（[NdjsonTurnObserver]）。 */
    fun production(): TurnObserver =
        CompositeTurnObserver(LoggingTurnObserver(), NdjsonTurnObserver())
}
