package com.beichen.polaris.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.beichen.polaris.audio.AudioGraph
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
 * ## 为什么在线那一组是灰的
 *
 * 2026-11-15 的结论：能免费白嫖的那批神经音色走的是微软网页版朗读接口，
 * 该接口已经上了防滥用校验，非官方客户端会被 403。要么用户自备服务端 key，
 * 要么就不该承诺「点开就能听」。
 *
 * 所以这里把 6 个音色**列出来但标明未启用**——藏起来会让用户以为「说好的 6 个音色没了」；
 * 假装能用则会变成「点了没声音」，那更糟。等接上带 key 的官方接口，这一组直接点亮。
 */
@Composable
fun VoiceStyleScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speaker = remember { AudioGraph.speaker(context) }
    // 直接读存储而不是 new 一个 VoiceSpeech：那里会起播放线程、持有本地引擎，
    // 为了显示一行「已选中」付这个代价不值。
    var selected by remember {
        mutableStateOf(
            VoiceCatalog.byId(VoiceStyleStore.selectedId(context))
                ?: VoiceCatalog.require(VoiceCatalog.DEFAULT_ID)
        )
    }
    var auditioning by remember { mutableStateOf<String?>(null) }

    fun audition(profile: VoiceProfile) {
        if (auditioning != null) return
        auditioning = profile.id
        scope.launch {
            val text = "你好，我是${profile.safeLabel}。今天想聊点什么？"
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
            description = "这批音色需要服务端 key，当前未启用；本地那一档不受影响",
        ) {
            VoiceCatalog.online.forEach { profile ->
                VoiceRow(
                    profile = profile,
                    selected = selected.id == profile.id,
                    auditioning = false,
                    enabled = false,
                    onClick = {
                        Toast.makeText(
                            context,
                            "在线音色还没接上服务端 key，暂时发不出声；本地 22kHz 现在就能用",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                    onAudition = null,
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
                    description = "发生一次就够了；如果每次都这样，多半是模型没展开成功",
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
