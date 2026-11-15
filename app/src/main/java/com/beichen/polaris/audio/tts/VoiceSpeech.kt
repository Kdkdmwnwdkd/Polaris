package com.beichen.polaris.audio.tts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.voice.VoiceAttempt
import com.beichen.polaris.core.voice.VoiceCatalog
import com.beichen.polaris.core.voice.VoicePlan
import com.beichen.polaris.core.voice.VoiceProfile
import com.beichen.polaris.core.voice.VoiceKind
import com.beichen.polaris.core.voice.VoiceSkip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val player = PcmPlayer(appContext)

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
    fun selectedProfile(): VoiceProfile =
        VoiceCatalog.byId(VoiceStyleStore.selectedId(appContext))
            ?: VoiceCatalog.require(VoiceCatalog.DEFAULT_ID)

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
        val chain = VoicePlan.chain(
            desiredId = desired.id,
            online = hasNetwork(),
            localInstalled = local.isReady(),
        )
        val demoted = chain.mapNotNull { it.skip }
        chain.firstOrNull { it.planned }?.let { candidate ->
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
                if (demoted.isNotEmpty()) {
                    lastDemotionRef.set(
                        "首选「${candidate.profile.safeLabel}」不可用（" +
                            demoted.joinToString("、") { skipText(it) } +
                            "），本次改用本地 22kHz"
                    )
                }
                Prepared(candidate.profile, clip)
            }
            else -> null
        }
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

    fun release() {
        stop()
        runCatching { playerThread.shutdownNow() }
        local.release()
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

    private fun skipText(skip: VoiceSkip): String = when (skip) {
        VoiceSkip.NO_NETWORK -> "需要联网但当前没网"
        VoiceSkip.MODEL_MISSING -> "本地模型未就绪"
    }

    /** 合成好、等播放的一段。 */
    class Prepared(val profile: VoiceProfile, val clip: AudioClip)
}
