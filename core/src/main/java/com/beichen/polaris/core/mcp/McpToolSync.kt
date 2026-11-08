package com.beichen.polaris.core.mcp

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.ToolRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * R-07：MCP 工具的**回卷式同步**。
 *
 * ## 修的是什么
 *
 * 修前只有「注册」没有「撤销」：`registerMcpTools()` 在启动时把 enabled 服务器的工具
 * 挨个 `registry.register(...)`，而用户在 MCP 页关掉/删掉服务器时，**没有任何人通知
 * 工具表**。于是工具仍然在列表里，模型仍然会调，调用打到一条已经不该用的连接上。
 *
 * ## 为什么不是「在 UI 里补一句 unregister」
 *
 * 那样只能治「用户在 MCP 页点删除」这一条路径，换个入口（配置迁移、启动时服务器已失效、
 * 同一 URL 改了地址）就又漏一次。这里把注册建模成**随持有者存续的副作用**：
 *
 *  - 每个持有者（一台服务器）有自己的 owner key；
 *  - 每次同步算的是「目标状态」，不是「再装一批」——多出来的自动撤销；
 *  - 幂等：同一份配置重复同步，第二次什么都不做。
 *
 * 判断「该撤销谁」是纯函数 [planMcpSync]，可以在 JVM 里直接断言；
 * [McpToolBinder] 只负责把计划执行出去（连服务器、装载工具）。
 */

/** 一台服务器在工具表里的持有者键。带前缀是为了与将来的「技能包持有者」区分。 */
fun mcpOwnerKey(serverId: String): String = "mcp:$serverId"

/**
 * 一次同步的决策结果（纯数据，无副作用）。
 *
 * @param toRevoke  目标里已不存在、或地址变了的 owner key
 * @param toConnect 需要（重新）连接的服务器，已按配置顺序排好
 * @param unchanged 地址没变、无需重连的 owner key
 */
data class McpSyncPlan(
    val toRevoke: List<String>,
    val toConnect: List<McpServerConfig>,
    val unchanged: List<String>
) {
    val isNoop: Boolean get() = toRevoke.isEmpty() && toConnect.isEmpty()
}

/**
 * 算出「从 [installed] 走到 [servers] 描述的现状」需要做什么。纯函数。
 *
 * @param installed `owner key -> 已装时的地址指纹`。地址变了要重连——旧连接指向旧地址，
 *                  留着它等于「停用了但还在调」的同一个 bug（只是换了触发方式）。
 */
fun planMcpSync(
    installed: Map<String, String>,
    servers: List<McpServerConfig>
): McpSyncPlan {
    val desired = LinkedHashMap<String, McpServerConfig>()
    servers.forEach { s ->
        if (s.enabled && s.url.isNotBlank()) desired[mcpOwnerKey(s.id)] = s
    }
    // 地址改了的也算撤销：旧连接指向旧地址，留着就是同一个 bug 换了个触发方式。
    // 先撤后连——重连失败时宁可没有工具，也不要一个指向作废地址的工具。
    val toRevoke = installed.keys.filter { owner ->
        val target = desired[owner]
        target == null || installed[owner] != fingerprintOf(target)
    }
    val toConnect = desired.filter { (owner, server) -> installed[owner] != fingerprintOf(server) }.values.toList()
    val unchanged = desired.keys.filter { installed[it] == fingerprintOf(desired.getValue(it)) }
    return McpSyncPlan(toRevoke = toRevoke, toConnect = toConnect, unchanged = unchanged)
}

/** 地址指纹：只认规范化后的 URL，尾部空格不算改动。 */
internal fun fingerprintOf(server: McpServerConfig): String = server.url.trim()

/**
 * 装载器：连一台服务器、拉 `tools/list`、把结果包成 [AgentTool]。
 *
 * 抽成接口是为了让 [McpToolBinder] 不依赖 OkHttp/Android——同步语义（谁该被撤销）
 * 才是 R-07 的正文，那部分必须能真跑断言。真实实现见 `McpClientLoader.kt`。
 */
interface McpToolLoader {    /**
     * @param maxTools 本次最多返回几个（总量预算由 binder 分配）
     * @return 失败 = 握手/拉取失败；成功但空列表 = 服务器没有工具（合法状态）
     */
    suspend fun load(server: McpServerConfig, maxTools: Int): Result<List<AgentTool>>
}

/**
 * 一次同步的结果。
 *
 * @param failed `owner key -> 失败原因`。失败的服务器**不**记入已装集合，
 *               下一次同步会重试——这与「连上了但没有工具」是两回事。
 */
data class McpSyncResult(
    val installed: Int = 0,
    val revoked: Int = 0,
    val connected: Int = 0,
    val total: Int = 0,
    val failed: Map<String, String> = emptyMap()
) {
    val isNoop: Boolean get() = installed == 0 && revoked == 0 && connected == 0
}

/**
 * 把「配置现状」同步进 [registry]：新增的装上、消失的撤掉、地址变了的重连。
 *
 * 线程模型：[sync] 由互斥锁串行化。启动时一次、用户每次改配置一次，两次可能并发
 * （用户手快），并发跑会让「算计划用的已装集合」过期，进而重复连接或漏撤销。
 */
class McpToolBinder(
    private val registry: ToolRegistry,
    private val loader: McpToolLoader,
    private val maxToolsPerServer: Int = 30,
    private val maxToolsTotal: Int = 60
) {
    /** `owner key -> 地址指纹`：只记「连成功过」的，失败的下次重试。 */
    private val installed = LinkedHashMap<String, String>()

    /** `owner key -> 已装工具名`：用于总量预算与日志。 */
    private val loadedNames = LinkedHashMap<String, List<String>>()

    private val mutex = Mutex()

    suspend fun sync(servers: List<McpServerConfig>): McpSyncResult = mutex.withLock {
        val plan = planMcpSync(installed, servers)
        var revoked = 0
        plan.toRevoke.forEach { owner ->
            revoked += registry.unregisterOwner(owner)
            installed.remove(owner)
            loadedNames.remove(owner)
        }

        var total = loadedNames.values.sumOf { it.size }
        var installedCount = 0
        var connected = 0
        val failed = LinkedHashMap<String, String>()

        for (server in plan.toConnect) {
            if (total >= maxToolsTotal) break
            val owner = mcpOwnerKey(server.id)
            val budget = minOf(maxToolsPerServer, maxToolsTotal - total)
            // 取消必须透传：runCatching 会把 CancellationException 一并吞掉，
            // 那样「用户退出」会被当成「这台服务器挂了」记进 failed。
            val result: Result<List<AgentTool>> = try {
                loader.load(server, budget)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            }
            result.fold(
                onSuccess = { tools ->
                    val sync = registry.registerOwned(owner, tools)
                    installed[owner] = fingerprintOf(server)
                    loadedNames[owner] = tools.map { it.name } - sync.skipped.toSet()
                    total += loadedNames.getValue(owner).size
                    installedCount += sync.installed
                    connected++
                },
                onFailure = { e ->
                    failed[owner] = e.message ?: e::class.simpleName ?: "unknown"
                }
            )
        }

        McpSyncResult(
            installed = installedCount,
            revoked = revoked,
            connected = connected,
            total = total,
            failed = failed
        )
    }

    /** 当前已接入的持有者（诊断/日志用）。 */
    fun activeOwners(): List<String> = installed.keys.toList()
}
