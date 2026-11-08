package com.beichen.polaris.core

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * 应用级协程约定（R-14）。
 *
 * ## 它不做什么（比它做什么更重要）
 *
 * **不接管生命周期。** 每个持有者（`TerminalSession`、`JobRunner`、`ProcessBackendBase`…）
 * 仍然 `new` 自己的 `SupervisorJob()`，`close()` 时 `scope.cancel()` 只影响自己。
 *
 * 这一点必须写清楚，因为「把所有 scope 换成一个全局 scope」是个**看起来对、实际会炸**的做法：
 * `TerminalSessionPool.closeAll()` 里就有 `scope.cancel()`，换成全局 scope 等于
 * 「关掉一个终端池 → 取消整个 App 的所有协程」。所以这里提供的是 **[io] 上下文**（调度器 +
 * 命名 + 异常出口），不是 Job。
 *
 * ## 它做什么
 *
 * 原来 6 个类各自写 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，缺两样东西：
 *
 * 1. **未捕获异常没有落点。** 协程里抛出的异常会走到线程默认处理器，只打一行
 *    `Exception in thread "DefaultDispatcher-worker-3"`——没有类名、没有上下文、
 *    不进 `AgentLogger`。真机上表现为「某个功能静默失效，日志里什么都没有」。
 * 2. **线程名不可读。** 线程池里的线程都叫 `DefaultDispatcher-worker-N`，
 *    抓 trace 时无法判断是哪个子系统在跑。
 *
 * 加上 [io] 之后这两条都解决了，而**行为一行没变**——因为它只换了上下文，没换生命周期。
 *
 * ## [app] 的用途
 *
 * 只给「必须活到进程结束」的动作：终端池异步释放、空闲会话回收。这两处原来是
 * 裸 `CoroutineScope(Dispatchers.IO).launch`，连引用都没有——问题不是它们会泄漏
 * （进程结束时自然会停），而是**没有任何地方能等到它们结束**，也没法在测试里同步。
 */
object AppScope {

    /**
     * 未捕获协程异常的落点。
     *
     * 用 `Log.e` 而不是 `AgentLogger`，是为了避免 `core.AppScope → core.AgentLogger`
     * 这种「基础件反过来依赖业务件」的环。日志文件那一路由 `AgentLogger` 自己兜，
     * 这里只保证**异常不会静默消失**。
     */
    private val handler = CoroutineExceptionHandler { _, e ->
        runCatching { Log.e("AppScope", "未捕获的协程异常（已兜住，进程继续）", e) }
    }

    /**
     * IO 调度上下文：**不含 Job**，由调用方拼上自己的 `SupervisorJob()`。
     *
     * 用法：`CoroutineScope(SupervisorJob() + AppScope.io)`。
     */
    val io: CoroutineContext =
        Dispatchers.IO + CoroutineName("agentkit-io") + handler

    /** 进程级作用域：给「不受任何实例取消影响」的动作（异步释放、空闲回收）。 */
    val app: CoroutineScope = CoroutineScope(SupervisorJob() + io)
}
