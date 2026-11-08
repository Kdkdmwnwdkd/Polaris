package com.beichen.polaris.core

/**
 * W-RT9：长对话全档保留——归档纯函数层。
 *
 * 背景：会话库存量封顶 [ConversationStore.MAX_TURNS] 轮，超限从最旧丢——
 * 丢掉的轮次此前是**真没了**。长对话用户回头查「上周那条命令的输出」就抓瞎。
 * 本层纪律：**内存里的工作集照旧封顶**（读写性能不打折），被挤出的轮次
 * 全量归档到旁路文件，一条不少。
 *
 * 纯函数三件套：
 *  - [evictOldest]：裁谁留谁（调用方拿 evicted 落归档文件）；
 *  - [page]：归档分段读（长会话一次性全量加载会卡 UI，按页取）；
 *  - 配合 `conversationToMarkdown` 的 archivedTurns 入参，导出也带全档。
 *
 * 归档文件本身是追加式 JSONL（每轮一行），由存储层负责读写——本层不碰 IO，
 * 沙箱可真编真跑。JSON 的容错解析（坏行跳过）也在存储层。
 */
object ConversationArchive {

    /** 裁剪结果：kept 是内存工作集（保持原顺序），evicted 是被挤出的最旧轮（旧→新顺序）。 */
    data class Eviction(val kept: List<TurnRecord>, val evicted: List<TurnRecord>)

    /**
     * 裁掉超出 [maxTurns] 的最旧轮。
     * 恰好等于上限不裁（无扰）；负数/零上限按「全裁」处理（调用方自己把关，
     * 本层不猜业务——归档场景不存在「全裁留着」的合理诉求，如实执行）。
     */
    fun evictOldest(turns: List<TurnRecord>, maxTurns: Int): Eviction {
        if (turns.size <= maxTurns) return Eviction(turns, emptyList())
        val cut = turns.size - maxTurns
        if (maxTurns <= 0) return Eviction(emptyList(), turns)
        return Eviction(turns.drop(cut), turns.take(cut))
    }

    /**
     * 归档分段读：[offset] 起（0-based，旧→新），取 [limit] 轮。
     * 越界一律夹到安全范围（空页返回空表），绝不抛——翻页 UI 乱点不崩。
     * 注意窗口起点先夹：offset 为负时终点从**夹后**的 0 起算（否则窗口整体跟着负数偏移）。
     */
    fun page(all: List<TurnRecord>, offset: Int, limit: Int): List<TurnRecord> {
        if (limit <= 0) return emptyList()
        val from = offset.coerceIn(0, all.size)
        val to = (from + limit).coerceIn(from, all.size)
        return all.subList(from, to)
    }
}
