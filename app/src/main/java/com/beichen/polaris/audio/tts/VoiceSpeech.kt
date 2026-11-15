package com.beichen.polaris.audio.tts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.voice.VoiceAttempt
import com.beichen.polaris.core.voice.VoiceAvailability
import com.beichen.polaris.core.voice.VoiceCatalog
import com.beichen.polaris.core.voice.VoicePlan
import com.beichen.polaris.core.voice.VoiceProfile
import com.beichen.polaris.core.voice.VoiceKind
import com.beichen.polaris.core.voice.VoiceSkip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 音色路由：选哪条、退到哪条、为什么退。
 *
 * ## 它解决的是「一条链」，不是「一个引擎」
 *
 * 用户选的是**音色**，系统能提供的却是**一条条各自会失败的路**：在线要网、
 * 本地要模型、系统要语音包。任何一条断掉，用户听到的是「没声音」——
 * 而这三种原因在体验上完全一样，在代码里却是三种不同的失败。
 *
 * 所以这里有且只有一件事：拿着 [VoicePlan] 给的顺序逐条试，把「退到了哪一步」
 * 记下来。谁在说话（在线/本地）是它决定的，但「怎么说」不归它管——
 * 播放与打断语义留在 `TextSpeaker`，因为仲裁器依赖那边唯一的 [TextSpeaker.speaking]。
 *
 * ## 为什么不在这里联网
 *
 * 见类 [VoiceCatalog] 与 `docs/ops` 里 2026-11-15 的结论：在线音色需要用户自备
 * 服务端 key，没有 key 时这一档必须明确显示「未配置」，而不是偷偷用一个
 * 逆向出来的免费接口把声音放出来——那种接口会在某天全部 403，
 * 而失败方式与「网络不好」无法区分。
 */
class VoiceSpeech(private val context: Context) {

    private val tag = "Voice"
    private val appContext = context.applicationContext
    private val local = LocalVoiceEngine(appContext)
    private val clone = ZipVoiceEngine(appContext)
    private val player = PcmPlayer(appContext)

    /** 录音器按需建：只是选音色、不录音的路径不该持有麦克风对象。 */
    private var recorderRef: VoiceRecorder? = null

    /** 播放在单独一条线程上阻塞执行；主线程只收「念完了」。 */
    private val playerThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "polaris-tts-play").apply { isDaemon = true }
    }

    private val lastDemotionRef = AtomicReference<String?>(null)

    /**
     * 用户选的音色；没选过时给出厂默认。
     *
     * 注意与 `VoiceCatalog.require` 的区别：那个把「脏 id」归到系统引擎（旧版本行为），
     * 这里「没选过」要归到出厂默认（本地 22kHz）。两者都是「有个能用的」，
     * 但含义不同：前者是降级兜底，后者是首次安装的正常路径。
     */
    fun selectedProfile(): VoiceProfile {
        val id = VoiceStyleStore.selectedId(appContext)
        // 克隆音色不在静态目录里（它是用户数据），所以先问存储。
        // 目录被清理过时这里拿到 null，落回下面的静态查找——
        // 用户的选择还在，只是那条声音没了，界面会显示「参考音频不见了」。
        CloneVoiceStore.read(appContext, id)?.let { return it.profile }
        return VoiceCatalog.byId(id) ?: VoiceCatalog.require(VoiceCatalog.DEFAULT_ID)
    }

    /** 是否装了克隆模型（ZipVoice）。音色页用它决定「录一句」入口是不是可用。 */
    fun cloneModelReady(): Boolean = clone.isReady()

    /** 录音器（单例复用：每次 new 一个 AudioRecord 释放不干净会占着麦克风）。 */
    fun recorder(): VoiceRecorder = recorderRef ?: VoiceRecorder().also { recorderRef = it }

    /** 上一次为什么没用首选音色；null = 没发生过回落。音色页读它显示。 */
    fun lastDemotion(): String? = lastDemotionRef.get()

    fun clearLastDemotion() {
        lastDemotionRef.set(null)
    }

    /**
     * 合成一段音频（尚未播放）。
     *
     * 返回 `null` 表示整条回落链都走不通，调用方应退到系统引擎。
     * 返回值里的 [Prepared.profile] 可能与用户选的不同——那是回落的结果，不是错误。
     */
    suspend fun prepare(text: String, rate: Float): Prepared? = prepareFor(selectedProfile(), text, rate)

    /**
     * 按指定音色合成（不看用户选择），给「试听」用。
     *
     * 试听必须先于选择：用户凭什么在没听过的情况下决定用哪个声音。
     * 但它不该改用户的选择——所以走这个入口而不是「临时改存储再改回来」，
     * 后者在任何一次崩溃/被杀之后都会留下一个用户没选过的音色。
     */
    suspend fun prepareFor(profile: VoiceProfile, text: String, rate: Float): Prepared? = withContext(Dispatchers.IO) {
        val desired = profile
        if (desired.kind == VoiceKind.SYSTEM) return@withContext null
        val availability = VoiceAvailability(
            online = hasNetwork(),
            localModel = local.isReady(),
            cloneModel = clone.isReady(),
            // 只对「当前这条就是克隆」时才查参考音频：别的音色不需要它，
            // 查了反而会让「内置音色可用」被一个无关文件的缺失影响。
            reference = desired.kind != VoiceKind.CLONE || referenceAvailable(desired),
        )
        val chain = VoicePlan.chain(desired, availability)
        val demoted = chain.mapNotNull { it.skip }
        for (candidate in chain.filter { it.planned }) {
            tryCandidate(text, rate, candidate, demoted)?.let { return@withContext it }
        }
        // 走到这里：所有能试的都试过了。逐条说明原因，别只留「失败了」。
        val reason = describeExhausted(chain)
        lastDemotionRef.set(reason)
        AgentLogger.w(tag, "音色回落失败：$reason")
        null
    }

    private suspend fun tryCandidate(
        text: String,
        rate: Float,
        candidate: VoiceAttempt,
        demoted: List<VoiceSkip>,
    ): Prepared? {
        return when (candidate.profile.kind) {
            VoiceKind.LOCAL -> {
                val clip = runCatching { local.synthesize(text, rate) }
                    .onFailure {
                        AgentLogger.w(tag, "本地合成失败：${it.javaClass.simpleName} ${it.message}")
                    }
                    .getOrNull() ?: return null
                noteDemotion(demoted, candidate.profile)
                Prepared(candidate.profile, clip)
            }
            VoiceKind.CLONE -> {
                val voice = CloneVoiceStore.read(appContext, candidate.profile.id) ?: return null
                val (samples, sampleRate) = CloneVoiceStore.loadReference(voice)
                    ?: run {
                        AgentLogger.w(tag, "参考音频读取失败：${voice.wavFile.absolutePath}")
                        return null
                    }
                // 克隆合成比内置音色慢一个数量级，而且它跑在 Mutex 里——
                // 万一某台机器上卡住，后面每一句都会排在它后面，表现为「从此再没声音」。
                // 给它一条硬上限：超时就当作这条失败，按回落链退到内置音色。
                val clip = withTimeoutOrNull(CLONE_TIMEOUT_MS) {
                    runCatching { clone.synthesize(text, rate, samples, sampleRate, voice.refText) }
                        .onFailure {
                            AgentLogger.w(tag, "克隆合成失败：${it.javaClass.simpleName} ${it.message}")
                        }
                        .getOrNull()
                } ?: run {
                    AgentLogger.w(tag, "克隆合成超时（${CLONE_TIMEOUT_MS}ms），本次退到内置音色")
                    null
                }
                if (clip == null) return null
                noteDemotion(demoted, candidate.profile)
                Prepared(candidate.profile, clip)
            }
            else -> null
        }
    }

    /**
     * 记一笔「这次为什么不是用户选的那条」。
     *
     * 只记**发生过回落**的情况：正常路径写一条「用的就是选的」，会让这个字段
     * 永远非空，音色页那栏提示就变成了噪声。
     */
    private fun noteDemotion(demoted: List<VoiceSkip>, used: com.beichen.polaris.core.voice.VoiceProfile) {
        if (demoted.isEmpty()) return
        lastDemotionRef.set(
            "这次用的是「${used.safeLabel}」：" + demoted.joinToString("、") { skipText(it) }
        )
    }

    /** 参考音频还在、且长度够（ZipVoice 太短的样本克隆出来不像）。 */
    private fun referenceAvailable(profile: com.beichen.polaris.core.voice.VoiceProfile): Boolean {
        val voice = CloneVoiceStore.read(appContext, profile.id) ?: return false
        return VoiceSampleIo.durationMsOf(voice.wavFile) >= CloneVoiceStore.MIN_REFERENCE_MS
    }

    /**
     * 起播。返回后立刻返回，播完调 [onDone]（主线程）。
     *
     * 播放失败也算 [onDone]：调用方靠它释放播报租约，漏掉一次就再也点不开麦克风。
     */
    fun playAsync(prepared: Prepared, onDone: () -> Unit) {
        playerThread.execute {
            val ok = runCatching { player.play(prepared.clip) }
                .onFailure { AgentLogger.w(tag, "播放失败：${it.javaClass.simpleName}") }
                .getOrDefault(false)
            if (!ok) AgentLogger.w(tag, "本次播报没有正常结束（被打断或播放器拒绝）")
            onDone()
        }
    }

    fun stop() {
        player.stop()
    }

    /** 录音前调用：先把自己正在说的话停掉，否则麦克风会录进机器音。 */
    fun stopForRecording() {
        stop()
        runCatching { recorderRef?.cancel() }
    }

    fun release() {
        stop()
        runCatching { playerThread.shutdownNow() }
        local.release()
        clone.release()
    }

    private fun hasNetwork(): Boolean = runCatching {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    private fun describeExhausted(chain: List<VoiceAttempt>): String = chain.joinToString("；") { attempt ->
        val why = attempt.skip?.let { skipText(it) } ?: "合成失败"
        "${attempt.profile.safeLabel}：$why"
    }

    private companion object {
        /**
         * 克隆合成的硬上限。
         *
         * 它跑在 Mutex 里，卡住会让后面每一句都排队——用户看到的不是「这一句慢」，
         * 而是「从此再没声音」。宁可这一句退到内置音色，也不要堵死整条链。
         * 30 秒是「慢得离谱但仍然可能是真的在算」与「已经死了」的分界。
         */
        const val CLONE_TIMEOUT_MS = 30_000L
    }

    private fun skipText(skip: VoiceSkip): String = when (skip) {
        VoiceSkip.NO_NETWORK -> "需要联网但当前没网"
        VoiceSkip.MODEL_MISSING -> "本地模型未就绪"
        VoiceSkip.REFERENCE_MISSING -> "参考录音不见了或太短，重新录一句就好"
    }

    /** 合成好、等播放的一段。 */
    class Prepared(val profile: VoiceProfile, val clip: AudioClip)
}
