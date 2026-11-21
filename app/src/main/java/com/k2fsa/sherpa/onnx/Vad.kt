// Copyright (c) 2023 Xiaomi Corporation
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

/**
 * silero VAD 的 Kotlin 绑定。
 *
 * ## 为什么是手抄一份而不是加依赖
 *
 * 本仓库的 sherpa 绑定（`OfflineRecognizer` / `Tts` 等 9 个文件）本来就是内嵌源码，
 * native 侧是 `libsherpa-onnx-jni.so` 里已编好的符号。VAD 那组符号一直在里面
 * （`Java_com_k2fsa_sherpa_onnx_Vad_*` 共 12 个），只是一直没人写 Kotlin 这半边。
 *
 * 所以这里没有引入任何新依赖、没有重编 so、APK 只多一个 629KB 的模型。
 *
 * ## 字段名不能改
 *
 * JNI 侧用 `GetFieldID` 按**名字和签名**取字段。改一个字段名，native 就取不到，
 * 表现为 `newFromAsset` 返回 0（构造失败）或字段永远是默认值——不报错，只是行为
 * 不对。所以这份 data class 的字段名、顺序、类型必须与 sherpa-onnx **v1.13.8**
 * （本仓库 so 的实际版本，从 so 的字符串里读出来的）严格一致。
 *
 * 官方源码：`sherpa-onnx/kotlin-api/Vad.kt` @ v1.13.8。
 */

data class SileroVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F,
    var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 512,
    var maxSpeechDuration: Float = 5.0F,
)

/**
 * ten-vad 的配置。
 *
 * 本项目不用它（silero 629KB、ten-vad 更大且中文场景没有明显优势），
 * 但 [VadModelConfig] 里必须有这个字段——它是同一个 struct 的一部分，
 * 缺了它 JNI 取 `tenVadModelConfig` 就会失败。
 */
data class TenVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F,
    var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 256,
    var maxSpeechDuration: Float = 5.0F,
)

data class VadModelConfig(
    var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var tenVadModelConfig: TenVadModelConfig = TenVadModelConfig(),
    var sampleRate: Int = 16000,
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false,
)

/** 一个语音段。本项目的用法只读 [Vad.isSpeechDetected]，不消费它，但签名要对得上。 */
class SpeechSegment(val start: Int, val samples: FloatArray)

class Vad(
    assetManager: AssetManager? = null,
    var config: VadModelConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        require(ptr != 0L) {
            "Invalid VadModelConfig: failed to create native Vad"
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    /** 单帧人声概率（0~1）。本项目不用——它要自己维护帧边界，容易和 windowSize 对不上。 */
    fun compute(samples: FloatArray): Float = compute(ptr, samples)

    /** 喂音频。内部按 [SileroVadModelConfig.windowSize] 攒帧，喂多喂少都可以。 */
    fun acceptWaveform(samples: FloatArray) = acceptWaveform(ptr, samples)

    /** 已切出的语音段是否为空。 */
    fun empty(): Boolean = empty(ptr)

    /** 丢掉最早的那个语音段。 */
    fun pop() = pop(ptr)

    /** 最早的那个语音段。 */
    fun front(): SpeechSegment = front(ptr)

    /** 丢掉所有段（不影响判定状态）。 */
    fun clear() = clear(ptr)

    /**
     * 当前是否处在「人声」状态。
     *
     * 带迟滞：连说话音满 [SileroVadModelConfig.minSpeechDuration] 才翻 true，
     * 连续静音满 [SileroVadModelConfig.minSilenceDuration] 才翻 false。
     * 本项目就是靠这个迟滞省掉了一层自己的去抖。
     */
    fun isSpeechDetected(): Boolean = isSpeechDetected(ptr)

    /** 复位到初始状态（清空内部缓存与状态机）。 */
    fun reset() = reset(ptr)

    /** 收尾：把内部残留的不足一帧的样本喂掉并强制输出尾段。 */
    fun flush() = flush(ptr)

    private external fun delete(ptr: Long)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: VadModelConfig,
    ): Long

    private external fun newFromFile(config: VadModelConfig): Long

    private external fun acceptWaveform(ptr: Long, samples: FloatArray)
    private external fun compute(ptr: Long, samples: FloatArray): Float

    private external fun empty(ptr: Long): Boolean
    private external fun pop(ptr: Long)
    private external fun clear(ptr: Long)
    private external fun front(ptr: Long): SpeechSegment
    private external fun isSpeechDetected(ptr: Long): Boolean
    private external fun reset(ptr: Long)
    private external fun flush(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
