#!/usr/bin/env kotlin
/**
 * R6（运行层判据收口 + 工具预算分级）行为验证 harness。
 *
 * ## 为什么单独一套
 * R6 修掉的是两个**语义冲突**类的缺陷，它们的共同点是「跑一遍看不出来，
 * 配错了才出事」：
 *
 * 1. **空闲释放有两个判据源**。旧 [IdleUnloadPolicy] 与 [LeaseRegistry] 各有一套
 *    `idleTimeoutMs`，而且语义**相反**：policy 里 `0` = 永不释放，
 *    registry 里 `0` = 立即回收。用户关掉「空闲释放」后，policy 停了，
 *    registry 照旧按全局 30 分钟回收——**设置形同虚设**。
 *
 * 2. **工具预算只有一个粒度**。R2 只有「只读 90s / 写 600s」两档，
 *    `gradle build` 这种真长任务不够、`http_request` 这种短任务白等。
 *
 * 覆盖：
 *  R6-1  租约自身阈值覆盖全局值（用户设置真的作用到回收上）
 *  R6-2  关闭空闲释放 = 租约阈值置负数 → 回收器跳过该租约
 *  R6-3  阈值语义统一：`0`=立即满足（测试档），负数=禁用，两侧一致
 *  R6-4  空闲释放开关关闭时，即便全局阈值是 0（立即满足）也不回收
 *  R6-5  工具自声明预算生效（覆盖分档默认）
 *  R6-6  未声明的工具仍走分档默认
 *  R6-7  自声明预算越界被夹紧（下界 1s / 上界 max）
 *  R6-8  分档默认**不被夹紧**（测试注入的 80ms 必须原样生效）
 *  R6-9  executor 实际按解析后的预算超时（端到端）
 *  R6-10 等用户的工具不吃预算（awaitsUser 优先于自声明）
 *
 * 运行：scripts/qa/run-r6-verification.sh
 */

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.FailureGuard
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.agent.RegistryToolExecutor
import com.beichen.polaris.core.agent.ToolBudget
import com.beichen.polaris.core.agent.ToolCallOutcome
import com.beichen.polaris.core.agent.ToolDescriptor
import com.beichen.polaris.runtime.lease.DISABLED_IDLE_TIMEOUT_MS
import com.beichen.polaris.runtime.lease.LeaseHandle
import com.beichen.polaris.runtime.lease.LeaseRegistry
import com.beichen.polaris.runtime.lease.RuntimeLease
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

private var passed = 0
private val failures = mutableListOf<String>()

private suspend fun check(name: String, block: suspend () -> Unit) {
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
    if (expected != actual) throw AssertionError("$name: expected=$expected actual=$actual")
}

/**
 * 可控的假租约（与 R3 同款，但多一个自声明阈值）。
 *
 * @param ownTimeout 自声明的空闲阈值；null = 跟随全局。
 */
private class FakeLease(
    override val id: String,
    override var idleTimeoutMs: Long? = null,
    var safeToRelease: Boolean = true,
) : RuntimeLease {

    private val active = java.util.concurrent.atomic.AtomicInteger(0)
    var releaseCount = 0
        private set

    override val activeCount: Int get() = active.get()

    override val isSafeToRelease: Boolean get() = safeToRelease

    override suspend fun acquire(): LeaseHandle {
        active.incrementAndGet()
        return LeaseRegistry.acquire(this) {
            active.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
    }

    fun doRelease() {
        releaseCount++
    }
}

private var counter = 0
private fun nextId(prefix: String): String = "$prefix-${counter++}"

/** 假工具：可指定只读/等用户/自声明预算。 */
private class FakeTool(
    override val name: String,
    override val readOnly: Boolean = false,
    override val awaitsUser: Boolean = false,
    override val timeoutBudgetMs: Long? = null,
    private val sleepMs: Long = 0L,
) : AgentTool {
    override val description = "fake"
    override val parameters = JsonSchema.objectSchema()

    override suspend fun execute(args: JsonObject): ToolResult {
        if (sleepMs > 0) delay(sleepMs)
        return ToolResult.ok(message = "done")
    }
}

/**
 * 模拟「按旧版 [AgentTool] 编译、未重新编译」的实现。
 *
 * 接口新增默认方法后，旧实现的 class 没有对应桥接，`invokeinterface` 会抛
 * [AbstractMethodError]。这里直接抛，精确复现该故障形态。
 */
private class LegacyTool(override val name: String) : AgentTool {
    override val description = "legacy"
    override val parameters = JsonSchema.objectSchema()

    override val timeoutBudgetMs: Long?
        get() = throw AbstractMethodError("getTimeoutBudgetMs()")

    override val readOnly: Boolean
        get() = throw AbstractMethodError("getReadOnly()")

    override suspend fun execute(args: JsonObject): ToolResult = ToolResult.ok(message = "done")
}

private fun main2() = runBlocking {
    println("\n[R6] 运行层判据收口 + 工具预算分级")

    // ———— 第一部分：空闲释放只有一个判据源 ————

    check("R6-1 租约自身阈值覆盖全局值") {
        val lease = FakeLease(nextId("r6-1"))
        LeaseRegistry.register(lease)
        LeaseRegistry.registerIdleReleaser(lease.id) { lease.doRelease() }
        // 全局设成「立即满足」，但租约自称要等一小时 → 不该回收
        lease.idleTimeoutMs = 60 * 60_000L
        LeaseRegistry.idleTimeoutMs = 0L
        LeaseRegistry.checkOnce()
        assertTrue("租约阈值应覆盖全局，实际回收 ${lease.releaseCount} 次", lease.releaseCount == 0)

        // 把租约阈值改成 0（立即满足）→ 该回收
        lease.idleTimeoutMs = 0L
        LeaseRegistry.checkOnce()
        assertTrue("租约阈值为 0 时应回收，实际 ${lease.releaseCount} 次", lease.releaseCount == 1)
    }

    check("R6-2 关闭空闲释放 = 阈值置负数 → 回收器跳过该租约") {
        val lease = FakeLease(nextId("r6-2"), idleTimeoutMs = DISABLED_IDLE_TIMEOUT_MS)
        LeaseRegistry.register(lease)
        LeaseRegistry.registerIdleReleaser(lease.id) { lease.doRelease() }
        // 全局「立即满足」也救不了它——禁用是租约自己的决定
        LeaseRegistry.idleTimeoutMs = 0L
        LeaseRegistry.checkOnce()
        assertTrue("禁用的租约不应回收，实际 ${lease.releaseCount} 次", lease.releaseCount == 0)
    }

    check("R6-3 阈值语义统一：0=立即满足，负数=禁用") {
        assertTrue("DISABLED_IDLE_TIMEOUT_MS 必须为负数", DISABLED_IDLE_TIMEOUT_MS < 0L)

        // 0 → 立即回收
        val zeroLease = FakeLease(nextId("r6-3z"), idleTimeoutMs = 0L)
        LeaseRegistry.register(zeroLease)
        LeaseRegistry.registerIdleReleaser(zeroLease.id) { zeroLease.doRelease() }
        LeaseRegistry.idleTimeoutMs = 60 * 60_000L   // 全局给足，租约自己说立即
        LeaseRegistry.checkOnce()
        assertTrue("租约阈值 0 应立即回收，实际 ${zeroLease.releaseCount} 次", zeroLease.releaseCount == 1)

        // 负数 → 禁用
        val negLease = FakeLease(nextId("r6-3n"), idleTimeoutMs = -5L)
        LeaseRegistry.register(negLease)
        LeaseRegistry.registerIdleReleaser(negLease.id) { negLease.doRelease() }
        LeaseRegistry.idleTimeoutMs = 0L
        LeaseRegistry.checkOnce()
        assertTrue("负数阈值应禁用回收，实际 ${negLease.releaseCount} 次", negLease.releaseCount == 0)
    }

    check("R6-4 空闲释放关闭时，即便全局阈值为 0 也不回收") {
        // 这一条直接对应 R6 修掉的真实 bug：
        // 用户关掉「空闲释放」→ 旧 policy 停了，registry 照旧回收。
        val lease = FakeLease(nextId("r6-4"), idleTimeoutMs = DISABLED_IDLE_TIMEOUT_MS)
        LeaseRegistry.register(lease)
        LeaseRegistry.registerIdleReleaser(lease.id) { lease.doRelease() }
        LeaseRegistry.idleTimeoutMs = 0L
        LeaseRegistry.checkOnce()
        assertTrue("关闭空闲释放后不应回收，实际 ${lease.releaseCount} 次", lease.releaseCount == 0)
    }

    check("R6-5 关闭空闲释放后重新开启，回收恢复") {
        val lease = FakeLease(nextId("r6-5"), idleTimeoutMs = DISABLED_IDLE_TIMEOUT_MS)
        LeaseRegistry.register(lease)
        LeaseRegistry.registerIdleReleaser(lease.id) { lease.doRelease() }
        LeaseRegistry.idleTimeoutMs = 0L
        LeaseRegistry.checkOnce()
        assertTrue("关闭期间不应回收", lease.releaseCount == 0)

        // 模拟用户重新打开开关：阈值回到正数
        lease.idleTimeoutMs = 30 * 60_000L
        LeaseRegistry.checkOnce()
        assertTrue("开启后（阈值 30 分钟、刚用过）仍不应立即回收", lease.releaseCount == 0)

        lease.idleTimeoutMs = 0L
        LeaseRegistry.checkOnce()
        assertTrue("重新开启且空闲超时后应回收，实际 ${lease.releaseCount} 次", lease.releaseCount == 1)
    }

    // ———— 第二部分：工具预算按工具覆盖 ————

    check("R6-6 工具自声明预算生效（覆盖分档默认）") {
        val tool = FakeTool("r6-budget", readOnly = false, timeoutBudgetMs = 123_456L)
        val resolved = ToolBudget.resolve(tool, readOnlyBudgetMs = 90_000L, writeBudgetMs = 600_000L)
        assertEquals("自声明预算应生效", 123_456L, resolved)
    }

    check("R6-7 未声明的工具走分档默认") {
        val ro = FakeTool("r6-ro", readOnly = true)
        assertEquals(
            "只读工具应走只读默认",
            90_000L,
            ToolBudget.resolve(ro, readOnlyBudgetMs = 90_000L, writeBudgetMs = 600_000L)
        )
        val wr = FakeTool("r6-wr", readOnly = false)
        assertEquals(
            "写工具应走写默认",
            600_000L,
            ToolBudget.resolve(wr, readOnlyBudgetMs = 90_000L, writeBudgetMs = 600_000L)
        )
    }

    check("R6-8 自声明预算越界被夹紧（下界 1s / 上界 max）") {
        val tooSmall = FakeTool("r6-small", timeoutBudgetMs = 5L)
        assertEquals(
            "过小的自声明应夹到 1 秒",
            ToolBudget.MIN_DECLARED_BUDGET_MS,
            ToolBudget.resolve(tooSmall, 90_000L, 600_000L)
        )

        val tooBig = FakeTool("r6-big", timeoutBudgetMs = 999 * 60_000L)
        assertEquals(
            "过大的自声明应夹到上界",
            ToolBudget.MAX_DECLARED_BUDGET_MS,
            ToolBudget.resolve(tooBig, 90_000L, 600_000L)
        )

        val customMax = FakeTool("r6-max", timeoutBudgetMs = 999 * 60_000L)
        assertEquals(
            "自定义上界应生效",
            120_000L,
            ToolBudget.resolve(customMax, 90_000L, 600_000L, maxBudgetMs = 120_000L)
        )
    }

    check("R6-9 分档默认不被夹紧（测试注入的 80ms 原样生效）") {
        val tool = FakeTool("r6-plain", readOnly = true)
        assertEquals(
            "分档默认必须原样使用，否则超时分支测不到",
            80L,
            ToolBudget.resolve(tool, readOnlyBudgetMs = 80L, writeBudgetMs = 80L)
        )
    }

    check("R6-10 executor 实际按自声明预算超时（端到端）") {
        // 声明 60ms（会被夹到 1 秒下界），实际睡 3 秒 → 应被判超时
        val tool = FakeTool("r6-e2e", readOnly = true, timeoutBudgetMs = 60L, sleepMs = 3_000L)
        val reg = ToolRegistry().apply { register(tool) }
        val exec = RegistryToolExecutor(reg, FailureGuard())
        val start = System.currentTimeMillis()
        val outcome = exec.execute(
            ToolCall("c1", "r6-e2e", JsonObject(emptyMap())),
            ToolDescriptor("r6-e2e", "r6-e2e", "{}")
        )
        val cost = System.currentTimeMillis() - start
        assertTrue("应超时，实际耗时 ${cost}ms", cost < 2_500L)
        val completed = outcome as ToolCallOutcome.Completed
        assertEquals("超时结果码应为 tool_timeout", "tool_timeout", completed.code)
    }

    check("R6-11 等用户的工具不吃预算（awaitsUser 优先）") {
        // awaitsUser 的工具声明再小的预算也不该被掐断——
        // 用户思考三分钟不是「工具卡死」。
        val tool = FakeTool("r6-await", readOnly = true, awaitsUser = true, timeoutBudgetMs = 50L)
        val reg = ToolRegistry().apply { register(tool) }
        val exec = RegistryToolExecutor(reg, FailureGuard())
        val start = System.currentTimeMillis()
        val outcome = exec.execute(
            ToolCall("c2", "r6-await", JsonObject(emptyMap())),
            ToolDescriptor("r6-await", "r6-await", "{}")
        )
        val cost = System.currentTimeMillis() - start
        assertTrue("不应被预算掐断，实际耗时 ${cost}ms", cost < 1_000L)
        val completed = outcome as ToolCallOutcome.Completed
        assertEquals("应正常完成", "ok", completed.code)
    }

    // —— A-2：回合预算必须作用到**单次调用**（2026-11-12） ——
    //
    // 修前回合预算只在步边界判：一个 29 分钟起跑的写工具仍按自己的 10 分钟预算跑，
    // 回合 39 分钟才收尾。用户对「30 分钟上限」的预期是硬承诺，不是「尽量」。

    check("A-2a 回合剩余额度参与裁剪（工具预算被压到剩余）") {
        // 工具声明 3 秒、实际睡 2 秒，但回合只剩 150ms → 必须按 150ms 收手
        val tool = FakeTool("a2-cut", readOnly = true, timeoutBudgetMs = 3_000L, sleepMs = 2_000L)
        val reg = ToolRegistry().apply { register(tool) }
        val exec = RegistryToolExecutor(reg, FailureGuard(), turnRemainingMs = { 150L })
        val start = System.currentTimeMillis()
        val outcome = exec.execute(
            ToolCall("a2-1", "a2-cut", JsonObject(emptyMap())),
            ToolDescriptor("a2-cut", "a2-cut", "{}")
        )
        val cost = System.currentTimeMillis() - start
        assertTrue("应被压到回合剩余的 150ms，实际 ${cost}ms", cost < 1_200L)
        assertEquals("只读超时码", "tool_timeout", (outcome as ToolCallOutcome.Completed).code)
    }

    check("A-2b 回合已耗尽时不进入执行（无副作用，可安全重试）") {
        // 关键区别：这不是「跑了没等到结果」，而是**根本没跑**。
        // 文案与 code 必须能区分——否则模型会去核对一件没发生的事，或者直接重做。
        val tool = FakeTool("a2-none", readOnly = false, sleepMs = 1_000L)
        val reg = ToolRegistry().apply { register(tool) }
        val exec = RegistryToolExecutor(reg, FailureGuard(), turnRemainingMs = { 0L })
        val start = System.currentTimeMillis()
        val outcome = exec.execute(
            ToolCall("a2-2", "a2-none", JsonObject(emptyMap())),
            ToolDescriptor("a2-none", "a2-none", "{}")
        )
        val cost = System.currentTimeMillis() - start
        assertTrue("不得真的执行（应 <200ms），实际 ${cost}ms", cost < 200L)
        assertEquals(
            "应是「未执行」而不是「超时」",
            "turn_budget_exhausted",
            (outcome as ToolCallOutcome.Completed).code
        )
    }

    check("R6-12 预算声明不可读时退回分档默认，不让工具整调用崩掉") {
        // 真实故障：接口新增默认方法后，某个实现类没有重新编译（增量构建的典型产物），
        // 它的 class 里没有 getTimeoutBudgetMs 桥接，invokeinterface 抛 AbstractMethodError。
        // R7 真机上 use_skill 就是这样整调用失败的——日志看起来像技能执行失败，实际是二进制不兼容。
        val legacy = LegacyTool("r6-legacy")
        assertEquals(
            "读不到声明应退回写档默认（readOnly 也读不到 → 按写处理，更保守）",
            600_000L,
            ToolBudget.resolve(legacy, readOnlyBudgetMs = 90_000L, writeBudgetMs = 600_000L)
        )
    }

    check("R6-13 不兼容工具经 executor 仍能正常执行（护栏降级而非阻断）") {
        val legacy = LegacyTool("r6-legacy-e2e")
        val reg = ToolRegistry().apply { register(legacy) }
        val exec = RegistryToolExecutor(reg, FailureGuard())
        val outcome = exec.execute(
            ToolCall("c3", "r6-legacy-e2e", JsonObject(emptyMap())),
            ToolDescriptor("r6-legacy-e2e", "r6-legacy-e2e", "{}")
        )
        val completed = outcome as ToolCallOutcome.Completed
        assertEquals("预算读不到不该影响业务执行", "ok", completed.code)
    }
}

fun main() {
    println("=== AgentKit R6 验证（运行层判据收口 + 工具预算分级）===")
    main2()
    println("\n=== 结果：$passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
