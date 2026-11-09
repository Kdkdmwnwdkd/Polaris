package com.beichen.polaris.core.media.providers

import com.beichen.polaris.core.llm.LlmHttp
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

/**
 * OpenAI images API（DALL·E 3 / gpt-image-1）。
 *
 * 协议：同步返回，包成瞬时任务（调研 06 号文档「OpenAI images（同步返回，包成瞬时任务）」）。
 *   - 文生图：POST /images/generations，取 data[0].url（dall-e-3）或
 *     data[0].b64_json（gpt-image-1 默认，包成 data URI 交给下载器解码）。
 *   - 图生图：POST /images/edits（multipart，参考图走文件字节）。
 *
 * baseUrl / apiKey 与 LLM 配置同源（ConfigStore.load()），媒体 Key 为空时
 * 由 ConfigStore.loadMediaKey 回退 LLM Key——同一账号同一把钥匙。
 */
class OpenAIMediaProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1"
) : MediaProvider {

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

    private fun generateImage(request: MediaRequest): MediaTaskHandle {
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

    private fun editImage(request: MediaRequest): MediaTaskHandle {
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
        http.newCall(httpReq).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw MediaException("HTTP ${resp.code}: ${text.take(300)}")
            val root = json.parseToJsonElement(text).objOrNull() ?: JsonObject(emptyMap())
            val data = root["data"]?.arrOrNull() ?: throw MediaException("images/edits 响应缺 data")
            val urls = data.mapNotNull { el ->
                val o = el.objOrNull() ?: return@mapNotNull null
                o["url"]?.primOrNull()?.contentOrNull
                    ?: o["b64_json"]?.primOrNull()?.contentOrNull?.let { "data:image/png;base64,$it" }
            }
            if (urls.isEmpty()) throw MediaException("images/edits 未返回图片")
            return MediaTaskHandle(null, MediaTaskState.Succeeded(urls))
        }
    }

    /** 参考图 → multipart 字节体（data URI 解码 base64；http URL 先下载）。 */
    private fun String.toImageRequestBody(): okhttp3.RequestBody? {
        if (startsWith("data:")) {
            val comma = indexOf(',')
            if (comma < 0) return null
            val bytes = android.util.Base64.decode(substring(comma + 1), android.util.Base64.DEFAULT)
            return bytes.toRequestBody("image/png".toMediaType())
        }
        if (startsWith("http")) {
            val req = Request.Builder().url(this).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                return (resp.body?.bytes() ?: return null)
                    .toRequestBody((resp.body?.contentType() ?: "image/png".toMediaType()))
            }
        }
        return null
    }

    private fun post(endpoint: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw MediaException("HTTP ${resp.code}: ${text.take(300)}")
            return json.parseToJsonElement(text).objOrNull() ?: JsonObject(emptyMap())
        }
    }

    private fun requireKey() {
        if (apiKey.isBlank()) throw MediaException("未配置 OpenAI API Key")
    }

    /**
     * 审计 F6：这里的 baseUrl 与 LLM 配置同源（见 [MediaRegistry] 的构造），
     * 用户填了公网明文地址时，媒体 Key 也会以明文出网。判据与对话请求共用
     * [EndpointPolicy]，避免两套规则各自漂移。
     */
    private fun requireSafeEndpoint() {
        EndpointPolicy.refusalFor(baseUrl, apiKey.isNotBlank())?.let { throw MediaException(it) }
    }

    companion object {
        /** Ratio → OpenAI size（x 分隔；CLASSIC 无官方尺寸，退 SQUARE）。 */
        fun openAiSize(ratio: Ratio): String = when (ratio) {
            Ratio.SQUARE, Ratio.CLASSIC -> "1024x1024"
            Ratio.WIDE -> "1792x1024"
            Ratio.TALL -> "1024x1792"
        }
    }
}
