package com.beichen.polaris.core.lifecycle

import java.lang.ref.WeakReference

/**
 * 进程级「切到后台」广播（R-19）。
 *
 * ## 为什么需要
 *
 * 会话落盘是**去抖**的（400ms 窗口，见 `ConversationStore.schedulePersist`）：
 * 变更只置脏标记，窗口内合并成一次写。这解决了流式输出时「每个 token 写一次全量文件」
 * 的卡顿，代价是窗口内被杀进程会丢最后一次变更。
 *
 * 正常退出（用户按 Home、切到别的 App）恰恰是**最可能被杀**的时刻：系统给后台进程
 * 的存活窗口很短。而这个时刻的脏数据是确定的——只要在切后台时立刻写一次，那 400ms
 * 的窗口就不存在了。
 *
 * `ConversationStore.flush()` 从 R 系列起就写好了（注释也写着「App 切后台时调用」），
 * 但**全仓零调用**：R-19 之前没有任何地方把「切后台」这件事告诉存储层。于是「窗口内
 * 被杀会丢一次变更」从一句理论风险，变成了每天都会发生的实际行为。
 *
 * ## 为什么不用 ProcessLifecycleOwner
 *
 * AndroidX 的 `lifecycle-process` 能直接给出「整个进程进入后台」，但它是一条新依赖，
 * 而 `AgentKitApplication` 已经用 `ActivityLifecycleCallbacks` 数前台 Activity（功能五
 * 的通知策略在用），计数归零就是「进程进后台」。复用同一个信号，不引第二个真相来源。
 *
 * ## 为什么 owner 是弱引用、action 不收 owner
 *
 * 注册方（存储层）生命周期比进程短：ViewModel 被回收后，它注册的 hook 必须自动失效，
 * 否则切后台会去 flush 一个已经没人用的实例——写盘不多，但引用泄漏是真的。
 * 若 action 闭包捕获 owner（`{ store.flush() }`），弱引用就白做了：hooks 表里的闭包
 * 强引用着 store。所以 action 的签名是 `(Any) -> Unit`，触发时把 owner 传进去，
 * 闭包本身不持有它。
 */
object AppBackgroundHooks {

    private class Entry(
        val owner: WeakReference<Any>,
        val action: (Any) -> Unit,
        /** 用于日志与排障：谁注册的（类名），不参与判定。 */
        val label: String
    )

    private val lock = Any()
    private val entries = ArrayList<Entry>()

    /**
     * 注册一个「切后台时要跑的动作」。
     *
     * 同一个 owner 重复注册会**替换**旧动作（幂等）：ViewModel 重建时不该留下两份。
     *
     * @param owner 注册方。弱引用持有——被回收后本条自动失效。
     * @param action 触发时执行，参数是仍存活的 owner（已保证非 null）。
     */
    fun register(owner: Any, action: (Any) -> Unit) {
        synchronized(lock) {
            entries.removeAll { it.owner.get() === owner || it.owner.get() == null }
            entries.add(Entry(WeakReference(owner), action, owner::class.java.simpleName))
        }
    }

    /** 注销（owner 主动收尾时调用；被回收的情况由 [fireAll] 惰性清理）。 */
    fun unregister(owner: Any) {
        synchronized(lock) { entries.removeAll { it.owner.get() === owner } }
    }

    /**
     * 广播「进后台」，同步执行所有仍存活的 hook。
     *
     * - 单个 hook 抛异常**不影响**其余：这里做的是收尾，一个存储写失败不该连带
     *   把别的收尾也跳过去。
     * - 返回**成功执行**的条数，供 harness 与日志核对。
     */
    fun fireAll(reason: String): Int {
        val snapshot: List<Entry>
        synchronized(lock) {
            entries.removeAll { it.owner.get() == null }
            snapshot = entries.toList()
        }
        var ok = 0
        for (e in snapshot) {
            val owner = e.owner.get() ?: continue
            try {
                e.action(owner)
                ok++
            } catch (t: Throwable) {
                // 刻意走 System.err 而不是 AgentLogger：这个 object 要能被 JVM harness
                // 单独编译（AgentLogger 会拖出 ConfigSanitizer → 供应商目录一大串）。
                System.err.println("[lifecycle] 后台收尾失败（${e.label}，$reason）：${t.message}")
            }
        }
        return ok
    }

    /** 当前仍存活的 hook 数（诊断 / harness 用）。 */
    fun activeCount(): Int = synchronized(lock) {
        entries.removeAll { it.owner.get() == null }
        entries.size
    }

    /** 清空（仅测试用）：harness 在同一进程里跑多个用例，残留会互相干扰。 */
    fun clearForTest() {
        synchronized(lock) { entries.clear() }
    }
}
