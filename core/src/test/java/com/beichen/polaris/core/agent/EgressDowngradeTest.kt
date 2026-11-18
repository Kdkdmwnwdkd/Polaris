package com.beichen.polaris.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本回合读过私有内容之后，外发审批降级为一次性」的决策表（2026-11-19）。
 *
 * ## 为什么从 ChatViewModel 搬出来
 *
 * 这段判定原来长在 `requestApproval` 里，是一行 `taintedNow && group in EGRESS_GROUPS` 加一个
 * `copy(memorizable = false)`。它依赖 ViewModel（Android 类），JVM 测不到，于是它**唯一的覆盖
 * 是行为段 harness 里 grep 源码**——而 harness 在 CI 上是 SKIP。
 *
 * 这是 lethal trifecta 的第三道闸：私有数据 + 不可信内容 + 外发通道。前两道（`EgressGuard`
 * 拦没有审批的 `web_*`、`ShellCommandRules` 拦越界读）各有一批断言，唯独这一道没有。
 *
 * ## 判据边的三种错法
 *
 * | 错法 | 后果 | 用户感知 |
 * |---|---|---|
 * | 完全不降级 | `read_file → http_request` 复用上周的域名授权，零弹窗外发 | 无 |
 * | 一刀切降级 | 所有审批都变「每次问」，一天点几十次后开始闭眼点 | 烦，然后失效 |
 * | 降级但 key 变了 | 干净回合里点过的「始终允许」也记不住，域名记忆彻底废掉 | 每次都问，以为是 bug |
 *
 * 三条都写成了断言。
 */
class EgressDowngradeTest {

    private fun args(vararg kv: Pair<String, String>): JsonObject =
        JsonObject(kv.associate { (k, v) -> k to JsonPrimitive(v) })

    private val readmeArgs = args("command" to "cat README.md")

    // ------------------------------------------------------- 没读过：维持原判

    @Test
    fun `未污染时网络外发仍按域名记忆`() {
        val a = args("url" to "https://example.com/collect", "method" to "POST")
        val grant = ApprovalScope.effectiveGrant("http_request", a, taintedNow = false)
        assertTrue("没读过私有内容的外发是正常用法，不该降级", grant.memorizable)
        assertFalse("未污染时文案不该出现「本回合」", grant.label.contains("本回合"))
    }

    @Test
    fun `未污染时 GitHub 写操作仍可记忆`() {
        val grant = ApprovalScope.effectiveGrant("github_commit", args("message" to "x"), taintedNow = false)
        assertTrue(grant.memorizable)
    }

    // ----------------------------------------------------------- 读过：降级

    @Test
    fun `污染后网络外发降级为不可记忆`() {
        val grant = ApprovalScope.effectiveGrant(
            "http_request",
            args("url" to "https://example.com/collect", "method" to "POST"),
            taintedNow = true,
        )
        assertFalse("读过私有内容后仍可「始终允许」——这就是零弹窗外发链", grant.memorizable)
    }

    @Test
    fun `污染后 GitHub 写操作降级为不可记忆`() {
        // read_file → github_commit → push：commit 本身要审批，但 GIT_WRITE 组是可记忆的。
        // 用户点过一次「始终允许」之后，这条链就再没有弹窗。
        val grant = ApprovalScope.effectiveGrant("github_commit", args("message" to "x"), taintedNow = true)
        assertFalse("GIT_WRITE 组漏在降级范围外", grant.memorizable)
    }

    @Test
    fun `污染后换域名依然是不可记忆的`() {
        // 域名记忆的盲区正是「同一个域」。这条确认降级不是靠「把 key 换掉」实现的——
        // 换 key 只会让下一次干净回合也记不住。
        val a = ApprovalScope.effectiveGrant("http_request", args("url" to "https://a.example/x"), true)
        val b = ApprovalScope.effectiveGrant("http_request", args("url" to "https://b.example/x"), true)
        assertFalse(a.memorizable)
        assertFalse(b.memorizable)
    }

    // ------------------------------------------------- 不降级：范围必须与风险同宽

    @Test
    fun `污染后普通 shell 不降级`() {
        val grant = ApprovalScope.effectiveGrant("shell_run", readmeArgs, taintedNow = true)
        assertTrue(
            "shell 不在外发组里，一刀切降级会让用户一天点几十次",
            grant.memorizable
        )
    }

    @Test
    fun `污染后文件写入不降级`() {
        val grant = ApprovalScope.effectiveGrant(
            "write_file",
            args("path" to "notes.md", "content" to "x"),
            taintedNow = true,
        )
        assertTrue("写自己的文件不构成外发", grant.memorizable)
    }

    // ------------------------------------------------------------- 幂等与文案

    @Test
    fun `本来就不可记忆的档不重复加提示`() {
        // rm 的 grant 本来就是 memorizable=false。再叠一句「本回合读过私有内容」
        // 只是让同一个命令在不同回合显示不同类别名，用户无法形成稳定预期。
        val grant = ApprovalScope.effectiveGrant(
            "shell_run",
            args("command" to "rm -rf /sdcard/Download"),
            taintedNow = true,
        )
        assertFalse("前置：rm 本就不可记忆", grant.memorizable)
        assertFalse("已经每次问的档不该再解释一遍", grant.label.contains("本回合"))
    }

    @Test
    fun `降级文案说明原因`() {
        val grant = ApprovalScope.effectiveGrant("http_request", args("url" to "https://example.com/x"), true)
        assertTrue(
            "不说明原因的话，用户会以为刚点过的允许丢了：${grant.label}",
            grant.label.contains("本回合读过私有内容")
        )
    }

    @Test
    fun `降级不改 key`() {
        // key 变了 = 干净回合里点过的「始终允许」永远命中不了，域名记忆整段失效。
        val a = args("url" to "https://example.com/collect")
        val before = ApprovalScope.of("http_request", a)
        val after = ApprovalScope.effectiveGrant("http_request", a, taintedNow = true)
        assertEquals("降级只该动 memorizable 与 label，不该换授权键", before.key, after.key)
        assertNotEquals("但要确实降级了", before.memorizable, after.memorizable)
    }

    @Test
    fun `降级后的授权永远不命中白名单`() {
        // 防「旧数据/别处写入」：即使这个 key 已经在 sessionAllowed 里（上周允许过），
        // 本回合读过私有内容之后也不能命中。
        val a = args("url" to "https://example.com/collect")
        val grant = ApprovalScope.effectiveGrant("http_request", a, taintedNow = true)
        assertFalse(
            "旧白名单替这一次签了字",
            ApprovalScope.allows(grant, setOf(grant.key))
        )
    }

    // --------------------------------------------------------------- 单一真源

    @Test
    fun `判据与降级同源`() {
        // isEgressTurn 决定「绕过免打扰通道」，effectiveGrant 决定「能不能记住」。
        // 两处各算一次 group 就会漂移：降级生效但通道没绕过，用户看到弹窗一闪而过。
        val cases = listOf(
            "http_request" to args("url" to "https://example.com/x"),
            "github_commit" to args("message" to "x"),
            "shell_run" to readmeArgs,
            "write_file" to args("path" to "a.md", "content" to "x"),
            "ui_tap" to args("x" to "10", "y" to "20"),
        )
        for ((tool, a) in cases) {
            val base = ApprovalScope.of(tool, a)
            if (!base.memorizable) continue
            val downgraded = ApprovalScope.effectiveGrant(tool, a, taintedNow = true)
            assertEquals(
                "$tool：isEgressTurn 与 effectiveGrant 的判定不一致",
                ApprovalScope.isEgressTurn(tool, taintedNow = true),
                base.memorizable != downgraded.memorizable,
            )
        }
    }

    @Test
    fun `EGRESS_GROUPS 只含外发通道`() {
        assertTrue(ApprovalGrouping.GROUP_NETWORK in ApprovalScope.EGRESS_GROUPS)
        assertTrue(ApprovalGrouping.GROUP_GIT_WRITE in ApprovalScope.EGRESS_GROUPS)
        // 往里加一个非外发组 = 永久把那个组变成「每次问」。安全的方向是加窄，不是加宽。
        val forbidden = listOf(
            ApprovalGrouping.GROUP_FILE_WRITE,
            ApprovalGrouping.GROUP_UI_ACTION,
            ApprovalGrouping.GROUP_UI_SCREENSHOT,
            ApprovalGrouping.GROUP_APP_MANAGE,
            ApprovalGrouping.GROUP_SHELL,
            ApprovalGrouping.GROUP_DEVICE_OUTPUT,
        )
        for (group in forbidden) {
            assertFalse("$group 不是外发通道，不该进 EGRESS_GROUPS", group in ApprovalScope.EGRESS_GROUPS)
        }
    }

    @Test
    fun `两个外发工具真的归到外发组`() {
        // 上面几条都是拿「组」做输入。若工具与组的映射断了（改名、目录笔误），
        // 判定会在真实链路上永远 false——测试全绿，防护为零。
        assertEquals(ApprovalGrouping.GROUP_NETWORK, ApprovalGrouping.groupOf("http_request"))
        assertEquals(ApprovalGrouping.GROUP_GIT_WRITE, ApprovalGrouping.groupOf("github_commit"))
    }
}
