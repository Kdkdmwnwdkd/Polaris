package com.beichen.polaris.pet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.beichen.polaris.audio.AudioGraph
import com.beichen.polaris.audio.asr.AsrDirectory
import com.beichen.polaris.audio.asr.LocalSpeechInput
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.text.SpeechLogic

/**
 * 桌宠语音输入的状态机。
 *
 * 只有四个状态，且都是**瞬时**的：没有任何一个需要持久化，进程重启后一律从 [Idle] 开始。
 * 这也是它与 `PetState` 的分工——那边是用户意图，这边是这一次按键的生命周期。
 */
sealed interface PetVoiceState {

    /** 没在听。 */
    data object Idle : PetVoiceState

    /**
     * 已按下、已拿到麦克风，正在等引擎就绪。
     *
     * 与 [Listening] 分开是因为这段时间里**还没有任何声音被采到**：引擎初始化要 200–600ms，
     * 用户在这期间说的第一个字会丢。分出来才能在界面上明确显示「还没开始听」。
     */
    data object Arming : PetVoiceState

    /** 正在收声。[partial] 是引擎给的实时文本，可能为空（多数引擎前几百毫秒不给）。 */
    data class Listening(val partial: String) : PetVoiceState

    /** 已经结束录音，等引擎出最终结果。这段时间用户会盯着屏幕，必须有反馈。 */
    data object Transcribing : PetVoiceState
}

/**
 * 桌宠的语音输入：**只用自带的离线识别**。
 *
 * ## 2026-11-16：把系统识别整条路删了
 *
 * 以前这里是双路径——有自带模型走自带，没有就退到 `SpeechRecognizer`。
 * 两个系统引擎在真机上都没用：Google 的服务在国内不可达，高通引擎不认中文，
 * 用户在它们之间切来切去，得到的都是「一个字的识别结果都没有」。
 *
 * 留着那条路只有一种效果：用户以为「没装模型也能用」，点下去等 20 秒，然后失败。
 * 所以现在模型没装就直接说清「去下模型，约 74MB，下完一定能用」——
 * 这是唯一一条在这类机器上真的能出字的路。
 *
 * ## 三条硬约束
 *
 * 1. **一次只有一个会话**：`active` 之外再按一次直接忽略。
 * 2. **必须有超时**：环境噪音可能让端点检测永远不触发，20 秒强制收尾。
 * 3. **麦克风租约由录音层申请**：申请两次会被仲裁判成自己占用自己。
 */
class PetVoiceInput(
    context: Context,
    /** 状态变化。主线程回调。 */
    private val onState: (PetVoiceState) -> Unit,
    /** 识别成功的最终文本（已清洗，非空）。主线程回调。 */
    private val onText: (String) -> Unit,
    /** 一句话的可执行提示（没权限 / 没模型 / 没听清 / 被占用）。主线程回调。 */
    private val onProblem: (String) -> Unit,
    /** 需要麦克风权限时的直达动作（跳到本应用的权限页）。参数是给用户看的原因。 */
    private val onNeedPermission: (String) -> Unit = onProblem,
    /** 需要下载识别模型时的直达动作（跳到语音页）。参数是给用户看的原因。 */
    private val onNeedModel: (String) -> Unit = onProblem,
) {

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 自带的离线识别。
     *
     * 进程内单例：加载一次模型要 600–900ms，聊天页与桌宠共用同一份，
     * 不共享就等于同一台手机上常驻两份权重。
     */
    private val localAsr = AsrDirectory.of(app)
    private var localInput: LocalSpeechInput? = null

    /** 本次会话是否还活着。所有异步回调先查它，避免迟到的回调改动新会话的状态。 */
    private var active = false

    /** 已经结束、正在等最终结果。用于忽略结束之后才到的 partial。 */
    private var finishing = false

    /**
     * 整段录音的硬上限。
     *
     * 20 秒：正常人一句话 3–8 秒，超过 20 秒基本是「按住了忘了松」或者引擎卡死。
     * 到点直接收尾，并把说到一半的内容当作已识别（引擎通常已经在 partial 里给了）。
     */
    private val hardTimeout = Runnable {
        if (!active) return@Runnable
        AgentLogger.w(TAG, "录音超时，强制收尾")
        finishWithProblem("等太久没听到完整的话，已经停下了")
    }

    /**
     * 开始听。
     *
     * @return true = 已经开始；false = 没能开始，原因已经通过 [onProblem] 给出，
     *         调用方不要额外编文案。
     */
    fun start(): Boolean {
        if (active) return false

        if (!localAsr.isInstalled()) {
            AgentLogger.i(TAG, "没装离线识别模型，拒绝开麦")
            onNeedModel(
                "还没下载离线识别模型（约 74MB）。下完就能说话，不再依赖系统识别服务——" +
                    "手机上那两个系统引擎在这台机器上出不了中文"
            )
            return false
        }

        // 录音发生在我们的进程里，RECORD_AUDIO 是硬前置。桌宠走的就是这条路，
        // 所以这里的判定只针对自己，不再有「系统页那条路不需要权限」的分叉。
        if (!hasMic()) {
            onNeedPermission("要听你说话，得先允许「录音」权限")
            return false
        }

        active = true
        finishing = false
        onState(PetVoiceState.Arming)

        val input = LocalSpeechInput(app, localAsr, AudioGraph.arbiter(app))
        localInput = input
        val started = input.start { event ->
            // 回调来自录音线程，UI 状态与 onText 都必须在主线程。
            handler.post {
                when (event) {
                    is LocalSpeechInput.Event.Partial -> {
                        if (!active || finishing) return@post
                        onState(PetVoiceState.Listening(event.text))
                    }
                    is LocalSpeechInput.Event.Final -> {
                        if (!active) return@post
                        val text = SpeechLogic.cleanResult(event.text)
                        teardown("离线识别完成")
                        if (text != null) onText(text) else onProblem("没听清，再说一次试试")
                    }
                    is LocalSpeechInput.Event.Failed -> {
                        if (!active) return@post
                        AgentLogger.w(TAG, "离线识别失败：${event.message}")
                        teardown("离线识别失败")
                        onProblem(event.message)
                    }
                }
            }
        }
        if (!started) {
            active = false
            localInput = null
            onState(PetVoiceState.Idle)
            return false
        }
        handler.postDelayed(hardTimeout, HARD_TIMEOUT_MS)
        return true
    }

    /**
     * 结束录音：让录音线程收尾。最终文本通过 [LocalSpeechInput.Event.Final] 回来。
     *
     * 正常路径下用户松手就会调它；模型自带的端点检测也可能先把录音停下。
     */
    fun stop() {
        if (!active || finishing) return
        finishing = true
        handler.removeCallbacks(hardTimeout)
        onState(PetVoiceState.Transcribing)
        // 收尾就是几毫秒的 decode，不需要给「等引擎回结果」的宽限。
        localInput?.stop()
    }

    /**
     * 取消：立刻释放，丢弃已录内容。
     *
     * 用在拖动手势抢占、窗口销毁、Service 停止——这些动作说明用户不想说了。
     */
    fun cancel() {
        if (!active) return
        teardown("用户取消")
    }

    /** Service 销毁时调。幂等。 */
    fun destroy() {
        cancel()
        handler.removeCallbacksAndMessages(null)
    }

    // —— 内部 ——

    private fun hasMic(): Boolean =
        app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun finishWithProblem(message: String) {
        if (!active) return
        teardown(message)
        onProblem(message)
    }

    /** 收尾：停回调、停录音、回 Idle。幂等——录音线程已经走完时 cancel 是空操作。 */
    private fun teardown(reason: String) {
        handler.removeCallbacks(hardTimeout)
        active = false
        finishing = false
        localInput?.cancel()
        localInput = null
        onState(PetVoiceState.Idle)
        AgentLogger.i(TAG, "语音会话结束：$reason")
    }

    private companion object {
        const val TAG = "PetVoice"
        const val HARD_TIMEOUT_MS = 20_000L
    }
}
