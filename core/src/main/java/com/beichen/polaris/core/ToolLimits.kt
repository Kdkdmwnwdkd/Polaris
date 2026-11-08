package com.beichen.polaris.core

/**
 * 工具结果体积上限 + 单结果截断（T06，设计参照：operit / aicode 的「单结果独立截断」做法，
 * 净室实现，未复制代码）。
 *
 * 手机上下文宝贵：一个 100KB 文件原样回喂会撑爆上下文、推高 token 成本。
 * 每个工具结果**独立**截断——不可整批截，因为每个调用必须保留对应结果槽位。
 */
const val MAX_TOOL_RESULT_CHARS: Int = 24_000

/**
 * 超长时**头部**分到的预算占比，剩下的给尾部。
 *
 * 留尾的理由：被截断的大结果绝大多数是「日志 / 报错 / 编译输出 / 测试结论」，
 * 而这四类里**唯一有用的一行几乎总在尾部**：
 *
 *  - shell 命令失败：最后一行是 `command not found` / 退出码；
 *  - Gradle / 编译器：`error:` 与 `FAILURE: Build failed` 在末尾；
 *  - 测试：`Tests: 3 failed, 12 passed` 在末尾；
 *  - 长日志：崩溃栈的 `Caused by:` 在末尾。
 *
 * 改造前只留头部，等于把这些结论整段丢掉，模型看到「开始跑了」却没有任何失败原因，
 * 只能盲目重试。这是「省 token」省过头的地方。
 *
 * 取 0.6 而非 0.5：头部还要承担「这是什么内容」的识别职责（文件开头的结构、
 * 命令回显、首行摘要），比尾部多留一成。
 */
private const val HEAD_BUDGET_RATIO = 0.6

/**
 * 单个工具结果截断到 [maxChars] 以内（含省略提示本身——保证回喂内容恒 ≤ 上限）。
 *
 * 策略是**头 [HEAD_BUDGET_RATIO] + 省略提示 + 尾**，不是「只留前 N 字符」。
 *
 * 纯函数：只依赖入参，同样输入两次调用结果完全相等（与 [formatToolResult] 同性质）。
 *
 * 两个实现细节：
 *  1. **切点对齐码点**（[safeHeadEnd] / [safeTailStart]）：直接在代理对中间切会留下
 *     半个 emoji，序列化成 JSON 时变成 `?` 或直接抛异常。终端链路早已处理，这里补齐。
 *  2. **省略数的位数依赖**：提示里要写「省略了多少字符」，而该数字取决于头尾预算，
 *     预算又取决于提示长度——循环依赖。解法是先用 `text.length`（省略数的位数上界）
 *     拼一个**最长可能**的提示取长度当预算，真实提示只会更短，故结果恒 ≤ [maxChars]。
 *
 * @param text 已格式化的工具结果文本（通常来自 [formatToolResult]）。
 * @param maxChars 上限，默认 [MAX_TOOL_RESULT_CHARS]。
 * @return 未超长原样返回；超长则保留头尾两段并在中间插入体量与分页提示。
 */
fun truncateToolResult(text: String, maxChars: Int = MAX_TOOL_RESULT_CHARS): String {
    if (text.length <= maxChars) return text

    val budget = maxChars - markerOf(text.length, text.length).length
    if (budget <= 0) {
        // 上限小到连提示都放不下（只有测试会构造这种值）：退化，不越界
        val m = markerOf(0, text.length)
        return m.substring(0, safeHeadEnd(m, maxChars.coerceAtMost(m.length)))
    }

    val headLen = (budget * HEAD_BUDGET_RATIO).toInt()
    val tailLen = budget - headLen
    val headEnd = safeHeadEnd(text, headLen)
    val tailStart = safeTailStart(text, text.length - tailLen)
    // 对齐后头尾各可能让出 1 个码元，省略数按**实际切点**重算，保证提示与内容一致
    return text.substring(0, headEnd) +
        markerOf(tailStart - headEnd, text.length) +
        text.substring(tailStart)
}

/**
 * 中间的省略提示。纯文本拼接（不用 `String.format`——默认 Locale 下数字字形
 * 可能变形，破坏确定性）。
 */
private fun markerOf(omitted: Int, total: Int): String =
    "\n…[结果过长：中间省略 $omitted 字符，共 $total 字符；" +
        "如需完整内容，改用分页参数（如 read_file 的 offset/limit）分批获取]\n"

/**
 * 头部切点对齐：若 [end] 恰落在**代理对中间**（前一码元是高位代理、当前是低位代理），
 * 则回退 1，避免切出孤立代理（半个 emoji / 非法 UTF-8 码点）。BMP 字符（中 / é 等单个
 * UTF-16 码元）天然对齐，不受影响。
 *
 * 与 [truncateToolResult] 共用同一份实现——终端链路的 `ToolOutputTruncator` 也调这里，
 * 两处规则不许各写一遍（两处默认值不一致正是本项目踩过的坑）。
 */
internal fun safeHeadEnd(s: String, end: Int): Int {
    var e = end.coerceIn(0, s.length)
    if (e in 1 until s.length && s[e - 1].isHighSurrogate() && s[e].isLowSurrogate()) e -= 1
    return e
}

/** 尾部起点对齐：若起点落在代理对中间（当前是低位代理、前一位是高位代理），后移 1。 */
internal fun safeTailStart(s: String, start: Int): Int {
    var st = start.coerceIn(0, s.length)
    if (st in 1 until s.length && s[st].isLowSurrogate() && s[st - 1].isHighSurrogate()) st += 1
    return st
}
