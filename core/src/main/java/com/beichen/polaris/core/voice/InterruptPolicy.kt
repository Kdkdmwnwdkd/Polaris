package com.beichen.polaris.core.voice

/**
 * 此刻 App 在忙什么。两个维度**可以同时成立**：回合跑着的同时，工具调了 `speak` 在播报。
 *
 * 为什么不做成三值枚举（Idle / Thinking / Speaking）：那样就必须选一个，
 * 而「回合在跑 + 正在播报」是真实存在的状态（`SpeakTool` 在回合内被调用）。
 * 强行归一只能丢掉一个维度，丢掉的那个恰好是打断判据要用的。
 */
data class Activity(
    val turnRunning: Boolean = false,
    val speaking: Boolean = false,
) {
    val idle: Boolean get() = !turnRunning && !speaking

    companion object {
        val IDLE = Activity()
    }
}

/**
 * 一次新输入从哪来。这个枚举是整套仲裁的**输入变量**——同一个时刻、同一句话，
 * 来源不同，该不该打断就不同。
 */
enum class RequestSource {

    /** 用户按麦克风说话或语音识别回调。 */
    Voice,

    /** 用户在输入框打字后按发送。 */
    Text,

    /** 唤醒词命中（桌宠常驻监听）。 */
    Wake,

    /** 桌宠被点击 / 拖动后触发。 */
    Pet,
}

/** 需要先停掉什么。 */
enum class StopScope {

    /** 什么都不用停。 */
    None,

    /** 只停播报（回合可能已经结束，只是还在念）。 */
    Speech,

    /** 停回合。**隐含也要停播报**——回合没了，它的播报没有归属。 */
    Turn,
}

/**
 * 仲裁结论。
 *
 * 三个分支对应三种真实结果：直接放行、先停再放行、不放行。没有第四种
 * （「放行但警告」）——那等于把决策责任推回给调用方，而调用方正是不知道该怎么办才来问的。
 */
sealed interface InterruptDecision {

    /** 直接放行。 */
    data object Accept : InterruptDecision

    /**
     * 先执行 [stop]，然后再放行。
     *
     * @property notice 给用户看的一句话。**必须显示**：用户按下发送后上一轮回答突然断了，
     *   没有这句话他不知道是自己按断的还是 App 崩了。
     */
    data class AcceptAfter(
        val stop: StopScope,
        val notice: String,
    ) : InterruptDecision

    /**
     * 不放行。
     *
     * @property code 机器可读码，必须稳定。
     * @property message 给用户看的一句话（说明「为什么不理你」）。
     */
    data class Reject(
        val code: String,
        val message: String,
    ) : InterruptDecision
}

/**
 * 打断仲裁：**谁的话算数**。
 *
 * 这是纯函数，没有状态、没有 IO、不持有任何对象。理由是这张规则表会被反复质疑
 * （「为什么打字能打断、唤醒词不能」），必须能一行一行指着断言看，而不是埋在
 * ViewModel 的某个 if 里靠读代码猜。
 *
 * ## 规则表
 *
 * | 当前状态 | Voice | Text | Wake | Pet |
 * |---|---|---|---|---|
 * | 空闲 | 放行 | 放行 | 放行 | 放行 |
 * | 仅播报中 | 停播报 | 停播报 | **拒绝** | **拒绝** |
 * | 回合中 | 停回合 | 停回合 | **拒绝** | **拒绝** |
 *
 * ## 三条判据（按权重）
 *
 * **① 用户的显式动作 > 系统正在进行的工作。**
 * 说话和打字都是用户主动表达新意图，此刻他想要的答案已经变了，把旧回答跑完是浪费
 * 他的时间与 token。所以一律打断，并且告诉他打断了。
 *
 * **② 唤醒词不是指令。**
 * `Wake` 在忙时被拒，这是全表最反直觉、也最容易被实现错的一条。唤醒词的语义是
 * 「我在听」，不是「重来」。若让它打断，桌宠旁边的人随口说一句唤醒词就会掐掉正在
 * 写文件、正在跑命令的回合——而那个回合可能有未闭合的副作用。**打断一个正在执行
 * 副作用的回合，是有代价的动作，只有用户显式表达才配触发。**
 *
 * **③ 播报时不能听。**
 * `Wake` 在播报时被拒，是物理约束不是产品选择：麦克风收到的是自己的声音，
 * 唤醒词会被自己的播报触发，形成自激。这条挡不住的话，桌宠会自己叫自己。
 *
 * ## 刻意不做的事
 *
 * - **不做「排队」**：忙时把请求存下来等空闲再执行。用户说完一句等 30 秒才收到回应，
 *   体验比被拒更差，而且他早就忘了自己说过什么。
 * - **不做「部分打断」**：比如只停当前这一句播报但让回合继续。回合继续就会继续调
 *   `speak`，用户马上又被打断一次——变成一个他关不掉的循环。
 */
object InterruptPolicy {

    /**
     * 裁决。
     *
     * @param activity 此刻的状态（调用方在**动作发生前**采样）
     * @param source 新输入的来源
     */
    fun decide(activity: Activity, source: RequestSource): InterruptDecision {
        // 空闲：任何来源都放行。唤醒词在空闲时是唯一入口，当然要放。
        if (activity.idle) return InterruptDecision.Accept

        return when (source) {
            RequestSource.Text, RequestSource.Voice -> acceptAfter(activity)
            RequestSource.Wake -> InterruptDecision.Reject(
                code = "wake_ignored_busy",
                message = if (activity.turnRunning) {
                    "正在处理上一个任务，唤醒词不会打断它。"
                } else {
                    "正在说话，唤醒暂不响应。"
                },
            )
            RequestSource.Pet -> InterruptDecision.Reject(
                code = "pet_ignored_busy",
                message = if (activity.turnRunning) {
                    "正在处理上一个任务，点一下不会打断它。"
                } else {
                    "正在说话，点一下不会打断它。"
                },
            )
        }
    }

    private fun acceptAfter(activity: Activity): InterruptDecision.AcceptAfter =
        when {
            // 回合在跑：停回合（播报随之停）
            activity.turnRunning -> InterruptDecision.AcceptAfter(
                stop = StopScope.Turn,
                notice = if (activity.speaking) "已停止上一轮回答与播报" else "已停止上一轮回答",
            )

            // 只剩播报：停播报，不动回合（回合本来就结束了）
            activity.speaking -> InterruptDecision.AcceptAfter(
                stop = StopScope.Speech,
                notice = "已停止播报",
            )

            // 理论上到不了这里（idle 已在上面返回）；保留兜底而不是抛异常：
            // 仲裁器崩掉会让「发消息」这个主路径一起崩，代价远大于一次多余放行。
            else -> InterruptDecision.AcceptAfter(StopScope.None, "")
        }
}
