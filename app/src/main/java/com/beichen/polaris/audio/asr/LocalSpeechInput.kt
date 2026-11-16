package com.beichen.polaris.audio.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.beichen.polaris.audio.VoiceArbiter
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.voice.ClaimResult
import kotlin.math.max

/**
 * 自带麦克风录音 + 离线识别的「一次说话」。
 *
 * ## 与系统识别的关系
 *
 * 系统识别（`SpeechRecognizer`）是**服务端/厂商实现**，我们只能祈祷它认中文。
 * 这里是另一条完全独立的腿：自己开麦、自己算。两者在 [com.beichen.polaris.pet.PetVoice]
 * 里按「本地模型装没装」分派——装了就走这条，没装仍走系统。
 *
 * ## 为什么录音线程单独起，而不是用协程
 *
 * `AudioRecord.read` 是阻塞的，且必须在一个优先级稳定的线程上连续读；
 * 中间一旦被协程调度器挪走，缓冲区就溢出（丢音，表现为「第一个字听不到」）。
 * 一个普通 `Thread` + 固定的 100ms 块最稳，也最好懂。
 *
 * ## 端点（说完自动停）
 *
 * 桌宠是「按一下说一句」，如果非要用户再按一次结束，体验就退化成对讲机。
 * 所以把判断交给流式模型的 endpoint：尾部静音够了就自动收尾。
 * 同时保留硬上限 [MAX_MS]，防止环境噪音让识别永远不结束。
 */
class LocalSpeechInput(
    private val context: Context,
    private val asr: AsrRouter,
    private val arbiter: VoiceArbiter,
) {

    sealed interface Event {
        /** 实时文本（整句累计，不是增量）。 */
        data class Partial(val text: String) : Event

        /** 说完了。[text] 可能为空——那是「确实没听到人声」，不是失败。 */
        data class Final(val text: String) : Event

        /** 走不下去（没权限 / 麦克风被占 / 模型坏了）。[message] 直接给人看。 */
        data class Failed(val message: String) : Event
    }

    private val tag = "AsrInput"

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var lease: com.beichen.polaris.core.voice.AudioLease? = null

    /** 模型装好没有（决定要不要走这条路）。 */
    fun isAvailable(): Boolean = asr.isInstalled()

    /**
     * 开始收音。
     *
     * @return true = 真的开了麦；false = 当场失败（已通过 [onEvent] 报了原因）。
     */
    fun start(onEvent: (Event) -> Unit): Boolean {
        if (running) {
            onEvent(Event.Failed("上一次还没结束"))
            return false
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onEvent(Event.Failed("没有麦克风权限：到设置里允许「录音」再试"))
            return false
        }
        when (val claim = arbiter.beginCapture("离线语音输入")) {
            is ClaimResult.Denied -> {
                onEvent(Event.Failed(claim.message))
                return false
            }
            is ClaimResult.Granted -> lease = claim.lease
        }

        val session = asr.ensureSession()
        if (session == null) {
            releaseLease()
            onEvent(Event.Failed("离线识别模型没装好：到 设置 → 语音 里重新下载"))
            return false
        }

        running = true
        val t = Thread({ loop(session, onEvent) }, "asr-capture")
        t.priority = Thread.NORM_PRIORITY + 1
        thread = t
        t.start()
        return true
    }

    /** 用户松手：让录音线程收尾，最终文本通过 [Event.Final] 回来。 */
    fun stop() {
        running = false
    }

    /** 立即丢弃（来电、页面退出）。不回调 Final。 */
    fun cancel() {
        running = false
        thread?.interrupt()
    }

    // —— 内部 ——

    private fun loop(session: AsrSession, onEvent: (Event) -> Unit) {
        var recorder: AudioRecord? = null
        try {
            // 先等扬声器出口静下来再开麦。
            //
            // 为什么必需：用户常常在 AI 还在念的时候按麦克风。仲裁器那一刻会
            // `speaker.stop()`，但「命令已下发」不等于「已经没声音」——AudioTrack 的
            // 硬件缓冲、功放与房间反射还要一段时间。少于这段就直接 startRecording，
            // 录到的是我们刚才那句话的尾巴；而离线识别是整段认，这段会被拼进用户句子里，
            // 表现为「我说了一句，它却把上一条回答也识别进去了」。
            //
            // 只在这次抢占过播报时才等（仲裁器返回 0 表示没在播报），
            // 所以平常开麦没有任何额外延迟。
            val settled = arbiter.awaitQuietForCapture()
            if (settled > 0) AgentLogger.i(tag, "开麦前等待扬声器静音 ${settled}ms")

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            // 用 VOICE_RECOGNITION 而不是 MIC：前者会关掉 AGC/降噪里对识别有害的那部分，
            // 且部分 ROM 上会走低延迟通路。识别场景下它比 MIC 明显准。
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, CHUNK_SAMPLES * 2 * 4),
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                onEvent(Event.Failed("麦克风打不开（可能被别的应用占着）"))
                return
            }
            recorder.startRecording()

            val buf = ShortArray(CHUNK_SAMPLES)
            val started = System.currentTimeMillis()
            var last = ""
            while (running) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { buf[it] / 32768.0f }
                val text = runCatching { session.accept(samples) }.getOrNull() ?: continue
                if (text != last) {
                    last = text
                    onEvent(Event.Partial(text))
                }
                if (session.isEndpoint() || System.currentTimeMillis() - started > MAX_MS) {
                    running = false
                }
            }

            val finalText = runCatching { session.finish() }.getOrDefault(last).trim()
            AgentLogger.i(tag, "离线识别完成：${finalText.length} 字（${System.currentTimeMillis() - started}ms）")
            onEvent(Event.Final(finalText))
        } catch (e: Throwable) {
            AgentLogger.w(tag, "离线识别中断：${e.message}")
            onEvent(Event.Failed("录音出错：${e.message ?: e.javaClass.simpleName}"))
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            session.close()
            releaseLease()
        }
    }

    private fun releaseLease() {
        val l = lease ?: return
        lease = null
        runCatching { arbiter.endCapture(l) }
            .onFailure { AgentLogger.w(tag, "释放麦克风失败：${it.message}") }
    }

    private companion object {
        /** 100ms 一块。太小会被调度抖动绊住，太大实时文本会一顿一顿。 */
        const val CHUNK_SAMPLES = 1_600

        /** 两套模型都是 16kHz 训练的；喂别的采样率进去得到的是一串音近词。 */
        const val SAMPLE_RATE = 16_000

        /** 一口气说 30 秒已经远超「随口问一句」，到这里强制收尾。 */
        const val MAX_MS = 30_000L
    }
}
