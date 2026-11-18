package com.beichen.polaris.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `shell_run` 读边界判定的 JVM 断言（2026-11-18 从 harness 迁移）。
 *
 * ## 为什么要有这一份
 *
 * 同样的用例在 `scripts/qa/ShellReadBoundaryVerification.kt` 里有一份，但那份要靠
 * `run-shell-read-boundary-verification.sh` 现拼 kotlinc 才能跑——沙箱里缺 kotlinc、
 * CI 里路径一变，整段就 SKIP，**看起来绿了其实什么都没验**。本文件用 `./gradlew
 * :core:test` 跑，编译器由 Gradle 提供，不存在「环境不具备」这条路。
 *
 * ## 覆盖的正是 P1-1 那个真实缺陷
 *
 * `cat ../../../../../../sdcard/DCIM/a.jpg` 曾经零弹窗读相册：判据只认绝对路径。
 * [A5b]/[A5c]/[A5d] 三条断言把它钉死——任何人把相对路径解析删掉，这里立刻红。
 */
class ShellCommandRulesTest {

    private fun argsOf(command: String): JsonObject =
        buildJsonObject { put("command", JsonPrimitive(command)) }

    private fun shellGrant(command: String) = ApprovalScope.of("shell_run", argsOf(command))

    /** 断言这条命令命中的同意粒度。 */
    private fun assertScope(command: String, expected: String?) =
        assertEquals("「$command」的同意粒度", expected, ShellCommandRules.sensitiveReadRoot(command))

    // ---------------------------------------------------------------- A 识别

    @Test
    fun `A1 相册单个文件命中相册目录`() {
        assertScope("cat /sdcard/DCIM/Camera/IMG_0001.jpg", "/sdcard/DCIM")
    }

    @Test
    fun `A2 只 ls 根时粒度是根本身`() {
        assertScope("ls /sdcard", "/sdcard")
    }

    @Test
    fun `A3 外置存储路径`() {
        assertScope("head -n 20 /storage/emulated/0/Download/a.csv", "/storage/emulated")
    }

    @Test
    fun `A4 其它应用私有目录`() {
        assertScope("ls /data/data/com.tencent.mm/files", "/data/data/com.tencent.mm")
    }

    @Test
    fun `A5 普通相对路径不触发`() {
        assertScope("cat notes.txt", null)
        assertScope("grep -r todo src", null)
    }

    @Test
    fun `A5b 相对路径穿越必须命中`() {
        // 修前返回 null：这是 P1-1 的原始缺陷形态。
        assertScope("cat ../../../../../../sdcard/DCIM/a.jpg", "/sdcard/DCIM")
        assertScope("head -n 5 ../../../storage/emulated/0/Download/a.csv", "/storage/emulated")
    }

    @Test
    fun `A5c cd 之后的相对路径按新目录解析`() {
        assertScope("cd /sdcard && cat DCIM/a.jpg", "/sdcard/DCIM")
        assertScope("cd /sdcard/DCIM; head -n 1 Camera/a.jpg", "/sdcard/DCIM")
    }

    @Test
    fun `A5d 带重定向的穿越仍命中读目录`() {
        // 本函数只回答「读的是哪个目录」；「要不要每次都问」由 isMemorizable 先判。
        assertScope("cat ../../sdcard/DCIM/a.jpg > /tmp/x", "/sdcard/DCIM")
    }

    @Test
    fun `A6 系统只读位置不触发`() {
        assertScope("cat /proc/cpuinfo", null)
        assertScope("cat /system/build.prop", null)
        assertScope("ls /data/local/tmp", null)
    }

    @Test
    fun `A7 前缀陷阱`() {
        assertScope("cat /sdcardevil/x.jpg", null)
        assertScope("cat /data/database/x", null)
    }

    @Test
    fun `A8 管道与串接里任意一段命中即触发`() {
        assertScope("ls | wc -l", null)
        assertScope("ls /sdcard/DCIM | wc -l", "/sdcard/DCIM")
        assertScope("cat a.txt && cat /sdcard/b.txt", "/sdcard/b.txt")
    }

    @Test
    fun `A9 绝对路径的命令名不影响判定`() {
        assertScope("/system/bin/cat /sdcard/DCIM/x", "/sdcard/DCIM")
    }

    @Test
    fun `A10 引号与长选项里的路径也认`() {
        assertScope("grep -F needle \"/sdcard/My Files/a.txt\"", "/sdcard/My")
        assertScope("grep --file=/sdcard/patterns.txt a.txt", "/sdcard/patterns.txt")
    }

    @Test
    fun `A11 find 的目标目录`() {
        assertScope("find /sdcard/DCIM -name \"*.jpg\"", "/sdcard/DCIM")
    }

    // ------------------------------------------------------------ B 规范化

    @Test
    fun `B1 穿越后按真实目录算`() {
        assertScope("cat /sdcard/../data/data/x/y.db", "/data/data/x")
        assertScope("cat /sdcard/DCIM/../Download/a.csv", "/sdcard/Download")
    }

    @Test
    fun `B2 重复分隔符与点被折叠`() {
        assertScope("cat //sdcard///DCIM/./x.jpg", "/sdcard/DCIM")
    }

    @Test
    fun `B3 尾随标点被剥掉`() {
        assertScope("cat /sdcard/DCIM/x.jpg,", "/sdcard/DCIM")
    }

    // ------------------------------------------------------ C 授权键与作用域

    @Test
    fun `C1 受保护读的键带目录且可记忆`() {
        val g = shellGrant("cat /sdcard/DCIM/x.jpg")
        assertEquals("${ApprovalGrouping.GROUP_SHELL_READ}:/sdcard/DCIM", g.key)
        assertTrue(g.memorizable)
        assertEquals("读取 /sdcard/DCIM", g.label)
        assertEquals("本次会话不再询问读取 /sdcard/DCIM", g.memoLabel)
    }

    @Test
    fun `C2 换目录必须重问`() {
        val g = shellGrant("cat /sdcard/DCIM/x.jpg")
        assertFalse(
            "同意过下载目录不该放行相册",
            ApprovalScope.allows(g, setOf("${ApprovalGrouping.GROUP_SHELL_READ}:/sdcard/Download"))
        )
        assertTrue(
            "同意过相册，同目录应放行",
            ApprovalScope.allows(g, setOf("${ApprovalGrouping.GROUP_SHELL_READ}:/sdcard/DCIM"))
        )
    }

    @Test
    fun `C3 同意 shell 组不等于同意读相册`() {
        val g = shellGrant("cat /sdcard/DCIM/x.jpg")
        assertFalse(
            "`npm install` 的不再询问被顺带用来放行读相册，就是 F7 本身",
            ApprovalScope.allows(g, setOf(ApprovalGrouping.GROUP_SHELL))
        )
    }

    @Test
    fun `C4 普通只读命令仍是纯 shell 组`() {
        val g = shellGrant("cat notes.txt")
        assertEquals(ApprovalGrouping.GROUP_SHELL, g.key)
        assertTrue(g.memorizable)
        assertNull(ShellCommandRules.sensitiveReadRoot("cat notes.txt"))
    }

    @Test
    fun `C5 不可记忆的授权写进白名单也不命中`() {
        // 防御别处误写：`approveAlways` 只在 memorizable 时才写，但白名单是可变集合，
        // 任何一条「顺手 add」的代码都能把它塞进去。allows 是最后一道，必须自己判。
        val g = ApprovalGrant("shell_unmemorizable:rm -rf /sdcard/x", "shell 命令", memorizable = false)
        assertFalse(ApprovalScope.allows(g, setOf(g.key)))
    }

    // -------------------------------------------------------------- D 不变量

    @Test
    fun `D1 带重定向的读仍落每次都问`() {
        val g = shellGrant("cat /sdcard/DCIM/x.jpg > /sdcard/DCIM/y.jpg")
        assertTrue(
            "应落不可记忆档（实际 ${g.key}）",
            g.key.startsWith(ApprovalGrouping.GROUP_SHELL_UNMEMORIZABLE)
        )
        assertFalse(g.memorizable)
        assertFalse(
            "读相册的授权被用来放行往相册写文件",
            ApprovalScope.allows(g, setOf("${ApprovalGrouping.GROUP_SHELL_READ}:/sdcard/DCIM"))
        )
    }

    @Test
    fun `D2 写命令不归读档`() {
        assertFalse(shellGrant("cp /sdcard/DCIM/x.jpg /sdcard/Download/").memorizable)
        assertFalse(shellGrant("rm -rf /sdcard/DCIM").memorizable)
        assertTrue(shellGrant("rm -rf /sdcard/DCIM").key.startsWith(ApprovalGrouping.GROUP_SHELL_DESTRUCTIVE))
    }

    @Test
    fun `D3 包装器里的读仍不可记忆`() {
        assertFalse(shellGrant("busybox cat /sdcard/DCIM/x.jpg").memorizable)
    }

    @Test
    fun `D4 提权身份优先于读档`() {
        val args = buildJsonObject {
            put("command", JsonPrimitive("cat /sdcard/DCIM/x.jpg"))
            put("identity", JsonPrimitive("shizuku"))
        }
        val g = ApprovalScope.of("shell_run", args)
        assertTrue("提权应先命中（实际 ${g.key}）", g.key.startsWith(ApprovalGrouping.GROUP_SHELL_ELEVATED))
        assertFalse(g.memorizable)
    }

    @Test
    fun `D5 读档只对 shell 工具生效`() {
        assertFalse(ApprovalScope.of("http_request", argsOf("x")).key.startsWith(ApprovalGrouping.GROUP_SHELL_READ))
        assertEquals("read_file", ApprovalScope.of("read_file", argsOf("x")).key)
    }

    // ---------------------------------------------------------------- E 回归

    @Test
    fun `E1 只读命令仍然免审批`() {
        assertFalse(ShellCommandRules.needsApproval("cat /sdcard/DCIM/x.jpg"))
        assertFalse(ShellCommandRules.needsApproval("ls /sdcard"))
        assertTrue(ShellCommandRules.needsApproval("npm install"))
    }

    @Test
    fun `E2 构建类命令的授权未变`() {
        val g = shellGrant("npm install")
        assertEquals(ApprovalGrouping.GROUP_SHELL, g.key)
        assertTrue(g.memorizable)
    }

    @Test
    fun `E3 系统信息类命令不触发读档`() {
        assertNull(ShellCommandRules.sensitiveReadRoot("getprop ro.product.model"))
        assertNull(ShellCommandRules.sensitiveReadRoot("ps -A"))
        assertNull(ShellCommandRules.sensitiveReadRoot("df -h"))
    }

    @Test
    fun `E4 空命令不炸`() {
        assertNull(ShellCommandRules.sensitiveReadRoot(""))
        assertNull(ShellCommandRules.sensitiveReadRoot("   "))
    }
}
