package com.beichen.polaris.core.media.providers

import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.llm.arrOrNull
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.AgentLogger
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
import com.beichen.polaris.core.net.BoundedBody
import com.beichen.polaris.core.net.BodySlice

/**
 * 阿里云 DashScope 通义万相（调研 06 号文档的国内首选：图/视频都有、文档全）。
 *
 * 协议：异步两段式（X-DashScope-Async: true 提交 → GET /api/v1/tasks/{id} 轮询）。
 *   - 文生图：services/aigc/text2image/image-synthesis（wanx2.1-t2i-turbo）
 *   - 文生视频：services/aigc/video-generation/video-synthesis（wanx2.1-t2v-turbo）
 *
 * 图生图不走本 Provider：通义万相的图像编辑是另一套端点（multipart 差异大），
 * 由 OpenAI images/edit 承担 IMAGE_TO_IMAGE，避免在一个类里捏造不确定的 API 形状。
 */
class DashScopeMediaProvider(private val apiKey: String) : MediaProvider {

    override val id = "dashscope"
    override val displayName = "通义万相"
    override val kinds = setOf(MediaKind.TEXT_TO_IMAGE, MediaKind.TEXT_TO_VIDEO)

    private val json = Json { ignoreUnknownKeys = true }
    private val http = LlmHttp.llm

    override suspend fun submit(request: MediaRequest): MediaTaskHandle = withContext(Dispatchers.IO) {
        requireKey()
        val body = buildJsonObject {
            put("model", request.extra["model"] ?: if (request.kind == MediaKind.TEXT_TO_VIDEO) "wanx2.1-t2v-turbo" else "wanx2.1-t2i-turbo")
            put("input", buildJsonObject {
                put("prompt", request.prompt)
                request.extra["negative_prompt"]?.let { put("negative_prompt", it) }
            })
            put("parameters", buildJsonObject {
                put("size", dashScopeSize(request.ratio))
                put("n", request.extra["n"]?.toIntOrNull() ?: 1)
                request.extra["seed"]?.let { put("seed", it) }
            })
        }
        val endpoint = if (request.kind == MediaKind.TEXT_TO_VIDEO) VIDEO_URL else IMAGE_URL
        val taskId = postAsync(endpoint, body)
        MediaTaskHandle(providerTaskId = taskId, state = MediaTaskState.Running(null))
    }

    override suspend fun poll(taskId: String): MediaTaskState = withContext(Dispatchers.IO) {
        requireKey()
        val resp = get("$TASK_URL/$taskId")
        val output = resp["output"]?.objOrNull() ?: throw MediaException("轮询响应缺 output 字段")
        when (val status = output["task_status"]?.primOrNull()?.contentOrNull) {
            "SUCCEEDED" -> {
                val urls = mutableListOf<String>()
                output["results"]?.arrOrNull()?.forEach { el ->
                    el.objOrNull()?.get("url")?.primOrNull()?.contentOrNull?.let { urls += it }
                }
                output["video_url"]?.primOrNull()?.contentOrNull?.let { urls += it }
                if (urls.isEmpty()) throw MediaException("任务成功但响应无结果 URL")
                MediaTaskState.Succeeded(urls)
            }

            "FAILED", "CANCELED" -> {
                val msg = output["message"]?.primOrNull()?.contentOrNull ?: "任务失败（$status）"
                MediaTaskState.Failed(msg)
            }

            else -> MediaTaskState.Running(null)
        }
    }

    private fun requireKey() {
        if (apiKey.isBlank()) throw MediaException("未配置 DashScope API Key")
    }

    private fun postAsync(endpoint: String, body: JsonObject): String {
        val request = Request.Builder()
            .url(endpoint)
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .header("Authorization", "Bearer $apiKey")
            .header("X-DashScope-Async", "true")
            .build()
        http.newCall(request).execute().use { resp ->
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
            val root = json.parseToJsonElement(text).objOrNull() ?: JsonObject(emptyMap())
            return root["output"]?.objOrNull()?.get("task_id")?.primOrNull()?.contentOrNull
                ?: throw MediaException("提交响应缺 task_id：${text.take(300)}")
        }
    }

    private fun get(url: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
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
            return json.parseToJsonElement(text).objOrNull() ?: JsonObject(emptyMap())
        }
    }

    companion object {
        private const val IMAGE_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis"
        private const val VIDEO_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis"
        private const val TASK_URL = "https://dashscope.aliyuncs.com/api/v1/tasks"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Ratio → 通义万相 size（星号分隔，非 OpenAI 的 x 分隔；图/视频同尺寸表）。 */
        fun dashScopeSize(ratio: Ratio): String = when (ratio) {
            Ratio.SQUARE -> "1024*1024"
            Ratio.WIDE -> "1280*720"
            Ratio.TALL -> "720*1280"
            Ratio.CLASSIC -> "1024*768"
        }
    }
}
