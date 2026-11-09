/**
 * R13 验证：会话落盘去抖（ConversationStore）。
 *
 * 修的是一个会随使用时长恶化的性能问题：流式输出时每个 token 追加一条消息，
 * 旧实现「每次变更 = 一次全量序列化 + 一次写盘」。会话几万字时单次写几十毫秒，
 * 而 token 每秒来几十个——写线程被压满，越聊越卡。加 fsync 之后更明显。
 *
 * 现在：400ms 去抖窗口合并变更；结构性变更（新建/删除/整库恢复）不吃窗口。
 *
 * 断言全部真跑真读：临时目录 + 真文件 + 真等待。用 [ConversationStore.persistCount]
 * 直接数「写了几次」——这是去抖是否真的生效的唯一硬证据，
 * 只断言「内容最终正确」的话，每条都写也能通过，等于没验。
 */
import com.beichen.polaris.core.ConversationStore
import com.beichen.polaris.core.MsgRecord
import java.io.File
import java.nio.file.Files

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

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$name: 期望 $expected，实际 $actual")
}

private fun tempDir(): File = Files.createTempDirectory("akit-conv-").toFile()

/** 轮询等待条件成立，最多 [timeoutMs]。比固定 sleep 稳定，且不掩盖「永远不成立」。 */
private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return true
        Thread.sleep(20)
    }
    return cond()
}

private fun msg(i: Int, role: String = "user") = MsgRecord(
    id = "m$i",
    role = role,
    content = "第 $i 条消息"
)

fun main() {
    println("=== AgentKit R13 验证（会话落盘去抖）===")

    println("\n[R13] 结构性变更：不吃去抖窗口")

    check("R13-1 新建会话立即落盘（不用等 400ms）") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val store = ConversationStore(f)
        // 预热一次再计时。
        //
        // 首次 create() 要付的是**进程级一次性成本**：类加载、kotlinx 序列化器初始化、
        // 落盘调度器的线程池启动、临时目录首次创建。CI 上并行跑 124 个 harness 时，
        // 这笔成本实测可以超过 200ms——但它与「这条变更有没有等 400ms 去抖窗口」无关。
        // 旧写法把两者算在一起，于是断言会在机器忙的时候随机变红：
        // 2026-11-09 main 构建就这样红过一次（run 42643097373），重跑即绿。
        store.create()
        val id = store.create()
        val ok = waitUntil(200) { f.exists() && f.readText().contains(id) }
        assertTrue("200ms 内已落盘", ok)
        dir.deleteRecursively()
    }

    check("R13-2 删除会话立即落盘（避免被杀后「删了又回来」）") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val store = ConversationStore(f)
        val id = store.create()
        waitUntil(500) { f.exists() && f.readText().contains(id) }
        store.delete(id)
        val gone = waitUntil(300) { f.exists() && !f.readText().contains(id) }
        assertTrue("300ms 内已从磁盘移除", gone)
        dir.deleteRecursively()
    }

    println("\n[R13] 高频追加：合并写入")

    check("R13-3 连续 100 次追加只写很少几次（去抖生效）") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val store = ConversationStore(f)
        val id = store.create()
        Thread.sleep(100)   // 让 create 的那次写先落地，计数从干净状态开始
        val before = store.persistCount
        repeat(100) { i -> store.appendMessage(id, msg(i)) }
        waitUntil(2000) { store.persistCount > before }
        Thread.sleep(600)   // 让窗口彻底关闭
        val writes = store.persistCount - before
        assertTrue("100 次追加的写盘次数 = $writes，应 <= 3", writes <= 3)
        dir.deleteRecursively()
    }

    check("R13-4 去抖不丢数据：最终 100 条全部在磁盘上") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val store = ConversationStore(f)
        val id = store.create()
        repeat(100) { i -> store.appendMessage(id, msg(i)) }
        Thread.sleep(800)
        // 用新实例读磁盘（走真实反序列化路径），而不是匹配文件里的文本片段——
        // 落盘格式改一次，文本断言就假失败一次，而读回来才是「用户能看到几条」
        val reread = ConversationStore(f).load(id)
        assertTrue("磁盘上仍有该会话", reread != null)
        assertEquals("磁盘上的消息条数", 100, reread!!.messages.size)
        assertEquals("最后一条内容", "第 99 条消息", reread.messages.last().content)
        dir.deleteRecursively()
    }

    check("R13-5 flush() 立即落盘（不等窗口）") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val store = ConversationStore(f)
        val id = store.create()
        Thread.sleep(100)
        store.appendMessage(id, MsgRecord(id = "last", role = "user", content = "窗口内的最后一条"))
        store.flush()
        // R-10：落盘改为分片（主文件只存 id 清单），「主文件正文文本匹配」不再成立。
        // 与 R13-4 同律：用新实例读回才是「用户能不能看到这条」的硬证据，
        // 换一次落盘格式也不会假失败。
        val ok = waitUntil(300) {
            runCatching {
                ConversationStore(f).load(id)?.messages?.lastOrNull()?.content == "窗口内的最后一条"
            }.getOrDefault(false)
        }
        assertTrue("flush 后 300ms 内落盘（分片读回）", ok)
        dir.deleteRecursively()
    }

    println("\n[R13] 重启与一致性")

    check("R13-6 新实例读同一文件：内容与内存态一致") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s1 = ConversationStore(f)
        val id = s1.create()
        repeat(5) { i -> s1.appendMessage(id, msg(i)) }
        s1.flush()
        Thread.sleep(300)

        val s2 = ConversationStore(f)
        val rec = s2.load(id)
        assertTrue("会话仍在", rec != null)
        assertEquals("消息条数", 5, rec!!.messages.size)
        dir.deleteRecursively()
    }

    check("R13-7 主文件写坏时从 .bak 回退（去抖不削弱容错）") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s1 = ConversationStore(f)
        val id = s1.create()
        s1.appendMessage(id, msg(0))
        s1.flush()
        Thread.sleep(300)
        s1.appendMessage(id, msg(1))
        s1.flush()
        Thread.sleep(300)

        // 模拟「rename 生效但内容没落盘」
        f.writeText("")
        val s2 = ConversationStore(f)
        val rec = s2.load(id)
        assertTrue("从备份恢复了会话", rec != null)
        dir.deleteRecursively()
    }

    println("\n[R13] 空转不写")

    check("R13-8 没有任何变更时不产生写（flush 是幂等的）") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val store = ConversationStore(f)
        store.create()
        waitUntil(1000) { store.persistCount >= 1 }
        val before = store.persistCount
        repeat(10) { store.flush() }
        Thread.sleep(300)
        assertEquals("空 flush 不应增加写次数", before, store.persistCount)
        dir.deleteRecursively()
    }

    println()
    if (failures.isEmpty()) {
        println("R13 结果：$passed 通过 / 0 失败")
    } else {
        println("R13 结果：$passed 通过 / ${failures.size} 失败")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
