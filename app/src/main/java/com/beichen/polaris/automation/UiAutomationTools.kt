package com.beichen.polaris.automation

import com.beichen.polaris.core.llm.primOrNull
import com.beichen.polaris.automation.shizuku.ShizukuShell
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 自动化工具组：把 AgentAccessibilityService 的能力包装成标准 AgentTool，
 * 注册进 ToolRegistry 后，LLM 即可通过 function calling 直接操作手机。
 *
 * 全部基于 AutomationBridge 做状态校验——服务未连接或总开关未开时直接返回错误提示，
 * 不会触发任何真实动作（总开关即安全阀）。动作执行统一切到主线程，
 * 因为 Accessibility API 在主线程调用最稳定。
 *
 * T02：全部返回统一契约 ToolResult{ok, code, message, hint, data}，失败 hint 必填。
 * T03：readOnly 标注——ui_tree 只读；ui_tap/ui_long_press/ui_swipe/ui_input/ui_key/ui_click
 * 均为写操作（真实改变屏幕状态），含任一写操作的批次整批串行。
 */

private fun notReady(): ToolResult =
    ToolResult.fail(
        code = "service_not_ready",
        message = "自动化未启用：需要无障碍服务，或一条可用的调试通道（Shizuku / 无线调试）。",
        hint = "任选其一后重试：①「设置 → 自动化」开总开关并授予无障碍权限；" +
            "② 到「权限中心 → 调试通道」完成 Shizuku 授权；" +
            "③ 到「设置 → 调试通道」用配对码连一次无线调试（不需装任何 App）。" +
            "三条都不可用时再请用户手动操作，勿重复调用同一工具。"
    )

/**
 * 动作回执的等待上限（毫秒）与轮询间隔。
 *
 * 600ms 是「中端机点击后界面完成一次重绘」的经验值：比它短会误报 no_effect，
 * 比它长会让连续操作（点一下、再点一下）明显变慢。轮询只在**没有变化**时发生，
 * 界面一变就立刻返回，所以正常路径几乎不增加耗时。
 */
private const val SCREEN_CHANGE_TIMEOUT_MS = 600L
private const val SCREEN_CHANGE_POLL_MS = 80L

/** 动作已下发但界面没变时给模型的建议——重点是「别重复点同一位置」。 */
private const val NO_EFFECT_HINT =
    "动作已执行，但 ${SCREEN_CHANGE_TIMEOUT_MS}ms 内界面结构没有变化。不要重复点同一位置：" +
        "先 ui_tree 确认目标 clickable 且未被弹窗遮挡；若页面还在加载，稍等后重试；" +
        "坐标点不准时改用 ui_click 按文本/ID 点击。"

/**
 * 界面指纹：无障碍树的 JSON 哈希。
 *
 * 为什么用整棵树的哈希而不是截图：截图要走 takeScreenshot + 编码，单次几十毫秒到几百毫秒，
 * 且要处理 RecyclerView 复用导致的像素抖动；树是现成的，一次遍历即可。
 *
 * 局限（写出来，不假装没有）：树里包含时间/倒计时文本，页面有动画时指纹会持续变化，
 * 此时「没变化」判不出来——表现为漏报，而不是误报。漏报的代价只是少一句提示，
 * 比误报（把有效动作说成没生效、诱导模型重试）安全。
 */
private fun AgentAccessibilityService.uiFingerprint(): Int =
    getUiTreeSnapshot().json.hashCode()

/** 轮询到指纹变化或超时；返回是否检测到变化。调用方必须在主线程（无障碍 API 要求）。 */
private suspend fun awaitScreenChange(
    svc: AgentAccessibilityService,
    before: Int,
    timeoutMs: Long = SCREEN_CHANGE_TIMEOUT_MS
): Boolean = withTimeoutOrNull(timeoutMs) {
    while (svc.uiFingerprint() == before) delay(SCREEN_CHANGE_POLL_MS)
    true
} ?: false

/** 在主线程调用服务方法，并把 Boolean 成败转成 ToolResult（统一契约）。 */
private suspend fun runOnService(
    okCode: String,
    okMsg: String,
    failCode: String,
    failMsg: String,
    hint: String,
    /**
     * W-18：无障碍不可用时的兜底命令（走 Shizuku 的 `input`，shell uid 2000）。
     * null = 该动作没有兜底（如 ui_tree/ui_click 需要读取界面结构，input 做不到）。
     */
    shizukuCommand: (() -> String)? = null,
    /**
     * 动作回执（2026-11-18）：执行后等界面变化，没变就把结果码降级为 `<okCode>_no_effect`。
     *
     * 修前只看 `dispatchTap()` 的 Boolean——它代表**事件注入成功**，不代表界面响应。
     * 模型拿到「已点击」继续往下走，实际什么都没发生（坐标落在不可点区域、被弹窗遮挡、
     * 页面还没加载完），于是多一次 LLM 往返，还可能把同一个无效坐标再点一遍。
     * droidrun 与 Mobile-Agent-v3 都用「动作后比对屏幕」解决这件事；它们只能截图，
     * 我们有无障碍树，成本低一个数量级。
     */
    expectScreenChange: Boolean = true,
    block: suspend (AgentAccessibilityService) -> Boolean
): ToolResult {
    val svc = AutomationBridge.service
    if (!AutomationBridge.isReady() || svc == null) {
        return shizukuFallback(shizukuCommand, okCode, okMsg) ?: notReady()
    }
    return try {
        var ok = false
        var changed = false
        withContext(Dispatchers.Main) {
            // 指纹与动作必须在同一线程顺序执行：中间被别的协程插进来，
            // 「动作前」的指纹就可能已经包含动作结果，回执永远判定为「没变化」。
            val before = svc.uiFingerprint()
            ok = block(svc)
            if (ok && expectScreenChange) {
                changed = awaitScreenChange(svc, before)
            } else if (ok) {
                changed = true
            }
        }
        when {
            !ok -> shizukuFallback(shizukuCommand, okCode, okMsg)
                ?: ToolResult.fail(code = failCode, message = failMsg, hint = hint)
            changed -> ToolResult.ok(code = okCode, message = okMsg)
            else -> ToolResult.ok(
                code = "${okCode}_no_effect",
                message = "$okMsg（界面未检测到变化）"
            ).copy(hint = NO_EFFECT_HINT)
        }
    } catch (c: CancellationException) {
        // 取消绝不是失败：`withContext(Dispatchers.Main)` 是挂起点，用户点「停止」时抛的就是它。
        // 一旦被下面的 catch (e: Exception) 吞掉，不但会记成「执行异常」，还会**再发一次
        // shizukuFallback**——取消之后还去动设备，比假失败更糟（见 AppControlTools 的 W-7 F1）。
        throw c
    } catch (e: Exception) {
        shizukuFallback(shizukuCommand, okCode, okMsg)
            ?: ToolResult.fail(
                code = "exception",
                message = "执行异常：${e.message}",
                hint = "确认自动化总开关与无障碍权限仍然有效，然后重试；持续失败请告知用户。"
            )
    }
}

/**
 * W-18：无障碍不可用时，用 Shizuku（uid 2000）的 `input` 命令完成同样的动作。
 *
 * 为什么值得做：系统或 ROM 常在息屏/重装后掐掉无障碍，而 Shizuku 仍活着；
 * 此时「点一下、滑一下」不该整条链路失败。反过来，读界面（ui_tree/ui_click 的查找）
 * 只能靠无障碍，所以不给它们兜底——**做不到就如实失败**。
 *
 * @return 成功返回结果；通道不可用或命令失败返回 null（调用方回落原错误）
 */
private suspend fun shizukuFallback(
    command: (() -> String)?,
    okCode: String,
    okMsg: String
): ToolResult? {
    if (command == null) return null
    if (ShizukuShell.status() != ShizukuShell.Status.READY) return null
    val cmd = command()
    val result = withContext(Dispatchers.IO) { ShizukuShell.exec(cmd) }
    return if (result.ok) {
        AgentLogger.i("UiAutomation", "无障碍不可用，已走调试通道：$cmd")
        ToolResult.ok(code = "${okCode}_via_shizuku", message = "$okMsg（无障碍不可用，已用调试通道 input 完成）")
    } else {
        null
    }
}

/**
 * `input text` 的转义（W-18，纯函数）：空格固定转 `%s`（input 命令的约定），
 * 单引号/反斜杠按 shell 单引号规则处理，保证文本原样送进输入框。
 */
internal fun escapeInputText(raw: String): String {
    val escaped = raw
        .replace("\\", "\\\\")
        .replace("'", "'\\''")
        .replace(" ", "%s")
    return "'$escaped'"
}

private fun intArg(args: JsonObject, key: String): Int? =
    args[key]?.primOrNull()?.contentOrNull?.toIntOrNull()

private fun strArg(args: JsonObject, key: String): String? =
    args[key]?.primOrNull()?.contentOrNull

/* ------------------------------------------------------------------ */
/* ui_tree                                                             */
/* ------------------------------------------------------------------ */
class UiTreeTool : AgentTool {
    override val name = "ui_tree"
    override val description =
        "读取当前屏幕的 UI 结构（精简 JSON 数组）。每个元素含 text/desc（内容描述）/id/cls（类名）/bounds（left,top,right,bottom 像素坐标）/clickable/scrollable/editable/checked。在分析屏幕或决定点哪里之前先调用它；ui_tap/ui_click 的坐标与目标都以它为准。每次操作后界面已变，需重新调用本工具；同一界面内容未变时不要重复调用。"
    override val systemPrompt =
        "你可以通过 ui_tree 读取当前屏幕结构，并用 ui_tap/ui_long_press/ui_swipe/ui_input/ui_key/ui_click 操作手机——这是操控界面的唯一可行通道。坐标取自 ui_tree 返回的 bounds（格式 left,top,right,bottom）。每次操作后重新 ui_tree 确认界面；界面内容未变则不要重复读取。"
    override val parameters = JsonSchema.objectSchema()

    /** 只读：仅读取界面结构，无副作用。 */
    override val readOnly = true

    /**
     * 读屏 = 读隐私，必须经用户授权。
     *
     * 返回的 JSON 里是整屏文本（标题栏、聊天内容、输入框里的手机号、验证码），
     * 会原样进入模型上下文并随请求发往云端供应商。这跟 `ui_screenshot` 是同一件事，
     * 只是表现形式不同（结构 vs 像素）——目录里两者同归 `UI_SCREENSHOT` 组。
     *
     * 高频调用不会反复打扰：`ChatViewModel.requestApproval` 对本组有「本回合已批准」缓存，
     * 同一用户回合内第二次读屏直接放行；跨回合重置，新任务首次读屏仍问一次。
     */
    override val needsApproval = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val svc = AutomationBridge.service
        if (!AutomationBridge.isReady() || svc == null) {
            // W-50：无障碍不可用时改走调试通道（uiautomator dump）。
            // 这是「只开了无线调试/Shizuku」的用户能继续干活的关键——原先这里直接返回
            // notReady()，导致 ui_tree 不可用 → 模型不知道点哪 → 后续 tap 再能兜底也没用。
            val tree = ShellUiBackend.dumpTree()
                ?: return ToolResult.fail(
                    code = "service_not_ready",
                    message = "读取界面失败：无障碍服务未启用，调试通道（Shizuku / 无线调试）也不可用。",
                    // 服务在系统列表里叫「北辰 · 自动化」（AndroidManifest 的 service label）。
                    // 这里必须写这个确切名字：早前写的是内部代号，用户到设置里根本找不到。
                    hint = "任选其一：① 到系统「设置 → 无障碍」开启「北辰 · 自动化」服务；" +
                        "② 到「权限中心 → 调试通道」完成一次 Shizuku 授权（需已装 Shizuku）；" +
                        "③ 到「设置 → 调试通道」用配对码连一次无线调试（不需要装任何 App）。"
                )
            return ToolResult.ok(
                code = "ui_tree_ok_via_debug",
                message = "已通过「${tree.channelLabel}」读取界面结构（${tree.json().length} 字符，保留 ${tree.kept} 个有效节点" +
                    (if (tree.filtered > 0) "，已过滤 ${tree.filtered} 个纯容器节点" else "") +
                    "）。该通道基于 uiautomator dump，比无障碍慢 1~2 秒；操作后请重新读取。",
                data = tree.json()
            )
        }
        return try {
            // W-7 P1-1：用带陈旧窗口防护的新版抓取——刚切换窗口时根节点可能没跟上，
            // 会自动轮询等待（≤1.5s），避免模型拿到 [] 或 AgentKit 自己的界面。
            val snap = withContext(Dispatchers.Main) { svc.getFreshUiTreeSnapshot() }
            val msg = buildString {
                append("已读取当前界面结构（${snap.json.length} 字符，保留 ${snap.kept} 个有效节点")
                if (snap.filtered > 0) append("，已过滤 ${snap.filtered} 个纯容器节点")
                append("）")
                if (snap.kept == 0) {
                    // kept=0 且没抛异常：要么界面真在切换中（启动应用/跳转后的过渡态），
                    // 要么当前前台是没有可读节点的界面。给模型明确的自纠指引。
                    append("。未读到可交互节点——可能界面正在切换（如刚打开新应用/页面），稍等片刻重新调用本工具；")
                    append("若持续为空，可先用 device_foreground_app 确认前台应用，或用 ui_screenshot 直接看屏幕。")
                }
            }
            ToolResult.ok(code = "ui_tree_ok", message = msg, data = snap.json)
        } catch (c: CancellationException) {
            throw c // 同上：withContext(Dispatchers.Main) 的取消不得吞成 ui_tree_error
        } catch (e: Exception) {
            ToolResult.fail(
                code = "ui_tree_error",
                message = "读取界面失败：${e.message}",
                hint = "确认无障碍服务在线；稍后重试或改用 ui_screenshot 看屏幕。"
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* ui_tap / ui_long_press                                             */
/* ------------------------------------------------------------------ */
class UiTapTool : AgentTool {
    override val name = "ui_tap"
    /** 危险操作：真实点击屏幕，执行前必须弹窗经用户审批（L10）。 */
    override val needsApproval = true
    override val description =
        "在屏幕坐标 (x, y) 处点击一次。坐标必须取自 ui_tree 返回元素的 bounds 中心点：(left+right)/2, (top+bottom)/2。当 ui_click 按文本/ID 找不到元素时，用它按 ui_tree 的 bounds 坐标兜底点击。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("x", "integer", "点击的 X 坐标（像素）"),
        JsonSchema.prop("y", "integer", "点击的 Y 坐标（像素）")
    )

    /** 写操作：真实点击屏幕，改变设备状态。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val x = intArg(args, "x")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 x",
                hint = "ui_tap 需要整数坐标 x/y（取自 ui_tree 的 bounds，格式 left,top,right,bottom）。"
            )
        val y = intArg(args, "y")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 y",
                hint = "ui_tap 需要整数坐标 x/y（取自 ui_tree 的 bounds，格式 left,top,right,bottom）。"
            )
        return runOnService(
            okCode = "tap_ok",
            okMsg = "已在 ($x, $y) 点击",
            failCode = "tap_failed",
            failMsg = "点击失败（服务可能未激活或坐标无效）",
            hint = "先调 ui_tree 确认界面结构与 bounds 坐标，再用有效坐标重试。",
            shizukuCommand = { "input tap $x $y" }
        ) { it.tap(x, y) }
    }
}

class UiLongPressTool : AgentTool {
    override val name = "ui_long_press"
    /** 危险操作：真实长按屏幕，执行前必须弹窗经用户审批（L10）。 */
    override val needsApproval = true
    override val description = "在屏幕坐标 (x, y) 处长按约 0.6 秒。坐标来自 ui_tree 的 bounds。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("x", "integer", "长按的 X 坐标（像素）"),
        JsonSchema.prop("y", "integer", "长按的 Y 坐标（像素）")
    )

    /** 写操作：真实长按屏幕，改变设备状态。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val x = intArg(args, "x")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 x",
                hint = "ui_long_press 需要整数坐标 x/y（取自 ui_tree 的 bounds）。"
            )
        val y = intArg(args, "y")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 y",
                hint = "ui_long_press 需要整数坐标 x/y（取自 ui_tree 的 bounds）。"
            )
        return runOnService(
            okCode = "long_press_ok",
            okMsg = "已在 ($x, $y) 长按",
            failCode = "long_press_failed",
            failMsg = "长按失败",
            hint = "先调 ui_tree 确认 bounds 坐标后重试。",
            shizukuCommand = { "input swipe $x $y $x $y 600" }
        ) { it.longPress(x, y) }
    }
}

/* ------------------------------------------------------------------ */
/* ui_swipe                                                           */
/* ------------------------------------------------------------------ */
class UiSwipeTool : AgentTool {
    override val name = "ui_swipe"
    /** 危险操作：真实滑动屏幕，执行前必须弹窗经用户审批（L10）。 */
    override val needsApproval = true
    override val description =
        "从 (start_x, start_y) 滑动到 (end_x, end_y)。用于滚动列表、翻页、返回手势等。坐标来自 ui_tree 的 bounds。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("start_x", "integer", "起点 X 坐标"),
        JsonSchema.prop("start_y", "integer", "起点 Y 坐标"),
        JsonSchema.prop("end_x", "integer", "终点 X 坐标"),
        JsonSchema.prop("end_y", "integer", "终点 Y 坐标"),
        JsonSchema.prop("duration_ms", "integer", "滑动时长（毫秒，默认 300，越大越慢）")
    )

    /** 写操作：真实滑动屏幕，改变设备状态。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val x1 = intArg(args, "start_x")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 start_x",
                hint = "ui_swipe 需要 start_x/start_y/end_x/end_y 四个整数坐标（取自 ui_tree 的 bounds）。"
            )
        val y1 = intArg(args, "start_y")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 start_y",
                hint = "ui_swipe 需要 start_x/start_y/end_x/end_y 四个整数坐标（取自 ui_tree 的 bounds）。"
            )
        val x2 = intArg(args, "end_x")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 end_x",
                hint = "ui_swipe 需要 start_x/start_y/end_x/end_y 四个整数坐标（取自 ui_tree 的 bounds）。"
            )
        val y2 = intArg(args, "end_y")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 end_y",
                hint = "ui_swipe 需要 start_x/start_y/end_x/end_y 四个整数坐标（取自 ui_tree 的 bounds）。"
            )
        val dur = intArg(args, "duration_ms") ?: 300
        return runOnService(
            okCode = "swipe_ok",
            okMsg = "已从 ($x1,$y1) 滑动到 ($x2,$y2)",
            failCode = "swipe_failed",
            failMsg = "滑动失败",
            hint = "确认坐标在屏幕范围内；可先 ui_tree 确认可滚动元素的 bounds。",
            shizukuCommand = { "input swipe $x1 $y1 $x2 $y2 $dur" }
        ) { it.swipe(x1, y1, x2, y2, dur.toLong()) }
    }
}

/* ------------------------------------------------------------------ */
/* ui_input                                                           */
/* ------------------------------------------------------------------ */
class UiInputTool : AgentTool {
    override val name = "ui_input"
    /** 危险操作：向当前焦点输入框写入文本，执行前必须弹窗经用户审批（L10）。 */
    override val needsApproval = true
    override val description =
        "把文本写入当前获得焦点的输入框（如搜索框、聊天输入框）。调用前通常先 ui_tree 确认焦点在输入框，或先 ui_tap 点一下输入框。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("text", "string", "要输入的文本")
    )

    /** 写操作：向界面输入文本，改变设备状态。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = strArg(args, "text")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 text",
                hint = "ui_input 必须提供 text（要写入输入框的文本）。"
            )
        return runOnService(
            okCode = "input_ok",
            okMsg = "已输入文本：${text.take(60)}",
            failCode = "input_failed",
            failMsg = "输入失败（当前可能没有聚焦的输入框）",
            hint = "先 ui_tree 确认 editable 元素，或先 ui_tap 点一下输入框获取焦点后重试。",
            shizukuCommand = { "input text ${escapeInputText(text)}" }
        ) { it.inputText(text) }
    }
}

/* ------------------------------------------------------------------ */
/* ui_key                                                             */
/* ------------------------------------------------------------------ */
class UiKeyTool : AgentTool {
    override val name = "ui_key"
    /** 危险操作：执行 home/back/recent 系统按键，执行前必须弹窗经用户审批（L10）。 */
    override val needsApproval = true
    override val description = "执行系统级按键动作。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("key", "string", "动作类型", enum = listOf("home", "back", "recent"))
    )

    /** 写操作：执行系统按键，改变设备状态。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val key = strArg(args, "key")
            ?: return ToolResult.fail(
                code = "missing_param",
                message = "缺少 key",
                hint = "ui_key 必须提供 key，取值 home/back/recent 之一。"
            )
        // W-50：系统键同样可以走 shell（input keyevent），无障碍挂掉时不该整条失败
        val shellKey: String? = when (key.lowercase()) {
            "home" -> "KEYCODE_HOME"
            "back" -> "KEYCODE_BACK"
            "recent", "recents", "overview" -> "KEYCODE_APP_SWITCH"
            else -> null
        }
        val fallback: (() -> String)? = shellKey?.let { c -> { "input keyevent $c" } }
        return runOnService(
            okCode = "key_ok",
            okMsg = "已执行系统动作：$key",
            failCode = "key_failed",
            failMsg = "未知或失败的系统动作：$key",
            hint = "key 仅支持 home/back/recent；请用受支持的取值重试。",
            shizukuCommand = fallback
        ) { it.globalAction(key) }
    }
}

/* ------------------------------------------------------------------ */
/* ui_click                                                           */
/* ------------------------------------------------------------------ */
class UiClickTool : AgentTool {
    override val name = "ui_click"
    /** 危险操作：按文本/ID 定位并点按元素，执行前必须弹窗经用户审批（L10）。 */
    override val needsApproval = true
    override val description =
        "按「可见文本」或「resource-id 后缀」定位界面元素并点按，比 ui_tap 更语义化。至少提供 text 或 resource_id 之一。定位失败时会返回屏幕上最接近的候选列表，请用最接近的候选重试一次；仍失败再改用 ui_tap 按 ui_tree 的 bounds 中心坐标点击。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("text", "string", "元素的可见文本或内容描述（可选）"),
        JsonSchema.prop("resource_id", "string", "元素的 resource-id 后缀，如 \"btn_submit\"（可选）")
    )

    /** 写操作：真实点按界面元素，改变设备状态。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = strArg(args, "text")
        val rid = strArg(args, "resource_id")
        if (text.isNullOrBlank() && rid.isNullOrBlank()) {
            return ToolResult.fail(
                code = "missing_param",
                message = "ui_click 至少需要 text 或 resource_id 之一",
                hint = "提供元素的可见文本（text）或 resource-id 后缀（resource_id）；可先 ui_tree 查看可用元素。"
            )
        }
        val svc = AutomationBridge.service
        if (!AutomationBridge.isReady() || svc == null) {
            // W-50：无障碍不可用时，按文本/ID 定位改走调试通道（同一套匹配器，候选一致）。
            val outcome =
                if (!rid.isNullOrBlank()) ShellUiBackend.clickByResourceId(rid!!)
                else ShellUiBackend.clickByText(text!!)
            if (outcome.clicked) {
                return ToolResult.ok(
                    code = "click_ok_via_debug",
                    message = "已点击「${text ?: rid}」（无障碍不可用，已用${outcome.channelLabel ?: "调试通道"}）"
                )
            }
            val extra = if (outcome.candidates.isEmpty()) "" else "屏幕上最相近的候选：${outcome.candidates.joinToString("、")}；"
            val why = outcome.reason?.let { "原因：$it；" }.orEmpty()
            return ToolResult.fail(
                code = "click_not_found",
                message = "未找到匹配元素或点击失败（调试通道）",
                hint = extra + why + "请从候选里选最接近的「重试一次」；仍失败改用 ui_tap 按 ui_tree 的 bounds 中心坐标点击。"
            )
        }
        return try {
            val ok = withContext(Dispatchers.Main) {
                if (!rid.isNullOrBlank()) svc.clickByResourceId(rid)
                else svc.clickByText(text!!)
            }
            if (ok) {
                ToolResult.ok(code = "click_ok", message = "已点击「${text ?: rid}」")
            } else {
                // 真机第三轮 Bug B：失败必须带候选（归一化/包含/前缀都落空时，把屏幕上最相近
                // 的标签告诉模型），否则模型只能瞎换成 ui_tap 硬点坐标。
                // 真机第四轮 Bug（界面操作协议）：把「失败自纠三步」写进 hint——先用候选重试一次，
                // 再退到 ui_tap 按 bounds 坐标，避免模型转投 shell（dumpsys/am start 必因权限失败）。
                val candidates = svc.lastClickCandidates
                val extra = if (candidates.isEmpty()) "" else "屏幕上最相近的候选：${candidates.joinToString("、")}；"
                ToolResult.fail(
                    code = "click_not_found",
                    message = "未找到匹配元素或点击失败",
                    hint = extra + "请从候选里选最接近的「重试一次」；仍失败改用 ui_tap 按 ui_tree 的 bounds 中心坐标点击。" +
                        "已做归一化（去空白/全角↔半角/忽略大小写）+ 包含 + 前缀匹配；也可先 ui_tree 确认元素文本/resource-id 的最新值。"
                )
            }
        } catch (c: CancellationException) {
            throw c // 同上
        } catch (e: Exception) {
            ToolResult.fail(
                code = "exception",
                message = "执行异常：${e.message}",
                hint = "确认自动化服务在线后重试；持续失败请告知用户手动操作。"
            )
        }
    }
}
