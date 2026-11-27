package com.beichen.polaris.core.media.providers

import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.llm.runCancellable
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.llm.arrOrNull
import com.beichen.polaris.core.media.MediaException
import com.beichen.polaris.core.media.MediaKind
import com.beichen.polaris.core.media.MediaProvider
import com.beichen.polaris.core.media.MediaRequest
import com.beichen.polaris.core.media.MediaTaskHandle
import com.beichen.polaris.core.media.MediaTaskState
import com.beichen.polaris.core.media.Ratio
import com.beichen.polaris.core.net.EndpointPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import com.beichen.polaris.core.net.BoundedBody
import com.beichen.polaris.core.net.BodySlice

/**
 * OpenAI images API（DALL·E 3 / gpt-image-1）。
 *
 * 协议：同步返回，包成瞬时任务（调研 06 号文档「OpenAI images（同步返回，包成瞬时任务）」）。
 *   - 文生图：POST /images/generations，取 data[0].url（dall-e-3）或
 *     data[0].b64_json（gpt-image-1 默认，包成 data URI 交给下载器解码）。
 *   - 图生图：POST /images/edits（multipart，参考图走文件字节）。
 *
 * apiKey 为空时由 ConfigStore.loadMediaKey 回退 LLM Key——同一账号同一把钥匙。
 *
 * **但 baseUrl 不再与对话同源**（2026-11-27 真机）。原来它读 `ConfigStore.load().baseUrl`，
 * 于是用户把主对话换成 deepseek 之后，出图请求跟着打到
 * `api.deepseek.com/v1/images/generations`——那个路径不存在，回来的是 404，
 * 而 404 的样子（`{"message":"Not Found"}`）看起来像「中转站不支持下图」，
 * 把人往错误方向带。现在读 `ConfigStore.loadMediaBaseUrl("openai")`，
 * 留空回落官方端点。
 */
class OpenAIMediaProvider(
    private val apiKey: String,
    baseUrl: String = "https://api.openai.com/v1"
) : MediaProvider {

    /** 去掉尾斜杠：`https://x/v1/` + `/images/generations` 会拼出 `//`，部分网关直接 404。 */
    private val baseUrl = baseUrl.trim().trimEnd('/')

    override val id = "openai"
    override val displayName = "OpenAI 图片"
    override val kinds = setOf(MediaKind.TEXT_TO_IMAGE, MediaKind.IMAGE_TO_IMAGE)

    private val json = Json { ignoreUnknownKeys = true }
    private val http = LlmHttp.llm

    override suspend fun submit(request: MediaRequest): MediaTaskHandle = withContext(Dispatchers.IO) {
        requireKey()
        requireSafeEndpoint()
        if (request.kind == MediaKind.IMAGE_TO_IMAGE) {
            editImage(request)
        } else {
            generateImage(request)
        }
    }

    override suspend fun poll(taskId: String): MediaTaskState =
        throw MediaException("OpenAI 图片为同步接口，提交即终态，无需轮询")

    private suspend fun generateImage(request: MediaRequest): MediaTaskHandle {
        val body = buildJsonObject {
            put("model", request.extra["model"] ?: "gpt-image-1")
            put("prompt", request.prompt)
            put("size", openAiSize(request.ratio))
            put("n", request.extra["n"]?.toIntOrNull() ?: 1)
        }
        val resp = post("$baseUrl/images/generations", body)
        val data = resp["data"]?.arrOrNull()
            ?: throw MediaException("images/generations 响应缺 data")
        val urls = data.mapNotNull { el ->
            val o = el.objOrNull() ?: return@mapNotNull null
            o["url"]?.primOrNull()?.contentOrNull
                ?: o["b64_json"]?.primOrNull()?.contentOrNull?.let { "data:image/png;base64,$it" }
        }
        if (urls.isEmpty()) throw MediaException("images/generations 未返回图片")
        return MediaTaskHandle(null, MediaTaskState.Succeeded(urls))
    }

    private suspend fun editImage(request: MediaRequest): MediaTaskHandle {
        val ref = request.refImageUris.firstOrNull()
            ?: throw MediaException("图生图需要至少一张参考图")
        val imageBody = ref.toImageRequestBody()
            ?: throw MediaException("参考图解析失败（需要 http(s) URL 或 base64 data URI）")
        val form = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", request.extra["model"] ?: "gpt-image-1")
            .addFormDataPart("prompt", request.prompt)
            .addFormDataPart("size", openAiSize(request.ratio))
            .addFormDataPart("image", "reference.png", imageBody)
            .build()
        val httpReq = Request.Builder()
            .url("$baseUrl/images/edits")
            .header("Authorization", "Bearer $apiKey")
            .post(form)
            .build()
        return http.runCancellable(httpReq) { resp ->
            // 审计 F42：返回体可能是 b64_json（整图 base64），`body?.string()` 无上限。
            // 8MB 够装两三张 1024² PNG；连这个都超说明服务端在返回别的东西，明确失败。
            val slice = resp.body?.byteStream()
                ?.let { BoundedBody.read(it, BoundedBody.MAX_READ_BYTES) }
                ?: BodySlice.EMPTY
            val text = slice.text
            if (!resp.isSuccessful) {
                // 404 / 401 各给一句人话。真机上的 404 原文是
                // `{"error":{"message":"Not Found","type":"not_found_error"}}`——
                // 读起来像「中转站不支持下图」，实际是请求打到了对话端点（deepseek），
                // 那里根本没有 /images/generations。不把端点写进错误里，用户永远猜不到。
                val hint = when (resp.code) {
                    404 -> "（该端点不存在：当前 Base URL = $baseUrl，" +
                        "请确认它支持 images 接口；多数对话中转站只代理 /chat/completions）"
                    401, 403 -> "（Key 无效，或该 Key 无权调用 images 接口）"
                    else -> ""
                }
                throw MediaException("HTTP ${resp.code}$hint：${text.take(300)}")
            }
            if (slice.truncated) {
                throw MediaException("响应超过 ${BoundedBody.MAX_READ_BYTES / 1024 / 1024} MB，已中止解析")
            }
            val root = json.parseToJsonElement(text).objOrNull() ?: JsonObject(emptyMap())
            val data = root["data"]?.arrOrNull() ?: throw MediaException("images/edits 响应缺 data")
            val urls = data.mapNotNull { el ->
                val o = el.objOrNull() ?: return@mapNotNull null
                o["url"]?.primOrNull()?.contentOrNull
                    ?: o["b64_json"]?.primOrNull()?.contentOrNull?.let { "data:image/png;base64,$it" }
            }
            if (urls.isEmpty()) throw MediaException("images/edits 未返回图片")
            MediaTaskHandle(null, MediaTaskState.Succeeded(urls))
        }
    }

    /** 参考图 → multipart 字节体（data URI 解码 base64；http URL 先下载）。 */
    private suspend fun String.toImageRequestBody(): okhttp3.RequestBody? {
        if (startsWith("data:")) {
            val comma = indexOf(',')
            if (comma < 0) return null
            val bytes = android.util.Base64.decode(substring(comma + 1), android.util.Base64.DEFAULT)
            return bytes.toRequestBody("image/png".toMediaType())
        }
        if (startsWith("http")) {
            val req = Request.Builder().url(this).get().build()
            // 用户填的参考图链接可能指向一个不响应的服务器，图生图会一直挂着
            return http.runCancellable(req) { resp ->
                if (!resp.isSuccessful) return@runCancellable null
                // 审计 F43：参考图是用户填的链接，`body?.bytes()` 无上限，
                // 指向一个大文件就是 OOM（后面还要 base64 翻倍）。
                val ctype = resp.body?.contentType() ?: "image/png".toMediaType()
                resp.body?.byteStream()
                    ?.let { BoundedBody.readBytes(it, BoundedBody.MAX_READ_BYTES) }
                    ?.toRequestBody(ctype)
            }
        }
        return null
    }

    private suspend fun post(endpoint: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return http.runCancellable(request) { resp ->
            // 审计 F42：返回体可能是 b64_json（整图 base64），`body?.string()` 无上限。
            // 8MB 够装两三张 1024² PNG；连这个都超说明服务端在返回别的东西，明确失败。
            val slice = resp.body?.byteStream()
                ?.let { BoundedBody.read(it, BoundedBody.MAX_READ_BYTES) }
                ?: BodySlice.EMPTY
            val text = slice.text
            if (!resp.isSuccessful) throw MediaException("HTTP ${resp.code}: ${text.take(300)}")
            if (slice.truncated) {
                throw MediaException("响应超过 ${BoundedBody.MAX_READ_BYTES / 1024 / 1024} MB，已中止解析")
            }
            json.parseToJsonElement(text).objOrNull() ?: JsonObject(emptyMap())
        }
    }

    private fun requireKey() {
        if (apiKey.isBlank()) throw MediaException("未配置 OpenAI API Key")
    }

    /**
     * 审计 F6：用户填了公网明文地址时，媒体 Key 会以明文出网。
     * 判据与对话请求共用 [EndpointPolicy]，避免两套规则各自漂移。
     *
     * 注意 baseUrl 自 2026-11-27 起**独立于对话端点**（见类注释），
     * 但这条判据不能跟着松：正因为它现在可以由用户任意填写，
     * 才更需要在下单前拦一次明文地址。
     */
    private fun requireSafeEndpoint() {
        EndpointPolicy.refusalFor(baseUrl, apiKey.isNotBlank())?.let { throw MediaException(it) }
    }

    companion object {
        /** 官方端点。媒体 baseUrl 未配置时的回落值——**不再继承对话端点**。 */
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        /** Ratio → OpenAI size（x 分隔；CLASSIC 无官方尺寸，退 SQUARE）。 */
        fun openAiSize(ratio: Ratio): String = when (ratio) {
            Ratio.SQUARE, Ratio.CLASSIC -> "1024x1024"
            Ratio.WIDE -> "1792x1024"
            Ratio.TALL -> "1024x1792"
        }
    }
}
