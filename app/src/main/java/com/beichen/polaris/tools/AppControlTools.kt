package com.beichen.polaris.tools

import com.beichen.polaris.core.llm.primOrNull
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.JsonSchema
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.tool.ActivityTargets
import com.beichen.polaris.core.ToolResult
import com.beichen.polaris.automation.DeviceContextStore
import com.beichen.polaris.automation.deviceContextEverUpdated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * Phase 1 补口 · 应用操控工具集（AppControlTools）。
 *
 * 动机（用户点名场景）：用户要「让它打开快手，它就能打开」——Agent 必须能操控整台手机的
 * 全部应用，而不是只在一个应用里帮忙。能力盘点后，看界面（ui_tree）、操作界面
 * （ui_tap/ui_input/ui_swipe/ui_key/ui_click/ui_long_press）、感知前台
 * （device_foreground_app）、感知通知（device_notifications）都已具备，唯独缺「启动应用」
 * 这一最基础动作（全仓此前无 getLaunchIntentForPackage）。本文件补上三件套：
 *
 *   - app_list   （只读）：列出用户可见（有 launcher 入口）的已安装应用；
 *   - app_launch （写）  ：按名称/包名启动任意应用，零权限零依赖（Intent + NEW_TASK）；
 *   - app_stop   （写）  ：停止应用，优先 shell 通道（am force-stop），不可用则诚实降级。
 *
 * 设计约束（防冲突 + 可测性）：
 *   - 本文件**不改动 tools/BuiltInTools.kt**；注册入口由本文件提供的
 *     [registerAppControlTools] 承担，注册行由主理人统一补进 ChatViewModel。
 *   - 「名称/包名解析」抽成 internal 纯函数（[resolveAppTarget]），JVM harness 可直接断言
 *     「唯一命中 / 歧义 / 无命中」三类结果，无需真实 PackageManager。
 *
 * 并发标注（T03）：app_list 只读（readOnly=true）；app_launch/app_stop 为写操作
 * （readOnly=false），含任一写的批次整批串行。
 */

/* ------------------------------------------------------------------ */
/* 纯函数层：解析 / 过滤 / 格式化（JVM 可测，无 Android 依赖）              */
/* ------------------------------------------------------------------ */

/**
 * 一条「可启动应用」条目（纯数据，便于 harness 用假数据构造）。
 *
 * @param label 应用显示名（如「快手」）
 * @param packageName 应用包名（如 "com.smile.gifmaker"）
 * @param isSystem 是否为系统应用（ApplicationInfo.FLAG_SYSTEM）
 */
internal data class AppEntry(
    val label: String,
    val packageName: String,
    val isSystem: Boolean = false
)

/** app_list 默认返回条数上限。 */
internal const val APP_LIST_DEFAULT_LIMIT: Int = 30

/** app_list 返回条数硬上限（钳制防刷屏）。 */
internal const val APP_LIST_MAX_LIMIT: Int = 100

/**
 * app_launch 启动后的沉降等待（毫秒，W-7 P0-2）。
 *
 * 取 1 秒的经验值：
 * startActivity 后系统窗口切换需要时间，先沉降再确认前台，避免 ui_tree 读到旧窗口。
 */
internal const val POST_LAUNCH_SETTLE_MS: Long = 1_000L

/**
 * 把用户传入的 limit 钳制到 [1, [APP_LIST_MAX_LIMIT]]：
 * 缺省 → [APP_LIST_DEFAULT_LIMIT]；越界（含 0/负数/超上限）→ 夹到边界。
 *
 * 纯函数：同样入参两次调用结果相等。
 */
internal fun clampAppListLimit(raw: Int?): Int {
    val v = raw ?: APP_LIST_DEFAULT_LIMIT
    return v.coerceIn(1, APP_LIST_MAX_LIMIT)
}

/**
 * 目标解析结果（唯一命中 / 歧义候选 / 无命中）。
 *
 * 「唯一命中才启动」是硬约束：调用方在 [Ambiguous] / [NotFound] 分支**绝不**执行启动，
 * 分别返回候选列表或可自纠 hint。
 */
internal sealed interface AppResolution {
    /** 唯一命中，可直接用 [entry] 启动/停止。 */
    data class Unique(val entry: AppEntry) : AppResolution

    /** 歧义：多个候选匹配，需让模型/用户从中选择，不得擅自启动。 */
    data class Ambiguous(val candidates: List<AppEntry>) : AppResolution

    /** 无命中：返回可自纠 hint（提示改用 app_list 查看准确名称/包名）。 */
    data object NotFound : AppResolution
}

/**
 * 名称/包名 → 应用条目的**纯函数**解析（本补口的核心可测点）。
 *
 * 解析策略（顺序固定，优先精确）：
 *  1. 精确包名：`target == entry.packageName` → 唯一命中。
 *  2. 精确名称（忽略大小写）：恰好 1 个 → 唯一命中；>1 个（同名不同包）→ 歧义。
 *  3. 模糊匹配：包名或名称「包含」target（忽略大小写）：
 *     - 0 个 → 无命中；
 *     - 恰好 1 个 → 唯一命中；
 *     - 多个 → 再用「以 target 开头」进一步收窄：收窄后恰好 1 个 → 唯一命中，否则歧义。
 *
 * @param target 应用名或包名（会先 trim）
 * @param candidates 候选应用列表（通常来自「可启动应用」查询）
 * @return [AppResolution]；纯函数，同样入参结果恒相等
 */
internal fun resolveAppTarget(target: String, candidates: List<AppEntry>): AppResolution {
    val t = target.trim()
    if (t.isEmpty() || candidates.isEmpty()) return AppResolution.NotFound

    // 1. 精确包名
    candidates.firstOrNull { it.packageName == t }?.let { return AppResolution.Unique(it) }

    // 2. 精确名称（忽略大小写）
    val exactName = candidates.filter { it.label.equals(t, ignoreCase = true) }
    when {
        exactName.size == 1 -> return AppResolution.Unique(exactName[0])
        exactName.size > 1 -> return AppResolution.Ambiguous(exactName)
    }

    // 3. 模糊匹配（包含）
    val fuzzy = candidates.filter {
        it.packageName.contains(t, ignoreCase = true) || it.label.contains(t, ignoreCase = true)
    }
    if (fuzzy.isEmpty()) return AppResolution.NotFound
    if (fuzzy.size == 1) return AppResolution.Unique(fuzzy[0])

    // 3.1 用「前缀命中」进一步收窄
    val prefix = fuzzy.filter {
        it.packageName.startsWith(t, ignoreCase = true) || it.label.startsWith(t, ignoreCase = true)
    }
    return if (prefix.size == 1) AppResolution.Unique(prefix[0]) else AppResolution.Ambiguous(fuzzy)
}

/**
 * 过滤 + 排序候选列表（纯函数）。
 *
 * @param all 全量可启动应用
 * @param query 名称/包名模糊匹配关键字（空/缺省 = 不过滤）
 * @param includeSystem false 时剔除系统应用
 * @return 过滤后按 label 升序排列；纯函数
 */
internal fun filterAppEntries(
    all: List<AppEntry>,
    query: String?,
    includeSystem: Boolean
): List<AppEntry> {
    val q = query?.trim().orEmpty()
    return all.asSequence()
        .filter { includeSystem || !it.isSystem }
        .filter {
            q.isEmpty() ||
                it.label.contains(q, ignoreCase = true) ||
                it.packageName.contains(q, ignoreCase = true)
        }
        .sortedWith(compareBy({ it.label }, { it.packageName }))
        .toList()
}

/**
 * 把应用列表格式化成紧凑文本（纯函数）。
 *
 * 每行 `序号. 名称 (包名)`；条目数超过 [limit] 时只显示前 [limit] 条，
 * 并追加「共 N 个，仅显示前 M 个，请用 query 收窄」的提示（防刷屏 + 引导自纠）。
 */
internal fun formatAppList(entries: List<AppEntry>, limit: Int): String {
    val shown = entries.take(limit.coerceAtLeast(0))
    return buildString {
        shown.forEachIndexed { i, e ->
            append(i + 1).append(". ").append(e.label).append(" (").append(e.packageName).append(')')
            append('\n')
        }
        if (entries.size > shown.size) {
            append("…（共 ").append(entries.size).append(" 个，仅显示前 ").append(shown.size)
            append(" 个；请用 query 收窄查询，或直接用更精确的包名调用 app_launch）")
        }
    }.trimEnd()
}

/**
 * 「无命中」失败 hint（纯函数，供 execute 与 harness 复用）：
 * 明确指向 app_list，教模型自纠。
 */
internal fun appNotFoundHint(target: String): String =
    "未找到与「$target」匹配的可启动应用。请先调用 app_list（可带 query 参数）查看已安装应用的准确名称或包名，" +
        "再用确切名称/包名重试 app_launch。"

/**
 * 「歧义」失败 hint（纯函数）：列出候选并教模型精确到包名，绝不擅自启动。
 */
internal fun appAmbiguousHint(target: String, candidates: List<AppEntry>): String {
    val names = candidates.take(10).joinToString("、") { "${it.label}(${it.packageName})" }
    return "「$target」匹配到多个应用：$names。请从候选中选择，并使用**准确包名**重新调用（歧义时不会擅自启动）。"
}

/**
 * app_launch「启动 + 前台确认」结果 → (code, message) 映射（**纯函数**，JVM harness 可断言）。
 *
 * 三分支语义（W-7 P0-2，绝不分不清「发了」和「成了」）：
 *  - confirmed=true → 硬成功 `app_launched`：已启动并确认切换到前台；
 *  - confirmed=false → `app_launched_unconfirmed`：启动意图确已发出（调用方仍按 **ok**
 *    返回），但未在前台确认到（可能被系统拦截、应用异常或无障碍服务未开启），提示模型用
 *    device_foreground_app 复核、或用 ui_tree 查看当前界面。
 * 抽出为纯函数的动机：与 [appStopResult] 同构——让结果映射在无 Android Context 的 JVM
 * harness 里可被真实断言。
 *
 * @param entry 目标应用条目（仅用于文案）
 * @param confirmed 是否已由 [DeviceContextStore.awaitForegroundPackage] 确认到前台
 * @return (code, message) 二元组；调用方包成 ok 结果（未确认也是 ok——意图确已发出）
 */
internal fun launchConfirmationResult(entry: AppEntry, confirmed: Boolean): Pair<String, String> =
    if (confirmed) {
        "app_launched" to "已启动「${entry.label}」（包名 ${entry.packageName}）并已确认切换到前台。"
    } else {
        "app_launched_unconfirmed" to
            "已发出启动「${entry.label}」的意图，但未在前台确认到（可能被系统拦截、应用异常或无障碍服务未开启）。" +
            "请用 ui_tree 查看当前界面；确有必要时再用 device_foreground_app 复核一次即可，不要反复查询。"
    }

/**
 * app_stop 降级 hint（纯函数）：shell 通道不可用或无权时，写清用户手动替代路径，
 * **不静默假装成功**。
 */
internal fun stopUnavailableHint(label: String, pkg: String): String =
    "引导用户手动停止：系统「设置 → 应用 → 找到「$label」（$pkg）→ 强制停止」。当前无可用 shell 通道" +
        "（am force-stop 需 shell/root 身份），Agent 无法直接停止应用；不要重复尝试同一调用。"

/* ------------------------------------------------------------------ */
/* Android 侧：从 PackageManager 装载「用户可见」的可启动应用              */
/* ------------------------------------------------------------------ */

/**
 * 装载「用户看得见」的可启动应用（有 Launcher 入口）——用 queryIntentActivities +
 * ACTION_MAIN/CATEGORY_LAUNCHER + [PackageManager.MATCH_ALL] 过滤，避免把几百个无入口的
 * 系统包也列出来。按包名去重（一个 App 可能声明多个 Launcher Activity）。
 *
 * 依赖 Manifest 已声明的 `QUERY_ALL_PACKAGES`（Android 11+ 包可见性），保证能看全。
 */
private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val infos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    val seen = HashSet<String>()
    val entries = ArrayList<AppEntry>(infos.size)
    for (ri in infos) {
        val pkg = ri.activityInfo?.packageName ?: continue
        if (!seen.add(pkg)) continue
        val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
        val flags = ri.activityInfo?.applicationInfo?.flags ?: 0
        val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
        entries.add(AppEntry(label = label, packageName = pkg, isSystem = isSystem))
    }
    return entries
}

/* ------------------------------------------------------------------ */
/* 工具自带 systemPrompt：教模型跨应用套路                                */
/* ------------------------------------------------------------------ */

/**
 * 跨应用操作套路（三个工具共用），先启动、再定位、再操作、必要时确认前台；
 * 并显式提醒「已在前台时不要重复启动」，避免无效动作。
 */
private const val APPCONTROL_ROUTINE: String =
    "跨应用操作套路：先用 app_launch 打开目标应用 → 等界面就绪 → 用 ui_tree 读取界面结构定位元素 " +
        "→ 用 ui_tap/ui_input/ui_click 等操作 → 需要确认是否已切到目标时用 device_foreground_app。" +
        "注意：若目标应用已在前台，不要重复 app_launch，直接操作界面；不确定目标应用的准确名称或包名时，" +
        "先用 app_list 查询。"

/* ------------------------------------------------------------------ */
/* 1. app_list（只读）                                                  */
/* ------------------------------------------------------------------ */
/**
 * app_list：列出已安装（用户可见）应用。
 *
 * 参数：query（可选，名称/包名模糊匹配）、include_system（可选，默认 false 过滤系统应用）、
 * limit（可选，默认 30，上限 100）。返回紧凑文本（名称 + 包名），超限提示收窄查询。
 */
class AppListTool(private val context: Context) : AgentTool {
    override val name = "app_list"
    override val description =
        "列出已安装的可启动应用（名称 + 包名，仅用户可见、有图标入口的应用）。支持 query 按名称/包名模糊匹配、" +
            "include_system 是否包含系统应用（默认 false）、limit 返回条数（默认 30，上限 100）。用于在启动前确认应用的准确名称或包名。"
    override val systemPrompt =
        "用 app_list 查看设备上已安装的可启动应用（名称 + 包名），在不确定目标应用名称/包名时先查它。" +
            APPCONTROL_ROUTINE
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("query", "string", "名称或包名的模糊匹配关键字（可选）"),
        JsonSchema.prop("include_system", "boolean", "是否包含系统应用，默认 false（仅列用户可见应用）"),
        JsonSchema.prop("limit", "integer", "返回条数上限，默认 30，最大 100")
    )

    /** 只读：仅查询已安装应用列表，无副作用。 */
    override val readOnly = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val query = args["query"]?.primOrNull()?.contentOrNull
            val includeSystem = args["include_system"]?.primOrNull()?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false
            val limit = clampAppListLimit(args["limit"]?.primOrNull()?.contentOrNull?.toIntOrNull())

            val all = loadLaunchableApps(context)
            val filtered = filterAppEntries(all, query, includeSystem)
            if (filtered.isEmpty()) {
                return@runCatching ToolResult.fail(
                    code = "no_apps",
                    message = "未找到匹配的可启动应用（query=${query ?: "无"}，include_system=$includeSystem）。",
                    hint = "放宽条件：去掉 query 或设置 include_system=true 后重试；也可不带参数浏览已安装应用。"
                )
            }
            ToolResult.ok(
                code = "app_list_ok",
                message = "共 ${filtered.size} 个可启动应用（显示前 ${minOf(filtered.size, limit)} 个）",
                data = formatAppList(filtered, limit)
            )
        }.getOrElse {
            ToolResult.fail(
                code = "app_list_error",
                message = "读取应用列表失败：${it.message}",
                hint = "确认已授予包查询权限（Manifest 的 QUERY_ALL_PACKAGES）后重试；持续失败请告知用户。"
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* 2. app_launch（写）                                                  */
/* ------------------------------------------------------------------ */
/**
 * app_launch：按名称/包名启动任意应用。
 *
 * 参数：target（应用名或包名）、activity（可选，指定 Activity 时直接按 ComponentName 启动）。
 * 解析策略（W-7 P0-1）：静态字典（中文名/别名）→ 候选匹配（精确包名/精确名称/模糊，唯一命中才
 * 启动；歧义返回候选；无命中返回自纠 hint）→ 包名形态兜底（形如包名直接试启）。
 * 实现零权限零依赖：PackageManager.getLaunchIntentForPackage + startActivity(FLAG_ACTIVITY_NEW_TASK)。
 */
class AppLaunchTool(private val context: Context) : AgentTool {
    override val name = "app_launch"
    override val description =
        "启动任意已安装应用。target 传应用名（如「快手」）或包名（如 com.smile.gifmaker），支持模糊匹配；" +
            "若已知具体 Activity，可传 activity 直接按组件启动。启动后会自动等待并确认前台切换，未确认时会明确提示。"
    override val systemPrompt =
        "用 app_launch 打开目标应用（零权限）。操作任意应用的第一步就是它。" + APPCONTROL_ROUTINE
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("target", "string", "要启动的应用名称或包名"),
        JsonSchema.prop("activity", "string", "可选，指定要启动的 Activity 全类名（如 com.x.MainActivity）")
    )

    /** 写操作：启动应用、真实改变设备前台状态，必须串行执行。 */
    override val readOnly = false

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val target = args["target"]?.primOrNull()?.contentOrNull
            ?: return@withContext ToolResult.fail(
                code = "missing_param",
                message = "缺少 target",
                hint = "app_launch 必须提供 target（应用名或包名，如「快手」或 com.smile.gifmaker）。"
            )
        val activity = args["activity"]?.primOrNull()?.contentOrNull

        // W-7 审核 F1：不用裸 runCatching——它会连 CancellationException 一起吞进
        // getOrElse，把用户按「停止」转成 launch_error 假失败。改用取消安全包装
        // [runCatchingRethrowCancel]：取消原样上抛（回合树尾闭合收尾），
        // 其余异常才转 launch_error（既有行为不变）。
        runCatchingRethrowCancel(
            block = {
                val apps = loadLaunchableApps(context)
                when (val r = resolveAppTargetWithDict(target, apps)) {
                    is AppResolution.Unique -> launchResolved(r.entry, activity)
                    is AppResolution.Ambiguous -> ToolResult.fail(
                        code = "ambiguous_target",
                        message = "「$target」匹配到 ${r.candidates.size} 个应用，请指定准确包名。",
                        hint = appAmbiguousHint(target, r.candidates),
                        data = formatAppList(r.candidates, r.candidates.size)
                    )
                    AppResolution.NotFound -> ToolResult.fail(
                        code = "not_found",
                        message = "未找到应用：$target",
                        hint = appNotFoundHint(target)
                    )
                }
            },
            onFailure = {
                ToolResult.fail(
                    code = "launch_error",
                    message = "启动「$target」异常：${it.message}",
                    hint = "确认应用已安装且未被停用；可先用 app_list 核对准确包名后重试；持续失败请告知用户手动打开。"
                )
            }
        )
    }

    /** 真正执行启动：优先按指定组件，其次 getLaunchIntentForPackage。 */
    private suspend fun launchResolved(entry: AppEntry, activity: String?): ToolResult {
        val pm = context.packageManager
        val intent = if (!activity.isNullOrBlank()) {
            // 2026-11-22 真机：模型给的是 `Settings$AccessibilitySettings`（少了 `Activity`），
            // 组件不存在 → launch_error。按候选顺序挑第一个真能解析的（生成规则见 ActivityTargets）。
            val picked = ActivityTargets.candidates(activity).firstOrNull { c ->
                try {
                    pm.getActivityInfo(ComponentName(entry.packageName, c), 0); true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
            } ?: activity
            Intent().setComponent(ComponentName(entry.packageName, picked))
        } else {
            pm.getLaunchIntentForPackage(entry.packageName)
        }
        if (intent == null) {
            return ToolResult.fail(
                code = "no_launch_intent",
                message = "「${entry.label}」没有可用的启动入口（无 Launcher Activity）。",
                hint = "该应用可能被停用或无界面入口；请用 app_list 核对包名，或改用 ui 通道手动操作。"
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // W-7 审核 F1：startActivity 与「沉降 + 前台确认」必须结构分离——后者
        // （[confirmLaunch]）内部 delay + 轮询全是挂起点，CancellationException
        // 继承自 Exception，一旦包进 catch (e: Exception) 会被吞成假失败：
        // UI 收到假失败、同批后续写工具（如 app_stop）还会照常真实执行一次，
        // 「取消绝不是失败」契约被破坏。组合骨架见 [launchWithConfirmation]。
        return launchWithConfirmation(entry) { context.startActivity(intent) }
    }
}

/* ------------------------------------------------------------------ */
/* W-7 审核 F1：取消传播可测缝（internal，JVM harness 经 -Xfriend-paths 断言） */
/* ------------------------------------------------------------------ */

/**
 * 取消安全的 [runCatching]（W-7 审核 F1 修复）。
 *
 * 坑：kotlin 标准库 `runCatching {}.getOrElse {}` 会连 CancellationException
 * 一并吞掉并交给 getOrElse——用户按「停止」发生在挂起段（app_launch 的沉降/轮询、
 * app_stop 的 shell 执行）时，取消被转成 `launch_error` / `stop_error` **假失败**
 * 返回，UI 收到假失败、同批后续写工具还会照常真实执行一次（回合末才重抛），
 * 「取消绝不是失败」契约被破坏。
 *
 * 本包装保证：CancellationException **原样上抛**（由 RealAgentLoop 树尾闭合收尾），
 * 其余异常（业务失败）才交 [onFailure] 转 ToolResult——与仓库
 * ToolExecutor.kt / RealAgentLoop.kt 既有同构修法一致。
 *
 * 抽成 internal 顶层函数的动机：JVM harness（-Xfriend-paths）可对
 * 「CE 上抛 / 业务异常转失败 / 正常透传」三分支直接断言，无需 Android Context。
 *
 * @param block 业务段（可含挂起点）
 * @param onFailure 非取消异常的降级处理（转 ToolResult）
 */
internal suspend fun <T> runCatchingRethrowCancel(
    block: suspend () -> T,
    onFailure: (Throwable) -> T
): T = try {
    block()
} catch (c: CancellationException) {
    // 取消绝不是失败：绝不上包装修、绝不变 Failed/launch_error，原样上抛
    throw c
} catch (e: Throwable) {
    onFailure(e)
}

/**
 * W-7 P0-2：启动意图发出后的「沉降 + 前台确认」。
 *
 * 1. **短路**：`DeviceContextStore.snapshot().foregroundPackage` 空白说明无障碍服务
 *    从未喂过数据（ui_tree 同样不可用），确认无从谈起——直接返回旧语义的 ok，
 *    避免无障碍没开时白等约 4 秒（判据纯函数见 [deviceContextEverUpdated]）。
 * 2. **沉降**：等 [POST_LAUNCH_SETTLE_MS] 毫秒，给系统窗口切换留时间。
 * 3. **前台确认**：[DeviceContextStore.awaitForegroundPackage] 轮询直到目标包名到前台。
 * 4. **三分支**（映射纯函数见 [launchConfirmationResult]）：已确认 → 硬成功；
 *    未确认 → 仍是 ok（意图已发出）但 code 区分开，引导模型用 ui_tree 看当前界面
 *    （W-8：device_foreground_app 仅作一次性复核，去掉轮询诱导）——「发了」与
 *    「成了」绝不含混。
 *
 * 取消语义（W-7 审核 F1）：本函数内部 delay + 轮询全是挂起点，被取消时
 * CancellationException 原样上抛——调用方 [launchWithConfirmation] 从结构上保证
 * 它绝不落入 catch (e: Exception)。抽成 internal 顶层（原为 AppLaunchTool 私有
 * 方法）的动机：JVM harness 可经 seam 驱动真实沉降/轮询路径做取消传播断言。
 */
internal suspend fun confirmLaunch(entry: AppEntry): ToolResult {
    if (!deviceContextEverUpdated(DeviceContextStore.snapshot().foregroundPackage)) {
        // W-8：无障碍未喂数据时不再诱导 device_foreground_app 轮询（真机日志：连查 5 次
        // + 重复 app_launch 纯烧时间）——主通道是 ui_tree，截图兜底，一句话说清。
        return ToolResult.ok(
            code = "app_launched",
            message = "已发出启动「${entry.label}」（包名 ${entry.packageName}）的意图。" +
                "可接着用 ui_tree 查看当前界面是否已切换；若持续未切换，请引导用户手动打开。"
        )
    }
    delay(POST_LAUNCH_SETTLE_MS)
    val confirmed = DeviceContextStore.awaitForegroundPackage(entry.packageName)
    val (code, message) = launchConfirmationResult(entry, confirmed)
    return ToolResult.ok(code = code, message = message)
}

/**
 * 「startActivity + 前台确认」组合骨架（W-7 审核 F1 修复的核心结构）。
 *
 * 硬约束：[try] **只包 [start]**（即 startActivity 自身）——ActivityNotFoundException /
 * SecurityException 仍转 `launch_failed`（既有行为保持）；而 [confirmLaunch]
 * （沉降 + 轮询，内部全是挂起点）**绝不入 try**：CancellationException 继承自
 * Exception，一旦落入 catch (e: Exception) 会被吞成假失败，破坏「取消绝不是失败」。
 * 从构造上消除 CE 落入 catch 的可能，而非依赖 catch 内排序。
 *
 * 抽成 internal 顶层函数的动机：JVM harness 注入假 [start]（记录调用/抛异常）、
 * 驱动**真实** [confirmLaunch]，对「start 异常转 launch_failed / start 阶段取消上抛 /
 * 确认阶段取消上抛 / 正常路径三分支透传」直接断言（无需 Android Context）。
 *
 * @param entry 目标应用条目（仅用于失败文案）
 * @param start 启动动作（生产传 `context.startActivity(intent)`；harness 传假动作）
 * @return [confirmLaunch] 的结果；[start] 抛非取消异常时转 `launch_failed`
 */
internal suspend fun launchWithConfirmation(
    entry: AppEntry,
    start: suspend () -> Unit
): ToolResult {
    try {
        start()
    } catch (c: CancellationException) {
        // 取消绝不是失败：start 途中被取消也原样上抛（防御性；生产 start 是
        // startActivity，无挂起点，正常不会抛 CE）
        throw c
    } catch (e: Exception) {
        // 仅 startActivity 自身异常转 launch_failed；确认阶段的取消/异常
        // 从结构上到不了这里（见 KDoc）
        return ToolResult.fail(
            code = "launch_failed",
            message = "启动「${entry.label}」失败：${e.message}",
            hint = "确认应用已安装且未被停用、Agent 处于前台；可改用精确包名重试；如仍失败请告知用户手动打开。"
        )
    }
    // startActivity 成功 ≠ 已切换到前台：先沉降、再确认（见 [confirmLaunch]）。
    // 本调用绝不包进上方 try——取消必须上抛给回合树尾闭合，绝不吞成假失败。
    return confirmLaunch(entry)
}

/* ------------------------------------------------------------------ */
/* 3. app_stop（写）                                                    */
/* ------------------------------------------------------------------ */

/**
 * shell 命令执行结果（纯数据）。
 *
 * @param exitCode 退出码（0 = 成功）
 * @param output 命令输出（stdout/stderr 合并，可空）
 */
data class ShellOutcome(val exitCode: Int, val output: String)

/**
 * shell 通道抽象：`am force-stop` 等系统命令需要 shell/root 身份执行。
 *
 * 默认实现 [Unavailable] 恒返回 null（表示通道不可用）——app_stop 据此走诚实降级路径。
 * 后续「自授权高权限通道」（Phase 2）或 T07/T08 终端后端就绪后，由注册处注入真实实现，
 * 本文件**零耦合**于尚未定型的终端 API。
 *
 * 本接口为公开扩展点（[registerAppControlTools] 的入口参数），供外部注入真实 shell 实现。
 */
fun interface ShellCommandRunner {
    /**
     * 执行一条 shell 命令。
     *
     * @param command 完整命令文本
     * @return 执行结果；通道不可用时返回 null
     */
    suspend fun exec(command: String): ShellOutcome?

    companion object {
        /** 默认：通道不可用（永远返回 null）。 */
        val Unavailable: ShellCommandRunner = ShellCommandRunner { null }
    }
}

/**
 * app_stop 的 shell 结果 → [ToolResult] 映射（**纯函数**，JVM harness 可直接断言）。
 *
 * 三分支语义，**绝不静默假装成功**：
 *  1. [outcome] 为 null（shell 通道不可用）→ 失败 `stop_needs_manual`，hint 指向用户手动停止路径；
 *  2. 退出码 0 → 成功 `app_stopped`（附命令输出）；
 *  3. 退出码非 0（无权限 / 命令失败）→ 失败 `stop_failed`，hint 说明需 shell/root 身份并给替代路径。
 *
 * 抽出此纯函数的动机：让「通道不可用 → 降级」「通道可用成功/失败」三条路径在**无 Android Context**
 * 的 JVM harness 里可被真实断言（`AppStopTool.execute` 依赖 PackageManager，无法在 JVM 直跑）。
 *
 * @param label 应用显示名（仅用于文案）
 * @param pkg 应用包名（仅用于文案）
 * @param outcome shell 通道执行结果；null = 通道不可用
 * @return 可直接回填给模型的 [ToolResult]
 */
internal fun appStopResult(label: String, pkg: String, outcome: ShellOutcome?): ToolResult {
    if (outcome == null) {
        // 通道不可用：明确告知需用户手动停止，不假装成功
        return ToolResult.fail(
            code = "stop_needs_manual",
            message = "未能停止「$label」：当前无可用 shell 通道执行 am force-stop。",
            hint = stopUnavailableHint(label, pkg)
        )
    }
    return if (outcome.exitCode == 0) {
        ToolResult.ok(
            code = "app_stopped",
            message = "已停止「$label」（包名 $pkg）。",
            data = outcome.output.ifBlank { null }
        )
    } else {
        ToolResult.fail(
            code = "stop_failed",
            message = "停止「$label」失败（退出码 ${outcome.exitCode}）：${outcome.output.take(200)}",
            hint = "shell 通道可能无权限执行 am force-stop（需 shell/root 身份）。" +
                stopUnavailableHint(label, pkg)
        )
    }
}

/**
 * app_stop：停止任意应用。
 *
 * 参数：target（名称/包名，解析同 app_launch）。优先走 shell 通道（`am force-stop <pkg>`）；
 * 通道不可用或执行失败时**诚实降级**为「需用户手动停止」失败结果，hint 写清替代路径，
 * 绝不静默假装成功。结果映射见纯函数 [appStopResult]。
 */
class AppStopTool(
    private val context: Context,
    private val shellRunner: ShellCommandRunner = ShellCommandRunner.Unavailable
) : AgentTool {
    override val name = "app_stop"
    override val description =
        "停止（强制关闭）指定应用。target 传应用名或包名。优先经 shell 通道执行 am force-stop；" +
            "无可用 shell 通道时会明确提示需用户手动停止，不会假装成功。"
    override val systemPrompt =
        "用 app_stop 停止目标应用（优先 shell 通道）。若返回需手动停止的失败结果，请如实告知用户并给出替代路径，" +
            "不要重复尝试同一调用。"
    override val parameters = JsonSchema.objectSchema(
        JsonSchema.prop("target", "string", "要停止的应用名称或包名")
    )

    /** 写操作：停止应用、真实改变设备状态，必须串行执行。 */
    override val readOnly = false

    /**
     * 强制停止必须经用户确认。
     *
     * `am force-stop` 是不可撤销的：目标应用正在编辑的草稿、未提交的表单、后台同步的
     * 中间状态都会丢，且应用侧没有「恢复」入口。目录里它归 `APP_MANAGE` 组，
     * 与 `app_install` / `app_uninstall` 同级——那两个一直会弹窗，只有这里漏了声明。
     */
    override val needsApproval = true

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val target = args["target"]?.primOrNull()?.contentOrNull
            ?: return@withContext ToolResult.fail(
                code = "missing_param",
                message = "缺少 target",
                hint = "app_stop 必须提供 target（应用名或包名，如「快手」或 com.smile.gifmaker）。"
            )

        // W-7 审核 F1（同构防护）：stopResolved 内的 shellRunner.exec 是挂起点
        // （真实实现走终端会话池），裸 runCatching 会把取消吞成 stop_error 假失败。
        // 与 app_launch 的 execute 同一修法：取消原样上抛，业务异常才转失败。
        runCatchingRethrowCancel(
            block = {
                val apps = loadLaunchableApps(context)
                when (val r = resolveAppTargetWithDict(target, apps)) {
                    is AppResolution.Unique -> stopResolved(r.entry)
                    is AppResolution.Ambiguous -> ToolResult.fail(
                        code = "ambiguous_target",
                        message = "「$target」匹配到 ${r.candidates.size} 个应用，请指定准确包名。",
                        hint = appAmbiguousHint(target, r.candidates),
                        data = formatAppList(r.candidates, r.candidates.size)
                    )
                    AppResolution.NotFound -> ToolResult.fail(
                        code = "not_found",
                        message = "未找到应用：$target",
                        hint = appNotFoundHint(target)
                    )
                }
            },
            onFailure = {
                ToolResult.fail(
                    code = "stop_error",
                    message = "停止「$target」异常：${it.message}",
                    hint = "可先用 app_list 核对准确包名后重试；持续失败请引导用户手动停止。"
                )
            }
        )
    }

    /** 走 shell 通道停止；不可用时诚实降级。结果映射见纯函数 [appStopResult]。 */
    private suspend fun stopResolved(entry: AppEntry): ToolResult {
        val outcome = shellRunner.exec("am force-stop ${entry.packageName}")
        return appStopResult(entry.label, entry.packageName, outcome)
    }
}

/* ------------------------------------------------------------------ */
/* 注册入口                                                             */
/* ------------------------------------------------------------------ */

/**
 * 注册应用操控三件套到 [registry]。
 *
 * 本函数是应用操控工具集的**唯一注册入口**。当前注册点为 `ChatViewModel`：
 * `registerAppControlTools(this, app, terminalShellCommandRunner(terminalPool))`——
 * app_stop 的 shell 通道已接真实会话池（见 `ShellTool.terminalShellCommandRunner`）。
 *
 * @param registry 目标工具注册表
 * @param context 应用上下文（用于 PackageManager 与 startActivity）
 * @param shellRunner app_stop 的 shell 通道；默认不可用（诚实降级）；生产由 `ChatViewModel` 注入
 *   `terminalShellCommandRunner(terminalPool)` 真实实现。
 * @return 传入的 [registry]（链式 register）
 */
fun registerAppControlTools(
    registry: ToolRegistry,
    context: Context,
    shellRunner: ShellCommandRunner = ShellCommandRunner.Unavailable
): ToolRegistry = registry.apply {
    register(AppListTool(context))
    register(AppLaunchTool(context))
    register(AppStopTool(context, shellRunner))
}
