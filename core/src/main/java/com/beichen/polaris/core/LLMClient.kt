package com.beichen.polaris.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.llm.ChatStreamEvent
import com.beichen.polaris.core.llm.parseOpenAiReasoning
import com.beichen.polaris.core.llm.parseOpenAiUsage
import com.beichen.polaris.core.llm.logPromptCacheUsage
import java.io.IOException
import com.beichen.polaris.core.llm.arrOrNull
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.llm.FinishReason
import com.beichen.polaris.core.llm.KeyPoolPolicy
import com.beichen.polaris.core.llm.KeyPoolRuntime
import com.beichen.polaris.core.net.EndpointPolicy

/**
 * 模型供应商配置：无状态，每次调用都携带。
 * 支持自定义请求头/体与推理强度，以接入任意 OpenAI 兼容端点
 * （含 Ollama / vLLM / OneAPI / 本地 llama.cpp）。
 */
data class LLMConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val customHeaders: Map<String, String> = emptyMap(),
    val customBody: Map<String, JsonElement> = emptyMap(),
    val reasoningLevel: String? = null,
    /**
     * 厂商 id（[com.beichen.polaris.core.llm.ProviderCatalog] 条目 id）。
     *
     * **由 [ProviderManager.create] 统一盖章，调用方不需要传**——`create` 的第一个参数
     * `name` 本来就是权威来源，从这里再传一遍等于给同一个事实两个入口。
     *
     * 默认空串的语义是「未经 create」：客户端此时只能退回协议名，属于兜底而非正常路径。
     */
    val providerId: String = "",
    /**
     * 单次输出上限。**0 = 不发送**（见 [com.beichen.polaris.core.llm.ProviderCatalog.LlmProvider.maxOutputTokens]）。
     *
     * 与 [providerId] 同一套来源：由 [ProviderManager.create] 从目录盖章。调用方显式
     * 给值则优先（留给将来「设置页自定义上限」，现在没有入口）。
     */
    val maxOutputTokens: Int = 0
)

/**
 * LLM 请求失败（携带 HTTP 状态码）。
 *
 * [retryable] 表示是否值得重试：429/5xx 属瞬时问题可重试，
 * 401/403/400 等鉴权/参数错误重试也无用，必须快速失败（避免用户白等 3 次重试）。
 */
class LlmHttpException(
    val code: Int,
    message: String,
    val serverBody: String = "",
    /**
     * 服务端 `Retry-After` 头解析出的毫秒数（0 = 没给/解析不了）。
     * hotfix10：429 限流时按它退避，别用固定短序列硬刚。
     */
    val retryAfterMs: Long = 0L
) : java.io.IOException(message) {
    val retryable: Boolean get() = code == 429 || code >= 500
}

/**
 * 「测试连接」最小探测的返回：状态码 + 响应体预览（已脱敏）。
 * 仅用于诊断连通性，不进入对话。
 */
data class PingResult(val code: Int, val bodyPreview: String)

/**
 * 模型信息：Provider 动态列出的可用模型（listModels）。
 * 本地 llama 走内置清单，OpenAI 兼容端点可走 /models，Claude/Gemini 可内置常用档位。
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val contextLength: Int? = null
)

/**
 * LLM 抽象：Agent 核心只依赖它。换供应商（OpenAI / Claude / Gemini / 本地 llama.cpp）
 * 只需新增一个实现，AgentLoop 一行都不用改。
 *
 * 协议差异（鉴权头、消息结构、工具回传形态）全部内聚在各实现类内，
 * 对外统一成同一套 ChatMessage / AssistantMessage / ToolCall。
 */
interface LLMClient {
    /**
     * **协议名**（"openai" / "claude" / "gemini" / "local-llama"）。
     *
     * ⚠️ 这不是厂商 id。原先这里写的是「供应商标识，ProviderManager 的键」，那句话
     * 直接把 R10 的窗口收敛带进了沟里：35 家里 28 家走 OpenAI 兼容，它们的 `id`
     * 全是 `"openai"`；而目录里那条 `id = "openai"` 是「OpenAI 兼容」**通用兜底条目**
     * （窗口 0，代表不了任何厂商），于是 `contextWindowOf(id)` 恒返回 0，
     * 收敛分支从未进入，且不会报「未知 id」——查不出异常。
     *
     * 按**厂商**分档的逻辑（上下文窗口 / 工具结果长度 / 工具暴露档位）一律用 [providerId]；
     * 这个字段只用于「判断走哪套协议」（如 `startsWith("local-")`）。
     */
    val id: String

    /**
     * 厂商 id（目录条目 id，如 `"deepseek"` / `"moonshot"` / 用户自填的 `"my-endpoint"`）。
     *
     * 默认取 [id]：原生协议的目录 id 恰好等于协议名（claude / gemini / local-llama），
     * 这条默认值对它们是对的。**OpenAI 兼容客户端必须覆盖**，否则 28 家共用一副面孔。
     *
     * 由 [ProviderManager.create] 从配置盖章传入，不接受调用方另行指定。
     */
    val providerId: String get() = id

    suspend fun chat(messages: List<ChatMessage>, tools: List<AgentTool>): AssistantMessage

    /** 流式输出（文本增量）。默认不支持；实现方按需覆盖。 */
    fun streamText(messages: List<ChatMessage>, tools: List<AgentTool>): Flow<String> =
        throw UnsupportedOperationException("当前供应商不支持流式输出")

    /**
     * T01 流式对话通道：Agent 主循环（RealAgentLoop）的唯一 LLM 入口。
     *
     * **默认实现 = 调 [chat] 得整条 AssistantMessage**：content 非空时先 emit 一条
     * [ChatStreamEvent.TextDelta]，再 emit [ChatStreamEvent.Completed]——四个现有
     * 客户端零改动即合规（行为与旧裸 chat() 调用等价：一次请求、整条 commit）。
     * 真 SSE 实现方按需覆盖（OpenAIClient 已覆盖；Claude/Gemini/LocalLlama 后续可接）。
     *
     * 消费约定（RealAgentLoop）：TextDelta 只进 onTextDelta 回调（UI 打字机），
     * 进历史的一定是 Completed 整条（含 ToolCall）。
     */
    fun streamChat(messages: List<ChatMessage>, tools: List<AgentTool>): Flow<ChatStreamEvent> = flow {
        val message = chat(messages, tools)
        if (message.content.isNotEmpty()) {
            emit(ChatStreamEvent.TextDelta(message.content))
        }
        emit(ChatStreamEvent.Completed(message))
    }

    /** 动态列出可用模型；默认空（实现方按需覆盖，如本地 llama 内置清单）。 */
    suspend fun listModels(): List<ModelInfo> = emptyList()

    /** 「测试连接」最小探测（各协议自行实现最小请求）。 */
    suspend fun testPing(): PingResult
}

/**
 * OpenAI 兼容实现（也适用于 vLLM、Ollama、OneAPI 等兼容端点）。
 * 传输用 OkHttp（挂起到 Dispatchers.IO），消息/工具按 OpenAI 协议手动拼 JsonObject。
 *
 * 流式（T01）：streamText（文本增量，公开 API 保留不许删）与 streamChat
 * （文本增量 + tool_calls 分片累积 → Completed 整条 commit，Agent 主循环消费）
 * 共享同一 SSE 读取逻辑（readSseDataObjects）。
 */

/**
 * 解析 `Retry-After` 响应头为毫秒（hotfix10）。
 *
 * 支持两种合法形态：延迟秒数（`Retry-After: 120`，最常见）与 HTTP 日期
 * （`Retry-After: Wed, 21 Oct 2015 07:28:00 GMT`，少见）。解析不出一律返回 0
 * （调用方按自己的 429 序列退避，不会被坏头值卡死）。
 * 纯函数、零 Android 依赖——QA harness 可直接断言。
 */
fun parseRetryAfterMs(raw: String?): Long {
    val v = raw?.trim().orEmpty()
    if (v.isEmpty()) return 0L
    v.toLongOrNull()?.let { if (it >= 0) return it * 1000L }
    val fmt = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
    return runCatching {
        val t = java.time.ZonedDateTime.parse(v, fmt).toInstant().toEpochMilli()
        (t - System.currentTimeMillis()).coerceAtLeast(0L)
    }.getOrDefault(0L)
}

/**
 * 从错误响应体里抠出服务端给的「人话原因」（2026-10-27 真机）。
 *
 * 429 的真实原因常常不在状态码里。真机 agnes 的响应体是：
 * ```
 * {"error":{"code":"","message":"请登录platform进行实名认证以获取更高rpm", ...}}
 * ```
 * 这是「去实名认证」就能解决的事。但原来只显示「请求过于频繁（429）」，
 * 用户看到的是「又限流了」，只能反复重试——重试一百次也还是 429。
 *
 * 兼容三种常见形态：`error.message`（OpenAI/Agnes）、`error` 本身是字符串
 * （部分网关）、`message` / `msg`（国内厂商）。都取不到返回 null，调用方退回
 * 通用文案，绝不把整段 body 塞给用户（可能夹带 request id / key 片段）。
 *
 * 截到 60 字：横幅只有一行，超出部分省略号。纯函数、零 Android 依赖。
 */
fun extractServerHint(body: String): String? {
    if (body.isBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(body).objOrNull() }.getOrNull() ?: return null
    val raw = obj["error"]?.objOrNull()?.get("message")
        ?: obj["error"]
        ?: obj["message"]
        ?: obj["msg"]
    val text = raw.primOrNull()?.contentOrNull?.trim()
    return text?.takeIf { it.isNotEmpty() }?.let { if (it.length > 60) it.take(60) + "…" else it }
}

/** 主 Key 在池中的固定槽位 id（备用槽位 id 由 KeyPoolStore 生成）。 */
private const val PRIMARY_SLOT_ID = "primary"

class OpenAIClient(
    private val config: LLMConfig,
    /**
     * 备用 Key 槽位（W-21）：非空时启用「限流自动切换」。
     * 主 Key（config.apiKey）永远排第一位，这里的槽位排在它后面。
     */
    private val backupKeys: List<KeyPoolPolicy.KeySlot> = emptyList(),
    /** Key 池运行时（冷却状态 + 轮询游标）；null 表示不做切换（保持单 Key 行为）。 */
    private val keyRuntime: KeyPoolRuntime? = null,
    /**
     * W-46：读超时 180s / 不限整通时长（默认 10s 会把「模型在思考」误判成网络故障）。
     * 共享实例：连接池与 Dispatcher 按实例持有，三家协议没必要各开一份。
     */
    private val http: OkHttpClient = LlmHttp.shared
) : LLMClient {

    override val id: String = "openai"

    /**
     * 厂商 id：由 [ProviderManager.create] 从配置盖章带进来。
     *
     * `ifBlank` 兜底成协议名，是为了「有人直接 `OpenAIClient(config)` 绕过工厂」这种情况
     * 不至于退化成空串去查目录（空串会让 `contextWindowOf` 返回 0，与本 bug 同形）。
     * 正常路径上它永远是真实厂商 id。
     */
    override val providerId: String = config.providerId.ifBlank { id }

    /**
     * 全部可用 Key 槽位：主 Key + 备用池。
     *
     * W-22 修：主 Key 为空（用户只配了备用 Key）时必须**剔除主槽位**——
     * 否则首请求带空 Bearer → 401 → 鉴权错误不触发切换 → 备用 Key 永远轮不到。
     */
    private fun allSlots(): List<KeyPoolPolicy.KeySlot> {
        val primaryKey = ConfigSanitizer.cleanApiKey(config.apiKey)
        val primary = if (primaryKey.isBlank()) {
            emptyList()
        } else {
            listOf(KeyPoolPolicy.KeySlot(id = PRIMARY_SLOT_ID, label = "主 Key", key = primaryKey))
        }
        return primary + backupKeys.filter { it.key.isNotBlank() }
    }

    /** 解析 `Retry-After`：既支持「秒」也支持 HTTP 日期，解析不了返回 0（走指数退避）。 */
    private fun parseRetryAfterMs(header: String?): Long {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return 0L
        raw.toLongOrNull()?.let { return (it * 1000L).coerceAtLeast(0) }
        return runCatching {
            val date = java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            (date.toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
        }.getOrDefault(0L)
    }

    /**
     * 对话请求入口（W-21：多 Key 轮询 + 限流自动切换）。
     *
     * 单 Key（未配备用池）时行为与改造前**完全一致**——不走切换分支，零回归面。
     *
     * 多 Key 时：主 Key 先试；失败且判定为「限流/额度类」（[KeyPoolPolicy.worthFailover]）
     * 就把该 Key 置入冷却并换下一把，**最多试遍所有槽位**。
     * 401/403 等鉴权错误**不换 Key**（换一把也是同样的错，快速失败更诚实）。
     * 全部冷却中 → 抛 429 并明确告知最早多久后恢复（不给用户一句干巴巴的报错）。
     */
    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): AssistantMessage = withContext(Dispatchers.IO) {
        val slots = allSlots()
        val runtime = keyRuntime
        // 未启用 Key 池（或没配任何 Key）：原路径。空池时用空 Key 走原请求，
        // 让服务端返回 401，与改造前的报错行为一致（不静默、也不伪造成功）。
        if (runtime == null || slots.size <= 1) {
            val slot = slots.firstOrNull()
                ?: KeyPoolPolicy.KeySlot(id = PRIMARY_SLOT_ID, label = "主 Key", key = "")
            return@withContext callOnce(messages, tools, slot)
        }

        val tried = LinkedHashSet<Int>()
        var lastError: LlmHttpException? = null
        while (tried.size < slots.size) {
            val idx = runtime.nextUsableIndex(slots) ?: break
            if (!tried.add(idx)) break
            val slot = slots[idx]
            try {
                val result = callOnce(messages, tools, slot)
                runtime.markSuccess(slot.id)
                return@withContext result
            } catch (e: LlmHttpException) {
                lastError = e
                if (!KeyPoolPolicy.worthFailover(e.code, e.serverBody)) throw e
                runtime.markFailure(
                    slotId = slot.id,
                    code = e.code,
                    error = e.message ?: "",
                    retryAfterSeconds = if (e.retryAfterMs > 0) e.retryAfterMs / 1000 else null
                )
                AgentLogger.w(
                    "LLM",
                    "Key「${slot.label}」被限流（${e.code}），切换下一把：${runtime.summary(slots)}"
                )
            }
            // IOException（网络/DNS/超时）不换 Key —— 换 Key 解决不了网络问题，直接抛出
        }

        val waitSec = KeyPoolPolicy.earliestRecoveryMs(
            slots, runtime.statesSnapshot(), System.currentTimeMillis()
        ) / 1000
        val detail = if (waitSec > 0) "最早 ${waitSec} 秒后恢复" else "请检查额度或稍后重试"
        throw LlmHttpException(
            code = lastError?.code ?: 429,
            message = "所有 ${slots.size} 把 Key 都不可用（${runtime.summary(slots)}），$detail",
            serverBody = lastError?.serverBody ?: ""
        )
    }

    /**
     * 单次请求（不做任何切换）：用指定槽位的 Key 真发一次。
     * 成功/失败的切换决策全部在 [chat] 里，保持这里单一职责。
     */
    private suspend fun callOnce(
        messages: List<ChatMessage>,
        tools: List<AgentTool>,
        slot: KeyPoolPolicy.KeySlot
    ): AssistantMessage {
        requireUsableEndpoint()
        val requestJson = buildJsonObject {
            put("model", effectiveModel())
            putJsonArray("messages") { messages.forEach { add(toApiMessage(it)) } }
            if (tools.isNotEmpty()) {
                put("tool_choice", "auto")
                putJsonArray("tools") { tools.forEach { add(toApiTool(it)) } }
            }
            config.reasoningLevel?.let { put("reasoning_effort", it) }
            // 【R12】输出上限：目录声明了才发（0 = 交给服务端默认）。
            // 位置同下条 temperature——必须写在 customBody 之前，否则用户在
            // 「自定义参数」里填的 max_tokens 会被静默覆盖。
            if (config.maxOutputTokens > 0) put("max_tokens", config.maxOutputTokens)
            // 【W-38 修复】默认温度必须写在 customBody **之前**。
            // 原顺序是先应用用户自定义体、再硬写 0.7，导致用户在「自定义参数」里填的
            // temperature 被静默覆盖——功能在、但不生效，属于最伤信任的那类 bug。
            put("temperature", 0.7)
            config.customBody.forEach { (k, v) -> put(k, v) }
        }

        val request = buildRequest(requestJson, slot.key)

        // L8 日志：请求前记录（只记模型/消息数/工具数，绝不记 apiKey）
        AgentLogger.llmRequest(effectiveModel(), messages.size, tools.size)
        // 记录实际端点/模型/Key 指纹，让 401 能在日志里自证（含不可见字符告警）
        AgentLogger.apiTrace(endpointUrl(), effectiveModel(), slot.key)
        val startedAt = System.currentTimeMillis()

        // 超时/DNS/连接失败都走 IOException，记下堆栈后原样抛出
        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            AgentLogger.e("LLM", "网络请求异常", e)
            throw e
        }

        return resp.use { r ->
            if (!r.isSuccessful) {
                // ⚠️ resp.body?.string() 只能读一次，必须先存变量再使用
                val errBody = runCatching { r.body?.string() }.getOrDefault("") ?: ""
                // 401/403 的响应体常回显 sk-xxx 或 token，落日志与抛出的异常文案都必须先脱敏
                val safeBody = AgentLogger.sanitize(errBody)
                AgentLogger.httpError(r.code, safeBody)
                // 带上状态码抛出：上层据此判断是否值得重试、以及给用户什么文案
                throw LlmHttpException(
                    r.code,
                    "LLM 请求失败 ${r.code}：${safeBody.take(300)}",
                    safeBody,
                    // W-21：429 优先听服务端的等待建议（秒或 HTTP 日期两种写法都解析）
                    parseRetryAfterMs(r.header("Retry-After"))
                )
            }
            // 空体不是「空回答」而是协议异常：旧写法对 body 直接加了非空断言，
            // 空体时抛 NPE，文案退化成「请求失败：null」，既看不出是哪种失败、
            // 也没法按状态码归类。
            val text = r.body?.string()
                ?: throw LlmHttpException(r.code, "LLM 响应体为空（HTTP ${r.code}）", "")
            AgentLogger.llmResponse(
                effectiveModel(),
                true,
                System.currentTimeMillis() - startedAt,
                "响应长度=${text.length}"
            )
            parseResponse(text)
        }
    }

    /**
     * 流式文本增量（公开 API）。Round1 Blocker 修复后用 [channelFlow]（先例：
     * LocalLlamaClient.streamText）——`flow {}` 构建器有上下文保持不变量，从
     * [readSseDataObjects] 的 withContext(IO) 嵌套协程里 emit 会抛 "Flow invariant
     * is violated"（JVM harness 实测记录）；channelFlow 的通道语义允许生产侧在 IO
     * 协程 send、收集方仍在自己的上下文（含 Main）收增量。
     */
    override fun streamText(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<String> = channelFlow {
        val outcome = readSseDataObjects(messages, tools) { obj ->
            val content = sseDeltaContent(obj)
            if (content != null) send(content)
        }
        // hotfix35：供应商忽略 stream:true 返回整段 JSON → 非流式解析降级成一次性输出
        if (outcome is SseReadOutcome.PlainBody) {
            runCatching { parseResponse(outcome.raw.trim()) }.getOrNull()?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { send(it) }
        }
    }

    /**
     * T01：流式对话（真 SSE 覆盖，替代默认的 chat()+单条 Completed）。
     *
     * 发射文本增量（打字机），并按 index 累积 tool_calls 分片——OpenAI 流式协议里
     * tool_calls 跨 chunk 拆分下发（首片带 id + function.name，后续片只带
     * function.arguments 片段，多个调用按 index 归组，见
     * [OpenAiToolCallAccumulator]）。流结束后 emit 一条 [ChatStreamEvent.Completed]
     * 整条 commit（含 ToolCall）——RealAgentLoop「整条 Assistant commit」三段式
     * 在流式下同样成立。
     *
     * 调度（Round1 Blocker 修复）：[channelFlow] + [readSseDataObjects] 内
     * withContext(IO)——网络段绝不跑在收集方调度器（含 Android Main）上，增量经
     * 通道序列化回传，收集方仍在自己的上下文收 delta。
     */
    override fun streamChat(
        messages: List<ChatMessage>,
        tools: List<AgentTool>
    ): Flow<ChatStreamEvent> = channelFlow {
        val accumulator = OpenAiToolCallAccumulator()
        val text = StringBuilder()
        // 功能五（P1-9）：推理过程（思维链）累积——DeepSeek-R1/QwQ 等经 delta.reasoning_content 下发
        val reasoning = StringBuilder()
        // 功能五（P1-5）：流末 chunk（choices 为空、仅带 usage）单独到达，捕获最后一份非空值
        var usage: com.beichen.polaris.core.TokenUsage? = null
        // 输出为什么结束：该字段只在**最后**一个非 usage chunk 上出现，前面全是 null，
        // 所以「见到非空就覆盖」。之前整条链路没读它 → 截断在 App 里不可见。
        var finishReason: String? = null
        val outcome = readSseDataObjects(messages, tools) { obj ->
            val delta = sseDeltaObject(obj)
            if (delta != null) {
                delta["content"]?.primOrNull()?.contentOrNull?.let {
                    text.append(it)
                    send(ChatStreamEvent.TextDelta(it))
                }
                delta["tool_calls"]?.arrOrNull()?.let { accumulator.absorb(it) }
                // 功能五（P1-9）：思考增量与正文分流（UI 折叠展示，不进正文打字机）
                parseOpenAiReasoning(delta)?.let {
                    reasoning.append(it)
                    send(ChatStreamEvent.ReasoningDelta(it))
                }
            }
            parseOpenAiUsage(obj)?.let { usage = it }
            FinishReason.fromOpenAi(obj)?.let { finishReason = it }
        }
        // hotfix35：供应商忽略 stream:true（或中转不带 SSE）时返回的是整段 JSON——
        // 旧实现一条 data: 都没匹配上，最终产出空回答（真机「不逐字/直接一句话/
        // 有时干脆空白」的另一种根因）。这里退化为非流式解析，一次性把整条回答
        // 作为增量发给 UI，行为与「一次性输出」的供应商一致。
        if (outcome is SseReadOutcome.PlainBody) {
            // hotfix36：降级要留痕——用户「不逐字/一块出」的疑问可直接在日志页对上号
            AgentLogger.i(
                "Chat",
                "供应商未按 SSE 返回（stream:true 被忽略），已降级为整段响应输出"
            )
            val parsed = runCatching { parseResponse(outcome.raw.trim()) }.getOrNull()
            if (parsed != null && (parsed.content.isNotBlank() || parsed.toolCalls.isNotEmpty())) {
                if (parsed.content.isNotBlank()) send(ChatStreamEvent.TextDelta(parsed.content))
                parsed.reasoning?.let { send(ChatStreamEvent.ReasoningDelta(it)) }
                send(ChatStreamEvent.Completed(parsed))
                return@channelFlow
            }
        }
        // OpenAI 兼容端点的自动缓存命中量在 prompt_tokens 内部（子集），分母直接用 prompt
        logPromptCacheUsage(usage, cachedInPrompt = true)
        FinishReason.logIfTruncated("streamChat", finishReason)
        send(
            ChatStreamEvent.Completed(
                AssistantMessage(
                    text.toString(),
                    accumulator.build(),
                    usage,
                    reasoning.toString().takeIf { it.isNotBlank() },
                    finishReason
                )
            )
        )
    }

    /**
     * 共享 SSE 读取（[streamText] / [streamChat] 复用，T01 重构抽出）：
     * 建流式请求 -> 执行 -> 逐行解析 `data:` 载荷 -> 每个 JSON 对象回调 [onData]；
     * `[DONE]` 哨兵结束读取。请求构造 / 端点指纹日志 / 失败抛错只此一份，两条流式
     * 通道行为一致（回归面：streamText 公开 API 与语义不变）。
     *
     * **调度契约（Round1 Blocker 修复）**：网络段（`execute()` + 逐行 `readUtf8Line()`）
     * 强制 `withContext(Dispatchers.IO)`——阻塞网络 IO 绝不跑在调用方调度器上，任何
     * 上下文（含 Android Main）都不会触发 `NetworkOnMainThreadException`。[onData]
     * 内的 `send` 在 IO 协程执行是安全的：两个流式通道用 [channelFlow]（先例：
     * LocalLlamaClient.streamText），通道线程安全、序列化回传给收集方，收集方仍在
     * 自己的上下文（如 Main）收 delta。
     *
     * 实现注记（为什么必须 channelFlow 而不是 flow{}）：`flow {}` 构建器有上下文
     * 保持不变量（SafeCollector 校验），从本方法的 withContext(IO) 嵌套协程里
     * `emit` 会抛 "Flow invariant is violated"——JVM harness Round1 区块有实测
     * 失败记录；channelFlow 的通道语义才允许「生产侧 IO 协程、收集方原地上下文」。
     *
     * 非网络段（构造请求体 / 指纹日志）不切换调度。
     */
    /**
     * SSE 读取结果（hotfix35 兼容加固）：
     *  - [Sse]：正常——至少见到一条 `data:` 行，已按 SSE 全部消费；
     *  - [PlainBody]：**未见任何 `data:` 行**——供应商忽略 `stream: true`（或中转
     *    剥了 SSE 外壳），body 是整段 JSON；带回原始文本供调用方走非流式解析降级。
     */
    private sealed interface SseReadOutcome {
        object Sse : SseReadOutcome
        data class PlainBody(val raw: String) : SseReadOutcome
    }

    /** 非 SSE 降级收集上限：防超大 body 占内存（正常回答远小于此）。 */
    private val PLAIN_BODY_MAX = 2 * 1024 * 1024

    private suspend fun readSseDataObjects(
        messages: List<ChatMessage>,
        tools: List<AgentTool>,
        onData: suspend (JsonObject) -> Unit
    ): SseReadOutcome {
        requireUsableEndpoint()
        val requestJson = buildJsonObject {
            put("model", effectiveModel())
            put("stream", true)
            putJsonArray("messages") { messages.forEach { add(toApiMessage(it)) } }
            if (tools.isNotEmpty()) {
                put("tool_choice", "auto")
                putJsonArray("tools") { tools.forEach { add(toApiTool(it)) } }
            }
            // 功能五（P1-5）：要服务端在流末单独发一个带 usage 的 chunk（OpenAI 官方 2023 起支持，
            // vLLM / Ollama / DeepSeek / one-api 系均支持）。放在 customBody 之前——
            // 用户在自定义体里显式配置 stream_options 时以用户为准。
            putJsonObject("stream_options") { put("include_usage", true) }
            // 【R12】输出上限，位置要求同非流式（见 callOnce 处的说明）
            if (config.maxOutputTokens > 0) put("max_tokens", config.maxOutputTokens)
            config.customBody.forEach { (k, v) -> put(k, v) }
        }
        val request = buildRequest(requestJson)
        // 流式请求同样记录端点/指纹，保持诊断能力一致
        AgentLogger.apiTrace(endpointUrl(), effectiveModel(), effectiveApiKey())
        // Round1 Blocker 修复：网络段强制 IO 调度（streamText/streamChat 共用本方法，
        // 一处修两通道）。onData 里的 send 经 channelFlow 通道序列化回收集方，上下文安全。
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // hotfix10：流式通道原来抛裸 IOException("LLM 流式请求失败 429")，
                    // 丢了 HTTP 结构与 Retry-After——上层认不出 429、退避只能用通用
                    // 3s/10s 短序列硬刚限流（真机 19:05 连续 3 次 429 后运行直接失败）。
                    // 改抛结构化 LlmHttpException：isRetryable/retryReason/429 专用
                    // 退避序列全部能认出来。
                    val errBody = runCatching { resp.body?.string() }.getOrDefault("") ?: ""
                    AgentLogger.httpError(resp.code, AgentLogger.sanitize(errBody))
                    throw LlmHttpException(
                        code = resp.code,
                        message = "LLM 流式请求失败 ${resp.code}",
                        serverBody = AgentLogger.sanitize(errBody).take(200),
                        retryAfterMs = parseRetryAfterMs(resp.header("Retry-After"))
                    )
                }
                // 同上：流式请求拿到 2xx 却没有 body，是一次协议级失败，不是「零增量」。
                val source = resp.body?.source()
                    ?: throw LlmHttpException(resp.code, "LLM 流式响应体为空（HTTP ${resp.code}）", "")
                // hotfix35：一边按 SSE 消费，一边留一份原文——若整段都没出现 data:
                // 前缀，说明供应商忽略 stream:true，原文即非流式响应体
                var sawData = false
                val rawFallback = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: continue
                    if (!line.startsWith("data:")) {
                        if (rawFallback.length < PLAIN_BODY_MAX) {
                            rawFallback.append(line).append('\n')
                        }
                        continue
                    }
                    sawData = true
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val obj = runCatching { json.parseToJsonElement(data).objOrNull() }.getOrNull() ?: continue
                    onData(obj)
                }
                return@use if (sawData) SseReadOutcome.Sse else SseReadOutcome.PlainBody(rawFallback.toString())
            }
        }
    }

    /** 从一个 SSE data 对象里取 choices[0].delta（纯函数；无 choices/delta 时 null）。 */
    private fun sseDeltaObject(obj: JsonObject): JsonObject? =
        obj["choices"]?.arrOrNull()?.firstOrNull()?.objOrNull()?.get("delta")?.objOrNull()

    /** 从 SSE data 对象里取 choices[0].delta.content 文本（纯函数；缺失时 null）。 */
    private fun sseDeltaContent(obj: JsonObject): String? =
        sseDeltaObject(obj)?.get("content")?.primOrNull()?.contentOrNull

    // —— 防御性清洗：统一在此处把配置三元组洗一遍，供 buildRequest / apiTrace / testPing 复用 ——

    /** 规范化后的 Base URL（去空白、去尾斜杠、剥掉误填的 /chat/completions 后缀）。 */
    private fun effectiveBaseUrl(): String = ConfigSanitizer.normalizeBaseUrl(config.baseUrl)

    /** 清洗后的 API Key（剔除一切非可打印 ASCII，含 NBSP/零宽/BOM）。 */
    private fun effectiveApiKey(): String = ConfigSanitizer.cleanApiKey(config.apiKey)

    /** 清洗后的模型名。 */
    private fun effectiveModel(): String = ConfigSanitizer.cleanModel(config.model)

    /** 实际拼出的请求端点（Base URL + /chat/completions）。 */
    private fun endpointUrl(): String = "${effectiveBaseUrl().trimEnd('/')}/chat/completions"

    /**
     * W-43：端点没配就**立刻**报清楚，别让 OkHttp 抛「Expected URL scheme…」再去重试。
     * 真机日志：切过模型后 baseUrl 为空，连续两次 3s/10s 退避，用户只看到「调用失败」，
     * 完全不知道是没填地址。这类配置错误重试一百次也不会好。
     *
     * 2026-11-09（审计 F6）：这里顺带挡「公网明文 + 带凭据」。判据在
     * [EndpointPolicy]（纯函数，`run-endpoint-policy-verification.sh` 有 16 条断言），
     * 本方法只负责把它变成用户看得懂的 400——**不重试**，因为这不是网络问题，
     * 重试三次只会把同一串 Key 明文再发三次。
     *
     * 为什么不在用户保存配置时就拦：Base URL 允许先填后改，且「测试连接」按钮与真正
     * 发请求走的是同一条判定，早拦会多出一份可能与这里漂移的规则。
     */
    private fun requireUsableEndpoint() {
        val url = endpointUrl()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw LlmHttpException(
                code = 400,
                message = "模型端点未配置（当前值「$url」）。请到「设置 → 模型」选择供应商并填写 Base URL。"
            )
        }
        // 自定义头同样可能夹带凭据（X-Api-Key / HTTP-Referer 等中转站要求），一并计入
        val carriesCredential = effectiveApiKey().isNotBlank() || config.customHeaders.isNotEmpty()
        EndpointPolicy.refusalFor(url, carriesCredential)?.let { reason ->
            AgentLogger.e("LLM", "拒绝向公网明文端点发送凭据：${EndpointPolicy.hostOf(url)}", null)
            throw LlmHttpException(code = 400, message = reason)
        }
    }

    private fun buildRequest(bodyJson: JsonObject, apiKeyOverride: String? = null): Request {
        // 防御性双保险：即便调用方忘了清洗，也不会带着 \u00A0 等不可见字符发请求
        val baseUrl = ConfigSanitizer.normalizeBaseUrl(config.baseUrl)
        val apiKey = ConfigSanitizer.cleanApiKey(apiKeyOverride ?: config.apiKey)
        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        val builder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
        config.customHeaders.forEach { (k, v) -> builder.addHeader(k, v) }
        return builder.post(body).build()
    }

    /**
     * 最小连通性探测：发一条 [{role:user,content:"ping"}]、max_tokens=1、不带工具。
     * 用于「测试连接」按钮，不污染对话历史，也几乎不产生 token 消耗。
     *
     * @return [PingResult] 状态码与响应体预览（均已脱敏处理）。
     */
    override suspend fun testPing(): PingResult = withContext(Dispatchers.IO) {
        requireUsableEndpoint()
        val requestJson = buildJsonObject {
            put("model", effectiveModel())
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "ping")
                }
            }
            put("max_tokens", 1)
            put("temperature", 0.0)
        }
        val request = buildRequest(requestJson)
        // 记录实际端点/模型/Key 指纹，让测试也能在日志里自证
        AgentLogger.apiTrace(endpointUrl(), effectiveModel(), effectiveApiKey())
        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            AgentLogger.e("LLM", "测试连接网络异常", e)
            throw e
        }
        resp.use { r ->
            // 响应体可能回显 sk-xxx，必须先脱敏再截断
            val body = runCatching { r.body?.string() }.getOrDefault("") ?: ""
            val safe = AgentLogger.sanitize(body)
            PingResult(code = r.code, bodyPreview = safe.take(200))
        }
    }

    private fun parseResponse(text: String): AssistantMessage {
        // hotfix33：全链安全取值——非流式路径同样可能收到 `message: null` /
        // `choices: []` / 各类 null 字段（真机 DeepSeek 间歇性崩溃同源），
        // 任一环缺都退化为空回答，绝不抛异常打断整轮
        val root = json.parseToJsonElement(text).objOrNull()
            ?: return AssistantMessage("", emptyList(), null)
        val msg = root["choices"]?.arrOrNull()?.firstOrNull()?.objOrNull()
            ?.get("message")?.objOrNull()
            ?: return AssistantMessage("", emptyList(), parseOpenAiUsage(root))

        val content = msg["content"]?.primOrNull()?.contentOrNull ?: ""
        // hotfix35：非流式 / 流式降级路径同样提取思维链——DeepSeek reasoner、
        // GLM、QwQ 等在非流式响应的 message 里也带 reasoning_content / reasoning，
        // 之前只认流式 delta，降级后思考会整段丢掉。
        val reasoning = msg["reasoning_content"]?.primOrNull()?.contentOrNull
            ?: msg["reasoning"]?.primOrNull()?.contentOrNull

        val toolCalls = msg["tool_calls"]?.arrOrNull()?.mapNotNull { tc ->
            val obj = tc.objOrNull() ?: return@mapNotNull null
            val id = obj["id"]?.primOrNull()?.content ?: return@mapNotNull null
            val fn = obj["function"]?.objOrNull() ?: return@mapNotNull null
            val name = fn["name"]?.primOrNull()?.content ?: return@mapNotNull null
            val rawArgs = fn["arguments"]?.primOrNull()?.content ?: "{}"
            val args = runCatching {
                json.parseToJsonElement(rawArgs).objOrNull() ?: JsonObject(emptyMap())
            }.getOrElse { JsonObject(emptyMap()) }
            ToolCall(id, name, args)
        } ?: emptyList()

        val finish = FinishReason.fromOpenAi(root)
        FinishReason.logIfTruncated("chat", finish)
        return AssistantMessage(
            content,
            toolCalls,
            parseOpenAiUsage(root),
            reasoning?.takeIf { it.isNotBlank() },
            finish
        )
    }

    private fun toApiMessage(msg: ChatMessage): JsonObject = buildJsonObject {
        when (msg) {
            is SystemMessage -> { put("role", "system"); put("content", msg.content) }
            is UserMessage -> {
                put("role", "user")
                if (msg.images.isEmpty()) {
                    put("content", msg.content)
                } else {
                    // 多模态：content 改为 part 数组，文本 + image_url 交替
                    putJsonArray("content") {
                        if (msg.content.isNotBlank()) {
                            addJsonObject { put("type", "text"); put("text", msg.content) }
                        }
                        msg.images.forEach { url ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", url) }
                            }
                        }
                    }
                }
            }
            is AssistantMessage -> {
                put("role", "assistant")
                if (msg.content.isNotBlank()) put("content", msg.content)
                if (msg.toolCalls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        msg.toolCalls.forEach { call ->
                            addJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.arguments.toString())
                                }
                            }
                        }
                    }
                }
            }
            is ToolMessage -> {
                put("role", "tool")
                put("tool_call_id", msg.id)
                put("content", msg.content)
            }
        }
    }

    private fun toApiTool(tool: AgentTool): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", tool.name)
            put("description", tool.description)
            // schema 序列化全仓唯一实现在 ToolSchemaJson（递归写 items / 嵌套 properties）
            put("parameters", ToolSchemaJson.objectSchema(tool.parameters))
        }
    }

    companion object {
        /** 宽松 JSON 解析（容忍未知键与非常严格式）；internal：同文件的 SSE 累积器复用。 */
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/**
 * OpenAI SSE tool_calls 增量累积器（T01，纯数据 + 纯函数，harness 可直接断言）。
 *
 * OpenAI 流式协议里 tool_calls 按 index 归组、跨 chunk 分片下发：
 * 首片带 id + function.name，后续片只带 function.arguments 的片段（本身也可能
 * 再分多片）；id/name 只在首片出现，后续片不重复。本类把分片拼回整条
 * [ToolCall]：absorb 吸收分片（只追加、不重排），build 产出整表——
 * 顺序 = 首次出现顺序（LinkedHashMap 保序，即模型意图顺序）。
 *
 * 容错（与 [OpenAIClient.parseResponse] 的非流式路径一致）：
 *  - arguments 末尾整体解析为 JsonObject，解析失败（流被截断/分片损坏）退化为空对象；
 *  - 无名调用（从未收到 function.name）无法执行，build 时丢弃；
 *  - id 全程缺失时按 index 生成确定性回退 id（`call_<index>`），不产出随机量。
 */
internal class OpenAiToolCallAccumulator {

    /** 单个 index 上的增量状态（id/name 取首片值，arguments 追加拼接）。 */
    private class Delta(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    /** index -> 增量；LinkedHashMap 保序（build() 结果顺序 = 首次出现顺序）。 */
    private val byIndex = LinkedHashMap<Int, Delta>()

    /**
     * 吸收一个 chunk 的 tool_calls 数组（纯函数式更新：只追加，不重排）。
     *
     * @param toolCalls SSE `choices[0].delta.tool_calls` 数组；元素缺 index 时
     *   按数组位置兜底（兼容不守规矩的兼容端点）。
     */
    fun absorb(toolCalls: JsonArray) {
        toolCalls.forEachIndexed { position, element ->
            // hotfix33：元素可能是 JsonNull（个别端点会下发 null 占位），软转换跳过
            val obj = element.objOrNull() ?: return@forEachIndexed
            val index = obj["index"]?.primOrNull()?.intOrNull ?: position
            val delta = byIndex.getOrPut(index) { Delta() }
            obj["id"]?.primOrNull()?.contentOrNull?.let { if (it.isNotBlank()) delta.id = it }
            val fn = obj["function"]?.objOrNull()
            fn?.get("name")?.primOrNull()?.contentOrNull?.let { if (it.isNotBlank()) delta.name = it }
            fn?.get("arguments")?.primOrNull()?.contentOrNull?.let { delta.arguments.append(it) }
        }
    }

    /**
     * 产出整条工具调用列表（纯函数）：过滤无名调用，arguments 整体解析
     * （失败 / 解析出 null 都退化空对象），id 缺失按 index 回退 `call_<index>`。
     */
    fun build(): List<ToolCall> = byIndex.entries.mapNotNull { (index, delta) ->
        if (delta.name.isBlank()) return@mapNotNull null
        val args = runCatching {
            OpenAIClient.json.parseToJsonElement(delta.arguments.toString()).objOrNull()
                ?: JsonObject(emptyMap())
        }.getOrElse { JsonObject(emptyMap()) }
        ToolCall(
            id = delta.id.ifBlank { "call_$index" },
            name = delta.name,
            arguments = args
        )
    }
}

/**
 * 供应商注册表（RikkaHub ProviderManager 思路，净室重写）。
 * 通过 [create] 按名称构造 LLMClient；新增供应商只需 registerProvider。
 *
 * hotfix34 全量适配：注册表只注册**协议风格**（openai / claude / gemini / local-llama），
 * 具体厂商（deepseek / moonshot / zhipu / qwen / openrouter / ollama …共 30+ 家）
 * 由 [com.beichen.polaris.core.llm.ProviderCatalog] 映射到风格上——
 * [create] 遇到未直接注册的 id 时按 [ProviderCatalog.styleOf] 兜底，
 * 未收录的 id 默认 OpenAI 兼容。**新增供应商 = 加一条目录数据，零代码零发版**。
 */
class ProviderManager {
    private val providers = mutableMapOf<String, (LLMConfig) -> LLMClient>()

    /**
     * 备用 Key 池（W-21）：设置为非空即启用「限流自动切换」——
     * 主 Key 被限流后自动换下一把，冷却期内不再浪费请求。
     *
     * 只覆盖 OpenAI 兼容协议（deepseek / moonshot / qwen / openrouter / ollama 等
     * 绝大多数厂商都走这条）；Claude / Gemini 原生协议本批未接入，
     * 保持单 Key 行为（如实说明，不假装支持）。
     */
    @Volatile
    var backupKeys: List<KeyPoolPolicy.KeySlot> = emptyList()

    /** 冷却状态与轮询游标；null = 不启用切换。 */
    @Volatile
    var keyRuntime: KeyPoolRuntime? = null

    init {
        registerProvider("openai") { OpenAIClient(it, backupKeys, keyRuntime) }
        registerProvider("claude") { com.beichen.polaris.core.llm.ClaudeClient(it) }
        registerProvider("gemini") { com.beichen.polaris.core.llm.GeminiClient(it) }
    }

    fun registerProvider(name: String, factory: (LLMConfig) -> LLMClient) {
        providers[name] = factory
    }

    /**
     * 按 providerId 造客户端。
     * 查找顺序：直接注册名 → 目录风格（厂商 id → 风格）→ OpenAI 兼容兜底。
     * 这样 deepseek/ollama/openrouter 等目录内厂商、以及任何自定义 id 都能拿到客户端。
     */
    fun create(name: String, config: LLMConfig): LLMClient {
        // 盖章：把「这次到底找的是哪家」写进配置，由客户端自己带着走。
        //
        // 为什么不要求调用方传：`name` 已经是权威来源（它决定走哪个 style），
        // 让调用方在 LLMConfig 里再传一遍，就是给同一个事实开第二个入口——
        // 而历史恰恰是这么丢的：配置层有 providerId，`buildMainClient` 也拿它去查了
        // style，偏偏构造 LLMConfig 时没写进来，于是客户端只知道自己"是 OpenAI 兼容"。
        val entry = com.beichen.polaris.core.llm.ProviderCatalog.byId(name)
        val stamped = config.copy(
            providerId = name,
            // 用户显式给值优先；否则取目录声明；目录没声明就是 0（不发送）。
            maxOutputTokens = if (config.maxOutputTokens > 0) {
                config.maxOutputTokens
            } else {
                entry?.maxOutputTokens ?: 0
            }
        )
        val direct = providers[name]
        if (direct != null) return direct.invoke(stamped)
        val style = com.beichen.polaris.core.llm.ProviderCatalog.styleOf(name)
        val byStyle = providers[style]
            ?: providers[com.beichen.polaris.core.llm.ProviderCatalog.STYLE_OPENAI]
        return byStyle?.invoke(stamped)
            ?: throw IllegalArgumentException("未知供应商：$name（已注册：${providers.keys}）")
    }

    val names: Set<String> get() = providers.keys
}
