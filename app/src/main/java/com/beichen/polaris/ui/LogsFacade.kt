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
 * 字段与方法体逐行照搬：最近 200 条刷新、清空后回刷、导出 500 条纯文本。
 */
internal class LogsFacade {

    /** L8 最近日志（格式化后的文本，最新的在前），供自动化页「运行日志」卡片展示。 */
    private val _logs = MutableStateFlow<List<String>>(emptyList())

    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /** 拉取最近 200 条日志文本（最新的在前）刷新 UI。 */
    fun refreshLogs() {
        _logs.value = AgentLogger.recent(200).map { it.format() }
    }

    /** 清空日志（内存缓冲 + 当日文件），随后刷新列表。 */
    fun clearLogs() {
        AgentLogger.clear()
        refreshLogs()
    }

    /** 导出日志全文，供用户复制到剪贴板排查问题。 */
    fun exportLogs(): String = AgentLogger.snapshotText(500)

    /** 当前日志文件绝对路径（卡片底部展示，方便 adb pull）。 */
    fun logFilePath(): String = AgentLogger.currentLogFile()?.absolutePath ?: "（日志文件未就绪）"
}
