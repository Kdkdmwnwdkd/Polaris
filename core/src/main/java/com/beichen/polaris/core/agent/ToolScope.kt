package com.beichen.polaris.core.agent

import com.beichen.polaris.core.KernelExposure
import com.beichen.polaris.core.ToolRegistry

/**
 * 暴露面（[KernelExposure.Plan]）→ **回合级执行视图**。
 *
 * ## 为什么这一步值得单独存在
 *
 * 规划（这一轮该露出哪些工具）已经由 [KernelExposure.plan] 决定，并且有 harness 判据。
 * 但「拿规划去建视图」这一步有两个**不会报错**的错法，两个都只在真机上表现为
 * 「模型的行为很奇怪」：
 *
 *  1. **视图不收紧**（直接 return 全量）。执行器靠「[ToolRegistry] 里有它、而回合视图里
 *     没有」来区分「模型记错了名字」与「名字对、只是没开组」两种情形。视图一旦等于全量，
 *     后一条分支永远命中不了：被裁掉的工具照常直接执行——裁剪只是没省到 token，
 *     更糟的是**本该先 `__use_tools` 的那次调用被静默放行**，审批层看到的是一次正常调用。
 *  2. **视图收紧过头**（连元工具一起滤掉）。`__use_tools` 自己的 description 里塞着全部
 *     分组的清单，几百字符，看起来是最该"优化"掉的。滤掉之后模型再也开不了新组，
 *     而它唯一能看到的提示是「未知工具」，于是开始猜别的工具名——一次开组能解决的往返，
 *     变成若干次瞎猜加失败回填。
 *
 * ## 返回同一实例而不是等价副本
 *
 * 未裁剪（[KernelExposure.Plan.trimmed] = false）时直接返回入参 [full]。全量模式下
 * `visible` 就是全部工具，照抄一份 58 个条目的新表不但白花每轮的开销，还会切断
 * 「视图 === 全量」这个可比较的事实——`RegistryToolExecutor` 注入的 `fullRegistry`
 * 正是同一个对象，两者相等时后续按名字兜底的判定才是显然成立的。
 */
internal object ToolScope {

    /**
     * 按 [plan] 造本回合的工具视图。
     *
     * - 未裁剪 → 原样返回 [full]（零分配、身份不变）；
     * - 裁剪 → 新建仅含 `plan.visible` 的注册表，被裁工具在该视图内**查无此工具**，
     *   于是 `descriptorOf` 解析为 null、执行器走「未启用」路径（指出所属组 + 开组指引），
     *   而不是抛异常或误执行。
     *
     * 视图是一次性快照：本回合内不再反映 [full] 的变化（工具在装配期注册完毕后即稳定，
     * MCP 的动态注册走的是全局 [full]，不经这里）。
     */
    fun viewOf(full: ToolRegistry, plan: KernelExposure.Plan): ToolRegistry {
        if (!plan.trimmed) return full
        return ToolRegistry().apply { plan.visible.forEach { register(it) } }
    }
}
