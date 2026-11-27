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
import com.beichen.polaris.ui.TraceOwnership
import com.beichen.polaris.ui.TraceState
import com.beichen.polaris.ui.traceAt
import com.beichen.polaris.ui.traceOwnership
import com.beichen.polaris.ui.traceRunAt
import com.beichen.polaris.ui.withLiveDraft
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

    // ── [H] 过程流聚合：思考与工具按发生顺序连成一条 ──────────────
    // 2026-11-26：原来只有「相邻工具」才聚在一起，思考被切成独立的一块。
    // 这条规则全是边界判断（段在哪断、正文那条算不算、纯问答要不要折），
    // 错哪一种都不报错，只是少一段或重复一段。所以直接跑真函数。
    println()
    println("[H] 过程流：思考与工具交错")

    val hUser = UiItem.User("换个法子再试")
    val hThink1 = UiItem.Assistant(text = "", reasoning = "先探一下网络能力")
    val hThink2 = UiItem.Assistant(text = "", reasoning = "GET 带 body 不行，换 POST")
    val hAnswer = UiItem.Assistant(text = "我按你的要求请求了……", reasoning = "四条都失败了，如实说明")
    val hTool1 = toolItem("t1", "ok", running = false)
    val hTool2 = toolItem("t2", "boom", running = false, isError = true)

    check("H1 思考→工具→思考→工具→思考，连成一条并按顺序排") {
        val msgs = listOf(hUser, hThink1, hTool1, hThink2, hTool2, hAnswer)
        val flow = traceRunAt(msgs, 1)
        expect(flow != null, "段首应当拿得到流")
        flow!!
        eq(flow.steps.size, 5, "步数（3 段思考 + 2 次工具）")
        eq(
            flow.steps.map { it.title },
            listOf("思考", "run_shell", "思考", "run_shell", "思考"),
            "步骤顺序必须与发生顺序一致，不能把思考全排到工具前面"
        )
        eq(flow.toolCount, 2, "工具数")
        eq(flow.failedCount, 1, "失败数")
        eq(flow.range, 1..5, "段区间")
        eq(flow.bodyIndex, 5, "交出正文的那条是段尾")
        // 「两条无正文的思考整条吸收」由 H2 的 ownership 断言：从区间 + 正文有无就能
        // 现算谁被吸收，不再需要一份独立的下标集合（那种第二份状态正是 902 的错误来源）。
    }

    check("H2 段内非首条不重复渲染") {
        val msgs = listOf(hUser, hThink1, hTool1, hThink2, hTool2, hAnswer)
        expect(traceRunAt(msgs, 2) == null, "段内的工具不该自己再开一段")
        expect(traceRunAt(msgs, 3) == null, "段内的思考不该自己再开一段")
        // 段首若被判成「被吸收」，整条流就没人画了——段首必须是 NONE（自己渲染流）。
        eq(traceOwnership(msgs, 1), TraceOwnership.NONE, "段首自己负责渲染流，不能被吸收")
        eq(traceOwnership(msgs, 3), TraceOwnership.ABSORBED, "无正文的思考：整条跳过")
        eq(traceOwnership(msgs, 5), TraceOwnership.TAIL, "有正文的那条：只渲染正文")
    }

    check("H3 没有工具的问答不折成流") {
        val msgs = listOf(hUser, UiItem.Assistant(text = "答案是 42", reasoning = "先算一遍"))
        expect(traceRunAt(msgs, 1) == null, "纯问答不该变成折叠的过程")
        eq(traceOwnership(msgs, 1), TraceOwnership.NONE, "思考仍按正文上方一行渲染")
    }

    // H4 三改（2026-11-27）。
    //
    // 一改：`while (next is Tool)` 只收相邻工具 → 思考与工具切成两块。
    // 二改：反过来把所有「交出正文的助手消息」当墙 → 修好了多段，但误伤了
    //   同轮「正文 + tool_call」（`[A(r=有,t=有)] [Tool]`），又变回两块。
    // 三改（现在）：**墙只有用户消息**。两轮之间必然隔着 User，`isProcessEntry`
    //   已经天然断开；再拿正文当墙就是多此一举，而代价是真实形态被切碎。
    check("H4 墙是用户消息：同轮正文与工具合并，正文仍自己渲染") {
        // ① 同轮：一条助手消息既说了话、又要调工具
        val same = listOf(hUser, hAnswer, hTool1)
        val flow = traceRunAt(same, 1)
        expect(flow != null, "同轮的正文与工具应当成一条流（否则思考与工具分开）")
        eq(flow!!.range, 1..2, "段应覆盖正文与工具两条")
        eq(flow.steps.size, 2, "流里应有两步：思考 + 工具")
        // 段首带正文：自己渲染流，正文在流下方照常出现——不能被判成「已吸收」
        eq(traceOwnership(same, 1), TraceOwnership.NONE, "段首的正文必须还在，不能被吸收")
        expect(traceRunAt(same, 2) == null, "段内的工具不该自己再开一段")

        // ② 隔了一条用户消息 = 真的下一轮，必须断开
        val nextTurn = listOf(hUser, hAnswer, hUser, hTool1)
        expect(traceRunAt(nextTurn, 1) != null || traceRunAt(nextTurn, 3) != null, "两轮里至少各成一段")
        eq(traceRunAt(nextTurn, 2), null, "用户消息不成段")
        val second = traceRunAt(nextTurn, 3)
        expect(second != null, "第二轮的工具应当自成一（新）段")
        eq(second!!.range, 3..3, "新段只含第二轮的工具")
        eq(second.bodyIndex, -1, "新段没有正文尾巴")
    }

    check("H8 同轮正文落在段中：思考进流，正文留在原地") {
        // 真实形态：think → tool → 「我再查一次」+ tool → 结论
        val msgs = listOf(
            hUser,
            hThink1,
            hTool1,
            UiItem.Assistant(text = "再查一次。", reasoning = "结果不够"),
            hTool2,
            hAnswer
        )
        val flow = traceRunAt(msgs, 1)
        expect(flow != null, "整段应当合成一条流")
        eq(flow!!.range, 1..5, "区间要覆盖到最后的正文")
        eq(
            flow.steps.map { it.title },
            listOf("思考", "run_shell", "思考", "说明", "run_shell", "思考"),
            "途中说明紧跟在它自己的思考之后，位置就是它说出口的位置"
        )
        // 2026-11-27 四改：**只有最后一条有正文的是回答**。
        // 「再查一次。」这种说到一半就接着调工具的话，属于过程，不是一条回复：
        // 它若自己渲染成气泡，一轮里就会冒出两个「回答」，思考被夹在中间。
        eq(traceOwnership(msgs, 3), TraceOwnership.ABSORBED, "途中说明整条进流（正文变成一步）")
        eq(traceOwnership(msgs, 5), TraceOwnership.TAIL, "段尾的最终回答才开始渲染正文")
        eq(flow.bodyIndex, 5, "本轮交出的正文只有最后那条")
    }

    // H9/H10（2026-11-27 真机反馈）：跑的时候又是两层——上面一条工具流，下面单挂「思考中」，
    // 只有跑完落库才合成一条。根因是流式草稿不在 messages 里，段算法看不见它。
    // 修法是让草稿以虚拟末项参与段计算（withLiveDraft），这里把三条结论钉死。
    check("H9 流式草稿并进同一条流：思考不再另起一块挂在工具下面") {
        val done = listOf(hUser, hThink1, hTool1)
        val live = withLiveDraft(done, text = null, reasoning = "正在算第二步")
        eq(live.size, 4, "草稿要接到列表末尾")
        val flow = traceRunAt(live, 1, liveIndex = 3)
        expect(flow != null, "段首仍拿得到流")
        eq(
            flow!!.steps.map { it.title },
            listOf("思考", "run_shell", "思考中"),
            "「思考中」必须排在流尾，不能另起一块"
        )
        eq(flow.steps.last().state, TraceState.RUNNING, "还在长的思考不能打对勾")
        expect(flow.running, "段内有流式思考 = 段还在跑，不能提前折起")
        eq(flow.range, 1..3, "草稿要落在同一段区间里")
        eq(traceOwnership(live, 3), TraceOwnership.ABSORBED, "纯思考草稿已被流吸收，不能画两遍")
        // 落库只是「虚拟项换成真实项」：位置、步数都不该变（只有文案从「思考中」变「思考」）
        val settled = traceRunAt(done + UiItem.Assistant(text = "", reasoning = "正在算第二步"), 1)!!
        eq(settled.steps.size, flow.steps.size, "落库后步数不能变")
        eq(
            settled.steps.map { it.isThinking },
            flow.steps.map { it.isThinking },
            "落库后每一步的位置不能变，否则跑完会跳一下"
        )
    }

    check("H10 流式正文留在原地；没有草稿时行为一点不变") {
        val live = withLiveDraft(listOf(hUser, hThink1, hTool1), text = "结论是 42", reasoning = "算完了")
        eq(traceOwnership(live, 3), TraceOwnership.TAIL, "有正文的草稿只渲染正文")
        // 2026-11-27 真机：正文已经涨了两行，「思考中」还在转——看起来像同时在想和说。
        // 正文一开流，这条思考就是想完的那一步，必须落成对勾。
        eq(traceRunAt(live, 1, 3)!!.steps.last().state, TraceState.DONE, "正文已开流，思考不能再转圈")
        // 没草稿：不能凭空多一条，也不能把完成态的 running 改掉
        val none = withLiveDraft(listOf(hUser, hThink1, hTool1), text = null, reasoning = null)
        eq(none.size, 3, "没有草稿就不能多一条")
        eq(traceRunAt(none, 1)!!.running, false, "没草稿的段不能被判成在跑")
    }

    // ── [I] 思考链：中间步的思考不能当变体 ─────────────────────────
    //
    // 真机反馈「思考只有一条、而且永远在最下面」，根因在存储层：一轮里 assistant 一律
    // 被收成 variants（变体），而中间步 content 恰好是空的——于是要么被 UI 判空丢掉，
    // 要么变成「一轮 N 个变体」。两种都让前面的思考从界面上消失。
    // 正确的去处是 trace：它就是「最终回答之前的过程，保序」。
    println("\n[I] 思考链：中间步思考进 trace，回答才进变体")

    check("I1 只想没答的中间步进 trace：思考与工具按发生顺序交替") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "查一下 GitHub"))
        store.appendMessage(conv, MsgRecord("s1", ROLE_ASSISTANT, "", reasoning = "先看配置"))
        store.appendMessage(conv, toolItem("t1", "ok", running = false).toMsgRecord())
        store.appendMessage(conv, MsgRecord("s2", ROLE_ASSISTANT, "", reasoning = "再确认一次"))
        store.appendMessage(conv, toolItem("t2", "ok", running = false).toMsgRecord())
        store.appendMessage(conv, MsgRecord("a1", ROLE_ASSISTANT, "好了"))

        val rec = store.load(conv)!!
        eq(
            rec.messages.map { it.role },
            listOf(ROLE_USER, ROLE_ASSISTANT, ROLE_TOOL, ROLE_ASSISTANT, ROLE_TOOL, ROLE_ASSISTANT),
            "顺序必须是 思考/工具 交替，且回答在最后"
        )
        val turn = rec.turns.last()
        eq(turn.variants.size, 1, "只有最终回答算变体，中间思考不能成为变体")
        eq(turn.variants[0].content, "好了", "变体是那条有正文的回答")
        eq(turn.trace.count { it.role == ROLE_ASSISTANT }, 2, "两步思考都在 trace 里")
        eq(
            turn.trace.map { it.reasoning },
            listOf("先看配置", "", "再确认一次", ""),
            "trace 内部也必须交替：思考 / 工具 / 思考 / 工具"
        )
    }

    check("I2 有正文的回答仍然进变体（重新生成语义不变）") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "你好"))
        store.appendMessage(conv, MsgRecord("a1", ROLE_ASSISTANT, "第一条回答"))
        store.appendMessage(conv, MsgRecord("a2", ROLE_ASSISTANT, "第二条回答"))
        val turn = store.load(conv)!!.turns.last()
        eq(turn.variants.size, 2, "两条回答 = 两个变体")
        eq(turn.selectedIndex, 1, "游标指向最新变体")
        eq(turn.trace.size, 0, "回答不进 trace")
    }

    check("I3 空正文且无思考的步照旧丢弃（不凭空多一条）") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "你好"))
        store.appendMessage(conv, MsgRecord("x1", ROLE_ASSISTANT, ""))
        store.appendMessage(conv, MsgRecord("a1", ROLE_ASSISTANT, "回答"))
        val turn = store.load(conv)!!.turns.last()
        eq(turn.trace.size, 0, "没有可看的空步不进 trace")
        eq(turn.variants.size, 1, "也不成为变体")
    }

    // 真机第四报：一轮里模型「说完一段又接着调工具」，那段话被当成变体收走——界面上
    // 只显示选中的那条，中间那段消失；flatten 也不带它，下一轮模型读不到自己说过的话。
    // 判据不是「有没有正文」，是「它是不是本轮最终回答」——只有调用方手里有 lastAnswer，
    // 所以由调用方用 asTrace 声明，存储层不猜。
    println("\n[J] 中间正文：说完还接着调工具的，是过程不是回答")

    check("J1 有正文的中间步留在 trace 原位，flatten 保序") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "查一下"))
        store.appendMessage(conv, MsgRecord("s1", ROLE_ASSISTANT, "", reasoning = "先看配置"))
        store.appendMessage(conv, toolItem("t1", "ok", running = false).toMsgRecord())
        store.appendMessage(conv, MsgRecord("m1", ROLE_ASSISTANT, "配置是空的，我去建"), asTrace = true)
        store.appendMessage(conv, MsgRecord("s2", ROLE_ASSISTANT, "", reasoning = "再确认"))
        store.appendMessage(conv, toolItem("t2", "ok", running = false).toMsgRecord())
        store.appendMessage(conv, MsgRecord("a1", ROLE_ASSISTANT, "建好了"))

        val rec = store.load(conv)!!
        eq(
            rec.messages.map { it.role },
            listOf(ROLE_USER, ROLE_ASSISTANT, ROLE_TOOL, ROLE_ASSISTANT, ROLE_ASSISTANT, ROLE_TOOL, ROLE_ASSISTANT),
            "中间正文不能被挪到末尾：它在第 4 位（工具之后、下一步思考之前）"
        )
        eq(rec.messages[3].content, "配置是空的，我去建", "中间正文原样保留")
        eq(rec.messages.last().content, "建好了", "回答仍在最后")
        val turn = rec.turns.last()
        eq(turn.variants.size, 1, "中间正文不占变体槽")
        eq(turn.variants[0].content, "建好了", "变体仍是最终回答")
        eq(
            turn.trace.filter { it.role == ROLE_ASSISTANT && it.content.isNotBlank() }.map { it.content },
            listOf("配置是空的，我去建"),
            "trace 里带着这段正文，导出/搜索都还在"
        )
    }

    check("J2 不声明 asTrace 时行为不变（旧调用点零改动）") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "你好"))
        store.appendMessage(conv, MsgRecord("a1", ROLE_ASSISTANT, "回答"))
        val turn = store.load(conv)!!.turns.last()
        eq(turn.variants.size, 1, "默认路径仍进变体")
        eq(turn.trace.size, 0, "不进 trace")
    }

    check("J3 重新生成只换回答，过程顺序一点不动") {
        val store = freshStore()
        val conv = store.create()
        store.appendMessage(conv, MsgRecord("u1", ROLE_USER, "查"))
        store.appendMessage(conv, MsgRecord("s1", ROLE_ASSISTANT, "", reasoning = "想"))
        store.appendMessage(conv, MsgRecord("m1", ROLE_ASSISTANT, "中间"), asTrace = true)
        store.appendMessage(conv, MsgRecord("a1", ROLE_ASSISTANT, "答一"))
        store.appendVariant(conv, 0, MsgRecord("a2", ROLE_ASSISTANT, "答二"))
        val rec = store.load(conv)!!
        eq(
            rec.messages.map { it.content },
            listOf("查", "", "中间", "答二"),
            "过程原样，只换末尾那条回答"
        )
    }

    // 中间正文留在段里之后出现一种新形态：**有正文、没思考**的一步。
    // 它不算过程条目（isProcessEntry 看 reasoning），所以段停在它之前、正文自己渲染——
    // 这条断言把「停在哪儿」和「塞不塞空步骤」都钉住：以后谁放宽了成段判据，
    // 忘了给这类步产出正文步骤，就会被这里拦住。
    // K1 在 2026-11-27 反转。
    //
    // 旧判据：只有正文、没有思考的一步「不算过程」，流停在它之前、正文自己渲染。
    // 真机上这是错的——它导致一轮里出现**两条正文气泡**（途中汇报 + 最终回答），
    // 每条都带自己的复制/朗读，思考卡夹在两者中间；而参考实现（ChatGPT 的
    // collapsed Thought）是一块过程 + 一段回答。
    //
    // 新判据：有没有思考跟「是不是回答」无关，**只有本轮最后一条正文是回答**。
    check("K1 无思考的途中正文进流成为「说明」，流里不出现空白步骤") {
        val mid = UiItem.Assistant(text = "配置是空的，我去建", reasoning = null)
        val done = UiItem.Assistant(text = "建好了", reasoning = null)
        val msgs = listOf(hUser, hTool1, mid, done)
        val flow = traceRunAt(msgs, 1)!!
        eq(flow.steps.map { it.title }, listOf("run_shell", "说明"), "工具之后接一步「说明」")
        eq(flow.steps.last().summary, "配置是空的，我去建", "摘要取正文首行")
        eq(flow.steps.last().detail, "配置是空的，我去建", "全文进详情，点开能读")
        expect(flow.steps.none { it.isThinking && it.summary.isBlank() }, "流里不能有空白条目")
        eq(traceOwnership(msgs, 2), TraceOwnership.ABSORBED, "途中正文不自己渲染气泡")
        eq(traceOwnership(msgs, 3), TraceOwnership.TAIL, "最后一条正文才是回答")
        eq(flow.bodyIndex, 3, "回答的位置")
    }

    check("K2 一轮里只有一条正文气泡：说明与思考都进流") {
        val msgs = listOf(
            hUser,
            UiItem.Assistant(text = "先说一句", reasoning = "想一下"),
            hTool1,
            UiItem.Assistant(text = "再补一句", reasoning = "再想一下"),
            hTool2,
            UiItem.Assistant(text = "结论", reasoning = null)
        )
        val flow = traceRunAt(msgs, 1)!!
        eq(
            flow.steps.map { it.title },
            listOf("思考", "说明", "run_shell", "思考", "说明", "run_shell"),
            "两次途中说明都按位置进流"
        )
        val bodies = msgs.indices.filter { traceOwnership(msgs, it) == TraceOwnership.TAIL }
        eq(bodies, listOf(5), "整轮只有最后一条渲染成正文")
    }

    check("K3 段首是途中说明时不再重复画正文（同一句话只出现一次）") {
        val lead = UiItem.Assistant(text = "我先看看配置", reasoning = "得先确认")
        val answer = UiItem.Assistant(text = "配置是空的", reasoning = null)
        val msgs = listOf(hUser, lead, hTool1, answer)
        val flow = traceRunAt(msgs, 1)!!
        eq(flow.steps.map { it.title }, listOf("思考", "说明", "run_shell"), "段首的那句话进流")
        eq(traceAt(msgs, 1).drawBody, false, "段首已进流，正文不能再画一遍")
        eq(traceAt(msgs, 3).drawBody, true, "答案照常渲染")
        // 不折流的普通问答仍要画正文
        val simple = listOf(hUser, UiItem.Assistant(text = "42", reasoning = "算一下"))
        eq(traceAt(simple, 1).drawBody, true, "普通问答的正文不能因为加了判据而消失")
        eq(traceAt(simple, 1).run, null, "普通问答不折流")
    }

    check("H5 中断的步骤带文字说明") {
        val dead = toolItem("t9", "", running = false).copy(interrupted = true)
        val flow = traceRunAt(listOf(hUser, dead), 1)
        expect(flow != null, "中断的工具照样成流")
        eq(flow!!.steps[0].note, "已中断", "中断态要有文案，不能只给一个灰点")
        eq(flow.interruptedCount, 1, "中断计数")
        eq(flow.running, false, "中断不是运行中")
    }

    // ── [H6/H7] 不变量：穷举所有条目组合 ──────────────────────────
    //
    // 前面 H1~H5 是「我记得的几种排布」。可这类 bug 的共同点是**不报错**：
    // 界面少一张卡、少一段回答，日志里什么都不留。真正能兜住的是一个对任意
    // 序列都必须成立的命题。五种条目穷举到长度 5（3905 条），把下列三条钉死：
    //
    //   I1 每条工具都必须被**某一段**覆盖（否则那张卡谁都不画）
    //   I2 任意两段的区间不重叠（否则同一段画两遍）
    //   I3 一次问全 == 分开问两次（否则调用方只问一半就重现 902 的消失）
    //
    // 另加 H7：带正文的助手消息永远不被吸收（回答消失是最贵的一种错）。

    val atoms = listOf(hUser, hThink1, hTool1, hAnswer, hTool2, hThink2)

    fun eachSequence(body: (List<UiItem>) -> Unit) {
        var n = 0
        fun gen(prefix: List<UiItem>, depth: Int) {
            if (depth == 0) return
            for (a in atoms) {
                val next = prefix + a
                n++
                body(next)
                gen(next, depth - 1)
            }
        }
        gen(emptyList(), 5)
        expect(n > 9_000, "穷举条数太少（$n），不变量没覆盖到")
    }

    check("H6 不变量：每条工具都被一段覆盖、区间不重叠、一次问全等于分开问") {
        var tools = 0
        eachSequence { msgs ->
            val runs = msgs.indices.mapNotNull { traceRunAt(msgs, it) }

            // I1 每条工具都有人画
            msgs.forEachIndexed { i, m ->
                if (m is UiItem.Tool) {
                    tools++
                    expect(runs.any { i in it.range }, "下标 $i 的工具谁都没画（界面上会少一张卡）：$msgs")
                }
            }
            // I2 区间不重叠
            for (a in runs.indices) {
                for (b in a + 1 until runs.size) {
                    val x = runs[a].range
                    val y = runs[b].range
                    expect(
                        x.last < y.first || y.last < x.first,
                        "两段区间重叠：$x 与 $y（同一段会画两遍）：$msgs"
                    )
                }
            }
            // I2b 段里必须真有工具
            //
            // 「只有思考、没有工具」是流式中的常见中间态（模型刚开始想、还没调工具）。
            // 这种段落若成流，界面会从「正文上方的思考一行」跳变成「一步的过程流」，
            // 等工具出现再跳回来。H3 只测了「思考+正文」，漏了这条纯思考的情形，
            // 所以补在这里——变异测试把它改坏时，H3 是绿的。
            // 2026-11-27：段不再要求「必须含工具」。多步思考（含途中说明）也是一段过程，
            // 没有工具的纯问答仍不折流（H3 守着）。这里改成「要么有工具、要么确实多步」，
            // 防止把单条助手消息的问答也裹进过程卡。
            for (r in runs) {
                val assistants = (r.range).count { msgs[it] is UiItem.Assistant }
                expect(
                    r.toolCount > 0 || assistants >= 2,
                    "单步且无工具的一轮不该成流：${r.range} @ $msgs"
                )
            }
            // I3 一次问全 == 分开问（只有段首拿得到流）
            for (i in msgs.indices) {
                val slot = traceAt(msgs, i)
                eq(slot.run, traceRunAt(msgs, i), "traceAt.run @ $i")
                eq(slot.ownership, traceOwnership(msgs, i), "traceAt.ownership @ $i")
                if (slot.run != null) eq(slot.run.range.first, i, "拿到流的必须是段首 @ $i")
            }
        }
        expect(tools > 3_000, "穷举里工具出现次数太少（$tools）")
    }

    check("H7 每轮的最后一条正文永不被吸收（回答消失是最贵的错）") {
        var answers = 0
        eachSequence { msgs ->
            var turnStart = 0
            msgs.forEachIndexed { i, m ->
                if (m is UiItem.User) { turnStart = i + 1; return@forEachIndexed }
                if (m is UiItem.Assistant && m.text.isNotBlank()) {
                    // 这一条之后如果还有别的助手正文，它就不是本轮回答，被吸收是对的
                    val later = (i + 1 until msgs.size).any {
                        msgs[it] !is UiItem.User && msgs[it] is UiItem.Assistant &&
                            (msgs[it] as UiItem.Assistant).text.isNotBlank()
                    }
                    if (later) return@forEachIndexed
                    answers++
                    val own = traceOwnership(msgs, i)
                    expect(
                        own != TraceOwnership.ABSORBED,
                        "下标 $i 的最终回答被判成「已并入流」，界面上会直接消失：$msgs"
                    )
                }
            }
            expect(turnStart >= 0, "")
        }
        expect(answers > 500, "穷举里的最终回答太少（$answers）")
    }

    // ── 汇总 ────────────────────────────────────────────────────
    println()
    println("=== $passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
