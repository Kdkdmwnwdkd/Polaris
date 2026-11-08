package com.beichen.polaris.tools

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.knowledge.KnowledgeIndex
import com.beichen.polaris.core.llm.primOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * knowledge_search（W-RT6）：在本地知识库里检索离线文档。
 *
 * 与记忆库的分工：记忆库存「经历」（我们聊过什么、用户偏好什么），知识库存
 * 「资料」（用户放进来让我查的文档）。Agent 答题时两边一起喂。
 *
 * 文档从哪来：知识库目录是 `<filesDir>/knowledge/`，用户把 .txt / .md 放进去
 * （文件管理器或让 Agent 用文件工具复制都行）。没有独立导入界面——Agent 有
 * 完整的文件能力，「把这份资料加进知识库」本身就是一句工具调用的事。
 *
 * 每次调用现扫现建内存索引：量级是几十个本地文本，毫秒级，换来零索引失效
 * 问题（改了文件立刻生效）。
 */
class KnowledgeSearchTool(
    private val kbDir: File
) : AgentTool {

    override val name = "knowledge_search"

    override val description =
        "在本地知识库（用户主动放入的离线文档）里按关键词检索，返回最相关的文本片段。" +
            "用户让你查「资料/文档/手册」里写了什么，或问题涉及用户放进来让你参考的文件时用它；" +
            "查历史对话用 conversation_search，两者不通用。"

    override val systemPrompt =
        "知识库检索：查询词要具体（术语、型号、条目名），中文单字查询召回差，至少给两个字。" +
            "拿到片段后引用要点即可，不要整段复述；来源文件名要告诉用户。"

    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop(
            "action", "string", "search=按关键词检索（默认）；list=列出知识库里有哪些文件",
            enum = listOf("search", "list")
        ),
        JsonSchema.prop("query", "string", "检索关键词（action=search 时必填）"),
        JsonSchema.prop("limit", "integer", "最多返回几条片段，默认 4，上限 8")
    )

    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!kbDir.isDirectory) {
            return ToolResult.fail(
                "no_library",
                "知识库还没有建立（目录不存在）。",
                "让用户把 .txt / .md 文档放进知识库目录，或经用户同意后由你用文件工具复制过去，再重试。"
            )
        }
        val files = kbDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXT }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) {
            return ToolResult.fail(
                "no_docs",
                "知识库是空的（没有 .txt / .md 文档）。",
                "让用户把资料文件放进知识库目录，或经用户同意后由你复制过去；放好再检索。"
            )
        }

        val action = args["action"]?.primOrNull()?.contentOrNull?.trim()?.lowercase().orEmpty()
        if (action == "list") {
            val lines = files.mapIndexed { i, f ->
                "${i + 1}. ${f.name}（${f.length() / 1024 + 1} KB）"
            }
            return ToolResult.ok(
                code = "knowledge_list",
                message = "知识库里有 ${files.size} 篇文档：",
                data = lines.joinToString("\n")
            )
        }

        val raw = args["query"]?.primOrNull()?.contentOrNull?.trim().orEmpty()
        if (raw.isBlank()) {
            return ToolResult.fail("missing_param", "缺少 query", "传入具体关键词，例如「保修条款」或 default_router。")
        }
        val limit = (args["limit"]?.primOrNull()?.contentOrNull?.toIntOrNull() ?: 4).coerceIn(1, 8)

        // 现扫现建：上限卡住总量（50 篇 × 200K 字符），防止一个误放的巨型文件拖死调用
        val docs = files.take(MAX_FILES).map { f ->
            f.name to (runCatching { f.readText() }.getOrElse { "" }).take(MAX_CHARS_PER_DOC)
        }.filter { it.second.isNotBlank() }
        val built = KnowledgeIndex.build(docs)
        val hits = built.search(raw, limit)

        if (hits.isEmpty()) {
            return ToolResult.ok(
                code = "knowledge_no_hit",
                message = "${files.size} 篇文档里没有命中「$raw」。",
                data = "换更具体或同义的关键词再试；也可以先 action=list 看看库里到底有什么。"
            )
        }
        val text = hits.joinToString("\n\n") { s ->
            "【${s.chunk.doc} · 第${s.chunk.order + 1}段】\n${s.chunk.text}"
        }
        return ToolResult.ok(
            code = "knowledge_hits",
            message = "在知识库里找到 ${hits.size} 条相关片段（按相关度排序）：",
            data = text.take(MAX_RESULT_CHARS)
        )
    }

    private companion object {
        val SUPPORTED_EXT = setOf("txt", "md", "csv", "log")
        const val MAX_FILES = 50
        const val MAX_CHARS_PER_DOC = 200_000
        const val MAX_RESULT_CHARS = 4_000
    }
}
