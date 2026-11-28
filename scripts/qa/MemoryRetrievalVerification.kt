import com.beichen.polaris.memory.MemoryAutoCapture
import com.beichen.polaris.memory.MemoryStore
import java.io.File

/**
 * W-16 记忆检索 harness：BM25 排序 + 自动抽取 + 去重（纯 JVM，不加载 Android）。
 *
 * 断言口径：
 *  - BM25 必须让「查询词只出现在相关内容里」的条目排第一；
 *  - 高频词（两条都有的「我的」）不能把无关条目顶上来；
 *  - 完全不相关的查询返回空列表（记忆工具据此如实说「没找到」）；
 *  - 自动抽取只认显式信号、不认闲聊；近义说法视为重复。
 */

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

fun main() {
    println("=== W-16 记忆检索（BM25 / 自动抽取 / 去重）===")

    // 临时目录建库（每次全新，避免污染）
    val dir = File(System.getProperty("java.io.tmpdir"), "w16-memory-qa-${System.nanoTime()}")
    val store = MemoryStore(dir)
    store.add("我对花生过敏，点外卖要避开", kind = "fact", salience = 9)
    store.add("我的手机是魅族 20", kind = "fact", salience = 6)
    store.add("我的常用邮箱是 work@example.com", kind = "fact", salience = 5)
    store.add("我喜欢深色模式", kind = "preference", salience = 7)

    check("BM25：查「花生」→ 过敏那条排第一") {
        val r = store.search("花生", topK = 3)
        assertTrue("应有结果", r.isNotEmpty())
        assertTrue("首条应含花生，实际：${r.first().content}", r.first().content.contains("花生"))
    }

    check("BM25：高频词「我的」不把无关条目顶上来") {
        val r = store.search("我的花生", topK = 4)
        assertTrue("应有结果", r.isNotEmpty())
        assertTrue("首条仍应是花生条目，实际：${r.first().content}", r.first().content.contains("花生"))
    }

    check("BM25：完全不相关 → 空列表（不硬凑）") {
        val r = store.search("量子力学 傅里叶变换", topK = 3)
        assertTrue("应无结果，实际 ${r.size} 条：${r.map { it.content }}", r.isEmpty())
    }

    check("BM25：标签命中加权（tag 含查询词时排前）") {
        store.add("每周三练腿", kind = "event", tags = listOf("健身"), salience = 5)
        val r = store.search("健身", topK = 2)
        assertTrue("应有结果", r.isNotEmpty())
        assertTrue("首条应为健身条目，实际：${r.first().content}", r.first().content.contains("练腿"))
    }

    check("自动抽取：记住… → fact") {
        val caps = MemoryAutoCapture.extract("记住：我对花生过敏")
        assertEquals("条数", 1, caps.size)
        assertEquals("类型", "fact", caps[0].kind)
        assertTrue("内容", caps[0].content.contains("花生"))
        assertTrue("重要度应 >= 8", caps[0].salience >= 8)
    }

    check("自动抽取：以后… → preference") {
        val caps = MemoryAutoCapture.extract("以后都用简体中文回答我")
        assertEquals("条数", 1, caps.size)
        assertEquals("类型", "preference", caps[0].kind)
    }

    check("自动抽取：闲聊不记") {
        val caps = MemoryAutoCapture.extract("今天天气不错，出去走走")
        assertTrue("不应有候选，实际：$caps", caps.isEmpty())
    }

    check("自动抽取：超长消息不处理（避免把整段文章记进去）") {
        val caps = MemoryAutoCapture.extract("记住：" + "很长".repeat(300))
        assertTrue("不应有候选", caps.isEmpty())
    }

    check("去重：换种说法视为同一条") {
        assertTrue(
            "「我不吃辣」应≈「我不太能吃辣」",
            MemoryAutoCapture.isDuplicate("我不吃辣", listOf("我不太能吃辣"))
        )
        assertTrue(
            "「我喜欢深色模式」与「我的邮箱」不应重复",
            !MemoryAutoCapture.isDuplicate("我喜欢深色模式", listOf("我的常用邮箱是 work@example.com"))
        )
    }

    // ---------- §11.13-6 双时态：失效不删 + §11.5 写入即钉住 ----------
    //
    // 这组断言的共同口径：**每条都要能被一句代码改动杀死**。
    // 删掉 search/summaryForPrompt 里的 isActive 过滤、删掉 trimToLimit 的 pinned 判断、
    // 删掉 update 里的 invalidAt=0、把 invalidate 写成 removeAt —— 任意一种都会让下面某条红。

    check("失效不删：有效条数减一，物理条数不变") {
        val d = File(System.getProperty("java.io.tmpdir"), "w16-inv-${System.nanoTime()}")
        val s = MemoryStore(d)
        val a = s.add("我的手机是魅族 20", kind = "fact", salience = 6)
        assertEquals("初始有效 1 条", 1, s.size())
        assertTrue("invalidate 应命中", s.invalidate(a.id))
        assertEquals("有效条数应减一", 0, s.size())
        assertEquals("物理条数不应变（留档而非删除）", 1, s.totalSize())
        d.deleteRecursively()
    }

    check("失效后不可检索：旧事实不再压住新事实") {
        val d = File(System.getProperty("java.io.tmpdir"), "w16-inv2-${System.nanoTime()}")
        val s = MemoryStore(d)
        val old = s.add("我的常用邮箱是 work@example.com", kind = "fact", salience = 9)
        s.add("我的常用邮箱改成 personal@example.com", kind = "fact", salience = 9)
        assertEquals("失效前两条都在", 2, s.search("邮箱", topK = 5).size)
        s.invalidate(old.id)
        val after = s.search("邮箱", topK = 5)
        assertEquals("失效后只应剩一条", 1, after.size)
        assertTrue("剩下那条必须是新邮箱，实际：${after.first().content}", after.first().content.contains("personal"))
        d.deleteRecursively()
    }

    check("失效后不进提示词") {
        val d = File(System.getProperty("java.io.tmpdir"), "w16-inv3-${System.nanoTime()}")
        val s = MemoryStore(d)
        val old = s.add("我的常用邮箱是 work@example.com", kind = "fact", salience = 9)
        s.add("我的常用邮箱改成 personal@example.com", kind = "fact", salience = 9)
        s.invalidate(old.id)
        val prompt = s.summaryForPrompt()
        assertTrue("提示词不该含旧值（含了就是亲手制造「知道却做错」）", !prompt.contains("work@example.com"))
        assertTrue("提示词应含新值", prompt.contains("personal@example.com"))
        d.deleteRecursively()
    }

    check("invalidate 幂等：重复调用返回 false 且不刷新失效时刻") {
        val d = File(System.getProperty("java.io.tmpdir"), "w16-inv4-${System.nanoTime()}")
        val s = MemoryStore(d)
        val x = s.add("临时事实：今天在测试", kind = "event")
        assertTrue("首次应命中", s.invalidate(x.id, 1000L))
        assertTrue("二次应无操作", !s.invalidate(x.id, 2000L))
        val kept = s.invalidated().first { it.id == x.id }
        assertEquals("失效时刻不该被刷新（否则时间线随调用次数漂移）", 1000L, kept.invalidAt)
        d.deleteRecursively()
    }

    check("编辑即复活：改过的记忆重新可检索") {
        val d = File(System.getProperty("java.io.tmpdir"), "w16-inv5-${System.nanoTime()}")
        val s = MemoryStore(d)
        val x = s.add("我喜欢深色模式", kind = "preference", salience = 7)
        s.invalidate(x.id)
        assertEquals("失效后检索不到", 0, s.search("深色模式", 3).size)
        assertTrue("编辑应成功", s.update(x.id, "我喜欢浅色模式", 7, emptyList()))
        assertEquals("编辑后应重新可检索", 1, s.search("浅色模式", 3).size)
        d.deleteRecursively()
    }

    check("落盘保留失效：重开库仍能追溯「它以前认为什么」") {
        val d = File(System.getProperty("java.io.tmpdir"), "w16-inv6-${System.nanoTime()}")
        val s1 = MemoryStore(d)
        val x = s1.add("旧事实：手机是魅族 20", kind = "fact", salience = 8)
        s1.invalidate(x.id)
        val s2 = MemoryStore(d)   // 重新读盘，模拟进程重启
        assertEquals("重开后有效条数 0", 0, s2.size())
        assertEquals("重开后留档 1 条", 1, s2.totalSize())
        assertTrue("重开后仍能查到旧值", s2.invalidated().first().content.contains("魅族"))
        d.deleteRecursively()
    }

    check("钉住：容量淘汰不动 pinned 条目") {
        val d = File(System.getProperty("java.io.tmpdir"), "w16-pin-${System.nanoTime()}")
        val s = MemoryStore(d)
        // salience=1（最低档）：若不保护，它是第一个被淘汰的
        val pin = s.add("我对花生过敏（硬约束）", kind = "fact", salience = 1, pinned = true)
        // 灌到远超容量上限（MAX_ITEMS=500）。写 520 而不是「MAX_ITEMS+1」：
        // Companion 是 private，且这样断言不依赖常量值——只要**确实发生过淘汰**就有意义，
        // 将来上限调到 1000 这条会红，提醒「淘汰机制被改了，重新看这条」。
        repeat(520) { i -> s.add("填充记忆 $i", salience = 5) }
        assertTrue("必须真的发生过淘汰（否则下面那条等于没测）", s.totalSize() < 520)
        assertTrue(
            "pinned 条目必须还在（钉住是对用户的承诺）",
            s.snapshot().any { it.id == pin.id }
        )
        d.deleteRecursively()
    }

    println("\n=== 汇总: $pass 通过 / $fail 失败 ===")
    dir.deleteRecursively()
    if (fail > 0) kotlin.system.exitProcess(1)
}
