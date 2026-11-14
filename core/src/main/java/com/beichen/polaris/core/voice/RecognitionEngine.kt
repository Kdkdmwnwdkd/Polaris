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
 * @property probed 这一项**真的问到了**吗。
 *
 *        为 `false` 时上面三个布尔值全部无意义——它们只是构造参数的默认值。
 *        这不是洁癖：2026-11-14 的真机诊断报告里，设备上有 2 个识别服务，
 *        报告却只列出 1 个。被吞掉的那个正是系统默认引擎（Google TTS），
 *        它没在 800ms 内应答，于是被当成「不存在」从列表里删掉了。
 *
 *        删掉一项的后果不是「少一行字」，而是**结论反转**：剩下的那项
 *        （Qualcomm）明确不支持中文，于是 `pick` 返回 null，报告宣布
 *        「没有能用的」——而我们其实从没问过那个最该问的引擎。
 *
 *        所以「没问到」必须作为第三种答案留下来，不能被折叠成「不支持」。
 */
data class EngineCapability(
    val component: String,
    val installedOffline: Boolean = false,
    val supportedOffline: Boolean = false,
    val online: Boolean = false,
    val isDefault: Boolean = false,
    val probed: Boolean = true,
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

    /**
     * 引擎**没回答**（超时、报错、或系统/引擎没实现这个查询）。
     *
     * 既不是支持，也不是不支持，而是一份**缺失的证据**。
     * 排在 [Unsupported] 之前：一个没回答的引擎值得试一次，
     * 而一个明确说了「不认中文」的引擎不值得。
     *
     * 为什么单独一档而不是并进 [Unsupported]：并进去就是拿「不知道」
     * 当「不行」用，后果是本来能用的引擎被判死、用户被拦住——
     * 2026-11-14 的真机报告正是这么错的。
     */
    Unknown(3),

    /** 不支持当前语言。 */
    Unsupported(4),
    ;

    /** 已经能用了（不需要用户再做什么）。 */
    val isReady: Boolean get() = this == ReadyOffline || this == ReadyOnline

    /**
     * 这个档位是否**明确**判定为「不支持当前语言」。
     *
     * 单独一个属性，是为了让「没问到」与「不支持」的区别落在**类型**上，
     * 而不是落在文案上。文案随时会改，而两者的处理方式必须永远不同：
     * 明确不支持 → 别浪费用户的时间，去换引擎；
     * 没问到 → 放行试一次，因为我们没有证据说它不行。
     *
     * 用 `describe(fit).contains("不支持")` 那种写法守不住这条：
     * 改一个措辞它就红了，而判错档位时它可能照样绿。
     */
    val isDefinitelyUnsupported: Boolean get() = this == Unsupported

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
        // 没问到就没有依据。必须排在最前：下面三个布尔在没问到时是默认值
        // （全 false），落到 else 分支就会被说成「不支持当前语言」。
        !engine.probed -> EngineFit.Unknown
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
     * 2. 否则按「离线已装 → 在线可用 → 需下载 → 未应答」挑最优。
     *    「未应答」排在「明确不支持」之前：没回答的引擎值得试一次。
     * 3. 全都明确不支持 → null（调用方据此给「换引擎」的提示，而不是硬着头皮录音）。
     *
     * @return 选中的引擎；null = 这台设备上**所有**引擎都明确说了不支持当前语言。
     *         注意 null 与「有引擎但没问到」不是一回事——后者会返回那个未应答的引擎。
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
     * - 有结果 → 只要有一个引擎**不是明确不支持**就算能试。
     *
     * 空表不能当成「都不支持」：它的真实含义是「没问到」。
     * 把「没问到」当成「不支持」会让低版本系统上的用户每次都被拦住——
     * 而那台设备可能好得很。
     *
     * 同理，**单个引擎没问到**也不能当成它不支持（见 [EngineCapability.probed]）。
     * 整张表里只要有一项是 [EngineFit.Unknown]，这里就给 `true`：
     * 我们没有证据说它不行，那就让它自己回答。
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
        // 措辞上要和「不支持」明显区分：用户看到「不支持」会去换引擎，
        // 而这里的正确处理是「先试一次」。
        EngineFit.Unknown -> "没问到（引擎没在超时内应答，或没实现这个查询；先按默认试）"
        EngineFit.Unsupported -> "不支持当前语言"
    }

    /**
     * 所有引擎都不认当前语言时的提示。
     *
     * 旧文案是「换个系统语言试试」——**这句话把代价推给了用户**：
     * 改系统语言会让整台手机的所有应用变成另一种语言，只为用一次语音输入。
     *
     * 上一版改成「到系统设置 → 默认应用 → 语音识别 换一个」，听起来对了，
     * 但在真机上同样是死路：2026-11-14 的截图显示，这台魅族 20 的「默认应用」
     * 里**根本没有「语音识别」这一项**（只有「文字转语音输出」）。
     * 用户照着做只会在 TTS 页面里打转，然后回来问「找不到」。
     *
     * 所以出口只能指向我们自己的页面——那里列的是真实的引擎清单，
     * 点一下就能指定，不需要去一个不存在的系统设置项里翻。
     */
    const val MSG_NO_ENGINE_FOR_LANGUAGE =
        "这台设备上的识别服务都不支持中文。到「设置 → 语音识别」指定一个能用的，" +
            "或给现在这个服务下载中文语言包"
}

/**
 * 引擎的人类可读名字。
 *
 * 存在的理由：设置页要把引擎摆出来让用户选，而 `com.google.android.tts/…GoogleTTSRecognitionService`
 * 这种东西没法读。也不能只显示包名——同一包里可能有多个识别服务。
 *
 * 纯函数，可被 JVM harness 断言：起名规则错了不报错，只会让用户在一串
 * 看不懂的字符串里瞎选。
 */
object EngineLabel {

    /** 常见厂商/产品前缀 → 给人看的名。按前缀匹配，长的放前面避免被短的抢走。 */
    private val KNOWN = listOf(
        "com.google.android.googlequicksearchbox" to "Google 语音服务",
        "com.google.android.tts" to "Google 语音",
        "com.qualcomm.qti" to "高通语音",
        "com.iflytek" to "讯飞语音",
        "com.baidu" to "百度语音",
        "com.huawei" to "华为语音",
        "com.hihonor" to "荣耀语音",
        "com.miui" to "小米语音",
        "com.xiaomi" to "小米语音",
        "com.oppo" to "OPPO 语音",
        "com.coloros" to "OPPO 语音",
        "com.vivo" to "vivo 语音",
        "com.meizu" to "魅族语音",
        "com.samsung" to "三星语音",
        "com.sec.android" to "三星语音",
    )

    /**
     * 组件名 → 短名。
     *
     * 已知产品给中文名；未知的退化为「类名去后缀后按驼峰拆词」，
     * 再退化到包名末段。**永远不返回空串**：设置页的每一行都必须可读，
     * 一个空标题会看起来像界面坏了。
     */
    fun friendly(component: String): String {
        val pkg = component.substringBefore('/')
        KNOWN.firstOrNull { pkg.startsWith(it.first) }?.let { return it.second }
        val simple = component.substringAfter('/', "")
            .substringAfterLast('.')
            .removeSuffix("Service")
            .removeSuffix("Recognition")
        val spaced = CAMEL.split(simple).joinToString(" ").trim()
        if (spaced.isNotEmpty()) return spaced
        val tail = pkg.substringAfterLast('.')
        return tail.ifEmpty { component.ifEmpty { "语音服务" } }
    }

    /** `GoogleTTSRecognition` → `Google TTS Recognition`（不动全大写缩写）。 */
    private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])")
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
