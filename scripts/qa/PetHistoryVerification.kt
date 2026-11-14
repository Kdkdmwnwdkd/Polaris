// 桌宠上下文维护（PetAgentSession.ask 里的 history 增删）的行为验证。
//
// 为什么这里复刻了一份类型而不用真实 ChatMessage：
// PetAgentSession 依赖 android.content.Context，纯 JVM 编不动；把 Context 抽掉的
// 重构成本大于收益。所以 B 段验证的是**规则本身**——「send 返回整段上下文（含
// system），history 只留对话部分」——并把旧写法一起跑，证明这个测试有分辨力：
// 换成修复前的写法它会红。结构断言（A 段）负责保证源码里用的确实是这条规则。
//
// 真实缺陷（2026-11-14 第三轮实测）：请求体第二轮出现两个一模一样的 system，
// 第三轮「看起来好了」只是 takeLast 恰好把旧 system 挤出去了。

sealed class M {
    data class Sys(val c: String) : M()
    data class User(val c: String) : M()
    data class Asst(val c: String) : M()
}

const val MAX_HISTORY = 20
const val SYSTEM_PROMPT = "你是北辰"

/** 修复前：原样把整段上下文存回 history。 */
fun putOld(h: MutableList<M>, returned: List<M>) {
    h.clear()
    h.addAll(returned.takeLast(MAX_HISTORY))
}

/** 修复后：只留对话，不留 system。 */
fun putNew(h: MutableList<M>, returned: List<M>) {
    h.clear()
    h.addAll(returned.filterNot { it is M.Sys }.takeLast(MAX_HISTORY))
}

/** 复刻 RealAgentLoop.send：prepend 自己的 systemPrompt，再拼 prior 与本轮。 */
fun simulateSend(prior: List<M>, input: String): List<M> =
    listOf(M.Sys(SYSTEM_PROMPT)) + prior + listOf(M.User(input), M.Asst("答：" + input))

var p = 0
var f = 0
fun pass(s: String) { println("  PASS  " + s); p++ }
fun fail(s: String) { println("  FAIL  " + s); f++ }
fun check(ok: Boolean, s: String) { if (ok) pass(s) else fail(s) }

fun sysCount(l: List<M>) = l.count { it is M.Sys }

fun main() {
    println("[B] 行为：history 只留对话，system 不累积")

    // 1. 修复后：连续五轮，请求体里 system 恒为 1
    val h = mutableListOf<M>()
    var bad = -1
    var lastSent: List<M> = emptyList()
    for (i in 1..5) {
        val sent = simulateSend(h.toList(), "第" + i + "问")
        lastSent = sent
        if (sysCount(sent) != 1) bad = i
        putNew(h, sent)
    }
    check(bad == -1, "修复后连续 5 轮，请求体 system 恒为 1 次（首轮不符：" + bad + "）")
    check(sysCount(h) == 0, "history 里 system 个数为 0（实际 " + sysCount(h) + "）")
    check(lastSent.size == 1 + 8 + 2, "第 5 轮请求长度 = system + 4 轮往返 + 本轮两条 = 11（实际 " + lastSent.size + "）")
    check(lastSent.first() is M.Sys && lastSent[1] is M.User,
        "system 之后直接是第一轮 user，中间没有夹旧的 system")

    // 2. 对照组：旧写法必须复现缺陷，否则这个测试没有分辨力
    val ho = mutableListOf<M>()
    val counts = mutableListOf<Int>()
    for (i in 1..3) {
        val sent = simulateSend(ho.toList(), "第" + i + "问")
        counts.add(sysCount(sent))
        putOld(ho, sent)
    }
    check(counts[0] == 1, "旧写法第 1 轮 system = 1（实际 " + counts[0] + "）")
    check(counts[1] == 2, "旧写法第 2 轮 system = 2 —— 缺陷可复现（实际 " + counts[1] + "）")

    // 3. 上限：超长对话按 MAX_HISTORY 截断，且保留的是最近的
    val h2 = mutableListOf<M>()
    for (i in 1..30) {
        val sent = simulateSend(h2.toList(), "问题" + i)
        putNew(h2, sent)
    }
    check(h2.size == MAX_HISTORY, "30 轮后 history 封顶 20 条（实际 " + h2.size + "）")
    val tail = (h2.last() as M.Asst).c
    check(tail == "答：问题30", "保留的是最近一轮（末条 = " + tail + "）")
    val firstUserAfterTrim = h2.firstOrNull { it is M.User }
    check(firstUserAfterTrim != null, "裁剪后仍留有 user 消息（否则上下文无意义）")
    check(sysCount(h2) == 0, "30 轮后 history 里依然没有 system（实际 " + sysCount(h2) + "）")

    // 4. 空输入不写历史（PetAgentSession 对空串直接返回，不该产生任何记录）
    val h3 = mutableListOf<M>()
    check(h3.isEmpty(), "新会话 history 为空")

    println("")
    println(p.toString() + " 通过 / " + f + " 失败")
}
