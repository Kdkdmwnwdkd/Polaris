/**
 * 审批未通过文案（[com.beichen.polaris.core.agent.DenyMessage]）的行为验证。
 *
 * ## 为什么要有这个 harness
 *
 * 2026-11-22 的真机日志里，用户连拒三次读屏工具，模型随后断言
 * 「我读屏幕的操作被系统拒绝（**无障碍服务没生效**）」——同一台机器半分钟前
 * 刚有一次 `ui_click` 成功。无障碍是好的，错在文案：把只读的读屏叫「危险操作」，
 * 又提示「可改换非危险工具完成」，模型于是换了个还是读屏的工具，再被拒，
 * 只能得出「设备能力坏了」。
 *
 * 这类错误不崩、不报错、日志里看不出异常，只能靠对文案本身的断言钉住。
 * 所以这里测的不是「有没有这个函数」，而是**每句话会不会把模型带沟里**。
 *
 * ## 能真跑
 *
 * `DenyMessage` 只依赖 `ApprovalGrouping`（纯数据 + 纯函数），
 * 三个文件即可编译，不需要 Android、不需要序列化插件。
 */

import com.beichen.polaris.core.agent.ApprovalGrouping
import com.beichen.polaris.core.agent.DenyMessage

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, body: () -> Unit) {
    try {
        body()
        println("  PASS  $name")
        passed++
    } catch (e: Throwable) {
        println("  FAIL  $name  -> ${e.message}")
        failures += name
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

fun main() {
    println("[D] 审批未通过文案")

    // ── 分档：谁能被误读成「能力不可用」 ──
    check("D1 读屏工具被认出来，点击/命令不算") {
        assertTrue("ui_tree 应算读屏", DenyMessage.isScreenRead("ui_tree"))
        assertTrue("ui_screenshot 应算读屏", DenyMessage.isScreenRead("ui_screenshot"))
        assertTrue("ui_tap 不该算读屏", !DenyMessage.isScreenRead("ui_tap"))
        assertTrue("ui_click 不该算读屏", !DenyMessage.isScreenRead("ui_click"))
        assertTrue("shell_run 不该算读屏", !DenyMessage.isScreenRead("shell_run"))
    }

    check("D2 读屏被拒不许再说「危险」") {
        val t = DenyMessage.denied("ui_tree")
        assertTrue("message 仍带「危险」：${t.message}", "危险" !in t.message)
        assertTrue("message 仍带「危险」：${t.hint}", "危险" !in t.hint)
    }

    check("D3 读屏被拒要说清「与无障碍无关」") {
        val t = DenyMessage.denied("ui_tree")
        assertTrue("hint 没提无障碍：${t.hint}", "无障碍" in t.hint)
        assertTrue("hint 没说明二者无关：${t.hint}", "无关" in t.hint)
    }

    check("D4 读屏被拒不许诱导「换个工具绕过去」") {
        val t = DenyMessage.denied("ui_tree")
        assertTrue("hint 仍在诱导换工具：${t.hint}", "改换非危险工具" !in t.hint)
        assertTrue("hint 没提「不要重复」：${t.hint}", "不要重复" in t.hint)
    }

    check("D5 读屏被拒要明确禁止自己去翻无障碍设置") {
        val t = DenyMessage.denied("ui_screenshot")
        assertTrue("没禁止自行打开无障碍设置页：${t.hint}", "无障碍设置" in t.hint)
        assertTrue("没禁止自行判断状态：${t.hint}", "判断" in t.hint)
    }

    check("D6 非读屏被拒也不许夸大成功「危险」") {
        val t = DenyMessage.denied("shell_run")
        assertTrue("message 仍带「危险」：${t.message}", "危险" !in t.message)
        assertTrue("hint 仍带「危险」：${t.hint}", "危险" !in t.hint)
        assertTrue("message 没说清是谁拒的：${t.message}", "用户" in t.message)
    }

    check("D6b 四条路径的 message 与 hint 一律不许出现「危险」") {
        // 「危险」是工具目录里的审批等级枚举名（Capability.DANGEROUS），
        // 泄漏到给模型看的语义层就会被读成「这工具本身有问题」。
        val all = listOf(
            DenyMessage.denied("ui_tree"),
            DenyMessage.denied("ui_screenshot"),
            DenyMessage.denied("ui_tap"),
            DenyMessage.denied("shell_run"),
            DenyMessage.noApprover("ui_tap", "当前是后台通道"),
            DenyMessage.channelFailed("ui_tap")
        )
        all.forEach { t ->
            assertTrue("message 出现「危险」：${t.message}", "危险" !in t.message)
            assertTrue("hint 出现「危险」：${t.hint}", "危险" !in t.hint)
        }
    }

    check("D6c 四条路径都不许诱导「换个工具绕过去」") {
        // 「可改换非危险工具完成」是给有副作用的工具写的退路。套在读屏上，
        // 模型会换一个还是读屏的工具，再被拒，然后判定设备能力坏了（真机日志实证）。
        // 对非读屏工具同样有害：它诱导绕过，而不是停下来问用户。
        val banned = listOf("改换非危险工具", "换个工具", "改换工具")
        val all = listOf(
            DenyMessage.denied("ui_tree"),
            DenyMessage.denied("ui_tap"),
            DenyMessage.noApprover("ui_tap", "当前是后台通道"),
            DenyMessage.channelFailed("ui_tap")
        )
        all.forEach { t ->
            banned.forEach { word ->
                assertTrue("hint 仍含「$word」：${t.hint}", word !in t.hint)
            }
        }
    }

    check("D7 三条路径都咬住「不要重复发起相同调用」") {
        val all = listOf(
            DenyMessage.denied("ui_tree"),
            DenyMessage.denied("ui_tap"),
            DenyMessage.noApprover("ui_tap", "当前通道没有审批界面"),
            DenyMessage.channelFailed("ui_tap")
        )
        all.forEach { t ->
            assertTrue("有一条没写「不要重复发起相同调用」：${t.hint}", "不要重复发起相同调用" in t.hint)
        }
    }

    check("D8 通道出错必须说「不是用户拒绝」——否则日志和模型都会记错账") {
        val t = DenyMessage.channelFailed("ui_tap")
        assertTrue("没区分「不是用户拒绝」：${t.hint}", "不是用户拒绝" in t.hint)
        assertTrue("把通道故障说成了用户拒绝：${t.message}", "用户拒绝" !in t.message)
    }

    check("D9 没有审批通道时要把原因带出来，不能吞掉") {
        val reason = "当前是后台通道"
        val t = DenyMessage.noApprover("ui_tap", reason)
        assertTrue("原因被吞了：${t.message}", reason in t.message)
    }

    check("D10 三类文案两两不同，不是一句话套所有场景") {
        val a = DenyMessage.denied("ui_tree").message
        val b = DenyMessage.denied("ui_tap").message
        val c = DenyMessage.channelFailed("ui_tap").message
        assertTrue("读屏被拒与点击被拒说成同一句", a != b)
        assertTrue("用户拒绝与通道故障说成同一句", b != c)
        assertTrue("读屏被拒与通道故障说成同一句", a != c)
    }

    check("D11 没见过的工具名不许崩，也不许误判成读屏") {
        val weird = listOf("", "mcp__foo__bar", "ui_tree_v2", "未知工具")
        weird.forEach { name ->
            val t = DenyMessage.denied(name)
            assertTrue("空名/未知名的文案空了：$name", t.message.isNotBlank() && t.hint.isNotBlank())
            assertTrue("把 $name 误判成读屏", !DenyMessage.isScreenRead(name))
        }
    }

    check("D12 归组键自洽：读屏组的键确实是 UI_SCREENSHOT") {
        assertTrue(
            "ui_tree 的归组变了：${ApprovalGrouping.groupOf("ui_tree")}",
            ApprovalGrouping.groupOf("ui_tree") == ApprovalGrouping.GROUP_UI_SCREENSHOT
        )
        assertTrue(
            "ui_screenshot 的归组变了",
            ApprovalGrouping.groupOf("ui_screenshot") == ApprovalGrouping.GROUP_UI_SCREENSHOT
        )
    }

    println("\n通过 $passed 条，${failures.size} 失败")
    if (failures.isNotEmpty()) {
        println("失败项：" + failures.joinToString("；"))
        kotlin.system.exitProcess(1)
    }
}
