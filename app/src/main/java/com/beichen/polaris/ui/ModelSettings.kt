package com.beichen.polaris.ui

import android.content.Context
import android.net.Uri
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.llm.local.AndroidDeviceProbe
import com.beichen.polaris.core.llm.local.DownloadedModel
import com.beichen.polaris.core.llm.local.GgufCatalogEntry
import com.beichen.polaris.core.llm.local.GgufModelStore
import com.beichen.polaris.core.llm.local.GpuMode
import com.beichen.polaris.core.llm.local.LlamaJni
import com.beichen.polaris.core.llm.local.LocalDeviceRules
import com.beichen.polaris.core.llm.local.LocalLlamaEngine
import com.beichen.polaris.core.llm.local.LocalLlamaSetting
import com.beichen.polaris.core.llm.local.ResourceTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 本地模型这一摊子的**唯一所有者**：参数（ctx / 线程 / 上限 / 策略）、模型文件、
 * 下载进度、GPU 结论。
 *
 * ## 为什么从 ChatViewModel 搬出来
 *
 * 改动前这些成员散落在 ViewModel 里，和「会话」「生成」「技能市场」混在同一层，
 * 彼此只靠「都在同一个类里」产生联系。它们真正的耦合面只有三个外部动作：
 * ① 落盘（[LocalLlamaSetting]）② 读文件（[GgufModelStore]）③ 选中模型时把当前
 * 档案切到 local 供应商。前两个本来就有自己的类，第三个是 ViewModel 的职责——
 * 所以这里只留一个 [switchToLocalProvider] 回调，由宿主决定怎么改配置。
 *
 * ## 线程纪律
 *
 * 所有 StateFlow 都在主线程写（调用方都是 Compose 事件或 viewModelScope），
 * 只有下载回调来自 IO 线程——它写的是 `MutableStateFlow.value`，本身原子。
 */
internal class ModelSettings(
    context: Context,
    private val scope: CoroutineScope,
    /** 选中本地模型时把「当前生效档案」切到本地供应商（宿主实现，会落盘并重建客户端）。 */
    private val switchToLocalProvider: () -> Unit,
    /** 一次性提示文案的出口（下载完成 / 导入失败都走这里，复用输入区上方的提示条）。 */
    private val onStatus: (String) -> Unit,
) {

    /** 本地推理参数持久化。VM 里的日志与连接测试还要读它，故暴露为 internal。 */
    internal val setting = LocalLlamaSetting(context)

    /** GGUF 文件管理（files/models/）。 */
    internal val files = GgufModelStore(context)

    private val _models = MutableStateFlow(files.listDownloaded())
    val models: StateFlow<List<DownloadedModel>> = _models.asStateFlow()

    private val _active = MutableStateFlow(setting.activeModel)
    val active: StateFlow<String> = _active.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 下载/导入共用的进度通道：文件名 → 0f~1f（total 未知为 null，UI 显示不确定进度）。 */
    private val _downloading = MutableStateFlow<Pair<String, Float?>?>(null)
    val downloading: StateFlow<Pair<String, Float?>?> = _downloading.asStateFlow()

    private val _nCtx = MutableStateFlow(setting.nCtx)
    val nCtx: StateFlow<Int> = _nCtx.asStateFlow()

    private val _threads = MutableStateFlow(setting.nThreads)
    val threads: StateFlow<Int> = _threads.asStateFlow()

    private val _maxTokens = MutableStateFlow(setting.maxTokens)
    val maxTokens: StateFlow<Int> = _maxTokens.asStateFlow()

    private val _agentMode = MutableStateFlow(setting.agentMode)
    val agentMode: StateFlow<Boolean> = _agentMode.asStateFlow()

    private val _gpuMode = MutableStateFlow(setting.gpuMode)
    val gpuMode: StateFlow<GpuMode> = _gpuMode.asStateFlow()

    private val _safeMode = MutableStateFlow(setting.safeMode)
    val safeMode: StateFlow<Boolean> = _safeMode.asStateFlow()

    /**
     * 资源档位锁（W-RT2）："AUTO" 或 [ResourceTier] 名。
     *
     * 存原始字符串而不是枚举：配置被写坏时 [ResourceTier.parse] 返回 null，
     * 语义自然退回「跟随自动」，与 [GpuMode] 的兜底口径一致。
     */
    private val _resourceTierLock = MutableStateFlow(setting.resourceTierLock)
    val resourceTierLock: StateFlow<String> = _resourceTierLock.asStateFlow()

    private val _gpuCrashNote = MutableStateFlow(setting.lastGpuCrashNote)
    val gpuCrashNote: StateFlow<String> = _gpuCrashNote.asStateFlow()

    // 必须在 gpuSummary 之前初始化：combine 在属性构造时就会订阅它。
    private val _device = MutableStateFlow(AndroidDeviceProbe.cachedProfile)

    /**
     * 「这台机器上 GPU 到底怎么样」的一行结论，给设置页当副标题。
     *
     * 三源合成：包里有没有编 Vulkan、设备画像怎么说、用户选了什么模式。三者任一变了
     * 这句就变，所以它不是常量。
     */
    val gpuSummary: StateFlow<String> = combine(_gpuMode, _device) { mode, dev ->
        val compiled = LlamaJni.gpuBackendAvailable
        if (!compiled) return@combine "当前安装包未编入 Vulkan 后端，本地推理只用 CPU"
        val advice = dev?.let { LocalDeviceRules.advise(it) }
            ?: return@combine "设备信息还没采集完，暂时只用 CPU"
        val plan = LocalDeviceRules.resolveGpu(advice, compiled, mode)
        val gpu = advice.gpuModel ?: advice.gpu.label
        if (plan.usingGpu) {
            "已启用：$gpu（Vulkan ${advice.vulkanVersion ?: "?"}），模型层会卸载到显卡"
        } else {
            "$gpu · Vulkan ${advice.vulkanVersion ?: "?"}｜${plan.reason}"
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), "正在读取设备信息…")

    /** 页签 onResume / 下载完成后刷新清单与运行时状态。 */
    fun refresh() {
        _models.value = files.listDownloaded()
        _loaded.value = LocalLlamaEngine.isLoaded
        // 画像在启动时后台采一次，这里补读一次：用户可能进得太快，建 VM 时还是 null。
        _device.value = AndroidDeviceProbe.cachedProfile
        // 崩溃说明也可能被 Application 在本次启动写进去，重进页面时要对齐。
        _gpuCrashNote.value = setting.lastGpuCrashNote
        _safeMode.value = setting.safeMode
    }

    /**
     * 选用某个已下载模型（立即落盘；下次对话时引擎按参数加载/复用）。
     *
     * W-70：**选模型 = 要用它对话**，所以同时把当前档案的 provider 切成 local。
     * 真机反馈（2026-10-28）：用户在配置页把供应商标成群「本地模型（内置）」、
     * 测试连接显示「连接正常」，但那是编辑区的临时值——没点「保存」就没写进 config，
     * 发消息时照旧打到云端，本地引擎一次都没启动。
     *
     * 只改 providerId，**不清 baseUrl/apiKey**：清空会让用户切回云端时得重填 Key，
     * 且让「本地 ⇄ 云端」切换可逆。
     */
    fun setActive(fileName: String) {
        setting.activeModel = fileName
        _active.value = fileName
        switchToLocalProvider()
    }

    fun delete(fileName: String) {
        files.delete(fileName)
        _models.value = files.listDownloaded()
        if (_active.value == fileName) _active.value = setting.activeModel
    }

    fun unload() {
        LocalLlamaEngine.unload()
        _loaded.value = false
    }

    /** 下载内置目录中的模型（断点续传；重复调用复用已完成文件）。 */
    fun download(entry: GgufCatalogEntry) {
        if (_downloading.value != null) return
        scope.launch {
            _downloading.value = entry.fileName to null
            val outcome = runCatching {
                files.download(entry) { downloaded, total ->
                    _downloading.value = entry.fileName to
                        if (total > 0) downloaded.toFloat() / total else null
                }
            }
            _downloading.value = null
            _models.value = files.listDownloaded()
            onStatus(
                if (outcome.isFailure) "下载失败：${outcome.exceptionOrNull()?.message}"
                // 下完自动选用，省一次点击
                else {
                    setActive(entry.fileName)
                    "已下载并选用 ${entry.displayName}"
                }
            )
        }
    }

    /**
     * W-45：导入已有的 .gguf（SAF content:// URI）。
     *
     * 与下载共用同一条进度通道：UI 不需要区分「在下」还是「在拷」。导入成功后自动设为
     * 当前模型（与下载完一致）。
     */
    fun import(uri: Uri) {
        if (_downloading.value != null) return
        scope.launch {
            _downloading.value = "本地文件" to null
            // importFrom 内部已 runCatching 并返回 Result<String>，不要再包一层
            val outcome = files.importFrom(uri) { copied, total ->
                _downloading.value = "本地文件" to
                    if (total > 0) copied.toFloat() / total else null
            }
            _downloading.value = null
            _models.value = files.listDownloaded()
            onStatus(
                outcome.fold(
                    onSuccess = { name ->
                        setActive(name)
                        "已导入并选用 $name"
                    },
                    onFailure = { "导入失败：${it.message}" }
                )
            )
        }
    }

    fun setCtx(v: Int) {
        setting.nCtx = v
        _nCtx.value = setting.nCtx
    }

    fun setThreads(v: Int) {
        setting.nThreads = v
        _threads.value = setting.nThreads
        // W-95：绑核投票的结论是按「上一组加载参数」投出来的，线程数一变必须作废，
        // 否则下次加载会沿用旧结论，用户看到「我设了 6 线程，还是跑 4 个」。
        runCatching { LlamaJni.nativeResetAffinity() }
    }

    fun setMaxTokens(v: Int) {
        setting.maxTokens = v
        _maxTokens.value = setting.maxTokens
    }

    fun setAgentMode(on: Boolean) {
        setting.agentMode = on
        _agentMode.value = on
        AgentLogger.i(
            "LocalLlama",
            if (on) "本地切到 Agent 模式（会注入工具协议，prompt 变长、首字变慢）"
            else "本地切到轻聊档（只发系统提示前几行 + 最近两轮，首字最快）"
        )
    }

    /**
     * 开关安全模式（压上下文 + 压批）。
     *
     * 不在这里调 `LlamaJni.applySafeMode`：native 的 n_batch 只在建 context 时读一次，
     * 由 [com.beichen.polaris.core.llm.local.LocalLlamaClient.ensureModelLoaded] 在装载前
     * 统一推。这里只落盘 + 更新 UI，语义就是「下一条消息生效」。
     */
    fun setSafeMode(on: Boolean) {
        setting.safeMode = on
        _safeMode.value = on
        AgentLogger.i(
            "LocalLlama",
            if (on) "安全模式已开启：上下文压到 2048、批压到 128，下一条消息生效"
            else "安全模式已关闭：恢复设置里的上下文与批大小，下一条消息生效",
        )
    }

    /**
     * 锁定资源档位（"AUTO" 或 [ResourceTier] 名）。
     *
     * 只落盘 + 更新 UI：档位在装载模型时读一次，由 `ensureModelLoaded` 统一解析，
     * 语义是「下一条消息（重载）生效」，与安全模式、GPU 策略同一口径。
     */
    fun setResourceTierLock(name: String) {
        setting.resourceTierLock = name
        _resourceTierLock.value = name
        val label = ResourceTier.parse(name)?.label ?: "自动（跟机型走）"
        AgentLogger.i("LocalLlama", "资源档位改为「$label」，下次加载模型生效")
    }

    fun setGpuMode(mode: GpuMode) {
        setting.gpuMode = mode
        _gpuMode.value = mode
        // 用户已经看过这条提示并做了选择，不再重复打扰。
        if (_gpuCrashNote.value.isNotBlank()) {
            setting.lastGpuCrashNote = ""
            _gpuCrashNote.value = ""
        }
        AgentLogger.i("LocalLlama", "GPU 策略改为「${mode.label}」，下次加载模型生效")
    }
}
