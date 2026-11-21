package com.beichen.polaris.ui

import com.beichen.polaris.core.capability.BackendAvailability
import com.beichen.polaris.core.capability.Capability
import com.beichen.polaris.core.capability.CapabilityRequest
import com.beichen.polaris.core.capability.CapabilityResolution
import com.beichen.polaris.core.capability.capabilityFingerprint
import com.beichen.polaris.core.capability.logTrace
import com.beichen.polaris.core.capability.resolveCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 能力就绪度快照（DoD⑤，3.1 第四批拆分，自 ChatViewModel 原地搬出）。
 *
 * 逐条能力给出「选中的执行路径 + 降级轨迹 + 最终引导」，供权限中心页「一次走完」地
 * 告诉用户需要做什么，而不是让用户逐条工具试错。
 *
 * 为什么能独立成块：本类只做「快照 → 裁决」这一步，两份快照都由宿主注入——
 *  Android 侧探测（proot/rootfs 是否有、无障碍是否就绪、Shizuku 是否就绪）
 *  属于各自的域，本类不碰。
 *
 * 裁决后把降级轨迹逐级落日志（可观测），**不静默**。
 */
internal class CapabilityReadiness(
    private val snapshotFor: (Capability) -> List<BackendAvailability>,
    /**
     * 落日志的动作，默认走 [logTrace]。可注入是为了让 harness 能数「这次 refresh 到底打了几条」——
     * 去重这件事没法靠读源码证明（源码里就是一句 if），只能在行为上数行数。
     */
    private val log: (CapabilityResolution) -> Unit = { it.logTrace() },
) {

    private val _plans = MutableStateFlow<List<CapabilityResolution>>(emptyList())
    val plans: StateFlow<List<CapabilityResolution>> = _plans.asStateFlow()

    /** 上一次落过日志的裁决指纹；`null` = 本次进程还没落过。 */
    private var lastLoggedFingerprint: String? = null

    /** 重算全部能力的就绪度（进入权限页 / 权限或自动化状态变化时调用）。 */
    fun refresh() {
        val next = Capability.entries.map { capability ->
            resolveCapability(CapabilityRequest(capability, snapshotFor(capability)))
        }
        _plans.value = next
        // 同一份裁决重复落盘会把诊断包的「最近 300 行」挤满：实测 234 行里 171 行是能力链，
        // 而每次 refresh 都整表打一遍、内容还完全一样（无状态变化也照打）。
        // 崩溃前那几行因此被顶出窗口——那几行才是诊断包存在的理由。
        // 指纹算法在 core（[capabilityFingerprint]），那里是纯函数、harness 直接跑行为断言；
        // 这里只剩「比不比、打不打」这一句。
        val fingerprint = capabilityFingerprint(next)
        if (fingerprint != lastLoggedFingerprint) {
            next.forEach(log)
            lastLoggedFingerprint = fingerprint
        }
    }
}

