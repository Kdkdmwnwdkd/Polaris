#!/usr/bin/env kotlin
/**
 * Phase 2 · DoD②「消息分支与重新生成」QA 行为验证 harness（Kou）。
 *
 * 被测对象：`core/ConversationBranch.kt`（纯函数变体引擎）+ `core/ConversationStore.kt`
 * 的 turns 字段/迁移/切换/截断（持久化）。
 *
 * 为什么用纯 main() + 手写断言：仓库无测试源集、离线 Gradle 缓存无 JUnit（同 T02-T06
 * harness 既定做法）。运行方式见 scripts/qa/run-branch-verification.sh
 * （kotlin-compiler-embeddable 编译 + java 运行，-Xfriend-paths 访问 internal 符号）。
 *
 * 覆盖（逐条对应需求）：
 *  1) 轮次变体：追加 3 个变体后 selectedIndex 正确、切换边界（首左移不动、末右移不动）；
 *  2) 序列化往返：多变体会话 → 序列化 → 反序列化完全相等（含 selectedIndex）；多次往返幂等；
 *  3) 旧格式迁移：旧 JSON（无 variants 字段）→ 变体数=1、selectedIndex=0、历史不丢；
 *  4) 从中间轮重新发问：后续被截断、轮次序号连续；
 *  5) 选中变体写回历史：A/B 两变体，切到 A 时"下一次请求的历史"含 A 不含 B；
 *  6) 边界：空会话、单轮单变体、异常数据（variants 为空/游标越界 → 兜底而非崩溃）。
 *
 * 另：验证写盘后**重新打开**（模拟杀进程重启）仍能读回同一结构（持久性）。
 */

import com.beichen.polaris.core.ConversationBranch
import com.beichen.polaris.core.ConversationRecord
import com.beichen.polaris.core.ConversationStore
import com.beichen.polaris.core.MsgRecord
import com.beichen.polaris.core.ROLE_ASSISTANT
import com.beichen.polaris.core.ROLE_TOOL
import com.beichen.polaris.core.ROLE_USER
import com.beichen.polaris.core.TurnRecord
import com.beichen.polaris.core.toUiItems
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

private fun <T> assertEquals(name: String, expected: T, actual: T) {
    if (expected != actual) throw AssertionError("$name: expected <$expected> but was <$actual>")
}

private fun assertContains(name: String, haystack: String, needle: String) {
    if (!haystack.contains(needle)) throw AssertionError("$name: expected <$haystack> to contain <$needle>")
}

private fun assertNotContains(name: String, haystack: String, needle: String) {
    if (haystack.contains(needle)) throw AssertionError("$name: expected <$haystack> NOT to contain <$needle>")
}

/* ------------------------------ 构造助手 ------------------------------ */

private fun user(content: String, id: String = "u-$content") = MsgRecord(id, ROLE_USER, content)
private fun asst(content: String, id: String = "a-$content") = MsgRecord(id, ROLE_ASSISTANT, content)

/** 从 [ConversationBranch.flatten] 展开出的「下一次请求的历史」取全部内容文本。 */
private fun historyText(turns: List<TurnRecord>): List<String> =
    ConversationBranch.flatten(turns).map { it.content }

/** 轮询等待 store 的异步落盘完成（单线程执行器 FIFO）——最多 [timeoutMs]。 */
private fun awaitFile(file: File, timeoutMs: Long = 3000, predicate: (String) -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val text = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        if (predicate(text)) return true
        Thread.sleep(20)
    }
    return false
}

/* ------------------------------ 1. 轮次变体 + 边界 ------------------------------ */

private fun testVariantAppendAndBoundary() {
    println("\n== ① 轮次变体：追加变体 + 切换边界 ==")

    check("追加 3 个变体后：变体数=4、selectedIndex 指向最新（3）") {
        var turns = listOf(
            TurnRecord(user = user("问题"), variants = listOf(asst("初始")), selectedIndex = 0)
        )
        turns = ConversationBranch.appendVariant(turns, 0, asst("变体2"))
        turns = ConversationBranch.appendVariant(turns, 0, asst("变体3"))
        assertEquals("after 2 appends idx", 2, turns[0].selectedIndex)
        turns = ConversationBranch.appendVariant(turns, 0, asst("变体4"))
        assertEquals("variants size", 4, turns[0].variants.size)
        assertEquals("selectedIndex", 3, turns[0].selectedIndex)
        assertEquals("old variant kept", "初始", turns[0].variants[0].content)
    }

    check("切换边界：首个左移不动、末个右移不动（clamp）") {
        var turns = listOf(
            TurnRecord(user = user("q"), variants = listOf(asst("A"), asst("B"), asst("C")), selectedIndex = 0)
        )
        // 已在首个：左移不动
        val left = ConversationBranch.select(turns, 0, -1)
        assertEquals("first left no-op", 0, left[0].selectedIndex)
        // 移到末
        turns = ConversationBranch.select(turns, 0, 1)
        turns = ConversationBranch.select(turns, 0, 1)
        assertEquals("reach last", 2, turns[0].selectedIndex)
        // 末个右移不动
        val right = ConversationBranch.select(turns, 0, 1)
        assertEquals("last right no-op", 2, right[0].selectedIndex)
        // 正常左右切换
        assertEquals("mid left", 1, ConversationBranch.select(turns, 0, -1)[0].selectedIndex)
        assertEquals("mid right", 2, ConversationBranch.select(turns, 0, 1)[0].selectedIndex)
    }

    check("追加/切换对越界 turnIndex 安静 no-op（不崩）") {
        val turns = listOf(TurnRecord(user = user("q"), variants = listOf(asst("A")), selectedIndex = 0))
        assertEquals("append invalid", turns, ConversationBranch.appendVariant(turns, 5, asst("X")))
        assertEquals("select invalid", turns, ConversationBranch.select(turns, -1, 1))
    }
}

/* ------------------------------ 2. 序列化往返 ------------------------------ */

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

private fun sampleRecord(): ConversationRecord {
    val t0 = TurnRecord(
        user = user("第一问", "u0"),
        variants = listOf(asst("回答A", "a0a"), asst("回答B", "a0b"), asst("回答C", "a0c")),
        selectedIndex = 1
    )
    val t1 = TurnRecord(user = user("第二问", "u1"), variants = listOf(asst("唯一回答", "a1")), selectedIndex = 0)
    val turns = listOf(t0, t1)
    return ConversationRecord(
        id = "conv-1",
        title = "多变体会话",
        createdAt = 100L,
        updatedAt = 200L,
        messages = ConversationBranch.flatten(turns),
        turns = turns
    )
}

private fun testSerializationRoundTrip() {
    println("\n== ② 序列化往返：完全相等 + 幂等 ==")

    check("多变体会话 序列化→反序列化 完全相等（含 selectedIndex / variants / trace）") {
        val rec = sampleRecord()
        val once = json.decodeFromString<ConversationRecord>(json.encodeToString(rec))
        assertEquals("record equal", rec, once)
        assertEquals("turns equal", rec.turns, once.turns)
        assertEquals("selectedIndex kept", 1, once.turns[0].selectedIndex)
        assertEquals("variant count kept", 3, once.turns[0].variants.size)
    }

    check("同一对象多次往返幂等（值相等 + 字节相等）") {
        val rec = sampleRecord()
        val once = json.decodeFromString<ConversationRecord>(json.encodeToString(rec))
        val twice = json.decodeFromString<ConversationRecord>(json.encodeToString(once))
        assertEquals("value idempotent", once, twice)
        assertEquals("byte idempotent", json.encodeToString(once), json.encodeToString(twice))
    }

    check("写入 store 后重开（模拟杀进程重启）读回同一 turns 结构") {
        val dir = Files.newTempDir("branch-persist")
        val file = File(dir, "conversations.json")
        val store = ConversationStore(file)
        val rec = sampleRecord()
        // 用公开 API 造等价数据：create + 逐条追加
        val id = store.create()
        store.rename(id, rec.title)
        for (t in rec.turns) {
            store.appendMessage(id, t.user)
            t.trace.forEach { store.appendMessage(id, it) }
            t.variants.forEach { store.appendVariant(id, store.load(id)!!.turns.lastIndex, it) }
        }
        // 追加 3 个变体后 selectedIndex=2；左移两次切到 0（验证持久化的是「选中态」而非默认值）
        store.selectVariant(id, 0, -1)
        store.selectVariant(id, 0, -1)
        // R-10 起消息落在分片 conversations.d/<id>.json，index 只留 id 列表（不含正文）；
        // 判据跟着存储布局走，否则会一直等一个永远不含正文的 index 文件。
        val shard = File(File(dir, "conversations.d"), "$id.json")
        assertTrue("shard persisted", awaitFile(shard) { it.contains("回答A") && it.contains("回答C") })

        // 重开：新的 ConversationStore 实例读同一文件
        val reopened = ConversationStore(file)
        val loaded = reopened.load(id)
        assertTrue("loaded not null", loaded != null)
        assertEquals("turn count", 2, loaded!!.turns.size)
        assertEquals("turn0 variants", 3, loaded.turns[0].variants.size)
        assertEquals("selectedIndex round-trip", 0, loaded.turns[0].selectedIndex)
    }
}

/* ------------------------------ 3. 旧格式迁移 ------------------------------ */

private fun testLegacyMigration() {
    println("\n== ③ 旧格式迁移：无 variants 字段 → 变体数=1、游标=0、历史不丢 ==")

    val legacyJson = """
        [
          {
            "id": "legacy-1",
            "title": "旧对话",
            "createdAt": 1,
            "updatedAt": 2,
            "messages": [
              {"id": "u1", "role": "user", "content": "你好"},
              {"id": "a1", "role": "assistant", "content": "你好呀，有什么可以帮你"}
            ]
          }
        ]
    """.trimIndent()

    check("fromMessages：每个旧会话变体数=1、selectedIndex=0") {
        val msgs = listOf(user("你好", "u1"), asst("你好呀，有什么可以帮你", "a1"))
        val turns = ConversationBranch.fromMessages(msgs)
        assertEquals("turn count", 1, turns.size)
        assertEquals("variant count", 1, turns[0].variants.size)
        assertEquals("selectedIndex", 0, turns[0].selectedIndex)
        assertEquals("user kept", "你好", turns[0].user.content)
        assertEquals("assistant kept", "你好呀，有什么可以帮你", turns[0].variants[0].content)
    }

    check("旧 JSON 读入 store：变体数=1、selectedIndex=0、messages 不丢") {
        val dir = Files.newTempDir("branch-legacy")
        val file = File(dir, "conversations.json")
        file.writeText(legacyJson)
        val store = ConversationStore(file)
        val loaded = store.load("legacy-1")
        assertTrue("load ok", loaded != null)
        assertEquals("turn count", 1, loaded!!.turns.size)
        assertEquals("variant count", 1, loaded.turns[0].variants.size)
        assertEquals("selectedIndex", 0, loaded.turns[0].selectedIndex)
        // 历史不丢：messages 原样保留，flatten 也恒等
        assertEquals("messages size kept", 2, loaded.messages.size)
        assertEquals("messages flatten identical", loaded.messages, ConversationBranch.flatten(loaded.turns))
        assertContains("user content", loaded.messages[0].content, "你好")
        assertContains("assistant content", loaded.messages[1].content, "你好呀")
    }

    check("迁移含工具痕迹的旧历史：tools 进 trace，flatten 还原原序") {
        val msgs = listOf(
            user("查天气", "u1"),
            MsgRecord("t1", ROLE_TOOL, "北京晴 26℃", toolName = "weather"),
            asst("北京今天晴，26℃。", "a1")
        )
        val turns = ConversationBranch.fromMessages(msgs)
        assertEquals("variant count", 1, turns[0].variants.size)
        assertEquals("trace size", 1, turns[0].trace.size)
        assertEquals("flatten reproduces order", msgs, ConversationBranch.flatten(turns))
    }
}

/* ------------------------------ 4. 从中间轮重新发问 ------------------------------ */

private fun testReaskFromMiddle() {
    println("\n== ④ 从中间轮重新发问：后续被截断、轮次序号连续 ==")

    check("reaskAfter：保留到第 K 轮（含），截断其后，再新开一轮") {
        val turns = (0..3).map { TurnRecord(user = user("Q$it"), variants = listOf(asst("A$it")), selectedIndex = 0) }
        val re = ConversationBranch.reaskAfter(turns, 1, user("新问题"))
        assertEquals("size", 3, re.size)
        assertEquals("keep Q0", "Q0", re[0].user.content)
        assertEquals("keep Q1", "Q1", re[1].user.content)
        assertEquals("new turn", "新问题", re[2].user.content)
        // 轮次连续：0..2
        assertTrue("indices continuous", re.indices.toList() == listOf(0, 1, 2))
    }

    check("store.truncateAfter：保留到该轮（含），丢弃其后轮次") {
        val dir = Files.newTempDir("branch-reask")
        val file = File(dir, "conversations.json")
        val store = ConversationStore(file)
        val id = store.create()
        for (i in 0..3) {
            store.appendMessage(id, user("Q$i"))
            store.appendMessage(id, asst("A$i"))
        }
        val remaining = store.truncateAfter(id, 1)
        assertEquals("remaining turns", 2, remaining)
        val loaded = store.load(id)!!
        assertEquals("loaded turns", 2, loaded.turns.size)
        assertEquals("turn0", "Q0", loaded.turns[0].user.content)
        assertEquals("turn1", "Q1", loaded.turns[1].user.content)
        // 再新开一轮（equals 后续 send 追加 user）
        store.appendMessage(id, user("新问题"))
        assertEquals("turns after new ask", 3, store.load(id)!!.turns.size)
    }

    check("reaskAfter 越界 index 不崩（clamp 到 [0,size]）") {
        val turns = listOf(TurnRecord(user = user("Q0"), variants = listOf(asst("A0")), selectedIndex = 0))
        assertEquals("index>=size → 全留 + 新轮", 2, ConversationBranch.reaskAfter(turns, 99, user("X")).size)
        assertEquals("index<0 → 全截 + 新轮", 1, ConversationBranch.reaskAfter(turns, -5, user("Y")).size)
    }
}

/* ------------------------------ 5. 选中变体写回历史 ------------------------------ */

private fun testSelectedVariantWritesHistory() {
    println("\n== ⑤ 选中变体写回历史：切到 A → 历史含 A 不含 B ==")

    check("flatten 只出选中变体（切 A 含 A 不含 B；切 B 反之）") {
        val turn = TurnRecord(user = user("q"), variants = listOf(asst("ANSWER-A"), asst("ANSWER-B")), selectedIndex = 0)
        val selA = listOf(turn)
        val selB = ConversationBranch.select(selA, 0, 1)
        val textA = historyText(selA).joinToString("|")
        val textB = historyText(selB).joinToString("|")
        assertContains("history has A", textA, "ANSWER-A")
        assertNotContains("history excludes B", textA, "ANSWER-B")
        assertContains("history has B", textB, "ANSWER-B")
        assertNotContains("history excludes A", textB, "ANSWER-A")
    }

    check("store.selectVariant 把选中变体写回 messages（下一次请求的历史）") {
        val dir = Files.newTempDir("branch-select")
        val file = File(dir, "conversations.json")
        val store = ConversationStore(file)
        val id = store.create()
        store.appendMessage(id, user("q"))
        store.appendVariant(id, store.load(id)!!.turns.lastIndex, asst("ANSWER-A"))
        store.appendVariant(id, store.load(id)!!.turns.lastIndex, asst("ANSWER-B"))
        // 当前选中 B（追加即选最新）
        assertEquals("selected B", 1, store.load(id)!!.turns[0].selectedIndex)
        // 切到 A
        val idx = store.selectVariant(id, 0, -1)
        assertEquals("selected A", 0, idx)
        val msgs = store.load(id)!!.messages
        val text = msgs.joinToString("|") { it.content }
        assertContains("messages has A", text, "ANSWER-A")
        assertNotContains("messages excludes B", text, "ANSWER-B")
    }

    check("ConversationRecord.toUiItems：助手气泡带变体游标元数据（variantIndex/Count/turnIndex）") {
        val rec = sampleRecord()
        val items = rec.toUiItems()
        val asst = items.filterIsInstance<com.beichen.polaris.core.UiItem.Assistant>()
        assertTrue("has assistant", asst.isNotEmpty())
        val first = asst.first()
        assertEquals("variantIndex", 1, first.variantIndex)
        assertEquals("variantCount", 3, first.variantCount)
        assertEquals("turnIndex", 0, first.turnIndex)
        // 展示的即选中变体内容
        assertEquals("selected content", "回答B", first.text)
    }
}

/* ------------------------------ 6. 容量上限 ------------------------------ */

/**
 * 轮数上限：超过 [ConversationStore.MAX_TURNS] 时丢最旧轮，并把丢弃数回传给调用方。
 *
 * 为什么这条要单独测：截断是这个类里**唯一会丢用户数据**的路径。旧实现静默丢掉，
 * `appendMessage` 返回 Unit，界面没有任何办法知道「刚才悄悄删了一轮」。
 * 这里把三件事钉死：恰好到上限不丢、超一轮丢一轮、丢完仍是上限且丢的是最早那条。
 */
private fun testTurnCap() {
    check("E1 超过 MAX_TURNS：丢最旧轮并回传丢弃数") {
        val dir = Files.newTempDir("branch-cap")
        val store = ConversationStore(File(dir, "conversations.json"))
        val id = store.create()
        val cap = ConversationStore.MAX_TURNS

        var dropped = -1
        repeat(cap) { i ->
            dropped = store.appendMessage(id, MsgRecord("u$i", ROLE_USER, "q$i"))
        }
        assertEquals("刚好 $cap 轮不该丢", 0, dropped)

        dropped = store.appendMessage(id, MsgRecord("u-over", ROLE_USER, "q-over"))
        assertEquals("第 ${cap + 1} 轮应丢 1 轮", 1, dropped)

        val turns = store.load(id)!!.turns
        assertEquals("总轮数封顶", cap, turns.size)
        assertEquals("丢的是最早那轮", "q1", turns.first().user.content)
        assertEquals("留的是最新那轮", "q-over", turns.last().user.content)
    }
}

/* ------------------------------ 7. 边界 ------------------------------ */

private fun testBoundaries() {
    println("\n== ⑥ 边界：空会话 / 单变体 / 异常数据 ==")

    check("空会话：normalize/flatten/fromMessages 全为空，不崩") {
        assertEquals("normalize empty", emptyList<TurnRecord>(), ConversationBranch.normalize(emptyList()))
        assertEquals("flatten empty", emptyList<MsgRecord>(), ConversationBranch.flatten(emptyList()))
        assertEquals("fromMessages empty", emptyList<TurnRecord>(), ConversationBranch.fromMessages(emptyList()))
    }

    check("单轮单变体：左右切换原地不动（无更多变体）") {
        val turns = listOf(TurnRecord(user = user("q"), variants = listOf(asst("only")), selectedIndex = 0))
        assertEquals("single left no-op", turns, ConversationBranch.select(turns, 0, -1))
        assertEquals("single right no-op", turns, ConversationBranch.select(turns, 0, 1))
    }

    check("异常数据：variants 为空 → 游标兜底 0、flatten 只出 user(+trace)，不崩") {
        val bad = listOf(TurnRecord(user = user("q"), variants = emptyList(), selectedIndex = 5))
        val norm = ConversationBranch.normalize(bad)
        assertEquals("clamp to 0", 0, norm[0].selectedIndex)
        assertEquals("flatten = [user]", listOf("q"), historyText(bad))
    }

    check("异常数据：selectedIndex 越界（>size）→ normalize 夹到末尾合法值") {
        val bad = listOf(
            TurnRecord(user = user("q"), variants = listOf(asst("A"), asst("B")), selectedIndex = 99)
        )
        assertEquals("clamped", 1, ConversationBranch.normalize(bad)[0].selectedIndex)
        // flatten 对未规范化输入也不崩：越界 → getOrNull=null → 只出 user
        assertEquals("flatten out-of-range safe", 1, ConversationBranch.flatten(bad).size)
    }

    check("空会话读入 store：turns 为空、list/load 不崩") {
        val dir = Files.newTempDir("branch-empty")
        val file = File(dir, "conversations.json")
        file.writeText("[]")
        val store = ConversationStore(file)
        assertEquals("no conversations", 0, store.list().size)
        assertEquals("load missing null", null, store.load("nope"))
        // ensureNotEmpty 会新建一个空会话
        val id = store.ensureNotEmpty()
        assertEquals("new empty turns", 0, store.load(id)!!.turns.size)
    }
}

/* ------------------------------ 主入口 ------------------------------ */

fun main() {
    println("=== Phase 2 DoD② 消息分支与重新生成 · BranchVerification ===")
    testVariantAppendAndBoundary()
    testSerializationRoundTrip()
    testLegacyMigration()
    testReaskFromMiddle()
    testSelectedVariantWritesHistory()
    testTurnCap()
    testBoundaries()

    println("\n=== 结果：$passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}

/** 临时目录工具（避免依赖外部库）。 */
private object Files {
    fun newTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }
}
