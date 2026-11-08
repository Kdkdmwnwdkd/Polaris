package com.beichen.polaris.core.llm

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/*
 * 连接失败诊断（P1，2026-10-21）。
 *
 * ## 为什么要有这个文件
 * 「测试连接」失败时，旧实现只按状态码给一句 `Key 无效/无权限` 或 `客户端错误`。
 * 真机上用户看到的是一句技术描述，下一步该干什么仍然要靠猜——尤其 429（限流）
 * 被误当成「Key 错了」，用户会反复去重新生成 Key，问题却不在那里。
 *
 * 这里把「失败信号 → 是谁的问题 → 下一步点哪」收敛成纯函数：
 *  - 输入：HTTP 状态码 + 响应体片段，或一个异常；
 *  - 输出：[Diagnosis]（结论 + 一句建议 + 一个可执行的补救动作）。
 *
 * 纯函数化（同 [CapabilityChain] / [PermissionGuide] 的做法）：不碰网络、不碰 Context，
 * 可在 JVM harness 里把整张状态码矩阵跑一遍，不必真机逐个制造故障。
 */

/** 失败归因：回答「这次是谁的问题」。 */
enum class FailureKind {
    /** 没有失败。 */
    NONE,

    /** Key 本身的问题：无效、无权限、余额不足。 */
    KEY,

    /** 限流 / 并发超限：不是 Key 错，等一会儿就好。 */
    RATE_LIMIT,

    /** 模型名或端点路径不对（404 / 400 提到 model）。 */
    MODEL,

    /** Base URL 写错：域名解析不到、路径不对。 */
    ENDPOINT,

    /** 网络不通：超时、连接被拒、TLS 失败。 */
    NETWORK,

    /** 服务端 5xx：不是用户配置的问题。 */
    SERVER
}

/** 失败后可直接执行的动作（UI 据此渲染一个按钮，不再只是「给句建议」）。 */
enum class RecoveryAction {
    /** 无需动作。 */
    NONE,

    /** 去供应商控制台拿 / 换 Key。 */
    GET_KEY,

    /** 从端点拉取可用模型列表，挑一个对准。 */
    PICK_MODEL,

    /** 原样重试一次（限流 / 服务端抖动）。 */
    RETRY
}

/**
 * 一次诊断结论。
 *
 * @param kind 归因
 * @param suggestion 给用户看的一句话（说清「是谁的问题」，不含 HTTP 术语堆砌）
 * @param action 可直接点的补救动作
 */
data class Diagnosis(
    val kind: FailureKind,
    val suggestion: String,
    val action: RecoveryAction
)

/**
 * HTTP 非 2xx 的诊断。
 *
 * @param code HTTP 状态码
 * @param body 响应体片段（已脱敏；用于区分「400 因为模型名」这类同码不同因）
 */
fun diagnoseHttpFailure(code: Int, body: String = ""): Diagnosis {
    val lower = body.lowercase()
    return when {
        code == 401 || code == 403 -> Diagnosis(
            FailureKind.KEY,
            "Key 无效或没有该模型的调用权限：确认粘贴时没夹带空格 / 零宽字符，" +
                "并到控制台确认这把 Key 已开通该模型。",
            RecoveryAction.GET_KEY
        )

        // 402 是 DeepSeek / 部分聚合平台的「余额不足」，归到 KEY（用户要去控制台处理），
        // 不能只说「客户端错误」——那样用户会去改 Base URL。
        code == 402 -> Diagnosis(
            FailureKind.KEY,
            "账户余额不足（402）：到控制台充值或换一把有余额的 Key。",
            RecoveryAction.GET_KEY
        )

        code == 429 -> Diagnosis(
            FailureKind.RATE_LIMIT,
            "触发限流（429）：不是 Key 错了。等 30–60 秒再试；免费额度、并发上限、" +
                "同一 Key 多端同时用都会报这个。",
            RecoveryAction.RETRY
        )

        code == 404 -> Diagnosis(
            FailureKind.MODEL,
            "端点或模型不存在（404）：先点「选择模型」从服务端拉一份可用列表，" +
                "确认模型名与 Base URL 是同一个平台的。",
            RecoveryAction.PICK_MODEL
        )

        // 400 是「请求被拒」，绝大多数情况是模型名不被该端点接受，其次是参数不被支持。
        // 用响应体里的 model 关键字区分，避免把模型问题误导成网络问题。
        code == 400 && lower.contains("model") -> Diagnosis(
            FailureKind.MODEL,
            "模型名不被接受（400）：到「选择模型」里挑一个该端点实际提供的模型。",
            RecoveryAction.PICK_MODEL
        )

        code == 400 -> Diagnosis(
            FailureKind.ENDPOINT,
            "请求被拒绝（400）：检查 Base URL 是否少了或多写了路径段（多数平台需要 /v1），" +
                "以及自定义请求体是否覆盖了必填字段。",
            RecoveryAction.NONE
        )

        code in 500..599 -> Diagnosis(
            FailureKind.SERVER,
            "服务端错误（$code）：不是你配置的问题，稍后重试或换一家供应商。",
            RecoveryAction.RETRY
        )

        code in 400..499 -> Diagnosis(
            FailureKind.ENDPOINT,
            "请求被拒绝（$code）：检查 Base URL 与模型名是否属于同一平台。",
            RecoveryAction.NONE
        )

        else -> Diagnosis(
            FailureKind.NETWORK,
            "未预期的响应（$code）：检查网络后重试。",
            RecoveryAction.RETRY
        )
    }
}

/**
 * 网络异常的诊断。
 *
 * 顺序有意义：`UnknownHostException` 必须先于通用 `IOException` 判断，
 * `SSLException` 与 `ConnectException` 的处置完全不同（前者多半是协议/证书，
 * 后者是「对方没在听」）。
 */
fun diagnoseException(t: Throwable?): Diagnosis = when (t) {
    is UnknownHostException -> Diagnosis(
        FailureKind.ENDPOINT,
        "域名解析失败：Base URL 的域名写错了（或当前网络屏蔽了它）。" +
            "自建服务要写具体 IP / 局域网地址，不能写 localhost。",
        RecoveryAction.NONE
    )

    is SocketTimeoutException -> Diagnosis(
        FailureKind.NETWORK,
        "连接超时：网络不通或代理拦截。局域网端点请确认手机与电脑在同一网段，" +
            "地址用 http:// 开头并带端口。",
        RecoveryAction.RETRY
    )

    is SSLException -> Diagnosis(
        FailureKind.ENDPOINT,
        "TLS 握手失败：地址可能该用 http:// 而不是 https://（局域网自建常见），" +
            "或中间有代理改写了证书。",
        RecoveryAction.NONE
    )

    is ConnectException -> Diagnosis(
        FailureKind.NETWORK,
        "连接被拒绝：地址能解析，但该端口没有服务在监听。检查端口号与服务是否已启动。",
        RecoveryAction.RETRY
    )

    else -> Diagnosis(
        FailureKind.NETWORK,
        "无法连接：检查网络、代理与 Base URL 是否可达。",
        RecoveryAction.RETRY
    )
}
