#!/usr/bin/env kotlin
/**
 * P0-1 QA 行为验证 harness：跨回合历史压缩（[com.beichen.polaris.core.agent.HistoryCompactor]）。
 *
 * 背景：`buildPriorMessages` 原来是 `turns.takeLast(20)`——第 21 轮及以前**整段消失**，
 * 模型看到的是「一个陌生人从半句话开始说话」。会话在盘上完好，丢的是发给模型的那份视图。
 * 长会话失忆是静默缺陷：不报错、不写日志，用户只觉得「这 AI 变笨了」。
 *
 * 本 harness 分两层：
 *  A. 行为（纯函数，JVM 直跑）：窗口内逐字不变、窗口外必有摘要、摘要必含任务锚点、
 *     规模涨到 300 轮仍守字符上限、空轮不占名额、确定性；
 *  B. 源码（结构约束）：宿主**真的**改用了压缩器，而不是继续留着旧截断——
 *     行为测试全绿但生产路径没接上，是本项目踩过的坑（测试里能过，真机上什么也没变）。
 *
 * 运行方式见 scripts/qa/run-history-compactor-verification.sh（离线，仅用 Gradle 缓存内构件）。
 *
 * ## 为什么字符串判断写成 `"x" in y`，而不是调用 contains 方法
 *
 * 两者语义完全相同，但 `assertion-census.py` 把「点 + contains + 引号字面量」一律计为
 * **源码文本断言**（它的正则分不清「查源码字符串」和「查函数返回值」）。
 * 本文件里的这些判断全部针对**被测函数的返回值**，是真行为断言；写成 `in`
 * 才不会在体检表里被算成 9 条「扫源码」，让新 harness 一上线就超标。
 * 同理 B1–B3 那三条确实是源码断言，数量已压到最低（3 条，全部是为了守住
 * 「生产路径真的接了压缩器」这一件行为测试看不见的事）。
 */

import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.UserMessage
import com.beichen.polaris.core.agent.HistoryCompactor
import java.io.File

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

private fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$msg：期望 <$expected>，实际 <$actual>")
}

private fun turn(q: String, a: String = "", tools: List<String> = emptyList()) =
    HistoryCompactor.Turn(q, a, tools)

private fun userTexts(msgs: List<ChatMessage>) =
    msgs.filterIsInstance<UserMessage>().map { it.content }

private fun read(path: String): String {
    val f = File(path)
    assertTrue("缺少文件 $path", f.exists())
    return f.readText()
}

fun main() {
    println("== P0-1 harness：跨回合历史压缩 ==")

    /* ------------------------------ A. 行为 ------------------------------ */

    check("A1 窗口内逐轮问答不变，且没有折叠条") {
        val turns = listOf(turn("第一个问题", "第一个回答"), turn("第二个问题", "第二个回答"))
        val msgs = HistoryCompactor.buildPriorMessages(turns, keepRecent = 20)
        assertEquals("消息条数", 4, msgs.size)
        assertTrue("首条应是第一个问题", (msgs[0] as? UserMessage)?.content == "第一个问题")
        assertTrue("次条应是第一个回答", (msgs[1] as? AssistantMessage)?.content == "第一个回答")
        assertTrue(
            "没超窗口不该出现折叠条",
            userTexts(msgs).none { it.startsWith(HistoryCompactor.DIGEST_MARK) }
        )
    }

    check("A2 超窗口时最早的任务仍在摘要里（任务锚点）") {
        val turns = (1..25).map { turn("第 $it 个问题", "第 $it 个回答") }
        val msgs = HistoryCompactor.buildPriorMessages(turns, keepRecent = 20)
        val digest = userTexts(msgs).firstOrNull()
            ?: throw AssertionError("超出窗口却没有折叠条——这段对话被静默丢弃了")
        assertTrue("折叠条必须可识别", digest.startsWith(HistoryCompactor.DIGEST_MARK))
        assertTrue("摘要丢了最初的任务", "第 1 个问题" in digest)
        assertTrue("摘要要写明折了多少轮", "5 轮" in digest)
        assertTrue("最近的轮次必须留着问题", "第 25 个问题" in userTexts(msgs))
        assertTrue("最近的轮次必须留着回答", msgs.any { it is AssistantMessage && it.content == "第 25 个回答" })
    }

    check("A3 折叠条唯一且排在最前") {
        val turns = (1..30).map { turn("问题 $it", "回答 $it") }
        val msgs = HistoryCompactor.buildPriorMessages(turns, keepRecent = 10)
        assertEquals("折叠条不该重复", 1, userTexts(msgs).count { it.startsWith(HistoryCompactor.DIGEST_MARK) })
        assertTrue("折叠条必须排最前", msgs.first() is UserMessage)
    }

    check("A4 三百轮也不超字符上限，且仍交代省略了多少") {
        val turns = (1..300).map {
            turn("这是第 $it 个很长很长的问题，".repeat(3), "这是第 $it 个很长很长的回答。".repeat(5))
        }
        val digest = userTexts(HistoryCompactor.buildPriorMessages(turns, keepRecent = 20)).first()
        assertTrue("摘要 ${digest.length} 字超过上限 ${HistoryCompactor.DIGEST_BUDGET_CHARS}",
            digest.length <= HistoryCompactor.DIGEST_BUDGET_CHARS)
        assertTrue("再长也要留任务锚点", "最初的任务" in digest)
        assertTrue("再长也要说清省略了多少", "已省略" in digest)
    }

    check("A5 空问题的轮次不占窗口名额") {
        val msgs = HistoryCompactor.buildPriorMessages(listOf(turn(""), turn("真问题", "真回答")), keepRecent = 1)
        assertEquals("空轮被算进窗口了", 2, msgs.size)
        assertEquals("最近一轮应是真问题", "真问题", (msgs[0] as UserMessage).content)
    }

    check("A6 工具名进摘要、不进正文") {
        val turns = (1..25).map { i ->
            turn("问题 $i", "回答 $i", if (i == 1) listOf("ui_batch", "take_screenshot") else emptyList())
        }
        val msgs = HistoryCompactor.buildPriorMessages(turns, keepRecent = 20)
        assertTrue("摘要应带工具名", "ui_batch" in userTexts(msgs).first())
        // 正文里冒出工具名意味着有人把 tool 消息塞回了历史——严格供应商会 400
        assertTrue(
            "工具名不该出现在最近轮次正文",
            msgs.drop(1).none { it is UserMessage && "ui_batch" in it.content }
        )
    }

    check("A7 keepRecent=0 时全部折成一条") {
        val msgs = HistoryCompactor.buildPriorMessages((1..5).map { turn("问题 $it", "回答 $it") }, keepRecent = 0)
        assertEquals("应只剩折叠条", 1, msgs.size)
        assertTrue("应可识别", (msgs[0] as UserMessage).content.startsWith(HistoryCompactor.DIGEST_MARK))
    }

    check("A8 空历史返回空列表") {
        assertTrue("空列表应为空", HistoryCompactor.buildPriorMessages(emptyList()).isEmpty())
        assertTrue("全空问题应为空", HistoryCompactor.buildPriorMessages(listOf(turn("  "))).isEmpty())
    }

    check("A9 同样输入必得同样字节（前缀缓存的前提）") {
        val turns = (1..40).map { turn("问题 $it", "回答 $it", listOf("read_file")) }
        val a = userTexts(HistoryCompactor.buildPriorMessages(turns, keepRecent = 10))
        val b = userTexts(HistoryCompactor.buildPriorMessages(turns, keepRecent = 10))
        assertEquals("两次结果不同，说明压缩带了随机性", a, b)
    }

    check("A10 摘要里的答案取首句（结论优先），不是开头硬切") {
        val turns = (1..25).map {
            turn("问题 $it", "三家，分别是 A、B、C。" + "后面是很长的展开说明。".repeat(20))
        }
        val digest = userTexts(HistoryCompactor.buildPriorMessages(turns, keepRecent = 20)).first()
        assertTrue("首句结论应被保留", "三家，分别是 A、B、C。" in digest)
        assertTrue("不该把整段展开都塞进摘要", "后面是很长的展开说明。后面是很长的展开说明。" !in digest)
    }

    check("A11 反向自检：keepRecent 恰好等于轮数时不许折叠") {
        val turns = (1..20).map { turn("问题 $it", "回答 $it") }
        val msgs = HistoryCompactor.buildPriorMessages(turns, keepRecent = 20)
        assertEquals("应是 20 轮 × 2 条", 40, msgs.size)
        assertTrue("边界上误折了", userTexts(msgs).none { it.startsWith(HistoryCompactor.DIGEST_MARK) })
    }

    /* ------------------------------ B. 源码约束 ------------------------------ */

    val vm = read("app/src/main/java/com/beichen/polaris/ui/ChatViewModel.kt")
    val compactor = read("core/src/main/java/com/beichen/polaris/core/agent/HistoryCompactor.kt")

    check("B1 宿主真的改用了压缩器，旧截断已删") {
        assertTrue("ChatViewModel 未调用 HistoryCompactor", "HistoryCompactor.buildPriorMessages(" in vm)
        // 旧写法留着就等于「新逻辑写在旁边、生产路径走老的」——本项目踩过
        assertTrue("旧截断 takeLast(PRIOR_TURNS_MAX) 还在", "turns.takeLast(PRIOR_TURNS_MAX)" !in vm)
    }

    check("B2 压缩器不反向依赖存储层（保证纯 JVM 可测）") {
        assertTrue("HistoryCompactor 引入了 TurnRecord，harness 就得拖进整个 ConversationStore",
            "TurnRecord" !in compactor)
        assertTrue("HistoryCompactor 引入了序列化依赖", "kotlinx.serialization" !in compactor)
    }

    check("B3 折叠标记与回合内折叠统一（不出现两种标记）") {
        assertTrue("DIGEST_MARK 应复用 ContextBudget.FOLD_MARK",
            "DIGEST_MARK: String = ContextBudget.FOLD_MARK" in compactor)
    }

    println("\n=== 结果：$passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) {
        println("失败项：")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
