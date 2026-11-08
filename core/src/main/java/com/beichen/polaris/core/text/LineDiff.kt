package com.beichen.polaris.core.text

/**
 * 一行差异。
 *
 * @param kind `' '` 上下文 / `'+'` 新增 / `'-'` 删除
 */
data class DiffLine(val kind: Char, val text: String) {
    /** unified diff 风格的前缀行。 */
    fun render(): String = "$kind$text"
}

/**
 * 行级 diff（W-34）：LCS + 公共前后缀裁剪，输出 unified 风格的 hunk。
 *
 * ## 为什么自己写
 * 只为了「看一眼改了哪几行」，引 diff-match-patch 之类要几万行代码。
 * LCS 五十行就够，且能完全控制退化策略。
 *
 * ## 两个必须有的保护
 *  1. **先裁公共前后缀**：实际改动通常只占文件很小一段，裁完剩下的规模往往从几千行降到几十行；
 *  2. **规模上限**：裁完仍超过 [MAX_CELLS] 就不做逐行 LCS（DP 表会吃掉几十 MB），
 *     退化为「整段替换」并明确标注——宁可粗，不可卡死或 OOM。
 */
object LineDiffLogic {

    /** LCS DP 表的格子上限（n×m）；超过就退化。约 4MB 的 int 表。 */
    const val MAX_CELLS = 250_000

    /** 输出的最大行数（超出截断，避免一次 diff 撑爆上下文）。 */
    const val MAX_OUTPUT_LINES = 400

    /** 退化模式下每侧展示的示例行数。 */
    private const val PREVIEW = 80

    /**
     * 计算两段文本的行级差异，只保留改动附近 [context] 行上下文。
     *
     * @return 差异行；完全相同返回空列表
     */
    fun diff(oldText: String, newText: String, context: Int = 3): List<DiffLine> {
        val oldLines = splitLines(oldText)
        val newLines = splitLines(newText)

        // 1) 公共前缀
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size &&
            oldLines[prefix] == newLines[prefix]
        ) prefix++

        // 2) 公共后缀（不与前缀重叠）
        var suffix = 0
        while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
            oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
        ) suffix++

        val oldMid = oldLines.subList(prefix, oldLines.size - suffix)
        val newMid = newLines.subList(prefix, newLines.size - suffix)

        if (oldMid.isEmpty() && newMid.isEmpty()) return emptyList()

        // 3) 中段 LCS（或退化）
        val midDiff: List<DiffLine> = if (oldMid.size.toLong() * newMid.size > MAX_CELLS) {
            degradedDiff(oldMid, newMid)
        } else {
            lcsDiff(oldMid, newMid)
        }

        // 4) 拼上上下文（前后各 context 行），再按 hunk 折叠
        val withContext = ArrayList<DiffLine>()
        for (i in (prefix - context).coerceAtLeast(0) until prefix) {
            withContext += DiffLine(' ', oldLines[i])
        }
        withContext += midDiff
        for (i in 0 until minOf(context, suffix)) {
            withContext += DiffLine(' ', oldLines[oldLines.size - suffix + i])
        }
        return withContext.take(MAX_OUTPUT_LINES)
    }

    /**
     * 按行切分；**空文本视为 0 行**。
     *
     * `"".split('\n')` 在 Kotlin 里返回 `[""]`（一个空行），会把"新增文件"算成
     * "先删一个空行再新增 N 行"。这里显式处理掉。
     */
    private fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split('\n')

    /**
     * 超大改动的退化输出：**两边各取前 [PREVIEW] 行示例**。
     *
     * 为什么不做"整段全列"：旧实现全列 600 删 + 600 增，再被 [MAX_OUTPUT_LINES] 截断，
     * 结果用户只看到一屏删除、一个新增都没有——看起来像"文件被删光了"。
     * 平衡预览 + 明确写总数，比"列一屏但被砍一半"诚实。
     */
    private fun degradedDiff(oldMid: List<String>, newMid: List<String>): List<DiffLine> = buildList {
        add(
            DiffLine(
                ' ',
                "…（改动范围过大，未逐行比对：删除 ${oldMid.size} 行 / 新增 ${newMid.size} 行，" +
                    "下面各展示前 $PREVIEW 行）"
            )
        )
        oldMid.take(PREVIEW).forEach { add(DiffLine('-', it)) }
        if (oldMid.size > PREVIEW) add(DiffLine(' ', "…（还有 ${oldMid.size - PREVIEW} 行删除未显示）"))
        newMid.take(PREVIEW).forEach { add(DiffLine('+', it)) }
        if (newMid.size > PREVIEW) add(DiffLine(' ', "…（还有 ${newMid.size - PREVIEW} 行新增未显示）"))
    }

    /**
     * 中段 LCS 差异。
     *
     * DP 表：`dp[i][j]` = old 从 i 到末尾、new 从 j 到末尾的 LCS 长度。
     * 从 (0,0) 正向走：相等就输出上下文并双进；否则按 dp 值决定"删"还是"增"。
     */
    private fun lcsDiff(old: List<String>, new: List<String>): List<DiffLine> {
        val n = old.size
        val m = new.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (old[i] == new[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        val out = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                old[i] == new[j] -> {
                    out += DiffLine(' ', old[i]); i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out += DiffLine('-', old[i]); i++
                }
                else -> {
                    out += DiffLine('+', new[j]); j++
                }
            }
        }
        while (i < n) { out += DiffLine('-', old[i]); i++ }
        while (j < m) { out += DiffLine('+', new[j]); j++ }
        return out
    }

    /** 统计新增/删除行数（给一句话摘要用，不含上下文行）。 */
    fun countChanges(lines: List<DiffLine>): Pair<Int, Int> {
        val added = lines.count { it.kind == '+' }
        val removed = lines.count { it.kind == '-' }
        return added to removed
    }

    /** 人话摘要：`+3 -1`；无改动返回「无差异」。 */
    fun summary(lines: List<DiffLine>): String {
        if (lines.isEmpty()) return "无差异"
        val (added, removed) = countChanges(lines)
        return "+$added  -$removed"
    }
}
