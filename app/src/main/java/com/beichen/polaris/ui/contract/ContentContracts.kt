package com.beichen.polaris.ui.contract

import android.net.Uri
import androidx.compose.runtime.Stable
import com.beichen.polaris.core.Bookmark
import com.beichen.polaris.core.PromptDeleteResult
import com.beichen.polaris.core.PromptItem
import com.beichen.polaris.core.PromptUpsertResult
import com.beichen.polaris.memory.MemoryItem
import com.beichen.polaris.skill.market.MarketInstallPreview
import com.beichen.polaris.skill.market.MarketSource
import com.beichen.polaris.skill.market.MarketUiItem
import com.beichen.polaris.ui.SkillUiInfo
import com.beichen.polaris.workflow.Workflow
import kotlinx.coroutines.flow.StateFlow

/**
 * 内容型页面的读写契约（R-14）。
 *
 * ## 为什么要有这一层
 *
 * 18 个屏幕原本都用 `vm: ChatViewModel` 作参数。`ChatViewModel` 有 173 个 public
 * 成员（会话、生成、模型、终端、技能、备份……），这意味着：
 *
 *  - 收藏页在语法上可以调 `send()`、可以 `deleteConversation()`，编译器不拦；
 *  - 任何屏幕都拖得动整个 ViewModel 的初始化链（Application + 十余个 store），
 *    想给它写一个只喂假数据的测试或预览，等于先把 App 装起来；
 *  - 改 ViewModel 内部的任何一块，18 个文件全部落在受影响面上。
 *
 * 这里按**领域**切窄接口，屏幕只声明自己真正需要的那一两个。接口成员集合是
 * 从各屏幕实际调用反推的（不多给），并有守卫脚本盯着：成员数超上限、或接口里
 * 出现没有任何屏幕使用的成员，都判红。
 *
 * ## 为什么标 `@Stable`
 *
 * 接口只暴露 `StateFlow` 与命令方法：前者引用恒定、由订阅方 `collectAsState`
 * 自行驱动重组；后者是普通函数。实现类（`ChatViewModel`）确实是长生命周期单例，
 * 所以「参数相等即跳过重组」的推断成立——这不是给编译器灌迷魂汤。
 */
@Stable
interface BookmarksContract {
    val bookmarks: StateFlow<List<Bookmark>>
    fun removeBookmark(id: String)
    fun clearBookmarks()
}

/** 运行日志页：只读流 + 三个命令，别的什么都拿不到。 */
@Stable
interface LogsContract {
    val logs: StateFlow<List<String>>
    fun refreshLogs()
    fun clearLogs()

    /** 导出为纯文本（分享用），不写盘。 */
    fun exportLogs(): String

    /** 当前日志文件绝对路径；未就绪时返回一句人话。 */
    fun logFilePath(): String
}

/** 记忆库：增删改查 + 检索 + 刷新。 */
@Stable
interface MemoryBankContract {
    val memories: StateFlow<List<MemoryItem>>
    fun addMemory(content: String, kind: String, tags: List<String>)
    fun updateMemory(id: String, content: String, salience: Int, tags: List<String>): Boolean
    fun forgetMemory(id: String)
    fun clearMemory()
    fun searchMemory(query: String)
    fun refreshMemories()
}

/**
 * 提示词库（管理页）：列表 + 保存/删除/复制为自定义。
 *
 * 「插到输入框」不在这里：那是输入栏闪电按钮的选择器做的事，消费方是对话页
 * （走 ChatContextBar.insertPromptIntoInput），与管理页无关。管理页的卡片点击是
 * 「编辑」，不是「填入」——旧 KDoc 把两者写混了，顺带纠正。
 */
@Stable
interface PromptLibraryContract {
    val prompts: StateFlow<List<PromptItem>>
    fun savePrompt(draft: PromptItem): PromptUpsertResult
    fun deletePrompt(id: String): PromptDeleteResult

    /** 把内置提示词复制成可编辑副本；返回新 id，失败为 null。 */
    fun duplicatePromptAsCustom(id: String): String?
}

/** 工作流：启停、删除、立即执行。 */
@Stable
interface WorkflowContract {
    val workflows: StateFlow<List<Workflow>>
    fun refreshWorkflows()
    fun runWorkflowNow(id: String)
    fun setWorkflowEnabled(id: String, enabled: Boolean)
    fun deleteWorkflow(id: String)
}

/** 技能库（已安装）：导入三条路径（示例 / URL / SAF）+ 授权与卸载。 */
@Stable
interface SkillLibraryContract {
    val skillInfos: StateFlow<List<SkillUiInfo>>
    val importStatus: StateFlow<String?>
    fun importDemo()
    fun importFromUrl(url: String)

    /** SAF 选择结果直接交给实现，读取与解包都在实现内部的后台线程完成。 */
    fun importFromUri(uri: Uri)
    fun setScriptAuthorization(id: String, allow: Boolean)
    fun uninstallSkill(id: String)
}

/** 技能市场（远程源）。 */
@Stable
interface SkillMarketContract {
    val marketSources: StateFlow<List<MarketSource>>
    val marketItems: StateFlow<List<MarketUiItem>>
    val marketLoading: StateFlow<Boolean>
    val marketBusyId: StateFlow<String?>
    val marketStatus: StateFlow<String?>

    /**
     * 已下载、等待用户确认安装的插件（非空即弹确认框）。
     *
     * 没有它，「安装」这个动作就发生在用户看清包里写了什么之前：索引里的 `permissions`
     * 是市场方的营销文案，真正的 `net_hosts` 只在 zip 的 skill.json 里。
     */
    val marketPending: StateFlow<MarketInstallPreview?>
    fun refreshMarket()
    fun addMarketSource(raw: String): Boolean
    fun removeMarketSource(url: String)
    fun installMarketPlugin(id: String)

    /** 用户确认安装 [marketPending] 里那个包（只有此时才解压落盘）。 */
    fun confirmMarketInstall()

    /** 放弃 [marketPending]（关框等同取消，不保留包体）。 */
    fun cancelMarketInstall()

    /** 卸载市场插件（含它注册的工具回卷）。 */
    fun uninstallMarketPlugin(id: String)
}
