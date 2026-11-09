package com.beichen.polaris.core.net

/**
 * 端点明文策略：**带凭据的请求不许走公网明文 HTTP**。
 *
 * ## 为什么需要它
 *
 * Base URL 由用户自己填，App 里没有一处会拒绝 `http://`。这对 Ollama / LM Studio /
 * one-api 这类局域网服务是刚需（见 `res/xml/network_security_config.xml`），所以不能
 * 一刀切禁掉明文。但同一条放行规则也覆盖了「公网主机 + 明文」这一种情况：
 * `Authorization: Bearer sk-...` 会以明文经过沿途每一个热点、每一级运营商链路。
 * 一次咖啡店的 Wi-Fi 就够把 Key 拿走，而用户不会收到任何提示。
 *
 * ## 判据为什么不看域名白名单
 *
 * 端点主机是运行期才确定的，编译期无从枚举；`network_security_config` 的 `<domain>`
 * 又只接受主机名、不支持 CIDR，表达不了「私网段」这件事。所以判据只能落在
 * 「这个主机地址本身是不是可路由到公网」，也就是本文件的 [isPrivateHost]。
 *
 * ## 三个档位
 *
 * | 端点 | 判定 | 理由 |
 * |---|---|---|
 * | `https://` 任意 | 放行 | 传输层已加密 |
 * | `http://` 私网 / 本机 / 单标签主机名 | 放行 | 流量不出本机或不出局域网 |
 * | `http://` 公网主机 | **有凭据时拒绝** | 凭据会明文过公网 |
 *
 * 没有凭据时不拒（比如 keyless 的本地 llama.cpp 被反代到公网）：这时泄漏的是 prompt
 * 本身，不是可被复用的长期凭据，拦下来收益小于把用户的可用配置直接打死。
 *
 * ## 不做什么
 *
 * 不解析 DNS。`http://my-nas.lan` 若在公网 DNS 里解析到公网地址，本类仍判为私网——
 * 这种配置本身就说明用户以为是内网，真正该修的是 DNS。判据只回答「字面上这是不是一个
 * 私网地址」，因为那是唯一能在纯函数层确定回答的问题。
 */
object EndpointPolicy {

    /** 端点形态。`MALFORMED` 交给调用方已有的「端点未配置」分支，本类不重复报错。 */
    enum class Kind {
        /** `https://`，传输层已加密。 */
        HTTPS,

        /** `http://` 但主机是本机 / 私网 / 单标签主机名：明文不出局域网。 */
        CLEARTEXT_PRIVATE,

        /** `http://` 且主机可路由到公网：凭据会明文过公网。 */
        CLEARTEXT_PUBLIC,

        /** 不是 http(s)、没有主机、或空串。 */
        MALFORMED
    }

    /** 局域网 / 本机常见后缀。`.local` 是 mDNS，`.home.arpa` 是 RFC 8375 保留的家庭网段。 */
    private val PRIVATE_SUFFIXES = listOf(
        ".localhost", ".local", ".lan", ".internal", ".home.arpa", ".localdomain"
    )

    /**
     * 判定端点形态。容忍用户输入的常见脏形态：首尾空白、大写 scheme、尾部点、
     * userinfo、IPv6 方括号、带端口。
     *
     * @param url Base URL 或完整请求地址（两者都能吃，只看 scheme + authority）。
     */
    fun kindOf(url: String): Kind {
        val s = url.trim()
        val schemeEnd = s.indexOf("://")
        if (schemeEnd <= 0) return Kind.MALFORMED

        val scheme = s.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return Kind.MALFORMED

        val host = hostOf(s)
        if (host.isEmpty()) return Kind.MALFORMED

        if (scheme == "https") return Kind.HTTPS
        return if (isPrivateHost(host)) Kind.CLEARTEXT_PRIVATE else Kind.CLEARTEXT_PUBLIC
    }

    /**
     * 从地址里取主机名：去 userinfo、去端口、IPv6 去方括号、统一小写、去尾部点。
     * 取值失败返回空串（由 [kindOf] 归到 `MALFORMED`）。
     */
    fun hostOf(url: String): String {
        val s = url.trim()
        val schemeEnd = s.indexOf("://")
        if (schemeEnd <= 0) return ""

        val rest = s.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
        if (authority.isEmpty()) return ""

        // userinfo 在最后一个 @ 之前：密码里可能含 @，所以取最后一个
        val hostPort = authority.substringAfterLast('@')

        val host = if (hostPort.startsWith("[")) {
            // IPv6：方括号里全是主机，方括号后面才是端口
            val close = hostPort.indexOf(']')
            if (close < 0) "" else hostPort.substring(1, close)
        } else {
            hostPort.substringBeforeLast(':', hostPort)
        }
        return host.trim().lowercase().trimEnd('.')
    }

    /**
     * 主机是否属于「明文不出局域网」的范围。
     *
     * 覆盖：
     * - 名字：`localhost`、`*.localhost`、`.local` / `.lan` / `.internal` /
     *   `.home.arpa` / `.localdomain`，以及**不含点**的单标签主机名（`nas`、`my-router`）；
     * - IPv4：`127/8`、`10/8`、`192.168/16`、`172.16/12`、`169.254/16`（链路本地）、
     *   `100.64/10`（CGNAT，Tailscale 等组网工具常用）、`0.0.0.0`；
     * - IPv6：`::1`、`::`、`fe80::/10`（链路本地）、`fc00::/7`（ULA）、
     *   `::ffff:a.b.c.d`（IPv4 映射，递归判定内嵌地址）。
     *
     * 刻意**不**包含「看起来像公网 IP 但其实在用户内网」的情况——那需要用户自己判断，
     * 判据只回答字面事实。
     */
    fun isPrivateHost(host: String): Boolean {
        var h = host.trim().lowercase().trimEnd('.')
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        if (h.isEmpty()) return false

        if (h == "localhost") return true
        if (PRIVATE_SUFFIXES.any { h.endsWith(it) }) return true

        val v4 = parseIpv4(h)
        if (v4 != null) return isPrivateIpv4(v4)

        if (h.contains(':')) return isPrivateIpv6(h)

        // 单标签主机名：局域网里 `http://nas:8080` 这种写法很常见，没有点说明不是公网域名
        return !h.contains('.')
    }

    /**
     * 带凭据的请求能否发往该端点。
     *
     * @param url Base URL 或完整请求地址。
     * @param sendsCredentials 本次请求是否携带密钥（API Key / 自定义鉴权头）。
     * @return `null` 表示放行；非空字符串是**可以直接展示给用户**的拒绝理由。
     */
    fun refusalFor(url: String, sendsCredentials: Boolean): String? {
        if (!sendsCredentials) return null
        if (kindOf(url) != Kind.CLEARTEXT_PUBLIC) return null
        val host = hostOf(url).ifEmpty { "该主机" }
        return "端点「$host」使用明文 HTTP 且可从公网访问，API Key 会以明文经过沿途网络。" +
            "请把地址改成 https，或改成本机 / 局域网地址（如 192.168.x.x、10.x.x.x）；" +
            "服务本身只支持 http 时，请在前面加一层 TLS 反向代理（Caddy / Nginx 均可）。"
    }

    /** `a.b.c.d` → 四段整数；不是合法 IPv4 返回 null（含空段、非数字、越界）。 */
    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3) return null
            if (p.any { it !in '0'..'9' }) return null
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v
        }
        return out
    }

    private fun isPrivateIpv4(a: IntArray): Boolean {
        val (x, y) = a[0] to a[1]
        return when {
            x == 0 -> true                 // 0.0.0.0/8（含未指定地址）
            x == 10 -> true                // 10/8
            x == 127 -> true               // 回环
            x == 192 && y == 168 -> true   // 192.168/16
            x == 172 && y in 16..31 -> true // 172.16/12
            x == 169 && y == 254 -> true   // 链路本地（含云厂商元数据地址）
            x == 100 && y in 64..127 -> true // 100.64/10 CGNAT
            else -> false
        }
    }

    private fun isPrivateIpv6(host: String): Boolean {
        if (host == "::1" || host == "::") return true

        // IPv4 映射 / 兼容地址：::ffff:192.168.1.5 —— 按内嵌的 IPv4 判
        val mapped = host.substringAfterLast(':')
        if (mapped.contains('.')) {
            val v4 = parseIpv4(mapped)
            if (v4 != null) return isPrivateIpv4(v4)
        }

        val first = host.substringBefore(':').ifEmpty { return false }
        val head = first.toIntOrNull(16) ?: return false
        return when {
            head in 0xfe80..0xfebf -> true  // fe80::/10 链路本地
            head in 0xfc00..0xfdff -> true  // fc00::/7 唯一本地地址（ULA）
            else -> false
        }
    }
}
