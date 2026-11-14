package com.beichen.polaris.pet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.beichen.polaris.audio.AudioGraph
import com.beichen.polaris.audio.SpeechProbe
import com.beichen.polaris.audio.VoiceEngineStore
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.text.SpeechLogic
import com.beichen.polaris.core.voice.AudioLease
import com.beichen.polaris.core.voice.ClaimResult
import com.beichen.polaris.core.voice.EngineSelector
import com.beichen.polaris.core.voice.SpeechCapability
import com.beichen.polaris.core.voice.SpeechCapabilityPolicy
import com.beichen.polaris.core.voice.SpeechRemedy
import com.beichen.polaris.core.voice.VoicePath
import java.util.Locale

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
 * 桌宠的语音输入：`SpeechRecognizer` 直连，不弹系统页。
 *
 * ## 为什么不复用对话页那套
 *
 * `ChatScreen` 走的是 [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] 的 **Activity** 版：
 * 发一个 Intent，系统弹一个识别界面，用户在那边说完点确定，结果回传。那条路一定可用
 * （有识别服务的机器都能跑），代价是全屏弹窗。
 *
 * 对桌宠这是致命的：它存在的全部意义是「一直在这儿、随手问一句」。弹一次系统页就退化成
 * 「打开一个应用」。所以这里用 [SpeechRecognizer] 的 **API** 版——同样的识别服务，
 * 不弹界面。spec §2 把这件事写成了需求决定的，不是技术偏好。
 *
 * ## 三条硬约束
 *
 * 1. **必须在主线程**：`createSpeechRecognizer` 与 `startListening` 都只能在主线程调，
 *    否则抛 `RuntimeException: SpeechRecognizer should be used only from the main thread`。
 *    本类所有公开方法都假定调用方在主线程（Compose 手势、Service 生命周期都满足）。
 * 2. **一次只有一个会话**：同一个 `SpeechRecognizer` 实例在 `startListening` 期间再调一次
 *    会得到 `ERROR_RECOGNIZER_BUSY`。所以每次录音**新建一个实例，用完就 destroy**——
 *    复用实例在部分 ROM 上会出现「第二次录音没有任何回调」的静默失败，那种问题没法在
 *    CI 里发现，代价远高于每次多花 300ms。
 * 3. **必须有超时**：引擎的静音判定依赖厂商实现，`EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS`
 *    在很多 ROM 上直接被忽略。没有兜底的话，一次「按下去、没说话」会让状态永久停在
 *    [PetVoiceState.Listening]，桌宠看起来像死了。
 *
 * ## 与声音仲裁的关系
 *
 * 录音前先向 [com.beichen.polaris.core.voice.MicClaim] 请求 `Capture`。请求成功意味着
 * 播报已经被自动停掉（[com.beichen.polaris.audio.VoiceArbiter] 的 revoked 回调负责），
 * 所以这里**不需要**先调 `stopSpeech()`——互斥是仲裁的结果，不是调用方的义务。
 *
 * 反过来，被拒时（别的应用在录音、通话中）原样把仲裁给的原因显示出来，不要自己编一句
 * 「麦克风不可用」——用户看到「正在通话中」才知道该挂电话。
 */
class PetVoiceInput(
    context: Context,
    /** 状态变化。主线程回调。 */
    private val onState: (PetVoiceState) -> Unit,
    /** 识别成功的最终文本（已清洗，非空）。主线程回调。 */
    private val onText: (String) -> Unit,
    /** 一句话的可执行提示（没权限 / 没服务 / 没听清 / 被占用）。主线程回调。 */
    private val onProblem: (String) -> Unit,

    /**
     * 带**结构化出路**的提示回调。默认走 [onProblem]。
     *
     * 为什么需要它：调用方要据此决定要不要给「去授权」这个动作——
     * 只有 [SpeechRemedy.GrantMicPermission] 是**当场就能解决**的
     * （点一下跳到系统权限页），而「没听清」「识别服务正忙」给个按钮只会让人多点一次。
     *
     * 传的是枚举而不是字符串：字符串写错一个字母不会编译报错，
     * 只会静默地不再跳权限页——正是「按了没反应」这一类最难查的问题。
     */
    private val onProblemWithCode: ((String, SpeechRemedy) -> Unit)? = null,
) {

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val arbiter = AudioGraph.arbiter(app)

    /** 识别能力探测。与聊天页共用同一套结论——两边说法不一致会让用户以为其中一边坏了。 */
    private val probe = SpeechProbe(app)

    private var lease: AudioLease? = null
    private var recognizer: SpeechRecognizer? = null

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
     * 主动结束后的结果等待上限。
     *
     * 结束 → 引擎上传/解码 → 出结果，正常 300–1500ms。5 秒还没回来说明引擎侧没响应，
     * 只能当作没听清。这段时间的 UI 是 [PetVoiceState.Transcribing]，用户不会以为卡死。
     */
    private val resultTimeout = Runnable {
        if (!active) return@Runnable
        finishWithProblem("没听清，再说一次试试")
    }

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            if (!active) return
            onState(PetVoiceState.Listening(""))
        }

        override fun onBeginningOfSpeech() {
            // 引擎检测到人声。onReadyForSpeech 更早，UI 已经在「听」的状态里，这里不重复。
        }

        override fun onRmsChanged(rmsdB: Float) {
            // 音量变化每秒几十次。做波形很好看，但每帧重组 Canvas 不值得——
            // 形象本身有呼吸和眨眼，已经足够表达「它活着」。
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            // 引擎认为说完了。**这里不要调 stopListening()**：
            // 部分引擎在 onEndOfSpeech 之后已经开始解码，再 stop 会让它直接 cancel，
            // 结果是「说完了却什么都没识别到」。让它自己走 onResults。
        }

        override fun onError(error: Int) {
            if (!active) return
            val message = errorText(error)
            AgentLogger.i(TAG, "识别失败 code=$error → $message")
            finishWithProblem(message)
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            val raw = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            val cleaned = raw?.let { SpeechLogic.cleanResult(it) }
            // 先收尾再回调：onText 里会立刻发起新一轮 Agent 请求，
            // 若此时麦克风还没释放，Agent 里的 speak 工具会拿到一次假 busy。
            teardown("识别完成")
            if (cleaned != null) onText(cleaned) else onProblem("没听清，再说一次试试")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!active || finishing) return
            val p = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!p.isNullOrBlank()) onState(PetVoiceState.Listening(p))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /**
     * 开始听。
     *
     * @return true = 已经开始（状态会先 [PetVoiceState.Arming] 再 [PetVoiceState.Listening]）；
     *         false = 没能开始，原因已经通过 [onProblem] 给出，调用方不要额外编文案。
     */
    fun start(): Boolean {
        if (active) return false

        // 「能不能识别」要查三件事，缺一件都会静默失败：
        //   ① 有没有识别服务；
        //   ② 系统**选了**哪个引擎（Android 14 的 SpeechRecognizer 绑定时读
        //      Settings.Secure.VOICE_RECOGNITION_SERVICE，它为空时绑定直接失败，
        //      应用侧只能看到笼统的 ERROR_CLIENT）；
        //   ③ 引擎**认不认**当前语言（只能在录音前问，方式是 checkRecognitionSupport，
        //      它不碰麦克风）。
        //
        // 三件事合起来才是完整判定，所以统一交给探测层，与聊天页共用同一套结论——
        // 两边说的不一样，用户会以为其中一边坏了。
        //
        // 这里**不再自己先判权限**：那会让桌宠的结论顺序（先说缺权限）与聊天页
        // （先说缺服务）不一致，而这两条路必须给出同一句结论。
        // 判定顺序里「没服务」排在「没权限」前面，理由见 SpeechCapabilityPolicy 的注释。
        //
        // 桌宠走的是**自己录音**那条路（[VoicePath.SelfRecorded]）：
        // 录音发生在我们的进程里，所以我们的麦克风权限是硬前置。
        // 聊天页走系统识别页，那边的权限归系统管——两边不能用同一套要求。
        when (val cap = probe.check(VoicePath.SelfRecorded)) {
            is SpeechCapability.Available -> Unit
            is SpeechCapability.Unavailable -> {
                report(cap.message, cap.remedy)
                return false
            }
            is SpeechCapability.LanguageUnsupported -> {
                report(cap.message, cap.remedy)
                return false
            }
        }

        when (val r = arbiter.beginCapture("桌面助手语音输入")) {
            is ClaimResult.Granted -> lease = r.lease
            is ClaimResult.Denied -> {
                AgentLogger.i(TAG, "麦克风被拒：${r.code} / ${r.message}")
                onProblem(r.message)
                return false
            }
        }

        val rec = createRecognizer()
        if (rec == null) {
            releaseLease("创建识别器失败")
            onProblem("语音识别服务启动失败，请稍后再试")
            return false
        }
        rec.setRecognitionListener(listener)
        recognizer = rec
        active = true
        finishing = false
        onState(PetVoiceState.Arming)

        val started = runCatching { rec.startListening(buildIntent()) }
        if (started.isFailure) {
            AgentLogger.w(TAG, "startListening 抛异常：${started.exceptionOrNull()?.message}")
            teardown("启动失败")
            onProblem("语音识别启动失败，请稍后再试")
            return false
        }
        handler.postDelayed(hardTimeout, HARD_TIMEOUT_MS)
        return true
    }

    /**
     * 选定的识别引擎，`包名/类名`；null = 用系统默认。
     *
     * 为什么需要它：真机上「语音用不了」常常不是没装引擎，而是**装了好几个、
     * 默认那个不认中文**。此时最省事的修法不是让用户去系统设置里翻，
     * 而是我们直接把识别指向一个能用的引擎——
     * `SpeechRecognizer.createSpeechRecognizer(ctx, componentName)` 就是为这件事存在的。
     *
     * 值由 [com.beichen.polaris.audio.SpeechProbe.probeEngines] 的探测结果决定，
     * 挑选规则在 [com.beichen.polaris.core.voice.EngineSelector.pick]。
     */
    @Volatile
    var preferredEngine: String? = null
        set(value) {
            if (field == value) return
            field = value
            AgentLogger.i(TAG, "识别引擎切换为 ${value ?: "系统默认"}")
        }

    /**
     * 探一遍「哪个引擎认当前语言」，把能用的那个接上。
     *
     * 什么时候调：桌宠服务起来时调一次（[com.beichen.polaris.pet.PetOverlay] 建完窗口）。
     * 不放在 [start] 里——探测要连上每个引擎问一遍，几百毫秒起步，
     * 而用户长按要的是「立刻开始听」。
     *
     * 挑中的是系统默认那个时，仍走默认路径（不锁死组件）：默认引擎可能被系统改掉，
     * 锁死一个组件反而会在用户换引擎后继续用旧的那个。
     */
    fun refreshEngine() {
        probe.probeEngines { engines ->
            val chosen = EngineSelector.pick(engines)
            preferredEngine = if (chosen == null || chosen.isDefault) null else chosen.component
            when {
                // 查不到服务 / 系统版本低：不动，`start()` 里还会按默认路径再判一次。
                engines.isEmpty() -> Unit
                // 只有「每个引擎都明确说不支持」才会走到这里。
                chosen == null -> AgentLogger.w(
                    TAG,
                    "没有引擎支持当前语言（${probe.currentLanguageTag()}）：" +
                        engines.joinToString("、") { it.component },
                )
                // 没问到 ≠ 不支持。这种情况**不换引擎**，让它按自己的方式试一次——
                // 我们没有证据说它不行（见 EngineCapability.probed）。
                !chosen.probed -> AgentLogger.w(
                    TAG,
                    "引擎 ${chosen.component} 未在超时内应答，先按默认路径试一次",
                )
            }
        }
    }

    /**
     * 建一个识别器，优先用用户指定的引擎，其次用探测挑出来的。
     *
     * 顺序说明：**用户的选择高于自动探测**。自动那套的规则是「默认引擎能用就别换」，
     * 它解决的是「用户不知道该怎么办」的场景；一旦用户自己点了某个引擎，
     * 再让探测结果盖掉他的选择就成了「App 觉得自己更懂」。
     *
     * 指定引擎创建失败时**静默回退系统默认**：用户此刻要的是「说话能被识别」，
     * 不是「必须用某个引擎」。为了一次创建失败就弹一句技术错误，等于把内部
     * 选择逻辑的失败转嫁给用户。
     */
    private fun createRecognizer(): SpeechRecognizer? {
        val chosen = VoiceEngineStore.pinned(app) ?: preferredEngine
        if (chosen != null) {
            val component = ComponentName.unflattenFromString(chosen)
            if (component != null) {
                val rec = runCatching {
                    SpeechRecognizer.createSpeechRecognizer(app, component)
                }.getOrNull()
                if (rec != null) {
                    AgentLogger.i(TAG, "使用指定引擎：$chosen")
                    return rec
                }
                AgentLogger.w(TAG, "指定引擎 $chosen 创建失败，回退系统默认")
            }
        }
        return runCatching { SpeechRecognizer.createSpeechRecognizer(app) }.getOrNull()
    }

    /**
     * 结束录音：请引擎尽快给结果。不会立刻释放麦克风——那要等 [onResults] / [onError]。
     *
     * 正常路径下**不会**被调用：识别引擎自带静音判定，说完它会自己走 [onResults]。
     * 这个方法给的是「用户点麦克风提前结束」——比如他说完了但引擎还在等更多内容。
     */
    fun stop() {
        if (!active || finishing) return
        finishing = true
        handler.removeCallbacks(hardTimeout)
        onState(PetVoiceState.Transcribing)
        runCatching { recognizer?.stopListening() }
        handler.postDelayed(resultTimeout, RESULT_TIMEOUT_MS)
    }

    /**
     * 取消：立刻释放，不等结果。
     *
     * 用在拖动手势抢占、窗口销毁、Service 停止。与 [stop] 的区别是**丢弃**已录内容，
     * 因为触发取消的动作（拖桌宠、关桌宠）说明用户不想说了。
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

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        // 用系统语言的 BCP-47 形式（`zh-CN`）而不是 [Locale.toString]（`zh_CN`）：
        // 后者不是 BCP-47，严格按规范解析的引擎会读不出语言。
        // 双语用户的手机可能是英文，硬编码 zh-CN 会让他说中文被识别成一串英文音近词。
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        // 这里**故意不传** EXTRA_PREFER_OFFLINE。
        //
        // 以前传了 true，想法是「有离线包就用离线，更快、不费流量」。但它是
        // **prefer**，在厂商实现里常被当成 **require**：Google 的识别服务在没装
        // 当前语言离线包时，收到这个 extra 会直接回
        // ERROR_LANGUAGE_NOT_SUPPORTED(12)，而不是退回在线识别。
        //
        // 2026-11-14 的真机日志正好是这一幕：桌宠每按一次就是 code=12，
        // 而同机的在线识别本来是能用的。也就是说，这一行 extra 可能一直在
        // 把「能用」变成「不能用」——代价（一次几百 KB 流量）完全不对等。
        //
        // 不传之后由识别服务自己决定走离线还是在线：装了离线包它照样会优先用，
        // 没装才会走网络。这个决定应该由引擎做，不该由我们替它做。
    }

    /**
     * 报一个提示，优先走带结构化出路的回调。
     *
     * 带出路的回调存在时由它负责展示——否则两条路会弹两次提示。
     */
    private fun report(message: String, remedy: SpeechRemedy) {
        val cb = onProblemWithCode
        if (cb != null) cb(message, remedy) else onProblem(message)
    }

    private fun finishWithProblem(message: String) {
        if (!active) return
        teardown(message)
        onProblem(message)
    }

    /** 收尾：停回调、销毁引擎、释放麦克风、回 Idle。顺序不能换（见类注释第 2 条）。 */
    private fun teardown(reason: String) {
        handler.removeCallbacks(hardTimeout)
        handler.removeCallbacks(resultTimeout)
        active = false
        finishing = false
        val rec = recognizer
        recognizer = null
        if (rec != null) {
            // cancel 先于 destroy：destroy 在部分引擎上不等回调就拆底层连接，
            // 先 cancel 能让它走正常收尾路径。
            runCatching { rec.cancel() }
            runCatching { rec.destroy() }
        }
        releaseLease(reason)
        onState(PetVoiceState.Idle)
    }

    private fun releaseLease(reason: String) {
        val l = lease ?: return
        lease = null
        runCatching { arbiter.endCapture(l) }
            .onFailure { AgentLogger.w(TAG, "释放麦克风失败（$reason）：${it.message}") }
    }

    /**
     * 错误码 → 给人看的一句话。
     *
     * 翻译表放在 [SpeechCapabilityPolicy.explainError]，与聊天页共用一份。
     * 两处各写一套的结果是：同一个错误码在桌宠上显示「识别失败」，在聊天页显示
     * 「网络不太好」，用户没法判断到底是哪一种。
     *
     * 特别地，`ERROR_CLIENT`（=5）**不是**「未知错误」，它是信息量最大的一个：
     * Android 14 的识别器在「系统没选定默认引擎」和「绑定引擎失败」时都走这一条，
     * 应用侧看不出区别，所以文案给最可能的那条出路。
     */
    private fun errorText(code: Int): String = SpeechCapabilityPolicy.explainError(code)

    private companion object {
        const val TAG = "PetVoice"
        const val HARD_TIMEOUT_MS = 20_000L
        const val RESULT_TIMEOUT_MS = 5_000L
    }
}
