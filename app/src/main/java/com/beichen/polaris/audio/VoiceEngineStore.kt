package com.beichen.polaris.audio

import android.content.Context

/**
 * 用户手动指定的语音识别引擎。
 *
 * ## 为什么必须由 App 自己存这个值
 *
 * 2026-11-14 的真机截图把一件事说清楚了：这台魅族 20 的「默认应用」里
 * **没有「语音识别」这一项**——只有「文字转语音输出」（TTS）。
 * 也就是说，我们此前所有提示语里那句「到系统设置 → 默认应用 → 语音识别 换一个」，
 * 在这台机器上是**死路**：用户照着走，只能在 TTS 页面里打转。
 *
 * 系统设置改不了，但代码能改：`SpeechRecognizer.createSpeechRecognizer(ctx, component)`
 * 允许应用直接指定引擎。那就把这个选择放进我们自己的设置页，
 * 不再依赖一个这台机器上不存在的入口。
 *
 * ## 为什么只存一个字符串
 *
 * 存组件名（`包名/类名`）而不是索引或包名：同包可能有多个识别服务，
 * 而且索引会随服务增减而错位。组件名是唯一且稳定的。
 *
 * 空值 = 自动（按当前语言挑一个能用的），这是默认行为——
 * 绝大多数用户不该被迫理解「引擎」是什么。
 */
object VoiceEngineStore {

    private const val PREF = "polaris_voice"
    private const val KEY_PINNED = "pinned_engine"

    /** 用户指定的引擎；null = 自动。 */
    fun pinned(context: Context): String? = runCatching {
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_PINNED, null)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 指定引擎；传 null 恢复自动。 */
    fun pin(context: Context, component: String?) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PINNED, component?.takeIf { it.isNotBlank() })
                .apply()
        }
    }
}
