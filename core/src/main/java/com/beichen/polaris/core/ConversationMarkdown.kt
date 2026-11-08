package com.beichen.polaris.core

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 功能五（P1-4a）：会话 → Markdown 导出（纯函数，零 Android / 零 IO 依赖——
 * harness 直接喂 ConversationRecord 断言输出文本）。
 *
 * 设计取舍（对标四家的共识形态，净室重写）：
 *  - **标题 + 元信息头**：导出时间 / 消息条数 / token 用量，让导出件离开 App 也能自描述；
 *  - **按轮组织**（`## 第 N 轮`）：与 App 内轮次变体结构同构，读者按「问→答」读；
 *  - **只导出选中变体**：重新生成产生的废弃变体不进导出件（用户分享的是「当前这版对话」）；
 *  - **工具调用折叠为引用块 + 代码块**：保留「AI 做过什么」的痕迹但不喧宾夺主；
 *  - **截断上限 [MAX_CONTENT_CHARS]**：ui_tree 转储 / 大文件读取结果动辄几十 KB，
 *    不限长会把分享 Intent 撑爆（TransactionTooLarge）。
 */

/** 单条消息正文的导出截断上限（超长贴「已截断」注记）。 */
private const val MAX_CONTENT_CHARS = 20_000

/** 导出时间格式（本地时区，秒级精度足够——导出件是给人看的）。 */
private val EXPORT_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/**
 * 会话记录 → Markdown 文本。
 *
 * 轮内顺序：user → trace（工具卡等）→ 选中变体（与 [ConversationBranch.flatten] 一致）；
 * 多变体轮在选中变体前标注「（变体 k/N）」——读者知道这轮曾重新生成过。
 *
 * W-RT9：轮号改成 `第 k/总轮数 轮`，且总轮数**含归档**。导出件是离开 App 的成品，
 * 只说「第 3 轮」的话，读者无从判断这是不是完整对话；带上总数才看得出有没有缺。
 * 无归档时总数 = 工作集轮数，编号与旧版逐字一致，导出件形态不变。
 */
fun conversationToMarkdown(
    record: ConversationRecord,
    /** W-RT9：归档轮（超出工作集的更早历史，旧→新）。空 = 无归档，导出与旧版一致。 */
    archivedTurns: List<TurnRecord> = emptyList()
): String = buildString {
    appendLine("# ${record.title}")
    appendLine()
    appendLine("- 导出时间：${EXPORT_TIME_FORMAT.format(java.time.Instant.ofEpochMilli(record.updatedAt))}")
    appendLine("- 消息条数：${record.messages.size}")
    // token 用量（hotfix21 起落盘）：对全部变体求和，与抽屉行的口径一致。
    // W-RT9：归档轮也算——用量是「这个会话一共花了多少」，删掉早期轮次不该让它变小。
    val usage = TokenUsage.sum(
        (archivedTurns + record.turns).flatMap { it.variants }
            .filter { it.role == ROLE_ASSISTANT }
            .map { TokenUsage(it.promptTokens, it.completionTokens) }
    )
    if (usage.totalTokens > 0) {
        appendLine("- Token 用量：↑ ${usage.promptTokens} · ↓ ${usage.completionTokens}")
    }
    appendLine()
    appendLine("---")

    // W-RT9：归档轮排在最前（旧→新），与工作集首尾相接——读者看到的是完整时间线，
    // 轮号连续不跳号。归档段前加一句说明，否则读者会以为这些轮次还留在 App 里可点开。
    val totalTurns = archivedTurns.size + record.turns.size
    val allTurns: List<Pair<TurnRecord, Boolean>> =
        archivedTurns.map { it to true } + record.turns.map { it to false }
    allTurns.forEachIndexed { index, (turn, isArchived) ->
        appendLine()
        if (isArchived && index == 0) {
            appendLine("（以下 ${archivedTurns.size} 轮为归档历史，已不在当前对话工作集内）")
            appendLine()
        }
        appendLine("## 第 ${index + 1}/$totalTurns 轮")
        appendMsg(turn.user)
        turn.trace.forEach { appendMsg(it) }
        val selected = turn.variants.getOrNull(turn.selectedIndex)
        if (selected != null) {
            if (turn.variants.size > 1) {
                appendLine()
                appendLine("*（变体 ${turn.selectedIndex + 1}/${turn.variants.size}）*")
            }
            appendMsg(selected)
        }
    }
    // 结尾补一行导出来源，读者知道这是什么
    appendLine()
    appendLine("---")
    appendLine()
    appendLine("*由北辰导出*")
}

/** 单条消息 → Markdown 片段（私有：只服务本文件的组装，不构成公开 API）。 */
private fun StringBuilder.appendMsg(rec: MsgRecord) {
    appendLine()
    when (rec.role) {
        ROLE_USER -> {
            appendLine("**🧑 用户**")
            appendLine()
            appendLine(rec.content.truncatedForExport())
        }

        ROLE_ASSISTANT -> {
            appendLine("**🤖 助手**")
            appendLine()
            appendLine(rec.content.truncatedForExport().ifBlank { "（无文本内容）" })
            if (rec.promptTokens > 0 || rec.completionTokens > 0) {
                appendLine()
                appendLine("*tokens：↑ ${rec.promptTokens} · ↓ ${rec.completionTokens}*")
            }
        }

        ROLE_TOOL -> {
            appendLine("> 🔧 工具 `${rec.toolName.orEmpty().ifBlank { "未知" }}`${if (rec.isError) "（失败）" else ""}")
            appendLine()
            appendLine("```")
            appendLine(rec.content.truncatedForExport().ifBlank { "（无输出）" })
            appendLine("```")
        }

        else -> Unit // 未知 role 不导出（存储层的兼容兜底，正常数据不会出现）
    }
}

/** 超长截断 + 注记（纯函数）；未超长原样返回。 */
private fun String.truncatedForExport(): String =
    if (length <= MAX_CONTENT_CHARS) this
    else take(MAX_CONTENT_CHARS) + "\n\n…（已截断，原文共 $length 字）"
