package com.beichen.polaris.ui

import com.beichen.polaris.core.capability.BackendAvailability
import com.beichen.polaris.core.capability.Capability
import com.beichen.polaris.core.capability.CapabilityRequest
import com.beichen.polaris.core.capability.CapabilityResolution
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
) {

    private val _plans = MutableStateFlow<List<CapabilityResolution>>(emptyList())
    val plans: StateFlow<List<CapabilityResolution>> = _plans.asStateFlow()

    /** 重算全部能力的就绪度（进入权限页 / 权限或自动化状态变化时调用）。 */
    fun refresh() {
        _plans.value = Capability.entries.map { capability ->
            resolveCapability(CapabilityRequest(capability, snapshotFor(capability)))
                .also { it.logTrace() }
        }
    }
}
