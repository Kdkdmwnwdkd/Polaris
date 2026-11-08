package com.beichen.polaris.terminal

import com.beichen.polaris.core.AppScope
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.ProcessEnv
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * 终端 pty 会话：一条 shell 的完整生命周期（净室重写）。
 *
 * 职责边界（与 Termux 同构的四层切分，但全部自写）：
 * - 本类持有 JNI 句柄，管「读循环 → 仿真器 → 回调」与「输入 → pty」两条数据流；
 * - 解析与屏幕模型在 [TerminalEmulator]（纯 Kotlin，零 Android 依赖，可单测）；
 * - native 原语在 [TerminalJni]。
 *
 * 线程模型：读循环跑在 [Dispatchers.IO] 协程（阻塞 read），写操作由调用方线程直接
 * 下发（writeBytes 内部持锁，与销毁路径互斥）。[onUpdate] / [onExit] 保证切回主线程。
 *
 * @param shellPath 要执行的 shell 绝对路径（默认系统 mksh）
 * @param cwd 会话工作目录（终端默认 cd 到这里）
 * @param initialCols / initialRows 初始窗口尺寸（首帧后由 UI 按容器尺寸 [resize] 校正）
 * @param filesDir App filesDir（PATH / TMPDIR 的环境变量基准）
 * @param packageName 包名（注入环境变量，供容器脚本识别宿主）
 * @param homeDir HOME 目录（默认 cwd 的上级目录，与 Termux 的 filesDir/home 语义一致）
 * @param argv 完整 argv；**argv[0] 必须是可执行文件自身**（容器场景由
 *   [com.beichen.polaris.core.container.ProotRunner.buildInvocation] 直接给出，选项不会错位）
 * @param env 额外环境变量（`KEY=VALUE`）。**非空时与宿主 `System.getenv()` 求并集**
 *   （同名键以本参数为准）——forkpty 后子进程不继承 JVM 进程环境，只喂自定义 env 会让
 *   proot 因缺 `ANDROID_ROOT` / `LD_LIBRARY_PATH` 瞬间退出且日志无报错。传 null 时用 [buildEnv]
 */
class TerminalSession(
    private val shellPath: String = "/system/bin/sh",
    private val cwd: File,
    private val initialCols: Int = 80,
    private val initialRows: Int = 24,
    private val filesDir: File,
    private val packageName: String,
    private val argv: List<String> = listOf(shellPath),
    private val env: List<String>? = null,
    private val homeDir: File = cwd.parentFile ?: cwd
) {

    /** 本会话的屏幕模型（读线程写、UI 快照读；内部已互斥）。 */
    val emulator = TerminalEmulator(initialCols, initialRows)

    /** 屏幕有新输出（主线程回调；UI 在此重新快照渲染）。 */
    var onUpdate: (() -> Unit)? = null

    /**
     * 原始输出分片回调（**读线程**回调，非主线程）。
     *
     * 存在理由（T07）：终端后端接口 [com.beichen.polaris.core.terminal.backend.TerminalBackend]
     * 的 `output: Flow` 需要**流式原始文本**，而 [emulator] 只提供「屏幕快照」语义（丢失流式边界）。
     * 这里在读循环把每个 pty chunk 原样透出，供 [com.beichen.polaris.core.terminal.backend.ProotBackend]
     * 桥接成输出流。**默认 null，不影响既有行为**；注意分片可能切断多字节 UTF-8 序列（仿真器内部
     * 另有自己的增量解码，本回调仅供上层按哨兵做完成判定，不做字符边界保证）。
     */
    var onRawOutput: ((String) -> Unit)? = null

    /** 会话退出（主线程回调）；参数为退出码（信号杀死 = 128 + 信号，未获取 = -1）。 */
    var onExit: ((Int) -> Unit)? = null

    private var handle: Long = 0L
    private val scope = CoroutineScope(SupervisorJob() + AppScope.io)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var started = false

    @Volatile
    private var destroyed = false

    /** 会话是否仍在运行（已启动且未销毁、未退出）。 */
    val isAlive: Boolean
        get() = started && !destroyed && handle != 0L

    /**
     * 启动会话：构造环境变量 → forkpty 执行 shell → 起读循环。
     *
     * @throws IOException JNI 库不可用或 forkpty 失败（UI 显示降级文案，不崩）
     */
    fun start() {
        if (started || destroyed) return
        if (!TerminalJni.ensureLoaded()) {
            throw IOException("libagentkit-termux.so 不可用（当前 ABI 未编入 pty 模块）")
        }
        // 外部提供 env（容器场景）时**不能二选一**：forkpty 后子进程不继承 JVM 的
        // System.getenv()，只喂自定义 env 会让 proot 因缺 ANDROID_ROOT / LD_LIBRARY_PATH
        // 瞬间退出且日志无任何报错。这里取并集，同名键以外部 env 为准（外部覆盖宿主）。
        // 未提供 env 的普通终端会话保持 buildEnv() 原行为不变。
        val envArr = if (env != null) {
            val merged = ProcessEnv.mergeHostEnv(env)
            AgentLogger.d(
                TAG,
                "env 合并：宿主 ${ProcessEnv.hostEnvSize()} 项 + 自定义 ${env.size} 项 → ${merged.size} 项"
            )
            merged
        } else {
            buildEnv()
        }
        handle = if (argv.size == 1) {
            TerminalJni.createSubprocess(argv[0], envArr, cwd.absolutePath, initialRows, initialCols)
        } else {
            TerminalJni.createSubprocessArgv(
                argv.toTypedArray(), envArr, cwd.absolutePath, initialRows, initialCols
            )
        }
        if (handle == 0L) {
            throw IOException("forkpty 失败：无法启动 ${argv[0]}（cwd=${cwd.absolutePath}）")
        }
        started = true
        AgentLogger.i(
            TAG,
            "终端会话已启动：$shellPath cwd=${cwd.absolutePath} ${initialCols}x$initialRows"
        )
        scope.launch { readLoop() }
    }

    /**
     * 环境变量构造（Java 侧职责，C 层只原样转给 execve）：
     * HOME / PATH（含 filesDir/usr/bin，为将来的容器工具链预留）/
     * LANG=C.UTF-8 / TERM=xterm-256color / TMPDIR / PWD。
     */
    private fun buildEnv(): Array<String> {
        val tmpDir = File(filesDir, "tmp").apply { runCatching { mkdirs() } }
        return arrayOf(
            "HOME=${homeDir.absolutePath}",
            "PATH=${filesDir.absolutePath}/usr/bin:/system/bin:/system/xbin",
            "LANG=C.UTF-8",
            "TERM=xterm-256color",
            "TMPDIR=${tmpDir.absolutePath}",
            "PWD=${cwd.absolutePath}",
            "ANDROID_PACKAGE=$packageName"
        )
    }

    /** 读循环：pty 输出 → 仿真器 → 主线程刷新回调；EOF / 错误时收尾并回调退出码。 */
    private suspend fun readLoop() {
        val buf = ByteArray(8192)
        while (scope.isActive && !destroyed) {
            val n = try {
                TerminalJni.readBytes(handle, buf)
            } catch (t: Throwable) {
                AgentLogger.w(TAG, "readBytes 异常：${t.message}")
                -1
            }
            if (n > 0) {
                emulator.append(buf, n)
                // T07：原始分片透出给终端后端（默认 null，零行为变化）
                onRawOutput?.let { cb -> runCatching { cb(String(buf, 0, n, Charsets.UTF_8)) } }
                postUpdate()
                continue
            }
            // 0 = EOF（shell 退出并关闭了 slave）；-1 = 已销毁或子进程退出后的 EIO
            break
        }
        val code = runCatching { TerminalJni.waitFor(handle, 3000) }.getOrDefault(-1)
        // 主动销毁（离开页面 / 旋转窗口期结束）会 kill 进程组，waitFor 拿到的必然是
        // 137（128+SIGKILL）。这不是崩溃——把它当退出码上报，UI 会显示「进程被杀死」
        // 这类吓人的错误，日志里也会出现一条看起来像故障的记录。真机排查时这条误报
        // 曾与真正的 SIGKILL（LMK 杀进程）混在一起，得靠时间戳猜。所以这里分开记：
        // 主动销毁只记一行 INFO，不回调 onExit（页面已离开，没人需要这个码）。
        if (destroyed) {
            AgentLogger.i(TAG, "终端会话已主动结束（code=$code，kill 进程组的预期结果）")
        } else {
            AgentLogger.i(TAG, "终端会话结束，退出码=$code")
            postExit(code)
        }
    }

    /**
     * 向 shell 写文本（UTF-8 编码后整体下发）。
     * 会话已退出时静默丢弃——输入栏在退出态本就不该有输入。
     */
    fun write(text: String) {
        if (destroyed || handle == 0L || text.isEmpty()) return
        val data = text.toByteArray(Charsets.UTF_8)
        val n = TerminalJni.writeBytes(handle, data)
        if (n < 0) AgentLogger.w(TAG, "writeBytes 失败（会话可能已退出）")
    }

    /**
     * 发送控制字符：如 [sendCtrl]('C') → 0x03（Ctrl+C，中断前台进程）；
     * 'D' → EOF（退出登录 shell）、'Z' → 挂起。取字符编码低 5 位。
     */
    fun sendCtrl(c: Char) {
        val code = (c.code and 0x1F).toChar()
        write(code.toString())
    }

    /** 改变窗口尺寸：同步 Emulator 缓冲与 pty winsize（触发 shell 侧 SIGWINCH 重排）。 */
    fun resize(cols: Int, rows: Int) {
        if (destroyed || handle == 0L) return
        emulator.resize(cols, rows)
        TerminalJni.setPtyWindowSize(handle, rows, cols)
    }

    /**
     * 销毁会话（幂等）：杀进程组（含 shell 的所有子进程）+ 回收僵尸 + 取消读协程。
     * App 退后台 / 退出终端页必须调用——pty 读线程是阻塞 IO，不回收会白耗电。
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        if (handle != 0L) {
            runCatching { TerminalJni.killProcessGroup(handle) }
                .onFailure { AgentLogger.w(TAG, "killProcessGroup 失败：${it.message}") }
        }
        scope.cancel()
        AgentLogger.i(TAG, "终端会话已销毁")
    }

    private fun postUpdate() {
        main.post { if (!destroyed) onUpdate?.invoke() }
    }

    private fun postExit(code: Int) {
        main.post { onExit?.invoke(code) }
    }

    companion object {
        private const val TAG = "TerminalSession"
    }
}
