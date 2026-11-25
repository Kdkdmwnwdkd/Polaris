package com.beichen.polaris.core

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
