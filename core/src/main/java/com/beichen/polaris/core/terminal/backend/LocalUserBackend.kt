package com.beichen.polaris.core.terminal.backend

import java.io.File

/**
 * 本机 user 后端（T07）：以 App 的本机 uid 身份跑一条 `/system/bin/sh`（无 rootfs 时的降级路径）。
 *
 * 零外部依赖：只用 `java.lang.ProcessBuilder`。**不 import 任何 `android.*`**，
 * 因此可在 JVM harness 里以 `/bin/sh` 直接跑真实会话做端到端验证（cwd / 环境变量跨调用保持）。
 *
 * W-56 起进程生命周期（读流、写 stdin、回收、退出码轮询）全部由 [ProcessBackendBase] 承担——
 * 本类只回答「进程从哪来」。这样 Shizuku 后端（`ShizukuTerminalBackend`）能复用同一份读循环，
 * 不会出现「两份 close 语义各自演化」。
 *
 * 已知取舍：无 PTY（`ProcessBuilder` 拿到的不是伪终端），故 `Ctrl-C` 不是真中断、
 * `ls --color` 之类的交互特性不生效——这已写进 shell 工具描述，让模型知情而不是瞎试。
 *
 * @param shellPath shell 绝对路径（Android = `/system/bin/sh`；JVM harness = `/bin/sh`）
 * @param workingDir 会话初始工作目录（宿主真实目录）
 * @param extraEnv 追加/覆盖的环境变量（`ProcessBuilder` 默认继承 JVM 环境）
 */
class LocalUserBackend(
    private val shellPath: String = DEFAULT_SHELL,
    private val workingDir: File,
    private val extraEnv: Map<String, String> = emptyMap()
) : ProcessBackendBase() {

    override val backendKey: String = TerminalBackend.KEY_USER

    override fun openProcess(): Process {
        val pb = ProcessBuilder(shellPath)
        pb.directory(workingDir)
        pb.redirectErrorStream(true) // stderr 并入 stdout：模型只看一路输出更省心
        pb.environment().putAll(extraEnv)
        return pb.start()
    }

    override val startLog: String get() = "本机 user 后端已启动：$shellPath"

    override val startFailLog: String get() = "本机 user shell 启动失败（$shellPath）"

    companion object {
        /** Android 上的本机 shell 路径。 */
        const val DEFAULT_SHELL = "/system/bin/sh"
    }
}
