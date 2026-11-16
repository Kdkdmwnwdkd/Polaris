package com.beichen.polaris.audio.tts

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.model.ModelSpec
import com.beichen.polaris.model.ModelZip
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
 * ## 为什么模型不在 APK 里了
 *
 * 2026-11-16 起这套权重（139MB）改成按需下载：装包从 361MB 降到 30MB 上下。
 * 代价是「第一次点本地音色要先下 139MB」，收益是「不用它的人一个字节都不付」。
 * 下载与删除都在语音页里，删了随时能重下——与识别模型同一套机制
 * （[com.beichen.polaris.model.ModelDownloader]）。
 *
 * ## 为什么用 Mutex 而不是每次 new 一个 OfflineTts
 *
 * 构造要加载 139MB 权重（真机实测 300–800ms，视存储速度）。每句重建等于每句都卡一下。
 * 但 `generate` 不是线程安全的，共享实例就必须串行——串行也正好符合「一次只说一句」。
 */
class LocalVoiceEngine(private val context: Context) {

    private val tag = "Voice"

    private val mutex = Mutex()

    @Volatile
    private var tts: OfflineTts? = null

    /**
     * 模型是否可用：磁盘上必需文件齐全。
     *
     * 以前这里还查「包里带着」，因为展开动作写在 synthesize 里，而 synthesize
     * 要先被回落链选中——只看 filesDir 会自锁成「永远未就绪」。现在模型由下载器
     * 直接落到最终目录，没有「展开」这一步，那段自锁的前提也就不存在了。
     */
    fun isReady(): Boolean = ModelZip.missingIn(modelDir(), ModelSpec.TtsMatcha.required).isEmpty()

    /**
     * 合成一段，返回 16-bit 单声道 PCM。
     *
     * 失败抛异常而不是返回 null：调用方（[VoiceSpeech]）要用**哪一种失败**决定
     * 记什么日志、要不要继续往下退，`null` 会把「模型没装」和「推理崩了」抹平。
     */
    suspend fun synthesize(text: String, speed: Float): AudioClip = withContext(Dispatchers.IO) {
        mutex.withLock {
            // 没有「首次展开」这一步了：模型要么整包下好了、要么没下。
            // 没下时 isReady() 已经让回落链跳过这一档，走到这里只可能是文件被删了一半，
            // build() 里的体检会把缺的文件名说出来。
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
        // 必须先接管 stderr，再建引擎：sherpa 的 C++ 只在 stderr 里说真话，
        // 而 Android 上 fd 2 默认什么都不通向，那几行原本是直接消失的。
        NativeStderr.install()
        val dir = modelDir()
        checkFiles(dir)
        val ruleFsts = listOf("date.fst", "number.fst")
            .map { File(dir, it) }
            .filter { it.length() > 0 }
            .joinToString(",") { it.absolutePath }

        // 原生层拒绝配置时只回一句 `Invalid OfflineTtsConfig: failed to create native
        // OfflineTts`，不说是哪个字段。而 zipvoice 用同一套 JNI、同一批 .so 能成功，
        // 差别只在 matcha 独有的 lexicon / dictDir / dataDir / ruleFsts 上。
        //
        // 于是在包内一次试完四种组合：失败只要 1ms，成功的那个留下来。
        // 这不是「多试几次碰运气」，是把无法从 native 拿到的错误信息，
        // 转换成「哪一组参数能被接受」——结果会写进日志，下一版据此收敛成一条。
        val attempts = listOf(
            Attempt("dataDir=模型目录、带标点规则", dir.absolutePath, ruleFsts),
            Attempt("dataDir=空、带标点规则", "", ruleFsts),
            Attempt("dataDir=空、无标点规则", "", ""),
            Attempt("dataDir=模型目录、无标点规则", dir.absolutePath, ""),
        )
        var last: Exception? = null
        for ((index, a) in attempts.withIndex()) {
            // 每组单独清一次：失败时取到的原话必须属于这一组，不能混进上一组的输出。
            NativeStderr.clear()
            try {
                val tts = OfflineTts(config = configOf(dir, a.dataDir, a.ruleFsts))
                AgentLogger.i(tag, "本地 TTS 引擎就绪（${a.label}）")
                if (index > 0) {
                    AgentLogger.w(tag, "本地 TTS 退到第 ${index + 1} 组配置：${a.label}")
                }
                return tts
            } catch (e: Exception) {
                // 原生层只回一句「配置无效」，不说字段名。它真正的理由留在 stderr，
                // 这里必须原样带出来，否则又是「改一版猜一轮」。
                AgentLogger.w(tag, "本地 TTS 配置被拒（${a.label}）：${e.javaClass.simpleName} ${e.message}")
                AgentLogger.w(tag, "  └ 原生层原话：${NativeStderr.tail().ifBlank { "（未捕获到）" }}")
                last = e
            }
        }
        throw IllegalStateException(NativeStderr.withNative(last))
    }

    private class Attempt(val label: String, val dataDir: String, val ruleFsts: String)

    private fun configOf(dir: File, dataDir: String, ruleFsts: String) = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            matcha = OfflineTtsMatchaModelConfig(
                acousticModel = File(dir, ACOUSTIC_MODEL).absolutePath,
                vocoder = File(dir, VOCODER).absolutePath,
                lexicon = File(dir, "lexicon.txt").absolutePath,
                tokens = File(dir, "tokens.txt").absolutePath,
                dictDir = File(dir, "dict").absolutePath,
                dataDir = dataDir,
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

    /**
     * 初始化前把每个文件的字节数写进日志。
     *
     * 原生层的「找不到/读不了」只有一句没头没尾的报错，而磁盘上文件不完整
     * （拷到一半被杀、空间不足）时长度照样大于 0，只看存在与否查不出来。
     * 把长度摊开写，比对官方包就能立刻看出是哪个文件短了一截。
     */
    private fun checkFiles(dir: File) {
        // 必需清单只在 [ModelSpec] 里写一份：下载器按它判「装好了没」，
        // 这里按它判「读之前齐不齐」。两处各写一份，改模型时必然漏掉一处。
        val required = ModelSpec.TtsMatcha.required.filter { it != "dict" }
        AgentLogger.i(
            tag,
            "本地模型体检：${required.joinToString(", ") { "$it=${File(dir, it).length()}" }}",
        )
        val missing = ModelZip.missingIn(dir, ModelSpec.TtsMatcha.required)
        if (missing.isNotEmpty()) {
            throw IllegalStateException("模型文件缺失：$missing（目录 ${dir.absolutePath}）")
        }
    }

    /**
     * 模型目录。与 [ModelSpec.TtsMatcha] 的 `home/dirName` 必须是同一个路径——
     * 下载器往哪儿解、引擎从哪儿读，只能有一个答案。
     */
    private fun modelDir(): File = File(File(context.filesDir, "tts"), DIR_NAME)

    private fun toPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[i] = v.toShort()
        }
        return out
    }

    private companion object {
        const val DIR_NAME = "matcha"
        const val ACOUSTIC_MODEL = "model-steps-3.onnx"
        const val VOCODER = "vocos-22khz-univ.onnx"

        /** matcha-icefall-zh-baker 是单说话人模型，sid 只能是 0。 */
        const val SPEAKER_ID = 0
    }
}
