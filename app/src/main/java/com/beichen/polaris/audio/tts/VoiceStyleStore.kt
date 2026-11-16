package com.beichen.polaris.audio.tts

import android.content.Context

/**
 * 用户选的音色。
 *
 * ## 与「识别」的区别
 *
 * 识别是**听谁的**（走哪套离线模型），音色是**说成谁的声**。
 * 两件事在系统设置的同一个页面里挨着，实际完全不同：
 * 前者影响麦克风，后者影响喇叭。合并成一个「语音设置」会让用户改错东西。
 *
 * ## 为什么存 id 而不是存音色名
 *
 * 音色名会随供应商改版变化（`zh-CN-XiaoxiaoNeural` 这种字符串哪天就不是这个写法了），
 * 用户存的选择会因为一个外部改名而失效。id 是我们自己的稳定键，改名不影响。
 *
 * 读到脏数据（卸载重装、手工改过、降级回退）时 [selectedId] 返回 null，
 * 由 `VoiceCatalog.require` 归到系统引擎——不抛异常，也不静默挑一个「最像的」。
 */
object VoiceStyleStore {

    private const val PREF = "polaris_voice_style"
    private const val KEY_SELECTED = "selected_voice_id"

    /** 用户选的音色 id；null = 没选过 / 存的值已失效。 */
    fun selectedId(context: Context): String? = runCatching {
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, null)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun select(context: Context, id: String?) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SELECTED, id?.takeIf { it.isNotBlank() })
                .apply()
        }
    }
}
