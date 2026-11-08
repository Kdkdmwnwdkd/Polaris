package com.beichen.polaris.core.container

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.beichen.polaris.core.AgentLogger
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 在容器里执行一次性命令并拿到退出码（B 批）。
 *
 * ## 为什么不用 TerminalSession
 * [com.beichen.polaris.terminal.TerminalSession] 是 **pty 交互式**会话：它按屏幕语义
 * 解析输出，发一条命令进去只能知道「命令是否已下发」，**取不到退出码**（见
 * [ContainerSession.runCommand] 的 KDoc 明确说明）。
 *
 * 但「一键安装运行时」必须有退出码才能判断成功与否——`apk add` 失败时退出码非 0，
 * 而 pty 通道拿不到。所以这里走**独立的 ProcessBuilder 通道**：
 * 直接以 proot 的 argv 起一个短命进程，命令跑完即退出，退出码与 stdout/stderr 都拿得到。
 *
 * 这与 [com.beichen.polaris.core.job.JobRunner] 是同一思路（后台任务用独立进程，
 * 不与会话池共享状态），区别是这里要**同步等退出码**，所以用 `waitFor` 而非轮询。
 *
 * ## 并发：这里必须只有一个写者（真机实测反例）
 * 真机上连点两次「安装 Node.js 20」，结果是：
 * ```
 * 安装失败（exit 99）：ERROR: Unable to lock database: temporary error (try again later)
 * ```
 * 因为 apk 用 `lib/apk/db/lock` 做进程间互斥，两个 proot 同时跑 `apk add` 时会抢锁。
 * 这不是网络问题、也不是 rootfs 损坏——**并发本身**就是 bug。所以本类加了两道闸：
 * 1. [execLock]：进程级公平锁，所有容器命令**排队串行**执行（不同命令也不许并跑）；
 * 2. 同命令去重：一模一样的命令已在执行时直接返回 [EXIT_BUSY]，不排队、不重复下载。
 *
 * 另外对「抢锁」做了原地重试（见 [LOCK_RETRY_DELAYS_MS]），兜住极端情况：上一次
 * 安装被系统杀掉后，apk 的锁可能还没完全释放。
 *
 * ## 边界
 * - 安装 node/python/java 可能耗时几分钟（下载 + 解包），因此给一个较宽的超时上限。
 * - 超时按「失败」处理并杀掉进程——不留半装状态在后台偷偷跑。
 */
object ContainerCommandRunner {

    private const val TAG = "ContainerCommandRunner"

    /** 执行结果。 */
    data class Result(
        val exitCode: Int,
        val output: String
    ) {
        val ok: Boolean get() = exitCode == 0

        /** 是否因为「同一命令已在执行」被拒（不是失败，是别重复点）。 */
        val busy: Boolean get() = exitCode == EXIT_BUSY
    }

    /**
     * 退出码：同一命令已在执行。
     *
     * 与「前置条件不满足」的 -1 分开，是因为两者对用户的含义完全不同：
     * -1 要用户去修环境；-2 只要等一会儿，UI 该显示「正在进行」而不是红色报错。
     */
    const val EXIT_BUSY = -2

    /** 默认超时：装 JDK 慢，但也不该无限等。 */
    private const val DEFAULT_TIMEOUT_MINUTES = 15L

    /** 单次输出上限（安装日志可能很长，截断避免 OOM）。 */
    private const val MAX_OUTPUT_CHARS = 64 * 1024

    /** 失败输出落盘保留份数（`logs/container-fail-*.log`）。 */
    private const val DIR_KEEP_FILES = 5

    /** 抢锁失败后的重试间隔；长度即重试次数（不含首次）。 */
    private val LOCK_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L)

    /**
     * 超时后交给 SIGTERM 的宽限时间。
     *
     * proot 的 `--kill-on-exit` 是 `atexit` 注册的清理钩子（二进制符号可见
     * `kill_all_tracees` / `__atexit_handler_wrapper`），只有在进程**自己走完 exit()**
     * 时才执行。给 3 秒足够它杀掉 tracee 并回收；真机上正常路径 < 200ms 就退出了。
     */
    private const val TERM_GRACE_MS = 3_000L

    /** 进程树下探深度上限（proot → sh → apk → 子进程……），防异常环路。 */
    private const val MAX_TREE_DEPTH = 6

    /**
     * 全局串行闸门（公平锁：先来先服务，不会饿死）。
     *
     * 为什么是**进程级**而不是「按发行版一把锁」：`/data` 上不同 rootfs 的 apk
     * 各自有自己的 lock 文件，理论上可以并跑；但真机上多个 proot 同时跑时内存
     * 压力会触发 LMK，pty 会话被 SIGKILL（退出码 137）。串行是更稳的选择，
     * 而「同时装两个发行版的运行时」本来也不是真实诉求。
     */
    private val execLock = ReentrantLock(true)

    /** 在飞命令登记表：key = distroId + 命令原文 → 开始时间(ms)。 */
    private val inFlight = HashMap<String, Long>()

    /** 只保护 [inFlight] 的短锁（不覆盖执行期，避免阻塞去重判断）。 */
    private val registryLock = ReentrantLock()

    /**
     * 该发行版是否有命令在飞；有则返回到现在已经跑了多少毫秒。
     *
     * 存在的理由（2026-10-27 真机 13:56）：装 Java 的 proot 正在跑，用户点进容器终端，
     * 于是同时有两个 proot。系统内存吃紧触发 LMK，**pty 会话 2 秒后就被 SIGKILL**
     * （日志 `终端会话已主动结束（code=137）`）。用户在终端里看到的是一闪而过的黑屏。
     *
     * 调用方（容器页）用它把「进终端」这一步挡住并说明原因，而不是让用户
     * 一边装一边进、然后撞上 137。
     */
    fun busyFor(distroId: String): Long? = registryLock.withLock {
        val prefix = "$distroId\u0000"
        inFlight.entries.firstOrNull { it.key.startsWith(prefix) }?.let {
            System.currentTimeMillis() - it.value
        }
    }

    /**
     * 在容器中执行一条命令并等待结束。
     *
     * @param context 应用上下文
     * @param distroId 目标发行版 id（须已解压）
     * @param command 命令原文（**原样传入**，由容器内 shell 自己解析）
     * @return 执行结果；前置条件不满足返回 exitCode=-1，重复提交返回 [EXIT_BUSY]
     */
    fun run(
        context: Context,
        distroId: String,
        command: String,
        timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES
    ): Result {
        val key = "$distroId\u0000$command"
        // 先登记再去排队：同一条命令正在跑时立刻拒绝，别让用户连点十下攒出十个下载任务
        registryLock.withLock {
            val since = inFlight[key]
            if (since != null) {
                val waited = (System.currentTimeMillis() - since) / 1000
                AgentLogger.w(TAG, "拒绝重复命令：distro=$distroId 已在执行 ${waited}s")
                return Result(EXIT_BUSY, "同一条命令已在容器内执行（已 ${waited}s），请等它结束")
            }
            inFlight[key] = System.currentTimeMillis()
        }
        try {
            // 不同命令也必须串行：apk/apt 是单实例包管理器，并跑必然抢锁
            execLock.withLock {
                AgentLogger.i(TAG, "容器命令开始：distro=$distroId cmd=${command.take(160)}")
                val started = System.currentTimeMillis()
                val result = execute(context, distroId, command, timeoutMinutes)
                val cost = (System.currentTimeMillis() - started) / 1000
                AgentLogger.i(
                    TAG,
                    "容器命令结束：distro=$distroId exit=${result.exitCode} 耗时=${cost}s"
                )
                // 失败时把完整输出落盘：UI 只能显示十来行，而 apk 的「1 error」前面
                // 往往还有决定性的那一条。落盘后用户从日志页就能导出，不用再靠截图。
                if (result.exitCode != 0 && result.exitCode != EXIT_BUSY) {
                    dumpFailure(context, distroId, command, result)
                }
                return result
            }
        } finally {
            registryLock.withLock { inFlight.remove(key) }
        }
    }

    /** 前置检查 + 组装 argv + 带抢锁重试地执行。 */
    private fun execute(
        context: Context,
        distroId: String,
        command: String,
        timeoutMinutes: Long
    ): Result {
        val prootBin = ProotRunner.ensureBinary(context)
        if (prootBin == null) {
            return Result(-1, "proot 运行时不可用，无法进入容器")
        }
        if (!RootfsManager.isExtracted(context, distroId)) {
            return Result(-1, "rootfs($distroId) 尚未解压")
        }
        val rootfs = RootfsManager.rootfsDir(context, distroId)
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
        // 一次性命令：argv = proot + 选项 + guest shell + 命令参数
        val argv = invocation.argv.dropLast(1) + ProotRunner.buildCommandArgv(
            cwd = ProotRunner.GUEST_HOME,
            command = command
        )
        // envArray 已是「宿主 env + guest env」的并集（见 ProotRunner.buildInvocation），
        // 直接透传给子进程即可，不需要再拼一次。
        return runProcessWithLockRetry(
            argv,
            invocation.envArray,
            maxOf(timeoutMinutes, estimateTimeoutMinutes(command))
        )
    }

    /**
     * 把失败命令的完整输出写到 `filesDir/logs/container-fail-<ts>.log`（保留最近 5 份）。
     *
     * 为什么需要：UI 卡片只能放下十来行，而 apk 的收尾是
     * `1 error: 424 MiB in 77 packages`——真正的错误行在它上面几十行处。
     * 落盘后用户可以在日志页导出完整内容，不必反复截图猜。
     */
    private fun dumpFailure(
        context: Context,
        distroId: String,
        command: String,
        result: Result
    ) {
        runCatching {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            val file = File(dir, "container-fail-${System.currentTimeMillis()}.log")
            file.writeText(
                buildString {
                    append("exit=").append(result.exitCode).append('\n')
                    append("distro=").append(distroId).append('\n')
                    append("command=").append(command).append("\n\n")
                    append(result.output)
                }
            )
            dir.listFiles { f -> f.name.startsWith("container-fail-") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(DIR_KEEP_FILES)
                ?.forEach { it.delete() }
            AgentLogger.w(TAG, "失败输出已保存：${file.absolutePath}")
        }.onFailure { AgentLogger.w(TAG, "保存失败输出出错：${it.message}") }
    }

    /**
     * 按命令估算超时（只放宽已知的大包，不做全局调大）。
     *
     * 15 分钟对 `apk add nodejs npm`（约 40 MB）够用；但 `openjdk17` + `openjdk17-jdk`
     * 在 aarch64 上合计约 200 MB，慢网（200 KB/s）光下载就要十几分钟。
     * 把「还在下」误判成「卡死」比多等一会儿更糟——用户会以为是 bug 去重试，
     * 而重试又要从零开始。
     */
    private fun estimateTimeoutMinutes(command: String): Long = when {
        "openjdk" in command || "jdk" in command -> 30L
        "nodejs" in command -> 20L
        else -> DEFAULT_TIMEOUT_MINUTES
    }

    /**
     * 执行，并在「抢锁」时重试。
     *
     * 判断依据是 apk 的两种表现之二：退出码 99，或输出里出现
     * `Unable to lock database`。只看退出码不够——apt 的锁冲突退出码是 100，
     * 且某些版本会把锁错误包一层包装；同时看文本更稳。
     */
    private fun runProcessWithLockRetry(
        argv: List<String>,
        env: Array<String>,
        timeoutMinutes: Long
    ): Result {
        var attempt = 0
        while (true) {
            val result = runProcess(argv, env, timeoutMinutes)
            val conflict = ToolchainInstaller.isLockConflict(result.exitCode, result.output)
            if (!conflict || attempt >= LOCK_RETRY_DELAYS_MS.size) {
                if (conflict) {
                    AgentLogger.e(
                        TAG,
                        "包管理器仍被占用（重试 ${attempt} 次后放弃）：exit=${result.exitCode}"
                    )
                    return Result(
                        result.exitCode,
                        result.output + "\n[北辰] 包管理器数据库被占用，重试 ${attempt} 次仍失败；" +
                            "可能有上一次安装的进程没退出，稍等一会儿再点一次。"
                    )
                }
                return result
            }
            val delay = LOCK_RETRY_DELAYS_MS[attempt]
            attempt++
            AgentLogger.w(TAG, "包管理器被占用（exit=${result.exitCode}），${delay}ms 后第 $attempt 次重试")
            runCatching { Thread.sleep(delay) }
        }
    }

    /**
     * 起进程、等退出、**并发收输出**。
     *
     * ## 为什么必须并发读（真机实测：装 JDK 卡满 15 分钟，零输出）
     * 旧实现是「先 `waitFor`，再读 inputStream」。这在输出少的命令上看不出问题，
     * 但管道缓冲区只有约 64 KB：`apk add openjdk17 openjdk17-jdk` 的输出（下载 +
     * 逐个解包几百个文件）会写满缓冲区，子进程阻塞在 `write(2)` 上等我们读，
     * 而我们在 `waitFor` 等它退出——**双方互等**，直到 15 分钟超时。
     * 真机日志：
     * ```
     * 14:31:37 容器命令开始：... apk add --no-cache openjdk17 openjdk17-jdk
     * 14:46:37 容器命令结束：exit=-1 耗时=900s      ← 整整 15 分钟，一个字节输出都没有
     * ```
     * Node / Python 输出少（< 64 KB），所以之前"看着正常"——不是没问题，是没撞上。
     *
     * 现在起一条守护线程持续把 stdout 抽干，主线程只管等退出，两边不再互锁。
     *
     * ## 输出策略
     * 只保留**尾部** [MAX_OUTPUT_CHARS]：apk / apt 的失败原因和成功摘要都在最后，
     * 而开头的进度行既没有诊断价值、又会把关键信息挤出窗口。
     */
    private fun runProcess(argv: List<String>, env: Array<String>, timeoutMinutes: Long): Result =
        runCatching {
            val process = ProcessBuilder(argv)
                .apply {
                    environment().clear()
                    environment().putAll(env.associate { it.substringBefore('=') to it.substringAfter('=') })
                    redirectErrorStream(true)
                }
                .start()
            val sink = OutputSink(MAX_OUTPUT_CHARS)
            val reader = Thread({ sink.drain(process.inputStream) }, "container-stdout").apply {
                isDaemon = true
                start()
            }
            val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
            if (!finished) {
                // 先礼后兵：SIGTERM → 宽限 → 抓子树 → SIGKILL（见 terminateTree 的 KDoc）
                terminateTree(process)
                runCatching { reader.join(1_500) }
                val tail = sink.tail(3)
                AgentLogger.e(
                    TAG,
                    "容器命令超时（${timeoutMinutes}min）已终止；最后输出=${sink.lastLine().ifBlank { "（无）" }}"
                )
                return@runCatching Result(
                    -1,
                    buildString {
                        append("命令超时（${timeoutMinutes} 分钟）已终止。")
                        if (tail.isBlank()) {
                            // 零输出是重要信号：说明不是「下载慢」，而是进程根本没跑起来
                            append("\n[北辰] 这段时间内没有任何输出——更像是卡在启动阶段（proot/rootfs），不是网速慢。")
                        } else {
                            append("\n[北辰] 最后输出：\n").append(tail)
                        }
                    }
                )
            }
            // 进程已退出，但管道里可能还剩最后几 KB；给读线程一点时间收尾
            runCatching { reader.join(2_000) }
            Result(process.exitValue(), sink.text())
        }.getOrElse {
            AgentLogger.e(TAG, "容器命令执行失败：${it.message}")
            Result(-1, "执行失败：${it.message}")
        }

    /**
     * 超时终止：SIGTERM → 宽限 → 抓子树 → SIGKILL。
     *
     * ## 为什么不能直接 destroyForcibly()
     *
     * `--kill-on-exit` 在 proot 里是 `atexit` 钩子（符号表可见 `kill_all_tracees` /
     * `__atexit_handler_wrapper`），**只在进程自己走完 exit() 时执行**：
     * - SIGKILL 不可捕获 —— 进程被内核直接摘掉，钩子一次都不会跑；
     * - SIGTERM 只有在 proot 装了处理器并转成 exit() 时才有效，否则同样静默终止。
     *
     * 任一情况下 proot 的 tracee（`apk` / `apt`）都会被内核 detach 后**继续运行**，
     * 继续占着 `/lib/apk/db/lock`，下一次安装直接 exit 99。这正是
     * [runProcessWithLockRetry] 那套抢锁重试在兜的坑——但那是兜底，不是正解。
     *
     * ## 顺序为什么必须是「先抓子树，再 SIGKILL」
     *
     * proot 一死，它的子进程立刻被 reparent 到 init，`/proc` 里的父子关系就断了，
     * 事后无从查找。所以次序是：
     * 1. `destroy()`（SIGTERM）+ 宽限 [TERM_GRACE_MS]；
     * 2. 还活着 → 递归读 `/proc/<pid>/task/<pid>/children`，记下整棵子树；
     * 3. `destroyForcibly()` 杀 proot，再逐个 SIGKILL 记下的 pid。
     *
     * 全程 best-effort：任一环节失败（拿不到 pid / SELinux 挡住 /proc）只记日志，
     * 不抛异常——超时路径本身已在失败处理里，再抛就把「超时」变成「崩溃」了。
     */
    private fun terminateTree(process: Process) {
        val pid = pidOf(process)
        process.destroy()
        if (process.waitFor(TERM_GRACE_MS, TimeUnit.MILLISECONDS)) {
            AgentLogger.i(TAG, "超时进程已由 SIGTERM 正常收尾（pid=${pid ?: "?"}）")
            return
        }
        // 必须在 destroyForcibly 之前抓：proot 死后子树就 reparent 到 init 了
        val tree = pid?.let { collectDescendants(it) }.orEmpty()
        process.destroyForcibly()
        if (tree.isEmpty()) {
            AgentLogger.w(
                TAG,
                "超时进程 SIGTERM 无效，已强杀 proot（pid=${pid ?: "?"}，未取到子树）"
            )
            return
        }
        var killed = 0
        tree.forEach { child ->
            if (runCatching { Os.kill(child, OsConstants.SIGKILL) }.isSuccess) killed++
        }
        AgentLogger.w(
            TAG,
            "超时进程 SIGTERM 无效：已强杀 proot（pid=$pid）及子树 $killed/${tree.size} 个进程"
        )
    }

    /**
     * 取进程 pid。
     *
     * API 26~32 没有公开的 `Process.pid()`（那是 Java 9 API，Android 较新版本才有），
     * 只能反射 libcore `ProcessImpl` 的私有字段 `pid`。取不到返回 null，调用方跳过子树
     * 清理——宁可少杀，也不要因为拿不到 pid 把超时变成崩溃。
     */
    private fun pidOf(process: Process): Int? = runCatching {
        val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }
        field.getInt(process)
    }.getOrNull()

    /**
     * 递归收集 [rootPid] 的全部后代 pid（best-effort，最多 [MAX_TREE_DEPTH] 层）。
     *
     * 数据源是 `/proc/<pid>/task/<pid>/children`（内核 3.5+ 提供，Android 全版本可用，
     * 空格分隔）。读不到（SELinux、内核裁剪、进程已消失）就返回已收集到的部分——
     * 少杀的那几个由 [runProcessWithLockRetry] 的抢锁重试兜底。
     *
     * 用 `LinkedHashSet` 而不是 List：proot 多线程 fork 时同一 pid 可能被父子两路各列
     * 一次，去重顺便防环。
     */
    private fun collectDescendants(rootPid: Int, maxDepth: Int = MAX_TREE_DEPTH): List<Int> {
        val out = LinkedHashSet<Int>()
        fun walk(pid: Int, depth: Int) {
            if (depth > maxDepth) return
            val kids = runCatching {
                File("/proc/$pid/task/$pid/children").readText()
                    .trim()
                    .split(' ')
                    .filter { it.isNotEmpty() }
                    .map { it.toInt() }
            }.getOrDefault(emptyList())
            for (kid in kids) {
                if (kid <= 0 || !out.add(kid)) continue
                walk(kid, depth + 1)
            }
        }
        walk(rootPid, 1)
        return out.toList()
    }

    /**
     * 边跑边收的输出池（线程安全，只保留尾部）。
     *
     * 顺带记录「最后一个非空行」：超时时间（15/30 分钟）里用户只看到一个转圈，
     * 日志里一行都没有——把最后一行写进日志，下次就能一眼看出是卡在
     * `fetch ...`（网络）、`(1/12) Installing ...`（解包）还是压根没输出（启动失败）。
     */
    private class OutputSink(private val limit: Int) {
        private val buf = StringBuilder()
        private val lock = Any()
        private var current = StringBuilder()
        private var lastLine = ""

        /** 在专属线程里跑：把流读干直到 EOF。 */
        fun drain(stream: java.io.InputStream) {
            runCatching {
                stream.reader(Charsets.UTF_8).use { r ->
                    val chunk = CharArray(4_096)
                    while (true) {
                        val n = r.read(chunk)
                        if (n <= 0) break
                        feed(chunk, n)
                    }
                }
            }
        }

        private fun feed(chunk: CharArray, n: Int) {
            synchronized(lock) {
                buf.append(chunk, 0, n)
                if (buf.length > limit) buf.delete(0, buf.length - limit)
                for (i in 0 until n) {
                    val c = chunk[i]
                    if (c == '\n' || c == '\r') {
                        val line = current.toString().trim()
                        if (line.isNotEmpty()) lastLine = line.take(200)
                        current = StringBuilder()
                    } else if (current.length < 400) {
                        current.append(c)
                    }
                }
            }
        }

        fun text(): String = synchronized(lock) { buf.toString() }

        /** 最后 [n] 个非空行（失败诊断用；错误几乎都在尾部）。 */
        fun tail(n: Int): String = text().trim().lines().filter { it.isNotBlank() }.takeLast(n).joinToString("\n")

        fun lastLine(): String = synchronized(lock) { lastLine }
    }
}
