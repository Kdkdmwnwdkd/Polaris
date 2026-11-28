/**
 * R8 回合结束原因 harness（纯 JVM，不加载 Android）。
 *
 * 为什么这个文件到 R8 才有：`TurnClock.kt` 的注释从 R5 起就写着「`TurnClockTest`
 * 可在变异门禁里逐条打靶」，但仓里一直没有这个文件——注释承诺的东西没人验，
 * 等于没有。这一版把它补上，同时补 `TurnExit` 的语义。
 *
 * 断言口径（每条都要能被一句代码改动杀死）：
 *  - `wire` 必须与历史字面值逐字一致（改一个字，既有日志解析与既有断言全断）；
 *  - 默认值必须是 Unknown 而不是 Completed（改回 Completed 立刻红）；
 *  - `isCompleted` 只能认 Completed / InlineReply（把 MaxSteps 也算完成立刻红）；
 *  - `RealAgentLoop` 里不许再出现裸字符串 `status.set("`，也不许再引入第二个真相源
 *    `turnClosed(reason)`——这两条是防回退，不是防当前错误。
 */
import com.beichen.polaris.core.agent.TurnEndStatus
import com.beichen.polaris.core.agent.TurnExit
import java.io.File

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body()
        pass++
        println("  PASS  $name")
    } catch (t: Throwable) {
        fail++
        println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private fun assertEquals(msg: String, expected: Any?, actual: Any?) {
    if (expected != actual) throw AssertionError("$msg：期望 $expected，实际 $actual")
}

fun main() {
    println("=== R8 回合结束原因（TurnExit / TurnEndStatus）===")

    // ---------- wire 兼容性：一个字都不能改 ----------
    // 这些字面值已经写进了历史日志文件、NDJSON 遥测和 harness 断言里。
    // 枚举名随便改，wire 改了就是「老日志再也对不上」。
    val expectedWire = mapOf(
        "completed" to TurnExit.Completed,
        "cancelled" to TurnExit.Cancelled,
        "failed" to TurnExit.Failed,
        "inline_reply" to TurnExit.InlineReply,
        "budget_exceeded" to TurnExit.BudgetExceeded,
        "max_steps" to TurnExit.MaxSteps,
        "stopped" to TurnExit.Stopped,
        "unknown" to TurnExit.Unknown
    )

    check("wire 与历史字面值逐字一致（8 个）") {
        assertEquals("枚举值个数", expectedWire.size, TurnExit.values().size)
        expectedWire.forEach { (wire, exit) ->
            assertEquals("${exit.name} 的 wire", wire, exit.wire)
        }
    }

    check("wire 互不重复（重复会让日志无法反解）") {
        val wires = TurnExit.values().map { it.wire }
        assertEquals("wire 应全不重复，实际：$wires", wires.size, wires.toSet().size)
    }

    // ---------- 默认值：fail-closed ----------
    check("默认值是 Unknown，不是 Completed") {
        val s = TurnEndStatus()
        assertEquals("默认值", TurnExit.Unknown, s.value)
        assertTrue("默认值不许被当成完成", !s.value.isCompleted)
    }

    check("set 生效且可覆盖（同一出口多次 set 以最后一次为准）") {
        val s = TurnEndStatus()
        s.set(TurnExit.MaxSteps)
        assertEquals("首次 set", TurnExit.MaxSteps, s.value)
        s.set(TurnExit.Completed)
        assertEquals("后写覆盖", TurnExit.Completed, s.value)
    }

    // ---------- 完成判据 ----------
    check("isCompleted：只有 completed / inline_reply 为真") {
        val truthy = TurnExit.values().filter { it.isCompleted }.map { it.wire }.toSet()
        assertEquals("为真的集合", setOf("completed", "inline_reply"), truthy)
    }

    check("isCompleted：用满步数 / 超预算 / 被停止，都不算完成") {
        // 这三条是「做到了但没做完」。把它们算成完成，完成率会系统性高估，
        // 而且高估的恰好是最该被看见的那一端。
        listOf(TurnExit.MaxSteps, TurnExit.BudgetExceeded, TurnExit.Stopped).forEach {
            assertTrue("${it.wire} 不应算完成", !it.isCompleted)
        }
    }

    check("isCompleted：Unknown 不算完成（缺陷不该被记成好消息）") {
        assertTrue("unknown 不应算完成", !TurnExit.Unknown.isCompleted)
    }

    // ---------- 防回退：源码级 ----------
    val loop = File("core/src/main/java/com/beichen/polaris/core/agent/RealAgentLoop.kt")
    if (!loop.exists()) {
        println("  SKIP  源码级防回退（找不到 ${loop.path}，非仓库根目录运行）")
    } else {
        val src = loop.readText()

        check("RealAgentLoop 里没有裸字符串 status.set(\"") {
            assertTrue(
                "又出现了裸字符串出口——拼错不会报错，只会让日志出现不认识的值",
                !src.contains("status.set(\"")
            )
        }

        check("turnClosed 的实参是 status.value.wire（单一真相源）") {
            assertTrue(
                "turnClosed 必须直接取 status，不许再引入第二个 reason 变量",
                src.contains("turn.turnClosed(status.value.wire)")
            )
            assertTrue("不应再有 turnClosed(reason)", !src.contains("turnClosed(reason)"))
        }

        check("每个出口都用 TurnExit 成员（无残留 REASON_ 常量）") {
            assertTrue(
                "RealAgentLoop 里不该再有 TurnJournal.REASON_*：它只有三态，装不下 max_steps/budget/stopped",
                !src.contains("TurnJournal.REASON_")
            )
        }
    }

    println("\n=== 汇总: $pass 通过 / $fail 失败 ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
