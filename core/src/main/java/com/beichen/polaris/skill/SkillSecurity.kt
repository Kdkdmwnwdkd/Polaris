package com.beichen.polaris.skill

import com.beichen.polaris.core.tool.ToolCatalog

/**
 * Skill 安全内核（L5「契约化只读」的硬约束层，净室原创）。
 *
 * 背景：原 [SkillRuntimeBridge] 只校验 skill 自己 manifest 里声明的 `tools` 白名单，
 * 等于「自己证明自己」——一个恶意 skill 只需在 skill.json 写 `"tools":["ui_tap","ui_screenshot"]`
 * 就能拿到全部高危权限（因为 registry 注册了所有工具）。本文件补一层**全局能力门**：
 * 所有工具按危险度分级，skill 按其信任等级被赋予能力上限，越权调用即使写在白名单里也被拦。
 *
 * 设计原则：
 *  - 不依赖第三方 skill 的「自证白名单」，而是用应用内置的工具目录
 *    （[com.beichen.polaris.core.tool.ToolCatalog]，R-13 起是能力分级的唯一真相源）作为判据。
 *  - 信任等级默认 PACKAGED（打包进 APK，可信全开）；IMPORTED/REMOTE 收紧，且默认禁止脚本执行。
 */

/**
 * 兼容别名：能力分级的真源已迁到 [com.beichen.polaris.core.tool.Capability]（R-13）。
 *
 * 保留是为了让 `import com.beichen.polaris.skill.Capability` 的既有代码与 harness
 * 继续编译——typealias 只是别名，不是第二份定义，改真源不需要动这里。
 */
typealias Capability = com.beichen.polaris.core.tool.Capability

/**
 * 工具 → 能力分级的查询入口。
 *
 * ## R-13：这里不再持有那张表
 *
 * 修前本对象有一张 56 行的手写 map。它与 `CapabilityManifest.capabilities`、
 * 工具类里的 `name`、`ApprovalGrouping.groupOf` 三处互不知道——加一个工具要改四遍，
 * 漏改也不会编译失败：新工具被 fail-closed 当成 DANGEROUS，imported 技能一调就报越权，
 * 而排查得从「技能为什么被拒」倒推回这张表。
 *
 * 现在分级跟着工具走，只有 `core/tool/ToolCatalog.kt` 一处记录。本对象只剩查询，
 * 对外签名一个没变（skill 运行时、UI 调试页、harness 都按旧签名调用）。
 */
object CapabilityRegistry {

    /**
     * 查某工具的能力分级。
     *
     * **未知工具按 [Capability.DANGEROUS] 处理**（fail-closed）：旧实现按 SAFE 处理，
     * 等于「新工具只要忘了登记就自动放行」。现在「忘了登记」在目录层面就会被
     * [com.beichen.polaris.core.tool.ToolCatalog.audit] 抓住；这里的默认值只兜底
     * MCP 动态工具——它们的名字连上服务器才知道，目录里本来就不该有。
     */
    fun cap(toolName: String): Capability = ToolCatalog.capabilityOf(toolName)

    /** 返回全部已知危险工具名（供 UI / 审计展示）。 */
    fun dangerousTools(): List<String> =
        ToolCatalog.tools.filter { it.capability == Capability.DANGEROUS }.map { it.name }

    /** 已登记的工具名（只读快照，供审计与调试页使用）。 */
    val knownTools: Set<String> get() = ToolCatalog.toolNames.toSet()

    /**
     * 与「受治理的工具全集」对账，返回人话问题列表（空 = 对得上）。
     *
     * ## 为什么这条不能只看 [cap]
     *
     * [cap] 是 fail-closed 的：查不到就当 DANGEROUS。这让「漏登记」在**安全**上无害，
     * 却在**可解释性**上有害——一个标了 SAFE 语义的新工具忘了登记，表现是
     * imported/remote 技能调它时拿到「能力越权：…无权调用 xxx（DANGEROUS）」，
     * 而工具本身明明只读。查这个问题要从「技能为什么被拒」倒推回目录，中间没有线索。
     *
     * 反方向同样要查：工具删了、目录没删，`dangerousTools()` 的展示与审批弹窗的分级说明
     * 都会指着不存在的东西。
     *
     * ## 入参口径
     *
     * 传 [com.beichen.polaris.core.capability.CapabilityManifest.toolNames]（受治理的工具），
     * **不要传工具注册表全集**：元工具（`__use_tools` / `__find_tools`）与 MCP 动态工具的
     * 名字不受技能白名单约束，传进来只会制造每次都一样的固定噪声。
     */
    fun audit(governedTools: Collection<String>): List<String> {
        val governed = governedTools.toSet()
        val known = ToolCatalog.toolNames.toSet()
        val missing = governed.filter { it !in known }.sorted()
        val stale = known.filter { it !in governed }.sorted()
        return buildList {
            if (missing.isNotEmpty()) {
                add(
                    "能力表缺 ${missing.size} 个工具（会按 DANGEROUS 处理，" +
                        "imported/remote 技能调用即被拒）：${missing.joinToString()}"
                )
            }
            if (stale.isNotEmpty()) {
                add("能力表多 ${stale.size} 个清单里没有的工具（死条目）：${stale.joinToString()}")
            }
        }
    }
}

/** Skill 信任等级：来源越不可控，权限越收紧。 */
enum class TrustLevel {
    /** 打包进 APK，由开发者审计过，默认可信（允许脚本 + 全部能力）。 */
    PACKAGED,
    /** 用户/生态导入到本地（未来支持），需显式授权才允许脚本。 */
    IMPORTED,
    /** 远程/第三方动态加载（如 CocoLoop），默认只给提示词形态 + 安全能力。 */
    REMOTE
}

/** 针对某信任等级的执行策略。 */
data class SkillPolicy(
    /** 是否允许执行脚本（main.js）。false 时强制回退 prompt 形态。 */
    val allowScript: Boolean,
    /** 允许调用的工具能力上限；高于此级的工具一律拦截。 */
    val maxCapability: Capability
)

/**
 * 由 skill 的 trust 字段解析执行策略。
 *
 * **B 批：未知/缺省 trust 一律按最严处理（fail-closed）。**
 *
 * 旧实现是 `else -> packaged`——只有精确命中 "remote"/"imported" 才收紧，
 * 任何其它字符串（含拼错的 "Packaged"、含空格的 " packaged"、manifest 里
 * 手写的 "trusted"）都落进 else，拿到**最高信任**：允许执行脚本 +
 * DANGEROUS 级工具全部放行。这与 A 批能力表 fail-closed 的原则直接矛盾——
 * 攻击者只要让 trust 字段「不是那两个精确值」，就自动拿到最宽权限。
 *
 * 现在改为**显式白名单**：只有精确的 "packaged" 才给最高信任；
 * "imported"/"remote" 按来源收紧；其余（含 null、空串、未知值）按
 * [TrustLevel.REMOTE] 处理——最严的一档。
 *
 * 大小写与首尾空格**归一化后再比对**（`trim().lowercase()`）：trust 是
 * manifest 里的自由文本，"Packaged" / "PACKAGED" 表达的显然是同一个意图，
 * 不该因为大小写不同就被降级——那会逼着 skill 作者写出精确小写，
 * 也让「未知值从严」这条规则被大小写噪声淹没。
 *
 * 为什么 null 也要收紧：`SkillManifest.trust` 的默认值是 ""，
 * 但 manifest 是可被构造/反序列化的对象，缺省值不能当作「已审计」的证据。
 * 真正的 packaged 技能由 [com.beichen.polaris.skill.SkillImportRepository]
 * 依据**本地授权记录**写入（id 在 authorizedIds 里才写 packaged），
 * 以及 assets 内置技能在 [com.beichen.polaris.skill.SkillLoader] 显式标注。
 */
fun policyFor(trust: String?): SkillPolicy = when (trust?.trim()?.lowercase()) {
    "packaged" -> SkillPolicy(allowScript = true, maxCapability = Capability.DANGEROUS)
    "imported" -> SkillPolicy(allowScript = false, maxCapability = Capability.PRIVILEGED)
    "remote" -> SkillPolicy(allowScript = false, maxCapability = Capability.SAFE)
    // 未知 / 缺省 / 空串：最严一档（与 remote 同级），并显式标注为未知
    else -> SkillPolicy(allowScript = false, maxCapability = Capability.SAFE)
}

/** 供 UI / 日志展示的信任等级标签：未知值不再伪装成 packaged。 */
fun trustLabel(trust: String?): String = when (trust?.trim()?.lowercase()) {
    "packaged" -> "打包可信"
    "imported" -> "本地导入"
    "remote" -> "远程来源"
    else -> "来源未知（按最严策略处理）"
}

/**
 * 全局能力门：在 manifest 白名单之后二次校验。
 * @return null 表示放行；非 null 为拦截原因（直接作为工具返回文本）。
 */
fun checkCapabilityGate(toolName: String, trust: String?): String? {
    val policy = policyFor(trust)
    val cap = CapabilityRegistry.cap(toolName)
    if (cap.ordinal > policy.maxCapability.ordinal) {
        return "⛔ 能力越权：技能来源「${trustLabel(trust)}」最多允许 ${policy.maxCapability.name} 级工具，" +
            "无权调用 $toolName（${cap.name}）。"
    }
    return null
}

/**
 * 技能 manifest 的工具白名单判定（A 批新增，fail-closed）。
 *
 * 旧实现是 `if (skill.tools.isNotEmpty() && name !in skill.tools) 拒绝`——
 * 白名单为空等于「不限制」，一个不写 tools 的恶意包反而能调用全部工具。
 * 现在空表只允许「不调用任何工具」（提示词形态仍可正常工作）；
 * 需要全量的技能必须显式写 `["*"]`，并在安装/授权时单独提示。
 */
fun isToolAllowedByWhitelist(declared: List<String>, toolName: String): Boolean =
    declared.contains("*") || toolName in declared
