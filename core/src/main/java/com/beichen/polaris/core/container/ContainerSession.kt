package com.beichen.polaris.core.container

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.terminal.backend.ProotBackend
import com.beichen.polaris.core.terminal.backend.TerminalBackend
import com.beichen.polaris.terminal.TerminalSession
import java.io.File

/**
 * 容器会话工厂：把 proot 二进制 + 解压后的 rootfs 组装成一条 [TerminalSession]。
 *
 * 复用 TerminalSession 的 pty 机制（forkpty + 读循环 + 仿真器），只是把要执行的程序
 * 从 `/system/bin/sh` 换成 `proot -r <rootfs> ... /bin/sh`——argv 形式走新增的
 * [com.beichen.polaris.terminal.TerminalJni.createSubprocessArgv]。
 *
 * argv 与 env 全部来自 [ProotRunner.buildInvocation]：
 * - `argv[0]` 是 proot 自身路径（选项不会错位）；
 * - env 是「宿主 `System.getenv()` + 容器自定义」的并集（proot 不会静默退出）。
 *
 * 任何前置条件不满足（proot 缺失 / rootfs 未解压）都返回 null，由 UI 降级。
 */
object ContainerSession {

    private const val TAG = "ContainerSession"

    /**
     * T07 适配：把容器（proot）**适配成统一终端后端** [TerminalBackend]。
     *
     * [create]/[runCommand] 的对外行为与签名**保持不变**（UI 的 ContainerScreen 仍走它们）；
     * 本函数只是把「proot 容器」纳入后端抽象，使会话池 / shell 工具能用同一个接口驱动
     * 「容器」与「本机 user」两条路径，上层无需 `if (isContainer)` 分支。
     *
     * 惰性：返回的 [ProotBackend] 在 `start()` 时才真正调用 [create]（提取三件套 + 组装
     * argv/env），避免工厂探测阶段白做重活。
     *
     * @return 统一的 proot 后端
     */
    fun backend(context: Context, distro: RootfsManager.Distro): TerminalBackend =
        ProotBackend(context, distro)

    /**
     * 创建并配置一条容器终端会话（不自动 start，start 由 holder 在 UI 线程调用）。
     *
     * @return 就绪的 [TerminalSession]；前置条件不满足返回 null。
     */
    fun create(context: Context, distro: RootfsManager.Distro): TerminalSession? {
        val prootBin = ProotRunner.ensureBinary(context) ?: run {
            AgentLogger.e(TAG, "proot 二进制不可用")
            return null
        }
        if (!RootfsManager.isExtracted(context, distro.id)) {
            AgentLogger.e(TAG, "rootfs(${distro.id}) 尚未解压")
            return null
        }
        val rootfs = RootfsManager.rootfsDir(context, distro.id)
        val home = File(rootfs, "root")
        // 容器内 DNS：rootfs 自带的 resolv.conf 是空/缺失，不写就解析不了域名
        ContainerNetwork.ensureResolvConf(context, rootfs)
        val invocation = ProotRunner.buildInvocation(
            prootBinary = prootBin,
            rootfs = rootfs,
            workDir = ProotRunner.GUEST_HOME,
            packageName = context.packageName,
            // 运行库在 filesDir/bin，proot 本体在 nativeLibraryDir——必须显式区分
            libDir = ProotRunner.libDir(context)
        )
        // 日志自检（无 JUnit 的替代验收手段）：不变量不满足会落一条 WARN，adb 一查即知
        val problems = invocation.validate()
        if (problems.isNotEmpty()) {
            AgentLogger.w(TAG, "proot invocation 自检未通过：$problems")
        } else {
            AgentLogger.i(TAG, "proot invocation 自检通过：${invocation.describe()}")
        }
        return TerminalSession(
            argv = invocation.argv,
            // 宿主侧真实目录，chdir 可成功；proot -w /root 再切到 guest /root
            cwd = rootfs,
            filesDir = context.filesDir,
            packageName = context.packageName,
            homeDir = home,
            env = invocation.envArray.toList()
        )
    }

    /**
     * 在容器会话里执行一条**非交互**命令（T10 的对外入口；当前为 PTY 版占位实现）。
     *
     * 命令通过**位置参数**传入（`sh -l -c 'cd -- "$1" && eval "$2"' agentkit "$cwd" "$cmd"`），
     * 不拼进 shell 字符串，因此 `echo "*.vue"` 这类含空格 / 引号 / 通配符的命令按字面执行。
     * 非交互三件套 `CI=true NO_COLOR=1 PAGER=cat` 已在 [ProotRunner.guestEnvMap] 注入会话 env，
     * `apt install -y` / `git log` 不会卡在确认提示或分页器。
     *
     * **当前实现的边界（明确标注）**：本方法把命令行写进会话的 pty stdin，输出落到
     * [TerminalSession.emulator]；由于现有通道只有 pty（没有独立的 stdout / exitCode 管道），
     * **返回值只能表示「命令是否已下发」，取不到退出码，也拿不到结构化输出**。
     * 需要「退出码 + 干净输出」的调用方（未来的 run_command 工具）应等 T07/T08 的
     * 终端后端抽象（带 `awaitExit` 与输出流的通道）落地后再接，届时只需把本方法内部的
     * `session.write(...)` 换成后端 `send(...)`，[ProotRunner.buildCommandArgv] 可原样复用。
     *
     * 参数序：**有默认值的 [cwd] 必须排在无默认值的 [command] 之后**——否则位置实参调用
     * `runCommand(session, "ls")` 会被静默解析成 `cwd="ls"`，编译不报错但行为完全错。
     *
     * @param session 已 start 的容器会话；null 或未存活时返回 false
     * @param command 命令原文（**不要**预先转义）
     * @param cwd     命令的 guest 内工作目录（默认 `/root`）
     * @return 命令是否已成功下发
     */
    fun runCommand(
        session: TerminalSession?,
        command: String,
        cwd: String = ProotRunner.GUEST_HOME
    ): Boolean {
        if (session == null || !session.isAlive) {
            AgentLogger.w(TAG, "runCommand 被忽略：容器会话未就绪（session=$session）")
            return false
        }
        if (command.isBlank()) {
            AgentLogger.w(TAG, "runCommand 被忽略：命令为空")
            return false
        }
        val line = ProotRunner.toCommandLine(cwd, command)
        AgentLogger.i(TAG, "runCommand：cwd=$cwd cmd=${command.take(200)}")
        session.write("$line\n")
        return true
    }
}
