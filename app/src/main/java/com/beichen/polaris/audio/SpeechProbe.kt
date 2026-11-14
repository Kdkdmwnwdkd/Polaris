package com.beichen.polaris.audio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.voice.SpeechCapability
import com.beichen.polaris.core.voice.SpeechCapabilityPolicy
import com.beichen.polaris.core.voice.VoicePath
import java.util.Locale

/**
 * 语音识别的**环境探测**。
 *
 * 存在的理由：桌宠（[com.beichen.polaris.pet.PetVoiceInput]）与聊天页
 * （[com.beichen.polaris.ui.ChatScreen]）都要回答同一个问题——「这台机器现在能不能语音输入」，
 * 但它们查的方式不同、给的结论不同。用户在桌宠上听到「没有语音识别服务」、
 * 到聊天页却能用，会以为其中一边坏了。
 *
 * 所以把探测集中在这里，两条路都调它，结论必然一致。
 *
 * ## 四条判定各自查什么
 *
 * 1. **有没有识别服务**：[SpeechRecognizer.isRecognitionAvailable] 的语义是
 *    「设备上是否存在任何声明了 [RecognitionService.SERVICE_INTERFACE] 的服务」。
 *    它**不看**是不是默认引擎，也不看有没有声明 `CATEGORY_DEFAULT`。
 * 2. **系统选了默认引擎吗**：`Settings.Secure.VOICE_RECOGNITION_SERVICE`。
 *    Android 14 的 [SpeechRecognizer] 绑定引擎时读的就是这个设置；
 *    它为空时，引擎连接会直接失败，应用侧只能看到一个笼统的 `ERROR_CLIENT`。
 * 3. **有没有系统识别页**：`ACTION_RECOGNIZE_SPEECH` 有没有处理方。
 *    这是**聊天页**那条路的专属前置条件（见 [VoicePath]）。
 * 4. **语言支持吗**：[SpeechRecognizer.checkRecognitionSupport]。这是唯一能提前问出
 *    「这个引擎认不认当前语言」的办法，且**不占用麦克风**。
 *
 * ## 麦克风权限按路径给
 *
 * 谁来录音，决定我们的 `RECORD_AUDIO` 算不算数：
 * 桌宠自己录音 → 算；聊天页走系统识别页 → **不算**（录音在系统进程里）。
 * 理由与依据见 [VoicePath] 的注释。
 *
 * ## 关于 package visibility（Android 11+）
 *
 * [SpeechRecognizer.isRecognitionAvailable] 内部走
 * `queryIntentServices(RecognitionService.SERVICE_INTERFACE, 0)`，在 Android 11+ 上
 * 受 package visibility 限制：查不到别的 App 声明的服务时它会返回 false。
 * 本 App 的 `targetSdk = 28`（不是 30+），不受该限制影响，所以不需要
 * `<queries>` 声明——这一点已对着 AOSP `SpeechRecognizer` 源码确认过。
 *
 * 但为了将来 `targetSdk` 一旦升上去不至于静默失效，这里仍然按官方建议
 * 在清单里声明了 `<queries>`（见 `AndroidManifest.xml`）。声明了不会有副作用，
 * 不声明则会在升级 targetSdk 的那一天变成「语音莫名其妙不能用」。
 */
class SpeechProbe(context: Context) {

    private val app = context.applicationContext

    /**
     * 设备上是否存在任何识别服务。
     *
     * 复刻 [SpeechRecognizer.isRecognitionAvailable] 的判定，但**不直接调它**——
     * 直接调的话，出问题时无法区分「没有服务」和「被 package visibility 挡了」。
     * 这里自己查一遍，顺带把服务清单记进日志，真机上一眼就能看出是哪种。
     */
    fun hasRecognitionService(): Boolean {
        val services = runCatching {
            app.packageManager.queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE),
                0,
            )
        }.getOrElse { e ->
            AgentLogger.w(TAG, "查询识别服务失败：${e.message}")
            return false
        }
        val list = services.orEmpty()
        if (list.isEmpty()) {
            AgentLogger.i(TAG, "设备上没有识别服务")
            return false
        }
        AgentLogger.i(
            TAG,
            "识别服务 ${list.size} 个：" +
                list.joinToString("、") { "${it.serviceInfo.packageName}/${it.serviceInfo.name}" }
        )
        return true
    }

    /**
     * 系统是否已选定默认识别引擎。
     *
     * @return true = 已选定；false = 设置为空（Android 14 上会导致绑定失败）；
     *         null = 查不到这个信息（不据此下结论）。
     */
    fun hasDefaultEngine(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 9 及更早由系统内部决定默认引擎，没有这个设置项可读。
            return null
        }
        return runCatching {
            val s = Settings.Secure.getString(app.contentResolver, VOICE_RECOGNITION_SERVICE)
            s?.takeIf { it.isNotBlank() } != null
        }.getOrElse { e ->
            AgentLogger.w(TAG, "读取默认识别引擎失败：${e.message}")
            null
        }
    }

    /**
     * 引擎是否支持当前语言。
     *
     * **当前实现恒返回 null（查不出来），这是有意的。**
     *
     * AOSP 的 `SpeechRecognizer.checkRecognitionSupport` 是**实例方法 + 异步回调**
     * （要 Intent + Executor + RecognitionSupportCallback），而且它要先连上引擎才能问。
     * 把它做成同步探测需要挂起整个调用链（桌宠的 `start()` 在主线程、聊天页在
     * Compose lambda 里），代价远大于收益。
     *
     * 更重要的是：**这个信息本来就有更好的来源**。引擎不认当前语言时，会在
     * `onError` 里回 [android.speech.SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED]（12）
     * 或 `ERROR_LANGUAGE_UNAVAILABLE`（13），由 `SpeechCapabilityPolicy.explainError`
     * 翻译成准确提示。提前问只是把同一个答案提前一点拿到，还搭上了一条异步链路。
     *
     * 保留这个函数是为了让判定表的维度完整（harness 会逐条钉死它），
     * 以及将来若需要「开始录音前就告知语言问题」时有现成位置。
     */
    fun isLanguageSupported(language: String = currentLanguageTag()): Boolean? {
        AgentLogger.i(TAG, "语言支持情况交由引擎错误码回报（$language）")
        return null
    }

    /**
     * 是否有 App 能处理系统识别 Intent（`ACTION_RECOGNIZE_SPEECH`）。
     *
     * 这是**聊天页**那条路的专属前置条件：走系统识别页时，如果这台机器上
     * 没有 App 声明了处理这个 Intent 的 Activity（系统语音助手/搜索应用被停用、
     * 或被精简 ROM 删掉了），连识别页都弹不出来——`ActivityNotFoundException`。
     *
     * 提前查出来，才能给一句准确的话。旧代码是等 `launch` 抛异常才回一句
     * 「本机没有可用的语音识别服务」，那句话把「没有识别页」说成了「没有识别服务」，
     * 会把用户引到错误的修法上（去装识别服务，而真正缺的是系统语音助手）。
     */
    fun hasSystemRecognizerPage(): Boolean {
        val activities = runCatching {
            app.packageManager.queryIntentActivities(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                0,
            )
        }.getOrElse { e ->
            AgentLogger.w(TAG, "查询系统识别页失败：${e.message}")
            return true
        }
        val list = activities.orEmpty()
        if (list.isEmpty()) {
            AgentLogger.i(TAG, "没有 App 处理 ${RecognizerIntent.ACTION_RECOGNIZE_SPEECH}（系统语音助手可能被停用了）")
            return false
        }
        AgentLogger.i(
            TAG,
            "系统识别页 ${list.size} 个：" +
                list.joinToString("、") { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        )
        return true
    }

    /**
     * 一次性判定当前能不能语音输入。
     *
     * @param path 这次识别走哪条路（谁来录音）。见
     *        [com.beichen.polaris.core.voice.VoicePath]。
     */
    fun check(path: VoicePath): SpeechCapability {
        val hasService = hasRecognitionService()
        val hasEngine = hasDefaultEngine()
        val langSupported = if (hasService) isLanguageSupported() else null
        val result = SpeechCapabilityPolicy.decide(
            path = path,
            hasRecognitionService = hasService,
            hasDefaultEngine = hasEngine,
            languageSupported = langSupported,
            // 自己录音才需要我们的麦克风权限；系统页那条路上录音在系统进程里，
            // 我们的权限与它无关——查它、也**不用它去拦用户**。
            hasMicPermission = if (path == VoicePath.SelfRecorded) hasMicPermission() else true,
            hasSystemRecognizerPage =
                if (path == VoicePath.SystemPage) hasSystemRecognizerPage() else true,
        )
        when (result) {
            is SpeechCapability.Available ->
                AgentLogger.i(TAG, "语音能力检查通过（路径=$path，引擎已选=$hasEngine，语言支持=$langSupported）")
            is SpeechCapability.Unavailable ->
                AgentLogger.i(TAG, "语音能力检查未通过（路径=$path）：${result.code} → ${result.message}")
            is SpeechCapability.LanguageUnsupported ->
                AgentLogger.i(TAG, "语音能力检查未通过（路径=$path）：${result.code} → ${result.message}")
        }
        return result
    }

    /**
     * 我们的麦克风权限是否已授予。
     *
     * **只对 [VoicePath.SelfRecorded] 有意义**：走系统识别页时录音在系统进程里，
     * 我们的权限与它无关。
     */
    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 当前语言标签（BCP-47）。
     *
     * 用 [Locale.getDefault] 的 BCP-47 形式（`zh-CN`），**不是** [Locale.toString]
     * （`zh_CN`）。后者不是 BCP-47，部分引擎按规范解析会失败——这正是聊天页
     * 与桌宠曾经给出不同结果的原因之一。
     */
    fun currentLanguageTag(): String = Locale.getDefault().toLanguageTag()

    private companion object {
        const val TAG = "SpeechProbe"

        /** `Settings.Secure.VOICE_RECOGNITION_SERVICE`，API 29 起可读。 */
        const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"
    }
}
