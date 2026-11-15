package com.beichen.polaris.audio.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.beichen.polaris.core.AgentLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一段合成好的音频。
 *
 * 统一成 16-bit PCM：本地模型本来吐的就是 float 采样，在线服务要解码成 mp3——
 * 让两者在出口处收敛成同一种格式，播放端与「正在说话」判定就只有一条路径。
 * 分两条路径的后果是：一条有完成回调、一条没有，仲裁器会漏掉其中一条的结束时刻。
 */
class AudioClip(
    val pcm: ShortArray,
    val sampleRate: Int,
) {
    val durationMs: Long
        get() = if (sampleRate <= 0) 0 else pcm.size * 1000L / sampleRate
}

/**
 * PCM 播放器。
 *
 * ## 为什么用 AudioTrack 而不是 MediaPlayer
 *
 * MediaPlayer 只吃文件/URI，合成结果得先落盘再读——多一次 IO，且失败点从一处变三处
 * （写文件、建播放器、异步 prepare）。AudioTrack 直接吃内存里的 PCM，
 * 播放与「念完了」都在同一条线程上确定发生。
 *
 * ## 为什么不用 `MODE_STREAM` 边合成边播
 *
 * 会更快出声，但 `stop()` 的语义会变得依赖「写线程是否还在阻塞」，
 * 而 `stop()` 是打断仲裁的最后一环（用户点麦克风要立刻静音）。
 * 先整段合成再播，停就是停。
 */
class PcmPlayer(private val context: Context) {

    private val tag = "Voice"

    @Volatile
    private var track: AudioTrack? = null

    private val cancelled = AtomicBoolean(false)

    /**
     * 播放一整段，返回是否正常念完。
     *
     * `false` 表示被打断或被系统拒绝——调用方据此决定要不要继续念下一段。
     */
    fun play(clip: AudioClip): Boolean {
        if (clip.pcm.isEmpty() || clip.sampleRate <= 0) return false
        cancelled.set(false)
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(clip.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            AgentLogger.w(tag, "AudioTrack 不支持 ${clip.sampleRate}Hz 单声道——设备音频栈异常")
            return false
        }
        val bufferSize = maxOf(minBuffer, clip.pcm.size * 2)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(clip.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                // STATIC：整段放进去再播。流式写入要自己算「缓冲放完了没」，
                // 算错就吞尾音——而尾音恰恰是「这句话说完了」的唯一凭据。
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull()
        if (t == null) {
            AgentLogger.w(tag, "AudioTrack 创建失败（采样率 ${clip.sampleRate}）")
            return false
        }
        track = t
        return try {
            val written = t.write(clip.pcm, 0, clip.pcm.size)
            if (written <= 0) {
                AgentLogger.w(tag, "AudioTrack 写入失败（$written）")
                return false
            }
            val done = CountDownLatch(1)
            t.setNotificationMarkerPosition(clip.pcm.size)
            t.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack?) = done.countDown()
                    override fun onPeriodicNotification(track: AudioTrack?) = Unit
                }
            )
            t.play()
            // 上限按实际时长留 3 秒余量：设备被降频/耳机拔出时 marker 可能不来，
            // 没有上限就会把播报租约一直捏在手里（表现为「麦克风一直点不开」）。
            val finished = done.await(clip.durationMs + 3_000L, TimeUnit.MILLISECONDS)
            if (!finished) {
                AgentLogger.w(tag, "AudioTrack 未在预期时长内报完成（${clip.durationMs}ms）——按已念完处理")
            }
            !cancelled.get()
        } catch (e: Exception) {
            AgentLogger.w(tag, "AudioTrack 播放异常：${e.javaClass.simpleName} ${e.message}")
            false
        } finally {
            runCatching { t.stop() }
            runCatching { t.release() }
            track = null
        }
    }

    fun stop() {
        cancelled.set(true)
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.stop() }
    }
}

/** 造 [PcmPlayer] 需要一个 Context，这里只做归属，不持有 Activity。 */
internal fun pcmPlayer(context: Context): PcmPlayer = PcmPlayer(context.applicationContext)
