/**
 * P1-3 `Consent` 契约的行为验证。
 *
 * ## 为什么是行为 harness
 *
 * 这一层要证的不是「有个 sealed interface」，而是**四种裁决各自成立**：
 * 没通道时给得出理由、问了允许、问了拒绝、问崩了算拒绝、取消要上抛。
 * 源码扫描只能证明写了 `when`，证明不了 `CancellationException` 没被吞成「用户拒绝」
 * ——而那条一旦错了，用户点「停止」会显示成「你拒绝了这个操作」，属于会被当成
 * 产品缺陷报上来的错误。
 *
 * ## 为什么能真跑
 *
 * `Consent.decide` 只依赖 `AgentTool` 与 `JsonObject`，`Consent.kt` + `AgentContract.kt`
 * + `ToolResult.kt` 三个文件就能编。这正是把裁决从 `RegistryToolExecutor`（牵
 * `ToolRegistry` → `JsonSchema.serializer()` → 要序列化编译器插件）搬出来的原因。
 *
 * 执行器里「拒绝分支必须在工具调用之前 return」这一条，本 harness 证不了——那是
 * `run-consent-verification.sh` 的 A6 用源码顺序断言盯的，两段合起来才完整。
 */

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.Consent
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

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

/** 只需要「有名字」与「能被 decide 接收」，不执行任何东西。 */
private class FakeTool(override val name: String) : AgentTool {
    override val description = "consent harness 假工具 $name"
    override val parameters = JsonSchema.objectSchema()
    override suspend fun execute(args: JsonObject): ToolResult = ToolResult.ok(message = "ran")
}

private val tool = FakeTool("write_file")
private val args = buildJsonObject { put("path", "/tmp/x") }
private val otherArgs = buildJsonObject { put("path", "/tmp/y") }

fun main() = runBlocking {
    println("── B 段：Consent.decide 的四种裁决 ──")

    // ── 没通道：拒绝，并且理由要带得出来 ────────────────────────────────
    val deny = Consent.Deny("桌宠气泡上没有审批按钮")
    val d1 = deny.decide(tool, args)
    ok(
        "B1 Deny → Unavailable，理由原样带出",
        d1 is Consent.Decision.Unavailable && d1.reason == "桌宠气泡上没有审批按钮",
        "实际 $d1",
    )

    val unset = Consent.unset("RealAgentLoop")
    val d2 = unset.decide(tool, args)
    ok(
        "B2 默认值 unset() 的拒绝理由点名是哪一层",
        d2 is Consent.Decision.Unavailable &&
            d2.reason == "执行通道「RealAgentLoop」没有声明审批能力",
        "实际 $d2",
    )

    ok(
        "B3 Deny 不依赖入参：换一组 args 仍然拒绝",
        deny.decide(tool, otherArgs) is Consent.Decision.Unavailable,
    )

    // ── 有通道：允许 / 拒绝 / 崩了 ─────────────────────────────────────
    val allowCalls = AtomicInteger()
    val allow = Consent.ask { _, _ ->
        allowCalls.incrementAndGet()
        true
    }
    ok("B4 Ask{true} → Allowed", allow.decide(tool, args) is Consent.Decision.Allowed)
    ok("B5 handler 被调用恰好一次", allowCalls.get() == 1, "实际 ${allowCalls.get()} 次")

    val denyCalls = AtomicInteger()
    val refuse = Consent.ask { _, _ ->
        denyCalls.incrementAndGet()
        false
    }
    val d6 = refuse.decide(tool, args)
    ok("B6 Ask{false} → Denied", d6 is Consent.Decision.Denied, "实际 $d6")
    ok("B7 拒绝时 handler 同样被调用（确实问过，不是跳过）", denyCalls.get() == 1)

    // 不缓存：同一条 Ask 连续决定两次，要问两次。「本次会话不再询问」的短路在
    // ChatViewModel 的 approvalHandler 里，不属于 Consent —— 这层若偷偷缓存，
    // 上层就再也收不回授权。
    allow.decide(tool, args)
    ok("B8 同一 Ask 连续决定两次会问两次（不缓存授权）", allowCalls.get() == 2, "实际 ${allowCalls.get()} 次")

    val boom = Consent.ask { _, _ -> throw IllegalStateException("弹窗已销毁") }
    val d9 = boom.decide(tool, args)
    ok(
        "B9 handler 抛异常 → Failed（不是 Denied），并带出原因",
        d9 is Consent.Decision.Failed && d9.cause == "弹窗已销毁",
        "实际 $d9",
    )

    // ── 取消不是拒绝：这条错了，用户点「停止」会显示成「你拒绝了这个操作」 ──
    val cancel = Consent.ask { _, _ -> throw CancellationException("用户停止") }
    val thrown = runCatching { cancel.decide(tool, args) }.exceptionOrNull()
    ok(
        "B10 handler 抛 CancellationException 时上抛，不吞成 Denied/Failed",
        thrown is CancellationException,
        "实际 ${thrown?.let { it::class.simpleName }}",
    )

    // ── 四种裁决必须彼此可区分 ─────────────────────────────────────────
    val all = listOf(
        Consent.Decision.Allowed,
        Consent.Decision.Denied,
        Consent.Decision.Unavailable("x"),
        Consent.Decision.Failed("x"),
    )
    ok(
        "B11 四种 Decision 互不相等（布尔会把这个区分压扁）",
        all.toSet().size == 4,
        "实际只有 ${all.toSet().size} 种",
    )
    ok(
        "B12 两条不同理由的 Deny 不相等（理由是判据的一部分）",
        Consent.Deny("甲") != Consent.Deny("乙"),
    )

    println("")
    println("$passed 通过 / $failed 失败")
    if (failed > 0) kotlin.system.exitProcess(1)
}
