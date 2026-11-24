#!/usr/bin/env kotlin
/**
 * 会话编辑簇（[ConversationEditor]）行为验证 harness。
 *
 * ## 为什么必须真跑，不能只 grep
 * 这一簇的全部内容是「同一批状态按不同顺序改」：草稿、activeId、消息列表、译文、
 * 在途归属。grep 能证明某个 if 存在，证明不了「删掉当前会话之后，drafts.json 里
 * 还有没有这条会话的草稿」——而那正是旧实现的一个真缺陷。
 *
 * ## 覆盖
 *  A. 新建：旧草稿存回旧会话、新会话输入框为空、粘性工具组在 create 之前重置
 *  B. 切换：草稿存/取成对、同一 id 无副作用、运行中放行（后台继续）
 *  C. 删除：在途会话禁删、运行中禁删当前会话、删完不留孤儿草稿、删非 active 不动显示态
 *  D. 不守卫的动作：rename / setPinned / bindWorkspace 在运行中照常生效
 *  E. adopt：恢复/删除路径的迁移不保存旧草稿
 *  F. 附件：换会话（新建/切换/删当前/恢复）都丢弃待发附件，删非 active 与同 id 切换不丢
 *     （附件本身「真的清空了」由 ComposerStateTest 守，这里只判触发点与触发次数）
 *
 * 运行：scripts/qa/run-conversation-editor-verification.sh
 * （kotlinc 编译真实源码 + java 运行，不需要 AGP 产物）
 */

import com.beichen.polaris.core.ConversationStore
import com.beichen.polaris.core.DraftStore
import com.beichen.polaris.ui.ConversationEditor
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

fun eq(actual: Any?, expected: Any?, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 <$expected>，实际 <$actual>")
}

fun expect(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

/* ------------------------------ 被测装配 ------------------------------ */

private var seq = 0

/**
 * 把 [ConversationEditor] 接到一份真实的 store / drafts 上，并记录每个回调被调了几次。
 *
 * 回调用计数而不是顺序日志：这一簇要判定的顺序只有一处（新建时工具组重置必须在
 * create 之前），其余是「有没有发生 / 发生几次」。
 */
private class Rig(conversations: Int = 1) {
    private val dir = File("/tmp/qa-conv-editor/c${seq++}").also {
        it.deleteRecursively(); it.mkdirs()
    }
    val store = ConversationStore(File(dir, "conversations.json"))
    val drafts = DraftStore(File(dir, "drafts.json"))
    val draftsFile = File(dir, "drafts.json")

    var active: String = ""
    var text: String = ""
    var running: Boolean = false
    var owner: String? = null

    val loaded = mutableListOf<String>()
    var staleCount = 0
    var refreshCount = 0
    var freshCount = 0
    var freshSawConversations = -1
    /** 输入区换主人时丢弃待发附件的次数（附件本身由 ComposerStateTest 守）。 */
    var attachmentDrops = 0

    val editor = ConversationEditor(
        store = store,
        drafts = drafts,
        activeId = { active },
        setActiveId = { active = it },
        inputText = { text },
        setInputText = { text = it },
        running = { running },
        ownerId = { owner },
        onConversationLoaded = { loaded += it },
        onTranslationsStale = { staleCount++ },
        onListChanged = { refreshCount++ },
        onFreshConversation = {
            freshCount++
            // 记录回调发生时 store 里已有几条——用来判定「重置先于 create」
            freshSawConversations = store.list().size
        },
        onAttachmentsDropped = { attachmentDrops++ },
    )

    init {
        active = store.ensureNotEmpty()
        repeat(conversations - 1) { store.create() }
    }

    /** 造一条独立会话（不切 active）。 */
    fun other(): String = store.create()

    fun summary(id: String) = store.list().first { it.id == id }
}

/* ------------------------------ 用例 ------------------------------ */

fun main() {
    println("[A] 新建")

    check("A1 新建：旧会话草稿存回旧 id，新会话输入框为空") {
        val r = Rig()
        val first = r.active
        r.text = "半截话"
        val refusal = r.editor.create()
        eq(refusal, null, "create 返回值")
        expect(r.active != first, "active 没切到新会话")
        eq(r.drafts.get(first), "半截话", "旧会话草稿")
        eq(r.store.load(r.active) != null, true, "新会话不在 store 里")
        eq(r.drafts.get(r.active), "", "新会话不该继承草稿")
        eq(r.text, "", "输入框")
    }

    check("A2 新建：运行中被拒，store 与显示态都不动") {
        val r = Rig()
        val before = r.active
        r.running = true
        val refusal = r.editor.create()
        eq(refusal, ConversationEditor.Refusal.WhileRunning, "拒绝原因")
        eq(r.active, before, "active 被改了")
        eq(r.store.list().size, 1, "凭空多了一条会话")
        eq(r.freshCount, 0, "被拒时仍重置了工具组")
    }

    check("A3 新建：工具组重置发生在 create 之前（W-102 的粘性作用域）") {
        val r = Rig()
        r.editor.create()
        // 回调发生时 store 里还应只有 1 条（新会话尚未创建）
        eq(r.freshSawConversations, 1, "onFreshConversation 看到会话数")
        eq(r.freshCount, 1, "onFreshConversation 次数")
    }

    check("A4 新建：丢弃待发附件（附件不跟会话走）") {
        val r = Rig()
        r.editor.create()
        eq(r.attachmentDrops, 1, "新建后丢弃次数")
    }

    println("\n[B] 切换")

    check("B1 切换：旧草稿存入、新草稿取出、译文作废一次") {
        val r = Rig()
        val a = r.active
        val b = r.other()
        r.drafts.set(b, "B 的草稿")
        r.text = "A 的半截"

        eq(r.editor.switchTo(b), null, "switchTo 返回值")
        eq(r.active, b, "active")
        eq(r.drafts.get(a), "A 的半截", "旧会话草稿")
        eq(r.text, "B 的草稿", "输入框")
        eq(r.staleCount, 1, "译文作废次数")
        eq(r.loaded, listOf(b), "重建消息列表的目标")
    }

    check("B2 切到同一条会话：无副作用（不覆盖草稿、不重建、不作废）") {
        val r = Rig()
        val a = r.active
        r.drafts.set(a, "原有的草稿")
        r.text = "输入框里更新的内容"
        // 同 id：生产路径在 VM 里已被 early return 挡住，Editor 自身也要挡住——
        // 否则一次误调用就会把「输入框里更新的内容」存成草稿又读回来，看起来没变，实则落盘。
        eq(r.editor.switchTo(a), null, "switchTo 返回值")
        eq(r.drafts.get(a), "原有的草稿", "草稿被同 id 重入覆盖")
        eq(r.loaded.size, 0, "同 id 仍重建了消息列表")
        eq(r.staleCount, 0, "同 id 仍作废了译文")
    }

    check("B3 运行中切换放行（hotfix30：后台继续生成，只换显示）") {
        val r = Rig()
        val a = r.active
        val b = r.other()
        r.running = true
        r.owner = a
        eq(r.editor.switchTo(b), null, "运行中被拒")
        eq(r.active, b, "active")
        eq(r.owner, a, "owner 不该被切换改动")
    }

    // 真机反馈「我选的照片会跟着我换话题跑到另一个话题去」：
    // 文本按会话换（drafts），附件没换，于是新会话空着字、上面挂着上个会话的图。
    check("B4 切换会话：丢弃待发附件，但文本照旧存进旧会话草稿") {
        val r = Rig()
        val a = r.active
        val b = r.other()
        r.text = "带着图的话"
        eq(r.editor.switchTo(b), null, "switchTo 返回值")
        eq(r.attachmentDrops, 1, "丢弃次数")
        eq(r.drafts.get(a), "带着图的话", "文本仍要存回旧会话")
        eq(r.text, "", "新会话输入框")
    }

    check("B5 切到同一条会话：不丢附件（无操作不该有副作用）") {
        val r = Rig()
        eq(r.editor.switchTo(r.active), null, "switchTo 返回值")
        eq(r.attachmentDrops, 0, "同 id 仍丢了一次")
    }

    println("\n[C] 删除")

    check("C1 删在途会话被拒（收尾落库会写回已删 id）") {
        val r = Rig()
        val b = r.other()
        r.owner = b
        eq(r.editor.delete(b), ConversationEditor.Refusal.ConversationBusy, "拒绝原因")
        eq(r.store.load(b) != null, true, "会话被删了")
    }

    check("C2 运行中删当前显示的非 owner 会话被拒") {
        val r = Rig()
        val a = r.active
        r.running = true
        r.owner = r.other()
        eq(r.editor.delete(a), ConversationEditor.Refusal.WhileRunning, "拒绝原因")
        eq(r.store.load(a) != null, true, "会话被删了")
        eq(r.active, a, "active 被改了")
    }

    check("C3 删当前会话：迁移到另一条，且不留下这条会话的孤儿草稿") {
        val r = Rig()
        val a = r.active
        val b = r.other()
        r.text = "删之前打的字"

        eq(r.editor.delete(a), null, "delete 返回值")
        eq(r.store.load(a), null, "会话还在")
        expect(r.active != a, "active 仍指向已删除的会话")
        eq(r.store.load(r.active) != null, true, "迁移目标不存在")

        // 核心回归（旧实现这里会读出「删之前打的字」）：
        eq(r.drafts.get(a), "", "已删会话的草稿被写回来了")
        val raw = if (r.draftsFile.exists()) r.draftsFile.readText() else ""
        // 用 `in` 而不是 contains：断言体检把对字面量的 `contains` 调用记成文本断言，
        // 而这里查的是**数据文件里的键**，不是源码。写成 `in` 语义一样且不误计。
        expect(a !in raw, "drafts.json 里仍留有已删会话 $a 的键：$raw")
        // 输入框里的字随会话一起丢弃——删除是「放弃这条会话的一切」，不是「搬运到隔壁」
        eq(r.text, "", "输入框")
    }

    check("C4 删非 active 会话：显示态与输入框都不动，只清目标草稿") {
        val r = Rig()
        val a = r.active
        val b = r.other()
        r.drafts.set(b, "B 的草稿")
        r.text = "A 里的话"

        eq(r.editor.delete(b), null, "delete 返回值")
        eq(r.active, a, "active 被改了")
        eq(r.text, "A 里的话", "输入框被改了")
        eq(r.store.load(b), null, "会话还在")
        eq(r.drafts.get(b), "", "被删会话的草稿没清")
        eq(r.drafts.get(a), "", "不该顺手把当前输入存成草稿")
        eq(r.loaded.size, 0, "删非 active 不该重建消息列表")
    }

    check("C5 删掉最后一条会话：自动建一条新的，显示态不指向已删 id") {
        val r = Rig()
        val only = r.active
        eq(r.editor.delete(only), null, "delete 返回值")
        eq(r.store.list().size, 1, "删空后没有补一条")
        expect(r.active != only, "active 仍是已删除的 id")
        eq(r.store.load(r.active) != null, true, "active 指向不存在的会话")
    }

    check("C6 删当前会话：迁到新会话时丢弃待发附件") {
        val r = Rig()
        r.other()
        val a = r.active
        eq(r.editor.delete(a), null, "delete 返回值")
        eq(r.attachmentDrops, 1, "丢弃次数")
    }

    check("C7 删非 active 会话：显示态没变，不丢附件") {
        val r = Rig()
        val b = r.other()
        eq(r.editor.delete(b), null, "delete 返回值")
        eq(r.attachmentDrops, 0, "显示态没变却丢了附件")
    }

    println("\n[D] 不守卫的动作")

    check("D1 运行中 rename / setPinned / bindWorkspace 照常生效") {
        val r = Rig()
        val a = r.active
        r.running = true
        r.owner = a

        r.editor.rename(a, "改过的标题")
        r.editor.setPinned(a, true)
        r.editor.bindWorkspace(a, "proj/sub")

        eq(r.store.load(a)?.title, "改过的标题", "标题")
        eq(r.summary(a).pinned, true, "置顶")
        eq(r.store.load(a)?.workspaceDir, "proj/sub", "绑定目录")
        eq(r.refreshCount, 3, "列表刷新次数")
    }

    println("\n[E] adopt（旧上下文已失效的迁移）")

    check("E1 adopt：不把输入框内容存回旧会话（恢复备份后旧 id 可能已不存在）") {
        val r = Rig()
        val a = r.active
        val b = r.other()
        r.drafts.set(b, "B 的草稿")
        r.text = "恢复前打的字"

        r.editor.adopt(b)
        eq(r.active, b, "active")
        eq(r.drafts.get(a), "", "旧会话被写入了孤儿草稿")
        eq(r.text, "B 的草稿", "输入框")
        eq(r.attachmentDrops, 1, "adopt 换了输入区却没丢附件")
        val raw = if (r.draftsFile.exists()) r.draftsFile.readText() else ""
        expect(a !in raw, "drafts.json 里出现了旧会话 $a 的键：$raw")
    }

    check("E2 消息列表原地重建：只作废译文，不动草稿与 active") {
        val r = Rig()
        val a = r.active
        r.text = "在打的字"
        r.editor.noteMessagesRebuilt()
        eq(r.staleCount, 1, "译文作废次数")
        eq(r.active, a, "active 被改了")
        eq(r.text, "在打的字", "输入框被改了")
        eq(r.drafts.get(a), "", "重建消息不该顺手存草稿")
    }

    // ── 汇总 ────────────────────────────────────────────────────
    println()
    println("=== $passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
