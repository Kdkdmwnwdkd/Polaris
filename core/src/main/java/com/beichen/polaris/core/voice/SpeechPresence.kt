package com.beichen.polaris.core.voice

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 「这段录音里到底有没有人说话」。
 *
 * ## 为什么必须单独判一次
 *
 * 2026-11-21 真机实测：用户**一个字没说**、只是按了一下麦克风，离线识别吐出了 3 字；
 * 连续四次空按，得到 3 / 3 / 4 / 0 字。那不是识别不准，是模型在给底噪编词。
 *
 * 两个因素叠在一起才让它发生：
 *
 * 1. 整段识别（`OfflineAsrEngine`）收尾时会把峰值拉到 0.9——本意是救轻声说话，
 *    可静音的峰值只有 0.003 左右，这一拉等于把底噪放大 300 倍，送到模型面前
 *    就是一段「很响的音频」；
 * 2. paraformer 这类模型对任何非静音输入都会给出一串最可能的汉字，不会说「没人说话」。
 *
 * 所以判定必须发生在**放大之前**。顺序反了，判定看到的就是被自己放大的噪声，
 * 阈值再准也没意义。
 *
 * ## 为什么不用固定的一个数字
 *
 * 手机麦克风的底噪横跨三个数量级：安静卧室约 0.0005，地铁里可能 0.02。
 * 一个写死的门限，要么在安静环境放过底噪，要么在嘈杂环境吃掉轻声说话。
 * 这里取「全段帧能量的低分位」当底噪估计，再乘一个余量；同时保留一个绝对下限，
 * 免得全静音时低分位本身就是 0（0 乘任何数还是 0）。
 *
 * 反过来的风险是「整段都在说话、没有静音可采样」——那低分位会落在字与字的缝隙，
 * 乘 3 之后仍低于正常语音电平；再加一道 [MAX_GATE] 封顶，防止极端情况下把真人声全判掉。
 *
 * ## 为什么放 core
 *
 * 纯 Kotlin、不碰 Android API，可以进 JVM 单测。这个判定的每一条阈值都值得被钉住——
 * 它错一次，用户的体验是「我没说话它自己发了一句」。
 */
object SpeechPresence {

    /** 分帧长度。20ms 是语音处理的常规粒度：足够短到不漏掉短音，足够长到不吃进 VAD 抖动。 */
    const val FRAME_MS = 20

    /** 累计有声时长低于这个值就算「没人说话」。"嗯"一声大约 300ms，200ms 不会误杀。 */
    const val MIN_SPEECH_MS = 200

    /**
     * 绝对门限（帧 RMS）。0.006 ≈ -44 dBFS。
     *
     * 真人对着手机说话，即便很轻也在 0.02 以上；底噪基本在 0.005 以下。
     * 取这个值是为了兜住「低分位估计失效」的情况（整段纯静音、或低分位恰好为 0）。
     */
    const val FLOOR_RMS = 0.006f

    /** 底噪估计的余量：超过底噪三倍才算人声，挡掉空调、风扇这类平稳噪声。 */
    const val NOISE_MARGIN = 3f

    /** 门限封顶。防止「整段都在说话」时低分位把门限抬到人声之上。 */
    const val MAX_GATE = 0.08f

    /** 估计底噪时取哪一档分位。取低值是因为说话帧通常占多数，低分位更接近真实底噪。 */
    private const val NOISE_PERCENTILE = 0.10

    /**
     * 一段 PCM 里累计有多少毫秒像人声。
     *
     * @param pcm 单声道浮点样本，取值范围 [-1, 1]
     * @param sampleRate 采样率（本应用固定 16 kHz）
     */
    fun voicedMillis(pcm: FloatArray, sampleRate: Int): Int {
        val frame = sampleRate * FRAME_MS / 1000
        if (frame <= 0 || pcm.size < frame) return 0
        val frames = pcm.size / frame
        val rms = FloatArray(frames) { i ->
            var sum = 0.0
            val base = i * frame
            for (j in base until base + frame) {
                val v = pcm[j].toDouble()
                sum += v * v
            }
            sqrt(sum / frame).toFloat()
        }
        return voicedMillisOfFrames(rms, FRAME_MS)
    }

    /**
     * 已经是逐帧 RMS 时走这个重载（流式识别不缓存 PCM，只留每块的 RMS）。
     *
     * @param frameRms 每帧的 RMS，帧长由调用方决定，必须与 [frameMs] 一致
     */
    fun voicedMillisOfFrames(frameRms: FloatArray, frameMs: Int): Int {
        if (frameRms.isEmpty() || frameMs <= 0) return 0
        val gate = gateOf(frameRms)
        var voiced = 0
        for (v in frameRms) if (v >= gate) voiced += frameMs
        return voiced
    }

    /** 这段 PCM 里有没有人说话。 */
    fun hasSpeech(pcm: FloatArray, sampleRate: Int): Boolean =
        voicedMillis(pcm, sampleRate) >= MIN_SPEECH_MS

    /**
     * 已经是逐帧 RMS 时用这个。
     *
     * **不要和 [hasSpeech] 混用**：两个都是 `(FloatArray, Int)`，编译器分不出
     * 「PCM + 采样率」和「帧 RMS + 帧长」。2026-11-21 第一版就叫 `hasSpeech(帧, 帧长)`，
     * 测试把 PCM 连采样率一起传进去，16000 被当成帧长、每个样本被当成一帧，
     * 于是一段敲桌子的噪声被判成「说了 8 秒话」。名字分开是唯一可靠的防呆。
     */
    fun hasSpeechInFrames(frameRms: FloatArray, frameMs: Int = FRAME_MS): Boolean =
        voicedMillisOfFrames(frameRms, frameMs) >= MIN_SPEECH_MS

    /**
     * 门限 = max(绝对下限, 低分位底噪 × 余量)，并封顶。
     *
     * 分位取的是排序后的第 [NOISE_PERCENTILE] 个元素。段太短（不足 10 帧）时分位不可信，
     * 直接退到绝对下限——短录音本来也不该因为「估不出底噪」被放行。
     */
    private fun gateOf(frameRms: FloatArray): Float {
        val n = frameRms.size
        if (n < 10) return FLOOR_RMS
        val sorted = frameRms.copyOf()
        sorted.sort()
        val noise = sorted[((n - 1) * NOISE_PERCENTILE).toInt()]
        return min(max(noise * NOISE_MARGIN, FLOOR_RMS), MAX_GATE)
    }
}
