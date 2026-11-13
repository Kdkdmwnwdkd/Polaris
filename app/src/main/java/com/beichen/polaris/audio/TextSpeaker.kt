package com.beichen.polaris.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 系统 TTS 封装（W-16）。
 *
 * 为什么用系统 TTS 而不是内置模型：零依赖、零下载、装机即有；
 * 代价是音色取决于系统语音包（用户可在系统设置里换）。内置本地模型的音质更好，
 * 但要额外下载几十 MB——先做「能读」这一步，音质后置。
 *
 * 初始化是异步的（`OnInitListener`），这里用 [CompletableDeferred] + 8 秒超时把它变成 suspend；
 * 引擎缺失（精简 ROM 常见）时明确失败，不假装读过。
 *
 * ## 为什么要有 [speaking] 和 [stop]（2026-11-13 补）
 *
 * 改造前这个类只能「开始念」，不能「停下」，也没有任何「正在念」的信号。后果有两个，
 * 都发生在真机上：
 *  1. 播报中点麦克风，播报不停 —— 录音把 AI 自己的声音录进去，识别结果是一团乱码；
 *  2. 打断仲裁拿不到 `speaking`，永远只能判「空闲」，于是「用户插话打断播报」这条
 *     规则表第一维全空。
 * [stop] 与 `TextToSpeech.QUEUE_FLUSH` 不是一回事：后者只在**下一次** speak 时丢弃旧队列，
 * 在此之前旧内容照念，用户点了「停」却还在响。
 */
class TextSpeaker(context: Context) {

    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    private var initDeferred: CompletableDeferred<Boolean>? = null

    private val _speaking = MutableStateFlow(false)

    /** 是否正在出声。供打断仲裁判断 `Activity.speaking`。 */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** 播报用的 AudioManager；null = 设备没有音频服务（极少数精简 ROM），此时跳过焦点申请。 */
    private val audioManager: AudioManager? =
        runCatching { appContext.getSystemService(AudioManager::class.java) }.getOrNull()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 是否持有音频焦点。speak 在主线程、onDone 在引擎线程，必须保证可见性。 */
    @Volatile
    private var focusHeld = false

    /**
     * 卡死兜底。
     *
     * `speaking` 是**乐观**置 true 的（见 [speak] 里「部分引擎不回调 onStart」那段）。
     * 乐观的另一面是：如果引擎既不回调 `onStart` 也不回调 `onDone`/`onError`
     * （静默失败、引擎进程被杀、厂商 ROM 的 TTS 服务被冻结），`speaking` 会永久为 true。
     *
     * 现在的代价只是「点麦克风先停播报」多一次无害的 stop；三期唤醒词上线后，
     * 它会变成「桌宠永远认为自己在说话，唤醒词永久被拒」——用户只会看到
     * 「喊了没反应」，而且重启才好。所以不是锦上添花，是给乐观置位补一条退路。
     */
    @Volatile
    private var watchdog: Runnable? = null

    /**
     * 被别的应用抢走音频焦点时停止播报。
     *
     * 只处理**丢失**，不处理 `LOSS_TRANSIENT_CAN_DUCK`：朗读本来就不该压过导航播报，
     * 但「导航说一句」就掐掉整段回答也不合适。让引擎自己混音是更小的恶。
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stop()
        }
    }

    /**
     * 焦点请求。`GAIN_TRANSIENT_MAY_DUCK` 而不是 `GAIN_TRANSIENT`：
     * 用户在听音乐时问一句，把音乐压下去、答完恢复，比直接掐掉音乐礼貌。
     *
     * 输出用途声明为 `USAGE_ASSISTANT`：让系统的「语音助手正在说话」判定生效，
     * 部分 ROM 会据此降低其他 App 的音量，这是免费的体验提升。
     */
    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
    }

    /**
     * 当前这一句的 utteranceId。
     *
     * 存在的理由：`QUEUE_FLUSH` 丢弃旧句后，旧句的 `onDone` 仍可能以**旧 id** 迟到。
     * 不比对就会把「新一句刚开始」误置成「已说完」，于是仲裁器以为没人说话，
     * 放行了一次本该被拦的唤醒。回调线程是 TTS 引擎的，用 @Volatile 保证可见性。
     */
    @Volatile
    private var currentUtteranceId: String? = null

    private suspend fun ensureReady(): Boolean {
        if (ready && tts != null) return true
        val deferred = initDeferred ?: CompletableDeferred<Boolean>().also { initDeferred = it }
        if (tts == null) {
            withContext(Dispatchers.Main) {
                tts = TextToSpeech(appContext) { status ->
                    ready = status == TextToSpeech.SUCCESS
                    if (!deferred.isCompleted) deferred.complete(ready)
                }.also { engine ->
                    // 引擎可能返回 ERROR 但仍构造出对象；listener 设置本身是安全的，
                    // 返回值不看——真正的可用性由 ready 决定。
                    runCatching { engine.setOnUtteranceProgressListener(progressListener) }
                }
            }
        }
        return withTimeoutOrNull(8_000) { deferred.await() } ?: false
    }

    /**
     * 播报进度回调 → [speaking]。
     *
     * 只在 id 与 [currentUtteranceId] 相等时改状态：迟到回调必须被挡掉（见字段注释）。
     * `onStart` 在部分引擎上不回调，所以 [speak] 成功时先乐观置 true，这里只做兜底。
     */
    private val progressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            if (utteranceId != null && utteranceId == currentUtteranceId) _speaking.value = true
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId != null && utteranceId == currentUtteranceId) {
                currentUtteranceId = null
                _speaking.value = false
                finishUtterance()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            if (utteranceId == null || utteranceId == currentUtteranceId) {
                currentUtteranceId = null
                _speaking.value = false
                finishUtterance()
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (utteranceId == null || utteranceId == currentUtteranceId) {
                currentUtteranceId = null
                _speaking.value = false
                finishUtterance()
            }
        }
    }

    /** 一句结束的公共收尾：撤兜底、放焦点。回调线程是引擎的，Handler 操作本身线程安全。 */
    private fun finishUtterance() {
        disarmWatchdog()
        abandonFocus()
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        if (focusHeld) return
        val r = runCatching { am.requestAudioFocus(focusRequest) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        // 拿不到焦点也继续念：用户明确要求朗读，静默取消比「和音乐混在一起」更糟。
        // 这里只决定「要不要在结束时放弃焦点」，不决定「要不要出声」。
        focusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        runCatching { audioManager?.abandonAudioFocusRequest(focusRequest) }
    }

    /**
     * 按文本长度估一个「正常该念完」的时刻，到点仍在念就判死。
     *
     * 中文 4.5 字/秒是系统 TTS 默认语速的实测中位数；英文按字符算偏保守（念得完）。
     * 多留 10 秒不是怕算不准，是怕用户中途暂停/接电话后恢复——那属于正常播放。
     */
    private fun armWatchdog(id: String, text: String, rate: Float) {
        disarmWatchdog()
        val estimate = (text.length / 4.5f * 1_000f / rate.coerceIn(0.5f, 2.0f)).toLong()
        val delayMs = estimate.coerceAtLeast(15_000L) + 10_000L
        val r = Runnable {
            // id 已变 = 后面又念了新的一句，这条兜底过期了，不能误杀新句
            if (currentUtteranceId == id) {
                currentUtteranceId = null
                _speaking.value = false
                runCatching { tts?.stop() }
                abandonFocus()
            }
        }
        watchdog = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun disarmWatchdog() {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
    }

    /**
     * 朗读一段文本（异步播放，调用立即返回）。
     *
     * @param language "zh" / "en" / "auto"
     * @param rate 0.5–2.0
     */
    suspend fun speak(text: String, language: String = "auto", rate: Float = 1.0f): Result<Unit> =
        withContext(Dispatchers.Main) {
            if (text.isBlank()) return@withContext Result.failure(IllegalArgumentException("文本为空"))
            if (!ensureReady()) {
                return@withContext Result.failure(
                    IllegalStateException("系统 TTS 未就绪（设备可能没有语音引擎，或引擎初始化超时）")
                )
            }
            val engine = tts ?: return@withContext Result.failure(IllegalStateException("TTS 引擎不可用"))
            val locale = when (language.lowercase()) {
                "zh" -> Locale.SIMPLIFIED_CHINESE
                "en" -> Locale.US
                else -> Locale.getDefault()
            }
            val langResult = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                return@withContext Result.failure(
                    IllegalStateException("系统缺少 ${locale.displayName} 语音包（可在系统设置 → 语音合成 中安装）")
                )
            }
            engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
            val utteranceId = "agentkit_" + System.currentTimeMillis()
            // 先登记 id 再出声：onStart 可能比 speak() 返回还早（引擎线程），
            // 顺序反了会让第一句的 onStart 因 id 不匹配而被丢掉。
            currentUtteranceId = utteranceId
            requestFocus()
            val spoken = text.take(MAX_SPEAK_CHARS)
            val code = runCatching {
                engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }.getOrDefault(TextToSpeech.ERROR)
            if (code == TextToSpeech.ERROR) {
                currentUtteranceId = null
                _speaking.value = false
                abandonFocus()
                Result.failure(IllegalStateException("TTS 播放失败（引擎返回 ERROR）"))
            } else {
                // 乐观置 true：部分引擎不回调 onStart（尤其讯飞/小爱系），
                // 只靠回调会让 speaking 永远停在 false，仲裁器就不知道有人在说话。
                _speaking.value = true
                armWatchdog(utteranceId, spoken, rate)
                Result.success(Unit)
            }
        }

    /**
     * 立即停止播报。
     *
     * 与 `QUEUE_FLUSH` 的区别见类注释。停止后 [speaking] 立刻为 false，
     * 不等引擎回调——用户点「停」的那一瞬间，麦克风就该可以用了。
     */
    fun stop() {
        disarmWatchdog()
        runCatching { tts?.stop() }
        currentUtteranceId = null
        _speaking.value = false
        abandonFocus()
    }

    /** 停止并释放引擎（[AudioGraph.reset] 调用）。 */
    fun shutdown() {
        disarmWatchdog()
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        abandonFocus()
        tts = null
        ready = false
        initDeferred = null
        currentUtteranceId = null
        _speaking.value = false
    }

    private companion object {
        /** 单次朗读上限：再长也没人听得完，且会把语音队列堵死。 */
        const val MAX_SPEAK_CHARS = 3_000
    }
}
