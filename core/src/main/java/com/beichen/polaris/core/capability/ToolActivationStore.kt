package com.beichen.polaris.core.capability

import android.content.Context
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.KernelExposure

/**
 * 工具组激活状态的持久化（W-102）。
 *
 * ## 为什么需要落盘
 * `KernelExposure` 的 `active` 从「一次 send 内有效」改成粘性之后，如果只放在内存里，
 * 杀进程就回到 CORE 5 个工具，用户第二天打开还得让模型重新 `__use_tools` 一遍——
 * 而开一次组的代价是一次完整往返（见 [KernelExposure.plan] 的 KDoc）。
 *
 * ## 存什么
 * 只存**组名**，按 LRU 顺序（迭代序 = 最久未用在前）。不存工具名：工具与组的映射是
 * 代码里的 [KernelExposure.groupOf]，落盘会变成第二份真相，改名即失配。
 *
 * ## 与「上限」的关系
 * 这里不做裁剪。[KernelExposure.enforceCap] 在每次 `plan()` 时统一裁决，
 * 且它是纯函数、可断言。存储层只负责「原样存取」，不参与业务判断。
 *
 * ## 失败模式
 * 读写全包 `runCatching`：SharedPreferences 在极少数机型上会抛（磁盘满、多进程竞争）。
 * 失败等于「这次没记住」，下次退化成默认的 CORE 集——**降级到没用优化，不是功能坏掉**
 * （与报告 §11.3「降级优先于收益」一致）。
 */
object ToolActivationStore {

    private const val TAG = "ToolActivation"
    private const val PREF_NAME = "tool_activation"
    private const val KEY_GROUPS = "active_groups"

    /**
     * 读回上次的激活组（LRU 序）。
     *
     * 返回可变集合，且保证是 `LinkedHashSet`——[KernelExposure.enforceCap] 的淘汰顺序
     * 依赖插入序，换成无序实现会让「最久未用」变成「随机」。
     */
    fun load(context: Context): MutableSet<String> {
        val raw = runCatching {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_GROUPS, null)
        }.onFailure {
            AgentLogger.w(TAG, "读取激活组失败（退化为默认）：${it.message}")
        }.getOrNull()

        val out = LinkedHashSet<String>()
        raw?.split(',')?.forEach { g ->
            val name = g.trim()
            // 只收合法组名字符：防止落盘内容被外部改坏后带进奇怪的东西
            if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                out.add(name)
            }
        }
        return out
    }

    /** 写入激活组。空集合也要落（表示「用户主动清空了」，不能当成没存过）。 */
    fun save(context: Context, groups: Collection<String>) {
        runCatching {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_GROUPS, groups.joinToString(","))
                .apply()
        }.onFailure {
            AgentLogger.w(TAG, "保存激活组失败（本次不记忆）：${it.message}")
        }
    }
}
