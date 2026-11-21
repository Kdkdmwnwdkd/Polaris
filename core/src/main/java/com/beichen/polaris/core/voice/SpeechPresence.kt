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
 * 这道封顶是有代价的，别以为随手能改：底噪高于 ~0.027 时门限被压到 0.08，
 * 说话电平落在 0.06–0.08 之间就会被判成静音。增量判据的后果是「不自动停」，
 * 整段判据的后果是「录音被丢掉」。三种改法（去封顶 / 按窗口高分位封顶 / 按高分位
 * 的固定倍数封顶）都会在别的场景塌掉——时间域里「稳态噪声」和「持续说话」不可分，
 * 要靠频谱 VAD 才能解，不是调常数能解决的。
 *
 * ## 为什么放 core
 *
 * 纯 Kotlin、不碰 Android API，可以进 JVM 单测。这个判定的每一条阈值都值得被钉住——
 * 它错一次，用户的体验是「我没说话它自己发了一句」。
 *
 * ## 两种用法
 *
 * - **整段判定**：[voicedMillis] / [hasSpeech] / [voicedMillisOfFrames] / [hasSpeechInFrames]。
 *   音频全拿到手再算，用于「这段录音该不该送去识别」。
 * - **增量判定**：[Tracker]。边录边判「这句说完了」，用于让整段模型也能自动收尾。
 */
object SpeechPresence {

    /** 分帧长度。20ms 是语音处理的常规粒度：足够短到不漏掉短音，足够长到不吃进 VAD 抖动。 */
    const val FRAME_MS = 20

    /** 累计有声时长低于这个值就算「没人说话」。"嗯"一声大约 300ms，200ms 不会误杀。 */
    const val MIN_SPEECH_MS = 200

    /**
     * 静音阈值的下限：再干脆的人，说完也要给 800ms 才收尾。
     *
     * 低于这个数，一口气说完的短句会被句中的换气切成两半。
     */
    const val MIN_END_SILENCE_MS = 800

    /**
     * 静音阈值的上限：再爱停顿的人，等 1.3 秒也该收了。
     *
     * 上限不是为了准确，是为了让「说完」这件事有时间上的确定性——
     * 否则一个习惯长停顿的用户会永远等下去，看起来就像卡住了。
     */
    const val MAX_END_SILENCE_MS = 1300

    /**
     * 自适应阈值在「已观测到的最长句内停顿」之上多留的余量。
     *
     * 500ms 来自中文换气的量级：句中换气 200–400ms，句末沉下来 600ms 以上。
     * 取比最长停顿多 500ms，等于说「你停过多久我都等你，再多等半秒」。
     */
    const val PAUSE_MARGIN_MS = 500

    /**
     * 计入「句内停顿」统计的最长停顿。
     *
     * 超过 800ms 的停顿本来就该判停，把它记进统计只会让后面的阈值一路往上爬，
     * 用户的下一句话反而更慢。所以只统计这个范围内的。
     */
    const val MAX_TRACKED_PAUSE_MS = 800

    /**
     * 增量判据里估底噪用的窗口：20 块。
     *
     * 块长由调用方决定（录音循环固定 100ms），20 块即 2 秒。
     * [gateOf] 的分位估计要求至少 10 个样本，所以开头 1 秒内门限退回绝对下限——
     * 那一秒里宁放松不收紧：把用户第一个字判掉，比把底噪当人声更难受。
     */
    private const val NOISE_WINDOW_BLOCKS = 20

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
        val noise = noiseOf(frameRms) ?: return FLOOR_RMS
        return min(max(noise * NOISE_MARGIN, FLOOR_RMS), MAX_GATE)
    }

    /**
     * 底噪估计：排序后取 [NOISE_PERCENTILE] 分位。
     *
     * 样本不足 10 个时返回 null——分位在这个量级上没有统计意义，
     * 调用方要么退回绝对下限（整段判据），要么干脆不下结论（[Tracker]）。
     */
    private fun noiseOf(frameRms: FloatArray): Float? {
        val n = frameRms.size
        if (n < 10) return null
        val sorted = frameRms.copyOf()
        sorted.sort()
        return sorted[((n - 1) * NOISE_PERCENTILE).toInt()]
    }

    /**
     * 边录边判「这句说完了没有」。
     *
     * ## 为什么需要它
     *
     * 上面那一整套（[voicedMillis] / [hasSpeech]）都要**整段音频拿到手**才能算。
     * 可整段识别（paraformer）的会话是「攒到松手才认一次」，它没有流式模型那种
     * 原生 endpoint——`AsrSession.isEndpoint()` 只能恒返回 false。
     *
     * 于是界面上那句「正在听（说完自己停）」是假的：2026-11-21 核对代码时发现，
     * 只要装了 paraformer（`AsrRouter` 优先选它），用户说完话之后录音不会停，
     * 要么手动点「说完了」，要么一直录到 30 秒硬上限。屏幕上还一直是「说点什么…」——
     * 因为整段模型在收尾前不产出任何文本。用户感受到的就是「按了麦克风，然后卡住」。
     *
     * ## 判据是同一套，只是换成了增量
     *
     * 每来一块音频算一个 RMS，放进一个 2 秒的滑动窗口，用窗口内的低分位估底噪
     * （与 [gateOf] 完全同一个函数），超过底噪 [NOISE_MARGIN] 倍算有声。
     * 窗口 2 秒够跟上空调启停这类缓变噪声，又短到不会被一个人的说话声长期抬高。
     *
     * 与 [voicedMillisOfFrames] 的关系：同一段音频、同样安静的环境下，两者对
     * 「有没有说过话」的结论应当一致；差别只在 Tracker 每 100ms 就能回答一次。
     *
     * ## 两个刻意的不对称
     *
     * 1. **没说过话就永远不判停**。否则用户按下麦克风后稍一犹豫（1 秒没开口），
     *    会话就被判「说完了」并提交一句空话。
     * 2. **只要有声就把尾部静音清零**。静音是「连续」概念，中间一个「嗯」
     *    就该重新计时，不然一段有停顿的话会被从停顿处切开。
     *
     * ## 「等多久算说完」为什么不写死
     *
     * 上一版是 1000ms，拍出来的数字：短了会切句子，长了每句话都要多等。
     * 但「多长算停顿」因人而异——有人一口气说完，有人每三个字停一下。
     * 一个数字不可能同时对这两种人正确。
     *
     * 现在改成**按这个人自己的停顿习惯推**：记录本次录音里出现过的最长句内停顿，
     * 阈值取 `最长停顿 + [PAUSE_MARGIN_MS]`，夹在 [MIN_END_SILENCE_MS] 与
     * [MAX_END_SILENCE_MS] 之间。干脆的人 800ms 就收，爱停顿的人自动放宽，
     * 但永远比他自己停过的最长处再多留半秒——所以永远比他慢半拍，不会被切。
     *
     * 这个值**只升不降**：一句里停过 600ms，后面就按 1100ms 等。降回去会让
     * 同一句话的后半段更容易被切，而前半段已经证明了这个用户确实会停这么久。
     *
     * ## 为什么还要一道「对比度」
     *
     * 录音开头 1 秒窗口里样本不足 10 个，估不出底噪，门限只能退到绝对下限 [FLOOR_RMS]。
     * 于是空调声（0.01，高于 0.006）在这 1 秒里会被误计成「有声」。如果只看
     * 「有声够 200ms、尾部静音够了」两条，空调一开一停就能把会话判结束——
     * 用户什么都没说，界面却自己关了。
     *
     * 所以判停前再看一眼**对比度**：有声段的平均电平必须比当前底噪高 [NOISE_MARGIN] 倍。
     * 空调的「有声」和「底噪」都是 0.01，比值 1，不通过；真人说话 0.05 对底噪 0.001，
     * 比值 50，通过。这条恰好也是 [gateOf] 的定义——只是换成事后核验。
     */
    class Tracker(
        /**
         * 钉死的静音阈值（毫秒）。
         *
         * 默认 null = 自适应。[LocalSpeechInput] 用默认；测试与「阈值必须钉死」的
         * 场景可以显式传值，这样调自适应参数不会连带改掉它们的语义。
         */
        private val fixedEndSilenceMs: Int? = null,
        private val windowBlocks: Int = NOISE_WINDOW_BLOCKS,
    ) {

        private val recent = ArrayDeque<Float>(windowBlocks)

        private var voicedMs = 0
        private var silenceMs = 0
        private var totalMs = 0

        /** 本次录音里出现过的最长「句内停顿」。自适应阈值就是它 + 余量。 */
        private var maxPauseMs = 0

        /** 有声段的电平和：Σ rms × ms。判停前用它核算「这段到底算不算人声」。 */
        private var voicedWeighted = 0.0

        /**
         * 喂一块音频。
         *
         * @param blockRms 这一块的 RMS（不是峰值）
         * @param blockMs 这一块的时长。块长由调用方定，与 [FRAME_MS] 无关
         */
        fun push(blockRms: Float, blockMs: Int) {
            if (blockMs <= 0 || !blockRms.isFinite()) return
            recent.addLast(blockRms)
            while (recent.size > windowBlocks) recent.removeFirst()
            totalMs += blockMs
            if (blockRms >= gateOf(recent.toFloatArray())) {
                // 有声块来了：刚才那段静音是句内停顿，记下它有多长。
                // voicedMs > 0 排除「按下麦克风先愣神」那一段——那不是句内停顿，
                // 记进去只会把整句话的阈值顶高。
                if (voicedMs > 0 && silenceMs in 1..MAX_TRACKED_PAUSE_MS) {
                    maxPauseMs = max(maxPauseMs, silenceMs)
                }
                voicedMs += blockMs
                voicedWeighted += blockRms.toDouble() * blockMs
                silenceMs = 0
            } else {
                silenceMs += blockMs
            }
        }

        /** 累计有声时长。 */
        val voiced: Int get() = voicedMs

        /** 尾部连续静音时长。 */
        val silence: Int get() = silenceMs

        /** 已录总时长。 */
        val elapsed: Int get() = totalMs

        /**
         * 当前生效的静音阈值。
         *
         * 固定值直接用；否则按「最长句内停顿 + 余量」推，并夹在上下限之间。
         * 对外暴露是为了让调用方（与测试）能看见此刻的判定依据——
         * 一个会自己变的阈值如果不透明，出问题时无法复盘。
         */
        val endSilenceMs: Int
            get() = fixedEndSilenceMs
                ?: (maxPauseMs + PAUSE_MARGIN_MS)
                    .coerceIn(MIN_END_SILENCE_MS, MAX_END_SILENCE_MS)

        /** 说完了没有。 */
        fun shouldEnd(): Boolean =
            voicedMs >= MIN_SPEECH_MS && silenceMs >= endSilenceMs && hasContrast()

        /**
         * 有声段确实比底噪响 [NOISE_MARGIN] 倍吗。
         *
         * 窗口不足 10 块时返回 false：没有底噪样本就没有对比依据，
         * 宁可多等一会儿让用户手点，也不要提前把会话关掉。
         */
        private fun hasContrast(): Boolean {
            if (voicedMs <= 0) return false
            val noise = noiseOf(recent.toFloatArray()) ?: return false
            val mean = (voicedWeighted / voicedMs).toFloat()
            return mean >= noise * NOISE_MARGIN
        }
    }
}
