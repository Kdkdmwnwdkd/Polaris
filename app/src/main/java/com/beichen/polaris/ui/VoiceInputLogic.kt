package com.beichen.polaris.ui

import android.content.Intent
import android.speech.RecognizerIntent
import com.beichen.polaris.core.voice.SpeechCapability
import java.util.Locale

/**
 * 聊天页语音输入的**纯逻辑**部分。
 *
 * ## 为什么把它从 ChatScreen 里抽出来
 *
 * ChatScreen 有行数冻结（只减不增，架构守卫强制）。而语音输入这一块
 * 有三样东西与页面无关：能不能识别的判定、系统识别 Intent 的构造、
 * 以及「识别结果怎么并进输入框」。
 *
 * 把它们留在这里，ChatScreen 里就只剩「点麦克风 → 调这里 → 处理结果」，
 * 页面重新变成「编排」而不是「实现」。
 *
 * ## 为什么只有 Intent、没有 launcher
 *
 * 权限申请与系统识别页的发起都必须在 Activity 上（Compose 的
 * `RequestPermission` / `StartActivityForResult` launcher 需要 Activity 上下文），
 * 而 ChatScreen 拿得到、这里拿不到。所以这里只负责**造出要发的东西**，
 * 发出去的动作由页面执行——职责切得很干净：这里管「发什么」，页面管「怎么发」。
 */
object VoiceInputLogic {

    /**
     * 构造系统语音识别 Intent。
     *
     * @param prompt 系统识别页上的提示语。
     * @return 可直接 launch 的 Intent。
     */
    fun buildRecognizeIntent(prompt: String = "说出你要问的内容"): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // BCP-47（`zh-CN`），不是 [Locale.toString] 的 `zh_CN`——后者不是
            // BCP-47，严格按规范解析的引擎读不出语言。桌宠那边也是这个形式。
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault().toLanguageTag()
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }

/**
     * 判定结果 → 该做什么。
     *
     * 两种走向：
     * - 能识别 → 直接发起；
     * - 不能识别 → 提示，把出路说清楚。
     *
     * 为什么**没有**「申请麦克风权限」这一种走向：
     * 聊天页走系统识别页，录音在系统进程里，我们的麦克风权限与它无关
     * （见 [com.beichen.polaris.core.voice.VoicePath]）。
     * 曾经在这里加过一个「缺权限就当场申请」的分支，那是**误诊**——
     * 它会把一条本来能用的路拦下来。
     * 真正需要麦克风权限的是桌宠（自己录音那条路），它那边有独立的提示与跳转。
     *
     * @return 动作类型，由调用方执行（发 Intent / 弹提示）。
     */
    sealed interface Action {
        /** 可以发起识别。 */
        data object Start : Action

        /** 不能识别，给一句可执行的提示。 */
        data class Notify(val message: String) : Action
    }

    /**
     * 把能力判定翻译成一个动作。
     *
     * 纯函数：不碰 Android，行为断言能直接跑。
     */
    fun actionFor(capability: SpeechCapability): Action = when (capability) {
        is SpeechCapability.Available -> Action.Start
        is SpeechCapability.Unavailable -> Action.Notify(capability.message)
        is SpeechCapability.LanguageUnsupported -> Action.Notify(capability.message)
    }
}
