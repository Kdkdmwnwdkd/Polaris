package com.beichen.polaris.ui

import com.beichen.polaris.memory.MemoryItem
import com.beichen.polaris.memory.MemoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 记忆库域（L9 / 3.1 第二批拆分，自 ChatViewModel 原地搬出）。
 *
 * 为什么能独立成块：记忆卡片只做「对 [MemoryStore] 的增删改查 + 把结果刷进列表」，
 * 不读会话、不碰工具表、不做检索增强（注入式检索 `summaryForPrompt` 是生成链的事，
 * 留在 VM）。
 *
 * `memoryStore` 本体仍由 ChatViewModel 持有——四个 Memory*Tool 注册进工具表、
 * 会话恢复导入、prompt 注入都要直接用它；这里接收同一实例，两侧看到同一份数据。
 *
 * 字段与方法体逐行照搬：列表 50 条、检索 20 条、手动新增默认重要度 5。
 */
internal class MemoryFacade(private val memoryStore: MemoryStore) {

    /** L9 记忆列表快照，随写入/检索/遗忘/清空刷新。 */
    private val _memories = MutableStateFlow<List<MemoryItem>>(emptyList())

    val memories: StateFlow<List<MemoryItem>> = _memories.asStateFlow()

    /** 刷新记忆列表（最近 50 条，按更新时间倒序）。 */
    fun refreshMemories() {
        _memories.value = memoryStore.list(50)
    }

    /** 手动新增一条记忆（UI 直接录入，默认 note / 重要度 5）。 */
    fun addMemory(content: String, kind: String, tags: List<String>) {
        if (content.isBlank()) return
        memoryStore.add(content.trim(), kind, tags, 5)
        refreshMemories()
    }

    /** 关键词检索记忆；结果直接替换列表，便于查看命中情况。 */
    fun searchMemory(query: String) {
        _memories.value = if (query.isBlank()) memoryStore.list(50) else memoryStore.search(query.trim(), 20)
    }

    /** 遗忘指定记忆并重刷新列表。 */
    fun forgetMemory(id: String) {
        memoryStore.forget(id)
        refreshMemories()
    }

    /**
     * hotfix31：编辑一条记忆（详情卡「编辑」→ 对话框）。
     * 空白内容 / 目标不存在时 store 返回 false——UI 据此保持对话框不关，
     * 并给一句提示；成功则刷新列表（updatedAt 刷新会把它顶到最前）。
     */
    fun updateMemory(id: String, content: String, salience: Int, tags: List<String>): Boolean {
        val ok = memoryStore.update(id, content, salience, tags)
        if (ok) refreshMemories()
        return ok
    }

    /** 清空全部记忆（UI 需二次确认）并重刷新列表。 */
    fun clearMemory() {
        memoryStore.clear()
        refreshMemories()
    }
}
