package com.beichen.polaris.memory

import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * L9 记忆库的四个 AgentTool：写 / 检索 / 列表 / 遗忘。
 * 风格对齐 tools/BuiltInTools.kt：AgentTool + JsonSchema.objectSchema + runCatching 兜底，
 * 且**可选参数必须显式传 required**，否则 objectSchema 默认把所有参数都置为必填。
 *
 * T02：统一契约 ToolResult{ok, code, message, hint, data}，失败 hint 必填。
 * T03：readOnly 标注——memory_search / memory_list 只读；
 * memory_write / memory_forget 为写操作（改记忆库状态）。
 */

/* ------------------------------------------------------------------ */
/* memory_write                                                        */
/* ------------------------------------------------------------------ */
class MemoryWriteTool(private val store: MemoryStore) : AgentTool {
    override val name = "memory_write"
    override val description =
        "把用户偏好、关键事实或重要约定写入长期记忆库，跨会话长期保留。kind 可选 fact/preference/event/note，salience 越大越不容易被遗忘（1-10）。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("content", "string", "要记住的内容（一句话说清事实或偏好）"),
        JsonSchema.prop("kind", "string", "记忆类型", enum = listOf("fact", "preference", "event", "note")),
        JsonSchema.prop("tags", "string", "标签，中英文逗号或空格分隔，便于后续检索（可选）"),
        JsonSchema.prop("salience", "string", "重要度 1-10，默认 5（可选）"),
        required = listOf("content")
    )

    override val systemPrompt =
        "当你从对话中得知用户的长期偏好、稳定事实或重要约定时，用 memory_write 记下来，" +
            "这样即便开启新会话也能保持一致；不要记录一次性临时信息。"

    /** 写操作：向记忆库新增数据。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val content = args["content"]?.primOrNull()?.contentOrNull?.trim()
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 content",
                    hint = "memory_write 必须提供 content（要记住的一句话内容）。"
                )
            if (content.isBlank()) {
                return@withContext ToolResult.fail(
                    code = "invalid_param",
                    message = "content 不能为空",
                    hint = "请提供要记录的非空白内容（一句话说清事实或偏好）。"
                )
            }
            val kind = args["kind"]?.primOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: "note"
            val tags = args["tags"]?.primOrNull()?.contentOrNull
                ?.split(",", "，", "、", " ")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            // salience 上限压到 7：8–10 留给系统/用户手动标记的重要记忆，
            // 否则模型（或被劫持的工具调用）能把恶意记忆置 10 使其永驻并稳定进提示词。
            val salience = args["salience"]?.primOrNull()?.contentOrNull?.trim()
                ?.toIntOrNull()?.coerceIn(1, 7) ?: 5

            // 为什么 memory_write **故意**不暴露 pinned：
            // pinned 免于衰减与淘汰，等价于「永久驻留且每轮必进提示词」。上面刚把 salience
            // 压到 7 才堵住「模型给自己置顶」，再开一个 pinned 参数就等于把那个口子以更彻底
            // 的形式还回去——被注入的一段文字只要带 pinned=true，就能永久占据上下文，
            // 且用户在任何衰减/淘汰里都看不到它被清掉。
            // 所以 pinned 只有两个合法入口：用户手动标记（UI）、系统写入硬约束（负知识）。
            val item = store.add(content, kind, tags, salience)
            val tagText = if (item.tags.isEmpty()) "无" else item.tags.joinToString(",")
            ToolResult.ok(
                code = "memory_written",
                message = "已记住 [${item.kind}] id=${item.id}｜重要度=${item.salience}｜标签=$tagText\n内容：${item.content}"
            )
        }.getOrElse {
            ToolResult.fail(
                code = "io_error",
                message = "写入记忆失败：${it.message}",
                hint = "确认存储可写；可换用更短的 content 重试。"
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* memory_search                                                       */
/* ------------------------------------------------------------------ */
class MemorySearchTool(private val store: MemoryStore) : AgentTool {
    override val name = "memory_search"
    override val description =
        "在长期记忆库中按关键词检索过往记忆（偏好/事实/事件），返回最相关的若干条。需要回忆用户信息时优先用它。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("query", "string", "检索关键词，可中英文混写"),
        JsonSchema.prop("topK", "string", "返回条数上限，默认 10（可选）"),
        required = listOf("query")
    )

    /** 只读：仅检索记忆库，无副作用。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val query = args["query"]?.primOrNull()?.contentOrNull?.trim()
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 query",
                    hint = "memory_search 必须提供 query（检索关键词，可中英文混写）。"
                )
            val topK = args["topK"]?.primOrNull()?.contentOrNull?.trim()
                ?.toIntOrNull()?.coerceIn(1, 50) ?: 10

            val hits = store.search(query, topK)
            if (hits.isEmpty()) {
                return@withContext ToolResult.ok(
                    code = "memory_no_hit",
                    message = "未找到相关记忆（query=$query）",
                    data = ""
                )
            }
            val body = hits.joinToString("\n") { item ->
                val tagText = if (item.tags.isEmpty()) "" else " #${item.tags.joinToString(" #")}"
                "[${item.kind}] ${item.id} ${item.content}$tagText"
            }
            ToolResult.ok(
                code = "memory_hits",
                message = "命中 ${hits.size} 条（共 ${store.size()} 条记忆）",
                data = body
            )
        }.getOrElse {
            ToolResult.fail(
                code = "io_error",
                message = "检索记忆失败：${it.message}",
                hint = "更换关键词后重试；持续失败请告知用户记忆库暂不可用。"
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* memory_list                                                         */
/* ------------------------------------------------------------------ */
class MemoryListTool(private val store: MemoryStore) : AgentTool {
    override val name = "memory_list"
    override val description = "列出记忆库中最新的若干条记忆（按更新时间倒序），用于概览已记住了什么。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("limit", "string", "返回条数，默认 20（可选）"),
        required = emptyList()
    )

    /** 只读：仅列举记忆库，无副作用。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val limit = args["limit"]?.primOrNull()?.contentOrNull?.trim()
                ?.toIntOrNull()?.coerceIn(1, 100) ?: 20
            val items = store.list(limit)
            if (items.isEmpty()) {
                return@withContext ToolResult.ok(
                    code = "memory_empty",
                    message = "记忆库为空，还没有任何记忆。",
                    data = ""
                )
            }
            val body = items.joinToString("\n") { item -> "[${item.kind}] ${item.id} ${item.content}" }
            ToolResult.ok(
                code = "memory_listed",
                message = "共 ${store.size()} 条记忆，以下为最近 ${items.size} 条",
                data = body
            )
        }.getOrElse {
            ToolResult.fail(
                code = "io_error",
                message = "读取记忆失败：${it.message}",
                hint = "稍后重试；持续失败请告知用户记忆库暂不可用。"
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* memory_forget                                                       */
/* ------------------------------------------------------------------ */
class MemoryForgetTool(private val store: MemoryStore) : AgentTool {
    override val name = "memory_forget"
    override val description = "按 id 永久删除一条记忆。用户明确要求忘记某件事时使用；误删无法恢复，请谨慎。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("id", "string", "要删除的记忆 id，可从 memory_search / memory_list 获得"),
        required = listOf("id")
    )

    /** 写操作：永久删除记忆数据。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val id = args["id"]?.primOrNull()?.contentOrNull?.trim()
                ?: return@withContext ToolResult.fail(
                    code = "missing_param",
                    message = "缺少 id",
                    hint = "memory_forget 必须提供 id（可从 memory_search / memory_list 获得）。"
                )
            val ok = store.forget(id)
            if (ok) {
                ToolResult.ok(
                    code = "memory_forgotten",
                    message = "已忘记该记忆：$id（剩余 ${store.size()} 条）"
                )
            } else {
                ToolResult.fail(
                    code = "not_found",
                    message = "未找到 id 对应的记忆：$id",
                    hint = "先用 memory_search / memory_list 确认有效 id，再重试。"
                )
            }
        }.getOrElse {
            ToolResult.fail(
                code = "io_error",
                message = "删除记忆失败：${it.message}",
                hint = "确认 id 有效后重试；持续失败请告知用户。"
            )
        }
    }
}
