package com.beichen.polaris.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批档位的决策表（2026-11-18）。
 *
 * ## 为什么这张表值得单独钉住
 *
 * `shouldSkipDialog` 是「问不问」的唯一开关，三档 × 高低风险 × 白名单有无 = 12 个格子。
 * 每一个格子的错误方向都是**单向的安全降级**：本该弹窗的静默放行，用户不会有任何感知。
 * 而 harness 里对它的覆盖是 `grep 'STRICT -> false'`——把 `false` 改成 `true` 照样绿。
 *
 * ## 三档的语义（与 KDoc 一致，改实现前先改这里）
 *
 * | 档位 | 白名单命中 | 低风险组 | 高风险组 |
 * |---|---|---|---|
 * | STRICT | 仍弹窗 | 仍弹窗 | 仍弹窗 |
 * | STANDARD | 免弹窗 | 弹窗 | 弹窗 |
 * | AUTO | 免弹窗 | **免弹窗** | 弹窗 |
 */
class ApprovalTierTest {

    private val lowRisk = ApprovalGrouping.GROUP_UI_ACTION
    private val highRisk = ApprovalGrouping.GROUP_SHELL

    // ------------------------------------------------------------ STRICT：一律问

    @Test
    fun `STRICT 任何组合都弹窗`() {
        for (group in listOf(lowRisk, highRisk)) {
            for (allowed in listOf(true, false)) {
                assertFalse(
                    "STRICT 下 group=$group allowed=$allowed 不该免弹窗",
                    ApprovalTier.shouldSkipDialog(ApprovalTier.STRICT, group, allowed)
                )
            }
        }
    }

    // ------------------------------------------------- STANDARD：白名单说了算

    @Test
    fun `STANDARD 只看白名单`() {
        for (group in listOf(lowRisk, highRisk)) {
            assertTrue(
                "STANDARD 下 group=$group 已授权应免弹窗",
                ApprovalTier.shouldSkipDialog(ApprovalTier.STANDARD, group, sessionAllowed = true)
            )
            assertFalse(
                "STANDARD 下 group=$group 未授权必须弹窗",
                ApprovalTier.shouldSkipDialog(ApprovalTier.STANDARD, group, sessionAllowed = false)
            )
        }
    }

    // ------------------------------------------- AUTO：低风险免问，高风险照问

    @Test
    fun `AUTO 低风险组免弹窗`() {
        for (group in listOf(lowRisk, ApprovalGrouping.GROUP_UI_SCREENSHOT,
            ApprovalGrouping.GROUP_DEVICE_OUTPUT, ApprovalGrouping.GROUP_FILE_WRITE)) {
            assertTrue(
                "AUTO 下低风险组 $group 应免弹窗",
                ApprovalTier.shouldSkipDialog(ApprovalTier.AUTO, group, sessionAllowed = false)
            )
        }
    }

    @Test
    fun `AUTO 高风险组仍按白名单判`() {
        assertFalse(
            "AUTO 不该自动放行 shell",
            ApprovalTier.shouldSkipDialog(ApprovalTier.AUTO, highRisk, sessionAllowed = false)
        )
        assertTrue(
            "AUTO 下 shell 用户已授权则免弹窗",
            ApprovalTier.shouldSkipDialog(ApprovalTier.AUTO, highRisk, sessionAllowed = true)
        )
    }

    @Test
    fun `AUTO 白名单集不含任何高风险组`() {
        // 这条防的是「为了少弹窗往 AUTO_ALLOW_GROUPS 里加一组」——加进去就等于永久自动放行。
        val forbidden = listOf(
            ApprovalGrouping.GROUP_SHELL,
            ApprovalGrouping.GROUP_APP_MANAGE,
            ApprovalGrouping.GROUP_GIT_WRITE,
            ApprovalGrouping.GROUP_NETWORK,
        )
        for (group in forbidden) {
            assertFalse("$group 不该出现在 AUTO_ALLOW_GROUPS", group in ApprovalTier.AUTO_ALLOW_GROUPS)
        }
    }

    // ----------------------------------------------------------------- normalize

    @Test
    fun `normalize 非法值退到 STANDARD`() {
        assertEquals(ApprovalTier.STANDARD, ApprovalTier.normalize(null))
        assertEquals(ApprovalTier.STANDARD, ApprovalTier.normalize(""))
        assertEquals(ApprovalTier.STANDARD, ApprovalTier.normalize("AUTO_PLEASE"))
        assertEquals(ApprovalTier.STANDARD, ApprovalTier.normalize("strict"))  // 大小写不能放宽
    }

    @Test
    fun `normalize 合法值原样返回`() {
        for (mode in ApprovalTier.ALL_MODES) assertEquals(mode, ApprovalTier.normalize(mode))
    }

    @Test
    fun `未归一化的档位字符串会落进 STANDARD 分支`() {
        // 调用方忘了 normalize 时的行为：未知值走 else（= STANDARD 语义），
        // 比「抛异常」安全，比「当 STRICT」诚实——这条把该假设写成断言，别让它靠运气。
        assertFalse(ApprovalTier.shouldSkipDialog("WHAT", highRisk, sessionAllowed = false))
        assertTrue(ApprovalTier.shouldSkipDialog("WHAT", highRisk, sessionAllowed = true))
    }

    @Test
    fun `三档文案互不相同且非空`() {
        val texts = ApprovalTier.ALL_MODES.map { ApprovalTier.descriptionOf(it) }
        assertTrue(texts.all { it.isNotBlank() })
        assertNotEquals(texts[0], texts[1])
        assertNotEquals(texts[1], texts[2])
    }
}
