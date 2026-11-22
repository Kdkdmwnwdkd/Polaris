package com.beichen.polaris.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.beichen.polaris.audio.asr.AsrDirectory

/**
 * 聊天页语音输入的权限闸门。
 *
 * ## 为什么需要这一层
 *
 * 旧实现只判断「离线模型装没装」：装了就直接开 [LocalVoiceSheet]。可面板里的
 * [com.beichen.polaris.audio.asr.LocalSpeechInput.start] 一发现没有麦克风权限就只回报
 * 一行红字——没有按钮、没有跳转，而本应用自己的设置页里并没有「麦克风」这一项
 * （那是开桌面助手时才顺手申请的）。从聊天页进来的用户于是永远拿不到授权。
 *
 * 同一仓库里 `VoiceCloneScreen` 的写法是对的（点录音先 `launch`），所以这是漏改，
 * 不是设计。这里把闸门前移到入口，三条出路各归各位：
 *
 * 1. 模型没装 → 告诉用户去哪下（这时要权限也没用）；
 * 2. 已授权 → 直接开面板；
 * 3. 没授权 → 第一次弹系统框；已经问过还被拒的，系统不会再弹（`launch` 只会立刻
 *    回调 false），这时把人送到本应用详情页手动开。
 *
 * 第 3 条的后半段是重点：没有它，「永久拒绝」的用户会掉进和旧实现一样的死路，
 * 只是换成点一次弹一次 Toast 而已。
 *
 * ## 分支判定不在这里
 *
 * 「走哪条路」由 [VoiceInputDecision.next]（同目录，纯函数）决定，本文件只负责
 * 把结论落到副作用上。原因是旧版的判据只能靠 grep 看「`open()` 是否出现在
 * `if (granted)` 后面」——把 `open()` 挪进 `else` 分支，grep 全绿而真机不工作。
 * 判定搬进纯函数后才有 JUnit 与变异门禁，grep 只再管「接线」。
 *
 * ## 为什么放在单独文件
 *
 * `ChatScreen.kt` 有 1500 行上限（arch-guard [B]），且只减不增。这段逻辑带注释二十多行，
 * 塞回去就等于让守卫变红；闸门本身也不依赖 ChatScreen 的任何状态（只要一个
 * 「授予后干什么」的回调），天然该独立。
 */
@Composable
fun rememberVoiceInputGate(onOpen: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    // 「已经问过一次」只在本进程内有效。进程重启后再点会重新弹框（若系统仍允许），
    // 或者立刻回调 false 再把人送去设置页——两种结果都能接受，不需要落盘。
    var asked by remember { mutableStateOf(false) }
    val open by rememberUpdatedState(onOpen)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            open()
        } else {
            Toast.makeText(
                ctx,
                "没有麦克风权限，语音输入用不了。再点一次麦克风按钮可去系统设置里打开",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    return remember {
        {
            val installed = AsrDirectory.of(ctx).isInstalled()
            val granted =
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED

            // 分支判定在 VoiceInputDecision（纯函数，有 JUnit + 变异门禁）。
            // 这里只负责把结论落到副作用上——`when` 不写 else，枚举漏一支就编译不过。
            when (VoiceInputDecision.next(installed, granted, asked)) {
                VoiceInputNext.TellInstallModel -> Toast.makeText(
                    ctx,
                    "还没下载离线识别模型。到 设置 → 语音识别 里下「更准识别」（约 74MB），下完就能说话",
                    Toast.LENGTH_LONG,
                ).show()

                VoiceInputNext.OpenPanel -> open()

                VoiceInputNext.GoSettings -> openAppSettings(ctx)

                VoiceInputNext.RequestPermission -> {
                    asked = true
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
}

/**
 * 跳到本应用详情页（权限开关在这里）。
 *
 * `startActivity` 可能抛（部分 ROM 缺该 Activity），故 runCatching——跳不过去顶多
 * 停在原地，不该把整个点击事件炸掉。
 */
private fun openAppSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", ctx.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
