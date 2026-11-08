package com.beichen.polaris.core

import com.beichen.polaris.core.capability.CapabilityManifest

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 工具暴露的**实现层**：把 [AgentTool]（现有 52 个工具的实现契约）接到内核的暴露模型上。
 *
 * ## 为什么需要这一层
 * 那套内核实验（`core/kernel/`，995 行）已经删除，但它描述的「按规则暴露」是对的，
 * 而现有 52 个工具实现的是 `AgentTool` 契约。所以保留这一层：
 * 规则由本文件承担，工具侧一行不用改。
 *
 * 删除时留下的教训：那 995 行里并不是全死——`Strength` 和 `ExposureMode` 被本文件用着，
 * 「747 行整个删掉」的说法是错的，照删会编译不过。所以清理时先把这两个枚举搬到
 * `core/ExposureTypes.kt`，`Prompt` 那组搬到 `core/PromptSegments.kt`，再删整个包。
 *
 * ## 和 [ToolExposurePolicy] 的差别（三处，都是针对现状的毛病）
 * 1. **不再有白名单**。旧策略是「本地小模型只给这 7 个，名单写死在代码里」，新增工具忘了
 *    登记就等于永久对本地模型不可见。这里改成**分组**：任何工具都能按名字推到一个组，
 *    推不出来也归 `misc`，绝不会「消失」。
 * 2. **按能力档位分派，不按 provider 名**。旧策略用 `providerId == "local-llama"` 判断，
 *    每接一个 provider 就要回来改一次；这里用 [Strength]。
 * 3. **裁剪后仍然能被发现**。旧策略裁掉就是真没了（`scopedRegistry` 让它「查无此工具」）。
 *    这里给模型留了两个元工具：`__find_tools` 查、`__use_tools` 开。工具被裁不等于能力被禁。
 *
 * ## 安全边界（重要）
 * 暴露 ≠ 授权。裁掉只是「不摆在模型眼前」，不是「禁止执行」；真正的拦截在审批层
 * （`ToolExecutor` 的 approval 链路），本文件不碰、也不该碰。
 */
object KernelExposure {

    /** 元工具名。双下划线前缀：与真实工具不可能撞名。 */
    const val FIND_TOOLS = "__find_tools"
    const val USE_TOOLS = "__use_tools"

    /**
     * 云端默认预激活的组：**只有读写文件**。
     *
     * W-102：原先给 `file/ui/skill` 三组，理由是「大模型读得懂组索引」。实测反过来——
     * `ui`（9 个工具，description 特别长）和 `skill` 占了工具表近一半，而绝大多数对话
     * 一条都用不到；它们不是「读不懂」，是「不该每轮都摆在眼前」。三组约 6782 字符，
     * 只留 `file` 约 1500 字符，每轮省 5000 余字符（≈1300 token）。
     *
     * 代价是可能多一次 `__use_tools` 往返。这个代价**只在真要用的任务上付一次**，
     * 之后靠 [PINNED_GROUPS] 之外的粘性激活（见 [plan]）一直留着，不再重复付。
     */
    private val DEFAULT_GROUPS = setOf("file")

    /**
     * 同时活跃的**可淘汰组**上限（`file` / `misc` 不占名额，见 [PINNED_GROUPS]）。
     *
     * 粘性激活必须配上限，否则「开过就一直开」会把 15 个组全打开，回到全量——
     * 那等于没裁。取 2：一次任务通常跨 1~2 个组（文件 + 屏幕，或文件 + 终端），
     * 再多就是模型在乱开。
     */
    const val MAX_ACTIVE_GROUPS: Int = 2

    /**
     * 触发淘汰时保留几个可淘汰组。
     *
     * 为什么不是「每加一个淘汰一个」：工具表一变，KV 前缀就失效（整段 prefill 重算，
     * 云端全额计费、本地几秒延迟）。滑动淘汰会让工具表每轮都变，省下的 token 全赔进
     * 缓存失效。**滞回**（一次降到 1，再涨到 2 才动手）把抖动频率从「每轮」降到「每两组」。
     */
    const val TRIM_TO_GROUPS: Int = 1

    /**
     * 端侧小模型的初始组：只给文件读写。
     *
     * 为什么连 ui 都不给：1.5B~4B 的模型在 16 个工具里选错的概率，比「先用 `__use_tools`
     * 开一组再用」带来的额外一轮更贵——选错一次至少浪费一轮 + 一次失败回填，而开组只需一次
     * 明确的调用。屏幕操作类工具的 description 又特别长（ui_* 9 个占工具表近一半），
     * 对小模型是纯噪声。
     */
    private val TINY_GROUPS = setOf("file")

    /**
     * 未归类工具所在的组名。
     *
     * **这个组永远常驻，不参与裁剪。** 理由：前缀推不出组，说明「我们不认识这个工具」，
     * 而不是「这个工具不重要」。把它藏起来的后果是——插件工具、用户自己新增的工具、
     * 第三方 Skill 注册的工具，全部默认对模型不可见，模型只会看到「未知工具 xxx」，
     * 然后反复重试或改用别的路径。这类"我们不知道的东西"必须保持可见。
     *
     * 真正该裁的是那些我们**清楚知道**体积大、用得少的组（ui 9 个、workflow、memory…），
     * 因为你至少知道裁掉了什么。
     */
    private const val MISC = CapabilityManifest.MISC

    /**
     * 永不淘汰的组（W-102）：核心工具集 + 兜底分类。
     *
     * 「核心调用的工具可以一直动」——读写文件是每轮都要用的路径，把它也纳入淘汰，
     * 模型会在第二次任务时突然发现 `read_file` 不见了，只能先 `__use_tools` 找回来，
     * 纯亏一轮。`misc` 同理（见上文：不认识的东西不能藏）。
     *
     * 这两组不占 [MAX_ACTIVE_GROUPS] 的淘汰名额，即上限实际是「2 个可淘汰组 + 2 个固定组」。
     * 名单本身在 [CapabilityManifest]（`pinned = true`），这里只留一个取用入口——
     * 两处各写一份的话，加一个常驻组就得记得改两个地方。
     */
    private val PINNED_GROUPS: Set<String> =
        CapabilityManifest.capabilities.filter { it.pinned }.mapTo(LinkedHashSet()) { it.group }

    /**
     * 名字 → 组。规则与例外表都在 [CapabilityManifest]（补丁不是白名单：推不出来的归 misc，
     * 仍然可见可发现，只是组名不好看——新增工具永远不会因为「忘了登记」而从模型视野里消失）。
     */
    fun groupOf(name: String): String = CapabilityManifest.groupOf(name)

    /**
     * 该组是否常驻（`file` / `misc`）。
     *
     * 常驻组不参与淘汰，因此**也不该让用户「关掉」**——关掉也会在下一轮 [plan] 的
     * 强制 pin 步骤被加回来，UI 上留一个按了没反应的按钮比不给按钮更糟。
     */
    fun isPinned(group: String): Boolean = CapabilityManifest.isPinned(group)

    /** 组的一句话说明（与 `__use_tools` 给模型看的组索引同源）；未知组回退成组名。 */
    fun noteOf(group: String): String = CapabilityManifest.noteOf(group)

    /**
     * provider + 用户设置 → 能力档位。
     *
     * 云端一律 [Strength.SMALL]（走分组）：它的工具表值 4k token/轮，是纯浪费；
     * 而云端大模型完全能读懂组索引并主动 `__use_tools`。
     */
    fun strengthOf(providerId: String, forceFull: Boolean): Strength = when {
        forceFull -> Strength.LARGE
        providerId == "local-llama" -> Strength.TINY
        else -> Strength.SMALL
    }

    /**
     * 计算本回合的工具暴露面。
     *
     * ## 粘性激活（W-102）
     * [active] 不再按「一次 send」持有，而是由调用方跨回合持有并落盘。语义是：
     * **被开过的组就一直开着，直到撞上 [MAX_ACTIVE_GROUPS] 才按最久未用淘汰**。
     *
     * 为什么不「用完就关」：开一次组要一次完整往返（整段历史重发 + 工具结果回填，
     * 约 1500~3000 token，还要多吃一次前缀失效的 prefill）。留着一组只要每轮
     * 200~500 token。**平衡点在 4~6 轮**——只要这组工具在接下来 5 轮内还会用到，
     * 留着就比重开便宜。而「一轮十几秒」的场景里，5 轮也就一分多钟。
     *
     * @param all 全量工具（注册序）。
     * @param active 已激活的组（**会被就地修改**：seed、pin、淘汰都发生在这里）。
     * @param forceFull 强制全量（开发者开关）：返回全量，且不生成元工具。
     *
     * ## 为什么要加锁（W-103）
     *
     * `active` 从「一次 send 内有效」变成跨回合持有之后，它就有了**两个写入方**：
     * agent 线程（每轮 plan、`__use_tools`）与 UI 线程（设置页「关闭」按钮）。
     * 本函数内部有 `retainAll` / `filter` / `toSet()` 这类**复合遍历**，与一次 `remove`
     * 交错就抛 ConcurrentModificationException。崩溃点是随机的，栈里只会指向
     * `LinkedHashSet` 的迭代器——看不出是哪个按钮干的。
     *
     * 锁对象用集合本身而不是另建一把：谁操作这个集合谁就先 `synchronized(active)`，
     * 省掉一张共享锁登记表。代价是 active 不能换成别的实例——调用方已保证如此。
     */
    fun plan(
        all: List<AgentTool>,
        providerId: String,
        forceFull: Boolean,
        active: MutableSet<String>
    ): Plan = synchronized(active) { planLocked(all, providerId, forceFull, active) }

    /** [plan] 的实现体，**不加锁**：调用方必须已持有 `active` 的监视器。 */
    private fun planLocked(
        all: List<AgentTool>,
        providerId: String,
        forceFull: Boolean,
        active: MutableSet<String>
    ): Plan {
        val strength = strengthOf(providerId, forceFull)
        if (strength == Strength.LARGE) {
            return Plan(ExposureMode.FULL, CapabilityManifest.sort(all), 0, groupsOf(all), emptySet())
        }
        // 先剔掉「已经不存在的组名」：active 可能是上个版本落盘读回的，而代码改版后
        // 某个组可能已被删掉（工具合并、改名）。留着它们会让 enforceCap 误以为超限，
        // 每轮都淘汰别的组——工具面跟着抖动，前缀缓存全废。这是「持久化 + 上限」
        // 组合必然出现的副作用，必须在入口处清一次。
        val knownGroups = all.mapTo(HashSet()) { groupOf(it.name) }
        active.retainAll(knownGroups)
        // 云端与端侧都走「分组 + 元工具」：差别只在初始激活哪些组。
        if (active.isEmpty()) {
            val seed = if (strength == Strength.TINY) TINY_GROUPS else DEFAULT_GROUPS
            active.addAll(seed.filter { g -> all.any { groupOf(it.name) == g } })
        }
        // 核心组与兜底组恒激活（见 PINNED_GROUPS）。放在 seed 分支**之外**：
        // 调用方可能带着「上次会话留下的非空集合」进来，那条路径不会走 seed。
        if (all.any { groupOf(it.name) == "file" }) active.add("file")
        if (all.any { groupOf(it.name) == MISC }) active.add(MISC)
        // 滞回淘汰：超过上限才动手，一次降到 TRIM_TO_GROUPS 组。
        enforceCap(active)

        // P3-5：业务工具按**清单顺序**排，不按注册顺序。注册顺序是 `ChatViewModel` 里那 40 行
        // `register(...)` 的书写顺序，改一次（插一个工具到中间）就会让云端每一轮的请求体整体
        // 位移、KV 前缀失效，而这种位移过去没有任何断言能发现。排序后注册顺序只影响
        // 「谁先被 register」，不影响工具表字节。元工具追加在最后：它们是「查/开」的入口，
        // 摆在业务工具后面符合阅读顺序，也让前缀部分尽量不被它们打断。
        val business = CapabilityManifest.sort(all.filter { groupOf(it.name) in active })
        val visible = business + metaTools(all, active)
        val mode = if (strength == Strength.TINY) ExposureMode.META else ExposureMode.GROUPED
        val realVisible = visible.count { it.name != FIND_TOOLS && it.name != USE_TOOLS }
        return Plan(mode, visible, all.size - realVisible, groupsOf(all), active.toSet())
    }

    /**
     * 滞回淘汰：**可淘汰组超过上限才动手，一次降到 [TRIM_TO_GROUPS] 个**（就地修改 [active]）。
     *
     * 只数可淘汰组：[PINNED_GROUPS] 里的 `file` / `misc` 既不占名额也不会被删——
     * 「降 N 组」如果把它们也算进去，一次淘汰就会把刚开的功能组和核心组一起清掉。
     *
     * 淘汰顺序依赖 [active] 的迭代序 = 插入序，所以调用方必须传有序集合；
     * `__use_tools` 会把重新启用的组 remove+add 移回尾部，形成 LRU。
     *
     * @return 被淘汰的组名（保留顺序，供日志与 harness 断言）。
     */
    fun enforceCap(active: MutableSet<String>): List<String> {
        val removable = active.filter { it !in PINNED_GROUPS }
        if (removable.size <= MAX_ACTIVE_GROUPS) return emptyList()
        val overflow = removable.size - TRIM_TO_GROUPS
        if (overflow <= 0) return emptyList()
        val victims = removable.take(overflow)
        active.removeAll(victims.toSet())
        return victims
    }

    /** 组名 → 工具数，按组名排序（顺序稳定，避免提示逐轮抖动）。 */
    fun groupsOf(all: List<AgentTool>): List<Pair<String, Int>> =
        all.groupBy { groupOf(it.name) }.mapValues { it.value.size }.toList().sortedBy { it.first }

    /**
     * 两个元工具。它们**只改变「下一轮能不能看见」**，不执行任何真实工具，
     * 因此不需要审批、也没有副作用（`readOnly = true`）。
     */
    private fun metaTools(all: List<AgentTool>, active: MutableSet<String>): List<AgentTool> {
        val index = all.groupBy { groupOf(it.name) }.toSortedMap()

        val find = MetaTool(
            name = FIND_TOOLS,
            description = "按关键词检索全部工具（含当前未显示的）。只返回名字与用途，不执行。" +
                "查到后调 $USE_TOOLS 启用它所在的分组。",
            parameters = JsonSchema(
                type = "object",
                properties = mapOf("query" to JsonSchema.Property("string", "关键词，如 文件/截图/记忆")),
                required = listOf("query")
            )
        ) { args ->
            val q = args["query"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.trim().orEmpty()
            if (q.isEmpty()) {
                ToolResult.fail("missing_param", "缺少 query", "传一个关键词，例如 query=\"文件\"")
            } else {
                val hits = all.filter {
                    it.name.contains(q, true) || it.description.contains(q, true) || groupOf(it.name).contains(q, true)
                }.take(10)
                when {
                    hits.isNotEmpty() -> ToolResult.ok(
                        "find_ok",
                        hits.joinToString("\n") { "- ${it.name}（组 ${groupOf(it.name)}）：${it.description.take(40)}" }
                    )
                    // 没精确命中时不报失败，退化为「列出全部」。
                    // 理由：模型用中文口语查（「截图」），工具名和描述可能是「截取当前屏幕画面」，
                    // 纯子串匹配必然有漏。这里让模型一次拿到全量清单（只列名字，不列描述），
                    // 比让它换个词再查一轮便宜得多——失败重试也要花 token。
                    else -> ToolResult.ok(
                        "find_fallback",
                        "没有精确匹配「$q」。以下是全部工具（按组；标 [已启用] 的可直接调用，" +
                            "其余需先 $USE_TOOLS 启用所在组）：\n" + index.entries.joinToString("\n") { (g, ts) ->
                                "- $g" + (if (g in active) " [已启用]" else "") + "：" +
                                    ts.joinToString(", ") { t -> t.name }
                            }
                    )
                }
            }
        }

        val use = MetaTool(
            name = USE_TOOLS,
            description = buildString {
                append("启用工具分组，启用后下一轮即可直接调用其中的工具。可分组的全部工具：\n")
                index.forEach { (g, tools) ->
                    append("- $g（${tools.size} 个，${noteOf(g)}）")
                    if (g in active) append(" [已启用]")
                    append('\n')
                }
                append("参数 groups 用逗号分隔，例如 groups=\"ui,memory\"")
            },
            parameters = JsonSchema(
                type = "object",
                properties = mapOf("groups" to JsonSchema.Property("string", "组名，逗号分隔")),
                required = listOf("groups")
            )
        ) { args ->
            val raw = args["groups"]?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val want = raw.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
            if (want.isEmpty()) {
                ToolResult.fail("missing_param", "缺少 groups", "传组名，例如 groups=\"ui,memory\"")
            } else {
                val unknown = want.filter { it !in index }
                val known = want.filter { it in index }
                // W-102 粘性激活：本次启用的组一律视为「最近使用」，按参数顺序排到末尾。
                //
                // 必须先**全部 remove 再 addAll**，不能 `addAll` 之后逐个 `remove+add`：
                // 后者对「本来就不在集合里」的组是空操作，于是新组停在 addAll 给的旧位置，
                // 而顺序变成「后写的更新、先写的反而更旧」。实测 groups="ui,memory" 得到
                // {memory,ui}——恰好与语义相反，第一次淘汰会先砍掉刚开的 memory。
                //
                // 回执必须反映**实际生效**的组：一次开 2 个而容量只剩 1 个时，
                // 先开的那个会被同一次调用里的滞回淘汰掉。只说「已启用」会让模型
                // 下一轮调一个看不见的工具，然后陷入「未知工具」重试。
                //
                // 加锁：这个闭包在**模型调用工具时**才执行，已经脱离 plan 的临界区，
                // 而 UI 线程此刻可能正在关组（见 plan 的 KDoc）。钥匙是同一个集合。
                val (effective, dropped) = synchronized(active) {
                    known.forEach { active.remove(it) }
                    active.addAll(known)
                    enforceCap(active)
                    known.filter { it in active } to known.filter { it !in active }
                }
                // 淘汰不在这里打日志：本文件是**零 Android 依赖的纯内核**
                // （harness 的编译单元只含 kernel + KernelExposure），引入 AgentLogger
                // 会让 harness 缺类编译不过。日志由调用方经工具回执与 ToolExposure 日志承担。
                val droppedNote = if (dropped.isEmpty()) "" else
                    "；因组上限（${MAX_ACTIVE_GROUPS}）挤掉了 ${dropped.joinToString(",")}，" +
                        "需要时再开一次（会挤掉更久未用的组）"
                when {
                    known.isEmpty() -> ToolResult.fail(
                        "unknown_group",
                        "没有这些分组：${unknown.joinToString(",")}",
                        "可用分组：" + index.keys.joinToString("、")
                    )
                    effective.isEmpty() -> ToolResult.ok(
                        "use_evicted",
                        "没能启用任何组：${known.joinToString(",")} 刚开就被上限挤掉了。" + droppedNote
                    )
                    unknown.isEmpty() -> ToolResult.ok(
                        "use_ok",
                        "已启用 " + effective.joinToString("、") { g ->
                            "$g(${index[g]?.size ?: 0} 个：" + (index[g]?.joinToString(",") { t -> t.name } ?: "") + ")"
                        } + droppedNote
                    )
                    else -> ToolResult.ok(
                        "use_partial",
                        "已启用 ${effective.joinToString(",")}；未找到 ${unknown.joinToString(",")}" + droppedNote,
                        data = "可用分组：" + index.keys.joinToString("、")
                    )
                }
            }
        }

        return listOf(use, find)
    }

    /**
     * 一次暴露的完整快照。
     *
     * @param hiddenCount 被裁掉的真实工具数（不含元工具）。
     */
    /**
     * 暴露面计算结果。
     *
     * @property visible 本回合真正发给模型的工具，**顺序即 [CapabilityManifest] 的清单顺序**
     *   （业务工具在前，元工具在最后）。这个顺序是请求体的一部分：它一变，KV 前缀就失效。
     *   所以它只能由清单决定，不能由注册顺序、Map 迭代序或线程调度决定。
     */
    data class Plan(
        val mode: ExposureMode,
        val visible: List<AgentTool>,
        val hiddenCount: Int,
        val groups: List<Pair<String, Int>>,
        val activated: Set<String>
    ) {
        val names: List<String> get() = visible.map { it.name }
        val trimmed: Boolean get() = hiddenCount > 0
    }

    /** 元工具的最小实现：只读、无审批、行为由闭包给定。 */
    private class MetaTool(
        override val name: String,
        override val description: String,
        override val parameters: JsonSchema,
        private val block: suspend (JsonObject) -> ToolResult
    ) : AgentTool {
        override val readOnly: Boolean get() = true
        override suspend fun execute(args: JsonObject): ToolResult = block(args)
    }
}
