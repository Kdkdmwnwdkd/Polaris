/**
 * R-12 验证：终端「身份」维度。
 *
 * ## 背景：修前这个维度只有名字
 *
 * `TerminalBackend.identity` 在**七处实现里全是 `User`**——包括以 uid 2000 跑的
 * Shizuku 后端与无线调试后端。它们不是填错了，是当时枚举里没有可填的档：
 * `Shizuku` 被理解成「通道名」，只在 schema 与文案里出现。
 *
 * 于是一件事有三个说法，只有第一个是真的：
 *  - 请求：`prefer_proot=false`（要 uid 2000）
 *  - 实际：uid 2000
 *  - 报告：「身份 user」
 *
 * 下游据此做判断，得出的是错误结论：权限不足时按 user 档劝模型「换条通道试试」，
 * 而它已经在最高档上了；`providersFor` 用 `it.identity == identity` 过滤，
 * 因为所有 provider 都是 User，这个过滤对 User 请求恒真，对非 User 请求靠前面
 * 一句 `return emptyList()` 兜住——**真正起作用的从来是特例分支**。
 *
 * ## 修法（语义）
 *
 * `identity` = **权限档**，带 `uid`；请求值是**下限**，后端声明值是**实际**。
 * 一个谓词贯穿三处：`TerminalIdentity.satisfies(required)`。
 *
 * 由此得到两个此前做不到的性质：
 *  - 「请求 user、实际 uid 2000」是**合法且自洽**的状态（权限更高满足下限），不再是矛盾；
 *  - 「请求 su、会话是 user」会**被拒**（下限不满足），而不是靠字符串相等碰巧拒绝。
 *
 * ## 本文件测什么
 *
 * A 档位语义（纯函数）· B 工厂候选 · C 真取后端 · D 会话池真跑 · E 源码守卫。
 * D 段用的是**真** `TerminalSessionPool`（不是假池）：假 provider 只负责造进程，
 * 哨兵、串行、复用、冲突判定全走真代码——这正是「身份语义有没有真的生效」的判据。
 */
import com.beichen.polaris.core.terminal.TerminalSessionPool
import com.beichen.polaris.core.terminal.backend.TerminalBackend
import com.beichen.polaris.core.terminal.backend.TerminalBackendFactory
import com.beichen.polaris.core.terminal.backend.TerminalBackendProvider
import com.beichen.polaris.core.terminal.backend.TerminalBackendSource
import com.beichen.polaris.core.terminal.backend.TerminalIdentity
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

private var p = 0
private var f = 0

private fun ok(name: String) {
    p++
    println("  PASS  $name")
}

private fun no(name: String, why: String) {
    f++
    println("  FAIL  $name -> $why")
}

/**
 * 单条断言。
 *
 * 抓 `Throwable` 而不是只抓 `AssertionError`：被测代码抛出的运行时异常同样是一条失败，
 * 放它逃出去会打断整个 harness——后面的用例一条不跑、结果行都不打印，
 * runner 拿不到「N 通过 / M 失败」就会误判（R-19 的 N1 变异暴露过这个坑）。
 */
private fun check(name: String, body: () -> Unit) {
    try {
        body()
        ok(name)
    } catch (t: Throwable) {
        no(name, "${t::class.java.simpleName}: ${t.message}")
    }
}

/**
 * 同一套断言的挂起版：会话池的 `open` / `run` 是 suspend，普通 lambda 装不下。
 *
 * 每条用例各自 `runBlocking`（而不是整段包一层）是刻意的——一条用例卡住不会连带
 * 让后面的用例一条都不跑；失败信息也仍然挂在它自己那行上。
 */
private fun checkBlocking(name: String, body: suspend () -> Unit) {
    try {
        runBlocking { body() }
        ok(name)
    } catch (t: Throwable) {
        no(name, "${t::class.java.simpleName}: ${t.message}")
    }
}

private fun expect(cond: Boolean, msg: () -> String) {
    if (!cond) throw AssertionError(msg())
}

private fun <T> eq(actual: T, wanted: T, what: String) {
    if (actual != wanted) throw AssertionError("$what：期望 <$wanted>，实际 <$actual>")
}

/* ------------------------------ 假后端 / 假 provider ------------------------------ */

/**
 * 假后端：**回放哨兵**，让真会话池能判定命令完成。
 *
 * 池会先写命令、再写 `echo "<marker><token>__$?"`。这里从 echo 行里剥出前缀，
 * 然后把「命令输出 + 前缀 + 退出码」推回 output 流——与真实 shell 的回显结构一致。
 */
private class FakeBackend(
    override val backendKey: String,
    override val identity: TerminalIdentity,
    private val onSend: (String) -> Unit = {}
) : TerminalBackend {
    private val bus = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val output: Flow<String> = bus
    override val isAlive: Boolean get() = true
    val sent = ArrayList<String>()

    override suspend fun start(): Boolean = true

    override suspend fun send(input: String): Boolean {
        sent += input.trimEnd('\n')
        onSend(input)
        // echo "<marker><token>__$?" → 取引号内去掉结尾 $? 的部分作为哨兵前缀
        val m = Regex("echo \"(.*?)\\$\\?\"").find(input)
        if (m != null) {
            val prefix = m.groupValues[1]
            bus.emit("echo-line\n")
            bus.emit(prefix + "0\n")
        }
        return true
    }

    override suspend fun close() {}
    override suspend fun awaitExit(): Int = 0
}

private class FakeProvider(
    override val identity: TerminalIdentity,
    override val backendKey: String,
    private val available: Boolean = true
) : TerminalBackendProvider {
    val created = AtomicInteger(0)
    override fun probe(): Boolean = available
    override fun create(): TerminalBackend? =
        if (available) FakeBackend(backendKey, identity).also { created.incrementAndGet() } else null
}

/**
 * 一套「设备形态」：哪些通道装着。
 *
 * 注意 provider 声明的 identity 与真实实现一一对应——这正是修 R-12 要保证的事，
 * 所以这里不能像旧 harness 那样一律写 User（那会让被测语义失去意义）。
 */
private fun factory(
    proot: Boolean = false,
    shizuku: Boolean = false,
    adb: Boolean = false,
    user: Boolean = true
): TerminalBackendFactory = TerminalBackendFactory(
    listOf(
        FakeProvider(TerminalIdentity.User, TerminalBackend.KEY_PROOT, proot),
        FakeProvider(TerminalIdentity.Shizuku, TerminalBackend.KEY_SHIZUKU, shizuku),
        FakeProvider(TerminalIdentity.Shizuku, TerminalBackend.KEY_ADB, adb),
        FakeProvider(TerminalIdentity.User, TerminalBackend.KEY_USER, user)
    )
)

private fun keys(f: TerminalBackendFactory, id: TerminalIdentity, proot: Boolean) =
    f.providersFor(id, proot).map { it.backendKey }

/* ================================================================== */
/* A. 档位语义                                                          */
/* ================================================================== */

private fun testIdentitySemantics() {
    println("\n== A 档位语义：uid / satisfies / 反查 ==")

    check("A1 uid：User 无固定 uid，Shizuku=2000，Su=0，Ssh 无本地 uid") {
        eq(TerminalIdentity.User.uid, null, "User.uid")
        eq(TerminalIdentity.Shizuku.uid, 2000, "Shizuku.uid")
        eq(TerminalIdentity.Su.uid, 0, "Su.uid")
        eq(TerminalIdentity.Ssh.uid, null, "Ssh.uid")
    }

    check("A2 uid 2000 满足 user 下限（权限更高本来就够用）") {
        expect(TerminalIdentity.Shizuku.satisfies(TerminalIdentity.User)) {
            "实现里 uid 2000 的会话被当成「不满足 user 请求」——那会逼用户关掉会话重开"
        }
    }

    check("A3 App 身份不满足 uid 2000 下限（这才是冲突该拦的方向）") {
        expect(!TerminalIdentity.User.satisfies(TerminalIdentity.Shizuku)) {
            "user 会话被当成能满足 shizuku 请求——用户会以为提权了，命令其实仍以旧身份跑"
        }
    }

    check("A4 uid 0 满足本地所有档（含 uid 2000 与 user）") {
        expect(TerminalIdentity.Su.satisfies(TerminalIdentity.User)) { "su 不满足 user" }
        expect(TerminalIdentity.Su.satisfies(TerminalIdentity.Shizuku)) { "su 不满足 shizuku" }
        expect(TerminalIdentity.Su.satisfies(TerminalIdentity.Su)) { "su 不满足 su" }
    }

    check("A5 每个档都满足自己（自反，satisfies 的基本性质）") {
        TerminalIdentity.entries.forEach { id ->
            expect(id.satisfies(id)) { "${id.wire} 不满足自己" }
        }
    }

    check("A6 Ssh 只满足 Ssh：远端 root 不是本机 root") {
        expect(TerminalIdentity.Ssh.satisfies(TerminalIdentity.Ssh)) { "ssh 不满足 ssh" }
        expect(!TerminalIdentity.Ssh.satisfies(TerminalIdentity.Su)) {
            "ssh 被当成能满足本机 su——跨机器的权限不可替代"
        }
        expect(!TerminalIdentity.Shizuku.satisfies(TerminalIdentity.Ssh)) {
            "本机 uid 2000 被当成能满足远端 ssh"
        }
    }

    check("A7 satisfies 全表 16 格：本地三档单调，Ssh 独立") {
        // 期望表（行 = 本方，列 = 请求下限）
        val table = mapOf(
            (TerminalIdentity.User to TerminalIdentity.User) to true,
            (TerminalIdentity.User to TerminalIdentity.Shizuku) to false,
            (TerminalIdentity.User to TerminalIdentity.Su) to false,
            (TerminalIdentity.User to TerminalIdentity.Ssh) to false,
            (TerminalIdentity.Shizuku to TerminalIdentity.User) to true,
            (TerminalIdentity.Shizuku to TerminalIdentity.Shizuku) to true,
            (TerminalIdentity.Shizuku to TerminalIdentity.Su) to false,
            (TerminalIdentity.Shizuku to TerminalIdentity.Ssh) to false,
            (TerminalIdentity.Su to TerminalIdentity.User) to true,
            (TerminalIdentity.Su to TerminalIdentity.Shizuku) to true,
            (TerminalIdentity.Su to TerminalIdentity.Su) to true,
            (TerminalIdentity.Su to TerminalIdentity.Ssh) to false,
            (TerminalIdentity.Ssh to TerminalIdentity.User) to false,
            (TerminalIdentity.Ssh to TerminalIdentity.Shizuku) to false,
            (TerminalIdentity.Ssh to TerminalIdentity.Su) to false,
            (TerminalIdentity.Ssh to TerminalIdentity.Ssh) to true
        )
        eq(table.size, 16, "真值表格数")
        table.forEach { (k, wanted) ->
            eq(k.first.satisfies(k.second), wanted, "${k.first.wire} satisfies ${k.second.wire}")
        }
    }

    check("A8 describe() 说的是身份，不是参数名（不回退成 wire）") {
        // 修前回执直接把 wire 写出去，于是 uid 2000 的会话显示成「身份 user」。
        // describe() 必须真的区分档位，而不是把枚举名小写一遍。
        eq(TerminalIdentity.User.describe(), "App 身份", "User")
        eq(TerminalIdentity.Shizuku.describe(), "uid 2000", "Shizuku")
        eq(TerminalIdentity.Su.describe(), "uid 0（root）", "Su")
        TerminalIdentity.entries.forEach { id ->
            expect(id.describe() != id.wire) { "${id.wire} 的 describe() 与 wire 相同——又变成参数名了" }
        }
    }

    check("A9 schema 取值不因新增 uid 语义而膨胀（R-20 契约不回归）") {
        eq(TerminalIdentity.schemaValues(), listOf("user", "shizuku"), "schemaValues()")
    }
}

/* ================================================================== */
/* B. 工厂候选                                                          */
/* ================================================================== */

private fun testFactoryCandidates() {
    println("\n== B 工厂候选：请求是下限，按档筛而不是按名比 ==")

    check("B1 请求 user：四条候选全在（proot → shizuku → adb → user）") {
        eq(
            keys(factory(proot = true, shizuku = true, adb = true), TerminalIdentity.User, true),
            listOf(
                TerminalBackend.KEY_PROOT,
                TerminalBackend.KEY_SHIZUKU,
                TerminalBackend.KEY_ADB,
                TerminalBackend.KEY_USER
            ),
            "顺序"
        )
    }

    check("B2 请求 shizuku（uid 2000）：只剩两条 uid 2000 通道，容器与本机 shell 被筛掉") {
        eq(
            keys(factory(proot = true, shizuku = true, adb = true), TerminalIdentity.Shizuku, true),
            listOf(TerminalBackend.KEY_SHIZUKU, TerminalBackend.KEY_ADB),
            "候选"
        )
    }

    check("B3 请求 su：空列表——且这是**推导**出来的，不是硬编码特例") {
        val f = factory(proot = true, shizuku = true, adb = true, user = true)
        eq(keys(f, TerminalIdentity.Su, true), emptyList<String>(), "su 候选")
        eq(keys(f, TerminalIdentity.Su, false), emptyList<String>(), "su 候选（no-proot）")
    }

    check("B4 请求 ssh：空列表（本机没有远端身份的后端）") {
        eq(keys(factory(proot = true, shizuku = true, adb = true), TerminalIdentity.Ssh, true),
            emptyList<String>(), "ssh 候选")
    }

    check("B5 prefer_proot=false：跳过容器，但 uid 2000 仍在（W-57 语义不回归）") {
        eq(
            keys(factory(proot = true, shizuku = true, adb = true), TerminalIdentity.User, false),
            listOf(TerminalBackend.KEY_SHIZUKU, TerminalBackend.KEY_ADB, TerminalBackend.KEY_USER),
            "no-proot 顺序"
        )
    }

    check("B6 请求 shizuku + no-proot：仍是两条，且不含容器") {
        eq(
            keys(factory(proot = true, shizuku = true, adb = true), TerminalIdentity.Shizuku, false),
            listOf(TerminalBackend.KEY_SHIZUKU, TerminalBackend.KEY_ADB),
            "候选"
        )
    }

    check("B8 顺序表之外的后端也能被选到（将来接入 su 不用改工厂）") {
        // 这一条是 M9 变异逼出来的：D4 走的是自定义 source，绕过了工厂，
        // 所以「工厂只会遍历写死的四个 key」这个缺陷它看不见——只有直接问工厂才看得见。
        // 修前 providersFor 遍历的 `order` 是四个字面量 key，任何新后端都匹配不到，
        // 静默失效且没有任何地方会响；而 KDoc 里还写着「本类一行不用改」（那是假的）。
        val f = TerminalBackendFactory(
            listOf(
                FakeProvider(TerminalIdentity.User, TerminalBackend.KEY_USER, true),
                FakeProvider(TerminalIdentity.Su, TerminalBackend.KEY_SU, true)
            )
        )
        eq(keys(f, TerminalIdentity.Su, true), listOf(TerminalBackend.KEY_SU), "su 候选")
        eq(f.obtain(TerminalIdentity.Su, preferProot = true)?.backendKey,
            TerminalBackend.KEY_SU, "obtain(su)")
    }

    check("B7 设备没装 uid 2000 通道时，obtain(shizuku) 拿不到后端（不虚报可用）") {
        // 注意：providersFor 返回的是**候选提供者**（编译期固定注册），没装的通道也在列表里，
        // 只是 probe() 为 false。所以这条必须看 obtain——它才做可用性求值。
        // 修前这里靠 `return emptyList()` 让 providersFor 直接空，看似「更早拦住」，
        // 实际是把「设备没装」和「身份不存在」两件事混成一句话。
        val f = factory(proot = true, user = true)
        eq(f.obtain(TerminalIdentity.Shizuku, preferProot = true), null, "obtain(shizuku)")
    }
}

/* ================================================================== */
/* C. 真取后端                                                          */
/* ================================================================== */

private fun testObtain() {
    println("\n== C 真取后端：拿到的是实际档，不是请求值 ==")

    check("C1 obtain(user) 在有 uid 2000 通道时，返回的后端 identity 是 Shizuku") {
        val f = factory(shizuku = true, user = true)
        val b = f.obtain(TerminalIdentity.User, preferProot = true)
        eq(b?.backendKey, TerminalBackend.KEY_SHIZUKU, "选中通道")
        eq(b?.identity, TerminalIdentity.Shizuku, "实际身份")
    }

    check("C2 obtain(shizuku) 绝不返回容器或本机 shell") {
        val f = factory(proot = true, shizuku = true, adb = true, user = true)
        val b = f.obtain(TerminalIdentity.Shizuku, preferProot = true)
        expect(b != null) { "有 uid 2000 通道却取不到后端" }
        expect(b!!.identity.satisfies(TerminalIdentity.Shizuku)) {
            "取到的后端是 ${b.backendKey}（${b.identity.wire}），满足不了 uid 2000"
        }
    }

    check("C3 obtain(su) 返回 null（无候选，不靠特例）") {
        eq(factory(proot = true, shizuku = true, adb = true).obtain(TerminalIdentity.Su, true), null, "su")
    }

    check("C4 只有本机 shell 时 obtain(user) 如实返回 User（不虚报成更高档）") {
        val b = factory(user = true).obtain(TerminalIdentity.User, preferProot = true)
        eq(b?.backendKey, TerminalBackend.KEY_USER, "通道")
        eq(b?.identity, TerminalIdentity.User, "身份")
    }

    check("C5 候选列表里的每一条都满足下限（obtainCandidates 与 obtain 同一判据）") {
        val f = factory(proot = true, shizuku = true, adb = true, user = true)
        f.obtainCandidates(TerminalIdentity.User, preferProot = true).forEach {
            expect(it.identity.satisfies(TerminalIdentity.User)) { "${it.backendKey} 不满足 user" }
        }
        f.obtainCandidates(TerminalIdentity.Shizuku, preferProot = true).forEach {
            expect(it.identity.satisfies(TerminalIdentity.Shizuku)) { "${it.backendKey} 不满足 shizuku" }
        }
    }
}

/* ================================================================== */
/* D. 会话池真跑                                                        */
/* ================================================================== */

private fun testSessionPool() {
    println("\n== D 会话池：固化实际身份 / 复用按档 / 冲突按下限 ==")

    checkBlocking("D1 open(user) 落到 uid 2000 通道时，OpenResult.identity 报的是 Shizuku") {
        val pool = TerminalSessionPool(factory(shizuku = true, user = true))
        val r = pool.open(TerminalIdentity.User, "a", preferProot = true)
        expect(r.ok) { "开辟失败：${r.errorCode} ${r.message}" }
        eq(r.identity, TerminalIdentity.Shizuku, "固化身份")
        eq(r.backendKey, TerminalBackend.KEY_SHIZUKU, "通道")
    }

    checkBlocking("D2 同别名再 open(user)：复用之，不因实际档更高而报冲突") {
        val pool = TerminalSessionPool(factory(shizuku = true, user = true))
        val h = pool.open(TerminalIdentity.User, "a", preferProot = true).handle
        val again = pool.open(TerminalIdentity.User, "a", preferProot = true)
        expect(again.ok) { "被拒：${again.errorCode} ${again.message}" }
        expect(again.reused) { "没有复用（说明判据把「权限更高」当成了冲突）" }
        eq(again.handle, h, "句柄")
    }

    checkBlocking("D3 同别名 open(shizuku)：同一档，复用") {
        val pool = TerminalSessionPool(factory(shizuku = true, user = true))
        val h = pool.open(TerminalIdentity.User, "a", preferProot = true).handle
        val r = pool.open(TerminalIdentity.Shizuku, "a", preferProot = true)
        expect(r.ok) { "被拒：${r.errorCode} ${r.message}" }
        eq(r.handle, h, "句柄")
        eq(r.identity, TerminalIdentity.Shizuku, "固化身份")
    }

    checkBlocking("D4 同一池同一别名上，已有会话达不到请求下限 → identity_conflict") {
        // 为什么要自定义 source：走真工厂时这个分支几乎不可达——providersFor 已经按档筛过，
        // 同一个 backendKey 的 provider 身份是固定的，不会「同 key 不同档」。
        // 而这一分支存在的意义正是**当 source 自己不讲规矩时兜底**（自定义 source、
        // 或将来某个 provider 把 identity 声明错）。用 T07 那套手法造出该场景：
        // 同一个 KEY_USER 池，先给 User 后端，请求 Su 时给 Su 后端——同池同别名 → 冲突。
        val pool = TerminalSessionPool(object : TerminalBackendSource {
            override fun obtain(identity: TerminalIdentity, preferProot: Boolean): TerminalBackend? = null
            override fun obtainCandidates(
                identity: TerminalIdentity,
                preferProot: Boolean
            ): List<TerminalBackend> = when (identity) {
                TerminalIdentity.User ->
                    listOf(FakeBackend(TerminalBackend.KEY_USER, TerminalIdentity.User))
                TerminalIdentity.Su ->
                    listOf(FakeBackend(TerminalBackend.KEY_USER, TerminalIdentity.Su))
                else -> emptyList()
            }
        })
        val opened = pool.open(TerminalIdentity.User, "shared", preferProot = false)
        expect(opened.ok) { "前置：user 会话没开起来（${opened.errorCode}）" }
        val r = pool.open(TerminalIdentity.Su, "shared", preferProot = false)
        expect(!r.ok) { "su 请求被复用了 user 会话——用户会以为拿到了 uid 0，命令其实仍以旧身份跑" }
        eq(r.errorCode, "identity_conflict", "错误码")
    }

    checkBlocking("D5 run(handle, user) 在 uid 2000 会话上照常执行（下限满足）") {
        val pool = TerminalSessionPool(factory(shizuku = true, user = true))
        val h = pool.open(TerminalIdentity.User, "a", preferProot = true).handle!!
        val r = pool.run(h, "pwd", identity = TerminalIdentity.User)
        expect(r.ok) { "执行失败：${r.errorCode} ${r.message}" }
        eq(r.exitCode, 0, "退出码")
    }

    checkBlocking("D6 run(handle, su) 在 uid 2000 会话上被拒（不是静默降级执行）") {
        val pool = TerminalSessionPool(factory(shizuku = true, user = true))
        val h = pool.open(TerminalIdentity.User, "a", preferProot = true).handle!!
        val r = pool.run(h, "id -u", identity = TerminalIdentity.Su)
        expect(!r.ok) { "以 su 声明的命令在 uid 2000 会话上跑通了——这条命令其实没有 root" }
        eq(r.errorCode, "identity_conflict", "错误码")
    }

    checkBlocking("D7 open(su) 无候选 → identity_unsupported（不是 no_backend）") {
        val pool = TerminalSessionPool(factory(shizuku = true, user = true))
        val r = pool.open(TerminalIdentity.Su, "a", preferProot = true)
        expect(!r.ok) { "su 开辟成功？" }
        eq(r.errorCode, "identity_unsupported", "错误码")
    }

    checkBlocking("D8 快照里的身份是实际档，不是请求值") {
        val pool = TerminalSessionPool(factory(shizuku = true, user = true))
        val h = pool.open(TerminalIdentity.User, "a", preferProot = true).handle!!
        val snap = pool.list().firstOrNull { it.handle == h }
        expect(snap != null) { "快照里找不到会话" }
        eq(snap!!.identity, TerminalIdentity.Shizuku, "快照身份")
    }

    checkBlocking("D9 只有本机 shell 时，open(user) 的会话身份如实为 User") {
        val pool = TerminalSessionPool(factory(user = true))
        val r = pool.open(TerminalIdentity.User, "a", preferProot = true)
        expect(r.ok) { "开辟失败：${r.errorCode}" }
        eq(r.identity, TerminalIdentity.User, "固化身份")
    }

    checkBlocking("D10 不同别名：user 会话与 shizuku 会话各占一池，互不顶替") {
        val pool = TerminalSessionPool(factory(shizuku = true, user = true))
        val a = pool.open(TerminalIdentity.User, "a", preferProot = true)
        val b = pool.open(TerminalIdentity.Shizuku, "b", preferProot = true)
        expect(a.ok && b.ok) { "开辟失败：${a.errorCode} / ${b.errorCode}" }
        expect(a.backendKey == b.backendKey) { "两条请求的通道应相同（都选了 uid 2000）" }
        expect(a.handle != b.handle) { "不同别名拿到了同一句柄" }
    }
}

/* ================================================================== */
/* E. 源码守卫                                                          */
/* ================================================================== */

private fun read(path: String): String = File(path).readText(charset = Charsets.UTF_8)

/**
 * 只留**代码行**（去掉整行注释与 KDoc）。
 *
 * 为什么需要：R-12 的说明性注释里刻意引用了被删掉的旧代码（`it.identity == identity`、
 * `if (identity != TerminalIdentity.User)`），那是给人看的「改了什么」，不是回归。
 * 直接全文搜会把这些说明判成缺陷——判据必须区分「代码」与「文档」，
 * 否则每写一次解释就要改一次断言，最后大家会把注释删掉，知识就没了。
 *
 * 只处理**整行**注释：行尾注释里出现这些片段不影响语义判断（真正的代码在行首）。
 */
private fun codeOf(src: String): String = src.lineSequence()
    .map { it.trim() }
    .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
    .joinToString("\n")

private fun testSourceGuards() {
    println("\n== E 源码守卫：旧形态不能回来 ==")

    // 3.5 拆模块：terminal 在 :core，tools/automation 在 :app——两个根都要有
    val root = System.getProperty("agentkit.src") ?: "app/src/main/java/com/beichen/polaris"
    val coreRoot = System.getProperty("agentkit.core.src") ?: "core/src/main/java/com/beichen/polaris"
    val factorySrc = read("$coreRoot/core/terminal/backend/TerminalBackendFactory.kt")
    val poolSrc = read("$coreRoot/core/terminal/TerminalSessionPool.kt")
    val identitySrc = read("$coreRoot/core/terminal/backend/TerminalIdentity.kt")
    val shellSrc = read("$root/tools/ShellTool.kt")

    check("E1 工厂按 satisfies 筛候选（代码里，不是注释里）") {
        val code = codeOf(factorySrc)
        expect("it.identity.satisfies(identity)" in code) {
            "providersFor 里没有 satisfies —— 枚举值一变又得靠特例分支"
        }
        expect("it.identity == identity" !in code) {
            "仍用相等判定：请求 user 会把 uid 2000 后端筛掉（那正是「明明有提权后端却用不上」的老毛病）"
        }
    }

    check("E2 工厂代码里没有「非 User 一律返空」的硬编码特例") {
        val code = codeOf(factorySrc)
        expect("if (identity != TerminalIdentity.User)" !in code) {
            "硬编码特例还在——将来加 su 后端时还得回来改这里"
        }
    }

    check("E3 会话池按 satisfies 判复用与冲突，且固化的是后端实际身份") {
        expect("existing.identity.satisfies(identity)" in poolSrc) { "复用分支没走 satisfies" }
        expect("entry.identity.satisfies(identity)" in poolSrc) { "run() 的身份校验没走 satisfies" }
        expect("Entry(handle, candidate.identity," in poolSrc) {
            "Entry 没记后端实际身份——那 OpenResult 报的还是请求值"
        }
    }

    check("E4 枚举带 uid，且 uid 2000 有明确归属") {
        expect("val uid: Int?" in identitySrc) { "枚举没有 uid 字段" }
        expect("Shizuku(uid = 2000" in identitySrc) { "Shizuku 没有声明 uid 2000" }
        expect("Su(uid = 0" in identitySrc) { "Su 没有声明 uid 0" }
    }

    check("E5 shell 工具回执用 describe() 报身份，不再直接吐 wire") {
        expect("it.identity.describe()" in shellSrc) {
            "回执没用 describe()——会继续出现「uid 2000 的会话显示成身份 user」"
        }
    }

    check("E6 两处提权后端的声明是 uid 2000 档（不是 User）") {
        val shizuku = read("$root/automation/shizuku/ShizukuTerminalBackend.kt")
        val adb = read("$root/automation/adb/AdbTerminalBackend.kt")
        // provider 侧
        expect("override val identity: TerminalIdentity = TerminalIdentity.Shizuku" in shizuku) {
            "ShizukuBackendProvider 仍声明 User"
        }
        val adbDecl = Regex("override val identity: TerminalIdentity = TerminalIdentity\\.(\\w+)")
            .findAll(adb).map { it.groupValues[1] }.toList()
        eq(adbDecl.size, 2, "AdbTerminalBackend 里的身份声明数")
        adbDecl.forEach { eq(it, "Shizuku", "adb 侧声明") }
        // 后端本体：Shizuku 走 ProcessBackendBase 的构造参数
        expect("ProcessBackendBase(identity = TerminalIdentity.Shizuku)" in shizuku) {
            "ShizukuTerminalBackend 本体没把身份传给基类，仍是 User"
        }
    }
}

/* ================================================================== */

fun main() {
    println("== R-12 身份维度验证 ==")

    testIdentitySemantics()
    testFactoryCandidates()
    testObtain()
    testSessionPool()
    testSourceGuards()

    println("\n$p 通过 / $f 失败")
    if (f > 0) throw AssertionError("$f 条失败")
}
