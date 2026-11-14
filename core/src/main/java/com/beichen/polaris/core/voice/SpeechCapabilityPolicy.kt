package com.beichen.polaris.core.voice

/**
 * 语音失败之后**该做什么**。
 *
 * ## 为什么把「出路」从散文里抽成枚举
 *
 * 提示语是给人看的，但「提示够不够可执行」其实是个**能判定的问题**——
 * 只是判定它只能靠「文案里含不含『系统设置』这几个字」。而那种断言守不住行为：
 * 把文案从 A 文件搬到 B 文件，它照样绿；把「系统设置」改成「设置页」，它就红了，
 * 哪怕用户拿到的话还是一样有用。
 *
 * 抽成枚举之后，harness 断言的是**判定结果**而不是**文案长相**：
 * 「缺麦克风权限 → remedy 必须是 [GrantMicPermission]」。
 *
 * 另一半好处在 UI 侧：桌面提示要不要附一个「去授权」按钮，按 remedy 判断就行，
 * 不用写 `if (code == "no_permission")` 这种字符串匹配——
 * 字符串写错一个字母不会编译报错，只会静默地不再跳权限页。
 */
enum class SpeechRemedy {
    /** 去系统设置给本 App 打开麦克风权限。 */
    GrantMicPermission,

    /** 去系统设置里选定默认语音识别服务。 */
    SelectDefaultEngine,

    /** 装一个语音识别服务。 */
    InstallRecognitionService,

    /** 换系统语言，或在识别服务的设置里下载对应语言的识别包。 */
    ChangeLanguage,

    /**
     * 换一个识别服务（或给它下语言包）。
     *
     * 与 [ChangeLanguage] 分开：这是**换引擎**，不是换系统语言。
     * 真机上「不支持当前语言」绝大多数是当前默认引擎的问题——
     * 设备上往往还装着另一个认中文的引擎，换过去就好了。
     * 而换系统语言要把整台手机的所有应用都变一种语言，代价完全不对等。
     */
    SwitchEngine,

    /** 检查网络后重试。 */
    CheckNetwork,

    /** 麦克风被别的应用占着，等对方结束。 */
    FreeMicrophone,

    /** 直接重试即可。 */
    Retry,

    /** 没有更好的出路，只能把错误码带出来（未知错误码走这里）。 */
    ReportErrorCode,

    /** 启用系统自带的语音助手（它才是系统识别页的提供方）。 */
    EnableVoiceAssistant,
}

/**
 * 这次识别**走哪条路**。
 *
 * 这不是可有可无的细节：它决定「缺麦克风权限」到底算不算阻塞。
 *
 * ## 两条路的录音主体不同
 *
 * | 路径 | 谁录音 | 我们需要 `RECORD_AUDIO` 吗 |
 * |---|---|---|
 * | [SelfRecorded] | 我们自己调 `SpeechRecognizer` API，录音在**我们的进程**里 | **要** |
 * | [SystemPage] | 发 Intent 给系统识别页，录音在**系统进程**里 | **不要** |
 *
 * ## 依据（AOSP android-34 源码）
 *
 * 1. `RecognitionService.checkPermissionAndStartDataDelivery` 校验的是
 *    `getAttributionContextForCaller().getAttributionSource()`——
 *    也就是**调用 `SpeechRecognizer` 的那个 App** 的录音归属，不是「谁发出了 Intent」。
 * 2. `SpeechRecognizer.startListening` 传的是 `mContext.getAttributionSource()`，
 *    而 `mContext` 是 `createSpeechRecognizer(context)` 的那个 context。
 *    走系统识别页时，创建识别器的是系统语音助手（Google 语音服务 / 厂商助手），
 *    归属是它、权限也是它的。
 * 3. 框架自己的 `SearchView` 启动这个 Intent 时，
 *    `EXTRA_CALLING_PACKAGE` 填的是**系统搜索 Activity** 的组件名，而不是嵌入它的第三方 App
 *    （`SearchView.java:1684`）——第三方 App 用 SearchView 的语音搜索从不申请录音权限，
 *    这是最硬的旁证。
 *
 * ## 为什么必须把它写进类型
 *
 * 2026-11-14 之前，聊天页和桌宠共用同一条「缺麦克风权限就拦下」的判定。
 * 那是**误诊**：聊天页走的是系统识别页，权限跟它无关。
 * 照着误诊去改，用户点麦克风会被「请授予麦克风权限」拦下——
 * **而那条路本来是通的**。拦住一条本来能用的路，比不拦更糟：
 * 用户看到的还是「语音没反应」，而且这次是我们自己挡的。
 *
 * 所以「谁来录音」必须是判定输入的一部分，不能是调用点的一句注释。
 */
enum class VoicePath {
    /**
     * 自己录音：直接调 `SpeechRecognizer` API。
     *
     * 桌宠走这条（要求不弹界面）。录音发生在我们的进程里，
     * 所以**我们必须持有 `RECORD_AUDIO`**，否则引擎会回
     * `ERROR_INSUFFICIENT_PERMISSIONS`。
     */
    SelfRecorded,

    /**
     * 系统页录音：发 `ACTION_RECOGNIZE_SPEECH` 给系统识别页。
     *
     * 聊天页走这条。录音发生在系统进程里，**我们不需要 `RECORD_AUDIO`**；
     * 但这台机器上必须**有 App 声明了处理这个 Intent 的 Activity**，
     * 否则连系统识别页都弹不出来。
     */
    SystemPage,
}

/**
 * 语音识别**前置条件**的判定结果。
 *
 * 存在的理由：这两条路（桌宠的 API 版、聊天页的 Activity 版）共用同一个问题——
 * 「这台机器现在能不能语音输入」。答案不是布尔值，而是一组**互斥的失败原因**，
 * 因为每种原因要给的提示和出路完全不同：
 *
 * | 原因 | 现象 | 出路 |
 * |---|---|---|
 * | 没有识别服务 | 一按就说「本机没有语音识别服务」 | 装一个语音服务 |
 * | 缺麦克风权限（**只对自己录音的路径**） | 一按就说「没有麦克风权限」 | 授予权限 |
 * | 没有系统识别页（**只走系统页的路径**） | 一按就说「本机没有语音识别页」 | 启用系统语音助手 |
 * | 系统没选默认引擎 | 一按就静默失败 / 立刻报错 | 去系统设置选默认引擎 |
 * | 麦克风被占用 | 一按就说「另一个应用正在录音」 | 等对方结束 |
 * | 引擎不认这个语言 | 一按就报「语言不支持」 | 换系统语言 |
 *
 * 之前这些判断散在三个地方（[PetVoice.start] 的两处 if、[ChatScreen] 的一处），
 * 各自写各自的文案，结论还互相矛盾：桌宠认为「没服务」时，聊天页可能认为「有服务」。
 * 收敛到这里之后，两条路给出**同一句结论**，用户不用记「哪个入口说的不一样」。
 *
 * ## 为什么是纯函数
 *
 * 这类逻辑错了**不报错**，只是静默失败——桌宠表现为「按了没反应」，聊天页表现为
 * 「弹了个系统页然后什么都没发生」。CI 里没有真机也没有真引擎，行为断言永远抓不到它。
 * 所以能抽的部分必须抽成纯函数，让 harness 能逐条钉死。
 *
 * Android 相关的部分（查引擎列表、读权限、读设置）留在调用方，
 * 由它们把事实喂进来。
 */
sealed interface SpeechCapability {

    /** 可以开始识别。 */
    data object Available : SpeechCapability

    /**
     * 不能识别，且原因已知。
     *
     * @property code 机器可读码，进日志。
     * @property message 给人看的一句话，必须能回答「我现在该做什么」。
     * @property remedy 这句话背后的**结构化出路**，供 UI 决定要不要给动作按钮
     *        （例如 [SpeechRemedy.GrantMicPermission] 才值得挂一个「去授权」）。
     */
    data class Unavailable(
        val code: String,
        val message: String,
        val remedy: SpeechRemedy,
    ) : SpeechCapability

    /** 引擎存在，但当前语言的识别包没装/不可用。 */
    data class LanguageUnsupported(
        val code: String,
        val message: String,
        val remedy: SpeechRemedy,
    ) : SpeechCapability
}

/**
 * 语音识别的纯逻辑判定器。
 *
 * 全部是纯函数：输入是「事实」，输出是「结论」。不碰 Android API。
 */
object SpeechCapabilityPolicy {

    /** 引擎列表为空时的提示。 */
    const val MSG_NO_SERVICE =
        "本机没有语音识别服务，暂时只能用文字。系统设置里装一个语音服务（如 Google 语音服务）后可再用"

    /** 麦克风权限缺失时的提示。 */
    const val MSG_NO_PERMISSION =
        "没有麦克风权限。去系统设置给「北辰」打开麦克风后重试"

    /** 系统没设置默认识别引擎时的提示。 */
    const val MSG_NO_DEFAULT_ENGINE =
        "系统还没选定语音识别服务。在「系统设置 → 应用 → 默认应用 → 语音识别」里选一个就能用"

    /** 语言不受支持时的提示。 */
    const val MSG_LANGUAGE_UNSUPPORTED =
        "当前的语音识别服务不支持中文。到系统设置 → 默认应用 → 语音识别 换一个服务，" +
            "或给现在这个服务下载中文语言包"

    /** 没有 App 能处理系统识别 Intent 时的提示。 */
    const val MSG_NO_SYSTEM_PAGE =
        "本机没有系统语音识别页（系统语音助手或搜索应用可能被停用了），语音输入暂时用不了"

    /**
     * 判定能不能开始识别。
     *
     * @param path 这次识别走哪条路（谁来录音）。它决定下面哪几维算数——
     *        见 [VoicePath] 的说明。**不要把它当装饰性参数**：
     *        走 [VoicePath.SystemPage] 时，我们的麦克风权限与识别无关，
     *        拿它去拦用户会挡住一条本来能用的路。
     * @param hasRecognitionService 设备上是否存在任何识别服务（对应
     *        [android.speech.SpeechRecognizer.isRecognitionAvailable] 的语义）。
     *        两条路都要它：没有引擎，谁来录音都识别不出东西。
     * @param hasDefaultEngine 系统是否选定了默认识别引擎（对应
     *        `Settings.Secure.VOICE_RECOGNITION_SERVICE` 非空）。
     *        为 null 表示「查不到这个信息」——不据此下结论。
     * @param languageSupported 引擎是否认这个语言。为 null 表示「还没问过」。
     * @param hasMicPermission 麦克风权限是否已授予。**只在 [VoicePath.SelfRecorded] 时算数**，
     *        默认 true 表示「这一维对本次判定不适用，不构成阻塞」。
     * @param hasSystemRecognizerPage 是否有 App 声明了处理
     *        `ACTION_RECOGNIZE_SPEECH` 的 Activity。**只在 [VoicePath.SystemPage] 时算数**，
     *        默认 true 同上。
     * @return 判定结果。
     */
    fun decide(
        path: VoicePath,
        hasRecognitionService: Boolean,
        hasDefaultEngine: Boolean? = null,
        languageSupported: Boolean? = null,
        hasMicPermission: Boolean = true,
        hasSystemRecognizerPage: Boolean = true,
    ): SpeechCapability {
        // 顺序：先查「有没有服务」，再查「这条路上特有的前置条件」，
        // 最后才是配置类原因。
        //
        // 为什么「没服务」排在最前：**不因为它更容易修，而因为它更致命**——
        // 没有识别服务时，麦克风权限给不给都识别不出东西来。这时候先弹一个麦克风权限
        // 申请框，用户稀里糊涂给了，下一秒听到的却是「本机没有语音识别服务」：
        // 权限白给了（麦克风指示灯还亮起来，用户从此不信任这个 App），路也白走了。
        //
        // 倒过来的顺序才是对的：先说「没服务」（用户去装服务），装完再问权限——
        // 这时权限申请框是**有意义的**，它真的能换来一次识别。
        //
        // 两种顺序的最终步数一样（装服务 → 给权限 → 能用），
        // 差别只在于「有没有先要一个用不上的权限」。
        if (!hasRecognitionService) {
            return SpeechCapability.Unavailable(
                "no_service",
                MSG_NO_SERVICE,
                SpeechRemedy.InstallRecognitionService,
            )
        }
        // 路径特有的一条：自己录音才需要我们的录音权限。
        // 系统页那条路上，录音在系统进程里，我们的权限与它无关——**不许在这里拦用户**。
        if (path == VoicePath.SelfRecorded && !hasMicPermission) {
            return SpeechCapability.Unavailable(
                "no_permission",
                MSG_NO_PERMISSION,
                SpeechRemedy.GrantMicPermission,
            )
        }
        // 路径特有的另一条：走系统页就得有 App 能接这个 Intent，
        // 否则连识别页都弹不出来（ActivityNotFoundException）。
        if (path == VoicePath.SystemPage && !hasSystemRecognizerPage) {
            return SpeechCapability.Unavailable(
                "no_system_page",
                MSG_NO_SYSTEM_PAGE,
                SpeechRemedy.EnableVoiceAssistant,
            )
        }
        // 默认引擎为空时，Android 14 的 SpeechRecognizer 会在绑定阶段直接失败，
        // 而且失败发生在系统里、应用侧只能看到一个笼统的 ERROR_CLIENT。
        // 提前查出来才能给出「去系统设置选一个」这种能照做的提示。
        if (hasDefaultEngine == false) {
            return SpeechCapability.Unavailable(
                "no_default_engine",
                MSG_NO_DEFAULT_ENGINE,
                SpeechRemedy.SelectDefaultEngine,
            )
        }
        if (languageSupported == false) {
            return SpeechCapability.LanguageUnsupported(
                "language_unsupported",
                MSG_LANGUAGE_UNSUPPORTED,
                SpeechRemedy.SwitchEngine,
            )
        }
        return SpeechCapability.Available
    }

    /**
     * 引擎错误码。
     *
     * ## 为什么在 core 里重新定义一遍常量
     *
     * AOSP 的错误码是 `SpeechRecognizer.ERROR_*`，在 `android.speech` 包里。
     * 直接用它们会让 core 依赖 Android，于是这张表就不能被 JVM harness 编译——
     * 而它恰恰是最需要被逐条断言的东西（错了不报错，只静默失败）。
     *
     * 所以这里定义一份**同名同值**的常量，用数字字面量而不是 Android 常量。
     *
     * ## 脱钩风险与它的兜底
     *
     * 复制常量有风险：AOSP 改号，这张表就悄悄错位了。兜底是
     * `scripts/qa/run-speech-capability-verification.sh` 的 A 段——
     * 它拿 [android.speech.SpeechRecognizer] 的真实常量与这里逐个比对，
     * 对不上就红。**常量错位在 CI 里就会暴露，而不是等到用户遇到「网络超时」
     * 却看到「没听清」。**
     */
    object ErrorCode {
        /** = `SpeechRecognizer.ERROR_NETWORK_TIMEOUT` */
        const val NETWORK_TIMEOUT = 1

        /** = `SpeechRecognizer.ERROR_NETWORK` */
        const val NETWORK = 2

        /** = `SpeechRecognizer.ERROR_AUDIO` */
        const val AUDIO = 3

        /** = `SpeechRecognizer.ERROR_SERVER` */
        const val SERVER = 4

        /** = `SpeechRecognizer.ERROR_CLIENT` */
        const val CLIENT = 5

        /** = `SpeechRecognizer.ERROR_SPEECH_TIMEOUT` */
        const val SPEECH_TIMEOUT = 6

        /** = `SpeechRecognizer.ERROR_NO_MATCH` */
        const val NO_MATCH = 7

        /** = `SpeechRecognizer.ERROR_RECOGNIZER_BUSY` */
        const val RECOGNIZER_BUSY = 8

        /** = `SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS` */
        const val INSUFFICIENT_PERMISSIONS = 9

        /** = `SpeechRecognizer.ERROR_SERVER_DISCONNECTED` */
        const val SERVER_DISCONNECTED = 11

        /** = `SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED` */
        const val LANGUAGE_NOT_SUPPORTED = 12

        /** = `SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE` */
        const val LANGUAGE_UNAVAILABLE = 13
    }

    /**
     * 一个错误码的完整解读：**说什么** + **该做什么**。
     *
     * 两者必须放在一起，否则会出现「文案说去设置，出路却是重试」这种自相矛盾，
     * 而且没人会发现——因为它们在两个函数里，谁也不看谁。
     */
    private data class ErrorSpec(
        val message: String,
        val remedy: SpeechRemedy,
    )

    /**
     * 错误码 → 解读。**唯一**的映射来源。
     *
     * [explainError] 与 [remedyForError] 都从这里取，所以「话」和「出路」不可能分叉。
     */
    private fun specFor(code: Int): ErrorSpec = when (code) {
        ErrorCode.NETWORK_TIMEOUT -> ErrorSpec(
            "网络超时，识别不了。检查一下网络再试",
            SpeechRemedy.CheckNetwork,
        )
        ErrorCode.NETWORK -> ErrorSpec("网络不太好，识别不了", SpeechRemedy.CheckNetwork)
        ErrorCode.AUDIO -> ErrorSpec(
            "录音出错了，检查一下麦克风有没有被别的应用占着",
            SpeechRemedy.FreeMicrophone,
        )
        ErrorCode.SERVER -> ErrorSpec("识别服务出错了，稍后再试", SpeechRemedy.Retry)

        // 这一条最容易被写成「未知错误」，但它其实是**信息量最大**的一个：
        // Android 14 的 SpeechRecognizer 在「系统没选定默认引擎」或「绑定引擎失败」时
        // 都会走这里，应用侧看不到区别。与其笼统说「取消了」，不如给出最可能的那条出路。
        ErrorCode.CLIENT -> ErrorSpec(
            "识别服务连不上，去系统设置里确认已选定语音识别服务",
            SpeechRemedy.SelectDefaultEngine,
        )
        ErrorCode.SPEECH_TIMEOUT -> ErrorSpec("没听到声音，靠近麦克风再说一次", SpeechRemedy.Retry)
        ErrorCode.NO_MATCH -> ErrorSpec("没听清，再说一次试试", SpeechRemedy.Retry)
        ErrorCode.RECOGNIZER_BUSY -> ErrorSpec("识别服务正忙，等一下再试", SpeechRemedy.Retry)
        ErrorCode.INSUFFICIENT_PERMISSIONS -> ErrorSpec(MSG_NO_PERMISSION, SpeechRemedy.GrantMicPermission)
        ErrorCode.SERVER_DISCONNECTED -> ErrorSpec("识别服务断开了，稍后再试", SpeechRemedy.Retry)
        ErrorCode.LANGUAGE_NOT_SUPPORTED -> ErrorSpec(MSG_LANGUAGE_UNSUPPORTED, SpeechRemedy.SwitchEngine)
        ErrorCode.LANGUAGE_UNAVAILABLE -> ErrorSpec(
            "当前语言的识别包没装。到系统设置 → 默认应用 → 语音识别，给这个服务下载中文包，" +
                "或换一个已经支持中文的服务",
            SpeechRemedy.SwitchEngine,
        )
        else -> ErrorSpec("识别失败（错误码 $code）", SpeechRemedy.ReportErrorCode)
    }

    /**
     * 把引擎错误码翻译成「该做什么」。
     *
     * 与 [SpeechCapability] 分开：这个函数回答的是「已经开始了，但中途失败了」，
     * 而不是「一开始就不能开始」。
     *
     * @param code 引擎回调的错误码。
     * @return 给人看的一句话。
     */
    fun explainError(code: Int): String = specFor(code).message

    /**
     * 错误码对应的**结构化出路**。
     *
     * 与 [explainError] 同源（都走 [specFor]），所以不会出现「话说去设置、
     * 出路却是重试」这种自相矛盾。
     *
     * @param code 引擎回调的错误码。
     * @return 该做什么；未知错误码返回 [SpeechRemedy.ReportErrorCode]。
     */
    fun remedyForError(code: Int): SpeechRemedy = specFor(code).remedy
}
