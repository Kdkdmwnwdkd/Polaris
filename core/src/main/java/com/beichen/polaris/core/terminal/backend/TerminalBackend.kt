package com.beichen.polaris.core.terminal.backend

import kotlinx.coroutines.flow.Flow

/**
 * 终端后端统一接口（T07）。
 *
 * 契约 5 方法：`start` / `send` / `close` / `awaitExit` / `output: Flow`。
 * **上层（会话池 / shell 工具）只认本接口**，因此不存在「是容器还是本机」的分支——
 * proot 容器（[ProotBackend]）与本机 user shell（[LocalUserBackend]）从这里之后完全同构。
 *
 * 线程契约：实现须保证 [output] 可被**单一消费者**持续收集；[send] 只负责把输入写入
 * 后端（不等待命令结束，完成判定交由上层按哨兵实现）。
 */
interface TerminalBackend {

    /**
     * 后端**实际**以何种身份执行命令（R-12：这是事实，不是请求）。
     *
     * 修前这里写的是「首期实现均为 [TerminalIdentity.User]」，于是七处实现照抄 `User`——
     * 包括以 uid 2000 跑的 Shizuku 后端与无线调试后端。它们不是「填错了」，
     * 而是当时枚举里没有可填的档：`Shizuku` 被理解成通道名，只在 schema 里出现。
     *
     * 现在 [TerminalIdentity.uid] 让 uid 2000 成为一个可声明的档，实现必须如实填写：
     * 上层（会话池、工具回执、能力快照）据此报告「这条命令以什么权限跑了」，
     * 权限不足时也才能正确分档，而不是对着一个假的 `user` 出主意。
     */
    val identity: TerminalIdentity

    /**
     * 后端**通道**键，用于会话池分池不串味（同一档身份下的不同通道各占独立别名）。
     * 取值用 [KEY_USER] / [KEY_PROOT] 等常量，不要硬编码字符串。
     *
     * 与 [identity] 是两个维度，别混：`shizuku` 与 `adb-wireless` 两条通道的
     * [identity] 都是 uid 2000，但它们是不同的池、不同的启动方式、不同的失效原因。
     */
    val backendKey: String

    /** 后端是否仍存活（进程/连接在）。会话池据此识别僵尸会话并释放别名。 */
    val isAlive: Boolean

    /** 输出流（文本 chunk）。实现须允许被持续收集，已产出的数据不因订阅时机而丢失。 */
    val output: Flow<String>

    /** 启动后端。返回值表示是否成功；失败时上层降级/报错。 */
    suspend fun start(): Boolean

    /** 向终端写入输入（通常是一整行命令文本，含结尾换行）。返回是否成功写入。 */
    suspend fun send(input: String): Boolean

    /** 关闭后端并回收资源（幂等）。 */
    suspend fun close()

    /** 等待后端退出，返回退出码（信号杀死 = 128 + 信号；无法获取 = -1）。 */
    suspend fun awaitExit(): Int

    companion object {
        /** 本机 user 后端键。 */
        const val KEY_USER = "user"

        /** proot 容器后端键。 */
        const val KEY_PROOT = "proot"

        /** root（su）后端键：首期未实现。 */
        const val KEY_SU = "su"

        /**
         * Shizuku 后端键（W-56 起**已实现**为
         * [com.beichen.polaris.automation.shizuku.ShizukuTerminalBackend]）。
         *
         * 早先判它是「远端进程代理、流语义不可用」，其实混了两件事：W-36 踩的是**定时 `waitFor`**
         * （远端代理上立刻抛异常，「启动失败」被误报成「超时」），不是 stdin/stdout 读写。
         * 改用 `exitValue()` 轮询之后，持久 `sh` 在这条通道上完全可用。
         */
        const val KEY_SHIZUKU = "shizuku"

        /**
         * 无线调试（adb over TCP）后端键。W-53 起实现为 [com.beichen.polaris.automation.adb.AdbTerminalBackend]。
         *
         * 与能力链的 `BACKEND_ADB` 同值——两处都指向「同一条通道」，日志里可对齐。
         */
        const val KEY_ADB = "adb-wireless"

        /**
         * SSH 后端键（W-RT5 起实现为 [SshBackend]）。
         *
         * 特殊之处是**不在降级链里**：只有显式 `identity=ssh`（会话池 `open(backendKey=…)`）
         * 才会用到它。远端机器不能顶替本机 / 容器 shell，理由见 [SshBackendProvider]。
         */
        const val KEY_SSH = "ssh"
    }
}

/**
 * 后端来源：会话池据此按身份惰性获取后端实例（**每次调用返回新实例**，
 * 因为一个后端 = 一条独立 shell 进程，不能被多个会话共享）。
 *
 * 把「取后端」抽象成一个函数式接口，让 [com.beichen.polaris.core.terminal.TerminalSessionPool]
 * 零 Android 依赖、可在 JVM harness 里注入假实现。
 */
fun interface TerminalBackendSource {
    /**
     * 取一条后端。
     *
     * @param identity **权限下限**（R-12）：后端实际身份须 [TerminalIdentity.satisfies] 它。
     *   没有满足该下限的后端时返回 null（调用方报「不可用」）。
     * @param preferProot true = 优先 proot（最高可用），不可用时回落本机 user
     * @return 后端实例；不可用返回 null
     */
    fun obtain(identity: TerminalIdentity, preferProot: Boolean): TerminalBackend?

    /**
     * W-55：按候选顺序给出**全部**可构造实例，供会话池「逐个 start，失败回落」。
     *
     * 与 [obtain] 的关系：`obtain` 是最快路径（第一个能造的），本方法给全量。
     * 默认实现退化为单候选——harness 里的 lambda 假 source 因此不用改。
     *
     * **调用方负责**：没用上的实例要 `close()`，否则每条候选都留一个未启动的后端对象。
     */
    fun obtainCandidates(identity: TerminalIdentity, preferProot: Boolean): List<TerminalBackend> =
        listOfNotNull(obtain(identity, preferProot))

    /**
     * W-RT5：按后端键**显式**取一条后端（如 [TerminalBackend.KEY_SSH]）。
     *
     * 与 [obtain] 的分工：`obtain` 走身份降级链（本机 / 容器这类同构身份），本方法用于
     * **不该进降级链的显式指定后端**——远端机器的能力面与手机不同，绝不能在本机 shell
     * 起不来时悄悄顶上。
     *
     * 默认返回 null（无此键按「不可用」处理），于是 harness 里的 lambda 假 source、
     * 以及一切不关心显式路由的实现都不用改。
     */
    fun obtainByKey(backendKey: String): TerminalBackend? = null
}

/**
 * 后端「可用性 + 构造」提供者（T07）：把 Android 侧的探测（rootfs/proot 三件套是否就绪）
 * 与构造从工厂剥离，使 [TerminalBackendFactory] 本身零 Android 依赖、可离线验证。
 */
interface TerminalBackendProvider {

    /** 该提供者实现的**实际**身份（见 [TerminalBackend.identity]）。 */
    val identity: TerminalIdentity

    /** 后端键（见 [TerminalBackend] 常量）。 */
    val backendKey: String

    /** 当前环境下该后端是否可用（可能触及文件系统；工厂会缓存结果直到 [TerminalBackendFactory.clearCache]）。 */
    fun probe(): Boolean

    /** 构造后端实例；不可用时返回 null。 */
    fun create(): TerminalBackend?
}
