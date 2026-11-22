package com.beichen.polaris.core.llm

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * 余额查询失败 → 给人看的一句话。
 *
 * ## 起因（2026-11-22 真机截图）
 *
 * 界面上出现过 **「查询失败：null」**。`null` 不是我们写的，是
 * `Throwable.message` 为 null 时被字符串模板拼进去的：
 *
 * ```kotlin
 * onFailure = { "查询失败：${it.message}" }   // message == null → "查询失败：null"
 * ```
 *
 * 而 message 恰好为 null 的那个异常是 **`NetworkOnMainThreadException`**——
 * Android 里它是 `new NetworkOnMainThreadException()`（无参构造，无 detail message）。
 * 触发原因：`BalanceService.query()` 是唯一一个没包 `withContext(Dispatchers.IO)`
 * 的网络客户端（ClaudeClient / GeminiClient / LocalLlamaClient / 其余 20 多处都有），
 * 而它被 `rememberCoroutineScope().launch`（主线程）直接调用。
 *
 * 两条教训，都在这里落地：
 *
 * 1. **异常 message 是给人调试看的，不是给用户看的产品文案。** 它可能是 null，
 *    可能是英文堆栈式的长句，可能带内网地址。所以这里永远不直接透传 message，
 *    而是先按异常种类给一句中文，把原 message 作为括号里的补充（有才放）。
 * 2. **兜底那句话必须自证身份。** 认不出的异常至少要说清「哪个类抛的」，
 *    否则下次再出现，用户截的图仍然只是一句「查询失败」。类名比 message 可靠——
 *    它一定存在。
 *
 * 纯 JVM（不引 Android 类型，按类名分派），harness 可直接验证。
 */
object BalanceFailure {

    /** 括号里原 message 的最大长度：够看清 401/404 的响应体开头，又不至于顶满屏幕。 */
    private const val DETAIL_LIMIT = 120

    /**
     * @return 一句可以直接显示在界面上的中文失败原因。**不含 "null" 字面量**，
     *         也不会是空白（认不出时兜底为异常类名）。
     */
    fun describe(t: Throwable): String {
        val name = t::class.java.simpleName.ifBlank { t.javaClass.name }
        // message 为空白或字面量 "null" 一律视为没有：那种情况透传出去就是这次的 bug。
        val detail = t.message?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }

        val reason = when {
            // 主线程网络请求：Android 直接拒绝，跟网络好坏无关。必须说清是 App 的问题，
            // 否则用户会去换 WiFi / 换运营商，白折腾。
            name.contains("NetworkOnMainThread") ->
                "网络请求被放到了主线程，系统直接拒绝（App 自身缺陷，与网络无关）"

            // 协程取消不是失败：用户退出了页面或点了取消。query() 里已经 rethrow，
            // 正常走不到这里；留着是防「以后有人又用 runCatching 包一层」。
            t is CancellationException -> return "查询已取消"

            t is UnknownHostException ->
                "域名解析失败，检查接口地址拼写或当前网络（${detail ?: "无 DNS 响应"}）"

            // SocketTimeoutException 是 InterruptedIOException 的子类，一并归到超时。
            // "timeout" 这个词对用户零信息量，宁可换成「30 秒内没返回」；带耗时的那种
            // OkHttp message（"failed to connect to … after 10000ms"）才有价值。
            t is InterruptedIOException -> {
                val useful = detail?.takeIf { it.length > 7 && !it.equals("timeout", true) }
                "请求超时：" + (useful ?: "服务端没在 30 秒内返回")
            }

            t is ConnectException ->
                "连不上接口：${detail ?: "连接被拒绝"}"

            t is SSLException ->
                "HTTPS 握手失败：${detail ?: "证书或被中间设备拦截"}"

            name.contains("UnknownServiceException") || detail?.contains("CLEARTEXT") == true ->
                "系统禁止明文 HTTP，请把接口地址改成 https://"

            t is EOFException ->
                "服务端提前断开，响应不完整（可能是代理或网关截断）"

            detail != null && detail.startsWith("HTTP ") -> detail
            // 自己抛的几句中文（404 全试过 / 超过 1MB / 解析不出字段 / 未提供接口）
            t is IllegalStateException || t is UnsupportedOperationException ||
                t is IllegalArgumentException -> detail?.let { clip(it) } ?: "${name}（没有附加说明）"

            else -> null
        }

        if (reason != null) return finish(reason, detail)
        return "查询失败（$name）" + (detail?.let { "：${clip(it)}" } ?: "")
    }

    /** 中文原因后面挂原始 message——**长原因不再重复拼**，避免「超时：timeout」这种废话。 */
    private fun finish(reason: String, detail: String?): String =
        if (detail == null || reason.contains(detail.take(12))) reason else "$reason（${clip(detail)}）"

    private fun clip(s: String): String =
        if (s.length <= DETAIL_LIMIT) s else s.take(DETAIL_LIMIT) + "…"
}
