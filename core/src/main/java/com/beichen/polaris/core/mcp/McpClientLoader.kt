package com.beichen.polaris.core.mcp

import com.beichen.polaris.core.AgentTool

/**
 * [McpToolLoader] 的真实实现：用 [McpClient] 握手 + 拉列表，包成 [McpToolAdapter]。
 *
 * 单独一个文件是为了让 `McpToolSync.kt` 保持零 Android/OkHttp 依赖——
 * 那边是 R-07 的正文（谁该被撤销），必须在 JVM harness 里能真跑。
 *
 * 一个实例服务一台服务器：适配出来的工具共享同一个 client，`tools/call` 才能复用连接。
 */
class ClientMcpToolLoader : McpToolLoader {

    override suspend fun load(server: McpServerConfig, maxTools: Int): Result<List<AgentTool>> {
        if (maxTools <= 0) return Result.success(emptyList())
        val client = McpClient(server.url)
        client.initialize().exceptionOrNull()?.let { return Result.failure(it) }
        val tools = client.listTools().getOrElse { return Result.failure(it) }
        return Result.success(
            tools.take(maxTools).map { McpToolAdapter(server.name, it.name, client, it) }
        )
    }
}
