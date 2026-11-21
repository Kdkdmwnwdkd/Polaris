#!/usr/bin/env kotlin
/**
 * 「说完自己停」QA harness（[com.beichen.polaris.core.voice.SpeechPresence.Tracker]）。
 *
 * 背景：聊天页的语音面板上写着「正在听（说完自己停）」，而整段识别引擎
 * （paraformer，`AsrRouter` 默认优先选它）的 `isEndpoint()` 一直恒返回 false。
 * 结果是用户说完话之后录音不停，界面一直停在「说点什么…」，直到手动点「说完了」
 * 或者 30 秒硬上限——用户感受到的是「按了麦克风就卡住」。
 *
 * 本 harness 分两层：
 *  A. 行为（纯函数，JVM 直跑）：什么时候该判停、什么时候绝对不能判停；
 *  B. 源码（结构约束）：端点真的接进了引擎、桌宠真的关掉了自动停。
 *
 * ## B 组为什么必须存在
 *
 * A 组只能证明「判据是对的」。判据再对，只要 `isEndpoint()` 还是 `= false`，
 * 用户那边一点变化都没有——这正是这个缺陷活了这么久的原因：
 * 没有任何一层会报错，代码看起来完全正常。
 *
 * ## 为什么字符串判断写成 `"x" in y`
 *
 * `assertion-census.py` 把「点 + contains + 引号字面量」一律计为源码文本断言。
 * A 组那些判断针对的是被测函数的返回值，是真行为断言；写成 in 才不会被算成「扫源码」。
 */

import com.beichen.polaris.core.voice.SpeechPresence
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += name
        println("  FAIL  $name -> ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun assertTrue(cond: Boolean) = assertTrue("断言失败", cond)

private fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$msg：期望 <$expected>，实际 <$actual>")
}

private fun assertEquals(expected: Any?, actual: Any?) = assertEquals("值不符", expected, actual)

private fun read(path: String): String {
    val f = File(path)
    assertTrue("缺少文件 $path", f.exists())
    return f.readText()
}

/* ------------------------------ 合成信号 ------------------------------ */

private const val RATE = 16_000
private const val QUIET = 0.001f
private const val SPEECH = 0.05f

private fun SpeechPresence.Tracker.feed(rms: Float, ms: Int) {
    repeat(ms / 100) { push(rms, 100) }
}

private fun noise(ms: Int, amplitude: Float, seed: Int = 7): FloatArray {
    var s = seed
    return FloatArray(RATE * ms / 1000) {
        s = s * 1_103_515_245 + 12_345
        val unit = ((s ushr 16) and 0x7fff) / 32768f * 2f - 1f
        unit * amplitude
    }
}

private fun tone(ms: Int, amplitude: Float): FloatArray {
    val n = RATE * ms / 1000
    return FloatArray(n) { amplitude * sin(2 * PI * 220.0 * it / RATE).toFloat() }
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

fun main() {
    println("== 说完自己停 harness ==")

    /* ------------------------------ A. 行为 ------------------------------ */

    check("A1 没开口时永远不判停") {
        val t = SpeechPresence.Tracker()
        t.feed(QUIET, 5_000)
        assertEquals("有声时长", 0, t.voiced)
        assertTrue("没说过话却被判说完了，会话会提交一句空话", !t.shouldEnd())
    }

    check("A2 说完话后静音满 1 秒判停") {
        val t = SpeechPresence.Tracker()
        t.feed(SPEECH, 400)
        t.feed(QUIET, 900)
        assertTrue("900ms 还不够", !t.shouldEnd())
        t.feed(QUIET, 100)
        assertTrue("1000ms 到了该收尾", t.shouldEnd())
    }

    check("A3 句中停顿不判停，两边的话都算") {
        val t = SpeechPresence.Tracker()
        t.feed(SPEECH, 300)
        t.feed(QUIET, 600)
        t.feed(SPEECH, 400)
        assertEquals("两段都要累计", 700, t.voiced)
        assertTrue("停顿后才 600ms，不该判停", !t.shouldEnd())
        t.feed(QUIET, 1_000)
        assertTrue(t.shouldEnd())
    }

    check("A4 半声哼（100ms）不算说过话") {
        val t = SpeechPresence.Tracker()
        t.feed(SPEECH, 100)
        t.feed(QUIET, 2_000)
        assertEquals(100, t.voiced)
        assertTrue("不足 MIN_SPEECH_MS", !t.shouldEnd())
    }

    check("A5 只有底噪时既不判有声也不判停") {
        val t = SpeechPresence.Tracker()
        t.feed(0.002f, 3_000)
        assertEquals("底噪不算人声", 0, t.voiced)
        assertTrue(!t.shouldEnd())
    }

    check("A6 空调声一开一停不会把会话自己关掉") {
        val t = SpeechPresence.Tracker()
        t.feed(0.01f, 2_000)
        assertTrue(
            "有声段和底噪一样响，对比度不成立（voiced=${t.voiced}, silence=${t.silence}）",
            !t.shouldEnd(),
        )
    }

    check("A7 空调声里正常说话仍能判停") {
        val t = SpeechPresence.Tracker()
        t.feed(0.01f, 2_000)
        t.feed(0.10f, 600)
        t.feed(0.01f, 1_100)
        assertTrue("说话电平是空调的 10 倍", t.shouldEnd())
    }

    check("A8 静音被一声「嗯」打断后重新计时") {
        val t = SpeechPresence.Tracker()
        t.feed(SPEECH, 300)
        t.feed(QUIET, 700)
        t.feed(SPEECH, 100)
        assertEquals("尾部静音必须清零", 0, t.silence)
        t.feed(QUIET, 900)
        assertTrue("从「嗯」之后重新数，才 900ms", !t.shouldEnd())
        t.feed(QUIET, 100)
        assertTrue(t.shouldEnd())
    }

    check("A9 非法块被忽略，不污染计时") {
        val t = SpeechPresence.Tracker()
        t.push(SPEECH, 0)
        t.push(SPEECH, -100)
        t.push(Float.NaN, 100)
        t.push(Float.POSITIVE_INFINITY, 100)
        assertEquals("块长非正或数值非法一律丢弃", 0, t.elapsed)
        assertTrue(!t.shouldEnd())
    }

    check("A10 常量自洽：判停阈值在合理区间") {
        assertTrue(
            "静音阈值要长于最小语音时长，否则一句话内部就会被切开",
            SpeechPresence.END_SILENCE_MS > SpeechPresence.MIN_SPEECH_MS,
        )
        assertTrue(
            "静音阈值 ${SpeechPresence.END_SILENCE_MS}ms 超出 500–2000 的可接受区间",
            SpeechPresence.END_SILENCE_MS in 500..2_000,
        )
    }

    check("A11 与整段判据对同一段音频结论一致") {
        val pcm = concat(noise(300, 0.001f), tone(500, 0.08f), noise(1_200, 0.001f))
        assertTrue("整段判据认为有人说话", SpeechPresence.hasSpeech(pcm, RATE))

        val t = SpeechPresence.Tracker()
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

    /* ------------------------------ B. 源码（结构约束） ------------------------------ */

    // 判据再对，只要引擎那边还是恒 false，用户那边一点变化都没有。
    check("B1 整段引擎的端点真的接了判据，不是恒 false") {
        val src = read("app/src/main/java/com/beichen/polaris/audio/asr/OfflineAsrEngine.kt")
        assertTrue("isEndpoint 还是恒 false —— 界面上的「说完自己停」依然是假的",
            "override fun isEndpoint(): Boolean = false" !in src)
        assertTrue("isEndpoint 没有读 Tracker", "tracker.shouldEnd()" in src)
    }

    check("B2 每收一块音频都要喂给 Tracker") {
        val src = read("app/src/main/java/com/beichen/polaris/audio/asr/OfflineAsrEngine.kt")
        assertTrue("accept 里没有 push", "tracker.push(" in src)
        assertTrue("块长要用真实采样数算，不能写死 100",
            "samples.size * 1000 / sampleRate" in src)
    }

    // 桌宠是按住说话：自动判停会把「按住期间想词」当成说完。
    check("B3 自动停能关，且桌宠真的关了") {
        val input = read("app/src/main/java/com/beichen/polaris/audio/asr/LocalSpeechInput.kt")
        assertTrue("start 没有 autoStop 参数", "fun start(autoStop: Boolean" in input)
        assertTrue("循环里没吃 autoStop", "autoStop && session.isEndpoint()" in input)

        val pet = read("app/src/main/java/com/beichen/polaris/pet/PetVoice.kt")
        assertTrue("桌宠（按住说话）必须关掉自动停", "start(autoStop = false)" in pet)
    }

    // 聊天页面板是「按一下、说完自己停」，绝不能跟着关掉。
    check("B4 聊天页面板保持自动停") {
        val sheet = read("app/src/main/java/com/beichen/polaris/ui/LocalVoiceSheet.kt")
        assertTrue("面板把自动停关了 —— 文案与行为又不一致了", "autoStop = false" !in sheet)
    }

    /* ------------------------------ 汇总 ------------------------------ */
    println()
    if (failures.isEmpty()) {
        println("结果：$passed 条通过，0 失败")
    } else {
        println("结果：$passed 条通过，${failures.size} 失败")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
