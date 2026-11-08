package com.beichen.polaris.core.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.beichen.polaris.core.AgentLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 重试退避期间的「网络恢复」等待器（可注入的接口，零新增依赖）。
 *
 * **要修的体验（真机第三轮 Bug A2）**：断网（飞行模式）时 LLM 请求瞬间失败，退避
 * 只能干等——用户关掉飞行模式后，进程仍然傻等到满额才重试，体感「不会马上恢复」。
 * 本接口把「等待」这件事抽出来：等待期间若网络恢复可用，立即结束等待、马上重试。
 *
 * 契约：
 *  - [awaitRecovery] 最多挂起 [timeoutMs] 毫秒；网络在等待期间恢复 → **立即返回 true**
 *    （调用方据此立刻发起下一次尝试）；等到满额仍无恢复 → 返回 false（正常退避）。
 *  - 实现必须可取消：协程取消时要马上退出（由 `delay` / `await` 等可取消挂起保证），
 *    且不得吞掉 [kotlinx.coroutines.CancellationException]。
 *  - 监听类资源必须在返回前注销（防泄漏），注册失败（缺权限 / 厂商 ROM 异常 / 拿不到
 *    ConnectivityManager）时**降级为纯定时等待**，绝不崩、绝不把异常抛给重试循环。
 */
fun interface NetworkRecoveryWaiter {

    /**
     * 等待网络恢复或退避满额。
     *
     * @param timeoutMs 本次退避的计划等待毫秒（由 [RetryBackoffPolicy] 给出）。
     * @return true = 被「网络恢复」打断（应立即重试）；false = 等满或降级等待。
     */
    suspend fun awaitRecovery(timeoutMs: Long): Boolean

    companion object {

        /**
         * 纯定时降级实现：**不监听网络**，等满 [timeoutMs] 后返回 false。
         *
         * 用途：① 无 Context / 无权限 / 注册失败时的兜底；② QA harness 在纯 JVM 下
         * 构造 [RealAgentLoop] 时的默认值（core 层因此保持零 Android 依赖可测）。
         */
        val TIMER_ONLY: NetworkRecoveryWaiter = NetworkRecoveryWaiter { timeoutMs ->
            delay(timeoutMs.coerceAtLeast(0L))
            false
        }
    }
}

/**
 * Android 默认实现：用 [ConnectivityManager.registerDefaultNetworkCallback] 监听默认网络。
 *
 * **何时真的监听**：仅当开始等待时**没有**默认网络（`activeNetwork == null`，典型是飞行
 * 模式 / 刚开 WiFi 还没连上）才注册回调——此时「网络恢复」才是本次失败的原因，恢复即重试。
 * 若开始等待时网络本来就通（429 限流、5xx 服务端错误），注册回调会被立刻回调一次而退化成
 * 「不等就重试」，反而会打爆限流；故这种情况**不注册**，老老实实按退避序列等满。
 *
 * **生命周期**：`register` 与 `unregister` 严格配对（try/finally），取消路径同样注销；
 * 任何一步抛异常都转成 WARN 日志 + 降级等待，绝不上抛（重试循环不得因网络监听而失败）。
 */
class ConnectivityNetworkRecoveryWaiter(
    private val appContext: Context
) : NetworkRecoveryWaiter {

    override suspend fun awaitRecovery(timeoutMs: Long): Boolean {
        if (timeoutMs <= 0L) return false
        val cm = connectivityManager()
        if (cm == null) {
            // 拿不到 ConnectivityManager（权限/ROM）：降级为纯定时退避
            delay(timeoutMs)
            return false
        }
        if (hasDefaultNetwork(cm)) {
            // 网络本来就通 —— 失败原因不是断网，按退避序列等满（避免 429/5xx 被打成冲锋）
            AgentLogger.i("NetworkRecovery", "退避开始时网络即通（非断网失败），按序列等满 ${timeoutMs}ms")
            delay(timeoutMs)
            return false
        }

        val recovered = CompletableDeferred<Unit>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                AgentLogger.i("NetworkRecovery", "默认网络恢复可用，立即结束退避并重试")
                recovered.complete(Unit)
            }
        }
        val registered = runCatching {
            cm.registerDefaultNetworkCallback(callback)
            true
        }.onFailure {
            AgentLogger.w(
                "NetworkRecovery",
                "注册网络回调失败，降级为纯定时退避：${it.javaClass.simpleName}"
            )
        }.getOrDefault(false)
        if (!registered) {
            delay(timeoutMs)
            return false
        }

        return try {
            // 网络恢复 → true（立刻重试）；等满 → false（正常退避）；协程取消 → 抛 CancellationException
            withTimeoutOrNull(timeoutMs) {
                recovered.await()
                true
            } ?: false
        } finally {
            // 无论恢复 / 超时 / 取消，监听必须注销（防泄漏）
            runCatching { cm.unregisterNetworkCallback(callback) }
                .onFailure {
                    AgentLogger.w("NetworkRecovery", "注销网络回调失败：${it.javaClass.simpleName}")
                }
        }
    }

    /** 当前是否已有默认网络（true = 网络本来就通）。任何异常都按「无网络」处理，保证降级不崩。 */
    private fun hasDefaultNetwork(cm: ConnectivityManager): Boolean = runCatching {
        cm.activeNetwork != null
    }.getOrDefault(false)

    /** 取 ConnectivityManager；取不到（ROM / 权限异常）返回 null，由调用方降级。 */
    private fun connectivityManager(): ConnectivityManager? = runCatching {
        appContext.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }.onFailure {
        AgentLogger.w("NetworkRecovery", "获取 ConnectivityManager 失败，降级为纯定时退避：${it.javaClass.simpleName}")
    }.getOrNull()
}
