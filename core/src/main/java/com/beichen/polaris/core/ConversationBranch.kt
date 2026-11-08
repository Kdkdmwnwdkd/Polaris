package com.beichen.polaris.core

import kotlinx.serialization.Serializable

/**
 * Phase 2 DoD②：「重新生成 3 次可左右切换且杀进程后仍在」的持久化模型。
 *
 * ## 为什么是「轮次变体 + 游标」而不是完整树
 * 完整树（任意节点分叉）表达力虽强，但序列化/渲染/迁移成本高；本批按产品裁定采用
 * **轮次变体 + 游标**（对齐 RikkaHub `MessageNode(messages, selectIndex)` 思路）：
 *  - 一轮 = [TurnRecord]（用户提问 + 若干助手变体 + 游标）；
 *  - 会话 = `List<TurnRecord>`（线性顺序）；
 *  - 重新生成 = 给同一轮**追加**一个变体，游标指向它（旧变体保留，可切回）；
 *  - 左右切换 = 游标 ±1（边界 clamp，首左移/末右移不动）；
 *  - 从中间轮重新发问 = 截断该轮之后的轮次，再新开一轮。
 * 完整树形（任意节点分叉）留后续工单（见 scripts/qa/BRANCH-NOTES.md）。
 *
 * ## 字段语义
 * @param user 该轮的用户提问（role=user）。
 * @param variants 该轮的助手回答变体（role=assistant），按生成顺序排列；重生成追加、可切回。
 * @param selectedIndex 当前选中变体下标（0-based）；越界/空变体时 [ConversationBranch.normalize]
 *   兜底为 0，绝不因脏数据崩溃。
 * @param trace 该轮中「用户之后、最终回答之前」的消息痕迹（工具卡、被中途打断了内容的中间助手），
 *   按发生顺序保存——迁移旧格式时用来兜住全部历史，保证「不许静默丢历史」。
 */
@Serializable
data class TurnRecord(
    val user: MsgRecord,
    val variants: List<MsgRecord> = emptyList(),
    val selectedIndex: Int = 0,
    val trace: List<MsgRecord> = emptyList()
)

/**
 * 轮次变体引擎（纯函数，无副作用、无 IO）——所有分支语义的唯一实现处，
 * 供 [ConversationStore]（持久化）与 JVM harness（`scripts/qa/BranchVerification.kt`）共用。
 *
 * 设计约束：
 *  - **纯函数**：输入 `List<TurnRecord>`，输出新的 `List<TurnRecord>`（不可变 data class），
 *    便于断言与幂等复跑；
 *  - **越界安全**：所有下标访问都先做范围判断或 `getOrNull`，脏数据（空变体、游标越界）只兜底、不崩溃；
 *  - **单点定义**：`variants` 顺序 = 生成顺序，`selectedIndex` 指向选中项，别处不得另写一套下标算术。
 */
object ConversationBranch {

    /**
     * 规范化：把每轮 `selectedIndex` 夹到合法范围。
     *  - `variants` 为空（异常/未生成）→ 游标归 0（不崩）；
     *  - `variants` 非空 → `selectedIndex ∈ [0, size-1]`（越界夹取）。
     * 已经是合法值的轮次原样返回（引用相等可省内存，语义无差别）。
     */
    fun normalize(turns: List<TurnRecord>): List<TurnRecord> = turns.map { t ->
        val idx = if (t.variants.isEmpty()) 0 else t.selectedIndex.coerceIn(0, t.variants.size - 1)
        if (idx == t.selectedIndex) t else t.copy(selectedIndex = idx)
    }

    /**
     * 旧格式迁移：把「平坦消息列表」切成轮次变体结构。
     *
     * 映射规则（旧格式 → 新格式）：
     *  - 遇到 `role=user` → 开一轮新的 [TurnRecord]；
     *  - 该轮内、最终助手回答**之前**的全部消息（工具卡、带 tool_calls 的中间助手）→ `trace`，保序；
     *  - 该轮内**最后一条** assistant → 唯一的 `variants` 元素，`selectedIndex=0`（老记录没有变体概念）；
     *  - 该轮没有 assistant（问了没答）→ `variants` 为空，`normalize` 后游标仍为 0。
     *
     * 这样 `flatten(fromMessages(msgs))` 对良构旧历史 **恒等于** `msgs`（迁移不丢内容、顺序不变），
     * 且每个旧会话读入后「变体数=1、selectedIndex=0」，与 DoD 迁移断言一致。
     */
    fun fromMessages(messages: List<MsgRecord>): List<TurnRecord> {
        val turns = mutableListOf<TurnRecord>()
        var user: MsgRecord? = null
        val pending = mutableListOf<MsgRecord>()

        fun closeTurn() {
            val u = user
            if (u != null) {
                val last = pending.lastOrNull()
                val variant = if (last != null && last.role == ROLE_ASSISTANT) last else null
                val trace = if (variant != null) pending.dropLast(1) else pending.toList()
                turns += TurnRecord(
                    user = u,
                    variants = if (variant != null) listOf(variant) else emptyList(),
                    selectedIndex = 0,
                    trace = trace
                )
            }
            pending.clear()
        }

        for (m in messages) {
            if (m.role == ROLE_USER) {
                closeTurn()
                user = m
            } else {
                // user 之前的前导消息（正常数据不应出现）也并入 pending，绝不放丢
                pending += m
            }
        }
        closeTurn()
        return turns
    }

    /**
     * 展开为「当前选中的」平坦消息列表——即下一轮 LLM 请求/UI 线性渲染所用的历史。
     *
     * 顺序：每轮 = `user → trace → 选中变体`。`selectedIndex` 越界时 `getOrNull` 返回 null，
     * 该轮只出 `user + trace`（不崩、不静默丢 user/trace）。
     */
    fun flatten(turns: List<TurnRecord>): List<MsgRecord> = buildList {
        for (t in turns) {
            add(t.user)
            addAll(t.trace)
            val sel = t.variants.getOrNull(t.selectedIndex)
            if (sel != null) add(sel)
        }
    }

    /**
     * 为第 [turnIndex] 轮追加一个助手变体，并把游标指向新变体（重新生成的写入口）。
     * `turnIndex` 越界时原样返回（无副作用，调用方零成本传 `turns.lastIndex`）。
     */
    fun appendVariant(turns: List<TurnRecord>, turnIndex: Int, variant: MsgRecord): List<TurnRecord> {
        if (turnIndex !in turns.indices) return turns
        val t = turns[turnIndex]
        val variants = t.variants + variant
        return turns.toMutableList().also {
            it[turnIndex] = t.copy(variants = variants, selectedIndex = variants.lastIndex)
        }
    }

    /**
     * 左右切换第 [turnIndex] 轮的选中变体，[delta] = -1（左）/ +1（右）。
     * 边界 clamp：首个左移、末个右移都**原地不动**（返回不变列表）；单变体轮不响应切换。
     * `turnIndex` 越界返回原列表。
     */
    fun select(turns: List<TurnRecord>, turnIndex: Int, delta: Int): List<TurnRecord> {
        if (turnIndex !in turns.indices) return turns
        val t = turns[turnIndex]
        if (t.variants.size <= 1) return turns
        val target = (t.selectedIndex + delta).coerceIn(0, t.variants.size - 1)
        if (target == t.selectedIndex) return turns
        return turns.toMutableList().also { it[turnIndex] = t.copy(selectedIndex = target) }
    }

    /**
     * 从第 [turnIndex] 轮重新发问（「从中间轮重新发问」）：
     * **保留** `turns[0..turnIndex]`（含该轮），**截断**其后所有轮次，再追加一轮新提问 [user]。
     *
     * 裁决说明（见 BRANCH-NOTES.md）：需求原文「截断该轮之后的 Turn，新开一轮」——
     * 即保留该轮、丢弃其后的续写分支，再以新问题开一轮；返回列表轮次下标天然连续。
     * `turnIndex` 越界时 `coerceIn` 到 `[0, size]`，等价于「全留」或「全截」后新开，不崩。
     */
    fun reaskAfter(turns: List<TurnRecord>, turnIndex: Int, user: MsgRecord): List<TurnRecord> {
        val end = (turnIndex + 1).coerceIn(0, turns.size)
        return turns.take(end) + TurnRecord(user = user)
    }
}
