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

    /** 本进程内是否已确认模型落盘。展开是幂等的，但没必要每次合成都遍历一遍目录。 */
    @Volatile
    private var expanded = false

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
            // 无条件保证落盘。**不能写成 `if (!isReady())`**：isReady 现在把「包里有」
            // 也算就位（为了不自锁），那条件在首次安装时恒为 false —— 判定说就绪、
            // 展开永不发生、磁盘上始终是空的，原生层 2ms 后回一句
            // `Invalid OfflineTtsConfig`，而日志里连一行「模型展开」都不会有。
            // 736 就是这么挂的。放在锁里做，避免两句话同时展开同一批文件。
            ensureExpanded()
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
        val required = listOf(
            ACOUSTIC_MODEL, VOCODER, "lexicon.txt", "tokens.txt", "date.fst", "number.fst",
        )
        AgentLogger.i(
            tag,
            "本地模型体检：${required.joinToString(", ") { "$it=${File(dir, it).length()}" }}",
        )
        val missing = required.filter { File(dir, it).length() <= 0 }
        val dictOk = File(dir, "dict").let { it.isDirectory && it.list()?.isNotEmpty() == true }
        if (missing.isNotEmpty() || !dictOk) {
            throw IllegalStateException(
                "模型文件缺失：$missing" + if (dictOk) "" else " + dict 目录为空" +
                    "（目录 ${dir.absolutePath}）"
            )
        }
    }

    /**
     * 保证 `filesDir/tts/matcha` 里文件齐。
     *
     * 与 [isReady] 分得很清：[isReady] 回答「这个音色能不能用」（包里有就算能用，
     * 否则回落链会把它跳过）；这里回答「磁盘上现在有没有」（没有就去拷）。
     * 把两件事合成一件，就是 736 的自锁。
     *
     * 幂等：逐文件比长度，齐了就 0 拷贝，只花目录遍历的时间。
     */
    private fun ensureExpanded() {
        if (expanded) return
        val dir = modelDir()
        val copied = TtsAssetInstaller.expand(context, ASSET_ROOT, dir)
        AgentLogger.i(tag, "本地模型就位：${dir.absolutePath}（本次补拷 $copied 个文件）")
        expanded = true
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
