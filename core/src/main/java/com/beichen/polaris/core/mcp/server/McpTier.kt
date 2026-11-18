package com.beichen.polaris.core.mcp.server

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.tool.Capability
import com.beichen.polaris.core.tool.ToolCatalog

/**
 * MCP 对外暴露的档位。
 *
 * ## 为什么档位不直接等于 [Capability]
 *
 * `Capability` 回答的是「这个工具本身有多危险」，档位回答的是「这个客户端被允许看到多少」。
 * 两者是**正交**的：`write_file` 是 PRIVILEGED，在标准档里可见，但它依然会走
 * `needsApprovalFor` 弹窗。「看得见」不等于「能直接执行」——把这两件事合成一个维度，
 * 结果必然是「为了少弹窗而放宽档位」，而档位一放宽就再也收不回来。
 *
 * ## 只读档为什么用 `capability == SAFE` 而不是 `readOnly == true`
 *
 * `readOnly` 是「可并行」的标注（T03），不是安全边界：它只标在少数工具上，
 * 漏标是常态。`Capability.SAFE` 的定义才是「只读沙箱资源，几乎无副作用」，
 * 且由 [ToolCatalog.audit] 在启动时与实现对账——漏标会直接让 harness 红。
 * 所以档位判定信 `capability`，`readOnly` 只用来填 MCP 的 `readOnlyHint`。
 */
enum class McpTier(val wire: String, val label: String) {

    /** 只读：让外部 Agent「看一眼手机」。默认档。 */
    READONLY("readonly", "只读"),

    /** 标准：可代操作，但写类工具仍逐次审批。 */
    STANDARD("standard", "标准"),

    /** 完全信任：含无障碍操控。审批规则不变，只是「可见」。 */
    FULL("full", "完全信任");

    /**
     * 该档位是否**可见**这个工具。
     *
     * 注意这里只做「可见性」判定，不做审批判定——审批由调用方在拿到工具后
     * 用 `needsApprovalFor(args)` 单独问。两件事分开，才不会有「档位一高就免审」的后门。
     */
    fun allows(tool: AgentTool): Boolean {
        // awaitsUser：等人回答的工具在 MCP 场景下语义不成立（人不在同一块屏幕上）。
        // 这不是危险不危险的问题，是「等谁」的问题——所以放在档位之前，任何档位都不放行。
        if (tool.awaitsUser) return false
        if (tool.name in NEVER_EXPOSED) return false
        return when (this) {
            READONLY -> ToolCatalog.capabilityOf(tool.name) == Capability.SAFE
            STANDARD -> ToolCatalog.capabilityOf(tool.name) != Capability.DANGEROUS
            FULL -> true
        }
    }

    companion object {

        /**
         * 任何档位都不暴露的工具。
         *
         * 每一条都有具体理由，不是「感觉危险」：
         *  - `app_uninstall` / `memory_forget`：不可逆，且**无法从错误中恢复**。外部 Agent
         *    没有「我刚才删错了」的补救路径，用户也没有。
         *  - `shell_background` / `container_toolchain`：长驻或提权，副作用在调用返回之后才发生，
         *    审批卡片拦不住「它待会儿会干什么」。
         *  - `ask_user` / `plan_propose` 由 `awaitsUser` 兜住，这里再列一次是防有人改了那个标注。
         */
        val NEVER_EXPOSED: Set<String> = setOf(
            "app_uninstall",
            "memory_forget",
            "shell_background",
            "container_toolchain",
            "ask_user",
            "plan_propose",
        )

        /** 线名 → 档位。未知/缺失一律降到最保守的 [READONLY]，不抛异常。 */
        fun fromWire(raw: String?): McpTier =
            values().firstOrNull { it.wire == raw } ?: READONLY
    }
}
