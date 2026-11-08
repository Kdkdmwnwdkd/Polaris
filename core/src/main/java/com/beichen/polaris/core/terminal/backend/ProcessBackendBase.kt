package com.beichen.polaris.core.terminal.backend

import com.beichen.polaris.core.AppScope
import com.beichen.polaris.core.AgentLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.InputStreamReader

/**
 * W-56：以 `java.lang.Process` 为底的终端后端基类——把「起进程 + 读流 + 写 stdin + 回收」
 * 这段从具体来源里剥出来，子类只需要回答**进程从哪来**。
 *
 * ## 为什么抽这一层
 *
 * 本机 user shell（`ProcessBuilder`）与 Shizuku（`newProcess` 反射）在拿到 `Process` 之后，
 * 其余行为**完全一致**：一条常驻 shell、stdout/stderr 合并成文本 chunk、写入即执行、
 * 完成判定交给上层的哨兵协议。W-56 之前这段逻辑只存在于 `LocalUserBackend`；再复制一份
 * 到 Shizuku 后端就会变成「读循环有两份、close 语义有两份」，日后必然分叉。
 *
 * ## 数据模型
 *
 * 一条**常驻** shell 进程；[send] 写入一行（或多行）命令，[output] 持续吐出合并输出。
 * 退出码由上层哨兵 `echo "<哨兵>$?"` 决定，本类不解析命令边界。
 *
 * ## 线程契约
 *
 * [output] 由单一读协程投递（`Channel.UNLIMITED`，先产出后订阅也不丢）；
 * [send] 只写 stdin、不等待命令结束。
 *
 * ## 已知取舍
 *
 * 无 PTY：`Ctrl-C` 不是真中断、依赖终端能力的程序（分页器/颜色）行为受限——两条通道
 * 都一样，已写进 shell 工具描述。
 */
abstract class ProcessBackendBase(
    override val identity: TerminalIdentity = TerminalIdentity.User
) : TerminalBackend {

    private val channel = Channel<String>(Channel.UNLIMITED)

    override val output: Flow<String> = channel.receiveAsFlow()

    private var process: Process? = null
    private val exit = CompletableDeferred<Int>()
    private val scope = CoroutineScope(SupervisorJob() + AppScope.io)
    private var readerJob: Job? = null
    private var waiterJob: Job? = null

    /**
     * 构造一条新的 shell 进程。
     *
     * 实现方约定：
     * - 返回的进程 stdout 应包含 stderr（`redirectErrorStream` 或等价），本类按单一流读；
     * - 失败返回 null（本类按启动失败处理，不抛）；
     * - 不得在此处做长时间阻塞（启动超时由调用方的会话开辟负责）。
     */
    protected abstract fun openProcess(): Process?

    /** 启动成功/失败时打什么日志（默认用 backendKey 拼，子类可覆盖）。 */
    protected open val startLog: String get() = "后端已启动：$backendKey"

    protected open val startFailLog: String get() = "后端启动失败：$backendKey"

    /**
     * 进程是否还活着。
     *
     * **必须包 runCatching**：Shizuku 的远端进程代理（`ShizukuRemoteProcess`）在"进程还在跑"
     * 时，内部的 `exitValue()` 抛的是 `IllegalArgumentException: process hasn't exited`，
     * 而标准 `java.lang.Process` 抛 `IllegalThreadStateException`——它的 `isAlive()` 只捕获后者，
     * 于是前一种异常会直接穿出来。裸调本属性会在**正常运行时**崩掉进程（真机已复现：
     * CrashLogger 里 `process hasn't exited` × 2）。
     *
     * 拿不到状态时返回 **true**（视为存活）：异常在这个上下文里恰好等于"还在跑"；
     * 真死了的话 `send` 会失败并由池如实报错，不会静默卡住。
     */
    override val isAlive: Boolean
        get() = runCatching { process?.isAlive == true }.getOrDefault(true)

    override suspend fun start(): Boolean {
        if (process != null) return isAlive
        // openProcess 单独取出来：放在 runCatching 里用 `?: return false` 会**跳过整个日志段**
        // （非局部返回直接跳出 start），失败时一条日志都没有，排查时只能靠猜。
        val p = openProcess() ?: run {
            AgentLogger.w(TAG, "$startFailLog（openProcess 返回 null）")
            return false
        }
        val started = runCatching {
            process = p

            readerJob = scope.launch {
                val reader = InputStreamReader(p.inputStream, Charsets.UTF_8)
                val buf = CharArray(READ_BUFFER_CHARS)
                try {
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (n > 0) channel.send(String(buf, 0, n))
                    }
                } catch (t: Throwable) {
                    // 取消不是异常：readerJob 被 cancel 时也会走到这里，别把取消记成"读取异常"
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    AgentLogger.w(TAG, "读取 $backendKey 输出异常：${t.message}")
                } finally {
                    channel.close()
                    // 输出流关闭 ≈ 进程结束。这是 `exitValue()` 不可用时**唯一**可靠的退出信号
                    // （Shizuku 代理就属于这种），少了它，会话永远不会被判定为已退出。
                    if (!exit.isCompleted) exit.complete(-1)
                }
            }
            waiterJob = scope.launch {
                // 用轮询而不是 waitFor(timeout)：Shizuku 的远端 Process 代理对定时 waitFor
                // 会立刻抛异常（W-36 真机日志：echo hello 6ms 报 timeout）。
                // 拿不到退出码时返回 null —— **不能**用 -1 顶替，那会被池当成「进程已退出」，
                // 把仍然可用的会话判死（见 awaitExitCode 的注释）。
                val code = runCatching { awaitExitCode(p) }.getOrNull()
                if (code != null && !exit.isCompleted) exit.complete(code)
            }
            true
        }.getOrElse { e ->
            AgentLogger.e(TAG, "$startFailLog（${e.javaClass.simpleName}: ${e.message}）")
            false
        }
        // 不记 pid：Android 的 java.lang.Process 没有实例 pid()（只有 Process.myPid()），
        // 反射取值不值得——日志里有 backendKey 已够定位。
        if (started) AgentLogger.i(TAG, startLog)
        return started
    }

    override suspend fun send(input: String): Boolean {
        val p = process ?: return false
        if (!isAlive) return false
        return runCatching {
            p.outputStream.write(input.toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
            true
        }.getOrElse { e ->
            AgentLogger.w(TAG, "写入 stdin 失败（$backendKey）：${e.message}")
            false
        }
    }

    override suspend fun close() {
        val p = process
        if (p != null) {
            // 先请求终止，再**主动关流**。只 destroy 不够：Shizuku 的远端进程代理对 destroy
            // 不一定及时生效（W-36 已知它的 waitFor 语义不靠谱），reader 会一直阻塞在
            // `reader.read()` 上——那是 Dispatchers.IO 的一条线程，泄漏在这里就是真占资源。
            // 关流让阻塞中的 read 立刻抛异常 → finally 里 channel.close() → reader 收工。
            runCatching { p.destroy() }
            runCatching { p.outputStream.close() }
            runCatching { p.inputStream.close() }
            runCatching { p.errorStream?.close() }
        }
        if (!exit.isCompleted) exit.complete(-1)
        runCatching { channel.close() }
        scope.launch {
            runCatching { readerJob?.join() }
            runCatching { waiterJob?.join() }
        }
        AgentLogger.i(TAG, "后端已关闭：$backendKey")
    }

    override suspend fun awaitExit(): Int = exit.await()

    /**
     * 轮询退出码。
     *
     * @return 真实退出码；**null = 这条通道拿不到退出码**（此时由 reader 的 EOF 兜底）
     *
     * ## 为什么不能笼统地「异常就当失败」
     *
     * `exitValue()` 在"进程还在跑"时必然抛异常，但抛的类型因实现而异：
     *
     * | 实现 | 抛什么 |
     * |---|---|
     * | 标准 `java.lang.Process` | `IllegalThreadStateException` |
     * | Shizuku 远端代理 | `IllegalArgumentException: process hasn't exited`（经 Parcel 回来） |
     *
     * 老代码 `ShizukuShell.exec` 只把前者当"还在跑"、其余当"通道异常"直接放弃——
     * 对一次性执行没问题，对**持久会话**就不行：放弃等于会话立刻判死。
     * 所以这里把两种都当"还在跑"，只有真正拿到退出码或确认拿不到时才收工。
     *
     * 另外原来的 `if (!p.isAlive && ...)` 是**裸调** `isAlive`——Shizuku 代理在那里会抛，
     * 而本函数跑在 `scope.launch` 里，异常没人接，直接崩进程（真机复现过 2 次）。
     */
    private suspend fun awaitExitCode(p: Process): Int? {
        while (true) {
            val r = runCatching { p.exitValue() }
            r.getOrNull()?.let { return it }
            val e = r.exceptionOrNull()
            val stillRunning = e is IllegalThreadStateException ||
                (e is IllegalArgumentException && e.message?.contains("hasn't exited") == true)
            if (!stillRunning) {
                AgentLogger.w(
                    TAG,
                    "取退出码不可用（${e?.javaClass?.simpleName}: ${e?.message}），改由输出流关闭判定退出"
                )
                return null
            }
            if (exit.isCompleted) return null // reader 已收工（EOF），不必再轮询
            kotlinx.coroutines.delay(EXIT_POLL_MS)
        }
    }

    companion object {
        private const val TAG = "ProcessBackend"

        /** 读缓冲（字符）；8192 与 pty 读循环同量级。 */
        private const val READ_BUFFER_CHARS = 8192

        /** 退出码轮询间隔：进程结束的感知延迟上限。 */
        private const val EXIT_POLL_MS = 200L
    }
}
