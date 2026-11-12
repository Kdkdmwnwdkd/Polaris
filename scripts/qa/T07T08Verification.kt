#!/usr/bin/env kotlin
/**
 * Phase 1 · T07（终端后端抽象）+ T08（会话池）QA 行为验证 harness。
 *
 * 背景：仓库无测试源集、离线 Gradle 缓存无 JUnit，故用「纯 main() + 手写断言」，
 * 直接对 assembleDebug 编译产物做行为验证（JVM 上跑不了真 Android / proot，故：
 *   - 本机 user 后端用**真实 `/bin/sh` 进程**（[LocalUserBackend]）验证端到端；
 *   - proot 后端用**假后端**（实现 [TerminalBackend] 同一接口）验证「两后端同构」；
 *   - 工厂降级链 / 缓存用**假 provider** 验证。
 *
 * 覆盖：
 *   1. 两后端走同一接口（假 proot + 真 /bin/sh，均经会话池）
 *   2. getHighestAvailable() 降级链：proot 可用→proot；不可用→回落 user
 *   3. 可用性缓存 + clearCache() 后重新求值
 *   4. 句柄恒 4 位十六进制 + 碰撞处理（随机重试 / 顺序补位）
 *   5. 身份冲突显式报错（open 复用 & run 声明不一致）
 *   6. 并发会话输出不串（跨会话并行 + 同会话串行）
 *   7. 多轮 shell 保持 cwd/env（假后端模拟 + 真 /bin/sh）
 *   8. 僵尸会话自动释放别名并可重开
 *   9. ToolOutputTruncator 边界（=上限 / 超限 / 多字节字符不切断）
 *  10. 上层 shell 工具参数校验 + app_stop shell runner 桥
 *
 * 运行方式见 scripts/qa/run-t07t08-verification.sh。
 */

import com.beichen.polaris.core.terminal.TERMINAL_HANDLE_REGEX
import com.beichen.polaris.core.terminal.TerminalSessionPool
import com.beichen.polaris.core.terminal.ToolOutputTruncator
import com.beichen.polaris.core.terminal.backend.LocalUserBackend
import com.beichen.polaris.core.terminal.backend.TerminalBackend
import com.beichen.polaris.core.terminal.backend.TerminalBackendFactory
import com.beichen.polaris.core.terminal.backend.TerminalBackendProvider
import com.beichen.polaris.core.terminal.backend.TerminalBackendSource
import com.beichen.polaris.core.terminal.backend.TerminalIdentity
import com.beichen.polaris.tools.ShellCloseTool
import com.beichen.polaris.tools.ShellRunTool
import com.beichen.polaris.tools.terminalShellCommandRunner
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

private suspend fun check(name: String, block: suspend () -> Unit) {
    try {
        block()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += "$name -> ${t.message}"
        println("  FAIL  $name -> ${t.message}")
    }
}

private fun assertTrue(name: String, cond: Boolean) {
    if (!cond) throw AssertionError("expected true: $name")
}

private fun assertEquals(name: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("[$name] expected <$expected> but was <$actual>")
}

private fun assertContains(name: String, haystack: String?, needle: String) {
    if (haystack?.contains(needle) != true) {
        throw AssertionError("[$name] expected to contain <$needle> in <${haystack?.take(120)}>")
    }
}

private fun json(vararg kv: Pair<String, String>): JsonObject =
    JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })

/* ================================================================== */
/* 假后端：实现 TerminalBackend 同一接口，模拟一条带状态的小 shell        */
/* ================================================================== */

private data class FakeReply(val output: String, val exit: Int)

/**
 * 假 shell 后端：把池下发的「命令行」按一组最小规则求值，并**应答哨兵协议**
 * （收到 `echo "__AGENTKIT_POOL_END__<token>__$?"` → 回吐 `__AGENTKIT_POOL_END__<token>__<退出码>`）。
 *
 * 模拟状态：cwd 与 env 在多次 send 之间**持久**（用于验证会话复用保持 cwd/env）。
 * [chunkDelayMs] > 0 时把输出**分两片**并夹一段延迟，用于制造「未串行即交织」的窗口。
 */
private class FakeShellBackend(
    override val backendKey: String,
    override val identity: TerminalIdentity = TerminalIdentity.User,
    private val chunkDelayMs: Long = 0,
    private val startOk: Boolean = true,
    /**
     * 启动耗时（毫秒）：用于制造「查 → 启 → 登记」之间的重叠窗口。
     *
     * A-10 的原子性只有在 start 真的挂起一段时间时才暴露得出来——瞬间返回的话，
     * 两个协程大概率是严格串行跑完的，测试会假绿。
     */
    private val startDelayMs: Long = 0,
    /**
     * 静默模式：收到命令后**不回哨兵**，让池一直等到超时。
     *
     * 用来测「调用方覆盖超时」——默认后端总会在同一 tick 内应答，超时分支永远走不到。
     */
    private val silent: Boolean = false
) : TerminalBackend {

    private val channel = Channel<String>(Channel.UNLIMITED)
    override val output: Flow<String> = channel.receiveAsFlow()

    @Volatile private var started = false
    @Volatile private var closed = false
    @Volatile var forceDead = false

    private var cwd = "/root"
    private val env = linkedMapOf<String, String>()
    private var lastExit = 0

    override val isAlive: Boolean get() = started && !closed && !forceDead

    override suspend fun start(): Boolean {
        if (startDelayMs > 0) delay(startDelayMs)
        if (startOk) started = true
        return startOk
    }

    override suspend fun send(input: String): Boolean {
        if (!isAlive) return false
        // 静默模式：收下但不产生任何输出（含不答哨兵）→ 池只能靠超时收场。
        if (silent) return true
        val line = input.trimEnd('\n', '\r')
        val markIdx = line.indexOf(TerminalSessionPool.SENTINEL_MARKER)
        if (markIdx >= 0 && line.trimStart().startsWith("echo")) {
            // 应答哨兵（模拟真 shell 执行 echo）
            val token = line.substring(markIdx + TerminalSessionPool.SENTINEL_MARKER.length).substringBefore("__")
            emit("${TerminalSessionPool.SENTINEL_MARKER}${token}__$lastExit")
            return true
        }
        val (out, code) = eval(line)
        lastExit = code
        if (out.isNotEmpty()) emit(out)
        return true
    }

    private suspend fun emit(s: String) {
        if (chunkDelayMs <= 0 || s.length < 2) {
            channel.send(s)
            return
        }
        val mid = s.length / 2
        channel.send(s.substring(0, mid))
        delay(chunkDelayMs)
        channel.send(s.substring(mid))
    }

    private fun eval(raw: String): FakeReply {
        val t = raw.trim()
        return when {
            t.isEmpty() -> FakeReply("", 0)
            t.startsWith("cd ") -> {
                val d = t.removePrefix("cd ").trim().trim('"')
                cwd = d.ifEmpty { "/" }
                FakeReply("", 0)
            }
            t == "pwd" -> FakeReply(cwd, 0)
            t.startsWith("export ") -> {
                val kv = t.removePrefix("export ").trim()
                val i = kv.indexOf('=')
                if (i > 0) env[kv.substring(0, i)] = kv.substring(i + 1)
                FakeReply("", 0)
            }
            t.startsWith("echo ") -> {
                val arg = t.removePrefix("echo ").trim().trim('"')
                val v = if (arg.startsWith("$")) env[arg.substring(1)].orEmpty() else arg
                FakeReply(v, 0)
            }
            else -> FakeReply("$t: not found", 127)
        }
    }

    override suspend fun close() {
        closed = true
        started = false
        channel.close()
    }

    override suspend fun awaitExit(): Int = lastExit
}

/* ------------------------------------------------------------------ */
/* 假 provider / source                                                 */
/* ------------------------------------------------------------------ */

/** 假后端提供者：`probe()` 计数用于验证「缓存命中 / clearCache 后重探」。 */
private class FakeProvider(
    override val identity: TerminalIdentity,
    override val backendKey: String,
    @Volatile var available: Boolean,
    private val factory: () -> TerminalBackend
) : TerminalBackendProvider {
    var probeCount = 0
    override fun probe(): Boolean {
        probeCount++
        return available
    }
    override fun create(): TerminalBackend = factory()
}

/** 假后端来源：proot 优先，未实现身份返回 null（与真工厂语义一致）。 */
private class FakeSource(
    private val proot: (() -> TerminalBackend?)? = null,
    private val user: () -> TerminalBackend? = { null }
) : TerminalBackendSource {
    override fun obtain(identity: TerminalIdentity, preferProot: Boolean): TerminalBackend? {
        if (identity != TerminalIdentity.User) return null
        return if (preferProot) (proot?.invoke() ?: user()) else user()
    }
}

/** 宽松来源：对 Su 身份也返回后端（key 仍为 user）——用于隔离验证「池层身份冲突」。 */
private class PermissiveSource(
    private val makeUser: () -> TerminalBackend,
    private val makeSu: () -> TerminalBackend
) : TerminalBackendSource {
    override fun obtain(identity: TerminalIdentity, preferProot: Boolean): TerminalBackend? = when (identity) {
        TerminalIdentity.User -> makeUser()
        TerminalIdentity.Su -> makeSu()
        else -> null
    }
}

private fun userSource(): TerminalBackendSource =
    FakeSource(user = { FakeShellBackend(TerminalBackend.KEY_USER) })

/**
 * W-55 用：按顺序给出**多条候选**的假来源（对应真工厂的 `obtainCandidates`）。
 *
 * `FakeSource` 只实现 `obtain`，默认 `obtainCandidates` 会退化成单候选——
 * 要测「首选起不来、回落下一档」就必须显式给全量。
 */
private class CandidateSource(private val makers: List<() -> TerminalBackend>) : TerminalBackendSource {
    override fun obtain(identity: TerminalIdentity, preferProot: Boolean): TerminalBackend? =
        if (identity == TerminalIdentity.User) makers.firstOrNull()?.invoke() else null

    override fun obtainCandidates(identity: TerminalIdentity, preferProot: Boolean): List<TerminalBackend> =
        if (identity == TerminalIdentity.User) makers.mapNotNull { it() } else emptyList()
}

/* ================================================================== */
/* 0. W-55：后端 start 失败 → 回落下一档                                 */
/* ================================================================== */

/**
 * 现实对应：rootfs 被删 / 无线调试配对失效后，proot 或 adb 后端「能构造、起不来」，
 * 而本机 shell 明明可用。改动前 `open()` 是「取一个 → start，失败就报 start_failed」，
 * 用户看到失败而不是回落——这组用例钉住新语义。
 */
private fun testBackendStartFallback() = runBlocking {
    println("\n== W-55：后端 start 失败 → 回落下一档 ==")

    check("首选（proot）起不来 → 落到第二档 user，且 backendKey 如实报 user") {
        val pool = TerminalSessionPool(
            CandidateSource(
                listOf(
                    { FakeShellBackend(TerminalBackend.KEY_PROOT, startOk = false) },
                    { FakeShellBackend(TerminalBackend.KEY_USER) }
                )
            ),
            commandTimeoutMs = 8000
        )
        val opened = pool.open(TerminalIdentity.User, alias = null, preferProot = true)
        assertTrue("open ok (code=${opened.errorCode})", opened.ok)
        assertEquals("pool=user", TerminalBackend.KEY_USER, opened.backendKey)
        val r = pool.run(opened.handle!!, "echo hi")
        assertTrue("回落后的会话可用", r.ok)
    }

    check("三档依次失败两档 → 第三档接手（逐级回落，不是只试一次）") {
        val pool = TerminalSessionPool(
            CandidateSource(
                listOf(
                    { FakeShellBackend(TerminalBackend.KEY_PROOT, startOk = false) },
                    { FakeShellBackend(TerminalBackend.KEY_ADB, startOk = false) },
                    { FakeShellBackend(TerminalBackend.KEY_USER) }
                )
            ),
            commandTimeoutMs = 8000
        )
        val opened = pool.open(TerminalIdentity.User, alias = null, preferProot = true)
        assertTrue("open ok (code=${opened.errorCode})", opened.ok)
        assertEquals("pool=user", TerminalBackend.KEY_USER, opened.backendKey)
    }

    check("全部起不来 → start_failed，消息里列出尝试过的每一档（不笼统说失败）") {
        val pool = TerminalSessionPool(
            CandidateSource(
                listOf(
                    { FakeShellBackend(TerminalBackend.KEY_PROOT, startOk = false) },
                    { FakeShellBackend(TerminalBackend.KEY_ADB, startOk = false) }
                )
            ),
            commandTimeoutMs = 8000
        )
        val opened = pool.open(TerminalIdentity.User, alias = null, preferProot = true)
        assertTrue("open 应失败", !opened.ok)
        assertEquals("code", "start_failed", opened.errorCode)
        assertTrue("message 含 proot", opened.message.contains(TerminalBackend.KEY_PROOT))
        assertTrue("message 含 adb", opened.message.contains(TerminalBackend.KEY_ADB))
    }

    check("空候选 → no_backend（不崩、不静默降级到别处）") {
        val pool = TerminalSessionPool(CandidateSource(emptyList()), commandTimeoutMs = 8000)
        val opened = pool.open(TerminalIdentity.User)
        assertTrue("open 应失败", !opened.ok)
        assertEquals("code", "no_backend", opened.errorCode)
    }
}

/* ================================================================== */
/* 1. 两后端走同一接口（假 proot + 真 /bin/sh）                          */
/* ================================================================== */

private fun testTwoBackendsSameInterface() = runBlocking {
    println("\n== 两后端走同一接口（假 proot + 真 /bin/sh，均经会话池）==")

    val tmp = Files.createTempDirectory("agentkit-t07t08").toFile()
    val source = FakeSource(
        proot = { FakeShellBackend(TerminalBackend.KEY_PROOT) },
        user = { LocalUserBackend(shellPath = "/bin/sh", workingDir = tmp) }
    )
    val pool = TerminalSessionPool(source, commandTimeoutMs = 8000)

    check("proot（假后端）经同一接口执行 pwd") {
        val opened = pool.open(TerminalIdentity.User, alias = null, preferProot = true)
        assertTrue("open ok", opened.ok)
        assertEquals("pool=proot", TerminalBackend.KEY_PROOT, opened.backendKey)
        val r = pool.run(opened.handle!!, "pwd")
        assertTrue("run ok (${r.errorCode})", r.ok)
        assertEquals("fake cwd", "/root", r.output)
    }

    check("本机（真 /bin/sh）经同一接口执行 cd && pwd") {
        val opened = pool.open(TerminalIdentity.User, alias = null, preferProot = false)
        assertTrue("open ok", opened.ok)
        assertEquals("pool=user", TerminalBackend.KEY_USER, opened.backendKey)
        val sub = File(tmp, "sub").apply { mkdirs() }
        val cd = pool.run(opened.handle!!, "cd ${sub.absolutePath}")
        assertTrue("cd ok", cd.ok)
        val r = pool.run(opened.handle!!, "pwd")
        assertTrue("run ok (${r.errorCode})", r.ok)
        assertEquals("real cwd", sub.canonicalPath, File(r.output.trim()).canonicalPath)
    }

    pool.closeAll()
}

/* ================================================================== */
/* 2 + 3. 工厂降级链 + 可用性缓存 + clearCache                          */
/* ================================================================== */

private fun testFactoryDegradeAndCache() = runBlocking {
    println("\n== 工厂 getHighestAvailable 降级链 + 缓存 + clearCache ==")

    check("proot 可用 → getHighestAvailable 返回 proot") {
        val proot = FakeProvider(TerminalIdentity.User, TerminalBackend.KEY_PROOT, true) {
            FakeShellBackend(TerminalBackend.KEY_PROOT)
        }
        val user = FakeProvider(TerminalIdentity.User, TerminalBackend.KEY_USER, true) {
            FakeShellBackend(TerminalBackend.KEY_USER)
        }
        val factory = TerminalBackendFactory(listOf(proot, user))
        assertEquals("highest=proot", TerminalBackend.KEY_PROOT, factory.getHighestAvailable()?.backendKey)
        assertEquals("preferred=user", TerminalBackend.KEY_USER, factory.getUserPreferred()?.backendKey)
    }

    check("proot 不可用 → getHighestAvailable 回落 user") {
        val proot = FakeProvider(TerminalIdentity.User, TerminalBackend.KEY_PROOT, false) {
            FakeShellBackend(TerminalBackend.KEY_PROOT)
        }
        val user = FakeProvider(TerminalIdentity.User, TerminalBackend.KEY_USER, true) {
            FakeShellBackend(TerminalBackend.KEY_USER)
        }
        val factory = TerminalBackendFactory(listOf(proot, user))
        assertEquals("highest=user", TerminalBackend.KEY_USER, factory.getHighestAvailable()?.backendKey)
    }

    check("可用性判定被缓存；clearCache() 后重新探测并生效") {
        val proot = FakeProvider(TerminalIdentity.User, TerminalBackend.KEY_PROOT, true) {
            FakeShellBackend(TerminalBackend.KEY_PROOT)
        }
        val user = FakeProvider(TerminalIdentity.User, TerminalBackend.KEY_USER, true) {
            FakeShellBackend(TerminalBackend.KEY_USER)
        }
        val factory = TerminalBackendFactory(listOf(proot, user))

        assertEquals("first=proot", TerminalBackend.KEY_PROOT, factory.getHighestAvailable()?.backendKey)
        assertEquals("probed once", 1, proot.probeCount)

        // proot 变为不可用，但缓存仍命中 → 仍返回 proot，且未再 probe
        proot.available = false
        assertEquals("cached=proot", TerminalBackend.KEY_PROOT, factory.getHighestAvailable()?.backendKey)
        assertEquals("still cached (no probe)", 1, proot.probeCount)

        // clearCache 后重新探测 → 回落 user
        factory.clearCache()
        assertEquals("after clear=user", TerminalBackend.KEY_USER, factory.getHighestAvailable()?.backendKey)
        assertTrue("probed again", proot.probeCount >= 2)
    }

    check("设备只有 App 身份后端时，更高档的请求取不到后端（不虚报可用）") {
        // R-12：标题改过。原来叫「未实现身份对工厂不可用」——在 uid 2000 成为真实档位之后，
        // `Shizuku` 已经不是「未实现」，只是这台设备（这个假工厂）没有那条通道。
        // 断言本身不变：拿不到就是拿不到，只是原因从「身份不存在」变成了「权限不够」。
        val factory = TerminalBackendFactory(
            listOf(
                FakeProvider(TerminalIdentity.User, TerminalBackend.KEY_USER, true) { FakeShellBackend(TerminalBackend.KEY_USER) }
            )
        )
        for (id in listOf(TerminalIdentity.Su, TerminalIdentity.Shizuku, TerminalIdentity.Ssh)) {
            assertEquals("$id → null", null, factory.obtain(id, true))
        }
    }
}

/* ================================================================== */
/* 4. 句柄格式 + 碰撞处理                                              */
/* ================================================================== */

private fun testHandleFormatAndCollision() = runBlocking {
    println("\n== 句柄恒 4 位十六进制 + 碰撞处理 ==")

    check("默认生成器：句柄恒匹配 ^[0-9a-f]{4}$ 且互不重复") {
        val pool = TerminalSessionPool(userSource())
        val handles = (1..8).map { i -> pool.open(TerminalIdentity.User, "h$i", preferProot = false).handle!! }
        handles.forEach { assertTrue("match $it", it.matches(TERMINAL_HANDLE_REGEX)) }
        assertEquals("unique", handles.size, handles.toSet().size)
        pool.closeAll()
    }

    check("碰撞：随到重复句柄时重试到下一个不同值") {
        val seq = ArrayDeque(listOf("abcd", "abcd", "abce"))
        val pool = TerminalSessionPool(userSource(), handleGenerator = { seq.removeFirst() })
        val h1 = pool.open(TerminalIdentity.User, "s1", preferProot = false).handle!!
        val h2 = pool.open(TerminalIdentity.User, "s2", preferProot = false).handle!!
        assertEquals("h1", "abcd", h1)
        assertEquals("h2 避开碰撞", "abce", h2)
        pool.closeAll()
    }

    check("反复碰撞 → 顺序补位（0x0000 起找首个空闲）") {
        val pool = TerminalSessionPool(userSource(), handleGenerator = { "0000" })
        val h1 = pool.open(TerminalIdentity.User, "f1", preferProot = false).handle!!
        val h2 = pool.open(TerminalIdentity.User, "f2", preferProot = false).handle!!
        assertEquals("h1", "0000", h1)
        assertEquals("h2 顺序补位", "0001", h2)
        pool.closeAll()
    }
}

/* ================================================================== */
/* 5. 身份冲突显式报错                                                 */
/* ================================================================== */

private fun testIdentityConflict() = runBlocking {
    println("\n== 身份固化：后续冲突 identity 显式报错 ==")

    val source = PermissiveSource(
        makeUser = { FakeShellBackend(TerminalBackend.KEY_USER, TerminalIdentity.User) },
        makeSu = { FakeShellBackend(TerminalBackend.KEY_USER, TerminalIdentity.Su) }
    )
    val pool = TerminalSessionPool(source)

    val hUser = pool.open(TerminalIdentity.User, "shared", preferProot = false).handle!!

    check("open 同别名冲突身份 → identity_conflict（不静默复用）") {
        val r = pool.open(TerminalIdentity.Su, "shared", preferProot = false)
        assertTrue("not ok", !r.ok)
        assertEquals("code", "identity_conflict", r.errorCode)
    }

    check("run 声明身份与会话固化身份不符 → identity_conflict") {
        val r = pool.run(hUser, "pwd", identity = TerminalIdentity.Su)
        assertTrue("not ok", !r.ok)
        assertEquals("code", "identity_conflict", r.errorCode)
    }

    check("run 身份一致 → 正常执行") {
        val r = pool.run(hUser, "pwd", identity = TerminalIdentity.User)
        assertTrue("ok (${r.errorCode})", r.ok)
    }

    check("open 同别名同身份 → 复用同一句柄（reused=true）") {
        val r = pool.open(TerminalIdentity.User, "shared", preferProot = false)
        assertTrue("ok", r.ok)
        assertTrue("reused", r.reused)
        assertEquals("same handle", hUser, r.handle)
    }

    pool.closeAll()
}

/* ================================================================== */
/* 6. 并发输出不串                                                      */
/* ================================================================== */

private fun testConcurrentNoInterleave() = runBlocking {
    println("\n== 并发会话输出不串（跨会话并行 / 同会话串行）==")

    check("跨会话并发：各自输出归属正确") {
        val pool = TerminalSessionPool(
            FakeSource(user = { FakeShellBackend(TerminalBackend.KEY_USER, chunkDelayMs = 20) }),
            commandTimeoutMs = 8000
        )
        val hA = pool.open(TerminalIdentity.User, "A", preferProot = false).handle!!
        val hB = pool.open(TerminalIdentity.User, "B", preferProot = false).handle!!
        coroutineScope {
            val a = async { pool.run(hA, "echo AAAAAAAA") }
            val b = async { pool.run(hB, "echo BBBBBBBB") }
            val ra = a.await(); val rb = b.await()
            assertTrue("A ok", ra.ok); assertTrue("B ok", rb.ok)
            assertEquals("A output", "AAAAAAAA", ra.output)
            assertEquals("B output", "BBBBBBBB", rb.output)
        }
        pool.closeAll()
    }

    check("同会话并发：每会话一把锁 → 串行、不交织") {
        val pool = TerminalSessionPool(
            FakeSource(user = { FakeShellBackend(TerminalBackend.KEY_USER, chunkDelayMs = 20) }),
            commandTimeoutMs = 8000
        )
        val h = pool.open(TerminalIdentity.User, "S", preferProot = false).handle!!
        coroutineScope {
            val r1 = async { pool.run(h, "echo AAAAAAAA") }
            val r2 = async { pool.run(h, "echo BBBBBBBB") }
            val o1 = r1.await(); val o2 = r2.await()
            assertTrue("r1 ok", o1.ok); assertTrue("r2 ok", o2.ok)
            // 两条命令各自完整、互不含对方字符（若未串行会交织/串读）
            assertTrue("r1 clean A", o1.output == "AAAAAAAA")
            assertTrue("r2 clean B", o2.output == "BBBBBBBB")
        }
        pool.closeAll()
    }
}

/* ================================================================== */
/* 7. 多轮 shell 保持 cwd / env                                        */
/* ================================================================== */

private fun testMultiTurnState() = runBlocking {
    println("\n== 多轮 shell 保持 cwd / env ==")

    check("假后端：同一会话多轮保住 cwd 与 export 的环境变量") {
        val pool = TerminalSessionPool(userSource(), commandTimeoutMs = 8000)
        val h = pool.open(TerminalIdentity.User, "state", preferProot = false).handle!!
        assertEquals("initial pwd", "/root", pool.run(h, "pwd").output)
        pool.run(h, "cd /tmp")
        assertEquals("after cd", "/tmp", pool.run(h, "pwd").output)
        pool.run(h, "export GREETING=hello")
        assertEquals("env persisted", "hello", pool.run(h, "echo \$GREETING").output)
        pool.closeAll()
    }

    check("真 /bin/sh：同一会话多轮保住 cwd") {
        val tmp = Files.createTempDirectory("agentkit-t07t08-sh").toFile()
        val sub = File(tmp, "deep").apply { mkdirs() }
        val pool = TerminalSessionPool(
            FakeSource(user = { LocalUserBackend("/bin/sh", tmp) }),
            commandTimeoutMs = 8000
        )
        val h = pool.open(TerminalIdentity.User, "sh", preferProot = false).handle!!
        assertTrue("cd ok", pool.run(h, "cd ${sub.absolutePath}").ok)
        val pwd = pool.run(h, "pwd")
        assertTrue("pwd ok", pwd.ok)
        assertEquals("cwd persisted", sub.canonicalPath, File(pwd.output.trim()).canonicalPath)
        pool.closeAll()
    }
}

/* ================================================================== */
/* 8. 僵尸会话释放别名并可重开                                          */
/* ================================================================== */

private fun testZombieRelease() = runBlocking {
    println("\n== 僵尸会话自动释放别名并可重开 ==")

    check("会话后端死亡 → run 报 session_dead 且释放句柄；同别名可重开新会话") {
        var last: FakeShellBackend? = null
        val source = FakeSource(user = { FakeShellBackend(TerminalBackend.KEY_USER).also { last = it } })
        val pool = TerminalSessionPool(source, commandTimeoutMs = 8000)

        val h1 = pool.open(TerminalIdentity.User, "z", preferProot = false).handle!!
        last!!.forceDead = true

        val dead = pool.run(h1, "pwd")
        assertTrue("not ok", !dead.ok)
        assertEquals("code", "session_dead", dead.errorCode)
        assertEquals("handle released", null, pool.lookup(h1))

        val reopened = pool.open(TerminalIdentity.User, "z", preferProot = false)
        assertTrue("reopen ok", reopened.ok)
        assertTrue("new handle", reopened.handle != h1)
        pool.closeAll()
    }

    check("open 命中僵尸别名 → 回收后新建（不返回僵尸句柄）") {
        var last: FakeShellBackend? = null
        val source = FakeSource(user = { FakeShellBackend(TerminalBackend.KEY_USER).also { last = it } })
        val pool = TerminalSessionPool(source)

        val h1 = pool.open(TerminalIdentity.User, "z2", preferProot = false).handle!!
        last!!.forceDead = true
        val h2 = pool.open(TerminalIdentity.User, "z2", preferProot = false).handle!!
        assertTrue("new handle", h2 != h1)
        assertEquals("old gone", null, pool.lookup(h1))
        assertTrue("new alive", pool.lookup(h2)?.alive == true)
        pool.closeAll()
    }
}

/* ================================================================== */
/* 9. ToolOutputTruncator 边界                                          */
/* ================================================================== */

/** UTF-8 往返稳定 ⇒ 无孤立代理（半截多字节码点）。 */
private fun utf8Stable(s: String): Boolean =
    String(s.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == s

private fun testTruncatorBoundaries() = runBlocking {
    println("\n== ToolOutputTruncator 边界 ==")

    check("恰好等于上限 → 不截断，原文返回") {
        val t = ToolOutputTruncator(50)
        val text = "x".repeat(50)
        val r = t.truncate(text)
        assertTrue("not truncated", !r.truncated)
        assertEquals("original", text, r.text)
    }

    check("超限 → 截断且长度恒 ≤ 上限，含头部与尾部") {
        val t = ToolOutputTruncator(200)
        val text = "HEAD" + "m".repeat(1000) + "TAIL"
        val r = t.truncate(text)
        assertTrue("truncated", r.truncated)
        assertTrue("len ≤ max (${r.text.length})", r.text.length <= 200)
        assertContains("keeps head", r.text, "HEAD")
        assertContains("keeps tail", r.text, "TAIL")
        assertEquals("original length", text.length, r.originalLength)
    }

    check("多字节（BMP 中文）恰好截断：长度 ≤ 上限且不出现半截码点") {
        val t = ToolOutputTruncator(120)
        val text = "中".repeat(500)
        val r = t.truncate(text)
        assertTrue("truncated", r.truncated)
        assertTrue("len ≤ max", r.text.length <= 120)
        assertTrue("utf8 stable", utf8Stable(r.text))
    }

    check("表情（代理对）在多种上限下均不被切断") {
        val base = "😀".repeat(300)
        for (max in 20..140) {
            val r = ToolOutputTruncator(max).truncate(base)
            assertTrue("len ≤ $max (got ${r.text.length})", r.text.length <= max)
            assertTrue("surrogate-safe at max=$max", utf8Stable(r.text))
        }
    }
}

/* ================================================================== */
/* 10. shell 工具参数校验 + app_stop runner 桥                          */
/* ================================================================== */

private fun testShellToolAndBridge() = runBlocking {
    println("\n== shell 工具参数校验 + app_stop shell runner 桥 ==")

    val pool = TerminalSessionPool(userSource(), commandTimeoutMs = 8000)
    val run = ShellRunTool(pool)
    val close = ShellCloseTool(pool)

    check("无 session → 自动开辟并执行") {
        val r = run.execute(json("command" to "pwd"))
        assertTrue("ok (${r.code}: ${r.message})", r.ok)
        assertEquals("handle count", 1, pool.list().size)
    }

    check("带 session 句柄 → 复用会话执行") {
        val h = pool.list().first().handle
        val r = run.execute(json("command" to "pwd", "session" to h))
        assertTrue("ok", r.ok)
        assertContains("message has handle", r.message, h)
    }

    check("非法句柄格式 → bad_handle") {
        val r = run.execute(json("command" to "pwd", "session" to "ZZZZ"))
        assertTrue("not ok", !r.ok)
        assertEquals("code", "bad_handle", r.code)
    }

    check("不存在的合法句柄 → 自动开新会话并说明（W-63）") {
        // 旧行为：直接 no_such_session 失败。真机 15:16——App 重启后模型沿用历史对话里的
        // 「b69f」，整轮白跑，用户只看到一张红色失败卡。
        // 新行为：「句柄没了」等价于「没有会话」，把命令跑起来，但必须在返回里说清
        // 原句柄已失效、cwd 已重置（静默替换会让模型以为还在老目录，更难查）。
        val r = run.execute(json("command" to "pwd", "session" to "ffff"))
        assertTrue("应成功执行（不再直接失败）", r.ok)
        assertContains("应点明原句柄失效", r.message, "ffff")
        assertContains("应说明已换新会话", r.message, "已开辟新会话")
    }

    check("缺 command / 空 command → 明确失败") {
        assertEquals("missing", "missing_param", run.execute(JsonObject(emptyMap())).code)
        assertEquals("empty", "empty_command", run.execute(json("command" to "  ")).code)
    }

    check("非法 identity → bad_identity") {
        val r = run.execute(json("command" to "pwd", "identity" to "bogus"))
        assertTrue("not ok", !r.ok)
        assertEquals("code", "bad_identity", r.code)
    }

    check("shell_close 关闭会话；重复关闭 → no_such_session") {
        val h = pool.list().first().handle
        assertTrue("close ok", close.execute(json("session" to h)).ok)
        assertEquals("gone", null, pool.lookup(h))
        assertEquals("re-close", "no_such_session", close.execute(json("session" to h)).code)
    }

    pool.closeAll()

    check("app_stop runner 桥：经会话池执行命令并回传退出码") {
        val p = TerminalSessionPool(userSource(), commandTimeoutMs = 8000)
        val runner = terminalShellCommandRunner(p)
        val outcome = runner.exec("echo bridge-ok")
        assertTrue("not null", outcome != null)
        assertEquals("exit 0", 0, outcome!!.exitCode)
        assertContains("output", outcome.output, "bridge-ok")
        p.closeAll()
    }

    check("app_stop runner 桥：无可用后端时返回 null（诚实降级）") {
        val p = TerminalSessionPool(FakeSource(user = { null }))
        val runner = terminalShellCommandRunner(p)
        assertEquals("null", null, runner.exec("am force-stop x"))
        p.closeAll()
    }
}

/* ================================================================== */
/* 11. 资源生命周期：非挂起 releaseAllAsync 收口（QA Major 修复验收）      */
/* ================================================================== */

private fun testReleaseLifecycle() = runBlocking {
    println("\n== 资源生命周期：releaseAllAsync 非挂起 best-effort 释放 ==")

    check("releaseAllAsync：同步清空会话表（句柄立即失效）+ 异步关闭后端进程") {
        val created = mutableListOf<FakeShellBackend>()
        val source = FakeSource(user = {
            FakeShellBackend(TerminalBackend.KEY_USER).also { created += it }
        })
        val pool = TerminalSessionPool(source, commandTimeoutMs = 8000)
        val h1 = pool.open(TerminalIdentity.User, "r1", preferProot = false).handle!!
        val h2 = pool.open(TerminalIdentity.User, "r2", preferProot = false).handle!!
        assertEquals("2 sessions before release", 2, pool.list().size)

        pool.releaseAllAsync()

        // 同步部分（onCleared 返回后即可见）：旧句柄立即不可达
        assertEquals("list empty after release", 0, pool.list().size)
        assertEquals("lookup h1 → null", null, pool.lookup(h1))
        assertEquals("lookup h2 → null", null, pool.lookup(h2))
        assertEquals("run → no_such_session", "no_such_session", pool.run(h1, "pwd").errorCode)

        // 异步部分（best-effort）：后端进程最终被关闭
        val deadline = System.currentTimeMillis() + 2000
        while (created.any { it.isAlive } && System.currentTimeMillis() < deadline) delay(20)
        assertEquals("all backends closed", 0, created.count { it.isAlive })
    }

    check("releaseAllAsync：空池调用安全（不抛）") {
        val pool = TerminalSessionPool(FakeSource(user = { null }))
        pool.releaseAllAsync() // 不得抛
        assertEquals("still empty", 0, pool.list().size)
    }

    check("releaseAllAsync 幂等：重复调用不抛且保持空") {
        val pool = TerminalSessionPool(FakeSource(user = { FakeShellBackend(TerminalBackend.KEY_USER) }))
        pool.open(TerminalIdentity.User, "x", preferProot = false)
        pool.releaseAllAsync()
        pool.releaseAllAsync() // 幂等
        assertEquals("empty", 0, pool.list().size)
    }
}

/* ================================================================== */
/* A-10 / A-2：会话池的两处并发与超时语义（2026-11-12 加固）             */
/* ================================================================== */

/**
 * A-10：同别名并发 open 必须只留一条会话。
 *
 * 修前「查 → 启 → 登记」三段各自出锁：两个协程同时读到「该槽位为空」，各起一个后端，
 * 先后登记——后登记的覆盖 `byAlias`，前一个就此失去句柄，成为**没人能关掉的孤儿 shell**
 * （进程泄漏，输出收集协程还在跑）。模型一批里并发调两个 `shell_run` 就会触发。
 *
 * 判据用 `pool.list().size`：修前是 2（两条都在 byHandle 里，只有一条在 byAlias 里），
 * 修后必须是 1。只看「句柄是否相同」不够——修后也可能恰好都复用同一条，区分度低。
 */
private fun testConcurrentOpenSameAlias() = runBlocking {
    check("A-10 同别名并发 open 只留一条会话（不再开孤儿 shell）") {
        val source = object : TerminalBackendSource {
            override fun obtain(identity: TerminalIdentity, preferProot: Boolean): TerminalBackend? =
                if (identity == TerminalIdentity.User) {
                    // startDelayMs：让 start 真的挂起 60ms，制造「查-启-写」重叠窗口。
                    // 不加这个延时，两个协程往往严格串行跑完，测试会假绿。
                    FakeShellBackend(TerminalBackend.KEY_USER, startDelayMs = 60)
                } else {
                    null
                }
        }
        val pool = TerminalSessionPool(source)
        val (a, b) = coroutineScope {
            val ja = async { pool.open(TerminalIdentity.User) }
            val jb = async { pool.open(TerminalIdentity.User) }
            ja.await() to jb.await()
        }
        assertTrue("两次 open 都应成功（a=${a.errorCode} b=${b.errorCode}）", a.ok && b.ok)
        assertEquals("应复用同一句柄", a.handle, b.handle)
        assertEquals("会话表只应有一条（修前会多出孤儿 shell）", 1, pool.list().size)
        pool.closeAll()
    }
}

/**
 * A-2：池层超时必须能由调用方覆盖。
 *
 * 修前 `run()` 没有超时参数，一律用池常量 30 秒；`shell_run` 声明了 20 分钟预算，
 * 但那个值只到执行器、再没往下传——`gradle build` 第 30 秒必被判超时、会话标脏。
 *
 * 这里把池默认设成 10 秒、调用时传 200ms：如果覆盖没生效，耗时会接近 10 秒，
 * 断言直接失败（这也是为什么阈值取 2 秒而不是「接近 200ms」——留出 CI 抖动余量）。
 */
private fun testCommandTimeoutOverride() = runBlocking {
    check("A-2 单条命令超时可被调用方覆盖（20 分钟预算才有意义）") {
        val source = object : TerminalBackendSource {
            override fun obtain(identity: TerminalIdentity, preferProot: Boolean): TerminalBackend? =
                if (identity == TerminalIdentity.User) {
                    // silent：收下命令但永不回哨兵 → 只能靠超时收场
                    FakeShellBackend(TerminalBackend.KEY_USER, silent = true)
                } else {
                    null
                }
        }
        val pool = TerminalSessionPool(source, commandTimeoutMs = 10_000L)
        val handle = pool.open(TerminalIdentity.User).handle ?: error("open 应成功")
        val t0 = System.currentTimeMillis()
        val result = pool.run(handle, "echo hi", timeoutMs = 200L)
        val cost = System.currentTimeMillis() - t0
        assertEquals("应按 timeout 失败", "timeout", result.errorCode)
        assertTrue("必须按传入的 200ms 收手（池默认 10s），实际 ${cost}ms", cost < 2_000L)
        pool.closeAll()
    }
}

/* ================================================================== */

fun main() {
    println("=== Phase1 · T07/T08 终端后端抽象 + 会话池 QA 行为验证 ===")
    testBackendStartFallback()
    testTwoBackendsSameInterface()
    testFactoryDegradeAndCache()
    testHandleFormatAndCollision()
    testIdentityConflict()
    testConcurrentNoInterleave()
    testMultiTurnState()
    testZombieRelease()
    testTruncatorBoundaries()
    testShellToolAndBridge()
    testReleaseLifecycle()
    // 2026-11-12 加固：并发安全（A-10）与超时覆盖（A-2）
    testConcurrentOpenSameAlias()
    testCommandTimeoutOverride()

    println("\n=== 汇总: $passed passed, ${failures.size} failed ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
