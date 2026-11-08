package com.beichen.polaris.core.terminal.backend

import com.beichen.polaris.core.AgentLogger
import java.io.File

/**
 * W-RT5：SSH 远端 shell 后端——把 `ssh` 客户端进程包成统一终端后端。
 *
 * 继承 [ProcessBackendBase] 之后只剩一件事要做：回答「进程从哪来」。常驻远程 shell、
 * 输出合并、stdin 写入、退出码轮询全部复用基类——远程登录 shell 与本机 shell 在进程
 * 语义上没有区别，会话池的哨兵协议照常工作。
 *
 * 已知取舍（写进这里而不是让模型自己撞）：无 PTY、无交互式登录，`BatchMode=yes` 下
 * 认证失败会直接退出；远端侧读到的是**非交互 shell**，`~/.bashrc` 里依赖
 * `[[ $- != *i* ]]` 提前返回的那种配置不会生效。
 *
 * @param sshPath ssh 可执行文件路径（探测阶段由 [SshBinaryLocator.locate] 确定）
 * @param profile 已通过 [normalizeSshProfile] 校验的连接档案
 */
class SshBackend(
    private val sshPath: String,
    private val profile: SshProfile
) : ProcessBackendBase(
    /**
     * 声明的身份是 [TerminalIdentity.User]，不是 [TerminalIdentity.Ssh]。
     *
     * 远端登录 shell 就是「远端那台机器上的普通用户」，与本机 uid 不可比
     * （[TerminalIdentity.satisfies] 里两者互不满足）；而会话池的复用 / 冲突判定要求
     * 「会话实际身份 ≥ 本次下限」。声明 Ssh 的话，同一会话上后续任何 `identity=user`
     * 的命令都会被判冲突——远端机器连 `ls` 都跑不了。
     *
     * 「这是远端」由 [backendKey] 承载（池名、回执、日志都按通道报告），不借身份维度表达。
     */
    TerminalIdentity.User
) {

    override val backendKey: String = TerminalBackend.KEY_SSH

    override val startLog: String
        get() = "后端已启动：ssh（${profile.user}@${profile.host}:${profile.port}）"

    override val startFailLog: String
        get() = "后端启动失败：ssh（${profile.user}@${profile.host}:${profile.port}）"

    override fun openProcess(): Process? = runCatching {
        ProcessBuilder(buildSshArgs(profile, sshPath))
            .redirectErrorStream(true)
            .start()
    }.getOrElse { e ->
        AgentLogger.w(TAG, "起 ssh 进程失败：${e.message}")
        null
    }

    companion object {
        private const val TAG = "SshBackend"
    }
}

/**
 * SSH 后端提供者：探测 = 设备上有可用的 ssh 命令 **且** 档案已配置。
 *
 * **不进自动降级链**：[TerminalBackendFactory.providersFor] 把本键排除在候选之外——
 * 远端机器不能悄悄顶替本机 / 容器 shell。上层用 `identity=ssh` 显式指名时，
 * 经会话池的 `open(backendKey = KEY_SSH)` 路由到这里（见 [TerminalBackendSource.obtainByKey]）。
 *
 * @param sshPathProvider 惰性定位 ssh 可执行文件；null = 当前环境没有
 * @param profileProvider 惰性取当前连接档案；null = 用户还没配置
 */
class SshBackendProvider(
    private val sshPathProvider: () -> String?,
    private val profileProvider: () -> SshProfile?
) : TerminalBackendProvider {

    override val identity: TerminalIdentity = TerminalIdentity.User

    override val backendKey: String = TerminalBackend.KEY_SSH

    /** 探测只看「有没有 ssh 命令 + 有没有档案」，**不连**远端：每次探测都发网络请求会拖慢权限页。 */
    override fun probe(): Boolean = sshPathProvider() != null && profileProvider() != null

    override fun create(): TerminalBackend? {
        val sshPath = sshPathProvider() ?: return null
        val profile = profileProvider() ?: return null
        return SshBackend(sshPath, profile)
    }
}

/**
 * ssh 可执行文件定位（纯函数 + `File.canExecute` 探测，JVM / Android 通用）。
 *
 * Android 没有预装 ssh，实际来源通常是容器 rootfs 里的 openssh-client（容器工具链安装）
 * 或用户自带的静态编译二进制——候选清单由装配处给定，本对象不做环境假设。
 *
 * 为什么查 `canExecute` 而不是 `exists`：rootfs 里的文件在宿主侧存在但可能没有执行位
 * （解压时权限位丢失），`exists` 会报「有」然后起进程时抛 IOException，
 * 那一步的错误信息里只有 `error=13, Permission denied`，看不出是定位错了。
 */
object SshBinaryLocator {

    /** Android 侧默认候选：系统目录极少有（厂商定制机偶见），容器 rootfs 是主渠道。 */
    fun defaultCandidates(rootfsBinDir: String?): List<String> = buildList {
        add("/system/bin/ssh")
        add("/system/xbin/ssh")
        rootfsBinDir?.takeIf { it.isNotBlank() }?.let { add("$it/ssh") }
    }

    /** 返回第一个存在且可执行的候选路径；都没有返回 null。 */
    fun locate(candidates: List<String>): String? =
        candidates.firstOrNull { p -> runCatching { File(p).canExecute() }.getOrDefault(false) }
}
