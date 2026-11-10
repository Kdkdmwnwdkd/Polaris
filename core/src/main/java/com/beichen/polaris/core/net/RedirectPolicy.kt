package com.beichen.polaris.core.net

/**
 * HTTP 重定向策略：**只跟同源跳转，跨源一律停下交回调用方**。
 *
 * ## 这条规则补的是什么洞
 *
 * `http_request` 的审批按**域名**记忆（见 `ApprovalScope.of`）：用户批准
 * `api.example.com` 之后，本会话再发往该域名不再弹窗。但 OkHttp 默认自动跟随重定向，
 * 于是 30x 一次就能把请求带到 `evil.com`——用户从头到尾只看到过一个域名，
 * 数据却已经到了另一个地方。审批粒度越细（域名级），这条口子越致命：
 * 它把「按域名授权」直接退化成「按第一个域名授权」。
 *
 * ## 为什么不是"跟随时再校验一次域名"
 *
 * 调用方（技能桥 / 主对话）是唯一持有授权信息的角色，本文件没有。若在这里
 * 「校验域名」，只能校验「与起始域名相同」——那和 [Stop] 是同一件事，
 * 却让调用方误以为跳转已被授权。返回 `Stop` 并带上目标地址，由调用方决定
 * 要不要重新发起（重新发起会走完整审批），语义才不歧义。
 *
 * ## 允许什么
 *
 * | 情形 | 处理 | 理由 |
 * |---|---|---|
 * | 同 host、同端口、`http→https` | 跟随 | 升级加密，且目标就是同一台服务器 |
 * | 同 host、同端口、同 scheme | 跟随 | 常见于补尾斜杠、`/v1` → `/v1/` |
 * | 跨 host / 跨端口 | **停** | 换了一台服务器，就是换了一个授权对象 |
 * | `https→http` | **停** | 降级到明文，等于把已加密的内容交出去 |
 * | 超过 [MAX_HOPS] 跳 | 停 | 环状跳转与跳转链拖时间，正常 API 不会这么干 |
 *
 * 相对 Location 会先按 RFC 3986 的常见形态解析成绝对地址（`/x`、`//host/x`、`x`），
 * 解析不了就停——**猜错了就是放行一个未知目标**，不如让调用方重来。
 */
object RedirectPolicy {

    /** 最多跟随几跳（不含首次请求）。 */
    const val MAX_HOPS = 3

    /** 判定结果。 */
    sealed interface Redirect {
        /**
         * 可以跟随。
         * @param to 解析后的绝对地址
         * @param method 跟随之后应当使用的方法（303 会把非 GET/HEAD 降级为 GET）
         * @param dropBody 是否丢弃请求体（方法被降级时必丢）
         */
        data class Follow(val to: String, val method: String, val dropBody: Boolean) : Redirect

        /** 不跟随。`reason` 可直接展示给用户 / 模型。 */
        data class Stop(val reason: String, val target: String?) : Redirect
    }

    /**
     * 判定一次跳转。
     *
     * @param from 当前请求的绝对地址
     * @param location 响应头 `Location` 原文（可能为空 / 相对地址）
     * @param hop 已经跟随过几跳（首次跳转传 0）
     * @param status 响应状态码（301/302/303/307/308）
     * @param method 当前请求方法
     */
    fun decide(from: String, location: String?, hop: Int, status: Int, method: String): Redirect {
        if (hop >= MAX_HOPS) {
            return Redirect.Stop("重定向超过 $MAX_HOPS 跳，已停止（可能是环状跳转）", null)
        }
        val loc = location?.trim().orEmpty()
        if (loc.isEmpty()) return Redirect.Stop("响应声明了重定向但没有 Location 头", null)

        val target = resolve(from, loc)
            ?: return Redirect.Stop("重定向目标无法解析：$loc", null)

        val a = parse(from) ?: return Redirect.Stop("当前地址无法解析：$from", target)
        val b = parse(target) ?: return Redirect.Stop("重定向目标无法解析：$loc", target)

        if (a.scheme == "https" && b.scheme == "http") {
            return Redirect.Stop("重定向试图把 https 降级成明文 http（目标：${b.host}）", target)
        }
        if (a.host != b.host) {
            return Redirect.Stop(
                "重定向跳到了另一个域名（${a.host} → ${b.host}）。域名级授权不能覆盖它，" +
                    "已停止跟随；如需访问请对目标地址重新发起请求。",
                target
            )
        }
        if (a.port != b.port) {
            return Redirect.Stop("重定向跳到了另一个端口（${a.port} → ${b.port}），已停止跟随", target)
        }

        // 303 See Other 的语义固定是「去 GET 那个地址」；301/302 对 POST 的浏览器行为同样是转 GET
        // （RFC 7231 允许，实践一致）。307/308 明确要求保持方法与请求体。
        val upper = method.trim().uppercase().ifEmpty { "GET" }
        val downgrade = status == 303 || ((status == 301 || status == 302) && upper != "GET" && upper != "HEAD")
        return if (downgrade) {
            Redirect.Follow(target, "GET", dropBody = true)
        } else {
            Redirect.Follow(target, upper, dropBody = false)
        }
    }

    /**
     * 把 `Location` 解析成绝对地址。这里只支持 http(s)，别的 scheme 一律 `null`。
     *
     * 相对地址的基准取 `from` 的 scheme+authority+所在目录（RFC 3986 §5.2 的简化版）：
     * 只有 `.` / `..` 段与查询串的处理被省掉——真实重定向里这两种写法极少，
     * 而实现错了的代价是「跟随到一个本不该去的地址」。
     */
    fun resolve(from: String, location: String): String? {
        val loc = location.trim()
        if (loc.isEmpty()) return null

        return when {
            loc.startsWith("//") -> {
                val base = parse(from) ?: return null
                "${base.scheme}:$loc"
            }
            loc.contains("://") -> {
                // 只认 http(s)：`ftp://` / `file://` 不是我们能跟随的传输层，
                // 原样交回去会被当成"跳转成功"，调用方随后在解析阶段拿到 null——
                // 不如在这里就断掉，理由更准。
                val sch = loc.substringBefore("://").lowercase()
                if (sch == "http" || sch == "https") loc else null
            }
            loc.startsWith("/") -> "${originOf(parse(from) ?: return null)}$loc"
            else -> {
                val base = parse(from) ?: return null
                val dir = base.path.substringBeforeLast('/', "")
                "${originOf(base)}$dir/$loc"
            }
        }
    }

    /**
     * 由解析结果拼回 `scheme://host[:port]`。
     *
     * 刻意**不带 userinfo**：`from` 里的 `user:pass@` 是调用方自己填的，跳转目标不该继承它
     * ——那会把凭据发给跳转后的地址，且跳转后的地址本就由对方指定。
     */
    private fun originOf(p: Parts): String {
        val hostPart = if (p.host.contains(':')) "[${p.host}]" else p.host
        val portPart = if (p.port == defaultPort(p.scheme)) "" else ":${p.port}"
        return "${p.scheme}://$hostPart$portPart"
    }

    /** 解析出的地址片段。 */
    private data class Parts(
        val scheme: String,
        val host: String,
        val port: Int,
        val path: String,
    )

    private fun parse(url: String): Parts? {
        val s = url.trim()
        val schemeEnd = s.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = s.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null

        val rest = s.substring(schemeEnd + 3)
        val authEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authEnd < 0) rest else rest.substring(0, authEnd)
        val path = if (authEnd < 0) "/" else rest.substring(authEnd)
        if (authority.isEmpty()) return null

        val host = EndpointPolicy.hostOf(s)
        if (host.isEmpty()) return null

        val hostPort = authority.substringAfterLast('@')
        val port = if (hostPort.startsWith("[")) {
            hostPort.substringAfter("]", "").removePrefix(":").toIntOrNull()
                ?: defaultPort(scheme)
        } else {
            hostPort.substringAfterLast(':', "").toIntOrNull() ?: defaultPort(scheme)
        }
        return Parts(scheme, host, port, path)
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80
}
