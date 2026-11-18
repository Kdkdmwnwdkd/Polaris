package com.beichen.polaris.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beichen.polaris.core.mcp.McpClient
import com.beichen.polaris.core.mcp.McpServerConfig
import com.beichen.polaris.core.mcp.McpServerStore
import com.beichen.polaris.core.mcp.McpServerInfo
import com.beichen.polaris.core.mcp.McpTool
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/**
 * MCP 客户端页（调研文档「第 7 步：MCP 客户端」直译）。
 *
 * 连接态驱动：未连接 → 输入服务器 URL 握手（initialize）→ 已连接拉取
 * tools/list → 点选工具填 JSON 参数 tools/call → 展示 result。
 *
 * 传输复用应用内 OkHttp + kotlinx.serialization（[McpClient]），零新增依赖。
 * 所有网络调用在 Dispatchers.IO，失败以 [McpStatus.Error] 友好呈现，不崩溃。
 *
 * @param onServersChanged 保存的服务器**被增/删/启停**后回调。R-07：本页只改
 *   SharedPreferences，工具表在 ChatViewModel 里；不回调就等于「页面关了、对话里还在调
 *   一台已经停用的服务器」。默认空实现是为了本页能单独预览/测试。
 */
@Composable
fun McpScreen(onServersChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<McpStatus>(McpStatus.Idle) }
    var tools by remember { mutableStateOf<List<McpTool>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var argsJson by remember { mutableStateOf("{}") }
    var calling by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    // W-14：会话级保存的 MCP 服务器（对话启动时会自动连接这些 enabled 项）
    val serverStore = remember { McpServerStore(context) }
    var savedServers by remember { mutableStateOf(serverStore.load()) }

    fun saveCurrent() {
        val u = url.trim()
        if (u.isEmpty()) return
        val host = runCatching { java.net.URI(u).host }.getOrNull().orEmpty().ifBlank { "MCP" }
        val cfg = McpServerConfig(
            id = host + "_" + Integer.toHexString(u.hashCode()),
            name = host,
            url = u,
            enabled = true
        )
        savedServers = serverStore.upsert(cfg)
        // R-07：新服务器立刻接入对话（否则要等下次冷启动才生效）
        onServersChanged()
        Toast.makeText(context, "已保存，对话中可自动接入其工具", Toast.LENGTH_SHORT).show()
    }

    fun connect() {
        val u = url.trim()
        if (u.isEmpty()) {
            Toast.makeText(context, "请输入服务器 URL", Toast.LENGTH_SHORT).show()
            return
        }
        status = McpStatus.Connecting
        tools = emptyList()
        selected = null
        resultText = null
        scope.launch {
            val client = McpClient(u)
            client.initialize()
                .onSuccess { info ->
                    status = McpStatus.Connected(info)
                    client.listTools().onSuccess { list ->
                        tools = list
                    }.onFailure { e ->
                        Toast.makeText(context, "tools/list 失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure { e ->
                    status = McpStatus.Error(e.message ?: "握手失败")
                }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(
            title = AppDest.MCP.title,
            actionIcon = if (status is McpStatus.Connected) Icons.AutoMirrored.Outlined.ArrowBack else null,
            actionDescription = "断开连接",
            onAction = {
                status = McpStatus.Idle
                tools = emptyList()
                selected = null
                resultText = null
            }
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberPersistentScrollState())
                .padding(AgentSpacing.space5)
        ) {
            // ——— 对外提供（M1 只读桥）———
            McpHostCard()
            Spacer(Modifier.height(AgentSpacing.space4))

            // ——— 服务器卡：URL 输入 + 连接/断开 + 连接态三显 ———
            AkSectionCard(
                title = "MCP 服务器",
                icon = Icons.Outlined.Cloud,
                description = "Model Context Protocol：握手 initialize → 列举工具 → 调用工具。"
            ) {
                McpUrlInput(
                    value = url,
                    onValueChange = { url = it },
                    enabled = status !is McpStatus.Connected,
                    onSubmit = { connect() }
                )
                Spacer(Modifier.height(AgentSpacing.space3))
                when (val s = status) {
                    is McpStatus.Idle -> Text(
                        "未连接。输入以 http(s):// 开头的 MCP 服务端点后点右侧连接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    is McpStatus.Connecting -> Text(
                        "正在握手…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    is McpStatus.Connected -> Column {
                        Text(
                            "已连接：${s.info.name} v${s.info.version} · 共 ${tools.size} 个工具",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(AgentSpacing.space2))
                        AkTextButton(text = "保存为常用服务器（对话中自动接入）", onClick = { saveCurrent() })
                    }

                    is McpStatus.Error -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ——— 我的服务器（W-14：保存后对话启动时自动接入其工具）———
            Spacer(Modifier.height(AgentSpacing.space4))
            AkSectionCard(
                title = "我的服务器",
                icon = Icons.Outlined.Cloud,
                description = "启用的服务器会在 App 启动时自动连接，工具直接出现在对话里（每台最多 30 个，合计 60 个）。"
            ) {
                if (savedServers.isEmpty()) {
                    Text(
                        "还没有保存的服务器。连接成功后点「保存为常用服务器」即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    savedServers.forEach { cfg ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = AgentSpacing.space2),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).clickable {
                                url = cfg.url
                                connect()
                            }) {
                                Text(cfg.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    cfg.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Switch(
                                checked = cfg.enabled,
                                onCheckedChange = { on ->
                                    savedServers = serverStore.setEnabled(cfg.id, on)
                                    // R-07：关掉即撤销它名下的工具，不能等下次启动
                                    onServersChanged()
                                }
                            )
                            IconButton(onClick = {
                                savedServers = serverStore.remove(cfg.id)
                                onServersChanged()
                            }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ——— 工具列表（连接后可见）———
            if (status is McpStatus.Connected) {
                Spacer(Modifier.height(AgentSpacing.space4))
                AkSectionCard(
                    title = "工具",
                    icon = Icons.Outlined.Build,
                    description = "点击工具填写参数并调用。"
                ) {
                    if (tools.isEmpty()) {
                        Text(
                            "该服务器未暴露工具。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        tools.forEach { tool ->
                            ToolRow(
                                tool = tool,
                                selected = selected == tool.name,
                                onClick = {
                                    selected = if (selected == tool.name) null else tool.name
                                    resultText = null
                                }
                            )
                            Spacer(Modifier.height(AgentSpacing.space2))
                        }
                    }
                }
            }

            // ——— 调用面板（选中工具后展开）———
            val open = tools.firstOrNull { it.name == selected }
            if (open != null) {
                Spacer(Modifier.height(AgentSpacing.space4))
                AkSectionCard(
                    title = "调用 ${open.name}",
                    icon = Icons.Outlined.PlayArrow,
                    description = open.description.ifBlank { "该工具未提供描述" }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(AgentSpacing.space3)
                    ) {
                        BasicTextField(
                            value = argsJson,
                            onValueChange = { argsJson = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = McpMonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.TopStart) {
                                    if (argsJson.isEmpty()) {
                                        Text("{}", style = McpMonoStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    inner()
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space3))
                    Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
                        AkPrimaryButton(
                            text = if (calling) "调用中…" else "调用",
                            onClick = {
                                calling = true
                                resultText = null
                                scope.launch {
                                    val client = McpClient(url.trim())
                                    // 已连接态直接调用；客户端无会话态，无需重复握手
                                    client.callTool(open.name, argsJson)
                                        .onSuccess { r -> resultText = r.toString() }
                                        .onFailure { e ->
                                            resultText = "错误：${e.message}"
                                        }
                                    calling = false
                                }
                            },
                            enabled = !calling,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (resultText != null) {
                        Spacer(Modifier.height(AgentSpacing.space3))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(AgentSpacing.space3)
                        ) {
                            Text(
                                resultText!!,
                                style = McpMonoStyle.copy(color = MaterialTheme.colorScheme.onSurface)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 连接态机：Idle / Connecting / Connected(serverInfo) / Error(message)。 */
private sealed interface McpStatus {
    data object Idle : McpStatus
    data object Connecting : McpStatus
    data class Connected(val info: McpServerInfo) : McpStatus
    data class Error(val message: String) : McpStatus
}

/** 工具行：名称 + 描述（1 行省略），选中态 primary 高亮。 */
@Composable
private fun ToolRow(tool: McpTool, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AgentSpacing.space3, vertical = AgentSpacing.space3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Build,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(AgentSpacing.space3))
        Column(Modifier.weight(1f)) {
            Text(
                tool.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (tool.description.isNotBlank()) {
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** URL 单行输入 + 右侧连接按钮（连接后输入禁用，按钮转为断开语义由顶栏承担）。 */
@Composable
private fun McpUrlInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSubmit: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = AgentSpacing.space4, vertical = AgentSpacing.space2),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = McpMonoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                enabled = enabled,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text("http://10.0.2.2:8080/mcp", style = McpMonoStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.width(AgentSpacing.space2))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (enabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onSubmit)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = "连接",
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** MCP 页等宽文本样式（noFontPadding，与编辑器同语汇）。 */
private val McpMonoStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    textAlign = TextAlign.Start
)
