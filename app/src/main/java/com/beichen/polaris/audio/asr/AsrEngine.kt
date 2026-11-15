package com.beichen.polaris.audio.asr

import java.io.File

/**
 * 一次识别的「会话」。
 *
 * 两种引擎（[StreamingAsr] 边听边出字、[OfflineAsrEngine] 说完再认）在录音循环里
 * 的用法完全一样：喂 PCM、问文本、收尾。差别只在 `accept` 内部——
 * 一个真的在解码，一个只是把音频攒起来。所以 [LocalSpeechInput] 不需要知道用的是哪个。
 */
interface AsrSession {

    /** 喂一段 16kHz 单声道 PCM，返回**目前为止**的整句文本。 */
    fun accept(samples: FloatArray, sampleRate: Int = 16_000): String

    /** 引擎自己判断「这句说完了」。整段识别永远返回 false——它只在收尾时认一次。 */
    fun isEndpoint(): Boolean

    /** 松手：给出最终文本。 */
    fun finish(): String

    fun close()
}

/**
 * 一个离线识别引擎（一套模型 + 一种解码方式）。
 *
 * 为什么抽象而不是只有一个实现：这两种模型是**互补**的——
 * 14M 流式模型边说边出字、占 24MB，准确率一般；
 * paraformer 整段模型要等说完、占 74MB，但错字明显少。
 * 用户按自己的耐心和存储选，路由（[AsrRouter]）只负责挑一个能用的。
 */
interface AsrEngine {

    /** 模型目录。下载器往这里写，删模型就是删这个目录。 */
    val dir: File

    /** 文件齐不齐（只看存在与长度，不建识别器——那要几百毫秒）。 */
    fun isInstalled(): Boolean

    fun sizeBytes(): Long

    /** 建一个会话；失败返回 null 并记日志。 */
    fun ensureSession(): AsrSession?

    /** 释放原生句柄。删模型、切模型前必须先调。 */
    fun release()
}
