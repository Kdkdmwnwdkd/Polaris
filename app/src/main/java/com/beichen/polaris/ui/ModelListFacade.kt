package com.beichen.polaris.ui

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.ConfigSanitizer
import com.beichen.polaris.core.ConfigStore
import com.beichen.polaris.core.ModelListFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 「选择模型」弹窗的模型列表域（3.1 第一次拆分，自 ChatViewModel 原地搬出）。
 *
 * 为什么先搬这一块：它只依赖 [ConfigStore]（收藏落盘）与一个协程作用域，
 * 和生成链 / 工具表 / 会话状态零交集——是 188 个成员里耦合面最小的一块。
 * 搬完之后 ChatViewModel 保留 `override` 转发，契约（ModelProfilesContract
 * 的 modelList* / favoriteModels 一族）一行没动。
 *
 * 字段与方法体逐行照搬，行为不变的证据链由既有 harness 负责
 * （ProviderIdWiring / ModelSetup 等）。这里不改任何语义，包括并发守卫、
 * 取消传播与「旧 job 晚到不写脏状态」的 isActive 双闸。
 */
internal class ModelListFacade(
    private val configStore: ConfigStore,
    private val scope: CoroutineScope,
) {

    /** 「选择模型」弹窗的模型 id 列表；emptyList = 未拉取或拉到空。 */
    private val _modelList = MutableStateFlow<List<String>>(emptyList())
    val modelList: StateFlow<List<String>> = _modelList.asStateFlow()

    /**
     * 功能五（P1-6）：收藏的模型名（对标 RikkaHub `favoriteModels`）。
     *
     * 收藏区恒在列表最前，跨端点共用——目的是让「每天用的那两三个模型」
     * 不用每次在几十个列表项里翻。
     */
    private val _favoriteModels = MutableStateFlow(configStore.loadFavoriteModels())
    val favoriteModels: StateFlow<List<String>> = _favoriteModels.asStateFlow()

    /** 功能五（P1-6）：收藏 / 取消收藏（即时落盘，重启不丢）。 */
    fun toggleFavoriteModel(name: String) {
        val cur = _favoriteModels.value
        val next = if (name in cur) cur - name else cur + name
        _favoriteModels.value = next
        configStore.saveFavoriteModels(next)
    }

    /** 是否正在拉取模型列表：UI 显示 loading，并作并发守卫（拉取中忽略重复触发）。 */
    private val _modelListLoading = MutableStateFlow(false)
    val modelListLoading: StateFlow<Boolean> = _modelListLoading.asStateFlow()

    /** 拉取失败文案（已脱敏后截断）；null = 无错误。 */
    private val _modelListError = MutableStateFlow<String?>(null)
    val modelListError: StateFlow<String?> = _modelListError.asStateFlow()

    /** 拉取任务句柄：重试 / 关闭时先 cancel 旧 job，避免旧结果覆盖新结果。 */
    private var modelListJob: Job? = null

    /**
     * 拉取模型列表（模型配置页「选择模型」弹窗打开即调用）。
     * 与 testConnection 同模式：并发守卫 + Dispatchers.IO + 失败脱敏。
     *
     * @param baseUrl 输入框当前的 Base URL（原始值，fetcher 内部先过 ConfigSanitizer）。
     * @param apiKey 输入框当前的 API Key（原始值）。
     * @param providerId 当前选中的供应商（"openai" / "claude" / "gemini" / "local-llama"）。
     */
    fun fetchModelList(baseUrl: String, apiKey: String, providerId: String) {
        // 并发守卫：正在拉取时直接忽略重复点击（与 _testing 同一思路）
        if (_modelListLoading.value) return
        // 本地模型不做网络拉取：请在「本地模型」页签的状态卡中选择
        // （isLocal 时弹窗入口根本不显示，这里是双保险）
        if (providerId == "local-llama") {
            _modelListError.value = "本地模型请在下方状态卡中选择"
            return
        }
        modelListJob?.cancel()
        _modelListLoading.value = true
        _modelListError.value = null
        modelListJob = scope.launch {
            try {
                // 先过 ConfigSanitizer（与 testConnection 同模式；fetcher 内部还会再洗一遍做防御双保险）
                val base = ConfigSanitizer.normalizeBaseUrl(baseUrl)
                val key = ConfigSanitizer.cleanApiKey(apiKey)
                val models = withContext(Dispatchers.IO) {
                    ModelListFetcher().fetch(base, key, providerId)
                }
                // OkHttp 的 execute() 是阻塞调用，withContext(IO) 里的挂起不会因
                // 外层 job cancel 而中断：旧任务可能跑完阻塞调用后走到这里。
                // isActive 守卫保证旧 job 晚到时不写脏状态（旧配置的列表 / 错误都不覆盖新拉取）。
                if (!isActive) return@launch
                _modelList.value = models
            } catch (e: CancellationException) {
                // 取消不是错误：向上传播，绝不吞（否则协程树语义被破坏）
                throw e
            } catch (e: Throwable) {
                if (!isActive) return@launch
                // 异常文案离开 UI 前先脱敏再截断（防御：异常里可能夹带 url 中的 key 片段）
                _modelListError.value =
                    (e.message?.let { AgentLogger.sanitize(it) } ?: "未知错误").take(200)
            } finally {
                // 只有当期 job 能收 loading：旧 job 的 finally 晚到时 isActive=false，
                // 不会把新任务刚置的 _modelListLoading=true 误收成 false
                if (isActive) _modelListLoading.value = false
            }
        }
    }

    /** 清空模型列表三状态（弹窗关闭时调用：cancel 在途任务 + 三态归零，下次打开重新拉）。 */
    fun clearModelList() {
        modelListJob?.cancel()
        modelListJob = null
        _modelList.value = emptyList()
        _modelListLoading.value = false
        _modelListError.value = null
    }
}
