package com.beichen.polaris.runtime.lease

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.llm.local.LocalLlamaEngine

/**
 * 本地模型租约（R3）。
 *
 * ## 与原 [com.beichen.polaris.runtime.LocalLlmRuntime] 的关系
 * 原实现已有「在途计数 + 空闲释放」两件事，但**没有租约语义**：
 * 计数只在装饰器里手工加减（`beginUse` / `endUse`），调用方漏一次减法就永久泄漏。
 *
 * 本类把同样的行为包进 [RuntimeLease] 契约：
 * - [acquire] → 计数 +1、刷新心跳；
 * - [LeaseHandle.close] → 计数 -1、刷新心跳；
 * - 引用数归零且空闲超阈值 → 卸载模型。
 *
 * **行为完全不变**，只是把「谁在减这个数」从手工约定变成 `use { }` 保证。
 *
 * ## 为什么本地模型够格进租约
 * 3B Q4 模型的权重 + KV cache 通常在 1–2GB，符合「占内存 > 50MB」的准入判据，
 * 且空闲确实可回收（下一次推理会自动重载）。
 */
class LocalLlmLease(
    override val id: String = ID,
) : RuntimeLease {

    companion object {
        /**
         * 租约 id。
         *
         * 公开的理由：诊断面板要按它取「未归还句柄数」（[LeaseRegistry.outstandingCount]），
         * 而那个查询只需要一个字符串——没必要为此构造一个租约实例。
         * 但也**只能有一处定义**：写错一个字，面板会永远显示 0，看起来像「没有泄漏」，
         * 比不显示更误导。
         */
        const val ID = "local-llm"

        private const val TAG = "LocalLlmLease"
    }

    /**
     * R6：空闲回收阈值（毫秒）。null = 跟随 [LeaseRegistry.idleTimeoutMs] 全局默认。
     *
     * 做成可变而非构造参数，是因为它跟着**用户设置**走：用户在设置页调空闲时长
     * （或直接关掉空闲释放）时，要能立刻生效，不必重启进程。
     *
     * 语义与全局一致：负数 = 禁用本租约的空闲回收。
     */
    @Volatile
    override var idleTimeoutMs: Long? = null

    /** 活跃引用数（= 在途推理数）。 */
    private val active = java.util.concurrent.atomic.AtomicInteger(0)

    override val activeCount: Int get() = active.get()

    /**
     * 释放安全性：模型已加载**且**没有在途推理。
     *
     * 注意「已加载」这一项：模型没加载时本来就没什么可释放的，
     * 此时返回 false 让回收器跳过（而不是调一次无意义的 unload）。
     */
    override val isSafeToRelease: Boolean
        get() = active.get() == 0 && LocalLlamaEngine.isLoaded

    override suspend fun acquire(): LeaseHandle {
        active.incrementAndGet()
        return LeaseRegistry.acquire(this) { release() }
    }

    /**
     * 空闲释放：卸载模型。
     *
     * 失败只记日志不抛——回收失败不该影响后续使用（模型仍加载着，
     * 下一次回收还会再试）。
     */
    fun releaseIdle(reason: String) {
        if (!LocalLlamaEngine.isLoaded) return
        val model = LocalLlamaEngine.loadedModelName ?: "未知模型"
        runCatching { LocalLlamaEngine.unload() }
            .onSuccess {
                AgentLogger.i(TAG, "已释放本地模型 $model（$reason）。下次对话会自动重载，首字会慢十几秒")
            }
            .onFailure { AgentLogger.w(TAG, "本地模型释放失败（$reason）", it) }
    }

    /** 供 [com.beichen.polaris.runtime.LocalLlmRuntime] 复用：归还引用。 */
    fun release() {
        active.updateAndGet { (it - 1).coerceAtLeast(0) }
        LeaseRegistry.touch(id)
    }
}
