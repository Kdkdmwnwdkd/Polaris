package com.beichen.polaris.core.tool

/**
 * `app_launch` 的 activity 全类名容错。
 *
 * ## 起因（2026-11-22 真机日志）
 *
 * 用户让助手「打开无障碍设置」，模型调 `app_launch(target="设置",
 * activity="com.android.settings.Settings$AccessibilitySettings")`——**少了 `Activity` 后缀**。
 * 真实组件名是 `com.android.settings.Settings$AccessibilitySettingsActivity`。
 * `startActivity` 抛 `ActivityNotFoundException`，工具回了一个 `launch_error`，
 * 模型只好改走别的路（去翻 ui 树），用户看到的是「点了没反应」。
 *
 * 模型不是乱猜：`Settings$AccessibilitySettings` 是这个页面的**类名**（`Settings` 内部类），
 * 而 AndroidManifest 里注册的组件名多一个 `Activity` 后缀。这个后缀对人没有信息量、
 * 对 `ComponentName` 却是致命的。
 *
 * ## 做法
 *
 * 不去猜「哪个系统页面叫什么」，只做两件机械变换，按顺序试：
 *
 *  1. 原样（用户/模型给对的情况，一次命中）；
 *  2. 末尾补 `Activity`；
 *  3. 短名（`Settings$Xxx`）先补 `com.android.settings.` 前缀，再走 1、2。
 *
 * 是否真的存在由 `PackageManager.getActivityInfo` 判定（在 App 侧），
 * 这里只负责生成候选——所以是纯函数，JVM 直接可测。
 *
 * **不猜别的包名**：只有以 `Settings` 开头的短名才补 `com.android.settings.`。
 * 给任意短名都补前缀，会把 `MainActivity` 变成 `com.android.settings.MainActivity`，
 * 那是在制造新的错。
 */
object ActivityTargets {

    /** 系统设置的包名——短名补全只对这家的页面做。 */
    private const val SETTINGS_PKG = "com.android.settings"

    /**
     * 按尝试顺序返回候选全类名。空输入返回空列表（调用方按「没给 activity」处理）。
     *
     * 顺序即优先级：先原样，再补后缀——用户给对了就只试一次。
     */
    fun candidates(raw: String): List<String> {
        val base = raw.trim()
        if (base.isEmpty()) return emptyList()
        // `Settings$AccessibilitySettings` 的 `substringBefore('$')` 是 `Settings`，没有点号，
        // 说明它是个短名；带包名的 `com.android.settings.Settings$X` 才有点号。
        val full = when {
            '.' in base.substringBefore('$') -> base
            base.startsWith("Settings") -> "$SETTINGS_PKG.$base"
            else -> base
        }
        return if (full.endsWith("Activity")) listOf(full) else listOf(full, "$full" + "Activity")
    }
}
