package com.beichen.polaris.core

/**
 * 外发文本的凭据脱敏（2026-11-19）。
 *
 * ## 为什么单独成文件
 *
 * 脱敏逻辑原来散在 [DiagnosticsExporter] 里，而那个文件 import 了
 * `android.content.Context` / `Build` / `FileProvider`——于是**脱敏这条判据
 * 一行断言都没有**：JVM 上根本编不过它，只能靠 grep 看「有没有调 maskKey」。
 *
 * 这与 `ScreenChangeReceipt`（第十轮）、`EgressGuard`（第十轮）是同一个动作：
 * 把**判据**从 Android 壳里拆出来，判据可测，壳只负责接线。
 *
 * ## 这条判据保的是什么
 *
 * 诊断包是**用户主动发出去**的文件（贴 issue、发群里）。它同时带三样东西：
 * 设备信息、配置（含端点与 Key）、最近日志与回合流水。
 * 前两样决定了它一旦漏，漏的是「能用你的账号花钱」的凭据。
 *
 * 所以本文件只做一件事：**给定一段将要离开应用的文本，返回一段不再含凭据的文本**。
 * 不含任何 IO、不读配置、不知道调用方是谁——测试因此可以在 JVM 上逐条对拍。
 *
 * ## 为什么「替换」也要管顺序
 *
 * `String.replace` 是字面量替换。若主 Key 恰好是某个备用 Key 的前缀，
 * 先替换短的那个，长 Key 就会剩下尾部明文：
 *
 * ```
 * 主 Key  sk-AAAAAAAA            （11 字符）
 * 备用    sk-AAAABBBBCCCCDDDD    （19 字符）
 * 日志    ... sk-AAAABBBBCCCCDDDD ...
 * 先短后长 → ... ****BBBBCCCCDDDD ...   ← 尾部 12 位明文留在文件里
 * ```
 *
 * 换成长 Key 优先就变成 `****`，一位不漏。这不是理论问题：用户完全可能
 * 在同一家服务商先后申请两把 Key，后一把以先前那把为前缀（不同后缀区分项目）。
 * 判据是 [secretsOf] 的**排序**，断言与变异都盯着它。
 *
 * ## 为什么还要「模式兜底」
 *
 * 用户日志里出现的 Key 不一定是我们配置里那把：切过服务商、贴过别人的 curl、
 * 插件自己打的日志、`MCP` 服务器回显的鉴权头。只替换「已知的两三把」等于
 * 只防住了自己知道的那部分泄露。
 *
 * 兜底模式**只认形状**（`sk-` / `ghp_` / `Bearer` / `"api_key":"…"` / `?token=`），
 * 不认来源。形状能覆盖的泄露，比「已知 key 列表」大一个数量级。
 *
 * ## 已知边界（写在这里，因为测试测不到）
 *
 * - **被 JSON 转义过的 Key 匹配不到**：日志里若是 `sk-abc\"def`，字面量替换失效。
 *   真实 Key 是 `[A-Za-z0-9_-]`，不含引号与反斜杠，故不处理。
 * - **拆行/加空格的 Key 匹配不到**：`sk-abc\n def` 不是一把合法 Key 的写法。
 * - **短于 8 位的值不当作 Key**：8 位以下全抹会把 `token=abc` 这类普通字段
 *   一起糊掉，排障价值归零——取舍写在 [secretsOf]。
 */
object DiagnosticsRedaction {

    /** 遮蔽后的占位。长度固定，读日志时一眼能看出「这里原本有东西」。 */
    const val MASK: String = "****"

    /** 空值的展示文案，与诊断包里「Key：」一栏保持一致。 */
    const val NOT_CONFIGURED: String = "(未配置)"

    /** 短于此长度的值不当作凭据（见类注释的取舍）。 */
    private const val MIN_SECRET_LEN = 8

    private const val KEEP_HEAD = 4
    private const val KEEP_TAIL = 4

    /**
     * 单把 Key 的遮蔽：前 4 + `****` + 后 4。
     *
     * 保留首尾各 4 位是为了排障时能对上「是哪一把」——同一账号下的两把 Key
     * 前缀常常相同，靠后 4 位区分。这 8 位不足以还原一把 32 位以上的 Key。
     *
     * 8 位及以下**整体抹掉**：`take(4) + "****" + takeLast(4)` 对 8 位 Key
     * 等于原样输出，是最容易写错的一行。
     */
    fun maskKey(key: String): String {
        val k = key.trim()
        if (k.isEmpty()) return NOT_CONFIGURED
        if (k.length <= MIN_SECRET_LEN) return MASK
        return k.take(KEEP_HEAD) + MASK + k.takeLast(KEEP_TAIL)
    }

    /**
     * 汇总「要替换的凭据」：主 Key + 备用池，去空白、去短值、去重，**按长度降序**。
     *
     * 降序是判据不是优化：短 Key 若是长 Key 的前缀，先替换短的会让长 Key
     * 残留尾部明文（见类注释里的例子）。
     */
    fun secretsOf(mainKey: String, backups: List<String> = emptyList()): List<String> =
        (listOf(mainKey) + backups)
            .map { it.trim() }
            .filter { it.length >= MIN_SECRET_LEN }
            .distinct()
            .sortedByDescending { it.length }

    /**
     * 抹掉文本里的凭据：先替换已知 Key（长 → 短），再按形状兜底。
     *
     * 幂等：[redact] 的产物再跑一次不变（`****` 本身不匹配任何模式）。
     * 这一点在导出流程里有用——日志与流水会分别过一遍，重复无害。
     */
    fun redact(text: String, secrets: List<String>): String {
        if (text.isEmpty()) return text
        var out = text
        // 已知 Key 优先：值精确，替换后一定不含原文
        for (s in secrets) {
            if (s.isNotEmpty() && out.contains(s)) out = out.replace(s, maskKey(s))
        }
        // 形状兜底：覆盖「配置里没有、但日志里出现」的凭据
        for (p in PATTERNS) {
            if (p.regex.containsMatchIn(out)) out = p.regex.replace(out, p.rebuild)
        }
        return out
    }

    /** 逐行版：给文件导出用，避免把几 MB 流水一次读进内存再复制一份。 */
    fun redactLines(lines: Sequence<String>, secrets: List<String>): Sequence<String> =
        lines.map { redact(it, secrets) }

    /** 形状 + 重写规则。绑定在一起，避免「加了模式忘了重写」。 */
    private class Rule(val regex: Regex, val rebuild: (MatchResult) -> String)

    /**
     * 兜底模式表。
     *
     * 排序无关紧要（各模式互不重叠），但**每条都必须是高熵形状**：
     * 宽松的模式会把普通参数一起抹掉，而排障时最需要看到的恰恰是参数。
     * 例如 `[A-Za-z0-9]{16,}` 这种「看起来像 Key」的规则绝不能加——
     * 它会抹掉 UUID、时间戳、文件哈希，诊断包直接失去价值。
     */
    private val PATTERNS: List<Rule> = listOf(
        // OpenAI 及兼容服务：sk- 开头，实测 48~164 位
        Rule(Regex("""\bsk-[A-Za-z0-9_\-]{16,}""")) { MASK },
        // GitHub：ghp_ / gho_ / ghu_ / ghs_ / ghr_
        Rule(Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}""")) { MASK },
        // GitHub fine-grained PAT
        Rule(Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}""")) { MASK },
        // Slack
        Rule(Regex("""\bxox[abposr]-[A-Za-z0-9\-]{10,}""")) { MASK },
        // AWS Access Key ID（Secret 没有可识别前缀，靠 JSON 字段那条兜）
        Rule(Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b""")) { MASK },
        // Google API Key
        Rule(Regex("""\bAIza[0-9A-Za-z_\-]{35}\b""")) { MASK },
        // 鉴权头：保留方案名（`Bearer`/`Basic` 本身是排障信息），只抹令牌
        Rule(Regex("""(?i)\b(bearer\s+)[A-Za-z0-9._\-]{8,}""")) { it.groupValues[1] + MASK },
        Rule(Regex("""(?i)\b(basic\s+)[A-Za-z0-9+/=]{8,}""")) { it.groupValues[1] + MASK },
        // JSON / 表单字段：保留字段名，只抹值。字段名是排障信息（「它往哪带了 token」）
        Rule(
            Regex(
                """(?i)("(?:api[_-]?key|apikey|token|access[_-]?token|refresh[_-]?token|secret|client[_-]?secret|password|authorization)"\s*:\s*")([^"]{4,})(")"""
            )
        ) { it.groupValues[1] + MASK + it.groupValues[3] },
        // URL query：`?key=` / `&token=` / `&api_key=`
        Rule(Regex("""(?i)([?&](?:key|api[_-]?key|token|access[_-]?token|secret)=)([^&\s"']{4,})""")) {
            it.groupValues[1] + MASK
        },
        // shell 里的 `--token xxx` / `--token=xxx` / `-H "Authorization: Bearer ..."` 已由上面覆盖
        Rule(Regex("""(?i)(--?(?:token|password|api-key|apikey)[ =])([^\s"']{6,})""")) {
            it.groupValues[1] + MASK
        },
    )
}
