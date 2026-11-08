package com.beichen.polaris.core

import android.content.Context
import android.content.SharedPreferences
import com.beichen.polaris.skill.ModelConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 模型配置持久化（SharedPreferences）。
 *
 * 解决的问题：原实现 ModelConfig 只存内存，App 进程被系统回收后全部丢失，
 * 退回「openai.com / 空 Key / gpt-4o-mini」默认值，用户「明明填过」的认知与
 * App 实际状态永久不一致，进而出现「在别处能用、在本 App 一直 401」的迷惑现象。
 * 这里把配置落盘，进程重启也能还原。
 *
 * 功能二（多模型配置，用户真机录屏反馈：「别人的模型可以搞好几个，你就只能搞一个」）：
 * 单配置进化为多档案——`profiles_json` 存 List<[ModelProfile]>，`active_profile_id`
 * 记当前生效档案。旧四键（base_url/api_key/model/provider_id）保留不删，并**镜像**
 * 当前生效配置：`loadMediaKey("openai")` 的媒体 Key 空值回退读的是旧 api_key 键
 * （媒体工坊用），镜像保证回退读到的是「当前生效 Key」，多配置切换后不会读到
 * 上一个配置的 Key。
 *
 * Key 存明文（App 私有目录 SharedPreferences，与 AutomationPrefs、Skill 授权做法一致）；
 * 不引入新的加密依赖——移动端本地私有存储已足够隔离，加密无法防御 root 设备，
 * 反而徒增复杂度。读取与写入都过一遍 [ConfigSanitizer]，防历史脏数据复活。
 */
class ConfigStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** profiles_json 解码用：容错将来加字段（老包写的新键多几个字段不崩）。 */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 读出「当前生效配置」（= active profile 的 config，或旧四键镜像值）。
     * 读出来的原始值再过一遍 [ConfigSanitizer]，
     * 防止历史脏数据（早期版本可能把带不可见字符的 Key 写进了盘）继续生效。
     *
     * 注意：多档案入口见 [loadProfiles] / [loadActiveId]；本方法保留给旧调用方与
     * 「镜像键」视角的读取（媒体 Key 回退链仍读旧四键），ChatViewModel 已不再使用。
     *
     * @return 已净化的配置；从未保存过时返回 [ModelConfig] 默认值。
     */
    fun load(): ModelConfig {
        val raw = ModelConfig(
            baseUrl = sp.getString(KEY_BASE_URL, ModelConfig().baseUrl) ?: ModelConfig().baseUrl,
            apiKey = sp.getString(KEY_API_KEY, ModelConfig().apiKey) ?: ModelConfig().apiKey,
            model = sp.getString(KEY_MODEL, ModelConfig().model) ?: ModelConfig().model,
            // 旧版本没有此键：默认 "openai"，老用户的配置无缝延续
            providerId = sp.getString(KEY_PROVIDER, "openai") ?: "openai"
        )
        return ConfigSanitizer.sanitized(raw)
    }

    /**
     * 保存配置（旧单配置入口，保留兼容）。
     * 多档案写入见 [saveProfiles]；本方法只动旧四键，不再被 ChatViewModel 调用。
     *
     * @param cfg 用户提交的原始配置（可能含不可见字符）。
     */
    fun save(cfg: ModelConfig) {
        val clean = ConfigSanitizer.sanitized(cfg)
        sp.edit().apply {
            putString(KEY_BASE_URL, clean.baseUrl)
            putString(KEY_API_KEY, clean.apiKey)
            putString(KEY_MODEL, clean.model)
            putString(KEY_PROVIDER, clean.providerId)
        }.apply()
    }

    /**
     * 读出全部配置档案；首次读取时执行一次性迁移（见 [migrateLegacyIfNeeded]）。
     * 读出来再过一遍 [ConfigSanitizer]（与 [load] 一致的双保险）。
     *
     * @return 档案列表；全新用户（未迁移、未保存过）返回空列表，由调用方兜底建默认配置。
     */
    fun loadProfiles(): List<ModelProfile> {
        migrateLegacyIfNeeded()
        val raw = sp.getString(KEY_PROFILES, null) ?: return emptyList()
        val parsed = runCatching {
            json.decodeFromString<List<ModelProfile>>(raw)
        }.getOrNull() ?: return emptyList()
        return parsed.map { it.copy(config = ConfigSanitizer.sanitized(it.config)) }
    }

    /** 当前生效档案 id；没有（全新用户）返回 null。 */
    fun loadActiveId(): String? {
        migrateLegacyIfNeeded()
        return sp.getString(KEY_ACTIVE_ID, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * 保存全部档案 + 当前生效 id，并同步镜像旧四键。
     *
     * 镜像键（重要）：把当前生效配置的 baseUrl/apiKey/model/providerId 写回旧四键——
     * `loadMediaKey("openai")` 在媒体 Key 为空时回退读旧 api_key 键（媒体工坊用），
     * 镜像保证回退读到的是「当前生效 Key」，多配置切换后不会读到上一个配置的 Key。
     *
     * @param list 全部档案（内部会先净化再落盘）。
     * @param activeId 当前生效档案 id；失效时回退第一个。
     */
    fun saveProfiles(list: List<ModelProfile>, activeId: String) {
        // 净化后再存：与旧 save() 一致，落盘即干净（load 还会再洗一遍）
        val cleaned = list.map { it.copy(config = ConfigSanitizer.sanitized(it.config)) }
        val active = cleaned.firstOrNull { it.id == activeId } ?: cleaned.firstOrNull()
        sp.edit().apply {
            putString(KEY_PROFILES, json.encodeToString(cleaned))
            putString(KEY_ACTIVE_ID, active?.id ?: "")
            // 镜像：旧四键 = 当前生效配置（媒体 Key 回退链不断，见方法注释）
            putString(KEY_BASE_URL, active?.config?.baseUrl ?: "")
            putString(KEY_API_KEY, active?.config?.apiKey ?: "")
            putString(KEY_MODEL, active?.config?.model ?: "")
            putString(KEY_PROVIDER, active?.config?.providerId ?: "")
        }.apply()
    }

    /**
     * 一次性迁移（重要，老用户 Key 不能丢）：`profiles_json` 不存在、但旧四键有值时，
     * 用旧值包成第一个 profile（id 用 UUID，name 按 providerId 给中文名）并立即落盘
     * `profiles_json` + `active_profile_id`。旧四键保留不删（媒体 Key 回退等历史逻辑还读它们）。
     *
     * 幂等：`profiles_json` 一存在就直接返回。
     */
    private fun migrateLegacyIfNeeded() {
        if (sp.contains(KEY_PROFILES)) return
        val baseUrl = sp.getString(KEY_BASE_URL, null)
        val apiKey = sp.getString(KEY_API_KEY, null)
        val model = sp.getString(KEY_MODEL, null)
        val provider = sp.getString(KEY_PROVIDER, null)
        // 四键全无 = 全新用户，没有可迁移的数据（VM 会兜底建默认配置）
        if (baseUrl == null && apiKey == null && model == null && provider == null) return
        val cfg = ConfigSanitizer.sanitized(
            ModelConfig(
                baseUrl = baseUrl ?: ModelConfig().baseUrl,
                apiKey = apiKey ?: ModelConfig().apiKey,
                model = model ?: ModelConfig().model,
                // 旧版本没有此键：默认 "openai"，老用户的配置无缝延续
                providerId = provider ?: "openai"
            )
        )
        val profile = ModelProfile(
            id = UUID.randomUUID().toString(),
            name = defaultNameFor(cfg.providerId),
            config = cfg
        )
        // 立即落盘：迁移结果持久化，下次启动不再迁移
        // （镜像键同步发生——旧四键被写成净化后的同一份值，语义不变）
        saveProfiles(listOf(profile), profile.id)
    }

    /** 迁移时按 providerId 给中文名（老用户的第一个 profile 要一眼认出是哪家）。 */
    private fun defaultNameFor(providerId: String): String = when (providerId) {
        "openai" -> "OpenAI"
        "claude" -> "Claude"
        "gemini" -> "Gemini"
        else -> "默认配置"
    }

    /**
     * 功能五（P1-6）：收藏的模型名（对标 RikkaHub `favoriteModels`）。
     *
     * 存**模型名**而非「端点 + 模型名」：模型名在绝大多数情况下全局唯一
     * （gpt-4o / deepseek-chat / claude-sonnet-4-5），跨端点共用一份收藏夹
     * 更省心；同名的极端情况最多多收藏一条，无正确性风险。
     *
     * 用逗号分隔存单键：模型名不含逗号（OpenAI 兼容端点命名惯例），
     * 不值得为它引入一份 JSON。
     */
    fun loadFavoriteModels(): List<String> =
        sp.getString(KEY_FAVORITE_MODELS, "").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /** 功能五（P1-6）：写回收藏模型（顺序无关，UI 侧按列表原序展示）。 */
    fun saveFavoriteModels(list: List<String>) {
        sp.edit().putString(KEY_FAVORITE_MODELS, list.joinToString(",")).apply()
    }

    /**
     * 保存媒体 Provider Key（生图/视频工坊用，与 LLM Key 同库不同键——调研 06 号文档
     * 明确「不引入统一密钥库」，开源前不做加密，与 LLM Key 同等待遇）。
     *
     * @param providerId media Provider id（dashscope / openai / volc / minimax）。
     */
    fun saveMediaKey(providerId: String, key: String) {
        sp.edit().putString(KEY_MEDIA_KEY_PREFIX + providerId, key.trim()).apply()
    }

    /**
     * 读媒体 Provider Key。
     *
     * OpenAI 特例：媒体 Key 为空时回退到 LLM Key——images API 与 chat API
     * 同一账号同一把钥匙，避免用户「明明配过模型还要再填一遍」的割裂感。
     * 多配置时代（功能二）回退读到的是旧 api_key 键的镜像值 = 当前生效 Key
     * （镜像逻辑见 [saveProfiles]），切换配置后回退不会读到上一个配置的 Key。
     */
    fun loadMediaKey(providerId: String): String {
        val own = sp.getString(KEY_MEDIA_KEY_PREFIX + providerId, "").orEmpty()
        if (own.isNotBlank()) return own
        if (providerId == "openai") {
            return sp.getString(KEY_API_KEY, "").orEmpty()
        }
        return ""
    }

    /**
     * 保存 GitHub 构建配置（第 9 步「构建」按钮用，与 LLM/媒体 Key 同库不同键）。
     * PAT 明文存储的取舍见 [ConfigStore] 类注释：App 私有目录 + 开源前迁移加密。
     */
    fun saveGithubConfig(cfg: com.beichen.polaris.core.build.GitHubConfig) {
        sp.edit().apply {
            putString(KEY_GH_OWNER, cfg.owner.trim())
            putString(KEY_GH_REPO, cfg.repo.trim())
            putString(KEY_GH_PAT, cfg.pat.trim())
            putString(KEY_GH_WORKFLOW, cfg.workflow.ifBlank { "build.yml" })
        }.apply()
    }

    /** 读 GitHub 构建配置；从未保存过返回 null（UI 据此走首次引导）。 */
    fun loadGithubConfig(): com.beichen.polaris.core.build.GitHubConfig? {
        val owner = sp.getString(KEY_GH_OWNER, "").orEmpty()
        val repo = sp.getString(KEY_GH_REPO, "").orEmpty()
        val pat = sp.getString(KEY_GH_PAT, "").orEmpty()
        if (owner.isBlank() || repo.isBlank() || pat.isBlank()) return null
        return com.beichen.polaris.core.build.GitHubConfig(
            owner = owner,
            repo = repo,
            pat = pat,
            workflow = sp.getString(KEY_GH_WORKFLOW, "build.yml") ?: "build.yml"
        )
    }

    /**
     * 功能五（P1-4b）：读全部媒体 Provider Key（`media_key_<id>` 前缀扫盘）。
     * providerId 是开放集合（dashscope/openai/volc/minimax…），无法枚举，
     * 只能按前缀扫 [SharedPreferences.all]；只读不写，无净化需求（写入侧已 trim）。
     */
    fun loadAllMediaKeys(): Map<String, String> {
        val prefix = KEY_MEDIA_KEY_PREFIX
        return sp.all.filterKeys { it.startsWith(prefix) }
            .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(prefix) to it } }
            .toMap()
    }

    /**
     * 功能五（P1-4b）：从备份恢复模型配置（**经公开 API 写回，不搬 raw prefs 文件**）。
     *
     * 为什么走公开 API：saveProfiles 自带旧四键镜像与档案校验，restore 若直接
     * putAll raw prefs 会绕过这些不变量（比如 profiles_json 与 api_key 镜像不一致），
     * 恢复完反而出现「切换档案不生效」的幽灵 bug。
     *
     * @param backup 备份件里的配置快照；profilesJson 为空时跳过档案恢复
     *   （老备份件无此成员时保留现有配置，不做破坏性清空）。
     */
    fun restoreFrom(backup: ConfigBackup) {
        val profilesText = backup.profilesJson
        if (!profilesText.isNullOrBlank()) {
            val profiles = runCatching {
                json.decodeFromString<List<ModelProfile>>(profilesText)
            }.getOrNull()
            if (profiles != null) {
                // activeId 缺失/失效时 saveProfiles 自动回退第一个档案（见其 KDoc）
                saveProfiles(profiles, backup.activeProfileId ?: profiles.firstOrNull()?.id.orEmpty())
            }
        }
        saveFavoriteModels(backup.favoriteModels)
        backup.mediaKeys.forEach { (providerId, key) ->
            sp.edit().putString(KEY_MEDIA_KEY_PREFIX + providerId, key.trim()).apply()
        }
        val ghOwner = backup.githubOwner
        val ghRepo = backup.githubRepo
        // W-47：备份件自本版起不再携带 PAT（明文 token 存网盘等于送仓库权限）。
        // 恢复策略分两路：老备份带了 PAT → 照旧覆盖；新备份 pat 为空 → **沿用本机已有 PAT**，
        // 只更新 owner/repo/workflow。本机也没有 PAT 时整段跳过（不写半截配置）。
        val ghPat = backup.githubPat?.takeIf { it.isNotBlank() }
            ?: loadGithubConfig()?.pat?.takeIf { it.isNotBlank() }
        if (!ghOwner.isNullOrBlank() && !ghRepo.isNullOrBlank() && !ghPat.isNullOrBlank()) {
            saveGithubConfig(
                com.beichen.polaris.core.build.GitHubConfig(
                    owner = ghOwner,
                    repo = ghRepo,
                    pat = ghPat,
                    workflow = backup.githubWorkflow?.ifBlank { "build.yml" } ?: "build.yml"
                )
            )
        }
    }

    companion object {
        /** 与现有 SharedPreferences 命名风格保持一致（agentkit_ 前缀）。 */
        private const val PREFS_NAME = "agentkit_model_config"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_PROVIDER = "provider_id"
        /** 功能二：多配置档案 JSON（List<ModelProfile>）。 */
        private const val KEY_PROFILES = "profiles_json"
        /** 功能二：当前生效档案 id。 */
        private const val KEY_ACTIVE_ID = "active_profile_id"
        /** 功能五（P1-6）：收藏模型名（逗号分隔）。 */
        private const val KEY_FAVORITE_MODELS = "favorite_models"
        private const val KEY_MEDIA_KEY_PREFIX = "media_key_"
        private const val KEY_GH_OWNER = "gh_owner"
        private const val KEY_GH_REPO = "gh_repo"
        private const val KEY_GH_PAT = "gh_pat"
        private const val KEY_GH_WORKFLOW = "gh_workflow"
        /** 功能五（P1-10）：回答完成提醒开关（默认关——通知是侵入式能力，opt-in）。 */
        private const val KEY_COMPLETION_NOTIFY = "completion_notify"
        private const val KEY_PLAN_MODE = "plan_mode"
        private const val KEY_TASK_PROFILE = "task_profile_id"
        // hotfix32：聊天背景图（URI + 不透明度）
        private const val KEY_CHAT_BG_URI = "chat_bg_uri"
        private const val KEY_CHAT_BG_ALPHA = "chat_bg_alpha"
    }

    /** 功能五（P1-10）：回答完成提醒是否开启（默认 false）。 */
    fun isCompletionNotifyEnabled(): Boolean =
        sp.getBoolean(KEY_COMPLETION_NOTIFY, false)

    /** 功能五（P1-10）：写回答完成提醒开关（设置页开关即时生效，落盘供下次启动读取）。 */
    fun setCompletionNotifyEnabled(enabled: Boolean) {
        sp.edit().putBoolean(KEY_COMPLETION_NOTIFY, enabled).apply()
    }

    /** W-25：任务模式——true = 先计划后执行（PLAN），false = 自动执行（AUTO，默认）。 */
    fun isPlanModeEnabled(): Boolean = sp.getBoolean(KEY_PLAN_MODE, false)

    /** W-25：写任务模式（设置页开关即时生效，落盘供下次启动读取）。 */
    fun setPlanModeEnabled(enabled: Boolean) {
        sp.edit().putBoolean(KEY_PLAN_MODE, enabled).apply()
    }

    /**
     * W-26：任务模型（标题生成 / 记忆抽取等轻量任务使用的档案 id）。
     * 空串 = 跟随当前主配置（默认）。
     */
    fun taskProfileId(): String = sp.getString(KEY_TASK_PROFILE, "").orEmpty()

    fun setTaskProfileId(id: String) {
        sp.edit().putString(KEY_TASK_PROFILE, id.trim()).apply()
    }

    /**
     * hotfix32：聊天背景图 URI（SAF content://，持久化读授权后的字符串；null = 无背景）。
     *
     * 真机反馈原话：「我不是说我可以用我照片的壁纸当成壁纸」——动态取色只染控件色，
     * 用户要的是**自己的照片当对话页背景**。落 SharedPreferences：URI 是轻量字符串，
     * 位图本体不拷贝（随时可从 content:// 重新解码），换机/清缓存即失效，符合预期。
     */
    fun chatBackgroundUri(): String? =
        sp.getString(KEY_CHAT_BG_URI, null)?.takeIf { it.isNotBlank() }

    /** hotfix32：写/清背景图 URI（null = 清除）。 */
    fun setChatBackgroundUri(uri: String?) {
        sp.edit().apply {
            if (uri.isNullOrBlank()) remove(KEY_CHAT_BG_URI)
            else putString(KEY_CHAT_BG_URI, uri)
        }.apply()
    }

    /**
     * hotfix32/33：背景图不透明度（0.05..1.00，默认 0.30）。
     * 下限 0.05 防「设了等于没设」的困惑；**上限 1.00 = 原图全浓度**
     * （真机反馈「60% 都不够清楚，要 100%」——上限是用户的选择，不是产品的规定）。
     * 文字可读性由全局 scrim（surface 色 25%）兜底，不靠压浓度。
     */
    fun chatBackgroundAlpha(): Float = sp.getFloat(KEY_CHAT_BG_ALPHA, 0.30f)

    /** hotfix32/33：写背景图不透明度（clamp 后落盘）。 */
    fun setChatBackgroundAlpha(alpha: Float) {
        sp.edit().putFloat(KEY_CHAT_BG_ALPHA, alpha.coerceIn(0.05f, 1.0f)).apply()
    }
}

/**
 * 一个模型配置档案（功能二：多模型配置——用户真机录屏反馈：
 * 「别人的模型可以搞好几个，你就只能搞一个」）。
 *
 * 放在 ConfigStore 同文件：档案是存储形态的一部分，与迁移/镜像逻辑同进退。
 *
 * @param id 稳定标识（UUID），切换/重命名/删除都认它不认下标。
 * @param name 用户可见名称（「OpenAI」「配置 2」…，可重命名）。
 * @param config 该档案的完整模型配置（baseUrl/apiKey/model/providerId）。
 */
@Serializable
data class ModelProfile(
    val id: String,
    val name: String,
    val config: ModelConfig
)
