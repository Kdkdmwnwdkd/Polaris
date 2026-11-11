package com.beichen.polaris.core

/**
 * 一条消息在界面上的表示（气泡 / 工具卡）。
 *
 * ## 为什么住在 core 而不是 ui
 * 这是 P4-1 的下沉：它原先是 `ui/ChatViewModel.kt` 里的一个 sealed interface，
 * 而 `core/ConversationStore.kt` 需要用它做「落盘记录 ↔ 界面项」的映射，
 * 于是 `core` 反过来 `import com.beichen.polaris.ui.UiItem`——整个工程唯一一处
 * 分层倒置（架构守卫的基线里那张豁免条目就是它）。
 *
 * 倒置的代价不是「不好看」：`core` 本应可以在没有 UI 的 JVM 里单独编译与测试
 * （记忆、会话、备份全都只有 core 依赖），一个 `ui` 引用会把整条 Compose 依赖链
 * 拖进每一次独立编译。R11~R13 的 harness 已经三次因为「顺手引了一个上层类型」
 * 而编不过。
 *
 * 判据：**纯数据、无 Compose 类型、被 core 与 ui 同时需要** → 住在 core。
 * [TokenUsage] 与 [ToolCall] 本来就在 core，所以这里没有任何新依赖。
 *
 * ## 字段语义
 * 三个子类型都只描述「显示什么」，不含任何行为：事件如何驱动、生命周期如何，
 * 都在 ViewModel 与 Compose 侧。
 */
sealed interface UiItem {

    /**
     * 用户气泡。
     *
     * @param turnIndex 该提问所属轮次下标；重新发问（截断其后轮次）按它定位；
     *   `< 0` 表示未归属（实时上屏的草稿）。
     * @param timeMs 消息时间（epoch 毫秒）；`0` = 未记录，UI 不显示时间行。
     */
    data class User(
        val text: String,
        val turnIndex: Int = -1,
        val timeMs: Long = 0
    ) : UiItem

    /**
     * 助手气泡。
     *
     * Phase 2 DoD②：额外携带「变体游标」元数据，供气泡旁切换器 `← 2/3 →` 消费。
     * 默认值保证旧构造点（只有 text）零改动即兼容——单变体、无切换器。
     *
     * @param variantIndex 当前选中变体下标（0-based）；`< 0` 表示非分叉消息（旧数据/中间助手）。
     * @param variantCount 该轮变体总数；`<= 1` 时不显示切换器。
     * @param turnIndex 该轮在会话 [TurnRecord] 列表中的下标；重新生成 / 切换 /
     *   重新发问都按它定位轮次；`< 0` 表示未归属任何轮。
     * @param usage 本条回答的 token 用量（一个回合多次 LLM 往返的合计；
     *   null = 服务端没给 usage，UI 不显示而非猜一个数）。
     * @param reasoning 本条回答的推理过程（思维链全文）；
     *   null = 模型没给思考，UI 不渲染折叠块而非摆个空框。
     * @param modelId 产生本回答的模型标识（W-RT7）；空 = 旧数据/未记录，费用下钻归「未知模型」。
     *   本地推理统一记 `local-llama`——它价格表里是 0，且不会误撞云端模型名。
     */
    data class Assistant(
        val text: String,
        val variantIndex: Int = -1,
        val variantCount: Int = 1,
        val turnIndex: Int = -1,
        val usage: TokenUsage? = null,
        val reasoning: String? = null,
        val timeMs: Long = 0,
        val modelId: String = ""
    ) : UiItem

    /**
     * 工具调用卡。
     *
     * @param result 工具输出（已截断的展示文本，不是回喂给模型的原始载荷）。
     * @param images 工具返回的图片（data URL 列表），如 ui_screenshot 截图，直接渲染在对话中。
     * @param running 执行中态（`AgentEvent.ToolStarted` 已上报、`ToolFinished` 未到）。
     * @param interrupted 被中断（R-06）：这一轮在工具执行期间被停止 / 进程被杀，终态永远
     *   不会到达。与 [isError] 分开，是因为「用户点了停止」不是工具失败，文案与配色都不同；
     *   但它**不转圈**——转圈意味着「还在等」，而这里已经没有人在等了。
     */
    data class Tool(
        val call: ToolCall,
        val result: String,
        val isError: Boolean,
        val images: List<String> = emptyList(),
        val running: Boolean = false,
        val interrupted: Boolean = false
    ) : UiItem
}

/**
 * LazyColumn 的稳定身份（R-05）。
 *
 * 不给 key 时 Compose 拿**下标**当身份：工具卡一插入，其后每一项的身份都换了，
 * 整表按新身份重建——滚动位置跳一下、图片闪一下、正展开的工具卡自己合上。
 * 这不是「性能损耗」，是看得见的交互抖动。
 *
 * 用数据模型自己的主键，不另造一套：
 * - 工具卡 → [ToolCall.id]（与落盘同一个 id，全生命周期不变）
 * - 已归属轮次的气泡 → 轮次下标（每轮恰好一个用户气泡 + 一个选中变体）
 * - 未归属的（实时草稿、trace 里的中间消息）→ 下标兜底：它们本来就没有稳定身份，
 *   硬编一个反而会在「草稿转正」那一刻制造一次身份迁移
 *
 * @param index 在列表里的位置，**仅用于兜底**；有稳定主键时忽略。
 */
fun UiItem.uiKey(index: Int): String = when (this) {
    // 空 id 的老数据（手工编辑、早期版本）退回下标，否则多张卡会撞同一个 key ——
    // Compose 遇到重复 key 直接抛异常，整个对话页白屏。
    is UiItem.Tool -> "t:" + call.id.ifEmpty { "@$index" }
    is UiItem.User -> if (turnIndex >= 0) "u:$turnIndex" else "u@$index"
    is UiItem.Assistant -> if (turnIndex >= 0) "a:$turnIndex" else "m@$index"
}

/**
 * 给整表生成 key，保证**两两不同**（审计 F17）。
 *
 * [uiKey] 假设「工具调用 id 唯一」，可 id 不是本地产的——它从模型供应商回来。
 * 中转站、兼容层、本地 llama.cpp 都会复用 id；重试与分支（reask）也会把同一条
 * tool_call 复制成两份。一旦撞上，Compose 在 `LazyColumn` 里直接抛
 * `IllegalArgumentException: Key "t:xxx" was already used`，**整个对话页崩掉**，
 * 用户连历史会话都打不开。
 *
 * 真机复现（模拟器 API 30，mock 端点每次回同一个 `call_mock_1`）：
 *
 * ```
 * 07:36:34.110 java.lang.IllegalArgumentException: Key "t:call_mock_1" was already used
 *    at androidx.compose.foundation.lazy.LazyListMeasuredItemProvider.getAndMeasure(...)
 * ```
 *
 * 修法要同时满足两件事：
 *  - **首次出现保持原样**（`t:<id>`）——稳定身份不能变，否则滚动位置与展开态全丢；
 *  - **重复出现必须唯一**——追加 `@dup<下标>`，下标在同一张表内唯一，兜底成立。
 *
 * 不改 [uiKey] 本身：它的语义（单条 → 一个 key）是对的，错的是「假设全局唯一」。
 * 唯一性是**整表**性质，就该由整表来保证。
 */
fun List<UiItem>.uiKeySeq(): List<String> {
    val seen = HashSet<String>(size)
    return mapIndexed { index, item ->
        val key = item.uiKey(index)
        if (seen.add(key)) key else "$key@dup$index"
    }
}
