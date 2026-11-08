#!/usr/bin/env kotlin
/**
 * 暴露实现层 harness：验证 [com.beichen.polaris.core.KernelExposure]。
 *
 * 验**接上真实工具之后的效果**——用仓库里实际存在的工具名，量「发给 LLM 的工具表到底
 * 省了多少」，并确认元工具真的能把裁掉的工具捞回来、粘性激活与滞回淘汰按预期工作。
 *
 * （原先还有一份 `KernelCoreVerification` 验内核那套未接入的规则实现；那套实现已删，
 * 它里面唯一有价值的「提示分段稳定性」断言已并入本文件的第 8 段（提示分段）。）
 *
 * 运行：bash scripts/qa/run-kernel-exposure-verification.sh
 *
 * 为什么工具用假实现、名字用真名：名字决定分组与描述体量，是成本的主要来源；
 * execute 的行为与本 harness 无关（这里不测工具本身）。描述按现有工具的实际长度写，
 * 避免「拿一句话的工具去比，把差距算小」。
 */

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.KernelExposure
import com.beichen.polaris.core.PromptAssembler
import com.beichen.polaris.core.Section
import com.beichen.polaris.core.Stability
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.capability.CapabilityManifest
import com.beichen.polaris.core.llm.local.LocalAgentPrompt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/** 仓库里实际注册的 57 个工具名（2026-11-08 补 knowledge_search，其余同 10-30 全量提取）。 */
private val NAMES = listOf(
    "app_install", "app_launch", "app_list", "app_stop", "app_uninstall",
    "ask_user", "calculator", "clipboard", "container_toolchain", "conversation_search",
    "device_foreground_app", "device_notifications", "edit_file", "github_clone", "github_commit",
    "github_diff", "github_log", "github_status", "http_request", "knowledge_search", "load_skill",
    "memory_forget", "memory_list", "memory_search", "memory_write", "ocr_image",
    "plan_propose", "read_file", "send_notification", "shell_background", "shell_close",
    "shell_jobs", "shell_run", "spawn_agents", "speak", "todo_read",
    "todo_write", "ui_click", "ui_input", "ui_key", "ui_long_press",
    "ui_screenshot", "ui_swipe", "ui_tap", "ui_tree", "use_skill",
    "web_click", "web_eval", "web_open", "web_search", "web_snapshot",
    "web_type", "workflow_create", "workflow_delete", "workflow_list", "workflow_toggle",
    "write_file",
    // 以下两个刻意用「推不出分组的前缀」，模拟插件 / 用户新增 / 第三方注册的工具。
    // 起因是 T01 harness 用 weather/wordcount 测主循环，被分组裁剪后模型看到「未知工具」。
    // 「我们不认识它」不等于「它不该可见」，所以 misc 必须常驻——这两条就是那条规则的哨兵。
    "weather", "wordcount"
)

private fun fakeTools(): List<AgentTool> = NAMES.map { n ->
    object : AgentTool {
        override val name: String = n
        override val description: String = when (n) {
            // 这三条按仓库里的真实 description 写，用于验证「按中文关键词能搜到英文工具名」
            "ui_screenshot" -> "截取当前屏幕画面，返回图片供视觉模型查看。需要先确认无障碍权限。"
            "read_file" -> "读取指定路径的文本文件内容。路径必须是绝对路径或工作区相对路径。"
            "memory_write" -> "把一条事实写入长期记忆，跨会话可用。重复写入会覆盖同键旧值。"
            else -> "$n：执行 $n 相关操作。适用于需要 $n 的场景；调用前请确认参数有效，" +
                "返回结果可能是文本或结构化数据，失败时会给出原因与重试建议。避免在高频循环中重复调用。"
        }
        override val parameters: JsonSchema = JsonSchema(
            type = "object",
            properties = mapOf(
                "target" to JsonSchema.Property("string", "目标对象"),
                "mode" to JsonSchema.Property("string", "执行模式"),
                "timeout" to JsonSchema.Property("int", "超时毫秒")
            ),
            required = listOf("target")
        )
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult.ok(message = "ok")
    }
}

/**
 * 手写 JsonSchema → JSON 串。
 *
 * 不用 `JsonSchema.serializer()`：那需要 kotlinx-serialization 编译器插件，而 harness 的
 * 编译单元刻意只走标准 kotlinc（离线可跑、不依赖 Gradle 插件链）。字段顺序与官方序列化一致，
 * 长度差异 < 2%，用来量 token 足够。
 */
private fun schemaJson(s: JsonSchema): String = buildString {
    append("""{"type":""" + "\"" + s.type + "\",\"properties\":{")
    append(s.properties.entries.joinToString(",") { (k, p) ->
        "\"" + k + "\":{\"type\":\"" + p.type + "\",\"description\":\"" + p.description + "\"}"
    })
    append("},\"required\":[")
    append(s.required.joinToString(",") { "\"" + it + "\"" })
    append("]}")
}

/**
 * 估算「这批工具发给 LLM 要多少字符」：按 OpenAI function-calling 的报文结构拼一遍。
 *
 * 不直接用 `ToolRegistry.descriptors()` 是因为那样只算 schema、漏掉 name/description，
 * 而 description 恰恰是工具表里最长的部分（每个约 100 字）。
 */
private fun payloadText(tools: List<AgentTool>): String = buildString {
    for (t in tools) {
        append("{\"type\":\"function\",\"function\":{\"name\":\"")
        append(t.name)
        append("\",\"description\":\"")
        append(t.description)
        append("\",\"parameters\":")
        append(schemaJson(t.parameters))
        append("}}")
    }
}

private fun payloadChars(tools: List<AgentTool>): Int = payloadText(tools).length

/**
 * token 估算，口径与 `ContextBudget.estimateTokens` 一致：CJK 1 字 1 token、
 * ASCII 4 字 1 token、其余 2 字 1 token。
 *
 * **早前报表写的是 `字符 / 2`。** 那个系数对纯中文描述会低估约一倍，对纯 ASCII 的
 * schema 样板会高估约一倍；工具表恰好是「中文描述 + 英文 JSON 结构」的混合，两个误差
 * 互相抵消，所以结果看着还算接近（22167 字符：粗除得 11083，按口径算 9063）。
 * 但这是巧合，不是正确性——工具描述一旦全写成英文，粗除 2 就会高估一倍。
 * 既然要拿这个数去和真机 usage 对账，就别赌巧合。
 */
private fun estTokens(s: String): Int {
    var cjk = 0; var ascii = 0; var other = 0
    for (ch in s) {
        val c = ch.code
        when {
            c < 0x80 -> ascii++
            c in 0x3000..0x30FF || c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF ||
                c in 0xAC00..0xD7AF || c in 0xFF00..0xFFEF -> cjk++
            else -> other++
        }
    }
    return cjk + (ascii + 3) / 4 + (other + 1) / 2
}

fun main() = runBlocking {
    println("=== 暴露实现层自检（57 个真实工具名 + 2 个哨兵）===")
    println("    注：本表用**假描述**（每个约 90 字）验分组规则，因此体量只能横向比较、")
    println("    不能当真实成本。真实工具的描述长短差 6 倍（web_type 30 字 / ui_screenshot 180 字），")
    println("    真实数字见 `python3 scripts/tool-surface-audit.py`。")
    val all = fakeTools()

    // ---------- 1. 分组 ----------
    println("\n[1] 分组：每个工具都必须有组，不能有工具消失")
    val groups = KernelExposure.groupsOf(all)
    println("    " + groups.joinToString("  ") { "${it.first}:${it.second}" })
    eq("分组覆盖全部工具", groups.sumOf { it.second }, all.size)
    val misc = groups.firstOrNull { it.first == "misc" }?.second ?: 0
    eq("无前缀工具归入 misc", misc, 2)
    eq("read_file 归 file", KernelExposure.groupOf("read_file"), "file")
    eq("ui_tap 归 ui", KernelExposure.groupOf("ui_tap"), "ui")
    eq("github_clone 归 github", KernelExposure.groupOf("github_clone"), "github")
    eq("没见过的名字也能归组", KernelExposure.groupOf("never_seen_tool"), "misc")

    // ---------- 2. 三档工具表体量 ----------
    println("\n[2] 三档的工具表体量（真实报文结构）")
    val fullPlan = KernelExposure.plan(all, "openai", forceFull = true, active = mutableSetOf())
    val cloudPlan = KernelExposure.plan(all, "openai", forceFull = false, active = mutableSetOf())
    val localPlan = KernelExposure.plan(all, "local-llama", forceFull = false, active = mutableSetOf())

    val tFull = payloadText(fullPlan.visible)
    val tCloud = payloadText(cloudPlan.visible)
    val tLocal = payloadText(localPlan.visible)
    val cFull = tFull.length
    val cCloud = tCloud.length
    val cLocal = tLocal.length
    val kFull = estTokens(tFull)
    val kCloud = estTokens(tCloud)
    val kLocal = estTokens(tLocal)
    println("    全量         ${fullPlan.visible.size.toString().padStart(2)} 个 → $cFull 字符 ≈ $kFull token")
    println("    云端·分组    ${cloudPlan.visible.size.toString().padStart(2)} 个 → $cCloud 字符 ≈ $kCloud token（省 ${100 - cCloud * 100 / cFull}%）")
    println("    本地·分组    ${localPlan.visible.size.toString().padStart(2)} 个 → $cLocal 字符 ≈ $kLocal token（省 ${100 - cLocal * 100 / cFull}%）")
    // 口径断言：token 必须落在「字符的 1/4 ~ 1 倍」之间。
    // 早前是 `字符 / 2`——对纯中文低估一倍、对纯 ASCII 高估一倍，工具表恰好混合才凑巧接近。
    check("token 估算落在合理区间（字符的 1/4 ~ 1 倍）", kFull in (cFull / 4)..cFull, "字符 $cFull / token $kFull")

    eq("forceFull 就是全量", fullPlan.visible.size, all.size)
    check("云端默认不是全量", cloudPlan.visible.size < all.size)
    check("云端至少省 1/3", cCloud * 3 < cFull * 2, "$cCloud vs $cFull")
    check("本地比全量省 2/3 以上", cLocal * 3 < cFull, "$cLocal vs $cFull")
    // W-102：云端默认只剩 file（+ 兜底 misc）。原先给 file/ui/skill 三组，
    // 实测 ui 9 个工具占工具表近一半，而绝大多数对话一条都用不到。
    check("云端默认只激活 file（+misc）", cloudPlan.activated - "misc" == setOf("file"), "${cloudPlan.activated}")
    check("元工具总在末尾", cloudPlan.visible.takeLast(2).map { it.name }.toSet() == setOf(KernelExposure.USE_TOOLS, KernelExposure.FIND_TOOLS))

    // ---------- 2b. 外部 MCP 工具：独立成组、默认不激活 ----------
    println("\n[2b] MCP：外部工具不占常驻名额")
    val mcpStub = object : AgentTool {
        override val name: String = "mcp_demo_ping"
        override val description: String = "外部 MCP 服务器提供的工具，每次调用都需要确认。"
        override val parameters: JsonSchema = JsonSchema()
        override suspend fun execute(args: JsonObject): ToolResult = ToolResult.ok(message = "ok")
    }
    val withMcp = all + mcpStub
    // 修前：`mcp_` 前缀推不出组 → misc（常驻）→ 每一轮都把外部工具塞进工具表。
    eq("mcp_ 前缀归 mcp 组（不进 misc）", KernelExposure.groupOf("mcp_demo_ping"), "mcp")
    eq("mcp 组不常驻", KernelExposure.isPinned("mcp"), false)

    val mcpActive = mutableSetOf<String>()
    val mcpOff = KernelExposure.plan(withMcp, "openai", false, mcpActive)
    check("默认不可见（必须显式开组）", mcpOff.visible.none { it.name == "mcp_demo_ping" })
    check("默认不激活", "mcp" !in mcpOff.activated, mcpOff.activated.toString())
    // 组索引里要能看到它——不然模型连「有这么一组」都不知道，永远不会去开
    check("组索引里能看到 mcp", KernelExposure.groupsOf(withMcp).any { it.first == "mcp" })

    mcpActive.add("mcp")
    val mcpOn = KernelExposure.plan(withMcp, "openai", false, mcpActive)
    check("开组后可见", mcpOn.visible.any { it.name == "mcp_demo_ping" })

    // 断连：服务器没了，工具从注册表消失 → 残留的组名必须被清掉，
    // 否则它会顶掉一个淘汰名额，把别的功能组挤出去。
    val staleMcp = mutableSetOf("mcp")
    KernelExposure.plan(all, "openai", false, staleMcp)
    check("MCP 断连后残留的 mcp 组被清掉", "mcp" !in staleMcp, staleMcp.toString())

    // ---------- 3. 元工具：找回被裁掉的工具 ----------
    println("\n[3] 元工具：裁掉不等于禁用")
    val active = mutableSetOf<String>()
    val before = KernelExposure.plan(all, "openai", false, active)
    check("memory 组初始不可见", before.visible.none { it.name == "memory_write" })

    val useTool = before.visible.first { it.name == KernelExposure.USE_TOOLS }
    val useOut = useTool.execute(buildJsonObject { put("groups", "memory,workflow") })
    check("__use_tools 成功", useOut.ok, useOut.message.take(60))
    val after = KernelExposure.plan(all, "openai", false, active)
    check("启用后 memory_write 可见", after.visible.any { it.name == "memory_write" })
    check("启用后 workflow_list 可见", after.visible.any { it.name == "workflow_list" })
    check("启用后工具表变大但没到全量", after.visible.size > before.visible.size && after.visible.size < all.size)
    println("    memory+workflow 后：${after.visible.size} 个 → ${payloadChars(after.visible)} 字符")

    val findTool = after.visible.first { it.name == KernelExposure.FIND_TOOLS }
    val findOut = findTool.execute(buildJsonObject { put("query", "屏幕") })
    check("__find_tools 按中文描述命中", findOut.ok && findOut.message.contains("ui_screenshot"), findOut.message.take(60))

    // 口语查询（「截图」）与描述（「截取当前屏幕画面」）不是子串关系：不报错，退化为全量清单
    val loose = findTool.execute(buildJsonObject { put("query", "截图") })
    check("找不到时不报错而是给全量清单", loose.ok && loose.code == "find_fallback", loose.message.take(50))
    check("全量清单里含被裁掉的工具名", loose.message.contains("memory_write"))

    val badUse = useTool.execute(buildJsonObject { put("groups", "不存在组") })
    check("未知组失败", !badUse.ok)
    check("未知组给可用清单", badUse.hint?.contains("可用分组") == true)

    val noQuery = findTool.execute(buildJsonObject { })
    check("缺参数失败并给 hint", !noQuery.ok && !noQuery.hint.isNullOrBlank())

    // ---------- 4. 安全侧 ----------
    println("\n[4] 边界")
    eq("元工具是只读的", cloudPlan.visible.filter { it.name.startsWith("__") }.all { it.readOnly }, true)
    check("元工具不需要审批", cloudPlan.visible.filter { it.name.startsWith("__") }.none { it.needsApproval })
    eq("本地通道走 META", localPlan.mode.name, "META")
    check(
        "本地预激活 = file 组 + 常驻的 misc",
        localPlan.activated == setOf("file", "misc"), "${localPlan.activated}"
    )
    // W-102：云端默认面收到只剩 file 之后，与端侧的初始工具面已经一致；
    // 两者的差别只剩 mode（本地 META 更强调「先查再开」）。
    eq("W-102：本地与云端默认工具面一致", cLocal, cCloud)

    // 哨兵：不认识的工具必须默认可见（无论是云端还是端侧）。
    // 这两条对应一个真实踩过的坑：T01 用 weather/wordcount 自定义工具测主循环，
    // 分组裁剪把它们藏起来后，主循环收到的是「未知工具 weather」，端到端断言全红。
    for (case in listOf("云端" to cloudPlan, "本地" to localPlan)) {
        val label = case.first
        val plan = case.second
        val visibleNames = plan.visible.map { it.name }
        check("$label：无前缀工具 weather 默认可见", "weather" in visibleNames)
        check("$label：无前缀工具 wordcount 默认可见", "wordcount" in visibleNames)
    }
    eq("云端走 GROUPED", cloudPlan.mode.name, "GROUPED")
    eq("强制全量走 FULL", fullPlan.mode.name, "FULL")

    // ---------- 5. 粘性激活与滞回淘汰（W-102）----------
    println("\n[5] 粘性激活：开过就留着，撞上限才淘汰")
    val sticky = linkedSetOf<String>()
    KernelExposure.plan(all, "openai", false, sticky)
    check("种子只有核心组", sticky == setOf("file", "misc"), "$sticky")

    fun useGroup(g: String) {
        runBlocking {
            KernelExposure.plan(all, "openai", false, sticky)
                .visible.first { it.name == KernelExposure.USE_TOOLS }
                .execute(buildJsonObject { put("groups", g) })
        }
    }

    // 一次开两个组：参数顺序必须等于 LRU 顺序（先写的更旧、后写的更新）。
    // 这里锁的是一个真 bug：原写法先 addAll 再逐个 remove+add，对「本来不在集合里」
    // 的组是空操作，新组停在 addAll 给的位置，得到 {misc,ui,memory}——与语义相反，
    // 第一次淘汰会把刚开的 memory 砍掉、留下更早的 ui。
    val pair = linkedSetOf<String>()
    runBlocking {
        KernelExposure.plan(all, "openai", false, pair)
            .visible.first { it.name == KernelExposure.USE_TOOLS }
            .execute(buildJsonObject { put("groups", "memory,ui") })
    }
    eq(
        "一次开两组：迭代序 = 参数顺序（memory 旧、ui 新）",
        pair.toList(), listOf("file", "misc", "memory", "ui")
    )

    useGroup("memory")
    check("开 1 组后共 3 个（file+misc+memory）", sticky.size == 3, "$sticky")
    useGroup("ui")
    check("开 2 个可淘汰组仍在限内（不动手）", sticky.size == 4, "$sticky")
    useGroup("shell")
    check(
        "第 3 个可淘汰组触发滞回：降到 1 个",
        sticky.count { it != "file" && it != "misc" } == 1, "$sticky"
    )
    check("最后开的 shell 被保留（先开的 memory/ui 被挤掉）", "shell" in sticky, "$sticky")

    // LRU：重新启用旧组 = 移回尾部，撞上限时淘汰的是别人
    val lru = linkedSetOf("file", "misc", "memory", "ui", "workflow")
    lru.remove("memory"); lru.add("memory")
    val evicted = KernelExposure.enforceCap(lru)
    eq("淘汰最久未用的两个", evicted, listOf("ui", "workflow"))
    check("刚重开（移回尾部）的 memory 留下", lru == setOf("file", "misc", "memory"), "$lru")
    eq(
        "未超限时不动手",
        KernelExposure.enforceCap(linkedSetOf("file", "misc", "ui")),
        emptyList<String>()
    )

    // 持久化遗留：落盘读回的组名可能在代码改版后已不存在（组被删/合并）。
    // 不清理的话它们会一直占着上限名额，让 enforceCap 每轮误判超限、反复淘汰在用的组。
    val stale = linkedSetOf("file", "misc", "gone_group", "ui")
    KernelExposure.plan(all, "openai", false, stale)
    check("失效组名被清理掉", "gone_group" !in stale, "$stale")
    check("清理不误伤在用组", "ui" in stale && "file" in stale, "$stale")

    // ---------- 6. find_tools 兜底必须标出「哪些能直接调」----------
    //
    // 兜底会把全部工具名列出来。只列名字而不标启用状态，模型看到就会直接调一个
    // 当前不可见的工具，吃一次 unknown_tool，再重试一轮——正好是这轮改造想省掉的钱。
    val fbActive = linkedSetOf<String>()
    KernelExposure.plan(all, "openai", false, fbActive)
    val fbMsg = runBlocking {
        KernelExposure.plan(all, "openai", false, fbActive)
            .visible.first { it.name == KernelExposure.FIND_TOOLS }
            .execute(buildJsonObject { put("query", "zzz_no_such_tool") })
    }.message
    check("兜底列出全部工具", fbMsg.contains("read_file") && fbMsg.contains("ui_swipe"), fbMsg.take(80))
    check("已启用的组带 [已启用] 标记", fbMsg.contains("file [已启用]"), fbMsg.lines().firstOrNull { "file" in it } ?: "")
    check(
        "未启用的组不带标记（避免模型直接调）",
        fbMsg.contains("ui：") && !fbMsg.contains("ui [已启用]"),
        fbMsg.lines().firstOrNull { "ui" in it } ?: ""
    )

    // ---------- 7. 设置页要用的元信息 ----------
    //
    // 设置页「工具范围」要把「哪些组能关、每个组是干什么的」显示给用户。这些判断必须
    // 和内核同源（都在 KernelExposure 里），不能在 UI 里再写一份组名表——两份真相
    // 迟早会分叉，表现是「UI 上能关、关了下一轮又回来了」。
    println("\n[7] 设置页元信息：哪些组能关、每个组是什么")
    check("file 常驻（不给关闭入口）", KernelExposure.isPinned("file"))
    check("misc 常驻（不认识的东西不能藏，也不该让用户关）", KernelExposure.isPinned("misc"))
    check("ui 可关闭", !KernelExposure.isPinned("ui"))
    check("shell 可关闭", !KernelExposure.isPinned("shell"))
    check("组说明来自同一张表", KernelExposure.noteOf("shell").contains("命令行"), KernelExposure.noteOf("shell"))
    eq("未知组的说明回退成组名", KernelExposure.noteOf("zzz"), "zzz")

    // ---------- 8. 提示分段：稳定前缀是否真的稳定 ----------
    //
    // 原在 KernelCoreVerification（已随 `core/kernel/` 那套实现一起删除），搬过来是因为
    // PromptAssembler 仍在主链路上——RealAgentLoop 用它拼系统提示，串了就会每轮 cache miss。
    println("\n[8] 提示分段：TURN 段变化不拖累 FROZEN/SESSION")
    val base = listOf(
        Section("identity", "你是北辰", Stability.FROZEN),
        Section("rules", "规则一……", Stability.FROZEN),
        Section("persona", "人格设定", Stability.SESSION)
    )
    val p1 = PromptAssembler.assemble(base + Section("turn", "时间 10:00 计划 A", Stability.TURN))
    val p2 = PromptAssembler.assemble(base + Section("turn", "时间 10:05 计划 B", Stability.TURN))
    val p3 = PromptAssembler.assemble(
        listOf(Section("identity", "你是北辰（改）", Stability.FROZEN)) + base.drop(1) +
            Section("turn", "时间 10:00 计划 A", Stability.TURN)
    )
    check("TURN 变化不影响前缀指纹", p1.signature == p2.signature, p1.signature)
    eq("TURN 变化不影响稳定前缀长度", p1.frozenChars, p2.frozenChars)
    check("FROZEN 变化会改变指纹（该失效就失效）", p3.signature != p1.signature, p3.signature)
    check("TURN 段排在最后", p2.text.endsWith("计划 B"), p2.text.takeLast(14))
    check("空段不留多余空行", !p1.text.contains("\n\n\n"), "")
    val withBlank = PromptAssembler.assemble(
        listOf(Section("a", "A", Stability.FROZEN), Section("b", "   ", Stability.FROZEN))
    )
    eq("空白段不产生额外换行", "A", withBlank.text)

    // ---------- 9. 工具表顺序 = 清单顺序（P3-5） ----------
    //
    // 顺序是请求体的一部分：它一变，KV 前缀就失效（云端按量重算、本地整段 prefill）。
    // 修前有两个互不相干的顺序来源——云端看 `ToolRegistry` 的注册顺序，本地看
    // `LocalAgentPrompt.ORDER` 那张手写表。注册顺序那条没有任何防线：`register(...)`
    // 里插一行，每轮报文整体位移，却没人会发现。这一节把它钉在清单上。
    println("\n[9] 工具表顺序 = 清单顺序（注册顺序不再影响报文）")
    val manifestOrder = CapabilityManifest.toolNames
    val cloudNames = cloudPlan.names

    check(
        "全量模式下已登记工具严格按清单顺序",
        fullPlan.names.filter { it in manifestOrder } == manifestOrder.filter { it in fullPlan.names },
        "前 3 个：${fullPlan.names.take(3)}"
    )
    // 输入是字母序的 NAMES，清单是「先文件、再屏幕、再网络…」的语义序：
    // 两者不同才说明真的排过序，否则这条断言在「没排序」的实现上也会绿。
    check(
        "确实重排了（不是照抄入参序）",
        fullPlan.names.filter { it in manifestOrder } != all.map { it.name },
        "入参首项=${all.first().name} 输出首项=${cloudNames.first()}"
    )

    // 注册顺序由 `ChatViewModel` 里 40 行 register 决定，今天偶然稳定，明天就可能变。
    // 打乱两次必须得到同一张表——这是「顺序的拥有者不是注册表」的可执行定义。
    val shuffledA = all.shuffled(kotlin.random.Random(1))
    val shuffledB = all.reversed()
    val planA = KernelExposure.plan(shuffledA, "openai", forceFull = true, active = mutableSetOf())
    val planB = KernelExposure.plan(shuffledB, "openai", forceFull = true, active = mutableSetOf())
    check("打乱注册顺序后工具表逐字节相同", planA.names == planB.names, "A 首项=${planA.names.first()} B 首项=${planB.names.first()}")
    check("与基准全量表的顺序相同", planA.names == fullPlan.names)

    check("元工具在最后两位", cloudNames.takeLast(2).toSet() == setOf(KernelExposure.USE_TOOLS, KernelExposure.FIND_TOOLS), "${cloudNames.takeLast(3)}")
    // 默认路径（云端只开 file）必须零位移：这三个名字在旧实现里恰好也是注册顺序，
    // 所以这次统一顺序**不会**让日常对话的报文变一个字节，只有全量/多组场景才有位移。
    // weather / wordcount 是 misc 的哨兵（常驻），元工具单列，都不算默认路径的成员。
    check(
        "默认云端路径零位移（read → write → edit）",
        cloudNames.filterNot { it.startsWith("__") || it == "weather" || it == "wordcount" } ==
            listOf("read_file", "write_file", "edit_file"),
        "$cloudNames"
    )
    check(
        "未登记工具排在已登记工具之后、元工具之前",
        run {
            val lastRanked = cloudNames.indexOfLast { it in manifestOrder }
            val firstUnranked = cloudNames.indexOfFirst { it !in manifestOrder && !it.startsWith("__") }
            firstUnranked < 0 || lastRanked < firstUnranked
        },
        "weather=${cloudNames.indexOf("weather")} 末个已登记=${cloudNames.indexOfLast { it in manifestOrder }}"
    )

    // 本地提示词：同一份顺序，行为级验证（此前只有 grep，没有真跑过 build()）
    val promptA = LocalAgentPrompt.build(shuffledA)
    val promptB = LocalAgentPrompt.build(shuffledB)
    check("提示词在乱序输入下逐字节相同", promptA == promptB, "${promptA.length} 字符")
    // 只取「- name(」开头的行：用正则匹配行首，而不是对行做子串判断
    val lineHead = Regex("""^- ([a-z0-9_]+)\(""")
    val promptLines = promptA.lines().mapNotNull { lineHead.find(it)?.groupValues?.get(1) }
    // 无前缀的两个哨兵（weather / wordcount）不在清单里，排在最后，所以只比对已登记的部分，
    // 再单独确认未登记的那两个确实在末尾。
    check(
        "提示词行序与清单顺序一致",
        promptLines.filter { it in manifestOrder } == manifestOrder.filter { it in promptLines },
        "前 4 行：${promptLines.take(4)}"
    )
    check(
        "未登记工具排在提示词末尾",
        promptLines.takeLast(2).toSet() == setOf("weather", "wordcount"),
        "${promptLines.takeLast(3)}"
    )
    check("提示词行数 = 工具数（每个工具恰好一行）", promptLines.size == all.size, "${promptLines.size} 行 / ${all.size} 个工具")
    // 每轮都要拼一次，且它决定本地模型的 prefill 长度：格式改动要显式看见。
    check(
        "单行格式稳定（name(必填, 可选?) 后接一句话）",
        promptA.lines().first { it.startsWith("- read_file(") }.let {
            it.startsWith("- read_file(target, mode?, timeout?): read a workspace file as text")
        },
        promptA.lines().firstOrNull { it.startsWith("- read_file(") } ?: "缺 read_file 行"
    )

    // ---------- 10. 清单 → 实现（静态） ----------
    //
    // `CapabilityManifest.audit` 的第 ② 条也在查这个，但它跑在**运行期**、拿的是注册表：
    // 只有 App 启动过一次才知道缺谁，且只 WARN。这条在**编译前**用源码查同一件事——
    // 清单是发给模型的工具表，写错一个名字的后果是模型照着调用、拿到 unknown_tool，
    // 更糟的是模糊匹配会把它指到一个不相干的工具上（见 CapabilityManifest 的注释）。
    //
    // 扫 `override val name = "xxx"` 而不是按类名走：工具的继承链有四五种写法
    // （`class X(...) : AgentTool`、多行构造参数、object、匿名实现），按类名切会漏，
    // 而漏掉的正好是「清单里名字最长的那些」（WriteFileTool 就是这样漏的）。
    // 3.5 拆模块：工具实现分布在 :app（tools）与 :core（skill 的 use_skill 等），两个根都要扫
    val implNames: Set<String> = listOf(
        "app/src/main/java/com/beichen/polaris",
        "core/src/main/java/com/beichen/polaris"
    ).asSequence()
        .flatMap { root ->
            File(root).walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { f ->
                    Regex("""override val name(?::\s*String)?\s*=\s*"([a-z0-9_]+)"""")
                        .findAll(f.readText()).map { it.groupValues[1] }.asSequence()
                }
        }
        .toSet()
    // 自检：正则失效时集合会变空，「全部存在」就成了空话。
    check("扫到足够多的工具实现（当前 ${implNames.size} 个）", implNames.size >= 40)
    val noImpl = CapabilityManifest.toolNames.filter { it !in implNames }
    check("清单里每个工具都有实现（否则模型照清单调用会拿到 unknown_tool）", noImpl.isEmpty(), "缺：$noImpl")
    val noGroup = implNames - CapabilityManifest.toolNames.toSet()
    check("实现了的工具都在清单里（未登记的工具会落进 misc 且组索引看不到）", noGroup.isEmpty(), "未登记：$noGroup")

    // ---------- 11. 结果 ----------

    // 结果行必须含 "0 失败"：run-all.sh 靠这个字样判 PASS，
    // 写成「0 项失败」不匹配它的正则，会被当成「跑完没有通过标记」判 FAIL。
    println("\n=== 结果：$pass 项通过 / " + if (fails.isEmpty()) "0 失败" else "${fails.size} 条断言失败" + " ===")
    if (fails.isNotEmpty()) {
        fails.forEach { println("  失败：$it") }
        kotlin.system.exitProcess(1)
    }
}
