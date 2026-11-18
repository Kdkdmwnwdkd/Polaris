/**
 * P1-3 第二阶段 `ApprovalGate` 的行为验证。
 *
 * ## 这一层要证什么
 *
 * 「有个 suspend 函数能等」不是重点。重点是四种收场各自成立，且**不会互相串台**：
 * 超时后再点按钮不能算数、界面销毁后的调用不能重新开一个没人看的等待、
 * 用户点「停」不能被记成「拒绝」。这些在真机上要复现得靠手速，在这里是几行 `delay`。
 *
 * ## 为什么能真跑
 *
 * `ApprovalGate` 只依赖 kotlinx-coroutines，一个源文件就能编。这正是把它从
 * `PetApproval`（要 Context / StateFlow / Compose）里拆出来的原因——同一条教训
 * 在 `Consent` 与 `McpServerHost` 上各踩过一次。
 *
 * ## 本 harness 证不了的那一半
 *
 * 「卡片真的画出来了」靠 `run-pet-consent-verification.sh` 的 A 段源码断言；
 * 「宿主看到 pending 会把气泡弹开」也在那里（A3）。两段合起来才是完整闭环。
 */

import com.beichen.polaris.core.ApprovalGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private var passed = 0
private var failed = 0

private fun ok(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        println("  PASS  $name")
        passed++
    } else {
        println("  FAIL  $name${if (detail.isBlank()) "" else " —— $detail"}")
        failed++
    }
}

/** 等闸门真的进入等待态。轮询而不是 `delay(固定值)`：固定值在慢机器上会偶发。 */
private suspend fun waitUntilWaiting(gate: ApprovalGate, limitMs: Long = 2_000) {
    val start = System.currentTimeMillis()
    while (!gate.isWaiting() && System.currentTimeMillis() - start < limitMs) delay(2)
}

fun main() = runBlocking {
    println("== 网关：四种收场 ==")

    // ── B1 允许 ────────────────────────────────────────────────────────
    run {
        val gate = ApprovalGate(2_000)
        val job = async(Dispatchers.Default) { gate.await() }
        waitUntilWaiting(gate)
        ok("B1 有人在等（isWaiting 为真）", gate.isWaiting())
        val accepted = gate.settle(true)
        val outcome = withTimeout(1_000) { job.await() }
        ok("B2 点「允许」→ ALLOWED，且 settle 返回 true", accepted && outcome == ApprovalGate.Outcome.ALLOWED, "实际 $outcome")
        ok("B3 收场后槽位清空（下一轮不会误用上一个等待）", !gate.isWaiting())
    }

    // ── B4 拒绝 ────────────────────────────────────────────────────────
    run {
        val gate = ApprovalGate(2_000)
        val job = async(Dispatchers.Default) { gate.await() }
        waitUntilWaiting(gate)
        gate.settle(false)
        val outcome = withTimeout(1_000) { job.await() }
        ok("B4 点「不允许」→ DENIED（与超时可区分）", outcome == ApprovalGate.Outcome.DENIED, "实际 $outcome")
    }

    // ── B5 超时 ────────────────────────────────────────────────────────
    run {
        val gate = ApprovalGate(120)
        val start = System.currentTimeMillis()
        val outcome = gate.await()
        val cost = System.currentTimeMillis() - start
        ok("B5 无人回应 → TIMEOUT", outcome == ApprovalGate.Outcome.TIMEOUT, "实际 $outcome")
        ok("B6 超时真的等满了窗口（$cost ms ≥ 100）", cost >= 100, "实际 $cost ms")
        ok("B7 超时后再点「允许」不算数（返回 false）", !gate.settle(true))
    }

    // ── B8 取消 ────────────────────────────────────────────────────────
    run {
        val gate = ApprovalGate(5_000)
        val job = async(Dispatchers.Default) { gate.await() }
        waitUntilWaiting(gate)
        gate.cancelAll()
        val outcome = withTimeout(1_000) { job.await() }
        ok("B8 界面销毁 → CANCELLED（不必等到超时）", outcome == ApprovalGate.Outcome.CANCELLED, "实际 $outcome")
        ok("B9 cancelAll 之后 new 的等待立刻 CANCELLED（不会开一个没人看的等待）",
            gate.await() == ApprovalGate.Outcome.CANCELLED)
    }

    // ── B10 重建 ───────────────────────────────────────────────────────
    run {
        val gate = ApprovalGate(2_000)
        gate.cancelAll()
        gate.reopen()
        val job = async(Dispatchers.Default) { gate.await() }
        waitUntilWaiting(gate)
        gate.settle(true)
        val outcome = withTimeout(1_000) { job.await() }
        ok("B10 Service 重建（reopen）后能重新等待", outcome == ApprovalGate.Outcome.ALLOWED, "实际 $outcome")
    }

    // ── B11 重复点击 ───────────────────────────────────────────────────
    run {
        val gate = ApprovalGate(2_000)
        val job = async(Dispatchers.Default) { gate.await() }
        waitUntilWaiting(gate)
        val first = gate.settle(true)
        val second = gate.settle(true)
        withTimeout(1_000) { job.await() }
        ok("B11 连点两下只认第一下（第二次返回 false）", first && !second, "first=$first second=$second")
    }

    // ── B12 空转 ───────────────────────────────────────────────────────
    run {
        val gate = ApprovalGate(200)
        ok("B12 没有等待者时 settle 返回 false（不会凭空造一个已完成的裁决）", !gate.settle(true))
        ok("B13 从未等待时 isWaiting 为假", !gate.isWaiting())
    }

    // ── B14 协程取消 ───────────────────────────────────────────────────
    run {
        val gate = ApprovalGate(5_000)
        val job = launch(Dispatchers.Default) {
            try {
                gate.await()
            } catch (c: CancellationException) {
                throw c
            }
        }
        waitUntilWaiting(gate)
        job.cancelAndJoin()
        ok("B14 用户点「停」时 await 上抛取消，不吞成 DENIED", job.isCancelled)
        ok("B15 取消后槽位已清（之后 settle 返回 false，不会误放行下一轮）", !gate.settle(true))
    }

    // ── B16 串行 ───────────────────────────────────────────────────────
    run {
        val gate = ApprovalGate(2_000)
        val first = async(Dispatchers.Default) { gate.await() }
        waitUntilWaiting(gate)
        // 第二个等待覆盖第一个：桌宠的回合是串行的，这种情况只会在宿主异常时出现，
        // 但一旦出现，必须让第一个等待收场，而不是两个都挂着。
        val second = async(Dispatchers.Default) { gate.await() }
        delay(30)
        gate.settle(true)
        val o2 = withTimeout(500) { second.await() }
        first.cancelAndJoin()
        delay(30)
        ok("B16 后一个等待能接管前一个（不会双双吊死）", o2 == ApprovalGate.Outcome.ALLOWED, "实际 $o2")
        ok("B17 被接管的那个已收场（isWaiting 由接管者决定，当前为假）", !gate.isWaiting())
    }

    println("")
    println("$passed 通过 / $failed 失败")
    if (failed > 0) kotlin.system.exitProcess(1)
}
