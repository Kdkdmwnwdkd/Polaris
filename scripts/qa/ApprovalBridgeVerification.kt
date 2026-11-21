/**
 * `ApprovalBridge` 的语义验证（跨应用审批浮层的中转层）。
 *
 * ## 为什么这个文件值得存在
 *
 * 审批浮层有三个「顺序」上的不变量，写错了都不会崩、也不会报错，只会在真机上
 * 表现成「点了没反应」或「卡片留在屏幕上」：
 *
 *  1. **发布即可见**：`publish` 之后 `pending` 必须非空——否则浮层永远不出现，
 *     而应用内对话框又已经被 `overlayReady` 让位了，用户一张卡都看不到。
 *  2. **决策先撤卡、再回调**：`allowOnce` 必须先把 `pending` 置空再调 sink。
 *     反过来的话，用户手指落下到 Agent 循环走完 `finally` 之间有一段时间卡片还在，
 *     用户会以为没点上而连点——第二次点击会打到一个已经 complete 的 deferred 上。
 *  3. **没有 sink 时不能假装成功**：ViewModel 已被销毁（进程还在、服务还在）时，
 *     点浮层必须返回 false 而不是静默吞掉，否则日志里看不出「谁都没接」。
 *
 * 这三条都是纯逻辑，不需要 Android 运行时——所以用 JVM harness 真跑，
 * 而不是在源码里 grep `withdraw()` 出现过几次。
 */

import com.beichen.polaris.automation.ApprovalBridge

private var pass = 0
private var fail = 0

private fun expect(cond: Boolean, msg: String) {
    if (cond) pass++ else {
        fail++
        println("    FAIL  $msg")
    }
}

private fun case(name: String, body: () -> Unit) {
    println("  · $name")
    body()
}

/** 记录调用顺序，并在回调里回读 `pending`——顺序不变量只能这样验。 */
private class Recorder : ApprovalBridge.Sink {
    val calls = mutableListOf<String>()
    var pendingSeenInside: Boolean? = null

    override fun allowOnce() {
        calls += "allowOnce"
        pendingSeenInside = ApprovalBridge.pending.value != null
    }

    override fun allowAlways() {
        calls += "allowAlways"
        pendingSeenInside = ApprovalBridge.pending.value != null
    }

    override fun deny() {
        calls += "deny"
        pendingSeenInside = ApprovalBridge.pending.value != null
    }
}

fun main() {
    println("=== ApprovalBridge 行为验证 ===")

    case("A1 publish 之后 pending 非空，字段原样透传") {
        ApprovalBridge.reset()
        ApprovalBridge.publish("ui_click", "点击「发送」", "本次会话不再询问界面点击与输入", true)
        val p = ApprovalBridge.pending.value
        expect(p != null, "publish 之后 pending 仍是 null——浮层永远不会出现")
        expect(p?.toolName == "ui_click", "工具名被改写：${p?.toolName}")
        expect(p?.summary == "点击「发送」", "摘要被改写：${p?.summary}")
        expect(p?.memorizable == true, "可记忆标记丢了")
    }

    case("A2 withdraw 清空，且重复调用幂等") {
        ApprovalBridge.reset()
        ApprovalBridge.publish("app_launch", "com.tencent.mm", "本次会话不再询问应用安装/卸载/停止", true)
        ApprovalBridge.withdraw()
        expect(ApprovalBridge.pending.value == null, "withdraw 之后还有卡")
        ApprovalBridge.withdraw()
        expect(ApprovalBridge.pending.value == null, "第二次 withdraw 把状态弄脏了")
    }

    case("A3 后一次 publish 覆盖前一次（同一时刻只有一张卡）") {
        ApprovalBridge.reset()
        ApprovalBridge.publish("ui_tap", "第一次", "m1", true)
        ApprovalBridge.publish("ui_input", "第二次", "m2", false)
        val p = ApprovalBridge.pending.value
        expect(p?.toolName == "ui_input", "没有覆盖：${p?.toolName}")
        expect(p?.memorizable == false, "可记忆标记没有跟着更新")
    }

    case("A4 allowOnce：先撤卡，再回调") {
        ApprovalBridge.reset()
        val r = Recorder()
        ApprovalBridge.bind(r)
        ApprovalBridge.publish("ui_click", "点击", "memo", true)
        val handled = ApprovalBridge.allowOnce()
        expect(handled, "有 sink 却返回 false")
        expect(r.calls == listOf("allowOnce"), "回调序列不对：${r.calls}")
        expect(r.pendingSeenInside == false, "回调时卡还在——用户会以为没点上而连点")
        expect(ApprovalBridge.pending.value == null, "决策之后卡没撤")
    }

    case("A5 allowAlways / deny 各自路由到对应方法") {
        ApprovalBridge.reset()
        val r = Recorder()
        ApprovalBridge.bind(r)
        ApprovalBridge.publish("ui_long_press", "长按", "memo", true)
        ApprovalBridge.allowAlways()
        ApprovalBridge.publish("shell_run", "rm -rf /tmp/x", "该操作每次都会单独确认", false)
        ApprovalBridge.deny()
        expect(r.calls == listOf("allowAlways", "deny"), "路由错了：${r.calls}")
        expect(ApprovalBridge.pending.value == null, "deny 之后卡没撤")
    }

    case("A6 没有 sink 时返回 false，不假装成功") {
        ApprovalBridge.reset()
        ApprovalBridge.publish("ui_click", "点击", "memo", true)
        expect(!ApprovalBridge.allowOnce(), "没有 sink 却报成功——日志里会看不出谁都没接")
        expect(!ApprovalBridge.deny(), "没有 sink 却报成功（deny）")
        // 没有 sink 时也不该撤卡：撤了等于「无人生还」，Agent 会等满 30 秒
        expect(ApprovalBridge.pending.value != null, "没有 sink 也把卡撤了，用户看不到任何提示")
    }

    case("A7 bind 可被替换（ViewModel 重建后旧 sink 不得继续接单）") {
        ApprovalBridge.reset()
        val first = Recorder()
        val second = Recorder()
        ApprovalBridge.bind(first)
        ApprovalBridge.bind(second)
        ApprovalBridge.publish("ui_click", "点击", "memo", true)
        ApprovalBridge.allowOnce()
        expect(first.calls.isEmpty(), "旧 sink 还在接单：${first.calls}")
        expect(second.calls == listOf("allowOnce"), "新 sink 没接到：${second.calls}")
    }

    case("A8 overlayReady 默认 false，只能由浮层显式置位") {
        ApprovalBridge.reset()
        expect(ApprovalBridge.overlayReady.value == false, "默认就报 true——应用内对话框会被错误地让位")
        ApprovalBridge.markOverlayReady(true)
        expect(ApprovalBridge.overlayReady.value, "置位没生效")
        ApprovalBridge.markOverlayReady(false)
        expect(!ApprovalBridge.overlayReady.value, "复位没生效")
    }

    case("A9 不可记忆的操作（rm / 提权 shell）标记必须原样带出") {
        ApprovalBridge.reset()
        ApprovalBridge.publish("shell_run", "rm -rf /data/x", "该操作每次都会单独确认", false)
        val p = ApprovalBridge.pending.value
        expect(p?.memorizable == false, "不可记忆被翻成了可记忆——浮层会给用户一个骗人的「不再问」按钮")
    }

    println()
    println("$pass 通过 / $fail 失败")
    if (fail != 0) kotlin.system.exitProcess(1)
}
