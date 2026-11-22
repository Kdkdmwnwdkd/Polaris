import com.beichen.polaris.core.tool.ActivityTargets

/**
 * `app_launch` 的 activity 全类名容错（纯 JVM）。
 *
 * 真机（2026-11-22）：模型给的是 `com.android.settings.Settings$AccessibilitySettings`，
 * 少一个 `Activity` 后缀 → 组件不存在 → `launch_error`。候选生成规则见 ActivityTargets。
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

fun main() {
    println("=== app_launch：activity 候选 ===")

    check("缺 Activity 后缀：先原样试，再补后缀（真机那条）") {
        val c = ActivityTargets.candidates("com.android.settings.Settings\$AccessibilitySettings")
        assertTrue("候选数 ${c.size}", c.size == 2)
        assertTrue("第一个必须是原样（用户给对时只试一次）：$c", c[0] == "com.android.settings.Settings\$AccessibilitySettings")
        assertTrue("第二个要补 Activity：$c", c[1] == "com.android.settings.Settings\$AccessibilitySettingsActivity")
    }

    check("已经带 Activity：只有 1 个候选，不做多余探测") {
        val c = ActivityTargets.candidates("com.x.MainActivity")
        assertTrue("$c", c == listOf("com.x.MainActivity"))
    }

    check("短名 Settings\$X：先补 com.android.settings. 前缀") {
        val c = ActivityTargets.candidates("Settings\$AccessibilitySettings")
        assertTrue("$c", c[0] == "com.android.settings.Settings\$AccessibilitySettings")
        assertTrue("$c", c[1].endsWith("AccessibilitySettingsActivity"))
    }

    check("短名已经带 Activity：补前缀后不再重复加后缀") {
        val c = ActivityTargets.candidates("Settings\$AccessibilitySettingsActivity")
        assertTrue("$c", c == listOf("com.android.settings.Settings\$AccessibilitySettingsActivity"))
    }

    check("别的应用的短名不补 settings 包名（补了就是制造新错）") {
        val c = ActivityTargets.candidates("MainActivity")
        assertTrue("$c", c[0] == "MainActivity")
        assertTrue("$c", c.none { "com.android.settings" in it })
    }

    check("空输入 → 空列表（调用方按「没给 activity」处理）") {
        assertTrue("空串", ActivityTargets.candidates("").isEmpty())
        assertTrue("纯空白", ActivityTargets.candidates("   ").isEmpty())
    }

    check("首尾空白被清掉（复制粘贴常带空格）") {
        val c = ActivityTargets.candidates("  com.x.MainActivity  ")
        assertTrue("$c", c == listOf("com.x.MainActivity"))
    }

    check("候选里不含重复项") {
        listOf(
            "com.android.settings.Settings\$AccessibilitySettings",
            "Settings\$X",
            "com.x.MainActivity"
        ).forEach { raw ->
            val c = ActivityTargets.candidates(raw)
            assertTrue("$raw → $c", c.size == c.toSet().size)
        }
    }

    println("\n=== 汇总: PASS=$pass FAIL=$fail ===")
    if (fail > 0) kotlin.system.exitProcess(1)
}
