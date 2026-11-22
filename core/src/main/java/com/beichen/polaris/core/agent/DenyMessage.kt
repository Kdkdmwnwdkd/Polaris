package com.beichen.polaris.core.agent

/**
 * 审批未通过时回给模型的文案。
 *
 * ## 为什么单独抽出来
 *
 * 这段文案直接决定模型怎么理解「被拒」。原来的两句是：
 *
 * ```
 * message: "用户拒绝了该危险操作（ui_tree）。"
 * hint:    "不要重复发起相同调用；可改换非危险工具完成，或向用户说明需要该操作的原因。"
 * ```
 *
 * 2026-11-22 真机日志里这两句把模型带沟里了：用户连拒三次读屏工具
 * （`ui_tree` / `ui_tree` / `ui_screenshot`），模型换了一个「非危险工具」还是读屏，
 * 又被拒，于是写出「我读屏幕的操作被系统拒绝（**无障碍服务没生效**）」，
 * 还给用户摆了三选一。**无障碍一直是好的**——同一台机器 30 秒前刚有一次
 * `ui_click` 按 resource-id 命中并点击成功。
 *
 * 两个诱导源都在文案里：
 *
 *  - `Capability.DANGEROUS` 是工具目录里的**审批等级枚举名**，被原样抄进用户可见语义。
 *    「读屏」不是危险，是隐私；「点一下」不是危险，是有副作用。用「危险」概括全部，
 *    模型只能往「这工具本身有问题」上想。
 *  - 「可改换非危险工具完成」是给**有副作用**的工具写的退路。套在读屏上，等于告诉模型
 *    「读屏这条路可以绕」，它绕去 `ui_screenshot`，还是读屏，还是被拒——两次之后
 *    只剩「设备能力不可用」一个解释。
 *
 * ## 分档规则
 *
 * 只分一类：**读屏组**（`GROUP_UI_SCREENSHOT`，含 `ui_tree` / `ui_screenshot`）。
 * 这一类的拒绝必须明说「与无障碍是否开启无关」，因为它俩最容易被误读成能力探测失败。
 * 其余工具沿用通用文案，只把「危险」换成「该操作」——不夸大、不缩小。
 *
 * ## 为什么 `channelFailed` 要说「这不是用户拒绝」
 *
 * 审批通道异常时原 code 也是 `approval_denied`，日志与模型侧都分不清「用户拒了」
 * 和「通道崩了」。code 暂不动（下游有按 code 匹配的逻辑），但至少让文案说真话：
 * 否则事后翻日志会把系统故障记成用户操作，正是我们最怕的那种脏数据。
 *
 * ## 零依赖
 *
 * 只依赖 [ApprovalGrouping]（纯数据），JVM harness 可直接编、直接测。
 */
object DenyMessage {

    /** 一条给模型的失败结果文案。`message` 说发生了什么，`hint` 说下一步怎么办。 */
    data class Text(val message: String, val hint: String)

    /** 读屏组：拒绝只代表「这一步没被授权」，不代表读屏通道不可用。 */
    private const val SCREEN_READ_HINT =
        "这是审批层面的拒绝：用户只是没同意这一步，与无障碍服务是否开启无关。" +
            "不要据此判断无障碍状态，也不要自行打开无障碍设置页去「修」它。" +
            "不要重复发起相同调用；直接向用户说明你需要看屏幕的原因。"

    /** 其余工具：保留「不要重试」的硬约束，删掉「改换非危险工具」这个绕行诱导。 */
    private const val GENERIC_HINT =
        "不要重复发起相同调用；如确有其他路径可完成，先向用户说明理由再试。"

    /** 该工具是否属于「读屏」——被拒最容易被误读成设备能力故障的那一类。 */
    fun isScreenRead(toolName: String): Boolean =
        ApprovalGrouping.groupOf(toolName) == ApprovalGrouping.GROUP_UI_SCREENSHOT

    /** 用户点了「拒绝」（含超时无人回应，见调用方对超时的单独处理）。 */
    fun denied(toolName: String): Text =
        if (isScreenRead(toolName)) {
            Text("用户没有允许读取屏幕内容（$toolName）。", SCREEN_READ_HINT)
        } else {
            Text("用户拒绝了该操作（$toolName）。", GENERIC_HINT)
        }

    /** 当前通道根本没有审批界面（后台通道 / 无浮层），一律拒绝。 */
    fun noApprover(toolName: String, reason: String): Text = Text(
        "该操作需要用户确认，但$reason，已拒绝执行（$toolName）。",
        "不要重复发起相同调用；请回到主对话中直接调用该工具，由用户确认后再执行。"
    )

    /** 审批通道自身出错。**不是**用户拒绝，文案必须区分开。 */
    fun channelFailed(toolName: String): Text = Text(
        "审批通道出错，已拒绝执行（$toolName）。",
        "这不是用户拒绝，也不是能力不可用；不要重复发起相同调用，请回报审批通道异常，由用户确认后再试。"
    )
}
