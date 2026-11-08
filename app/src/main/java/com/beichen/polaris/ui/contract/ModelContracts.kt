package com.beichen.polaris.ui.contract

import android.net.Uri
import androidx.compose.runtime.Stable
import com.beichen.polaris.core.ModelProfile
import com.beichen.polaris.core.llm.local.DownloadedModel
import com.beichen.polaris.core.llm.local.GgufCatalogEntry
import com.beichen.polaris.core.llm.local.GpuMode
import com.beichen.polaris.skill.ModelConfig
import com.beichen.polaris.ui.TestResult
import kotlinx.coroutines.flow.StateFlow

/**
 * 模型配置页契约（R-14）。
 *
 * 这个页面原本一个参数吃下 43 个成员：档案 CRUD、连通性测试、远程模型列表、
 * 本地 GGUF 的下载/导入/卸载/采样参数，全挤在一起。它其实是**两个不相干的世界**：
 *
 *  - 远程 provider（`/v1/models` 拉列表、填 Key、ping 一下）；
 *  - 本地 llama（GGUF 文件、上下文长度、线程数、GPU 层数）。
 *
 * 两者的唯一交集是「当前生效的是哪一档」（`switchProfile` / `localActive`），
 * 所以在这里切成两个接口，页面同时接收——用户可以只给本地那半边写假实现，
 * 不需要伪造一套 OpenAI 的 URL。
 */
/**
 * 「当前在用哪套档案」的最小切片。
 *
 * 设置页只需要在顶部显示一行「当前模型」，却曾经为此拿到整个 24 成员接口；
 * 对话页顶栏同理。单拎出来正是为了让这类只读展示方拿不到删除/改名/测试的权限。
 */
@Stable
interface ModelProfileSummary {
    val profiles: StateFlow<List<ModelProfile>>
    val activeProfileId: StateFlow<String>
}

@Stable
interface ModelProfilesContract : ModelProfileSummary {
    val config: StateFlow<ModelConfig>
    val testing: StateFlow<Boolean>
    val testResult: StateFlow<TestResult?>
    val modelList: StateFlow<List<String>>
    val modelListLoading: StateFlow<Boolean>
    val modelListError: StateFlow<String?>
    val favoriteModels: StateFlow<List<String>>

    fun switchProfile(id: String)

    /** 新建档案，返回新 id。 */
    fun addProfile(name: String? = null): String
    fun deleteProfile(id: String)
    fun renameProfile(id: String, name: String)
    fun updateConfig(cfg: ModelConfig)

    /** 用**输入框里的原始值**试连（未保存也要能测）。 */
    fun testConnection(baseUrl: String, apiKey: String, model: String, providerId: String)
    fun clearTestResult()

    fun fetchModelList(baseUrl: String, apiKey: String, providerId: String)
    fun clearModelList()
    fun toggleFavoriteModel(name: String)
}

/** 本地 GGUF 模型：文件态、采样参数、下载/导入/卸载。 */
@Stable
interface LocalModelContract {
    val localModels: StateFlow<List<DownloadedModel>>
    val localActive: StateFlow<String>
    val localLoaded: StateFlow<Boolean>

    /** 下载中：文件名 + 进度（null 表示还没拿到总大小）。 */
    val localDownloading: StateFlow<Pair<String, Float?>?>
    val localNCtx: StateFlow<Int>
    val localThreads: StateFlow<Int>
    val localMaxTokens: StateFlow<Int>
    val localAgentMode: StateFlow<Boolean>
    val localGpuMode: StateFlow<GpuMode>
    val localSafeMode: StateFlow<Boolean>

    /**
     * 资源档位锁（W-RT2）：**原始字符串**，`"AUTO"` 或 `ResourceTier` 名。
     *
     * 不在这里暴露枚举：配置可能被写坏（手改 SharedPreferences / 老版本遗留），
     * 解析职责在 `ResourceTier.parse`，它认不出时返回 null（= 跟随自动）。
     * 接口层只负责搬运，避免「一个字符串在三个地方各自判定合法性」。
     */
    val localResourceTierLock: StateFlow<String>
    val localGpuSummary: StateFlow<String>

    /** 上次 GPU 崩溃的说明（空串 = 没崩过）。 */
    val localGpuCrashNote: StateFlow<String>

    fun refreshLocalModels()
    fun setActiveLocalModel(fileName: String)
    fun deleteLocalModel(fileName: String)
    fun downloadLocalModel(entry: GgufCatalogEntry)
    fun importLocalModel(uri: Uri)
    fun unloadLocalModel()
    fun setLocalNCtx(v: Int)
    fun setLocalThreads(v: Int)
    fun setLocalMaxTokens(v: Int)
    fun setLocalAgentMode(on: Boolean)
    fun setLocalGpuMode(mode: GpuMode)
    fun setLocalSafeMode(on: Boolean)

    /** 锁档（`"AUTO"` 或档位名）；下次加载模型生效。 */
    fun setLocalResourceTierLock(name: String)
}

/** 模型配置页全部能力：远程 provider + 本地 GGUF，两个子接口的合成。 */
@Stable
interface ModelConfigContract : ModelProfilesContract, LocalModelContract
