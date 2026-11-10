import com.beichen.polaris.core.agent.ApprovalGrouping
import com.beichen.polaris.core.tool.Capability
import com.beichen.polaris.core.tool.ToolCatalog
import com.beichen.polaris.skill.CapabilityRegistry

/**
 * R-13 行为段：真跑 [ToolCatalog] 及其三个派生视图。
 *
 * 零 Android 依赖、零第三方依赖——编得动、跑得起来，不是源码扫描。
 * 静态段（工具类 ↔ 目录的双向对账）在 `run-r13-catalog-verification.sh` 的 [A]/[B] 段，
 * 那里要扫 56 个工具类，用 shell 比在这里反射更直接。
 */

private var pass = 0
private var fail = 0

/**
 * @param desc 断言本身（PASS / FAIL 都打这一句，描述「期望什么」而不是「哪里错了」）
 * @param detail 失败时追加的事实（实际值），PASS 时不打印
 */
private fun expect(cond: Boolean, desc: String, detail: () -> String = { "" }) {
    if (cond) {
        pass++
        println("  PASS  $desc")
    } else {
        fail++
        println("  FAIL  $desc -> ${detail()}")
    }
}

private fun check(name: String, body: () -> Unit) {
    try {
        body()
    } catch (t: Throwable) {
        // 抓 Throwable 而不是只抓 AssertionError：被测代码抛运行时异常也是一条失败，
        // 放它逃出去会打断整个 harness，连结果行都不打印——runner 会把「没有输出」
        // 当成「没有失败」。（R-19 变异测试 N1 踩过这个坑。）
        fail++
        println("  FAIL  $name -> ${t::class.java.simpleName}: ${t.message}")
    }
}

fun main() {
    println("== R-13 工具元数据单一真源 ==")

    check("C1 目录规模与唯一性") {
        val names = ToolCatalog.toolNames
        expect(names.size >= 50, "目录工具数 ${names.size}（≥50）")
        expect(names.size == names.toSet().size, "目录里没有重名工具", {
            names.groupBy { it }.filterValues { it.size > 1 }.keys.toString()
        })
    }

    check("C2 每个工具都写进了一个已定义的组") {
        val defined = ToolCatalog.groups.map { it.name }.toSet()
        val bad = ToolCatalog.tools.filter { it.group !in defined }
        expect(bad.isEmpty(), "所有工具的组都已定义", { bad.map { it.name + "->" + it.group }.toString() })
    }

    check("C3 组内顺序 = 目录顺序（工具表顺序的唯一来源）") {
        // file 组的顺序是历史顺序，插行会让云端 KV 前缀整体失效。
        val file = ToolCatalog.membersOf("file")
        expect(
            file == listOf("read_file", "write_file", "edit_file"),
            "file 组保持历史顺序 read_file/write_file/edit_file",
            { file.toString() }
        )
        val mismatch = ToolCatalog.groups.filter { g ->
            ToolCatalog.membersOf(g.name) != ToolCatalog.tools.filter { it.group == g.name }.map { it.name }
        }
        expect(mismatch.isEmpty(), "每组成员顺序与目录一致", { mismatch.map { it.name }.toString() })
    }

    check("C4 动态组无静态成员，兜底组为空，其余组非空") {
        expect(ToolCatalog.membersOf("mcp").isEmpty(), "mcp 是动态组，没有静态成员")
        expect(ToolCatalog.membersOf(ToolCatalog.MISC).isEmpty(), "misc 是兜底桶，没有登记成员")
        val empty = ToolCatalog.groups
            .filter { !it.dynamic && it.name != ToolCatalog.MISC }
            .filter { ToolCatalog.membersOf(it.name).isEmpty() }
        expect(empty.isEmpty(), "没有空组（模型按索引开组不该开到空组）", { empty.map { it.name }.toString() })
    }

    check("C5 groupOf：目录命中 / 前缀回落 / 兜底") {
        expect(ToolCatalog.groupOf("read_file") == "file", "read_file 属 file")
        // 不在目录、但前缀是已声明组 → 按前缀归组（MCP 工具的形态）
        expect(ToolCatalog.groupOf("mcp_github_search") == "mcp", "mcp_ 前缀归 mcp")
        // 前缀不认识 → 兜底可见，不能丢
        expect(ToolCatalog.groupOf("zzz_unknown") == ToolCatalog.MISC, "未知前缀归 misc")
        expect(ToolCatalog.groupOf("nosuffix") == ToolCatalog.MISC, "无下划线名归 misc")
    }

    check("C6 能力分级 fail-closed，且已知工具按目录") {
        expect(ToolCatalog.capabilityOf("read_file") == Capability.SAFE, "read_file 是 SAFE")
        expect(ToolCatalog.capabilityOf("shell_run") == Capability.DANGEROUS, "shell_run 是 DANGEROUS")
        expect(ToolCatalog.capabilityOf("ghost_tool") == Capability.DANGEROUS, "未登记工具按 DANGEROUS（fail-closed）")
        // 包装层与目录必须同源
        expect(CapabilityRegistry.cap("ghost_tool") == Capability.DANGEROUS, "CapabilityRegistry 默认也是 DANGEROUS")
        expect(CapabilityRegistry.cap("ui_tap") == Capability.DANGEROUS, "CapabilityRegistry 走目录取值")
        expect("shell_run" in CapabilityRegistry.dangerousTools(), "危险工具清单含 shell_run")
        expect(
            CapabilityRegistry.knownTools == ToolCatalog.toolNames.toSet(),
            "knownTools 与目录完全一致"
        )
    }

    check("C7 审批归组从目录读；未声明的工具回退自身名字") {
        expect(ApprovalGrouping.groupOf("shell_run") == ApprovalGrouping.GROUP_SHELL, "shell_run 归 shell")
        expect(ApprovalGrouping.groupOf("shell_background") == ApprovalGrouping.GROUP_SHELL, "shell_background 与 shell_run 同组")
        expect(ApprovalGrouping.groupOf("ui_tap") == ApprovalGrouping.GROUP_UI_ACTION, "ui_tap 归界面动作")
        expect(ApprovalGrouping.groupOf("ui_tree") == ApprovalGrouping.GROUP_UI_SCREENSHOT, "ui_tree 归屏幕读取")
        // 保守回退：宁可弹窗难看，也不悄悄并进已有组扩大授权
        expect(
            ApprovalGrouping.groupOf("some_new_tool") == "some_new_tool",
            "未声明工具回退自身名字（不并进任何已有组）"
        )
        // MCP 工具名前缀是 mcp_，但**不能**因此并进一个 mcp 审批组：
        // 那会让用户勾一次「不再询问」就放过所有服务器的所有工具——授权范围被悄悄放大。
        // 暴露面分组（ToolCatalog.groupOf 归 mcp）与审批归组是两件事，别合并。
        expect(
            ApprovalGrouping.groupOf("mcp_demo_ping") == "mcp_demo_ping",
            "MCP 动态工具按自身名字单独成组（勾一次不放过全部 MCP 工具）"
        )
    }

    check("C8 审批键取值域封闭") {
        // 闭集：新增审批归组必须在这里显式登记，防止随手写个字符串当键
        // （那样弹窗文案会退化成裸工具名，且用户勾「不再询问」时范围不可预期）。
        val allowed = setOf(
            ToolCatalog.Approval.UI_ACTION, ToolCatalog.Approval.UI_SCREENSHOT,
            ToolCatalog.Approval.APP_MANAGE, ToolCatalog.Approval.FILE_WRITE,
            ToolCatalog.Approval.GIT_WRITE, ToolCatalog.Approval.GIT_READ,
            ToolCatalog.Approval.DEVICE_OUTPUT, ToolCatalog.Approval.SHELL,
            // 2026-11-09 审计修复：`http_request` 纳入审批（F1），按域名记忆。
            ToolCatalog.Approval.NETWORK,
            // 下面两个只由 ApprovalScope 产出（不进目录），但同属审批键的取值域：
            // 提权 shell（F1）与「首词安全但整体不可记忆」的 shell（F2）。
            ToolCatalog.Approval.SHELL_ELEVATED, ToolCatalog.Approval.SHELL_UNMEMORIZABLE,
            // 2026-11-10 F7：工作区外的只读访问（按目录记忆），同样只由 ApprovalScope 产出。
            ToolCatalog.Approval.SHELL_READ,
            // 2026-11-10 F9：读用户私有数据（剪贴板 / 通知）。它进目录（clipboard、
            // device_notifications 两个工具都标注了），粒度由 ApprovalScope 再细到工具名。
            ToolCatalog.Approval.PRIVATE_READ,
        )
        val bad = ToolCatalog.tools.mapNotNull { it.approval }.filter { it !in allowed }
        expect(bad.isEmpty(), "所有审批键都在 Approval 词表内", { bad.toString() })
    }

    check("C12 组在目录里成段出现（否则工具表顺序与书写顺序不一致）") {
        // toolNames 是按组切分拼出来的：同一组被插行拆成两段时，拼出来的顺序
        // 与目录里写的顺序就不是一回事，工具表顺序悄悄变了、KV 前缀整段失效。
        val gs = ToolCatalog.tools.map { it.group }
        val broken = gs.withIndex().groupBy({ it.value }, { it.index })
            .filter { (_, idx) -> idx.last() - idx.first() + 1 != idx.size }.keys
        expect(broken.isEmpty(), "每组在目录里是连续的一段", { broken.toString() })
    }

    check("C9 目录自检：真实目录零问题") {
        val problems = ToolCatalog.audit(ToolCatalog.toolNames)
        expect(problems.isEmpty(), "真实目录自检通过", { problems.toString() })
    }

    check("C10 目录自检能抓「多」：注册了但没登记") {
        val problems = ToolCatalog.audit(ToolCatalog.toolNames + "ghost_tool")
        expect(problems.any { "ghost_tool" in it }, "报出 ghost_tool 已注册但未登记", { problems.toString() })
    }

    check("C11 目录自检能抓「少」：登记了但没实现") {
        val problems = ToolCatalog.audit(ToolCatalog.toolNames - "read_file")
        expect(problems.any { "read_file" in it }, "报出 read_file 登记了但没有实现", { problems.toString() })
    }

    println()
    println("$pass 通过 / $fail 失败")
}
