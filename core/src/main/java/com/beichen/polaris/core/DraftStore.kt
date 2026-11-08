package com.beichen.polaris.core

import com.beichen.polaris.core.io.AtomicJsonFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 功能五（P1-8）：会话草稿按会话独立保存——切走再切回，未发送的内容还在。
 *
 * 形态：`drafts.json` 放 App filesDir，`{ 会话id: 草稿文本 }` 单键值表。
 * 为什么不并入 ConversationRecord：草稿是「易失的中间态」——发送即消失、删会话即消失，
 * 与消息的持久化生命周期不同；混进 conversations.json 会让每次追加消息都重写一份
 * 大概率为空的草稿字段，也让备份件语义变浑（备份该不该含没发送的文字？含了是隐私面扩大）。
 *
 * 持久化纪律从简（形态学 [com.beichen.polaris.memory.MemoryStore] 的轻量子集）：
 * 原子写 tmp + rename（无 .bak——草稿丢了顶多重新打，不值得双倍文件）；
 * 读写 synchronized + runCatching：草稿坏了不能拖垮任何主流程。
 */
class DraftStore(file: File) {

    private val file: File = file
    private val tmpFile = File(file.parentFile, file.name + ".tmp")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private val drafts = mutableMapOf<String, String>()

    init {
        runCatching { file.parentFile?.mkdirs() }
        load()
    }

    /** 读某会话草稿；不存在/空白返空串（输入框直接显示空）。 */
    fun get(convId: String): String = synchronized(lock) { drafts[convId]?.takeIf { it.isNotBlank() } ?: "" }

    /**
     * 写某会话草稿。空白 = 删除该键（切走时空输入框不该在文件里留一堆空串）。
     * 同步落盘：切会话是低频操作，一次全量重写可负担。
     */
    fun set(convId: String, text: String) {
        synchronized(lock) {
            if (text.isBlank()) drafts.remove(convId) else drafts[convId] = text
        }
        persist()
    }

    /** 删会话时清它的草稿（会话没了草稿留着是垃圾数据）。 */
    fun remove(convId: String) {
        synchronized(lock) { drafts.remove(convId) }
        persist()
    }

    /** 全部清空（备份恢复用：恢复后的会话 id 与旧草稿对不上，留着是错配数据）。 */
    fun clear() {
        synchronized(lock) { drafts.clear() }
        persist()
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    private fun load() {
        val parsed = runCatching {
            if (file.exists()) {
                json.decodeFromString<Map<String, String>>(file.readText())
            } else {
                emptyMap()
            }
        }.getOrNull() ?: emptyMap()
        synchronized(lock) {
            drafts.clear()
            // 读入净化：空白值一律丢弃（手改文件/旧版本可能留空串）
            parsed.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) drafts[k] = v }
        }
    }

    private fun persist() {
        // 草稿是「用户刚打的字」，丢一次就是一次实打实的体验事故——必须带 fsync
        val payload = runCatching {
            json.encodeToString(synchronized(lock) { drafts.toMap() })
        }.getOrNull() ?: return
        AtomicJsonFile.write(file, payload)
    }
}
