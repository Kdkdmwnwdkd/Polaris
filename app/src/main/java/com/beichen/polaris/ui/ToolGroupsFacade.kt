package com.beichen.polaris.ui

import android.app.Application
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.KernelExposure
import com.beichen.polaris.core.ToolRegistry
import com.beichen.polaris.core.capability.ToolActivationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 工具暴露面开关域（3.1 第五批拆分，自 ChatViewModel 原地搬出）。
 *
 * 为什么能独立成块：这一域只做三件事——把「本任务开过哪些组」落盘、按工具数重算设置页
 * 的行、按用户点击增删一个组。它**不**决定「哪个工具能跑」（那是 [KernelExposure] 的
 * 裁剪算法），也不认识会话与生成链，与 loop 的唯一接触面是那个共享集合。
 *
 * **集合实例由宿主注入**，不是 facade 自己 new：loop 的 `activeGroups` 拿的是同一个
 * 实例，元工具 `__use_tools` 就地改它。若这里另建一个集合，用户点击与 agent 开启会各写
 * 各的——表现是「设置页显示已关闭，实际每轮还在付钱」，没有报错、没有日志。
 *
 * 锁对象就是集合本身（与 `KernelExposure.plan` 同一把），这条约定一并搬过来：
 * 只要有人遍历，就必须先拿到集合的监视器，否则会在 joinToString 里撞
 * ConcurrentModificationException。
 */
internal class ToolGroupsFacade(
    private val app: Application,
    /** 与 loop 共享的同一个集合实例（禁止在此 new）。 */
    private val groups: MutableSet<String>,
    private val registry: ToolRegistry,
) {

    private val _ui = MutableStateFlow<List<ToolGroupRow>>(emptyList())

    /** 把当前激活组落盘（失败只记日志，不打断收尾——见 ToolActivationStore 的失败模式）。 */
    /**
     * 把当前激活组落盘并重算 UI。
     *
     * 公开是因为**收尾点不在这个类里**：一轮生成结束（成功/失败/取消）由宿主的
     * GenerationRunController.onFinished 触发，那里必须能叫到这里——漏掉那一次，
     * 「这次开的组」就只活在内存里，杀进程即回到 CORE 集。
     */
    fun persist() {
        // 快照后落盘：集合可能正被 agent 线程改（每轮 plan 会 seed/pin/淘汰），
        // 直接传进去会在 joinToString 里撞 ConcurrentModificationException。
        // 锁对象就是集合本身，与 KernelExposure.plan 用同一把（见那里的 KDoc）。
        val snapshot = synchronized(groups) { groups.toList() }
        ToolActivationStore.save(app, snapshot)
        refresh()
    }

    /**
     * 设置页要展示的组清单（已启用在前，同状态内按工具数降序）。
     *
     * 为什么是 StateFlow 而不是「进页面时算一次」：关掉一个组之后同屏的其它行要立刻
     * 重新排序/置灰，靠 `remember` 算一次会停在旧快照上，用户点第二下才发现已经关了。
     */
    val ui: StateFlow<List<ToolGroupRow>> = _ui.asStateFlow()

    /**
     * 重算 [ui]。
     *
     * 调用点：设置页进入时（`LaunchedEffect`）、每次落盘激活组之后、以及用户手动关闭后。
     * 不做成「响应式监听注册表」：MCP 工具是异步注册的，真要做到实时得给 registry 加
     * 观察者，而这块 UI 的用途只是「看一眼、关掉一个」，下一次进页面刷新足够。
     */
    fun refresh() {
        val counts = KernelExposure.groupsOf(registry.list()).toMap()
        val snapshot = synchronized(groups) { groups.toSet() }
        _ui.value = counts.entries
            // 排序：常驻 → 其它已启用 → 未启用；同档按工具数从多到少。
            // 「常驻」必须排在「已启用」之前：file 永远是启用态，若只按启用与否排，
            // 用户勾上一个 9 个工具的组，那一行就会插到 file 上方，看起来像 file 掉了。
            .sortedWith(
                compareBy(
                    { it.key !in snapshot },
                    { !KernelExposure.isPinned(it.key) },
                    { -it.value },
                    { it.key }
                )
            )
            .map { (g, n) ->
                ToolGroupRow(
                    name = g,
                    note = KernelExposure.noteOf(g),
                    count = n,
                    active = g in snapshot,
                    pinned = KernelExposure.isPinned(g)
                )
            }
    }

    /**
     * 手动关掉一个工具组（W-103）——补上「粘性激活」缺的另一半。
     *
     * 之前只有「新建会话」能清空：用户在一个长会话里用完 `shell` 就再也关不掉，
     * 只能等下次新建会话。而 `shell` 组 4 个工具 2676 字符，每轮都在付。
     *
     * 常驻组（`file` / `misc`）直接忽略：它们在 [KernelExposure.plan] 里会被强制加回，
     * 关掉是假动作，UI 也不给入口（见 [ToolGroupRow.pinned]）。
     *
     * **不需要通知 loop**：组集合是同一个实例，下一轮 `plan()` 直接用新状态。
     */
    fun drop(group: String) {
        if (KernelExposure.isPinned(group)) return
        // 与 agent 线程共用同一把锁（集合本身）：关组时那一轮可能正在 plan 里遍历它。
        val removed = synchronized(groups) { groups.remove(group) }
        if (!removed) return
        AgentLogger.i(
            "ToolExposure",
            "用户手动关闭工具组 $group，剩余=" +
                synchronized(groups) { groups.joinToString(",") }
        )
        persist()
    }

    /**
     * 手动开一个工具组（用户误关了想恢复）。
     *
     * 和 [drop] 共用同一把锁；顺序语义与 `__use_tools` 一致：先 remove 再 add，
     * 让这组排到 LRU 末尾（刚用过）。超上限时 [KernelExposure.enforceCap] 会挤掉最久未用的，
     * UI 下一帧就会看到那一行变灰——不需要额外弹窗解释。
     */
    fun enable(group: String) {
        // 判存在要用 `in`，不能用 `!remove(...)`：
        // remove 返回 true 表示「原本就在、已经被我拿掉了」——依据它分支会把
        // 「不存在」当成「已在」，然后**一个组也加不进去**（设置页勾选框点了没反应）。
        val (changed, effective, remaining) = synchronized(groups) {
            if (group in groups) {
                // 已经开着：不改变 LRU 顺序，避免「点一下已勾选的框」把它顶成最新
                Triple(false, true, groups.joinToString(","))
            } else {
                // remove + add 与 `__use_tools` 同形：让这组排到 LRU 末尾（刚用过）。
                // 本来就不在里面，remove 是空操作，保留只为两条路径写法一致。
                groups.remove(group)
                groups.add(group)
                KernelExposure.enforceCap(groups)
                // 回执写**实际结果**：容量满时本次加入可能立刻被滞回淘汰掉。
                Triple(true, group in groups, groups.joinToString(","))
            }
        }
        if (!changed) return
        AgentLogger.i(
            "ToolExposure",
            "用户手动开启工具组 $group（" + (if (effective) "已生效" else "被上限挤掉") + "），当前=$remaining"
        )
        persist()
    }

    /**
     * 清空粘性激活（新会话时调用）。
     *
     * 不清会怎样：用户做完一次屏幕任务，新开一个纯聊天会话，ui 组的 9 个工具依旧
     * 每轮随请求发出——约 4500 字符，一条都用不到。这正是「不需要它的时候它还在」。
     *
     * 清空不等于「一个工具都不给」：下一次 `plan()` 会自动重新种上 file 组与元工具，
     * 等于回到刚装好的状态。
     */
    fun reset() {
        // 同上：清空也是一次写，必须和 agent 线程的遍历互斥。
        val hadAny = synchronized(groups) {
            if (groups.isEmpty()) {
                false
            } else {
                groups.clear()
                true
            }
        }
        if (!hadAny) return
        persist()
    }
}
