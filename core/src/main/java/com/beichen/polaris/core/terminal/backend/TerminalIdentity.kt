package com.beichen.polaris.core.terminal.backend

/**
 * 终端「身份」维度（T07）。
 *
 * 身份 = 这条终端会话**以谁的权限**执行命令。
 *
 * ## 它是权限档，不是通道（R-12）
 *
 * 修前这个枚举被当成「通道名」在用：`identity=shizuku` 读起来像「走 Shizuku」，
 * 而后端一侧则统一声明 `identity = User`——**七处声明全是 User**，连 Shizuku 后端和
 * 无线调试后端（两者实际都是 uid 2000）也照抄。后果是三元组
 * （请求身份 / 后端实际身份 / 报告给模型的身份）里只有第一个是真的：
 *
 *  - 模型传 `prefer_proot=false` 拿到 uid 2000 的会话，结果里写着「身份 user」；
 *  - 权限不足时的提示按这个假身份分档，于是把「已是 uid 2000」的失败说成「换条路试试」；
 *  - `providersFor` 用 `it.identity == identity` 过滤，而所有 provider 都是 User，
 *    这个过滤对 User 请求恒真，对非 User 请求则靠前面一句 `return emptyList()` 兜住——
 *    **真正起作用的是特例分支，不是身份比较**。
 *
 * 现在 [uid] 与 [satisfies] 让「档」可比较：请求是**下限**，后端声明的是**实际值**。
 * 这条语义贯穿工厂（候选过滤）、会话池（复用与冲突判定）、工具回执（如实报告）。
 *
 * ## 枚举位 ≠ 可用取值（R-20）
 *
 * 枚举保留全量（User / Shizuku / Su / Ssh）是为了给将来留位，但**工具 schema 只该
 * 暴露真的能跑的那些**。修前 `ShellRunTool` 手写了一句 `listOf("user","su","shizuku","ssh")`，
 * 于是模型看得到 `su` / `ssh`，选过去却必然失败——工厂对它们返回空候选列表。
 * 一次失败就浪费一步，而模型从错误信息里也读不出「这个值本来就不存在」。
 *
 * 现在由 [usable] 标注事实、由 [schemaValues] 生成 schema，两处不再各写各的。
 *
 * ## 安全语义（身份固化）
 * 身份在**会话开辟时固化**（写入会话元数据，取后端声明的实际值）。
 * 后续再对同一别名传**高于会话实际档**的身份时显式报错，绝不静默复用一个权限不足的会话
 * （用户以为提权了，命令其实仍以旧身份执行）——见
 * [com.beichen.polaris.core.terminal.TerminalSessionPool.open]。
 * 反向（请求的档低于会话实际档）**允许复用**：权限更高本来就满足需求。
 */
enum class TerminalIdentity(
    /**
     * 这个身份对应的 Linux uid；null = 由 App 自身进程决定（编译期不可知）。
     *
     * 只用于**报告与判定**，不参与任何执行路径——真正的权限来自后端进程怎么起来，
     * 不是来自这里写了几。
     */
    val uid: Int?,
    /**
     * 传这个取值，有没有可能真的执行成功。
     *
     * 注意它与「有没有后端」不是一回事：[Shizuku] 在 `providersFor` 里拿到的后端
     * 取决于设备（装了 Shizuku 或配对过无线调试才有），而它对模型是一个有效取值——
     * 只要设备上有任一 uid 2000 通道就能跑。这也正是不能用「后端数量 > 0」表达可用性的原因。
     *
     * false 的那一档是**尚未实现**，不是「这台机器上暂时不可用」——
     * 后者属于运行时状态，不该写进枚举。（W-RT5 之前这里是 Su / Ssh 两档；
     * Ssh 接上后端后只剩 Su。）
     */
    val usable: Boolean
) {

    /** 本机 App 用户身份（无提权）：proot 容器与 `/system/bin/sh` 均走此身份。 */
    User(uid = null, usable = true),

    /** root（su）：首期**只留接口不实现**，后端工厂返回空候选。 */
    Su(uid = 0, usable = false),

    /**
     * uid 2000（ADB shell 档）。
     *
     * **名字来自 Shizuku 通道，语义却是权限档**：Shizuku 持久后端与无线调试后端都以
     * uid 2000 执行，两者对上层是同一个身份（[TerminalBackend.KEY_SHIZUKU] /
     * [TerminalBackend.KEY_ADB] 才是通道的区别）。名字不改是因为它已经是模型可见的
     * `identity` 取值、也写进了历史对话——改 wire 值会让旧会话里的参数集体失效；
     * 语义在 KDoc 与工具描述里说清即可。
     */
    Shizuku(uid = 2000, usable = true),

    /**
     * SSH 远端（W-RT5 起**已实现**）。
     *
     * 与其它三档的根本差别：它不是「本机权限的某一层」，而是**另一台机器**。
     * 因此：
     *  - 它不满足任何本地身份，本地身份也满足不了它（见 [satisfies]）；
     *  - 它**不进** shell 的自动降级链（[TerminalBackendFactory.providersFor] 显式排除），
     *    只能经 `identity=ssh` + [TerminalBackendSource.obtainByKey] 显式路由——
     *    「本机 shell 起不来就悄悄跑到远端去」是最难查的一类故障；
     *  - 会话池里它固化的是 User（远端登录 shell 的身份与本机 uid 不可比），
     *    通道由 [TerminalBackend.KEY_SSH] 表达。
     */
    Ssh(uid = null, usable = true);

    /** 对外线格式（工具 schema 的 enum 取值）：枚举名小写。 */
    val wire: String get() = name.lowercase()

    /**
     * 本方身份能否满足 [required] 这个**下限**。
     *
     * 这是 R-12 的核心谓词，三处共用：工厂筛候选、会话池判复用、池层判冲突。
     * 用显式 when 而不是数值比较：`Ssh` 是远端，它的权限与本地 uid 不可比，
     * 给一个「等级数字」只会诱导出「ssh > shizuku」这类没有依据的推断。
     *
     * | 本方 \ 要求 | User | Shizuku | Su | Ssh |
     * |---|---|---|---|---|
     * | User | ✔ | ✘ | ✘ | ✘ |
     * | Shizuku (uid 2000) | ✔ | ✔ | ✘ | ✘ |
     * | Su (uid 0) | ✔ | ✔ | ✔ | ✘ |
     * | Ssh | ✘ | ✘ | ✘ | ✔ |
     */
    fun satisfies(required: TerminalIdentity): Boolean = when (this) {
        // uid 0 能跑一切本地命令（文件系统权限除外，那是 SELinux/挂载的事，不归身份管）
        Su -> required != Ssh
        Shizuku -> required == User || required == Shizuku
        User -> required == User
        // 远端只满足远端：它的「root」是另一台机器的 root，不能拿来满足本机提权
        Ssh -> required == Ssh
    }

    /**
     * 给人看的一句话（工具回执、日志、诊断导出用）。
     *
     * 刻意不用 `wire`：`identity=shizuku` 是**请求参数**的说法，而回执要说的是
     * 「这条命令以什么权限跑了」。两个概念混用正是 R-12 的病根。
     */
    fun describe(): String = when (this) {
        User -> "App 身份"
        // 用 uid 拼而不是再写一遍数字：档位的真源是构造参数，文案只是它的呈现。
        // 哪天 2000 改成别的（比如将来区分 work profile），这里自动跟上。
        Shizuku -> "uid $uid"
        Su -> "uid $uid（root）"
        Ssh -> "远端 ssh"
    }

    companion object {
        /**
         * 线格式 → 枚举（大小写不敏感）。
         *
         * @return 匹配的枚举；空白或未知取值返回 null（调用方按「身份不支持」显式报错，
         *   不静默回落到 User）。
         */
        fun fromWire(s: String?): TerminalIdentity? {
            if (s.isNullOrBlank()) return null
            val t = s.trim().lowercase()
            return entries.firstOrNull { it.wire == t }
        }

        /**
         * 工具 schema 里 `identity` 的 enum 取值：只列 [usable] 的。
         *
         * 加新身份时只改枚举本身，schema、参数说明、错误提示三处都会跟着变——
         * 这是 R-20 修的那个「文案与枚举各写一份、悄悄漂移」的根。
         */
        fun schemaValues(): List<String> = entries.filter { it.usable }.map { it.wire }

        /** 给模型看的取值清单，例如 `user / shizuku`。 */
        fun schemaValuesText(sep: String = " / "): String = schemaValues().joinToString(sep)
    }
}
