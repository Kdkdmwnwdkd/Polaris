package com.beichen.polaris.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

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

    private companion object {
        /** 目录名序号：只要不重复就够，不需要随机（见 setUp 里那段说明）。 */
        val seq = AtomicInteger()
    }

    private lateinit var dir: File

    @Before
    fun setUp() {
        AgentLogger.reset()
        // reset 之后 logDir 为 null，clear 只清内存缓冲（不会误删目录）
        AgentLogger.clear()
        // 目录名里不能有随机数字。理由见下面 init 之后那段——这串路径会进日志、也会被搜到。
        dir = File(System.getProperty("java.io.tmpdir"), "polaris-logtest-${seq.incrementAndGet()}")
            .apply {
                deleteRecursively()
                mkdirs()
            }
        AgentLogger.init(dir)
        // 把 init 自己写的那条「日志目录已就绪」清掉，再等 50ms 让它的落盘结束。
        //
        // 两个理由：
        //  1. 这条带绝对路径，是**真的会被搜到**的。原先用的 TemporaryFolder 建出来是
        //     `junit18076469358617064019/logs`，那串随机数字里出现过 "4019"，搜 401 的用例
        //     凭空多一条命中 —— 2026-11-25 的 CI 就是这么红的（`maxDays` 拿到 2 条）。
        //  2. 下面 writeLog 用 writeText 截断重写，若和这次异步追加撞上，FileWriter 在
        //     append 模式下记的「末尾位置」在截断后失效，实测会让多行文件少掉最后一行。
        //
        // 清完 buffer 是空的、文件是空的，每个用例从真正的零点开始。
        // `initialized` 仍为 true，不会再触发一次 init/restoreTail。
        Thread.sleep(50)
        AgentLogger.clear()
    }

    @After
    fun tearDown() {
        AgentLogger.reset()
        AgentLogger.clear()
        // reset 之后写盘协程拿不到 logDir，这里删是安全的
        dir.deleteRecursively()
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
    fun `命中来自磁盘时 fromDisk 等于命中数`() {
        writeLog(0, lines = arrayOf(line("10:00:00.001", "INFO", "Turn", "code=http_404")))
        // setUp 已清空内存，这条只可能从文件里读到
        val result = AgentLogger.search(listOf("http_404"))

        assertEquals(1, result.hits.size)
        assertEquals("磁盘命中必须如实计数，否则屏幕上分不清内存与磁盘", 1, result.fromDisk)
        assertEquals(0, result.fromMemory)
        assertFalse(result.noDiskFiles)
    }

    @Test
    fun `目录里一个日志文件都没有时不假装搜过今天`() {
        dir.listFiles()?.forEach { it.delete() }
        AgentLogger.clear()

        val result = AgentLogger.search(listOf("随便什么"))

        assertTrue(result.hits.isEmpty())
        assertTrue(result.days.isEmpty())
        // 这条断言是这次改动的核心：旧实现在这里返回空 days，UI 于是写「已搜索今天的日志」，
        // 把一个真实故障（一个文件都没读到）说成了正常结果。
        assertTrue("一个文件都没扫到时必须能识别出来：$result", result.noDiskFiles)
        assertEquals(dir.absolutePath, result.dirPath)
        assertEquals(0, result.fromDisk)
        assertTrue("空目录不该编造条目：${result.otherEntries}", result.otherEntries.isEmpty())
    }

    @Test
    fun `目录里只有非日志文件时把它列出来`() {
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "turns-20261125.ndjson").writeText("{}\n")
        AgentLogger.clear()

        val result = AgentLogger.search(listOf("随便什么"))

        assertTrue(result.noDiskFiles)
        // 这条列表就是「写盘写到了别的名字/别的地方」的证据，屏幕上要能看见。
        assertEquals(listOf("turns-20261125.ndjson(3B)"), result.otherEntries)
    }

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

        assertEquals("多出来的是谁：${result.hits.map { "${it.day} ${it.line}" }}", 2, result.hits.size)
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

        assertEquals("多出来的是谁：${result.hits.map { "${it.day} ${it.line}" }}", 2, result.hits.size)
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
        assertEquals("shallow 只该命中今天这条：${shallow.hits}", 1, shallow.hits.size)
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
        assertTrue("5 条命中、limit=2 必须标截断：${result.hits}", result.truncated)
        // 保留的是最新的两条（第 5、第 4）
        assertTrue("hits[0] 应当是最新的第 5 条：${result.hits}", result.hits[0].line.contains("第 5 条"))
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
        // 内存里的行也有确定日期：timeMs 是刚记下的真实时刻，formatDay 直接算。
        // （曾经这里是 null → UI 显示「本次」，结果把恢复进来的昨天日志一起标成了本次。）
        assertEquals(dayStamp(0), hit.day)
        // 这条不校验 days：内存命中已经带日期了，days 就从命中行推出来，与磁盘无关。
    }

    @Test
    fun `恢复进内存的昨天日志不会被标成今天`() {
        // 2026-11-25 真机现场：11-24 夜里最后一条 23:12 的 WorkflowScheduler 记录，
        // 第二天 18:13 启动后（restoreTail 把它读回内存）搜「周期巡检」，
        // 它必须标成 11-24。当时标的是「本次」，因为内存命中一律 day=null。
        AgentLogger.reset()
        AgentLogger.clear()
        writeLog(
            1,
            lines = arrayOf(
                line("23:12:45.224", "INFO", "WorkflowScheduler", "周期巡检已注册（15 分钟）")
            )
        )
        // 这次 init 才会真的执行 doInit → restoreTail，把那 1 条读进内存
        AgentLogger.init(dir)

        val result = AgentLogger.search(listOf("周期巡检"))

        assertEquals(1, result.hits.size)
        assertEquals(dayStamp(1), result.hits[0].day)
        // days 取命中行的日期，不是「扫过哪些天」：屏幕上的范围必须能解释这些结果。
        assertEquals(listOf(dayStamp(1)), result.days)
    }

    @Test
    fun `昨天文件里的白天记录不会被还原成今天`() {
        // 上一条靠的是 23:12（跨零点），旧逻辑里「未来则减一天」那条兜底恰好能救它。
        // 这条挑一个**今天已经过去**的时刻（两小时前）：昨天 16:00 与今天 16:00 字面一样，
        // 旧实现按「今天 16:00」还原，16:00 已过 → 不触发兜底 → 昨天的事记成今天。
        // 只有「日期从文件名来」才解得对。
        val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            .format(Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000L))
        AgentLogger.reset()
        AgentLogger.clear()
        writeLog(1, lines = arrayOf(line(clock, "INFO", "Chat", "昨天白天的那条 401")))
        AgentLogger.init(dir)

        val result = AgentLogger.search(listOf("昨天白天"))

        assertEquals(1, result.hits.size)
        assertEquals(dayStamp(1), result.hits[0].day)
        assertEquals(listOf(dayStamp(1)), result.days)
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
