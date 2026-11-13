package com.beichen.polaris.pet

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.beichen.polaris.core.AgentLogger

/**
 * 快捷设置磁贴：下拉通知栏点「北辰」= 把它叫出来听一句。
 *
 * ## 它替代的是唤醒词
 *
 * spec §2.1 的结论是**不做唤醒词**：常驻麦克风 + 关键词检测要 1–3% 的电/小时，
 * 而「桌宠到底会不会被用」这件事还没有任何数据。可「每次都要回桌面找那个小球」
 * 又确实太深了——这一步不解决，桌宠就是「装完看一次」。
 *
 * 磁贴是这条缝里最省的答案：零常驻、零唤醒词、零新权限，一次点击直达「正在听」。
 * 用户把磁贴拖到第一屏，交互成本与「喊一声」差不了多少。
 *
 * ## 与主应用、服务的关系
 *
 * 磁贴**不自己开窗、不自己录音**，只是把「请开始听」这个意图交给
 * [PetOverlayService]。理由和二期所有语音代码一样：
 * - 悬浮窗只有 Service 能长期持有（Activity 一退就没了）；
 * - 麦克风只有 [PetVoiceInput] 能碰（它管着仲裁租约与引擎销毁）。
 *
 * 所以这里的三段逻辑是：没权限 → 去授权页；没运行 → 起服务并带上 ACTION_LISTEN；
 * 在运行 → 直接把 ACTION_LISTEN 递进去。
 *
 * ## 锁屏
 *
 * 锁屏上点磁贴时悬浮窗在 keyguard 之下，用户看不到任何反应。走
 * [unlockAndRun]：系统要求先解锁，解完再执行——比「什么都没发生」诚实。
 */
class PetTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        PetState.sync(this)
        render()
    }

    /**
     * 每次面板被拉开都会回调。桌宠可能刚在设置页被关掉，磁贴得跟着变灰，
     * 否则用户点一下「亮的磁贴」却发现什么都没发生。
     */
    override fun onStartListening() {
        super.onStartListening()
        PetState.sync(this)
        render()
    }

    override fun onClick() {
        super.onClick()
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked == true) {
            AgentLogger.i(TAG, "磁贴：锁屏中，先要求解锁")
            unlockAndRun { activate() }
            return
        }
        activate()
    }

    /**
     * 真正的点击动作。抽出来是因为 [onClick] 与 [unlockAndRun] 两个入口共用。
     *
     * 只在「没有悬浮窗权限」这一条上分支：其余情况（没开、没运行、正在跑）
     * 都由 [PetOverlayService.listen] 内部收敛成同一句「起服务并带上 ACTION_LISTEN」，
     * 免得磁贴这里再长出一份启动顺序的判断——那种重复判断迟早与服务里的真实行为分叉。
     */
    private fun activate() {
        PetState.sync(this)
        if (!PetState.canDrawOverlays(this)) {
            AgentLogger.i(TAG, "磁贴：没有悬浮窗权限，跳授权页")
            openOverlaySettingsPage()
            render()
            return
        }
        val requested = PetOverlayService.listen(this)
        AgentLogger.i(TAG, "磁贴：请求聆听 requested=$requested")
        // 请求是异步的（服务要 onCreate → 建窗 → 才可能开始听），这里刷新只是让
        // 磁贴至少反映「已开启」。真正的运行态由下一次 onStartListening 纠正。
        render()
    }

    /**
     * 打开「显示在其他应用上层」授权页。
     *
     * Android 14 起 `startActivityAndCollapse(Intent)` 不再允许后台拉起任意 Activity，
     * 必须改用 PendingIntent 重载。targetSdk 还是 28，系统眼下不会拦，但这条路径
     * 一旦在升级 targetSdk 后才暴露，表现就是「点磁贴毫无反应」——最难查的那种。
     */
    private fun openOverlaySettingsPage() {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    REQ_OVERLAY_SETTINGS,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (launched.isFailure) {
            AgentLogger.w(TAG, "磁贴打不开授权页：${launched.exceptionOrNull()?.message}")
        }
    }

    /** 磁贴外观：运行中为点亮态。图标用系统麦克风，免得为一个 24dp 图标新增资源。 */
    private fun render() {
        val tile = qsTile ?: return
        val running = PetState.running.value
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "北辰"
        tile.icon = Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) "在桌面待命" else "点一下听你说"
        }
        runCatching { tile.updateTile() }
            .onFailure { AgentLogger.w(TAG, "磁贴刷新失败：${it.message}") }
    }

    private companion object {
        const val TAG = "PetTile"
        const val REQ_OVERLAY_SETTINGS = 41
    }
}
