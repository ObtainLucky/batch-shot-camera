/**
 * BatchFormFields.kt - 批次表单组件（相机页与设置页共用）
 *
 * 负责「批次名 / 目录名 / 文件名前缀 / 起始序号 / 序号位数 / 日期子目录」的输入，
 * 并实时预览最终会生成的文件名与目录，让用户在拍之前就知道结果长什么样。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.presentation.common.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.qihao.filtercamera.domain.model.BatchConfig
import com.qihao.filtercamera.domain.model.NamingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 批次表单字段
 *
 * 所有输入都以 String 形式持有，交由调用方决定何时解析 —— 这样用户清空输入框时
 * 不会因为解析失败而被打回默认值。
 */
@Composable
fun BatchFormFields(
    name: String,
    onNameChange: (String) -> Unit,
    dirName: String,
    onDirNameChange: (String) -> Unit,
    prefix: String,
    onPrefixChange: (String) -> Unit,
    startIndex: String,
    onStartIndexChange: (String) -> Unit,
    indexWidth: String,
    onIndexWidthChange: (String) -> Unit,
    dateSubDir: Boolean,
    onDateSubDirChange: (Boolean) -> Unit,
    namingMode: NamingMode,
    onNamingModeChange: (NamingMode) -> Unit,
    nameListText: String,
    onNameListTextChange: (String) -> Unit,
    note: String = "",
    onNoteChange: (String) -> Unit = {},
    /** 可复制的来源批次（从已有批次复制名字列表） */
    copyableBatches: List<BatchConfig> = emptyList(),
    modifier: Modifier = Modifier
) {
    // 只在用户已经输入内容后才提示错误，避免一进表单就满屏红字
    val dirError = if (dirName.isEmpty()) null else BatchConfig.validateDirName(dirName)
    val prefixError = if (prefix.isEmpty()) null else BatchConfig.validatePrefix(prefix)

    Column(modifier = modifier) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("批次名") },
            placeholder = { Text("A批-8月货") },
            singleLine = true,
            isError = name.isBlank(),
            supportingText = if (name.isBlank()) {
                { Text("批次名不能为空") }
            } else null,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = dirName,
            onValueChange = onDirNameChange,
            label = { Text("相册目录名") },
            placeholder = { Text("工作/设备上架") },
            singleLine = true,
            isError = dirError != null,
            supportingText = {
                Text(dirError ?: "支持多级目录，照片保存到 Pictures/$dirName/；可用 / 分层")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = prefix,
            onValueChange = onPrefixChange,
            label = { Text("文件名前缀") },
            placeholder = { Text("BA") },
            singleLine = true,
            isError = prefixError != null,
            supportingText = if (prefixError != null) {
                { Text(prefixError) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 命名模式
        Text(
            text = "命名方式",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        NamingMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = namingMode == mode,
                        role = Role.RadioButton,
                        onClick = { onNamingModeChange(mode) }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = namingMode == mode,
                    onClick = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = mode.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 工作模式：名字列表
        if (namingMode == NamingMode.WORK) {
            Spacer(modifier = Modifier.height(8.dp))
            val parsedNames = remember(nameListText) { BatchConfig.parseNameList(nameListText) }
            val duplicated = parsedNames.size != parsedNames.distinct().size
            val clipboard = LocalClipboardManager.current
            var showCopySource by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = nameListText,
                onValueChange = onNameListTextChange,
                label = { Text("名字列表（按拍摄顺序）") },
                placeholder = { Text("拖车\nsn号") },
                minLines = 3,
                maxLines = 8,
                isError = parsedNames.isEmpty() || duplicated,
                supportingText = {
                    when {
                        parsedNames.isEmpty() ->
                            Text("至少填一个名字，每行一个", color = MaterialTheme.colorScheme.error)
                        duplicated ->
                            Text(
                                "有重复的名字（共 ${parsedNames.size} 个），重复的会生成同名文件",
                                color = MaterialTheme.colorScheme.error
                            )
                        else -> Text("每行一个名字，共 ${parsedNames.size} 个；按顺序逐张取用")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 复用与备份：从已有批次复制 / 复制到剪贴板
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val sources = copyableBatches.filter {
                    it.effectiveNameList.isNotEmpty() && it.namingMode == NamingMode.WORK
                }
                OutlinedButton(
                    onClick = { showCopySource = true },
                    enabled = sources.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("从已有批次复制", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(BatchConfig.formatNameList(parsedNames)))
                    },
                    enabled = parsedNames.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("复制到剪贴板", fontSize = 12.sp)
                }
            }

            if (showCopySource) {
                val sources = copyableBatches.filter {
                    it.effectiveNameList.isNotEmpty() && it.namingMode == NamingMode.WORK
                }
                AlertDialog(
                    onDismissRequest = { showCopySource = false },
                    title = { Text("从哪个批次复制名字列表？") },
                    text = {
                        Column {
                            Text(
                                text = "将覆盖当前填写的内容（共 ${parsedNames.size} 个名字）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            sources.forEach { source ->
                                Text(
                                    text = "${source.name}（${source.effectiveNameList.size} 个名字）",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onNameListTextChange(
                                                BatchConfig.formatNameList(source.effectiveNameList)
                                            )
                                            showCopySource = false
                                        }
                                        .padding(vertical = 10.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showCopySource = false }) { Text("取消") }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 序号相关设置仅序号模式需要
        if (namingMode == NamingMode.SEQUENCE) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = startIndex,
                onValueChange = { input ->
                    onStartIndexChange(input.filter { it.isDigit() }.take(6))    // 只允许数字
                },
                label = { Text("起始序号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )

            OutlinedTextField(
                value = indexWidth,
                onValueChange = { input ->
                    onIndexWidthChange(input.filter { it.isDigit() }.take(1))    // 单位数
                },
                label = { Text("序号位数") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 本批次备注（水印里的「备注」行，优先于设置页的全局备注）
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            label = { Text("本批次备注") },
            placeholder = { Text("如：段嘉轩 13297470239") },
            supportingText = {
                Text("留空则用水印设置里的全局备注；填了则本批次优先")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 日期子目录开关
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "按日期分子目录", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "开启后每天一个子目录，如 BatchA/2026-09-16/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = dateSubDir, onCheckedChange = onDateSubDirChange)
        }

        Spacer(modifier = Modifier.height(8.dp))

        BatchPreviewLine(
            dirName = dirName,
            prefix = prefix,
            startIndex = startIndex,
            indexWidth = indexWidth,
            dateSubDir = dateSubDir,
            namingMode = namingMode,
            nameListText = nameListText
        )
    }
}

/**
 * 命名规则实时预览
 *
 * 展示"下一张会叫什么、存到哪"，这是批次拍摄体验里最关键的一句反馈。
 */
@Composable
fun BatchPreviewLine(
    dirName: String,
    prefix: String,
    startIndex: String,
    indexWidth: String,
    dateSubDir: Boolean,
    namingMode: NamingMode = NamingMode.SEQUENCE,
    nameListText: String = "",
    modifier: Modifier = Modifier
) {
    // 日期在整场拍摄中不会变，记住一次即可
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }

    val safeDir = BatchConfig.sanitizeDirName(dirName).ifEmpty { BatchConfig.DEFAULT_DIR_NAME }
    val safePrefix = BatchConfig.sanitizePrefix(prefix).ifEmpty { BatchConfig.DEFAULT_PREFIX }
    val start = startIndex.toIntOrNull()?.coerceAtLeast(0) ?: BatchConfig.DEFAULT_START_INDEX
    val width = indexWidth.toIntOrNull()
        ?.coerceIn(BatchConfig.MIN_INDEX_WIDTH, BatchConfig.MAX_INDEX_WIDTH)
        ?: BatchConfig.DEFAULT_INDEX_WIDTH

    // 工作模式预览第一个名字，序号模式预览起始号
    val names = BatchConfig.parseNameList(nameListText)
    val previewFile = if (namingMode == NamingMode.WORK) {
        val first = names.firstOrNull() ?: "（未填名字）"
        if (safePrefix.isEmpty()) "$first.${BatchConfig.PHOTO_EXTENSION}"
        else "${safePrefix}_$first.${BatchConfig.PHOTO_EXTENSION}"
    } else {
        "${safePrefix}_${start.toString().padStart(width, '0')}.${BatchConfig.PHOTO_EXTENSION}"
    }
    // 与真实落盘顺序保持一致：目录 / 日期 / 轮次
    val previewDir = buildString {
        append("Pictures/").append(safeDir).append("/")
        if (dateSubDir) append(today).append("/")
        if (namingMode == NamingMode.WORK && names.isNotEmpty()) append("第1轮/")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Text(
            text = if (namingMode == NamingMode.WORK) {
                "将生成：$previewFile（第 1 个，共 ${names.size} 个名字）"
            } else {
                "将生成：$previewFile"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "保存到：$previewDir",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 带标签的只读信息行（批次管理页展示用）
 */
@Composable
fun BatchInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}
