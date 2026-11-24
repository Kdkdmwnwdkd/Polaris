package com.beichen.polaris.core.agent

/**
 * 批派发遥测行的**拼装**（保序的直接证据）。
 *
 * ## 为什么需要这一行
 *
 * `tool.start` 事件由 `executeOne` 在**各协程内部**发出：并行时多个调用同时开跑，
 * 事件的到达顺序取决于线程调度，没有保证。于是真机日志里「谁先派发」这个问题
 * 只能靠间接推理回答——`ToolBatchExecutor` 的 `chunked` + `awaitAll` 在语义上保序，
 * 但日志本身看不出来。
 *
 * 这一行由**父协程单线程**写：块序即调用序，写下去的顺序就是派发顺序。
 * 它与 `tool.start` 的到达顺序摆在一起，「派发保序、事件乱序」与「派发也乱序」
 * 才分得开——后者是真 bug，前者是设计。
 *
 * ## 为什么抽成纯函数
 *
 * 原来这段拼装内联在 `RealAgentLoop.executeBatch` 里。内联时只能断言「日志里
 * 出现过某个字符串」，那是对实现的匹配，不是对行为的验证。抽出来之后
 * `assertEquals` 直接对比整行输出，格式一改就红——这种断言在重构时才不会假绿。
 *
 * 用 [shortCallId] 而不是全 id：一行里塞进五个 30 位长串，人眼反而分不出边界；
 * 10 位足够区分同批调用（见 `CallIdShort`），全 id 可以去流水里取。
 *
 * @param step 步号（回合内第几次「模型请求 → 工具批次」）
 * @param callIds 本批调用 id，**按模型给出的顺序**
 * @param hasMutation 本批是否含写操作（true = 串行路径）
 */
internal fun dispatchLine(step: Int, callIds: List<String>, hasMutation: Boolean): String =
    "派发 step=$step 本批 ${callIds.size} 个：hasMutation=$hasMutation" +
        "（${if (hasMutation) "串行" else "并行"}）" +
        " order=[${callIds.joinToString(",") { shortCallId(it) }}]"
