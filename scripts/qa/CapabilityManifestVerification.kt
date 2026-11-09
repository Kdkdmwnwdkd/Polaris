#!/usr/bin/env kotlin
/**
 * 能力清单 harness：验证 [com.beichen.polaris.core.capability.CapabilityManifest] 的四条双向断言。
 *
 * ## 为什么这个 harness 值得存在
 *
 * 分组表是「人记得更新」的那类东西：新增工具忘了登记 → 静默落进 misc；工具改名 →
 * 补丁表留死条目；工具删光 → 组索引里挂一个空组。三种都不会报错，只会让模型
 * 多绕一圈（或永远找不到某组工具）。所以这里既验**正向**（真实工具集必须零问题），
 * 也验**反向**（每种不同步都必须被抓到，且指名道姓）——只验正向的话，一个永远返回
 * 空问题的 audit 也能「通过」。
 *
 * ## 工具名从哪来
 *
 * 不写死清单（写死就会随代码漂移，变成第二个需要人同步的表）：直接扫
 * `app/src/main/java` 下所有 `override val name = "..."`，得到当前真实工具名集合。
 * 于是「新增工具忘了登记组」这件事会在下一次跑 harness 时自己冒出来。
 *
 * 运行：bash scripts/qa/run-capability-manifest-verification.sh
 */

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.KernelExposure
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.capability.CapabilityManifest
import kotlinx.serialization.json.JsonObject
import java.io.File

private var pass = 0
private val fails = mutableListOf<String>()

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        pass++
        println("  ✅ $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    } else {
        fails += name
        println("  ❌ $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    }
}

private fun eq(name: String, actual: Any?, expected: Any?) =
    check(name, actual == expected, "实际=$actual 期望=$expected")

/** 只需名字的工具：审计只看 name，execute/参数与本题无关。 */
private fun stub(name: String): AgentTool = object : AgentTool {
    override val name: String = name
    override val description: String = "$name 的占位描述"
    override val parameters: JsonSchema = JsonSchema()
    override suspend fun execute(args: JsonObject): ToolResult = ToolResult.ok(message = "")
}

/** 扫源码拿真实工具名（`override val name = "xxx"`；动态名如 mcpWireName(...) 自然不匹配）。 */
private fun realToolNames(srcRoot: File): List<String> {
    val re = Regex("""override val name(?::\s*String)?\s*=\s*"([A-Za-z0-9_]+)"""")
    return srcRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { re.findAll(it.readText(Charsets.UTF_8)).map { m -> m.groupValues[1] } }
        .toList()
        .distinct()
        .sorted()
}

fun main(args: Array<String>) {
    val root = File(args.getOrElse(0) { "." })
    val src = File(root, "app/src/main/java")
    val coreSrc = File(root, "core/src/main/java")
    println("== 能力清单（P3-1）==")

    // —— 1. 清单本身的常量自检 ——
    println("\n[1] 清单常量")
    val groups = CapabilityManifest.groups
    check("组名唯一", groups.size == groups.toSet().size, "共 ${groups.size} 组")
    check("工具不重复", CapabilityManifest.toolNames.size == CapabilityManifest.toolNames.toSet().size,
        "共 ${CapabilityManifest.toolNames.size} 个")
    check("每组都有说明", CapabilityManifest.capabilities.all { it.note.isNotBlank() })
    eq("常驻组只有 file/misc", groups.filter { CapabilityManifest.isPinned(it) }.toSet(), setOf("file", "misc"))
    eq("未归类兜底组常驻", CapabilityManifest.isPinned(CapabilityManifest.MISC), true)
    eq("file 组常驻", CapabilityManifest.isPinned("file"), true)
    eq("ui 组不常驻", CapabilityManifest.isPinned("ui"), false)
    // P3-3：MCP 必须独立成组且**不常驻**。修前 `mcp_` 前缀推不出组 → 落进 misc（常驻），
    // 于是连上几个服务器就有十几个外部工具塞进每一轮工具表，而组索引里看不到「mcp」。
    eq("mcp 组存在且动态", CapabilityManifest.isDynamic("mcp"), true)
    eq("mcp 组不常驻", CapabilityManifest.isPinned("mcp"), false)
    eq("mcp 组成员不在清单里（运行时才知道）",
        CapabilityManifest.capabilities.first { it.group == "mcp" }.tools, emptyList<String>())

    // —— 2. 归组规则 ——
    println("\n[2] 归组规则（补丁 → 前缀 → misc）")
    eq("read_file → file", CapabilityManifest.groupOf("read_file"), "file")
    eq("ask_user → ui", CapabilityManifest.groupOf("ask_user"), "ui")
    eq("http_request → net（前缀推不出，靠清单）", CapabilityManifest.groupOf("http_request"), "net")
    eq("speak → media（前缀推不出，靠清单）", CapabilityManifest.groupOf("speak"), "media")
    eq("shell_run → shell", CapabilityManifest.groupOf("shell_run"), "shell")
    eq("github_commit → github", CapabilityManifest.groupOf("github_commit"), "github")
    // W-RT6：knowledge_search 进清单，56 → 57。这个数字是**刻意写死**的——
    // 新增工具忘了登记清单，groupOf 会安静地把它归到 misc，模型看到的组说明就不对。
    eq("清单收录全部静态工具", CapabilityManifest.toolNames.size, 57)
    eq("mcp_demo_create_issue → mcp（前缀归组，不进 misc）",
        CapabilityManifest.groupOf("mcp_demo_create_issue"), "mcp")
    eq("weather 推不出来 → misc", CapabilityManifest.groupOf("weather"), "misc")
    check("说明文案不等于组名", CapabilityManifest.noteOf("ui") != "ui", CapabilityManifest.noteOf("ui"))

    // —— 3. KernelExposure 与清单同源（委托而不是各写一份） ——
    println("\n[3] KernelExposure 委托一致")
    // 3.5 拆模块：工具类分布在 :app（tools）与 :core（skill 的 use_skill 等），合并两个根
    val names = (realToolNames(src) + realToolNames(coreSrc)).distinct().sorted()
    check("扫到真实工具名", names.size >= 40, "共 ${names.size} 个")
    val mismatch = names.filter { KernelExposure.groupOf(it) != CapabilityManifest.groupOf(it) }
    check("groupOf 与清单逐名一致", mismatch.isEmpty(), mismatch.take(3).joinToString())
    check(
        "isPinned 与清单一致",
        listOf("file", "misc", "ui", "shell").all { KernelExposure.isPinned(it) == CapabilityManifest.isPinned(it) }
    )
    check("noteOf 与清单一致", KernelExposure.noteOf("ui") == CapabilityManifest.noteOf("ui"))

    // —— 4. 正向：真实工具集审计必须零问题 ——
    println("\n[4] 真实工具集审计（${names.size} 个）")
    val live = CapabilityManifest.audit(names.map { stub(it) })
    check("零问题", live.ok, if (live.ok) "" else live.problems.joinToString("；"))
    check("空着的 misc 不算空组（兜底桶）", live.problems.none { it.contains("`misc`") })

    // —— 5. 反向：四种不同步都要被抓到，且指出是谁 ——
    println("\n[5] 反向（每条断言都能单独失败）")
    val unknown = CapabilityManifest.audit(names.map { stub(it) } + stub("demo_widget"))
    check(
        "① 新工具未登记 → 被抓",
        unknown.problems.any { it.contains("demo_widget") },
        unknown.problems.firstOrNull().orEmpty()
    )

    val noWeb = CapabilityManifest.audit(names.filterNot { it.startsWith("web_") }.map { stub(it) })
    val webProblems = noWeb.problems.filter { it.contains("`web_") }
    check(
        "② 整组工具删光 → 逐个点名（不是只报一句空组）",
        webProblems.size == 6 && webProblems.all { it.contains("没有对应实现") },
        "命中 ${webProblems.size} 条"
    )

    // 验收口径原文：「删一个工具不改清单 → harness 红」。这里就是那个场景。
    val noSpeak = CapabilityManifest.audit(names.filterNot { it == "speak" }.map { stub(it) })
    check(
        "③ 删了工具没改清单 → 被抓",
        noSpeak.problems.any { it.contains("`speak`") },
        noSpeak.problems.firstOrNull().orEmpty()
    )

    val noFile = CapabilityManifest.audit(
        names.filterNot { it in setOf("read_file", "write_file", "edit_file") }.map { stub(it) }
    )
    check(
        "④ 常驻组工具全删 → 三个都点名",
        listOf("read_file", "write_file", "edit_file").all { n ->
            noFile.problems.any { it.contains("`$n`") }
        },
        noFile.problems.size.toString()
    )

    // —— 5b. 动态组：今天的写法对，明天改一行就会错的规则 ——
    println("\n[5b] 动态组自洽")
    val mcpStub = stub("mcp_demo_ping")
    // ① 放行动态成员：真实场景就是「服务器连上后 registry 里多出 mcp_*」，
    //    审计若把它报成「未登记」，启动日志每次都会带一串假问题，真问题就被淹了。
    val withMcp = CapabilityManifest.audit(names.map { stub(it) } + mcpStub)
    check("① 动态组成员不报「未登记」",
        withMcp.problems.none { it.contains("mcp_demo_ping") },
        withMcp.problems.firstOrNull().orEmpty())

    // ⑥ 手写成员 → 抓：这类名单连不上服务器时就全是死名字
    val handwritten = CapabilityManifest.capabilities.map {
        if (it.group == "mcp") it.copy(tools = listOf("mcp_demo_ping")) else it
    }
    val hw = CapabilityManifest.audit(handwritten, names.map { stub(it) } + mcpStub)
    check("⑥ 动态组手写成员 → 被抓", hw.problems.any { it.contains("手写了") },
        hw.problems.firstOrNull().orEmpty())

    // ⑥ 标成常驻 → 抓：空组占淘汰名额，且模型永远不知道为什么有一组是空的
    val pinnedMcp = CapabilityManifest.capabilities.map {
        if (it.group == "mcp") it.copy(pinned = true) else it
    }
    val pm = CapabilityManifest.audit(pinnedMcp, names.map { stub(it) } + mcpStub)
    check("⑥ 动态组标常驻 → 被抓", pm.problems.any { it.contains("被标成常驻") },
        pm.problems.firstOrNull { it.contains("被标成常驻") }.orEmpty())

    // —— 6. 单点纪律（静态） ——
    println("\n[6] 单点（静态）")
    val exposure = File(coreSrc, "com/beichen/polaris/core/KernelExposure.kt").readText(Charsets.UTF_8)
    check("KernelExposure 不再自持分组表", !exposure.contains("GROUP_NOTES =") && !exposure.contains("GROUP_PATCH ="))
    check("KernelExposure 的 groupOf 委托给清单", exposure.contains("CapabilityManifest.groupOf("))

    // P5-1 起自检搬进 core/capability（UI 层不该做能力自检），所以这里锚三处：
    // 宿主有没有调用、实现有没有真的跑 audit、调用点是否在 MCP 注册之前。
    val vm = File(src, "com/beichen/polaris/ui/ChatViewModel.kt").readText(Charsets.UTF_8)
    val reporter = File(coreSrc, "com/beichen/polaris/core/capability/CapabilityAuditReporter.kt")
        .readText(Charsets.UTF_8)
    val auditAt = vm.indexOf("CapabilityAuditReporter.report(")
    // R-07 起 MCP 工具同步改名为 refreshMcpTools()（注册/撤销统一在 McpToolSync 里决策）。
    // 锚点带 8 空格缩进 + 前导换行：定义行是 `private fun refreshMcpTools() {`，不能被误命中。
    val mcpAt = vm.indexOf("\n        refreshMcpTools()")
    check("启动时跑一次审计", auditAt > 0)
    check("审计在 MCP 注册之前", auditAt in 1 until mcpAt, "audit=$auditAt mcp=$mcpAt")
    check("自检实现在 core（不在 UI 层）", reporter.indexOf("CapabilityManifest.audit(tools)") >= 0)
    check("McpToolAdapter 强制审批（外部工具不得免审）",
        File(coreSrc, "com/beichen/polaris/core/mcp/McpToolAdapter.kt")
            .readText(Charsets.UTF_8).contains("override val needsApproval: Boolean = true"))

    println("\n==== 结果：PASS=$pass FAIL=${fails.size} ====")
    if (fails.isNotEmpty()) {
        println("失败项：")
        fails.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
