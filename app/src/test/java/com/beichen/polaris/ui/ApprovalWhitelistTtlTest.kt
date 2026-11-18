package com.beichen.polaris.ui

import com.beichen.polaris.core.agent.ApprovalGrant
import com.beichen.polaris.core.agent.ApprovalScope
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「始终允许」的过期语义（2026-11-18）。
 *
 * 这组断言存在的理由：授权窗口的长度是**安全属性**，但它在真机上完全不可见——
 * 用户看不到「这张票还有 4 分钟到期」，只有「昨天点过的允许，今天怎么又要问」的困惑。
 * 一旦有人把 [ApprovalWhitelistStore.TTL_MS] 改大、或者把 `contains` 里的过期判断
 * 删掉，任何 harness 都不会红（它们只 grep 源码文本）。所以在 JVM 上把它钉住。
 *
 * 为什么用可注入时钟而不是 `Thread.sleep(30 分钟)`：单测不能跑 30 分钟，
 * 也不该为了可测把 TTL 做成参数曝光给业务代码。
 */
class ApprovalWhitelistTtlTest {

    /** 假时钟，测试自己推进它。 */
    private var now = 1_700_000_000_000L

    private val conversation = "conv-ttl-test"

    @After
    fun tearDown() {
        ApprovalWhitelistStore.resetForTest()
    }

    private fun store(): MutableSet<String> {
        ApprovalWhitelistStore.clock = { now }
        return ApprovalWhitelistStore.of(conversation)
    }

    /** 可记忆的授权（普通工具，如 write_file）。 */
    private fun memorizable(key: String) = ApprovalGrant(key, "文件写入", memorizable = true)

    @Test
    fun `有效期内命中`() {
        val set = store()
        val grant = memorizable("file_write")
        set += grant.key

        now += ApprovalWhitelistStore.TTL_MS - 1
        assertTrue("到期前 1ms 仍应免问", ApprovalScope.allows(grant, set))
    }

    @Test
    fun `超过有效期不再命中`() {
        val set = store()
        val grant = memorizable("file_write")
        set += grant.key

        now += ApprovalWhitelistStore.TTL_MS
        assertFalse("到期时刻起必须重新确认", ApprovalScope.allows(grant, set))
        assertFalse("过期键应被顺手清掉", grant.key in set)
    }

    @Test
    fun `再次批准会续期`() {
        val set = store()
        val grant = memorizable("file_write")
        set += grant.key

        // 第 29 分钟用户又点了一次「始终允许」——窗口从这一刻重新算。
        now += 29L * 60 * 1000
        set += grant.key

        // 从第一次批准算已过 50 分钟，从续期算只过 21 分钟。
        now += 21L * 60 * 1000
        assertTrue("续期后应从续期时刻重新计时", ApprovalScope.allows(grant, set))
    }

    @Test
    fun `不可记忆的授权即使写入也不命中`() {
        val set = store()
        val grant = ApprovalGrant("shell:rm -rf /sdcard/x", "shell 命令", memorizable = false)
        set += grant.key

        assertFalse(
            "ApprovalScope.allows 必须挡住不可记忆档（防御别处误写）",
            ApprovalScope.allows(grant, set)
        )
    }

    @Test
    fun `不同对话的授权互不影响`() {
        val set = store()
        set += "file_write"

        ApprovalWhitelistStore.clock = { now }
        val other = ApprovalWhitelistStore.of("conv-other")

        assertTrue("本对话命中", "file_write" in set)
        assertFalse("另一个对话不应继承", "file_write" in other)
    }
}
