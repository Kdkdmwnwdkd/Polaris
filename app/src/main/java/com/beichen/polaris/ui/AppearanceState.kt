package com.beichen.polaris.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 外观偏好（W-64）：明暗模式、强调色、动态取色。
 *
 * ## 为什么从 `rememberSaveable` 改成全局单例
 *
 * 原来主题三选只活在 Activity 的 `rememberSaveable` 里——进程一死就回默认。
 * 这对「明暗」勉强说得通（反正跟随系统也够用），但**自定义强调色不落盘等于白设**：
 * 用户挑了个色，重启回到金色，只会觉得这个功能是坏的。
 *
 * 所以这里用一个 Compose 可观察的单例承载，写盘走 SharedPreferences（三项都是
 * 极小的标量，犯不上开 DataStore）。
 *
 * ## 为什么是 object 而不是 ViewModel
 *
 * 颜色要在 `AgentKitTheme` 里生效——它在 `setContent` 的最外层，比任何 ViewModel
 * 都早，也比任何页面都活得久。读 `mutableStateOf` 即自动订阅，改一处，全 App 重组。
 */
object AppearanceState {

    private const val PREF = "appearance_v1"
    private const val K_MODE = "theme_mode"
    private const val K_ACCENT = "accent_hex"
    private const val K_DYNAMIC = "dynamic_color"

    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    /**
     * 自定义强调色的 `#RRGGBB`；null = 用品牌默认色（暖纸青碧）。
     *
     * 存字符串而不是 Color：Color 是 Compose 类型，落盘要额外编码，且字符串能直接回显到
     * 输入框，用户下次打开还能看见自己填的那个值。
     */
    var accentHex by mutableStateOf<String?>(null)
        private set

    var dynamicColor by mutableStateOf(false)
        private set

    /** 解析后的强调色；非法/未设为 null。 */
    val accent: Color? get() = parseAccentHex(accentHex)

    /**
     * 回到默认外观（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * **只清内存，不写盘**：reset 若顺手把默认值写回 SharedPreferences，
     * 一次测试清场就会把用户的自定义配色抹掉——那是比测试污染严重得多的事故。
     * 用户下次 [load] 读到的仍是自己存的值。
     */
    fun reset() {
        themeMode = ThemeMode.SYSTEM
        accentHex = null
        dynamicColor = false
    }

    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        themeMode = runCatching {
            ThemeMode.valueOf(p.getString(K_MODE, null).orEmpty())
        }.getOrDefault(ThemeMode.SYSTEM)
        accentHex = p.getString(K_ACCENT, null)?.takeIf { parseAccentHex(it) != null }
        dynamicColor = p.getBoolean(K_DYNAMIC, false)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        themeMode = mode
        write(context) { it.putString(K_MODE, mode.name) }
    }

    /** 传 null 或非法值 = 恢复默认色。 */
    fun setAccent(context: Context, hex: String?) {
        val normalized = parseAccentHex(hex)?.toHexString()
        accentHex = normalized
        write(context) {
            if (normalized == null) it.remove(K_ACCENT) else it.putString(K_ACCENT, normalized)
        }
    }

    fun setDynamicColor(context: Context, on: Boolean) {
        dynamicColor = on
        write(context) { it.putBoolean(K_DYNAMIC, on) }
    }

    private inline fun write(context: Context, block: (android.content.SharedPreferences.Editor) -> Unit) {
        val p = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        p.edit().also(block).apply()
    }
}
