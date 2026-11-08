package com.beichen.polaris.core.llm

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.ConfigSanitizer
import com.beichen.polaris.core.LlmHttpException
import com.beichen.polaris.core.LLMClient
import com.beichen.polaris.core.LLMConfig
import com.beichen.polaris.core.PingResult
import com.beichen.polaris.core.SystemMessage
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolSchemaJson
import com.beichen.polaris.core.ToolMessage
import com.beichen.polaris.core.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/**
 * Claude Messages API 适配（Provider id = "claude"）。
 *
 * 与 OpenAIClient 的关键差异（全部内聚在本类内，对外统一成同一套
 * ChatMessage / AssistantMessage / ToolCall）：
 *  - system 是顶层字段，不是 messages[0]；
 *  - max_tokens 必填；
 *  - 工具调用是 content block：assistant 消息里 tool_use block，
 *    回传时是**一条 user 消息**里并列多个 tool_result block
 *    （并行工具调用必须合并成一条，拆多条会 400）；
 *  - 鉴权头是 x-api-key + anthropic-version，不是 Bearer；
 *  - 图片只认 base64（http URL 由 [ImageUris] 预下载转换）。
 *
 * 日志与脱敏复用 OpenAIClient 的同一套 AgentLogger / ConfigSanitizer 机制。
 *
 * 流式（T01）：[LLMClient.streamChat] 暂用默认实现（chat() 整条 + 单条 Completed）；
 * 后续可接 SSE（content_block_delta 增量 + input_json_delta 工具参数分片累积，
 * 参照 OpenAIClient 的 readSseDataObjects / OpenAiToolCallAccumulator 模式）。
 */
class ClaudeClient(
    private val config: LLMConfig,
    /**
     * W-46：读超时 180s / 不限整通时长（默认 10s 会把「模型在思考」误判成网络故障）。
     * 共享实例：连接池与 Dispatcher 按实例持有，三家协议没必要各开一份。
     */
    private val http: OkHttpClient = LlmHttp.shared
) : LLMClient {

    override val id: String = "claude"

    /** Claude 的 max_tokens 必填；给足一档，避免长回复被截断。 */
    private val maxTokens = 8192

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage = withContext(Dispatchers.IO) {
        // 图片统一成 data URI（URL 需要先下载，Claude 不认外链）
        val resolved = ImageUris.resolveImages(http, messages)

        AgentLogger.llmRequest(effectiveModel(), messages.size, tools.size)
        AgentLogger.apiTrace(endpointUrl(), effectiveModel(), effectiveApiKey())
        val startedAt = System.currentTimeMillis()

        val text = executeChat(bodyJson(resolved, tools, stream = false, cache = promptCacheEnabled()))
            ?: run {
                // 400 且错误里点到缓存字段 → 中转/兼容端点不认，摘掉标记重发一次。
                // 宁可贵一点，不能因为「想省钱」把整条通道打死。
                disablePromptCache("首次请求被拒")
                executeChat(bodyJson(resolved, tools, stream = false, cache = false))
            }
            ?: throw IllegalStateException("Claude 请求在降级后仍未返回内容")

        AgentLogger.llmResponse(
            effectiveModel(), true,
            System.currentTimeMillis() - startedAt,
            "响应长度=${text.length}"
        )
        val message = parseResponse(text)
        // Claude 的 input_tokens 不含命中部分，分母要自己加（见 TokenUsage.cachedPromptTokens）
        logPromptCacheUsage(message.usage, cachedInPrompt = false)
        message
    }

    /**
     * 发一次请求并读回原文；成功返回 body，失败抛 [LlmHttpException]。
     *
     * 缓存降级需要「先拿到失败原因再决定要不要重发」，所以错误处理从 [chat] 里拆出来：
     * 400 且响应体提到 cache_control / system 时返回 null 交给调用方重试，
     * 其余错误照旧快速失败（401/403 重试多少次都没用）。
     */
    private fun executeChat(body: JsonObject): String? {
        val resp = try {
            http.newCall(buildRequest(body)).execute()
        } catch (e: IOException) {
            AgentLogger.e("LLM", "Claude 网络请求异常", e)
            throw e
        }
        return resp.use { r ->
            if (!r.isSuccessful) {
                // body.string() 只能读一次：先存变量；401 响应体可能回显 key，必须先脱敏
                val errBody = runCatching { r.body?.string() }.getOrDefault("") ?: ""
                val safeBody = AgentLogger.sanitize(errBody)
                AgentLogger.httpError(r.code, safeBody)
                if (r.code == 400 && looksLikeCacheRejection(safeBody)) return@use null
                throw LlmHttpException(r.code, "Claude 请求失败 ${r.code}：${safeBody.take(300)}", safeBody)
            }
            r.body!!.string()
        }
    }

    override fun streamText(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<String> = flow {
        // 流式分支的图片解析需要挂起，放到 flow 的 collect 协程里做
        val resolved = ImageUris.resolveImages(http, messages)
        // 循环只为一件事故：端点不认 cache_control 时摘掉标记重发一次。
        // 此时**一个 delta 都还没 emit**，重发不会让客户端看到重复文本。
        while (true) {
            var retryWithoutCache = false
            val cache = promptCacheEnabled()
            val request = buildRequest(bodyJson(resolved, tools, stream = true, cache = cache))
            AgentLogger.apiTrace(endpointUrl(), effectiveModel(), effectiveApiKey())
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = runCatching { resp.body?.string() }.getOrDefault("") ?: ""
                    val safeBody = AgentLogger.sanitize(errBody)
                    AgentLogger.httpError(resp.code, safeBody)
                    if (resp.code == 400 && cache && looksLikeCacheRejection(safeBody)) {
                        retryWithoutCache = true
                        return@use
                    }
                    // hotfix10：同 OpenAIClient——抛结构化 LlmHttpException（带 code 与
                    // Retry-After），让上层认得出 429 并走限流专用退避序列。
                    throw com.beichen.polaris.core.LlmHttpException(
                        code = resp.code,
                        message = "Claude 流式请求失败 ${resp.code}: ${safeBody.take(200)}",
                        serverBody = safeBody.take(200),
                        retryAfterMs = com.beichen.polaris.core.parseRetryAfterMs(
                            resp.header("Retry-After")
                        )
                    )
                }
                val source = resp.body!!.source()
                // hotfix35：兼容加固——个别中转会忽略 stream:true 直接回整段 JSON
                // （无任何 data: 行）。旧实现产出空回答；这里留一份原文，末尾判定后
                // 走非流式解析降级成一次性输出。
                var sawData = false
                val rawFallback = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: continue
                    if (!line.startsWith("data:")) {
                        if (rawFallback.length < PLAIN_BODY_MAX) rawFallback.append(line).append('\n')
                        continue
                    }
                    sawData = true
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    runCatching {
                        // hotfix33：SSE 分片可能是 null / 非对象，软转换跳过（不打断流）
                        val obj = json.parseToJsonElement(data).objOrNull() ?: return@runCatching
                        when (obj["type"]?.primOrNull()?.contentOrNull) {
                            "content_block_delta" -> {
                                val delta = obj["delta"]?.objOrNull()
                                if (delta?.get("type")?.primOrNull()?.contentOrNull == "text_delta") {
                                    delta["text"]?.primOrNull()?.contentOrNull?.let { emit(it) }
                                }
                            }
                            // 流中途报错（超限等）：抛出供上层按普通错误处理
                            "error" -> {
                                val msg = obj["error"]?.objOrNull()?.get("message")
                                    ?.primOrNull()?.contentOrNull ?: "Claude 流式错误"
                                throw IOException(msg)
                            }
                        }
                    }
                }
                // hotfix35：整段无 data: 行 → 供应商忽略 stream:true，按非流式降级输出
                if (!sawData) {
                    // hotfix36：降级留痕（日志页可对上「不逐字」的现象）
                    com.beichen.polaris.core.AgentLogger.i(
                        "Chat",
                        "Claude 通道未按 SSE 返回（stream:true 被忽略），已降级为整段响应输出"
                    )
                    runCatching { parseResponse(rawFallback.toString().trim()) }.getOrNull()
                        ?.content?.takeIf { it.isNotBlank() }?.let { emit(it) }
                }
            }
            if (!retryWithoutCache) return@flow
            disablePromptCache("流式请求被拒")
        }
    }

    /** hotfix35：非 SSE 降级收集上限（防超大 body 占内存）。 */
    private val PLAIN_BODY_MAX = 2 * 1024 * 1024

    // —— 防御性清洗：与 OpenAIClient 同一套 ConfigSanitizer ——

    private fun effectiveBaseUrl(): String =
        ConfigSanitizer.normalizeBaseUrl(config.baseUrl).trimEnd('/')

    private fun effectiveApiKey(): String = ConfigSanitizer.cleanApiKey(config.apiKey)

    private fun effectiveModel(): String = ConfigSanitizer.cleanModel(config.model)

    /**
     * 实际端点。Claude 路径是 /v1/messages：
     * 用户填 https://api.anthropic.com → /v1/messages；
     * 已带 /v1 的不重复拼（防御手滑）。
     */
    private fun endpointUrl(): String {
        val base = effectiveBaseUrl()
        return if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
    }

    private fun buildRequest(bodyJson: JsonObject): Request {
        val apiKey = effectiveApiKey()
        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(endpointUrl())
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("Content-Type", "application/json")
            .apply { config.customHeaders.forEach { (k, v) -> addHeader(k, v) } }
            .post(body)
            .build()
    }

    /**
     * 最小连通性探测：一条 "ping"、max_tokens=1、不带工具。
     * 用于「测试连接」按钮，不污染对话历史，也几乎不产生 token 消耗。
     */
    override suspend fun testPing(): PingResult = withContext(Dispatchers.IO) {
        val requestJson = buildJsonObject {
            put("model", effectiveModel())
            put("max_tokens", 1)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "ping")
                }
            }
        }
        val request = buildRequest(requestJson)
        AgentLogger.apiTrace(endpointUrl(), effectiveModel(), effectiveApiKey())
        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            AgentLogger.e("LLM", "Claude 测试连接网络异常", e)
            throw e
        }
        resp.use { r ->
            val body = runCatching { r.body?.string() }.getOrDefault("") ?: ""
            val safe = AgentLogger.sanitize(body)
            PingResult(code = r.code, bodyPreview = safe.take(200))
        }
    }

    private fun parseResponse(text: String): AssistantMessage {
        // hotfix33：全链安全取值——content 为 null / 非数组时退化为空回答，不抛
        val root = json.parseToJsonElement(text).objOrNull()
            ?: return AssistantMessage("", emptyList())
        val blocks = root["content"]?.arrOrNull() ?: return AssistantMessage("", emptyList())

        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        blocks.forEach { el ->
            val obj = el.objOrNull() ?: return@forEach
            when (obj["type"]?.primOrNull()?.contentOrNull) {
                "text" -> obj["text"]?.primOrNull()?.contentOrNull?.let { content.append(it) }
                // 功能五（P1-9）：extended thinking 的思考块（thinking 字段，与正文 text 块分流）
                "thinking" -> obj["thinking"]?.primOrNull()?.contentOrNull?.let { reasoning.append(it) }
                "tool_use" -> {
                    val callId = obj["id"]?.primOrNull()?.contentOrNull ?: UUID.randomUUID().toString()
                    val name = obj["name"]?.primOrNull()?.contentOrNull ?: return@forEach
                    val input = obj["input"]?.objOrNull() ?: JsonObject(emptyMap())
                    toolCalls += ToolCall(callId, name, input)
                }
            }
        }
        val finish = FinishReason.fromClaude(root)
        FinishReason.logIfTruncated("claude", finish)
        return AssistantMessage(
            content.toString(),
            toolCalls,
            parseClaudeUsage(root),
            reasoning.toString().takeIf { it.isNotBlank() },
            finish
        )
    }

    /**
     * ChatMessage 列表 → Claude messages 数组。
     *
     * 连续多条 [ToolMessage]（AgentLoop 并行执行工具会产生）合并成**一条** user 消息，
     * 里面并列多个 tool_result block——Claude 协议要求，拆多条会 400。
     *
     * 注意：builder lambda 内不声明局部函数——局部函数拿不到外层 lambda 的隐式接收器
     * （`add` 会解析失败），所以合并逻辑直接写在 forEach 里。
     */
    private fun messagesJson(messages: List<ChatMessage>) = buildJsonArray {
        val pendingToolResults = mutableListOf<JsonObject>()
        messages.forEach { msg ->
            if (msg is ToolMessage) {
                pendingToolResults += toolResultBlock(msg)
            } else {
                if (pendingToolResults.isNotEmpty()) {
                    add(userToolResultsMessage(pendingToolResults.toList()))
                    pendingToolResults.clear()
                }
                add(toApiMessage(msg))
            }
        }
        if (pendingToolResults.isNotEmpty()) {
            add(userToolResultsMessage(pendingToolResults.toList()))
        }
    }

    /** 单条 tool_result block。 */
    private fun toolResultBlock(msg: ToolMessage): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", msg.id)
        put("content", msg.content)
        if (msg.isError) put("is_error", true)
    }

    /** 把多个 tool_result block 包成一条 user 消息（并行工具回传的合并形态）。 */
    private fun userToolResultsMessage(blocks: List<JsonObject>): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonArray("content") { blocks.forEach { add(it) } }
    }

    private fun toApiMessage(msg: ChatMessage): JsonObject = when (msg) {
        is SystemMessage -> buildJsonObject {
            // 正常不会走到这（system 已提为顶层字段）；防御性兜底
            put("role", "user")
            put("content", msg.content)
        }

        is UserMessage -> buildJsonObject {
            put("role", "user")
            if (msg.images.isEmpty()) {
                put("content", msg.content)
            } else {
                putJsonArray("content") {
                    if (msg.content.isNotBlank()) {
                        addJsonObject { put("type", "text"); put("text", msg.content) }
                    }
                    msg.images.forEach { url ->
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                val (mime, data) = ImageUris.splitDataUri(url) ?: ("image/png" to url)
                                put("media_type", mime)
                                put("data", data)
                            }
                        }
                    }
                }
            }
        }

        is AssistantMessage -> buildJsonObject {
            put("role", "assistant")
            putJsonArray("content") {
                if (msg.content.isNotBlank()) {
                    addJsonObject { put("type", "text"); put("text", msg.content) }
                }
                msg.toolCalls.forEach { call ->
                    addJsonObject {
                        put("type", "tool_use")
                        put("id", call.id)
                        put("name", call.name)
                        put("input", call.arguments)
                    }
                }
            }
        }

        is ToolMessage -> buildJsonObject {
            // 单条场景由 messagesJson 合并；此处防御性兜底
            put("role", "user")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", msg.id)
                    put("content", msg.content)
                    if (msg.isError) put("is_error", true)
                }
            }
        }
    }

    /**
     * 请求体。**整条链路里最省钱的一处**，改动前先读这段。
     *
     * 每轮请求都要重发「系统提示 + 工具定义」这两块——它们在多轮里逐字节不变，
     * 却按全价计费。Anthropic 的 prompt caching 能把命中部分打到 0.1x，
     * 但它**不会自动生效**：必须在内容块上显式打 `cache_control` 断点。
     *
     * 断点位置（官方上限 4 个，这里用 ≤2 个）：
     *  - 最后一个 tool：缓存整个 tools 块；
     *  - system 最后一块：缓存 tools + system（前缀顺序是 tools → system → messages）。
     *
     * 所以 system 从「一个字符串」变成「一个 text 块数组」——这是官方等价写法，
     * 也是打断点的唯一方式。中转站不认时由 [executeChat] 摘掉标记重发。
     */
    private fun bodyJson(
        resolved: List<ChatMessage>,
        tools: List<AgentTool>,
        stream: Boolean,
        cache: Boolean
    ): JsonObject = buildJsonObject {
        put("model", effectiveModel())
        put("max_tokens", maxTokens)
        if (stream) put("stream", true)

        val systemText = resolved.filterIsInstance<SystemMessage>()
            .joinToString("\n\n") { it.content }
        if (systemText.isNotBlank()) {
            if (cache) {
                putJsonArray("system") {
                    addJsonObject {
                        put("type", "text")
                        put("text", systemText)
                        putJsonObject(CACHE_CONTROL) { put("type", "ephemeral") }
                    }
                }
            } else {
                put("system", systemText)
            }
        }

        put("messages", messagesJson(resolved))

        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEachIndexed { i, t ->
                    val obj = toApiTool(t)
                    // 断点打在**最后一个**工具上：Anthropic 的语义是「缓存到此处为止」，
                    // 逐项打会白吃 4 个断点配额，还把缓存切成碎块，命中率反而下降。
                    if (cache && i == tools.lastIndex) {
                        add(
                            JsonObject(
                                obj + (CACHE_CONTROL to buildJsonObject { put("type", "ephemeral") })
                            )
                        )
                    } else {
                        add(obj)
                    }
                }
            }
            if (!stream) put("tool_choice", buildJsonObject { put("type", "auto") })
        }
        if (!stream) config.reasoningLevel?.let { put("temperature", 0.7) }
    }

    /** 本实例是否还愿意尝试提示缓存；被端点拒绝过一次就不再试，避免每轮白跑一趟 400。 */
    @Volatile
    private var promptCacheAllowed = true

    private fun promptCacheEnabled(): Boolean = promptCacheAllowed

    private fun disablePromptCache(reason: String) {
        if (!promptCacheAllowed) return
        promptCacheAllowed = false
        AgentLogger.w(
            "LLM",
            "该端点不接受提示缓存标记（$reason），已降级为不缓存；输入费用恢复全价"
        )
    }

    /**
     * 400 响应体像不像「不认缓存标记」。
     *
     * 只在 400 上判：401/403/429 里偶尔也会出现这些词，但那种错误重发也没用，
     * 按原错误抛出比「悄悄降级再失败」诚实得多。
     */
    private fun looksLikeCacheRejection(body: String): Boolean {
        val b = body.lowercase()
        return "cache_control" in b || "cache control" in b || "unexpected field" in b
    }

    private fun toApiTool(tool: AgentTool): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        // Anthropic 叫 input_schema，结构与非递归写法一致——统一走 ToolSchemaJson
        put("input_schema", ToolSchemaJson.objectSchema(tool.parameters))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Anthropic API 版本头（官方要求，缺了会 400）。 */
        private const val API_VERSION = "2023-06-01"

        /** 缓存断点字段名（出现两处，写成常量避免拼错——拼错不会报错，只是**悄悄不缓存**）。 */
        private const val CACHE_CONTROL = "cache_control"
    }
}
