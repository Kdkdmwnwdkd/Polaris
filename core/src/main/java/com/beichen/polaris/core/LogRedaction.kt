package com.beichen.polaris.core

import com.beichen.polaris.core.tool.ToolCatalog

/**
 * 日志**该写什么、不该写什么**的判据（审计 F12）。
 *
 * ## 为什么单独一个文件
 *
 * 它原本长在 `AgentLogger.kt` 里。而 `AgentLogger` 虽然自己不 import android，
 * 却通过 `ConfigSanitizer → ModelConfig → SkillProtocol`（后者 import
 * `android.content.Context`）把 Android 拖进了整条编译依赖链——于是「哪些字段该打码」
 * 这个纯判断只能在设备上验证，跑到 CI 里的只有一个 grep。
 *
 * 抽出来之后，本文件与 [ToolCatalog] 都是零依赖纯 Kotlin，`run-log-user-content-verification.sh`
 * 能直接构造输入、读出真实结果、逐条断言。**能被行为验证的判据才是有判据。**
 *
 * [AgentLogger] 保留同名转发方法，调用点不用改。
 */
object LogRedaction {

    /** 脱敏替换串。 */
    const val MASK = "***"

    /**
     * 敏感信息脱敏规则（大小写不敏感）。
     * 顺序很重要：先掩码长串凭据（sk-/Bearer），再掩码 key=value 形态，避免残留片段。
     */
    private val RULES: List<Regex> = listOf(
        Regex("""sk-[A-Za-z0-9_\-]{8,}""", RegexOption.IGNORE_CASE),
        Regex("""Bearer\s+[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE),
        Regex("""api[_-]?key"?\s*[:=]\s*"?[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE),
        Regex("""token"?\s*[:=]\s*"?[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE),
        Regex("""password"?\s*[:=]\s*"?\S+""", RegexOption.IGNORE_CASE)
    )

    /**
     * 内容天然携带隐私/凭据、但**不需要审批**的工具：输出同样不该落盘。
     *
     * 与下面按审批真源派生的那部分合并使用。必须声明在 [sensitiveTools] 之前——
     * object 的属性按声明顺序初始化，反过来会读到 null。
     */
    private val EXTRA_SENSITIVE: Set<String> = setOf(
        // 工作区内文件免审批（F7 只管工作区外），但内容一样是用户的
        "read_file",
        // 只给包名，F9 判定不必弹窗；但仍属「你在用哪个 App」，不进磁盘日志
        "device_foreground_app",
        // 页面正文；用户可能已登录，内容不等于「公开网页」
        "web_snapshot",
        "web_eval"
    )

    /**
     * 内容天然携带隐私/凭据的工具：其入参与返回值**只记长度，不记原文**，
     * 否则 http_request 的 headers/body（含 API Key）、ui_input 的密码、剪贴板、
     * 通知正文（验证码/私信）、read_file 的文件内容都会明文落到磁盘上的日志里。
     *
     * ## 为什么从 ToolCatalog 派生（审计 F12）
     *
     * 修前这是一张手写的 7 项名单，而「哪个工具的入参与输出会带用户数据」这件事，
     * [ToolCatalog] 已经用 `approval` 字段回答过一遍了——凡是需要审批的，正是因为
     * 它的参数或输出值得看一眼。两张表各自维护的结果是**静默不同步**：
     *
     *  - `ocr_image` 刚被认定与 `ui_screenshot` 同档（都是读屏幕，F9b），审批照做了，
     *    日志白名单没跟上——OCR 出来的屏幕文字照样落盘；
     *  - `shell_run` / `shell_background` / `container_toolchain` 的输出是**任意内容**
     *    （`cat` 出来的文件、`env` 打出来的环境变量），从来不在名单里；
     *  - `write_file` / `edit_file` 的参数是文件内容的前 300 字符。
     *
     * 而这种失效方式极隐蔽：日志看起来一切正常，只是「多记了一点」；直到用户点
     * 「导出诊断包」求助，那点内容才跟着发出去——**用户为了求助而泄露**。
     *
     * 现在一半派生自审批真源（新增工具自动纳入，不需要谁记得回来加一行），
     * 一半是手工补充的 [EXTRA_SENSITIVE]。派生为主、补充为辅：前者会随真源长大，
     * 后者不会。
     *
     * 判据刻意选「需审批即脱敏」而不去逐个甄别「这个工具的输出敏不敏感」：
     * `ui_tap` 的日志因此会变成「结果已脱敏(长度=18)」，少了一点诊断信息，
     * 但换来一条**没有例外可钻**的边界。例外集一旦开口，下一个工具就会掉进去。
     */
    val sensitiveTools: Set<String> =
        ToolCatalog.tools.filter { it.approval != null }.map { it.name }.toSet() +
            EXTRA_SENSITIVE

    /** 该工具的入参与输出是否「只记长度、不记原文」。 */
    fun isSensitiveTool(name: String): Boolean = name in sensitiveTools

    /**
     * 把 ApiKey / Bearer / api_key / token / password 等敏感片段替换为 [MASK]。
     *
     * @param s 原始文本；为空或正则异常时原样返回，保证「旁路不影响主流程」。
     */
    fun sanitize(s: String): String {
        if (s.isEmpty()) return s
        var out = s
        for (rule in RULES) {
            out = runCatching { rule.replace(out, MASK) }.getOrDefault(out)
        }
        return out
    }

    /**
     * URL 进日志前先裁掉 query 与 fragment（审计 F12）。
     *
     * `?code=`（OAuth 回调）、`?access_token=`、`?token=`、图床签名——全都长在 query 里。
     * 一条「页面加载完成」的 DEBUG 日志就够把授权码带出本机，而它看起来完全无害。
     * 保留 `scheme://host/path` 已经足够回答「打开了哪个页面」。
     */
    fun redactUrl(url: String): String =
        url.substringBefore('?').substringBefore('#')
}
