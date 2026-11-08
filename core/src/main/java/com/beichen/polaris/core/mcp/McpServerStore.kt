package com.beichen.polaris.core.mcp

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * MCP 服务器清单的持久化（W-14）。
 *
 * 之前 MCP 只有「临时填一个 URL 测一测」的页面，关闭页面即丢；对话里也用不到。
 * 这里把服务器列表落到 SharedPreferences（JSON 数组），供：
 *  - MCP 页面增删改；
 *  - ChatViewModel 启动时按 enabled 列表批量连接并把工具注册进 ToolRegistry。
 *
 * 为什么不用 Room：条目数量级是个位数，JSON 足够；零新增依赖。
 *
 * 数据结构 [McpServerConfig] 在 `McpModels.kt`——它不依赖 Android，同步逻辑要拿它做纯函数断言。
 */
class McpServerStore(context: Context) {

    private val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** 读出全部服务器；损坏的 JSON 按空列表处理（不崩、不丢用户手写内容之外的资产）。 */
    fun load(): List<McpServerConfig> = runCatching {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        json.decodeFromString<List<McpServerConfig>>(raw)
    }.getOrDefault(emptyList())

    fun save(list: List<McpServerConfig>) {
        runCatching { sp.edit().putString(KEY, json.encodeToString(list)).apply() }
    }

    fun upsert(config: McpServerConfig): List<McpServerConfig> {
        val list = load().toMutableList()
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) list[idx] = config else list.add(config)
        save(list)
        return list
    }

    fun remove(id: String): List<McpServerConfig> {
        val list = load().filterNot { it.id == id }
        save(list)
        return list
    }

    fun setEnabled(id: String, enabled: Boolean): List<McpServerConfig> {
        val list = load().map { if (it.id == id) it.copy(enabled = enabled) else it }
        save(list)
        return list
    }

    private companion object {
        const val PREFS = "agentkit_mcp"
        const val KEY = "servers_json"
    }
}
