package com.beichen.polaris.audio.tts

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.net.BoundedBody
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * 参考音频的读写与整形（WAV / PCM16 单声道）。
 *
 * ## 为什么自己写 WAV，而不是用 MediaRecorder / MediaExtractor
 *
 * 克隆的输入必须是**裸采样**：ZipVoice 直接吃 `FloatArray` + 采样率，
 * 中间经过一次编码解码（m4a/aac）会引入压缩伪影，而参考音频的音质会被原样带进合成结果。
 * MediaRecorder 只能出压缩格式，MediaExtractor 要处理容器解析——两头都是「为了绕开
 * 二十行 RIFF 头而引入两千行系统代码」。这里自己写，格式固定，失败点只有一处。
 *
 * ## 为什么读也要自己写
 *
 * 写的时候格式是我们定的，读的时候却要防「文件被别的程序改过 / 拷到一半」。
 * 于是读这边不信任长度字段，只信任实际文件长度——截断的文件按实际长度取，
 * 由调用方按「够不够长」判断能不能用。
 */
object VoiceSampleIo {

    private const val tag = "Voice"

    /** 参考音频的读取上限。实际文件约 0.6MB，留 6 倍余量给不同采样率与更长样本。 */
    private const val MAX_WAV_BYTES = 4L * 1024 * 1024

    /** 我们生成的参考音频统一 24kHz 单声道：ZipVoice 的原生采样率，省一次重采样。 */
    const val REFERENCE_RATE = 24_000

    /** 写成 16-bit 单声道 WAV。目录不存在时自动创建。 */
    fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        val dataBytes = samples.size * 2
        val header = ByteArray(44)
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            header[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            header[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        "RIFF".forEachIndexed { i, c -> header[i] = c.code.toByte() }
        putInt(4, 36 + dataBytes)
        "WAVE".forEachIndexed { i, c -> header[8 + i] = c.code.toByte() }
        "fmt ".forEachIndexed { i, c -> header[12 + i] = c.code.toByte() }
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, 1) // 单声道
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2) // 字节率
        putShort(32, 2) // 块对齐
        putShort(34, 16) // 位深
        "data".forEachIndexed { i, c -> header[36 + i] = c.code.toByte() }
        putInt(40, dataBytes)
        file.outputStream().use { out ->
            out.write(header)
            val body = ByteArray(dataBytes)
            for (i in samples.indices) {
                val v = samples[i].toInt()
                body[i * 2] = (v and 0xFF).toByte()
                body[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
            }
            out.write(body)
        }
    }

    /**
     * 读 WAV 成 [-1,1] 浮点。格式不认识时返回 null（不抛——调用方要按「这段参考音频没了」处理）。
     *
     * 只认 PCM16 单声道：那正是我们自己写的格式。别的格式出现在这里，说明文件被换过，
     * 与其猜它的布局，不如让上层提示重录。
     */
    fun readWav(file: File): Pair<FloatArray, Int>? {
        if (!file.isFile || file.length() < 44) return null
        // 走 BoundedBody：参考音频该有多大是已知的（12 秒 24kHz 16bit ≈ 576KB），
        // 超过 4MB 的文件只可能是被换过或写坏了。裸 readBytes 会把它整个读进内存，
        // 而这段代码跑在合成路径上——一个坏文件就能把应用 OOM 掉。
        val bytes = runCatching {
            file.inputStream().use { BoundedBody.readBytes(it, MAX_WAV_BYTES) }
        }.getOrNull() ?: return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        fun intAt(o: Int): Int =
            (bytes[o].toInt() and 0xFF) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                ((bytes[o + 2].toInt() and 0xFF) shl 16) or
                ((bytes[o + 3].toInt() and 0xFF) shl 24)

        // 逐个 chunk 找 fmt / data：不假设它们一定在固定偏移（有些工具会插入 LIST 块）。
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = intAt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 > bytes.size) return null
                    channels = (bytes[body + 2].toInt() and 0xFF) or ((bytes[body + 3].toInt() and 0xFF) shl 8)
                    rate = intAt(body + 4)
                    bits = (bytes[body + 14].toInt() and 0xFF) or ((bytes[body + 15].toInt() and 0xFF) shl 8)
                }
                "data" -> {
                    dataStart = body
                    // 不信任 size 字段：拷到一半的文件里它仍是完整值。
                    dataLen = (bytes.size - body).coerceAtMost(if (size > 0) size else bytes.size - body)
                }
            }
            if (size <= 0) break
            pos = body + size + (size and 1) // chunk 按偶数字节对齐
        }
        if (dataStart < 0 || rate <= 0 || channels != 1 || bits != 16) return null
        val count = dataLen / 2
        if (count <= 0) return null
        val out = FloatArray(count)
        for (i in 0 until count) {
            val lo = bytes[dataStart + i * 2].toInt() and 0xFF
            val hi = bytes[dataStart + i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out to rate
    }

    /**
     * 只读文件头算出时长，不把整段音频读进内存。
     *
     * 用途是「这条参考音频还够用吗」这种轻量判定——它在每次合成前都会跑，
     * 而真正要用时才会完整读一遍。为了判断「够不够 2 秒」而读 300KB，不值得。
     */
    fun durationMsOf(file: File): Long {
        if (!file.isFile || file.length() < 44) return 0
        val head = runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(64)
                val n = input.read(buf)
                if (n < 44) null else buf
            }
        }.getOrNull() ?: return 0
        fun intAt(o: Int): Int =
            (head[o].toInt() and 0xFF) or
                ((head[o + 1].toInt() and 0xFF) shl 8) or
                ((head[o + 2].toInt() and 0xFF) shl 16) or
                ((head[o + 3].toInt() and 0xFF) shl 24)
        val rate = intAt(24)
        if (rate <= 0) return 0
        // data chunk 从 36 起、长度在 40（我们自己写的固定布局，读这边只用于估算）。
        val dataLen = intAt(40).takeIf { it > 0 } ?: (file.length() - 44).toInt()
        return dataLen / 2L * 1000L / rate
    }

    fun toShorts(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            out[i] = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return out
    }

    /**
     * 裁掉首尾静音。
     *
     * 为什么要裁：参考音频是「读一句话」，但录制窗口固定 12 秒，读得快的人后面会剩 5 秒空气。
     * 静音进了参考，ZipVoice 会把它当成说话人的气息/停顿学进去，合成出来每句都带一段空白。
     *
     * 阈值取相对峰值的 -35dB：跟环境噪声走，不写死绝对值——安静房间和临街房间的底噪
     * 差三倍，写死一个数总有一边判错。两侧各留 120ms 余量，避免把字头字尾的辅音削掉。
     */
    fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty()) return samples
        var peak = 0f
        for (s in samples) peak = max(peak, abs(s))
        if (peak <= 1e-4f) return samples
        // -35dB ≈ 0.0178；相对峰值算，再设一个绝对下限防止全静音时把噪声当人声。
        val threshold = max(peak * 0.0178f, 0.002f)
        val margin = (sampleRate * 0.12f).toInt()
        var start = 0
        while (start < samples.size && abs(samples[start]) < threshold) start++
        var end = samples.size - 1
        while (end > start && abs(samples[end]) < threshold) end--
        if (start >= end) return samples
        val from = (start - margin).coerceAtLeast(0)
        val to = (end + margin).coerceAtMost(samples.size - 1)
        return samples.copyOfRange(from, to + 1)
    }

    /**
     * 线性重采样（兜底用）。
     *
     * 只在设备不支持 24kHz 采集时走到这里。线性插值对 44.1k→24k 这种降采样会有轻微混叠，
     * 但参考音频只要「音色对」就够了——ZipVoice 学的是音色与韵律，不是高频细节。
     * 为了这一点精度引一个重采样库不值得。
     */
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || samples.isEmpty() || from <= 0 || to <= 0) return samples
        val outLen = (samples.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val step = from.toDouble() / to
        for (i in 0 until outLen) {
            val x = i * step
            val i0 = x.toInt().coerceIn(0, samples.size - 1)
            val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
            val frac = (x - i0).toFloat()
            out[i] = samples[i0] * (1 - frac) + samples[i1] * frac
        }
        AgentLogger.i(tag, "参考音频重采样：$from → $to Hz，${samples.size} → $outLen 采样")
        return out
    }
}
