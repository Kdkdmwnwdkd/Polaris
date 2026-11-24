package com.beichen.polaris.ui

import com.beichen.polaris.core.agent.ApprovalGrant
import com.beichen.polaris.core.llm.FailureKind
import com.beichen.polaris.core.llm.RecoveryAction
import kotlinx.coroutines.CompletableDeferred

/*
 * 聊天界面的数据契约（3.1 第五批拆分，自 ChatViewModel.kt 原地搬出）。
 *
 * 为什么单独一个文件：这六个类型都是「UI 与宿主之间的可见形状」——审批弹窗要它、
 * 测试连接卡片要它、附件回执要它。它们**零逻辑**，却因为写在一个 3700 行的类文件头上
 * 而随那个文件一起被行数守卫冻结。
 *
 * 搬出来只改位置，不改形状：同包 top-level 声明，引用方（ApprovalHost / ChatScreen /
 * 各 Verification）一行 import 都不用动。
 */

/**
 * W-30：一条消息的翻译状态。
 *
 * @param text 译文（成功时非空）
 * @param error 失败原因（失败时非空，UI 用 error 色显示而不是假装翻出来了）
 */
data class TranslationUi(val text: String? = null, val error: String? = null)

/**
 * 一次待确认的危险工具调用（L10）：由 AgentLoop 发起、UI 弹窗、用户决策后回填。
 *
 * @param toolName 待执行的工具名（如 ui_tap）。
 * @param argsPreview 入参预览（[ApprovalPreview] 保头保尾，避免超长参数撑爆 Dialog）。
 * @param deferred UI 用它回复 AgentLoop：complete(true) 放行、complete(false) 拒绝。
 */
data class ApprovalRequest(
    val toolName: String,
    val argsPreview: String,
    val deferred: CompletableDeferred<Boolean>,
    /**
     * 本次授权的作用域（R-08）：键、展示名、能否记住。
     */
    val grant: ApprovalGrant = ApprovalGrant("", "", true)
)

/**
 * 供应商瞬时失败的「自动恢复」进行态（Phase 2 DoD③）。
 *
 * 由 [ChatViewModel.retryState] 暴露：非 null 时 UI 显示「正在恢复（n/N）· 原因」横幅，
 * 恢复成功 / 最终失败 / 用户取消时清回 null。**取消不算失败，但必须立即清状态**
 * （T05 不变量：点停止后不得残留「正在恢复」）。
 *
 * @param attempt 1-based 重试序号（第几次重试）。
 * @param max 最大重试次数（= providerRetry）。
 * @param reason 面向用户的可读原因（已脱敏，不含原始异常 message）。
 */
data class RetryState(
    val attempt: Int,
    val max: Int,
    val reason: String
)

/** 「测试连接」的诊断结果：配置页卡片直接消费。 */
data class TestResult(
    /** 是否连通（HTTP 2xx）。 */
    val ok: Boolean,
    /** HTTP 状态码；网络异常时为 -1。 */
    val code: Int,
    /** 实际发起请求的完整端点。 */
    val endpoint: String,
    /** Key 指纹：len=35 head=sk-a tail=d38d。 */
    val fingerprint: String,
    /** 响应体前 200 字（已脱敏）；异常时为异常文案。 */
    val bodyPreview: String,
    /** 针对状态码的修复建议（失败时有值）。 */
    val suggestion: String = "",
    /** 失败归因（P1，2026-10-21）：UI 据此决定给哪个补救按钮，不再只给一句建议。 */
    val kind: FailureKind = FailureKind.NONE,
    /** 可直接执行的补救动作（去拿 Key / 拉模型列表 / 重试）。 */
    val action: RecoveryAction = RecoveryAction.NONE
)

// PendingFile 已随 ComposerState 搬到 ui/ComposerState.kt——它是那个类的唯一 owner，
// 放一起才能让那边的编译闭包只剩 kotlinx.coroutines（变异门禁要单独编它）。同包，引用方无需改 import。

/**
 * 功能五（P1-4）：文件附件处理结果——三条成功路径 + 一条失败路径。
 *
 * 之所以要区分 [Text] 与 [Images]：用户需要知道「这份 PDF 是被当图片读的」
 * （影响模型能否引用原文精确字句），而不是笼统一句「已添加」。
 */
sealed interface FileAttachResult {
    /** 文本类文件已直读（[chars] = 实际取用字符数）。 */
    data class Text(val name: String, val chars: Int) : FileAttachResult

    /** PDF 已渲染为 [pages] 张图片并入图片链路。 */
    data class Images(val name: String, val pages: Int) : FileAttachResult

    /** 不支持的类型 / 读取失败——[reason] 直接展示给用户。 */
    data class Failed(val reason: String) : FileAttachResult
}
