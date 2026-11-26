package com.beichen.polaris.core.media

/**
 * 媒体生成的门面（2026-11-26）。
 *
 * ## 为什么要有它
 *
 * 生成能力原来只被 `MediaScreen` 一个界面调用，`MediaRegistry` 的每个方法都吃 `Context`。
 * 结果是：**这个能力只能由人手点表单触发**，Agent 碰不到它。要让「画一张雨夜街景」
 * 变成一句话就能做的事，第一步是把「界面能调」变成「工具能调」。
 *
 * 但工具不能直接接 `MediaRegistry`——那样工具就绑上了 `Context`，纯 JVM 测试里连
 * new 都做不到，只能靠真机验证。这一层接口把 Android 依赖挡在实现类里（见 app 层
 * `AndroidMediaGateway`），工具本身只认这五个方法，可以用假实现跑全部分支。
 *
 * ## 与 UI 的分工
 *
 * UI 负责「浏览、重试、删」，工具负责「提交、等结果、报路径」。两边写的是同一份
 * `tasks.json`（[MediaTaskStore]），所以工具生成的图会立刻出现在作品列表里，
 * 不需要额外同步。
 */
interface MediaGateway {

    /** 支持 [kind] 的 Provider（id + 展示名），顺序即 UI 下拉顺序。 */
    fun providers(kind: MediaKind): List<MediaProviderInfo>

    /** 该 Provider 是否已配置 Key（openai 会回落到主 API Key，见 `ConfigStore.loadMediaKey`）。 */
    fun hasKey(providerId: String): Boolean

    suspend fun submit(providerId: String, request: MediaRequest): MediaTask

    /** 推进任务状态；成功时实现方负责下载落盘（本地路径才是资产真理）。 */
    suspend fun poll(task: MediaTask): MediaTask

    /** 最近的任务（新的在前），供 status / list 两个动作查历史。 */
    fun recent(limit: Int): List<MediaTask>
}

/** Provider 的展示信息：工具回话、错误提示都要说人话，不能只给 id。 */
data class MediaProviderInfo(val id: String, val displayName: String)
