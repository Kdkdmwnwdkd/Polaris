package com.beichen.polaris.core.capability

import com.beichen.polaris.core.AgentLogger

/**
 * 能力链的**可观测性旁路**：把降级轨迹逐级写入日志，并在末尾记一条裁决行。
 *
 * ## 为什么单独一个文件
 *
 * 求解（[resolveCapability]）是纯函数，不碰 IO、不碰全局状态；日志是它之外的旁路。
 * 这两件事原本挤在一个文件里，导致 [CapabilityChain.kt] 顶着
 * `AgentLogger → LogRedaction → ToolCatalog` 一整条依赖链——于是 JVM harness 想跑
 * 「能力链的纯逻辑」就得把半个 core 拖进来，最后只能退化成读源码。
 * 拆开之后，纯逻辑文件零 Android 依赖，判据可以真跑行为断言。
 *
 * 异常安全：日志只是旁路，任何情况下都不该改变求解结果。
 */

/** 日志标签（降级轨迹逐级落日志，便于真机诊断）。 */
private const val TAG = "CapabilityChain"

/**
 * 把降级轨迹逐级写入日志（每级一行：候选名 + 可用性 + 原因），并在末尾记一条裁决行。
 *
 * 调用方（ViewModel / 工具层）在拿到结果后调用本方法即可。**注意**：调用频率要有节制——
 * 2026-11-22 云机实测中，无状态变化的重复调用把诊断包的「最近 300 行」挤掉了 73%，
 * 崩溃前的现场因此丢失。去重由调用方负责（见 `capabilityFingerprint`）。
 */
fun CapabilityResolution.logTrace() {
    trace.forEach { entry ->
        AgentLogger.i(
            TAG,
            "[${capability.wire}] 候选「${entry.label}」可用=${entry.available}" +
                (entry.reason?.takeIf { it.isNotBlank() }?.let { "，原因=$it" } ?: "")
        )
    }
    when (this) {
        is CapabilityResolution.Selected -> {
            // W-61：原来是 `降级 ${trace.size} 级`——**off by one**。trace 从首候选一直列到
            // 选中项，选中首选时 trace.size 已经是 1，于是日志写成「降级 1 级」；选中第 2 档
            // 写成「降级 2 级」。真机日志里 [run_shell] 明明命中的是首选 Shizuku，却显示
            // 「降级 1 级」，读日志时会被误导成「有更优候选但没选它」。
            val degraded = (trace.size - 1).coerceAtLeast(0)
            AgentLogger.i(
                TAG,
                "[${capability.wire}] 选中「${backend.label}」（" +
                    (if (degraded == 0) "首选" else "降级 $degraded 级") + "）"
            )
        }
        is CapabilityResolution.Unavailable ->
            AgentLogger.w(TAG, "[${capability.wire}] 全链不可用：$message")
    }
}
