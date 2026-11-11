#!/usr/bin/env kotlin
/**
 * 审计 F15「读通知的授权粒度是「通知」而不是「哪个应用」」验证 harness。
 *
 * ## 这条缺陷的真身
 *
 * F9 给 `device_notifications` 加了审批，看着这件事就做完了。可那个审批的粒度是
 * 「这个工具」：键固定 `private_read:device_notifications`，而且可记忆。
 *
 * 用户为了看「刚才那条微信」勾了「本次会话不再询问」，同意的是——
 *
 * > 本会话内，任意次，读取**全部应用**最近 60 条通知：短信验证码、银行推送、私聊。
 *
 * 与 R-08 的 `shell` 组键是同一个病：**键描述的是工具类别，用户同意的是本次范围**。
 * 它不会以「出错」的形式暴露：弹窗照弹、日志照记、功能正常，唯一的证据是
 * 「用户以为自己只批了微信」。
 *
 * ## 修法
 *
 * 让调用方说清读谁，把范围编进键；不点名就等于全量，而全量**不可记忆**。
 * 判据全部落在 `NotificationScope`（纯函数），过滤落在 `NotificationStore.byPackages`。
 *
 * ## 本 harness 的重点不是「键长什么样」，而是那条不变量
 *
 * **审批键里的包名集合 == 工具实际过滤用的集合。**
 * 只改键不改过滤，就是最典型的假修：弹窗写着「读取通知（微信）」，实际把 60 条
 * 全读走。所以 [D] 组拿同一份 args 走两条路——一条解 `ApprovalScope.of(...).key`
 * 里的包名，一条拿 `NotificationStore.byPackages(NotificationScope.parse(args))`
 * 实际读到的包名——逐项对账。
 *
 * ## 覆盖
 *  A. 解析：缺省/空/单串/数组/脏项/超限 各自落到哪一档
 *  B. 授权键：顺序无关、全量键与点名键不同、可记忆性、老键不再命中
 *  C. 文案：弹窗要说清是哪个应用；全量不能说「本次会话不再询问」
 *  D. 同源不变量：键里的集合 == 实际读到的集合；跨应用不互相放行
 *  E. 边界：8 个可以、9 个拒
 *
 * 运行：scripts/qa/run-notification-scope-verification.sh
 */

import com.beichen.polaris.automation.NotificationRecord
import com.beichen.polaris.automation.NotificationStore
import com.beichen.polaris.core.agent.ApprovalScope
import com.beichen.polaris.core.agent.NotificationFilter
import com.beichen.polaris.core.agent.NotificationScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/* ------------------------------ 迷你断言框架 ------------------------------ */

private var passed = 0
private val failures = mutableListOf<String>()

fun check(name: String, body: () -> Unit) {
    try {
        body(); passed++; println("  PASS  $name")
    } catch (t: Throwable) {
        failures += "$name — ${t.message}"; println("  FAIL  $name — ${t.message}")
    }
}

fun expect(cond: Boolean, msg: () -> String) {
    if (!cond) throw AssertionError(msg())
}

fun <T> eq(actual: T, expected: T, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 <$expected>，实际 <$actual>")
}

/* -------------------------------- 工具函数 -------------------------------- */

private const val TOOL = "device_notifications"
private const val WECHAT = "com.tencent.mm"
private const val SMS = "com.android.mms"
private const val BANK = "com.example.bank"

private fun emptyArgs(): JsonObject = buildJsonObject { }

private fun argsOf(vararg pkgs: String): JsonObject = buildJsonObject {
    put("packages", JsonArray(pkgs.map { JsonPrimitive(it) }))
}

private fun grantOf(args: JsonObject) = ApprovalScope.of(TOOL, args)

private fun filterOf(args: JsonObject): NotificationFilter = NotificationScope.parse(args)

/** 从授权键里解出包名集合（`…:*` / `…:!` 视为空集）。 */
private fun packagesInKey(key: String): Set<String> {
    val suffix = key.removePrefix(NotificationScope.keyPrefix())
    if (suffix == NotificationScope.WILDCARD || suffix == NotificationScope.REJECTED) return emptySet()
    return suffix.split(",").filter { it.isNotBlank() }.toSet()
}

private fun seed() {
    NotificationStore.clear()
    val t = 1_700_000_000_000L
    NotificationStore.add(NotificationRecord(WECHAT, "张三", "晚上吃啥", t, "k1"))
    NotificationStore.add(NotificationRecord(SMS, "10086", "验证码 123456", t + 1, "k2"))
    NotificationStore.add(NotificationRecord(WECHAT, "李四", "收到", t + 2, "k3"))
    NotificationStore.add(NotificationRecord(BANK, "银行", "支出 99 元", t + 3, "k4"))
}

/** 走工具的真实读取路径：解析 → 过滤。 */
private fun readPackages(args: JsonObject): Set<String> {
    val f = filterOf(args)
    val list = if (f is NotificationFilter.Named) {
        NotificationStore.byPackages(f.packages)
    } else {
        NotificationStore.all
    }
    return list.map { it.packageName }.toSet()
}

/* --------------------------------- A 解析 --------------------------------- */

private fun testParse() {
    check("A1 不传 packages = 读全部（All）") {
        eq(filterOf(emptyArgs()), NotificationFilter.All, "缺省档")
    }

    check("A2 packages=null 也是全部，不是「一个都不读」") {
        val a = buildJsonObject { put("packages", kotlinx.serialization.json.JsonNull) }
        eq(filterOf(a), NotificationFilter.All, "null 档")
    }

    check("A3 空数组归到全部（而不是静默返回空结果）") {
        eq(filterOf(argsOf()), NotificationFilter.All, "空数组档")
    }

    check("A4 单个应用") {
        eq(filterOf(argsOf(WECHAT)), NotificationFilter.Named(setOf(WECHAT)), "单应用档")
    }

    check("A5 去首尾空白、转小写、去重") {
        val a = argsOf("  $WECHAT  ", "COM.Android.MMS", WECHAT)
        eq(filterOf(a), NotificationFilter.Named(setOf(WECHAT, SMS)), "规范化后的集合")
    }

    check("A6 只给一个字符串（模型常见写法）也算点名，范围不会被放大") {
        val a = buildJsonObject { put("packages", JsonPrimitive(WECHAT)) }
        eq(filterOf(a), NotificationFilter.Named(setOf(WECHAT)), "字符串档")
    }

    check("A7 数组里混了非字符串 → Invalid（不猜、不放行）") {
        val a = buildJsonObject {
            put("packages", JsonArray(listOf(JsonPrimitive(WECHAT), JsonPrimitive(3))))
        }
        expect(filterOf(a) is NotificationFilter.Invalid) { "数字项应被判为无效，实际 ${filterOf(a)}" }
    }

    check("A8 packages 给成对象 → Invalid") {
        val a = buildJsonObject { put("packages", buildJsonObject { put("app", WECHAT) }) }
        expect(filterOf(a) is NotificationFilter.Invalid) { "对象应被判为无效，实际 ${filterOf(a)}" }
    }

    check("A9 数组全是空白 → 全部，而不是「只读空集」") {
        eq(filterOf(argsOf("", "   ")), NotificationFilter.All, "空白项被丢弃后按全部")
    }
}

/* -------------------------------- B 授权键 -------------------------------- */

private fun testKeys() {
    check("B1 参数顺序不影响键（否则同一份授权会重复弹窗）") {
        eq(
            grantOf(argsOf(WECHAT, SMS)).key,
            grantOf(argsOf(SMS, WECHAT)).key,
            "顺序无关的键"
        )
    }

    check("B2 键前缀是 private_read:device_notifications:") {
        eq(NotificationScope.keyPrefix(), "private_read:device_notifications:", "键前缀")
        expect(grantOf(argsOf(WECHAT)).key.startsWith(NotificationScope.keyPrefix())) {
            "键必须以固定前缀开头，实际 ${grantOf(argsOf(WECHAT)).key}"
        }
    }

    check("B3 全量读取的键与任何点名键都不同") {
        val all = grantOf(emptyArgs()).key
        expect(all != grantOf(argsOf(WECHAT)).key) { "全量键不能与点名键撞车" }
        eq(all, NotificationScope.keyPrefix() + NotificationScope.WILDCARD, "全量键后缀")
    }

    check("B4 全量读取不可记忆（范围不可界定时，一次授权=无限授权）") {
        eq(grantOf(emptyArgs()).memorizable, false, "全量可记忆性")
    }

    check("B5 点名读取可记忆（同意微信，本会话不再问微信）") {
        eq(grantOf(argsOf(WECHAT)).memorizable, true, "点名可记忆性")
    }

    check("B6 范围写坏时不可记忆，也不给可复用的键") {
        val g = grantOf(buildJsonObject { put("packages", buildJsonObject { }) })
        eq(g.memorizable, false, "无效范围可记忆性")
        eq(g.key, NotificationScope.keyPrefix() + NotificationScope.REJECTED, "无效范围键")
    }

    check("B7 升级前写进白名单的老键（无后缀）不再命中新键") {
        val legacy = "private_read:device_notifications"
        expect(!ApprovalScope.allows(grantOf(emptyArgs()), setOf(legacy))) {
            "老的全量授权不能自动升级成本次的全量读取"
        }
        expect(!ApprovalScope.allows(grantOf(argsOf(WECHAT)), setOf(legacy))) {
            "老的全量授权更不能自动变成「只读微信」的授权"
        }
    }
}

/* --------------------------------- C 文案 --------------------------------- */

private fun testLabels() {
    check("C1 单应用时弹窗写明是哪个应用") {
        val g = grantOf(argsOf(WECHAT))
        expect(g.label.indexOf(WECHAT) >= 0) { "文案要带包名，实际：${g.label}" }
    }

    check("C2 多应用时写明数量，用户能判断范围多大") {
        val g = grantOf(argsOf(WECHAT, SMS, BANK))
        expect(g.label.indexOf("3") >= 0) { "文案要带数量，实际：${g.label}" }
    }

    check("C3 全量时说「全部」，不能含糊成「读取通知」") {
        val g = grantOf(emptyArgs())
        expect(g.label.indexOf("全部") >= 0) { "全量文案要明说，实际：${g.label}" }
    }

    check("C4 全量的勾选框不能写「本次会话不再询问」（那会明确骗人）") {
        val g = grantOf(emptyArgs())
        expect(g.memoLabel.indexOf("本次会话不再询问") < 0) {
            "全量读取每次都问，勾选框不能承诺记住，实际：${g.memoLabel}"
        }
    }

    check("C5 点名读取的勾选框带应用名，用户知道记住的是哪个范围") {
        val g = grantOf(argsOf(WECHAT))
        expect(g.memoLabel.indexOf(WECHAT) >= 0) { "勾选框要带范围，实际：${g.memoLabel}" }
    }
}

/* ----------------------------- D 同源不变量 ------------------------------- */

private fun testSameSource() {
    check("D1 键里的包名集合 == 实际读到的包名集合（单应用）") {
        seed()
        val a = argsOf(WECHAT)
        eq(packagesInKey(grantOf(a).key), setOf(WECHAT), "键里的集合")
        eq(readPackages(a), setOf(WECHAT), "实际读到的集合")
    }

    check("D2 键里的包名集合 == 实际读到的包名集合（多应用，含脏输入）") {
        seed()
        val a = argsOf(" $SMS ", "COM.ANDROID.MMS", WECHAT)
        val inKey = packagesInKey(grantOf(a).key)
        eq(inKey, setOf(WECHAT, SMS), "键里的集合")
        eq(readPackages(a), inKey, "实际读到的必须与键里的一致")
    }

    check("D3 只读点名的应用：微信 2 条 + 短信 1 条，只批微信就只拿微信") {
        seed()
        val f = filterOf(argsOf(WECHAT))
        val list = NotificationStore.byPackages((f as NotificationFilter.Named).packages)
        eq(list.size, 2, "微信条数")
        expect(list.all { it.packageName == WECHAT }) { "不能夹带其他应用：$list" }
    }

    check("D4 不点名时读全部（功能没被砍掉）") {
        seed()
        eq(readPackages(emptyArgs()), setOf(WECHAT, SMS, BANK), "全部档读到的应用")
    }

    check("D5 大小写不同的包名同样能读到（键与过滤同时折叠）") {
        seed()
        val a = argsOf("COM.TENCENT.MM")
        eq(readPackages(a), setOf(WECHAT), "大写包名读到的集合")
        eq(packagesInKey(grantOf(a).key), setOf(WECHAT), "大写包名进键时也折叠")
    }

    check("D6 空集合不读任何东西（fail-closed：读不到 优于 读到全部）") {
        seed()
        eq(NotificationStore.byPackages(emptySet()).size, 0, "空集合结果")
    }

    check("D7 同意读微信，不放行读短信") {
        seed()
        val wechat = grantOf(argsOf(WECHAT))
        val sms = grantOf(argsOf(SMS))
        expect(wechat.key != sms.key) { "两个应用的键必须不同" }
        expect(!ApprovalScope.allows(sms, setOf(wechat.key))) { "批了微信不能顺带读短信" }
        expect(!ApprovalScope.allows(wechat, setOf(sms.key))) { "反过来同样不成立" }
    }

    check("D8 同意读微信，也不放行「微信+短信」这一档（范围变大要重问）") {
        seed()
        val wechat = grantOf(argsOf(WECHAT))
        val both = grantOf(argsOf(WECHAT, SMS))
        expect(!ApprovalScope.allows(both, setOf(wechat.key))) { "扩大范围必须重新确认" }
    }

    check("D9 同意读「微信+短信」，之后单独读微信仍要问（授权不向下拆）") {
        seed()
        val both = grantOf(argsOf(WECHAT, SMS))
        val wechat = grantOf(argsOf(WECHAT))
        expect(!ApprovalScope.allows(wechat, setOf(both.key))) { "授权只覆盖它写的那个集合" }
    }

    check("D10 同一个应用集合重复调用不再问") {
        seed()
        val g = grantOf(argsOf(WECHAT))
        expect(ApprovalScope.allows(g, setOf(g.key))) { "同一个键应命中" }
    }
}

/* --------------------------------- E 边界 --------------------------------- */

private fun testBoundary() {
    check("E1 恰好 8 个应用可以") {
        val eight = (1..NotificationScope.MAX_PACKAGES).map { "com.example.app$it" }
        expect(filterOf(argsOf(*eight.toTypedArray())) is NotificationFilter.Named) {
            "8 个应当是有效范围，实际 ${filterOf(argsOf(*eight.toTypedArray()))}"
        }
    }

    check("E2 9 个应用被拒（不静默截断：截断会让弹窗范围与实际读取不一致）") {
        val nine = (1..NotificationScope.MAX_PACKAGES + 1).map { "com.example.app$it" }
        expect(filterOf(argsOf(*nine.toTypedArray())) is NotificationFilter.Invalid) {
            "9 个应当被拒，实际 ${filterOf(argsOf(*nine.toTypedArray()))}"
        }
    }

    check("E3 超过上限时不可记忆，避免「批一次以后随便读」") {
        val nine = (1..NotificationScope.MAX_PACKAGES + 1).map { "com.example.app$it" }
        eq(grantOf(argsOf(*nine.toTypedArray())).memorizable, false, "超限可记忆性")
    }

    check("E4 上限常量就是 8（改它会在这一行被发现）") {
        eq(NotificationScope.MAX_PACKAGES, 8, "上限")
    }
}

/* --------------------------------- 入口 ----------------------------------- */

fun main() {
    println("== F15 读通知的授权粒度（按应用） ==")
    println("[A] 解析")
    testParse()
    println("[B] 授权键")
    testKeys()
    println("[C] 文案")
    testLabels()
    println("[D] 同源不变量")
    testSameSource()
    println("[E] 边界")
    testBoundary()
    NotificationStore.clear()
    println()
    println("$passed 通过 / ${failures.size} 失败")
    if (failures.isNotEmpty()) {
        failures.forEach { println("FAILED: $it") }
        kotlin.system.exitProcess(1)
    }
}
