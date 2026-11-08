package com.beichen.polaris.core.media.providers

import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.llm.arrOrNull
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.media.MediaException
import com.beichen.polaris.core.media.MediaKind
import com.beichen.polaris.core.media.MediaProvider
import com.beichen.polaris.core.media.MediaRequest
import com.beichen.polaris.core.media.MediaTaskHandle
import com.beichen.polaris.core.media.MediaTaskState
import com.beichen.polaris.core.media.Ratio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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
 * 火山方舟（Ark）即梦 / Seedream（调研 06 号文档「火山方舟」直译）。
 *
 * 协议两条腿：
 *   - 图片（同步）：POST /api/v3/images/generations（doubao-seedream 系），
 *     直接返回 data[0].url，包成瞬时任务。
 *   - 视频（异步两段式）：POST /api/v3/contents/generations/tasks 提交 →
 *     GET 同路径/{id} 轮询，完成在 content[].video_url.url。
 *
 * model 必须由用户在 extra 里给（方舟的模型是推理接入点 ep-xxx 或公开模型 id，
 * 没有放之四海皆准的默认值）：图片默认 doubao-seedream-4-0-250828，
 * 视频无默认，缺省直接报错好过猜错模型名。
 */
class VolcMediaProvider(private val apiKey: String) : MediaProvider {

    override val id = "volc"
    override val displayName = "火山即梦"
    override val kinds = setOf(MediaKind.TEXT_TO_IMAGE, MediaKind.TEXT_TO_VIDEO)

    private val json = Json { ignoreUnknownKeys = true }
    private val http = LlmHttp.llm

    override suspend fun submit(request: MediaRequest): MediaTaskHandle = withContext(Dispatchers.IO) {
        requireKey()
        if (request.kind == MediaKind.TEXT_TO_VIDEO) submitVideo(request) else submitImage(request)
    }

    override suspend fun poll(taskId: String): MediaTaskState = withContext(Dispatchers.IO) {
        requireKey()
        val resp = get("$TASKS_URL/$taskId")
        val status = resp["status"]?.primOrNull()?.contentOrNull
        when (status) {
            "succeeded" -> {
                val urls = mutableListOf<String>()
                resp["content"]?.arrOrNull()?.forEach { el ->
                    val o = el.objOrNull() ?: return@forEach
                    o["video_url"]?.objOrNull()?.get("url")?.primOrNull()?.contentOrNull?.let { urls += it }
                    o["image_url"]?.objOrNull()?.get("url")?.primOrNull()?.contentOrNull?.let { urls += it }
                    o["url"]?.primOrNull()?.contentOrNull?.let { urls += it }
                }
                if (urls.isEmpty()) throw MediaException("任务成功但响应无结果 URL")
                MediaTaskState.Succeeded(urls)
            }

            "failed", "cancelled" -> {
                val msg = resp["error"]?.objOrNull()?.get("message")?.primOrNull()?.contentOrNull
                    ?: "任务失败（$status）"
                MediaTaskState.Failed(msg)
            }

            else -> MediaTaskState.Running(null)
        }
    }

    private fun submitImage(request: MediaRequest): MediaTaskHandle {
        val body = buildJsonObject {
            put("model", request.extra["model"] ?: "doubao-seedream-4-0-250828")
            put("prompt", request.prompt)
            put("size", volcSize(request.ratio))
            put("n", request.extra["n"]?.toIntOrNull() ?: 1)
            put("response_format", "url")
        }
        val resp = post("$BASE_URL/images/generations", body)
        val data = resp["data"]?.arrOrNull() ?: throw MediaException("images/generations 响应缺 data")
        val urls = data.mapNotNull { it.objOrNull()?.get("url")?.primOrNull()?.contentOrNull }
        if (urls.isEmpty()) throw MediaException("images/generations 未返回图片")
        return MediaTaskHandle(null, MediaTaskState.Succeeded(urls))
    }

    private fun submitVideo(request: MediaRequest): MediaTaskHandle {
        val model = request.extra["model"]
            ?: throw MediaException("火山视频需在「高级参数」填写 model（接入点 id）")
        val body = buildJsonObject {
            put("model", model)
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", request.prompt)
                })
            })
        }
        val resp = post(TASKS_URL, body)
        val id = resp["id"]?.primOrNull()?.contentOrNull
            ?: throw MediaException("提交响应缺任务 id")
        return MediaTaskHandle(id, MediaTaskState.Running(null))
    }

    private fun requireKey() {
        if (apiKey.isBlank()) throw MediaException("未配置火山方舟 API Key")
    }

    private fun post(endpoint: String, body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw MediaException("HTTP ${resp.code}: ${text.take(300)}")
            return json.parseToJsonElement(text).objOrNull() ?: JsonObject(emptyMap())
        }
    }

    private fun get(url: String): kotlinx.serialization.json.JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw MediaException("HTTP ${resp.code}: ${text.take(300)}")
            return json.parseToJsonElement(text).objOrNull() ?: JsonObject(emptyMap())
        }
    }

    companion object {
        private const val BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
        private const val TASKS_URL = "$BASE_URL/contents/generations/tasks"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Ratio → Seedream size（像素值，>=4096 总像素上限内取常用档）。 */
        fun volcSize(ratio: Ratio): String = when (ratio) {
            Ratio.SQUARE -> "2048x2048"
            Ratio.WIDE -> "2560x1440"
            Ratio.TALL -> "1440x2560"
            Ratio.CLASSIC -> "2304x1728"
        }
    }
}
