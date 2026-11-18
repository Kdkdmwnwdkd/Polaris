package com.beichen.polaris.mcp

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.beichen.polaris.core.mcp.server.McpAudit
import com.beichen.polaris.core.mcp.server.McpClientRecord
import com.beichen.polaris.core.mcp.server.McpHostConfig
import com.beichen.polaris.core.mcp.server.McpPairing
import com.beichen.polaris.core.mcp.server.McpServerHost
import com.beichen.polaris.core.mcp.server.McpToolSource
import com.beichen.polaris.core.mcp.server.McpTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * MCP 宿主的进程级持有者。
 *
 * ## 为什么不放在 Service 字段里
 *
 * 前台服务会被系统杀掉再重建（`START_STICKY`）。如果把「用户开的这个监听」放在
 * 服务字段里，重建后端口会变、已配对客户端的连接会断，而用户看不到任何解释。
 * 放进 object 后，Service 只是它的「生命维持装置」，重建时接着用同一份配置。
 *
 * ## 为什么 reset 只断连接、不删文件
 *
 * 清单的全量重置是诊断/harness 的路径，语义是「回到冷启动状态」，
 * 不是「抹掉用户数据」。token 文件和审计日志是用户资产，只能由用户显式删除。
 */
object McpHostRuntime {

    data class Status(
        val running: Boolean = false,
        val port: Int = -1,
        val tier: McpTier = McpTier.READONLY,
        val clients: List<McpClientRecord> = emptyList(),
        val visibleTools: Int = 0,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val lock = Any()
    private var host: McpServerHost? = null
    private var pairingRef: McpPairing? = null
    private var auditRef: McpAudit? = null

    fun pairing(context: Context): McpPairing = synchronized(lock) {
        pairingRef ?: McpPairing(File(dir(context), "clients.json")).also { pairingRef = it }
    }

    fun audit(context: Context): McpAudit = synchronized(lock) {
        auditRef ?: McpAudit(File(dir(context), "audit")).also { auditRef = it }
    }

    /** 启动监听。端口由系统分配，返回实际端口；重复调用幂等。 */
    fun start(context: Context, tier: McpTier = McpTier.READONLY): Result<Int> = synchronized(lock) {
        host?.let { existing ->
            if (existing.isRunning()) return Result.success(existing.port)
        }
        val app = context.applicationContext
        val p = pairing(app)
        val a = audit(app)
        val registry = McpToolset.build(app)
        val created = McpServerHost(
            tools = RegistryToolSource(registry),
            pairing = p,
            audit = a,
            config = McpHostConfig(),
            // 「在场」= 亮屏 **且** 已解锁。只判 isInteractive 不够：
            // 亮着屏停在锁屏界面时，外部客户端照样能点屏幕、开应用，
            // 而用户在锁屏上什么也看不到——审批弹不出来，动作已经发生。
            screenOn = {
                val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val km = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val awake = pm?.isInteractive ?: true
                val unlocked = km?.isKeyguardLocked == false
                awake && unlocked
            },
        )
        created.start().fold(
            onSuccess = { port ->
                host = created
                _status.value = Status(
                    running = true,
                    port = port,
                    tier = tier,
                    clients = p.list(),
                    visibleTools = created.visibleToolCount(tier),
                )
                Result.success(port)
            },
            onFailure = { e ->
                host = null
                _status.value = Status(error = e.message ?: "启动失败")
                Result.failure(e)
            },
        )
    }

    fun stop() = synchronized(lock) {
        host?.stop()
        host = null
        _status.value = Status()
        Unit
    }

    /** 新建一个客户端并返回明文 token（只此一次）。 */
    fun pair(context: Context, name: String, tier: McpTier): McpPairing.Paired? = runCatching {
        val p = pairing(context)
        val paired = p.pair(name, tier)
        _status.value = _status.value.copy(clients = p.list())
        paired
    }.getOrNull()

    fun revoke(context: Context, id: String) {
        val p = pairing(context)
        p.revoke(id)
        _status.value = _status.value.copy(clients = p.list())
    }

    /** AppGraph 的 reset：断连接、丢引用，不碰文件。 */
    fun reset() = synchronized(lock) {
        host?.stop()
        host = null
        pairingRef = null
        auditRef = null
        _status.value = Status()
        Unit
    }

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, "mcp").apply { mkdirs() }
}
