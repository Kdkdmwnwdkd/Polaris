package com.beichen.polaris.core.llm.local

/**
 * 资源档位：把「这台机器该用多大的推理参数」收拢成一张表。
 *
 * 背景：线程数、上下文上限、批大小这些参数各自散在设置里，默认值是按「平均机器」
 * 拍的（核数 −2 夹 4~6）。真机反馈反复出现同一类问题——同一份默认值，在旗舰上是
 * 保守，在 4GB 入门机上就是压死进程的那根稻草（参见 [LocalDeviceProfile] 里
 * 「4096 ctx 在 8GB 机器上就是压死进程的那一档」的注释）。
 *
 * 缺的就是这层「探测结果 → 参数」的落点：[LocalDeviceRules.advise] 已经把机型
 * 归到 [DeviceTier]，这里顺着它加一张档位表，把散参数按档收口：
 *
 *   - [ResourceTier.ECO]        入门 / 中低端：压线程、压上下文、建议安全批；
 *   - [ResourceTier.BALANCED]   中高端：维持现行默认，只封顶不抬升；
 *   - [ResourceTier.PERFORMANCE] 旗舰：不设额外限制（设置里给多少用多少）。
 *
 * 用户可以手动锁档（[LocalLlamaSetting.resourceTierLock]）：锁了就完全按档位表来，
 * 自动选档只在 AUTO 下生效。规则层是纯 Kotlin——一旦引到安卓的类，行为断言
 * 就编不过、也跑不了（与 [LocalDeviceRules] 同一条纪律）。
 *
 * 移植自 runtime/resource-plan-w2 的 W-RT2（旧包名 com.example.agentkit），
 * 规则表本体未改一个数字，只换包名与内部引用。
 */
enum class ResourceTier(val label: String) {
    ECO("省电"),
    BALANCED("均衡"),
    PERFORMANCE("性能");

    companion object {
        /** 解析用户锁档；认不出（含 null / "AUTO"）返回 null = 跟随自动选档。 */
        fun parse(text: String?): ResourceTier? =
            text?.trim()?.uppercase()?.let { name -> entries.firstOrNull { it.name == name } }
    }
}

/** 一个档位落下来的具体参数。 */
data class ResourcePlan(
    val tier: ResourceTier,
    /** 档位线程上限。自动档只减不增（min），锁档按表直取。 */
    val threads: Int,
    /** 档位上下文上限（token）。 */
    val contextCap: Int,
    /** 是否建议开安全模式（压批）。只建议、不代开——安全模式是用户面前的东西。 */
    val suggestSafeMode: Boolean,
    /** 进日志和设置页的一句话理由。 */
    val reason: String,
) {
    /** 自动档只减不增：档位比用户设置松时，以用户设置为准。 */
    fun effectiveThreads(userThreads: Int): Int = minOf(userThreads, threads)

    /** 同上：档位上限比用户设置松时不动。 */
    fun capContext(userCtx: Int): Int = minOf(userCtx, contextCap)
}

object ResourcePlanRules {

    /**
     * 档位表本体。改这里的数字就是改产品行为，改完必须同步更新
     * scripts/qa/run-resource-plan-verification.sh 的断言。
     */
    fun specFor(tier: ResourceTier): ResourcePlan = when (tier) {
        ResourceTier.ECO ->
            ResourcePlan(
                tier = tier,
                threads = 4,
                contextCap = 4096,
                suggestSafeMode = true,
                reason = "入门 / 中低端机型：内存与散热余量小，压线程保流畅，上下文封顶 4096",
            )
        ResourceTier.BALANCED ->
            ResourcePlan(
                tier = tier,
                threads = 6,
                contextCap = 8192,
                suggestSafeMode = false,
                reason = "中高端机型：沿用现行默认口径，只对激进设置封顶",
            )
        ResourceTier.PERFORMANCE ->
            ResourcePlan(
                tier = tier,
                threads = 8,
                contextCap = 16384,
                suggestSafeMode = false,
                reason = "旗舰机型：不设额外限制，设置里给多少用多少",
            )
    }

    /** 自动选档：跟着 [DeviceAdvice.tier] 走，不重复发明机型判定。 */
    fun autoTier(advice: DeviceAdvice): ResourceTier = when (advice.tier) {
        DeviceTier.ENTRY, DeviceTier.LOW -> ResourceTier.ECO
        DeviceTier.MID -> ResourceTier.BALANCED
        DeviceTier.HIGH -> ResourceTier.PERFORMANCE
    }

    /**
     * 最终落档：锁档优先，AUTO 按画像；画像缺失时 AUTO 不猜档。
     *
     * 自动档与锁档的行为差异是故意的：
     *   - AUTO 是「替不懂参数的用户兜底」，只减不增（[ResourcePlan.effectiveThreads] /
     *     [ResourcePlan.capContext] 里的 min）——用户明确调高的值不该被悄悄改掉；
     *   - 锁档是「用户点名要这档」，按表直取，E 档就是 4 线程，不含糊。
     *
     * 为什么 advice 收成可空：[LocalLlamaClient] 里的画像来自
     * [AndroidDeviceProbe.cachedProfile]，启动早期采集还没跑完时是 null。AUTO 的语义是
     * 「跟机型走」，机型未知就不能拍脑袋猜档——猜成省电会把旗舰压到 4 线程，
     * 猜成性能又失去兜底意义。此时退回 [unprobed]：不套任何档位，
     * 参数完全按用户设置走（画像一到，下一轮装载自然落回正常档）。
     * 锁档不受影响：用户点名要这档，不需要画像。
     *
     * 实现注意：**只此一个函数**。可空/非空两个重载在 JVM 里擦除后签名相同
     * （旧分支 run 483 编译红的教训），收成单参数可空版，调用方传非空值照常工作。
     */
    fun resolve(lock: String?, advice: DeviceAdvice?): ResourcePlan {
        val locked = ResourceTier.parse(lock)
        val tier = locked ?: advice?.let(::autoTier) ?: return unprobed()
        return specFor(tier)
    }

    /**
     * 画像未采集完成时的「不套档位」：线程与上下文上限给到无穷大，
     * [ResourcePlan.effectiveThreads] / [ResourcePlan.capContext] 的 min
     * 天然等于用户设置原样生效（只减不增的空集）。
     * 调用方的日志只在档位真的压了参数时才打——这一档永远不会压，
     * 所以「未套档位」不会给用户任何噪音；这是有意的。
     */
    fun unprobed(): ResourcePlan = ResourcePlan(
        tier = ResourceTier.PERFORMANCE,
        threads = Int.MAX_VALUE,
        contextCap = Int.MAX_VALUE,
        suggestSafeMode = false,
        reason = "设备画像尚未采集完成，本轮不套档位（参数按你的设置原样生效）",
    )
}
