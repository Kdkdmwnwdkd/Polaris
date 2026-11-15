package com.beichen.polaris.audio.tts

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.beichen.polaris.core.AgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 录一段参考音频。
 *
 * ## 为什么不用 MediaRecorder
 *
 * 它出的是 m4a/aac，还要再加一步解码才能拿到采样。参考音频的音质会原样带进克隆结果，
 * 多一层有损编码纯属自己给自己降质。AudioRecord 直接给 PCM16，写完就是可用的参考。
 *
 * ## 为什么采样率优先 24kHz
 *
 * ZipVoice 原生 24kHz。用 24kHz 采集，参考音频进模型不需要重采样——
 * 重采样虽然是线性的、损失不大，但「能不做就不做」在有原生选项时是免费的。
 * 设备不支持 24kHz 时退到系统常给的 48kHz，再降采样（见 [VoiceSampleIo.resample]）。
 *
 * ## 为什么上限 12 秒而不是「按一下就停」
 *
 * 用户对着手机读一句「请按提示读这句话」，最怕的是「忘了按停止」——录 3 分钟，
 * 参考音频里全是后半段的空气声。设一个硬上限，读不完是他的问题，停不下来的不是。
 */
class VoiceRecorder {

    private val tag = "Voice"

    /** 录音上限。ZipVoice 的参考音频 5–15 秒都行，12 秒足够读完一句常见的话。 */
    val maxDurationMs: Int = 12_000

    @Volatile
    private var recording: AudioRecord? = null

    /**
     * 录一段，返回 [-1,1] 采样与实际采样率。
     *
     * [onLevel] 会被高频调用（每 50ms 左右一次），只用来画音量条；别在里面做重活。
     * 失败返回 null 而不是空数组：空数组会让界面显示「录到了，但没声音」，
     * 而真实原因是麦克风被占用或初始化失败——两件事用户该做的处理完全不同。
     */
    @SuppressLint("MissingPermission")
    suspend fun record(onLevel: (Float) -> Unit): Recording? = withContext(Dispatchers.IO) {
        val candidates = intArrayOf(VoiceSampleIo.REFERENCE_RATE, 48_000, 44_100)
        var track: AudioRecord? = null
        var rate = 0
        var minBuf = 0
        for (candidate in candidates) {
            val buf = AudioRecord.getMinBufferSize(
                candidate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (buf <= 0) continue
            val t = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    candidate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    buf * 2,
                )
            }.getOrNull() ?: continue
            if (t.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { t.release() }
                continue
            }
            track = t
            rate = candidate
            minBuf = buf
            break
        }
        val t = track
        if (t == null) {
            AgentLogger.w(tag, "录音初始化失败：${candidates.joinToString("/")}Hz 都不可用")
            return@withContext null
        }
        AgentLogger.i(tag, "开始录音：${rate}Hz，上限 ${maxDurationMs}ms")
        recording = t
        val chunk = ShortArray(max(minBuf, 2048))
        val acc = ArrayList<Short>(rate * maxDurationMs / 1000)
        var levelTick = 0
        try {
            t.startRecording()
            if (t.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                AgentLogger.w(tag, "录音没能真正开始（麦克风被占用？）")
                return@withContext null
            }
            val deadline = System.currentTimeMillis() + maxDurationMs
            while (System.currentTimeMillis() < deadline) {
                coroutineContext.ensureActive()
                val n = t.read(chunk, 0, chunk.size)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                        AgentLogger.w(tag, "录音读取失败：$n")
                        return@withContext null
                    }
                    continue
                }
                for (i in 0 until n) acc.add(chunk[i])
                // 每约 50ms 报一次电平：12000Hz/2048 ≈ 58ms，正好。
                if (levelTick++ % 1 == 0) {
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = chunk[i] / 32768.0
                        sum += v * v
                    }
                    onLevel(sqrt(sum / max(n, 1)).toFloat().coerceIn(0f, 1f))
                }
            }
        } catch (e: Exception) {
            AgentLogger.w(tag, "录音异常：${e.javaClass.simpleName} ${e.message}")
            return@withContext null
        } finally {
            runCatching { t.stop() }
            runCatching { t.release() }
            recording = null
        }
        if (acc.size < rate / 2) {
            AgentLogger.w(tag, "录音太短：${acc.size} 采样")
            return@withContext null
        }
        val shorts = ShortArray(acc.size)
        for (i in acc.indices) shorts[i] = acc[i]
        val floats = FloatArray(shorts.size) { shorts[it] / 32768f }
        val trimmed = VoiceSampleIo.trimSilence(floats, rate)
        Recording(trimmed, rate)
    }

    /** 中断当前录音（用户中途按「停」或页面被关）。 */
    fun cancel() {
        val t = recording ?: return
        runCatching { t.stop() }
    }

    /** 录音结果：已裁掉首尾静音。 */
    class Recording(val samples: FloatArray, val sampleRate: Int) {
        val durationMs: Long get() = samples.size * 1000L / max(sampleRate, 1)

        /** 音量是否够用：峰值低于 0.02（约 -34dBFS）说明基本没说话。 */
        fun looksSilent(): Boolean {
            var peak = 0f
            for (s in samples) peak = max(peak, abs(s))
            return peak < 0.02f
        }
    }
}
