package com.beichen.polaris.audio.tts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.beichen.polaris.core.AgentLogger
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * mp3 → 16-bit PCM。
 *
 * 播放端只有一条路（[PcmPlayer] 吃 PCM），所以在线拿到的 mp3 必须先解成 PCM 再交给它。
 * 反过来让播放器也认 mp3，就会出现「两条播放路径、两套结束判定」——仲裁器漏掉一条，
 * 表现是麦克风永远被占着。
 *
 * 解码器用系统的 [MediaCodec]，不引入 mp3 解码库：Android 自带，解码 1 秒音频约 3ms。
 */
internal object Mp3Decoder {

    private const val TAG = "Voice"
    private const val TIMEOUT_US = 10_000L

    fun decode(context: Context, mp3: ByteArray): AudioClip? {
        // MediaExtractor 只认文件路径，所以落一次临时文件；用完立刻删。
        val file = File.createTempFile("edge-tts-", ".mp3", context.cacheDir)
        try {
            file.writeBytes(mp3)
            return decodeFile(file)
        } catch (e: Exception) {
            AgentLogger.w(TAG, "mp3 解码异常：${e.javaClass.simpleName} ${e.message}")
            return null
        } finally {
            file.delete()
        }
    }

    private fun decodeFile(file: File): AudioClip? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var mime: String? = null
            for (i in 0 until extractor.trackCount) {
                val m = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (m != null && m.startsWith("audio/")) {
                    track = i
                    mime = m
                    break
                }
            }
            if (track < 0 || mime == null) {
                AgentLogger.w(TAG, "mp3 里没有音频轨")
                return null
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var guard = 0

            while (!outputDone && guard++ < MAX_LOOPS) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            pcm.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val bytes = pcm.toByteArray()
            if (bytes.isEmpty()) return null
            val shorts = ShortArray(bytes.size / 2) {
                val lo = bytes[it * 2].toInt() and 0xFF
                val hi = bytes[it * 2 + 1].toInt()
                ((hi shl 8) or lo).toShort()
            }
            return AudioClip(
                pcm = if (channels <= 1) shorts else downmix(shorts, channels),
                sampleRate = sampleRate,
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** 在线接口目前只回单声道；真回了立体声，取平均比丢一半声道更安全。 */
    private fun downmix(shorts: ShortArray, channels: Int): ShortArray {
        val frames = shorts.size / channels
        return ShortArray(frames) { frame ->
            var sum = 0
            for (c in 0 until channels) sum += shorts[frame * channels + c].toInt()
            (sum / channels).toShort()
        }
    }

    private const val MAX_LOOPS = 4000
}
