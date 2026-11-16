package com.beichen.polaris.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.beichen.polaris.audio.asr.AsrDirectory
import com.beichen.polaris.model.ModelSpec

/**
 * 语音识别页：只剩「下载哪套离线模型」。
 *
 * ## 为什么把「引擎」那一组删了
 *
 * 以前这一页列着系统里的识别引擎（Google / 高通 / 厂商自定义），让用户挑一个。
 * 2026-11-15 的真机日志证明这台机器上**两个引擎都用不了**：Google 的查询语言支持
 * 直接超时（国内不可达），高通的明确「不支持当前语言」且没有中文离线包。
 * 用户在这份清单里换来换去，得到的永远是「一个字都没有」——选项本身就是假的。
 *
 * 2026-11-16 用户明确要求「那个没用，删了」。于是整条系统识别路径（含本页的引擎清单、
 * 语言支持探测、系统识别页 Intent）全部移除。剩下的路只有一条：**下模型，在自己进程里算**。
 *
 * ## 两套都装时用哪套
 *
 * 「更准」优先（见 `AsrRouter.active`）。快的那套只适合「随口问一句」，
 * 而真机反馈里它把「你在这吗」认成「你在这好」——快 0.8 秒换一个错字，不值。
 * 留两套是因为 24MB 与 74MB 对存储紧张的人是两种选择，不是所有人都愿意为准确率付 50MB。
 */
@Composable
fun VoiceEngineScreen(onOpenStyle: (() -> Unit)? = null) {
    val context = LocalContext.current

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
            subtitle = "装一套离线模型：录音和识别都在手机里算，不依赖系统引擎，也不联网",
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
            title = "离线识别模型",
            description = "两套都装时自动用「更准」那个。删掉随时能重下，不占着空间也不会坏",
        ) {
            ModelRow(
                spec = ModelSpec.AsrFast,
                title = "快速识别",
                hint = "边说边出字，随口问一句最顺手；错字相对多",
                // 删之前先放掉原生识别器：文件删了但内存里还拿着句柄，
                // 表现是「删了还占着空间」，下次识别也未必能立刻重建。
                onBeforeRemove = { AsrDirectory.of(context).releaseAll() },
            )
            ModelRow(
                spec = ModelSpec.AsrAccurate,
                title = "更准识别",
                hint = "松手后约一秒出字，中文短句基本不用改；占地方多一点",
                onBeforeRemove = { AsrDirectory.of(context).releaseAll() },
            )
        }

        AkGroup(title = "这页在做什么", description = null) {
            AkListItem(
                icon = null,
                title = "为什么不再列系统识别引擎",
                description = "这台机器上两个系统引擎都出不了中文（Google 的国内不可达，高通的没有中文包）。" +
                    "列出来只会让人白点一遍，所以整条路都删了",
                onClick = null,
            )
            AkListItem(
                icon = null,
                title = "聊天页与桌宠用的是同一套模型",
                description = "下完就都能用：聊天页在弹层里录，桌宠长按直接录，都不弹系统页面",
                onClick = null,
            )
            AkListItem(
                icon = null,
                title = "模型不在安装包里",
                description = "安装包因此小了 300 多MB；只有真正用语音的人才付这份流量与存储",
                onClick = null,
            )
        }
    }
}
