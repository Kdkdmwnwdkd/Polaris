package com.beichen.polaris.tools

import com.beichen.polaris.core.AgentTool
import kotlinx.serialization.json.JsonObject

/**
 * 给任意工具加一道「必须先确认」的前置条件（P1-3 第二阶段）。
 *
 * 住在 `tools` 而不是 `pet`：它是对**任意工具**的通用装饰，与桌宠没有耦合。
 * 放在 `pet` 的后果是装配器（`tools/ToolAssembler`）要反向 import 界面入口，
 * 而装配器必须能被任何入口调用（含无 UI 的后台服务），架构守卫 [H] 钉住这一点。
 *
 * ## 为什么是装饰器而不是改原工具
 *
 * `app_launch` 在主界面是被允许**直接执行**的：用户就在屏幕前，模型开个应用他看得见，
 * 挡错了划一下返回就行。桌宠不一样——它是个悬浮球，用户说完「打开微信」就去等微信了，
 * 而气泡挡在微信上面。同一个工具，两个场景的风险不对等。
 *
 * 改原工具的 `needsApproval` 会让主界面跟着弹窗（那是一次全局行为变更，得单独论证）；
 * 包一层则把差异留在**调用点**：谁需要确认，谁自己声明。这与 [Consent] 的来源一致——
 * 通道的能力由通道自己表态，不由工具的类定义替它决定。
 *
 * ## 为什么两个都覆盖
 *
 * [AgentTool.needsApproval] 是工具级默认，`needsApprovalFor(args)` 是参数级细化
 * （W-79：`shell_run` 的 `ls` 免审、`rm -rf` 必审）。只覆盖前者，工具自带的参数级
 * 判断会把包装的意图盖掉——正好是「包装了但没生效」这种最难查的 bug。两个都返回 true，
 * 语义就一句话：**这个实例的每一次调用都要问**。
 */
class ConfirmRequiredTool(
    private val inner: AgentTool
) : AgentTool by inner {

    /** 写操作：包装只用于有副作用的工具，读工具不需要确认。 */
    override val readOnly = false

    override val needsApproval = true

    override fun needsApprovalFor(args: JsonObject): Boolean = true
}
