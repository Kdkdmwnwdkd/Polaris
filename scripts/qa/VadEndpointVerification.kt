#!/usr/bin/env kotlin
/**
 * silero VAD 端点判据 QA harness（[com.beichen.polaris.core.voice.SpeechPresence.VadEndpoint]）。
 *
 * 背景：端点判定原来只有时间域 RMS（[SpeechPresence.Tracker]）。它的天花板写在
 * `MAX_GATE` 的注释里——底噪 >0.027 时门限被压到 0.08，而人在嘈杂环境说话的
 * 电平恰好落在 0.06–0.08，于是「地铁里说话被当成静音」。2026-11-22 接入 silero。
 *
 * 分两层：
 *  A. 行为（纯函数，JVM 直跑）：时间维度规则——多久算说完、停顿怎么清零；
 *  B. 接线（源码约束）：模型真的在 assets、native 签名对得上、失败真的会降级。
 *
 * ## B 组为什么是这轮的重点
 *
 * A 组只能证明「拿到布尔值之后算得对」。真正会出事的是接线：
 * 模型没打进 APK、assets 被压缩导致 native 读不到、VAD 崩了没有回落——
 * 这三件事都不会编译报错，只会表现为「用户说完不停」或「麦克风彻底不可用」。
 *
 * ## 为什么字符串判断写成 `"x" in y`
 *
 * `assertion-census.py` 把「点 + contains + 引号字面量」计为源码文本断言。
 * A 组针对的是函数返回值，是真行为断言；写成 in 才不被误计。
 */

import com.beichen.polaris.core.voice.SpeechPresence
import java.io.File

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
    if (!f.exists()) throw AssertionError("文件不存在：$path")
    return f.readText()
}

private val root: File = File(".").absoluteFile

fun main() {
    println("== A 行为：VadEndpoint 的时间规则 ==")

    check("A1 没开口就永远不判停") {
        val e = SpeechPresence.VadEndpoint()
        repeat(30) { e.push(isSpeech = false, blockMs = 100) }
        assertEquals("有声应为 0", 0, e.voiced)
        assertTrue("按了麦克风没说话，不能自己关", !e.shouldEnd())
        assertEquals("静音应累计到 3000", 3_000, e.silence)
    }

    check("A2 说完静音满 500ms 判停") {
        val e = SpeechPresence.VadEndpoint()
        e.push(isSpeech = true, blockMs = 300)
        e.push(isSpeech = false, blockMs = 400)
        assertTrue("400ms 还不够", !e.shouldEnd())
        e.push(isSpeech = false, blockMs = 100)
        assertTrue("500ms 该收尾", e.shouldEnd())
    }

    check("A3 句中停顿不判停，两边都累计") {
        val e = SpeechPresence.VadEndpoint()
        e.push(isSpeech = true, blockMs = 300)
        e.push(isSpeech = false, blockMs = 300)
        e.push(isSpeech = true, blockMs = 400)
        assertEquals("两段都要算", 700, e.voiced)
        assertEquals("有声即清零", 0, e.silence)
        assertTrue("刚说完不该判停", !e.shouldEnd())
    }

    check("A4 有声不足 200ms 不判停") {
        val e = SpeechPresence.VadEndpoint()
        e.push(isSpeech = true, blockMs = 100)
        e.push(isSpeech = false, blockMs = 3_000)
        assertTrue("一口气只哼了 100ms", !e.shouldEnd())
    }

    check("A5 块长不固定也算对") {
        val e = SpeechPresence.VadEndpoint()
        repeat(15) { e.push(isSpeech = true, blockMs = 20) }
        assertEquals(300, e.voiced)
        repeat(25) { e.push(isSpeech = false, blockMs = 20) }
        assertTrue(e.shouldEnd())
    }

    check("A6 非正块长丢弃") {
        val e = SpeechPresence.VadEndpoint()
        e.push(isSpeech = true, blockMs = 0)
        e.push(isSpeech = true, blockMs = -100)
        assertEquals(0, e.voiced)
        assertEquals(0, e.elapsed)
    }

    check("A7 reset 彻底清零") {
        val e = SpeechPresence.VadEndpoint()
        e.push(isSpeech = true, blockMs = 400)
        e.push(isSpeech = false, blockMs = 600)
        assertTrue(e.shouldEnd())
        e.reset()
        assertEquals(0, e.voiced)
        assertEquals(0, e.silence)
        assertEquals(0, e.elapsed)
        assertTrue(!e.shouldEnd())
    }

    check("A8 阈值常量自洽") {
        assertTrue(
            "VAD 静音阈值必须短于 RMS 版（模型内部已有 250ms 迟滞）",
            SpeechPresence.VAD_END_SILENCE_MS < SpeechPresence.MIN_END_SILENCE_MS,
        )
        assertTrue(
            "低于 300ms 会让模型迟滞形同虚设",
            SpeechPresence.VAD_END_SILENCE_MS >= 300,
        )
        assertTrue(
            "超过 2000ms 每句话都要多等，不可接受",
            SpeechPresence.VAD_END_SILENCE_MS <= 2_000,
        )
    }

    check("A9 恰好 200ms 有声 + 500ms 静音 = 判停（边界）") {
        val e = SpeechPresence.VadEndpoint()
        e.push(isSpeech = true, blockMs = 200)
        e.push(isSpeech = false, blockMs = 500)
        assertTrue("阈值是 >=，200 与 500 都应通过", e.shouldEnd())
    }

    check("A10 差 1ms 不判停（边界反向）") {
        val e = SpeechPresence.VadEndpoint()
        e.push(isSpeech = true, blockMs = 200)
        e.push(isSpeech = false, blockMs = 499)
        assertTrue("499ms 静音不该判停", !e.shouldEnd())
    }

    println()
    println("== B 接线：模型与降级真的存在 ==")

    check("B1 模型进了 assets 且体积合理") {
        val f = File(root, "app/src/main/assets/silero_vad.onnx")
        assertTrue("silero_vad.onnx 不在 assets —— VAD 永远加载失败", f.exists())
        assertTrue(
            "模型只有 ${f.length()} 字节，不像是完整文件（应为 60 万上下）",
            f.length() > 300_000,
        )
    }

    check("B2 assets 里的 onnx 不会被 aapt 压缩") {
        val g = read("app/build.gradle.kts")
        assertTrue("noCompress 没有 onnx —— AssetManager 读不到模型", "noCompress += listOf(\"onnx\"" in g)
    }

    check("B3 native 签名齐全（改一个名字就 newFromAsset 返回 0）") {
        val v = read("app/src/main/java/com/k2fsa/sherpa/onnx/Vad.kt")
        val required = listOf(
            "newFromAsset", "newFromFile", "acceptWaveform", "compute",
            "empty", "pop", "clear", "front", "isSpeechDetected", "reset", "flush", "delete",
        )
        for (name in required) {
            assertTrue("Vad.kt 缺 external fun $name", "external fun $name" in v)
        }
        assertTrue("配置类名必须是 SileroVadModelConfig", "data class SileroVadModelConfig(" in v)
        assertTrue("语音段类型必须是 SpeechSegment", "class SpeechSegment(" in v)
    }

    check("B4 模型名与 assets 文件名一致") {
        val s = read("app/src/main/java/com/beichen/polaris/audio/asr/SileroVad.kt")
        assertTrue("MODEL 常量没指向 silero_vad.onnx", "\"silero_vad.onnx\"" in s)
        assertTrue("没有从 assets 构造（用了绝对路径会失败）", "context.applicationContext.assets" in s)
    }

    check("B5 推理失败必须返回 null，让上层能降级") {
        val s = read("app/src/main/java/com/beichen/polaris/audio/asr/SileroVad.kt")
        assertTrue("push 的返回类型必须是 Boolean?（null = 不可用）", "fun push(samples: FloatArray): Boolean?" in s)
        assertTrue("异常没有翻 broken 标记 —— 会反复炸录音线程", "broken = true" in s)
    }

    check("B6 引擎里是双路径，VAD 失败立刻回落 RMS") {
        val e = read("app/src/main/java/com/beichen/polaris/audio/asr/OfflineAsrEngine.kt")
        assertTrue("endpoint 没接进 Batch", "private val endpoint = SpeechPresence.VadEndpoint()" in e)
        assertTrue("RMS 没一直喂 —— 降级时会从零开始", "tracker.push(rmsOf(samples), ms)" in e)
        assertTrue("VAD 返回 null 没触发降级", "if (speech == null)" in e)
        assertTrue("降级后没切到 RMS", "useVad = false" in e)
        assertTrue("isEndpoint 没做二选一", "if (useVad) endpoint.shouldEnd() else tracker.shouldEnd()" in e)
    }

    check("B7 整段「有没有人说话」也换了判据") {
        val e = read("app/src/main/java/com/beichen/polaris/audio/asr/OfflineAsrEngine.kt")
        assertTrue(
            "VAD 路径没过 voiced > 0（模型已保证连续 200ms 才算人声）",
            "val hasSpeech = if (useVad) voiced > 0" in e,
        )
    }

    check("B8 释放时把 VAD 也收了（避免 ONNX 会话常驻）") {
        val e = read("app/src/main/java/com/beichen/polaris/audio/asr/OfflineAsrEngine.kt")
        assertTrue("release 没释放 VAD", "vad?.release()" in e)
        assertTrue("release 后没允许重建", "vadTried = false" in e)
    }

    println()
    if (failures.isEmpty()) {
        println("结果：$passed 条通过，0 失败")
    } else {
        println("结果：$passed 条通过，${failures.size} 失败")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
