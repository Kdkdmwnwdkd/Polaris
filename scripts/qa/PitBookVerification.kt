/**
 * R9 坑图 harness（纯 JVM，不加载 Android）。
 *
 * 断言口径（每条都要能被一句代码改动杀死）：
 *  - 同位置累加、不同位置分开（删掉 `old.hits + 1` 立刻红）；
 *  - `warnFor` 到阈值才提（把 `>=` 改成 `>` 或去掉阈值立刻红）；
 *  - 落盘可读回（删掉 persist 立刻红）；
 *  - 容量淘汰「错得少且久未出现」的，而不是最老的；
 *  - **坑图绝不回流熔断**——这是本次改动最重要的一条边界，见下。
 */
import com.beichen.polaris.core.FailureGuard
import com.beichen.polaris.core.GuardVerdict
import com.beichen.polaris.core.pit.PitBook
import java.io.File

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body()
        pass++
        println("  PASS  $name")
    } catch (t: Throwable) {
        fail++
        println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$msg：期望 $expected，实际 $actual")
}

private fun tmpDir(tag: String): File =
    File(System.getProperty("java.io.tmpdir"), "r9-pit-$tag-${System.nanoTime()}")

fun main() {
    println("=== R9 坑图（PitBook / FailureGuard 单向接入）===")

    // ---------- 聚合 ----------
    check("同位置错 3 次 → 1 条记录，hits=3，保留最近一次的 code") {
        val d = tmpDir("agg")
        val book = PitBook(File(d, "pits.json"))
        book.record("ui_click", "fp1", "click_not_found", "{\"text\":\"确认\"}", at = 1000)
        book.record("ui_click", "fp1", "click_not_found", "{\"text\":\"确认\"}", at = 2000)
        val last = book.record("ui_click", "fp1", "click_timeout", "{\"text\":\"确认\"}", at = 3000)
        assertEquals("条数", 1, book.size())
        assertEquals("hits", 3, last.hits)
        assertEquals("首次时刻不被覆盖", 1000L, last.firstAt)
        assertEquals("最近时刻", 3000L, last.lastAt)
        assertEquals("code 取最近一次", "click_timeout", last.code)
        d.deleteRecursively()
    }

    check("不同位置各自计数（指纹不同就是两个坑）") {
        val d = tmpDir("diff")
        val book = PitBook(File(d, "pits.json"))
        book.record("ui_click", "fpA", "click_not_found", "A")
        book.record("ui_click", "fpB", "click_not_found", "B")
        assertEquals("两个坑", 2, book.size())
        assertEquals("各自 hits=1", listOf(1, 1), book.snapshot().map { it.hits })
        d.deleteRecursively()
    }

    // ---------- 预警阈值 ----------
    check("错 1、2 次不提醒，第 3 次才提醒") {
        val d = tmpDir("warn")
        val book = PitBook(File(d, "pits.json"))
        book.record("ui_click", "fp", "click_not_found", "x")
        assertTrue("第 1 次不该提醒", book.warnFor("fp") == null)
        book.record("ui_click", "fp", "click_not_found", "x")
        assertTrue("第 2 次不该提醒", book.warnFor("fp") == null)
        val third = book.record("ui_click", "fp", "click_not_found", "x")
        val warned = book.warnFor("fp")
        assertTrue("第 3 次必须提醒", warned != null)
        assertEquals("提醒里应带次数", 3, warned!!.hits)
        assertEquals("阈值常量", 3, PitBook.WARN_HITS)
        assertEquals("返回的应是同一条", third.key, warned.key)
        d.deleteRecursively()
    }

    check("完全没记录过的位置不提醒") {
        val d = tmpDir("norec")
        val book = PitBook(File(d, "pits.json"))
        assertTrue("没记录不该提醒", book.warnFor("never-seen") == null)
        d.deleteRecursively()
    }

    // ---------- 落盘 ----------
    check("落盘可读回（进程重启后坑还在）") {
        val d = tmpDir("persist")
        val f = File(d, "pits.json")
        val b1 = PitBook(f)
        b1.record("ui_click", "fp", "click_not_found", "{\"text\":\"确认\"}", at = 500)
        b1.record("ui_click", "fp", "click_not_found", "{\"text\":\"确认\"}", at = 600)
        val b2 = PitBook(f)   // 重新读盘
        assertEquals("重开后条数", 1, b2.size())
        val pit = b2.snapshot().first()
        assertEquals("重开后 hits", 2, pit.hits)
        assertEquals("重开后 sample", "{\"text\":\"确认\"}", pit.sample)
        assertEquals("重开后 firstAt", 500L, pit.firstAt)
        d.deleteRecursively()
    }

    check("sample 截断到 160 字（不存整棵 UI 树）") {
        val d = tmpDir("trunc")
        val book = PitBook(File(d, "pits.json"))
        val pit = book.record("ui_tree", "fp", "ui_tree_error", "x".repeat(500))
        assertEquals("sample 长度", PitBook.MAX_SAMPLE_CHARS, pit.sample.length)
        d.deleteRecursively()
    }

    // ---------- 排序与淘汰 ----------
    check("search 按 hits 排序，不是按匹配度") {
        val d = tmpDir("sort")
        val book = PitBook(File(d, "pits.json"))
        repeat(10) { book.record("ui_click", "fpHot", "click_not_found", "确认按钮", at = 1000L + it) }
        book.record("ui_click", "fpCold", "click_not_found", "确认按钮", at = 2000)
        val r = book.search("确认")
        assertEquals("两条都命中", 2, r.size)
        assertEquals("错得多的在前", "fpHot", r.first().key)
        d.deleteRecursively()
    }

    check("容量上限：超出后淘汰错得最少且最久未出现的") {
        val d = tmpDir("cap")
        val book = PitBook(File(d, "pits.json"))
        // 1 个高频坑（错 50 次，最老）+ 200 个低频新坑
        repeat(50) { book.record("t", "hotkey", "c", "s", at = 1) }
        repeat(PitBook.MAX_PITS) { i -> book.record("t", "k$i", "c", "s", at = 10_000L + i) }
        assertEquals("应被压回上限", PitBook.MAX_PITS, book.size())
        assertTrue(
            "高频坑不该因为「最老」被淘汰",
            book.snapshot().any { it.key == "hotkey" }
        )
        d.deleteRecursively()
    }

    // ---------- 与 FailureGuard 的边界（最重要） ----------
    check("单向：坑图累积不影响熔断判定（跨回合也不影响）") {
        val d = tmpDir("onesided")
        val book = PitBook(File(d, "pits.json"))
        val guard = FailureGuard(book)
        val fp = "fingerprint-x"

        // 模拟「同一个位置在 10 个不同回合里各错一次」
        repeat(10) {
            guard.onExecuted(fp, failed = true, tool = "ui_click", code = "click_not_found", sample = "x")
            guard.resetForTurn()   // 每回合结束清熔断状态（W-8 的修复）
        }

        assertTrue("坑图应记住这个位置", book.warnFor(fp) != null)
        assertEquals("坑图次数", 10, book.snapshot().first().hits)

        // 关键：新回合里，熔断器必须是干净的 Allow——坑图不许把它毒化回 W-8 的形态
        assertTrue(
            "坑图不得回流污染熔断（否则就是 W-8 重演：跨会话永久挡死）",
            guard.check(fp, duplicateInBatch = false) is GuardVerdict.Allow
        )
        d.deleteRecursively()
    }

    check("瞬时故障不记坑（环境没就绪不是坑）") {
        val d = tmpDir("transient")
        val book = PitBook(File(d, "pits.json"))
        val guard = FailureGuard(book)
        repeat(5) {
            guard.onExecuted(
                "fp", failed = true, transient = true,
                tool = "ui_tree", code = "service_not_ready", sample = ""
            )
        }
        assertEquals("瞬时故障不该进坑图", 0, book.size())
        d.deleteRecursively()
    }

    check("成功不记坑（只有失败才记）") {
        val d = tmpDir("ok")
        val book = PitBook(File(d, "pits.json"))
        val guard = FailureGuard(book)
        repeat(5) {
            guard.onExecuted("fp", failed = false, tool = "ui_click", code = "click_ok", sample = "")
        }
        assertEquals("成功不该进坑图", 0, book.size())
        d.deleteRecursively()
    }

    check("没给 tool 时不记坑（存量调用点零影响）") {
        val d = tmpDir("notool")
        val book = PitBook(File(d, "pits.json"))
        val guard = FailureGuard(book)
        // 模拟未改造的调用点：只传 fingerprint / failed
        guard.onExecuted("fp", failed = true)
        assertEquals("不该记", 0, book.size())
        // 但熔断计数照旧生效，行为未变
        guard.onExecuted("fp", failed = true)
        assertTrue(
            "熔断行为必须与改造前一致",
            guard.check("fp", duplicateInBatch = false) !is GuardVerdict.Allow
        )
        d.deleteRecursively()
    }

    // ---------- 拦截文案接坑图：把「上次错在哪」带到模型眼前 ----------

    check("pitAt 不设阈值：错过 1 次也读得到（拦截时这是唯一线索）") {
        val d = tmpDir("pitat")
        val book = PitBook(File(d, "pits.json"))
        book.record("ui_click", "fp1", "element_not_found", "{\"text\":\"发送\"}")
        assertEquals("warnFor 要够 3 次才提", null, book.warnFor("fp1"))
        assertTrue("pitAt 必须能读到 1 次的记录", book.pitAt("fp1") != null)
        assertEquals("没记过的位置必须是 null", null, book.pitAt("never"))
        d.deleteRecursively()
    }

    check("拦截文案带上「上次错在哪」：结果码 / 参数摘要 / 换路指引") {
        val d = tmpDir("hint")
        val book = PitBook(File(d, "pits.json"))
        val guard = FailureGuard(book)
        repeat(5) { book.record("ui_click", "fp", "element_not_found", "{\"text\":\"发送\"}") }
        repeat(2) {
            guard.onExecuted(
                "fp", failed = true, tool = "ui_click",
                code = "element_not_found", sample = "{\"text\":\"发送\"}"
            )
        }
        val v = guard.check("fp", duplicateInBatch = false)
        assertTrue("本轮错 2 次应拦截", v is GuardVerdict.Blocked)
        val hint = (v as GuardVerdict.Blocked).hint
        assertTrue("hint 必须带结果码，实际：$hint", hint.contains("element_not_found"))
        assertTrue("hint 必须带参数摘要，实际：$hint", hint.contains("发送"))
        assertTrue("hint 必须区分「本轮」与「历史」，实际：$hint", hint.contains("本轮已失败 2 次"))
        assertTrue("hint 必须给出「换路」而不是「微调」的指引，实际：$hint", hint.contains("换一条路"))
        d.deleteRecursively()
    }

    check("hint 里的参数摘要被截断（不把 160 字符的 JSON 原样塞回去）") {
        val d = tmpDir("clip")
        val book = PitBook(File(d, "pits.json"))
        val guard = FailureGuard(book)
        val long = "A".repeat(200)
        guard.onExecuted("fp", failed = true, tool = "ui_input", code = "bad_args", sample = long)
        guard.onExecuted("fp", failed = true, tool = "ui_input", code = "bad_args", sample = long)
        val hint = (guard.check("fp", false) as GuardVerdict.Blocked).hint
        val run = Regex("A+").find(hint)?.value?.length ?: 0
        assertTrue("摘要要截到 60 以内，实际连续 $run 个", run in 1..60)
        d.deleteRecursively()
    }

    check("没有坑图时 hint 逐字回退到原文案（存量场景零变化）") {
        // pits = null：子代理 / 桌宠 / harness 替身都是这条路径
        val guard = FailureGuard()
        repeat(2) { guard.onExecuted("fp", failed = true) }
        val v = guard.check("fp", duplicateInBatch = false) as GuardVerdict.Blocked
        assertEquals(
            "无坑图必须回退原文案，否则等于凭空改了所有存量场景的用户可见文本",
            "上次相同参数以失败告终，请改变参数或换一个工具后重试。",
            v.hint
        )
    }

    check("坑图再厚也不改判定：本轮没失败就放行（W-8 边界，读口新开也不破）") {
        val d = tmpDir("noreflow")
        val book = PitBook(File(d, "pits.json"))
        val guard = FailureGuard(book)
        repeat(50) { book.record("ui_click", "fp", "element_not_found", "{}") }
        assertTrue(
            "堆了 50 次历史坑，本轮第一次调用仍必须放行",
            guard.check("fp", duplicateInBatch = false) is GuardVerdict.Allow
        )
        guard.resetForTurn()
        assertTrue("回合重置后同样放行", guard.check("fp", false) is GuardVerdict.Allow)
        d.deleteRecursively()
    }

    check("拦截只看本轮次数：历史 50 次 + 本轮 1 次仍放行") {
        val d = tmpDir("turnonly")
        val book = PitBook(File(d, "pits.json"))
        val guard = FailureGuard(book)
        repeat(50) { book.record("ui_click", "fp", "x", "{}") }
        guard.onExecuted("fp", failed = true, tool = "ui_click", code = "x", sample = "{}")
        assertTrue("历史厚不等于该拦", guard.check("fp", false) is GuardVerdict.Allow)
        guard.onExecuted("fp", failed = true, tool = "ui_click", code = "x", sample = "{}")
        assertTrue("本轮到 2 次才拦", guard.check("fp", false) is GuardVerdict.Blocked)
        d.deleteRecursively()
    }

    println("\n=== 汇总: $pass 通过 / $fail 失败 ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
