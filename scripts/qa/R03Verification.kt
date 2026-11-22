#!/usr/bin/env kotlin
/**
 * R-03「审批超时写在 Composable 里」行为验证 harness。
 *
 * ## 为什么必须能真跑
 * 缺陷的全部内容是「等待的时限取决于 UI 是否还在组合树上」——grep 只能证明
 * `withTimeoutOrNull` 这个词出现过，证明不了「超时真的会返回」「取消真的会被抛出」。
 * 被测函数 [awaitApprovalDecision] 是纯 Kotlin（只依赖 coroutines），所以这里直接
 * 编译它跑一遍，不依赖 AGP 产物。
 *
 * ## 覆盖
 *  A. 正常决策：超时之前到达的决策说了算
 *  B. 超时：返回 false、耗时对得上、迟到决策改不了已返回的值
 *  C. 外部取消：**抛 CancellationException**，不能被吞成「用户拒绝」
 *  E. 已完成：立即返回，不被超时逻辑拖住
 *
 * 运行：scripts/qa/run-r03-verification.sh
 */

import com.beichen.polaris.core.agent.awaitApprovalDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

fun check(name: String, body: () -> Unit) {
    try {
        body()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += "$name — ${t.message}"
        println("  FAIL  $name — ${t.message}")
    }
}

fun expect(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

/** 延迟求值的失败信息：避免热路径上白拼字符串。 */
fun expect(cond: Boolean, msg: () -> String) {
    if (!cond) throw AssertionError(msg())
}

fun <T> eq(actual: T, expected: T, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 <$expected>，实际 <$actual>")
}

/** 跑一段挂起代码并返回耗时（毫秒）。传 CoroutineScope 是为了能在里面 launch。 */
private fun timed(block: suspend CoroutineScope.() -> Unit): Long {
    val t0 = System.currentTimeMillis()
    runBlocking { block() }
    return System.currentTimeMillis() - t0
}

/* --------------------------------- 用例 --------------------------------- */

fun main() {
    println("R-03 审批等待：超时 / 取消的语义边界")

    // ── A. 正常决策 ────────────────────────────────────────────────────
    println("\n[A] 正常决策")
    check("A1 及时允许 → true") {
        runBlocking {
            val d = CompletableDeferred<Boolean>()
            launch { delay(20); d.complete(true) }
            eq(awaitApprovalDecision(d, 2_000), true, "返回值")
        }
    }
    check("A2 及时拒绝 → false") {
        runBlocking {
            val d = CompletableDeferred<Boolean>()
            launch { delay(20); d.complete(false) }
            eq(awaitApprovalDecision(d, 2_000), false, "返回值")
        }
    }
    check("A3 决策在超时前到达，就按决策走（不是硬等满时限）") {
        var got: Boolean? = null
        val cost = timed {
            val d = CompletableDeferred<Boolean>()
            launch { delay(100); d.complete(true) }
            got = awaitApprovalDecision(d, 3_000)
        }
        eq(got, true, "返回值")
        expect(cost < 1_500) { "决策 100ms 就到了，却等了 $cost ms —— 没有提前返回" }
    }

    // ── B. 超时 ────────────────────────────────────────────────────────
    println("\n[B] 超时：三态里的 null（不是 false）")
    check("B1 无人决策 → 返回 null（超时，不是拒绝）") {
        var got: Boolean? = null
        val cost = timed {
            val d = CompletableDeferred<Boolean>()
            got = awaitApprovalDecision(d, 150)
        }
        eq(got, null, "返回值：null 才代表没人回应")
        expect(cost < 2_000) { "150ms 的时限却等了 $cost ms —— 超时没生效" }
    }
    check("B2 超时耗时不早于时限（不能提前放弃）") {
        val cost = timed {
            awaitApprovalDecision(CompletableDeferred(), 150)
        }
        expect(cost >= 130) { "时限 150ms，实际 $cost ms 就返回了 —— 提前放弃等于误拒" }
    }
    check("B3 超时之后迟到的决策改不了已返回的值（仍为 null）") {
        runBlocking {
            val d = CompletableDeferred<Boolean>()
            val first = awaitApprovalDecision(d, 120)
            // 用户在半秒后才点「允许」——此时弹窗早已关闭，必须仍然算拒绝
            d.complete(true)
            eq(first, null, "首次返回值")
            eq(d.await(), true, "迟到的决策只是写进 deferred，不影响已返回的结果")
        }
    }

    // ── C. 取消：最容易写错的一条 ──────────────────────────────────────
    println("\n[C] 外部取消 != 超时")
    check("C1 取消抛 CancellationException，不返回 false") {
        runBlocking {
            val d = CompletableDeferred<Boolean>()
            var outcome: Any? = "<未返回>"
            // 不带 Dispatchers：继承 runBlocking 的事件循环，避免共享变量跨线程
            val job = launch {
                outcome = try {
                    awaitApprovalDecision(d, 60_000)
                } catch (e: CancellationException) {
                    e
                }
            }
            delay(60)
            job.cancelAndJoin()
            expect(
                outcome is CancellationException,
                "取消被当成了 $outcome —— 用户点「停止」会被记成一次「用户拒绝」，协程继续往下跑"
            )
        }
    }
    check("C2 取消等的是真取消，不是「取消时正好超时」") {
        // 60 秒时限 + 60ms 后取消：若实现把取消吞成 false，这里会返回 false 而不是抛。
        runBlocking {
            val d = CompletableDeferred<Boolean>()
            var threw = false
            val job = launch {
                try {
                    awaitApprovalDecision(d, 60_000)
                } catch (e: CancellationException) {
                    threw = true
                }
            }
            delay(60)
            job.cancelAndJoin()
            expect(threw) { "取消没有传播 CancellationException" }
            eq(d.isCompleted, false, "deferred 不该被取消顺手完成（那是把取消伪装成决策）")
        }
    }

    // ── E. 已完成 ──────────────────────────────────────────────────────
    println("\n[E] 已完成立即返回")
    check("E1 决策已经落定 → 立即返回，不被 60 秒时限拖住") {
        var got: Boolean? = null
        val cost = timed {
            val d = CompletableDeferred<Boolean>()
            d.complete(false)
            got = awaitApprovalDecision(d, 60_000)
        }
        eq(got, false, "已完成 → 立刻返回那个决策（这里是拒绝），不该等到超时")
        expect(cost < 1_000) { "已完成却等了 $cost ms" }
    }

    // ── 汇总 ───────────────────────────────────────────────────────────
    println()
    if (failures.isNotEmpty()) {
        println("失败清单：")
        failures.forEach { println("  - $it") }
    }
    println("=== 合计 $passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}
