package com.beichen.polaris.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * R-10：会话**分片**的落盘格式（一个 `<id>.json` = 一个会话）。
 *
 * ## 为什么单独一个 DTO，而不是直接序列化 [ConversationRecord]
 * [ConversationRecord] 同时带着 `messages` 与 `turns` 两份正文——这是 Phase 2
 * 为「旧版本 App 也能读新文件」留下的双写：前者是 [ConversationBranch.flatten]
 * 的展开缓存，后者才是真源。整库单文件时代每次落盘要把两份一起编码，
 * 写放大 2×，且 `messages` 这个字段永远删不掉。
 *
 * 分片之后「一个文件 = 一个会话」，落盘只需要真源：
 *  - 只写 [turns]，`messages` 在读入时用 [ConversationBranch.flatten] 现算；
 *  - 内存模型 / 备份交换格式仍是 [ConversationRecord]（含 messages），
 *    对外 API（[ConversationStore.snapshot] / [ConversationStore.replaceAll]）零变化——
 *    备份 ZIP 里的 `conversations.json` 照旧是完整快照，不受分片影响。
 *
 * 于是三宗写放大一起消失：整库全量重写 → 只写脏会话；整库 `.bak` 复制 →
 * 分片级 `.bak`；`messages`/`turns` 双写 → 只存 `turns`。
 */
@Serializable
internal data class ConversationShard(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val turns: List<TurnRecord> = emptyList(),
    val pinned: Boolean = false,
    val workspaceDir: String = ""
) {
    /** 分片 → 内存记录：`messages` 由真源现算，不落盘也不丢。 */
    fun toRecord(): ConversationRecord = ConversationRecord(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        messages = ConversationBranch.flatten(turns),
        turns = turns,
        pinned = pinned,
        workspaceDir = workspaceDir
    )

    companion object {
        /** 内存记录 → 分片：丢掉 `messages` 缓存，只留真源。 */
        fun of(rec: ConversationRecord): ConversationShard = ConversationShard(
            id = rec.id,
            title = rec.title,
            createdAt = rec.createdAt,
            updatedAt = rec.updatedAt,
            turns = rec.turns,
            pinned = rec.pinned,
            workspaceDir = rec.workspaceDir
        )
    }
}

/**
 * R-10：主文件（原来的整库 `conversations.json`）的新格式——**只存会话 id 清单**。
 *
 * 它回答的唯一问题是「磁盘上有哪些会话、按什么顺序」，不含任何消息正文：
 * 摘要（标题/时间/置顶/用量）全部从各自分片读入后在内存里算，
 * 追加一条消息再也不必重写这个主文件。
 *
 * @param schemaVersion 格式版本。旧整库文件是 JSON **数组**，用它和对象格式一刀切开：
 *   读到对象且版本 ≥2 走分片路径；读到数组走一次性迁移（见 ConversationStore.load）。
 * @param ids 会话 id 列表（顺序即 [ConversationStore.list] 之外的稳定兜底顺序）。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class ConversationIndex(
    // ALWAYS：即使等于默认值也要写进文件。index 靠「对象 + 版本」和旧整库数组
    // 一刀切开，版本号若因 encodeDefaults=false 缺席，格式判定就成了猜谜。
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = SCHEMA_VERSION,
    val ids: List<String> = emptyList()
) {
    companion object {
        const val SCHEMA_VERSION = 2
    }
}
