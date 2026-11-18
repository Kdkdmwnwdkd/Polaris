package com.beichen.polaris.core

import com.beichen.polaris.core.io.AtomicJsonFile
import com.beichen.polaris.core.lifecycle.AppBackgroundHooks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 分片恢复（`ConversationStore.recoverFromShards`）的断言（2026-11-19）。
 *
 * ## 为什么补这组
 *
 * R-10 把「整库单文件」拆成「index + 每会话一个分片」，换来的是**容错粒度变小**：
 * 以前坏一个文件就全丢，现在一个会话坏了只丢一个。但这条好处完全押在一条路径上——
 * 主 index 与它的 `.bak` 同时不可用时，扫描分片目录重建。
 *
 * 这条路径此前零断言：它只在真机「用了三个月、某次重启」时才被走到，走错也不会有人发现。
 * 而它写错的方式很隐蔽——index 里存的是**文件名 stem**，会话的真实 id 在分片正文里。
 * 两者对 UUID 恰好相同，对备份导入的脏 id（含 `/`、中文）就不同；一旦错位，
 * index 记录的 id 与内存 id 不一致，删除、去重、归档归属都会跟着错。
 *
 * 所以这组测试的重点不是「能读回来」，而是**读回来的 id 必须还是原来那个 id**。
 */
class ConversationShardRecoveryTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("polaris-shard-").toFile()
    }

    @After
    fun tearDown() {
        // 同一进程里跑多条用例，残留 hook 会互相干扰（且弱引用回收时机不确定）
        AppBackgroundHooks.clearForTest()
        root.deleteRecursively()
    }

    /* ------------------------------ 夹具 ------------------------------ */

    private fun indexFile() = File(root, "conversations.json")

    private fun shardDir() = File(root, "conversations.d")

    /** 与 `ConversationStore.safeStem` 同算法：合法 id 原样，其余 UTF-8 hex 加 `h-` 前缀。 */
    private fun safeStem(id: String): String =
        if (Regex("[A-Za-z0-9_-]{1,128}").matches(id)) id
        else "h-" + id.toByteArray(Charsets.UTF_8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun shardFile(id: String) = File(shardDir(), "${safeStem(id)}.json")

    /** 打开一个 store 并等到它的写入队列静默（`flush` 只投递，不等待）。 */
    private fun openAndSettle(): ConversationStore {
        val s = ConversationStore(indexFile())
        s.flush()
        assertTrue("写入队列未在超时内静默", s.awaitPersist())
        return s
    }

    private fun ConversationStore.settle() {
        flush()
        assertTrue("写入队列未在超时内静默", awaitPersist())
    }

    private fun seedTwoConversations(): Pair<String, String> {
        val s = openAndSettle()
        val a = s.create()
        s.appendMessage(a, MsgRecord(id = "m1", role = ROLE_USER, content = "第一条用户消息"))
        val b = s.create()
        s.appendMessage(b, MsgRecord(id = "m2", role = ROLE_USER, content = "第二条用户消息"))
        s.settle()
        return a to b
    }

    /**
     * 给一个分片造出「上一版」备份，并把主文件写坏。
     *
     * 为什么不用现成的 `.bak`：首次写入时主文件还不存在，`AtomicJsonFile` 不会留备份——
     * 只有「第二次写」才有。测试里要的是「曾经成功写过、后来被写坏」，手工构造更直接。
     *
     * @param oldContent 备份里的正文，非 null 时替换主分片里的同名字段，用来证明
     *   读回的是**备份那一版**而不是主文件（主文件此刻已坏，读不出任何东西）。
     */
    private fun breakShardKeepingBackup(id: String, oldContent: String? = null) {
        val shard = shardFile(id)
        val text = shard.readText()
        val bakText = if (oldContent == null) text else text.replace("第一条用户消息", oldContent)
        File(shard.parentFile, shard.name + AtomicJsonFile.BACKUP_SUFFIX).writeText(bakText)
        shard.writeText("{ 半截 json")
    }

    /** 把 index 与它的 .bak 一起写坏：模拟「断电时两个文件都半截」。 */
    private fun corruptIndexAndBackup() {
        indexFile().writeText("{ this is not valid json")
        File(root, indexFile().name + AtomicJsonFile.BACKUP_SUFFIX)
            .writeText("[ {\"id\": \"half")
    }

    private fun indexIds(): List<String> {
        val text = indexFile().readText()
        return Json.parseToJsonElement(text).jsonObject["ids"]!!
            .jsonArray.map { it.jsonPrimitive.content }
    }

    /* ------------------------------ 用例 ------------------------------ */

    @Test
    fun `主索引丢失时按分片重建全部会话`() {
        val (a, b) = seedTwoConversations()
        assertTrue("前置：分片应已落盘", shardFile(a).isFile && shardFile(b).isFile)

        assertTrue("删索引", indexFile().delete())
        assertTrue("删索引备份", File(root, indexFile().name + AtomicJsonFile.BACKUP_SUFFIX).delete())

        val reopened = openAndSettle()
        assertEquals(
            "两个会话都要从分片回来（id 集合，不依赖顺序）",
            setOf(a, b),
            reopened.list().map { it.id }.toSet()
        )
    }

    @Test
    fun `主索引与其备份都损坏时不崩溃并走分片恢复`() {
        val (a, b) = seedTwoConversations()
        corruptIndexAndBackup()

        val reopened = openAndSettle()
        assertEquals(2, reopened.list().size)
        assertNotNull("恢复出的会话要能按 id 打开", reopened.load(a))
        assertEquals(
            "正文要完整读回，不是只有空壳",
            "第一条用户消息",
            reopened.load(a)!!.messages.single().content
        )
        assertEquals("标题跟着首条消息走", "第一条用户消息", reopened.load(a)!!.title)
        assertEquals("第二个会话同样完整", "第二条用户消息", reopened.load(b)!!.messages.single().content)
    }

    @Test
    fun `单个分片主文件损坏时回退其备份`() {
        val (a, b) = seedTwoConversations()
        breakShardKeepingBackup(a, oldContent = "备份里的旧版本")

        val reopened = openAndSettle()
        assertEquals("坏掉的那个会话不该被丢掉", 2, reopened.list().size)
        assertEquals(
            "读回的必须是备份那一版的内容",
            "备份里的旧版本",
            reopened.load(a)!!.messages.single().content
        )
        assertEquals("同目录的另一个会话不受影响", "第二条用户消息", reopened.load(b)!!.messages.single().content)
    }

    @Test
    fun `回退备份后主分片应被自愈`() {
        val (a, _) = seedTwoConversations()
        breakShardKeepingBackup(a)

        openAndSettle()

        val repaired = shardFile(a).readText()
        assertFalse("主分片仍是坏 JSON：每次启动都走回退，且下次写入会把坏文件备份成 .bak", repaired.startsWith("{ 半截"))
        assertTrue("自愈后应能独立解析", repaired.contains("\"id\""))
    }

    @Test
    fun `分片与备份都损坏时只跳过该会话`() {
        val (a, b) = seedTwoConversations()
        shardFile(a).writeText("{ 坏")
        AtomicJsonFile.backupOf(shardFile(a)).writeText("{ 也坏")

        val reopened = openAndSettle()
        assertEquals("另一条会话不受牵连", listOf(b), reopened.list().map { it.id })
    }

    @Test
    fun `分片目录为空且索引损坏时返回空列表`() {
        corruptIndexAndBackup()
        val reopened = openAndSettle()
        assertTrue("没有分片就该是空，不是异常", reopened.list().isEmpty())
    }

    @Test
    fun `恢复写出的索引记录的是会话真实 id 而不是文件名`() {
        // 备份导入可能带来含 `/` 的 id：落盘时 stem 是 hex，但正文里的 id 才是真身。
        val dirty = "../../evil"
        val s = openAndSettle()
        s.replaceAll(
            listOf(
                ConversationRecord(
                    id = dirty,
                    title = "脏 id 会话",
                    createdAt = 1L,
                    updatedAt = 1L,
                    messages = listOf(MsgRecord(id = "m-evil", role = ROLE_USER, content = "危险 id")),
                    turns = ConversationBranch.fromMessages(
                        listOf(MsgRecord(id = "m-evil", role = ROLE_USER, content = "危险 id"))
                    )
                )
            )
        )
        s.settle()
        corruptIndexAndBackup()

        val reopened = openAndSettle()
        assertEquals("恢复出的 id 必须是正文里的真实 id", listOf(dirty), reopened.list().map { it.id })
        assertEquals(
            "重建的索引也要写真实 id，否则下次加载就与内存对不上",
            listOf(dirty),
            indexIds()
        )
    }

    @Test
    fun `非法 id 的分片不会逃出分片目录`() {
        val dirty = "../../evil"
        val s = openAndSettle()
        s.replaceAll(
            listOf(
                ConversationRecord(
                    id = dirty,
                    title = "穿越测试",
                    createdAt = 1L,
                    updatedAt = 1L
                )
            )
        )
        s.settle()

        assertTrue("分片必须落在 conversations.d 内", shardFile(dirty).isFile)
        assertFalse("不得在父目录生成 evil.json", File(root.parentFile, "evil.json").exists())
        assertFalse("不得在数据目录生成 evil.json", File(root, "evil.json").exists())

        corruptIndexAndBackup()
        val reopened = openAndSettle()
        assertEquals("恢复时也不能顺着 id 往目录外读", listOf(dirty), reopened.list().map { it.id })
    }

    @Test
    fun `恢复出的会话 messages 由 turns 现算而不是落盘值`() {
        val (a, _) = seedTwoConversations()
        corruptIndexAndBackup()

        val reopened = openAndSettle()
        val rec = reopened.load(a)!!
        assertTrue("前置：应有轮次真源", rec.turns.isNotEmpty())
        assertEquals(
            "messages 必须等于 flatten(turns)——分片不落 messages，读回时现算",
            ConversationBranch.flatten(rec.turns),
            rec.messages
        )
    }

    @Test
    fun `恢复后删除会话重启不会复活`() {
        val (a, b) = seedTwoConversations()
        corruptIndexAndBackup()

        val recovered = openAndSettle()
        assertEquals(2, recovered.list().size)

        recovered.delete(a)
        recovered.settle()

        val reopened = openAndSettle()
        assertEquals("被删的会话不能借恢复路径复活", listOf(b), reopened.list().map { it.id })
        assertFalse("分片本体也应清掉", shardFile(a).isFile)
    }

    @Test
    fun `恢复写出的索引是 v2 且能被下次加载直接使用`() {
        val (a, b) = seedTwoConversations()
        corruptIndexAndBackup()

        openAndSettle()

        val text = indexFile().readText()
        assertTrue("索引应是带版本的对象格式，而不是旧整库数组", text.trimStart().startsWith("{"))
        assertTrue("版本号必须落盘（ALWAYS 编码）", text.contains("\"schemaVersion\""))
        assertEquals("索引应列出全部会话", setOf(a, b), indexIds().toSet())

        // 二次启动：index 可用，走的是 ids → 分片的常规路径
        val again = openAndSettle()
        assertEquals(setOf(a, b), again.list().map { it.id }.toSet())
    }
}
