package com.beichen.polaris.core.media

import kotlinx.serialization.Serializable

/**
 * 媒体生成 Provider 接口与 UI 层参数模型（调研 06 号文档直译）。
 *
 * 与 LLM 的 [com.beichen.polaris.core.LLMClient] 并列同层：文本 Provider 管对话，
 * 媒体 Provider 管「文生图 / 图生图 / 文生视频」。协议差异（同步/异步两段式）
 * 全部内聚在各实现类内，对外统一成「提交 → 拿状态 → 拿 URL」三件事。
 */

/** 媒体生成类型（UI 概念，与 Provider 无关）。 */
@Serializable
enum class MediaKind {
    /** 文生图。 */
    TEXT_TO_IMAGE,

    /** 图生图（带参考图）。 */
    IMAGE_TO_IMAGE,

    /** 文生视频。 */
    TEXT_TO_VIDEO
}

/**
 * 画面比例（UI 概念）。Provider 负责翻译成各家 size 参数
 * （如 DashScope 的 "1280*720"、OpenAI 的 "1792x1024"）。
 */
@Serializable
enum class Ratio(val label: String) {
    SQUARE("1:1"),
    WIDE("16:9"),
    TALL("9:16"),
    CLASSIC("4:3")
}

/**
 * 生成请求。参数模型独立于 Provider——UI 只认这套概念。
 *
 * @param refImageUris 参考图（http(s) URL 或 `data:image/...;base64,` data URI），
 *   仅 IMAGE_TO_IMAGE 使用。
 * @param extra 逃生舱：n / seed / style / model 等各家特有参数，原样透传。
 */
@Serializable
data class MediaRequest(
    val kind: MediaKind,
    val prompt: String,
    val ratio: Ratio = Ratio.SQUARE,
    val refImageUris: List<String> = emptyList(),
    val extra: Map<String, String> = emptyMap()
)

/**
 * 提交结果：任务句柄 = 各家侧任务 id（同步 Provider 可为 null）+ 当前状态。
 * 同步 Provider（如 OpenAI images）提交即 [MediaTaskState.Succeeded]，
 * 异步 Provider（DashScope 视频 / 火山 / MiniMax）提交即 [MediaTaskState.Running]，
 * 之后靠 [MediaProvider.poll] 推进。
 */
data class MediaTaskHandle(
    val providerTaskId: String?,
    val state: MediaTaskState
)

/** 任务状态机：进行中（带可选进度 0..1）/ 成功（URL 列表）/ 失败（可读原因）。 */
sealed interface MediaTaskState {
    data class Running(val progress: Float?) : MediaTaskState
    data class Succeeded(val urls: List<String>) : MediaTaskState
    data class Failed(val message: String) : MediaTaskState
}

/** 媒体生成 Provider（与文本 Provider 并列同层）。 */
interface MediaProvider {
    /** Provider id（dashscope / openai / volc / minimax）。 */
    val id: String

    /** 展示名（工坊表单里的下拉/胶囊文案）。 */
    val displayName: String

    /** 支持的生成类型（表单按 kind 过滤可用 Provider）。 */
    val kinds: Set<MediaKind>

    /**
     * 提交生成任务。返回任务句柄；同步 Provider 直接返回 Succeeded。
     * 失败抛 [MediaException]（HTTP 层）或各家语义错误。
     */
    suspend fun submit(request: MediaRequest): MediaTaskHandle

    /**
     * 轮询异步任务进度。[taskId] 为 [MediaTaskHandle.providerTaskId]。
     * 同步 Provider 不会收到轮询（提交即终态），实现可直接抛错表明用错。
     */
    suspend fun poll(taskId: String): MediaTaskState
}

/** 媒体 Provider 异常：带可读原因，UI 直接展示 message。 */
class MediaException(message: String) : Exception(message)
