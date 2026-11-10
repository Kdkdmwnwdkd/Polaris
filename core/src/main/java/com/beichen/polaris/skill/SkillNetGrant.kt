package com.beichen.polaris.skill

import com.beichen.polaris.core.net.EndpointPolicy
import com.beichen.polaris.core.net.HostAllowlist

/**
 * 技能脚本的网络出口判定：**把"同意"提前到安装那一刻**。
 *
 * ## 为什么需要一条例外
 *
 * 脚本线程是 Rhino 的普通线程，求值过程不可挂起；审批是 suspend 的，
 * 所以「脚本内弹审批」只能靠 `runBlocking`，那会永久占住一个 worker 且取消不了
 * （见 `SkillRuntimeBridge.invoke` 第三道门的注释）。于是现状是一刀切拒绝：
 * 任何带脚本的技能只要调 `agent.http()` 就拿到 `script_approval_denied`。
 *
 * 对 `shell_run` 这类"后果无法在弹窗里说清"的工具，一刀切是对的；但
 * 「这个技能只查一个固定 API」不是——它需要的是**事先授权**，而不是每次确认。
 * 事先授权的载体就是 manifest 的 `net_hosts`，展示在安装页，用户装的时候看得见。
 *
 * ## 判定顺序（任一不满足就拒）
 *
 * 1. **来源允许执行脚本**：只有 packaged（含用户显式授权过的导入技能）才可能走到这里。
 *    imported / remote / 未知来源的 manifest 不可信，即使写了 `net_hosts` 也不算数。
 * 2. **请求地址落在 `net_hosts` 内**：[HostAllowlist.matches]，按真实请求主机比较。
 * 3. **不是公网明文**：[EndpointPolicy.refusalFor]。脚本没有能力把「这是明文、Key 会裸奔」
 *    解释给用户，所以一律按"带凭据"从严。
 *
 * ## 为什么判定要能被两处共用
 *
 * 同一份判据要同时回答两个问题：脚本桥的第三道门「要不要拦」、执行器的 approval
 * 「准不准执行」。两处各写一遍必然漂移（审计 F4 就是这么来的：一处 fail-open、
 * 一处 fail-closed）。这里只做纯函数，两边都调它。
 */
object SkillNetGrant {

    /** 唯一需要这条例外的工具名。写成常量，避免两处字面量漂移。 */
    const val NETWORK_TOOL = "http_request"

    /** 判定结果。 */
    sealed interface Verdict {
        /** 不是网络工具，本条规则不适用（交给原有的审批逻辑）。 */
        object NotNetwork : Verdict

        /** 命中事先授权，放行。 */
        object Granted : Verdict

        /** 拒绝。`reason` 直接展示；`hint` 告诉技能作者怎么补声明。 */
        data class Denied(val reason: String, val hint: String) : Verdict
    }

    /**
     * 判定一次脚本内工具调用。
     *
     * @param toolName 工具名
     * @param url `http_request` 的 url 参数（其它工具传 null）
     * @param trust 技能信任等级（manifest 原文，未归一化）
     * @param netHosts manifest 的 `net_hosts`（未校验原文）
     * @param skillName 技能显示名，用于错误文案
     */
    fun evaluate(
        toolName: String,
        url: String?,
        trust: String?,
        netHosts: List<String>,
        skillName: String,
    ): Verdict {
        if (toolName != NETWORK_TOOL) return Verdict.NotNetwork

        if (!policyFor(trust).allowScript) {
            return Verdict.Denied(
                "技能「$skillName」的来源（${trustLabel(trust)}）不允许执行脚本，也就不能发起网络请求。",
                "把技能来源改为可信（packaged）后再试；来源不可信的技能只能走提示词形态。"
            )
        }
        if (url.isNullOrBlank()) {
            return Verdict.Denied(
                "网络请求缺少 url，无法校验是否在技能声明的范围内。",
                "调用 agent.http(method, url) 时传完整地址，例如 https://api.example.com/v1/x。"
            )
        }

        val declared = netHosts.filter { it.isNotBlank() }
        if (declared.isEmpty()) {
            return Verdict.Denied(
                "技能「$skillName」没有声明可访问的域名，脚本不能发起网络请求" +
                    "（当前地址：${EndpointPolicy.hostOf(url).ifEmpty { url }}）。",
                "在 skill.json 里加 \"net_hosts\": [\"api.example.com\"]（支持 \"*.example.com\" 通配子域）；" +
                    "或者回到主对话，由用户逐次确认后再调用 http_request。"
            )
        }
        if (!HostAllowlist.matches(declared, url)) {
            val host = EndpointPolicy.hostOf(url).ifEmpty { url }
            val bad = declared.filter { HostAllowlist.validateEntry(it) != null }
            val extra = if (bad.isEmpty()) "" else "（其中 ${bad.joinToString()} 写法非法，已被忽略）"
            return Verdict.Denied(
                "域名 $host 不在技能声明的范围内。已声明：${declared.joinToString()}$extra。",
                "只访问声明过的域名；需要新域名时在 skill.json 的 net_hosts 里补一条，由用户重新安装/确认。"
            )
        }

        EndpointPolicy.refusalFor(url, sendsCredentials = true)?.let {
            return Verdict.Denied(it, "把地址改成 https，或改用局域网地址。")
        }
        return Verdict.Granted
    }

    /** 便捷判定：是否放行（非网络工具返回 false——它们不归本规则管）。 */
    fun allows(
        toolName: String,
        url: String?,
        trust: String?,
        netHosts: List<String>,
        skillName: String = "",
    ): Boolean = evaluate(toolName, url, trust, netHosts, skillName) is Verdict.Granted
}
