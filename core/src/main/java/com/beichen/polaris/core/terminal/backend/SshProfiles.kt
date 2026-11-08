package com.beichen.polaris.core.terminal.backend

/**
 * W-RT5：SSH 远端 shell 的纯函数层（零 IO / 零 Android 依赖，harness 可真编真跑）。
 *
 * 设计（对标 Operit「远程执行」的思路，净室重写）：
 *  - **不引入 SSH 协议库**（零新增依赖）：传输直接复用现成的 `ssh` 命令行客户端——
 *    本文件只负责「档案校验 + argv 拼装」，起进程 / 读流 / 哨兵判定全部由
 *    [ProcessBackendBase] 与会话池既有逻辑承担，SSH 与本机 / 容器在 [TerminalBackend]
 *    接口之后完全同构；
 *  - **仅密钥登录**（`BatchMode=yes`）：密码交互提示会让无 PTY 的常驻会话永远卡死，
 *    也避免密码出现在任何配置 / 日志里；不支持密码认证是安全边界，不是缺陷；
 *  - **SSH 不进自动降级链**：远端机器的能力面与手机完全不同（`pm` / `settings` 在远端
 *    毫无意义，反过来远端的 `systemctl` 在这边也没有），混进 proot → shizuku → user
 *    的候选链会让命令**静默跑到另一台机器上**——这是最难查的一类故障。
 *    所以 SSH 走显式选择：`identity=ssh` 经会话池 `open(backendKey=...)` 单独路由。
 */

/** 一条 SSH 连接档案（v1 单档案）：主机、端口、用户名、私钥路径（可选）。 */
data class SshProfile(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val user: String,
    val keyPath: String = ""
) {
    companion object {
        const val DEFAULT_PORT = 22
    }
}

/** 档案校验结果：[Ok]（带规范化后的档案）或 [Invalid]（带给用户看的理由）。 */
sealed class SshProfileResult {
    data class Ok(val profile: SshProfile) : SshProfileResult()
    data class Invalid(val reason: String) : SshProfileResult()
}

/**
 * 校验并规范化 SSH 档案（纯函数）。
 *
 * 三条判据都不是形式主义：
 *  - host / user 必填：缺了拼出来的 argv 是 `ssh -p 22 @`，ssh 会拿它去连本机；
 *  - 两者不得含空白：argv 是列表传参、不做 shell 拆分，带空格的值会被 ssh 当成**两个参数**
 *    （`-l` 之后多出来的那个，ssh 会当作 host），错误现场极难对应回这里；
 *  - port 允许「22」「 22 」这类文本（文本框里带空格是常态），越界与非数字都拒——
 *    端口拼进 argv 之后 ssh 只会给一句 `Bad port`，用户看不出是校验该拦的。
 *
 * keyPath 可选：留空即交给 ssh 自己按 `~/.ssh/id_*` 与 `~/.ssh/config` 解析。
 */
fun normalizeSshProfile(host: String, portText: String, user: String, keyPath: String): SshProfileResult {
    val h = host.trim()
    val u = user.trim()
    val k = keyPath.trim()
    if (h.isEmpty()) return SshProfileResult.Invalid("主机地址不能为空")
    if (h.any { it.isWhitespace() }) return SshProfileResult.Invalid("主机地址不能包含空格")
    if (u.isEmpty()) return SshProfileResult.Invalid("用户名不能为空")
    if (u.any { it.isWhitespace() }) return SshProfileResult.Invalid("用户名不能包含空格")
    val port = if (portText.isBlank()) SshProfile.DEFAULT_PORT else portText.trim().toIntOrNull()
        ?: return SshProfileResult.Invalid("端口必须是数字（1–65535）")
    if (port !in 1..65535) return SshProfileResult.Invalid("端口必须在 1–65535 之间")
    return SshProfileResult.Ok(SshProfile(host = h, port = port, user = u, keyPath = k))
}

/**
 * 拼装 ssh 命令 argv（纯函数，与 [SshBackend] 的实际执行一致）。
 *
 * 选项逐条有理由，不是抄来的模板：
 *  - `BatchMode=yes`：任何密码 / 口令提示直接失败而不是挂住会话（见文件头「仅密钥登录」）。
 *    没有这一条，未加公钥时表现为「命令一直没返回」，而不是「认证失败」；
 *  - `StrictHostKeyChecking=accept-new`：首次连接自动接受新主机指纹，**已知主机变更仍然拒绝**——
 *    既不让人卡在 yes/no 提示上，也不放弃中间人告警；
 *  - `ConnectTimeout=10`：会话开辟有超时预算，网络不通要快速失败并回落成可读错误，
 *    不能让工具调用在那里干等；
 *  - 私钥用 `-i` 显式给（用户自带的静态二进制不读 `~/.ssh/config` 的情况很常见）。
 */
fun buildSshArgs(profile: SshProfile, sshPath: String): List<String> = buildList {
    add(sshPath)
    add("-o"); add("BatchMode=yes")
    add("-o"); add("StrictHostKeyChecking=accept-new")
    add("-o"); add("ConnectTimeout=10")
    if (profile.keyPath.isNotBlank()) { add("-i"); add(profile.keyPath) }
    add("-p"); add(profile.port.toString())
    add("${profile.user}@${profile.host}")
}
