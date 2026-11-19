package com.beichen.polaris.ui

import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.ConfigSanitizer
import com.beichen.polaris.core.LLMConfig
import com.beichen.polaris.core.ProviderManager
import com.beichen.polaris.core.llm.Diagnosis
import com.beichen.polaris.core.llm.FailureKind
import com.beichen.polaris.core.llm.RecoveryAction
import com.beichen.polaris.core.llm.diagnoseException
import com.beichen.polaris.core.llm.diagnoseHttpFailure
import com.beichen.polaris.skill.ModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 由当前保存的 Key 生成「len=35 head=sk-a tail=d38d」指纹，供 401 气泡与测试卡片共用。
 *
 * 提到 top-level 而不是留在宿主里：401 气泡（friendlyMessage）与测试卡片是两个消费点，
 * 指纹口径必须只有一处，否则同一次失败在两个位置会显示两种长度。
 */
internal fun keyFingerprint(apiKey: String): String {
    val insp = ConfigSanitizer.inspectKey(apiKey)
    return "len=${insp.length} head=${insp.head} tail=${insp.tail}"
}

/**
 * 「测试连接」域（3.1 第五批拆分，自 ChatViewModel 原地搬出）。
 *
 * 为什么能独立成块：它只做「拿配置页当前输入 → 发一次最小请求 → 把结论写成
 * [TestResult]」，产物是给卡片渲染的一条数据；会话、工具表、生成链一概不碰。
 * 唯一需要宿主提供的是**本地模型名**（本地模式下指纹位展示文件名）——用一个 lambda
 * 注入，比把整个 ModelSettings 拖进来更清楚。
 *
 * 三个状态（结果 / 进行中 / 清空）搬到这里之后，「改输入框就清结果」这条语义
 * 由宿主转发调用 [clear]，facade 不监听任何输入源。
 */
internal class ConnectionProbe(
    private val scope: CoroutineScope,
    private val providerManager: ProviderManager,
    /** 当前本地模型文件名（本地推理卡片上代替 Key 指纹）。 */
    private val activeLocalModel: () -> String,
) {

    private val _result = MutableStateFlow<TestResult?>(null)
    val result: StateFlow<TestResult?> = _result.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    fun clear() {
        _result.value = null
    }

    /**
     * 「测试连接」：用配置页当前输入框的值（先实时清洗）发起一次最小请求，
     * 把连通性结果写入 [_result] 供卡片渲染。网络 IO 全部走 Dispatchers.IO，不阻塞主线程。
     *
     * 最小请求体按供应商各自协议构造（Provider 实现内聚），
     * 既不污染对话历史，也几乎不产生 token 消耗。
     *
     * @param baseUrl 输入框当前的 Base URL（原始值）。
     * @param apiKey 输入框当前的 API Key（原始值）。
     * @param model 输入框当前的模型名（原始值）。
     * @param providerId 当前选中的供应商（"openai" / "claude" / "gemini"）。
     */
    fun test(baseUrl: String, apiKey: String, model: String, providerId: String) {
        if (_testing.value) return
        _testing.value = true
        _result.value = null
        scope.launch {
            val cfg = ConfigSanitizer.sanitized(baseUrl, apiKey, model, providerId)
            val endpoint = endpointFor(cfg)
            // 本地模式没有 Key：指纹位展示模型文件名，比 "len=0" 更有信息量
            val finger = if (cfg.providerId == "local-llama") {
                "模型文件=${activeLocalModel().ifBlank { "未选择" }}"
            } else {
                keyFingerprint(cfg.apiKey)
            }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    providerManager
                        .create(cfg.providerId, LLMConfig(cfg.baseUrl, cfg.apiKey, cfg.model))
                        .testPing()
                }
            }
            _result.value = when {
                outcome.isFailure -> {
                    val t = outcome.exceptionOrNull()!!
                    // 异常文案离开日志系统前先脱敏（防御：异常里可能夹带 url 中的 key 片段）
                    val msg = (t.message?.let { AgentLogger.sanitize(it) } ?: "未知错误").take(200)
                    // P1：本地模型加载失败不是网络问题，单独归因（沿用原有排查提示）
                    val isLocalModel = providerId == "local-llama"
                    val diagnosis = if (isLocalModel) {
                        Diagnosis(
                            FailureKind.MODEL,
                            "本地推理失败：确认模型文件已下载完整、设备为 arm64；" +
                                "加载日志可在「日志」页查看（标签 llama-jni）",
                            RecoveryAction.NONE
                        )
                    } else {
                        diagnoseException(t)
                    }
                    TestResult(
                        ok = false,
                        code = -1,
                        endpoint = endpoint,
                        fingerprint = finger,
                        bodyPreview = msg,
                        suggestion = diagnosis.suggestion,
                        kind = diagnosis.kind,
                        action = diagnosis.action
                    )
                }

                else -> {
                    val ping = outcome.getOrThrow()
                    if (ping.code in 200..299) {
                        TestResult(
                            ok = true,
                            code = ping.code,
                            endpoint = endpoint,
                            fingerprint = finger,
                            bodyPreview = ping.bodyPreview
                        )
                    } else {
                        // P1：按「谁的问题 + 下一步点哪」归因（401/402→Key，429→限流，
                        // 404/400(model)→模型名，4xx→端点，5xx→服务端），不再一律「客户端错误」。
                        val diagnosis = diagnoseHttpFailure(ping.code, ping.bodyPreview)
                        TestResult(
                            ok = false,
                            code = ping.code,
                            endpoint = endpoint,
                            fingerprint = finger,
                            bodyPreview = ping.bodyPreview,
                            suggestion = diagnosis.suggestion,
                            kind = diagnosis.kind,
                            action = diagnosis.action
                        )
                    }
                }
            }
            _testing.value = false
        }
    }

    /**
     * 按供应商拼「测试连接」卡片展示的端点——与各 Provider 实现实际请求路径一致
     * （openai: /chat/completions；claude: /v1/messages；gemini: /v1beta/models/{m}:generateContent；
     * local-llama: 本地文件，展示「本地推理 + 文件名」）。
     */
    private fun endpointFor(cfg: ModelConfig): String {
        if (cfg.providerId == "local-llama") {
            val name = activeLocalModel().ifBlank { "未选择模型" }
            return "本地推理 · llama.cpp · $name"
        }
        val base = ConfigSanitizer.normalizeBaseUrl(cfg.baseUrl).trimEnd('/')
        return when (cfg.providerId) {
            "claude" -> if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
            "gemini" -> "$base/v1beta/models/${ConfigSanitizer.cleanModel(cfg.model)}:generateContent"
            else -> "$base/chat/completions"
        }
    }
}
