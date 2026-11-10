package com.beichen.polaris.ui

import com.beichen.polaris.skill.SkillDeclaration

/*
 * 技能中心的展示数据。
 *
 * 从 `ChatViewModel.kt` 搬到这里：那两处是纯数据（不碰任何 VM 状态），而 VM 的行数
 * 是冻结的（`scripts/qa/arch-baseline.txt`）。本文件与 VM 同包，引用点一行都不用改。
 */

/** 单个技能向 UI 暴露的「工具-能力」明细。 */
data class SkillToolInfo(
    val name: String,
    val capability: String,
    val allowed: Boolean
)

/**
 * UI 用技能信息（含 L5 安全策略摘要）：技能中心卡片直接消费。
 *
 * [declaration] 是 manifest 的原话（`net_hosts` / `entry` / `tools`），
 * 由 `SkillDeclarations.of` 翻译成用户看得懂的形态。它和 [tools] 的区别：
 * [tools] 是**能力门放行结果**（这个技能实际能调什么），[declaration] 是**技能自己声明的范围**
 * （它说自己要做什么）。两者都要展示——只看前者，用户不知道它想干什么；
 * 只看后者，用户不知道它实际能干成什么。
 */
data class SkillUiInfo(
    val id: String,
    val name: String,
    val description: String,
    val trust: String,
    /** 是否来自「导入」来源（filesDir/skills/imported），决定能否授权/卸载。 */
    val isImported: Boolean,
    val isScript: Boolean,
    val allowScript: Boolean,
    val maxCapability: String,
    val tools: List<SkillToolInfo>,
    val declaration: SkillDeclaration
)
