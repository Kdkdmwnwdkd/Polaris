package com.beichen.polaris.core.agent

import com.beichen.polaris.core.TurnRecord

/**
 * 存储结构（[TurnRecord]）→ 压缩视图（[HistoryCompactor.Turn]）。
 *
 * ## 为什么单独一个文件，而不是写进 [HistoryCompactor]
 *
 * [HistoryCompactor] 是**纯函数**：只认「问题 / 答案 / 工具名」三样，不认存储格式。
 * 这让它能在纯 JVM 里编译（`run-history-compactor-verification.sh` 只拖进两个 core 文件），
 * 也让它不会因为会话 schema 改一次就跟着改一次。
 *
 * 而这段映射天生要认识 [TurnRecord]。放在同一文件里，压缩器就被迫背上
 * `ConversationStore` 那串 IO / 序列化依赖——一个「拼字符串」的工具，
 * 因为一个字段名就得加载 `AtomicJsonFile`，不值得。
 *
 * ## 为什么也不留在 ChatViewModel
 *
 * 它曾经在那儿，作为 UI 层的一个私有函数。代价是：`ChatViewModel` 正处于冻结行数
 * 上限上（3221 行），每加一个纯映射都要从别处省回来；而这段逻辑与界面毫无关系，
 * 留着只是让那个文件更难读。
 *
 * 越界兜底（变体为空 / 游标越界）在这里安静退化为「无答案」，**绝不抛**：
 * 历史构造失败会把整轮对话搞崩，而崩溃的时机恰好在用户点了发送之后。
 */
fun TurnRecord.toPriorTurn(): HistoryCompactor.Turn {
    val picked = variants
        .getOrNull(selectedIndex.coerceIn(0, (variants.size - 1).coerceAtLeast(0)))
    return HistoryCompactor.Turn(
        question = user.content.trim(),
        // 2026-11-23：界面合成的提示（⏹ 已停止 / 空回答兜底 / 失败说明）**不算回答**。
        //
        // 为什么必须在这里挡：这些气泡是 App 写的，不是模型的输出。一旦当成 assistant
        // 的真实回答喂回去，模型就把「遇到这类请求就说已停止」当成了正确行为。
        // 真机事故：两条误点停止留下的「⏹ 已停止」进了历史，之后用户发
        // 「执行 shell 命令 sleep 20」，模型原样回「⏹ 已停止」，一个工具都不调——
        // 用户以为「一发出去就自己停了」，实际是模型在复述界面文案（那 13 个 token
        // 还带着 usage，说明确实是模型吐的）。
        //
        // 置空而不是整轮丢弃：buildPriorMessages 对空 answer 只发 user 一条，
        // 保住「用户问过这件事」的上下文，又不会凭空造一条助手发言。
        answer = if (picked?.synthetic == true) "" else picked?.content?.trim().orEmpty(),
        // 只带工具**名**，不带结果：结果需要与 tool_calls 严格配对才能回灌，
        // 跨回合重建极易被严格供应商直接 400；名字足够挡住「把做过的事再做一遍」。
        tools = trace.mapNotNull { it.toolName }
    )
}
