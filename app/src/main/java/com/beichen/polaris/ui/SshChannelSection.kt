package com.beichen.polaris.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beichen.polaris.core.terminal.backend.SshProfile
import com.beichen.polaris.ui.contract.PermissionStatusContract

/**
 * W-RT5：SSH 远程机器分区（权限中心页）。
 *
 * 与无线调试分区（[AdbChannelSection]）同一套版式。v1 是**单档案**：主机 / 端口 /
 * 用户名 / 私钥路径（可选）。两条纪律在 UI 层同样成立：
 *  - **仅密钥登录**：密码交互会卡死无 PTY 的常驻会话，也不让密码进任何配置——
 *    档案里根本没有密码字段，目标机器需先把本设备公钥加进 `authorized_keys`；
 *  - **不静默顶替**：SSH 不参与 shell 的自动降级，只有模型显式用 `identity=ssh`
 *    才会连远端机器。
 *
 * 状态刷新靠 `tick` 自增（保存后 +1）而不是轮询：探测要碰文件系统、连通性要发网络请求，
 * 权限页常驻轮询不值当，与 [AdbChannelSection] 的处理一致。
 */
@Composable
internal fun SshChannelSection(vm: PermissionStatusContract) {
    var showEdit by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val profile = remember(tick) { vm.sshSavedProfile() }

    AkGroup(
        title = "SSH 远程机器",
        description = "配置后 Agent 可用 identity=ssh 在远端机器上开持久 shell；" +
            "仅支持密钥登录，远端不会进本机能力的自动降级链"
    ) {
        AkListItem(
            icon = Icons.Outlined.Computer,
            title = if (profile == null) "连接设置" else "连接设置（已保存）",
            description = if (profile == null) {
                "未配置：填主机 / 端口 / 用户名，目标机器需把本设备公钥加入 authorized_keys"
            } else {
                "当前目标 ${profile.user}@${profile.host}:${profile.port}" +
                    if (profile.keyPath.isNotBlank()) "（密钥 ${profile.keyPath}）" else "（默认密钥）"
            },
            onClick = { showEdit = true },
            trailing = {
                Text(
                    text = if (profile == null) "填写" else "修改",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        )
        if (profile == null) {
            // 未配置时才讲「ssh 从哪来」：已经配好的人不需要这段，配不上的人正好缺这段。
            AkDivider(Modifier.padding(start = AgentSpacing.space4))
            AkListItem(
                icon = null,
                title = "ssh 命令从哪来",
                description = "Android 不预装 ssh：解压容器 rootfs 后在容器里装 openssh" +
                    "（apk add openssh-client / pacman -S openssh），或放入自带静态编译的 ssh 二进制；" +
                    "就绪后这里会自动检测到",
                onClick = null
            )
        }
    }

    if (showEdit) {
        SshProfileEditDialog(
            initial = profile,
            onDismiss = { showEdit = false },
            onSave = { host, port, user, keyPath ->
                // 返回 null = 保存成功；非 null 是给用户看的理由，弹窗就地显示、不关闭
                val error = vm.saveSshProfile(host, port, user, keyPath)
                if (error == null) {
                    tick++
                    showEdit = false
                }
                error
            }
        )
    }
}

/** SSH 档案编辑弹窗：四字段 + 校验错误就地展示（保存失败不关弹窗，省得重填）。 */
@Composable
private fun SshProfileEditDialog(
    initial: SshProfile?,
    onDismiss: () -> Unit,
    onSave: (host: String, port: String, user: String, keyPath: String) -> String?
) {
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember { mutableStateOf(initial?.port?.toString().orEmpty()) }
    var user by remember { mutableStateOf(initial?.user.orEmpty()) }
    var keyPath by remember { mutableStateOf(initial?.keyPath.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SSH 连接") },
        text = {
            Column {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("主机地址") },
                    placeholder = { Text("192.168.1.10 或 host.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口（留空 = 22）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyPath,
                    onValueChange = { keyPath = it },
                    label = { Text("私钥路径（可选）") },
                    placeholder = { Text("留空用默认 ~/.ssh/id_*") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onSave(host, port, user, keyPath) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
