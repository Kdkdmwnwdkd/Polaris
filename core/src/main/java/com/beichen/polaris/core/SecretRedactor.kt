package com.beichen.polaris.core

/**
 * 敏感信息脱敏（纯函数，Android 无关，可被 JVM harness 逐条断言）。
 *
 * ## 为什么需要
 *
 * 崩溃报告会被完整写进 `filesDir/crash/`，而且用户很可能把文件直接发出去求助
 * （贴到 issue、发到群里、上传给分析工具）。而 `Throwable.message` 里经常夹着：
 *
 * - `Authorization: Bearer sk-xxxx`——HTTP 客户端抛异常时把整个请求头带进消息；
 * - `.../v1/chat/completions?key=sk-xxxx`——URL 拼错、重定向失败时 OkHttp/HttpURLConnection
 *   常把请求 URL 原样写进 `IOException("unexpected url: ...")`；
 * - `"api_key": "sk-xxxx"`——JSON 解析异常把原文片段带出来。
 *
 * 崩一次就等于把用户的 Key 落盘一次，且这份文件会被保留 10 份。
 *
 * ## 设计约束
 *
 * 1. **只认形状，不认上下文**：崩溃上下文里没有配置信息可查（进程正在死），
 *    所以只能靠正则识别「像密钥的东西」。
 * 2. **宁可少遮，不可乱遮**：过宽的正则会把堆栈里的类名、行号、方法签名糊掉，
 *    让报告失去唯一价值。因此不碰裸的长 hex（40 位 commit sha 和 base64 内容
 *    在堆栈里都合法），只处理有明确前缀或明确键名的。
 * 3. **幂等**：遮过的文本再遮一次不变。崩溃处理链里可能重复调用，不能层层叠加。
 */
object SecretRedactor {

    /** 遮罩占位符。带方括号，便于人眼区分「这里原来有值」与「本来就没值」。 */
    const val MASK: String = "***"

    /**
     * 有序规则表：**顺序有语义**。
     *
     * 先按「键名 = 值」整体处理（能连 `Bearer ` 前缀一起保住），
     * 再处理裸 `Bearer`/`Basic`，最后处理自带前缀的 token 与 URL query。
     * 反过来做的话，`authorization: Bearer sk-x` 会先被裸 Bearer 规则截一段，
     * 键名规则再匹配到 `Bearer ***`，输出成 `authorization: ***`——前缀没了，
     * 人看不出这是哪种认证方式。
     */
    private val RULES: List<Pair<Regex, String>> = listOf(
        // 1) 键名 + 分隔符 + 值。覆盖头部、环境变量、JSON 字段、URL query 之外的所有写法。
        //    值里排除空白、引号、逗号、分号、花括号：这些是「值的边界」，
        //    否则遇到 `"password": {"a":1}` 会把整个对象吞掉，报告就不可读了。
        //    首字符还排除 `*` 并对 Bearer/Basic 加负向断言——这是**幂等**的关键：
        //    第一次遮完是 `Authorization: Bearer ***`，再遮一次若让 $4 退回去匹配裸
        //    `Bearer`，就会变成 `Authorization: *** ***`，报告越处理越糊。
        Regex(
            """(?i)((?:authorization|proxy-authorization|api[_-]?key|apikey|access[_-]?token|""" +
                """refresh[_-]?token|client[_-]?secret|secret[_-]?key|private[_-]?key|password|""" +
                """passwd|pwd|token|secret|cookie))("?\s*[:=]\s*"?)((?:bearer|basic)\s+)?""" +
                """((?!(?:bearer|basic)\b)[^\s"',;&{}*][^\s"',;&{}]{5,})"""
        ) to "\$1\$2\$3$MASK",

        // 2) 裸 Bearer/Basic（不在 key=value 上下文里，如 `failed with Bearer eyJhbGci...`）
        Regex("""(?i)\b(bearer|basic)\s+([A-Za-z0-9._~+/=\-]{12,})""") to "\$1 $MASK",

        // 3) 自带前缀的 token：前缀本身就是「这是密钥」的声明，遮掉更安全。
        Regex(
            """(?i)\b(?:sk-[A-Za-z0-9_\-]{12,}|ghp_[A-Za-z0-9]{20,}|""" +
                """github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9\-]{10,}|""" +
                """AIza[0-9A-Za-z_\-]{30,})"""
        ) to MASK,

        // 4) URL query 里的凭据（`?key=` / `&token=` / `&sig=`）。规则 1 的键名表里
        //    有 token/secret，但 `key` 和 `sig` 太泛不能进那张表（会误伤 `key=value` 这类
        //    正常的 Map 打印），只在 query 上下文里认。
        Regex("""(?i)([?&](?:key|token|access_token|api_key|sig|signature|secret)=)([^\s&"']{6,})""") to "\$1$MASK"
    )

    /** 对任意文本做脱敏。null 安全，永不抛。 */
    fun redact(text: String?): String {
        // 注意别写成 `text.isNullOrEmpty()`：那是扩展函数，不会把 `text` 智能转换成
        // 非空类型，后面 pattern.replace(out, ...) 会因 String? 编译失败。
        if (text == null || text.isEmpty()) return ""
        var out: String = text
        for ((pattern, replacement) in RULES) {
            out = runCatching { pattern.replace(out, replacement) }.getOrDefault(out)
        }
        return out
    }
}
