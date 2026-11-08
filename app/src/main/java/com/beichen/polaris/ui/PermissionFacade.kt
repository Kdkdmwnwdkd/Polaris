package com.beichen.polaris.ui

import android.app.AppOpsManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.beichen.polaris.automation.AgentNotificationListener
import com.beichen.polaris.automation.DeviceContext
import com.beichen.polaris.automation.DeviceContextStore
import com.beichen.polaris.automation.shizuku.ShizukuInstaller
import com.beichen.polaris.automation.shizuku.ShizukuShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 权限 / 调试通道域（3.1 第四批拆分，自 ChatViewModel 原地搬出）。
 *
 * 为什么能独立成块：这一域的全部内容都是「探测一次系统状态 → 写进一条 StateFlow」。
 * 通知监听、使用情况访问、电池白名单、Shizuku 三态、设备上下文快照，没有任何一个
 * 参与生成链的决策——它们只在权限页 / 控制台 / 就绪卡片被**读**，写点全在这里。
 *
 * 与宿主的唯一接触面是 [onGrantsChanged]：权限或通道状态变化后，能力就绪度需要重算
 * （`refreshCapabilityPlans` 的逻辑在宿主，facade 不反向依赖 ViewModel，也不持有
 * `TerminalBackendFactory`——那是 shell 能力快照的事，属于就绪度域）。
 *
 * 字段与方法体逐行照搬，包括注释里记录的历史坑：
 *  - 字段初值必须是静态值，真值由 [refreshGrants] 在 IO 线程填（字段区在主线程首帧之前跑）；
 *  - `handleShizukuAction` 按四态分派，已装就拉起、未装才去商店。
 */
internal class PermissionFacade(
    private val app: Application,
    private val scope: CoroutineScope,
    private val onGrantsChanged: () -> Unit = {},
) {

    /** 通知访问权限是否已授予（供感知层卡片展示）。 */
    private val _notificationGranted = MutableStateFlow(false)
    val notificationGranted: StateFlow<Boolean> = _notificationGranted.asStateFlow()

    /** 使用情况访问权限是否已授予（供感知层卡片展示）。 */
    private val _usageGranted = MutableStateFlow(false)
    val usageGranted: StateFlow<Boolean> = _usageGranted.asStateFlow()

    /**
     * 是否已在电池优化白名单（P0 权限引导，2026-10-21）。
     *
     * 只影响「长任务会不会被系统掐断」，不影响任何工具能不能用——所以它不在
     * `pendingGrantCount` 的计数里，只有引导页单独展示。
     */
    private val _batteryUnrestricted = MutableStateFlow(false)
    val batteryUnrestricted: StateFlow<Boolean> = _batteryUnrestricted.asStateFlow()

    /**
     * 调试通道（Shizuku）状态（W-13 B）。初值静态，真值由 [refreshGrants]（IO）填：
     * 原先调 `status()` = `binderAlive()`+`hasPermission()` 两次 binder IPC，主线程字段区里纯属白付。
     */
    private val _shizukuStatus = MutableStateFlow(ShizukuShell.Status.NOT_RUNNING)
    val shizukuStatus: StateFlow<ShizukuShell.Status> = _shizukuStatus.asStateFlow()

    /** Shizuku 是否已安装（与「是否在运行」分开：已装就该直接拉起，不再劝人去商店）。初值同理由 [refreshGrants] 填。 */
    private val _shizukuInstalled = MutableStateFlow(false)
    val shizukuInstalled: StateFlow<Boolean> = _shizukuInstalled.asStateFlow()

    /** 界面四态（未安装 / 已装未启动 / 未授权 / 就绪）——文案与按钮动作都由它决定。 */
    private val _shizukuUiState = MutableStateFlow(
        ShizukuShell.uiState(_shizukuStatus.value, _shizukuInstalled.value)
    )
    val shizukuUiState: StateFlow<ShizukuShell.UiState> = _shizukuUiState.asStateFlow()

    /** 当前设备上下文快照（L4 主动感知）：供自动化页展示 Agent 实时感知到的手机状态。初值为空（`isEmpty` 有明确语义），真值由 [refreshGrants] 填。 */
    private val _deviceContext = MutableStateFlow(DeviceContext("", "", emptyList(), 0L))
    val deviceContext: StateFlow<DeviceContext> = _deviceContext.asStateFlow()

    /** 调试通道此刻是否就绪——能力就绪度裁决要这一个布尔，不需要整条状态流。 */
    val shizukuReady: Boolean get() = _shizukuStatus.value == ShizukuShell.Status.READY

    /** 重新探测调试通道状态（进入权限页 / 授权回调后调用）。 */
    fun refreshShizukuStatus() {
        _shizukuStatus.value = ShizukuShell.status()
        _shizukuInstalled.value = ShizukuShell.isInstalled(app)
        _shizukuUiState.value = ShizukuShell.uiState(_shizukuStatus.value, _shizukuInstalled.value)
        // 状态变化可能让「跑 shell 命令」的实际路径改变，能力链需重算
        onGrantsChanged()
    }

    /**
     * 只重探状态、不重算能力链。
     *
     * 供 shell 工具的执行路径用：`execViaUserService` 报「未运行或未授权」时，通道状态已经
     * 变了，但当轮的执行路径由上层回落处理，能力链留给下一次权限页复检重算即可。
     * 与 [refreshShizukuStatus] 的差别仅此一处，与拆分前的行为一致。
     */
    fun reprobeShizukuStatus() {
        _shizukuStatus.value = ShizukuShell.status()
    }

    /**
     * 调试通道入口动作（权限页「去开启/去授权」）：
     *  - 未运行 → 拉起 Shizuku（未安装则打开应用商店页）；
     *  - 未授权 → 发起授权请求（弹窗由 Shizuku 提供，结果回调后刷新）；
     *  - 就绪 → 仅刷新状态。
     */
    fun handleShizukuAction(context: Context) {
        // 按「四态」动作：已装就拉起、未装才去商店——不再一律劝人装
        when (_shizukuUiState.value) {
            ShizukuShell.UiState.NOT_INSTALLED -> {
                // 优先「一键安装内置 APK」；没有内置包、或安装失败才退官方发布页。
                // 国内应用商店基本没有 Shizuku，market:// 只会落到空白页（真机录像实证）。
                when (ShizukuInstaller.install(context)) {
                    ShizukuInstaller.Result.INSTALLER_LAUNCHED,
                    ShizukuInstaller.Result.NEED_UNKNOWN_SOURCES -> Unit
                    ShizukuInstaller.Result.NO_BUNDLED_APK,
                    ShizukuInstaller.Result.FAILED -> ShizukuInstaller.openReleasePage(context)
                }
            }
            ShizukuShell.UiState.INSTALLED_NOT_RUNNING -> {
                // 已安装但没启动：直接拉起 Shizuku 本体（用户在里点「通过无线调试启动」）
                if (!ShizukuShell.launch(context)) {
                    // 少数 ROM 没有 launcher 入口，退到应用详情页
                    val details = Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${ShizukuShell.PACKAGE}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(details) }
                }
            }
            ShizukuShell.UiState.NO_PERMISSION -> ShizukuShell.requestPermission()
            ShizukuShell.UiState.READY -> Unit
        }
        refreshShizukuStatus()
    }

    /**
     * 权限引导页：申请电池优化白名单（P0，2026-10-21）。
     *
     * 优先弹系统「是否允许后台运行」对话框（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，
     * 清单里已声明该权限）；部分 ROM 没有这个 Activity，退到电池优化列表页让用户自己找本 App。
     * 两条路都失败时静默返回——引导页的「回来自动复检」会把结果如实显示出来，不编造成功。
     */
    fun openBatteryOptimizationSettings(ctx: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${app.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { ctx.startActivity(direct) }.isSuccess
        if (!launched) {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** 刷新权限授予状态。**异步**：5 处 IPC 实测 700~1000ms（启动最慢段、不吃类热缓存），压主线程=首帧白等一秒。 */
    fun refreshGrants(): Job = scope.launch(Dispatchers.IO) {
        // 通知监听权限：检查 enabled_notification_listeners 设置里是否包含本服务组件（免依赖、全版本可用）
        val cn = ComponentName(app, AgentNotificationListener::class.java)
        val enabledListeners = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners")
        _notificationGranted.value = enabledListeners
            ?.split(":")
            ?.any { it.equals(cn.flattenToString(), ignoreCase = true) }
            ?: false
        // 使用情况访问权限：通过 AppOps 判断 PACKAGE_USAGE_STATS 是否已授予
        _usageGranted.value = try {
            val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                app.packageName
            ) == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) { false }
        // 电池优化白名单（P0 引导）：只影响长任务存活，不阻塞任何工具
        _batteryUnrestricted.value = try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(app.packageName)
        } catch (_: Throwable) { false }
        // 调试通道（Shizuku）：用户可能刚在 Shizuku 里授权/撤权，或刚从商店装完回来
        _shizukuStatus.value = ShizukuShell.status()
        _shizukuInstalled.value = ShizukuShell.isInstalled(app)
        _shizukuUiState.value = ShizukuShell.uiState(_shizukuStatus.value, _shizukuInstalled.value)
        // L4 主动感知：刷新 UI 展示的设备上下文快照
        _deviceContext.value = DeviceContextStore.snapshot()
        // DoD⑤：权限/环境变化后重算能力就绪度（能力链一次走完），并逐级落降级轨迹日志
        onGrantsChanged()
    }
}
