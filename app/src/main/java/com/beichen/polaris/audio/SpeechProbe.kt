package com.beichen.polaris.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.voice.EngineCapability
import com.beichen.polaris.core.voice.EngineSelector
import com.beichen.polaris.core.voice.LanguageTag
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

    /** 探测的超时与回调都要落在主线程：`SpeechRecognizer` 只认主线程。 */
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(app)

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
     * 上一次 [probeEngines] 的结果。null = 还没探测过。
     *
     * 为什么全程缓存：探测要连上每个引擎问一遍（每个几百毫秒），
     * 而判定要同步返回。第一次判定时缓存为空，放行；探测回来后，
     * 后面的判定就能用上真实结论。
     *
     * 进程内有效，不做持久化：引擎的语言包用户随时可能装/卸，
     * 跨进程缓存一个旧结论比不缓存更糟。
     */
    /** 已探测到的引擎清单；null = 还没探过。 */
    fun cachedEngines(): List<EngineCapability>? = sharedEngines

    /**
     * 引擎是否支持当前语言。
     *
     * 同步版只能看**缓存**：
     *
     * - 没探测过（或探测不可用）→ null，不据此下结论。这是有意的：
     *   拿不到信息就放行，让引擎自己报错码，而不是凭猜测拦下用户。
     * - 探测过且一个能用的都没有 → false，明确不支持。
     * - 探测过且有能用的 → true。
     *
     * 真正的探测在 [probeEngines]——异步、逐个问、结果进缓存。
     * 分开的理由：`SpeechRecognizer.checkRecognitionSupport` 是实例方法 + 异步回调，
     * 还要先连上引擎；做不成同步查询，而桌宠的 `start()` 在主线程、
     * 聊天页在 Compose lambda 里，都不能挂起。
     */
    fun isLanguageSupported(language: String = currentLanguageTag()): Boolean? {
        val verdict = EngineSelector.languageVerdict(sharedEngines)
        AgentLogger.i(TAG, "语言支持（$language）：${verdict ?: "未知（未探测）"}")
        return verdict
    }

    /**
     * 逐个问引擎：当前语言你认不认。
     *
     * 这是本轮修复的核心。真机日志显示，用户设备上有两个识别服务，
     * 默认那个（Google TTS 自带的）不认中文，于是桌宠每次录音都拿到
     * `ERROR_LANGUAGE_NOT_SUPPORTED`——而 App 侧「引擎已选」的检查是绿的，
     * 因为那个设置里确实有值。
     *
     * 拿到每个引擎的结论后，[EngineSelector.pick] 就能挑一个**现在就能用**的，
     * 桌宠用 `createSpeechRecognizer(ctx, componentName)` 直接指过去，
     * 不需要用户去系统设置里翻。
     *
     * ## 约束
     *
     * - **必须在主线程调**（`createSpeechRecognizer` 的要求）。
     * - 每个引擎一个超时：引擎不应答是常态（未初始化、被停用），
     *   不能让它拖住整轮探测。
     * - 结果回调在主线程。
     *
     * @param language BCP-47 语言标签，默认取系统语言。
     * @param force 跳过 15 秒节流，强制重探。诊断报告用：用户点「语音诊断」的那一刻
     *        就是要看**现在**的结论，读到一份 14 秒前、且当时有引擎没应答的缓存
     *        等于白点。
     * @param onDone 探测完成。低版本系统或查询不到服务时给空列表。
     */
    fun probeEngines(
        language: String = currentLanguageTag(),
        force: Boolean = false,
        onDone: (List<EngineCapability>) -> Unit,
    ) {
        // checkRecognitionSupport 是 API 33 才有的。低版本上「引擎认不认这个语言」
        // 只能在录音开始后由错误码告诉我们——那就保持现状，不假装探到了。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            AgentLogger.i(TAG, "系统版本 ${Build.VERSION.SDK_INT} < 33，无法提前探测引擎的语言支持")
            sharedEngines = emptyList()
            onDone(emptyList())
            return
        }

        // 节流：聊天页每次进入都会调一次，而绑定每个引擎问一遍是有成本的
        // （要连服务）。15 秒内复用结果。装完语言包想立刻看到效果的话，
        // 退出再进一次聊天页即可（超过节流窗口就会重探）。
        val cached = sharedEngines
        if (!force && cached != null && System.currentTimeMillis() - lastProbeAt < PROBE_THROTTLE_MS) {
            onDone(cached)
            return
        }

        val services = queryRecognitionServices()
        if (services.isEmpty()) {
            sharedEngines = emptyList()
            lastProbeAt = System.currentTimeMillis()
            onDone(emptyList())
            return
        }

        val defaultComponent = defaultEngineComponent()
        val total = services.size
        val results = ArrayList<EngineCapability>(total)
        var finished = 0
        var delivered = false

        fun oneDone(capability: EngineCapability) {
            results.add(capability)
            finished++
            if (finished < total || delivered) return
            delivered = true
            sharedEngines = results.toList()
            lastProbeAt = System.currentTimeMillis()
            AgentLogger.i(
                TAG,
                "引擎探测完成（$language）：" + results.joinToString("；") {
                    "${it.component}=${EngineSelector.describe(EngineSelector.fit(it))}" +
                        if (it.isDefault) "[默认]" else ""
                },
            )
            onDone(results.toList())
        }

        services.forEach { info ->
            val pkg = info.serviceInfo.packageName
            val cls = info.serviceInfo.name
            val key = "$pkg/$cls"
            var settled = false

            /** 没问到时的占位项。**必须留在列表里**，理由见 [EngineCapability.probed]。 */
            fun unanswered(): EngineCapability = EngineCapability(
                component = key,
                isDefault = key == defaultComponent,
                probed = false,
            )

            val recognizer = runCatching {
                SpeechRecognizer.createSpeechRecognizer(app, ComponentName(pkg, cls))
            }.getOrNull()
            if (recognizer == null) {
                AgentLogger.w(TAG, "无法为引擎 $key 创建识别器")
                oneDone(unanswered())
                return@forEach
            }

            // 回调与超时可能都到，用 settled 保证只结算一次。
            fun settle(capability: EngineCapability) {
                if (settled) return
                settled = true
                runCatching { recognizer.destroy() }
                oneDone(capability)
            }

            handler.postDelayed({ settle(unanswered()) }, PROBE_TIMEOUT_MS)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            }
            runCatching {
                recognizer.checkRecognitionSupport(
                    intent,
                    mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            // 四个列表原样落日志。这一行是本轮修复的取证点：
                            // 真机上「引擎不认中文」到底是四个列表都空、还是只有
                            // 离线列表空、还是引擎根本没实现这个查询，只能靠它分辨。
                            // 转成布尔值之后就再也看不出来了。
                            AgentLogger.i(
                                TAG,
                                "引擎 $key 回答 $language：离线已装=${support.installedOnDeviceLanguages}" +
                                    "，可离线=${support.supportedOnDeviceLanguages}" +
                                    "，在线=${support.onlineLanguages}",
                            )
                            settle(
                                EngineCapability(
                                    component = key,
                                    installedOffline = LanguageTag.anyCovers(
                                        support.installedOnDeviceLanguages, language,
                                    ),
                                    supportedOffline = LanguageTag.anyCovers(
                                        support.supportedOnDeviceLanguages, language,
                                    ),
                                    online = LanguageTag.anyCovers(support.onlineLanguages, language),
                                    isDefault = key == defaultComponent,
                                ),
                            )
                        }

                        // 引擎自己报错（未初始化、内部异常、没实现这个查询）也走这里。
                        // 不处理的话这一项会一直等超时，白拖一整个超时窗口。
                        //
                        // 注意 settle 的是「未应答」而不是「不支持」：引擎报错说明
                        // 它没给出答案，不说明它不会中文。两者混为一谈就会误判。
                        override fun onError(error: Int) {
                            AgentLogger.w(TAG, "引擎 $key 查询语言支持报错：$error（按未应答处理）")
                            settle(unanswered())
                        }
                    },
                )
            }.onFailure { e ->
                AgentLogger.w(TAG, "询问引擎 $key 的语言支持失败：${e.message}")
                settle(unanswered())
            }
        }
    }

    /** 设备上的识别服务清单。查不到时给空表并记日志（不抛）。 */
    private fun queryRecognitionServices(): List<ResolveInfo> = runCatching {
        app.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            0,
        ).orEmpty()
    }.getOrElse { e ->
        AgentLogger.w(TAG, "查询识别服务失败：${e.message}")
        emptyList()
    }

    /** 系统选定的默认识别引擎，`包名/类名`。读不到给 null。 */
    private fun defaultEngineComponent(): String? = runCatching {
        Settings.Secure.getString(app.contentResolver, VOICE_RECOGNITION_SERVICE)
    }.getOrNull()?.takeIf { it.isNotBlank() }

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

    companion object {
        private const val TAG = "SpeechProbe"

        /** `Settings.Secure.VOICE_RECOGNITION_SERVICE`，API 29 起可读。 */
        private const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"

        /**
         * 单个引擎的探测超时。
         *
         * 2500ms 是**实测改出来的**：早先写 800ms，理由是「引擎正常应答 100–300ms」——
         * 那是服务已经热着时的数字。冷启动时要先 bind 服务、再让引擎初始化识别器，
         * 魅族 20 上的 Google TTS 就是在这个窗口里超时的，于是它被当成不存在，
         * 报告里只剩另一个明确不支持中文的引擎，结论变成「没有能用的」。
         *
         * 放宽的代价很小：探测全程在后台跑（桌宠启动时探一次，不阻塞长按），
         * 多个引擎并行问，最坏也就多等 1.7 秒，而且结果会被缓存 15 秒。
         * 而误判的代价是用户当场用不了语音——这个交换不划算才怪。
         */
        const val PROBE_TIMEOUT_MS = 2_500L

        /**
         * 探测结果的**进程级**缓存。
         *
         * 为什么不是实例字段：探测结果描述的是「这台设备上的引擎认不认当前语言」，
         * 与哪个 [SpeechProbe] 对象无关。做成实例字段的话，
         * 桌宠探完、用户在设置里点「语音诊断」（new 一个 probe）就又是空的——
         * 而那一刻恰恰是最需要看到结果的时刻。
         */
        @Volatile
        private var sharedEngines: List<EngineCapability>? = null

        /** 上次探测完成的时间，用于节流。 */
        @Volatile
        private var lastProbeAt = 0L

        /** 探测节流窗口：窗口内复用上次结果，不重复绑定引擎。 */
        private const val PROBE_THROTTLE_MS = 15_000L

        /**
         * 单个引擎的探测超时（毫秒），只读对外。
         *
         * 诊断报告要把它写出来：用户看到某个引擎「没问到」时，
         * 需要知道这是「等了 2.5 秒也没回」而不是「它不支持」——
         * 两者对应的动作完全不同（再点一次 / 去换引擎）。
         */
        val probeTimeoutMs: Long get() = PROBE_TIMEOUT_MS
    }
}
