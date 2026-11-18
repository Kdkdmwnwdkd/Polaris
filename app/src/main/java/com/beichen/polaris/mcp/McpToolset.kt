package com.beichen.polaris.mcp

import android.content.Context
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.memory.MemoryStore
import com.beichen.polaris.memory.MemorySearchTool
import com.beichen.polaris.tools.AppLaunchTool
import com.beichen.polaris.tools.AppListTool
import com.beichen.polaris.tools.CalculatorTool
import com.beichen.polaris.tools.KnowledgeSearchTool
import com.beichen.polaris.tools.ReadFileTool
import com.beichen.polaris.automation.UiTreeTool
import java.io.File

/**
 * MCP 宿主用的工具子集。
 *
 * ## 为什么是「另建一份」而不是复用主界面的 registry
 *
 * 主界面那份由 `ChatViewModel` 持有，生命周期跟着 Activity 走——而 MCP 服务要在
 * 用户切后台、Activity 销毁之后继续应答。把那份 registry 强引用过来，等于让整个
 * 对话状态（含会话历史、LLM 客户端）跟着一个后台服务常驻，代价远大于收益。
 *
 * ## 这确实是「第四个入口」，但边界是收窄的
 *
 * 只装 7 个工具，全部是已有实现、零新增；只读 6 个 + `app_launch` 1 个（PRIVILEGED）。
 * P1 的 `ToolSource` 落地后，这份文件整体删掉——它的存在就是为了**让 MCP 现在能跑**，
 * 而不是成为新的装配中心。所以这里刻意不注册任何写文件、shell、无障碍点击工具：
 * 那些需要 `ToolGuardChain` 与审批 UI，属于 M2。
 */
object McpToolset {

    /**
     * 构建工具表。
     *
     * @param context 用 applicationContext：服务可能比任何 Activity 活得久。
     */
    fun build(context: Context): ToolRegistry {
        val app = context.applicationContext
        val workspace = File(app.filesDir, "agent_workspace").apply { mkdirs() }
        val memoryDir = File(app.filesDir, "memory").apply { mkdirs() }
        val knowledgeDir = File(app.filesDir, "knowledge").apply { mkdirs() }

        return ToolRegistry().apply {
            // ── 只读档（Capability.SAFE）────────────────────────────────
            register(AppListTool(app))
            register(UiTreeTool())
            register(ReadFileTool(workspace))
            register(MemorySearchTool(MemoryStore(memoryDir)))
            register(KnowledgeSearchTool(knowledgeDir))
            register(CalculatorTool())

            // ── 标准档（Capability.PRIVILEGED）──────────────────────────
            // 零权限，走 Intent；即使被外部调到，也只是「打开了某个 App」，
            // 而且它不产生数据外发，所以放在标准档而不是完全信任档。
            register(AppLaunchTool(app))
        }
    }

    /** 工具名清单，供 UI 与 harness 对账（不依赖 registry 实例）。 */
    val names: List<String> = listOf(
        "app_list",
        "ui_tree",
        "read_file",
        "memory_search",
        "knowledge_search",
        "calculator",
        "app_launch",
    )
}
