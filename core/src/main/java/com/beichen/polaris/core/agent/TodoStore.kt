package com.beichen.polaris.core.agent

import com.beichen.polaris.core.llm.primOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 任务清单存储（W-14）：模型用 `todo_write` / `todo_read` 自己维护步骤清单。
 *
 * 长任务（10+ 步）里模型容易忘掉「还剩什么」，
 * 一份显式清单能显著降低「跑到一半跑偏」的概率；用户也能在对话里看到进度。
 *
 * 存储：`filesDir/todos.json`，`{ "<conversationId>": "<markdown 清单>" }`。
 * 按会话隔离——换会话不串味；不做并发写保护（单进程、写入是整串覆盖，最坏丢一次清单）。
 */
class TodoStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun write(conversationId: String, markdown: String): Boolean = runCatching {
        val map = readMap().toMutableMap()
        if (markdown.isBlank()) map.remove(conversationId) else map[conversationId] = markdown
        file.parentFile?.mkdirs()
        val obj = JsonObject(map.mapValues { JsonPrimitive(it.value) })
        file.writeText(json.encodeToString(JsonObject.serializer(), obj))
        true
    }.getOrDefault(false)

    fun read(conversationId: String): String? = runCatching {
        readMap()[conversationId]?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun readMap(): Map<String, String> = runCatching {
        if (!file.exists()) return emptyMap()
        val obj = json.parseToJsonElement(file.readText()) as? JsonObject ?: return emptyMap()
        obj.mapNotNull { (k, v) -> v.primOrNull()?.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    companion object {
        /** 清单为空时的提示（读工具用，避免空字符串让模型以为坏了）。 */
        const val EMPTY_HINT = "（当前会话没有任务清单；需要时用 todo_write 建立）"
    }
}
