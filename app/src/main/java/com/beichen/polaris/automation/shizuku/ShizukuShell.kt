package com.beichen.polaris.automation.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.beichen.polaris.core.time.Monotonic
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream

/**
 * Shizuku 提权执行（W-13 B 方案）。
 *
 * 前提：用户安装 Shizuku，并在 Shizuku 内「通过无线调试启动」，再回本 App 授权一次。
 * 授权后本对象可用 shell（uid 2000）执行命令：文件清理、pm、settings、screencap、input。
 *
 * 设计取舍：
 *  - 不持有长连接（Shizuku binder 自己管），每次执行起一个 `sh -c` 进程；
 *  - stdout/stderr 并发读取，避免大输出把管道塞死；
 *  - 超时强杀，返回 timedOut 标记（绝不假装成功）。
 */
object ShizukuShell {

    const val REQUEST_CODE = 4210

    /** UserService 绑定的等待上限（毫秒）。Shizuku 自身启动服务约 3~10 秒。 */
    private const val BIND_TIMEOUT_MS = 15_000L

    /**
     * Shizuku 系应用包名的**候选列表**（顺序即优先级）。
     *
     * 官方 Shizuku 的 applicationId 是 `moe.shizuku.privileged.api`（GitHub RikkaApps/Shizuku、
     * Play 商店同一包名）；`moe.shizuku.manager` 只是其内部 manager 模块名，**不是安装包名**。
     * 之前只查后者，导致「明明装了却显示未安装」（真机录像实证）。
     * Sui（Magisk 版 Shizuku fork）包名 `com.rosan.sui`，一并纳入。
     */
    val PACKAGES = listOf(
        "moe.shizuku.privileged.api", // 官方 Shizuku
        "moe.shizuku.manager",        // 个别分支/旧包名
        "com.rosan.sui"               // Sui（Magisk 模块版）
    )

    /** 官方 Shizuku 包名（安装引导/应用详情兜底用）。 */
    const val PACKAGE = "moe.shizuku.privileged.api"

    /** 已安装的 Shizuku 系包名；未安装返回 null。 */
    fun installedPackage(context: Context): String? = PACKAGES.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    /**
     * Shizuku 是否**已安装**（与「是否在运行」分开判断）。
     *
     * 分开的原因（真机反馈）：用户装好了但没启动时，旧文案仍写「先安装 Shizuku」，
     * 把人往应用商店赶——而正确动作是「打开已装的 Shizuku 并点『通过无线调试启动』」。
     */
    fun isInstalled(context: Context): Boolean = installedPackage(context) != null

    /**
     * 拉起 Shizuku 主界面（已安装时）。返回是否成功拉起；
     * 未安装返回 false，由调用方决定是否引导到商店。
     */
    fun launch(context: Context): Boolean {
        val pkg = installedPackage(context) ?: return false
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg)
        }.getOrNull() ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /** Shizuku 服务是否在运行（用户是否启动过 Shizuku）。 */
    fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 是否已获得本 App 的授权。 */
    fun hasPermission(): Boolean =
        binderAlive() && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** 发起授权请求（需 App 在前台；结果通过 Shizuku.addRequestPermissionResultListener 回调）。 */
    fun requestPermission(): Boolean {
        if (!binderAlive()) return false
        return runCatching { Shizuku.requestPermission(REQUEST_CODE); true }.getOrDefault(false)
    }

    /** 可用性摘要（UI/能力链共用，避免两处判断漂移）。 */
    fun status(): Status = when {
        !binderAlive() -> Status.NOT_RUNNING
        !hasPermission() -> Status.NO_PERMISSION
        else -> Status.READY
    }

    enum class Status { NOT_RUNNING, NO_PERMISSION, READY }

    /**
     * 面向界面的四态（把「未运行」拆成未安装 / 已装未启动）：
     *  - [NOT_INSTALLED]       → 引导安装（商店 / GitHub releases）
     *  - [INSTALLED_NOT_RUNNING] → **直接拉起 Shizuku**，提示在其中点「通过无线调试启动」
     *  - [NO_PERMISSION]       → 直接发起授权请求
     *  - [READY]               → 已就绪
     *
     * 纯函数：入参为探测结果，便于 JVM harness 断言文案/动作路由。
     */
    enum class UiState { NOT_INSTALLED, INSTALLED_NOT_RUNNING, NO_PERMISSION, READY }

    fun uiState(status: Status, installed: Boolean): UiState = when {
        status == Status.READY -> UiState.READY
        status == Status.NO_PERMISSION -> UiState.NO_PERMISSION
        installed -> UiState.INSTALLED_NOT_RUNNING
        else -> UiState.NOT_INSTALLED
    }

    data class ShellOut(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        /** 通道**启动**就失败时的真实原因（null = 进程确实起来了）。与 timedOut 互斥。 */
        val startError: String? = null,
        /** 实际耗时（毫秒）——用于区分「真超时」与「秒失败」。 */
        val elapsedMs: Long = 0
    ) {
        val ok: Boolean get() = !timedOut && startError == null && exitCode == 0
    }

    /* ================================================================== */
    /* W-37：UserService 通道（首选执行路径）                              */
    /*                                                                     */
    /* 命令在 shell 进程内起「本地进程」，waitFor(timeout) 语义 */
    /* 正常；而 newProcess 返回的是远端代理，定时 waitFor 未实现会直接抛异常。*/
    /* 保留 newProcess 轮询作为兜底（Shizuku 版本差异时仍可降级）。          */
    /* ================================================================== */

    private val bindMutex = Mutex()

    @Volatile
    private var userService: IShizukuShellService? = null

    @Volatile
    private var pendingBind: CompletableDeferred<IShizukuShellService>? = null

    @Volatile
    private var serviceArgs: Shizuku.UserServiceArgs? = null

    @Volatile
    private var appContext: Context? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = IShizukuShellService.Stub.asInterface(service)
            userService = bound
            runCatching { pendingBind?.complete(bound) }
            pendingBind = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            runCatching {
                pendingBind?.completeExceptionally(IllegalStateException("Shizuku UserService 已断开"))
            }
            pendingBind = null
        }
    }

    /**
     * 初始化（幂等）。必须在 Application 里尽早调用一次：
     * 构造 UserServiceArgs 需要 Context，且 binder 死亡监听要尽早挂上。
     */
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        serviceArgs = runCatching {
            Shizuku.UserServiceArgs(
                ComponentName(ctx.packageName, ShizukuShellUserService::class.java.name)
            )
                .daemon(false)
                .tag("agentkit_shell")
                .processNameSuffix("shizuku_shell")
                .debuggable((ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                .version(appVersionCode(ctx))
        }.getOrNull()
        runCatching {
            Shizuku.addBinderDeadListener(
                Shizuku.OnBinderDeadListener {
                    // Shizuku 服务死了，代理立刻作废，否则下一次调用会挂在死 binder 上
                    userService = null
                    runCatching {
                        pendingBind?.completeExceptionally(IllegalStateException("Shizuku 服务已断开"))
                    }
                    pendingBind = null
                }
            )
        }
    }

    /**
     * 解绑并清空绑定状态（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * 不调 `unbindUserService`：它需要当初 bind 时那个 Context，而 reset 的调用方
     * （harness）手里没有真实 Activity。这里只把代理置空——**正在跑的调用会拿到
     * 「通道不可用」并回落**，不会踩到悬空 binder。绑定监听是匿名实例，Shizuku
     * 没有提供按引用移除的 API，所以那一个监听会留到进程结束；它回调时
     * `userService = null` 本身是幂等的，留下的副作用只是多一次空写。
     */
    fun reset() {
        runCatching { pendingBind?.cancel() }
        pendingBind = null
        userService = null
        serviceArgs = null
        appContext = null
    }

    @Suppress("DEPRECATION")
    private fun appVersionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    }.getOrDefault(1)

    /**
     * 用 UserService 执行命令。返回 null = 该通道当前不可用（未初始化 / 未授权 / 绑定失败），
     * 调用方应回落到 [exec]（newProcess 轮询）。
     */
    suspend fun execViaUserService(command: String, timeoutSec: Long = 20): ShellOut? {
        val ctx = appContext ?: return null
        if (!hasPermission()) return null
        // Shizuku v11 之前没有 UserService
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) return null
        val service = ensureBound(ctx) ?: return null

        val started = System.currentTimeMillis()
        return try {
            val bundle = withContext(Dispatchers.IO) {
                service.exec(command, (timeoutSec * 1000).coerceAtMost(1_800_000L).toInt())
            }
            val outcome = ShizukuResultLogic.map(
                stdout = bundle.getString(ShizukuShellUserService.KEY_STDOUT).orEmpty(),
                stderr = bundle.getString(ShizukuShellUserService.KEY_STDERR).orEmpty(),
                exitCode = bundle.getInt(
                    ShizukuShellUserService.KEY_EXIT_CODE,
                    ShizukuResultLogic.EXIT_FAILURE
                )
            )
            ShellOut(
                exitCode = outcome.exitCode,
                stdout = outcome.stdout,
                stderr = outcome.stderr,
                timedOut = outcome.timedOut,
                startError = outcome.startError,
                elapsedMs = bundle.getLong(ShizukuShellUserService.KEY_ELAPSED_MS).takeIf { it > 0 }
                    ?: (System.currentTimeMillis() - started)
            )
        } catch (e: Throwable) {
            val why = "UserService 调用失败：${e.javaClass.simpleName}: ${e.message}"
            ShellOut(
                exitCode = -1,
                stdout = "",
                stderr = why,
                startError = why,
                elapsedMs = System.currentTimeMillis() - started
            )
        }
    }

    /** 幂等绑定 UserService；失败返回 null（由调用方降级，不抛给模型）。 */
    private suspend fun ensureBound(context: Context): IShizukuShellService? {
        userService?.let { return it }
        val args = serviceArgs ?: return null
        return bindMutex.withLock {
            userService?.let { return@withLock it }
            val deferred = CompletableDeferred<IShizukuShellService>()
            pendingBind = deferred
            runCatching {
                // Shizuku 要求在主线程绑定
                withContext(Dispatchers.Main) {
                    Shizuku.bindUserService(args, serviceConnection)
                }
            }.onFailure {
                pendingBind = null
                // withContext(Dispatchers.Main) 的取消不能被吞成「绑定失败」——
                // 否则取消会让 ensureBound 返回 null，调用方误判成通道不可用
                if (it is CancellationException) throw it
                return@withLock null
            }
            withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }.also { bound ->
                if (bound == null) {
                    // 超时：解开绑定，避免僵尸连接占着 shell 进程
                    runCatching { Shizuku.unbindUserService(args, serviceConnection, true) }
                    pendingBind = null
                }
            }
        }
    }


    /**
     * 以 shell 身份执行命令。未授权/未运行时返回 exitCode=-1 且 stderr 说明原因，
     * 调用方据此决定是否回退本机 user shell。
     */
    fun exec(command: String, timeoutSec: Long = 20): ShellOut {
        // 这一段的 started / deadline / elapsed 必须同源（都走单调时钟）：
        // 超时判定不能被墙钟回拨影响，对外报的 elapsedMs 也不能因为对时变成负数。
        val started = Monotonic.nowMs()
        if (!hasPermission()) {
            val why = "Shizuku 未运行或未授权（请先在 Shizuku 内启动并授权本应用）"
            return ShellOut(-1, "", why, startError = why, elapsedMs = 0)
        }
        // W-56：反射集中在 ShizukuProcess（持久后端 ShizukuTerminalBackend 也用它）
        val process = ShizukuProcess.open(arrayOf("sh", "-c", command)) ?: run {
            val why = "启动 shell 失败：Shizuku newProcess 不可用（未授权或 API 变更）"
            return ShellOut(-1, "", why, startError = why, elapsedMs = Monotonic.nowMs() - started)
        }

        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val tOut = Thread { runCatching { process.inputStream.use { it.copyTo(out) } } }
        val tErr = Thread { runCatching { process.errorStream.use { it.copyTo(err) } } }
        tOut.start()
        tErr.start()

        // 【W-36 修复】原实现用 process.waitFor(timeout, SECONDS)。Shizuku 返回的 Process
        // 是远端进程代理，其定时 waitFor 会立刻抛异常；异常被 runCatching 吞成 false，
        // 于是「启动失败」被误报成「超时」——真机日志里 `echo hello` 6ms 就报 timeout，
        // 模型被骗着反复重试十几轮。
        // 改为自己轮询 exitValue()：任何 Process 实现都成立，且异常能被如实记录。
        var exitCode: Int? = null
        var waitError: String? = null
        val deadline = started + timeoutSec * 1000
        while (Monotonic.nowMs() < deadline) {
            val code = runCatching { process.exitValue() }
            if (code.isSuccess) {
                exitCode = code.getOrDefault(-1)
                break
            }
            val e = code.exceptionOrNull()
            // IllegalThreadStateException = 进程还在跑，正常；其余（RemoteException 等）= 通道异常
            if (e != null && e !is IllegalThreadStateException) {
                waitError = "${e.javaClass.simpleName}: ${e.message}"
                break
            }
            try {
                Thread.sleep(25)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        if (exitCode == null) {
            runCatching { process.destroy() }
            tOut.join(300)
            tErr.join(300)
            val elapsed = Monotonic.nowMs() - started
            val partial = out.text()
            // 秒退 + 无任何输出 + 有异常 → 是通道坏了，不是命令跑久了
            if (waitError != null && elapsed < 1_500) {
                val why = "提权通道异常：$waitError"
                return ShellOut(-1, partial, err.text().ifBlank { why }, startError = why, elapsedMs = elapsed)
            }
            return ShellOut(-1, partial, err.text(), timedOut = true, elapsedMs = elapsed)
        }
        tOut.join(500)
        tErr.join(500)
        return ShellOut(exitCode, out.text(), err.text(), elapsedMs = Monotonic.nowMs() - started)
    }

    private fun ByteArrayOutputStream.text(): String = String(toByteArray(), Charsets.UTF_8)
}
