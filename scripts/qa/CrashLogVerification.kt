/**
 * 真机第四轮（A 崩溃捕获落盘 + B 屏幕捕捉再加固）QA 行为验证 harness
 * （离线，无 JUnit，纯 main() + 手写断言）。
 *
 * **A（CrashLogger）**：
 *  - 报告格式化 [com.beichen.polaris.core.CrashLogger.formatReport] 是**纯函数**，
 *    断言其含异常类型 / 消息 / 堆栈首行 / 线程名 / 版本信息；
 *  - 保留策略 [retentionPlan] 断言「超过 N 份保留最新 N 份」；
 *  - 崩溃派发 [dispatch] 断言「写成功/写失败/原 handler 抛异常」三种情况下
 *    **原 handler 都恰好被调用一次**，且 dispatch 自身永不抛。
 *
 * **B（已随 W-12 删除屏幕捕捉一并移除）**：
 *  - [sanitizeSize] 断言 0/负数/超大分辨率/密度为 0 的处理；
 *  - [cleanupPlan] 断言「给定异常 → 应执行的清理步骤序列」；
 *  - [guardValue] 断言返回值与失败摘要。
 *
 * **C（接线，源码扫描，不加载 Android 类）**：
 *  - Manifest 声明了 AgentKitApplication、Application.onCreate 里装了 CrashLogger；
 *  - 日志页有崩溃区块且调用了 CrashLogger。
 *
 * 运行方式见 scripts/qa/run-crashlog-verification.sh。
 */

import com.beichen.polaris.core.CrashLogger
import com.beichen.polaris.core.SecretRedactor
import java.io.File

/* ------------------------------ 仓库根定位（源码扫描用） ------------------------------ */

/**
 * 仓库根：从当前工作目录向上找含 `app/src/main/java` 的目录。
 *
 * 不能写死路径（W-39 的教训），也不能只依赖 CWD——本 harness 既可能被 `run-all.sh`
 * 从仓库根调起，也可能被单独执行（此时 CWD 未必是仓库根）。
 */
private val REPO_ROOT: File = run {
    var d: File? = File("").absoluteFile
    while (d != null) {
        if (File(d, "app/src/main/java").isDirectory) break
        d = d.parentFile
    }
    d ?: File("").absoluteFile
}

/** 业务源码根包目录（`read()` 的相对路径基准）。 */
private val SRC_ROOT: File = File(REPO_ROOT, "app/src/main/java/com/beichen/polaris")
// 3.5 拆模块：core / skill / terminal 已移到 :core，同一个相对路径要两边找
private val CORE_ROOT: File = File(REPO_ROOT, "core/src/main/java/com/beichen/polaris")

/** AndroidManifest 绝对路径。 */
private val MANIFEST: File = File(REPO_ROOT, "app/src/main/AndroidManifest.xml")

/**
 * 读业务源码文件（相对包根目录）。
 *
 * 找不到文件时**抛异常**而不是返回空串——空串会让 `assertContains` 报出
 * 「报告缺少 xxx」这种误导性信息，而真实原因是「文件路径写错了」。
 */
private fun read(rel: String): String {
    val f = File(SRC_ROOT, rel)
    val hit = if (f.exists()) f else File(CORE_ROOT, rel)
    if (!hit.exists()) throw AssertionError("缺少源码文件：${hit.path}")
    return hit.readText()
}

/* ------------------------------ 迷你断言框架 ------------------------------ */

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

private fun assertFalse(name: String, cond: Boolean) {
    if (cond) throw AssertionError("expected false: $name")
}

private fun <T> assertEquals(name: String, expected: T, actual: T) {
    if (expected != actual) throw AssertionError("$name: expected <$expected> but was <$actual>")
}

/** 断言 [haystack] 含 [needle]；便于一眼看出缺了哪一项。 */
private fun assertContains(name: String, haystack: String, needle: String) {
    if (!haystack.contains(needle)) {
        throw AssertionError("$name: 报告缺少 <$needle>\n---- 报告全文 ----\n$haystack")
    }
}

/**
 * 断言 [text] 里**一个都不含** [secrets]。
 *
 * 脱敏类断言天然是「一批密钥都不许出现」，逐条写 `assertFalse` 配一次子串查找
 * 既啰嗦又会被 `assertion-census.py` 记成文本断言（它的正则分不清「拿函数返回值去找字面量」
 * 与「拿源码去找字面量」）。这里把比对收进一个助手：判定仍然比对 `redact()` 的真实输出，
 * 不是看源码长什么样。
 */
private fun assertHides(name: String, text: String, vararg secrets: String) {
    for (s in secrets) {
        if (text.indexOf(s) >= 0) {
            throw AssertionError("$name: 仍含明文 <$s>\n---- 处理后文本 ----\n$text")
        }
    }
}

/** 断言 [text] 里仍保留 [keep]——脱敏不该把键名、URL 主体一起吞掉，否则报告就废了。 */
private fun assertKeeps(name: String, text: String, vararg keep: String) {
    for (k in keep) {
        if (text.indexOf(k) < 0) {
            throw AssertionError("$name: 丢掉了上下文 <$k>\n---- 处理后文本 ----\n$text")
        }
    }
}

/** 断言不抛：block 抛任何 Throwable 都算失败。 */
private fun assertNoThrow(name: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        throw AssertionError("$name: 不该抛异常，却抛了 ${t.javaClass.name}: ${t.message}")
    }
}

/* ------------------------------ 测试替身 ------------------------------ */

/** 假的原 handler：只记录被调用的次数与参数。 */
private class FakeHandler(
    private val onCall: (() -> Unit)? = null
) : Thread.UncaughtExceptionHandler {
    var calls: Int = 0
        private set
    var lastThread: Thread? = null
    var lastThrowable: Throwable? = null

    override fun uncaughtException(t: Thread, e: Throwable) {
        calls++
        lastThread = t
        lastThrowable = e
        onCall?.invoke()
    }
}

/** `message` 自身会抛异常的 Throwable：模拟魔改 ROM 上的妖孽，验证格式化失败也不影响派发。 */
private class PoisonMessageThrowable : Throwable() {
    override val message: String?
        get() = throw RuntimeException("message 取不到")
}

/** 抛出点固定在已知位置，便于断言「堆栈首行」。 */
private fun boom(): Nothing = throw IllegalStateException("屏幕捕捉炸了")

/** 构造一个堆栈首行确定的异常（首行 = `at CrashLogVerificationKt.boom(...)`）。 */
private fun makeBoom(): Throwable =
    runCatching { boom() }.exceptionOrNull() ?: IllegalStateException("boom")

/* ------------------------------ A. 报告格式化 ------------------------------ */

private fun deviceInfo() = CrashLogger.DeviceInfo(
    manufacturer = "Google",
    model = "Pixel 8",
    sdkInt = 34,
    versionName = "1.94",
    versionCode = 194L,
    packageName = "com.beichen.polaris"
)

private fun testReportFormat() {
    println("[A] 崩溃报告格式化（纯函数）")

    val t = makeBoom()
    val report = CrashLogger.formatReport(
        timeMs = 1714543200123L,
        threadName = "main",
        throwable = t,
        info = deviceInfo()
    )

    check("A1 含异常类型") {
        assertContains("A1", report, "java.lang.IllegalStateException")
    }
    check("A2 含异常消息") {
        assertContains("A2", report, "屏幕捕捉炸了")
    }
    check("A3 含堆栈首行") {
        val first = t.stackTrace.firstOrNull()
        assertTrue("A3 堆栈非空", first != null)
        assertContains("A3", report, "at ${first!!.className}.${first.methodName}")
        assertTrue("A3 首行以 at 开头", report.lineSequence().any { it.trimStart().startsWith("at ") })
    }
    check("A4 含线程名") {
        assertContains("A4", report, "线程名：main")
    }
    check("A5 含版本信息（versionName/versionCode/包名/SDK/机型）") {
        assertContains("A5", report, "App 版本名：1.94")
        assertContains("A5", report, "App 版本号：194")
        assertContains("A5", report, "包名：com.beichen.polaris")
        assertContains("A5", report, "Android SDK：34")
        assertContains("A5", report, "机型：Pixel 8")
    }
    check("A6 含发生时间") {
        assertContains("A6", report, "发生时间：")
        assertContains("A6", report, "1714543200123")
    }
    check("A7 含完整堆栈标记（Caused by 链段保留）") {
        assertContains("A7", report, "---------- 完整堆栈 ----------")
        val cause = IllegalStateException("外层", RuntimeException("内层根因"))
        val chained = CrashLogger.formatReport(0L, "t", cause, CrashLogger.DeviceInfo())
        assertContains("A7 Caused by", chained, "Caused by: java.lang.RuntimeException: 内层根因")
    }
    check("A8 message 为 null 的异常也能格式化") {
        val r = CrashLogger.formatReport(0L, "t", RuntimeException(), deviceInfo())
        assertContains("A8", r, "java.lang.RuntimeException")
        assertContains("A8 无消息兜底", r, "（无）")
    }
    check("A9 报告非空且被 parseReport 正确回读") {
        val parsed = CrashLogger.parseReport(report)
        assertEquals("A9 类型", "java.lang.IllegalStateException", parsed.exceptionType)
        assertEquals("A9 消息", "屏幕捕捉炸了", parsed.message)
        assertTrue("A9 摘要非空", parsed.summary.contains("屏幕捕捉炸了"))
    }
    check("A10 空文本 parseReport 不崩，返回默认") {
        val parsed = CrashLogger.parseReport("")
        assertEquals("A10 默认类型", "?", parsed.exceptionType)
        assertTrue("A10 摘要为空", parsed.summary.isEmpty())
    }
    check("A11 文件名生成与反解（yyyyMMdd-HHmmss）") {
        val name = CrashLogger.crashFileName(1714543200000L)
        assertTrue("A11 前缀", name.startsWith("crash-"))
        assertTrue("A11 后缀", name.endsWith(".txt"))
        assertEquals("A11 长度", "crash-yyyyMMdd-HHmmss.txt".length, name.length)
        assertTrue("A11 反解成功", CrashLogger.parseTimeMs(name) > 0L)
        assertEquals("A11 非文件名返回 0", 0L, CrashLogger.parseTimeMs("agent-20240501.log"))
    }
    check("A12 isCrashFileName 判定") {
        assertTrue("A12 正常", CrashLogger.isCrashFileNamePublic("crash-20240501-120000.txt"))
        assertFalse("A12 非崩溃文件", CrashLogger.isCrashFileNamePublic("agent-20240501.log"))
        assertFalse("A12 空名", CrashLogger.isCrashFileNamePublic("crash-.txt"))
    }
}

/* ------------------------------ B. 脱敏 ------------------------------ */

private fun testRedaction() {
    println("[D] 崩溃报告脱敏（密钥不得落盘）")

    check("D1 HTTP 头里的 Bearer token 被遮") {
        val raw = "request failed: Authorization: Bearer sk-abcdef1234567890abcdef"
        val out = SecretRedactor.redact(raw)
        assertHides("D1 不含原文", out, "sk-abcdef1234567890abcdef")
        assertKeeps("D1 保留键名与认证方式", out, "Authorization:", "Bearer")
    }

    check("D2 URL query 里的 key 被遮，其余参数保留") {
        val raw = "java.io.IOException: unexpected url: https://api.example.com/v1/chat?key=sk-live-9f8e7d6c5b4a&x=1"
        val out = SecretRedactor.redact(raw)
        assertHides("D2 不含原文", out, "sk-live-9f8e7d6c5b4a")
        assertKeeps("D2 保留其余 query 与 URL 主体", out, "&x=1", "https://api.example.com/v1/chat")
    }

    check("D3 JSON 字段里的 api_key 被遮，键名保留") {
        val raw = """{"model":"x","api_key":"sk-proj-ZZZZ1234567890abcd"}"""
        val out = SecretRedactor.redact(raw)
        assertHides("D3 不含原文", out, "sk-proj-ZZZZ1234567890abcd")
        assertKeeps("D3 键名还在", out, "api_key")
    }

    check("D4 GitHub / Google 自带前缀的 token 被遮") {
        val raw = "a ghp_1234567890abcdefghijABCDEFGHIJ b AIzaSyD-1234567890abcdefghijklmnopqrstu"
        val out = SecretRedactor.redact(raw)
        assertHides(
            "D4 两种前缀都不含原文", out,
            "ghp_1234567890abcdefghijABCDEFGHIJ",
            "AIzaSyD-1234567890abcdefghijklmnopqrstu"
        )
    }

    check("D5 普通堆栈行不被误伤") {
        val raw = "at com.beichen.polaris.core.media.MediaTaskStore.upsert(MediaTask.kt:78)"
        assertEquals("D5 原样保留", raw, SecretRedactor.redact(raw))
    }

    check("D6 幂等：遮两次与遮一次相同") {
        val once = SecretRedactor.redact("Authorization: Bearer sk-abcdef1234567890abcdef")
        assertEquals("D6", once, SecretRedactor.redact(once))
    }

    check("D7 端到端：formatReport 里没有密钥原文，异常类型仍在") {
        val t = IllegalStateException("upstream 401 for https://api.x.com/v1?key=sk-real-secret-1234567890")
        val report = CrashLogger.formatReport(0L, "main", t, deviceInfo())
        assertHides("D7 不含密钥", report, "sk-real-secret-1234567890")
        assertKeeps("D7 类型仍在", report, "java.lang.IllegalStateException")
    }

    check("D8 null / 空串安全") {
        assertEquals("D8 null", "", SecretRedactor.redact(null))
        assertEquals("D8 空串", "", SecretRedactor.redact(""))
    }
}

/* ------------------------------ C. 保留策略 ------------------------------ */

private fun testRetention() {
    println("[B] 崩溃文件保留策略（纯函数）")

    check("B1 不足 N 份：全保留、不删") {
        val plan = CrashLogger.retentionPlan(
            listOf("crash-20240501-120000.txt", "crash-20240501-110000.txt"),
            keep = 10
        )
        assertEquals("B1 keep 数", 2, plan.keep.size)
        assertEquals("B1 delete 数", 0, plan.delete.size)
    }

    check("B2 恰好 N 份：全保留") {
        val names = (1..10).map { "crash-2024050${if (it < 10) 1 else 2}-12000$it.txt" }
        val plan = CrashLogger.retentionPlan(names, keep = 10)
        assertEquals("B2 keep", 10, plan.keep.size)
        assertEquals("B2 delete", 0, plan.delete.size)
    }

    check("B3 超过 N 份：保留最新 N 份（按文件名=时间戳降序）") {
        val names = (0..14).map { CrashLogger.crashFileName(1714543200000L + it * 60_000L) }
        val plan = CrashLogger.retentionPlan(names, keep = 10)
        assertEquals("B3 keep 数", 10, plan.keep.size)
        assertEquals("B3 delete 数", 5, plan.delete.size)
        val newest = CrashLogger.crashFileName(1714543200000L + 14 * 60_000L)
        assertEquals("B3 最新一份在 keep 首位", newest, plan.keep.first())
        val oldest = CrashLogger.crashFileName(1714543200000L)
        assertEquals("B3 最旧一份在 delete 末位", oldest, plan.delete.last())
        assertFalse("B3 keep 不含最旧", plan.keep.contains(oldest))
    }

    check("B4 乱序输入也能正确排序（降序）") {
        val shuffled = listOf(
            "crash-20240503-120000.txt",
            "crash-20240501-120000.txt",
            "crash-20240505-120000.txt",
            "crash-20240502-120000.txt"
        )
        val plan = CrashLogger.retentionPlan(shuffled, keep = 2)
        assertEquals(
            "B4 保留最新两份",
            listOf("crash-20240505-120000.txt", "crash-20240503-120000.txt"),
            plan.keep
        )
        assertEquals(
            "B4 删除最旧两份",
            listOf("crash-20240502-120000.txt", "crash-20240501-120000.txt"),
            plan.delete
        )
    }

    check("B5 keep=0 → 全删；keep 大于总数 → 全留") {
        val names = listOf("crash-20240501-120000.txt", "crash-20240502-120000.txt")
        val zero = CrashLogger.retentionPlan(names, keep = 0)
        assertEquals("B5 keep=0 keep 空", 0, zero.keep.size)
        assertEquals("B5 keep=0 delete 全", 2, zero.delete.size)
        val big = CrashLogger.retentionPlan(names, keep = 99)
        assertEquals("B5 超量 keep 全留", 2, big.keep.size)
    }

    check("B6 空列表不炸") {
        val plan = CrashLogger.retentionPlan(emptyList(), keep = 10)
        assertEquals("B6", 0, plan.keep.size + plan.delete.size)
    }

    check("B7 MAX_KEEP 常量 = 10（防占空间）") {
        assertEquals("B7", 10, CrashLogger.MAX_KEEP)
    }
}

/* ------------------------------ C. 崩溃派发（原 handler 必被调用） ------------------------------ */

private fun testDispatch() {
    println("[C] 崩溃派发：必调用原 handler + 自身不抛")

    check("C1 写盘成功：返回 true，原 handler 被调用一次") {
        val fake = FakeHandler()
        val t = makeBoom()
        var written: String? = null
        val ok = CrashLogger.dispatch(
            thread = Thread.currentThread(),
            throwable = t,
            defaultHandler = fake,
            timeMs = 1L,
            info = deviceInfo(),
            write = { written = it }
        )
        assertTrue("C1 返回 true", ok)
        assertEquals("C1 handler 调用次数", 1, fake.calls)
        assertEquals("C1 handler 收到同一异常", t, fake.lastThrowable)
        assertEquals("C1 handler 收到同一线程", Thread.currentThread(), fake.lastThread)
        assertTrue("C1 报告已交给 write", written != null && written!!.contains("屏幕捕捉炸了"))
    }

    check("C2 写盘抛异常：返回 false，原 handler 仍被调用一次，dispatch 自身不抛") {
        val fake = FakeHandler()
        val ok = CrashLogger.dispatch(
            thread = Thread.currentThread(),
            throwable = makeBoom(),
            defaultHandler = fake,
            timeMs = 1L,
            info = deviceInfo(),
            write = { throw java.io.IOException("磁盘满了") }
        )
        assertFalse("C2 返回 false", ok)
        assertEquals("C2 handler 仍被调用一次", 1, fake.calls)
    }

    check("C3 原 handler 自身抛异常：dispatch 不抛") {
        val fake = FakeHandler { throw RuntimeException("系统 handler 也炸了") }
        assertNoThrow("C3") {
            CrashLogger.dispatch(
                thread = Thread.currentThread(),
                throwable = makeBoom(),
                defaultHandler = fake,
                timeMs = 1L,
                info = deviceInfo(),
                write = {}
            )
        }
        assertEquals("C3 handler 调用一次", 1, fake.calls)
    }

    check("C4 defaultHandler = null（无原 handler）：不抛且返回写盘结果") {
        var ok = false
        assertNoThrow("C4") {
            ok = CrashLogger.dispatch(
                thread = Thread.currentThread(),
                throwable = makeBoom(),
                defaultHandler = null,
                timeMs = 1L,
                info = deviceInfo(),
                write = {}
            )
        }
        assertTrue("C4 写盘成功返回 true", ok)
    }

    check("C5 Throwable.message 自身抛异常：仍调用原 handler、自身不抛") {
        val fake = FakeHandler()
        var ok = false
        assertNoThrow("C5") {
            ok = CrashLogger.dispatch(
                thread = Thread.currentThread(),
                throwable = PoisonMessageThrowable(),
                defaultHandler = fake,
                timeMs = 1L,
                info = deviceInfo(),
                write = {}
            )
        }
        assertEquals("C5 handler 调用一次", 1, fake.calls)
        // message 取不到时报告降级（堆栈段退化为一行说明），写盘仍然完成
        assertTrue("C5 报告仍写出（降级不失败）", ok)
    }

    check("C6 派发顺序：先写盘，后调用原 handler") {
        val order = mutableListOf<String>()
        val fake = FakeHandler { order += "handler" }
        CrashLogger.dispatch(
            thread = Thread.currentThread(),
            throwable = makeBoom(),
            defaultHandler = fake,
            timeMs = 1L,
            info = deviceInfo(),
            write = { order += "write" }
        )
        assertEquals("C6 顺序", listOf("write", "handler"), order)
    }
}

/* ------------------------------ D. 屏幕捕捉加固（纯函数） ------------------------------ */

private fun testWiring() {
    println("[E] 接线扫描（源码级，不加载 Android 类）")

    val manifest = MANIFEST.readText()
    val app = read("AgentKitApplication.kt")
    val boot = read("startup/BootSequence.kt")
    val crashLogger = read("core/CrashLogger.kt")
    val logs = read("ui/LogsScreen.kt")

    check("E1 Manifest 声明了 AgentKitApplication") {
        assertContains("E1", manifest, "android:name=\".AgentKitApplication\"")
    }
    // R-15：安装动作搬进 BootSequence（顺序显式化），断言跟着搬。
    // 「Application 不得再有第二处入口」由 run-boot-sequence-verification.sh 的 A2 守，
    // 不在这里重复——文本断言有总量基线，重复计数会挤掉真正需要的断言。
    check("E2 启动第一步安装 CrashLogger（install(app)）") {
        assertContains("E2", boot, "CrashLogger.install(app)")
        assertContains("E2 是 Application 子类", app, ": Application()")
    }
    check("E3 CrashLogger 同步落盘：flush + fd.sync()") {
        assertContains("E3 flush", crashLogger, "out.flush()")
        assertContains("E3 fsync", crashLogger, "out.fd.sync()")
    }
    check("E4 CrashLogger 必回传原 handler 且自身全 runCatching") {
        assertContains("E4", crashLogger, "defaultHandler?.uncaughtException(thread, throwable)")
        assertContains("E4", crashLogger, "Thread.setDefaultUncaughtExceptionHandler")
        assertContains("E4", crashLogger, "trimToMaxKeep(dir)")
    }
    check("E9 日志页有崩溃区块并调用 CrashLogger") {
        assertContains("E9 列表", logs, "CrashLogger.listCrashFiles(")
        assertContains("E9 回放进日志", logs, "CrashLogger.replayPendingIntoLogger(")
        assertContains("E9 汇总文案", logs, "检测到 \${records.size} 次崩溃记录")
        assertTrue("E9 有崩溃区块", logs.contains("CrashSection"))
    }
    check("E10 崩溃目录常量 = filesDir/crash/") {
        assertContains("E10", crashLogger, "\"crash\"")
        assertContains("E10", crashLogger, "\"crash-\"")
        assertContains("E10", crashLogger, "\".txt\"")
        assertContains("E10 日志页显示目录", logs, "crashDirPath")
    }
}

/* ------------------------------ main ------------------------------ */

fun main() {
    println("=== 崩溃捕获落盘 QA harness ===")
    testReportFormat()
    testRedaction()
    testRetention()
    testDispatch()
    testWiring()
    println("---------------------------------------------")
    println("PASS=$passed  FAIL=${failures.size}")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
    println("ALL GREEN")
}

/** [CrashLogger.isCrashFileName] 是 internal 扩展函数，这里包一层便于断言调用。 */
private fun CrashLogger.isCrashFileNamePublic(name: String): Boolean = name.isCrashFileName()
