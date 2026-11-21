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
fun TurnRecord.toPriorTurn(): HistoryCompactor.Turn = HistoryCompactor.Turn(
    question = user.content.trim(),
    answer = variants
        .getOrNull(selectedIndex.coerceIn(0, (variants.size - 1).coerceAtLeast(0)))
        ?.content?.trim().orEmpty(),
    // 只带工具**名**，不带结果：结果需要与 tool_calls 严格配对才能回灌，
    // 跨回合重建极易被严格供应商直接 400；名字足够挡住「把做过的事再做一遍」。
    tools = trace.mapNotNull { it.toolName }
)
