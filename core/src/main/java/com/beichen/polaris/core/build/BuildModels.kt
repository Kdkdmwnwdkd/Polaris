package com.beichen.polaris.core.build

/**
 * GitHub 构建配置（调研 07 号文档「BuildConfig.kt」直译）。
 *
 * 存 ConfigStore（与 LLM Key、媒体 Key 同库不同键）；PAT 明文存储的取舍与
 * 现有 Key 一致——App 私有目录 SharedPreferences，开源前统一迁移加密
 * （09 号文档合规清单已记一笔）。
 *
 * @param workflow workflow 文件名（仓库 .github/workflows/ 下，如 build.yml）。
 */
data class GitHubConfig(
    val owner: String,
    val repo: String,
    val pat: String,
    val workflow: String = "build.yml"
) {
    /** 仓库全名 "owner/repo"（API 路径与日志展示用）。 */
    val fullName: String get() = "$owner/$repo"
}
