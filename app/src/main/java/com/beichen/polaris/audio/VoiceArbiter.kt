package com.beichen.polaris.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.beichen.polaris.core.voice.AudioHolder
import com.beichen.polaris.core.voice.AudioLease
import com.beichen.polaris.core.voice.ClaimResult
import com.beichen.polaris.core.voice.ExternalBusy
import com.beichen.polaris.core.voice.MicClaim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 声音仲裁器的 Android 装配层。
 *
 * 它做的只有三件事，没有任何业务判断（业务判断在 `core/voice/InterruptPolicy` 里）：
 *
 *  1. 把 `TextSpeaker` 的播报态、`AudioManager` 的外部录音探测喂给 [MicClaim]；
 *  2. 把「被抢占」翻译成动作（播报被抢 → 立刻 `stop()`）；
 *  3. 给播报与录音各提供一个入口，调用方不需要知道 [MicClaim] 存在。
 *
 * ## 为什么「停播报」不写在调用方
 *
 * 改造前 `ChatScreen` 点麦克风只做了一件事：启动 `RecognizerIntent`。播报还在响，
 * 于是录进去的是 AI 自己的声音。可以想见的最直接修法是「点麦克风前先调 stop」——
 * 但那样每加一个录音入口就要记得调一次，漏一个就复现一次，而且没有任何地方会报错。
 *
 * 现在改为：点麦克风 = `request(Capture)`。Capture 优先级高于 Speak，请求成功即抢占，
 * 抢占事件让本类去 `stop()`。**互斥是仲裁的结果，不是调用方的义务。**
 */
class VoiceArbiter(
    context: Context,
    private val speaker: TextSpeaker,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {

    private val appContext = context.applicationContext
    private val audioManager: AudioManager? =
        runCatching { appContext.getSystemService(AudioManager::class.java) }.getOrNull()

    private val mainHandler = Handler(Looper.getMainLooper())

    val claim = MicClaim(log)

    /** 当前播报持有的租约。多次 `speak` 复用同一个——播报是同一个持有者。 */
    private var speakLease: AudioLease? = null
    private val leaseLock = Any()

    private var recordingCallback: Any? = null

    init {
        // 播报结束（说完 / 出错 / 被 stop）即释放占位。
        scope.launch {
            speaker.speaking.collect { speaking ->
                if (!speaking) releaseSpeakLease("播报结束")
            }
        }
        // 被抢占 → 停播报。这是「用户插话自动打断 AI」的闭环所在。
        scope.launch {
            claim.revoked.collect { lease ->
                if (lease.holder == AudioHolder.Speak) {
                    log("播报被 ${claim.current.value?.holder?.name ?: "别处"} 抢占，停止出声")
                    speaker.stop()
                    releaseSpeakLease("被抢占")
                }
            }
        }
        registerRecordingProbe()
    }

    /**
     * 朗读。返回失败时 [Result.exceptionOrNull] 是携带 `code` 的 [VoiceDenied]，
     * 工具层据此给模型一句可执行的说明（「用户正在说话，稍后再读」）。
     */
    suspend fun speak(text: String, language: String = "auto", rate: Float = 1.0f): Result<Unit> {
        val lease = ensureSpeakLease() ?: return Result.failure(
            (claim.current.value?.let { cur ->
                VoiceDenied("audio_busy", "语音播报被拦下：${cur.holder.name}正在进行（${cur.reason}）。")
            } ?: VoiceDenied("audio_busy", "语音播报被拦下：音频通道正忙。"))
        )
        val r = speaker.speak(text, language, rate)
        if (r.isFailure) {
            // 引擎失败不该占着通道：否则「播报失败一次」会永久占位，
            // 之后所有录音请求都被判 busy，用户只看到「麦克风用不了」。
            claim.release(lease, "播报启动失败")
            synchronized(leaseLock) { speakLease = null }
        }
        return r
    }

    /** 请求麦克风（语音输入）。成功 = 播报已被自动停掉。 */
    fun beginCapture(reason: String = "用户语音输入"): ClaimResult =
        claim.request(AudioHolder.Capture, reason)

    fun endCapture(lease: AudioLease) {
        claim.release(lease, "语音输入结束")
    }

    /** 用户手动「停」。 */
    fun stopSpeech() {
        speaker.stop()
        releaseSpeakLease("用户停止")
    }

    fun close() {
        unregisterRecordingProbe()
        stopSpeech()
        claim.reset()
    }

    // —— 内部 ——

    private fun ensureSpeakLease(): AudioLease? {
        synchronized(leaseLock) {
            val current = claim.current.value
            val existing = speakLease
            // 复用自己的租约：连续两次 speak（第二句 FLUSH 掉第一句）不该被自己的
            // 上一句挡住——同优先级不抢占，若不复用就会得到一次假 busy。
            if (existing != null && current?.id == existing.id) return existing
            return when (val r = claim.request(AudioHolder.Speak, "朗读")) {
                is ClaimResult.Granted -> r.lease.also { speakLease = it }
                is ClaimResult.Denied -> {
                    log("朗读被拒：${r.code} / ${r.message}")
                    null
                }
            }
        }
    }

    private fun releaseSpeakLease(reason: String) {
        val lease = synchronized(leaseLock) { speakLease.also { speakLease = null } } ?: return
        claim.release(lease, reason)
    }

    /**
     * 外部录音探测（API 29+）。
     *
     * 两个必须处理的坑：
     *  1. **用自己的 uid 排除自己**。不排除的话，本 App 一录音就会收到自己的配置，
     *     判成「别的应用在录音」，于是自己永远拿不到麦克风——而且表现为「第一次能用，
     *     第二次就不行」，极难归因。
     *  2. **注册后要主动拉一次**。回调只在配置**变化**时触发，注册那一刻如果已经有
     *     别的应用在录音，不主动拉就不知道。
     *
     * 低于 API 29 不做探测：那个版本没有这个 API，而 Android 9 及以下的录音互斥
     * 由系统在 `AudioRecord` 构造时直接失败，不需要我们预判。
     */
    private fun registerRecordingProbe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val am = audioManager ?: return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                applyExternalState(configs)
            }
        }
        runCatching {
            // 用 (callback, handler) 这个重载。(Executor, callback) 那个在 SDK stub 里
            // 取不到（CI 里编译报「inferred type is Executor but AudioRecordingCallback
            // was expected」）——它只在部分 system stub 里可见。
            am.registerAudioRecordingCallback(cb, mainHandler)
            recordingCallback = cb
            applyExternalState(am.activeRecordingConfigurations)
        }.onFailure { log("注册录音探测失败：${it.message}") }
    }

    private fun unregisterRecordingProbe() {
        val cb = recordingCallback as? AudioManager.AudioRecordingCallback ?: return
        runCatching { audioManager?.unregisterAudioRecordingCallback(cb) }
        recordingCallback = null
    }

    private fun applyExternalState(configs: List<AudioRecordingConfiguration>?) {
        // 通话优先判：通话时系统电话进程在录音，报成「另一个应用在录音」会误导用户。
        val inCall = runCatching {
            audioManager?.mode == AudioManager.MODE_IN_CALL ||
                audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION
        }.getOrDefault(false)
        if (inCall) {
            claim.setExternalBusy(ExternalBusy.CallInProgress)
            return
        }
        // 不能按 uid 排除自己：AudioRecordingConfiguration.getClientUid() 在 SDK stub 里
        // 是 @hide（CI 报 Unresolved reference），拿不到。改用「此刻我们自己是不是
        // 正持有麦克风」来近似——自己没在录时，配置列表非空就一定是别人。
        //
        // 已知局限（写下来免得后人以为是 bug）：我们自己正在录的时候，列表里必然有自己，
        // 此时若还有别人在录也分辨不出来。这种情况下我们本来就在用麦克风，
        // 少报一次外部占用不会造成错误决策。
        val selfRecording = claim.current.value?.holder == AudioHolder.Capture
        val others = !selfRecording && configs.orEmpty().isNotEmpty()
        claim.setExternalBusy(if (others) ExternalBusy.OtherAppRecording else null)
    }
}

/**
 * 被仲裁拒绝。
 *
 * 用异常而不是返回 null：调用链上层是 `AgentTool.execute`，它需要一句能回喂模型的话，
 * 而「为什么被拒」的信息只有这里知道。用 null 会让所有调用点各自编一句文案，
 * 最后模型收到的提示五花八门，学不会「等一会儿再试」。
 */
class VoiceDenied(val code: String, override val message: String) : Exception(message)
