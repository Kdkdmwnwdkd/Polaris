package com.beichen.polaris.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.beichen.polaris.core.qr.ConfigQr
import com.beichen.polaris.core.qr.QrCode

/**
 * W-RT8：配置二维码的导出 / 导入（换机迁移）。
 *
 * 为什么单独一个文件：这三块（入口、导出弹窗、导入弹窗）加起来 140 行，
 * 塞进 ModelConfigScreen 会把那个文件推到 2500 行——它已经有四个页签与
 * 十余个编辑控件，再往里堆，下次改配置页的人得先滚过一段二维码绘制代码。
 * 这里只依赖 [ConfigQr] 与 [QrCode] 两个纯函数，不碰 vm、不碰配置状态，
 * 边界干净：所有状态都由调用方持有并传进来。
 *
 * 两条纪律写在类型上而不是注释里：
 *  - 导出的 [ConfigQr.ConfigPayload] 里**没有 Key 字段**，不是「记得别放」；
 *  - 导入只回填端点 / 模型 / 供应商，Key 由用户自己填（[ConfigQr.import] 解不出 Key）。
 */

/** 「配置二维码」入口卡片：两个按钮，交给调用方决定弹哪个。 */
@Composable
fun QrConfigSection(onExport: () -> Unit, onImport: () -> Unit) {
    AkGroup(
        title = "配置二维码",
        description = "导出的是上面正在编辑的端点与模型（未保存也算），码里不含 Key；" +
            "导入只回填端点、模型、供应商，Key 请自行填写",
        paddedContent = true
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(AgentSpacing.space3)) {
            AkTextButton(text = "导出二维码", onClick = onExport)
            AkTextButton(text = "导入配置", onClick = onImport)
        }
    }
}

/**
 * 导出弹窗：把当前编辑态的端点 / 模型 / 供应商编成码。
 *
 * 超容量时整块失败、不出半张码：截断的码扫出来是残缺配置，比扫不出更坑
 * （用户会以为导入成功了）。文案直接给可执行的动作，不说「出错了」。
 */
@Composable
fun QrExportDialog(
    providerId: String,
    baseUrl: String,
    model: String,
    profileName: String,
    onDismiss: () -> Unit
) {
    val payload = ConfigQr.export(providerId, baseUrl, model, profileName)
    val matrix = try {
        QrCode.encode(payload)
    } catch (_: QrCode.QrTooLongException) {
        null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置二维码", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (matrix != null) {
                    QrImage(matrix = matrix, modifier = Modifier.size(240.dp))
                    Spacer(Modifier.height(AgentSpacing.space2))
                    Text(
                        text = "端点、模型、供应商已编码，不含 Key。对方扫码后需自己填 Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "配置太长，出不了码——请缩短模型名或端点后再试（超长不会截断生成）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/**
 * 导入弹窗：粘贴扫码结果，解析成功才回调 [onApply]。
 *
 * Key 一律不动：码是公开品（会被拍照转发），把它当 Key 的传输通道，
 * 等于把密钥写进一张会被贴到群里的图片。回填后仍走既有「保存」按钮，
 * 用户看得到自己保存的到底是什么。
 */
@Composable
fun QrImportDialog(onDismiss: () -> Unit, onApply: (ConfigQr.ConfigPayload) -> Unit) {
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入配置", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    text = "粘贴扫码得到的配置文本（beichen://config 开头）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(AgentSpacing.space2))
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it; importError = null },
                    placeholder = { Text("beichen://config?...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (importError != null) {
                    Spacer(Modifier.height(AgentSpacing.space1))
                    Text(
                        text = importError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = ConfigQr.import(importText)
                if (parsed == null) {
                    importError = "格式不对：这不是本 App 的配置码"
                } else {
                    onApply(parsed)
                    onDismiss()
                }
            }) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 二维码渲染：模块方阵 → 白底黑块 Canvas。
 *
 * 静区（quiet zone）由外层 padding 提供，不要在这里画边：QR 规范要求四周
 * 留 4 个模块宽的白边，扫描器靠它定位；贴在深色背景上时不加白底会扫不出。
 */
@Composable
private fun QrImage(matrix: QrCode.Matrix, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp)) {
        val cell = size.width / matrix.size
        for (r in 0 until matrix.size) for (c in 0 until matrix.size) {
            if (matrix[r, c]) {
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(c * cell, r * cell),
                    size = Size(cell, cell)
                )
            }
        }
    }
}
