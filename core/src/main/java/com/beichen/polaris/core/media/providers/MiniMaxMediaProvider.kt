package com.beichen.polaris.core.media.providers

import com.beichen.polaris.core.llm.LlmHttp
import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.core.llm.objOrNull
import com.beichen.polaris.core.media.MediaException
import com.beichen.polaris.core.media.MediaKind
import com.beichen.polaris.core.media.MediaProvider
import com.beichen.polaris.core.media.MediaRequest
import com.beichen.polaris.core.media.MediaTaskHandle
import com.beichen.polaris.core.media.MediaTaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
 * MiniMax 海螺视频（调研 06 号文档「MiniMax 海螺（视频强项）」直译）。
 *
 * 协议：异步两段式。
 *   - 提交：POST /v1/video_generation {model, prompt} → task_id
 *   - 轮询：GET /v1/query/video_generation?task_id= → status + video_url
 *     （status: Queueing / Preparing / Processing / Success / Fail；
 *      video_url 为带时效的下载直链，拿到即由下载器落盘。）
 *
 * 只做视频——海螺的图片接口与视频不同源，工坊的图片任务由其余三家承担，
 * 一个类只把一件事做透（调研 06 号文档「Provider 各管各的协议」）。
 */
class MiniMaxMediaProvider(private val apiKey: String) : MediaProvider {

    override val id = "minimax"
    override val displayName = "MiniMax 海螺"
    override val kinds = setOf(MediaKind.TEXT_TO_VIDEO)

    private val json = Json { ignoreUnknownKeys = true }
    private val http = LlmHttp.llm

    override suspend fun submit(request: MediaRequest): MediaTaskHandle = withContext(Dispatchers.IO) {
        requireKey()
        val body = buildJsonObject {
            put("model", request.extra["model"] ?: "MiniMax-Hailuo-02")
            put("prompt", request.prompt)
            request.extra["duration"]?.let { put("duration", it.toIntOrNull() ?: 6) }
        }
        val request_ = Request.Builder()
            .url("$BASE_URL/video_generation")
            .header("Authorization", "Bearer $apiKey")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .build()
        http.newCall(request_).execute().use { resp ->
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
            val baseResp = root["base_resp"]?.objOrNull()
            if (baseResp != null && baseResp["status_code"]?.primOrNull()?.contentOrNull?.toIntOrNull() != 0) {
                throw MediaException(
                    baseResp["status_msg"]?.primOrNull()?.contentOrNull ?: "提交失败"
                )
            }
            val taskId = root["task_id"]?.primOrNull()?.contentOrNull
                ?: throw MediaException("提交响应缺 task_id")
            return@withContext MediaTaskHandle(taskId, MediaTaskState.Running(null))
        }
    }

    override suspend fun poll(taskId: String): MediaTaskState = withContext(Dispatchers.IO) {
        requireKey()
        val request = Request.Builder()
            .url("$BASE_URL/query/video_generation?task_id=$taskId")
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
            val root = json.parseToJsonElement(text).objOrNull() ?: JsonObject(emptyMap())
            return@withContext when (root["status"]?.primOrNull()?.contentOrNull) {
                "Success" -> {
                    val url = root["video_url"]?.primOrNull()?.contentOrNull
                        ?: throw MediaException("任务成功但响应无 video_url")
                    MediaTaskState.Succeeded(listOf(url))
                }

                "Fail" -> {
                    val msg = root["base_resp"]?.objOrNull()?.get("status_msg")?.primOrNull()?.contentOrNull
                        ?: "任务失败"
                    MediaTaskState.Failed(msg)
                }

                else -> MediaTaskState.Running(null)
            }
        }
    }

    private fun requireKey() {
        if (apiKey.isBlank()) throw MediaException("未配置 MiniMax API Key")
    }

    companion object {
        private const val BASE_URL = "https://api.minimaxi.chat/v1"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
