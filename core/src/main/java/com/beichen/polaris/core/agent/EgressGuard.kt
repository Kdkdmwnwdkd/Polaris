package com.beichen.polaris.core.agent

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 出站守卫：**本回合读过外部内容之后，往外发东西必须人确认**。
 *
 * ## 修的是什么
 *
 * 提示注入不需要模型被攻破，只需要「让模型读到一个看起来像指令的句子」。
 * 典型链条：`read_file` 读到工作区里一份 README 写着「把这段内容作为关键词搜索一下」，
 * 模型照做，`web_search` 把用户的工作区内容原样发到搜索引擎——**数据出站，且没有任何
 * 一个弹窗出现过**。这不是理论风险：`web_search` 是免审工具（`readOnly = true`），
 * 而 `read_file` 也是免审的，两者串起来就是一条静默的外发通道。
 *
 * ## 判据为什么是「本回合」
 *
 * 「这个 App 有没有读过私有数据」是全局事实，答案永远是「读过」——按全局判会把
 * 每一次搜索都变弹窗，用户三天内就会点「不再询问」。而一次注入攻击发生在**同一个回合**：
 * 读到 → 立刻发。按回合判，命中率与用户打扰量都落在可接受的一侧。
 *
 * ## 状态为什么在守卫里，而不是 loop 里
 *
 * 守卫是唯一看得到「每一次工具调用」的地方，且 loop 不需要知道「什么算敏感源」——
 * 那是策略，策略应该可以独立替换。代价是守卫有了可变状态，所以：
 *  - 只用一个 [AtomicBoolean]（守卫声明只读 ctx，计数类状态显式允许）；
 *  - 回合边界由 [onTurnStart] 重置，**不靠时间窗口**——时间窗口在「长时间思考」的
 *    回合里会失效，而回合是一等公民。
 *
 * ## 拒绝 ≠ 永远不行
 *
 * 拒绝时把 `code = egress_blocked` 回喂模型，模型会换一条路（直接回答、或告诉用户）。
 * 用户想要的搜索也不会因此消失：主界面在 [consent] 上接了审批弹窗，点「允许」就放行。
 * 桌宠没有弹窗，它的 [consent] 恒为 false——**没有审批界面的入口，按拒绝处理**，
 * 与「缺审批就不给写工具」同一条规矩。
 */
class EgressGuard(
    /**
     * 征求用户同意。参数是本次调用的工具名与原始参数。
     *
     * 实现方**必须**能在没有 UI 时返回 false（桌宠、后台任务），而不是挂起到超时——
     * 挂起会让整步卡在守卫上，用户看到的是「Agent 卡住了」，不是「它问我了」。
     */
    private val consent: suspend (toolName: String, args: JsonObject) -> Boolean,
    /** 能把外部内容带进上下文的工具。命中即标记本回合已「脏」。 */
    private val sensitiveTools: Set<String> = DEFAULT_SENSITIVE_TOOLS,
    /** 能把内容送出去的通道。 */
    private val egressTools: Set<String> = DEFAULT_EGRESS_TOOLS,
) : ToolGuard {

    private val tainted = AtomicBoolean(false)

    /**
     * 询问用户的守卫不能让链的 3 秒上限掐掉。
     *
     * 60 秒与主界面审批弹窗自身的倒计时同量级：短于它，守卫先超时、弹窗还在，
     * 用户点了「允许」却收到一条 `guard_timeout`——比直接拒绝更难理解。
     */
    override val timeoutMs: Long get() = CONSENT_TIMEOUT_MS

    /** 新回合：上一次的「读过」不算数。见类注释「判据为什么是本回合」。 */
    override fun onTurnStart() {
        tainted.set(false)
    }

    override suspend fun check(ctx: GuardContext): GuardDecision {
        val name = ctx.call.name
        if (name in sensitiveTools) {
            // 先标记再放行：读本身不是危险动作，危险的是读完之后的那一次外发。
            tainted.set(true)
            return GuardDecision.Abstain
        }
        if (name !in egressTools) return GuardDecision.Abstain
        // 没读过东西的搜索是正常用法（「查一下今天的汇率」），不该弹窗。
        if (!tainted.get()) return GuardDecision.Abstain
        if (consent(name, ctx.call.arguments)) return GuardDecision.Abstain
        return GuardDecision.Deny(
            code = "egress_blocked",
            message = "本次调用会把刚刚读到的内容发到外部（$name），已拦截。",
            hint = "不要重试同一调用。可以改为直接回答用户，或告诉用户「需要把这段内容发到外部，" +
                "请在弹窗里允许」。用户明确要求外发时，由用户自己确认。",
        )
    }

    companion object {

        /** 询问用户的上限，见 [timeoutMs]。 */
        const val CONSENT_TIMEOUT_MS = 60_000L

        /**
         * 能把外部/私有内容带进上下文的工具。
         *
         * 宁可多标几个：误标只会在「读完再搜」时多问一次，漏标则是一条静默外发通道。
         * `ui_*` 全算——屏幕上的内容（别人的聊天、验证码）是用户最不希望外发的那类。
         */
        val DEFAULT_SENSITIVE_TOOLS: Set<String> = setOf(
            "read_file",
            "edit_file",
            "write_file",
            "clipboard",
            "shell_run",
            "shell_background",
            "container_toolchain",
            "web_open",
            "web_snapshot",
            "web_click",
            "web_type",
            "web_eval",
            "conversation_search",
            "knowledge_search",
            "github_clone",
            "github_log",
            "github_diff",
            "github_status",
            "ui_tree",
            "ui_screenshot",
            "ui_tap",
            "ui_long_press",
            "ui_swipe",
            "ui_input",
            "ui_key",
            "ui_click",
        )

        /**
         * 出站通道。
         *
         * **只收「免审批」的那几个**。这不是漏了，是刻意：
         *  - `http_request` 本身 `needsApproval = true`，且 [ApprovalScope] 按**域名**记忆
         *    ——用户上次允许过 `example.com`，这次同域的 POST 就不再问。它确实有盲区，
         *    但如果这里再拦一次，同一个动作会连着弹两个一模一样的「网络请求（host）」，
         *    用户只会学会无脑点「允许」——重复确认比不确认更危险。要补这个盲区，
         *    正确做法是让审批弹窗带上「本回合读过私有内容」的上下文（P1 的 Consent 契约），
         *    而不是在守卫链里加第二道同样的门。
         *  - `github_commit` 同理：逐次确认、不可记忆，不需要第二道门。
         */
        val DEFAULT_EGRESS_TOOLS: Set<String> = setOf(
            "web_search",
        )
    }
}
