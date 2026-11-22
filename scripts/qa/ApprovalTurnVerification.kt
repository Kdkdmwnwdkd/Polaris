import com.beichen.polaris.core.agent.ApprovalTurn

/**
 * 审批记账的三态与「不再打扰」harness（纯 JVM）。
 *
 * ## 守的是什么
 *
 * 2026-11-22 真机：审批卡 30 秒消失 → 又弹一条同样的。两个原因叠在一起：
 *  - `awaitApprovalDecision` 把超时和拒绝都压成 `false`，模型以为用户拒了，换工具再试；
 *  - 应用没有记住「本回合已经没人理」，于是每条新调用都重新弹卡、重新等 30 秒。
 *
 * 这里把它们拆开：三态各自有措辞、超时置位后 `shouldSkipDialog()` 为真。
 * 另外守住「连续拒绝读屏时由 App 自己说真话」——那句话不能指望模型说。
 */

private var pass = 0
private var fail = 0

private fun check(name: String, body: () -> Unit) {
    try {
        body(); pass++; println("  PASS  $name")
    } catch (t: Throwable) {
        fail++; println("  FAIL  $name → ${t.message}")
    }
}

private fun assertTrue(msg: String, cond: Boolean) {
    if (!cond) throw AssertionError(msg)
}

private const val SHOT = "ui_screenshot"
private const val TREE = "ui_tree"
private const val SHELL = "shell_run"

fun main() {
    println("=== 审批记账：超时三态 + 不再打扰 + 连拒说真话 ===")

    check("新回合：截图缓存未命中，也没超时过") {
        val t = ApprovalTurn()
        assertTrue("截图缓存应为空", !t.screenshotApproved)
        assertTrue("不该跳过弹卡", !t.shouldSkipDialog())
    }

    check("允许截图 → 记缓存并按截图组生效") {
        val t = ApprovalTurn()
        assertTrue("措辞", t.record(SHOT, true, true) == "允许")
        assertTrue("截图缓存应置位", t.screenshotApproved)
        assertTrue("允许不该触发「不再弹卡」", !t.shouldSkipDialog())
    }

    check("允许非截图工具 → 不动截图缓存") {
        val t = ApprovalTurn()
        t.record(SHELL, true, false)
        assertTrue("非截图组的允许不该点亮截图缓存", !t.screenshotApproved)
    }

    check("拒绝 → 措辞是「拒绝」，且不置「不再弹卡」") {
        val t = ApprovalTurn()
        assertTrue("措辞", t.record(SHELL, false, false) == "拒绝")
        assertTrue("拒绝只是拒绝，不该当超时", !t.shouldSkipDialog())
    }

    check("超时 → 措辞说「无人回应」，不许说「拒绝」") {
        val t = ApprovalTurn()
        val text = t.record(TREE, null, false)
        assertTrue("没写超时：$text", "超时" in text)
        assertTrue("没说无人回应：$text", "无人回应" in text)
        assertTrue("写成用户拒绝了：$text", "拒绝" !in text)
    }

    check("超时之后 → 本回合不再弹卡") {
        val t = ApprovalTurn()
        t.record(TREE, null, false)
        assertTrue("必须跳过后续弹窗", t.shouldSkipDialog())
    }

    check("三态措辞互不相同（三句话混成一句就又回到原状）") {
        val t = ApprovalTurn()
        val yes = t.record(SHELL, true, false)
        val no = t.record(SHELL, false, false)
        val none = t.record(SHELL, null, false)
        assertTrue("允许/拒绝 撞了：$yes / $no", yes != no)
        assertTrue("拒绝/超时 撞了：$no / $none", no != none)
        assertTrue("允许/超时 撞了：$yes / $none", yes != none)
    }

    check("连续拒绝读屏 2 次 → App 自己说明「与无障碍无关」") {
        var advice: String? = null
        val t = ApprovalTurn(onAdvice = { advice = it })
        t.record(TREE, false, false)
        assertTrue("第一次拒绝不该唠叨", advice == null)
        t.record(SHOT, false, false)
        val text = advice ?: throw AssertionError("第二次拒绝读屏必须给一句提示")
        assertTrue("没说与无障碍无关：$text", "与无障碍开关无关" in text)
        assertTrue("没说是授权问题：$text", "授权" in text)
    }

    check("非读屏工具连拒三次 → 不提示（别把 shell 的拒绝也扯到读屏）") {
        var advice: String? = null
        val t = ApprovalTurn(onAdvice = { advice = it })
        repeat(3) { t.record(SHELL, false, false) }
        assertTrue("不该提示：$advice", advice == null)
    }

    check("允许读屏不累计拒绝次数") {
        var advice: String? = null
        val t = ApprovalTurn(onAdvice = { advice = it })
        t.record(TREE, false, false)
        t.record(TREE, true, false)
        t.record(TREE, false, false)
        assertTrue("中间允许过一次，「连续」就断了，不该提示：$advice", advice == null)
    }

    check("reset 清空三项状态（跨回合不继承）") {
        val t = ApprovalTurn()
        t.record(SHOT, true, true)
        t.record(TREE, null, false)
        t.reset()
        assertTrue("截图缓存没清", !t.screenshotApproved)
        assertTrue("超时标志没清", !t.shouldSkipDialog())
    }

    check("reset 之后连拒计数也归零") {
        var advice: String? = null
        val t = ApprovalTurn(onAdvice = { advice = it })
        t.record(TREE, false, false)
        t.reset()
        t.record(TREE, false, false)
        assertTrue("上一回合的拒绝被算进了这一回合：$advice", advice == null)
    }

    println("\n=== 汇总: PASS=$pass FAIL=$fail ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
