package com.beichen.polaris.ui

import com.beichen.polaris.core.ConfigStore
import com.beichen.polaris.core.ProviderManager
import com.beichen.polaris.core.llm.KeyPoolRuntime
import com.beichen.polaris.core.llm.KeyPoolStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 备用 Key 池域（W-21 / 3.1 第一次拆分，自 ChatViewModel 原地搬出）。
 *
 * 为什么能独立成块：Key 池只做「按档案存取 + 把备用 Key 灌进 [ProviderManager]」，
 * 不读会话、不碰工具表。它与生成链的唯一接触面就是 [providerManager] 的两个字段
 * （backupKeys / keyRuntime）——正好通过构造注入进来，接触面一眼可见。
 *
 * `keyPoolStore` 本体仍由 ChatViewModel 持有（BackupUseCase 导出备份时要读它），
 * 这里接收同一实例，两侧看到的是同一份数据。
 *
 * 字段与方法体逐行照搬：运行时冷却在内存、增删后即时刷新、摘要一行。
 */
internal class KeyPoolFacade(
    private val configStore: ConfigStore,
    private val keyPoolStore: KeyPoolStore,
    private val keyPoolRuntime: KeyPoolRuntime,
    private val providerManager: ProviderManager,
) {

    private val _backupKeys = MutableStateFlow<List<BackupKeyUi>>(emptyList())
    val backupKeys: StateFlow<List<BackupKeyUi>> = _backupKeys.asStateFlow()

    /** Key 池状态一行摘要（几把可用 / 几把冷却 / 最早多久恢复）。 */
    private val _keyPoolSummary = MutableStateFlow("")
    val keyPoolSummary: StateFlow<String> = _keyPoolSummary.asStateFlow()

    /** 把当前档案的备用 Key 灌进 ProviderManager（限流时自动切换）。 */
    fun refreshKeyPool() {
        val profileId = configStore.loadActiveId() ?: "default"
        val slots = keyPoolStore.list(profileId)
        providerManager.backupKeys = slots
        providerManager.keyRuntime = if (slots.isEmpty()) null else keyPoolRuntime
    }

    /** 重新读取备用 Key 并刷新 UI 状态（设置页增删后调用）。 */
    fun reloadBackupKeys() {
        val profileId = configStore.loadActiveId() ?: "default"
        val slots = keyPoolStore.list(profileId)
        _backupKeys.value = slots.map {
            BackupKeyUi(it.id, it.label, keyPoolStore.masked(it.key))
        }
        refreshKeyPool()
        _keyPoolSummary.value = if (slots.isEmpty()) {
            "未配备用 Key（仅主 Key 在跑）"
        } else {
            keyPoolRuntime.summary(slots)
        }
    }

    /** 新增一把备用 Key（空白/已存在则忽略）。 */
    fun addBackupKey(rawKey: String, label: String) {
        val key = rawKey.trim()
        if (key.isBlank()) return
        val profileId = configStore.loadActiveId() ?: "default"
        keyPoolStore.add(profileId, label, key)
        reloadBackupKeys()
    }

    fun removeBackupKey(id: String) {
        val profileId = configStore.loadActiveId() ?: "default"
        keyPoolStore.remove(profileId, id)
        keyPoolRuntime.resetAll()
        reloadBackupKeys()
    }
}
