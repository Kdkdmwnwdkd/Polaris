package com.beichen.polaris.core.terminal.backend

/**
 * shell_run 的**路由判定**：这一次调用该走一次性提权通道，还是交给会话池。
 *
 * ## 为什么单独成文件
 *
 * 它原本是 `ShellTool.kt` 里的顶层函数。那个文件为了执行命令拖进了
 * `TerminalSessionPool` / `JsonObject` / `ToolResult`，于是这条纯判定也只能
 * 「等构建机编出 AGP 产物」才能验——而它恰好是 R-20 那类「两处解析语义不一致」
 * 最容易出问题的地方。搬到这里之后零依赖，沙箱里就能真跑真值表。
 *
 * ## W-57 起默认不再走它
 *
 * 原规则是「identity 未给 → 通道就绪时自动优先」，理由是「uid 2000 能干的活最多」。
 * 但那条通道一条命令一个进程，`cd` / `export` 不跨调用保持（W-54 只是把它说清楚了）。
 * W-56 把 Shizuku 做成了**会话池后端**之后，「uid 2000」和「持久 shell」不再二选一——
 * 所以默认应当进池，由候选链 proot → shizuku → adb → user 自己挑，且 cwd 保有。
 *
 * 现在的规则：
 *  - 带了 session 句柄 → **不走**（会话已绑定某个后端，中途换通道会让 cwd/环境变量语义断裂）；
 *  - identity 解析出来是 Shizuku → 走（调用方明确表示「就要一条命令、不要开会话」）；
 *  - 其余（含 identity 未给）→ 不走，交给会话池。
 *
 * 注意：**「要 uid 2000」不必非走这里**——传 `prefer_proot=false` 进池，候选链会跳过容器、
 * 优先 shizuku / adb，同样是 uid 2000 但**持久**。这条通道只在「不想留会话」时才划算。
 *
 * ## 大小写（R-20）
 *
 * 修前这里是 `identity == "shizuku"`，而 `ShellRunTool.execute` 用的是
 * [TerminalIdentity.fromWire]（大小写不敏感）。于是 `identity=Shizuku` 能通过校验，
 * 却不走这条通道——掉进池里被 `providersFor` 拒掉（当时对非 User 一律返空）。
 * R-12 之后这条路是通的：池会按 uid 2000 的候选链开一个持久会话。
 * 合法的输入走了错的分支，是比「拒绝大写」更难查的一类缺陷。
 *
 * 现在两处都走 [TerminalIdentity.fromWire]，语义只剩一份。
 */
fun shouldUsePrivilegedShell(
    identity: String?,
    hasSession: Boolean,
    privilegedAvailable: Boolean
): Boolean {
    if (hasSession || !privilegedAvailable) return false
    return TerminalIdentity.fromWire(identity) == TerminalIdentity.Shizuku
}
