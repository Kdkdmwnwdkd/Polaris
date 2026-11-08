package com.beichen.polaris.core.capability

/*
 * 权限引导模型（P0，2026-10-21）。
 *
 * ## 为什么单独一个文件
 * 权限中心页（ui/PermissionsScreen.kt）回答的是「我现在有什么」；引导页回答的是
 * 「我要用起来，还差什么、点哪儿、点了能多做什么」。后者是**产品逻辑**，不是界面细节，
 * 所以抽成纯函数：输入一份设备状态快照，输出固定的 5 条引导项 + 一句能力总结。
 *
 * 纯函数化的收益与 [CapabilityChain] 一致：不碰 Context、不读系统设置、可在 JVM harness
 * 里直接断言「五项检测的缺失/就绪矩阵」，不必上真机。Android 侧的探测（无障碍是否连、
 * 通知监听是否勾选、AppOps 是否允许、电池是否白名单、Shizuku 四态）由调用方探测后填入
 * [GuideSnapshot]。
 *
 * ## 分级口径
 * 与 [CapabilityChain] 的降级链一致：**无障碍是硬门槛**（没有它 `control_screen` 全链不可用），
 * 通知 / 使用情况只影响「感知」，Shizuku 是 `run_shell` 的首选后端，电池白名单只影响长任务存活。
 * 因此 [GuideItem.required] 只有无障碍一项为 true，其余标为可选增强——不拿可选能力拦用户。
 */

/** 引导页的五个检查项（顺序即教学顺序：先能用，再好用）。 */
enum class GuideKey(val wire: String, val title: String) {
    /** 无障碍服务：硬门槛，Agent 操作屏幕的唯一路径。 */
    ACCESSIBILITY("accessibility", "无障碍服务"),

    /** 通知访问：读最近通知（敏感通知监听，非 Android 13 的 POST_NOTIFICATIONS）。 */
    NOTIFICATION("notification", "通知访问"),

    /** 使用情况访问：读当前前台应用。 */
    USAGE("usage", "使用情况访问"),

    /** 电池优化白名单：长任务不被系统掐断。 */
    BATTERY("battery", "电池优化白名单"),

    /** 调试通道（Shizuku）：后台 shell，`run_shell` 的首选后端。 */
    SHIZUKU("shizuku", "调试通道（Shizuku）")
}

/**
 * Shizuku 四态（与 `automation/shizuku/ShizukuShell.UiState` 同构）。
 *
 * 这里不直接引用那个枚举，是为了让本文件保持零 Android 依赖（ShizukuShell 依赖 rikka.* 与 Context）。
 * 调用方做一次映射：`ShizukuShell.UiState -> ShizukuStage`。
 */
enum class ShizukuStage { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

/**
 * 设备状态快照（由调用方探测后填入；纯数据，可相等比较）。
 *
 * @param accessibility 无障碍服务是否已连接（`AutomationBridge.service != null`）
 * @param notificationListener 通知监听是否已勾选（`enabled_notification_listeners` 含本服务）
 * @param usageAccess 使用情况访问是否已授予（AppOps `OPSTR_GET_USAGE_STATS` 允许）
 * @param batteryUnrestricted 是否已在电池优化白名单（`PowerManager.isIgnoringBatteryOptimizations`）
 * @param shizuku Shizuku 四态
 */
data class GuideSnapshot(
    val accessibility: Boolean = false,
    val notificationListener: Boolean = false,
    val usageAccess: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val shizuku: ShizukuStage = ShizukuStage.NOT_INSTALLED
) {
    /** 某一项是否已就绪（Shizuku 只有 READY 算就绪）。 */
    fun granted(key: GuideKey): Boolean = when (key) {
        GuideKey.ACCESSIBILITY -> accessibility
        GuideKey.NOTIFICATION -> notificationListener
        GuideKey.USAGE -> usageAccess
        GuideKey.BATTERY -> batteryUnrestricted
        GuideKey.SHIZUKU -> shizuku == ShizukuStage.READY
    }
}

/**
 * 一条引导项。
 *
 * @param granted 当前是否已就绪
 * @param required 是否硬门槛（只有无障碍为 true）；可选能力缺失不阻塞使用
 * @param works 开启后能做什么（一句，用户视角）
 * @param missing 现在缺什么（一句，说清代价，不吓人）
 * @param actionLabel 按钮文案（按缺什么给什么：安装 / 打开 / 授权 / 开启）
 */
data class GuideItem(
    val key: GuideKey,
    val granted: Boolean,
    val required: Boolean,
    val works: String,
    val missing: String,
    val actionLabel: String
)

/**
 * 由快照生成 5 条引导项（纯函数，同样输入恒等输出）。
 *
 * 顺序固定为教学顺序，**不做「缺项置顶」**——引导页要建立的是「能力地图」，
 * 顺序乱跳会让人找不到自己刚看过的那一项；缺项置顶是权限中心（排查页）的规则。
 */
fun buildGuideItems(snapshot: GuideSnapshot): List<GuideItem> = listOf(
    GuideItem(
        key = GuideKey.ACCESSIBILITY,
        granted = snapshot.accessibility,
        required = true,
        works = "Agent 能读取当前界面，替你点击、滑动、输入。Android 11+ 的截图也走这条链路，" +
            "不用再单独授权录屏。",
        missing = "Agent 只能聊天，不能替你操作手机；读取界面和截图都不可用。",
        actionLabel = "去开启"
    ),
    GuideItem(
        key = GuideKey.NOTIFICATION,
        granted = snapshot.notificationListener,
        required = false,
        works = "Agent 能读最近通知，回答「刚才有没有新消息」。",
        missing = "Agent 不知道有没有新通知。",
        actionLabel = "去开启"
    ),
    GuideItem(
        key = GuideKey.USAGE,
        granted = snapshot.usageAccess,
        required = false,
        works = "Agent 能读到当前前台应用，知道你在用哪个 App。",
        missing = "Agent 不知道你此刻在用哪个 App。",
        actionLabel = "去开启"
    ),
    GuideItem(
        key = GuideKey.BATTERY,
        granted = snapshot.batteryUnrestricted,
        required = false,
        works = "降低息屏或切到后台后被系统限制的概率（极长任务仍建议保持前台）。",
        missing = "息屏或切到后台后，长任务更容易被系统限制。",
        actionLabel = "去设置"
    ),
    GuideItem(
        key = GuideKey.SHIZUKU,
        granted = snapshot.shizuku == ShizukuStage.READY,
        required = false,
        works = "Agent 可用 shell（uid 2000）在后台清理文件、停应用、改系统设置，全程不跳屏。",
        missing = when (snapshot.shizuku) {
            ShizukuStage.NOT_INSTALLED -> "未安装。不装也能用：无障碍能完成大部分界面操作，但没有后台 shell。"
            ShizukuStage.NOT_RUNNING -> "已安装但没启动。Shizuku 每次重启手机后都要重新「启动」一次。"
            ShizukuStage.NO_PERMISSION -> "已启动但未授权，在 Shizuku 弹窗里选「允许」即可。"
            ShizukuStage.READY -> "已就绪。"
        },
        actionLabel = when (snapshot.shizuku) {
            ShizukuStage.NOT_INSTALLED -> "一键安装"
            ShizukuStage.NOT_RUNNING -> "打开 Shizuku"
            ShizukuStage.NO_PERMISSION -> "去授权"
            ShizukuStage.READY -> "已就绪"
        }
    )
)

/** 已就绪项数（进度用）。 */
fun readyCount(items: List<GuideItem>): Int = items.count { it.granted }

/** 是否还有硬门槛未满足（无障碍）。 */
fun hasBlockingGap(items: List<GuideItem>): Boolean =
    items.any { it.required && !it.granted }

/**
 * 当前能力总结（一句话，给引导页头部）。
 *
 * 只描述**此刻真能做什么**，不描述「还差什么」——差什么由列表逐项说，头部负责给结论。
 */
fun capabilitySummary(items: List<GuideItem>): String {
    val byKey = items.associateBy { it.key }
    val canOperate = byKey[GuideKey.ACCESSIBILITY]?.granted == true
    val canSense = byKey[GuideKey.NOTIFICATION]?.granted == true ||
        byKey[GuideKey.USAGE]?.granted == true
    val canShell = byKey[GuideKey.SHIZUKU]?.granted == true

    return when {
        canOperate && canShell -> "Agent 现在能替你操作手机，也能在后台跑命令。"
        canOperate -> "Agent 现在能替你操作手机。"
        canSense -> "Agent 现在只能感知（通知 / 前台应用），还不能替你点屏幕。"
        else -> "Agent 现在只能对话，还不能感知或操作手机。"
    }
}
