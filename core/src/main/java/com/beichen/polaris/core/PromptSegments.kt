package com.beichen.polaris.core

/**
 * 系统提示的分段拼装。
 *
 * ## 为什么按「变化频率」切段
 * 端侧本地推理有一个特性：**系统提示的前缀只要逐字节不变，KV 缓存就能复用**；一旦中间
 * 插进一个时间戳或随机顺序的工具列表，整段前缀作废，下一次请求要从头 prefill。
 * 原先 `buildSystemPrompt` 是每轮把「角色 + 规则 + 工具提示 + 记忆 + 时间」拼成一整块，
 * 因此**每一轮都必然作废**——用户感觉到的「每次发消息都在等」，有相当一部分来自这里。
 *
 * 切成三段：
 * - [Stability.FROZEN]：身份、规则、工具调用格式。整个会话不变，排最前。
 * - [Stability.SESSION]：会话级设定（人格、模型配置、已激活的工具组）。只随工具面变。
 * - [Stability.TURN]：本轮计划、时间、记忆摘要、压缩摘要。**必须在最后**，
 *   因为它一定变，放前面会把后面全部拖下水。
 *
 * 另外一条硬规则：**段内顺序必须由数据决定，不能由 Map 遍历或哈希序决定**。同内容两次
 * 组装必须得到逐字节相同的串，否则缓存照样失效。
 *
 * 迁移说明：本文件原为 `core/kernel/Prompt.kt`。那个包整体是未进主链路的实验，但
 * 这几个类被 [com.beichen.polaris.core.agent.RealAgentLoop] 用着，所以搬出来而不是删掉。
 */
enum class Stability { FROZEN, SESSION, TURN }

/** 提示的一个段落。 */
data class Section(val key: String, val text: String, val stability: Stability)

/**
 * 组装结果。
 *
 * @param text 最终提示全文。
 * @param frozenChars 稳定前缀长度（前这么多个字符与会话前文无关）。
 * @param signature 稳定前缀的指纹；两次相同即可判定 KV 前缀可复用。
 * @param turnKeys 本轮参与拼装的 TURN 段 key（排查「为什么缓存没命中」用）。
 */
data class Prompt(
    val text: String,
    val frozenChars: Int,
    val signature: String,
    val turnKeys: List<String>
) {
    val chars: Int get() = text.length
}

object PromptAssembler {

    /**
     * 组装。空段（text 为空或纯空白）直接跳过——空段会留下两个换行，
     * 白白多几个 token，还可能让不同轮次的空白不一致。
     */
    fun assemble(sections: List<Section>): Prompt {
        val frozen = sections.filter { it.stability == Stability.FROZEN && it.text.isNotBlank() }
        val session = sections.filter { it.stability == Stability.SESSION && it.text.isNotBlank() }
        val turn = sections.filter { it.stability == Stability.TURN && it.text.isNotBlank() }

        val frozenText = join(frozen)
        val sessionText = join(session)
        val turnText = join(turn)

        val text = listOf(frozenText, sessionText, turnText)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

        return Prompt(
            text = text,
            frozenChars = frozenText.length,
            signature = fingerprint(frozenText + "\u0000" + sessionText),
            turnKeys = turn.map { it.key }
        )
    }

    private fun join(parts: List<Section>): String =
        parts.joinToString("\n\n") { it.text.trim() }

    /**
     * FNV-1a 64 位。不用 `String.hashCode`：那个值虽稳定，但只有 32 位，段多时碰撞概率
     * 不可忽略；碰撞会导致「本该失效的缓存被判定为命中」，比不用缓存更危险。
     */
    fun fingerprint(s: String): String {
        var h = -0x340d631b7bdddcdbL // FNV offset basis
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h.toULong().toString(16).padStart(16, '0')
    }

    /**
     * 对比两次组装，指出稳定前缀是否变化。
     *
     * 全量重建提示时调用它打一条日志，就能回答「这次为什么没吃到缓存」：
     * 是规则段被改了，还是只是 TURN 段在变（那就属于正常）。
     */
    fun prefixChanged(prev: Prompt?, next: Prompt): Boolean =
        prev == null || prev.signature != next.signature
}
