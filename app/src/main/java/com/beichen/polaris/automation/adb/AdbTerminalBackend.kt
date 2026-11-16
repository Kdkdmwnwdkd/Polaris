package com.beichen.polaris.automation.adb

import com.beichen.polaris.core.AppScope
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.terminal.backend.TerminalBackend
import com.beichen.polaris.core.terminal.backend.TerminalBackendProvider
import com.beichen.polaris.core.terminal.backend.TerminalIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException

/**
 * W-53：把「无线调试」接成 `run_shell` 的第三级后端。
 *
 * ## 为什么不复用 [AdbSession.exec]
 *
 * `AdbSession.exec` 是「一条命令一个进程」——`cd /tmp` 之后下一条命令仍在原 cwd，
 * 环境变量也不保留。而 [com.beichen.polaris.core.terminal.TerminalSessionPool] 是
 * **持久 shell + 哨兵** 模型（写命令 → 写 `echo "<哨兵>$?"` → 从输出流等哨兵），
 * 要求 `cd` / 变量跨命令保持。所以这里走 [AdbClient.openInteractiveShell]，
 * 在设备上起一个常驻 `sh`，语义与 [com.beichen.polaris.core.terminal.backend.LocalUserBackend]
 * 完全一致（只是 uid 从 App 变成 2000）。
 *
 * ## 与其它两级的差别
 *
 * | | proot 容器 | 本机 user | **ADB shell** |
 * |---|---|---|---|
 * | 身份 | fake root | App uid | **uid 2000** |
 * | 能改系统设置 | 不能（只改容器内） | 不能 | **能** |
 * | 有包管理器 | 有（apk/pacman） | 无 | 无 |
 * | 首命令开销 | 中（chroot 启动） | 低 | 中（TCP + OPEN） |
 *
 * 所以候选顺序是 `proot → adb → user`：proot 工具最全、ADB 权限最高、user 最快兜底。
 *
 * ## 独占连接
 *
 * 一条 [AdbClient] 只能承载一条交互式 shell（单 socket、单读循环）。本类自己
 * 建连接，**不走** [AdbSession] 的单连接——否则 `ui_tree` 的 dump 会和常驻 shell
 * 抢同一个 socket。代价是每条 ADB 会话多占一条 TCP 连接，adbd 侧上限很高，可接受。
 */
internal class AdbTerminalBackend : TerminalBackend {

    /**
     * R-12：无线调试 shell 是 **uid 2000**（adbd 起的 shell），不是 App 身份。
     *
     * 修前这里写 `User`，于是 uid 2000 的会话在回执里报成「身份 user」，
     * 权限不足的提示也就按 user 档出主意（劝模型去换通道，而它已经是 uid 2000 了）。
     */
    override val identity: TerminalIdentity = TerminalIdentity.Shizuku

    override val backendKey: String = TerminalBackend.KEY_ADB

    private val channel = Channel<String>(Channel.UNLIMITED)

    override val output: Flow<String> = channel.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + AppScope.io)
    private val exit = CompletableDeferred<Int>()

    private var client: AdbClient? = null
    private var stream: AdbClient.ShellStream? = null
    private var readerJob: Job? = null

    @Volatile
    private var closed = false

    /**
     * shell 自己结束了（读到 null 或 Exit 帧）：与 [closed]（我们主动 release）分开记。
     *
     * 不分开的后果是实测过的：`stream` 要等 `release()` 才清，而 readLoop 在 EOF 上只是
     * `break`，于是 shell 已经死了、`isAlive` 仍报 true。池据此复用这条死会话，
     * 下一条命令写进一个关掉的流，一路等到 20 分钟工具预算烧完才报 `send_failed`。
     */
    @Volatile
    private var exited = false

    override val isAlive: Boolean
        get() = !closed && !exited && stream != null

    override suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (stream != null) return@withContext isAlive
        val host = AdbCredentials.host()
        val port = AdbCredentials.connectPort()
        val keyPair = AdbCredentials.keyPair()
        if (host.isNullOrBlank() || port <= 0 || keyPair == null) {
            AgentLogger.w(TAG, "ADB 后端无法启动：无线调试端点未配置")
            return@withContext false
        }

        // 局部变量接住连接：`openInteractiveShell` 失败时对象还没赋给字段，
        // 只调 release() 关不掉它——socket 就漏了。配错端口/配对失效时这条路径会反复走，
        // 漏的是真 TCP 连接（不是壳对象）。
        var pending: AdbClient? = null
        val started = runCatching {
            val c = AdbClient(host, port, keyPair)
            pending = c
            c.connect()
            val s = c.openInteractiveShell()
            client = c
            stream = s
            readerJob = scope.launch { readLoop(s) }
            true
        }.getOrElse { e ->
            AgentLogger.e(TAG, "ADB 交互式 shell 启动失败：${e.javaClass.simpleName}: ${e.message}")
            release()
            runCatching { pending?.close() }
            false
        }

        if (started) AgentLogger.i(TAG, "ADB 后端已启动：$host:$port（uid 2000，无 PTY）")
        started
    }

    /**
     * 读循环：`stdout` / `stderr` 都并入 [channel]（与 [LocalUserBackend] 同口径——
     * 模型只看一路输出更省心）。
     *
     * 超时**不是**结束信号：`read` 每 [READ_TIMEOUT_MS] 返回一次超时，继续轮询；
     * 只有连接关闭（null）或退出帧才收工。把超时当结束会让「命令在跑但暂时没输出」
     * 被误判成 shell 退出，哨兵永远等不到。
     */
    private suspend fun readLoop(s: AdbClient.ShellStream) {
        try {
            while (!closed) {
                val event = try {
                    s.read(READ_TIMEOUT_MS)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (t: Throwable) {
                    AgentLogger.w(TAG, "读循环异常结束：${t.message}")
                    break
                }
                when (event) {
                    null -> break
                    is ShellEvent.Stdout -> channel.send(event.text)
                    is ShellEvent.Stderr -> channel.send(event.text)
                    is ShellEvent.Exit -> {
                        if (!exit.isCompleted) exit.complete(event.code)
                        break
                    }
                }
            }
        } finally {
            // 读循环退出即"这条 shell 不再可用"，无论是因为 EOF、退出帧还是异常。
            // 先于 channel.close() 置位：池可能在另一个线程同时问 isAlive。
            exited = true
            channel.close()
        }
    }

    override suspend fun send(input: String): Boolean = withContext(Dispatchers.IO) {
        val s = stream ?: return@withContext false
        s.writeStdin(input)
    }

    override suspend fun close() {
        release()
        AgentLogger.i(TAG, "ADB 后端已关闭")
    }

    override suspend fun awaitExit(): Int = exit.await()

    private fun release() {
        closed = true
        if (!exit.isCompleted) exit.complete(-1)
        runCatching { stream?.close() }
        // 整条连接一起关：交互式 shell 与它的传输层是绑定的，留着 socket 反而占资源
        runCatching { client?.close() }
        stream = null
        client = null
        runCatching { channel.close() }
        scope.launch { runCatching { readerJob?.join() } }
    }

    companion object {
        private const val TAG = "AdbTerminalBackend"

        /** 读超时：与哨兵轮询节奏同量级，既能及时收输出，又不会空转烧 CPU。 */
        private const val READ_TIMEOUT_MS = 200
    }
}

/**
 * 「无线调试」后端的可用性 + 构造（供 `TerminalBackendFactory` 使用）。
 *
 * `probe()` 只看「是否配对过」（读 SharedPreferences），**不发网络包**——
 * 能力链/工厂会在每次取后端前调用它，探测阶段做 TCP 建连会让 shell 工具白白多等一次超时。
 * 真正的连接失败由 [AdbTerminalBackend.start] 如实返回 false。
 */
internal class AdbBackendProvider : TerminalBackendProvider {

    /** 与 [AdbTerminalBackend.identity] 必须同值：工厂用它筛候选，两边不一致会出现「能造但不该选」。 */
    override val identity: TerminalIdentity = TerminalIdentity.Shizuku

    override val backendKey: String = TerminalBackend.KEY_ADB

    override fun probe(): Boolean = AdbSession.available()

    override fun create(): TerminalBackend? = if (probe()) AdbTerminalBackend() else null
}
