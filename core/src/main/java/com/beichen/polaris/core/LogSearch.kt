package com.beichen.polaris.core

/**
 * 一条搜索命中。
 *
 * @param line 整行原文（`HH:mm:ss.SSS LEVEL [tag] message`），由 UI 自己解析——
 *   core 不产出 UI 的展示模型。
 * @param day 该行所属日期 `yyyyMMdd`，**一定有值**。
 *
 *   2026-11-25 之前这里是 `String?`，null 表示「还在内存缓冲里、日期未定」，UI 把它
 *   渲染成「本次」。真机上当场露馅：启动时 `restoreTail` 会把**昨天**的最后 500 条
 *   读回内存，这批行于是全被标成「本次」——其中一条 `23:12:45`，而截图时是 18:13，
 *   它只可能发生在昨天。日期从来都算得出来：`LogEntry.timeMs` 就是准确时刻
 *   （`clockToMillis` 连跨零点都修了），所以这里不该有 null，也不该有「本次」。
 */
data class LogHit(val line: String, val day: String)

/**
 * 一次搜索的结果。
 *
 * @param days 日期的倒序列表（最近的在前），有两种含义，按有无命中切换：
 *   - **有命中**：这些命中分别落在哪几天。UI 就靠它说「在 X 至 Y 的日志中命中 N 条」，
 *     所以必须是真实来源，不能是「扫过哪些有文件的天」——后者会把空白天算进范围。
 *   - **无命中**：这时用户想知道的是「你到底搜过哪儿」，所以给扫描范围。
 *   只写「近 7 天」会撒谎：7 天里只有 3 天有文件时，范围就是 3 天。
 * @param truncated 命中数已达上限、更早的日志没有搜。不标出来的话，
 *   用户会以为「就这么多」，而实际上是「只看了最近的这么多」。
 */
data class LogSearchResult(
    val hits: List<LogHit>,
    val days: List<String>,
    val truncated: Boolean
) {
    companion object {
        val EMPTY = LogSearchResult(emptyList(), emptyList(), false)
    }
}

/**
 * 日志搜索的关键词判据（2026-11-25）。
 *
 * **为什么在 core 而不在 LogsScreen**：`LogsScreen.kt` 依赖 Compose，
 * 在 JVM 上编不过，判据留在里面就等于零断言——只能靠读代码确认它对不对。
 * 与 [DiagnosticsFingerprint] 同一个理由（那个文件里写着同样的话）。
 *
 * 这里只管「哪些行命中」「命中的是哪几个区间」，不产出样式：
 * 区间交给 UI 拼 `AnnotatedString`，core 不依赖任何 Compose 类型。
 */
object LogSearch {

    /**
     * 拆关键词：按空白切分、丢弃空段。
     *
     * 不做模糊匹配、拼音、正则——日志是机器写的，要搜的就是那几个确切字符
     * （`401`、`http_request`、`call_00_ZY`）。把 `401` 当正则解释还会意外命中
     * `14012` 这类纯数字，那正是排查时最不想要的结果。
     */
    fun parseTerms(query: String): List<String> =
        query.split(' ', '\t', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 全部关键词都出现（AND）才算命中；无关键词时恒真。
     *
     * 为什么不是 OR：敲 `http_request 401` 的人想找的是同时含这两段的那些行。
     * OR 会把「所有 http 调用」和「所有带 401 的行」都倒出来，等于搜完还得再翻一遍。
     */
    fun matches(raw: String, terms: List<String>): Boolean =
        terms.all { raw.contains(it, ignoreCase = true) }

    /**
     * 命中区间，已合并重叠、按起点升序、互不相交。
     *
     * 用 `indexOf(ignoreCase)` 逐段找而不是正则：关键词来自用户输入，
     * `(`、`[`、`\` 在正则里都有特殊含义，转义漏一个就是 PatternSyntaxException。
     * 日志页不能因为用户在搜索框里打了个括号就崩。
     *
     * 必须合并的理由：`http` 与 `http_request` 同时命中时会得到互相包含的两段。
     * 不合并的话，调用方按区间切片拼字符串会把同一段文字重复拼进去
     * （`http` 切一刀、`http_request` 再切一刀，中间那段被拼两遍）。
     */
    fun ranges(text: String, terms: List<String>): List<IntRange> {
        if (text.isEmpty() || terms.isEmpty()) return emptyList()
        val hits = mutableListOf<IntRange>()
        terms.forEach { term ->
            if (term.isEmpty()) return@forEach
            var from = 0
            while (from <= text.length - term.length) {
                val at = text.indexOf(term, from, ignoreCase = true)
                if (at < 0) break
                hits += at until (at + term.length)
                // 步进一个词长而不是 +1：关键词不会和自己重叠，+1 只会做无用的重复扫描。
                from = at + term.length
            }
        }
        if (hits.isEmpty()) return emptyList()
        return hits.sortedBy { it.first }.fold(mutableListOf<IntRange>()) { acc, r ->
            val last = acc.lastOrNull()
            // `<= last.last + 1` 把「紧邻」也并进去（如 `a` 与 `bc` 命中 `abc`）：
            // 两个相邻区间分别加样式与合并后加样式视觉相同，合并可少切一刀。
            if (last != null && r.first <= last.last + 1) {
                acc[acc.lastIndex] = last.first..maxOf(last.last, r.last)
            } else {
                acc += r
            }
            acc
        }
    }
}
