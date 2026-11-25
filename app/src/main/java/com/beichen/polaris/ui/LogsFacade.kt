package com.beichen.polaris.ui

import com.beichen.polaris.core.AgentLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 运行日志域（L8 / 3.1 第二批拆分，自 ChatViewModel 原地搬出）。
 *
 * 为什么能独立成块：日志卡片只做「读 [AgentLogger] 的内存缓冲 → 展示 / 清空 / 导出」，
 * 不依赖任何 store、不碰会话与工具表——是 188 个成员里依赖最少的一域（零注入）。
 *
 * 字段与方法体逐行照搬：最近 500 条刷新、清空后回刷、导出 500 条纯文本。
 */
internal class LogsFacade {

    /** L8 最近日志（格式化后的文本，最新的在前），供自动化页「运行日志」卡片展示。 */
    private val _logs = MutableStateFlow<List<String>>(emptyList())

    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /**
     * 拉取最近 [WINDOW] 条日志文本（最新的在前）刷新 UI。
     *
     * 原来是 200。2026-11-25 改成与内存容量同值：内存里本来就有 500 条、导出也是 500 条、
     * 启动时还提示「已恢复最后 500 条」，只有这一处偷偷截到 200——三个数字对不上，
     * 用户搜不到被截掉的那 300 条，而它们往往正是要找的旧记录
     * （「上午那次 401」「昨天崩溃前那几十行」）。
     */
    fun refreshLogs() {
        _logs.value = AgentLogger.recent(WINDOW).map { it.format() }
    }

    /**
     * 删除全部日志（内存缓冲 + 目录下所有 `agent-*.log` / `.log.1`），随后刷新列表。
     *
     * 2026-11-25 之前只删「当天」两个文件，用户点完清空、搜索照样命中两三天前的记录。
     */
    fun clearLogs() {
        AgentLogger.clear()
        refreshLogs()
    }

    /** 导出日志全文，供用户复制到剪贴板排查问题。 */
    fun exportLogs(): String = AgentLogger.snapshotText(WINDOW)

    /** 当前日志文件绝对路径（卡片底部展示，方便 adb pull）。 */
    fun logFilePath(): String = AgentLogger.currentLogFile()?.absolutePath ?: "（日志文件未就绪）"

    private companion object {
        /** 日志页窗口：与内存缓冲容量一致，取更多没有意义。 */
        val WINDOW = AgentLogger.MEMORY_CAPACITY
    }
}
