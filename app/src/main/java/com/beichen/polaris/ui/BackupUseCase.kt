package com.beichen.polaris.ui

import android.app.Application
import android.net.Uri
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.BackupArchive
import com.beichen.polaris.core.BackupManifest
import com.beichen.polaris.core.BackupRedaction
import com.beichen.polaris.core.ConfigBackup
import com.beichen.polaris.core.ConfigStore
import com.beichen.polaris.core.ConversationRecord
import com.beichen.polaris.core.ConversationStore
import com.beichen.polaris.core.DraftStore
import com.beichen.polaris.memory.MemoryItem
import com.beichen.polaris.memory.MemoryStore
import com.beichen.polaris.core.PromptItem
import com.beichen.polaris.core.PromptStore
import com.beichen.polaris.core.llm.KeyPoolStore
import com.beichen.polaris.core.net.BoundedBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 备份/恢复的 UI 状态机（设置页「备份与恢复」消费）。
 *
 * [Done]/[Failed] 是一次性事件语义：UI 展示后调 [BackupUseCase.consume] 复位，
 * 避免旋转屏幕/重入页面时旧 toast 复读。
 */
sealed interface BackupUiState {
    data object Idle : BackupUiState

    /** @param exporting true=导出中 false=恢复中。 */
    data class Working(val exporting: Boolean) : BackupUiState
    data class Done(val message: String) : BackupUiState
    data class Failed(val reason: String) : BackupUiState
}

/**
 * 全局备份 / 恢复（ZIP：manifest + conversations + memories + prompts + config + key pool）。
 *
 * ## 为什么独立成类
 *
 * 它只做一件事：在「六个 store 的快照」与「用户选的 SAF 文件」之间搬字节，然后把
 * 结果报成四态。原先这三百行长在 ViewModel 里，和生成、会话、技能市场挤在一起，
 * 而它读写的每一个 store 都已经是独立对象——没有一处需要 ViewModel 的私有状态，
 * 只有一个「恢复完通知 UI 重载」的回调。
 *
 * ## 两条不可退让的约束
 *
 * ① **导出不含 GitHub PAT**（W-47）：备份件常被存到网盘/微信，明文 token 外流等于送出
 *   仓库读写权；owner/repo/workflow 不带凭据也能省掉重填。
 * ② **恢复是破坏性的**：调用方必须先二次确认；本类内部还有 [BackupArchive.read] 的
 *   信封校验，坏包在触碰任何生产数据之前就被拒。
 * ③ **导出不含 API Key**（审计 F6，2026-11-10）：与①同一个理由——包会被存到网盘、
 *   发微信。做法见 [BackupRedaction]：档案 Key、敏感自定义头、媒体 Key、备用 Key 池
 *   一律清空，恢复时按 id 沿用本机已填的值。代价是跨设备恢复要重填 Key，
 *   导出结果文案会明确告知这一点。
 */
internal class BackupUseCase(
    private val app: Application,
    private val scope: CoroutineScope,
    private val conversations: ConversationStore,
    private val memories: MemoryStore,
    private val prompts: PromptStore,
    private val config: ConfigStore,
    private val keyPool: KeyPoolStore,
    private val drafts: DraftStore,
    /** 恢复成功后的 UI 重载（会话列表 / 当前会话 / 记忆 / 档案流 / 提示词）。 */
    private val onRestored: (List<PromptItem>) -> Unit,
) {

    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** 与 store 落盘同配置：ignoreUnknownKeys 容忍跨版本字段增减（老备份件缺新成员）。 */
    private val json = Json { ignoreUnknownKeys = true }

    fun consume() {
        _state.value = BackupUiState.Idle
    }

    /**
     * 导出到用户选定的 SAF 位置。
     *
     * 组装逻辑在 [BackupArchive]（纯函数）；本类只负责取快照与写流。IO 全在
     * Dispatchers.IO，快照读取是内存操作（synchronized 内 map），不阻塞主线程。
     */
    fun exportTo(uri: Uri) {
        _state.value = BackupUiState.Working(exporting = true)
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val convs = conversations.snapshot()
                val mems = memories.snapshot()
                // hotfix28：提示词库快照（导出/恢复对称；内置项随 mergeWithBuiltins 补种）
                val promptItems = prompts.list()
                val gh = config.loadGithubConfig()
                val backup = ConfigBackup(
                    // 审计 F6：密钥不进备份包，与 W-47 的 PAT 同一个理由（见类注释①②）
                    profilesJson = BackupRedaction.redactProfiles(json.encodeToString(config.loadProfiles())),
                    activeProfileId = config.loadActiveId(),
                    favoriteModels = config.loadFavoriteModels(),
                    mediaKeys = BackupRedaction.redactMediaKeys(config.loadAllMediaKeys()),
                    githubOwner = gh?.owner,
                    githubRepo = gh?.repo,
                    // W-47：**不把 PAT 写进备份包**，理由见类注释①
                    githubPat = null,
                    githubWorkflow = gh?.workflow
                )
                val manifest = BackupManifest(
                    version = BackupArchive.VERSION,
                    createdAt = System.currentTimeMillis(),
                    conversationCount = convs.size,
                    memoryCount = mems.size,
                    hasConfig = true,
                    // hotfix28：提示词库挂挡（真机反馈漏备份项）
                    promptCount = promptItems.size,
                    hasPrompts = true
                )
                val bytes = BackupArchive.create(
                    manifest,
                    json.encodeToString(convs),
                    json.encodeToString(mems),
                    json.encodeToString(backup),
                    json.encodeToString(promptItems),
                    // W-26：备用 Key 池一并备份（槽位与名字保留，Key 脱敏）
                    BackupRedaction.redactKeyPool(keyPool.exportAll())
                )
                val out = app.contentResolver.openOutputStream(uri)
                    ?: error("无法写入所选位置（可能已被移除）")
                out.use { it.write(bytes) }
                "已导出 ${convs.size} 个会话、${mems.size} 条记忆、${promptItems.size} 条提示词" +
                    "（API Key 不随备份导出，恢复后需重新填写）"
            }
            _state.value = result.fold(
                onSuccess = { BackupUiState.Done(it) },
                onFailure = { BackupUiState.Failed(it.message ?: "导出失败") }
            )
        }
    }

    /**
     * 从备份文件恢复（**破坏性**：覆盖全部会话/记忆/模型配置）。
     *
     * 顺序：坏包拒绝 → store 重灌（内存+落盘）→ UI 状态重载 → Done。任一步抛异常 →
     * Failed，此时数据可能已部分重灌，但 store 自身是原子的（replaceAll 内 synchronized），
     * 不会留下半截 JSON。
     */
    fun restoreFrom(uri: Uri) {
        _state.value = BackupUiState.Working(exporting = false)
        scope.launch(Dispatchers.IO) {
            // 审计 F30：修前是 `openInputStream(uri)?.use { it.readBytes() }`——整份进堆，
            // 之后才交给 BackupArchive 解析。BackupArchive 内部的 64MB 上限做得很好，
            // 但它守的是「解压后」，而这里在它之前就已经把文件整个读完了。
            // 恢复备份时用户从文件管理器选错一个几百 MB 的视频，进程当场被打挂。
            // 改法：读的过程就限长，超限直接拒绝，压根不给 OOM 机会。
            val bytes = runCatching {
                app.contentResolver.openInputStream(uri)?.use {
                    BoundedBody.readBytes(it, BoundedBody.MAX_BULK_BYTES)
                }
            }.getOrNull() ?: run {
                _state.value = BackupUiState.Failed(
                    "无法读取所选文件，或文件超过 ${BoundedBody.MAX_BULK_BYTES / 1024 / 1024} MB（不是备份文件？）"
                )
                return@launch
            }
            when (val read = BackupArchive.read(bytes)) {
                is BackupArchive.ReadResult.Invalid ->
                    _state.value = BackupUiState.Failed(read.reason)

                is BackupArchive.ReadResult.Ok -> {
                    val result = runCatching {
                        val convs = json.decodeFromString<List<ConversationRecord>>(read.conversationsJson)
                        val mems = json.decodeFromString<List<MemoryItem>>(read.memoriesJson)
                        conversations.replaceAll(convs)
                        memories.restoreAll(mems)
                        // 功能五（P1-8）：草稿不随备份走——旧会话 id 与新数据对不上，
                        // 留着会让陌生草稿复活在恢复后的会话里，直接清
                        drafts.clear()
                        read.configJson?.let { cfgText ->
                            runCatching { json.decodeFromString<ConfigBackup>(cfgText) }
                                .getOrNull()?.let { config.restoreFrom(it) }
                        }
                        // W-26：老备份件没有 Key 池成员 → 保持现状。
                        // F6：新备份里 Key 是空的，按槽位 id 沿用本机已填的，避免恢复一次全丢。
                        read.keyPoolJson?.let { poolText ->
                            val merged = BackupRedaction.mergeKeyPool(poolText, keyPool.exportAll())
                            val n = keyPool.importAll(merged)
                            if (n > 0) AgentLogger.i("Backup", "已恢复 $n 个档案的备用 Key 池")
                        }
                        // hotfix28：老备份件没有提示词成员 → 跳过，不动现有提示词
                        read.promptsJson?.let { promptText ->
                            runCatching { json.decodeFromString<List<PromptItem>>(promptText) }
                                .getOrNull()?.let { prompts.restoreAll(it) }
                        }
                        // UI 重载（StateFlow.value 可跨线程设；loadConversation 内部同步）
                        onRestored(prompts.list())
                        "已恢复 ${convs.size} 个会话、${mems.size} 条记忆" +
                            if (read.manifest.hasPrompts) "、${read.manifest.promptCount} 条提示词" else ""
                    }
                    _state.value = result.fold(
                        onSuccess = { BackupUiState.Done(it) },
                        onFailure = { BackupUiState.Failed(it.message ?: "恢复失败") }
                    )
                }
            }
        }
    }
}
