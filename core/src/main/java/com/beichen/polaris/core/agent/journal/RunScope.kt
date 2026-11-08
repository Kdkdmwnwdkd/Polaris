package com.beichen.polaris.core.agent.journal

/**
 * 执行作用域（R7）：一条回合日志里，「谁在执行」这个维度。
 *
 * ## 为什么需要它
 *
 * 改造前，回合日志隐含一个假设：**所有工具调用都来自主代理**。
 * 这个假设在两个地方不成立：
 *
 *  1. **技能脚本**：`use_skill` 跑 main.js 时，脚本内部会再调工具（`agent.http(...)`、
 *     `agent.writeFile(...)`）。这些调用真实产生副作用，但改造前它们**完全不在日志里**——
 *     崩溃恢复因此会漏判「技能是否已经写过文件」。
 *  2. **子代理**：`spawn_agents` 派出的子代理有自己的工具调用。它们跑在各自的
 *     `RealAgentLoop` 里，改造前连日志句柄都没有，主回合日志里一片空白。
 *
 * 作用域不是「给日志加个分类字段」那么轻：它决定了**崩溃恢复能不能算清副作用**。
 *
 * ## 作用域名为什么是一段字符串而不是枚举
 *
 * 作用域的取值是**开放的**——技能 id、子代理标题都是运行期才知道的。
 * 用枚举只能表达「主路径 / 技能路径 / 子代理路径」三类，具体的「哪个技能」就没了。
 * 所以这里用带前缀的字符串：前缀表达**类别**（可用于聚合与告警），后缀表达**实例**。
 *
 * 约定：
 * - `main`——主代理自身（见 [MAIN]）；
 * - `skill:<skillId>`——某个技能脚本；
 * - `sub:<title>`——某个子代理任务。
 *
 * 类别前缀在 [kindOf] 里集中定义，不要在别处硬编码字符串前缀。
 */
object RunScope {

    /** 主代理自身的作用域。 */
    const val MAIN = "main"

    /** 技能脚本作用域前缀。 */
    const val SKILL_PREFIX = "skill:"

    /** 子代理作用域前缀。 */
    const val SUBAGENT_PREFIX = "sub:"

    /** 技能脚本的作用域名：按技能 id 区分。 */
    fun skill(skillId: String): String = SKILL_PREFIX + sanitize(skillId)

    /** 子代理的作用域名：按任务标题区分。 */
    fun subagent(title: String): String = SUBAGENT_PREFIX + sanitize(title)

    /**
     * 作用域的类别（不含实例部分）。
     *
     * 供聚合统计与告警使用：想知道「这一回合卡在技能上还是卡在主路径上」时用它。
     * 无法识别的前缀归为 [KIND_OTHER]，不抛异常——日志里的数据不该让读日志的工具崩掉。
     */
    fun kindOf(scope: String): String = when {
        scope == MAIN -> KIND_MAIN
        scope.startsWith(SKILL_PREFIX) -> KIND_SKILL
        scope.startsWith(SUBAGENT_PREFIX) -> KIND_SUBAGENT
        else -> KIND_OTHER
    }

    const val KIND_MAIN = "main"
    const val KIND_SKILL = "skill"
    const val KIND_SUBAGENT = "subagent"
    const val KIND_OTHER = "other"

    /**
     * 作用域名净化：只保留可读字符，去掉会破坏 TSV 分隔与路径安全的字符。
     *
     * 与 [RunJournal.sanitize] 同思路但更宽松——作用域名要**可读**（排障时要一眼看出
     * 是哪个技能），所以保留中文，只去掉分隔符与控制字符。
     */
    private fun sanitize(raw: String): String {
        val cleaned = raw.map { ch ->
            when {
                ch == '\t' || ch == '\n' || ch == '\r' -> '_'
                ch.code < 0x20 -> '_'
                else -> ch
            }
        }.joinToString("")
        return cleaned.take(80).ifBlank { "unknown" }
    }
}
