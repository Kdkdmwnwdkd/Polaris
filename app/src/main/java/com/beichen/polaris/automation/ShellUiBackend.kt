package com.beichen.polaris.automation

import android.util.Xml
import com.beichen.polaris.core.AgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * W-50：**调试通道驱动的界面操作后端**——无障碍之外的第二条「操作手机界面」路径。
 *
 * W-51 起「调试通道」不再只有 Shizuku：`ShellChannels.active()` 按优先级取
 * **Shizuku → 无线调试（ADB）**，两者都是 uid 2000，命令完全一致，因此本类不需要
 * 关心是哪条——只有日志与返回文案会带上实际通道名（[ShellChannel.label]）。
 *
 * ## 为什么需要它
 *
 * 改造前 `Capability.CONTROL_SCREEN` 只有一条候选（无障碍服务，见 CapabilityBridges），
 * 于是「只开了无线调试 / Shizuku、没开无障碍」的用户，界面类工具会**整条链路不可用**：
 * 点不了、也读不到界面。但 shell（uid 2000）本来就能做这些：
 *
 * | 动作 | 命令 |
 * |---|---|
 * | 点击 / 滑动 / 长按 / 输入 | `input tap / swipe / text` |
 * | 系统键 | `input keyevent KEYCODE_HOME / BACK / APP_SWITCH` |
 * | 读界面结构 | `uiautomator dump` → XML（含 text / resource-id / bounds / clickable） |
 *
 * 前四项 `UiAutomationTools` 的 `shizukuFallback` 早已覆盖；**唯独读界面（ui_tree）和
 * 按文本点击（ui_click）没有兜底**——而这两个恰是「先看再点」的前提，缺了它们，
 * 后面的 tap 再能兜底也没用（模型不知道点哪）。本类补的就是这最后一块。
 *
 * ## 与无障碍路径的关系
 *
 * - **首选仍是无障碍**：实时、无需 dump、能拿实时事件；本类只在无障碍不可用时启用。
 * - **不追求等价**：`uiautomator dump` 单次 1~3 秒，且拿不到无障碍的节点属性全集
 *   （如 enabled/focusable）。返回文案里会明确写「调试通道」，不假装是无障碍。
 * - **匹配逻辑完全复用** [UiNodeMatcher]（归一化 / 精确 / 包含 / 前缀 / 可点祖先上溯），
 *   所以「ui_click 找不到元素时的候选列表」两条路径行为一致，模型不需要学两套。
 *
 * ## 已知限制
 *
 * 部分 ROM 对 shell 跑 `uiautomator` 有 SELinux 限制（会返回空或报错）；此时如实返回 null，
 * 由调用方降级成「开启无障碍」的提示，不静默成功。
 */
internal object ShellUiBackend {

    private const val TAG = "ShellUi"

    /** dump 落盘路径：/data/local/tmp 是 shell 稳定可写的目录（不受 scoped storage 影响）。 */
    private const val DUMP_PATH = "/data/local/tmp/.agentkit-ui.xml"

    /** 节点上限：与无障碍快照保持一致（250），避免大屏设置页撑爆内存。 */
    private const val MAX_NODES = 250

    /** dump 的超时（秒）：TCG 模拟器上会到 5~8s，真机通常 1~2s。 */
    private const val DUMP_TIMEOUT_SEC = 25L

    /** 是否存在可用的调试通道（Shizuku 或无线调试，见 [ShellChannels]）。 */
    fun isReady(): Boolean = ShellChannels.active() != null

    /**
     * 一次界面快照。
     *
     * @param nodes 全部节点（**未过滤**，父子关系完整——匹配器的可点祖先上溯依赖它）
     * @param kept 有信息量的节点数（有文案/描述/id，或可点/可滑/可编辑/已勾选）
     * @param filtered 被过滤掉的纯容器节点数
     */
    data class Tree(
        val nodes: List<UiNodeSummary>,
        val kept: Int,
        val filtered: Int,
        /** 实际读到树的那条通道（"调试通道（Shizuku）" / "无线调试（ADB）"）。 */
        val channelLabel: String = ""
    ) {
        /**
         * 与无障碍快照**同形**的 JSON（字段：text/desc/id/cls/bounds/clickable/scrollable/
         * editable/checked），保证模型看到的两种来源格式一致。
         */
        fun json(): String = buildJsonArray {
            nodes.forEach { n ->
                if (!informative(n)) return@forEach
                add(
                    buildJsonObject {
                        put("text", n.text)
                        put("desc", n.desc)
                        put("id", n.resourceId)
                        put("cls", n.cls)
                        put("bounds", n.bounds)
                        put("clickable", n.clickable)
                        put("scrollable", n.scrollable)
                        put("editable", n.editable)
                        put("checked", n.checked)
                    }
                )
            }
        }.toString()
    }

    /**
     * 读取当前界面结构。不可用 / dump 失败 / 解析失败 → null（调用方如实降级）。
     *
     * `uiautomator dump` 是全量重建 UI 树，必须放在 IO 线程，且不能与连续操作并发
     * （连续两次 dump 会互相拖慢）；调用点已经是「一次工具调用一次 dump」的粒度。
     */
    suspend fun dumpTree(): Tree? = withContext(Dispatchers.IO) {
        val channel = ShellChannels.active() ?: run {
            AgentLogger.w(TAG, "无可用调试通道：Shizuku 未就绪，且无线调试未配对")
            return@withContext null
        }
        val cmd = "uiautomator dump $DUMP_PATH >/dev/null 2>&1; cat $DUMP_PATH 2>/dev/null"
        val out = runCatching { channel.exec(cmd, DUMP_TIMEOUT_SEC) }.getOrNull()
        if (out == null || !out.ok || !out.stdout.contains("<hierarchy")) {
            AgentLogger.w(
                TAG,
                "${channel.label} dump 失败（exit=${out?.exitCode}，len=${out?.stdout?.length ?: 0}）：" +
                    (out?.startError ?: out?.stderr?.take(160) ?: "通道不可用")
            )
            return@withContext null
        }
        runCatching { parse(out.stdout, channel.label) }.onFailure {
            AgentLogger.e(TAG, "dump XML 解析失败：${it.message}")
        }.getOrNull()
    }

    /**
     * 按可见文本 / contentDescription 点击。命中的节点不可点时，自动上溯最近的可点击祖先
     * （与无障碍路径同规则，复用 [UiNodeMatcher]）。
     *
     * @return 命中并已下发点击 → true；未命中 → false（candidates 里带最相近的标签）
     */
    suspend fun clickByText(query: String): ClickOutcome {
        val tree = dumpTree() ?: return ClickOutcome(false, emptyList(), "调试通道不可用")
        val result = UiNodeMatcher.matchByText(tree.nodes, query)
        return tapMatch(tree.nodes, result)
    }

    /** 按 resource-id 后缀点击，规则同 [clickByText]。 */
    suspend fun clickByResourceId(query: String): ClickOutcome {
        val tree = dumpTree() ?: return ClickOutcome(false, emptyList(), "调试通道不可用")
        val result = UiNodeMatcher.matchByResourceId(tree.nodes, query)
        return tapMatch(tree.nodes, result)
    }

    /** 点按结果（失败时 [candidates] 供模型自纠，与无障碍路径的 hint 同源）。 */
    data class ClickOutcome(
        val clicked: Boolean,
        val candidates: List<String> = emptyList(),
        val reason: String? = null,
        /** 实际生效的通道展示名（未点成功时为 null）——写进工具文案，避免用户以为是无障碍做的。 */
        val channelLabel: String? = null
    )

    /* ------------------------------------------------------------------ */
    /* 内部                                                               */
    /* ------------------------------------------------------------------ */

    /** 在已解析的树上执行「定位 → 取中心点 → input tap」。 */
    private suspend fun tapMatch(nodes: List<UiNodeSummary>, result: UiMatchResult): ClickOutcome {
        val idx = result.clickIndex ?: return ClickOutcome(false, result.candidates, "未找到匹配元素")
        val bounds = parseBounds(nodes.getOrNull(idx)?.bounds) ?: return ClickOutcome(false, result.candidates, "命中元素没有有效坐标")
        val cx = (bounds[0] + bounds[2]) / 2
        val cy = (bounds[1] + bounds[3]) / 2
        val channel = withContext(Dispatchers.IO) { ShellChannels.active() }
            ?: return ClickOutcome(false, result.candidates, "无可用调试通道")
        val out = withContext(Dispatchers.IO) {
            runCatching { channel.exec("input tap $cx $cy", 10) }.getOrNull()
        }
        return if (out?.ok == true) {
            AgentLogger.i(TAG, "${channel.label} 点击 ($cx,$cy) 策略=${result.strategy}")
            ClickOutcome(true, channelLabel = channel.label)
        } else {
            ClickOutcome(
                false,
                result.candidates,
                out?.startError ?: out?.stderr?.take(120) ?: "input tap 执行失败"
            )
        }
    }

    /**
     * 解析 `uiautomator dump` 的 XML（`<hierarchy><node …><node …/></node></hierarchy>`）。
     *
     * 用 [XmlPullParser] 而不是正则：属性里有中文与引号，正则容易切错。
     * `parentIndex` 由栈维护——[UiNodeMatcher] 的「可点祖先上溯」全靠它。
     *
     * `index` 恒等于列表下标（匹配器契约），因此**不做过滤**：纯容器节点也保留，
     * 只在 [Tree.json] 输出时跳过。
     */
    private fun parse(xml: String, channelLabel: String): Tree {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val nodes = ArrayList<UiNodeSummary>(64)
        val stack = ArrayDeque<Int>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "node") {
                    if (nodes.size >= MAX_NODES) {
                        // 超上限：不再收集，但仍要维护栈，避免父指向错乱
                        stack.addLast(-1)
                    } else {
                        val index = nodes.size
                        nodes.add(
                            UiNodeSummary(
                                index = index,
                                text = parser.getAttributeValue(null, "text").orEmpty(),
                                desc = parser.getAttributeValue(null, "content-desc").orEmpty(),
                                resourceId = parser.getAttributeValue(null, "resource-id")
                                    ?.substringAfterLast('/').orEmpty(),
                                depth = stack.size,
                                clickable = parser.getAttributeValue(null, "clickable") == "true",
                                parentIndex = stack.lastOrNull() ?: -1,
                                bounds = normalizeBounds(parser.getAttributeValue(null, "bounds").orEmpty()),
                                cls = parser.getAttributeValue(null, "class")
                                    ?.substringAfterLast('.').orEmpty(),
                                scrollable = parser.getAttributeValue(null, "scrollable") == "true",
                                editable = parser.getAttributeValue(null, "class")
                                    ?.contains("EditText", ignoreCase = true) == true,
                                checked = parser.getAttributeValue(null, "checked") == "true"
                            )
                        )
                        stack.addLast(index)
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "node") {
                    stack.removeLastOrNull()
                }
            }
            event = parser.next()
        }
        val kept = nodes.count { informative(it) }
        return Tree(nodes = nodes, kept = kept, filtered = nodes.size - kept, channelLabel = channelLabel)
    }

    /** `[l,t][r,b]` → `l,t,r,b`（与无障碍快照的 bounds 格式统一）。 */
    private fun normalizeBounds(raw: String): String {
        val nums = Regex("-?\\d+").findAll(raw).map { it.value }.toList()
        return if (nums.size >= 4) nums.take(4).joinToString(",") else ""
    }

    /** `"l,t,r,b"` → IntArray(4)；格式不对返回 null。 */
    private fun parseBounds(bounds: String?): IntArray? {
        if (bounds.isNullOrBlank()) return null
        val parts = bounds.split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (parts.size == 4) parts.toIntArray() else null
    }

    /** 与无障碍快照同一口径：有文案/描述/id，或本身可交互，才算「有信息量」。 */
    private fun informative(node: UiNodeSummary): Boolean =
        node.text.isNotBlank() ||
            node.desc.isNotBlank() ||
            node.resourceId.isNotBlank() ||
            node.clickable ||
            node.scrollable ||
            node.editable ||
            node.checked
}
