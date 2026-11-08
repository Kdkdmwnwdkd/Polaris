package com.beichen.polaris.core

import com.beichen.polaris.core.io.AtomicJsonFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一条收藏的消息（W-30）。
 *
 * 为什么存快照而不是只存引用：
 *  - 会话可能被删除、轮次可能被「从此处删除」截断，引用会变成死链；
 *  - 收藏的语义是「这条内容我要留着」，不是「指向某处」——快照才是正确语义；
 *  - 代价是重复存储，因此单条截断到 [MAX_TEXT] 字。
 */
@Serializable
data class Bookmark(
    val id: String,
    val convId: String,
    val convTitle: String,
    /** `user` / `assistant`（与 [MsgRecord.role] 一致）。 */
    val role: String,
    val text: String,
    /** 原消息时间（可能为 0：旧数据无时间戳）。 */
    val timeMs: Long = 0,
    /** 收藏动作发生的时间。 */
    val createdAt: Long = 0
)

/**
 * 消息收藏存储（W-30）：单文件 JSON，读写全量、内存保序。
 *
 * 与 [ConversationStore] 的关系：**独立文件**，删会话不影响收藏（这正是收藏的意义）。
 * 容量：条数上限 [MAX_COUNT]，超出丢最旧——收藏是「精选」，不是归档。
 */
class BookmarkStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true      // 旧版本多出来的字段不导致整读失败
        encodeDefaults = true
    }

    /** 内存缓存：文件只在首次读/每次写时碰，列表页滚动不反复读盘。 */
    private var cache: MutableList<Bookmark>? = null

    /** 全部收藏，按收藏时间倒序（最新在前）。 */
    fun list(): List<Bookmark> = load().sortedByDescending { it.createdAt }

    /** 是否已收藏（同一会话 + 同一角色 + 同一正文视为同一条）。 */
    fun contains(convId: String, role: String, text: String): Boolean =
        load().any { it.convId == convId && it.role == role && it.text == text }

    /**
     * 收藏一条；已存在则返回已有条目（幂等，不产生重复）。
     * 正文为空不收藏（空消息没有收藏价值）。
     */
    fun add(
        convId: String,
        convTitle: String,
        role: String,
        text: String,
        timeMs: Long
    ): Bookmark? {
        val body = text.trim()
        if (body.isEmpty()) return null
        val items = load()
        items.firstOrNull { it.convId == convId && it.role == role && it.text == body }
            ?.let { return it }

        val item = Bookmark(
            id = "bm_${System.currentTimeMillis()}_${(0..999).random()}",
            convId = convId,
            convTitle = convTitle.take(60),
            role = role,
            text = body.take(MAX_TEXT),
            timeMs = timeMs,
            createdAt = System.currentTimeMillis()
        )
        // 超出上限丢最旧（按 createdAt 升序取尾部）
        val next = (items + item)
            .sortedBy { it.createdAt }
            .takeLast(MAX_COUNT)
        persist(next)
        return item
    }

    /** 取消收藏；返回是否真的删掉了。 */
    fun remove(id: String): Boolean {
        val items = load()
        val next = items.filterNot { it.id == id }
        if (next.size == items.size) return false
        persist(next)
        return true
    }

    /** 按「会话 + 角色 + 正文」删除（长按菜单只知道内容，不一定知道收藏 id）。 */
    fun removeByContent(convId: String, role: String, text: String): Boolean {
        val items = load()
        val target = items.firstOrNull {
            it.convId == convId && it.role == role && it.text == text.trim()
        } ?: return false
        return remove(target.id)
    }

    /** 全部清空（设置页「清空收藏」用）。 */
    fun clear() {
        persist(emptyList())
    }

    private fun load(): MutableList<Bookmark> {
        cache?.let { return it }
        val items = runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString<List<Bookmark>>(file.readText())
        }.getOrDefault(emptyList()).toMutableList()
        cache = items
        return items
    }

    private fun persist(items: List<Bookmark>) {
        cache = items.toMutableList()
        // 旧写法先 delete 再 rename：rename 失败就彻底丢光收藏。改走统一原子写
        AtomicJsonFile.write(file, json.encodeToString(items))
    }

    companion object {
        /** 收藏条数上限。 */
        const val MAX_COUNT = 500

        /** 单条正文上限（字符）。 */
        const val MAX_TEXT = 20_000
    }
}
