package com.beichen.polaris.core.llm

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.UserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 多模态图片的 data URI 工具：Claude / Gemini 的图片都只认 base64 inline，
 * 而 AgentKit 的 [UserMessage.images] 里可能是 data URI，也可能 http(s) URL
 * （截图工具产出 data URI，用户粘贴/上传可能是 URL）。
 *
 * 收敛在本对象里，两个 Provider 复用；下载失败返回 null，调用方跳过该图并记日志。
 */
internal object ImageUris {

    /** 拆 `data:image/png;base64,XXXX` → ("image/png", "XXXX")；不是 data URI 返回 null。 */
    fun splitDataUri(uri: String): Pair<String, String>? {
        if (!uri.startsWith("data:")) return null
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(5, comma) // image/png;base64
        val mime = meta.substringBefore(';').trim().ifBlank { "image/png" }
        val data = uri.substring(comma + 1)
        if (data.isBlank()) return null
        return mime to data
    }

    /** 把 http(s) 图片下载后转成 data URI；失败返回 null（调用方跳过该图）。 */
    fun downloadAsDataUri(http: OkHttpClient, url: String): String? = try {
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        resp.use { r ->
            if (!r.isSuccessful) {
                AgentLogger.i("LLM", "图片下载失败 ${r.code}: ${url.take(80)}")
                return null
            }
            val bytes = r.body?.bytes() ?: return null
            val mime = r.header("Content-Type")?.substringBefore(';')?.trim() ?: "image/png"
            "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    } catch (e: IOException) {
        AgentLogger.e("LLM", "图片下载异常", e)
        null
    }

    /**
     * 预处理消息列表：把所有 [UserMessage.images] 统一成 data URI。
     * 网络下载走 IO 线程，调用方需在协程里调用。
     */
    suspend fun resolveImages(http: OkHttpClient, messages: List<ChatMessage>): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            messages.map { msg ->
                if (msg !is UserMessage || msg.images.isEmpty()) return@map msg
                val resolved = msg.images.mapNotNull { url ->
                    when {
                        url.startsWith("data:") -> url
                        url.startsWith("http://") || url.startsWith("https://") ->
                            downloadAsDataUri(http, url)
                        else -> {
                            AgentLogger.i("LLM", "跳过不支持的图片 scheme: ${url.take(40)}")
                            null
                        }
                    }
                }
                msg.copy(images = resolved)
            }
        }
}
