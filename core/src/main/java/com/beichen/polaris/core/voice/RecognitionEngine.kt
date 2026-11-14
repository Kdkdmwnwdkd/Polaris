package com.beichen.polaris.core.voice

/**
 * 一个语音识别引擎在**当前语言**上的能力。
 *
 * 存在的理由：真机上「语音用不了」最常见的原因不是没装引擎，而是**装了好几个、
 * 默认那个不认中文**。2026-11-14 的真机日志就是这么写的：
 *
 * ```
 * 识别服务 2 个：com.google.android.tts/…GoogleTTSRecognitionService、
 *               com.qualcomm.qti.voiceai.speech/…SpeechRecognitionService
 * 语音能力检查通过（引擎已选=true，语言支持=null）
 * 识别失败 code=12 → 语音识别服务不支持当前语言
 * ```
 *
 * 判定层放行了，引擎却拒绝——因为「引擎已选」只说明设置里有值，
 * 不说明那个引擎认中文。Google TTS 包里的识别服务要单独下中文离线包。
 *
 * 所以需要这份数据：**每个引擎在当前语言上到底行不行**。
 * 拿到之后就能自己挑一个能用的（[EngineSelector.pick]），
 * 而不是把「去系统设置里换一个」这种活推给用户。
 *
 * @property component 组件名 `包名/类名`，可直接喂给
 *        `SpeechRecognizer.createSpeechRecognizer(ctx, ComponentName)`。
 * @property installedOffline 当前语言的离线包**已装**。
 * @property supportedOffline 引擎**声称支持**当前语言的离线识别，但包没装。
 * @property online 引擎支持当前语言的**在线**识别。
 * @property isDefault 是否是系统当前选定的那一个（`VOICE_RECOGNITION_SERVICE`）。
 */
data class EngineCapability(
    val component: String,
    val installedOffline: Boolean = false,
    val supportedOffline: Boolean = false,
    val online: Boolean = false,
    val isDefault: Boolean = false,
)

/**
 * 一个引擎对当前语言的**适配程度**。四个档次互斥，按可用性递减排列。
 *
 * 分成四档而不是一个布尔值，是因为四档的「出路」完全不同：
 * 前两档直接用，第三档点一下就能下好包，第四档只能换引擎。
 */
enum class EngineFit(
    /** 排序用：值越小越优先。 */
    val rank: Int,
) {
    /** 离线包已装。最快，不耗流量，没网也能用。 */
    ReadyOffline(0),

    /** 只能在线识别。能用，但依赖网络。 */
    ReadyOnline(1),

    /** 引擎声称支持，但离线包没装——可以触发下载，用户点一下就行。 */
    NeedsDownload(2),

    /** 不支持当前语言。 */
    Unsupported(3),
    ;

    /** 已经能用了（不需要用户再做什么）。 */
    val isReady: Boolean get() = this == ReadyOffline || this == ReadyOnline
}

/**
 * 从一组引擎里挑一个**现在就能用**的。
 *
 * 纯函数，零 Android 依赖——这类逻辑错了不报错，只会挑错引擎，
 * 表现是「语音还是不能用」，所以必须能被 JVM harness 逐条断言。
 */
object EngineSelector {

    /** 引擎对当前语言的适配程度。判定顺序即优先级，先命中先返回。 */
    fun fit(engine: EngineCapability): EngineFit = when {
        // 离线包装了就一定认这个语言，这是最强证据。
        engine.installedOffline -> EngineFit.ReadyOffline
        // 支持在线识别：能用，但断网就废。
        engine.online -> EngineFit.ReadyOnline
        // 声称支持离线但没装：差一次下载。
        engine.supportedOffline -> EngineFit.NeedsDownload
        else -> EngineFit.Unsupported
    }

    /**
     * 挑一个引擎。
     *
     * 规则（顺序有讲究）：
     * 1. **默认引擎能用就别换**。换引擎会改变识别质量与断句习惯，
     *    用户没要求换，我们不该自作主张——只在默认那个不能用时才动手。
     * 2. 否则按「离线已装 → 在线可用 → 需下载」挑最优。
     * 3. 全都不支持 → null（调用方据此给「换引擎」的提示，而不是硬着头皮录音）。
     *
     * @return 选中的引擎；null = 这台设备上没有任何引擎认当前语言。
     */
    fun pick(engines: List<EngineCapability>): EngineCapability? {
        if (engines.isEmpty()) return null
        val usable = engines.filter { fit(it) != EngineFit.Unsupported }
        if (usable.isEmpty()) return null

        usable.firstOrNull { it.isDefault && fit(it).isReady }?.let { return it }
        // minByOrNull 取最早的最小值；同档位时保持传入顺序，
        // 也就是说系统列在前面的引擎优先——那是 ROM 自己的偏好。
        return usable.minByOrNull { fit(it).rank }
    }

    /**
     * 把探测结果折算成判定表要的那一维（`languageSupported`）。
     *
     * 三种输入对应三种答案，不能合并：
     * - `null`（还没探过）→ `null`，**不据此下结论**。拿不到信息就放行，
     *   让引擎自己报错码，而不是凭猜测拦住用户。
     * - 空表（探测不可用：系统版本低、查询不到服务）→ `null`，同上。
     * - 有结果 → 有没有任何一个能用。
     *
     * 空表不能当成「都不支持」：它的真实含义是「没问到」。
     * 把「没问到」当成「不支持」会让低版本系统上的用户每次都被拦住——
     * 而那台设备可能好得很。
     */
    fun languageVerdict(probed: List<EngineCapability>?): Boolean? = when {
        probed == null -> null
        probed.isEmpty() -> null
        else -> pick(probed) != null
    }

    /** 给人看的一句话：这个引擎现在是什么状态。 */
    fun describe(fit: EngineFit): String = when (fit) {
        EngineFit.ReadyOffline -> "离线可用"
        EngineFit.ReadyOnline -> "仅在线可用（断网就废）"
        EngineFit.NeedsDownload -> "支持但没下语言包"
        EngineFit.Unsupported -> "不支持当前语言"
    }

    /**
     * 所有引擎都不认当前语言时的提示。
     *
     * 旧文案是「换个系统语言试试」——**这句话把代价推给了用户**：
     * 改系统语言会让整台手机的所有应用变成另一种语言，只为用一次语音输入。
     * 真正的出路是换一个识别服务，或者给现在这个下语言包。
     */
    const val MSG_NO_ENGINE_FOR_LANGUAGE =
        "这台设备上的语音识别服务都不支持中文。到系统设置 → 默认应用 → 语音识别 换一个，" +
            "或者给现在这个服务下载中文语言包"
}

/**
 * 语言标签的比较。
 *
 * 引擎回报的语言标签写法五花八门，同一个中文有四种常见写法：
 * `zh`、`zh-CN`、`zh-Hans-CN`、`cmn-Hans-CN`（`cmn` 是普通话的 ISO 639-3 代码）。
 * 用 `==` 比较会把它们全判成不支持，然后用户看到「不支持当前语言」——
 * 而实际上是我们的比较写窄了。这是**只会在真机上暴露**的那类问题：
 * 单元测试里引擎返回什么我们说了算，真机上引擎返回什么它说了算。
 */
object LanguageTag {

    /** ISO 639-2/639-3 里指向中文的代码。 */
    private val CHINESE_ALIASES = setOf("cmn", "zho", "chi")

    /** 规范化：小写、下划线转连字符。`zh_CN` → `zh-cn`。 */
    fun canonical(tag: String): String = tag.trim().lowercase().replace('_', '-')

    /** 主语言子标签：`zh-Hans-CN` → `zh`。 */
    fun primary(tag: String): String {
        val c = canonical(tag)
        val head = c.substringBefore('-')
        return if (head in CHINESE_ALIASES) "zh" else head
    }

    /**
     * [candidate] 是否覆盖 [target]。
     *
     * 只比主语言，不比地区：引擎有 `zh` 就能应付 `zh-CN`；
     * 有 `en-US` 也能应付 `en-GB`。反过来若要求完整匹配，
     * 引擎返回 `zh` 而我们要 `zh-CN` 时会被误判成不支持——
     * 那种误判的代价是用户被拦住，比放过一次地区差异严重得多。
     */
    fun covers(candidate: String, target: String): Boolean {
        if (target.isBlank()) return true
        if (candidate.isBlank()) return false
        return primary(candidate) == primary(target)
    }

    /** 一组候选里有没有能覆盖 [target] 的。 */
    fun anyCovers(candidates: List<String>, target: String): Boolean =
        candidates.any { covers(it, target) }
}
