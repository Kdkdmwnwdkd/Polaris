package com.beichen.polaris.pet

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.beichen.polaris.core.AgentLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌面助手（桌宠）的**唯一状态源**：用户开关意图与悬浮窗服务的实际运行态。
 *
 * ## 为什么是 object 而不是 ViewModel
 *
 * 两边的生命周期对不上：开关住在设置页，悬浮窗住在 [PetOverlayService]，
 * 而 Service 比任何 Activity 都活得久（用户退到桌面它还在）。ViewModel 活在
 * Activity 里，用它当状态源会出现「Service 在跑、但状态说没开」的错位——
 * 用户下次进设置页看到的开关是关的，点一下「开启」反而把已经开着的服务重启一遍。
 *
 * ## 两个状态必须分开
 *
 * - [enabled] 是**意图**（落 SharedPreferences，跨进程重启保持）；
 * - [running] 是**事实**（Service 的 onCreate/onDestroy 更新，进程死就归 false）。
 *
 * 合成一个布尔会丢掉最需要告诉用户的那种情况：开关是开的、服务却没在跑
 * （悬浮窗权限被系统回收、ROM 把服务杀了）。那个状态下设置页要显示
 * 「已开启但未运行」并给一个重试入口，而不是假装一切正常。
 *
 * ## 与 [AudioGraph] 的关系
 *
 * 桌宠朗读必须走 `AudioGraph.speaker(ctx)`，不要自己 new——理由见那个类的注释。
 * 这里只管「窗口在不在」，不碰音频。
 */
object PetState {

    private const val TAG = "PetState"
    private const val PREF = "pet_overlay_v1"
    private const val K_ENABLED = "enabled"

    private val _enabled = MutableStateFlow(false)

    /** 用户是否开启了桌面助手（持久化意图）。 */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _running = MutableStateFlow(false)

    /** 悬浮窗服务当前是否真的在运行（进程内事实）。 */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val lock = Any()

    @Volatile
    private var loaded = false

    /**
     * 从磁盘读一次开关（幂等）。
     *
     * 调用点：`AgentKitApplication.onCreate`。**不在 `MainActivity` 里读**——
     * Service 有可能先于任何 Activity 被系统拉起（`START_STICKY` 重建），
     * 那时如果还没人读过盘，服务会以为自己「不该存在」而立刻停掉。
     */
    fun sync(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val on = runCatching {
                context.applicationContext
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(K_ENABLED, false)
            }.getOrDefault(false)
            _enabled.value = on
            loaded = true
        }
    }

    /** 悬浮窗权限是否已授予（API 23+ 一律走 `canDrawOverlays`）。 */
    fun canDrawOverlays(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context.applicationContext) }.getOrDefault(false)

    /**
     * 打开系统「显示在其他应用上层」授权页。
     *
     * 不申请权限、不弹自定义引导：这是系统设置页，用户自己能看懂。
     * 返回后权限**不会立刻生效**（系统异步写 AppOps），调用方要在 onResume 复查一次。
     */
    fun openOverlaySettings(context: Context): Boolean = runCatching {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.onFailure { AgentLogger.w(TAG, "打不开悬浮窗授权页：${it.message}") }.getOrDefault(false)

    /**
     * 用户打开开关。
     *
     * @return false = 没有悬浮窗权限，什么都没做（调用方应引导授权）。
     *         返回 true 只代表**已请求启动**，服务是否起来看 [running]。
     */
    fun start(context: Context): Boolean {
        val app = context.applicationContext
        if (!canDrawOverlays(app)) {
            AgentLogger.w(TAG, "未授予悬浮窗权限，不启动桌面助手")
            return false
        }
        _enabled.value = true
        persist(app, true)
        launchService(app)
        return true
    }

    /** 用户关闭开关（或桌宠气泡里点「关闭」）。 */
    fun stop(context: Context) {
        val app = context.applicationContext
        _enabled.value = false
        persist(app, false)
        runCatching { app.stopService(Intent(app, PetOverlayService::class.java)) }
            .onFailure { AgentLogger.w(TAG, "停止桌面助手失败：${it.message}") }
    }

    /**
     * 应用回到前台时补一次启动：用户开了开关、但服务在上次进程里被系统杀了。
     *
     * 不在 `Application.onCreate` 里做这件事：Application 也可能是被
     * WorkManager 之类的后台组件拉起的，那时 `startForegroundService` 会撞上
     * Android 12+ 的后台启动限制。用户真正打开 App 才是无争议的时机。
     */
    fun ensureRunning(context: Context) {
        sync(context)
        if (!_enabled.value || _running.value) return
        if (!canDrawOverlays(context)) return
        launchService(context.applicationContext)
    }

    /** 由 [PetOverlayService] 上报（只在 onCreate/onDestroy 各调一次）。 */
    fun markRunning(value: Boolean) {
        _running.value = value
    }

    /**
     * 清空进程内状态（仅 harness / 诊断，由 `AppGraph.resetAll` 调用）。
     *
     * **不写 SharedPreferences**：那是用户的意图，清场不该替用户做决定。
     * 这也是 `AppGraph` 对每条 `reset()` 的硬要求。
     */
    fun reset() {
        _enabled.value = false
        _running.value = false
        loaded = false
    }

    private fun persist(context: Context, value: Boolean) {
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(K_ENABLED, value).apply()
        }.onFailure { AgentLogger.w(TAG, "开关落盘失败：${it.message}") }
    }

    private fun launchService(context: Context) {
        val intent = Intent(context, PetOverlayService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }.onFailure {
            // Android 12+ 后台启动前台服务会抛 ForegroundServiceStartNotAllowedException。
            // 这里不崩：用户下次回到 App 时 ensureRunning 会再试一次。
            AgentLogger.w(TAG, "启动悬浮窗服务失败：${it.message}")
        }
    }
}
