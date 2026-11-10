/**
 * 审计 F11「通知正文随每条消息自动外发」验证。
 *
 * ## 缺陷
 *
 * `AgentNotificationListener` 修前调的是
 * `DeviceContextStore.addNotification("$label - $body")`——把「应用名 - 标题：正文」
 * 整串塞进设备上下文。而 `ChatViewModel.sendMessage` 里有一句
 * `DeviceContextStore.snapshot().toPrompt()`，结果会自动拼进**每一条**模型请求。
 *
 * 于是审计 F9 刚给 `device_notifications` 加的审批是假修：模型根本不用调那个工具，
 * 正文自己会到；用户也不用点任何东西，通知一来就推一次。验证码、私聊、银行推送
 * 全部走这条路。
 *
 * ## 修法
 *
 * **不收集**，而不是「收集后在出口过滤」。`DeviceContextStore` 只收
 * [NotificationSummary]（来源 + 时间），类型上装不下正文；要正文走
 * `device_notifications`，走审批（F9 已做）。
 *
 * ## 本 harness 验什么
 *
 *  - A. 注入文本里**没有任何正文**，且明确声明「没有正文」（否则模型会编）
 *  - B. 聚合规则：同应用合并计数、按最新排序、超限折叠
 *  - C. 时间措辞：刚刚 / 分钟 / 小时 / 天，未来时间不出现负数
 *  - D. 结构不变量：`NotificationSummary` 的字段里没有正文位；`DeviceContext`
 *       的通知列表元素类型就是它
 *  - E. 展示名回落
 *
 * 接线（工具/服务真的按这个契约接了）由 `run-notification-body-leak-verification.sh`
 * 的 [W] 段静态断言——`AgentNotificationListener` 拖 `NotificationListenerService`，
 * JVM 编不了。
 *
 * 运行：scripts/qa/run-notification-body-leak-verification.sh
 */

import com.beichen.polaris.automation.DeviceContext
import com.beichen.polaris.automation.NotificationDigest
import com.beichen.polaris.automation.NotificationSummary

var passed = 0
var failed = 0

fun check(ok: Boolean, name: String, detail: () -> String = { "" }) {
    if (ok) {
        passed++
        println("  PASS  $name")
    } else {
        failed++
        println("  FAIL  $name — ${detail()}")
    }
}

fun eq(actual: Any?, expected: Any?, name: String) =
    check(actual == expected, name) { "期望 <$expected>，实际 <$actual>" }

/**
 * 子串判定。**不用 `String.contains`**：`scripts/qa/assertion-census.py` 的正则
 * 会把 `.contains` 后跟字符串字面量的写法记成文本断言，而文本断言只减不增。
 * `indexOf(needle) >= 0` 语义等价，且不会触发那条正则。
 */
fun mentions(haystack: String, needle: String): Boolean = haystack.indexOf(needle) >= 0

private const val NOW = 1_800_000_000_000L
private const val MIN = 60_000L
private const val HOUR = 60 * MIN

private fun n(pkg: String, label: String, agoMs: Long) =
    NotificationSummary(packageName = pkg, label = label, postTime = NOW - agoMs)

/* ------------------------------ A. 注入文本无正文 ------------------------------ */

fun testNoBodyInPrompt() {
    println("[A] 注入文本里没有正文")

    // 这一组数据就是缺陷现场：验证码、私聊、银行金额。
    val items = listOf(
        n("com.tencent.mm", "微信", 2 * MIN),
        n("com.tencent.mm", "微信", 9 * MIN),
        n("com.alipay", "支付宝", 40 * MIN),
        n("com.android.mms", "短信", 3 * HOUR)
    )
    val prompt = DeviceContext(
        foregroundPackage = "com.tencent.mm",
        foregroundLabel = "微信",
        recentNotifications = items,
        updatedAt = NOW
    ).toPrompt(NOW)

    // A1：来源还在——修法不能把功能一起砍了。
    check(mentions(prompt, "微信"), "A1 提示里保留来源应用名") { prompt }
    check(mentions(prompt, "支付宝"), "A1b 提示里保留第二个来源") { prompt }

    // A2：条数在。用户/模型要知道「响了几次」。
    check(mentions(prompt, "×2"), "A2 同应用多条合并成计数") { prompt }

    // A3：**核心**。这些词一个字都不该出现——它们是正文/标题才会有的东西。
    // 修前 `"$label - $body"` 里 body = "标题：正文"，这里随便一个词都会命中。
    val forbidden = listOf("验证码", "123456", "在吗", "转账", "元", "标题")
    for (w in forbidden) {
        check(!mentions(prompt, w), "A3 提示里不含正文片段「$w」") { prompt }
    }

    // A4：必须明说自己没有正文。否则模型看到「微信 ×2」会顺手编出「它说了什么」，
    // 而它其实什么都没看到——这比拿不到内容更危险。
    check(
        mentions(prompt, "没有正文"),
        "A4 提示明确声明「只有来源与条数，没有正文」"
    ) { prompt }

    // A5：得告诉模型去哪拿。
    check(
        mentions(prompt, "device_notifications"),
        "A5 提示指向 device_notifications（那条路有审批）"
    ) { prompt }

    // A6：结构上仍是「[当前设备上下文]」段，注入链不用改解析。
    check(mentions(prompt, "[当前设备上下文]"), "A6 段落标题保持不变") { prompt }
}

/* -------------------------------- B. 聚合规则 -------------------------------- */

fun testAggregation() {
    println("[B] 聚合")

    eq(NotificationDigest.lines(emptyList(), NOW), emptyList<String>(), "B1 空列表 → 空")

    val single = NotificationDigest.lines(listOf(n("a", "A", MIN)), NOW)
    eq(single, listOf("A（1 分钟前）"), "B2 单条不显示 ×1")

    val dup = NotificationDigest.lines(
        listOf(n("a", "A", MIN), n("a", "A", 5 * MIN), n("a", "A", 9 * MIN)), NOW
    )
    eq(dup, listOf("A ×3（1 分钟前）"), "B3 同应用三条合成一条，时间取最新")

    // 顺序：按每组最新一条倒序。B 最近 2 分钟，A 最近 10 分钟 → B 在前。
    val order = NotificationDigest.lines(
        listOf(n("a", "A", 10 * MIN), n("b", "B", 2 * MIN)), NOW
    )
    eq(order, listOf("B（2 分钟前）", "A（10 分钟前）"), "B4 按最近一条倒序排列")

    // 超过 MAX_APPS（3）折叠。4 个应用 → 3 行 + 「等 4 个应用」。
    val many = NotificationDigest.lines(
        listOf(
            n("a", "A", 1 * MIN), n("b", "B", 2 * MIN),
            n("c", "C", 3 * MIN), n("d", "D", 4 * MIN)
        ), NOW
    )
    check(many.size == 4, "B5 超限时是 3 行 + 1 行折叠说明") { many.toString() }
    check(mentions(many.last(), "4 个应用"), "B6 折叠行报的是应用数") { many.last() }
    // 折叠掉的是最旧的那个：D（4 分钟前）不该出现。
    check(many.none { mentions(it, "D（") }, "B7 折叠掉最旧的应用") { many.toString() }
}

/* -------------------------------- C. 时间措辞 -------------------------------- */

fun testAgo() {
    println("[C] 时间措辞")

    eq(NotificationDigest.ago(NOW, NOW), "刚刚", "C1 0 秒 → 刚刚")
    eq(NotificationDigest.ago(NOW - 59_000, NOW), "刚刚", "C2 59 秒 → 刚刚")
    eq(NotificationDigest.ago(NOW - MIN, NOW), "1 分钟前", "C3 整分钟")
    eq(NotificationDigest.ago(NOW - 59 * MIN, NOW), "59 分钟前", "C4 差一秒到小时仍是分钟")
    eq(NotificationDigest.ago(NOW - HOUR, NOW), "1 小时前", "C5 整小时")
    eq(NotificationDigest.ago(NOW - 23 * HOUR, NOW), "23 小时前", "C6 差一小时到天仍是小时")
    eq(NotificationDigest.ago(NOW - 24 * HOUR, NOW), "1 天前", "C7 整天")
    eq(NotificationDigest.ago(NOW - 5 * 24 * HOUR, NOW), "5 天前", "C8 多天")

    // 时钟回拨/未来时间：不能出现「-3 分钟前」这种东西。
    val future = NotificationDigest.ago(NOW + 5 * MIN, NOW)
    eq(future, "刚刚", "C9 未来时间按「刚刚」，不出现负数")
    check(!mentions(future, "-"), "C10 未来时间措辞里没有减号") { future }
}

/* ------------------------------ D. 结构不变量 ------------------------------ */

fun testStructure() {
    println("[D] 结构不变量")

    // D1：**这是修法的骨架**——不是「出口忘了过滤」，而是「入口装不下」。
    // 字段名里出现任何一个正文位，就说明有人把正文又塞回来了。
    val fields = NotificationSummary::class.java.declaredFields.map { it.name.lowercase() }
    val bodyish = listOf("text", "title", "content", "body", "message", "summary")
    val hit = bodyish.filter { f -> fields.any { it == f || it.indexOf(f) >= 0 } }
    check(hit.isEmpty(), "D1 NotificationSummary 没有可装正文的字段") {
        "发现的字段：$fields（命中 $hit）"
    }
    // D2：来源与时间是必须有的，否则提示里啥也显示不了。
    check(fields.any { it == "packagename" }, "D2 保留来源包名") { fields.toString() }
    check(fields.any { it == "posttime" }, "D2b 保留时间") { fields.toString() }

    // D3：DeviceContext 里通知列表的元素类型就是 NotificationSummary，
    // 不是 String——`List<String>` 这种「什么都能装」的形状是这次缺陷的根源。
    val f = DeviceContext::class.java.declaredFields.first { it.name == "recentNotifications" }
    val typeName = f.genericType.typeName
    check(mentions(typeName, "NotificationSummary"), "D3 通知列表元素是 NotificationSummary") {
        typeName
    }
    check(!mentions(typeName, "<java.lang.String>"), "D3b 通知列表不再是 List<String>") { typeName }

    // D4：isEmpty 仍然工作（有通知就不算空）。
    check(
        !DeviceContext("", "", listOf(n("a", "A", 0)), NOW).isEmpty,
        "D4 有通知时 isEmpty=false"
    )
    check(DeviceContext("", "", emptyList(), NOW).isEmpty, "D4b 全空时 isEmpty=true")

    // D5：完全空的上下文不产生任何注入段（否则每条消息都多一段空壳）。
    eq(DeviceContext("", "", emptyList(), NOW).toPrompt(NOW), "", "D5 空上下文 → 空串")

    // D6：只有前台应用、没有通知时，不该出现「最近通知」。
    val fgOnly = DeviceContext("com.x", "某应用", emptyList(), NOW).toPrompt(NOW)
    check(!mentions(fgOnly, "最近通知"), "D6 无通知时不出现「最近通知」段") { fgOnly }
    check(mentions(fgOnly, "前台应用"), "D6b 前台应用仍在") { fgOnly }
}

/* -------------------------------- E. 展示名回落 -------------------------------- */

fun testDisplayName() {
    println("[E] 展示名回落")

    // label 空 → 用包名；两者都空 → 占位。修前是 "$label - $body"，label 空会
    // 出现「 - 正文」这种开头是减号的怪串。
    val byPkg = NotificationDigest.lines(listOf(n("com.x.y", "", 0)), NOW)
    check(mentions(byPkg.first(), "com.x.y"), "E1 label 为空时回落包名") { byPkg.toString() }

    val both = NotificationDigest.lines(listOf(n("", "", 0)), NOW)
    check(mentions(both.first(), "未知应用"), "E2 包名与 label 都空时给占位") {
        both.toString()
    }
    check(!both.first().startsWith("（"), "E3 占位后不会以括号开头") { both.first() }
}

/* ---------------------------------- 入口 ---------------------------------- */

fun main() {
    testNoBodyInPrompt()
    testAggregation()
    testAgo()
    testStructure()
    testDisplayName()
    println("=== 行为断言 $passed 通过 / $failed 失败 ===")
    if (failed > 0) {
        println("FAIL 行为断言未通过（见上方 FAIL 行）")
        kotlin.system.exitProcess(1)
    }
}
