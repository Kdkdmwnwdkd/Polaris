package com.beichen.polaris.core.runtime

import com.beichen.polaris.core.Consent
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [AgentRuntime] 的两级并发语义。
 *
 * 每一条都对着 v4 §3.2 的硬不变量或 P0 清单 §1 的验收写：
 * - 队列层：(session, source) 互斥——「同一个人不会同时说两句话」；
 * - 许可层：backend 上限——「本地推理交错 prefill 会互相拖死」；
 * - 代次号：cancel 自增，旧 lease 立即过期。
 *
 * ## 为什么每个挂起点都套 [withTimeout]
 *
 * 这些测试是**变异门禁的判据**：变异会故意把「互斥」或「许可」改坏，
 * 那时某个 `await` 就会永远挂起。若不套超时，坏的变异不是「测试红」，
 * 而是「门禁自己卡死」——比假绿更糟，因为结论出不来。
 * 套上超时后，死锁变成 [kotlinx.coroutines.TimeoutCancellationException]，
 * JUnit 记为失败，变异正确判为 CAUGHT。
 */
class AgentRuntimeTest {

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }

    private fun fresh(backends: Map<String, Int> = AgentRuntime.defaultBackends()) =
        AgentRuntime.of(backends)

    // —— 队列层：同键串行 ——

    @Test
    fun `同 session 同 source 的第二个回合挂起直到第一个归还`() = runBlocking {
        val rt = fresh()
        val first = async { rt.acquireTurn("s", RunSource.MAIN) }
        val lease = withTimeout(TIMEOUT_MS) { first.await() }
        val second = async { rt.acquireTurn("s", RunSource.MAIN) }
        // 让第二个协程有机会跑——它必须停在 gate.acquire()，不能拿到 lease
        delay(50)
        assertFalse("同键第二个回合不该拿到所有权", second.isCompleted)
        assertEquals(1, rt.activeTurns())

        lease.close()
        withTimeout(TIMEOUT_MS) { second.await() }
        assertEquals(1, rt.activeTurns())
    }

    @Test
    fun `不同 session 不互相阻塞`() = runBlocking {
        val rt = fresh()
        val a = async { rt.acquireTurn("s1", RunSource.MAIN) }
        val b = async { rt.acquireTurn("s2", RunSource.MAIN) }
        val la = withTimeout(TIMEOUT_MS) { a.await() }
        val lb = withTimeout(TIMEOUT_MS) { b.await() }
        assertEquals(2, rt.activeTurns())
        la.close(); lb.close()
        assertEquals(0, rt.activeTurns())
    }

    @Test
    fun `同 session 不同 source 也不互相阻塞`() = runBlocking {
        val rt = fresh()
        val main = async { rt.acquireTurn("s", RunSource.MAIN) }
        val pet = async { rt.acquireTurn("s", RunSource.PET) }
        val lm = withTimeout(TIMEOUT_MS) { main.await() }
        val lp = withTimeout(TIMEOUT_MS) { pet.await() }
        assertEquals(2, rt.activeTurns())
        lm.close(); lp.close()
    }

    // —— 许可层：backend 上限 ——

    @Test
    fun `local 上限 1 第二个挂起`() = runBlocking {
        val rt = fresh()
        val first = async { rt.withPermit("local") { delay(200); 1 } }
        val second = async { rt.withPermit("local") { 2 } }
        // 第一个拿到许可后在 delay，第二个必须挂起等许可
        delay(50)
        assertFalse("local=1 时第二个不该拿到许可", second.isCompleted)
        assertEquals(0, rt.availablePermits("local"))
        assertEquals(1, withTimeout(TIMEOUT_MS) { first.await() })
        assertEquals(2, withTimeout(TIMEOUT_MS) { second.await() })
    }

    @Test
    fun `cloud 上限 2 第三个挂起`() = runBlocking {
        val rt = fresh()
        val a = async { rt.withPermit("cloud") { delay(200); "a" } }
        val b = async { rt.withPermit("cloud") { delay(200); "b" } }
        val c = async { rt.withPermit("cloud") { "c" } }
        delay(50)
        assertEquals(0, rt.availablePermits("cloud"))
        assertFalse("cloud=2 时第三个不该拿到许可", c.isCompleted)
        assertEquals("a", withTimeout(TIMEOUT_MS) { a.await() })
        assertEquals("b", withTimeout(TIMEOUT_MS) { b.await() })
        assertEquals("c", withTimeout(TIMEOUT_MS) { c.await() })
        assertEquals(2, rt.availablePermits("cloud"))
    }

    @Test
    fun `未知 backend 直接抛异常而不是静默放行`() = runBlocking {
        val rt = fresh()
        try {
            rt.withPermit("nope") { }
            fail("未知 backend 必须 fail-fast")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("nope"))
        }
    }

    @Test
    fun `并发上限 0 或负数在构造时就拒绝`() {
        try {
            AgentRuntime.of(mapOf("local" to 0))
            fail("上限 0 不该被接受")
        } catch (e: IllegalArgumentException) {
            // require 命中
        }
    }

    // —— 代次号：cancel 自增，旧 lease 过期 ——

    @Test
    fun `代次号从 0 起 cancel 自增`() {
        val rt = fresh()
        assertEquals(0L, rt.generation("s"))
        assertTrue(rt.cancel("s"))
        assertEquals(1L, rt.generation("s"))
        assertTrue(rt.cancel("s"))
        assertEquals(2L, rt.generation("s"))
    }

    @Test
    fun `cancel 从未出现过的 session 返回 false`() {
        val rt = fresh()
        assertFalse(rt.cancel("ghost"))
    }

    @Test
    fun `lease 快照的是取得时的代次号 cancel 后仍指向旧值`() = runBlocking {
        val rt = fresh()
        val lease = rt.acquireTurn("s", RunSource.MAIN)
        val genAtAcquire = lease.generation
        assertEquals(0L, genAtAcquire)

        rt.cancel("s")
        assertEquals(1L, rt.generation("s"))
        // lease 的代次号是快照，不会跟着变——这正是「迟到事件按代次丢弃」的依据
        assertEquals(0L, lease.generation)
        lease.close()
    }

    @Test
    fun `cancel 只影响目标 session 的代次号`() {
        val rt = fresh()
        assertEquals(0L, rt.generation("a"))
        assertEquals(0L, rt.generation("b"))
        rt.cancel("a")
        assertEquals(1L, rt.generation("a"))
        assertEquals(0L, rt.generation("b"))
    }

    // —— 归还语义 ——

    @Test
    fun `activeTurns 随 acquire 与 close 增减`() = runBlocking {
        val rt = fresh()
        assertEquals(0, rt.activeTurns())
        // 两个 acquire 也套 withTimeout：正常语义下不同 session 不互斥、立即返回；
        // 但变异若把门改成全局一把（R6），第二个会在这里挂起——不套超时就是门禁自己卡死。
        val l1 = withTimeout(TIMEOUT_MS) { rt.acquireTurn("s1", RunSource.MAIN) }
        val l2 = withTimeout(TIMEOUT_MS) { rt.acquireTurn("s2", RunSource.PET) }
        assertEquals(2, rt.activeTurns())
        l1.close()
        assertEquals(1, rt.activeTurns())
        l2.close()
        assertEquals(0, rt.activeTurns())
    }

    @Test
    fun `lease 重复 close 幂等`() = runBlocking {
        val rt = fresh()
        val lease = rt.acquireTurn("s", RunSource.MAIN)
        assertEquals(1, rt.activeTurns())
        lease.close()
        lease.close()  // 第二次必须 no-op
        assertEquals(0, rt.activeTurns())
    }

    @Test
    fun `归还后同键可以再次取得`() = runBlocking {
        val rt = fresh()
        val l1 = rt.acquireTurn("s", RunSource.MAIN)
        l1.close()
        val l2 = withTimeout(TIMEOUT_MS) { rt.acquireTurn("s", RunSource.MAIN) }
        assertEquals(rt.generation("s"), l2.generation)  // 新 lease 拿到的是当前代次号
        l2.close()
    }

    @Test
    fun `主界面与桌宠都走 local 时许可层拦住第二个`() = runBlocking {
        // P0 清单 §1 的验收场景：主界面回合 + 桌宠回合同时发起，
        // 若都打 local backend，第二个在许可层挂起（local=1）。
        val rt = fresh()
        val main = launch {
            rt.withPermit("local") {
                val lease = rt.acquireTurn("m", RunSource.MAIN)
                try { delay(150) } finally { lease.close() }
            }
        }
        val pet = launch {
            rt.withPermit("local") {
                val lease = rt.acquireTurn("p", RunSource.PET)
                try { delay(50) } finally { lease.close() }
            }
        }
        delay(50)
        // 主界面已进 local 许可，桌宠的 withPermit 必须挂起
        assertEquals(0, rt.availablePermits("local"))
        withTimeout(TIMEOUT_MS) { main.join() }
        withTimeout(TIMEOUT_MS) { pet.join() }
        assertEquals(0, rt.activeTurns())
    }

    // —— 契约层：TaskSpec（v4 不变量 3）——

    @Test
    fun `TaskSpec 显式携带配额与审批——两个字段都是必填`() {
        // 不变量 3 的构造面：quota 与 consent 无默认值，任何 TaskSpec(...) 都必须表态。
        // 这条测试钉住的是「字段真的被塞进 TaskSpec 且原样取出」——类型层的「无默认值」
        // 由静态门禁（run-runtime-invariants-verification.sh 的 A1/A2）兜，编译期再兜一道。
        val q = Quota(maxSteps = 3, maxTokens = 1_000, deadlineMs = 9_000L)
        val c = Consent.Deny("测试通道没有审批界面")
        val spec = TaskSpec(
            session = "s",
            source = RunSource.MAIN,
            input = "hi",
            quota = q,
            consent = c,
        )
        assertEquals("s", spec.session)
        assertEquals(RunSource.MAIN, spec.source)
        assertEquals(3, spec.quota.maxSteps)
        assertEquals(1_000, spec.quota.maxTokens)
        assertEquals(c, spec.consent)
    }

    @Test
    fun `Quota 默认值语义——0 表示不限那一项`() {
        // maxTokens=0 / deadlineMs=0L 都表示「不限」；maxSteps 默认 20 是兜底步数上限。
        val q = Quota()
        assertEquals(20, q.maxSteps)
        assertEquals(0, q.maxTokens)
        assertEquals(0L, q.deadlineMs)
    }

    // —— 单例层：shared 是进程级唯一闸，of 是显式 fresh（v4 不变量 4 前半）——

    @Test
    fun `shared 返回同一实例——进程内许可池只有一个真源`() {
        val a = AgentRuntime.shared()
        val b = AgentRuntime.shared()
        assertSame("shared() 必须返回进程级单例", a, b)
    }

    @Test
    fun `of 每次返回独立实例——测试与显式隔离用`() {
        val a = AgentRuntime.of()
        val b = AgentRuntime.of()
        assertNotSame("of() 必须每次 fresh，否则测试之间会串状态", a, b)
        // fresh 实例之间互不影响：先让 "s" 进入 a 的代次表（generation 首次访问才建档），
        // 再 cancel 自增——b 是独立实例，它的 "s" 必须仍是 0。
        assertEquals(0L, a.generation("s"))
        a.cancel("s")
        assertEquals(1L, a.generation("s"))
        assertEquals(0L, b.generation("s"))
    }
}
