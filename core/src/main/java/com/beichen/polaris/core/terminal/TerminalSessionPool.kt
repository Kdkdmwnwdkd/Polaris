package com.beichen.polaris.core.terminal

import com.beichen.polaris.core.AppScope
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.terminal.backend.TerminalBackend
import com.beichen.polaris.core.terminal.backend.TerminalBackendFactory
import com.beichen.polaris.core.terminal.backend.TerminalBackendSource
import com.beichen.polaris.core.terminal.backend.TerminalIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * 会话对外句柄的格式约束：**恒 4 位十六进制小写**（`^[0-9a-f]{4}$`）。
 *
 * 用短 ID 对外、内部真身（后端实例）不外露，目的是**省 token**（模型每次传 4 字符而非 UUID）
 * 与**防泄露内部结构**。公开为顶层常量，供工具层做「句柄格式」前置校验，避免拿非法字符串
 * 直接查表。
 */
val TERMINAL_HANDLE_REGEX = Regex("^[0-9a-f]{4}$")

/**
 * 会话开辟结果（T08）。
 *
 * @param ok 是否成功
 * @param handle 成功时的对外句柄（[TERMINAL_HANDLE_REGEX]）；失败为 null
 * @param identity 该会话**实际**执行身份（R-12：取后端声明值，不是请求值）。
 *   请求是下限，所以它可能高于请求——`identity=user` 落到 Shizuku 通道时这里是
 *   `Shizuku`（uid 2000）。上层报告权限时必须用它，用请求值会说假话。
 * @param backendKey 会话所属池（`user` / `proot`），用于分池
 * @param alias 会话别名（同池内唯一）
 * @param reused true 表示复用了同池同别名的既有存活会话（未新建后端）
 * @param errorCode 失败时的机器可读码（`no_backend` / `identity_unsupported` /
 *   `identity_conflict` / `start_failed`）
 * @param message 失败主文本
 * @param hint 失败修复指引
 */
data class OpenResult(
    val ok: Boolean,
    val handle: String? = null,
    val identity: TerminalIdentity = TerminalIdentity.User,
    val backendKey: String? = null,
    val alias: String? = null,
    val reused: Boolean = false,
    val errorCode: String? = null,
    val message: String = "",
    val hint: String? = null
) {
    companion object {
        /** 成功工厂（[reused] 区分新建 / 复用）。 */
        fun ok(
            handle: String,
            identity: TerminalIdentity,
            backendKey: String,
            alias: String,
            reused: Boolean
        ): OpenResult = OpenResult(
            ok = true,
            handle = handle,
            identity = identity,
            backendKey = backendKey,
            alias = alias,
            reused = reused
        )

        /** 失败工厂。 */
        fun failure(code: String, message: String, hint: String): OpenResult =
            OpenResult(ok = false, errorCode = code, message = message, hint = hint)
    }
}

/**
 * 单条命令的执行结果（T08）。
 *
 * @param ok 是否执行成功（后端送达 + 拿到哨兵 + 未超时）
 * @param exitCode 命令退出码（池层错误时为 -1）
 * @param output 命令输出（**已经过 [ToolOutputTruncator] 截断**）
 * @param truncated 输出是否被截断
 * @param errorCode 池层错误码（`no_such_session` / `identity_conflict` / `session_dead` /
 *   `empty_command` / `send_failed` / `timeout`）
 * @param message 失败主文本
 * @param hint 失败修复指引
 */
data class ExecResult(
    val ok: Boolean,
    val exitCode: Int = -1,
    val output: String = "",
    val truncated: Boolean = false,
    val errorCode: String? = null,
    val message: String = "",
    val hint: String? = null
) {
    companion object {
        /** 成功工厂。 */
        fun ok(exitCode: Int, output: String, truncated: Boolean): ExecResult =
            ExecResult(ok = true, exitCode = exitCode, output = output, truncated = truncated)

        /** 失败工厂（池层错误，未跑通命令）。 */
        fun failure(code: String, message: String, hint: String): ExecResult =
            ExecResult(ok = false, exitCode = -1, errorCode = code, message = message, hint = hint)
    }
}

/**
 * 会话只读快照（供 list / 诊断 / harness 断言；不含后端实例，避免外泄内部真身）。
 */
data class SessionSnapshot(
    val handle: String,
    val identity: TerminalIdentity,
    val backendKey: String,
    val alias: String,
    val alive: Boolean
)

/**
 * 终端会话池（T08）。
 *
 * ## 职责
 * 在 [TerminalBackend]（T07）之上维护「**具名会话表**」，对上层（shell 工具）暴露：
 *  - **4 位十六进制短句柄**（[TERMINAL_HANDLE_REGEX]），内部真身不外露；
 *  - **每会话一把 [Mutex]**：同会话命令**串行**（防两条命令输出交织串味），
 *    跨会话互不阻塞（各持各的锁，可并发）；
 *  - **身份开辟时固化**：写入会话元数据，后续传**冲突 identity 显式报错**，绝不静默复用；
 *  - **user / proot 分池**：别名按（backendKey, alias）分键，两种后端各占独立槽位，不串味；
 *  - **僵尸会话自动释放别名并可重开**：别名槽位上的后端一旦不存活即回收，下次同名可新建。
 *
 * ## 完成判定（哨兵协议）
 * 后端 [TerminalBackend.output] 是原始文本流，无「命令边界」。本池在写完命令后追加一行
 * `echo "<哨兵>"$?`，并扫描输出流直到匹配哨兵（其后紧跟数字退出码），据此切出「本次命令
 * 的真实输出」。哨兵 = [SENTINEL_MARKER] + 每次随机的 token + `__`，避免与命令自身输出误撞；
 * 匹配时要求哨兵后紧跟 `数字`（PTY 回显的 `echo "...$?"` 之后是 `$`，不会误判）。
 *
 * ## 零 Android 依赖
 * 只依赖注入的 [TerminalBackendSource]（Android 侧真身 = [TerminalBackendFactory]，
 * JVM harness 侧 = 假 source），故本类可在离线 JVM 直接验证全部语义。
 *
 * ## 已知取舍
 * - 多行命令（含换行）以**整体一次写入**下发，哨兵只反映最后一条语句的退出码；
 * - PTY 回显：容器（proot）走伪终端会回显命令与 `echo` 行，本池做**尽力清洗**（见
 *   [cleanOutput]），无 PTY 的本机 user 后端输出天然干净；
 * - **超时上限按调用覆盖**：[run] 的 `timeoutMs` 优先于 [commandTimeoutMs]。
 *   池默认值只是「没有更好信息时」的兜底，不是命令种类的判据——同一个 30 秒对
 *   `ls` 太长、对 `gradle build` 太短，只有调用方（shell 工具）知道自己在跑什么。
 * - 超时后本次命令判失败，但**不杀后端**（命令可能仍在跑，由调用方决定
 *   是否 [close] 会话）；会话同时被标记不可复用（见 `session_desynced`）。
 *
 * @param source 后端来源（每次 [open] 向其取**新实例**：一后端 = 一条独立 shell 进程）
 * @param truncator 输出截断器；默认口径与工具结果一致（24000）
 * @param commandTimeoutMs 单条命令完成等待上限（毫秒）
 * @param handleGenerator 句柄生成器（默认随机 4-hex，可注入以在测试中构造碰撞）
 */
class TerminalSessionPool(
    private val source: TerminalBackendSource,
    private val truncator: ToolOutputTruncator = ToolOutputTruncator(),
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    private val handleGenerator: () -> String = { randomHex4() }
) {

    /** 收集输出用的协程作用域（读流并发于「写命令」，故需独立调度）。 */
    private val scope = CoroutineScope(SupervisorJob() + AppScope.io)

    private val tokenRandom = Random(System.nanoTime())

    /** 句柄 → 会话真身。 */
    private val byHandle = LinkedHashMap<String, Entry>()

    /** （backendKey, alias）→ 会话真身，用于分池 + 别名唯一性。 */
    private val byAlias = LinkedHashMap<String, Entry>()

    /** 会话表互斥锁（仅保护上面两张表的读写，临界区极短）。 */
    private val lock = Any()

    /**
     * 槽位门锁：（backendKey, alias）→ 开辟互斥。
     *
     * 与 [lock] 的分工是「临界区长度」：本表只在 [open] 的整档流程（含后端启动，
     * 最长 [BACKEND_START_TIMEOUT_MS]）里持有，而 [lock] 只包住几行 HashMap 读写。
     * 混成一把锁的后果是——启动一个卡死的后端时，连 `lookup` / `list` 都跟着卡住。
     */
    private val openGates = HashMap<String, Mutex>()

    /**
     * 开辟（或复用）一条会话。
     *
     * 语义：
     *  1. 经 [source] 取后端（`identity` 未实现则报 `identity_unsupported`；无可用后端报 `no_backend`）；
     *  2. 解析池键 =（后端键, 别名[默认按后端键推导]）；
     *  3. 该槽位已有**存活**会话：身份一致 → **复用**（返回既有句柄）；身份冲突 → **显式报错**；
     *     已有**僵尸**会话 → 回收别名后按新建走。
     *
     * @param identity **权限下限**（R-12）：候选后端须满足它，会话固化的是后端的实际身份。
     *   传 `user` 时设备若有 uid 2000 通道，拿到的会话实际身份就是 uid 2000。
     * @param alias 别名；null = 按后端键取默认别名（[TerminalBackendFactory.defaultAliasFor]）
     * @param preferProot true = 优先 proot 容器，不可用回落本机 user
     * @param backendKey W-RT5：显式指定后端键（如 [TerminalBackend.KEY_SSH]）。非 null 时
     *   **不走身份降级链**，直接经 [TerminalBackendSource.obtainByKey] 取该后端——远端机器
     *   绝不能在本机 shell 起不来时悄悄顶替。此时 [identity] 仅作会话元数据固化。
     * @return [OpenResult]
     */
    suspend fun open(
        identity: TerminalIdentity = TerminalIdentity.User,
        alias: String? = null,
        preferProot: Boolean = true,
        backendKey: String? = null
    ): OpenResult {
        // 1) 取候选：显式后端键 > 身份降级链（W-RT5）
        val candidates = if (backendKey != null) {
            listOfNotNull(source.obtainByKey(backendKey))
        } else {
            source.obtainCandidates(identity, preferProot)
        }
        if (candidates.isEmpty()) {
            // W-RT5：显式路由失败要说「这个后端不可用」，而不是「没有可用的终端后端」——
            // 后者会让用户去修本机 shell，而真正缺的是 SSH 档案或 ssh 命令。
            if (backendKey != null) {
                return OpenResult.failure(
                    code = "no_backend",
                    message = "显式指定的后端「$backendKey」当前不可用。",
                    hint = if (backendKey == TerminalBackend.KEY_SSH) {
                        "SSH 后端不可用。依次确认：① 权限中心 → SSH 已填好主机 / 端口 / 用户名并保存；" +
                            "② 目标机器已把本设备的公钥加进 authorized_keys（仅支持密钥登录，不支持密码）；" +
                            "③ 设备上有可用的 ssh 命令（通常由容器工具链安装 openssh 提供）。"
                    } else {
                        "确认后端已正确配置，或改用默认参数（自动降级链）。"
                    }
                )
            }
            // R-12：这里不再按身份写特例分支，而是按「有没有能提供这么高权限的后端」说话。
            // 修前是 `if (identity == User) 无后端 else 身份不支持`——于是 su / ssh 的失败
            // 被归成「身份不存在」，而真相是「本机没有任何以 uid 0 / 远端身份执行的后端」。
            // 两者的修复动作完全不同：前者是「换个值」，后者是「装一个东西」。
            return if (identity == TerminalIdentity.User) {
                OpenResult.failure(
                    code = "no_backend",
                    message = "当前环境下没有可用的终端后端（proot / 无线调试都不可用，本机 shell 也起不来）。",
                    hint = "确认设备可执行 /system/bin/sh；若期望容器请先解压 rootfs；若期望 shell 权限请先配对无线调试。"
                )
            } else {
                OpenResult.failure(
                    code = "identity_unsupported",
                    message = "没有以「${identity.describe()}」执行的后端，" +
                        "请求的权限下限是 ${identity.describe()}（identity=${identity.wire}）。",
                    // W-58：原本文案说「当前仅支持 identity=user」——在 W-56 把 Shizuku 做成后端
                    // 之后这句话已经半错：uid 2000 是能拿到的，只是入口不是 identity，而是
                    // prefer_proot=false 走候选链。传 identity=shizuku 进来的实际情况是
                    // 「一次性通道没起来」，所以要把两条路都指清楚。
                    hint = if (identity == TerminalIdentity.Shizuku) {
                        "identity=shizuku 走的是**一次性提权通道**，需要 Shizuku 服务在运行且已授权本应用。" +
                            "要 uid 2000 **并且** cwd 保持，改用 prefer_proot=false（会话池会依次试 " +
                            "Shizuku → 无线调试）；两者状态可到「设置 → 调试通道」查看。"
                    } else if (identity == TerminalIdentity.Ssh) {
                        // W-RT5：ssh 已有后端，但它**不在降级链里**——走到这里说明调用方只传了
                        // identity 没传 backendKey。要指路，不能说「没有这条通道」。
                        "identity=ssh 必须显式路由：会话池不会把远端机器放进降级链。" +
                            "请确认调用方传了 backendKey=${TerminalBackend.KEY_SSH}；" +
                            "若 SSH 本身不可用，先到「权限中心 → SSH 远程机器」检查连接配置与 ssh 命令。"
                    } else {
                        "su 首期无后端（本应用不提供 root 通道）。要容器用 prefer_proot=true（默认）；" +
                            "要 uid 2000 用 prefer_proot=false（Shizuku / 无线调试）；普通文件操作用默认参数即可。"
                    }
                )
            }
        }

        // 2) 按候选顺序：先查该槽位有无存活会话可复用，再试启动；起不来的**继续试下一档**
        //
        // 改动前这里是「obtain 选一个 → start，失败就报 start_failed」：一旦首选后端
        // 因环境变化起不来（rootfs 被删、配对失效、adbd 回收），用户看到的是失败，
        // 而不是回落到仍然可用的本机 shell。
        //
        // ★ 槽位门锁（A-10）：同一 (backendKey, alias) 的「查 → 启 → 登记」必须整体互斥。
        //
        // 修前只有 `synchronized(lock)` 保护**读表**，后端启动发生在锁外，登记又在另一段
        // 临界区里。于是两个协程同时 open 同一别名时：两边都读到 null → 都 start 出一个
        // 后端 → 先后登记，后登记的**覆盖**了 byAlias 里前一条。前一个后端就此失去句柄，
        // 变成没人能关掉的孤儿 shell（进程泄漏，且它的输出收集协程还在跑）。
        // 这在「模型一批并发调两个 shell_run」时就会发生。
        //
        // 门锁按槽位划分而不是全局一把：不同别名/不同后端之间仍可并发开辟，
        // 一把全局锁会让一个卡死的 proot 启动拖住整台设备上所有会话。
        val tried = ArrayList<String>(candidates.size)
        for (candidate in candidates) {
            val backendKey = candidate.backendKey
            val effectiveAlias = normalizeAlias(
                alias ?: TerminalBackendFactory.defaultAliasFor(backendKey)
            )
            val gate = openGate(poolKey(backendKey, effectiveAlias))
            // 返回 null = 这一档起不来（已 close 并记日志），继续试下一档；
            // 非 null = 最终结论（复用 / 冲突 / 成功），直接返回。
            val outcome = gate.withLock { tryCandidate(candidate, identity, effectiveAlias) }
            if (outcome != null) return outcome
            tried += backendKey
        }

        // 全部候选都起不来：列出试过哪些，不笼统说「失败」
        return OpenResult.failure(
            code = "start_failed",
            message = "所有终端后端都启动失败（identity=${identity.wire}，已尝试：${tried.joinToString(" → ")}）。",
            hint = "逐个确认：proot 的 rootfs / proot 三件套是否就绪；无线调试是否仍配对（端口会变）；" +
                "本机 shell 是否可执行。通道状态可到「设置 → 调试通道」查看。"
        )
    }

    /**
     * 在指定会话里执行一条命令（同会话串行）。
     *
     * @param handle 会话句柄（[TERMINAL_HANDLE_REGEX]）
     * @param command 命令原文（会 trim 掉结尾换行后单行写入；含内部换行则整体一次下发）
     * @param identity 可选的**权限下限**；非 null 时会话实际身份满足不了它 → 显式报错
     *   （R-12：判据是 [TerminalIdentity.satisfies]，不是相等——见下面的分支注释）
     * @param timeoutMs 本次命令的完成等待上限（毫秒）；null = 用池的
     *   [commandTimeoutMs]。**必须允许调用方按命令覆盖**——`ls` 和 `gradle build`
     *   共用一个 30 秒上限，等于后者永远跑不完（见下面的 KDoc「已知取舍」）。
     * @return [ExecResult]（输出已截断）
     */
    suspend fun run(
        handle: String,
        command: String,
        identity: TerminalIdentity? = null,
        timeoutMs: Long? = null
    ): ExecResult {
        val entry = synchronized(lock) { byHandle[handle] }
            ?: return ExecResult.failure(
                code = "no_such_session",
                message = "会话句柄「$handle」不存在（可能已被关闭或回收）。",
                hint = "用无 session 参数调用一次以开辟新会话，或先确认句柄是否为 4 位十六进制。"
            )

        // R-12：语义从「声明身份必须与会话相同」改为「会话实际身份须满足本次下限」。
        // 同样的道理在 open() 的复用分支里——两处判据必须是同一个谓词，否则
        // 「能复用的会话」和「能执行的会话」会给出不一致的答案。
        if (identity != null && !entry.identity.satisfies(identity)) {
            return ExecResult.failure(
                code = "identity_conflict",
                message = "会话「$handle」以「${entry.identity.describe()}」执行，" +
                    "满足不了本次请求的下限「${identity.describe()}」。",
                hint = "去掉 identity 参数（沿用会话身份），或以不低于「${identity.wire}」的身份重新开辟会话。"
            )
        }
        if (command.isBlank()) {
            return ExecResult.failure(
                code = "empty_command",
                message = "命令为空。",
                hint = "提供非空的 command，例如 `pwd` 或 `ls -la`。"
            )
        }

        // 每会话一把锁：同会话串行，跨会话并行
        return entry.mutex.withLock {
            if (!entry.alive) {
                releaseEntry(entry)
                return@withLock ExecResult.failure(
                    code = "session_dead",
                    message = "会话「$handle」已退出（僵尸会话已释放别名）。",
                    hint = "用无 session 参数重新调用以开辟新会话。"
                )
            }
            if (entry.desynced) {
                return@withLock ExecResult.failure(
                    code = "session_desynced",
                    message = "会话「$handle」上一条命令超时并被中断，输出边界已不可信，不能复用。",
                    hint = "先 shell_close 该句柄，再用无 session 参数开辟新会话后重试。"
                )
            }
            execute(entry, command, timeoutMs)
        }
    }

    /**
     * 「查既有槽位 → 复用 / 冲突 / 清僵尸 → 启动 → 登记」一档的全部动作。
     *
     * 调用方（[open]）必须持有该槽位的门锁 [openGate]——这四步放在一起才有意义：
     * 中间任何一步出锁，都给了另一个协程「读到同样的旧状态」的机会。
     *
     * @return null = 本档启动失败（后端已 close，继续试下一档）；非 null = 最终结论。
     */
    private suspend fun tryCandidate(
        candidate: TerminalBackend,
        identity: TerminalIdentity,
        effectiveAlias: String
    ): OpenResult? {
        val backendKey = candidate.backendKey
        val key = poolKey(backendKey, effectiveAlias)

        val existing = synchronized(lock) { byAlias[key] }
        if (existing != null) {
            if (existing.alive) {
                // R-12：判据从「身份相等」改成「会话实际身份能否满足本次请求的下限」。
                // 相等太严：`identity=user` 撞上 uid 2000 的会话会报冲突，而那个会话
                // 完全够用（权限更高）——用户被迫关掉重开，cwd 也丢了。
                // 相等也太松（反向）：请求 su 撞上 user 会话时，相等判定会放行，
                // 于是「用户以为提权了，命令其实仍以旧身份跑」——正是注释里要防的那件事。
                if (!existing.identity.satisfies(identity)) {
                    // 权限不足：绝不静默复用
                    runCatching { candidate.close() }
                    return OpenResult.failure(
                        code = "identity_conflict",
                        message = "别名「$effectiveAlias」上已有会话以「${existing.identity.describe()}」执行，" +
                            "满足不了本次请求的下限「${identity.describe()}」（identity=${identity.wire}）。",
                        hint = "改用不高于「${existing.identity.wire}」的身份，或先 " +
                            "shell_close(handle=${existing.handle}) 关闭旧会话再以新身份开辟。"
                    )
                }
                // 复用：丢弃刚建的多余后端，返回既有句柄
                runCatching { candidate.close() }
                return OpenResult.ok(
                    existing.handle, existing.identity, existing.backendKey, existing.alias, reused = true
                )
            }
            // 僵尸：释放别名 + 回收资源
            releaseEntry(existing)
            runCatching { existing.backend.close() }
        }

        // W-59：start 加超时。原本是裸调 `runCatching { candidate.start() }`——
        // Shizuku 的 `newProcess` 走 binder，binder 卡住时**不会**抛异常也不会返回，
        // 整条工具调用就挂在那里（用户看到的是「点了没反应」，而不是「启动失败」）。
        // 超时后照常 close + 回落下一档，语义与「启动失败」一致。
        var startTimedOut = false
        val started = withTimeoutOrNull(BACKEND_START_TIMEOUT_MS) {
            runCatching { candidate.start() }.getOrDefault(false)
        } ?: run {
            startTimedOut = true
            false
        }
        if (!started) {
            runCatching { candidate.close() }
            AgentLogger.w(
                TAG,
                (if (startTimedOut) "后端启动超时（>${BACKEND_START_TIMEOUT_MS}ms），" else "后端启动失败，") +
                    "回落到下一档：$backendKey（identity=${identity.wire}）"
            )
            return null
        }

        val handle = allocateHandle()
        // R-12：固化后端的**实际**身份，不是请求值。请求只是下限，
        // 记请求值会让后续所有报告与冲突判定都建立在假话上。
        val entry = Entry(handle, candidate.identity, backendKey, effectiveAlias, candidate)
        synchronized(lock) {
            byHandle[handle] = entry
            byAlias[key] = entry
        }
        AgentLogger.i(
            TAG,
            "会话已开辟 handle=$handle identity=${entry.identity.wire}（请求 ${identity.wire}）" +
                " pool=$backendKey alias=$effectiveAlias"
        )
        return OpenResult.ok(handle, entry.identity, backendKey, effectiveAlias, reused = false)
    }

    /**
     * 取某个槽位的门锁（首次用到时创建）。
     *
     * 条目**不回收**：数量上限就是「后端种类 × 别名数」，最多几十个 Mutex，
     * 而回收会带来一个更难查的问题——两个协程可能各自拿到新旧两把锁，
     * 于是门锁形同虚设。宁可留下几十个空对象。
     */
    private fun openGate(key: String): Mutex = synchronized(lock) {
        openGates.getOrPut(key) { Mutex() }
    }

    /** 关闭指定会话（幂等）：回收后端 + 释放别名与句柄。 */
    suspend fun close(handle: String): Boolean {
        val entry = synchronized(lock) { byHandle[handle] } ?: return false
        releaseEntry(entry)
        runCatching { entry.backend.close() }
        AgentLogger.i(TAG, "会话已关闭 handle=$handle")
        return true
    }

    /** 关闭全部会话（App 退后台 / 进程收尾时调用）。 */
    suspend fun closeAll() {
        val all = synchronized(lock) {
            val snapshot = byHandle.values.toList()
            byHandle.clear()
            byAlias.clear()
            snapshot
        }
        all.forEach {
            it.closed = true
            runCatching { it.backend.close() }
        }
        AgentLogger.i(TAG, "已关闭全部会话（${all.size} 条）")
    }

    /** 取消收集协程（进程收尾；不影响已关闭的后端）。 */
    fun shutdown() {
        scope.cancel()
    }

    /**
     * 非挂起 **best-effort 全量释放**：供**同步生命周期钩子**（如 `ViewModel.onCleared()`）调用。
     *
     * ## 与挂起版 [closeAll] 的差异（最小惊讶裁决）
     * [closeAll] 必须在协程里 `await` 每个后端关闭（[TerminalBackend.close] 是挂起操作），
     * 无法用于 `onCleared()` 这类**主线程同步钩子**（在那儿 `runBlocking` 会卡主线程，且
     * 关闭子进程可能耗时）。本方法因此把「逻辑释放」与「物理关闭」拆开：
     *  1. **同步**完成会话表逻辑释放：清空两张表 + 标记 closed → 调用方一返回，旧句柄立即失效
     *     （[lookup]/[list] 不再可见、[run] 报 `no_such_session`），杜绝「钩子返回后仍有会话可达」；
     *  2. **异步**关闭后端进程：后端 [close] 是挂起操作，交给池自有作用域**之外**的短命 IO 协程完成；
     *  3. 最后 [shutdown] 取消输出收集协程。
     *
     * ## 为什么是「best-effort」
     * 关闭是异步的，理论上进程回收可能「稍后」完成；但句柄已不可达，且本池是 ViewModel 级实例
     * （下次进入会新建池），故不会累积复用——对「防泄漏」这一目标足够。
     *
     * ## 异常安全
     * 池为空、后端 [close] 抛错都不外抛（`runCatching` + 提前分支），钩子可安全直调。
     */
    fun releaseAllAsync() {
        val all = synchronized(lock) {
            val snapshot = byHandle.values.toList()
            byHandle.clear()
            byAlias.clear()
            snapshot
        }
        all.forEach { it.closed = true }
        if (all.isNotEmpty()) {
            // 独立作用域：不与即将被 shutdown() 取消的 [scope] 竞争，保证关闭协程能跑完。
            AppScope.app.launch {
                all.forEach { runCatching { it.backend.close() } }
                AgentLogger.i(TAG, "已异步释放全部会话（${all.size} 条）")
            }
        }
        scope.cancel()
    }

    /** 会话快照列表（诊断 / 断言用）。 */
    fun list(): List<SessionSnapshot> = synchronized(lock) { byHandle.values.map { it.snapshot() } }

    /** 按句柄取快照；不存在返回 null。 */
    fun lookup(handle: String): SessionSnapshot? =
        synchronized(lock) { byHandle[handle]?.snapshot() }

    /* ------------------------------------------------------------------ */
    /* 内部实现                                                            */
    /* ------------------------------------------------------------------ */

    /** 执行一条命令：起收集协程 → 写命令 + 哨兵 → 等完成 → 切输出 → 截断。 */
    private suspend fun execute(entry: Entry, command: String, timeoutMs: Long? = null): ExecResult {
        // 本次的完成等待上限：调用方给了就用它，否则退回池默认值。
        // 所有「报给用户 / 报给模型」的文案都必须用这个实际值——
        // 固定在文案里写 commandTimeoutMs 会让「20 分钟预算」看起来没生效。
        val limit = timeoutMs?.coerceAtLeast(1L) ?: commandTimeoutMs
        val token = newToken()
        val sentinelPrefix = SENTINEL_MARKER + token + "__"
        // 哨兵必须「其后紧跟数字退出码」才算命中（PTY 回显的 echo "...$?" 之后是 '$'，不误判）
        val sentinelRegex = Regex(Regex.escape(sentinelPrefix) + "([0-9]+)")
        val echoLine = "echo \"$sentinelPrefix\$?\""

        val builder = StringBuilder()
        val done = CompletableDeferred<Int>()
        // W-46：缓冲上限——截断必须在**收流过程中**做，不能等命令结束再截。
        // 旧实现把全部输出堆进 StringBuilder 再一次性截断：`cat` 一个大文件就是几百 MB
        // 堆内存，直接 OOM（进程被杀，日志里只留一句「运行失败」）。
        // 保留**尾部**：哨兵在输出末尾，丢头不丢尾才能继续判完成。
        var droppedChars = 0L

        val collector = scope.launch {
            runCatching {
                entry.backend.output.collect { chunk ->
                    builder.append(chunk)
                    if (builder.length > MAX_BUFFER_CHARS) {
                        val cut = builder.length - MAX_BUFFER_CHARS
                        builder.delete(0, cut)
                        droppedChars += cut
                    }
                    if (!done.isCompleted) {
                        val m = sentinelRegex.find(builder)
                        if (m != null) {
                            done.complete(m.groupValues[1].toIntOrNull() ?: -1)
                        }
                    }
                }
            }
        }

        val sentOk = runCatching {
            entry.backend.send(command.trimEnd('\n', '\r') + "\n") &&
                entry.backend.send(echoLine + "\n")
        }.onFailure { if (it is CancellationException) throw it }
            // send 是挂起点：会话被取消时要传播取消，不能落进下面的 send_failed
            //（那会让调用方以为「写不进去」，而不是「这次执行被取消了」）
            .getOrDefault(false)

        if (!sentOk) {
            collector.cancelAndJoin()
            if (!entry.alive) releaseEntry(entry)
            return ExecResult.failure(
                code = "send_failed",
                message = "命令写入会话「${entry.handle}」失败（会话可能已退出）。",
                hint = "重新开辟会话后重试；持续失败请检查后端存活状态。"
            )
        }

        val exitCode = withTimeoutOrNull(limit) { done.await() }
        collector.cancelAndJoin()

        if (exitCode == null) {
            // W-46：超时不能只是「本次失败」——旧实现把命令丢在会话里继续跑，下一个命令
            // 排到它后面执行，哨兵匹配到旧退出码，**静默返回错位结果**。
            //
            // 处置分两步：先 Ctrl-C 尽力中断（PTY 上等于用户按停止）；300ms 后仍活着，
            // 说明这条命令不响应 SIGINT（`trap '' INT`、ffmpeg、python 紧循环）。
            // 此时不能只标脏了事——标脏只是「不再复用这条会话」，进程照样占着 CPU、
            // 文件锁和内存，而会话已经从池里摘除，再没人会管它。
            // 直接关后端：proot 走 killProcessGroup，本机走 destroy（+ SIGKILL 兜底）。
            runCatching { entry.backend.send("\u0003") }
            delay(300)
            runCatching { entry.backend.close() }
            releaseEntry(entry)
            return ExecResult.failure(
                code = "timeout",
                message = "命令在 ${limit}ms 内未完成（会话「${entry.handle}」），" +
                    "已中断并回收该会话。",
                hint = "把命令拆小、补非交互参数（如 -y），或改装 shell_background 跑长命令；" +
                    "下次调用不要带 session，会重新开辟。"
            )
        }

        val raw = builder.toString()
        // 用「其后紧跟数字」的正则定位真实哨兵（PTY 回显的 echo 行其后是 `$`，不会被误当边界）
        val mark = sentinelRegex.find(raw)
        val body = if (mark != null) raw.substring(0, mark.range.first) else raw
        val cleaned = cleanOutput(body, command, echoLine)
        // 溢出说明放在最前面：模型先看到「前面被丢了」，才不会把尾部当成完整输出
        val withNote = if (droppedChars > 0) {
            "…（输出过长，已丢弃前面 $droppedChars 字符，以下为末尾部分）\n$cleaned"
        } else {
            cleaned
        }
        val truncated = truncator.truncate(withNote)
        return ExecResult.ok(
            exitCode = exitCode,
            output = truncated.text,
            truncated = truncated.truncated || droppedChars > 0
        )
    }

    /** 释放一条会话（从两张表移除 + 标记关闭）；调用方负责关闭后端。 */
    private fun releaseEntry(entry: Entry) {
        synchronized(lock) {
            entry.closed = true
            byHandle.remove(entry.handle)
            byAlias.remove(poolKey(entry.backendKey, entry.alias))
        }
    }

    /**
     * 分配 4-hex 句柄：先多次随机重试，反复碰撞则**按序补位**（从 0x0000 起找首个空闲值）。
     * 句柄空间 65536，越界即抛（远超实际会话数）。
     */
    private fun allocateHandle(): String = synchronized(lock) {
        repeat(HANDLE_ALLOC_RETRIES) {
            val candidate = handleGenerator()
            if (candidate.matches(TERMINAL_HANDLE_REGEX) && candidate !in byHandle) return candidate
        }
        for (i in 0..0xFFFF) {
            val candidate = i.toString(16).padStart(4, '0')
            if (candidate !in byHandle) return candidate
        }
        error("4 位十六进制句柄空间已耗尽（上限 65536 个会话）")
    }

    /** 每次随机 16 位十六进制 token（哨兵去重）；不做安全用途，普通随机即可。 */
    private fun newToken(): String {
        val hex = HEX_CHARS
        return buildString(16) { repeat(16) { append(hex[tokenRandom.nextInt(hex.length)]) } }
    }

    /** 尽力清洗 PTY 回显：去掉与「本命令 / echo 命令」逐字相同的回显行，统一换行。 */
    private fun cleanOutput(body: String, command: String, echoLine: String): String {
        val cmdTrim = command.trim()
        val echoTrim = echoLine.trim()
        val sb = StringBuilder(body.length)
        for (line in body.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val t = line.trim()
            if (t.isEmpty()) {
                sb.append('\n')
                continue
            }
            if (t == cmdTrim || t == echoTrim) continue // 终端回显的命令行
            if (t.startsWith("echo \"") && t.contains(SENTINEL_MARKER)) continue // 回显的哨兵 echo 行
            sb.append(line).append('\n')
        }
        return sb.toString().trim('\n')
    }

    /** 会话真身（含每会话一把锁）。 */
    private class Entry(
        val handle: String,
        val identity: TerminalIdentity,
        val backendKey: String,
        val alias: String,
        val backend: TerminalBackend
    ) {
        /** 每会话一把互斥锁：同会话命令串行，跨会话互不阻塞。 */
        val mutex = Mutex()

        @Volatile
        var closed: Boolean = false

        /**
         * W-46：输出边界已不可信（上一条命令超时被中断，哨兵可能永远等不到）。
         *
         * 当前超时路径已经直接回收会话（[execute] 里 close + 摘表），所以正常流程走不到
         * 这里。保留这个标志是为了兼容「会话被外部标脏但还没摘除」的旧路径——例如后端
         * 自己检测到流断裂。判据仍是：宁可让调用方重开，也不返回错位结果。
         */
        @Volatile
        var desynced: Boolean = false

        val alive: Boolean get() = !closed && backend.isAlive

        fun snapshot(): SessionSnapshot =
            SessionSnapshot(handle, identity, backendKey, alias, alive)
    }

    companion object {
        private const val TAG = "TerminalSessionPool"

        /**
         * 单档后端启动上限（W-59）。
         *
         * 取值依据：proot 冷启动 < 5s；Shizuku `newProcess` 秒级；无线调试是
         * `connect` 5s（AdbClient.connectTimeoutMs）+ 握手 8s（openInteractiveShell 默认）。
         * 30s 留足余量，同时把「binder 卡死」这类无响应路径收敛成「失败并回落」。
         */
        private const val BACKEND_START_TIMEOUT_MS = 30_000L

        /** 哨兵标记前缀（公开：harness 的假后端需据协议应答）。 */
        const val SENTINEL_MARKER = "__AGENTKIT_POOL_END__"

        /** 单条命令默认完成等待上限（毫秒）。 */
        const val DEFAULT_COMMAND_TIMEOUT_MS: Long = 30_000L

        /**
         * 收流缓冲上限（字符）。约 256K 字符 ≈ 512KB 堆，足够容纳 24K 字符结果上限的十倍余量；
         * 超出部分丢头部（哨兵在尾部，不影响完成判定）。
         */
        const val MAX_BUFFER_CHARS: Int = 256_000

        /** 句柄随机重试次数；超过则顺序补位。 */
        private const val HANDLE_ALLOC_RETRIES = 8

        private const val HEX_CHARS = "0123456789abcdef"

        /** 随机 4-hex 句柄（[TERMINAL_HANDLE_REGEX]）。 */
        private fun randomHex4(): String =
            (Random.nextInt(0x10000)).toString(16).padStart(4, '0')

        /** 池键：后端键 + 别名（用不可能出现在别名里的分隔符拼接）。 */
        private fun poolKey(backendKey: String, alias: String): String = "$backendKey\u0000$alias"

        /** 归一化别名：trim；空串回退为 "default"。 */
        private fun normalizeAlias(alias: String): String =
            alias.trim().ifEmpty { TerminalBackendFactory.ALIAS_DEFAULT }
    }
}
