package com.beichen.polaris.core.capability

/**
 * 能力裁决的「指纹」：只取**会改变结论**的那部分，用于判断两次判定是否等价。
 *
 * ## 为什么需要它
 *
 * 2026-11-22 云机实测：诊断包 234 行里 171 行是 `[CapabilityChain]`，而且相隔 10 秒的
 * 两批判定逐字重复——`refresh()` 每次都会把整表重打一遍，哪怕系统状态没变。
 * 后果不是「日志难看」，是**崩溃前那几行被顶出「最近 300 行」的窗口**，
 * 而留给崩溃现场正是诊断包存在的理由。
 *
 * ## 为什么不直接比 data class
 *
 * [CapabilityResolution] 里带 `message` / `hint`，那是给人看的文案。文案改一个标点，
 * `equals` 就不相等，整表重打一遍——等于没去重。指纹只留「选了谁 + 每级是否可用 + 原因」。
 *
 * ## 放在 core 的理由
 *
 * 纯函数、不碰 Context、不碰日志。放在这里 harness 才能直接跑行为断言，
 * 而不是靠读源码里那句 `if` 来"证明"去重生效了。
 */

/** 分隔符用不可见控制字符：backendId / reason 里不会出现，避免拼接歧义。 */
private const val SEP = "\u0001"

/**
 * 单条裁决的指纹。
 *
 * 前缀用 `capability.wire` 而不是 `S:` / `U:`：裁决类型已由 trace 完整决定
 * （末条可用 = 选中；全不可用 = 全链不可用），再存一份就是冗余。
 * 但**能力本身**必须带——空候选链会让 trace 为空，那时 `run_shell` 与 `control_screen`
 * 都会拼出空串，不去重表里就撞成一条。
 */
fun resolutionFingerprint(resolution: CapabilityResolution): String =
    resolution.capability.wire + ":" + resolution.trace.joinToString("|") { entry ->
        "${entry.backendId}=${entry.available}:${entry.reason.orEmpty()}"
    }

/**
 * 整表的指纹。
 *
 * 顺序即 [Capability.entries] 的顺序，调用方保证入参按该顺序排列（`refresh()` 就是这么造的）；
 * 这里不再排序——排序会让「同一份输入两次调用」的语义变得可疑，而它本来就该是确定的。
 */
fun capabilityFingerprint(resolutions: List<CapabilityResolution>): String =
    resolutions.joinToString(SEP) { resolutionFingerprint(it) }
