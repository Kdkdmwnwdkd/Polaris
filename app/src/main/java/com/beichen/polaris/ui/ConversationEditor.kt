package com.beichen.polaris.ui

import com.beichen.polaris.core.ConversationStore
import com.beichen.polaris.core.DraftStore

/**
 * 会话级状态迁移的单一入口：新建 / 切换 / 删除 / 重命名 / 置顶 / 绑定目录。
 *
 * ## 为什么从 ChatViewModel 里拆出来
 *
 * 这六个动作共用三条不变量，原先散在 VM 的各方法里各写一遍，靠注释互相提醒：
 *
 * ① **草稿跟着会话走**：切走前把输入框存回旧会话，切来后把新会话的草稿填回输入框。
 * ② **下标类派生状态必须作废**：消息列表一换，按消息下标索引的译文就指错了行
 *   （W-30）。这条在 `loadConversation` 与 `reloadActiveMessages` 里各写过一次。
 * ③ **在途运行只约束「会切走显示」的动作**：新建拒绝、切换允许（后台继续生成）、
 *    删在途会话拒绝（否则收尾的落库会把消息写回已删 id，「删掉的会话复活」）。
 *
 * 拆出来之后，这三条各自只有一个实现点；而且本类**纯 JVM**（不 import Android /
 * Compose），能被 scripts/qa 的 harness 直接编译运行——VM 那一层只能静态断言，
 * 回到「靠注释保证正确」的老路。
 *
 * ## 一条顺手修掉的真缺陷
 *
 * 删除当前会话时，旧实现先 `drafts.remove(id)`，紧接着 `loadConversation()` 的第一行
 * 又无条件 `drafts.set(旧 id, 输入框内容)`——**把刚清掉的草稿写了回去**。触发条件很日常：
 * 用户打了半截话，去抽屉里把这个会话删掉（输入框非空）。后果是 drafts.json 里留下一条
 * 以已删除 id 为键的草稿，永远读不出来，备份时还会被一起带走。
 *
 * 现在只有 [switchTo] / [create] 保存上一段草稿——它们的语义是「用户主动换到另一条
 * 会话，原来那条还在」。而 [delete] / [adopt] 明确不保存：调用它们时旧会话已经不存在
 * （删除）或整体被替换（备份恢复），存下来只能是孤儿数据。
 */
internal class ConversationEditor(
    private val store: ConversationStore,
    private val drafts: DraftStore,
    /** 当前显示的会话 id（store 保证非空）。 */
    private val activeId: () -> String,
    private val setActiveId: (String) -> Unit,
    /** 输入框当前内容（草稿的写入源）。 */
    private val inputText: () -> String,
    private val setInputText: (String) -> Unit,
    /** 是否有在途生成（新建要拒、切换放行）。 */
    private val running: () -> Boolean,
    /** 在途生成的归属会话；无运行为 null。 */
    private val ownerId: () -> String?,
    /** 按 id 重建消息列表（投影 loadOf）。 */
    private val onConversationLoaded: (String) -> Unit,
    /** 作废按消息下标索引的派生状态（译文）。 */
    private val onTranslationsStale: () -> Unit,
    /** 会话列表有增删改后刷新摘要（抽屉「最近对话」与顶栏标题消费）。 */
    private val onListChanged: () -> Unit,
    /** 开新会话前的收尾（W-102：粘性工具组随任务重置）。 */
    private val onFreshConversation: () -> Unit,
    /**
     * 输入区换主人时丢弃待发附件。
     *
     * 为什么不放进 [setInputText]：那是「换文本」，而附件是另一个状态（[ComposerState]）。
     * 两者共用 [open] 这一个迁移点才是重点——新建 / 切换 / 删除当前会话 / 备份恢复
     * 四条路径全都经过这里，挂在这里不会有第五条路径漏掉。
     *
     * 刻意不给默认值：与其余参数一样强制接线，漏接就编不过，而不是「悄悄不清附件」。
     */
    private val onAttachmentsDropped: () -> Unit,
) {

    /** 被拒的原因。返回值而非静默 return——「点了没反应」在真机上无法归因。 */
    sealed interface Refusal {
        /** 有在途生成，该动作会切走显示或删掉正在写的会话。 */
        data object WhileRunning : Refusal

        /** 目标会话正是在途生成的归属（删了它，收尾落库会把消息写回已删 id）。 */
        data object ConversationBusy : Refusal
    }

    /**
     * 新建会话并切过去（顶栏 / 抽屉「＋ 新对话」）。
     *
     * 运行中拒绝：新建即切 activeConversationId，在途事件（ToolStarted/ToolFinished、
     * history 兜底、onFailure）会按新 id 落盘，把旧会话的尾巴写进新会话。
     * hotfix30 起该风险已由 owner 捕获消除，但**仍保守拦着**——放开后用户在新空会话
     * 发消息会被 send() 的 isRunning 守卫拒（无并行发送），「能进不能发」比「不能进」更割裂。
     */
    fun create(): Refusal? {
        if (running()) return Refusal.WhileRunning
        onFreshConversation()
        open(store.create(), keepPreviousDraft = true)
        return null
    }

    /**
     * 切换到指定会话（抽屉「最近对话」点击）。同 id 视作无操作。
     *
     * 运行中允许（hotfix30 真机反馈「我不能跳，不能过其他对话」）：在途任务按 owner
     * 继续后台生成（只落盘不上屏），这里只换显示态。写串风险由 owner 捕获机制消除。
     */
    fun switchTo(id: String): Refusal? {
        if (id == activeId()) return null
        open(id, keepPreviousDraft = true)
        return null
    }

    /**
     * 删除会话；删的是当前会话则顺手切到剩下的最近一个（消息列表不空指）。
     *
     * 两条拒绝都是数据安全，不是体验取舍：
     *  - 删在途会话：store 记录没了，收尾的 appendToStore 会把消息写回已删 id
     *    （ConversationStore 按需重建记录），「删掉的会话复活」成脏数据；
     *  - 运行中删当前显示的**非 owner** 会话：保守拦着。此刻 active 要迁移，
     *    而 UI 侧的草稿投影（syncDraftDisplay）与在途事件交错，没有验证过的顺序。
     */
    fun delete(id: String): Refusal? {
        if (id == ownerId()) return Refusal.ConversationBusy
        val isActive = id == activeId()
        if (isActive && running()) return Refusal.WhileRunning

        store.delete(id)
        // 会话没了，它的草稿就是垃圾数据。必须在 open() 之前清，且 open() 不能把
        // 输入框内容再存回来——这正是旧实现写出孤儿草稿的地方（见类注释）。
        drafts.remove(id)
        if (isActive) {
            adopt(store.ensureNotEmpty())
        } else {
            onListChanged()
        }
        return null
    }

    /**
     * 重命名（抽屉长按菜单 → 对话框）。
     * 不需要运行态守卫：只改标题、不切 activeConversationId、不动消息列表，
     * 在途事件仍按原 id 正常落盘。
     */
    fun rename(id: String, title: String) {
        store.rename(id, title)
        onListChanged()
    }

    /**
     * 置顶 / 取消置顶（抽屉长按菜单）。
     * 与 [rename] 同理：只改排序键，不碰显示态与在途落库。
     */
    fun setPinned(id: String, pinned: Boolean) {
        store.setPinned(id, pinned)
        onListChanged()
    }

    /** 绑定/解绑会话的项目目录（空串 = 解绑）。 */
    fun bindWorkspace(id: String, dir: String) {
        store.setWorkspaceDir(id, dir)
        onListChanged()
    }

    /**
     * 接管一条会话作为显示态，**不保存**输入框里的旧内容。
     *
     * 只给「旧会话已经不可用」的路径用：删除当前会话后的迁移、备份恢复后的重载。
     * 这两条路径上旧 id 已不在 store 里（或整体被替换），把输入框存回去就是孤儿草稿。
     */
    fun adopt(id: String) {
        open(id, keepPreviousDraft = false)
    }

    /** 消息列表被原地重建（变体切换 / 重新生成 / 重新发问后的 reloadActive）。 */
    fun noteMessagesRebuilt() = onTranslationsStale()

    /**
     * 显示态迁移的唯一实现。
     *
     * @param keepPreviousDraft 是否把输入框内容存回**上一条**会话。为 true 时也只存
     *   与目标不同的会话——同 id 重入会把刚设的值读回来，等于用旧草稿覆盖新输入。
     */
    private fun open(id: String, keepPreviousDraft: Boolean) {
        val prev = activeId()
        if (keepPreviousDraft && prev != id) drafts.set(prev, inputText())
        setActiveId(id)
        onConversationLoaded(id)
        // W-30：消息下标全变，旧译文清掉（否则会显示到别的消息下面）
        onTranslationsStale()
        setInputText(drafts.get(id))
        // 附件不跟会话走（没有按会话的持久化，理由见 [ComposerState.dropAttachments]）：
        // 不在这里清，新会话的输入框就会挂着上一个会话选的图——真机反馈的「换话题带过去」。
        onAttachmentsDropped()
        onListChanged()
    }
}
