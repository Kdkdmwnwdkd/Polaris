package com.beichen.polaris.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpeechPresence.VadEndpoint] 的行为判据。
 *
 * 这一层的输入是「这块是不是人声」——由 silero 给出，JVM 上给不了，所以单测只钉
 * **时间维度**的规则：多久算说完、停顿怎么清零、没开口会不会自己关。
 *
 * 频谱那一半（「人声 vs 地铁」）只能真机验，见 `手机验证手册.md` P33。
 */
class VadEndpointTest {

    private fun endpoint() = SpeechPresence.VadEndpoint()

    @Test
    fun `没开口就永远不判停`() {
        val e = endpoint()
        e.push(isSpeech = false, blockMs = 100)
        repeat(20) { e.push(isSpeech = false, blockMs = 100) }
        assertFalse("用户只是按了麦克风没说话，不能自己关", e.shouldEnd())
        assertEquals("有声时长应为 0", 0, e.voiced)
    }

    @Test
    fun `说完后静音满 500ms 判停`() {
        val e = endpoint()
        e.push(isSpeech = true, blockMs = 300)
        e.push(isSpeech = false, blockMs = 400)
        assertFalse("400ms 静音还不够", e.shouldEnd())
        e.push(isSpeech = false, blockMs = 100)
        assertTrue("500ms 静音到了就该收尾", e.shouldEnd())
    }

    @Test
    fun `句中短停顿不判停，接着说照样累计`() {
        val e = endpoint()
        e.push(isSpeech = true, blockMs = 300)
        e.push(isSpeech = false, blockMs = 300)  // 「我想想……」
        assertEquals("有声仍是 300ms", 300, e.voiced)
        e.push(isSpeech = true, blockMs = 400)   // 「就这样吧」
        assertEquals("两段话都要算进去", 700, e.voiced)
        assertEquals("有声即清零尾部静音", 0, e.silence)
        assertFalse("刚说完不该判停", e.shouldEnd())
    }

    @Test
    fun `有声时长不足 200ms 不判停`() {
        val e = endpoint()
        e.push(isSpeech = true, blockMs = 100)  // 一声短促的「嗯」
        e.push(isSpeech = false, blockMs = 2_000)
        assertFalse("只有 100ms 人声，不该当作说完", e.shouldEnd())
    }

    @Test
    fun `块长不固定也能算对`() {
        val e = endpoint()
        // 20ms 一块：300ms 语音 = 15 块
        repeat(15) { e.push(isSpeech = true, blockMs = 20) }
        assertEquals(300, e.voiced)
        repeat(25) { e.push(isSpeech = false, blockMs = 20) }  // 500ms
        assertTrue(e.shouldEnd())
    }

    @Test
    fun `非正块长直接丢弃`() {
        val e = endpoint()
        e.push(isSpeech = true, blockMs = 0)
        e.push(isSpeech = true, blockMs = -100)
        assertEquals("0 与负数不该改变任何状态", 0, e.voiced)
        assertEquals(0, e.elapsed)
    }

    @Test
    fun `reset 后彻底清零`() {
        val e = endpoint()
        e.push(isSpeech = true, blockMs = 400)
        e.push(isSpeech = false, blockMs = 600)
        assertTrue(e.shouldEnd())

        e.reset()
        assertEquals(0, e.voiced)
        assertEquals(0, e.silence)
        assertEquals(0, e.elapsed)
        assertFalse("复位后不该保留上一句的判定", e.shouldEnd())
    }

    @Test
    fun `阈值与常量保持一致`() {
        assertTrue(
            "VAD 的静音阈值应短于 RMS 版——模型内部已经先要求 250ms 静音",
            SpeechPresence.VAD_END_SILENCE_MS < SpeechPresence.MIN_END_SILENCE_MS,
        )
        assertTrue(
            "VAD 静音阈值低于 300ms 会让模型内部的迟滞形同虚设",
            SpeechPresence.VAD_END_SILENCE_MS >= 300,
        )
    }
}
