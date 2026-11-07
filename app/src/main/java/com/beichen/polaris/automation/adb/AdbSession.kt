package com.beichen.polaris.automation.adb

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * W-51：无线调试（adb over TCP）会话管理——App 内的「第二条 shell 身份」。
 *
 * ## 定位
 *
 * 与 `ShizukuShell` 同级：都以 **uid 2000（shell）** 执行命令，能做的事一样
 * （`input` / `uiautomator dump` / `screencap` / `pm` / `settings`）。区别只在通道：
 *
 * | | Shizuku | 无线调试直连 |
 * |---|---|---|
 * | 前置 | 装 Shizuku App 并授权 | 开发者选项里开「无线调试」并配对一次 |
 * | 依赖 | 第三方 App 常驻 | 无（系统 adbd） |
 * | 稳定性 | Shizuku 进程被系统杀掉则失效 | 跟随 adbd，较稳 |
 * | 断线恢复 | 自动（binder 重连） | 需手动重连（本类负责惰性重连） |
 *
 * 两条通道互不替代：Shizuku 失败时 ADB 可能仍可用，反之亦然。
 *
 * ## 连接与重连策略
 *
 * - **复用单连接**：adb 连接是会话式的，反复建连在真机上要 100~300ms，且 adbd 侧有
 *   fd 上限；本类持有一个 [AdbClient]，所有命令串行化（[lock]）执行。
 * - **闲置主动重建**：adbd 会回收长期空闲的连接（无 keepalive）。超过
 *   [IDLE_RECONNECT_MS] 未使用则下次调用前先关掉重建，避免「闲置后第一条命令必然失败」。
 * - **失败重连一次**：连接类错误（非超时、非鉴权）重连后重试一次。**代价说明**：
 *   命令可能在断开前已送达设备（例如 `input tap`），重试会再执行一次。权衡的是
 *   「闲置/切网后每次都要用户手点一次重试」的体验，且这类命令重复执行的影响面很小
 *   （点同一处、读同一棵树）。超时与鉴权失败**不重试**——前者设备仍在跑，后者重试无意义。
 */
object AdbSession {

    private const val TAG = "AdbSession"

    /** 无线调试是 Android 11（API 30）起才有的能力。 */
    const val MIN_SDK = 30

    /** 闲置超过该时长则重建连接（adbd 会回收空闲连接）。 */
    private const val IDLE_RECONNECT_MS = 60_000L

    enum class Status {
        /** 还没配对过（没有端点）。 */
        NOT_CONFIGURED,

        /** 配过对，但当前没有活动连接（下次调用会尝试连）。 */
        NOT_CONNECTED,

        /** 有活动连接（至少成功通过一次命令）。 */
        CONNECTED
    }

    /** 一次命令的结果。与 `ShizukuShell.ShellOut` 字段对齐，便于上层统一处理。 */
    data class AdbOutcome(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        /** 通道未就绪/连接失败时的原因（null = 命令确实跑过）。 */
        val startError: String? = null,
        val elapsedMs: Long = 0
    ) {
        val ok: Boolean get() = startError == null && !timedOut && exitCode == 0
    }

    /** 配对结果：ok=true 时 message 是成功说明，false 时是**可照做的失败原因**。 */
    data class PairOutcome(val ok: Boolean, val message: String)

    private val lock = Any()
    private var client: AdbClient? = null
    private var lastUsedAt = 0L

    @Volatile
    private var lastError: String? = null

    fun init(context: Context) = AdbCredentials.init(context)

    fun configured(): Boolean = AdbCredentials.hasEndpoint()

    fun status(): Status = when {
        !configured() -> Status.NOT_CONFIGURED
        client != null -> Status.CONNECTED
        else -> Status.NOT_CONNECTED
    }

    fun lastError(): String? = lastError

    /** 端点是否已配好（能力链用；真正不可用会在 [exec] 里如实报错）。 */
    fun available(): Boolean = configured()

    /**
     * 配对并立即验证连接。
     *
     * @param host 手机 IP（无线调试页面上显示的那个）
     * @param pairPort 配对端口（点「使用配对码配对设备」后弹窗里显示的**另一个**端口）
     * @param code 6 位配对码
     * @param connectPort 连接端口（无线调试**主页**显示的 `IP:端口`，不是配对端口）
     */
    fun pairAndConnect(host: String, pairPort: Int, code: String, connectPort: Int): PairOutcome {
        if (android.os.Build.VERSION.SDK_INT < MIN_SDK) {
            return PairOutcome(false, "无线调试需要 Android 11 及以上（当前 ${android.os.Build.VERSION.SDK_INT}）")
        }
        if (host.isBlank()) return PairOutcome(false, "IP 不能为空")
        if (pairPort !in 1..65535) return PairOutcome(false, "配对端口不合法：$pairPort")
        if (connectPort !in 1..65535) return PairOutcome(false, "连接端口不合法：$connectPort")
        if (code.length != 6 || code.any { !it.isDigit() }) return PairOutcome(false, "配对码应为 6 位数字")

        val kp = AdbCredentials.keyPair()
            ?: return PairOutcome(false, "adb 密钥不可用（存储初始化失败）")

        AgentLogger.i(TAG, "开始配对：host=$host pairPort=$pairPort connectPort=$connectPort")

        val pairResult = runCatching {
            AdbPairingClient(
                host = host,
                port = pairPort,
                pairingCode = code,
                keyPair = kp,
                // W-51：SPAKE2 实现（此前只有接口没有实现，是本通道无法启用的唯一硬阻塞）
                spake2Factory = { password -> Spake2Ed25519(password, isClient = true) }
            ).pair()
        }
        pairResult.exceptionOrNull()?.let { e ->
            val why = when {
                e is IllegalArgumentException && e.message?.contains("Android 12") == true ->
                    "无线配对需要 Android 12 及以上（系统 TLS 密钥导出 API 限制）"
                e.message?.contains("解密失败") == true ->
                    "配对码不正确，或配对端口填的是「连接端口」——这两个端口不同，请注意区分"
                e is SocketTimeoutException ->
                    "配对超时：确认 IP 正确、手机与当前设备在同一网络，且配对端口是弹窗里显示的那个"
                else -> "配对失败：${e.javaClass.simpleName}: ${e.message}"
            }
            lastError = why
            AgentLogger.w(TAG, why)
            return PairOutcome(false, why)
        }

        AdbCredentials.saveEndpoint(host, connectPort)
        synchronized(lock) {
            runCatching { client?.close() }
            client = null
        }

        // 配对成功不等于连得上（端口可能是旧的），立刻用一次 echo 验证，把问题留在这一屏
        val verify = exec("echo agentkit-adb-ok", timeoutSec = 12)
        return if (verify.ok && verify.stdout.contains("agentkit-adb-ok")) {
            AgentLogger.i(TAG, "配对并连接成功：$host:$connectPort")
            PairOutcome(true, "配对成功，已连接（shell 身份可用）")
        } else {
            val why = "配对成功，但连接验证失败：${verify.startError ?: verify.stderr.take(160)}"
            lastError = why
            PairOutcome(false, why)
        }
    }

    /**
     * 执行一条 shell 命令（阻塞；调用方需在 IO 线程）。
     *
     * @param timeoutSec 命令级超时（同时作为 socket 读超时）
     */
    fun exec(command: String, timeoutSec: Long = 20): AdbOutcome {
        val host = AdbCredentials.host()
        val port = AdbCredentials.connectPort()
        if (host.isNullOrBlank() || port <= 0) {
            return fail("未配置无线调试端点：请在「设置 → 调试通道」里完成一次配对")
        }
        val kp = AdbCredentials.keyPair() ?: return fail("adb 密钥不可用（存储初始化失败）")
        val timeoutMs = (timeoutSec * 1000).toInt().coerceIn(3_000, 15 * 60_000)

        synchronized(lock) {
            val started = System.currentTimeMillis()

            // 闲置太久 → 丢弃旧连接（adbd 已回收，继续用只会等超时）
            val cached = client
            if (cached != null && started - lastUsedAt > IDLE_RECONNECT_MS) {
                runCatching { cached.close() }
                client = null
            }

            // 用 val 接住：`var + 闭包捕获`会让编译器拒绝智能转换（runCatching 的 lambda 捕获）
            val conn: AdbClient = client ?: try {
                AdbClient(host, port, kp).also { it.connect() }.also { client = it }
            } catch (e: Throwable) {
                val why = describe(e, host, port)
                lastError = why
                AgentLogger.w(TAG, why)
                return fail(why, started)
            }

            val outcome = runCatching { conn.exec(command, timeoutMs) }
            val failure = outcome.exceptionOrNull()
            if (failure == null) return ok(outcome.getOrThrow(), started)

            // 超时 / 鉴权失败不重试：前者设备仍在执行（重放有副作用），后者重试也没用
            runCatching { conn.close() }
            client = null
            val retryable = failure is IOException &&
                failure !is SocketTimeoutException &&
                failure !is AdbAuthException
            if (!retryable) {
                val why = describe(failure, host, port)
                lastError = why
                return fail(why, started)
            }

            AgentLogger.w(TAG, "连接中断（${failure.javaClass.simpleName}），重连后重试一次")
            val retried: AdbClient = try {
                AdbClient(host, port, kp).also { it.connect() }.also { client = it }
            } catch (e2: Throwable) {
                val why = describe(e2, host, port)
                lastError = why
                return fail(why, started)
            }
            val retryOutcome = runCatching { retried.exec(command, timeoutMs) }
            retryOutcome.exceptionOrNull()?.let { e2 ->
                runCatching { retried.close() }
                client = null
                val why = describe(e2, host, port)
                lastError = why
                return fail(why, started)
            }
            return ok(retryOutcome.getOrThrow(), started)
        }
    }

    fun disconnect() {
        synchronized(lock) {
            runCatching { client?.close() }
            client = null
            lastUsedAt = 0
        }
    }

    /**
     * 断开并清掉错误记忆（仅供 [com.beichen.polaris.AppGraph.resetAll] 与 harness）。
     *
     * 复用 [disconnect] 而不是自己 close：`client` 是持锁写的，绕开锁单独置空
     * 会和 [exec] 的「发现 client 为空就重建」形成竞态，表现是 tests 偶发连两次。
     * 端点与密钥在 [AdbCredentials]，不受影响——重置不等于取消配对。
     */
    fun reset() {
        disconnect()
        synchronized(lock) { lastError = null }
    }

    /* ------------------------------------------------------------------ */

    private fun ok(r: ShellResult, started: Long): AdbOutcome {
        lastUsedAt = System.currentTimeMillis()
        lastError = null
        return AdbOutcome(
            exitCode = r.exitCode,
            stdout = r.stdout,
            stderr = r.stderr,
            elapsedMs = lastUsedAt - started
        )
    }

    private fun fail(why: String, started: Long = System.currentTimeMillis()): AdbOutcome =
        AdbOutcome(-1, "", why, startError = why, elapsedMs = System.currentTimeMillis() - started)

    /** 把传输层异常翻译成用户能照做的一句话。 */
    private fun describe(e: Throwable, host: String, port: Int): String = when {
        e is AdbAuthException ->
            "设备拒绝了本机密钥：请重新配对一次（无线调试 → 使用配对码配对设备）"
        e is SocketTimeoutException ->
            "连接超时（$host:$port）：确认与手机在同一网络，且端口是「无线调试」主页显示的连接端口"
        e is ConnectException ->
            "连不上 $host:$port：无线调试每次重新开关都会换端口，请核对主页上的端口"
        e is UnknownHostException ->
            "无法解析地址 $host：检查 IP 是否输错"
        e is IOException && e.message?.contains("报文头不完整") == true ->
            "连接被对端关闭（多为配对失效或端口过期），请重新配对"
        else -> "无线调试通道错误：${e.javaClass.simpleName}: ${e.message}"
    }

}
