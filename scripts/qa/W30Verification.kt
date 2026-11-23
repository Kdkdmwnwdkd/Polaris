import com.beichen.polaris.core.BookmarkStore
import com.beichen.polaris.core.text.ChatTime
import com.beichen.polaris.core.text.SpeechLogic
import com.beichen.polaris.core.text.SyntaxHighlighter
import com.beichen.polaris.core.text.TranslateLogic
import java.io.File

/**
 * W-30 harness：聊天体验批的纯逻辑。
 *
 * 覆盖：
 *  - [ChatTime]：今天/昨天/本周/更早四级粒度、0 不渲染、精确时间、秒→毫秒归一；
 *  - [TranslateLogic]：输出清洗（前缀/引号/空/同原文）；
 *  - [SpeechLogic]：识别结果清洗（纯标点/语气词/句末句号）与追加拼接；
 *  - [SyntaxHighlighter.normalizeLanguage]：代码块语言别名归一；
 *  - [BookmarkStore]：收藏往返（add/contains/remove/幂等/落盘重读）。
 *
 * 全部纯 JVM——不加载 Android，不需要设备。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

/** 2023-11-14 22:13:20 UTC（周二）——固定基准，避免依赖系统时钟。 */
private const val NOW = 1_700_000_000_000L
private const val DAY = 86_400_000L

fun main() {
    println("== W-30 harness：聊天体验批 ==")

    // ——————————————— ChatTime ———————————————
    //
    // 全部显式传 offsetMs = 0（UTC）：断言不随跑 harness 的机器时区漂移。
    // 原来这些用例不传时区、实现按 UTC 拆，两者恰好一致；现在实现默认跟随设备，
    // 不写死 0 就会在 CI（UTC）与开发者机（+8）给出不同结果。
    check("今天：只显示时分") {
        val got = ChatTime.format(NOW, NOW, 0)
        assertTrue("期望 22:13，实际 $got", got == "22:13")
    }
    check("一小时内：仍显示时分（不出现「刚刚」）") {
        val got = ChatTime.format(NOW - 3_600_000L, NOW, 0)
        assertTrue("期望 21:13，实际 $got", got == "21:13")
    }
    check("昨天：带「昨天」前缀") {
        val got = ChatTime.format(NOW - DAY, NOW, 0)
        assertTrue("期望 昨天 22:13，实际 $got", got == "昨天 22:13")
    }
    check("三天前：显示周几（11-11 是周六）") {
        val got = ChatTime.format(NOW - 3 * DAY, NOW, 0)
        assertTrue("期望 周六 22:13，实际 $got", got == "周六 22:13")
    }
    check("十天前：显示日期（11-04）") {
        val got = ChatTime.format(NOW - 10 * DAY, NOW, 0)
        assertTrue("期望 11月4日，实际 $got", got == "11月4日")
    }
    check("时间戳为 0：返回空串（UI 据此不渲染）") {
        assertTrue("0 应返回空", ChatTime.format(0, NOW, 0).isEmpty())
    }
    check("未来时间：按今天处理，不显示「明天」") {
        val got = ChatTime.format(NOW + 3_600_000L, NOW, 0)
        assertTrue("期望 23:13，实际 $got", got == "23:13")
    }
    check("precise：yyyy-MM-dd HH:mm") {
        val got = ChatTime.precise(NOW, 0)
        assertTrue("期望 2023-11-14 22:13，实际 $got", got == "2023-11-14 22:13")
    }
    // —— 时区（CT1~CT3）：2026-11-23 真机 bug，UTC 拆解把 21:45 显示成 13:45 ——
    check("CT1 北京时间：UTC 22:13 应显示次日 06:13，不是 22:13") {
        val got = ChatTime.format(NOW, NOW, ChatTime.OFFSET_BEIJING_MS)
        assertTrue("期望 06:13，实际 $got", got == "06:13")
    }
    check("CT2 北京时间 precise：跨日要进位（11-15 06:13）") {
        val got = ChatTime.precise(NOW, ChatTime.OFFSET_BEIJING_MS)
        assertTrue("期望 2023-11-15 06:13，实际 $got", got == "2023-11-15 06:13")
    }
    check("CT3 北京时间周几：UTC 11-11 22:13 已是本地 11-12 周日") {
        val got = ChatTime.format(NOW - 3 * DAY, NOW, ChatTime.OFFSET_BEIJING_MS)
        assertTrue("期望 周日 06:13，实际 $got", got == "周日 06:13")
    }
    check("normalizeToMillis：秒级自动乘 1000，毫秒级不动") {
        assertTrue("秒级应放大", ChatTime.normalizeToMillis(1_700_000_000L) == 1_700_000_000_000L)
        assertTrue("毫秒级应保持", ChatTime.normalizeToMillis(NOW) == NOW)
    }

    // ——————————————— TranslateLogic ———————————————
    check("译文清洗：去掉「译文：」前缀") {
        val got = TranslateLogic.clean("译文：Hello world", "你好世界")
        assertTrue("期望 Hello world，实际 $got", got == "Hello world")
    }
    check("译文清洗：去掉包裹引号") {
        val got = TranslateLogic.clean("\"Hello world\"", "你好世界")
        assertTrue("期望 Hello world，实际 $got", got == "Hello world")
    }
    check("译文清洗：中文书名号式引号也去掉") {
        val got = TranslateLogic.clean("「你好世界」", "Hello world")
        assertTrue("期望 你好世界，实际 $got", got == "你好世界")
    }
    check("译文清洗：与原文相同 → null（视为没翻出来）") {
        assertTrue("应返回 null", TranslateLogic.clean("Hello world", "Hello world") == null)
    }
    check("译文清洗：空输出 → null") {
        assertTrue("应返回 null", TranslateLogic.clean("   ", "abc") == null)
    }
    check("译文清洗：多层前缀叠加也能剥干净") {
        val got = TranslateLogic.clean("翻译：译文：\nNice", "好")
        assertTrue("期望 Nice，实际 $got", got == "Nice")
    }
    check("systemPrompt：目标语言出现在提示里，且要求保留代码原样") {
        val p = TranslateLogic.systemPrompt("English")
        assertTrue("应含 English", p.contains("English"))
        assertTrue("应要求保留代码", p.contains("代码块"))
    }

    // ——————————————— SpeechLogic ———————————————
    check("语音清洗：空白 → null") {
        assertTrue("应 null", SpeechLogic.cleanResult("   ") == null)
    }
    check("语音清洗：只有标点 → null") {
        assertTrue("应 null", SpeechLogic.cleanResult("。。，") == null)
    }
    check("语音清洗：只有语气词 → null") {
        assertTrue("应 null", SpeechLogic.cleanResult("嗯嗯") == null)
    }
    check("语音清洗：正常句子保留内容，去掉末尾单个句号") {
        val got = SpeechLogic.cleanResult("帮我看看这个文件。")
        assertTrue("期望「帮我看看这个文件」，实际 $got", got == "帮我看看这个文件")
    }
    check("语音清洗：问号保留（那是语义，不是噪声）") {
        val got = SpeechLogic.cleanResult("现在几点了？")
        assertTrue("问号应保留，实际 $got", got == "现在几点了？")
    }
    check("语音清洗：多个句号不裁（可能是省略号变体）") {
        val got = SpeechLogic.cleanResult("等等。。。")
        assertTrue("应保留原样，实际 $got", got == "等等。。。")
    }
    check("语音追加：空输入直接取识别结果") {
        assertTrue("应为 abc", SpeechLogic.appendTo("", "abc") == "abc")
    }
    check("语音追加：中文之间不加空格") {
        assertTrue("不应有空格", SpeechLogic.appendTo("你好", "世界") == "你好世界")
    }
    check("语音追加：英文之间补空格") {
        assertTrue("应有空格", SpeechLogic.appendTo("hello", "world") == "hello world")
    }

    // ——————————————— normalizeLanguage ———————————————
    check("语言归一：常见别名 → 规范名") {
        val cases = mapOf(
            "kt" to "kotlin", "kts" to "kotlin", "py" to "python", "bash" to "shell",
            "sh" to "shell", "js" to "javascript", "ts" to "typescript", "c++" to "c",
            "rs" to "rust", "yml" to "yaml", "SQL" to "sql"
        )
        for ((raw, want) in cases) {
            val got = SyntaxHighlighter.normalizeLanguage(raw)
            assertTrue("$raw 期望 $want，实际 $got", got == want)
        }
    }
    check("语言归一：未知语言 → null（不硬套关键字）") {
        assertTrue("应 null", SyntaxHighlighter.normalizeLanguage("brainfuck") == null)
    }
    check("语言归一：空白 → null") {
        assertTrue("应 null", SyntaxHighlighter.normalizeLanguage("  ") == null)
    }

    // ——————————————— BookmarkStore ———————————————
    val dir = File(System.getProperty("java.io.tmpdir"), "w30-bookmark-test-${System.nanoTime()}")
    dir.mkdirs()
    check("收藏：添加后能查到，且按内容去重") {
        val store = BookmarkStore(File(dir, "bookmarks.json"))
        val a = store.add("c1", "会话一", "user", "第一条", NOW)
        assertTrue("应返回条目", a != null)
        assertTrue("应能查到", store.contains("c1", "user", "第一条"))
        val b = store.add("c1", "会话一", "user", "第一条", NOW)
        assertTrue("重复添加应返回同一条", a!!.id == b!!.id)
        assertTrue("列表只有一条", store.list().size == 1)
    }
    check("收藏：正文为空不收藏") {
        val store = BookmarkStore(File(dir, "empty.json"))
        assertTrue("应返回 null", store.add("c1", "t", "assistant", "   ", NOW) == null)
        assertTrue("列表应为空", store.list().isEmpty())
    }
    check("收藏：落盘后新实例能读回（模拟重启）") {
        val f = File(dir, "persist.json")
        val s1 = BookmarkStore(f)
        s1.add("c1", "会话一", "assistant", "回答内容", NOW)
        val s2 = BookmarkStore(f)
        assertTrue("重启后应读到", s2.list().size == 1)
        assertTrue("内容应为回答内容", s2.list().first().text == "回答内容")
        assertTrue("时间应保留", s2.list().first().timeMs == NOW)
    }
    check("收藏：按 id 删除 / 按内容删除") {
        val f = File(dir, "remove.json")
        val s = BookmarkStore(f)
        val a = s.add("c1", "t", "user", "A", NOW)!!
        assertTrue("删除应成功", s.remove(a.id))
        assertTrue("删完查不到", !s.contains("c1", "user", "A"))
        s.add("c1", "t", "user", "B", NOW)
        assertTrue("按内容删除应成功", s.removeByContent("c1", "user", "B"))
        assertTrue("删完列表为空", s.list().isEmpty())
    }
    check("收藏：清空") {
        val s = BookmarkStore(File(dir, "clear.json"))
        s.add("c1", "t", "user", "A", NOW)
        s.add("c2", "t", "assistant", "B", NOW)
        s.clear()
        assertTrue("清空后为空", s.list().isEmpty())
    }
    check("收藏：坏文件不崩（按空处理）") {
        val f = File(dir, "broken.json")
        f.writeText("{ not json")
        val s = BookmarkStore(f)
        assertTrue("坏文件应返回空列表", s.list().isEmpty())
    }
    dir.deleteRecursively()

    println()
    println("== 结果：$pass 通过 / $fail 失败 ==")
    if (fail > 0) kotlin.system.exitProcess(1)
}
