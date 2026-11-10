package com.beichen.polaris.core.net

/**
 * 技能的网络出口白名单（manifest 的 `net_hosts`）。
 *
 * ## 为什么需要它
 *
 * 技能脚本调用 `agent.http()` 会被 [com.beichen.polaris.skill.SkillRuntimeBridge] 第三道门
 * 直接拒成 `script_approval_denied`——理由是「需要人确认的工具脚本不得代调用」。
 * 这条理由对 `shell_run` 成立（命都还没写，人看不出后果），但对「这个技能只查一个
 * 固定 API」是过度限制：脚本跑在非挂起的 Rhino 线程里，弹审批会死锁。
 *
 * 出路只有一条：把「同意」提到**安装那一刻**。manifest 声明这个技能会访问哪些域名，
 * 用户在装的时候看见，此后脚本只能发往这些域名——域名就是授权范围。
 *
 * ## 为什么域名要逐条校验而不是信 manifest
 *
 * manifest 来自 zip，是**攻击者完全可控**的文本。若直接采信，一个恶意技能写
 * `"net_hosts": ["*"]` 就拿到全网外发权，比不修还糟。所以：
 *  - 裸 `*` 无条件非法（[validateEntry]），[matches] 也永不把它当通配；
 *  - 只有 `policyFor(trust).allowScript == true` 的技能才会走到这条路径
 *    （见 `SkillRuntimeBridge.invoke`），也就是打包/用户显式授权过的技能；
 *  - 通配只允许 `*.子域` 形式，前缀通配（`api.*.com`）非法——那种写法没法审。
 *
 * ## 与 `EndpointPolicy` 的分工
 *
 * [EndpointPolicy] 回答「这个地址能不能明文」，本文件回答「这个地址是不是技能被允许去的」。
 * 两者都要过：白名单内但走公网明文的请求仍会被 [EndpointPolicy.refusalFor] 拒掉。
 */
object HostAllowlist {

    /** 通配前缀。只允许出现在最左边，且必须紧跟一个点。 */
    private const val WILDCARD = "*."

    /**
     * 声明项规范化：去空白、转小写、去尾部点（`API.Example.com.` → `api.example.com`）。
     *
     * 不做的是「自动补 scheme / 补路径」——含 `://` 或 `/` 的声明项在 [validateEntry]
     * 里就是非法的。让作者写错就报错，好过猜他想表达什么。
     */
    fun normalize(raw: String): String = raw.trim().lowercase().trimEnd('.')

    /**
     * 校验一条 manifest 声明。
     *
     * @return `null` 表示合法；非 null 是**可以直接回给技能作者**的原因。
     */
    fun validateEntry(raw: String): String? {
        val entry = raw.trim()
        if (entry.isEmpty()) return "域名声明为空"
        if (entry == "*") return "不允许裸 \"*\"（那等于放开全网）；请逐条列出域名，或用 \"*.example.com\" 限定子域"
        if (entry.contains("://")) return "只写主机名，不要带协议：\"$raw\""
        if (entry.contains('/')) return "只写主机名，不要带路径：\"$raw\""
        if (entry.contains(' ')) return "域名里不能有空格：\"$raw\""
        if (entry.contains('@')) return "域名里不能有 userinfo：\"$raw\""
        // 端口不属于主机名：`matchesOne` 比的是 host（`EndpointPolicy.hostOf` 已剥掉端口），
        // 声明里写 `:443` 的条目**永远**不会命中——作者以为限定了端口，实际是把这条声明写死了。
        // IPv6 字面量（`[::1]`）保留：它以 `[` 开头，冒号是地址的一部分。
        if (entry.contains(':') && !entry.startsWith('[')) {
            return "只写主机名，不要带端口：\"$raw\"（端口由请求地址决定，写在声明里永远不会命中）"
        }

        val host = normalize(entry)
        if (host.startsWith(WILDCARD)) {
            val tail = host.removePrefix(WILDCARD)
            if (tail.isEmpty() || !tail.contains('.') || tail.startsWith('*')) {
                return "通配只能写成 \"*.example.com\"（至少两级域名，且 \"*\" 只能在最左边）：\"$raw\""
            }
            return if (tail.startsWith('.') || tail.contains('*')) "通配写法非法：\"$raw\"" else null
        }
        if (host.contains('*')) return "\"*\" 只能作为最左边的整段（\"*.example.com\"）：\"$raw\""
        if (!host.contains('.')) {
            // 单标签主机名（`nas`）只可能是局域网，按字面接受——局域网内没有 DNS 抢注问题
            return null
        }
        if (host.startsWith('.') || host.startsWith('-') || host.endsWith('-')) {
            return "域名首尾不合法：\"$raw\""
        }
        return null
    }

    /**
     * 这条请求地址是否落在声明白名单内。
     *
     * 判定口径：
     *  - 取**真实请求主机**（去 userinfo、去端口、去尾部点、小写，复用 [EndpointPolicy.hostOf]）；
     *  - 精确项全等即命中；
     *  - `*.example.com` 命中 `a.example.com` / `a.b.example.com`，**不**命中 `example.com`
     *    本身，也不命中 `xexample.com`（按点边界比较，不是字符串 endsWith）。
     *
     * @param declared manifest 的 `net_hosts` 原文（不要求已校验；非法项在这里一律不命中）
     */
    fun matches(declared: List<String>, url: String): Boolean {
        val host = EndpointPolicy.hostOf(url)
        if (host.isEmpty()) return false
        return declared.any { matchesOne(normalize(it), host) }
    }

    /** 单条声明与主机的比较（[matches] 的单元）。非法声明返回 false（fail-closed）。 */
    fun matchesOne(entry: String, host: String): Boolean {
        if (entry.isEmpty() || host.isEmpty()) return false
        if (entry == "*") return false
        if (validateEntry(entry) != null) return false

        val h = normalize(host)
        if (!entry.startsWith(WILDCARD)) return entry == h

        val suffix = entry.removePrefix(WILDCARD)
        // 必须是「某个标签 + .suffix」：`example.com` 自身不算 `*.example.com` 内
        return h.length > suffix.length + 1 && h.endsWith(".$suffix")
    }
}
