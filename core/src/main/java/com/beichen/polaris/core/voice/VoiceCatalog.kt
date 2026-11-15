package com.beichen.polaris.core.voice

/**
 * 「这一条声音从哪来」——决定可用性判断，也决定回落顺序。
 *
 * 分成四类而不是「离线/在线」两类：`SYSTEM` 既不离线也不归我们管，
 * 它的音色、语速、甚至能不能出声都由系统语音包决定。把它和本地模型并成一类，
 * 就会出现「本地模型没下载」被误报成「设备不支持」这种错判。
 *
 * `CLONE` 与 `LOCAL` 都是本地合成，但失败面不同：内置模型的失败原因是「模型没装上」
 * （随包，理论上不会发生），克隆音色的失败原因是「参考音频丢了或不够长」——
 * 那是用户数据，删应用数据、换手机、清理存储都会让它消失。
 * 分成两类，界面才说得出「为什么这条用不了」。
 */
enum class VoiceKind { SYSTEM, LOCAL, ONLINE, CLONE }

/**
 * 一条可选的音色。
 *
 * @param id 应用内的稳定标识，存进 SharedPreferences 的就是它；改名字不改 id
 * @param label 界面上显示的名字
 * @param providerVoice 供应商侧的音色名（如 `zh-CN-XiaoxiaoNeural`）；`SYSTEM` 为空
 */
data class VoiceProfile(
    val id: String,
    val label: String,
    val kind: VoiceKind,
    val locale: String,
    val tagline: String,
    val providerVoice: String = "",
) {
    /** 名字永远不为空：持久化里被写坏的 id 也要能在界面上看见一个能读的词。 */
    val safeLabel: String get() = label.ifBlank { id.ifBlank { "未知音色" } }
}

/**
 * 音色目录。
 *
 * ## 为什么目录在 core
 *
 * 「有哪些音色、各自要不要联网」是纯粹的清单事实，与 Android 无关；
 * 而回落决策（[VoicePlan]）要用它，回落决策又必须能被 harness 独立编译——
 * 这条依赖链从 3.1 起就是这个项目的老账（见 `SpeechCapabilityPolicy` 的类注释）。
 *
 * ## 为什么 id 与音色名分开
 *
 * `providerVoice` 是供应商的私有字符串，会随供应商改版变化；`id` 是我们自己的。
 * 混成一个字段后，供应商一改名，用户存的选择就失效、回落链断在第一条。
 */
object VoiceCatalog {

    const val SYSTEM_ID = "system"

    val system = VoiceProfile(
        id = SYSTEM_ID,
        label = "系统语音",
        kind = VoiceKind.SYSTEM,
        locale = "auto",
        tagline = "设备自带引擎 · 音色取决于系统语音包",
    )

    /**
     * 本地 22kHz 模型（matcha-icefall-zh-baker + vocos 声码器）。
     *
     * 采样率 22.05kHz 是手机端本地模型的实用上限：再高，解码耗时超过实时，
     * 桌宠会「说一句卡一下」。它比在线神经音色「平」，但断网也能出声。
     */
    val local = VoiceProfile(
        id = "local-matcha-22k",
        label = "本地 22kHz 女声",
        kind = VoiceKind.LOCAL,
        locale = "zh",
        tagline = "离线可用 · 首次使用需下载 72MB 模型",
    )

    /**
     * 在线神经音色（同一批 Azure/Edge 音色，接口不同、听感一致）。
     *
     * 免 key 可用：客户端自己算 `Sec-MS-GEC` 令牌即可（见 app 层 `SecMsGec`）。
     * 但这是网页端私有协议，可能某天整体 403，所以回落链里它前面没有、
     * 后面必有本地音色——失败时用户听到的是「没那么好听」，不是「没声音」。
     */
    val online: List<VoiceProfile> = listOf(
        VoiceProfile("edge-xiaoxiao", "晓晓", VoiceKind.ONLINE, "zh", "女声 · 温柔", "zh-CN-XiaoxiaoNeural"),
        VoiceProfile("edge-xiaoyi", "晓伊", VoiceKind.ONLINE, "zh", "女声 · 活泼", "zh-CN-XiaoyiNeural"),
        VoiceProfile("edge-yunxi", "云希", VoiceKind.ONLINE, "zh", "男声 · 年轻", "zh-CN-YunxiNeural"),
        VoiceProfile("edge-yunjian", "云健", VoiceKind.ONLINE, "zh", "男声 · 浑厚", "zh-CN-YunJianNeural"),
        VoiceProfile("edge-yunyang", "云扬", VoiceKind.ONLINE, "zh", "男声 · 播报腔", "zh-CN-YunYangNeural"),
        VoiceProfile("edge-xiaobei", "晓北", VoiceKind.ONLINE, "zh", "女声 · 东北话", "zh-CN-liaoning-XiaobeiNeural"),
    )

    /** 界面顺序：在线在前（默认好听），本地垫后（兜底），系统最后（永远在）。 */
    val all: List<VoiceProfile> = online + local + system

    /**
     * 出厂默认：本地 22kHz。
     *
     * 不选在线，是因为它要联网：默认值必须是「装完立刻有声」，而不是「先确认网络」。
     * 本地模型随包装在 assets 里，断网也能用——它才是「开箱有声」的那一条。
     * 想要更好听的用户，去音色页把在线那一档点开即可，一个来回就能切换。
     */
    const val DEFAULT_ID = "local-matcha-22k"

    /**
     * 按 id 取音色。
     *
     * 找不到时返回 null 而不是抛异常：调用方是「读持久化」这种随时可能读到脏数据的路径。
     * 真正需要「一定有个音色」的地方用 [require]。
     */
    fun byId(id: String?): VoiceProfile? = all.firstOrNull { it.id == id }

    /** 一定返回一条：脏 id 归到系统引擎，与旧版本行为一致（老版本只有系统 TTS）。 */
    fun require(id: String?): VoiceProfile = byId(id) ?: system

    fun requiresNetwork(profile: VoiceProfile): Boolean = profile.kind == VoiceKind.ONLINE

    /** 该音色是否需要先在设备上准备模型。 */
    fun requiresModel(profile: VoiceProfile): Boolean = profile.kind == VoiceKind.LOCAL
}
