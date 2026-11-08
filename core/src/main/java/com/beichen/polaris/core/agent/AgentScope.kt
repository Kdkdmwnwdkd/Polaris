package com.beichen.polaris.core.agent

import com.beichen.polaris.core.agent.journal.RunScope
import com.beichen.polaris.core.agent.journal.TurnJournal
import kotlinx.coroutines.ThreadContextElement
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * 当前执行域（R7）：「正在执行的是谁」这一维度。
 *
 * ## 为什么不能靠逐层传参
 *
 * 技能脚本与子代理都在**别人**的调用栈里执行：
 *
 * ```
 * 主代理回合
 *   └─ use_skill 工具（主路径）
 *        └─ SkillExecutor.runSkill
 *             └─ Rhino 线程
 *                  └─ SkillRuntimeBridge.tool()   ← 脚本在这里调工具
 * ```
 *
 * 要让最内层的 `bridge.tool()` 知道「当前回合日志句柄是谁」，逐层加参数会一路污染：
 * `use_skill` 工具签名、`SkillExecutor.runSkill` 签名、`SkillRuntimeBridge` 构造参数……
 * 每加一条新执行路径就要改一遍全部中间层——这正是「漏一处就静默失效」的做法。
 *
 * 用协程上下文传递则只改两端：**入口安装、出口读取**。中间层不用知道有这回事，
 * 新增执行路径也不用记得改签名。
 *
 * ## 为什么用 ThreadContextElement 而不是裸 ThreadLocal
 *
 * 裸 ThreadLocal 在协程里是**错的**：协程会挂起并在**另一个线程**恢复，
 * 恢复时 ThreadLocal 的值是那个线程的旧值。ThreadContextElement 由协程库
 * 负责「挂起时存、恢复时还原」，跨线程切换下取值依然正确。
 *
 * ## 与 [RunScope] 的分工
 *
 * [RunScope] 是**名字**（写进日志的字符串）；本类是**载体**（连同日志句柄与步号一起传）。
 */
data class AgentScope(
    /**
     * 本执行域的回合日志句柄。
     *
     * 技能脚本与子代理拿到的是**派生句柄**（`turn.scoped(...)`）——同一文件、
     * 同一把锁，但事件带上各自的 scope。
     *
     * null = 本回合没有日志（改造前的行为，或日志文件打不开）。
     */
    val turn: TurnJournal.Turn? = null,

    /** 本执行域的作用域名。 */
    val scope: String = RunScope.MAIN,

    /**
     * 当前步号（由 loop 推进）。
     *
     * 用 [AtomicInteger] 而不是普通 Int：loop 在协程里推进步号，而技能脚本可能在
     * 另一个线程（Rhino）读它。步号写错不影响正确性（只影响排障定位），
     * 但仍然不该有数据竞争。
     */
    val currentStep: AtomicInteger = AtomicInteger(0)
)

/**
 * 把 [AgentScope] 挂进协程上下文的元素（R7）。
 *
 * 用法：
 * ```
 * withContext(AgentScopeContext(AgentScope(turn))) { ... }   // 安装
 * val scope = currentAgentScope()                            // 读取
 * ```
 */
/**
 * [AgentScopeContext] 的专用 key。
 *
 * 为什么不用 [ThreadContextElement] 自带的 key：那个 key 是所有 ThreadContextElement
 * **共享**的，多个上下文元素挂进同一个协程时只有最后一个能被按 key 取到。
 * 自己定义一个才拿得回本元素。
 */
object AgentScopeKey : CoroutineContext.Key<AgentScopeContext>

/**
 * 把 [AgentScope] 挂进协程上下文的元素（R7）。
 *
 * 用法：
 * ```
 * withContext(AgentScopeContext(AgentScope(turn))) { ... }   // 安装
 * val scope = currentAgentScope()                            // 读取
 * ```
 */
class AgentScopeContext(val scope: AgentScope) :
    ThreadContextElement<AgentScope?>,
    AbstractCoroutineContextElement(AgentScopeKey) {

    override fun updateThreadContext(context: CoroutineContext): AgentScope? = scope

    /** 无需恢复：上下文元素出栈时线程上的值由库管理，这里不做额外动作。 */
    override fun restoreThreadContext(context: CoroutineContext, oldState: AgentScope?) = Unit
}

/**
 * 读取当前执行域（R7）。不在执行域内时返回 null（= 主路径无日志的旧行为）。
 *
 * 只能在**挂起**函数里调用——协程上下文是挂起上下文才有的。
 * 非挂起函数（如 Rhino 同步回调）拿不到当前域，只能靠构造时显式传入。
 */
suspend fun currentAgentScope(): AgentScope? = coroutineContext[AgentScopeKey]?.scope
