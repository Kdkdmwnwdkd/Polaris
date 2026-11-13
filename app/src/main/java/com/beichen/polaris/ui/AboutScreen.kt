package com.beichen.polaris.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.beichen.polaris.core.update.ApkDownloader
import com.beichen.polaris.core.update.DownloadedApk
import com.beichen.polaris.core.update.UpdateChecker
import com.beichen.polaris.update.UpdateInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 关于页（UX v1 §1.9）：版本、设计规范、开源许可与数据去向。
 *
 * 一级页面：顶栏走 [PageTopBar]（标题「关于」，无右上动作）。
 * 本页纯静态展示，不接 ViewModel——装一次看一次，不值得为它拉一份状态。
 *
 * W-65：设置页把「关于」与「权限引导」压成了一行（关于与帮助），所以权限入口搬到这里，
 * 紧跟应用标识之后——先知道这是什么，再知道要给它什么权限。
 */
@Composable
fun AboutScreen(onOpenPermissionGuide: () -> Unit = {}) {
    val ctx = LocalContext.current
    // 版本号从 PackageManager 读：不依赖 BuildConfig 生成开关，AGP 关掉 buildConfig 也不会编译失败
    val versionName = remember(ctx) {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull() ?: "—"
    }
    // 「这一版是什么时候出的」直接读 APK 的安装时间戳 —— 它就是构建产物写入的时间，
    // 比在 gradle 里塞 BuildConfig 字段省事，也不会因为 buildConfig 开关被关掉而编译失败。
    //
    // 时区显式钉成 Asia/Shanghai：CI 机器跑在 UTC，用默认时区会显示成凌晨四点，
    // 和用户手机上看到的发布时间差 8 小时。
    val buildTime = remember(ctx) {
        runCatching {
            val ts = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
                .format(Date(ts))
        }.getOrNull() ?: "—"
    }

    Column(Modifier.fillMaxWidth()) {
        PageTopBar(title = AppDest.ABOUT.title)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberPersistentScrollState())
                .padding(
                    start = AgentSpacing.space5,
                    end = AgentSpacing.space5,
                    top = AgentSpacing.space3,
                    bottom = AgentSpacing.space8
                ),
            verticalArrangement = Arrangement.spacedBy(AgentSpacing.space4)
        ) {
            // —— 应用标识 ——
            AkSectionCard(title = "北辰", icon = Icons.Outlined.Shield) {
                Text(
                    text = "跑在本机的对话式手机助手：接你自己配置的模型接口，" +
                        "在危险操作前拦一道审批，把日志与记忆都留在本机。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(AgentSpacing.space3))
                Text(
                    text = "版本 $versionName  ·  $buildTime（北京时间）",
                    style = machineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(AgentSpacing.space2))
                // 更新入口放在版本号下面：用户想「看看有没有新版」时，视线就在这一行
                UpdateRow()
            }

            // —— 权限怎么给（W-65 从设置页并进来的那一半）——
            AkGroup(
                title = "帮助",
                description = "五项设备能力逐项说明「开了能做什么、不开会缺什么」"
            ) {
                AkListItem(
                    icon = Icons.Outlined.Shield,
                    title = "权限怎么给",
                    description = "无障碍、Shizuku、悬浮窗、通知与电池优化，逐项引导",
                    onClick = onOpenPermissionGuide
                )
            }

            // —— 设计规范 ----
            AkSectionCard(
                title = "设计规范",
                icon = Icons.Outlined.Style,
                description = "视觉规范 v3.1（暖纸 Warm Paper）+ UX v1（信息架构与交互规则）。" +
                    "三层底色分层、0 阴影 0 描边、按压 = scale 0.97 + 100ms、" +
                    "动效只四个 token；色值全部取自规范 §3 色板，本页不引入规范外色相。"
            )

            // —— 数据去向 ----
            AkSectionCard(
                title = "数据去向",
                icon = Icons.Outlined.Lock,
                description = "对话内容、前台应用名与最近通知会随请求发送到你配置的模型接口，" +
                    "除此之外不外传到任何地方；日志与记忆只写本机 filesDir，" +
                    "可在「运行日志」「记忆库」两页自行导出或清空。"
            )

            // —— 开源许可 ----
            AkSectionCard(
                title = "开源许可",
                icon = Icons.Outlined.Info,
                description = "本项目使用下列开源组件，各组件版权归其作者所有：" +
                    "\n· Jetpack Compose（UI / Material3 / Icons）— Apache 2.0" +
                    "\n· AndroidX Activity、Lifecycle — Apache 2.0" +
                    "\n· Kotlin、Kotlinx Coroutines、kotlinx.serialization — Apache 2.0" +
                    "\n· OkHttp — Apache 2.0" +
                    "\n· Rhino（Mozilla）— MPL 2.0"
            )

            // —— 收尾印记：应用标记 + 规范版本，一屏的落款 ——
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(AgentSpacing.space2))
                Text(
                    text = "北辰 · 设计规范 v3.1 · UX v1",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


/** 更新流程的各个阶段。 */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Latest : UpdateState
    data class HasUpdate(
        val latest: Int,
        val size: Long,
        val assetName: String,
        /** Release 接口给的 SHA-256；空串表示接口没给，此时下载器只走直连。 */
        val sha256: String
    ) : UpdateState
    data class Downloading(val done: Long, val total: Long) : UpdateState
    data class Ready(val apk: DownloadedApk) : UpdateState
    data class NeedPermission(val apk: DownloadedApk) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * 版本行 + 检查更新 + 应用内下载。
 *
 * 下载在应用内完成（[ApkDownloader] → cacheDir/updates/），完成后直接把
 * `content://` 交给系统安装器——不再跳浏览器。跳浏览器等于把「点一下装好」
 * 变成「找浏览器、等下载、回文件管理器、点安装」，四步里有三步会掉人。
 *
 * 浏览器入口只在失败时作为兜底保留：`api.github.com` 与加速通道都可能不通，
 * 那时总得让用户有条路。
 */
@Composable
private fun UpdateRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    // 下载进度回调在 IO 线程，统一 post 回主线程再改 state，避免跨线程写快照状态。
    val main = remember { Handler(Looper.getMainLooper()) }
    // 速度做指数滑动平均：瞬时值在缓冲抖动下会从 3MB/s 跳到 200KB/s，看着像坏了。
    var speed by remember { mutableStateOf(0L) }
    val meter = remember { longArrayOf(0L, 0L) } // [上次字节数, 上次时间戳]

    val currentCode = remember(ctx) {
        runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        }.getOrDefault(0)
    }

    fun startCheck() {
        if (state == UpdateState.Checking) return
        state = UpdateState.Checking
        scope.launch {
            UpdateChecker.check(currentCode).fold(
                onSuccess = { info ->
                    state = if (info.hasUpdate) {
                        UpdateState.HasUpdate(
                            info.latestCode, info.assetSize, info.assetName, info.assetSha256
                        )
                    } else {
                        UpdateState.Latest
                    }
                },
                onFailure = { e ->
                    state = UpdateState.Failed(e.message?.take(60) ?: "网络错误")
                }
            )
        }
    }

    fun startDownload(latest: Int, size: Long, assetName: String, sha256: String) {
        if (state is UpdateState.Downloading) return
        state = UpdateState.Downloading(0L, size)
        speed = 0L
        meter[0] = 0L
        meter[1] = 0L
        job = scope.launch {
            try {
                val apk = ApkDownloader.download(
                    context = ctx,
                    officialUrl = UpdateChecker.officialUrl(assetName),
                    expectedVersionCode = latest,
                    expectedSha256 = sha256,
                    expectedSize = size
                ) { done, total ->
                    main.post {
                        // 取消/失败之后可能还有一两个进度回调在路上；不拦的话会把状态
                        // 从「已取消」又推回「下载中」，用户以为取消没生效。
                        if (state !is UpdateState.Downloading) return@post
                        val now = System.currentTimeMillis()
                        if (meter[1] > 0 && now > meter[1]) {
                            val instant = (done - meter[0]) * 1000L / (now - meter[1])
                            speed = if (speed == 0L) instant else (speed * 3 + instant) / 4
                        }
                        meter[0] = done
                        meter[1] = now
                        state = UpdateState.Downloading(done, total)
                    }
                }
                state = when (UpdateInstaller.install(ctx, apk.file)) {
                    UpdateInstaller.Result.LAUNCHED -> UpdateState.Ready(apk)
                    UpdateInstaller.Result.NEED_PERMISSION -> UpdateState.NeedPermission(apk)
                    UpdateInstaller.Result.FAILED -> UpdateState.Failed("安装器没能打开，点「安装」重试")
                }
            } catch (ce: CancellationException) {
                state = UpdateState.HasUpdate(latest, size, assetName, sha256)
                throw ce
            } catch (t: Throwable) {
                state = UpdateState.Failed(t.message ?: "下载失败")
            } finally {
                job = null
            }
        }
    }

    // 离开关于页就停掉下载：让用户在别的页面等一个看不见的进度条没有意义。
    // 下次回来点「更新」会复用已下好的包（ApkDownloader 有校验 + 复用）。
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    val s = state
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AgentSpacing.space2)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (s) {
                    UpdateState.Idle -> "当前构建号 $currentCode"
                    UpdateState.Checking -> "正在检查…"
                    UpdateState.Latest -> "已是最新版（构建 $currentCode）"
                    is UpdateState.HasUpdate -> "有新版本：构建 ${s.latest}（当前 $currentCode）"
                    is UpdateState.Downloading -> "正在下载更新包…"
                    is UpdateState.Ready -> "更新包已就绪（构建 ${s.apk.versionCode}）"
                    is UpdateState.NeedPermission -> "更新包已下载，还差安装授权"
                    is UpdateState.Failed -> "出错了：${s.reason}"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = when (s) {
                    is UpdateState.HasUpdate, is UpdateState.Ready -> MaterialTheme.colorScheme.primary
                    is UpdateState.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            when (s) {
                UpdateState.Idle -> AkTextButton(text = "检查更新", onClick = { startCheck() })
                UpdateState.Checking -> Unit
                UpdateState.Latest -> AkTextButton(text = "重新检查", onClick = { startCheck() })
                is UpdateState.HasUpdate -> AkTextButton(
                    text = "立即更新",
                    onClick = { startDownload(s.latest, s.size, s.assetName, s.sha256) }
                )
                is UpdateState.Downloading -> AkTextButton(
                    text = "取消",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { job?.cancel() }
                )
                is UpdateState.Ready -> AkTextButton(
                    text = "重新安装",
                    onClick = {
                        state = when (UpdateInstaller.install(ctx, s.apk.file)) {
                            UpdateInstaller.Result.LAUNCHED -> UpdateState.Ready(s.apk)
                            UpdateInstaller.Result.NEED_PERMISSION -> UpdateState.NeedPermission(s.apk)
                            UpdateInstaller.Result.FAILED -> UpdateState.Failed("安装器没能打开这个包")
                        }
                    }
                )
                is UpdateState.NeedPermission -> AkTextButton(
                    text = "继续安装",
                    onClick = {
                        // 授权页回来后直接再走一次：没授权会再跳设置，授权了就把安装器拉起来。
                        // 只给「去授权」的话，用户开完开关回来会发现没有按钮可点，只能重查一遍。
                        state = when (UpdateInstaller.install(ctx, s.apk.file)) {
                            UpdateInstaller.Result.LAUNCHED -> UpdateState.Ready(s.apk)
                            UpdateInstaller.Result.NEED_PERMISSION -> UpdateState.NeedPermission(s.apk)
                            UpdateInstaller.Result.FAILED -> UpdateState.Failed("安装器没能打开这个包")
                        }
                    }
                )
                is UpdateState.Failed -> AkTextButton(text = "重试", onClick = { startCheck() })
            }
        }

        if (s is UpdateState.Downloading) {
            val frac = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
            if (s.total > 0) {
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            } else {
                // 服务端没给 Content-Length（chunked）时用不确定态，不假装知道进度
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
            Text(
                text = buildString {
                    append(mbText(s.done))
                    if (s.total > 0) append(" / ${mbText(s.total)} MB · ${(frac * 100).toInt()}%")
                    else append(" MB")
                    if (speed > 0) append(" · ${mbText(speed)} MB/s")
                },
                style = machineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 签名不一致只提示、不阻断：本地自测包装的更新包是 CI 签名，本来就对不上，
        // 拦下来会让这条链路完全没法自测。真正的拒绝由系统安装器给。
        if (s is UpdateState.Ready && !s.apk.signatureMatches) {
            Text(
                text = "这个包的签名和本机装的不一致，系统会拒绝覆盖安装；先卸载本应用再装即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // 失败才给浏览器兜底：正常路径不该离开应用
        if (s is UpdateState.Failed) {
            Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                AkTextButton(
                    text = "浏览器下载",
                    onClick = { openUrl(ctx, UpdateChecker.DOWNLOAD_URL) }
                )
                AkTextButton(
                    text = "打开 Release 页",
                    onClick = { openUrl(ctx, UpdateChecker.RELEASE_PAGE) }
                )
            }
        }
    }
}

private fun mbText(bytes: Long): String =
    String.format(Locale.CHINA, "%.1f", bytes / 1024.0 / 1024.0)

private fun openUrl(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
