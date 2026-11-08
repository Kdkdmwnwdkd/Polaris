package com.beichen.polaris.terminal

import com.beichen.polaris.core.AgentLogger

/**
 * 终端 pty 会话 JNI 桥的 Kotlin 侧声明。
 *
 * 对应 app/src/main/cpp/termux-jni/termux-jni.cpp 的六个 native 函数
 * （净室重写：仅 forkpty / read / write / ioctl / kill 薄封装，无 GPL 代码）。
 * 协议细节（环境变量构造、读循环编排、退出码语义）都在 [TerminalSession]，
 * 这里只做「句柄 + 六个原语」。
 *
 * 库名与 CMakeLists.txt 的 add_library(agentkit-termux SHARED ...) 一致。
 * 只在真机 arm64-v8a 上存在——模块缺失时 [ensureLoaded] 返回 false，UI 走降级文案。
 */
object TerminalJni {

    private const val TAG = "TerminalJni"

    @Volatile
    private var loadAttempted = false

    @Volatile
    var available: Boolean = false
        private set

    /**
     * 刻意的 no-op（登记在 [com.beichen.polaris.AppGraph] 里，`resettable = false`）。
     *
     * 与 `LlamaJni` 同理：`libagentkit-termux.so` 一旦载入就无法卸载，
     * 失败（ABI 未编入）重试也还是失败。[loadAttempted] 是「只试一次」的闸门，
     * 清掉它只会让下一次 ensureLoaded 再走一遍必然失败的加载。
     */
    fun reset() {
        // 加载状态是不可逆的事实，不清。
    }

    /** 首次调用时加载 .so；失败（如在 x86 模拟器上）只记一次日志，后续直接返回 false。 */
    fun ensureLoaded(): Boolean {
        if (available) return true
        if (loadAttempted) return false
        loadAttempted = true
        available = try {
            System.loadLibrary("agentkit-termux")
            true
        } catch (e: UnsatisfiedLinkError) {
            AgentLogger.e(TAG, "libagentkit-termux.so 不可用（当前 ABI 未编入？）：${e.message}")
            false
        }
        return available
    }

    /**
     * 创建 pty 子进程。
     *
     * @param cmd  可执行文件绝对路径（如 /system/bin/sh）
     * @param env  环境变量数组（K=V 形式，execve 原样使用）
     * @param cwd  子进程工作目录
     * @param rows 初始行数 / @param cols 初始列数
     * @return 会话句柄；0 = 失败（[TerminalSession.start] 包装为 IOException）
     */
    external fun createSubprocess(
        cmd: String,
        env: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int
    ): Long

    /**
     * 创建 pty 子进程并执行 [argv]（多参数命令，容器 proot 用）。
     *
     * @param argv 命令 + 参数数组（argv[0] 为可执行文件绝对路径）
     * @param env  环境变量数组（K=V 形式）
     * @param cwd  子进程工作目录
     * @param rows 初始行数 / @param cols 初始列数
     * @return 会话句柄；0 = 失败
     */
    external fun createSubprocessArgv(
        argv: Array<String>,
        env: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int
    ): Long

    /**
     * 阻塞读 master fd（pty 输出 → 仿真器）。
     *
     * @return 读到的字节数；0 = EOF（shell 已退出）；-1 = 句柄无效 / 已销毁 / 读错误
     */
    external fun readBytes(handle: Long, buf: ByteArray): Int

    /**
     * 写 master fd（用户输入 → pty），内部写全。
     *
     * @return 写入字节数；-1 = 句柄无效 / 已销毁 / 写错误
     */
    external fun writeBytes(handle: Long, data: ByteArray): Int

    /** 修改 pty 窗口大小（TIOCSWINSZ），子进程收到 SIGWINCH 后自行重排。 */
    external fun setPtyWindowSize(handle: Long, rows: Int, cols: Int)

    /** 杀整个进程组并回收僵尸（幂等；销毁会话必走）。 */
    external fun killProcessGroup(handle: Long)

    /**
     * 等待子进程退出（WNOHANG 轮询）。
     *
     * @param timeoutMs 轮询上限毫秒数
     * @return 退出码（信号杀死 = 128 + 信号）；-1 = 仍在运行或无法获取
     */
    external fun waitFor(handle: Long, timeoutMs: Int): Int
}
