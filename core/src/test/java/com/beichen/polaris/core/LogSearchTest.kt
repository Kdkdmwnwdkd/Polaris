package com.beichen.polaris.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志搜索判据（2026-11-25）。
 *
 * 重点不在「能不能搜到」，而在两个会静默出错的地方：
 * ① 多个关键词互相包含时区间重叠，按区间拼字符串会把同一段文字拼两遍；
 * ② 用户输入里有正则特殊字符（`(`、`[`、`\`）时不能崩。
 * 这两条的坏法都不会抛异常，只会让用户看到一段内容错乱的日志。
 */
class LogSearchTest {

    // —— 拆词 ——

    @Test
    fun `空查询拆出零个关键词`() {
        assertEquals(emptyList<String>(), LogSearch.parseTerms(""))
        assertEquals(emptyList<String>(), LogSearch.parseTerms("   "))
        assertEquals(emptyList<String>(), LogSearch.parseTerms("\t\n "))
    }

    @Test
    fun `连续空格与制表符不会产出空关键词`() {
        // 空关键词会命中一切（contains("") 恒真），一个空格就能把过滤变成空操作
        assertEquals(listOf("a", "b"), LogSearch.parseTerms("  a \t  b  "))
    }

    @Test
    fun `中文关键词原样保留`() {
        assertEquals(listOf("超时", "重试"), LogSearch.parseTerms("超时 重试"))
    }

    // —— 命中判定 ——

    @Test
    fun `多个关键词是同时命中而不是任一命中`() {
        val line = "13:46:24.657 INFO [Turn] tool.start tool=http_request args=..."
        assertTrue(LogSearch.matches(line, LogSearch.parseTerms("http_request")))
        assertTrue(LogSearch.matches(line, LogSearch.parseTerms("http_request tool.start")))
        // 只有其中一个的行不算命中——OR 语义会让「所有 http 调用」和「所有带 401 的行」
        // 一起倒出来，搜完还得再翻一遍
        assertFalse(LogSearch.matches(line, LogSearch.parseTerms("http_request 401")))
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(LogSearch.matches("ERROR [Chat] boom", listOf("error")))
        assertTrue(LogSearch.matches("error [Chat] boom", listOf("ERROR")))
    }

    @Test
    fun `没有关键词时一切命中`() {
        // 清空搜索框必须回到「显示全部」，不能变成「一条都不显示」
        assertTrue(LogSearch.matches("任意一行", emptyList()))
    }

    @Test
    fun `关键词按整行匹配所以能搜到 tag`() {
        val line = "13:46:24.657 INFO [AgentLoop] 派发 step=0 本批 2 个"
        assertTrue(LogSearch.matches(line, listOf("AgentLoop")))
    }

    // —— 高亮区间 ——

    @Test
    fun `单次命中返回一段区间`() {
        val text = "code=http_401 error=true"
        assertEquals(listOf(10..12), LogSearch.ranges(text, listOf("401")))
    }

    @Test
    fun `同一关键词出现多次全部标出`() {
        assertEquals(listOf(0..2, 8..10), LogSearch.ranges("401 ... 401", listOf("401")))
    }

    @Test
    fun `互相包含的关键词合并成一段`() {
        // `http` 与 `http_request` 都命中开头，不合并会得到 0..3 与 0..11 两段，
        // 调用方按区间切片就会把 "http" 拼两遍
        assertEquals(listOf(0..11), LogSearch.ranges("http_request 失败", listOf("http", "http_request")))
        assertEquals(listOf(0..11), LogSearch.ranges("http_request 失败", listOf("http_request", "http")))
    }

    @Test
    fun `相邻区间合并`() {
        assertEquals(listOf(0..2), LogSearch.ranges("abc", listOf("a", "bc")))
    }

    @Test
    fun `无命中返回空区间而不是整个文本`() {
        assertEquals(emptyList<IntRange>(), LogSearch.ranges("abc", listOf("zzz")))
        assertEquals(emptyList<IntRange>(), LogSearch.ranges("", listOf("a")))
    }

    @Test
    fun `正则特殊字符不会抛异常`() {
        // 关键词来自用户输入，如果走正则，`(` 会直接抛 PatternSyntaxException
        val text = "结果 (a+b) [x] \\d end"
        assertEquals(listOf(3..3), LogSearch.ranges(text, listOf("(")))
        assertEquals(listOf(4..5), LogSearch.ranges(text, listOf("a+")))
        assertEquals(listOf(13..14), LogSearch.ranges(text, listOf("\\d")))
    }

    /**
     * 最重要的一条：按区间切片重建原文必须与原文逐字节相同。
     *
     * 这直接覆盖「重复拼」「丢字」两种坏法——它们不抛异常，只让用户看到一段
     * 内容错乱的日志，而错乱的日志比没有日志更容易得出错误结论。
     */
    @Test
    fun `按区间重建的文本与原文完全一致`() {
        val samples = listOf(
            "13:46:41.835 tool.end tool=http_request code=http_401 error=true" to listOf("http", "http_request", "401"),
            "ERROR ERROR ERROR" to listOf("error"),
            "混合 中文 与 abcABC 和 空" to listOf("中文", "abc", "空"),
            "abc" to listOf("a", "bc", "abc"),
            "no-match-here" to listOf("zzz")
        )
        samples.forEach { (text, terms) ->
            val rebuilt = buildString {
                var cursor = 0
                LogSearch.ranges(text, terms).forEach { r ->
                    append(text.substring(cursor, r.first))
                    append(text.substring(r.first, r.last + 1))
                    cursor = r.last + 1
                }
                if (cursor < text.length) append(text.substring(cursor))
            }
            assertEquals("样本：$text / $terms", text, rebuilt)
        }
    }

    @Test
    fun `区间互不重叠且有序`() {
        val ranges = LogSearch.ranges("http_request http_request", listOf("http", "request"))
        ranges.zipWithNext().forEach { (a, b) ->
            assertTrue("区间 $a 与 $b 重叠或乱序", a.last < b.first)
        }
    }
}
