package com.beichen.polaris.audio.asr

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * silero VAD 的唯一入口。
 *
 * ## 它解决的是什么
 *
 * 语音端点判定原来只有时间域的 RMS 判据（[com.beichen.polaris.core.voice.SpeechPresence.Tracker]）。
 * 那条路的天花板写死在 `MAX_GATE`：底噪一旦超过 0.027，门限被压到 0.08，
 * 而人在嘈杂环境里说话的电平恰好落在 0.06–0.08 —— 地铁里说一句话，
 * 系统会认为「没人说话」，于是要么不自动停，要么整段录音被丢掉。
 *
 * silero 看的是**频谱形状**而不是音量：空调、风扇、地铁轰鸣的频谱是稳态的，
 * 人说话有共振峰和音节起伏。所以它能在「噪声比人声响」的情况下正确分出人声。
 *
 * ## 为什么这个类必须薄
 *
 * 真正的判定逻辑在 [com.beichen.polaris.core.voice.SpeechPresence.VadEndpoint]（纯 Kotlin、
 * JVM 可测）。这里只做三件事：加载模型、喂样本、把异常翻译成「不可用」。
 * 一旦把阈值逻辑写进来，它就又变成只能靠真机验的东西了。
 *
 * ## 降级是硬要求
 *
 * 模型缺失、so 不匹配、ONNX 推理崩溃——任何一种情况都不能让麦克风不可用。
 * 所以 [push] 失败返回 null，上层立刻回落到 RMS 判据：行为退回 1.840，
 * 也就是「安静环境够用、地铁里可能不停」，但**绝不会把用户的录音弄丢**。
 */
class SileroVad private constructor(private val vad: Vad) {

    /** 一旦推理抛过异常就不再尝试——反复炸一个已知会炸的调用只会拖慢录音线程。 */
    @Volatile
    private var broken = false

    /**
     * 喂一块音频并回答「这一块是不是人声」。
     *
     * @return true/false 正常判定；**null = 这个实例已经不可用**，调用方应立即降级
     */
    fun push(samples: FloatArray): Boolean? {
        if (broken || samples.isEmpty()) return if (broken) null else false
        return runCatching {
            vad.acceptWaveform(samples)
            val speech = vad.isSpeechDetected()
            // 我们不用它切出来的段（音频自己攒着），但要清空队列——
            // 不清的话内部会按 maxSpeechDuration 一段段堆着，30 秒录音约 1.9MB。
            while (!vad.empty()) vad.pop()
            speech
        }.getOrElse {
            broken = true
            AgentLogger.w(TAG, "VAD 推理失败，本会话回落 RMS 判据：${it.message}")
            null
        }
    }

    /** 复位，供下一条录音复用（ONNX 会话很贵，不值得每句重建）。 */
    fun reset() {
        if (broken) return
        runCatching {
            vad.reset()
            while (!vad.empty()) vad.pop()
        }
    }

    fun release() {
        runCatching { vad.release() }
    }

    companion object {
        private const val TAG = "SileroVad"

        /** 与录音链路一致（[LocalSpeechInput] 按 16k 采集）。 */
        const val SAMPLE_RATE = 16_000

        /** 放在 `app/src/main/assets/` 下的文件名；`androidResources.noCompress` 已含 onnx。 */
        const val MODEL = "silero_vad.onnx"

        /**
         * 一段最长 30 秒，与 `LocalSpeechInput.MAX_MS` 对齐。
         *
         * 官方默认 5 秒，但那会在长句中途强制切段、把 `isSpeechDetected()` 打断，
         * 表现为「说到第 5 秒被判说完」。上限本来就在录音层，这里不该更早。
         */
        private const val MAX_SPEECH_S = 30f

        /**
         * 构造实例。失败返回 null（模型缺失 / so 不匹配 / 内存不足），**不抛给调用方**。
         *
         * 用 applicationContext 的 AssetManager：native 侧按文件名从 assets 读，
         * 不接受绝对路径——所以模型必须放在 assets 里，不能像 paraformer 那样放 filesDir。
         */
        fun create(context: Context): SileroVad? = runCatching {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = MODEL,
                    // 0.5 是 silero 的官方默认。调到 0.6 会减少误报但漏掉轻声说话，
                    // 只有在真机确认「嘈杂环境下误触发」时才往上调。
                    threshold = 0.5F,
                    // 模型内部迟滞：连续 250ms 静音才翻 false。VadEndpoint 再加 500ms。
                    minSilenceDuration = 0.25F,
                    // 与 SpeechPresence.MIN_SPEECH_MS 对齐：短于 200ms 的声音不当作一句。
                    minSpeechDuration = 0.20F,
                    windowSize = 512,
                    maxSpeechDuration = MAX_SPEECH_S,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            )
            val t0 = System.currentTimeMillis()
            val vad = Vad(assetManager = context.applicationContext.assets, config = config)
            AgentLogger.i(TAG, "silero VAD 就绪（加载 ${System.currentTimeMillis() - t0}ms）")
            SileroVad(vad)
        }.onFailure {
            AgentLogger.w(TAG, "silero VAD 不可用，端点判定回落 RMS：${it.message}")
        }.getOrNull()
    }
}
