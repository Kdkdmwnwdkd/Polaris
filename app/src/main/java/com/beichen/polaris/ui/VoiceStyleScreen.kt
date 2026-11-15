package com.beichen.polaris.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.beichen.polaris.audio.AudioGraph
import com.beichen.polaris.audio.tts.CloneVoice
import com.beichen.polaris.audio.tts.CloneVoiceStore
import com.beichen.polaris.audio.tts.VoiceStyleStore
import com.beichen.polaris.core.voice.VoiceCatalog
import com.beichen.polaris.core.voice.VoiceKind
import com.beichen.polaris.core.voice.VoiceProfile
import kotlinx.coroutines.launch

/**
 * 音色选择页。
 *
 * ## 这一页回答的问题
 *
 * 「桌宠说话时听起来是谁」。它不影响识别（那是另一页），只影响喇叭。
 * 两件事在系统设置里挤在同一个入口，用户很容易改错——所以这里把边界写在副标题上。
 *
 * ## 为什么每条都能试听
 *
 * 音色是**听**出来的，不是看名字看出来的。「晓晓 · 温柔」对一个没听过的人来说
 * 等于没写。试听按钮是这一页存在的理由，不是附赠功能。
 *
 * ## 在线那一组为什么现在能点了
 *
 * 之前这 6 个只列出来、点不动：能免费用的那批神经音色走微软 Edge 朗读通道，
 * 非官方客户端一律 403（缺 `Sec-MS-GEC` 令牌）。
 *
 * 现在令牌在本地算（[com.beichen.polaris.audio.tts.SecMsGec]，与上游逐字节对拍），
 * 所以不填任何 key 也能出声。代价是这条路是网页端私有协议，微软改一次就会 403——
 * 因此它永远排在回落链前面而不是唯一一环：失败自动退到本地，用户不会没声音。
 *
 * ## 为什么「我的声音」排在最前
 *
 * 用户来这里十次有九次是想换成自己的声音。把它放在「本地/在线/系统」之间，
 * 等于让最重要的一条排在第三条之后。
 */
@Composable
fun VoiceStyleScreen(onOpenClone: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speaker = remember { AudioGraph.speaker(context) }

    // 克隆音色是用户数据，不在 VoiceCatalog 里，所以「当前选中」必须从存储解析。
    // 直接读存储而不是 new 一个 VoiceSpeech：那里会起播放线程、持有本地引擎，
    // 为了显示一行「已选中」付这个代价不值。
    fun resolveSelected(): VoiceProfile {
        val id = VoiceStyleStore.selectedId(context)
        CloneVoiceStore.read(context, id)?.let { return it.profile }
        return VoiceCatalog.byId(id) ?: VoiceCatalog.require(VoiceCatalog.DEFAULT_ID)
    }

    var selected by remember { mutableStateOf(resolveSelected()) }
    var clones by remember { mutableStateOf(CloneVoiceStore.list(context)) }
    var auditioning by remember { mutableStateOf<String?>(null) }

    fun audition(profile: VoiceProfile) {
        if (auditioning != null) return
        auditioning = profile.id
        scope.launch {
            val text = if (profile.kind == VoiceKind.CLONE) {
                "你好，这是你的声音。以后就由它来说话。"
            } else {
                "你好，我是${profile.safeLabel}。今天想聊点什么？"
            }
            val r = speaker.audition(profile, text)
            auditioning = null
            if (r.isFailure) {
                val why = r.exceptionOrNull()?.message ?: "试听失败"
                Toast.makeText(context, why, Toast.LENGTH_LONG).show()
            }
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
            title = "音色",
            subtitle = "桌宠与朗读用它出声；不影响语音识别",
        )

        AkGroup(
            title = "我的声音",
            description = if (clones.isEmpty()) {
                "录一句 10 秒的话，就能用自己的声音说话。全程离线，不上传"
            } else {
                "你录的声音。合成比内置音色慢，因为它每次都要重新读一遍你的参考音频"
            },
            // 内容始终是 AkListItem（缩进由容器下发），所以 paddedContent 保持 false。
            // 传成「是否为空」会让有克隆和无克隆时这一组的左边界差一格。
            paddedContent = false,
        ) {
            clones.forEach { voice ->
                CloneRow(
                    voice = voice,
                    selected = selected.id == voice.id,
                    auditioning = auditioning == voice.id,
                    onSelect = {
                        VoiceStyleStore.select(context, voice.id)
                        selected = voice.profile
                        audition(voice.profile)
                    },
                    onAudition = { audition(voice.profile) },
                    onDelete = {
                        CloneVoiceStore.delete(context, voice.id)
                        clones = CloneVoiceStore.list(context)
                        // 删掉的正是当前选中的：退回内置音色，并**写回存储**。
                        // 只改界面状态的话，下次启动仍会读到那个已经不存在的 id。
                        if (selected.id == voice.id) {
                            VoiceStyleStore.select(context, VoiceCatalog.DEFAULT_ID)
                            selected = VoiceCatalog.require(VoiceCatalog.DEFAULT_ID)
                        }
                        Toast.makeText(context, "已删除这条声音", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            AkListItem(
                icon = Icons.Outlined.Mic,
                title = if (clones.isEmpty()) "录一句，克隆我的声音" else "再录一条",
                description = "读完一句固定的话就自动保存；建议在安静的地方录",
                onClick = onOpenClone,
                enabled = onOpenClone != null,
            )
        }

        AkGroup(
            title = "本地",
            description = "装完即用，断网也能说话。模型 72MB 随包，不占流量",
        ) {
            VoiceRow(
                profile = VoiceCatalog.local,
                selected = selected.id == VoiceCatalog.local.id,
                auditioning = auditioning == VoiceCatalog.local.id,
                enabled = true,
                onClick = {
                    VoiceStyleStore.select(context, VoiceCatalog.local.id)
                    selected = VoiceCatalog.local
                    audition(VoiceCatalog.local)
                },
                onAudition = { audition(VoiceCatalog.local) },
            )
        }

        AkGroup(
            title = "在线神经音色",
            description = "最像真人那一档，需要联网；接口失效或断网会自动换成本地音色——最坏是没那么好听，不会没声音",
        ) {
            VoiceCatalog.online.forEach { profile ->
                VoiceRow(
                    profile = profile,
                    selected = selected.id == profile.id,
                    auditioning = auditioning == profile.id,
                    enabled = true,
                    onClick = {
                        VoiceStyleStore.select(context, profile.id)
                        selected = profile
                        audition(profile)
                    },
                    onAudition = { audition(profile) },
                )
            }
        }

        AkGroup(title = "系统", description = "设备自带的合成引擎，音色由系统语音包决定") {
            VoiceRow(
                profile = VoiceCatalog.system,
                selected = selected.id == VoiceCatalog.system.id,
                auditioning = auditioning == VoiceCatalog.system.id,
                enabled = true,
                onClick = {
                    VoiceStyleStore.select(context, VoiceCatalog.system.id)
                    selected = VoiceCatalog.system
                },
                onAudition = {
                    scope.launch {
                        speaker.speak("你好，我是系统语音。", "zh", 1.0f)
                    }
                },
            )
        }

        speaker.lastDemotion?.let { note ->
            AkGroup(title = "上次实际用的不是首选", description = null) {
                AkListItem(
                    icon = null,
                    title = note,
                    description = "发生一次就够了；如果每次都是，多半是模型没展开成功或参考录音丢了",
                    onClick = null,
                )
            }
        }

        AkGroup(title = "这一页在做什么", description = null) {
            AkListItem(
                icon = null,
                title = "为什么本地音色听起来比手机自带的好",
                description = "自带语音包常见是 8kHz（打电话那种）；本地模型是 22kHz，细节多一倍",
                onClick = null,
            )
            AkListItem(
                icon = Icons.Outlined.VolumeUp,
                title = "为什么在线音色现在点不动",
                description = "免费那批走的是微软网页朗读接口，已被防滥用校验挡住；要走官方接口得填 key",
                onClick = null,
            )
        }
    }
}

@Composable
private fun CloneRow(
    voice: CloneVoice,
    selected: Boolean,
    auditioning: Boolean,
    onSelect: () -> Unit,
    onAudition: () -> Unit,
    onDelete: () -> Unit,
) {
    AkListItem(
        icon = Icons.Outlined.Mic,
        title = voice.label + if (auditioning) " · 正在试听…" else "",
        description = "你的声音 · 离线克隆" + if (selected) " · 已选中" else "",
        selected = selected,
        onClick = onSelect,
        trailing = {
            Text(
                text = "删除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clickable { onDelete() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        },
    )
    AkListItem(
        icon = Icons.Outlined.VolumeUp,
        title = "试听「${voice.label}」",
        description = if (auditioning) "正在合成…（克隆比内置音色慢，等 2–5 秒）" else "用一句固定的话念给你听",
        selected = false,
        onClick = onAudition,
    )
}

@Composable
private fun VoiceRow(
    profile: VoiceProfile,
    selected: Boolean,
    auditioning: Boolean,
    /** 当前接得上的音色才可点；在线那一组传 false，点了只弹说明，不假装切换成功。 */
    enabled: Boolean,
    onClick: () -> Unit,
    onAudition: (() -> Unit)?,
) {
    val kindTag = when (profile.kind) {
        VoiceKind.LOCAL -> "离线"
        VoiceKind.ONLINE -> "需 key"
        VoiceKind.SYSTEM -> "系统"
        VoiceKind.CLONE -> "克隆"
    }
    AkListItem(
        icon = null,
        title = profile.safeLabel + if (auditioning) " · 正在试听…" else "",
        description = "$kindTag · ${profile.tagline}" + if (selected) " · 已选中" else "",
        selected = selected,
        onClick = onClick,
    )
    // 试听入口单独一行：AkListItem 只有一个点击区，把「试听」和「选择」塞进同一个
    // 点击里，用户想听第二次就会变成反复选中——听和选是两件事。
    if (onAudition != null) {
        AkListItem(
            icon = Icons.Outlined.VolumeUp,
            title = "试听「${profile.safeLabel}」",
            description = if (auditioning) "正在合成…" else "用一句固定的话念给你听",
            selected = false,
            onClick = onAudition,
        )
    }
}
