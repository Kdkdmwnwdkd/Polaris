package com.beichen.polaris.core.agent

import com.beichen.polaris.core.llm.primOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * 计划模式守卫（D-3）：接缝③的**第一个真实现**。
 *
 * ## 为什么是这个守卫
 *
 * 接缝③（[ToolGuard]）从 2026-11-13 就定好了形状——单调裁决、首个 Deny 即终止、
 * 崩溃与超时一律按拒绝（fail-closed）——但**一个实现都没写，一个装配点都没接**。
 * 三处 `RealAgentLoop(...)` 构造点全都走默认 [AgentSeams.NONE]，于是：
 *
 * - 守卫链在任何路径上都没有跑过，`ToolGuardChain` 的三条语义只有 harness 验过；
 * - 计划模式（设置页那个开关）只改了 [PlanTool] 的**描述文案**，模型照样能直接
 *   `write_file` / `shell_run` / `app_uninstall`。「先出方案待批，再执行」这句话
 *   在实现上从来没有发生过——它只是个提示词。
 *
 * 先接这一个，是因为它的收益最直接、判据最干净：**只读性已经是工具的既有属性**
 * （[com.beichen.polaris.core.AgentTool.readOnly]），不需要新造概念。
 *
 * ## 只读性从哪来（不新建第二张表）
 *
 * 守卫**不查目录表**，而是由装配方注入一个 `readOnlyOf` 查询。传进去的是
 * `registry.get(name)?.readOnly`——也就是工具类自己声明的那一份。
 *
 * 另立一张「哪些工具只读」的表会立刻产生第二个真源：加工具时漏填，计划模式下
 * 那个工具就静默可写。用注入函数则天然跟随工具实现，而且没注册的工具查不到时
 * 返回 false（按写处理），方向仍然是 fail-closed。
 *
 * ## 两个例外
 *
 * 1. `clipboard` 声明 `readOnly = true`，但 `action=set` 会把内容**写进**剪贴板
 *    （可能覆盖用户正在用的东西）。它的只读性是参数级的，工具级的布尔表达不了，
 *    所以这里单独看 action。这不是"守卫里写业务规则"的坏味道，而是「工具的
 *    readOnly 声明不够精确」这件事必须有人兜——兜在守卫里，至少是集中的一处。
 * 2. 子代理（`spawn_agents`）声明只读，因为派发本身不产生副作用。这是对的——
 *    子代理自己的每次工具调用会经过它自己那条 loop 的守卫（装配见
 *    [com.beichen.polaris.core.agent.SubAgentRunner]）。只要那条 loop 也接了守卫，
 *    计划模式就能穿过子代理边界继续生效。
 */
class PlanModeGuard(
    /** 当前是否处于计划模式。每步现取，用户中途切开关立刻生效。 */
    private val planMode: () -> Boolean,
    /** 工具是否只读（装配方注入 `registry.get(name)?.readOnly == true`）。 */
    private val readOnlyOf: (String) -> Boolean,
) : ToolGuard {

    override suspend fun check(ctx: GuardContext): GuardDecision {
        // 关着就弃权。这不是「放行」——[GuardDecision.Abstain] 的语义是「我没意见」，
        // 后面的守卫照常有机会拒绝。
        if (!planMode()) return GuardDecision.Abstain

        val name = ctx.call.name
        if (!readOnlyOf(name)) return deny(name)
        // 声明只读、但某个 action 会写的工具：只放行读的那一侧。
        if (name == CLIPBOARD_TOOL && actionOf(ctx.call.arguments) == CLIPBOARD_SET) return deny(name)
        return GuardDecision.Abstain
    }

    private fun deny(name: String): GuardDecision.Deny = GuardDecision.Deny(
        code = "plan_mode_readonly",
        message = "计划模式下不能调用 $name：它会改变设备状态。",
        hint = "先用 plan_propose 给出完整方案、等用户确认后再执行；" +
            "只读工具（读文件、搜索、看屏幕）在计划模式下不受限制。",
    )

    private fun actionOf(args: JsonObject): String? =
        args["action"]?.primOrNull()?.contentOrNull?.trim()?.lowercase()

    private companion object {
        const val CLIPBOARD_TOOL = "clipboard"
        const val CLIPBOARD_SET = "set"
    }
}
