package com.beichen.polaris.core.agent

import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.UserMessage

/**
 * 一次 LLM 请求的只读视图。
 *
 * @param messages 只发给模型的那份历史（**不写回**会话：取消/落盘/UI 仍用完整历史）
 * @param dropped 条数是否减少（剥图不改条数，因此不算 dropped）
 * @param folded 减少的部分是否换成了 `[早前对话已折叠]` 摘要
 */
data class RequestView(
    val messages: List<ChatMessage>,
    val dropped: Boolean,
    val folded: Boolean
)

/**
 * 把「完整历史 + 预算」变成「请求视图」的唯一编排点。
 *
 * ## 为什么这三步必须按这个顺序
 *
 * `pruneImages` → `fitToBudget`，**先剥图再压历史**。反过来（先压历史再剥图）的
 * 后果不是「多丢一条」，而是**按错误的体积判据丢**：旧截图的 base64 有 6~12 万字符，
 * `messageTokens` 对图片按 900 计价、不按字符计价，所以顺序反了之后 fit 看到的是
 * 「这张图值 900 token」而不是「这张图占 12 万字符」——听起来没问题，但旧图被剥掉后
 * 每张只剩约 20 token，先剥图能让 fit 多留下整整一步的真实结果。真机上的表现是
 * 「明明上下文没满，它却忘了上一步做了什么」。
 *
 * 顺序写在这一层、由断言钉住，是因为两处调用都在 `ContextBudget` 里各自看着都合理，
 * 只有「连起来」才看得出错。
 *
 * ## 为什么不做成 object 里的私有方法
 *
 * 这个编排依赖 `ChatMessage` 与 `ContextBudget`，二者都零 Android 依赖，所以能整体
 * 搬进 kotlinc + JUnit 的判据闭包（`ContextBudgetVerification.kt` 只能跑 harness，
 * 进不了变异门禁）。预算值仍由调用方算——`budgetForProvider` 需要 providerId 与
 * 模型窗口，那是 `RealAgentLoop` 的接线职责，不是这里该知道的。
 */
object RequestViewBuilder {

    /**
     * @param budgetTokens 已按通道算好的预算（本地 2k / 云端按窗口收敛）。必须为正。
     * @param keepRecentImages 保留最近几条工具截图的图片；负数 = 关闭裁剪。
     * @param maxMessages 条数上限。
     * @param anchorIndex 本轮输入在 [messages] 里的下标（W-49：不传则退回首条 UserMessage）
     */
    fun build(
        messages: List<ChatMessage>,
        budgetTokens: Int,
        keepRecentImages: Int,
        maxMessages: Int,
        anchorIndex: Int? = null
    ): RequestView {
        require(budgetTokens > 0) { "上下文预算必须为正：$budgetTokens" }
        val pruned = ContextBudget.pruneImages(
            messages,
            keepLast = keepRecentImages,
            anchorIndex = anchorIndex
        )
        val fitted = ContextBudget.fitToBudget(
            pruned,
            budgetTokens = budgetTokens,
            maxMessages = maxMessages
        )
        return RequestView(
            messages = fitted,
            // 条数比较用 pruned 而不是 messages：剥图不改条数，拿 messages 比也成立，
            // 但用 pruned 能把「上一段真的动了手」这件事留在比较里，语义更直白。
            dropped = fitted.size < pruned.size,
            folded = fitted.any { it is UserMessage && it.content.startsWith(ContextBudget.FOLD_MARK) }
        )
    }
}
