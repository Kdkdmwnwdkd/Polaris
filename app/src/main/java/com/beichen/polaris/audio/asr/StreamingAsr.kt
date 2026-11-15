package com.beichen.polaris.audio.asr

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * 内置离线中文识别（流式 zipformer）。
 *
 * ## 为什么必须有它
 *
 * 语音输入原来完全交给系统 `SpeechRecognizer`。在国行机上这条路经常整条是死的：
 * 2026-11-15 的真机日志里，两个识别服务一个都不可用——
 *
 * ```
 * com.google.android.tts/…GoogleTTSRecognitionService   查询语言支持报错：14（按未应答处理）
 * com.qualcomm.qti.voiceai.speech/…SpeechRecognitionService  离线已装=[]，可离线=[]，在线=[]
 * ```
 *
 * Google 那个在国内本来就不可达；高通那个明确「不支持当前语言」，也没有中文离线包。
 * 于是「点麦克风 → 没有任何字」——不是我们的代码崩了，是这台机器上根本没有能干活的服务。
 *
 * 所以识别必须能自带。选型是 sherpa-onnx 的**流式中文 zipformer（14M 参数，int8 约 24MB）**：
 * 边说边出字、纯 CPU、不联网，`.so` 本来就在包里（TTS 用的是同一套 JNI），
 * 不需要额外编 NDK。
 *
 * ## 模型文件不写死文件名
 *
 * 上游这几个模型的文件名带着训练信息（`encoder-epoch-99-avg-1.int8.onnx`），
 * 换一个版本就变。硬编码的结果是「升级模型 → 运行时报文件不存在 → 用户以为功能坏了」。
 * 这里按前缀找（`encoder*.onnx` 等），并在有 int8 版时优先用它。
 *
 * ## 线程模型
 *
 * [OnlineRecognizer] 的 decode 是**阻塞 CPU 计算**，一个实例同一时刻只能被一个流用。
 * 所以这里只缓存 recognizer（模型加载 200–500ms，每次录音都重载太浪费），
 * 而 [Session] 每次新建——两次录音不会重叠，因为上层 [LocalSpeechInput] 是单例且串行。
 */
class StreamingAsr(context: Context) : AsrEngine {

    private val tag = "Asr"

    /** 模型所在目录。下载器往这里写，删模型就是删这个目录。 */
    val modelDir: File = File(File(context.filesDir, "asr"), "zh-streaming")

    override val dir: File get() = modelDir

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var loaded: Files? = null

    /** 模型四个文件。 */
    private class Files(
        val encoder: File,
        val decoder: File,
        val joiner: File,
        val tokens: File,
    )

    /**
     * 模型是否就位。
     *
     * 只看文件在不在、非空——**不建识别器**。建一次要几百毫秒，界面每帧问一次会卡。
     * 真正能不能跑由 [ensureSession] 的结果说了算（它失败会把原因写进日志）。
     */
    override fun isInstalled(): Boolean = files() != null

    /** 给界面看的模型体积（字节）。没装返回 0。 */
    override fun sizeBytes(): Long = if (isInstalled()) {
        modelDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    } else 0L

    /**
     * 建一个会话。
     *
     * 失败返回 null 并记日志——调用方据此回落到系统识别，而不是让用户对着麦克风干等。
     */
    @Synchronized
    override fun ensureSession(): Session? {
        val f = files()
        if (f == null) {
            AgentLogger.w(tag, "离线识别模型不完整：${modelDir.absolutePath}（${describeDir()}）")
            return null
        }
        val r = recognizer ?: run {
            val t0 = System.currentTimeMillis()
            val built = runCatching { build(f) }
                .onFailure { AgentLogger.w(tag, "识别器创建失败：${it.message}") }
                .getOrNull()
                ?: return null
            AgentLogger.i(
                tag,
                "离线识别就绪：${f.encoder.name} + ${f.joiner.name}（加载 ${System.currentTimeMillis() - t0}ms）",
            )
            loaded = f
            built.also { recognizer = it }
        }
        // 模型被换过（下载了新版本）时旧 recognizer 还在用老文件，重建一次。
        if (loaded?.encoder?.absolutePath != f.encoder.absolutePath) {
            runCatching { recognizer?.release() }
            recognizer = null
            return ensureSession()
        }
        return runCatching { Session(r, r.createStream()) }
            .onFailure { AgentLogger.w(tag, "创建识别流失败：${it.message}") }
            .getOrNull()
    }

    /** 识别的「一次按下到松手」。用完必须 [Session.close]。 */
    class Session internal constructor(
        private val recognizer: OnlineRecognizer,
        private val stream: OnlineStream,
    ) : AsrSession {

        /**
         * 喂一段 16kHz 单声道 PCM，返回**目前为止**的整句文本。
         *
         * 流式模型的返回值是「整句累计」，不是增量——直接拿去显示即可，不需要自己拼接。
         */
        override fun accept(samples: FloatArray, sampleRate: Int): String {
            stream.acceptWaveform(samples, sampleRate)
            drain()
            return recognizer.getResult(stream).text
        }

        /** 松手：补最后一帧并给出最终文本。 */
        override fun finish(): String {
            stream.inputFinished()
            drain()
            return recognizer.getResult(stream).text
        }

        /** 引擎判断这句已经说完了（尾部静音够长），可以替用户收尾。 */
        override fun isEndpoint(): Boolean = runCatching { recognizer.isEndpoint(stream) }.getOrDefault(false)

        override fun close() {
            runCatching { stream.release() }
        }

        private fun drain() {
            // 必须循环：一次 decode 只推进一个 chunk，攒了多帧时要全部消化掉，
            // 否则文本会滞后一到两拍（用户看到的是「说完半秒前的话」）。
            var guard = 0
            while (recognizer.isReady(stream) && guard++ < 200) recognizer.decode(stream)
        }
    }

    override fun release() {
        runCatching { recognizer?.release() }
        recognizer = null
        loaded = null
    }

    // —— 内部 ——

    private fun build(f: Files): OnlineRecognizer {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = f.encoder.absolutePath,
                    decoder = f.decoder.absolutePath,
                    joiner = f.joiner.absolutePath,
                ),
                tokens = f.tokens.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            // 端点规则：说完停顿 2.4 秒判停，或一口气说了 20 秒强制断句。
            // 桌宠场景「问一句就走」，不给它判停的话用户得自己再点一下。
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0.0f),
                rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.2f, minUtteranceLength = 0.2f),
                rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.0f, minUtteranceLength = 20.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return OnlineRecognizer(assetManager = null, config = config)
    }

    private fun files(): Files? {
        if (!modelDir.isDirectory) return null
        val all = modelDir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: return null
        fun pick(prefix: String): File? {
            val hit = all.filter { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            // int8 量化版优先：体积约三分之一，手机上快一倍，中文短句的准确率差别听不出来。
            return hit.firstOrNull { it.name.contains("int8") } ?: hit.minByOrNull { it.length() }
        }
        val enc = pick("encoder") ?: return null
        val dec = pick("decoder") ?: return null
        val joi = pick("joiner") ?: return null
        val tok = all.firstOrNull { it.name == "tokens.txt" } ?: return null
        return Files(enc, dec, joi, tok)
    }

    private fun describeDir(): String {
        val names = modelDir.listFiles()?.map { "${it.name}(${it.length()})" } ?: emptyList()
        return if (names.isEmpty()) "目录为空或不存在" else names.joinToString(", ")
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
