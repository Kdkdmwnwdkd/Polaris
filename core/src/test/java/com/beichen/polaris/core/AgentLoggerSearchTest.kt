package com.beichen.polaris.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `AgentLogger.search` —— 内存 + 磁盘历史的检索（2026-11-25）。
 *
 * 用真临时目录、真文件，不 mock 磁盘：这条逻辑的价值全在「文件读得对不对」
 * （少了 `.log.1`、日期算错、读到半行），mock 掉文件之后这些一条都测不到。
 *
 * 每个用例的起点必须干净：[AgentLogger] 是 object，缓冲是进程级的，
 * 不 `reset + clear` 的话上一个用例的日志会跟着进下一个的搜索结果。
 */
class AgentLoggerSearchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    @Before
    fun setUp() {
        AgentLogger.reset()
        // reset 之后 logDir 为 null，clear 只清内存缓冲（不会误删目录）
        AgentLogger.clear()
        dir = tmp.newFolder("logs")
        AgentLogger.init(dir)
    }

    @After
    fun tearDown() {
        AgentLogger.reset()
        AgentLogger.clear()
    }

    // —— 工具 ——

    private fun dayStamp(offsetDays: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            .format(Date(System.currentTimeMillis() - offsetDays * 86_400_000L))

    private fun logFile(offsetDays: Long, archived: Boolean = false): File {
        val name = "agent-${dayStamp(offsetDays)}.log" + if (archived) ".1" else ""
        return File(dir, name)
    }

    private fun writeLog(offsetDays: Long, archived: Boolean = false, vararg lines: String): File =
        logFile(offsetDays, archived).apply { writeText(lines.joinToString("\n") + "\n") }

    private fun line(clock: String, level: String, tag: String, msg: String) =
        "$clock $level [$tag] $msg"

    // —— 磁盘 ——

    @Test
    fun `今天文件里的命中带今天的日期`() {
        writeLog(
            0,
            lines = arrayOf(
                line("10:00:00.001", "INFO", "Turn", "tool=http_request code=http_401 error=true"),
                line("10:00:01.002", "INFO", "Cache", "提示缓存命中 640/6420 tok"),
                line("10:00:02.003", "ERROR", "HTTP", "请求失败 code=401 body=...")
            )
        )

        val result = AgentLogger.search(listOf("401"))

        assertEquals(2, result.hits.size)
        assertEquals(listOf(dayStamp(0)), result.days)
        // 文件内是正序写、倒序出：最新的那条排在前面
        assertTrue(result.hits.first().line.contains("请求失败"))
        assertTrue(result.hits.all { it.day == dayStamp(0) })
    }

    @Test
    fun `昨天的文件也能搜到并标对日期`() {
        writeLog(0, lines = arrayOf(line("10:00:00.001", "INFO", "Turn", "今天的 401")))
        writeLog(1, lines = arrayOf(line("23:59:59.000", "ERROR", "HTTP", "昨天的 401")))

        val result = AgentLogger.search(listOf("401"))

        assertEquals(2, result.hits.size)
        // 今天在前、昨天在后
        assertEquals(dayStamp(0), result.hits[0].day)
        assertEquals(dayStamp(1), result.hits[1].day)
        assertEquals(listOf(dayStamp(0), dayStamp(1)), result.days)
    }

    @Test
    fun `maxDays 限制回溯天数`() {
        writeLog(0, lines = arrayOf(line("10:00:00.001", "INFO", "A", "401 today")))
        writeLog(3, lines = arrayOf(line("10:00:00.001", "INFO", "A", "401 three days ago")))

        val shallow = AgentLogger.search(listOf("401"), maxDays = 2)
        assertEquals(1, shallow.hits.size)
        assertEquals(dayStamp(0), shallow.hits[0].day)

        val deep = AgentLogger.search(listOf("401"), maxDays = 7)
        assertEquals(2, deep.hits.size)
        assertEquals(dayStamp(3), deep.hits[1].day)
    }

    @Test
    fun `轮转出来的归档文件也要搜`() {
        // rotate 把超过 2MB 的正文改名为 .log.1，旧记录只在这里
        writeLog(0, archived = true, lines = arrayOf(line("08:00:00.000", "WARN", "Tool", "早期的 401")))

        val result = AgentLogger.search(listOf("401"))

        assertEquals(1, result.hits.size)
        assertTrue(result.hits[0].line.contains("早期的 401"))
        assertEquals(dayStamp(0), result.hits[0].day)
    }

    @Test
    fun `同一天里 log 比 log1 新`() {
        writeLog(0, archived = true, lines = arrayOf(line("08:00:00.000", "INFO", "A", "401 归档")))
        writeLog(0, lines = arrayOf(line("20:00:00.000", "INFO", "A", "401 正文")))

        val result = AgentLogger.search(listOf("401"))

        assertEquals(2, result.hits.size)
        // .log 先读、内部倒序 → 正文那条在前
        assertTrue(result.hits[0].line.contains("正文"))
        assertTrue(result.hits[1].line.contains("归档"))
    }

    @Test
    fun `命中达到上限时标出 truncated`() {
        writeLog(
            0,
            lines = (1..5).map { line("10:00:0$it.000", "ERROR", "HTTP", "401 第 $it 条") }.toTypedArray()
        )

        val result = AgentLogger.search(listOf("401"), limit = 2)

        assertEquals(2, result.hits.size)
        assertTrue(result.truncated)
        // 保留的是最新的两条（第 5、第 4）
        assertTrue(result.hits[0].line.contains("第 5 条"))
    }

    @Test
    fun `文件里的重复行只出一条`() {
        val dup = line("10:00:00.001", "INFO", "Turn", "401 duplicate")
        writeLog(0, lines = arrayOf(dup, dup))

        assertEquals(1, AgentLogger.search(listOf("401")).hits.size)
    }

    @Test
    fun `堆栈续行不算一条结果`() {
        // 无时间戳的行是上一条的堆栈续行，混进列表会让「最新在前」失去依据
        writeLog(
            0,
            lines = arrayOf(
                line("10:00:00.001", "ERROR", "Crash", "401 boom"),
                "java.lang.IllegalStateException: 401",
                "\tat com.beichen.polaris.Foo.bar(Foo.kt:42)"
            )
        )

        val result = AgentLogger.search(listOf("401"))

        assertEquals(1, result.hits.size)
        assertTrue(result.hits[0].line.contains("boom"))
    }

    // —— 内存 ——

    @Test
    fun `刚写进内存还没落盘的也能搜到`() {
        // 落盘走异步串行协程，测试里不等；这条验证的正是「内存必须参与」
        AgentLogger.e("HTTP", "请求失败 code=401 内存里的那条")

        val result = AgentLogger.search(listOf("401"))

        assertTrue(result.hits.isNotEmpty())
        val hit = result.hits.first { it.line.contains("内存里的那条") }
        // day 为 null = 还在内存里，没有确定日期
        assertNull(hit.day)
        // 注意：不能断言 days 为空。setUp 里的 init 自己会写「日志目录已就绪」这一条
        // 并异步落盘，所以目录里几乎总有今天的文件——断言它为空会变成一个时序抽奖。
    }

    @Test
    fun `空关键词返回空结果而不是全量`() {
        writeLog(0, lines = arrayOf(line("10:00:00.001", "INFO", "A", "任意")))
        val result = AgentLogger.search(emptyList())
        assertEquals(0, result.hits.size)
        assertFalse(result.truncated)
        assertEquals(emptyList<String>(), result.days)
    }

    @Test
    fun `没有命中时不崩`() {
        val result = AgentLogger.search(listOf("这串词一定不存在-zzz"))
        assertEquals(0, result.hits.size)
    }

    @Test
    fun `多个关键词要同时命中`() {
        writeLog(
            0,
            lines = arrayOf(
                line("10:00:00.001", "INFO", "Turn", "tool=http_request code=http_401"),
                line("10:00:00.002", "INFO", "Turn", "tool=http_request code=http_200"),
                line("10:00:00.003", "INFO", "Cache", "401 但无关")
            )
        )

        val result = AgentLogger.search(listOf("http_request", "401"))

        assertEquals(1, result.hits.size)
        assertTrue(result.hits[0].line.contains("http_401"))
    }
}
