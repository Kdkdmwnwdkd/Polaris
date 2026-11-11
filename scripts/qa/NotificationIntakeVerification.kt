// 审计 F18：通知入库判据。
//
// 为什么这条能真跑：NotificationIntake 零 Android 依赖（只有 String 比较）。
// 拖 Android 的是 AgentNotificationListener（NotificationListenerService），
// 判据被抽出来就是为了让这里能逐条断言，而不是靠 grep 猜。
//
// 运行：scripts/qa/run-notification-intake-verification.sh

import com.beichen.polaris.automation.NotificationIntake

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, body: () -> Unit) {
    try {
        body()
        passed++
        println("  PASS  $name")
    } catch (t: Throwable) {
        failures += "$name — ${t.message}"
        println("  FAIL  $name — ${t.message}")
    }
}

private fun eq(actual: Any?, expected: Any?, what: String) {
    if (actual != expected) throw AssertionError("$what：期望 <$expected>，实际 <$actual>")
}

private const val SELF = "com.beichen.polaris"

fun main() {
    println("F18 通知入库：哪些该收")

    println("\n[A] 收不收")
    check("A1 自己发的通知不收（回环 + 正文含用户输入）") {
        eq(NotificationIntake.shouldIngest(SELF, SELF), false, "本应用自己发的通知")
    }
    check("A2 别的应用发的通知要收") {
        eq(NotificationIntake.shouldIngest("com.tencent.mm", SELF), true, "微信")
        eq(NotificationIntake.shouldIngest("com.android.shell", SELF), true, "shell")
    }
    check("A3 拿不到包名的不收（来源不明，F15 的范围也划不出来）") {
        eq(NotificationIntake.shouldIngest("", SELF), false, "空包名")
        eq(NotificationIntake.shouldIngest("   ", SELF), false, "全空白包名")
    }
    check("A4 包名只是前缀相同要收（防 startsWith 误判）") {
        eq(NotificationIntake.shouldIngest("$SELF.debug", SELF), true, "$SELF.debug 是另一个包")
        eq(NotificationIntake.shouldIngest("com.beichen.polaris2", SELF), true, "尾号不同的包")
    }
    check("A5 自身包名未知时放行（判据不完整，不该把所有通知吞掉）") {
        eq(NotificationIntake.shouldIngest("com.tencent.mm", ""), true, "selfPackage 为空")
    }
    check("A6 大小写不折叠（Android 包名本身小写，折叠反而会误杀）") {
        eq(NotificationIntake.shouldIngest("COM.BEICHEN.POLARIS", SELF), true, "全大写不是同一个包")
    }

    println()
    if (failures.isNotEmpty()) {
        println("失败清单：")
        failures.forEach { println("  - $it") }
    }
    println("=== 合计 $passed 通过 / ${failures.size} 失败 ===")
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}
