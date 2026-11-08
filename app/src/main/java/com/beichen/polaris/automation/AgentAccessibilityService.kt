package com.beichen.polaris.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.beichen.polaris.core.AgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.util.concurrent.Executor

/**
 * 纯函数（W-7 P1-1）：当前这次 UI 树抓取是否「陈旧到值得再等一轮」。
 * 零 Android 依赖，QA harness 可直接断言。
 *
 * 背景：app_launch 启动目标应用后立刻 ui_tree，`rootInActiveWindow` 常常还停在
 * ① null（新窗口未建起来）或 ② AgentKit 自己的窗口（审批弹窗/聊天页还没退），
 * 旧实现当场抓一次就返回，模型拿到的要么是 `[]` 要么是自己的界面，据此点的坐标全错。
 *
 * 判据：
 *  - 距上次窗口切换事件已 ≥ [maxWaitMs] → 不等（没有新窗口要等，或目标窗口早就在）；
 *  - 否则 root 为 null 或根节点就是 [selfPackage] → 值得再等一轮（目标应用窗口大概率正在路上）。
 *
 * @param rootPackage 当前活动窗口根节点包名；null = `rootInActiveWindow` 拿不到根
 * @param selfPackage AgentKit 自身包名
 * @param elapsedSinceSwitchMs 距上次窗口切换事件的毫秒数
 * @param maxWaitMs 最长等待预算（毫秒）
 */
internal fun shouldRetryForFreshWindow(
    rootPackage: String?,
    selfPackage: String,
    elapsedSinceSwitchMs: Long,
    maxWaitMs: Long
): Boolean {
    if (maxWaitMs <= 0L) return false
    if (elapsedSinceSwitchMs >= maxWaitMs) return false
    return rootPackage == null || rootPackage == selfPackage
}

/**
 * W-9：无障碍截屏所需的最低 API 级——`AccessibilityService.takeScreenshot`
 * 自 API 30（Android 11）起提供。
 */
internal const val A11Y_SCREENSHOT_MIN_API = 30

/**
 * 纯函数：当前是否**可以用无障碍截图**（W-12 起的唯一截图通道）。
 * 零 Android 依赖（API 级由调用方传入 `Build.VERSION.SDK_INT`，便于 harness 真值表断言）。
 *
 * 判据：无障碍服务就绪（用户已在系统设置里授予读屏/无障碍）且 API ≥ [A11Y_SCREENSHOT_MIN_API]。
 * W-12 起这是**唯一截图通道**（MediaProjection 已删除）。
 * 不满足则 ui_screenshot 直接返回可行动指引（不降级到任何录屏授权路径）。
 *
 * 命名从 `shouldPrefer` 改为 `canUse`：W-12 删除 MediaProjection 后不再存在「优先/兜底」
 * 两条路可选，只有一个判据——能不能用无障碍截图。
 */
internal fun canUseA11yScreenshot(a11yReady: Boolean, apiLevel: Int): Boolean =
    a11yReady && apiLevel >= A11Y_SCREENSHOT_MIN_API

/**
 * UI 节点的**纯数据镜像**（脱离 [AccessibilityNodeInfo]，可在纯 JVM 下断言）。
 *
 * 为什么要有这层：真机第三轮 Bug B 的根因在「按文本定位」的决策逻辑上，而决策不该
 * 绑死在 Android 节点对象上（不可测）。故把「取数据 → 纯函数决策 → 执行动作」三段
 * 拆开：本文件只负责① 取数据（[AgentAccessibilityService.collectSummaries]）与
 * ③ 执行（点坐标），② 全部落在 [UiNodeMatcher] 的纯函数里。
 *
 * @param index 遍历序号（稳定标识，列表下标 == index）
 * @param text 可见文本（可能为脏：带换行/空格/附带其他字）
 * @param desc contentDescription（与 text 同等参与匹配）
 * @param resourceId 短 resource-id（去掉包名前缀）
 * @param depth 树深度（0 = 根）
 * @param clickable 自身是否可点击
 * @param parentIndex 父节点 index（-1 = 根，无父）
 * @param bounds 屏幕坐标 `l,t,r,b`
 */
internal data class UiNodeSummary(
    val index: Int,
    val text: String = "",
    val desc: String = "",
    val resourceId: String = "",
    val depth: Int = 0,
    val clickable: Boolean = false,
    val parentIndex: Int = -1,
    val bounds: String = "",
    /**
     * W-50：以下四项只在「调试通道（uiautomator dump）」这条路径上用于还原节点形状，
     * 无障碍路径的 JSON 依旧直接读 [android.view.accessibility.AccessibilityNodeInfo]。
     * 给默认值是为了不动既有构造点（两条路径共用同一份节点模型，匹配器才能复用）。
     */
    val cls: String = "",
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checked: Boolean = false
) {
    /** 参与匹配/展示的标签：优先可见文本，无文本时退回 contentDescription。 */
    val label: String get() = text.ifBlank { desc }
}

/**
 * 匹配结果（纯数据）。
 *
 * @param matchIndex 命中的节点（已是「最深/最具体」的那个）；null = 未命中
 * @param clickIndex 真正用来点坐标的节点：命中节点不可点击时，是**最近的可点击祖先**；
 *   找不到可点击祖先时退回命中节点自身（等价旧行为：按其 bounds 派发手势）
 * @param candidates 未命中时给模型的候选标签（≤5，按相似度降序），用于写进 hint 自纠
 * @param strategy 命中策略（exact/contains/prefix/none/empty_query），仅供日志与断言
 */
internal data class UiMatchResult(
    val matchIndex: Int?,
    val clickIndex: Int?,
    val candidates: List<String> = emptyList(),
    val strategy: String = "none"
) {
    /** 是否命中。 */
    val matched: Boolean get() = matchIndex != null
}

/**
 * 按文本 / resource-id 定位节点的**纯函数决策器**（零 Android 依赖，QA harness 直测）。
 *
 * 修真机第三轮 Bug B：旧实现用 `text.equals(query)` 精确相等，设置页里那一项的真实文本
 * 常常带空格/换行/附带其他字（「字体大小\n默认」），精确匹配必然落空 → 工具报
 * `click_not_found` 且**没有候选**，模型只能瞎试。故改为**分级匹配**：
 *  1. 归一化后精确（去所有空白 + 全角↔半角 NFKC 归一 + 忽略大小写）；
 *  2. 包含（contains）：query 是节点文本的一部分；
 *  3. 前缀（startsWith）。
 * text 与 contentDescription 同等参与，取两者中更好的等级。
 *
 * 多命中时取「最深/最具体」：等级优先 → 深度大者优先 → 文本短者优先 → 序号小者优先。
 * 命中节点 `clickable == false` 时上溯最近的可点击祖先（否则点了没反应）。
 * 未命中时给出 ≤5 条屏幕候选（按包含关系 / 公共前缀打分），帮模型自纠。
 */
internal object UiNodeMatcher {

    /** 候选提示上限（T02 统一契约：hint 不能太长）。 */
    const val MAX_CANDIDATES: Int = 5

    /**
     * 归一化：NFKC（全角→半角）→ 去掉所有空白字符 → 转小写。
     * 「字体 大小」「Ｆｏｎｔ」这类脏文本据此与干净 query 对齐。
     */
    internal fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val sb = StringBuilder(folded.length)
        for (ch in folded) {
            if (!ch.isWhitespace()) sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * 按可见文本 / contentDescription 定位节点。
     *
     * @param nodes 节点摘要（index 与列表下标一致）
     * @param query 目标文本（来自工具参数，可能带空格/全角）
     * @param maxCandidates 未命中时的候选条数上限
     */
    internal fun matchByText(
        nodes: List<UiNodeSummary>,
        query: String,
        maxCandidates: Int = MAX_CANDIDATES
    ): UiMatchResult {
        val q = normalize(query)
        if (q.isEmpty()) {
            // query 为空（工具侧已拦截，这里是防御）：不命中，但把屏幕上前几个标签兜底
            // 列出来，模型至少知道页面上有什么
            return UiMatchResult(null, null, fallbackCandidates(nodes, maxCandidates), "empty_query")
        }
        val best = pickBest(nodes) { levelOfText(it, q) }
        if (best == null) {
            return UiMatchResult(null, null, similarCandidates(nodes, q, maxCandidates), "none")
        }
        val (index, level) = best
        return UiMatchResult(index, clickableTarget(nodes, index), emptyList(), strategyName(level))
    }

    /**
     * 按 resource-id 定位节点（短 id，去掉包名前缀）。
     * 等级：0 = 相等或以 `id:` 后缀相等，1 = 包含；其余同 [matchByText] 的选取规则。
     */
    internal fun matchByResourceId(
        nodes: List<UiNodeSummary>,
        query: String,
        maxCandidates: Int = MAX_CANDIDATES
    ): UiMatchResult {
        val q = normalize(query)
        if (q.isEmpty()) {
            return UiMatchResult(null, null, fallbackIdCandidates(nodes, maxCandidates), "empty_query")
        }
        val best = pickBest(nodes) { levelOfId(it.resourceId, q) ?: -1 }
        if (best == null) {
            return UiMatchResult(null, null, fallbackIdCandidates(nodes, maxCandidates), "none")
        }
        val (index, level) = best
        return UiMatchResult(index, clickableTarget(nodes, index), emptyList(), strategyName(level))
    }

    /**
     * 上溯最近的可点击祖先：命中节点自身可点 → 自身；否则沿 [UiNodeSummary.parentIndex]
     * 向上找第一个 `clickable == true` 的祖先；一路到根仍不可点 → 退回自身（旧行为）。
     *
     * @return 用于取 bounds 派发点击的节点 index
     */
    internal fun clickableTarget(nodes: List<UiNodeSummary>, index: Int): Int {
        val byIndex = HashMap<Int, UiNodeSummary>(nodes.size)
        for (n in nodes) byIndex[n.index] = n
        var cursor = index
        var guard = 0
        while (guard++ <= nodes.size) {
            val node = byIndex[cursor] ?: return index
            if (node.clickable) return node.index
            val parent = node.parentIndex
            // 一路到根都不可点：退回**命中节点自身**（等价旧行为：按其 bounds 派发手势）
            if (parent < 0) return index
            cursor = parent
        }
        return index
    }

    /* ------------------------- 内部：打分与选取 ------------------------- */

    /** 节点文本 / desc 对 query 的匹配等级：0 精确 / 1 包含 / 2 前缀 / -1 不命中。 */
    private fun levelOfText(node: UiNodeSummary, q: String): Int {
        val t = normalize(node.text)
        val d = normalize(node.desc)
        val candidates = ArrayList<Int>(2)
        if (t.isNotEmpty()) levelOf(t, q)?.let { candidates += it }
        if (d.isNotEmpty()) levelOf(d, q)?.let { candidates += it }
        return candidates.minOrNull() ?: -1
    }

    /**
     * [text] 对 [q] 的匹配等级（null = 不命中）：
     *  0 归一化精确 / 1 包含（`text` 含 `q`，应对「字体大小\n默认」这类脏文本）/
     *  2 前缀（`q` 以 `text` 开头，即节点文本是 query 的前缀——query 带了尾巴，
     *     如 query「字体大小 默认」vs 节点「字体大小」）。
     * 注：等级 2 必须放在 1 之后判断（1 涵盖面更广），且取「节点更短」的那一侧，
     * 这样三级才各自可达、不互相遮蔽。
     */
    private fun levelOf(text: String, q: String): Int? = when {
        text == q -> 0
        text.contains(q) -> 1
        q.startsWith(text) -> 2
        else -> null
    }

    /** resource-id 的匹配等级：0 相等（含 `:id` 后缀相等）/ 1 包含 / null 不命中。 */
    private fun levelOfId(id: String, q: String): Int? {
        if (id.isEmpty()) return null
        val n = normalize(id)
        if (n.isEmpty()) return null
        return when {
            n == q -> 0
            n.endsWith(":$q") -> 0
            n.contains(q) -> 1
            q.startsWith(n) -> 2
            else -> null
        }
    }

    /**
     * 在所有节点里挑「最深/最具体」的命中：等级优先 → 深度大 → 文本短 → 序号小。
     *
     * @return (index, level)；无命中返回 null
     */
    private fun pickBest(nodes: List<UiNodeSummary>, level: (UiNodeSummary) -> Int): Pair<Int, Int>? {
        var bestIndex: Int? = null
        var bestLevel = Int.MAX_VALUE
        var bestDepth = Int.MIN_VALUE
        var bestLen = Int.MAX_VALUE
        for (node in nodes) {
            val lv = level(node)
            if (lv < 0) continue
            val len = normalize(node.label).length
            val better = bestIndex == null ||
                lv < bestLevel ||
                (lv == bestLevel && node.depth > bestDepth) ||
                (lv == bestLevel && node.depth == bestDepth && len < bestLen)
            if (better) {
                bestIndex = node.index
                bestLevel = lv
                bestDepth = node.depth
                bestLen = len
            }
        }
        return bestIndex?.let { it to bestLevel }
    }

    /** 未命中时的候选：按「包含关系 + 公共前缀」打分，取前 [max] 条（去重、非空）。 */
    private fun similarCandidates(nodes: List<UiNodeSummary>, q: String, max: Int): List<String> {
        val seen = LinkedHashSet<String>()
        val scored = ArrayList<Pair<String, Int>>()
        for (node in nodes) {
            val label = node.label.trim()
            if (label.isBlank()) continue
            if (!seen.add(label)) continue
            val n = normalize(label)
            if (n.isEmpty()) continue
            val score = when {
                n == q -> 1_000
                n.contains(q) -> 800 - n.length.coerceAtMost(200)
                q.contains(n) -> 600 - n.length.coerceAtMost(200)
                else -> commonPrefixLength(n, q) * 10 - n.length.coerceAtMost(200)
            }
            scored += label to score
        }
        scored.sortWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
        return scored.take(max.coerceAtLeast(0)).map { it.first }
    }

    /** query 为空的兜底候选：屏幕上前 [max] 个非空标签（按树序，短者优先）。 */
    private fun fallbackCandidates(nodes: List<UiNodeSummary>, max: Int): List<String> {
        val seen = LinkedHashSet<String>()
        for (node in nodes) {
            val label = node.label.trim()
            if (label.isNotBlank()) seen.add(label)
        }
        return seen.sortedWith(compareBy<String> { it.length }.thenBy { it }).take(max.coerceAtLeast(0))
    }

    /** resource-id 未命中时的候选：屏幕上出现过的短 id（去重）。 */
    private fun fallbackIdCandidates(nodes: List<UiNodeSummary>, max: Int): List<String> {
        val seen = LinkedHashSet<String>()
        for (node in nodes) {
            if (node.resourceId.isNotBlank()) seen.add(node.resourceId)
        }
        return seen.sortedWith(compareBy<String> { it.length }.thenBy { it }).take(max.coerceAtLeast(0))
    }

    /** 两个归一化串的公共前缀长度（相似度兜底打分用）。 */
    private fun commonPrefixLength(a: String, b: String): Int {
        var i = 0
        val max = minOf(a.length, b.length)
        while (i < max && a[i] == b[i]) i++
        return i
    }

    /** 等级 → 策略名（日志/断言可读）。 */
    private fun strategyName(level: Int): String = when (level) {
        0 -> "exact"
        1 -> "contains"
        2 -> "prefix"
        else -> "none"
    }
}

/**
 * AgentKit 无障碍自动化服务（标准 Android AccessibilityService，净室原创实现）。
 *
 * 能力范围（v1）：
 *  - 抓取当前窗口 UI 树（精简 JSON）
 *  - 通过 dispatchGesture 执行 点击 / 长按 / 滑动
 *  - 向焦点输入框写入文本（ACTION_SET_TEXT）
 *  - 系统级动作 home / back / recent
 *  - 按文本或 resource-id 定位元素并点按
 *
 * 所有对外方法均 try/catch 兜底，失败时返回 false / 空数组，绝不向上抛异常，
 * 因为调用方是后台 Agent 线程，异常必须被收敛成工具结果文本。
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutomationBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // L4 主动感知：仅在窗口切换/窗口集合变化时记录前台应用，避免高频噪声。
        // 其余操作仍为 Agent 主动查询模式（被动监听仅用于喂 DeviceContextStore）。
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val pkg = event.packageName?.toString().orEmpty()
                if (pkg.isNotBlank()) DeviceContextStore.updateForeground(this, pkg)
                // W-7 P1-1：记录最近一次窗口切换时刻——ui_tree 据此判断「刚切换完窗口，
                // 根节点可能还没跟上」，见 getFreshUiTreeSnapshot / shouldRetryForFreshWindow。
                lastWindowSwitchAt = System.currentTimeMillis()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        AutomationBridge.detach(this)
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */
    /* UI 树抓取                                                           */
    /* ------------------------------------------------------------------ */

    /** 抓取当前窗口 UI 树，输出精简 JSON 数组（上限约 250 个节点）。 */
    /**
     * 读取当前界面结构为精简 JSON 数组（**已过滤纯容器节点**——hotfix9）。
     *
     * 为什么过滤：真机 19:04/19:05 实证，设置页一次 ui_tree 返回 11229~40085 字符
     * （250 个节点里绝大多数是 text/desc/id 全空且不可点的纯容器 FrameLayout/LinearLayout）。
     * ① 白白塞爆上下文、拖慢模型思考；② 关键项被淹没在噪声里，模型更容易点错。
     * 过滤规则：保留 text/desc/id 任一非空、或可点击/可滚动/可编辑/有勾选态的节点。
     * 上溯祖先与坐标定位在无障碍侧仍可查到容器，过滤不影响 ui_click 命中。
     */
    fun getUiTreeJson(): String = getUiTreeSnapshot().json

    /** [getUiTreeJson] 的结构化版：附带 kept/filtered 统计，供工具文案说明过滤了多少噪声。 */
    fun getUiTreeSnapshot(): UiTreeSnapshot {
        val root = rootInActiveWindow ?: return UiTreeSnapshot("[]", 0, 0)
        return try {
            parseRoot(root)
        } catch (e: Exception) {
            UiTreeSnapshot("[]", 0, 0)
        } finally {
            root.recycle()
        }
    }

    /**
     * **带陈旧窗口防护**的 UI 树抓取（W-7 P1-1）：启动应用/页面跳转后立刻读取时，
     * 根节点可能还没切换到目标窗口（null 或仍是 AgentKit 自己的窗口），此时按
     * [shouldRetryForFreshWindow] 轮询等待，最多 [maxWaitMs] 毫秒；超时后按当前
     * 状态抓一次返回（诚实降级，不无限等）。
     *
     * 仅在「距上次窗口切换事件很近」时才等待——没有任何窗口切换发生时（lastWindowSwitchAt
     * 为 0 或已久远）elapsed 远超 maxWaitMs，直接抓，行为与 [getUiTreeSnapshot] 一致。
     *
     * 必须在主线程调用（rootInActiveWindow 的稳定调用线程）；挂起函数，delay 让出主线程。
     */
    suspend fun getFreshUiTreeSnapshot(
        maxWaitMs: Long = FRESH_WINDOW_MAX_WAIT_MS,
        pollMs: Long = FRESH_WINDOW_POLL_MS
    ): UiTreeSnapshot {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (true) {
            val root = rootInActiveWindow
            val timedOut = System.currentTimeMillis() >= deadline
            val needWait = !timedOut && shouldRetryForFreshWindow(
                rootPackage = root?.packageName?.toString(),
                selfPackage = packageName,
                elapsedSinceSwitchMs = System.currentTimeMillis() - lastWindowSwitchAt,
                maxWaitMs = maxWaitMs
            )
            if (!needWait) {
                if (root == null) return UiTreeSnapshot("[]", 0, 0)
                return try {
                    parseRoot(root)
                } catch (e: Exception) {
                    UiTreeSnapshot("[]", 0, 0)
                } finally {
                    root.recycle()
                }
            }
            // 需要等待：先回收本次拿到的根（可能是 AgentKit 自己的陈旧窗口），再睡一轮
            root?.recycle()
            delay(pollMs.coerceAtLeast(1L))
        }
    }

    /**
     * W-9：经无障碍服务截屏（API 30+ `AccessibilityService.takeScreenshot`）——
     * **ui_screenshot 的唯一通道**（W-12 起）：用户在系统设置里开过无障碍（读屏授权）即可截图，
     * 无授权弹窗、无前台服务、无首帧等待。
     *
     * 失败（API<30 / onFailure / 厂商 ROM 抛异常）一律返回 null，由调用方
     * （[com.beichen.polaris.automation.UiScreenshotTool]）给出可行动指引。
     *
     * 在主线程调用（与 rootInActiveWindow 同线程稳定性考虑）；挂起函数——系统回调经
     * suspendCancellableCoroutine 转挂起，协程取消后回调安全失效（isActive 守卫）。
     *
     * @return 截屏位图；API < 30 / 调用抛异常 / 系统回调 onFailure → null
     */
    suspend fun takeScreenshotViaA11y(): Bitmap? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < A11Y_SCREENSHOT_MIN_API) return@withContext null
        suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    Executor { command -> command.run() },
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            if (cont.isActive) cont.resume(result.toSoftwareBitmap(), onCancellation = {})
                        }

                        override fun onFailure(errorCode: Int) {
                            AgentLogger.w("ScreenCapture", "a11y takeScreenshot onFailure: $errorCode")
                            if (cont.isActive) cont.resume(null, onCancellation = {})
                        }
                    }
                )
            } catch (t: Throwable) {
                // 厂商 ROM 可能在不支持的设备上抛异常——降级为 null，绝不逃逸
                AgentLogger.w("ScreenCapture", "a11y takeScreenshot 抛异常: ${t.message}")
                if (cont.isActive) cont.resume(null, onCancellation = {})
            }
        }
    }

/**
 * W-9 私有助手：[AccessibilityService.ScreenshotResult] → 软件位图。
 *
 * API 实证（CI 编译门 + 官方 reference）：ScreenshotResult **没有 getBitmap()**，
 * 只暴露 getHardwareBuffer() / getColorSpace() / getTimestamp()（均 API 30）。
 * 硬件缓冲须 wrapHardwareBuffer + copy(ARGB_8888) 转成软件副本后才能安全使用与回收
 * （copy 出的位图独立于缓冲生命周期；本方法 finally 关闭 HardwareBuffer）。
 * 转换任何一步失败（含厂商 ROM 异常）均回落 null，调用方返回失败指引。
 */
private fun AccessibilityService.ScreenshotResult.toSoftwareBitmap(): Bitmap? =
    try {
        val buffer = hardwareBuffer
        try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
            hw?.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            buffer.close()
        }
    } catch (t: Throwable) {
        AgentLogger.w("ScreenCapture", "a11y ScreenshotResult 转位图失败: ${t.message}")
        null
    }

    /** 把根节点解析成快照 JSON（调用方负责 recycle）。 */
    private fun parseRoot(root: AccessibilityNodeInfo): UiTreeSnapshot {
        nodeCount = 0
        filteredCount = 0
        val arr = buildJsonArray { traverse(root, this) }
        return UiTreeSnapshot(arr.toString(), nodeCount - filteredCount, filteredCount)
    }

    /** ui_tree 快照：json + 保留节点数 + 被过滤的纯容器节点数。 */
    data class UiTreeSnapshot(val json: String, val kept: Int, val filtered: Int)

    private var nodeCount = 0
    private var filteredCount = 0

    /** 节点是否「有信息量」：有文案/描述/资源 id，或本身可交互（点/滑/编/勾）。 */
    private fun informative(node: AccessibilityNodeInfo): Boolean =
        !node.text.isNullOrBlank() ||
            !node.contentDescription.isNullOrBlank() ||
            !node.viewIdResourceName.isNullOrBlank() ||
            node.isClickable ||
            node.isScrollable ||
            node.isEditable ||
            node.isChecked

    private fun traverse(node: AccessibilityNodeInfo, arr: kotlinx.serialization.json.JsonArrayBuilder) {
        if (nodeCount >= MAX_NODES) return
        nodeCount++
        if (!informative(node)) {
            filteredCount++
        } else {
            val b = Rect()
            node.getBoundsInScreen(b)
            arr.add(
                buildJsonObject {
                    put("text", node.text?.toString().orEmpty())
                    put("desc", node.contentDescription?.toString().orEmpty())
                    put("id", node.viewIdResourceName?.substringAfter('/').orEmpty())
                    put("cls", node.className?.toString()?.substringAfterLast('.').orEmpty())
                    put("bounds", "${b.left},${b.top},${b.right},${b.bottom}")
                    put("clickable", node.isClickable)
                    put("scrollable", node.isScrollable)
                    put("editable", node.isEditable)
                    put("checked", node.isChecked)
                }
            )
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverse(child, arr)
                child.recycle()
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 手势动作（dispatchGesture）                                         */
    /* ------------------------------------------------------------------ */

    private fun dispatchTap(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun tap(x: Int, y: Int): Boolean = dispatchTap(x.toFloat(), y.toFloat(), 1)

    fun longPress(x: Int, y: Int): Boolean = dispatchTap(x.toFloat(), y.toFloat(), 600)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /* ------------------------------------------------------------------ */
    /* 文本输入                                                           */
    /* ------------------------------------------------------------------ */

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (target == null) target = findFirstEditable(root)
            if (target == null) {
                false
            } else {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (target != root) target.recycle()
                ok
            }
        } catch (e: Exception) {
            false
        } finally {
            root.recycle()
        }
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocusable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    /* ------------------------------------------------------------------ */
    /* 系统级动作                                                         */
    /* ------------------------------------------------------------------ */

    fun globalAction(action: String): Boolean = when (action.lowercase()) {
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "recent", "recents", "overview" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }

    /* ------------------------------------------------------------------ */
    /* 按文本 / resource-id 点按                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 最近一次按文本 / resource-id 定位**失败**时给出的候选（≤5，按相似度降序）。
     *
     * 供 ui_click 工具写进 `hint` 帮助模型自纠（真机第三轮 Bug B：旧实现失败时只报
     * `click_not_found`，模型看不到屏幕上有什么，只能瞎换成 ui_tap 硬点坐标）。
     * 成功命中 / 新一次匹配开始前清空；只读给工具层，绝不参与匹配判据。
     */
    @Volatile
    var lastClickCandidates: List<String> = emptyList()
        private set

    /**
     * 按可见文本 / contentDescription 定位并点按（分级匹配，见 [UiNodeMatcher]）。
     *
     * @return true = 已按命中节点（或其最近可点击祖先）的坐标派发点击
     */
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow
        if (root == null) {
            lastClickCandidates = emptyList()
            return false
        }
        return try {
            val nodes = collectSummaries(root)
            val result = UiNodeMatcher.matchByText(nodes, text)
            lastClickCandidates = result.candidates
            if (!result.matched) {
                AgentLogger.w(
                    "UiAutomation",
                    "按文本定位失败：query=${text.take(40)}，候选=${result.candidates.joinToString("、")}"
                )
                false
            } else {
                // matched 已蕴含 matchIndex 非空，这里仍显式兜一层：
                // 「命中但下标丢了」是数据错误，要和「没命中」区分开——
                // 靠外层 catch 兜成 false 的话，排障时两次完全不同的失败长得一模一样。
                val target = result.clickIndex ?: result.matchIndex
                if (target == null) {
                    AgentLogger.w(
                        "UiAutomation",
                        "按文本命中但目标下标为空：策略=${result.strategy}，候选数=${result.candidates.size}"
                    )
                    false
                } else {
                    AgentLogger.i("UiAutomation", "按文本定位命中：策略=${result.strategy}，节点=${result.matchIndex} → 点击 ${result.clickIndex}")
                    tapNodeByIndex(nodes, target)
                }
            }
        } catch (e: Exception) {
            lastClickCandidates = emptyList()
            false
        } finally {
            root.recycle()
        }
    }

    /**
     * 按 resource-id 定位并点按；未命中时同样上溯可点击祖先，并把屏幕上的 id 候选
     * 写进 [lastClickCandidates]。
     */
    fun clickByResourceId(id: String): Boolean {
        val root = rootInActiveWindow
        if (root == null) {
            lastClickCandidates = emptyList()
            return false
        }
        return try {
            val nodes = collectSummaries(root)
            val result = UiNodeMatcher.matchByResourceId(nodes, id)
            lastClickCandidates = result.candidates
            if (!result.matched) {
                AgentLogger.w(
                    "UiAutomation",
                    "按 resource-id 定位失败：query=${id.take(40)}，候选=${result.candidates.joinToString("、")}"
                )
                false
            } else {
                val target = result.clickIndex ?: result.matchIndex
                if (target == null) {
                    AgentLogger.w(
                        "UiAutomation",
                        "按 resource-id 命中但目标下标为空：策略=${result.strategy}，候选数=${result.candidates.size}"
                    )
                    false
                } else {
                    AgentLogger.i("UiAutomation", "按 resource-id 定位命中：策略=${result.strategy}，节点=${result.matchIndex} → 点击 ${result.clickIndex}")
                    tapNodeByIndex(nodes, target)
                }
            }
        } catch (e: Exception) {
            lastClickCandidates = emptyList()
            false
        } finally {
            root.recycle()
        }
    }

    /**
     * 取数据：把当前窗口的 UI 树镜像成 [UiNodeSummary] 列表（纯数据，供纯函数决策）。
     *
     * 深度优先前序；[UiNodeSummary.index] 与列表下标一致，父节点必先于子节点进列表，
     * 故上溯祖先只需按 index 查表。节点数封顶 [MAX_NODES]，避免大屏设置页爆内存。
     */
    private fun collectSummaries(root: AccessibilityNodeInfo, limit: Int = MAX_NODES): List<UiNodeSummary> {
        val out = ArrayList<UiNodeSummary>(limit.coerceAtMost(MAX_NODES))
        fun walk(node: AccessibilityNodeInfo, depth: Int, parentIndex: Int) {
            if (out.size >= limit) return
            val b = Rect()
            node.getBoundsInScreen(b)
            val index = out.size
            out.add(
                UiNodeSummary(
                    index = index,
                    text = node.text?.toString().orEmpty(),
                    desc = node.contentDescription?.toString().orEmpty(),
                    resourceId = node.viewIdResourceName?.substringAfterLast('/').orEmpty(),
                    depth = depth,
                    clickable = node.isClickable,
                    parentIndex = parentIndex,
                    bounds = "${b.left},${b.top},${b.right},${b.bottom}"
                )
            )
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1, index)
                child.recycle()
            }
        }
        walk(root, 0, -1)
        return out
    }

    /** 执行动作：按节点 bounds 的中心派发点击（bounds 非法时退回 false，绝不崩）。 */
    private fun tapNodeByIndex(nodes: List<UiNodeSummary>, index: Int): Boolean {
        val node = nodes.firstOrNull { it.index == index } ?: return false
        val parts = node.bounds.split(",")
        if (parts.size != 4) return false
        val l = parts[0].trim().toIntOrNull() ?: return false
        val t = parts[1].trim().toIntOrNull() ?: return false
        val r = parts[2].trim().toIntOrNull() ?: return false
        val b = parts[3].trim().toIntOrNull() ?: return false
        return dispatchTap(((l + r) / 2f), ((t + b) / 2f), 1)
    }

    /**
     * 最近一次窗口切换事件的时刻（W-7 P1-1）。
     * 0 = 本次服务生命周期内还没发生过窗口切换——此时 elapsed 极大，
     * [getFreshUiTreeSnapshot] 直接抓，不会误等。
     */
    @Volatile
    private var lastWindowSwitchAt = 0L

    companion object {
        private const val MAX_NODES = 250

        /** ui_tree 陈旧窗口防护的最长等待预算（毫秒）——等这么久就够了，不无限等。 */
        private const val FRESH_WINDOW_MAX_WAIT_MS = 1_500L

        /** 陈旧窗口防护的轮询间隔（毫秒）。 */
        private const val FRESH_WINDOW_POLL_MS = 120L
    }
}
