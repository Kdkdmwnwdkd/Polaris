package com.beichen.polaris.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.beichen.polaris.core.mcp.server.McpTier
import com.beichen.polaris.mcp.McpHostRuntime
import com.beichen.polaris.mcp.McpHostService

/**
 * 「对外提供」卡片：把手机工具以 MCP 协议给外部 Agent 用。
 *
 * ## 为什么放在 MCP 客户端页，而不是新开一页
 *
 * 用户看到「MCP」只会来这一个地方。拆成两页的代价是：改完客户端配置还要再找
 * 「提供」在哪，找不到就以为没有这个功能。同一页上下两块，方向相反但概念同源。
 *
 * ## 为什么令牌只显示一次
 *
 * 磁盘上只有 SHA-256 摘要（`clients.json`），显示第二次就意味着要么存明文、
 * 要么解密——两条路都比「让用户当场复制走」更糟。所以对话框明确写「关闭后不可再查看」。
 */
@Composable
fun McpHostCard() {
    val context = LocalContext.current
    val status by McpHostRuntime.status.collectAsState()
    var tier by remember { mutableStateOf(McpTier.READONLY) }
    var paired by remember { mutableStateOf<Pair<String, String>?>(null) }

    AkSectionCard(
        title = "对外提供（实验）",
        icon = Icons.Outlined.Settings,
        description = "让电脑上的 Agent（DSH / Claude Code）通过 MCP 调用这台手机的工具。" +
            "默认只读：只看应用列表、界面结构、工作区文件，不能点屏幕、不能写文件。",
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (status.running) "运行中 · 127.0.0.1:${status.port}" else "未启动",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "当前档位可见 ${status.visibleTools} 个工具" +
                        (status.error?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = status.running,
                onCheckedChange = { on ->
                    if (on) {
                        context.startService(McpHostService.intent(context, tier))
                    } else {
                        context.stopService(Intent(context, McpHostService::class.java))
                    }
                },
            )
        }

        Spacer(Modifier.height(AgentSpacing.space3))
        Text(
            "档位",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space2)) {
            McpTier.values().forEach { t ->
                if (t == McpTier.FULL) return@forEach // M1 不开放完全信任档
                OutlinedButton(
                    onClick = { tier = t },
                    enabled = !status.running,
                ) {
                    Text(if (t == tier) "● ${t.label}" else t.label)
                }
            }
        }
        Text(
            if (tier == McpTier.READONLY)
                "只读：6 个工具。写操作与无障碍点击一律拒绝。"
            else
                "标准：+app_launch。其余仍需审批，而本版本没有审批界面，所以会被直接拒绝。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AgentSpacing.space1),
        )

        Spacer(Modifier.height(AgentSpacing.space3))
        OutlinedButton(
            onClick = {
                val result = McpHostRuntime.pair(context, "外部客户端", tier)
                if (result != null) {
                    paired = result.token to result.client.name
                }
            },
        ) {
            Text("生成访问令牌")
        }

        if (status.clients.isNotEmpty()) {
            Spacer(Modifier.height(AgentSpacing.space3))
            Text(
                "已配对 ${status.clients.size} 个",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status.clients.forEach { client ->
                Row(
                    Modifier.fillMaxWidth().padding(top = AgentSpacing.space1),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${client.name} · ${McpTier.fromWire(client.tier).label}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { McpHostRuntime.revoke(context, client.id) }) {
                        Text("撤销")
                    }
                }
            }
        }
    }

    paired?.let { (token, name) ->
        AlertDialog(
            onDismissRequest = { paired = null },
            title = { Text("访问令牌") },
            text = {
                Column {
                    Text("给「$name」配置：", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Text(
                        "host: 127.0.0.1\nport: ${status.port}\ntoken: $token",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(AgentSpacing.space2))
                    SelectionContainer {
                        Text(
                            token,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Text(
                        "关闭后不可再查看（磁盘上只存摘要）。手机与电脑不在同一台设备时，" +
                            "用 adb reverse tcp:${status.port} tcp:${status.port} 把端口转过去。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { paired = null }) { Text("知道了") } },
        )
    }
}
