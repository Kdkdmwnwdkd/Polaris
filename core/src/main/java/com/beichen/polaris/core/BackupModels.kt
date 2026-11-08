package com.beichen.polaris.core

import kotlinx.serialization.Serializable

/**
 * 功能五（P1-4b）：全局备份/恢复的数据契约。
 *
 * 备份件是一个 ZIP，内含四个 JSON 成员（见 [BackupArchive]）：
 *  - manifest.json      —— [BackupManifest]：自描述信封（版本/时间/条数），恢复前先验它
 *  - conversations.json —— ConversationStore 全量快照（含轮次变体与 token 用量）
 *  - memories.json      —— MemoryStore 全量快照
 *  - config.json        —— [ConfigBackup]：模型配置档案等（可缺省：老备份件没有）
 *  - prompts.json       —— PromptStore 全量快照（hotfix28 补入：提示词库当初漏挂挡，
 *    真机反馈「备份只备聊天记录」——用户自建的快捷指令也该跟着走）
 *
 * 设计取舍：
 *  - **恢复走公开 API**（ConfigStore.restoreFrom / ConversationStore.replaceAll /
 *    MemoryStore.restoreAll / PromptStore.restoreAll），不直接搬 raw prefs 文件——
 *    公开 API 自带净化与迁移，换 raw 文件会绕过 ConfigSanitizer 与 turns 迁移，
 *    脏数据直接复活；
 *  - manifest 必存且先行校验：版本不认识/条数异常时恢复直接拒绝，
 *    避免把半成品包灌进生产数据（「给了个坏包也不能毁了好数据」）；
 *  - **W-47 起 PAT 不再进 ZIP**：备份件常被存到网盘/微信，明文 token 外流等于送仓库权限；
 *    恢复时若备份不带 PAT，则沿用本机已存的 PAT（见 `ConfigStore.restoreFrom`）。
 *  - 模型 Key / 媒体 Key 仍随备份走（用户恢复配置的刚需）：这是**已知取舍**，与
 *    SharedPreferences 明文存储同等待遇；备份页应提示「此文件含密钥」。
 */
@Serializable
data class BackupManifest(
    /** 备份格式版本；[BackupArchive.VERSION] 不认识的大版本号 → 拒绝恢复。 */
    val version: Int,
    /** 备份创建时间（epoch millis，UI 展示用）。 */
    val createdAt: Long,
    /** 会话条数（与 conversations.json 实际条数一致性校验）。 */
    val conversationCount: Int,
    /** 记忆条数。 */
    val memoryCount: Int,
    /** 是否携带模型配置（false = 老备份件或用户无配置，恢复时不动现有配置）。 */
    val hasConfig: Boolean,
    /**
     * 提示词条数（hotfix28）。默认 0 = 老备份件没带提示词库，
     * 恢复时不动现有提示词（与 [hasConfig] 同款前向兼容纪律）。
     */
    val promptCount: Int = 0,
    /** 是否携带提示词库（hotfix28；false = 老备份件，恢复时跳过该成员）。 */
    val hasPrompts: Boolean = false
)

/**
 * 模型配置备份（对应 ConfigStore 的 SharedPreferences 语义键）。
 *
 * profilesJson 存**原始 JSON 文本**而非结构化 List<ModelProfile>：
 * 恢复路径与 ConfigStore.loadProfiles 共用同一份解码逻辑（含 ignoreUnknownKeys），
 * 备份件里多几个将来加的字段也不会让恢复失败；结构化拷贝反而要在两边同步演化。
 */
@Serializable
data class ConfigBackup(
    val profilesJson: String? = null,
    val activeProfileId: String? = null,
    val favoriteModels: List<String> = emptyList(),
    /** media_key_<providerId> → key（providerId 是开放集合，用 Map 承载）。 */
    val mediaKeys: Map<String, String> = emptyMap(),
    val githubOwner: String? = null,
    val githubRepo: String? = null,
    val githubPat: String? = null,
    val githubWorkflow: String? = null
)
