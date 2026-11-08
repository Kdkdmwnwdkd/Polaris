package com.beichen.polaris.core.agent

import com.beichen.polaris.core.ChatMessage
import com.beichen.polaris.core.ToolCall
import com.beichen.polaris.core.ToolMessage

/**
 * 执行过程钩子（T01，四时机通知，默认空实现）。
 *
 * 定位：**只读通知**，不参与改写——改配置走 [AgentLoop.update]，改工具表走
 * ToolRegistry；钩子只回答「什么时候发生了什么事」，供日志 / 埋点 / 测试观测。
 * 全部为非挂起函数：调用点可能在 NonCancellable（树尾闭合）或并行 async 里，
 * 非挂起保证任何时机调用都不会二次抛取消；需要挂起操作（如落盘）请自行 launch。
 *
 * 四时机与命中次数（harness 断言基准，正常两轮对话 = 2 次 LLM + 1 批 2 个工具）：
 *  - [beforeSerialization]：每次 LLM 请求序列化前（回合内每轮一次，本例 2 次）。
 *  - [beforeToolCall]：每个工具调用进入 executor 时（本例 2 次，与调用数相等）。
 *  - [afterToolCall]：每个工具调用产出 ToolMessage 时（本例 2 次；取消路径
 *    不产出 message，故不命中——afterToolCall 与 beforeToolCall 在非取消路径严格配对）。
 *  - [beforeStop]：send() 正常返回前一次（正常结束 / 优雅收尾 / 达到 maxSteps；
 *    取消路径与会话级失败路径不命中——回合未正常完结）。
 */
interface Hooks {

    /** 每次 LLM 请求前（消息即将序列化发给供应商）。[messages] 为当次请求的快照。 */
    fun beforeSerialization(messages: List<ChatMessage>) {
        // 默认空实现：不需要观测的实现方零成本
    }

    /** 每个工具调用进入 executor 前（参数已定）。未知工具 / 熔断 / 审批路径同样命中。 */
    fun beforeToolCall(call: ToolCall) {
        // 默认空实现
    }

    /** 每个工具调用产出回喂 ToolMessage 后（成功 / 业务失败 / 异常兜底都命中）。 */
    fun afterToolCall(call: ToolCall, message: ToolMessage) {
        // 默认空实现
    }

    /** 回合正常完结（send() 返回）前一次。 */
    fun beforeStop() {
        // 默认空实现
    }
}

/** 默认钩子：四时机全空。构造 loop 不传 hooks 时使用。 */
object NoopHooks : Hooks
