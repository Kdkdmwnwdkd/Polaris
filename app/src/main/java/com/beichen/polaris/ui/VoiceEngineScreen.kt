package com.beichen.polaris.ui

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.beichen.polaris.audio.SpeechProbe
import com.beichen.polaris.audio.VoiceDiagnostics
import com.beichen.polaris.audio.VoiceEngineStore
import com.beichen.polaris.audio.asr.AsrModelInstaller
import com.beichen.polaris.core.voice.EngineCapability
import com.beichen.polaris.core.voice.EngineLabel
import com.beichen.polaris.core.voice.EngineSelector
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 语音识别页。
 *
 * ## 为什么需要这一页
 *
 * 原来的提示语是「到系统设置 → 默认应用 → 语音识别 换一个服务」。
 * 2026-11-14 用户拍了三张截图回来：他的「默认应用」里只有「文字转语音输出」，
 * 点进去是 TTS 的首选引擎（MultiTTS / Google / Qualcomm），**根本没有「语音识别」这一项**。
 *
 * 也就是说，我们一直在把用户送进一条死路。而且那不是用户的机器特殊——
 * 很多国产 ROM 都不把 `VOICE_RECOGNITION_SERVICE` 暴露成设置项，
 * 系统只是自己挑了一个默认值写进去。
 *
 * 好在应用侧本来就能指定引擎：`createSpeechRecognizer(ctx, component)`。
 * 既然能指定，就不该要求用户去一个可能不存在的系统页面里翻——
 * 把选择搬到我们自己的页面上，这是唯一不依赖 ROM 的做法。
 *
 * ## 2026-11-15：多了一条更硬的路
 *
 * 真机日志证明这台机器上**两个系统引擎都用不了**：Google 的查询语言支持直接超时
 * （国内不可达），Qualcomm 的明确「不支持当前语言」且没有中文离线包。
 * 在这种机器上，无论把引擎换成谁都不会有字。
 *
 * 所以页面最上面加了一组「离线识别（自带）」：模型装在应用私有目录里，
 * 录、算、出字全在我们进程内，不认任何系统服务。装了它，下面那组引擎就退居备用。
 */
@Composable
fun VoiceEngineScreen(onOpenStyle: (() -> Unit)? = null) {
    val context = LocalContext.current
    var engines by remember { mutableStateOf<List<EngineCapability>?>(null) }
    var probing by remember { mutableStateOf(true) }
    var pinned by remember { mutableStateOf(VoiceEngineStore.pinned(context)) }

    // 自带离线识别：装了它，下面那一组引擎就不再是唯一出路。
    val asrInstaller = remember { AsrModelInstaller(context) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    var asrReady by remember { mutableStateOf(asrInstaller.isInstalled()) }
    var asrBytes by remember { mutableStateOf(asrInstaller.bytesOnDisk()) }
    var asrBusy by remember { mutableStateOf(false) }
    var asrProgress by remember { mutableStateOf(0.0f) }
    var asrError by remember { mutableStateOf<String?>(null) }

    // 进页面就重探一次：用户来这里往往刚装/卸过语言包，读缓存会给他看旧结论。
    LaunchedEffect(Unit) {
        probing = true
        SpeechProbe(context).probeEngines(force = true) {
            engines = it
            probing = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = AgentSpacing.space5,
                end = AgentSpacing.space5,
                top = AgentSpacing.space1,
                bottom = AgentSpacing.space8,
            )
            .navigationBarsPadding()
    ) {
        AkDisplayTitle(
            title = "语音识别",
            subtitle = "先看「离线识别」那一组：装了它就不依赖系统引擎；没装才用下面的",
        )

        if (onOpenStyle != null) {
            AkGroup(title = "相关", description = "识别是「听谁的」，音色是「说成谁的声」") {
                AkListItem(
                    icon = Icons.Outlined.VolumeUp,
                    title = "去选音色",
                    description = "桌宠与朗读用哪个声音；可逐条试听",
                    onClick = onOpenStyle,
                )
            }
        }

        AkGroup(
            title = "离线识别（自带）",
            description = if (asrReady) {
                "已就位：桌宠长按说话、聊天页麦克风都优先用它——不联网、只认中文"
            } else {
                "这台机器上系统那两个识别引擎都用不了（Google 的国内不可达，高通的没有中文包）。" +
                    "装这个才能真正识别出字"
            },
        ) {
            AkListItem(
                icon = Icons.Outlined.Mic,
                title = when {
                    asrBusy -> "正在下载中文识别模型… ${(asrProgress * 100).toInt()}%"
                    asrReady -> "已下载 · ${max(1L, asrBytes / 1048576)}MB"
                    else -> "下载中文识别模型（约 52MB）"
                },
                description = asrError ?: when {
                    asrReady -> "点一下可以删掉，随时能重下；删了会退回系统引擎"
                    asrBusy -> "下载中请保持前台；装好后自动生效"
                    else -> "装到应用私有目录，只下一次；不想要了随时删"
                },
                onClick = when {
                    asrBusy -> null
                    asrReady -> ({
                        asrInstaller.remove()
                        asrReady = false
                        asrBytes = 0
                        asrError = null
                    })
                    else -> ({
                        asrBusy = true
                        asrError = null
                        asrProgress = 0.0f
                        scope.launch {
                            runCatching {
                                asrInstaller.download { done, total, _ ->
                                    val ratio = done.toFloat() / max(1L, total)
                                    main.post { asrProgress = ratio }
                                }
                            }.onFailure { e -> main.post { asrError = e.message ?: "下载失败" } }
                            main.post {
                                asrBusy = false
                                asrReady = asrInstaller.isInstalled()
                                asrBytes = asrInstaller.bytesOnDisk()
                            }
                        }
                    })
                },
            )
        }

        AkGroup(
            title = "引擎",
            description = "只影响桌宠长按说话；改完下次长按就生效"
        ) {
            AkListItem(
                icon = null,
                title = "自动",
                description = "按当前语言挑一个能用的（推荐）",
                selected = pinned.isNullOrBlank(),
                onClick = {
                    VoiceEngineStore.pin(context, null)
                    pinned = null
                },
            )
            engines.orEmpty().forEach { e ->
                AkListItem(
                    icon = null,
                    title = EngineLabel.friendly(e.component),
                    description = buildString {
                        append(EngineSelector.describe(EngineSelector.fit(e)))
                        if (e.isDefault) append(" · 系统默认")
                    },
                    selected = pinned == e.component,
                    onClick = {
                        VoiceEngineStore.pin(context, e.component)
                        pinned = e.component
                    },
                )
            }
            if (probing) {
                AkListItem(
                    icon = null,
                    title = "正在探测本机的识别引擎…",
                    description = "约 2 秒，探完上面会列出实际引擎",
                    onClick = null,
                )
            } else if (engines.orEmpty().isEmpty()) {
                AkListItem(
                    icon = null,
                    title = "没探到识别引擎",
                    description = "系统版本低于 Android 13，或本机没有语音识别服务",
                    onClick = null,
                )
            }
        }

        AkGroup(title = "这页在做什么", description = null) {
            AkListItem(
                icon = null,
                title = "为什么系统设置里找不到「语音识别」",
                description = "很多机型（含这台魅族）不把它做成设置项。所以选择放在这里，点一下就能改",
                onClick = null,
            )
            AkListItem(
                icon = null,
                title = "聊天页现在也会用离线识别",
                description = "装了上面的模型，聊天页就不再弹系统识别页，直接在我们自己的界面里录",
                onClick = null,
            )
            AkListItem(
                icon = Icons.Outlined.Mic,
                title = "生成语音诊断",
                description = "把本机的引擎清单与结论复制出来，排查时发给我",
                onClick = {
                    Toast.makeText(context, "正在探测语音引擎…", Toast.LENGTH_SHORT).show()
                    VoiceDiagnostics.buildReportAsync(context) { report ->
                        runCatching {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(
                                android.content.ClipData.newPlainText("北辰语音诊断", report)
                            )
                        }
                        Toast.makeText(context, "诊断报告已复制到剪贴板", Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
    }
}
