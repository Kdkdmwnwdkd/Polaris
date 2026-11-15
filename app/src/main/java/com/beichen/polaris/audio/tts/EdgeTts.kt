package com.beichen.polaris.audio.tts

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.voice.VoiceProfile
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 在线神经音色：直连微软 Edge 朗读接口合成一段 mp3，再解成 PCM。
 *
 * ## 为什么不要 key
 *
 * 那 6 个音色（晓晓/晓伊/云希/云健/云扬/晓北）就是 Azure 神经 TTS 的同一批模型。
 * Edge 浏览器的「大声朗读」走的是免登录通道，服务端只校验请求里那个
 * `Sec-MS-GEC` 令牌（见 [SecMsGec]）——算得出来就能用。
 *
 * 这不是「逆向别人的付费接口」：不需要账号、不消耗任何配额、也不绕过鉴权，
 * 走的就是 Edge 自己在走的那条路。但它是**网页端私有协议**，微软随时可能改，
 * 所以这一档必须永远排在回落链的前面而不是唯一的一环：
 * 一旦 403，用户听到的应该是本地音色，而不是「没声音」。
 *
 * ## 协议要点
 *
 * 连上后先发一帧 `speech.config`（说明要 mp3、24kHz、不要逐字时间戳），
 * 再发一帧 SSML。服务端把音频切成若干**二进制帧**返回，每帧前 2 字节是大端
 * 头长度，头里写着 `Path:audio`，头后面才是 mp3 字节。收到文本帧
 * `Path:turn.end` 表示这一轮结束。
 */
class EdgeTts(private val context: Context) {

    private val tag = "Voice"

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // WebSocket 靠 ping 保活；服务端在长句上会静默十几秒。
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /**
     * 合成一段话。
     *
     * 阻塞执行，调用方在 IO 线程上等。失败抛异常——原因会被 [VoiceSpeech] 拼进
     * 回落说明里，用户看到的不是「在线音色失败」，而是「在线音色 403」。
     */
    fun synthesize(profile: VoiceProfile, text: String, rate: Float): AudioClip {
        val voice = profile.providerVoice.ifBlank {
            throw IllegalArgumentException("音色 ${profile.safeLabel} 没有接口侧名字")
        }
        val mp3 = requestAudio(voice, text, rate)
        if (mp3.isEmpty()) throw IllegalStateException("服务端返回了 0 字节音频")
        val clip = Mp3Decoder.decode(context, mp3)
            ?: throw IllegalStateException("mp3 解码失败（${mp3.size} 字节）")
        AgentLogger.i(tag, "在线合成完成：${text.length} 字 → ${clip.durationMs}ms，音色 $voice")
        return clip
    }

    private fun requestAudio(voice: String, text: String, rate: Float): ByteArray {
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=${SecMsGec.TRUSTED_CLIENT_TOKEN}" +
            "&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=${SecMsGec.token()}" +
            "&Sec-MS-GEC-Version=${SecMsGec.SEC_MS_GEC_VERSION}"

        val request = Request.Builder()
            .url(url)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", USER_AGENT)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var failure: String? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val stamp = timestamp()
                val config = "{\"context\":{\"synthesis\":{\"audio\":{" +
                    "\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\"," +
                    "\"wordBoundaryEnabled\":\"false\"}," +
                    "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}"
                webSocket.send(
                    "X-Timestamp:$stamp\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n$config"
                )
                webSocket.send(
                    "X-RequestId:$connectionId\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        // 尾巴上这个 Z 是上游踩过的坑：微软自己的 Edge 也这么发。
                        "X-Timestamp:${stamp}Z\r\n" +
                        "Path:ssml\r\n\r\n" + ssml(voice, text, rate)
                )
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                val headerEnd = 2 + headerLength
                if (data.size <= headerEnd) return
                val header = String(data, 2, headerLength, Charsets.UTF_8)
                if (!header.contains("Path:audio")) return
                audio.write(data, headerEnd, data.size - headerEnd)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    done.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = describe(t, response)
                done.countDown()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                done.countDown()
            }
        }

        val socket = client.newWebSocket(request, listener)
        val finished = done.await(TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            socket.cancel()
            throw IllegalStateException("在线合成超时（${TOTAL_TIMEOUT_MS / 1000} 秒没有回音）")
        }
        failure?.let { throw IllegalStateException(it) }
        return audio.toByteArray()
    }

    private fun describe(t: Throwable, response: Response?): String {
        val code = response?.code
        val hint = when (code) {
            403 -> "接口拒绝了令牌（403）——微软可能改过协议，先退到本地音色"
            429 -> "请求太频繁（429），稍后再试"
            null -> "连接失败：${t.javaClass.simpleName} ${t.message ?: ""}".trim()
            else -> "服务端返回 $code"
        }
        AgentLogger.w(tag, "在线合成失败：$hint")
        return hint
    }

    private fun ssml(voice: String, text: String, rate: Float): String {
        val percent = ((rate - 1f) * 100).toInt()
        val rateText = if (percent >= 0) "+$percent%" else "$percent%"
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
            "<voice name='$voice'>" +
            "<prosody rate='$rateText' pitch='+0Hz'>${escape(text)}</prosody>" +
            "</voice></speak>"
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun timestamp(): String = SimpleDateFormat(
        "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private companion object {
        const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 30_000L

        /** 一句话的正常往返是 1~3 秒；给到 20 秒是留给弱网，再久就当它挂了。 */
        const val TOTAL_TIMEOUT_MS = 20_000L

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    }
}
