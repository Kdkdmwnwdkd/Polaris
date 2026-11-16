package com.beichen.polaris.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 按会话隔离的**待发附件**桶（D-5）。
 *
 * ## 修的是什么
 *
 * `_pendingImages` / `_pendingFiles` 原来是两个**进程级**的 `MutableStateFlow`：
 * 用户在会话 A 里挂了三张照片和一份 PDF，转身去 B 会话问一句别的事，那三张图和那份 PDF
 * 还在输入框条上——发出去就成了 B 的附件。
 *
 * 这与审计 F3（审批白名单跨会话）是同一类错误，但后果更直接：审批白名单泄漏的是
 * 「免弹窗」，附件泄漏的是**内容本身**。相册里的图、剪贴板导出的文本，被带进一个
 * 从没打算发它的对话里，此后这条对话的每一次上下文重放都会把它一起发给模型。
 *
 * ## 为什么不是「切会话清空」
 *
 * 那样确实堵住了泄漏，代价是用户切去看一眼别的会话再切回来，辛苦挑的 8 张图没了。
 * 正确语义是「附件跟着会话走」——与草稿（`DraftStore`）一致。用户对草稿的预期
 * 已经是「切走再回来还在」，附件没有理由更差。
 *
 * ## 形态
 *
 * 一份 `Map<convId, List<T>>` 作权威存储 + 一个 [visible] 投影当前会话。
 * 投影由 [activate] 显式驱动（切会话时调一次），而不是监听 `_activeConversationId`
 * 自动跟随——跟随需要收集协程与取消逻辑，而切会话是一个明确的单点事件。
 *
 * 线程：所有读写都在 [lock] 内，因为 `append` 是「读—拼—写」三步，`ConcurrentHashMap`
 * 只保证每一步原子。附件操作是低频事件（选图、删图、发消息），锁的代价可以忽略。
 *
 * @param empty 空列表值。传 `emptyList()` 即可；单列出来是为了让调用方一眼看见
 *   「这里返回的是常量，不是每次新建」。
 */
class ConversationBuckets<T>(private val empty: List<T> = emptyList()) {

    private val lock = Any()
    private val byConversation = HashMap<String, List<T>>()

    private val _visible = MutableStateFlow<List<T>>(empty)
    /** 当前会话的附件视图（UI 直接收这个）。 */
    val visible: StateFlow<List<T>> = _visible.asStateFlow()

    private var active: String = ""

    /**
     * 兜底：第一次写入时若还没 [activate] 过，就把这个会话采纳为当前会话。
     *
     * 没有它会出现一种很难查的静默失效：`active` 还是空串，`append(真实 id, …)` 写进了
     * 正确的桶，但 `conversationId != active`，[visible] 不更新——UI 上什么都不出现，
     * 而存储里明明有数据。问题出在「调用方忘了在启动时 activate」，代价却表现成
     * 「选图没反应」，排查方向会被完全带偏。
     */
    private fun adoptIfEmpty(conversationId: String) {
        if (active.isEmpty() && conversationId.isNotEmpty()) {
            active = conversationId
        }
    }

    /** 切到某个会话：后续读写都针对它，[visible] 同步换成它的附件。 */
    fun activate(conversationId: String) {
        synchronized(lock) {
            active = conversationId
            _visible.value = byConversation[conversationId] ?: empty
        }
    }

    /** 某个会话当前的附件（不改变视图）。 */
    fun of(conversationId: String): List<T> = synchronized(lock) {
        byConversation[conversationId] ?: empty
    }

    /** 当前会话当前的附件。 */
    fun current(): List<T> = synchronized(lock) { byConversation[active] ?: empty }

    fun size(conversationId: String): Int = synchronized(lock) {
        byConversation[conversationId]?.size ?: 0
    }

    fun set(conversationId: String, items: List<T>) {
        synchronized(lock) {
            adoptIfEmpty(conversationId)
            byConversation[conversationId] = items
            if (conversationId == active) _visible.value = items
        }
    }

    fun append(conversationId: String, item: T) {
        synchronized(lock) {
            adoptIfEmpty(conversationId)
            val next = (byConversation[conversationId] ?: empty) + item
            byConversation[conversationId] = next
            if (conversationId == active) _visible.value = next
        }
    }

    fun appendAll(conversationId: String, items: List<T>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            adoptIfEmpty(conversationId)
            val next = (byConversation[conversationId] ?: empty) + items
            byConversation[conversationId] = next
            if (conversationId == active) _visible.value = next
        }
    }

    /** 按谓词删；返回删除后的列表。 */
    fun removeIf(conversationId: String, predicate: (T) -> Boolean) {
        synchronized(lock) {
            val next = byConversation[conversationId]?.filterNot(predicate) ?: empty
            byConversation[conversationId] = next
            if (conversationId == active) _visible.value = next
        }
    }

    /**
     * 取走某个会话的全部附件并清空（发送时用）。
     *
     * 必须是一次原子操作：先 [of] 再 `set(empty)` 是「读—写」两步，两条发送路径
     * 交错时会有一边发出重复附件，另一边发出空。
     */
    fun take(conversationId: String): List<T> = synchronized(lock) {
        val taken = byConversation[conversationId] ?: empty
        if (taken.isNotEmpty()) {
            byConversation[conversationId] = empty
            if (conversationId == active) _visible.value = empty
        }
        taken
    }
}
