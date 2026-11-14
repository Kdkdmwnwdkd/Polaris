package com.beichen.polaris.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.beichen.polaris.audio.SpeechProbe
import com.beichen.polaris.audio.VoiceDiagnostics
import com.beichen.polaris.audio.VoiceEngineStore
import com.beichen.polaris.core.voice.EngineCapability
import com.beichen.polaris.core.voice.EngineLabel
import com.beichen.polaris.core.voice.EngineSelector

/**
 * 语音识别引擎选择页。
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
 * ## 为什么只有「自动」和具体引擎两档
 *
 * 不给「离线/在线」之类的开关：那不是用户该做的决定，是引擎自己的事。
 * 这里只回答一个问题——**用哪个引擎**。
 *
 * ## 只影响桌宠
 *
 * 聊天页走系统识别页（`ACTION_RECOGNIZE_SPEECH` 的 Activity 版），
 * 那个页面由系统拉起，用哪个引擎、认哪个语言都由它自己决定，我们指定不了。
 * 这一点必须写在页面上，否则用户改完引擎发现聊天页没变，会以为设置没生效。
 */
@Composable
fun VoiceEngineScreen() {
    val context = LocalContext.current
    var engines by remember { mutableStateOf<List<EngineCapability>?>(null) }
    var probing by remember { mutableStateOf(true) }
    var pinned by remember { mutableStateOf(VoiceEngineStore.pinned(context)) }

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
            subtitle = "选一个引擎给桌宠用；聊天页走系统识别页，由系统决定",
        )

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
                title = "聊天页为什么不受这里影响",
                description = "聊天页弹的是系统识别页，用哪个引擎由系统决定，我们指定不了",
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
