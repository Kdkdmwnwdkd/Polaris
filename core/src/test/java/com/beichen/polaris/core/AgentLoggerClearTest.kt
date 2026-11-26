package com.beichen.polaris.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * `AgentLogger.clear` —— 「清空」必须真的把日志删干净（2026-11-25）。
 *
 * 真机反馈：「我每次有时候都清空的，但不知道为什么你还能收到两三天前的」。
 * 根因是 clear 只删 `agent-<今天>.log` 与 `.log.1`，历史文件一行没动。
 *
 * 这里守三件事：历史文件必须一起删、非日志文件不能误删、清空后还能正常写。
 */
class AgentLoggerClearTest {

    private companion object {
        val seq = AtomicInteger()
    }

    private lateinit var dir: File

    @Before
    fun setUp() {
        AgentLogger.reset()
        AgentLogger.clear()
        // 目录名不带随机数：它会进日志、也会被后面的用例读到（见 AgentLoggerSearchTest 的说明）。
        dir = File(System.getProperty("java.io.tmpdir"), "polaris-cleartest-${seq.incrementAndGet()}")
            .apply {
                deleteRecursively()
                mkdirs()
            }
        AgentLogger.init(dir)
        // 等 init 的那条「日志目录已就绪」落盘完，再清掉——每个用例从真正的零点开始。
        Thread.sleep(50)
        AgentLogger.clear()
    }

    @After
    fun tearDown() {
        AgentLogger.reset()
        AgentLogger.clear()
        dir.deleteRecursively()
    }

    /** 目录里还算「日志」的文件——与实现里的判据一致。 */
    private fun logFiles(): List<File> = dir.listFiles()
        ?.filter {
            it.isFile && it.name.startsWith("agent-") &&
                (it.name.endsWith(".log") || it.name.endsWith(".log.1"))
        }
        ?: emptyList()

    @Test
    fun `清空会删掉历史日志文件`() {
        File(dir, "agent-20261123.log").writeText("23 号\n")
        File(dir, "agent-20261124.log").writeText("24 号\n")
        File(dir, "agent-20261124.log.1").writeText("24 号的轮转归档\n")

        val deleted = AgentLogger.clear()

        assertEquals("三个文件都要算进删除数", 3, deleted)
        assertTrue(
            "历史日志必须一起删，实际还剩：${logFiles().map { it.name }}",
            logFiles().isEmpty(),
        )
    }

    @Test
    fun `清空不碰非日志文件`() {
        val turns = File(dir, "turns-20261125.ndjson").apply { writeText("{}\n") }
        File(dir, "agent-20261124.log").writeText("24 号\n")

        AgentLogger.clear()

        assertTrue("回合事件流不是日志，不该被清空带走", turns.exists())
    }

    @Test
    fun `清空返回 0 表示本来就没有文件`() {
        assertEquals(0, AgentLogger.clear())
    }

    @Test
    fun `清空后仍能正常写日志`() {
        File(dir, "agent-20261124.log").writeText("24 号\n")

        AgentLogger.clear()
        AgentLogger.i("ClearTest", "after-clear")
        Thread.sleep(200)

        val text = logFiles().joinToString("") { it.readText() }
        assertTrue("清空不该把日志系统本身弄哑：$text", text.contains("after-clear"))
    }

    /**
     * 排队中的写入不能在清空之后把旧行写回来。
     *
     * 落盘是单线程异步的：一次灌几百条，清空时队列里通常还压着一批。
     * 没有代数标记的实现，删完文件后这些任务醒来会重建当天文件、把旧内容整段写回——
     * 用户看到「已清空」，回头一搜旧内容还在。
     *
     * 这条是概率性守门（灌得够多才会有积压），但它抓的是真缺陷：把 `generation`
     * 比对去掉，多跑几轮必红。
     */
    @Test
    fun `清空后排队中的写入不会把旧内容带回来`() {
        repeat(500) { AgentLogger.i("Bulk", "OLD-LINE-$it") }
        AgentLogger.clear()
        Thread.sleep(400)

        val text = logFiles().joinToString("") { it.readText() }
        assertFalse("清空后不应出现旧行：${text.take(200)}", text.contains("OLD-LINE-"))
    }

    /**
     * 清空之后的第一次搜索，「目录为空」不能把目录又填上。
     *
     * 2026-11-26 真机 10:29：用户删完立刻搜了一次，清单显示
     * `日志文件 1 个：agent-20261126.log(181B)`——181B 就是那条
     * 「目录下没有任何 agent-*.log」的 WARN 自己。用写文件证明「没有文件」，
     * 用户看到的是「删了还剩一个」。
     */
    @Test
    fun `清空后立刻搜索不会把文件再造出来`() {
        File(dir, "agent-20261124.log").writeText("24 号\n")
        AgentLogger.clear()

        // 目录此刻真的空了：旧实现会写 WARN 并落盘，那条 WARN 自己就是新文件。
        val result = AgentLogger.search(listOf("随便什么"))
        Thread.sleep(300)

        assertTrue(
            "静默期内不该重建文件：${logFiles().map { it.name }}",
            logFiles().isEmpty(),
        )
        assertTrue("目录为空仍要如实报告给界面", result.noDiskFiles)
    }
}
