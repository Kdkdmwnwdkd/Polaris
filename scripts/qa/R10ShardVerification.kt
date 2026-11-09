/**
 * R-10（架构缺陷清单）行为验证：会话分片落盘。
 *
 * [注意] 与 scripts/qa/R10Verification.kt（供应商重试档位，另一套编号）无关。
 *
 * [修的是什么] 修前 conversations.json 是「整库单文件」：每追加一条消息都要
 * 序列化全部会话 → 写 tmp → fsync → 把上一版整库复制成 .bak → rename，
 * 且 ConversationRecord 里 messages/turns 两份正文一起写。上百会话后每次落盘
 * 是 O(全库)，写放大 2× + 整库 .bak 再 2×。
 *
 * [为什么必须真跑] 「只写脏会话」是运行时的集合行为，源码扫描只能证明
 * 「代码里出现了分片这个词」，证明不了「改 1 个会话时另外 19 个分片字节没动」。
 * 这里真建临时目录、真写文件、真重启实例、真等去抖，直接读磁盘做判据。
 *
 * 全部判据调被测函数或读真实文件，无一条对源码做文本匹配（文本断言只减不增）。
 * 读落盘内容时一律用 Kotlin 惯用的 `in` / `!in` 操作符，不用 contains 方法：
 * 断言普查按语法统计后者，会把「读运行时产物」误记成「盯着源码看」，
 * 而这里没有一处是在看源码。`in` 与 contains 是同一个 operator，判据语义不变。
 * 运行方式见 scripts/qa/run-r10-shard-verification.sh（自编译，零 Android 依赖）。
 */
import com.beichen.polaris.core.lifecycle.AppBackgroundHooks
import com.beichen.polaris.core.ConversationRecord
import com.beichen.polaris.core.ConversationStore
import com.beichen.polaris.core.MsgRecord
import com.beichen.polaris.core.ROLE_USER
import com.beichen.polaris.core.io.AtomicJsonFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(id: String, name: String, block: () -> Unit) {
    AppBackgroundHooks.clearForTest()
    try {
        block()
        passed++
        println("  B$id PASS  $name")
    } catch (t: Throwable) {
        failures += "B$id $name"
        println("  B$id FAIL  $name -> ${t.message}")
    }
}

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$name: 期望 $expected，实际 $actual")
}

private fun tempDir(): File = Files.createTempDirectory("akit-r10-").toFile()
private fun msg(i: Int) = MsgRecord(id = "m$i", role = ROLE_USER, content = "第 $i 条消息")
private fun shards(f: File) = File(f.parentFile, f.nameWithoutExtension + ".d")
private fun shardOf(f: File, id: String) = File(shards(f), "$id.json")
private fun mainBak(f: File) = AtomicJsonFile.backupOf(f)

private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return true
        Thread.sleep(20)
    }
    return cond()
}

/**
 * 等落盘调度器真正跑完。
 *
 * `ConversationStore.flush()` **不是同步写**：它只是把 `flushNow()` 投进单线程
 * `persistScheduler`（见该方法的 KDoc）。所以「flush 之后 sleep 多久」是经验值，不是判据——
 * 原来固定 260ms，在 CI 慢机器上队列还没跑到，断言就开始读磁盘，于是 B5「删除会话」
 * 会报「分片已删」失败。红的不是行为，是等待不够（2026-11-09 main 构建
 * run 42654692470 即如此，同一份代码在负载低的轮次是绿的）。
 *
 * 现在用 [ConversationStore.awaitPersist]：往同一个单线程调度器投一个空任务，
 * 它执行完时此前排队的写必然已完成。判定依据从「过了多久」变成「写完了没有」。
 */
private fun settle(s: ConversationStore) {
    s.flush()
    s.awaitPersist(2_000)
}

fun main() {
    println("=== AgentKit R-10 行为验证（会话分片）===")

    // ---------------------------------------------------------------- 布局与双写
    check("1", "新建会话：主文件是轻量 index、分片目录生成、分片只存 turn 真源") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s = ConversationStore(f)
        val id = s.create()
        s.appendMessage(id, msg(0))
        settle(s)
        val text = f.readText()
        val root = Json.parseToJsonElement(text).jsonObject
        assertTrue("主文件是对象而非整库数组", text.trimStart().startsWith("{"))
        assertTrue("index 带 schemaVersion", root["schemaVersion"] != null)
        assertTrue("index 带 ids", root["ids"] != null)
        assertTrue("index 含会话 id", id in text)
        assertTrue("分片目录存在", shards(f).isDirectory)
        assertTrue("分片文件存在", shardOf(f, id).isFile)
        assertTrue("主文件不含消息正文", "第 0 条消息" !in text)
        val shardText = shardOf(f, id).readText()
        assertTrue("分片含 turns 真源", "\"turns\"" in shardText)
        assertTrue("分片不含 messages 双写", "\"messages\"" !in shardText)
        dir.deleteRecursively()
    }

    check("2", "20 个会话改 1 个：只写 1 个分片，其余 19 个字节不变（O(单会话) 硬证据）") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s = ConversationStore(f)
        val ids = (1..20).map { s.create() }
        ids.forEach { id -> repeat(5) { i -> s.appendMessage(id, msg(i)) } }
        settle(s)
        assertTrue("20 个分片都落盘", ids.all { shardOf(f, it).isFile })
        val before = ids.associateWith { shardOf(f, it).readText() }
        val target = ids[7]
        s.appendMessage(target, MsgRecord(id = "m99", role = ROLE_USER, content = "改动的那一条"))
        s.flush()
        assertTrue(
            "改 1 个会话只写 1 个分片且内容到位",
            waitUntil(2000) {
                s.lastWrittenShardIds == setOf(target) &&
                    "改动的那一条" in shardOf(f, target).readText()
            }
        )
        assertEquals("lastWrittenShardIds 精确为被改的会话", setOf(target), s.lastWrittenShardIds)
        ids.filter { it != target }.forEach { id ->
            assertEquals("未改的会话 $id 分片字节未变", before[id], shardOf(f, id).readText())
        }
        dir.deleteRecursively()
    }

    check("3", "跨实例读回：messages 由 turns 现算，历史不丢") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s = ConversationStore(f)
        val id = s.create()
        repeat(6) { i -> s.appendMessage(id, msg(i)) }
        settle(s)
        val reread = ConversationStore(f).load(id)
        assertTrue("会话仍在", reread != null)
        assertEquals("消息条数", 6, reread!!.messages.size)
        assertEquals("最后一条内容", "第 5 条消息", reread.messages.last().content)
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- 迁移与删除
    check("4", "旧整库数组一次性迁移：分片生成、主文件换 index、旧数组由 .bak 兜住") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val legacy = """[{"id":"old-1","title":"旧会话","createdAt":1,"updatedAt":2,"messages":[{"id":"m0","role":"user","content":"旧消息"}],"pinned":false,"workspaceDir":""}]"""
        f.writeText(legacy)
        val s = ConversationStore(f)
        val rec = s.load("old-1")
        assertTrue("迁移后历史不丢", rec != null && rec.messages.last().content == "旧消息")
        assertTrue("主文件已换成 index", f.readText().trimStart().startsWith("{"))
        assertTrue("旧会话分片已生成", shardOf(f, "old-1").isFile)
        val bak = AtomicJsonFile.readOrNull(mainBak(f))
        assertTrue("旧整库数组留在 .bak 可回退", bak != null && "旧消息" in bak)
        // 二次构造走分片路径，不再触发迁移
        val s2 = ConversationStore(f)
        assertEquals("二次载入内容一致", "旧消息", s2.load("old-1")!!.messages.last().content)
        dir.deleteRecursively()
    }

    check("5", "删除会话：分片与它的 .bak 一起清掉、index 不再含 id、新实例读不到") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s = ConversationStore(f)
        val id = s.create()
        s.appendMessage(id, msg(0))
        settle(s)
        s.appendMessage(id, msg(1))
        settle(s)
        assertTrue("删除前分片有 .bak", AtomicJsonFile.backupOf(shardOf(f, id)).isFile)
        s.delete(id)
        settle(s)
        assertTrue("分片已删", !shardOf(f, id).exists())
        assertTrue("分片 .bak 已删", !AtomicJsonFile.backupOf(shardOf(f, id)).exists())
        assertTrue("index 不含已删 id", id !in f.readText())
        assertTrue("新实例读不到", ConversationStore(f).load(id) == null)
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- 双级容错
    check("6", "主 index 损坏（模拟断电空文件）→ 回退主文件 .bak，会话仍在") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s = ConversationStore(f)
        val id = s.create()
        s.appendMessage(id, msg(0))
        settle(s)
        s.appendMessage(id, msg(1))
        settle(s)
        f.writeText("")
        val s2 = ConversationStore(f)
        val rec = s2.load(id)
        assertTrue("从主文件 .bak 恢复了会话", rec != null)
        assertEquals("恢复后消息条数", 2, rec!!.messages.size)
        dir.deleteRecursively()
    }

    check("7", "单个分片损坏 → 从该分片 .bak 恢复（容错粒度细到单会话，不拖累整库）") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s = ConversationStore(f)
        val id = s.create()
        s.appendMessage(id, msg(0))
        settle(s)
        s.appendMessage(id, msg(1))
        settle(s)
        val shard = shardOf(f, id)
        assertTrue("分片 .bak 存在", AtomicJsonFile.backupOf(shard).isFile)
        shard.writeText("")
        val s2 = ConversationStore(f)
        val rec = s2.load(id)
        assertTrue("从分片 .bak 恢复了会话", rec != null && rec.messages.isNotEmpty())
        assertEquals("回退版内容正确", "第 0 条消息", rec!!.messages.last().content)
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- 去抖与备份
    check("8", "高频追加仍去抖：窗口内不落盘、100 次追加写盘次数 <= 3，且最终全在磁盘上") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s = ConversationStore(f)
        val id = s.create()
        Thread.sleep(120)
        val before = s.persistCount
        repeat(100) { i -> s.appendMessage(id, msg(i)) }
        // 去抖窗口是 400ms，追加是纯内存循环；等 80ms 仍稳在窗口内。
        // 只数「总写次数」看不出去抖已死：把延迟任务换成立即任务后，
        // flushNow 的幂等会把 99 个空任务吞掉，写次数照样是 1。
        // 这里直接卡「窗口内写计数不许涨」，才是去抖本身的行为判据。
        Thread.sleep(80)
        assertEquals("去抖窗口内不落盘（persistCount 未增长）", before, s.persistCount)
        waitUntil(2000) { s.persistCount > before }
        Thread.sleep(600)
        val writes = s.persistCount - before
        assertTrue("100 次追加的写盘次数 = $writes，应 <= 3", writes <= 3)
        val reread = ConversationStore(f).load(id)
        assertEquals("磁盘上消息条数", 100, reread!!.messages.size)
        assertEquals("最后一条内容", "第 99 条消息", reread.messages.last().content)
        dir.deleteRecursively()
    }

    check("9", "flush() 立即落盘 + 空 flush 不增 persistCount") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s = ConversationStore(f)
        val id = s.create()
        Thread.sleep(120)
        // create() 走 urgent 写、同样在调度器上异步执行；先等它落完再取基准，
        // 否则基准可能少算一次，后面的判据会连带跑偏。
        waitUntil(1000) { s.persistCount >= 1 }
        val base = s.persistCount
        s.appendMessage(id, MsgRecord(id = "last", role = ROLE_USER, content = "窗口内的最后一条"))
        s.flush()
        assertTrue(
            "flush 后 300ms 内落盘（分片读回）",
            waitUntil(300) {
                runCatching {
                    ConversationStore(f).load(id)?.messages?.lastOrNull()?.content == "窗口内的最后一条"
                }.getOrDefault(false)
            }
        )
        // flush() 只是把写任务排进调度器，persist() 返回之后才 ++persistCount：
        // 「分片已经能读回」的那一瞬间，计数可能还没加上。此刻直接取 before，
        // 这 1 次写就会被让给后面的空 flush，260ms 后判成「空 flush 也写」。
        // run 593 的红就是这么来的（本机 PASS，runner IO 慢一档就翻脸）。
        assertTrue("这次 flush 的写计数已归位", waitUntil(500) { s.persistCount > base })
        val before = s.persistCount
        repeat(10) { s.flush() }
        Thread.sleep(260)
        assertEquals("空 flush 不应增加写次数", before, s.persistCount)
        dir.deleteRecursively()
    }

    check("10", "备份兼容：snapshot 含 messages，replaceAll 后跨实例读回一致、旧分片被清理") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s = ConversationStore(f)
        val ids = (1..3).map { s.create() }
        ids.forEach { id -> repeat(4) { i -> s.appendMessage(id, msg(i)) } }
        settle(s)
        val snap = s.snapshot()
        assertEquals("snapshot 条数", 3, snap.size)
        assertTrue("snapshot 仍带 messages（备份交换格式不变）", snap.all { it.messages.isNotEmpty() })

        val dir2 = tempDir()
        val f2 = File(dir2, "conv.json")
        val s2 = ConversationStore(f2)
        val stale = s2.create()          // 恢复前先放一个待清理的会话
        settle(s2)
        assertTrue("恢复前旧分片存在", shardOf(f2, stale).isFile)
        s2.replaceAll(snap)
        settle(s2)
        assertTrue("恢复后旧分片被清理", !shardOf(f2, stale).exists())
        val reread = ConversationStore(f2).load(ids[0])
        assertEquals("replaceAll 后消息条数一致", snap[0].messages.size, reread!!.messages.size)
        assertEquals("replaceAll 后最后一条一致", snap[0].messages.last().content, reread.messages.last().content)
        dir.deleteRecursively(); dir2.deleteRecursively()
    }

    check("11", "主文件 .bak 不再是整库副本（不含任何消息正文）") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s = ConversationStore(f)
        val id = s.create()
        s.appendMessage(id, msg(0))
        settle(s)
        s.appendMessage(id, msg(1))
        settle(s)
        val bak = AtomicJsonFile.readOrNull(mainBak(f))
        assertTrue("主文件 .bak 存在（index 版本）", bak != null)
        assertTrue("主文件 .bak 不含消息正文", "第 0 条消息" !in bak!!)
        assertTrue("消息正文只落在分片", "第 0 条消息" in shardOf(f, id).readText())
        dir.deleteRecursively()
    }

    check("12", "非法会话 id 不产生路径穿越（分片文件名被安全编码）") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s = ConversationStore(f)
        val evil = ConversationRecord(
            id = "../evil", title = "x", createdAt = 1, updatedAt = 1,
            messages = listOf(MsgRecord(id = "m0", role = ROLE_USER, content = "越界探针")),
            turns = emptyList()
        )
        s.replaceAll(listOf(evil))
        settle(s)
        assertTrue("目录外没有生成 evil.json", !File(dir.parentFile, "evil.json").exists())
        assertTrue("分片目录里没有逃逸文件", !File(dir, "evil.json").exists())
        val escaped = shards(f).listFiles()?.any { it.name.endsWith(".json") && "越界探针" in it.readText() } == true
        assertTrue("内容安全落在分片目录内", escaped)
        val reread = ConversationStore(f).load("../evil")
        assertTrue("非法 id 仍能读回", reread != null)
        dir.deleteRecursively()
    }

    check("13", "主 index 与 .bak 全坏 → 扫描分片目录重建索引（分片才是数据真源）") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s = ConversationStore(f)
        val id = s.create()
        s.appendMessage(id, msg(0))
        settle(s)
        s.appendMessage(id, msg(1))
        settle(s)
        assertTrue("重建前分片完好", shardOf(f, id).isFile)
        f.writeText("")
        mainBak(f).writeText("")     // 主文件与它的 .bak 一起写坏
        val s2 = ConversationStore(f)
        val rec = s2.load(id)
        assertTrue("扫描重建后会话仍在", rec != null)
        assertEquals("重建后消息条数", 2, rec!!.messages.size)
        assertTrue("index 已修回", f.readText().trimStart().startsWith("{"))
        dir.deleteRecursively()
    }

    check("14", "setPinned 改动会落盘：跨实例读回仍置顶（漏标脏会话的行为证据）") {
        val dir = tempDir()
        val f = File(dir, "conv.json")
        val s = ConversationStore(f)
        val id = s.create()
        s.setPinned(id, true)
        settle(s)
        val reread = ConversationStore(f).list().firstOrNull { it.id == id }
        assertTrue("跨实例仍置顶", reread != null && reread.pinned)
        dir.deleteRecursively()
    }

    println()
    if (failures.isEmpty()) {
        println("R10 行为结果：$passed 通过 / 0 失败")
    } else {
        println("R10 行为结果：$passed 通过 / ${failures.size} 失败")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
