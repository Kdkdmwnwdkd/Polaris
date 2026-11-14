#!/usr/bin/env kotlin
/**
 * R-06「中断后残留执行中工具卡」行为验证 harness。
 *
 * ## 被测的是行为，不是字符串
 * 前面几轮的断言大多是源码级 grep。这一条不行：缺陷的全部内容就是「两份数据在不同
 * 时机被写成不同状态」，grep 只能证明「某函数存在」，证明不了「切走再切回那张卡
 * 到底显示什么」。所以这里直接编译真实的
 * [ConversationStore] / [ConversationProjection] / [GenerationRunController] 跑一遍。
 *
 * ## 覆盖
 *  A. 占位 → 收口：pending=false、结果补成可见文案、幂等、落盘往返
 *  B. 反向：**正常返回空输出**的工具（终态、pending=false）不得被改成「已中断」
 *  C. 兼容：旧文件没有 pending 字段 → false（按终态看待，行为与改动前一致）
 *  D. 投影 loadOf：没有活运行时 pending 呈现为「已中断」，有活运行（切回来）时仍是执行中
 *  E. settleInterrupted：显示态与存储都翻；用户切走时（显示的是别的会话）不误改显示列表
 *  F. 运行控制器：收口发生在 owner 清空之前；收口抛异常不影响复位（否则发送键永久灰）
 *
 * 运行：scripts/qa/run-r06-verification.sh（kotlinc 编译源码 + java 运行）
 */

import com.beichen.polaris.core.ConversationRecord
import com.beichen.polaris.core.ConversationStore
import com.beichen.polaris.core.MsgRecord
import com.beichen.polaris.core.ROLE_ASSISTANT
import com.beichen.polaris.core.ROLE_TOOL
import com.beichen.polaris.core.ROLE_USER
import com.beichen.polaris.core.TOOL_INTERRUPTED_NOTE
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.UiItem
import com.beichen.polaris.core.toMsgRecord
import com.beichen.polaris.ui.ConversationProjection
import com.beichen.polaris.ui.GenerationRunController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.File

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

fun check(name: String, body: () -> Unit) {
    try {
        body()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += "$name — ${t.message}"
        println("  FAIL  $name — ${t.message}")
    }
}

fun expect(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

fun <T> eq(actual: T, expected: T, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 <$expected>，实际 <$actual>")
}

/* ------------------------------ 工具函数 ------------------------------ */

/**
 * 落盘并等它真的写完。
 *
 * `ConversationStore.flush()` 是**异步**的（把任务插进单线程调度器队列），
 * 写完才知道——所以不能「flush 完立刻读文件」，那是用例自己制造的竞态。
 */
private fun flushAndWait(store: ConversationStore) {
    store.flush()
    // 往**同一个单线程**调度器投一个空任务：FIFO 保证它执行时，此前排队的写必然已跑完。
    //
    // 原来的写法是「等 persistCount 不再变化」——那是在赌调度器跑得够快：
    // 写还没开始执行时计数当然不变，于是这里提前返回，写随后才完成。
    // 2026-11-14 本轮全量回归里 E3 偶发红过一次（连跑三次又全绿），就是这个竞态。
    // ConversationStore.awaitPersist 正是为这件事准备的（它的注释写着同一句话）。
    store.awaitPersist()
}

/**
 * 轮询「重开文件后能读到指定工具记录」，最多等 [timeoutMs]。
 *
 * 为什么不用固定 sleep：写盘是异步的，`persistCount` 稳定 ≠ 文件已落盘（见 A5 注释）。
 * 为什么不用更长的固定 sleep：那只是把窗口开大，负载更高时照样漏，而且每次都白等。
 * 轮询到条件成立即返回，既不掩盖慢、也不制造假红。
 *
 * [until] 是**必须**的：只等「记录存在」会漏掉「存在但还是旧状态」——
 * settlePendingTools 之后文件里先有的是那条 running=true 的旧记录，20ms 后新记录才盖上，
 * 只要轮询在中间那一刻命中，就会拿着旧值去断言终态，报出来的却是产品落盘坏了。
 * 2026-11-11 CI #680 就是这样红的（同一份代码 #677/#679 全绿）。
 * 超时返回**最后读到的**记录（可能不满足 [until]），让调用方能区分
 * 「一次都没读到」和「读到了但一直不是终态」。
 */
private fun awaitToolRecord(
    file: java.io.File,
    convId: String,
    callId: String,
    timeoutMs: Long,
    until: (com.beichen.polaris.core.MsgRecord) -> Boolean = { true }
): com.beichen.polaris.core.MsgRecord? {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: com.beichen.polaris.core.MsgRecord? = null
    while (System.currentTimeMillis() < deadline) {
        val rec = runCatching { ConversationStore(file).load(convId)?.toolRecord(callId) }.getOrNull()
        if (rec != null) {
            last = rec
            if (until(rec)) return rec
        }
        Thread.sleep(20)
    }
    return last
}

/** 等一个 Job 结束（harness 是普通 main，只能借 runBlocking 挂起）。 */
private fun awaitJob(j: Job?) = runBlocking { j?.join() }

private var tmpSeq = 0

/** 最近一次 [freshStore] 建出来的文件（「重新打开」类用例要用同一个路径）。 */
private lateinit var lastStoreFile: File

/** 每个用例独立目录：用例之间不能共享文件（否则前一个用例的残留就是后一个的假绿来源）。 */
fun freshStore(): ConversationStore {
    val dir = File("/tmp/r06-harness/c${tmpSeq++}")
    dir.deleteRecursively()
    dir.mkdirs()
    lastStoreFile = File(dir, "conversations.json")
    return ConversationStore(lastStoreFile)
}

fun toolItem(id: String, result: String, running: Boolean, isError: Boolean = false) =
    UiItem.Tool(
        call = ToolCall(id, "run_shell", JsonObject(emptyMap())),
        result = result,
        isError = isError,
        running = running
    )

fun ConversationRecord.toolRecord(id: String): MsgRecord? =
    turns.flatMap { it.trace }.firstOrNull { it.role == ROLE_TOOL && it.id == id }

/* ------------------------------ 用例 ------------------------------ */

fun main() {
    println("=== R-06 中断收口 ===")

    // ── A 占位 → 收口 ────────────────────────────────────────────
    println("\n[A] 执行中占位 → 收口")

    check("A1 ToolStarted 落盘的是 pending=true 的占位") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "列一下目录"))
        store.appendMessage(conv, toolItem("call-1", "", running = true).toMsgRecord())
        val rec = store.load(conv)!!.toolRecord("call-1") ?: throw AssertionError("占位没落盘")
        eq(rec.pending, true, "占位的 pending")
        eq(rec.content, "", "占位的内容")
    }

    check("A2 settlePendingTools 命中并返回条数") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "列一下目录"))
        store.appendMessage(conv, toolItem("call-1", "", running = true).toMsgRecord())
        eq(store.settlePendingTools(conv), 1, "收口条数")
    }

    check("A3 收口后：pending 清掉、有可见文案、标为异常（不是成功也不是空白）") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "列一下目录"))
        store.appendMessage(conv, toolItem("call-1", "", running = true).toMsgRecord())
        store.settlePendingTools(conv)
        val rec = store.load(conv)!!.toolRecord("call-1")!!
        eq(rec.pending, false, "收口后的 pending")
        eq(rec.isError, true, "收口后的 isError")
        eq(rec.content, TOOL_INTERRUPTED_NOTE, "收口后的内容")
    }

    check("A4 幂等：再收一次返回 0，内容不变") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "列一下目录"))
        store.appendMessage(conv, toolItem("call-1", "", running = true).toMsgRecord())
        store.settlePendingTools(conv)
        eq(store.settlePendingTools(conv), 0, "第二次收口条数")
        eq(store.load(conv)!!.toolRecord("call-1")!!.content, TOOL_INTERRUPTED_NOTE, "内容被二次改写")
    }

    check("A5 落盘往返：重新打开文件后仍是终态（不是 pending）") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "列一下目录"))
        store.appendMessage(conv, toolItem("call-1", "", running = true).toMsgRecord())
        store.settlePendingTools(conv)
        flushAndWait(store)

        // 判据不能是「flush 之后立刻重开」：flush() 是**排一次写**（异步，去抖窗口外尽快执行），
        // 而 flushAndWait 只能观察 persistCount 的变化。机器负载一高（并行跑全量 harness 时
        // 很容易），写线程还没轮到，计数就已经「稳定」了——于是这里重开读到半截文件，
        // 报出来是「重开读不到记录」，看起来像产品的落盘坏了。
        // 2026-11-05 实测过一次：单独跑 24/24 全过，并行跑就红这一条。
        //
        // 现在改成**轮询到终态**（3 秒上限）：本条要验的是「往返之后仍是终态」，
        // 不是「写盘有多快」。只等「记录出现」不够——旧记录（running=true）先落盘、
        // 新记录后覆盖，中间命中就会拿旧值断言终态（#680 的假红正是这条）。
        val rec = awaitToolRecord(lastStoreFile, conv, "call-1", timeoutMs = 3_000) { !it.pending }
            ?: throw AssertionError("重开读不到记录（3 秒内文件里始终没有这条）")
        eq(rec.pending, false, "重开后的 pending（3 秒内未等到终态，最后读到的仍是 pending）")
        eq(rec.content, TOOL_INTERRUPTED_NOTE, "重开后的内容")
        // 反证：同一个轮询函数对一个**不存在**的记录必须返回 null。
        // 少了这一句，「轮询到出现」就有可能退化成「永远返回点什么」，A5 变成恒绿。
        expect(
            awaitToolRecord(lastStoreFile, conv, "no-such-call", timeoutMs = 200) == null,
            "轮询函数对不存在的记录也返回了内容——判据失效"
        )
    }

    // ── B 反向：正常空输出不得被误改 ─────────────────────────────
    println("\n[B] 反向：空结果的真终态")

    check("B1 工具正常返回空输出（pending=false）不被当成中断") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "跑个静默命令"))
        store.appendMessage(conv, toolItem("call-2", "", running = false).toMsgRecord())
        eq(store.settlePendingTools(conv), 0, "收口条数（应为 0）")
        val rec = store.load(conv)!!.toolRecord("call-2")!!
        eq(rec.content, "", "空输出被改写了")
        eq(rec.isError, false, "空输出被标成异常")
    }

    check("B2 同一会话里一真一假：只收 pending 那条") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "两个工具"))
        store.appendMessage(conv, toolItem("ok-1", "", running = false).toMsgRecord())
        store.appendMessage(conv, toolItem("bad-1", "", running = true).toMsgRecord())
        eq(store.settlePendingTools(conv), 1, "收口条数")
        val r = store.load(conv)!!
        eq(r.toolRecord("ok-1")!!.content, "", "正常空输出被改")
        eq(r.toolRecord("bad-1")!!.content, TOOL_INTERRUPTED_NOTE, "中断那条没被补")
    }

    check("B3 已结束的回合 + 新一轮占位：只收新一轮那条（不跨轮误伤）") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "第一轮"))
        store.appendMessage(conv, toolItem("t1", "", running = false).toMsgRecord())
        store.appendMessage(conv, MsgRecord("a1", ROLE_ASSISTANT, "好的"))
        store.appendMessage(conv, MsgRecord("u2", ROLE_USER, "第二轮"))
        store.appendMessage(conv, toolItem("t2", "", running = true).toMsgRecord())
        eq(store.settlePendingTools(conv), 1, "收口条数")
        val r = store.load(conv)!!
        eq(r.toolRecord("t1")!!.content, "", "上一轮的终态被改")
        eq(r.toolRecord("t2")!!.content, TOOL_INTERRUPTED_NOTE, "本轮的占位没补")
    }

    // ── C 兼容旧文件 ─────────────────────────────────────────────
    println("\n[C] 旧文件兼容")

    check("C1 旧 JSON 没有 pending 字段 → 按终态看待（false）") {
        val dir = File("/tmp/r06-harness/legacy"); dir.deleteRecursively(); dir.mkdirs()
        val f = File(dir, "conversations.json")
        f.writeText(
            """[{"id":"c-legacy","title":"旧会话","createdAt":1,"updatedAt":2,""" +
                """"messages":[],"turns":[{"user":{"id":"u1","role":"user","content":"旧问题"},""" +
                """"variants":[],"selectedIndex":0,"trace":[{"id":"call-old","role":"tool",""" +
                """"content":"旧结果","toolName":"run_shell","toolArgs":"{}"}]}]}]"""
        )
        val store = ConversationStore(f)
        val rec = store.load("c-legacy")!!.toolRecord("call-old")!!
        eq(rec.pending, false, "旧记录的 pending")
        eq(store.settlePendingTools("c-legacy"), 0, "旧文件被误收口")
    }

    // ── D 投影 loadOf ────────────────────────────────────────────
    println("\n[D] 投影：重新载入该显示什么")

    fun projectionOn(
        store: ConversationStore,
        active: () -> String,
        owner: () -> String?
    ) = ConversationProjection(
        store = store,
        activeId = active,
        ownerId = owner,
        ownerActive = { owner() != null && owner() == active() },
        onStoreChanged = {},
        lock = Any()
    )

    check("D1 没有活运行：pending 记录呈现为「已中断」，不转圈、不谎称已完成") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "列目录"))
        store.appendMessage(conv, toolItem("call-1", "", running = true).toMsgRecord())
        val p = projectionOn(store, { conv }, { null })
        val item = p.loadOf(conv).filterIsInstance<UiItem.Tool>().first()
        eq(item.running, false, "running")
        eq(item.interrupted, true, "interrupted")
        eq(item.result, TOOL_INTERRUPTED_NOTE, "可见文案")
    }

    check("D2 有活运行且 owner 就是这条会话：仍显示执行中（切走再切回应继续转圈）") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "列目录"))
        store.appendMessage(conv, toolItem("call-1", "", running = true).toMsgRecord())
        val p = projectionOn(store, { conv }, { conv })
        val item = p.loadOf(conv).filterIsInstance<UiItem.Tool>().first()
        eq(item.running, true, "running")
        eq(item.interrupted, false, "interrupted")
    }

    check("D3 活运行在**别的**会话：这条会话的 pending 仍是「已中断」") {
        val store = freshStore()
        val a = store.create()
        val b = store.create()
        store.appendMessage(a, MsgRecord("u1", ROLE_USER, "A 的请求"))
        store.appendMessage(a, toolItem("call-a", "", running = true).toMsgRecord())
        val p = projectionOn(store, { a }, { b })
        val item = p.loadOf(a).filterIsInstance<UiItem.Tool>().first()
        eq(item.interrupted, true, "interrupted")
    }

    // ── E settleInterrupted ──────────────────────────────────────
    println("\n[E] 收口：显示态 + 存储")

    check("E1 owner 正在显示：显示列表里的转圈卡当场翻成已中断，存储同时补齐") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "列目录"))
        store.appendMessage(conv, toolItem("call-1", "", running = true).toMsgRecord())
        var owner: String? = conv
        val p = projectionOn(store, { conv }, { owner })
        p.reloadActive()
        eq(p.items.value.filterIsInstance<UiItem.Tool>().first().running, true, "重建后应先转圈（还有活运行）")
        val n = p.settleInterrupted(conv)
        eq(n, 1, "收口条数")
        val shown = p.items.value.filterIsInstance<UiItem.Tool>().first()
        eq(shown.running, false, "显示态 running")
        eq(shown.interrupted, true, "显示态 interrupted")
        eq(shown.result, TOOL_INTERRUPTED_NOTE, "显示态文案")
        owner = null
        eq(store.load(conv)!!.toolRecord("call-1")!!.pending, false, "存储层 pending")
    }

    check("E2 用户切走了（显示的是别的会话）：显示列表不动，只补 owner 会话的存储") {
        val store = freshStore()
        val a = store.create()
        val b = store.create()
        store.appendMessage(a, MsgRecord("u1", ROLE_USER, "A 的请求"))
        store.appendMessage(a, toolItem("call-a", "", running = true).toMsgRecord())
        val p = projectionOn(store, { b }, { a })
        p.reloadActive() // 显示 B（空会话）
        val n = p.settleInterrupted(a)
        eq(n, 1, "收口条数（显示态里没有这张卡，只有存储里那条）")
        eq(p.items.value.size, 0, "B 的显示列表被误改")
        eq(store.load(a)!!.toolRecord("call-a")!!.pending, false, "A 的占位没补")
        eq(store.load(a)!!.toolRecord("call-a")!!.content, TOOL_INTERRUPTED_NOTE, "A 的文案")
    }

    check("E3 没有在途工具时返回 0（不写盘、不抖动）") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "你好"))
        store.appendMessage(conv, MsgRecord("a1", ROLE_ASSISTANT, "你好"))
        flushAndWait(store)
        val before = store.persistCount
        val p = projectionOn(store, { conv }, { null })
        eq(p.settleInterrupted(conv), 0, "收口条数")
        // 关键：等队列**排空**再比计数。原来靠固定 sleep，落盘若落在窗口外就被漏掉，
        // 断言就成了「有没有恰好在 100ms 内写完」的抽奖。
        store.awaitPersist()
        eq(store.persistCount, before, "无变更却触发了落盘")
    }

    // ── F 运行控制器：收口时机 ───────────────────────────────────
    println("\n[F] 运行控制器：owner 清空之前收口")

    check("F1 onSettling 收到 owner，且此时 owner 仍然有效；顺序在复位之前") {
        val events = mutableListOf<String>()
        var ownerAtSettle: String? = "<未调用>"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var gen: GenerationRunController
        gen = GenerationRunController(
            scope = scope,
            activeId = { "conv-1" },
            onFinished = { events += "finished" },
            onSettling = { who ->
                // 在回调里读 controller.owner：这里必须是本轮的会话，而不是 null
                ownerAtSettle = gen.owner
                events += "settling:$who"
            },
        )
        gen.start("conv-1", placeholderDraft = false, rebuildMessages = true) { }
        awaitJob(gen.job)
        eq(ownerAtSettle, "conv-1", "收口时 controller.owner")
        eq(events.first(), "settling:conv-1", "事件顺序")
        eq(gen.running.value, false, "复位后的 running")
    }

    check("F2 收口抛异常不影响复位（否则发送键永久灰）") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var finished = false
        val gen = GenerationRunController(
            scope = scope,
            activeId = { "conv-1" },
            onFinished = { finished = true },
            onSettling = { throw IllegalStateException("收口炸了") },
        )
        gen.start("conv-1", placeholderDraft = false, rebuildMessages = true) { }
        awaitJob(gen.job)
        eq(gen.running.value, false, "running")
        eq(gen.owner, null, "owner")
        eq(finished, true, "onFinished 仍应执行")
    }

    check("F3 取消路径同样收口（用户点停止）") {
        var settledOwner: String? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gen = GenerationRunController(
            scope = scope,
            activeId = { "conv-1" },
            onFinished = { },
            onSettling = { settledOwner = it },
        )
        gen.start("conv-1", placeholderDraft = false, rebuildMessages = true) {
            kotlinx.coroutines.delay(5_000)
        }
        Thread.sleep(60)
        val job = gen.job
        gen.cancel()
        awaitJob(job)
        eq(settledOwner, "conv-1", "取消路径的收口 owner")
    }

    // ── 汇总 ────────────────────────────────────────────────────
    println()
    println("=== $passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
