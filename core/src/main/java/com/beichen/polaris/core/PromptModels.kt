package com.beichen.polaris.core

import kotlinx.serialization.Serializable

/**
 * 功能五（P1-7）：一条提示词（快捷指令）。
 *
 * 一条提示词 = 一句可复用的话 + 一组标签，点选即填入输入框（追加语义，不覆盖已打的字）。
 * 结构取最小集 {id, title, content, tags}：字段再多，用户也不会填。
 *
 * @param builtin true = 内置提示词：不可编辑不可删除，只能「另存为自定义」，
 *   用户可「另存为自定义」后自由改。
 */
@Serializable
data class PromptItem(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val builtin: Boolean = false
)
