package com.beichen.polaris.ui

import com.beichen.polaris.core.AssistantMessage
import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.ConversationStore
import com.beichen.polaris.core.TOOL_INTERRUPTED_NOTE
import com.beichen.polaris.core.TokenUsage
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolMessage
import com.beichen.polaris.core.UiItem
import com.beichen.polaris.core.UserMessage
import com.beichen.polaris.core.toMsgRecord
import com.beichen.polaris.core.toUiItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「消息怎么上屏、怎么落库」的单一真源。
 *
 * ## 它守的四条纪律
 *
 * ① **显示态与持久态同源**：上屏的每一条都要落库，落库的每一条都能重新投影回来
 *   （[reloadActive] 用轮次变体结构整体重建）。改动前这两件事分散在 send / 事件回调 /
 *   失败分支里各写一遍，靠注释「与 send 同策略」保持同步。
 *
 * ② **读改写必须串行**：[append] 来自最多 5 路并行工具协程，而 `MutableStateFlow.value`
 *   只保证赋值原子，不保证「读-拼-写」原子。两路同时读到同一份旧列表，后写的会覆盖
 *   先写的——表现为并行工具调用时 UI 少一张卡（落盘不丢，切回来又出现，极难复现）。
 *   所以列表只有 [lock] 内一个追加入口。
 *
 * ③ **在途写按 owner 落库**：生成中用户切到别的会话时，旧会话的后台出字必须写回旧会话。
 *   落库目标 = owner ?: 当前会话（见 [appendToStore]）；上屏与否另由 [ownerActive] 决定。
 *
 * ④ **工具卡必有终态**：一轮结束时不允许还有 `running=true` 的卡（[settleInterrupted]）；
 *   落盘里残留的「执行中」只可能是进程被杀，重新载入时按「已中断」呈现（[loadOf]）——
 *   既不能一直转圈，也不能伪装成「已完成」。
 */
internal class ConversationProjection(
    private val store: ConversationStore,
    /** 当前显示的会话 id（对外可能为 null，内部由 store 保证非空）。 */
    private val activeId: () -> String,
    /** 在途运行的归属会话（无运行时为 null）。 */
    private val ownerId: () -> String?,
    /** owner 是否就是当前显示会话——决定在途事件要不要上屏。 */
    private val ownerActive: () -> Boolean,
    /** 落库后的回调（刷新会话摘要列表）。 */
    private val onStoreChanged: () -> Unit,
    /**
     * 与宿主共享的监视器：工具事件回调（[handleAgentEvent]）持锁做多步更新时再调进来，
     * 必须落在同一把锁上，否则「并行工具少一张卡」的老问题会从另一个入口复活。
     * `synchronized` 可重入，同线程二次进入不会死锁。
     */
    private val lock: Any,
    /**
     * 本地历史因超过 [ConversationStore.MAX_TURNS] 被截断时回调（参数 = 丢弃的轮数）。
     *
     * 默认空实现让既有构造点（含 harness）零改动。生产必须接上：这条路径会真的
     * 从文件里删掉最早的对话，静默删数据比删数据本身更糟。
     */
    private val onHistoryTruncated: (dropped: Int) -> Unit = {},
    /**
     * 当前生效的模型标识（W-RT7），用于给助手气泡打上「这条回答是谁生成的」。
     *
     * 用函数而不是值：模型可以在会话中途切换，而投影对象是长生命周期单例——
     * 构造期抓一个字符串，等于把「切模型前一秒的回答」永远记成旧模型。
     *
     * 默认返回空串，让既有构造点（含 harness）零改动：空 = 未记录，
     * 费用下钻把它归到「未知模型」，不会误算钱。
     */
    private val currentModelId: () -> String = { "" },
) {

    private val _items = MutableStateFlow(loadOf(activeId()))
    val items: StateFlow<List<UiItem>> = _items.asStateFlow()

    /** 追加一条已构造好的 UI 项（唯一追加入口，见类注释②）。 */
    fun appendItem(item: UiItem) = synchronized(lock) {
        _items.value = _items.value + item
    }

    /**
     * 把「执行中」的工具卡替换成终态（同一 call.id）。
     *
     * 返回 false = 列表里没有对应的执行中卡（用户切走期间只落盘、没上屏）——
     * 调用方据此跳过重复落库，终态由 send() 的 history 兜底补。
     */
    fun finishTool(callId: String, done: UiItem): Boolean = synchronized(lock) {
        val cur = _items.value
        val idx = cur.indexOfFirst { it is UiItem.Tool && it.call.id == callId && it.running }
        if (idx < 0) return@synchronized false
        _items.value = cur.toMutableList().also { it[idx] = done }
        true
    }

    /** 整体替换（切会话 / 变体切换 / 重新生成后重建）。同样入锁，避免与在途回调交错丢事件。 */
    fun replace(items: List<UiItem>) = synchronized(lock) {
        _items.value = items
    }

    /**
     * 把一条协议层消息投影成 UI 项。
     *
     * @param usage 仅由 send() 汇总整轮后传给最终回答；中间轮（只有 toolCalls）不传。
     */
    fun appendMessage(msg: ChatMessage, usage: TokenUsage? = null) {
        val item = when (msg) {
            is UserMessage -> UiItem.User(msg.content, timeMs = System.currentTimeMillis())
            // 功能五（P1-5/P1-9）：usage 与 reasoning 随消息透传，气泡可折叠展示推理过程
            is AssistantMessage -> UiItem.Assistant(
                msg.content,
                usage = usage,
                reasoning = msg.reasoning,
                timeMs = System.currentTimeMillis(),
                // W-RT7：归属到回答完成时的当前模型（本地推理统一记 local-llama，
                // 它在价格表里是 0，且不会误撞云端模型名）
                modelId = currentModelId()
            )
            is ToolMessage -> UiItem.Tool(
                ToolCall(msg.id, msg.name, msg.arguments),
                msg.content,
                msg.isError,
                msg.images
            )
            else -> return
        }
        // hotfix30：显示态只在 owner==active 时追加（后台续跑期间只落盘）
        if (ownerActive()) appendItem(item)
        appendToStore(item)
    }

    /**
     * 同步一条消息到会话存储。
     *
     * hotfix30：在途写按 [ownerId]（启动时捕获的归属会话），不读 current——用户生成中切到
     * 别的会话，旧会话的后台出字全部落回旧会话，不写串；非在途调用仍落当前会话。
     *
     * 每条消息后都刷摘要流：工具事件频次高（每轮多个 Started/Finished），不刷的话抽屉
     * 「最近对话」的标题/条数/updatedAt 整个会话期间全陈旧。store.list() 是内存快照
     * map+sort（synchronized 内、无磁盘 IO），可负担。
     */
    fun appendToStore(item: UiItem) {
        val dropped = store.appendMessage(ownerId() ?: activeId(), item.toMsgRecord())
        if (dropped > 0) onHistoryTruncated(dropped)
        onStoreChanged()
    }

    /** 用当前会话的轮次变体结构重建列表（变体切换 / 重新生成 / 重新发问后调用）。 */
    fun reloadActive() {
        replace(loadOf(activeId()))
    }

    /**
     * 回合收尾：把仍是「执行中」的工具卡补成终态（R-06）。
     *
     * 两份数据都要动，因为它们不是同一份：
     *  - **显示态**：`_items` 里 `running=true` 的卡直接翻。regenerate 的取消路径
     *    `rebuildMessages=false`（不重建列表），不翻就会一直转圈到用户切走；
     *  - **持久态**：`store.settlePendingTools` 按 [convId] 扫全量记录。用户在**别的
     *    会话**里点停止时，owner 会话的工具卡从没上过屏（`ownerActive` 为 false），
     *    显示态里根本没有它——只翻 `_items` 会漏掉这一类，而它恰恰是最常见的：
     *    「发出去、切走、在别处点停止」。
     *
     * @param convId 本轮归属会话；由运行控制器在清空 owner **之前**传入。
     * @return 持久态补了几条。显示态的翻转不单独计数——重载后能看见的是存储里那一份，
     *   它才是「这张卡到底什么状态」的真源。
     */
    fun settleInterrupted(convId: String): Int = synchronized(lock) {
        if (activeId() == convId) {
            val cur = _items.value
            var flipped = 0
            val next = cur.map { item ->
                if (item !is UiItem.Tool || !item.running) item
                else {
                    flipped++
                    item.asInterrupted()
                }
            }
            if (flipped > 0) _items.value = next
        }
        val stored = store.settlePendingTools(convId)
        if (stored > 0) onStoreChanged()
        stored
    }

    /**
     * 读某个会话的投影（切会话时用；不存在返回空表）。
     *
     * R-06：落盘里 `pending` 的记录 = 上一轮没走完（进程被杀 / 收尾没跑到）。
     * 只有当**这条会话确实正在跑**（owner 就是它）时才保留转圈——用户切走再切回在途
     * 会话，进度理应继续显示。其余情况一律按「已中断」呈现：重新载入还转圈等于永远
     * 转圈，而按空结果渲染成「已完成」更糟——那是在撒谎。
     */
    fun loadOf(convId: String): List<UiItem> {
        val items = store.load(convId)?.toUiItems() ?: emptyList()
        if (ownerId() == convId) return items
        return items.map { if (it is UiItem.Tool && it.running) it.asInterrupted() else it }
    }
}

/** 执行中的卡 → 「已中断」态：停转圈、补可见文案、状态单列（不混进「已失败」）。 */
private fun UiItem.Tool.asInterrupted(): UiItem.Tool = copy(
    running = false,
    isError = true,
    interrupted = true,
    result = result.ifBlank { TOOL_INTERRUPTED_NOTE }
)
