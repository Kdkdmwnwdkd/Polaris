package com.beichen.polaris.automation

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.core.agent.NotificationFilter
import com.beichen.polaris.core.agent.NotificationScope
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject

/**
 * device_notifications：读取最近捕获的系统通知（L3 感知层）。
 *
 * Agent 借此“感知”手机上正在发生的事（新消息、日程提醒、App 推送等），
 * 用于主动告知用户或在执行任务前了解上下文。需先在「自动化」页开启通知访问权限。
 *
 * ## 审计 F15：读哪个应用，得说出来
 *
 * 修前本工具没有参数，一次返回 `NotificationStore.all`——最近 60 条、**全部应用**，
 * 而审批键固定 `private_read:device_notifications` 且可记忆。用户为看一条微信点了
 * 「本次会话不再询问」，换来的授权是「本会话内任意次读全部应用」。
 *
 * 现在 `packages` 说清读谁：
 *  - 点名了 → 只读这些应用，授权按包名集合记忆（同意微信 ≠ 同意短信）；
 *  - 没点名 → 读全部，但那样**每次都要确认**（`NotificationScope.memorizable` 恒 false）。
 *
 * 过滤与授权键共用 [NotificationScope.parse]：同一个入参得到同一个范围，
 * 不存在「弹窗写着微信、实际把 60 条都读走」的空档。
 */
class DeviceNotificationsTool : AgentTool {
    // 必须是字面量：ApprovalGroupingVerification 用正则从源码里扫工具名与
    // needsApproval 的对应关系，写成常量引用会让它扫不到、误判成「死元数据」。
    override val name = "device_notifications"
    override val description =
        "读取设备最近收到的系统通知列表（含来源 App 包名、标题、正文、时间）。用于感知手机正在发生的事（新消息、提醒、推送等），或在执行任务前了解上下文。需在「自动化」页开启通知访问权限。" +
            "建议用 packages 指定要读的应用（最多 ${NotificationScope.MAX_PACKAGES} 个）；不指定则读取全部应用，且每次都要用户确认。"
    override val systemPrompt =
        "你可以用 device_notifications 读取设备最近的通知，感知手机上正在发生的事（如新消息、提醒）。当用户问“有没有新消息/刚才来了什么通知”或需要上下文时调用它。" +
            "用户只关心某个应用时，用 packages 传该应用的包名（如 [\"com.tencent.mm\"]），不要一次读全部——读全部会让用户每次都要点确认。"
    override val parameters = JsonSchema.objectSchema(
        "packages" to JsonSchema.Property(
            type = "array",
            description = "只读这些应用的通知（Android 包名，如 com.tencent.mm）。" +
                "最多 ${NotificationScope.MAX_PACKAGES} 个。不传 = 读取全部应用（每次都会单独确认）。",
            items = JsonSchema.Property(type = "string", description = "应用包名")
        ),
        required = emptyList()
    )

    /** 只读：仅读取已捕获的通知缓存，无副作用。 */
    override val readOnly = true

    /**
     * 审计 F9：通知正文里有验证码、聊天内容、银行推送——读一次等于把用户此刻的手机
     * 摊开在上下文里，而这些内容随时会随下一次模型请求离开本机。
     *
     * 与 `clipboard` 同属一档（`Approval.PRIVATE_READ`），但按工具分开记：
     * 同意读剪贴板不等于同意读通知。F15 起再按应用集合分开记。
     */
    override val needsApproval = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val filter = NotificationScope.parse(args)
        if (filter is NotificationFilter.Invalid) {
            return ToolResult.fail(
                code = "notifications_bad_scope",
                message = "读取范围无法识别：${filter.reason}",
                hint = "packages 传应用包名数组，例如 [\"com.tencent.mm\"]，最多 " +
                    "${NotificationScope.MAX_PACKAGES} 个；不确定包名先用 app_list 查。" +
                    "不传 packages 则读取全部应用（每次都会确认）。"
            )
        }

        val list = when (filter) {
            is NotificationFilter.Named -> NotificationStore.byPackages(filter.packages)
            else -> NotificationStore.all
        }
        if (list.isEmpty()) {
            // 点名读取时不说「其他应用有多少条」：用户只授权了这几个应用，
            // 连「别的应用有通知」这件事都不该顺带告知。
            val where = when (filter) {
                is NotificationFilter.Named ->
                    "指定的 ${filter.packages.size} 个应用（${filter.packages.sorted().joinToString("、")}）"
                else -> "设备"
            }
            return ToolResult.ok(
                code = "notifications_empty",
                message = "当前$where 没有捕获到通知（请确认已在「自动化」页开启通知访问权限，并等待新通知到达）。",
                data = ""
            )
        }

        val arr = JSONArray()
        for (r in list.reversed()) {
            arr.put(
                JSONObject().apply {
                    put("package", r.packageName)
                    put("title", r.title)
                    put("text", r.text)
                    put("time", r.postTime)
                }
            )
        }
        val scopeText = when (filter) {
            is NotificationFilter.Named ->
                "来源限于 ${filter.packages.sorted().joinToString("、")}"
            else -> "全部应用"
        }
        return ToolResult.ok(
            code = "notifications_ok",
            message = "共捕获 ${list.size} 条通知（$scopeText；按时间倒序）",
            data = arr.toString(2)
        )
    }
}
