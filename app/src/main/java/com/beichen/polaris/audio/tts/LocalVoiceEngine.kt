package com.beichen.polaris.audio.tts

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地 22kHz 语音合成（matcha-icefall-zh-baker + vocos 声码器）。
 *
 * ## 为什么是「本地」这一档
 *
 * 手机上的中文合成只有三档现实选择：
 *  - 系统语音包：装机即有，但音色由 ROM 决定，常见的是 8kHz 电话音；
 *  - 本地神经网络模型：22.05kHz，能实时，听起来「平」但没有机械腔；
 *  - 云端大模型：24kHz 最像人，但要联网、要钱、要 key。
 *
 * 这一档选第二种：它是**离线**能拿到的最好音质。它存在的意义不是「比云端好听」，
 * 而是「断网时不必退回上个版本的声音」。
 *
 * ## 为什么模型放 assets 再拷到 filesDir
 *
 * sherpa-onnx 的 matcha 前端要读 `lexicon.txt`、`dict/`（jieba 词典）这类**目录**，
 * 而 AssetManager 不能列目录、也不能给原生层一个可 seek 的 fd 集合。
 * 直接 `newFromAsset` 只对单文件模型稳。所以首次使用时整目录拷到 filesDir，
 * 之后一律走文件路径。代价是 72MB 空间翻倍，换来的是「装完就能用、断网也能用」。
 *
 * ## 为什么用 Mutex 而不是每次 new 一个 OfflineTts
 *
 * 构造要加载 72MB 权重（真机实测 300–800ms，视存储速度）。每句重建等于每句都卡一下。
 * 但 `generate` 不是线程安全的，共享实例就必须串行——串行也正好符合「一次只说一句」。
 */
class LocalVoiceEngine(private val context: Context) {

    private val tag = "Voice"

    private val mutex = Mutex()

    @Volatile
    private var tts: OfflineTts? = null

    /**
     * 模型是否可用：已展开 **或** 包里带着（首次点击时才展开）。
     *
     * 后半个条件不是放宽，是修死锁：展开只发生在 [synthesize] 里，而 synthesize 要
     * 先被回落链选中；链头筛选用这个函数。只看 filesDir 的话，首次安装永远是「未就绪」，
     * 于是永远走不到展开那一步——包里有 72MB 模型，用户听到的是静音。
     */
    fun isReady(): Boolean {
        val dir = modelDir()
        if (File(dir, ACOUSTIC_MODEL).length() > 0 && File(dir, VOCODER).length() > 0) return true
        return TtsAssetInstaller.availableInAssets(
            context, ASSET_ROOT, listOf(ACOUSTIC_MODEL, VOCODER, "lexicon.txt"),
        )
    }

    /**
     * 合成一段，返回 16-bit 单声道 PCM。
     *
     * 失败抛异常而不是返回 null：调用方（[VoiceSpeech]）要用**哪一种失败**决定
     * 记什么日志、要不要继续往下退，`null` 会把「模型没装」和「推理崩了」抹平。
     */
    suspend fun synthesize(text: String, speed: Float): AudioClip = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!isReady()) {
                // 首次使用：从 assets 展开。放在锁里做，避免两句话同时展开同一批文件。
                extractAssets()
            }
            val engine = tts ?: build().also { tts = it }
            val started = System.currentTimeMillis()
            val audio = engine.generate(text, sid = SPEAKER_ID, speed = speed.coerceIn(0.5f, 2.0f))
            val elapsed = System.currentTimeMillis() - started
            val samples = audio.samples
            if (samples.isEmpty()) throw IllegalStateException("本地模型返回空音频")
            val rate = engine.sampleRate()
            AgentLogger.i(
                tag,
                "本地合成完成：${text.length} 字 → ${samples.size * 1000 / maxOf(rate, 1)}ms 音频，耗时 ${elapsed}ms"
            )
            AudioClip(pcm = toPcm16(samples), sampleRate = rate)
        }
    }

    /** 释放原生资源（[com.beichen.polaris.audio.AudioGraph.reset] 时调用）。 */
    fun release() {
        val e = tts ?: return
        tts = null
        runCatching { e.free() }
    }

    private fun build(): OfflineTts {
        val dir = modelDir()
        val ruleFsts = listOf("date.fst", "number.fst")
            .map { File(dir, it) }
            .filter { it.length() > 0 }
            .joinToString(",") { it.absolutePath }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = File(dir, ACOUSTIC_MODEL).absolutePath,
                    vocoder = File(dir, VOCODER).absolutePath,
                    lexicon = File(dir, "lexicon.txt").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dictDir = File(dir, "dict").absolutePath,
                    dataDir = dir.absolutePath,
                ),
                // 2 线程是 sherpa 官方示例的默认值：再多在手机上收益递减，
                // 反而和前台 UI 抢核，桌宠动画会掉帧。
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            ruleFsts = ruleFsts,
            maxNumSentences = 1,
            silenceScale = 0.2f,
        )
        AgentLogger.i(tag, "本地 TTS 引擎初始化：$dir")
        return OfflineTts(config = config)
    }

    /**
     * 首次使用时把 assets 里的模型展开到 filesDir。
     *
     * 展开细节（逐文件比长度、能续传）在 [TtsAssetInstaller]：克隆音色那边要展开另一套
     * 模型，逻辑一模一样。两份各写一遍，迟早有一边忘了比长度。
     */
    private fun extractAssets() {
        TtsAssetInstaller.expand(context, ASSET_ROOT, modelDir())
    }

    private fun modelDir(): File = TtsAssetInstaller.destDir(context, "matcha")

    private fun toPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[i] = v.toShort()
        }
        return out
    }

    private companion object {
        /** assets 下的模型根目录。改这里必须同步 `app/src/main/assets/tts/matcha`。 */
        const val ASSET_ROOT = "tts/matcha"
        const val ACOUSTIC_MODEL = "model-steps-3.onnx"
        const val VOCODER = "vocos-22khz-univ.onnx"

        /** matcha-icefall-zh-baker 是单说话人模型，sid 只能是 0。 */
        const val SPEAKER_ID = 0
    }
}
