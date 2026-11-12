package com.beichen.polaris.core

import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.net.BoundedBody
import com.beichen.polaris.core.net.BodySlice
import com.beichen.polaris.core.net.EndpointPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.beichen.polaris.core.llm.arrOrNull
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.llm.primOrNull

/**
 * 模型列表拉取器：模型配置页「选择模型」弹窗的数据源。
 *
 * 背景：用户填好 Base URL + API Key 后，应该能拉取该端点有哪些模型并搜索选择，
 * 而不是盲手填模型名（三家供应商的模型列表端点实测形态见下）。
 *
 * 三家端点差异（与 ClaudeClient 同思路：协议差异全部内聚在本类内，对外只暴露 fetch）：
 *  - OpenAI 兼容（含 vLLM / Ollama / OneAPI / DeepSeek 等一切兼容端点）：
 *    GET {baseUrl}/models，请求头 Authorization: Bearer {apiKey}，
 *    响应 {"data":[{"id":"..."}]}，取 data[].id；
 *  - Claude：GET {baseUrl}/models，请求头 x-api-key: {apiKey}
 *    + anthropic-version: 2023-06-01，响应同样取 data[].id；
 *  - Gemini：GET {baseUrl}/models?pageSize=100，请求头 x-goog-api-key: {apiKey}，
 *    响应 {"models":[{"name":"models/xxx","supportedGenerationMethods":[...]}]}，
 *    name 去掉 models/ 前缀，且 supportedGenerationMethods 含 generateContent 才纳入。
 *
 * local-llama 不做网络拉取（本地模型走「本地模型」页签的状态卡管理），由调用方短路。
 *
 * 安全约定：失败抛出的 IOException message 已脱敏、绝不含 apiKey——HTTP 错误只带状态码，
 * 响应体只进日志（且先过 AgentLogger.sanitize），不回灌给调用方。
 */
class ModelListFetcher(
    private val http: OkHttpClient = LlmHttp.short
) {

    /**
     * 拉取指定端点的可用模型 id 列表。全程 Dispatchers.IO，不阻塞主线程。
     *
     * @param baseUrl 输入框当前的 Base URL（原始值，内部先过 ConfigSanitizer.normalizeBaseUrl）。
     * @param apiKey 输入框当前的 API Key（原始值，内部先过 ConfigSanitizer.cleanApiKey）。
     * @param providerId "openai" / "claude" / "gemini"（local-llama 由调用方短路，不进来）。
     * @return 模型 id 列表；端点不支持 /models（data/models 数组缺失）时返回 emptyList()。
     * @throws IOException 网络失败 / HTTP 非 2xx / 响应体解析失败；message 已脱敏，绝不含 apiKey。
     */
    suspend fun fetch(baseUrl: String, apiKey: String, providerId: String): List<String> =
        withContext(Dispatchers.IO) {
            // 防御性清洗：与三家 Client 同一套 ConfigSanitizer，避免带着 \u00A0 等不可见字符发请求
            val base = ConfigSanitizer.normalizeBaseUrl(baseUrl).trimEnd('/')
            val key = ConfigSanitizer.cleanApiKey(apiKey)
            // 与真正发对话请求同一把尺子：公网明文 + 带 Key 一律不发（审计 F6）。
            // 这一步在「选择模型」弹窗里就会红，比等到用户发第一条消息更早暴露。
            EndpointPolicy.refusalFor(base, key.isNotBlank())?.let { reason ->
                throw IOException(reason)
            }
            val request = buildRequest(base, key, providerId)
            val resp = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                AgentLogger.e("ModelList", "网络请求异常", e)
                throw e
            }
            resp.use { r ->
                if (!r.isSuccessful) {
                    // 响应体可能回显 sk-xxx：只脱敏后进日志，异常 message 只带状态码，
                    // 绝不把响应体拼进 message（可能夹带信息）
                    val errBody = runCatching {
        // 审计 F44：只用于日志/预览，却把整份响应读进堆。64KB 够放下 300 字符的提示。
        r.body?.byteStream()?.let { BoundedBody.read(it, 64L * 1024).text }.orEmpty()
    }.getOrDefault("")
                    AgentLogger.httpError(r.code, AgentLogger.sanitize(errBody).take(200))
                    throw IOException("获取模型列表失败：HTTP ${r.code}")
                }
                // 审计 F34：修前 `body?.string()` 整份进堆。baseUrl 是用户自己填的，
                // 一个坏掉或恶意的兼容服务返回 500MB JSON，点「拉取模型」就是 OOM。
                // 截断的 JSON 解析不了，所以撞到上限直接报错——不能把半截数据交给 parser
                // 再让用户看到一句莫名其妙的「格式错误」。
                val slice = r.body?.byteStream()
                    ?.let { BoundedBody.read(it, BoundedBody.MAX_READ_BYTES) }
                    ?: BodySlice.EMPTY
                if (slice.truncated) {
                    throw IOException("模型列表响应超过 ${BoundedBody.MAX_READ_BYTES / 1024 / 1024} MB，已中止")
                }
                parseModelIds(slice.text, providerId)
            }
        }

    /** 按供应商拼请求：端点与鉴权头的差异都在这里分叉（内聚在本类，对齐 ClaudeClient 风格）。 */
    private fun buildRequest(baseUrl: String, apiKey: String, providerId: String): Request =
        when (providerId) {
            // Claude：默认端点 https://api.anthropic.com 不带版本段，此处按 ClaudeClient
            // .endpointUrl 同款防御性拼接补齐 /v1（用户已手填 /v1 时不重复拼）
            "claude" -> {
                val b = if (baseUrl.endsWith("/v1")) baseUrl else "$baseUrl/v1"
                Request.Builder()
                    .url("$b/models")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", ANTHROPIC_VERSION)
                    .get()
                    .build()
            }

            // Gemini：默认端点 https://generativelanguage.googleapis.com 不带版本段，
            // 此处按 GeminiClient.endpointUrl 同款防御性拼接补齐 /v1beta
            // （base 在 fetch 里已 trimEnd('/')，removeSuffix 后重拼幂等）
            "gemini" -> {
                val b = baseUrl.removeSuffix("/v1beta") + "/v1beta"
                Request.Builder()
                    .url("$b/models?pageSize=100")
                    .addHeader("x-goog-api-key", apiKey)
                    .get()
                    .build()
            }

            // OpenAI 兼容（默认分支）：默认端点 https://api.openai.com/v1 已自带 /v1，
            // 不补版本段——兼容端点（vLLM / Ollama / OneAPI 等）的约定就是自带版本段，
            // 强行兜底 /v1 反而会破坏规范上就是裸域名的自填端点（与 OpenAIClient 行为一致）
            else -> Request.Builder()
                .url("$baseUrl/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
        }

    /**
     * 解析响应体为模型 id 列表。
     * 单条解析失败跳过（mapNotNull 容错，不抛）；整个 body 解析失败才抛 IOException。
     */
    private fun parseModelIds(text: String, providerId: String): List<String> {
        val root = try {
            Json.parseToJsonElement(text).objOrNull()
                ?: throw IOException("获取模型列表失败：响应不是 JSON 对象")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            AgentLogger.e("ModelList", "响应解析异常", e)
            throw IOException("获取模型列表失败：响应解析异常")
        }
        return when (providerId) {
            // Gemini：models[].name 去掉 models/ 前缀；只收支持 generateContent 的条目，
            // name 缺省 / 方法不含 generateContent 的跳过
            "gemini" -> root["models"]?.arrOrNull()?.mapNotNull { el ->
                runCatching {
                    val obj = el.objOrNull() ?: return@runCatching null
                    val name = obj["name"]?.primOrNull()?.contentOrNull ?: return@runCatching null
                    val supportsGenerate = obj["supportedGenerationMethods"]?.arrOrNull()
                        ?.any { it.primOrNull()?.contentOrNull == "generateContent" } == true
                    if (name.isNotBlank() && supportsGenerate) {
                        name.removePrefix("models/")
                    } else {
                        null
                    }
                }.getOrNull()
            } ?: emptyList()

            // OpenAI / Claude：data[].id；id 缺省或为空的条目跳过
            else -> root["data"]?.arrOrNull()?.mapNotNull { el ->
                runCatching {
                    el.objOrNull()?.get("id")?.primOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
                }.getOrNull()
            } ?: emptyList()
        }
    }

    companion object {
        /** Anthropic API 版本头（官方要求，缺了会 400；与 ClaudeClient 保持一致）。 */
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
