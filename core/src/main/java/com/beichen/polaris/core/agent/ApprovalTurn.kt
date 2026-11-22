package com.beichen.polaris.core.agent

/**
 * 一个用户回合内的审批记账。
 *
 * 只记两件事，都只为「不要连着打扰用户」：
 *
 *  - **截图缓存**：本回合批准过 `ui_screenshot` 后不再问（W-8 P1-C）。W-23 之前的真机
 *    日志里「看一眼点一下」被对话框反复打断。
 *  - **超时后不再弹卡**：本回合已经有一张卡没人回应时，后续需要审批的调用直接拒，
 *    30 秒都不让人等第二遍。
 *
 * ## 为什么这段要从 ViewModel 搬出来
 *
 * 2026-11-22 那次「超时等于是拒绝」的修改全部落在 `ChatViewModel.requestApproval` 里，
 * 一个函数连着加了十几行三态分支——而那个文件有 3221 行的冻结上限，加一行就红。
 * 更要紧的是：**这些是纯状态机，没有一行需要 Android**，留在 ViewModel 里等于
 * 天然放弃 JVM 判据（想验「超时后第二张卡还会不会弹」就得跑真机）。
 *
 * 搬出来之后两份状态都可以直接 `record(...)` 断言，ViewModel 只剩接线。
 *
 * 线程语义：审批在 `approvalMutex` 内串行执行，`send()` 入口重置发生在上锁之前。
 * 两个字段用 `@Volatile` 保证跨线程可见（重置 vs 记录）。
 */
class ApprovalTurn(
    /**
     * 界面提示通道（一句会自动消失的提示条）。**「应用自己说真话」的落点**：
     * 用户连拒两次读屏后，那句话得由 App 说，不能指望模型——2026-11-22 真机上
     * 模型把「用户拒了三次」解释成了「无障碍服务没生效」，还让用户去设置页修。
     */
    private val onAdvice: (String) -> Unit = {}
) {

    /** 本回合是否已批准过截图（`ui_screenshot`）。 */
    @Volatile
    var screenshotApproved: Boolean = false
        private set

    /** 本回合是否已经出现过一次「审批卡无人回应」。 */
    @Volatile
    var timedOut: Boolean = false
        private set

    /** 本回合读屏类（`ui_tree` / `ui_screenshot`）被拒的次数。 */
    private var screenReadDenials = 0

    /** 新回合开始：全部归零。跨回合不继承——上个回合没人理，不影响这一回合。 */
    fun reset() {
        screenshotApproved = false
        timedOut = false
        screenReadDenials = 0
    }

    /**
     * 本回合已经超时过：后续需要审批的调用**不再弹卡**。
     *
     * 真机原状：卡片 30 秒消失 → 模型以为被拒 → 换个工具再试 → 又一张卡。
     * 用户只是没在看屏幕，却被连着问三次。
     */
    fun shouldSkipDialog(): Boolean = timedOut

    /**
     * 记一次结果，返回要拼进日志的措辞（`true/false/null` 三态各一句）。
     *
     * 连续两次拒绝读屏时顺带调 [onAdvice] 说一句：**这是授权问题，不是无障碍坏了**。
     * 阈值取 2 而不是 3：第一次可以当成误触，第二次之后用户明显是在拒绝这条路。
     *
     * @param toolName 工具名（用于判断是否读屏组、以及日志措辞）
     * @param outcome 审批结果：允许 / 拒绝 / **null = 时限内无人回应**
     * @param screenshotGroup 本次是否为截图组（只有它享受回合缓存）
     */
    fun record(toolName: String, outcome: Boolean?, screenshotGroup: Boolean): String = when (outcome) {
        true -> {
            if (screenshotGroup) screenshotApproved = true
            // 只要中间允许过一次，「连续拒绝」就断了，计数归零。
            screenReadDenials = 0
            "允许"
        }
        false -> {
            if (DenyMessage.isScreenRead(toolName) && ++screenReadDenials >= 2) {
                onAdvice(
                    "你已连续 $screenReadDenials 次拒绝查看屏幕。这只是没给授权，" +
                        "与无障碍开关无关——需要它看屏幕时，在确认卡上选「允许一次」。"
                )
            }
            "拒绝"
        }
        null -> {
            timedOut = true
            screenReadDenials = 0
            "超时（时限内无人回应），本轮后续不再弹卡"
        }
    }
}
