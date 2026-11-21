package com.beichen.polaris.core

/**
 * W-RT4：提示词变量——模板里的 `{{占位}}` 与填槽。
 *
 * 背景：提示词库此前是「选了整段插入」——「排查问题」「规划手机任务」这类
 * 模板尾部都留着「问题描述：」让用户手动补。变量占位把补口子变成弹窗填槽：
 * 模板里写 `{{变量名}}`，插入时按名收集、逐个填值、一次成型。
 *
 * 语法：`{{名称}}`，两侧允许空白（`{{ 名称 }}` 等价）。名称非空、不含花括号
 * 和换行；`{{}}`、`{{a{b}}`、单边 `{{abc` 都不算变量（原样保留，不猜语义）。
 *
 * 纯函数、零依赖，JVM harness 真编真跑。
 *
 * 移植自 runtime/ssh-backend-w5 的 W-RT4（旧包名 com.example.agentkit），
 * 判定逻辑与正则一字未改。
 */

/**
 * 提示词变量的占位模式（名称捕获组；允许两侧空白）。
 *
 * ## 结尾那两个 `}` 为什么必须转义
 *
 * 原来写的是 `...\s*}}`。这在 JVM 上完全合法——OpenJDK 的 `Pattern` 把
 * 孤立的 `}` 当字面量，`"a}}"` 也能编过，所以 core 的单测全绿。
 *
 * **但 Android 的 `java.util.regex.Pattern` 不是 OpenJDK 的实现**，它底下是
 * ICU4C，对花括号的配对要求严格：孤立的 `}` 直接抛
 * `PatternSyntaxException: Syntax error in regexp pattern near index 23`。
 *
 * 而这一行在文件顶层 `val` 的初始化式里，异常会被包成
 * `ExceptionInInitializerError`，**类加载即崩**——2026-11-21 真机上点「快捷指令」
 * 就是这里闪退，`try/catch` 接不住，因为炸的是 `<clinit>`。
 *
 * 实测（API 30，dalvikvm 探针）：`\{\{...\s*}}` FAIL，`\{\{...\s*\}\}` OK。
 * 判据 `run-regex-compat-verification.sh` 会拦住这一类「JVM 能编、Android 不能编」的写法。
 */
private val PROMPT_VARIABLE = Regex("""\{\{\s*([^{}\n]+?)\s*\}\}""")

/**
 * 扫描提示词内容里的变量名：按首次出现顺序去重。
 * 注意与 [fillPromptVariables] 的判定一致：名称含花括号/换行的占位
 * 根本不会被 [PROMPT_VARIABLE] 捕获，两边天然不分歧。
 */
fun extractPromptVariables(content: String): List<String> =
    PROMPT_VARIABLE.findAll(content)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

/** 填槽结果：[Ok] 全部变量已填；[Missing] 列出缺值的变量名（UI 据此拦住确认键）。 */
sealed class PromptFillResult {
    data class Ok(val content: String) : PromptFillResult()
    data class Missing(val names: List<String>) : PromptFillResult()
}

/**
 * 按名填值（纯函数）。
 *
 * 规则：
 *  - [values] 里缺名、或值为空白 → [PromptFillResult.Missing]（列全部缺值名，
 *    不做半填——半填会把带 `{{占位}}` 的半成品塞进输入框，用户还得手动擦）；
 *  - 全齐 → 同名占位**全部出现处**替换为值（值原样插入，不做转义）；
 *  - values 里的多余键忽略（UI 按 [extractPromptVariables] 列表收集，正常不会多）。
 *
 * 为什么不做转义：插入目标是聊天输入框，不是 HTML。转义会把用户填的
 * `a&b` 变成 `a&amp;b` 直接发出去——那才是真的错。
 */
fun fillPromptVariables(content: String, values: Map<String, String>): PromptFillResult {
    val names = extractPromptVariables(content)
    val missing = names.filter { values[it].isNullOrBlank() }
    if (missing.isNotEmpty()) return PromptFillResult.Missing(missing)
    val filled = PROMPT_VARIABLE.replace(content) { m ->
        values[m.groupValues[1].trim()].orEmpty()
    }
    return PromptFillResult.Ok(filled)
}
