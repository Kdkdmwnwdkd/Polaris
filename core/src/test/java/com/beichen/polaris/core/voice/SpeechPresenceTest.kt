package com.beichen.polaris.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 「没人说话时不许识别出字」的判据（2026-11-21）。
 *
 * ## 为什么这条值得单测
 *
 * 真机反馈：一个字没说、只按了一下麦克风，连按四次得到 3 / 3 / 4 / 0 字。
 * 这类错误在日志里看起来是成功的（"离线识别完成：3 字"），在 UI 上看起来
 * 也只是"识别得莫名其妙"——没有任何地方会报错。只有把判定的边界钉住，
 * 下次调阈值时才知道自己踩到了谁。
 *
 * ## 每个用例对应一个真实现场
 *
 * | 用例 | 现场 |
 * |---|---|
 * | 全零 | 麦克风静音、飞行模式下的空录 |
 * | 底噪 | 手机放桌上，空调在响 |
 * | 一声敲桌子 | 30ms 瞬态，不是人说话 |
 * | 轻声说话 | 离手机半米、音量只有峰值两三成 |
 * | 连续大声 | 整段都在说话，没有静音可供估计底噪 |
 */
class SpeechPresenceTest {

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

    @Test
    fun `全零静音不算有人说话`() {
        val pcm = FloatArray(rate) // 1 秒
        assertEquals(0, SpeechPresence.voicedMillis(pcm, rate))
        assertFalse(SpeechPresence.hasSpeech(pcm, rate))
    }

    @Test
    fun `只有底噪不算有人说话`() {
        // 0.002 ≈ -54 dBFS，典型安静房间的麦克风底噪
        val pcm = noise(1200, 0.002f)
        assertFalse(SpeechPresence.hasSpeech(pcm, rate))
    }

    @Test
    fun `一下敲桌子的瞬态不算有人说话`() {
        // 30ms 的 0.5 脉冲：能量很高，但持续时间远不足 200ms
        val pcm = concat(noise(500, 0.002f), tone(30, 0.5f), noise(500, 0.002f))
        assertFalse(SpeechPresence.hasSpeech(pcm, rate))
    }

    @Test
    fun `轻声说话要算有人说话`() {
        // 0.04 ≈ -28 dBFS：离手机半米说话时的电平。低于它还不判，用户就得重复三遍。
        val pcm = concat(noise(200, 0.001f), tone(600, 0.04f), noise(200, 0.001f))
        assertTrue(SpeechPresence.hasSpeech(pcm, rate))
    }

    @Test
    fun `整段都在说话不能被自己的低分位误杀`() {
        // 没有静音段可采样，低分位落在字缝里；若门限无限抬高，这里会假阴性
        val pcm = tone(1000, 0.3f)
        assertTrue(SpeechPresence.hasSpeech(pcm, rate))
    }

    @Test
    fun `有声时长按 20ms 粒度累计`() {
        val pcm = concat(noise(500, 0.001f), tone(400, 0.2f), noise(500, 0.001f))
        val voiced = SpeechPresence.voicedMillis(pcm, rate)
        // 允许 ±2 帧的边界误差（起止各半帧）
        assertTrue("voiced=$voiced", voiced in 380..420)
    }

    @Test
    fun `逐帧重载与 PCM 重载结论一致`() {
        val pcm = concat(noise(300, 0.001f), tone(500, 0.05f))
        val frame = rate * SpeechPresence.FRAME_MS / 1000
        val frames = pcm.size / frame
        val rms = FloatArray(frames) { i ->
            var sum = 0.0
            for (j in i * frame until (i + 1) * frame) sum += pcm[j].toDouble() * pcm[j]
            kotlin.math.sqrt(sum / frame).toFloat()
        }
        assertEquals(
            SpeechPresence.voicedMillis(pcm, rate),
            SpeechPresence.voicedMillisOfFrames(rms, SpeechPresence.FRAME_MS),
        )
    }

    @Test
    fun `采样率非法时不崩且判为无人声`() {
        val pcm = tone(200, 0.3f)
        assertEquals(0, SpeechPresence.voicedMillis(pcm, 0))
        assertFalse(SpeechPresence.hasSpeech(pcm, 0))
    }
}
