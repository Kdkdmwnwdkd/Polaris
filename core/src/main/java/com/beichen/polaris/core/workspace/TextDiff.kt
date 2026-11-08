package com.beichen.polaris.core.workspace

/**
 * 行级文本差异（D 批 · 备份差异视图）。
 *
 * ## 解决什么
 * 现在备份只能「退回去」，但用户真正想问的是**「它到底改了什么」**——
 * 点「回滚」是个不可逆的决定，盲点回滚很危险。有了 diff，用户能先看一眼再决定。
 *
 * ## 为什么自己写
 * 项目零第三方依赖（`docs/` 里明确写了的约束），而且只需要**行级** diff，
 * 不需要字符级高亮。用 LCS 动态规划足够，代码不到 100 行，还能进 JVM harness 直接断言。
 *
 * ## 复杂度与保护
 * LCS 是 O(n·m)。两个 500 行的文件 = 25 万次比较，毫秒级；但用户可能贴进来
 * 一个几万行的日志。所以超过 [MAX_CELLS] 时**退化为「整段替换」**：
 * 不做逐行对齐，直接标记「全部删除 + 全部新增」。
 * 宁可粗一点，也不能让 UI 卡住——这是工具类的底线。
 *
 * 纯 Kotlin，无 Android 依赖。
 */
object TextDiff {

    /** 一行差异的类型。 */
    enum class Kind {
        /** 两边都有且相同。 */
        SAME,

        /** 只在新版本里（新增行）。 */
        ADD,

        /** 只在旧版本里（被删除行）。 */
        DEL
    }

    /**
     * 一行差异。
     *
     * @param oldNo 旧文件里的行号（1 起）；[Kind.ADD] 时为 null
     * @param newNo 新文件里的行号（1 起）；[Kind.DEL] 时为 null
     */
    data class Line(
        val kind: Kind,
        val text: String,
        val oldNo: Int?,
        val newNo: Int?
    )

    /** 变更统计。 */
    data class Stats(val added: Int, val removed: Int) {
        val isEmpty: Boolean get() = added == 0 && removed == 0

        /** 给 UI 的一句话摘要，如「+3 −1」。 */
        val label: String
            get() = buildString {
                if (added > 0) append("+$added")
                if (added > 0 && removed > 0) append(" ")
                if (removed > 0) append("−$removed")
                if (isEmpty) append("无变化")
            }
    }

    /** LCS 表格上限（单元格数）。超过就退化为整段替换。 */
    private const val MAX_CELLS = 4_000_000

    /**
     * 计算 [old] → [new] 的行级差异。
     *
     * 末尾换行不影响结果：`"a\n"` 与 `"a"` 视为同一行内容。
     */
    fun lines(old: String, new: String): List<Line> {
        val a = splitLines(old)
        val b = splitLines(new)

        // 退化路径：文件太大，逐行对齐既慢又没意义（用户也看不过来）
        if (a.size.toLong() * b.size.toLong() > MAX_CELLS) {
            return buildList {
                a.forEachIndexed { i, t -> add(Line(Kind.DEL, t, i + 1, null)) }
                b.forEachIndexed { i, t -> add(Line(Kind.ADD, t, null, i + 1)) }
            }
        }

        // LCS 长度表：lcs[i][j] = a[i..] 与 b[j..] 的最长公共子序列长度
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }

        val out = ArrayList<Line>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out += Line(Kind.SAME, a[i], i + 1, j + 1)
                    i++
                    j++
                }
                // 先出删除：同一处改动里"删了旧的"读起来更自然
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    out += Line(Kind.DEL, a[i], i + 1, null)
                    i++
                }
                else -> {
                    out += Line(Kind.ADD, b[j], null, j + 1)
                    j++
                }
            }
        }
        while (i < n) {
            out += Line(Kind.DEL, a[i], i + 1, null)
            i++
        }
        while (j < m) {
            out += Line(Kind.ADD, b[j], null, j + 1)
            j++
        }
        return out
    }

    /** 只算「加了几行 / 删了几行」，不返回明细（UI 摘要用）。 */
    fun stats(old: String, new: String): Stats {
        var added = 0
        var removed = 0
        for (l in lines(old, new)) {
            when (l.kind) {
                Kind.ADD -> added++
                Kind.DEL -> removed++
                Kind.SAME -> Unit
            }
        }
        return Stats(added, removed)
    }

    /**
     * 按行切分。
     *
     * `split("\n")` 对空串会返回 `[""]`（一个空行），这里要显式处理成 0 行——
     * 否则「空文件 vs 一行内容」会算出「删了 1 行空行 + 加了 1 行」，看起来像改了两处。
     */
    private fun splitLines(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val t = if (s.endsWith("\n")) s.dropLast(1) else s
        return t.split("\n")
    }
}
