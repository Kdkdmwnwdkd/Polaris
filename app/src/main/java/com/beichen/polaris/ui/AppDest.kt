package com.beichen.polaris.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 一级目的地全量路由表（UX v1 §1.7）：15 个值全部保留，页面都还能到。
 *
 * 用户反馈侧边栏拥挤（塞了 15 个入口），参照 RikkaHub 极简风只留 4 个常用入口在
 * 抽屉里，其余（容器 / 编辑器 / MCP / 创作工坊 / 构建 / 权限 / 技能 / 日志 /
 * 记忆 / 关于）整体收进设置页——入口搬家，路由一个不动。
 *
 * @param route 路由键（UX v1 §1.7 路由表直译，用于 rememberSaveable 持久化）。
 * @param label 菜单项文案（单行中文，§6.5.2）。
 * @param title 顶栏标题（§6.5.4；「模型」项的页面标题是「模型配置」）。
 * @param icon 24dp 线性图标（1.5dp stroke 语汇，禁 emoji）。
 */
enum class AppDest(
    val route: String,
    val label: String,
    val title: String,
    val icon: ImageVector
) {
    CHAT("chat", "对话", "对话", Icons.Outlined.Chat),
    MODEL("model", "模型", "模型配置", Icons.Outlined.ViewInAr),
    CONSOLE("console", "控制台", "控制台", Icons.Outlined.Dashboard),
    TERMINAL("terminal", "终端", "终端", Icons.Outlined.Terminal),
    CONTAINER("container", "容器", "容器", Icons.Outlined.ViewInAr),
    EDITOR("editor", "编辑器", "编辑器", Icons.Outlined.Edit),
    MCP("mcp", "MCP", "MCP", Icons.Outlined.Cloud),
    MEDIA("media", "创作", "创作工坊", Icons.Outlined.Palette),
    BUILD("build", "构建", "构建", Icons.Outlined.Build),
    PERMISSIONS("permissions", "权限中心", "权限中心", Icons.Outlined.Shield),
    /** P0（2026-10-21）：权限引导页——第一次上手时逐项走一遍，与权限中心（排查页）分工不同。 */
    PERMISSION_GUIDE("permission-guide", "权限引导", "权限引导", Icons.Outlined.Shield),
    SKILLS("skills", "技能中心", "技能中心", Icons.Outlined.Extension),
    /** W-48：插件市场（远端可装清单；与技能中心分工见 SkillMarketScreen 注释）。 */
    MARKET("market", "插件市场", "插件市场", Icons.Outlined.Public),
    WORKFLOW("workflow", "定时任务", "定时任务", Icons.Outlined.Schedule),
    LOGS("logs", "运行日志", "运行日志", Icons.Outlined.Description),
    MEMORY("memory", "记忆库", "记忆库", Icons.Outlined.Storage),
    PROMPTS("prompts", "提示词库", "提示词库", Icons.Outlined.AutoAwesome),
    BOOKMARKS("bookmarks", "收藏", "收藏", Icons.Outlined.Bookmark),
    /** W-69：全部对话——抽屉只留最近 8 条，旧的按标题找不到了，补一个完整列表页。 */
    CONVERSATIONS("conversations", "全部对话", "全部对话", Icons.Outlined.History),
    SETTINGS("settings", "设置", "设置", Icons.Outlined.Settings),
    /** W-67：外观子页——壁纸与颜色原来在设置页占掉一整屏，拆成独立页。 */
    APPEARANCE("appearance", "壁纸与颜色", "壁纸与颜色", Icons.Outlined.Palette),
    /** 语音引擎子页：桌宠的「去换引擎」要能直达（系统设置里很多机型没有这一项）。 */
    VOICE("voice", "语音识别", "语音识别", Icons.Outlined.Mic),
    /** 音色子页：播报用哪个声音。与上一项（听谁的）是两件事，所以是两条路由。 */
    VOICE_STYLE("voice-style", "音色", "音色", Icons.Outlined.VolumeUp),
    /** 录音克隆子页：录一句就把自己的声音变成一条可选音色。 */
    VOICE_CLONE("voice-clone", "克隆我的声音", "克隆我的声音", Icons.Outlined.Mic),
    /**
     * 存储明细子页：回答「我这几个 GB 都是什么」。
     *
     * 不进抽屉：它不是每天要用的入口，而是「觉得空间不对」时才来看的排查页。
     * 进了抽屉反而会让六项主导航变成七项，稀释真正高频的那几个。
     */
    STORAGE("storage", "存储空间", "存储空间", Icons.Outlined.PieChart),
    ABOUT("about", "关于", "关于", Icons.Outlined.Info);

    companion object {
        fun fromRoute(route: String): AppDest = values().firstOrNull { it.route == route } ?: CHAT
    }
}
