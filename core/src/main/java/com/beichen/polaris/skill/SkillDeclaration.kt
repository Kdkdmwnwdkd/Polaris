package com.beichen.polaris.skill

import com.beichen.polaris.core.net.HostAllowlist

/**
 * 一个技能对用户做出的「声明」：它会联网到哪里、要不要执行脚本、声明了哪些工具。
 *
 * ## 为什么要把 manifest 翻译成这个
 *
 * `net_hosts` 只存在于 `skill.json` 里，用户在安装页与技能中心看到的却是「名称 + 描述 +
 * 徽章」。于是「事先授权」在实现上成立了（[SkillNetGrant] 按它判定），在体验上却没发生：
 * 用户从未看过那串域名，却因为装了这个技能而放行了它们。**声明不可见，授权就不成立**。
 *
 * ## 三个字段各有一个反例
 *
 * - [hosts] 必须**规范化 + 去重**后再展示。manifest 里 `API.Example.com` 与
 *   `api.example.com.` 是同一条规则，展示成两行会让用户以为要连两个地方；而运行时的
 *   [HostAllowlist.matches] 也是先 normalize 再比，两边口径必须一致。
 * - [invalidHosts] 单独列出，不能静默丢弃。[HostAllowlist.matches] 对非法项一律不命中
 *   （fail-closed），所以写了 `"*"` 的技能装上去之后**每次**联网都被拒，而运行时错误只会说
 *   「不在声明范围内」——用户看不出是自己写错了。装机前这一行是唯一能纠正它的地方。
 * - [needsScript] 与「能不能执行脚本」是两件事：前者是 manifest 形态（`entry` 非空），
 *   后者由 trust 决定（[policyFor]）。这里只回答前者——替策略下结论就是第二个真相源。
 */
data class SkillDeclaration(
    /** 规范化后的域名（已去重、保序）。 */
    val hosts: List<String>,
    /** 写法非法、运行时会被忽略的声明项（原文）。 */
    val invalidHosts: List<String>,
    /** 是否脚本形态（manifest 的 `entry` 非空）。 */
    val needsScript: Boolean,
    /** manifest 里声明的工具名。 */
    val tools: List<String>
) {
    /** 是否声明了至少一个可用域名。 */
    val networked: Boolean get() = hosts.isNotEmpty()
}

/**
 * manifest → [SkillDeclaration] 的纯函数（无 Android、无 IO，JVM harness 直接断言）。
 *
 * 输入特意拆成三个字段而不是接收 `SkillManifest`：那个类是 `@Serializable` 且与
 * `SkillExecutor` 同包，直接依赖会把它拖进 harness 的编译面（`SkillProtocol.kt` 引了
 * `android.content.Context`，纯 JVM 编不过）。判据留在纯函数里，Android 依赖留给接线层。
 */
object SkillDeclarations {

    /** 一行里最多铺几个域名。超出只说「等 N 个」——8 个域名铺满两行，卡片就没法看了。 */
    private const val MAX_SHOWN_HOSTS = 3

    /**
     * @param netHosts manifest 的 `net_hosts` 原文（不要求已校验）
     * @param entry manifest 的 `entry`（脚本相对路径；null / 空白 = 提示词型）
     * @param tools manifest 的 `tools`
     */
    fun of(netHosts: List<String>, entry: String?, tools: List<String>): SkillDeclaration {
        val hosts = LinkedHashSet<String>()
        val invalid = LinkedHashSet<String>()
        netHosts.forEach { raw ->
            if (raw.isBlank()) return@forEach
            if (HostAllowlist.validateEntry(raw) == null) {
                hosts += HostAllowlist.normalize(raw)
            } else {
                invalid += raw.trim()
            }
        }
        return SkillDeclaration(
            hosts = hosts.toList(),
            invalidHosts = invalid.toList(),
            needsScript = !entry.isNullOrBlank(),
            tools = tools.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        )
    }

    /**
     * 卡片上的一行状态（技能中心与安装确认共用同一句）。
     *
     * 四种情形各自的措辞都对应一个会让用户误解的坑：
     *  - 提示词型技能写「可联网」——它根本不会联网，那句话只是让人白担心；
     *  - 没声明域名时写「不联网」——真实情况是**连不上**，不是不想连；
     *  - 声明全非法时与「没声明」分开说——否则作者永远找不到是哪个字段写坏了。
     */
    fun hostLine(d: SkillDeclaration): String = when {
        !d.needsScript -> "提示词型 · 不执行脚本"
        d.hosts.isEmpty() && d.invalidHosts.isEmpty() -> "未声明域名 · 脚本无法联网"
        d.hosts.isEmpty() -> "域名声明全部非法 · 脚本无法联网"
        else -> {
            val shown = d.hosts.take(MAX_SHOWN_HOSTS).joinToString("、")
            val more = if (d.hosts.size > MAX_SHOWN_HOSTS) " 等 ${d.hosts.size} 个" else ""
            "可联网：$shown$more"
        }
    }

    /**
     * 安装 / 授权前必须让用户看见的提示（空列表 = 没有需要强调的）。
     *
     * 这三条都不是错误，而是「用户点了确认之后会发生什么」的预告：
     * 装上一个域名全非法的技能，它每次联网都会失败，而失败原因与技能本身无关。
     */
    fun warnings(d: SkillDeclaration): List<String> {
        val out = ArrayList<String>(3)
        if (d.invalidHosts.isNotEmpty()) {
            out += "以下域名写法不合法，运行时会被忽略：" + d.invalidHosts.joinToString("、") +
                "（裸 \"*\"、带协议或路径、\"api.*.com\" 都属于这一类）"
        }
        if (d.needsScript && d.hosts.isEmpty() && d.invalidHosts.isEmpty()) {
            out += "该技能要执行脚本，但没有声明任何域名：脚本里的网络请求会被一律拒绝。"
        }
        if (!d.needsScript && d.hosts.isNotEmpty()) {
            out += "该技能是提示词型（不执行脚本）：域名声明不会生效，主对话里的联网仍会逐次询问。"
        }
        return out
    }
}
