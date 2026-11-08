package com.beichen.polaris.core.capability

import com.beichen.polaris.core.AgentTool
import com.beichen.polaris.core.tool.ToolCatalog

/**
 * 一个能力组：模型能「一次开一组」的最小单位。
 *
 * 注意与 [com.beichen.polaris.core.capability.Capability]（能力就绪度裁决用的枚举）不是一回事：
 * 那个是「这台机器能不能跑 shell / 控屏」，这个是「工具按调用场景怎么分组」。
 *
 * @param group 组名（工具名前缀，少数例外在 [tools] 里显式列出）
 * @param note 给模型看的组索引说明（`__use_tools` 的组清单与此同源）
 * @param tools 该组包含的工具名。**清单是真源**：注册表里有、这里没有的，[CapabilityManifest.audit] 会红
 * @param pinned 常驻组：不参与淘汰，也不该让用户关掉（关了下一轮也会被 pin 回来）
 * @param dynamic 成员由运行时提供（MCP 服务器连上才知道有哪些工具）。
 *   这类组的 `tools` 必须是空的——写了具体名字反而说明有人在手工同步一份**连不上就失效**的名单。
 *   审计对它们跳过「工具→清单」与「组→工具」：不是漏登记，而是结构上不可能登记。
 */
data class CapabilityGroup(
    val group: String,
    val note: String,
    val tools: List<String>,
    val pinned: Boolean = false,
    val dynamic: Boolean = false,
)

/**
 * 能力清单审计结果。`problems` 为空即通过；每条都是「谁、为什么」的一句人话。
 */
data class CapabilityAudit(val problems: List<String>) {
    val ok: Boolean get() = problems.isEmpty()
}

/**
 * 工具能力清单：**组从哪来、谁属于谁、哪些常驻**的单一真源，外加四条双向断言。
 *
 * ## 为什么它不能只是又一张表
 *
 * 分组原本散在 `KernelExposure` 里（一张 `GROUP_NOTES`、一张 `GROUP_PATCH`、两个常量集合），
 * 只被「计算本回合暴露面」用到。问题在于这些表**只能靠人记得更新**：
 *
 * - 新增工具 `foo_bar`，前缀 `foo` 不是已声明的组 → 它静默落进 `misc`。工具仍可见
 *   （misc 常驻），但 `__use_tools` 里永远找不到「foo」这一组，组索引也不会解释它是什么。
 * - 工具删了 / 改名了，组说明还挂在索引里，模型 `__use_tools("video")` 换回一个空组。
 * - 工具被挪了组，但名字前缀还指向老组，暴露面与索引从此对不上——这种最难查。
 *
 * 三种都是「表与代码不同步」，人眼审不出来。所以这张表带 [audit]：把同步关系变成
 * **可执行的双向断言**，启动时跑一次（留痕），harness 也跑（拦回归）。
 *
 * ## 归组规则
 *
 * 1. 先查清单（[groupOf] 命中 [toolNames] 即返回它所在组）；
 * 2. 未登记的名字（MCP 动态工具、插件）按「第一个下划线之前的前缀」推，前缀是已声明组就用它；
 * 3. 都不匹配 → `misc`（**兜底可见，不是丢弃**：不认识的东西不能藏）。
 */
object CapabilityManifest {

    /** 未归类兜底组。常驻：模型不认识的东西必须保持可见（名字的真源在 [ToolCatalog.MISC]）。 */
    const val MISC: String = ToolCatalog.MISC

    /**
     * 组与成员：**从 [ToolCatalog] 派生**，本对象不再持有第二份名单。
     *
     * R-13 之前这里手写 56 个工具名。它和工具类、能力分级表、审批归组表三处互不知道，
     * 加一个工具要改四遍、且漏改不报错。现在「一个工具一条记录」只有
     * `core/tool/ToolCatalog.kt` 一处，这里把记录切成视图：组成员、组序、工具表顺序
     * 全部随之而来。
     *
     * note 写「能干什么」而不是「内部叫什么」：模型看到的就是这句话。
     */
    val capabilities: List<CapabilityGroup> = ToolCatalog.groups.map { g ->
        CapabilityGroup(
            group = g.name,
            note = g.note,
            tools = ToolCatalog.membersOf(g.name),
            pinned = g.pinned,
            dynamic = g.dynamic,
        )
    }

    private val byGroup: Map<String, CapabilityGroup> = capabilities.associateBy { it.group }

    /**
     * 按名字推组：先目录，再前缀，最后 [MISC]。
     *
     * 判定逻辑本身只有 [ToolCatalog.groupOf] 一份——R-13 之前这里还抄了一遍
     * （buildMap + 前缀回落），两处规则一旦分叉，「工具属于哪个组」就会有两个答案。
     * 本方法只是暴露面这一侧的入口名。
     */
    fun groupOf(name: String): String = ToolCatalog.groupOf(name)

    /** 组是否常驻（`file` / `misc`）。 */
    fun isPinned(group: String): Boolean = byGroup[group]?.pinned == true

    /** 组是否由运行时提供成员（`mcp`）。 */
    fun isDynamic(group: String): Boolean = byGroup[group]?.dynamic == true

    /** 组的一句话说明；未知组回退成组名。 */
    fun noteOf(group: String): String = byGroup[group]?.note ?: group

    /** 声明过的组名（顺序同 [capabilities]）。 */
    val groups: List<String> get() = capabilities.map { it.group }

    /**
     * 声明过的全部工具名。**顺序即工具表顺序**（P3-5）——发送给模型的工具表、
     * 本地小模型的提示词行、`__find_tools` 的清单，全都按它排。
     */
    val toolNames: List<String> get() = capabilities.flatMap { it.tools }

    /**
     * 未登记工具的名次：排在所有已登记工具之后。
     *
     * 为什么不是 0 或 -1：未登记的工具必须**可见**（见 [MISC] 的注释），但它的位置
     * 不该插到核心工具前面——模型读工具表和读人读目录一样，先看到的先用。
     */
    const val UNRANKED: Int = Int.MAX_VALUE

    private val rankOfTool: Map<String, Int> =
        toolNames.withIndex().associate { (i, name) -> name to i }

    /** 工具的清单名次；未登记返回 [UNRANKED]。 */
    fun rankOf(name: String): Int = rankOfTool[name] ?: UNRANKED

    /**
     * 按清单顺序排出工具表（P3-5）。
     *
     * ## 为什么顺序必须有个唯一的拥有者
     *
     * 修前有**两个**顺序来源，谁也不认识谁：
     *  - 云端工具表 = `ToolRegistry` 的**注册顺序**（`ChatViewModel` 里那 40 行 `register(...)`）；
     *  - 本地提示词 = `LocalAgentPrompt.ORDER` 那张手写表。
     *
     * 后果是一句话讲不清「工具表长什么样」：`register` 里挪一行、加一个工具插在中间，
     * 云端每一轮的请求体就整体位移，KV 前缀全失效（整段 prefill 重算，云端按量计费、
     * 本地几秒白等），而**没有任何断言或日志会提示这件事发生了**。本地那条路径反而
     * 因为写了 ORDER 而免疫——同一个问题，一条路径有防线、另一条没有。
     *
     * 现在顺序归清单：注册顺序随便改，工具表逐字节不变；想调顺序就改 [capabilities]
     * 里组的先后或组内成员的先后，一次改完两条路径同时生效。
     *
     * 未登记的工具（MCP 动态工具、插件）排最后、彼此按名字排：它们的位置不该由
     * 「谁先连上服务器」决定，否则同一台设备两次启动的工具表就可能不一样。
     */
    fun sort(tools: List<AgentTool>): List<AgentTool> =
        tools.sortedWith(compareBy({ rankOf(it.name) }, { it.name }))

    /**
     * 双向断言。每一条都对应一种真实发生过的「表与代码不同步」。
     *
     * 1. **工具 → 清单**：每个注册进 ToolRegistry 的工具都必须在清单里有一个组。
     *    漏登不算致命（兜底 misc 让它仍可见），但组索引不会解释它，`__use_tools` 也找不到它。
     * 2. **清单 → 工具**：清单里的每个工具名都必须真有实现——否则模型照清单调用会拿到
     *    `unknown_tool`，而且那条错误还会被模糊匹配指到一个不相干的工具上。
     * 3. **组 → 工具**：每个非兜底组至少要有一个工具，否则模型按索引开组会开到空组。
     * 4. **归组一致**：按 [groupOf] 反推出来的组，必须与它被写进的组一致——这条防的是
     *    「清单写了一套、名字前缀指向另一套」，那种情况下暴露面与组索引会对不上。
     * 5. **常驻组非空**：pin 一个没有成员的组只是白占一个淘汰名额。
     * 6. **动态组自洽**（P3-3）：`dynamic` 组的 `tools` 必须为空、且不能 `pinned`——
     *    它的成员连上服务器才知道，手写就是一份「连不上时全失效」的名单。
     *
     * `misc` 是兜底桶：空着才是理想状态，所以 2/3 都跳过它。`mcp` 这类动态组同理，
     * 只是理由不同——它不是「兜底」，是「结构上不可能提前知道」。
     *
     * 1~6 全部可以在 [audit] 的**带组表重载**上单独触发失败，harness 就是这么反向验的。
     *
     * @param tools 当前真正注册进 ToolRegistry 的工具（顺序无所谓）。
     *   MCP 动态工具不用手工过滤——它们会被 ① 按「动态组」放行（见 [CapabilityGroup.dynamic]）。
     */
    fun audit(tools: List<AgentTool>): CapabilityAudit = audit(capabilities, tools)

    /**
     * 同一套断言的**可重载版本**：对任意组表求审计。
     *
     * 存在的理由只有一个——反向验证。真表是 `val`，改不动；想证明「动态组手写成员会被抓」
     * 就只能喂一张假的组表进来。若把断言写在「只吃真表」的函数里，这一类用例永远写不出来，
     * 于是 ⑥ 这种「今天不会发生、明天改一行就会发生」的规则就没人验过。
     */
    internal fun audit(groups: List<CapabilityGroup>, tools: List<AgentTool>): CapabilityAudit {
        val groupOfTool: Map<String, String> = buildMap {
            groups.forEach { cap -> cap.tools.forEach { put(it, cap.group) } }
        }
        val byGroup: Map<String, CapabilityGroup> = groups.associateBy { it.group }
        // 与公有 [groupOf] 同规则：先清单，再前缀，最后兜底。这里必须重算，
        // 因为它要跟着**入参**组表走，而不是对象里那张真表。
        fun resolve(name: String): String {
            groupOfTool[name]?.let { return it }
            val prefix = name.substringBefore('_', missingDelimiterValue = name)
            return if (byGroup.containsKey(prefix)) prefix else MISC
        }
        fun dynamic(group: String): Boolean = byGroup[group]?.dynamic == true

        val names = tools.map { it.name }
        val nameSet = names.toHashSet()
        val problems = mutableListOf<String>()

        // ① 工具 → 清单（动态组的成员本来就登记不了，跳过）
        names.forEach { name ->
            if (name !in groupOfTool && !dynamic(resolve(name))) {
                problems += "工具 `$name` 没有在清单里登记 → 会落进 ${resolve(name)}，" +
                    "组索引里看不到它、`__use_tools` 也开不到它所在的组。" +
                    "请在 CapabilityManifest.capabilities 的某个组 tools 里加上它。"
            }
        }

        // ② 清单 → 工具
        groupOfTool.keys.forEach { declared ->
            if (declared !in nameSet) {
                problems += "清单声明的 `$declared` 没有对应实现（删了或改名了？）→ " +
                    "模型照清单调用会拿到 unknown_tool。请同步更新清单。"
            }
        }

        // ③ 组 → 工具（misc 是兜底桶，空着才是理想状态；dynamic 组成员不在清单里，见字段注释）
        groups.filter { it.group != MISC && !it.dynamic }.forEach { cap ->
            if (cap.tools.isEmpty()) {
                problems += "能力组 `${cap.group}`（${cap.note}）没有任何工具 → " +
                    "模型按索引开这个组会开到空组。补上工具，或删掉这个组。"
            }
        }

        // ④ 归组一致：清单位置必须与「按名字推出来的组」一致
        groupOfTool.forEach { (name, group) ->
            val derived = resolve(name)
            if (derived != group) {
                problems += "`$name` 写在 `${group}` 组，但按名字推出来是 `$derived` → " +
                    "暴露面与组索引会对不上。要么改名字，要么把它挪到 `$derived`。"
            }
        }

        // ⑤ 常驻组不能空（misc 除外）：pin 一个没有内容的组只是白占淘汰名额
        groups.filter { it.pinned && it.group != MISC }.forEach { cap ->
            if (cap.tools.isEmpty()) {
                problems += "常驻组 `${cap.group}` 没有任何工具 → 每轮都要把它 pin 回来，纯浪费一个淘汰名额。"
            }
        }

        // ⑥ 动态组的两条自洽：成员不能手写（连不上就失效的名单）、不能常驻（每轮 pin 一个空组）
        groups.filter { it.dynamic }.forEach { cap ->
            if (cap.tools.isNotEmpty()) {
                problems += "动态组 `${cap.group}` 手写了 ${cap.tools.size} 个成员 → " +
                    "这些名字在运行时可能根本不存在（服务器未连接）。动态组的 tools 必须为空，" +
                    "成员由运行时注册决定。"
            }
            if (cap.pinned) {
                problems += "动态组 `${cap.group}` 被标成常驻 → 它默认是空的，" +
                    "每轮 pin 回来只会白占一个淘汰名额。外部工具必须显式开组。"
            }
        }

        // 常量自检（表本身写坏了也算问题）
        val dupGroups = groups.groupingBy { it.group }.eachCount().filterValues { it > 1 }.keys
        if (dupGroups.isNotEmpty()) problems += "能力组重复声明：${dupGroups.joinToString()}。"
        val allNames = groups.flatMap { it.tools }
        val dupTools = allNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (dupTools.isNotEmpty()) problems += "工具被重复登记到多个组：${dupTools.joinToString()}。"
        groups.filter { it.note.isBlank() }.forEach {
            problems += "能力组 `${it.group}` 没有说明文字 → 组索引里会显示成组名，模型读不懂。"
        }

        return CapabilityAudit(problems)
    }
}
