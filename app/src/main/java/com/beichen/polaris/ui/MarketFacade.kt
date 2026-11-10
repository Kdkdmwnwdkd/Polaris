package com.beichen.polaris.ui

import android.app.Application
import com.beichen.polaris.core.AgentLogger
import com.beichen.polaris.core.StartupMark
import com.beichen.polaris.skill.ImportedSkillRepository
import com.beichen.polaris.skill.SkillDeclarations
import com.beichen.polaris.skill.market.MarketClient
import com.beichen.polaris.skill.market.MarketInstallPreview
import com.beichen.polaris.skill.market.MarketInstallState
import com.beichen.polaris.skill.market.MarketPlugin
import com.beichen.polaris.skill.market.MarketRules
import com.beichen.polaris.skill.market.MarketSource
import com.beichen.polaris.skill.market.MarketStore
import com.beichen.polaris.skill.market.MarketUiItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * W-48 插件市场域（3.1 第一次拆分，自 ChatViewModel 原地搬出）。
 *
 * 为什么能独立成块：市场只做「取包 + 校验 + 交给 SkillImportRepository」，
 * 它对 VM 的全部需求是三个注入点——读已装列表（[importRepo]）、装/卸后刷新
 * 技能快照（[reloadSkills] 回调）、协程作用域。市场不知道会话、工具表、模型的存在，
 * 反之亦然：生成链从不读 `_market*` 字段（对账见 run-appgraph 之外的域引用统计）。
 *
 * 跨域的唯一握手是 [reloadSkills]：装/卸插件必须刷新技能快照，否则技能中心
 * 显示的还是旧列表。用回调而不是把 VM 传进来，是为了让这个依赖**看得见**——
 * 这里就是市场与技能库的全部接触面。
 *
 * 字段与方法体逐行照搬：并发守卫（busyId / loading / refreshPending 补刷）、
 * Zip Slip 防护沿用 SkillImportRepository、id 一致性校验（W-48 安全边界）。
 */
internal class MarketFacade(
    private val app: Application,
    private val importRepo: ImportedSkillRepository,
    private val scope: CoroutineScope,
    private val reloadSkills: () -> Unit,
    startup: StartupMark,
) {

    /**
     * W-48 插件市场：源列表落 SharedPreferences，索引每次刷新现拉（不本地镜像）。
     *
     * 为什么不缓存索引：市场里「已下架/已修版本」变化频繁，缓存会让用户装到过期包；
     * 而索引本身很小（几十 KB），每次刷新重拉的成本可以忽略。
     */
    private val marketStore = MarketStore(app)
    private val marketClient = MarketClient()

    /** 已配置的市场源（含顺序即用户添加顺序）。 */
    private val _marketSources =
        MutableStateFlow(marketStore.loadSources().also { startup.mark("marketSources") })
    val marketSources: StateFlow<List<MarketSource>> = _marketSources.asStateFlow()

    /** 合并去重后的插件列表（含本机安装态）。 */
    private val _marketItems = MutableStateFlow<List<MarketUiItem>>(emptyList())
    val marketItems: StateFlow<List<MarketUiItem>> = _marketItems.asStateFlow()

    /** 正在刷新索引（按钮置灰 + 顶部进度提示的唯一判据）。 */
    private val _marketLoading = MutableStateFlow(false)
    val marketLoading: StateFlow<Boolean> = _marketLoading.asStateFlow()

    /** 正在安装/卸载的插件 id（同一时刻只允许一个，避免并发写同一目录）。 */
    private val _marketBusyId = MutableStateFlow<String?>(null)
    val marketBusyId: StateFlow<String?> = _marketBusyId.asStateFlow()

    /** 面向用户的一行状态（成功/失败/源为空），UI 原样展示。 */
    private val _marketStatus = MutableStateFlow<String?>(null)
    val marketStatus: StateFlow<String?> = _marketStatus.asStateFlow()

    /** 已下载、待用户确认的安装（非空即弹确认框）。 */
    private val _marketPending = MutableStateFlow<MarketInstallPreview?>(null)
    val marketPending: StateFlow<MarketInstallPreview?> = _marketPending.asStateFlow()

    /**
     * 待确认的包体（最大 8MB，与 `MarketRules.MAX_PACKAGE_BYTES` 同源）。
     *
     * 不放 [MarketInstallPreview] 里：data class 的 equals 对 ByteArray 是引用比较，
     * 放进去等于每次状态重放都白持有一份 8MB 引用，而且比较结果恒不等。
     */
    private var pendingBytes: ByteArray? = null

    /**
     * 刷新期间又被要求刷新（例如「删源」触发的重刷）。
     *
     * 不能简单丢弃：刷新开始时已快照源列表，中途删掉的源会继续出现在结果里，
     * 用户看到「源删了、插件还在」。标记后在 finally 补刷一次，保证最终态与源列表一致。
     */
    @Volatile
    private var marketRefreshPending = false

    /**
     * 刷新全部市场源。
     *
     * 并发语义：同一时刻只允许一次刷新（[_marketLoading] 守卫）。多个源**串行**拉取而不是并发，
     * 是为了让错误信息能按源顺序聚合、且不会在弱网下同时压满连接；索引都很小，串行代价可接受。
     *
     * 合并规则：同 id 以**先出现的源**为准（源顺序 = 用户添加顺序），避免后加的源悄悄覆盖。
     */
    fun refreshMarket() {
        if (_marketLoading.value) {
            marketRefreshPending = true
            return
        }
        scope.launch {
            _marketLoading.value = true
            try {
                val sources = _marketSources.value
                if (sources.isEmpty()) {
                    _marketItems.value = emptyList()
                    _marketStatus.value = "还没有市场源。添加一个索引 JSON 地址后即可浏览插件。"
                    return@launch
                }
                // 读已装技能要走文件 IO，不能占主线程
                val installed = withContext(Dispatchers.IO) { installedVersions() }
                val merged = LinkedHashMap<String, MarketPlugin>()
                val errors = ArrayList<String>()
                sources.forEach { src ->
                    marketClient.fetchIndex(src.url).fold(
                        onSuccess = { idx ->
                            idx.plugins.forEach { merged.putIfAbsent(it.id, it) }
                            AgentLogger.i("Market", "源「${idx.name.ifBlank { src.url }}」返回 ${idx.plugins.size} 个插件")
                        },
                        onFailure = { e ->
                            errors += "${src.url.take(48)} → ${e.message ?: "未知错误"}"
                        }
                    )
                }
                _marketItems.value = merged.values
                    .map { p ->
                        val local = installed[p.id]
                        MarketUiItem(p, local, MarketRules.stateOf(p.version, local))
                    }
                    // 可更新的排最前，其余按名字；用户进页面第一眼看到的就是「该动手的」
                    .sortedWith(
                        compareBy<MarketUiItem> { it.state != MarketInstallState.UPDATE_AVAILABLE }
                            .thenBy { it.plugin.name }
                    )
                _marketStatus.value = when {
                    errors.isEmpty() -> "共 ${merged.size} 个插件"
                    merged.isEmpty() -> "刷新失败：" + errors.take(2).joinToString("；")
                    else -> "部分源失败（${errors.size}）：${errors.first()}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AgentLogger.e("Market", "刷新市场异常", e)
                _marketStatus.value = "刷新异常：${e.message ?: "未知错误"}"
            } finally {
                _marketLoading.value = false
                // 补刷：期间可能有源被增删，旧快照的结果已过期（只补一次，不递归放大）
                if (marketRefreshPending) {
                    marketRefreshPending = false
                    refreshMarket()
                }
            }
        }
    }

    /** 只在本地重算安装态（装/卸后调用），不重新拉网络。内部会读技能目录，故切 IO。 */
    private suspend fun refreshMarketInstallState() {
        val installed = withContext(Dispatchers.IO) { installedVersions() }
        _marketItems.value = _marketItems.value
            .map { item ->
                val local = installed[item.plugin.id]
                item.copy(installedVersion = local, state = MarketRules.stateOf(item.plugin.version, local))
            }
            .sortedWith(
                compareBy<MarketUiItem> { it.state != MarketInstallState.UPDATE_AVAILABLE }
                    .thenBy { it.plugin.name }
            )
    }

    /** 已装技能 id → 版本（读失败退空表：市场仍可浏览，只是状态显示为「未安装」）。 */
    private fun installedVersions(): Map<String, String> =
        runCatching { importRepo.loadImported(app).associate { it.id to it.version } }
            .getOrDefault(emptyMap())

    /**
     * 添加市场源。
     *
     * @return true = 已添加并开始刷新；false = 地址非法或重复（原因写进 [marketStatus]）。
     *   返回值用于 UI 决定「输入框要不要清空」——失败还清空，用户得重打一遍。
     */
    fun addMarketSource(raw: String): Boolean {
        if (MarketRules.validHttpUrl(raw) == null) {
            _marketStatus.value = "地址无效：只支持 http/https，且必须带主机名"
            return false
        }
        if (!marketStore.addSource(raw)) {
            _marketStatus.value = "该地址已存在"
            return false
        }
        _marketSources.value = marketStore.loadSources()
        refreshMarket()
        return true
    }

    /** 删除市场源（同时把它的条目从当前列表里移除，不等下次刷新）。 */
    fun removeMarketSource(url: String) {
        if (!marketStore.removeSource(url)) return
        _marketSources.value = marketStore.loadSources()
        val remaining = _marketSources.value.map { it.url }.toSet()
        // 条目本身不记录来源，删源后统一重刷一遍最准确（源通常只有一两个，代价很小）
        // 立即清空列表：刷新是异步的，若失败旧条目会一直留着，看起来像「删了源还在」。
        _marketItems.value = emptyList()
        if (remaining.isEmpty()) {
            _marketStatus.value = "已移除全部市场源"
        } else {
            refreshMarket()
        }
    }

    /**
     * 安装/更新一个插件。
     *
     * 复用 `SkillImportRepository.installZip`：解压、Zip Slip 防护、id 白名单、失败回滚、
     * **重新导入即撤销旧授权** 全部沿用既有实现——市场只负责「取包 + 校验 + 交给它」。
     *
     * 本方法**只下载、只校验、不落盘**：校验通过后把包体与声明挂到 [marketPending]，
     * 由 UI 弹确认框；用户点「安装」才走 [confirmMarketInstall]。
     * 落盘发生在用户看过 `net_hosts` 之后——否则「事先授权」只是实现上的说法。
     */
    fun installMarketPlugin(id: String) {
        if (_marketBusyId.value != null) return
        // 已有待确认的包：先处理那一个，否则两个包的字节会互相覆盖
        if (_marketPending.value != null) return
        val item = _marketItems.value.firstOrNull { it.plugin.id == id } ?: return
        scope.launch {
            _marketBusyId.value = id
            _marketStatus.value = "正在下载「${item.plugin.name}」…"
            try {
                val bytes = marketClient.downloadPlugin(item.plugin).getOrThrow()
                // W-48 安全边界：市场条目 id 必须与包内 skill.json 的 id 一致。
                // 不校验的话，一个「挂着 A 的名、装着 B 的包」会覆盖已装的 B，市场还以为装的是 A。
                val manifest = withContext(Dispatchers.IO) { importRepo.peekManifest(bytes) }
                val declaredId = manifest?.id?.takeIf { it.isNotBlank() }
                if (manifest == null || declaredId != item.plugin.id) {
                    throw IllegalStateException(
                        "包内技能 id（${declaredId ?: "无法读取"}）与市场条目（${item.plugin.id}）不一致，已拒绝安装"
                    )
                }
                val declaration =
                    SkillDeclarations.of(manifest.net_hosts, manifest.entry, manifest.tools)
                pendingBytes = bytes
                _marketPending.value = MarketInstallPreview(
                    id = item.plugin.id,
                    name = item.plugin.name.ifBlank { manifest.name },
                    version = item.plugin.version,
                    declaration = declaration,
                    hostLine = SkillDeclarations.hostLine(declaration),
                    warnings = SkillDeclarations.warnings(declaration)
                )
                _marketStatus.value = "「${item.plugin.name}」已下载，确认声明后才会安装"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AgentLogger.e("Market", "下载 ${item.plugin.id} 失败", e)
                _marketStatus.value = "下载失败：${e.message ?: "未知错误"}"
            } finally {
                _marketBusyId.value = null
            }
        }
    }

    /**
     * 用户确认安装 [marketPending] 里那个包（此时才解压落盘）。
     *
     * 包体取自 [pendingBytes] 而不是重新下载：重新下载会引入一次 TOCTOU——
     * 用户确认的是「他看过声明的那一份」，再取一次可能已经不是同一份。
     */
    fun confirmMarketInstall() {
        val preview = _marketPending.value ?: return
        val bytes = pendingBytes
        if (bytes == null) {
            // 状态不一致（理论上不可达）：宁可让用户重来，也不要静默装一个没声明过的包
            cancelMarketInstall()
            return
        }
        if (_marketBusyId.value != null) return
        scope.launch {
            _marketBusyId.value = preview.id
            _marketStatus.value = "正在安装「${preview.name}」…"
            try {
                withContext(Dispatchers.IO) {
                    importRepo.installZip(bytes).getOrThrow()
                    // reloadSkills 内部会扫 filesDir 下的技能目录，必须留在 IO 线程
                    reloadSkills()
                }
                refreshMarketInstallState()
                _marketStatus.value =
                    "已安装「${preview.name}」${preview.version}" +
                    "（重新导入会撤销旧授权，请在技能中心重新授权）"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AgentLogger.e("Market", "安装 ${preview.id} 失败", e)
                _marketStatus.value = "安装失败：${e.message ?: "未知错误"}"
            } finally {
                // 必须 finally：onSuccess 里的 reloadSkills/重排也可能抛（例如磁盘满），
                // 漏掉复位会让 busy 永久卡住，整个市场页从此装不了任何东西。
                pendingBytes = null
                _marketPending.value = null
                _marketBusyId.value = null
            }
        }
    }

    /** 放弃待确认的安装（关框等同取消）：包体立即释放，不留到下一次点击。 */
    fun cancelMarketInstall() {
        val preview = _marketPending.value ?: return
        pendingBytes = null
        _marketPending.value = null
        _marketStatus.value = "已取消安装「${preview.name}」"
    }

    /** 卸载一个已装插件（只删本地目录，市场列表保留该条目）。 */
    fun uninstallMarketPlugin(id: String) {
        if (_marketBusyId.value != null) return
        val item = _marketItems.value.firstOrNull { it.plugin.id == id } ?: return
        scope.launch {
            _marketBusyId.value = id
            try {
                val ok = withContext(Dispatchers.IO) {
                    val removed = importRepo.uninstall(id)
                    if (removed) reloadSkills()
                    removed
                }
                if (ok) {
                    refreshMarketInstallState()
                    _marketStatus.value = "已卸载「${item.plugin.name}」"
                } else {
                    _marketStatus.value = "卸载失败：本地没有该技能目录"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AgentLogger.e("Market", "卸载 ${item.plugin.id} 失败", e)
                _marketStatus.value = "卸载失败：${e.message ?: "未知错误"}"
            } finally {
                _marketBusyId.value = null
            }
        }
    }
}
