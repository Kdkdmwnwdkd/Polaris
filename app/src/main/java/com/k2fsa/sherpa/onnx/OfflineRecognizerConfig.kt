// 本文件是 sherpa-onnx 官方 v1.13.8 的 kotlin-api/OfflineRecognizer.kt 前 179 行原样拆分。
//
// 为什么拆：官方把「模型配置 data class」和「识别器实现」放在同一个文件里，共 1512 行，
// 超过本项目单文件 1500 行的上限。拆分只按原文件的自然分界做（配置 / 实现），
// 一个字节都没有改——将来跟上游对版本时，把两个文件拼回去即可逐字比对。
package com.k2fsa.sherpa.onnx

data class OfflineRecognizerResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
    val lang: String,
    val emotion: String,
    val event: String,

    // valid only for TDT models
    val durations: FloatArray,
)

data class OfflineTransducerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var joiner: String = "",
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineParaformerModelConfig(
    var model: String = "",
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineNemoEncDecCtcModelConfig(
    var model: String = "",
)

data class OfflineDolphinModelConfig(
    var model: String = "",
)

data class OfflineZipformerCtcModelConfig(
    var model: String = "",
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineWenetCtcModelConfig(
    var model: String = "",
)

data class OfflineOmnilingualAsrCtcModelConfig(
    var model: String = "",
)

data class OfflineMedAsrCtcModelConfig(
    var model: String = "",
)

data class OfflineFireRedAsrCtcModelConfig(
    var model: String = "",
)

data class OfflineFunAsrNanoModelConfig(
    var encoderAdaptor: String = "",
    var llm: String = "",
    var embedding: String = "",
    var tokenizer: String = "",
    var systemPrompt: String = "You are a helpful assistant.",
    var userPrompt: String = "语音转写：",
    var maxNewTokens: Int = 512,
    var temperature: Float = 1e-6f,
    var topP: Float = 0.8f,
    var seed: Int = 42,
    var language: String = "",
    var itn: Boolean = true,
    var hotwords: String = "",
)

data class OfflineQwen3AsrModelConfig(
    var convFrontend: String = "",
    var encoder: String = "",
    var decoder: String = "",
    var tokenizer: String = "",
    var maxTotalLen: Int = 512,
    var maxNewTokens: Int = 128,
    var temperature: Float = 1e-6f,
    var topP: Float = 0.8f,
    var seed: Int = 42,
    var hotwords: String = "",
)

data class OfflineWhisperModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var language: String = "en", // Used with multilingual model
    var task: String = "transcribe", // transcribe or translate
    var tailPaddings: Int = 1000, // Padding added at the end of the samples
    var enableTokenTimestamps: Boolean = false,
    var enableSegmentTimestamps: Boolean = false,
)

data class OfflineCanaryModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var srcLang: String = "en",
    var tgtLang: String = "en",
    var usePnc: Boolean = true,
)

data class OfflineCohereTranscribeModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var language: String = "",
    var usePunct: Boolean = true,
    var useItn: Boolean = true,
)

data class OfflineFireRedAsrModelConfig(
    var encoder: String = "",
    var decoder: String = "",
)

// For moonshine v1, you need four models.
// For moonshine v2, you need two models.
// - v1: preprocessor, encoder, uncachedDecoder, cachedDecoder
// - v2: encoder, mergedDecoder
data class OfflineMoonshineModelConfig(
    var preprocessor: String = "",
    var encoder: String = "",
    var uncachedDecoder: String = "",
    var cachedDecoder: String = "",
    var mergedDecoder: String = "",
)

data class OfflineSenseVoiceModelConfig(
    var model: String = "",
    var language: String = "",
    var useInverseTextNormalization: Boolean = true,
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineModelConfig(
    var transducer: OfflineTransducerModelConfig = OfflineTransducerModelConfig(),
    var paraformer: OfflineParaformerModelConfig = OfflineParaformerModelConfig(),
    var whisper: OfflineWhisperModelConfig = OfflineWhisperModelConfig(),
    var fireRedAsr: OfflineFireRedAsrModelConfig = OfflineFireRedAsrModelConfig(),
    var moonshine: OfflineMoonshineModelConfig = OfflineMoonshineModelConfig(),
    var nemo: OfflineNemoEncDecCtcModelConfig = OfflineNemoEncDecCtcModelConfig(),
    var senseVoice: OfflineSenseVoiceModelConfig = OfflineSenseVoiceModelConfig(),
    var dolphin: OfflineDolphinModelConfig = OfflineDolphinModelConfig(),
    var zipformerCtc: OfflineZipformerCtcModelConfig = OfflineZipformerCtcModelConfig(),
    var wenetCtc: OfflineWenetCtcModelConfig = OfflineWenetCtcModelConfig(),
    var omnilingual: OfflineOmnilingualAsrCtcModelConfig = OfflineOmnilingualAsrCtcModelConfig(),
    var medasr: OfflineMedAsrCtcModelConfig = OfflineMedAsrCtcModelConfig(),
    var funasrNano: OfflineFunAsrNanoModelConfig = OfflineFunAsrNanoModelConfig(),
    var qwen3Asr: OfflineQwen3AsrModelConfig = OfflineQwen3AsrModelConfig(),
    var fireRedAsrCtc: OfflineFireRedAsrCtcModelConfig = OfflineFireRedAsrCtcModelConfig(),
    var canary: OfflineCanaryModelConfig = OfflineCanaryModelConfig(),
    var cohereTranscribe: OfflineCohereTranscribeModelConfig =
        OfflineCohereTranscribeModelConfig(),
    var teleSpeech: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var tokens: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = "",
)

data class OfflineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    // var lmConfig: OfflineLMConfig(), // TODO(fangjun): enable it
    var hr: HomophoneReplacerConfig = HomophoneReplacerConfig(),
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)

