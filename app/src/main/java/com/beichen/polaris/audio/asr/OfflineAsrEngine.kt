package com.beichen.polaris.audio.asr

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.voice.SpeechPresence
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * 整段识别（paraformer-zh-small，非流式）。
 *
 * ## 为什么要多这一个引擎
 *
 * 流式那套（[StreamingAsr]）用的是 14M 参数的 zipformer：模型小、边说边出字，
 * 代价是**错字多**。2026-11-15 真机实测：「你好」认得对，
 * 「你在这吗」变成「你在这好」，「干嘛呢你」变成「干吗呢能够困」。
 * 这种程度在聊天里是不能用的——用户每句都得盯着改。
 *
 * paraformer 是整段模型：要等松开手指才开始认，慢约 0.6–1.2 秒，但中文短句的
 * 错字率明显低一档。用户的场景本来就是「按住说话、松手等回复」，
 * 那点延迟换来的是「不用改字」。
 *
 * ## 为什么两个都留着
 *
 * 不是所有人都愿意为准确率多付 50MB 和 1 秒。装哪个用哪个：
 * 装了 paraformer 就用它，只有流式的就还用流式——[AsrRouter] 负责挑。
 *
 * ## 归一化
 *
 * 手机揣在桌上、人离半米说话时，音量常常只有峰值的两三成，识别会漏字。
 * 收尾时把峰值拉到 0.9（只放大不衰减，且封顶防削顶）比调 AGC 稳：
 * AGC 会跟着环境噪声一起放大，安静时反而把底噪抬起来。
 */
class OfflineAsrEngine(context: Context) : AsrEngine {

    private val tag = TAG

    override val dir: File = File(File(context.filesDir, "asr"), "zh-paraformer")

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    override fun isInstalled(): Boolean = model() != null

    override fun sizeBytes(): Long = if (isInstalled()) {
        dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    } else 0L

    @Synchronized
    override fun ensureSession(): AsrSession? {
        val m = model() ?: run {
            AgentLogger.w(tag, "整段识别模型不完整：${dir.absolutePath}")
            return null
        }
        val r = recognizer ?: run {
            val t0 = System.currentTimeMillis()
            runCatching { build(m) }
                .onFailure { AgentLogger.w(tag, "识别器创建失败：${it.message}") }
                .getOrNull()
                ?.also {
                    recognizer = it
                    AgentLogger.i(tag, "整段识别就绪：${m.name}（加载 ${System.currentTimeMillis() - t0}ms）")
                }
                ?: return null
        }
        return Batch(r)
    }

    @Synchronized
    override fun release() {
        runCatching { recognizer?.release() }
        recognizer = null
    }

    /**
     * 攒音频，收尾时认一次。
     *
     * `accept` 返回的永远是空串：整段模型没有「已经认出来的前半句」这种东西。
     * 上层的 partial 回调于是只在最后跳一次——这是这套模型的固有代价，不是 bug。
     */
    private class Batch(private val recognizer: OfflineRecognizer) : AsrSession {

        private val chunks = ArrayList<FloatArray>(64)
        private var total = 0

        override fun accept(samples: FloatArray, sampleRate: Int): String {
            chunks.add(samples)
            total += samples.size
            return ""
        }

        override fun isEndpoint(): Boolean = false

        override fun finish(): String {
            if (total <= 0) return ""
            val pcm = FloatArray(total)
            var at = 0
            chunks.forEach { c ->
                System.arraycopy(c, 0, pcm, at, c.size)
                at += c.size
            }
            chunks.clear()
            total = 0

            // 先判有没有人说话，再决定要不要放大。
            //
            // 顺序不能反：normalize 会把峰值拉到 0.9，而静音的峰值只有 0.003 上下，
            // 一放大等于把底噪抬成「很响的音频」，模型就会给这段噪声编出几个字——
            // 2026-11-21 真机实测里「一个字没说却识别出 3 字」就是这么来的。
            // 判定放在放大之前，看到的才是真实音量。
            val fullMs = pcm.size * 1000 / SAMPLE_RATE
            val voiced = SpeechPresence.voicedMillis(pcm, SAMPLE_RATE)
            if (voiced < SpeechPresence.MIN_SPEECH_MS) {
                AgentLogger.i(TAG, "判为无人声：有声 ${voiced}ms / 全段 ${fullMs}ms，不送识别")
                return ""
            }
            AgentLogger.i(TAG, "送识别：有声 ${voiced}ms / 全段 ${fullMs}ms")

            normalize(pcm)

            val stream = recognizer.createStream()
            return try {
                stream.acceptWaveform(pcm, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }

        override fun close() {
            chunks.clear()
            total = 0
        }
    }

    private fun build(m: File): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = m.absolutePath),
                tokens = File(dir, TOKENS).absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(assetManager = null, config = config)
    }

    /** `model.int8.onnx` 优先（体积三分之一、手机上一倍速），没有就退到 fp32。 */
    private fun model(): File? {
        if (!dir.isDirectory) return null
        val cands = dir.listFiles { f -> f.isFile && f.name.startsWith("model") && f.name.endsWith(".onnx") }
            ?.filter { it.length() > 0 }
            .orEmpty()
        val hit = cands.firstOrNull { it.name.contains("int8") } ?: cands.minByOrNull { it.length() }
        if (hit == null || File(dir, TOKENS).length() <= 0) return null
        return hit
    }

    private companion object {
        const val TAG = "AsrOffline"
        const val SAMPLE_RATE = 16_000
        const val TOKENS = "tokens.txt"

        /** 峰值低于这个值才放大。正常近距离说话大约 0.4–0.8，不需要动。 */
        const val QUIET_PEAK = 0.25f

        /** 放大后的目标峰值。留 0.1 余量，避免后续重采样时削顶。 */
        const val TARGET_PEAK = 0.9f

        /** 只放大不衰减：已经很响的录音再拉满只会削顶，反而更糟。 */
        fun normalize(pcm: FloatArray) {
            var peak = 0f
            for (v in pcm) {
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
            }
            if (peak <= QUIET_PEAK || peak <= 0f) return
            val gain = TARGET_PEAK / peak
            if (gain <= 1f) return
            for (i in pcm.indices) pcm[i] *= gain
        }
    }
}
