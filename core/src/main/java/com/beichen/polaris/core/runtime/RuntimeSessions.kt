package com.beichen.polaris.core.runtime

/**
 * 回合身份的登记处（[AgentRuntime] 队列键的一半）。
 *
 * ## 为什么集中登记，而不是各入口写各自的字符串
 *
 * `(session, source)` 是互斥键：两个入口**不小心用了同一个值**，它们就会互相排队，
 * 而且表现只是「某个入口偶尔卡住」——没有报错、没有日志，最难查的一类问题。
 * 常量集中在这里，冲突在代码审查时一眼可见；子代理那种动态键用函数生成，
 * 保证「每个任务一个键」的口径只有一处。
 *
 * 主界面与桌宠刻意是**两个键**：它们是两个说话人，可以同时各跑一个回合，
 * 真正要互斥的只是「同一入口内两次提问」。
 */
object RuntimeSessions {

    /** 主界面（`ChatViewModel`）。全局只有一个回合在跑，固定键足够。 */
    const val UI_MAIN = "ui:main"

    /** 桌宠（`PetAgentSession`）：与主界面并行，不抢同一个队列。 */
    const val UI_PET = "ui:pet"

    /**
     * 子代理。按**序号**而不是标题：两个同名子任务是两个独立任务，
     * 用标题做键会让它们互斥，把 `cloud=2` 的并发压回 1。
     */
    fun subAgent(index: Int): String = "sub:$index"
}
