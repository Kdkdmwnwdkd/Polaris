package com.beichen.polaris.automation

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 动作回执：执行之后等界面变化，没变就别告诉模型「已生效」。
 *
 * ## 为什么这段逻辑单独成文件
 *
 * 修前它和 `AgentAccessibilityService` 写在同一处，于是整段回执没有任何 JVM 断言——
 * 唯一能测的方式是上真机点一下。而它恰恰是**最容易写反**的一段：
 *
 * | 写法 | 后果 |
 * |---|---|
 * | 超时当「已变化」（`?: true`） | 模型以为点击生效，实际被弹窗挡住。比不检测更糟——它不会再试 |
 * | 恒不返回（`while (true)`） | 界面明明变了也报 `no_effect`，模型开始重复点 |
 * | 去掉 `delay` | 忙等烧 CPU，主线程卡死，`withTimeoutOrNull` 也调度不上 |
 *
 * 把「判定」与「取指纹」拆开：本文件只认一个 `sense: () -> Int`，不 import 任何
 * `android.*`；调用方把 `getUiTreeSnapshot().json.hashCode()` 传进来。
 *
 * ## 时间的取值
 *
 * 600ms 是「中端机点击后界面完成一次重绘」的经验值：比它短会误报 `no_effect`，
 * 比它长会让连续操作（点一下、再点一下）明显变慢。轮询只在**没有变化**时发生，
 * 界面一变就立刻返回，所以正常路径几乎不增加耗时。
 *
 * ## 漏报比误报安全
 *
 * 树里包含时间/倒计时文本，页面有动画时指纹会持续变化，此时「没变化」判不出来——
 * 表现为漏报。漏报的代价只是少一句提示；误报的代价是「把有效动作说成没生效，
 * 诱导模型重试」。所以超时一律返回 false。
 */
internal const val SCREEN_CHANGE_TIMEOUT_MS = 600L
internal const val SCREEN_CHANGE_POLL_MS = 80L

/** 动作已下发但界面没变时给模型的建议——重点是「别重复点同一位置」。 */
internal const val NO_EFFECT_HINT =
    "动作已执行，但 ${SCREEN_CHANGE_TIMEOUT_MS}ms 内界面结构没有变化。不要重复点同一位置：" +
        "先 ui_tree 确认目标 clickable 且未被弹窗遮挡；若页面还在加载，稍等后重试；" +
        "坐标点不准时改用 ui_click 按文本/ID 点击。"

/**
 * 轮询到指纹变化或超时；返回是否检测到变化。
 *
 * @param before  动作**之前**取的指纹。取早了（隔了一次别的协程）会把动作结果包进来，
 *                于是永远判定「没变化」；取晚了则相反。调用方必须保证「取指纹 → 动作 →
 *                本函数」在同一线程顺序执行。
 * @param sense   取当前指纹。允许被调用 0..N 次，实现方不得有副作用。
 * @param timeoutMs 等待上限。`<= 0` 时直接判「没变化」，不取指纹。
 * @param pollMs  轮询间隔。必须为正；0 会退化成忙等，把主线程烧满。
 */
internal suspend fun awaitFingerprintChange(
    before: Int,
    timeoutMs: Long = SCREEN_CHANGE_TIMEOUT_MS,
    pollMs: Long = SCREEN_CHANGE_POLL_MS,
    sense: () -> Int,
): Boolean {
    if (timeoutMs <= 0) return false
    return withTimeoutOrNull(timeoutMs) {
        while (sense() == before) delay(pollMs)
        true
    } ?: false
}
