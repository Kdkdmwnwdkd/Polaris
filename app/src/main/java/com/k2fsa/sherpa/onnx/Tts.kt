// Copyright (c) 2023 Xiaomi Corporation
//
// 本文件取自 sherpa-onnx v1.12.4 的 `sherpa-onnx/kotlin-api/Tts.kt`（Apache-2.0），
// 未做逻辑修改，仅保留本项目用到的部分。原生实现由 scripts/qa/fetch-tts-assets.py
// 放进 app/src/main/jniLibs/{arm64-v8a,x86_64}/libsherpa-onnx-jni.so。
// 许可与来源见 THIRD_PARTY_NOTICES.md。

package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflineTtsVitsModelConfig(
    var model: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsMatchaModelConfig(
    var acousticModel: String = "",
    var vocoder: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var noiseScale: Float = 1.0f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsKokoroModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var lexicon: String = "",
    var lang: String = "",
    var dictDir: String = "",
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    var matcha: OfflineTtsMatchaModelConfig = OfflineTtsMatchaModelConfig(),
    var kokoro: OfflineTtsKokoroModelConfig = OfflineTtsKokoroModelConfig(),
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var maxNumSentences: Int = 1,
    var silenceScale: Float = 0.2f,
)

class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
)

class OfflineTts(
    assetManager: AssetManager? = null,
    var config: OfflineTtsConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) newFromAsset(assetManager, config) else newFromFile(config)
    }

    fun sampleRate() = getSampleRate(ptr)

    fun numSpeakers() = getNumSpeakers(ptr)

    fun generate(text: String, sid: Int = 0, speed: Float = 1.0f): GeneratedAudio {
        val objArray = generateImpl(ptr, text = text, sid = sid, speed = speed)
        return GeneratedAudio(samples = objArray[0] as FloatArray, sampleRate = objArray[1] as Int)
    }

    fun free() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    private external fun newFromAsset(assetManager: AssetManager, config: OfflineTtsConfig): Long

    private external fun newFromFile(config: OfflineTtsConfig): Long

    private external fun delete(ptr: Long)

    private external fun getSampleRate(ptr: Long): Int

    private external fun getNumSpeakers(ptr: Long): Int

    // 返回两元素数组：[0] 是 [-1,1] 的 float 采样，[1] 是采样率
    private external fun generateImpl(ptr: Long, text: String, sid: Int, speed: Float): Array<Any>

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
