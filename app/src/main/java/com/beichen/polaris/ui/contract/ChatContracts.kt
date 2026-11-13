package com.beichen.polaris.ui.contract

import android.net.Uri
import androidx.compose.runtime.Stable
import com.beichen.polaris.core.ConversationSummary
import com.beichen.polaris.core.ModelProfile
import com.beichen.polaris.core.TokenUsage
import com.beichen.polaris.core.UiItem
import com.beichen.polaris.core.agent.AskUserBroker
import com.beichen.polaris.core.agent.PlanBroker
import com.beichen.polaris.core.PromptItem
import com.beichen.polaris.skill.ModelConfig
import com.beichen.polaris.ui.ApprovalRequest
import com.beichen.polaris.ui.FileAttachResult
import com.beichen.polaris.ui.PendingFile
import com.beichen.polaris.ui.RetryState
import com.beichen.polaris.ui.TranslationUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 对话页契约（R-14）。
 *
 * ## 为什么对话页要拆成 7 个接口再合成一个
 *
 * 对话页用了 51 个成员，是 18 个屏幕里最多的。如果只给它一个 51 成员的接口，那
 * 什么也没解决——它依旧是「一个新接口，里面装着半个 ViewModel」。
 *
 * 拆开的依据不是「按行数平摊」，而是**状态变化的驱动源**：
 *
 * | 子接口 | 变了谁会重组 |
 * |---|---|
 * | [ChatStream] | 每一帧流式 token |
 * | [ChatTurnState] | 一轮开始/结束、审批/计划/追问挂起 |
 * | [ChatTurnActions] | 不因状态变，用户点按钮 |
 * | [ChatApprovalActions] | 同左 |
 * | [ChatComposer] | 打字、贴图、选文件 |
 * | [ChatMessageTools] | 翻译/收藏，单条消息级 |
 * | [ChatContextBar] | 顶栏的模型与会话 |
 *
 * 拆完仍是**一次传一个参数**（[ChatScreenContract] 全继承），因为 Compose 里
 * 传 7 个参数并不会让重组更准——真正决定重组的是读的是哪条 `StateFlow`。
 * 拆的意义在于：每个子接口能单独被假的实现替代，写完一条交互不用把整个 App 拉起来。
 */
@Stable
interface ChatStream {
    val messages: StateFlow<List<UiItem>>
    val isRunning: StateFlow<Boolean>
    val streamDraft: StateFlow<String?>
    val reasoningDraft: StateFlow<String?>
    val sessionUsage: StateFlow<TokenUsage>
    val translatingIndex: StateFlow<Int?>
    val translations: StateFlow<Map<Int, TranslationUi>>
}

/** 一轮的挂起态：中断提示、截断提示、重试、审批、计划、追问。 */
@Stable
interface ChatTurnState {
    val interruptedNotice: StateFlow<String?>
    val truncationNotice: StateFlow<String?>
    val retryState: StateFlow<RetryState?>
    val pendingApproval: StateFlow<ApprovalRequest?>
    val planPending: StateFlow<PlanBroker.Plan?>
    val askUserPending: StateFlow<AskUserBroker.Question?>
    fun dismissInterruptedNotice()
    fun dismissTruncationNotice()
}

/** 回合级命令：发、停、重来、换候选、从某轮删、从某轮重问。 */
@Stable
interface ChatTurnActions {
    fun send()
    fun stop()
    fun regenerate()
    fun switchVariant(turnIndex: Int, delta: Int)
    fun deleteFromTurn(turnIndex: Int)

    /** 以第 [turnIndex] 轮的用户文本为起点重新提问（会截断其后内容）。 */
    fun reaskFromTurn(turnIndex: Int, text: String)

    /**
     * 语音输入前申请麦克风。
     *
     * 返回 **null = 拿到**，非 null = 被拒的原因（直接给用户看）。
     *
     * 为什么是「返回一句话」而不是抛异常或返回布尔：调用点在 Compose 的按钮回调里，
     * 那里唯一能做的就是弹个提示。让 UI 去分辨 `ClaimResult` 的类型、再拼一句人话，
     * 等于把仲裁的语义又摊回界面层——那正是这次要收掉的东西。
     *
     * 成功时**播报已经被自动停掉**（Capture 抢占 Speak），调用方不需要先调停播报。
     */
    fun beginVoiceCapture(): String?

    /** 语音输入结束（拿到结果、取消、失败都要调，否则麦克风会被永久占住）。 */
    fun endVoiceCapture()
}

/** 审批 / 计划的四种答复 + 追问选择。 */
@Stable
interface ChatApprovalActions {
    fun approveOnce()
    fun approveAlways()
    fun approvePlan(planId: String)
    fun rejectPlan(planId: String, reason: String)
    fun denyApproval()
    fun answerAskUser(questionId: String, choice: String)
}

/**
 * 输入区。
 *
 * [input] 暴露成 [MutableStateFlow] 而不是 `StateFlow` + `onInputChange`：输入框
 * 需要「读到当前值再追加」（语音追加、@ 文件补全都是读改写），拆成回调会让这两处
 * 必须各带一份本地副本，与真实值不同步——这是 W-31 的原始缺陷。
 */
@Stable
interface ChatComposer {
    val input: MutableStateFlow<String>
    val pendingImages: StateFlow<List<String>>
    val pendingFiles: StateFlow<List<PendingFile>>
    suspend fun attachImageFromUri(uri: Uri): Boolean
    suspend fun attachFileFromUri(uri: Uri): FileAttachResult
    fun removePendingImage(url: String)
    fun removePendingFile(name: String)
}

/** 单条消息上的工具：翻译、收藏。 */
@Stable
interface ChatMessageTools {
    fun translateMessage(index: Int, text: String, target: String = "中文")
    fun clearTranslation(index: Int)
    fun toggleBookmark(role: String, text: String, timeMs: Long): Boolean
    fun isBookmarked(role: String, text: String): Boolean
}

/** 顶栏与「这条消息属于哪个会话」：模型档案、上下文窗口、提示词入口。 */
@Stable
interface ChatContextBar {
    val activeConversationId: StateFlow<String?>
    val generatingConvId: StateFlow<String?>
    val activeProfileId: StateFlow<String>
    val modelReady: StateFlow<Boolean>
    val config: StateFlow<ModelConfig>
    val localActive: StateFlow<String>
    val profiles: StateFlow<List<ModelProfile>>
    val conversations: StateFlow<List<ConversationSummary>>
    val prompts: StateFlow<List<PromptItem>>
    fun switchProfile(id: String)
    fun newConversation()
    fun insertPromptIntoInput(content: String)
    fun workspacePaths(): List<String>
}

/** 对话页实际拿到的全部能力，7 个子接口的合成。 */
@Stable
interface ChatScreenContract :
    ChatStream, ChatTurnState, ChatTurnActions, ChatApprovalActions,
    ChatComposer, ChatMessageTools, ChatContextBar
