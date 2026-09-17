/**
 * RoundPickerDialog.kt - 轮次选择弹窗（拍摄界面的轮次入口）
 *
 * 现场情形：第 2 轮拍到一半被临时叫去拍第 3 轮，忙完要回到第 2 轮接着拍。
 * 所以轮次必须能跳到任意值，而且入口要**在拍摄界面上**——要退出相机才能切轮次，
 * 那这个功能在实际节奏里就是不可用的。
 *
 * 各轮进度互相独立：跳过去再跳回来，原来那轮的进度还在。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.presentation.common.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qihao.filtercamera.domain.model.BatchConfig

/**
 * 轮次选择弹窗
 *
 * 列出已用过的轮次（带各自进度）+ 一个手输框，可以跳到任意轮次。
 * 各轮进度互相独立：第 2 轮拍一半被打断、跳去第 3 轮，回来时第 2 轮还在原处。
 */
@Composable
fun RoundPickerDialog(
    target: BatchConfig,
    onSelect: (Int) -> Unit,
    onResetToFirst: () -> Unit,
    onDismiss: () -> Unit
) {
    var customRound by remember { mutableStateOf("") }
    val current = target.round.coerceAtLeast(1)
    val total = target.effectiveNameList.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换轮次") },
        text = {
            Column {
                Text(
                    text = "当前第 $current 轮。点某一轮直接跳过去，那一轮已拍的进度会原样保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                target.usedRounds.forEach { round ->
                    val shot = target.roundShotCountOf(round)
                    val isCurrent = round == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(round) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "第 $round 轮",
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = when {
                                isCurrent -> "当前 · 已拍 $shot/$total"
                                shot > 0 -> "已拍 $shot/$total"
                                else -> "未拍"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customRound,
                    onValueChange = { customRound = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("跳到其它轮次") },
                    placeholder = { Text("直接输入轮次号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { customRound.toIntOrNull()?.let(onSelect) },
                enabled = (customRound.toIntOrNull() ?: 0) > 0
            ) { Text("跳过去") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onResetToFirst) { Text("清空并回第1轮") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
