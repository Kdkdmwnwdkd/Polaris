package com.beichen.polaris.ui.contract

import android.net.Uri
import androidx.compose.runtime.Stable
import com.beichen.polaris.ui.BackupKeyUi
import com.beichen.polaris.ui.BackupUiState
import com.beichen.polaris.ui.ToolGroupRow
import kotlinx.coroutines.flow.StateFlow

/**
 * 设置页契约（R-14）。
 *
 * 设置页原签名吃下 24 个成员，横跨四件互不相干的事：运行偏好、工具范围、
 * 备份恢复、模型档案（它只读档案列表做一处展示）。这里按这四件事切开，
 * 页面接收四个窄接口。
 *
 * 刻意**不**做「一个大接口全继承」：设置页的每个区块本来就是独立卡片，
 * 拆开之后「备份卡片」可以只拿到 [BackupContract] 写测试，
 * 不必伪造自动化开关和工具组。
 */
@Stable
interface AppSettingsContract {
    val automationEnabled: StateFlow<Boolean>
    val completionNotifyEnabled: StateFlow<Boolean>

    /** 计划模式：先出方案待批，再执行。 */
    val planMode: StateFlow<Boolean>

    /** 自动续跑：冷启动发现上次任务被系统中断时，替用户发一句「继续」。 */
    val autoResume: StateFlow<Boolean>

    /**
     * 审批档位（W-RT3）：`STRICT` / `STANDARD` / `AUTO`。
     *
     * 暴露字符串而不是枚举：档位由 `ApprovalTier.normalize` 归一化，认不出的值
     * 一律退回 `STANDARD`。接口层只搬运，判定在规则层——同一个字符串不该在
     * UI、VM、核心三处各判一次合法性。
     */
    val approvalTier: StateFlow<String>

    /** 后台任务用哪套模型档案；空串 = 跟随当前。 */
    val taskProfileId: StateFlow<String>

    /** Key 池一行摘要（几把可用 / 几把冷却 / 最早多久恢复）。 */
    val keyPoolSummary: StateFlow<String>

    fun setAutomationEnabled(v: Boolean)
    fun setCompletionNotifyEnabled(enabled: Boolean)
    fun setPlanMode(enabled: Boolean)

    /** 切换自动续跑。 */
    fun setAutoResume(enabled: Boolean)

    /** 切换审批档位；传非法值按 `STANDARD` 处理（归一化在规则层）。 */
    fun setApprovalTier(mode: String)
    fun setTaskProfile(id: String)
}

/** 工具范围：按组启停。行数据见 [ToolGroupRow]。 */
@Stable
interface ToolGroupContract {
    val toolGroupsUi: StateFlow<List<ToolGroupRow>>
    fun enableToolGroup(group: String)
    fun dropToolGroup(group: String)
    fun refreshToolGroups()
}

/** 备份 / 恢复：Key 列表、四态进度、导入导出。 */
@Stable
interface BackupContract {
    val backupKeys: StateFlow<List<BackupKeyUi>>
    val backupState: StateFlow<BackupUiState>
    fun addBackupKey(rawKey: String, label: String = "")
    fun removeBackupKey(id: String)
    fun reloadBackupKeys()
    fun exportBackupTo(uri: Uri)
    fun restoreBackupFrom(uri: Uri)

    /** 一次性事件展示后复位，避免重进页面复读 toast。 */
    fun consumeBackupState()
}

/**
 * 设置页全部能力。
 *
 * 含 [ModelProfileSummary] 而非 [ModelProfilesContract]：页面只在顶部展示
 * 「当前模型」一行，不该顺带拿到改档案、删档案、试连的权力。
 *
 * 背景只接 [ChatBackgroundUriRef] 而非 [ChatBackgroundRef]：页面读 uri 显示名字，
 * 不读 alpha（那是背景层合成用的）。多给一条 StateFlow 就多一条订阅，没必要。
 */
@Stable
interface SettingsScreenContract :
    AppSettingsContract, ToolGroupContract, BackupContract,
    ModelProfileSummary, ChatBackgroundUriRef
