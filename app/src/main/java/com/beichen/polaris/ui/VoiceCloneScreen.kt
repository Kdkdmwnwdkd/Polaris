package com.beichen.polaris.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.beichen.polaris.audio.AudioGraph
import com.beichen.polaris.audio.tts.CloneVoiceStore
import com.beichen.polaris.audio.tts.VoiceRecorder
import com.beichen.polaris.audio.tts.VoiceStyleStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 录一句，克隆自己的声音。
 *
 * ## 为什么这一页先说「读这句话」再说「录音」
 *
 * ZipVoice 的参考文本必须与音频**逐字对应**。用户不知道这条规则，就会自由发挥、
 * 或者读一半改口——那样克隆出来的声音会「像但发闷」，而他完全不知道为什么。
 * 所以句子写死在屏幕上，且说明「按原样读，别改字」。
 *
 * ## 为什么录音有 12 秒硬上限
 *
 * 「按一下开始、再按一下停」看着自由，实际最常发生的是一次录三分钟、
 * 后半段全是空气声。参考音频里混进长静音，模型会把这口气学进去，合成时每句都拖尾。
 * 12 秒读完一句话绰绰有余，超时就自动停。
 *
 * ## 为什么录完先让你听一遍，而不是直接保存
 *
 * 麦克风被占用、离太远、环境太吵，这几种情况下录出来的东西「有波形但没用」。
 * 先回放，用户自己就能判断「这能不能代表我的声音」——这比保存完发现克隆不像再回来重录省事。
 */
@Composable
fun VoiceCloneScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speaker = remember { AudioGraph.speaker(context) }

    var phase by remember { mutableStateOf(Phase.IDLE) }
    var level by remember { mutableStateOf(0f) }
    var elapsedMs by remember { mutableStateOf(0) }
    var take by remember { mutableStateOf<VoiceRecorder.Recording?>(null) }
    var savedId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    fun startRecording() {
        if (!hasPermission()) {
            message = "需要麦克风权限才能录你的声音"
            return
        }
        // 先停掉播报：正在出声时开麦，录进去的就是上一句机器音。
        runCatching { speaker.stopForRecording() }
        message = null
        savedId = null
        take = null
        level = 0f
        elapsedMs = 0
        phase = Phase.RECORDING
        scope.launch {
            val r = speaker.recorder().record { level = it }
            phase = Phase.IDLE
            if (r == null) {
                message = "没录到声音。可能是麦克风被别的应用占着，或录得太短"
                return@launch
            }
            if (r.looksSilent()) {
                message = "音量太小了。靠近手机一点，在安静的地方重录一次"
                return@launch
            }
            if (r.durationMs < CloneVoiceStore.MIN_REFERENCE_MS) {
                message = "只录到 ${r.durationMs / 1000.0} 秒，太短了。说完这一句再停"
                return@launch
            }
            take = r
        }
    }

    fun save() {
        val r = take ?: return
        val voice = CloneVoiceStore.save(
            context = context,
            samples = r.samples,
            sampleRate = r.sampleRate,
            refText = REFERENCE_TEXT,
            label = null,
        )
        if (voice == null) {
            message = "保存失败：存储空间不足，或样本不够长"
            return
        }
        VoiceStyleStore.select(context, voice.id)
        savedId = voice.id
        message = "已保存并设为当前音色。接下来会用它念一句，听听像不像"
        // 直接试听：这是「像不像」唯一可靠的判断方式，也顺便验证模型真的能跑。
        scope.launch {
            val text = "你好，我是你自己。以后就由我用这个声音说话了。"
            runCatching { speaker.audition(voice.profile, text) }
        }
    }

    // 页面被关掉（返回键、切走重建）时必须让录音线程停下来：
    // 它是一条 while 循环，没人中止就会一直读到 12 秒上限，
    // 期间麦克风一直被占着，用户回到别的页面会发现「录音被占用」。
    DisposableEffect(Unit) {
        onDispose { runCatching { speaker.stopForRecording() } }
    }

    // 录音期间走一个 100ms 的时钟：AudioRecord 只报电平，不报时间，
    // 而用户需要知道「还有多久自动停」。
    LaunchedEffect(phase) {
        if (phase != Phase.RECORDING) return@LaunchedEffect
        val started = System.currentTimeMillis()
        while (phase == Phase.RECORDING) {
            elapsedMs = (System.currentTimeMillis() - started).toInt()
            delay(100)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else message = "没有麦克风权限，录不了声音"
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
            title = "克隆我的声音",
            subtitle = "读一句话，10 秒；声音只存在这台手机里，不上传",
        )

        AkGroup(
            title = "请按原样读这句话",
            description = "别改字、别跳词。改一个字，克隆出来的声音就会明显发闷",
        ) {
            Text(
                text = REFERENCE_TEXT,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 19.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        AkGroup(
            title = when (phase) {
                Phase.RECORDING -> "正在录音"
                else -> "录音"
            },
            description = null,
            paddedContent = true,
        ) {
            if (phase == Phase.RECORDING) {
                LevelBar(level = level, elapsedMs = elapsedMs, limitMs = speaker.recorder().maxDurationMs)
            } else {
                Text(
                    text = when {
                        take != null -> "录到 ${"%.1f".format(take!!.durationMs / 1000.0)} 秒。听完觉得可以，就保存"
                        savedId != null -> "声音已保存。再录一次会覆盖成新的当前音色"
                        else -> "点开始，读完上面那句就自动停（最长 12 秒）"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (phase) {
                    Phase.RECORDING -> AkOutlinedButton(
                        text = "停止",
                        onClick = { runCatching { speaker.recorder().cancel() } },
                    )
                    else -> AkPrimaryButton(
                        text = if (take == null) "开始录音" else "重录",
                        onClick = {
                            if (hasPermission()) startRecording()
                            else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                    )
                }
                if (take != null && phase != Phase.RECORDING) {
                    AkPrimaryButton(text = "保存为我的音色", onClick = { save() })
                }
            }
        }

        message?.let { note ->
            AkGroup(title = "状态", description = null) {
                AkListItem(icon = null, title = note, onClick = null)
            }
        }

        if (savedId != null && onBack != null) {
            AkGroup(title = "下一步", description = null) {
                AkListItem(
                    icon = null,
                    title = "回到音色页",
                    description = "在那里可以试听、改名或删除这条声音",
                    onClick = onBack,
                )
            }
        }

        AkGroup(
            title = "关于这件事的边界",
            description = null,
        ) {
            AkListItem(
                icon = null,
                title = "只用来克隆你自己的声音",
                description = "克隆别人的声音可能侵犯声音权，《民法典》第 1023 条对声音有明确规定",
                onClick = null,
            )
            AkListItem(
                icon = null,
                title = "参考音频存在哪",
                description = "应用私有目录，卸载即删；不联网、不上传、不给第三方",
                onClick = null,
            )
            AkListItem(
                icon = null,
                title = "为什么合成比别人慢",
                description = "每次都要重新读一遍你的参考音频来定音色，这就是「零样本」的代价",
                onClick = null,
            )
        }
    }
}

@Composable
private fun LevelBar(level: Float, elapsedMs: Int, limitMs: Int) {
    val progress = (elapsedMs.toFloat() / limitMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(level.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Text(
            text = "已录 ${"%.1f".format(elapsedMs / 1000f)}s / ${limitMs / 1000}s · 说完点停止，或者等它自己停",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(Color(0xFF7FB3D5))
            )
        }
    }
}

private enum class Phase { IDLE, RECORDING }

/**
 * 屏幕上写死的那句话。
 *
 * 选择标准：全是常用字、没有生僻读音、句长 5–7 秒、覆盖前后鼻音与四声。
 * 换这句话必须同步 `VoiceCloneScreen` 里的说明——用户读的东西和存进参考文本的
 * 必须是同一个字符串，否则克隆质量会不明原因地下降。
 */
private const val REFERENCE_TEXT = "今天天气不错，我们一起出去走走吧。回来的时候顺便买点水果。"
