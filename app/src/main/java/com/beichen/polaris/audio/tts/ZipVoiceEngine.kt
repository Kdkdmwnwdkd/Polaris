package com.beichen.polaris.audio.tts

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 零样本声音克隆（ZipVoice distill int8，中英双语）。
 *
 * ## 「零样本」是什么意思
 *
 * 不训练、不微调，直接把一段参考音频喂进去，模型就在合成时把它当作说话人条件。
 * 代价是每次合成都得带着这段音频跑一遍编码器——所以它比内置音色慢，也吃内存。
 *
 * ## 为什么参考文本是必填
 *
 * ZipVoice 用「参考音频 + 参考文本」对齐出音色与韵律。只给音频、不给文本，
 * 模型不知道该把哪一段波形对应到哪几个音素，合成出来会「像但不像」。
 * 官方文档明确写了：文本与音频不逐字对应时，音质明显下降。
 *
 * ## 为什么单独一个 OfflineTts 实例，不跟内置音色共用一个
 *
 * 两套模型（matcha / zipvoice）的配置完全不相交，且都很占内存。共用一个实例
 * 意味着每次切换音色都要重建引擎、重新加载上百 MB 权重——那是秒级卡顿。
 * 分开持有，谁被选中谁常驻；两个都常驻时占的内存，换取「切音色不用等」。
 *
 * ## 尺寸与速度的实话
 *
 * int8 权重约 104MB，声码器 51MB。手机上初始化 2–4 秒，合成 RTF 大致 1–3
 * （取决于机型与句子长度）。它不适合做「桌宠随口一句话」，适合「我就要听自己的声音」。
 */
class ZipVoiceEngine(private val context: Context) {

    private val tag = "Voice"

    private val mutex = Mutex()

    @Volatile
    private var tts: OfflineTts? = null

    /**
     * 模型是否可用：已展开 **或** 包里带着（首次点击时才展开）。
     *
     * 与 [LocalVoiceEngine.isReady] 同一个理由：展开在 synthesize 里，synthesize 要先被
     * 回落链选中，而链头就是用它筛的。只看 filesDir 会自锁成「永远未就绪」。
     */
    fun isReady(): Boolean =
        TtsAssetInstaller.allPresent(destDir(), REQUIRED_FILES) ||
            TtsAssetInstaller.availableInAssets(context, ASSET_ROOT, REQUIRED_FILES)

    /**
     * 用 [reference] 的音色念 [text]。
     *
     * 失败抛异常：调用方（[VoiceSpeech]）要靠异常类型决定「继续往下退」还是「整条链都完了」，
     * 返回 null 会把「模型没展开」与「参考音频不可用」抹平成同一件事。
     */
    suspend fun synthesize(
        text: String,
        speed: Float,
        reference: FloatArray,
        referenceRate: Int,
        referenceText: String,
    ): AudioClip = withContext(Dispatchers.IO) {
        if (reference.isEmpty() || referenceRate <= 0) {
            throw IllegalArgumentException("参考音频为空")
        }
        if (referenceText.isBlank()) {
            throw IllegalArgumentException("缺少参考文本，克隆会明显失真")
        }
        mutex.withLock {
            if (!isReady()) {
                TtsAssetInstaller.expand(context, ASSET_ROOT, destDir())
            }
            val engine = tts ?: build().also { tts = it }
            val started = System.currentTimeMillis()
            val audio = engine.generateWithConfig(
                text,
                GenerationConfig(
                    silenceScale = 0.2f,
                    speed = speed.coerceIn(0.5f, 2.0f),
                    sid = 0,
                    referenceAudio = reference,
                    referenceSampleRate = referenceRate,
                    referenceText = referenceText,
                    // 蒸馏模型官方示例用 4 步。步数上去音质提升有限，耗时线性增长。
                    numSteps = NUM_STEPS,
                ),
            )
            val elapsed = System.currentTimeMillis() - started
            val samples = audio.samples
            if (samples.isEmpty()) throw IllegalStateException("克隆合成返回空音频")
            val rate = audio.sampleRate
            AgentLogger.i(
                tag,
                "克隆合成完成：${text.length} 字 → ${samples.size * 1000 / maxOf(rate, 1)}ms 音频，" +
                    "耗时 ${elapsed}ms（RTF ${"%.2f".format(elapsed.toFloat() / maxOf(samples.size * 1000 / maxOf(rate, 1), 1))}）"
            )
            AudioClip(pcm = VoiceSampleIo.toShorts(samples), sampleRate = rate)
        }
    }

    fun release() {
        val e = tts ?: return
        tts = null
        runCatching { e.free() }
    }

    private fun build(): OfflineTts {
        val dir = destDir()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                zipvoice = OfflineTtsZipVoiceModelConfig(
                    tokens = File(dir, "tokens.txt").absolutePath,
                    encoder = File(dir, "encoder.int8.onnx").absolutePath,
                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                    vocoder = File(dir, VOCODER).absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath,
                    lexicon = File(dir, "lexicon.txt").absolutePath,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
            silenceScale = 0.2f,
        )
        AgentLogger.i(tag, "克隆引擎初始化：$dir")
        return OfflineTts(config = config)
    }

    private fun destDir(): File = TtsAssetInstaller.destDir(context, DIR_NAME)

    private companion object {
        const val ASSET_ROOT = "tts/zipvoice"
        const val DIR_NAME = "zipvoice"
        const val VOCODER = "vocos_24khz.onnx"
        const val NUM_STEPS = 4

        /**
         * 少了任何一个都会在 build() 里失败，而失败信息只有一句「Invalid OfflineTtsConfig」。
         * 在这里列全，[isReady] 才能在下一次合成前就把缺的文件名写进日志。
         */
        val REQUIRED_FILES = listOf(
            "tokens.txt",
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            VOCODER,
            "lexicon.txt",
            "espeak-ng-data",
        )
    }
}
