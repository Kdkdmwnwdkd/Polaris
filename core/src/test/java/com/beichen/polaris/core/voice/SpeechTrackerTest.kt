package com.beichen.polaris.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「说完自己停」的判据（2026-11-21）。
 *
 * ## 为什么这条值得单测
 *
 * 界面上写着「正在听（说完自己停）」，而整段识别引擎的 `isEndpoint()` 原本恒为 false。
 * 用户装了 paraformer（[com.beichen.polaris.audio.asr.AsrRouter] 默认优先选它），
 * 于是每次说完话都得手点「说完了」，不点就录到 30 秒硬上限。这个缺陷在日志里
 * 完全看不出来：没有异常、没有告警，只有「按了麦克风之后卡住」这一种感受。
 *
 * 判据一旦做错，两种后果都很贵：
 * - **判早了**：把用户的话从中间切开，后半句直接丢掉，用户得重说。
 * - **判晚了**：用户盯着一动不动的界面，以为坏了。
 *
 * 所以这里把每个边界都钉住：停顿多久算句末、没开口时永远不能停、
 * 中间插一个「嗯」要重新计时、空调声不能自己把会话关掉。
 *
 * ## 用例与时长的对应
 *
 * 时间轴都用 100ms 一块（与 `LocalSpeechInput` 的 `CHUNK_SAMPLES` 一致），
 * 这样断言读起来就是「第几块该判停」，不用心算。
 */
class SpeechTrackerTest {

    /** 安静房间的底噪。 */
    private val quiet = 0.001f

    /** 正常说话的音量（0.05 ≈ -26 dBFS）。 */
    private val speech = 0.05f

    private fun tracker() = SpeechPresence.Tracker()

    private fun SpeechPresence.Tracker.feed(rms: Float, ms: Int) {
        repeat(ms / 100) { push(rms, 100) }
    }

    @Test
    fun `没开口就永远不判停`() {
        val t = tracker()
        t.feed(quiet, 5_000)
        assertEquals(0, t.voiced)
        assertEquals(5_000, t.silence)
        assertFalse("没说过话却被判说完了，会话会提交一句空话", t.shouldEnd())
    }

    @Test
    fun `说完话后静音够长才判停`() {
        val t = tracker()
        t.feed(speech, 400)

        t.feed(quiet, 900)
        assertFalse("900ms 静音还不够，中文句子中间的停顿就有这么长", t.shouldEnd())

        t.feed(quiet, 100)
        assertTrue("1000ms 静音到了就该收尾", t.shouldEnd())
    }

    @Test
    fun `句中停顿不判停，接着说照样累计`() {
        val t = tracker()
        t.feed(speech, 300)
        t.feed(quiet, 600)   // "我想想……"
        t.feed(speech, 400)  // "就这样吧"
        assertEquals("两段话都要算进去", 700, t.voiced)

        t.feed(quiet, 900)
        assertFalse(t.shouldEnd())
        t.feed(quiet, 100)
        assertTrue(t.shouldEnd())
    }

    @Test
    fun `半声哼不算说过话`() {
        val t = tracker()
        t.feed(speech, 100)  // 100ms < MIN_SPEECH_MS
        t.feed(quiet, 2_000)
        assertEquals(100, t.voiced)
        assertFalse("200ms 以下不足以判为说过话", t.shouldEnd())
    }

    @Test
    fun `只有底噪时既不判有声也不判停`() {
        val t = tracker()
        t.feed(0.002f, 3_000)
        assertEquals(0, t.voiced)
        assertFalse(t.shouldEnd())
    }

    @Test
    fun `静音被一声短促噪声打断后重新计时`() {
        val t = tracker()
        t.feed(speech, 300)
        t.feed(quiet, 700)
        t.feed(speech, 100)  // 一声「嗯」
        assertEquals("尾部静音要清零", 0, t.silence)
        t.feed(quiet, 900)
        assertFalse("从「嗯」之后重新数，才 900ms", t.shouldEnd())
        t.feed(quiet, 100)
        assertTrue(t.shouldEnd())
    }

    @Test
    fun `非法块被忽略，不污染计时`() {
        val t = tracker()
        t.push(0.05f, 0)
        t.push(0.05f, -100)
        t.push(Float.NaN, 100)
        t.push(Float.POSITIVE_INFINITY, 100)
        assertEquals(0, t.elapsed)
        assertEquals(0, t.voiced)
        assertFalse(t.shouldEnd())
    }

    @Test
    fun `空调声一开一停不会把会话自己关掉`() {
        val t = tracker()
        // 开麦 1 秒内估不出底噪，门限退回绝对下限，空调（0.01）会被误计成有声
        t.feed(0.01f, 2_000)
        // 然后空调照旧响着。这时静音也在累积，但对比度不成立
        assertFalse(
            "空调的「有声」和「底噪」一样响，不该判说完了（voiced=${t.voiced}, silence=${t.silence}）",
            t.shouldEnd(),
        )
    }

    @Test
    fun `空调声里正常说话仍能判停`() {
        val t = tracker()
        t.feed(0.01f, 2_000)   // 空调
        t.feed(0.10f, 600)     // 说话，明显高过空调
        t.feed(0.01f, 1_100)   // 停口
        assertTrue("说话电平是空调的 10 倍，对比度充足", t.shouldEnd())
    }

    @Test
    fun `窗口淘汰旧样本后，正常说话照样判得出`() {
        val t = tracker()
        t.feed(0.30f, 300)    // 开麦时一声咳嗽，短暂抬高了历史电平
        t.feed(quiet, 5_000)  // 静默把它挤出 2 秒窗口
        t.feed(speech, 400)   // 正常说话
        assertEquals("咳嗽 + 说话都算有声", 700, t.voiced)
        t.feed(quiet, 1_000)
        assertTrue(t.shouldEnd())
    }

    @Test
    fun `与整段判据对同一段音频结论一致`() {
        // 300ms 底噪 + 500ms 说话 + 1200ms 底噪
        val pcm = concat(noise(300, 0.001f), tone(500, 0.08f), noise(1_200, 0.001f))
        assertTrue("整段判据认为有人说话", SpeechPresence.hasSpeech(pcm, rate))

        val t = tracker()
        val block = 1_600
        var at = 0
        while (at + block <= pcm.size) {
            var sum = 0.0
            for (j in at until at + block) sum += pcm[j].toDouble() * pcm[j]
            t.push(sqrt(sum / block).toFloat(), 100)
            at += block
        }
        assertTrue("增量判据也该认为说完了", t.shouldEnd())
    }

    // —— 合成信号 ——

    private val rate = 16_000

    /** 确定性伪随机：测试要能复现，不能用 Random 的默认种子。 */
    private fun noise(ms: Int, amplitude: Float, seed: Int = 7): FloatArray {
        var s = seed
        return FloatArray(rate * ms / 1000) {
            s = s * 1_103_515_245 + 12_345
            val unit = ((s ushr 16) and 0x7fff) / 32768f * 2f - 1f
            unit * amplitude
        }
    }

    private fun tone(ms: Int, amplitude: Float): FloatArray {
        val n = rate * ms / 1000
        return FloatArray(n) { amplitude * sin(2 * PI * 220.0 * it / rate).toFloat() }
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }
}
