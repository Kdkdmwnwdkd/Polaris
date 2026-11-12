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
import kotlinx.coroutines.flow.flowOn
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
import com.beichen.polaris.core.net.BoundedBody

/**
 * Gemini generateContent API 适配（Provider id = "gemini"）。
 *
 * 与 OpenAIClient 的关键差异（全部内聚在本类内）：
 *  - 端点是 /v1beta/models/{model}:generateContent，流式加 ?alt=sse；
 *  - 鉴权头 x-goog-api-key；
 *  - role 名不同：assistant → "model"，工具结果 → user 消息里的 functionResponse part；
 *  - system 是顶层 systemInstruction；
 *  - functionCall **没有调用 id**（Claude/OpenAI 都有）——ToolCall.id 由本地合成，
 *    回传时靠 name 匹配，AgentLoop 不依赖 id 的唯一跨轮语义；
 *  - 图片只认 inlineData（base64），http URL 由 [ImageUris] 预下载转换。
 *
 * 流式（T01）：[LLMClient.streamChat] 暂用默认实现（chat() 整条 + 单条 Completed）；
 * 后续可接 SSE（?alt=sse 的 candidates[0].content.parts 增量 + functionCall 分片累积）。
 */
class GeminiClient(
    private val config: LLMConfig,
    /**
     * W-46：读超时 180s / 不限整通时长（默认 10s 会把「模型在思考」误判成网络故障）。
     * 共享实例：连接池与 Dispatcher 按实例持有，三家协议没必要各开一份。
     */
    private val http: OkHttpClient = LlmHttp.shared
) : LLMClient {

    override val id: String = "gemini"

    private val maxOutputTokens = 8192

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage = withContext(Dispatchers.IO) {
        val resolved = ImageUris.resolveImages(http, messages)

        val requestJson = buildJsonObject {
            val systemText = resolved.filterIsInstance<SystemMessage>()
                .joinToString("\n\n") { it.content }
            if (systemText.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject { put("text", systemText) }
                    }
                }
            }
            put("contents", contentsJson(resolved))
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.forEach { add(toApiTool(it)) }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.7)
                put("maxOutputTokens", maxOutputTokens)
            }
        }

        val request = buildRequest(requestJson, stream = false)
        AgentLogger.llmRequest(effectiveModel(), messages.size, tools.size)
        AgentLogger.apiTrace(endpointUrl(false), effectiveModel(), effectiveApiKey())
        val startedAt = System.currentTimeMillis()

        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            AgentLogger.e("LLM", "Gemini 网络请求异常", e)
            throw e
        }

        resp.use { r ->
            if (!r.isSuccessful) {
                val errBody = runCatching {
        // 审计 F44：只用于日志/预览，却把整份响应读进堆。64KB 够放下 300 字符的提示。
        r.body?.byteStream()?.let { BoundedBody.read(it, 64L * 1024).text }.orEmpty()
    }.getOrDefault("")
                val safeBody = AgentLogger.sanitize(errBody)
                AgentLogger.httpError(r.code, safeBody)
                throw LlmHttpException(r.code, "Gemini 请求失败 ${r.code}：${safeBody.take(300)}", safeBody)
            }
            // 审计 F38：同 F36。
            val slice = r.body?.byteStream()
                ?.let { BoundedBody.read(it, BoundedBody.MAX_READ_BYTES) }
                ?: throw LlmHttpException(r.code, "Gemini 响应体为空（HTTP ${r.code}）", "")
            if (slice.truncated) {
                throw LlmHttpException(
                    r.code,
                    "Gemini 响应超过 ${BoundedBody.MAX_READ_BYTES / 1024 / 1024} MB，已中止解析",
                    ""
                )
            }
            val text = slice.text
            AgentLogger.llmResponse(
                effectiveModel(), true,
                System.currentTimeMillis() - startedAt,
                "响应长度=${text.length}"
            )
            parseResponse(text)
        }
    }

    override fun streamText(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<String> = flow {
        val resolved = ImageUris.resolveImages(http, messages)
        val systemText = resolved.filterIsInstance<SystemMessage>()
            .joinToString("\n\n") { it.content }
        val requestJson = buildJsonObject {
            if (systemText.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", systemText) } }
                }
            }
            put("contents", contentsJson(resolved))
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") { tools.forEach { add(toApiTool(it)) } }
                    }
                }
            }
        }
        val request = buildRequest(requestJson, stream = true)
        AgentLogger.apiTrace(endpointUrl(true), effectiveModel(), effectiveApiKey())
        http.runCancellable(request) { resp ->
            if (!resp.isSuccessful) {
                val errBody = runCatching {
        // 审计 F44：只用于日志/预览，却把整份响应读进堆。64KB 够放下 300 字符的提示。
        resp.body?.byteStream()?.let { BoundedBody.read(it, 64L * 1024).text }.orEmpty()
    }.getOrDefault("")
                val safeBody = AgentLogger.sanitize(errBody)
                AgentLogger.httpError(resp.code, safeBody)
                // hotfix10：同 OpenAIClient——结构化 LlmHttpException（code + Retry-After）
                throw com.beichen.polaris.core.LlmHttpException(
                    code = resp.code,
                    message = "Gemini 流式请求失败 ${resp.code}: ${safeBody.take(200)}",
                    serverBody = safeBody.take(200),
                    retryAfterMs = com.beichen.polaris.core.parseRetryAfterMs(
                        resp.header("Retry-After")
                    )
                )
            }
            val source = resp.body?.source()
                ?: throw LlmHttpException(
                    resp.code, "Gemini 流式响应体为空（HTTP ${resp.code}）", ""
                )
            // hotfix35：兼容加固——中转忽略 alt=sse 直接回整段 JSON 时（无 data: 行），
            // 留原文并在末尾走非流式解析降级，避免产出空回答
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
                    val parts = obj["candidates"]?.arrOrNull()?.firstOrNull()
                        ?.objOrNull()?.get("content")?.objOrNull()?.get("parts")?.arrOrNull()
                    parts?.forEach { el ->
                        el.objOrNull()?.get("text")?.primOrNull()?.contentOrNull?.let { emit(it) }
                    }
                }
            }
            // hotfix35：整段无 data: 行 → 按非流式解析降级一次性输出
            if (!sawData) {
                // hotfix36：降级留痕（日志页可对上「不逐字」的现象）
                com.beichen.polaris.core.AgentLogger.i(
                    "Chat",
                    "Gemini 通道未按 SSE 返回（alt=sse 被忽略），已降级为整段响应输出"
                )
                runCatching { parseResponse(rawFallback.toString().trim()) }.getOrNull()
                    ?.content?.takeIf { it.isNotBlank() }?.let { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** hotfix35：非 SSE 降级收集上限（防超大 body 占内存）。 */
    private val PLAIN_BODY_MAX = 2 * 1024 * 1024

    // —— 防御性清洗：与 OpenAIClient 同一套 ConfigSanitizer ——

    private fun effectiveBaseUrl(): String =
        ConfigSanitizer.normalizeBaseUrl(config.baseUrl).trimEnd('/')

    private fun effectiveApiKey(): String = ConfigSanitizer.cleanApiKey(config.apiKey)

    private fun effectiveModel(): String = ConfigSanitizer.cleanModel(config.model)

    /**
     * 实际端点。路径形如 /v1beta/models/{model}:generateContent；
     * base 已带 /v1beta 时不重复拼（防御手滑）。
     */
    private fun endpointUrl(stream: Boolean): String {
        val base = effectiveBaseUrl().removeSuffix("/v1beta")
        val suffix = if (stream) ":streamGenerateContent?alt=sse" else ":generateContent"
        return "$base/v1beta/models/${effectiveModel()}$suffix"
    }

    private fun buildRequest(bodyJson: JsonObject, stream: Boolean): Request {
        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(endpointUrl(stream))
            .addHeader("x-goog-api-key", effectiveApiKey())
            .addHeader("Content-Type", "application/json")
            .apply { config.customHeaders.forEach { (k, v) -> addHeader(k, v) } }
            .post(body)
            .build()
    }

    /**
     * 最小连通性探测：一条 "ping"、maxOutputTokens=1。
     * 用于「测试连接」按钮，不污染对话历史，也几乎不产生 token 消耗。
     */
    override suspend fun testPing(): PingResult = withContext(Dispatchers.IO) {
        val requestJson = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", "ping") } }
                }
            }
            putJsonObject("generationConfig") { put("maxOutputTokens", 1) }
        }
        val request = buildRequest(requestJson, stream = false)
        AgentLogger.apiTrace(endpointUrl(false), effectiveModel(), effectiveApiKey())
        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            AgentLogger.e("LLM", "Gemini 测试连接网络异常", e)
            throw e
        }
        resp.use { r ->
            val body = runCatching {
        // 审计 F44：只用于日志/预览，却把整份响应读进堆。64KB 够放下 300 字符的提示。
        r.body?.byteStream()?.let { BoundedBody.read(it, 64L * 1024).text }.orEmpty()
    }.getOrDefault("")
            val safe = AgentLogger.sanitize(body)
            PingResult(code = r.code, bodyPreview = safe.take(200))
        }
    }

    private fun parseResponse(text: String): AssistantMessage {
        // hotfix33：全链安全取值——candidates 为空 / part 为 null 都安全退化，不抛
        val root = json.parseToJsonElement(text).objOrNull()
            ?: return AssistantMessage("", emptyList())
        val parts = root["candidates"]?.arrOrNull()?.firstOrNull()
            ?.objOrNull()?.get("content")?.objOrNull()?.get("parts")?.arrOrNull()
            ?: return AssistantMessage("", emptyList())

        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        parts.forEach { el ->
            val obj = el.objOrNull() ?: return@forEach
            // 功能五（P1-9）：thought: true 的 part 是思考（Gemini 2.5 thinking），
            // 与正式回答的 text part 分流——思考灌进正文会让下一轮历史带上自言自语
            if (parseGeminiThought(obj) != null) {
                obj["text"]?.primOrNull()?.contentOrNull?.let { reasoning.append(it) }
            } else {
                obj["text"]?.primOrNull()?.contentOrNull?.let { content.append(it) }
            }
            obj["functionCall"]?.objOrNull()?.let { fc ->
                val name = fc["name"]?.primOrNull()?.contentOrNull ?: return@let
                val args = fc["args"]?.objOrNull() ?: JsonObject(emptyMap())
                // Gemini 的 functionCall 没有 id：本地合成，回传靠 name 匹配
                toolCalls += ToolCall("gemini_${UUID.randomUUID().toString().take(8)}", name, args)
            }
        }
        val finish = FinishReason.fromGemini(root)
        FinishReason.logIfTruncated("gemini", finish)
        val message = AssistantMessage(
            content.toString(),
            toolCalls,
            parseGeminiUsage(root),
            reasoning.toString().takeIf { it.isNotBlank() },
            finish
        )
        // Gemini 的 cachedContentTokenCount 含在 promptTokenCount 里（子集），分母直接用 prompt
        logPromptCacheUsage(message.usage, cachedInPrompt = true)
        return message
    }

    /**
     * ChatMessage 列表 → Gemini contents 数组。
     * role 映射：user→user、assistant→model、tool→user（functionResponse part）。
     * 连续多条 [ToolMessage] 合并成一条 user 消息、并列多个 functionResponse part。
     *
     * 注意：builder lambda 内不声明局部函数（拿不到隐式接收器），合并逻辑写在 forEach 里。
     */
    private fun contentsJson(messages: List<ChatMessage>) = buildJsonArray {
        val pendingResponses = mutableListOf<JsonObject>()
        messages.forEach { msg ->
            if (msg is ToolMessage) {
                pendingResponses += functionResponsePart(msg)
            } else {
                if (pendingResponses.isNotEmpty()) {
                    add(userFunctionResponseMessage(pendingResponses.toList()))
                    pendingResponses.clear()
                }
                add(toApiContent(msg))
            }
        }
        if (pendingResponses.isNotEmpty()) {
            add(userFunctionResponseMessage(pendingResponses.toList()))
        }
    }

    /** 单条 functionResponse part。 */
    private fun functionResponsePart(msg: ToolMessage): JsonObject = buildJsonObject {
        putJsonObject("functionResponse") {
            put("name", msg.name)
            putJsonObject("response") { put("result", msg.content) }
        }
    }

    /** 把多个 functionResponse part 包成一条 user 消息。 */
    private fun userFunctionResponseMessage(parts: List<JsonObject>): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonArray("parts") { parts.forEach { add(it) } }
    }

    private fun toApiContent(msg: ChatMessage): JsonObject = when (msg) {
        is SystemMessage -> buildJsonObject {
            // 正常不会走到这（system 已提为 systemInstruction）；防御性兜底
            put("role", "user")
            putJsonArray("parts") { addJsonObject { put("text", msg.content) } }
        }

        is UserMessage -> buildJsonObject {
            put("role", "user")
            putJsonArray("parts") {
                if (msg.content.isNotBlank()) addJsonObject { put("text", msg.content) }
                msg.images.forEach { url ->
                    addJsonObject {
                        putJsonObject("inlineData") {
                            val (mime, data) = ImageUris.splitDataUri(url) ?: ("image/png" to url)
                            put("mimeType", mime)
                            put("data", data)
                        }
                    }
                }
            }
        }

        is AssistantMessage -> buildJsonObject {
            put("role", "model")
            putJsonArray("parts") {
                if (msg.content.isNotBlank()) addJsonObject { put("text", msg.content) }
                msg.toolCalls.forEach { call ->
                    addJsonObject {
                        putJsonObject("functionCall") {
                            put("name", call.name)
                            put("args", call.arguments)
                        }
                    }
                }
            }
        }

        is ToolMessage -> buildJsonObject {
            // 单条场景由 contentsJson 合并；此处防御性兜底
            put("role", "user")
            putJsonArray("parts") {
                addJsonObject {
                    putJsonObject("functionResponse") {
                        put("name", msg.name)
                        putJsonObject("response") { put("result", msg.content) }
                    }
                }
            }
        }
    }

    private fun toApiTool(tool: AgentTool): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        // Gemini 用 parameters（且对 schema 校验最严：array 无 items 会拒整个声明）
        put("parameters", ToolSchemaJson.objectSchema(tool.parameters))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
