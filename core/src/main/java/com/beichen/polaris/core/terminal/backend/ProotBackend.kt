package com.beichen.polaris.core.terminal.backend

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.container.ContainerSession
import com.beichen.polaris.core.container.ProotRunner
import com.beichen.polaris.core.container.RootfsManager
import com.beichen.polaris.terminal.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File

/**
 * proot 容器后端（T07）：把现有 [ContainerSession]（proot + rootfs + pty）**包装**成统一后端。
 *
 * 与 [LocalUserBackend] 走同一 [TerminalBackend] 接口——上层（会话池 / shell 工具）无
 * 「是容器还是本机」分支。后端的重活（proot 三件套提取、rootfs 校验、argv/env 组装）仍由
 * [ContainerSession.create] / [ProotRunner.buildInvocation] 完成，本类只做**生命周期与输出桥接**：
 *  - [start] 惰性创建 proot 会话（避免工厂探测时白白解压 rootfs）；
 *  - pty 的原始输出经 [TerminalSession.onRawOutput] 回调转入 [output] 流；
 *  - [awaitExit] 由 [TerminalSession.onExit] 完成。
 *
 * @param context App 上下文（传给 [ContainerSession]）
 * @param distro 目标发行版；null = 无 rootfs，[start] 直接失败（由上层回落本机 user）
 */
class ProotBackend(
    private val context: Context,
    private val distro: RootfsManager.Distro?
) : TerminalBackend {

    override val identity: TerminalIdentity = TerminalIdentity.User

    override val backendKey: String = TerminalBackend.KEY_PROOT

    private val channel = Channel<String>(Channel.UNLIMITED)

    override val output: Flow<String> = channel.receiveAsFlow()

    private var session: TerminalSession? = null
    private val exit = CompletableDeferred<Int>()

    override val isAlive: Boolean
        get() = session?.isAlive == true

    override suspend fun start(): Boolean {
        val d = distro ?: run {
            AgentLogger.e(TAG, "无可用 rootfs，proot 后端无法启动（应回落本机 user）")
            return false
        }
        val s = ContainerSession.create(context, d) ?: run {
            AgentLogger.e(TAG, "ContainerSession.create 返回 null（proot 三件套/rootfs 未就绪）")
            return false
        }
        session = s
        // 输出桥：pty 读线程吐出的原始 chunk 转进 output 流（会话池据此做哨兵判定）
        s.onRawOutput = { chunk -> channel.trySend(chunk) }
        s.onExit = { code ->
            if (!exit.isCompleted) exit.complete(code)
            channel.close()
        }
        return runCatching {
            s.start()
            AgentLogger.i(TAG, "proot 后端已启动：distro=${d.id}")
            true
        }.getOrElse { e ->
            AgentLogger.e(TAG, "proot 会话启动失败：${e.message}")
            false
        }
    }

    override suspend fun send(input: String): Boolean {
        val s = session ?: return false
        if (!s.isAlive) return false
        s.write(input)
        return true
    }

    override suspend fun close() {
        runCatching { session?.destroy() }
        if (!exit.isCompleted) exit.complete(-1)
        channel.close()
        AgentLogger.i(TAG, "proot 后端已关闭")
    }

    override suspend fun awaitExit(): Int = exit.await()

    companion object {
        private const val TAG = "ProotBackend"
    }
}

/**
 * proot 后端提供者（Android 侧）：探测 = proot 三件套齐备 **且** rootfs 已解压。
 *
 * **探测不做重活**（不解压、不提权）：只在 [create] 时把 distro 交给 [ProotBackend]，
 * 真正的提取/校验发生在后端 [ProotBackend.start]。
 *
 * @param distroProvider 惰性取当前目标发行版；返回 null 视为 proot 不可用
 */
class ProotBackendProvider(
    private val context: Context,
    private val distroProvider: () -> RootfsManager.Distro?
) : TerminalBackendProvider {

    override val identity: TerminalIdentity = TerminalIdentity.User

    override val backendKey: String = TerminalBackend.KEY_PROOT

    override fun probe(): Boolean {
        val d = distroProvider() ?: return false
        return ProotRunner.isAvailable(context) && RootfsManager.isExtracted(context, d.id)
    }

    override fun create(): TerminalBackend? {
        val d = distroProvider() ?: return null
        if (!probe()) return null
        // 走 ContainerSession 的适配入口（消除「provider 直接 new ProotBackend」与容器模块重复的
        // 构造路径）：封装容器后端的唯一入口归 `ContainerSession.backend(...)`，本 provider 只负责
        // 「探测 + 取 distro」。行为与原先 `ProotBackend(context, d)` 完全一致（该函数体即此构造）。
        return ContainerSession.backend(context, d)
    }
}

/**
 * 本机 user 后端提供者（Android 侧）：恒可用（`/system/bin/sh` 是 Android 标配）。
 *
 * 环境变量补齐：HOME / TMPDIR / ANDROID_PACKAGE；PATH 沿用宿主（本机命令需要）。
 */
class LocalUserBackendProvider(
    private val context: Context
) : TerminalBackendProvider {

    override val identity: TerminalIdentity = TerminalIdentity.User

    override val backendKey: String = TerminalBackend.KEY_USER

    override fun probe(): Boolean = true

    override fun create(): TerminalBackend {
        val tmp = File(context.filesDir, "tmp").apply { runCatching { mkdirs() } }
        return LocalUserBackend(
            shellPath = LocalUserBackend.DEFAULT_SHELL,
            workingDir = context.filesDir,
            extraEnv = linkedMapOf(
                "HOME" to context.filesDir.absolutePath,
                "TMPDIR" to tmp.absolutePath,
                "ANDROID_PACKAGE" to context.packageName,
                "TERM" to "dumb", // 无 PTY：显式告知，抑制依赖终端能力的程序乱试
                "CI" to "true"
            )
        )
    }
}

/**
 * 组装真实终端后端工厂（T07 出口）：proot → 无线调试（可注入）→ 本机 user。
 *
 * 放在本文件（Android 侧）而非 [TerminalBackendFactory]：后者须保持零 Android 依赖
 * 以便 JVM harness 直接验证降级链与缓存语义。
 *
 * @param context App 上下文
 * @param distroProvider 惰性取目标发行版（如 `RootfsManager.listAvailable(app).firstOrNull()`）
 */
fun createTerminalBackendFactory(
    context: Context,
    distroProvider: () -> RootfsManager.Distro?,
    /**
     * 额外后端（W-53）：无线调试的 provider 在 `automation.adb` 包，而本函数在 `core`——
     * 让 core 反向依赖 automation 会破坏分层，所以由装配处（ViewModel）注入。
     * 工厂按 `backendKey` 查找，顺序无关（见 [TerminalBackendFactory.providersFor]）。
     */
    extraProviders: List<TerminalBackendProvider> = emptyList()
): TerminalBackendFactory = TerminalBackendFactory(
    listOf(
        ProotBackendProvider(context, distroProvider),
        LocalUserBackendProvider(context)
    ) + extraProviders
)
