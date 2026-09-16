/**
 * BatchManageScreen.kt - 批次管理页
 *
 * 批次的完整管理入口（从设置页进入）：
 * - 列表展示所有批次（名称 / 目录 / 前缀 / 已拍张数 / 下一张文件名）
 * - 新建、编辑、删除、设为当前批次、重置序号
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qihao.filtercamera.domain.model.BatchConfig
import com.qihao.filtercamera.domain.model.NamingMode
import com.qihao.filtercamera.presentation.common.components.BatchFormFields
import com.qihao.filtercamera.presentation.common.components.BatchInfoRow

/**
 * 批次管理页
 *
 * @param onNavigateBack 返回回调
 * @param viewModel 批次管理ViewModel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchManageScreen(
    onNavigateBack: () -> Unit,
    viewModel: BatchManageViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val batches by viewModel.batches.collectAsStateWithLifecycle()
    val currentBatchId by viewModel.currentBatchId.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // 一次性提示（成功/失败）用 Snackbar 呈现
    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    // 表单打开时隐藏新建按钮，避免"表单上再叠一层表单"
    val isFormOpen = uiState.isCreating || uiState.editingBatch != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isFormOpen) "编辑批次" else "批次管理") },
                navigationIcon = {
                    IconButton(onClick = { if (isFormOpen) viewModel.dismissForm() else onNavigateBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (isFormOpen) "返回列表" else "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!isFormOpen) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::startCreate,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("新建批次") }
                )
            }
        }
    ) { paddingValues ->
        when {
            // 编辑已有批次
            uiState.editingBatch != null -> {
                val target = uiState.editingBatch!!
                BatchEditForm(
                    initial = target,
                    existingBatches = batches,
                    submitLabel = "保存",
                    onCancel = viewModel::dismissForm,
                    onSubmit = { name, dirName, prefix, startIndex, indexWidth, dateSubDir, mode, names, noteText ->
                        viewModel.updateBatch(
                            batch = target,
                            name = name,
                            dirName = dirName,
                            namePrefix = prefix,
                            startIndex = startIndex,
                            indexWidth = indexWidth,
                            dateSubDir = dateSubDir,
                            namingMode = mode,
                            nameList = names,
                            note = noteText
                        )
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }

            // 新建批次
            uiState.isCreating -> {
                BatchEditForm(
                    initial = null,
                    existingBatches = batches,
                    submitLabel = "创建",
                    onCancel = viewModel::dismissForm,
                    onSubmit = { name, dirName, prefix, startIndex, indexWidth, dateSubDir, mode, names, noteText ->
                        viewModel.createBatch(
                            name = name,
                            dirName = dirName,
                            namePrefix = prefix,
                            startIndex = startIndex,
                            indexWidth = indexWidth,
                            dateSubDir = dateSubDir,
                            namingMode = mode,
                            nameList = names,
                            note = noteText
                        )
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }

            // 批次列表
            batches.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "还没有批次",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "新建批次后，相机页顶部就能选它来按序号拍摄",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 不使用批次
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentBatchId == null) {
                                    "当前：不使用批次（默认命名）"
                                } else {
                                    "当前批次已高亮显示"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (currentBatchId != null) {
                                TextButton(onClick = viewModel::clearSelection) {
                                    Text("取消选择")
                                }
                            }
                        }
                    }

                    items(items = batches, key = { it.id }) { batch ->
                        BatchCard(
                            batch = batch,
                            isCurrent = batch.id == currentBatchId,
                            onSelect = { viewModel.selectBatch(batch.id) },
                            onEdit = { viewModel.startEdit(batch) },
                            onResetCounter = { viewModel.resetCounter(batch) },
                            onNewRound = { viewModel.requestNewRound(batch) },
                            onDelete = { viewModel.requestDelete(batch) }
                        )
                    }
                }
            }
        }
    }

    // 新一轮确认（会重置名字指针，先问一下）
    uiState.newRoundTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::cancelNewRound,
            title = { Text("开始新一轮？") },
            text = {
                Text(
                    "「${target.name}」当前是第 ${target.round} 轮，" +
                        "将进入第 ${target.round + 1} 轮。\n\n" +
                        "名字列表从第一个重新开始，文件会保存到 " +
                        "Pictures/${target.safeDirName}/第${target.round + 1}轮/，" +
                        "与上一轮的文件互不冲突。"
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmNewRound) { Text("开始新一轮") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelNewRound) { Text("取消") }
            }
        )
    }

    // 删除确认
    uiState.deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("删除批次「${target.name}」？") },
            text = {
                Text(
                    if (target.counter > 0) {
                        "该批次已拍 ${target.counter} 张。删除只移除批次定义，" +
                            "已存进相册的照片不受影响。"
                    } else {
                        "删除只移除批次定义，不会影响相册里的任何照片。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 单个批次卡片
 */
@Composable
private fun BatchCard(
    batch: BatchConfig,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onResetCounter: () -> Unit,
    onNewRound: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isCurrent) Icons.Default.CheckCircle else Icons.Default.Folder,
                    contentDescription = null,
                    tint = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = batch.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (isCurrent) {
                    Text(
                        text = "当前",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            BatchInfoRow(label = "下一张", value = batch.nextFileName())
            BatchInfoRow(
                label = "已拍",
                value = batch.workProgressLabel ?: "${batch.counter} 张"
            )
            if (batch.usesRoundSubDir) {
                BatchInfoRow(
                    label = "轮次",
                    value = "第 ${batch.round} 轮（拍完自动进入第 ${batch.round + 1} 轮）"
                )
            }

            BatchInfoRow(
                label = "保存到",
                value = "Pictures/" + batch.relativeSubPath(
                    dateStamp = if (batch.dateSubDir) "yyyy-MM-dd" else null
                ) + "/"
            )
            BatchInfoRow(
                label = "规则",
                value = "前缀 ${batch.namePrefix} · 起始 ${batch.startIndex} · ${batch.indexWidth} 位"
            )

            // 名字列表用完时会静默回落到序号命名，这里必须说出来，
            // 否则用户只会看到"填了名字却没按名字命名"而不知道原因
            if (batch.isNameListExhausted) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "第 ${batch.round} 轮已按顺序拍完（共 ${batch.effectiveNameList.size} 个名字）。" +
                        "继续拍会自动进入第 ${batch.round + 1} 轮，" +
                        "文件保存到 ${batch.roundDirName} 的同级新目录，不与本轮冲突。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (batch.isDirNameSanitized) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "目录名含非法字符，实际使用 ${batch.safeDirName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // 操作行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isCurrent) {
                    OutlinedButton(onClick = onSelect) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("设为当前")
                    }
                } else {
                    OutlinedButton(onClick = onSelect, enabled = false) {
                        Text("使用中")
                    }
                }

                // 工作模式才有"新一轮"的概念（同一套名字重拍一遍）
                if (batch.usesRoundSubDir) {
                    OutlinedButton(onClick = onNewRound) {
                        Text("新一轮")
                    }
                }

                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onResetCounter) {
                    Icon(Icons.Default.Restore, contentDescription = "重置序号")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 计算下一张的文件名预览（仅用于表单提示）
 */
private fun namePreview(
    prefix: String,
    namingMode: NamingMode,
    names: List<String>
): String {
    val safePrefix = BatchConfig.sanitizePrefix(prefix)
    return if (namingMode == NamingMode.WORK) {
        val first = names.firstOrNull() ?: "（未填名字）"
        if (safePrefix.isEmpty()) "$first.jpg" else "${safePrefix}_$first.jpg"
    } else {
        if (safePrefix.isEmpty()) "001.jpg" else "${safePrefix}_001.jpg"
    }
}

/**
 * 批次新建/编辑表单
 *
 * @param initial 待编辑批次；为 null 表示新建
 * @param existingBatches 已有批次（用于冲突校验与命名建议）
 * @param submitLabel 提交按钮文案
 * @param onCancel 取消回调
 * @param onSubmit 提交回调
 */
@Composable
private fun BatchEditForm(
    initial: BatchConfig?,
    existingBatches: List<BatchConfig>,
    submitLabel: String,
    onCancel: () -> Unit,
    onSubmit: (String, String, String, Int, Int, Boolean, NamingMode, List<String>, String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 新建时预填建议值，编辑时用批次自身字段
    val suggestion = remember(existingBatches) { BatchConfig.suggestNextNaming(existingBatches) }

    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var dirName by remember(initial) { mutableStateOf(initial?.dirName ?: suggestion.second) }
    var prefix by remember(initial) { mutableStateOf(initial?.namePrefix ?: suggestion.third) }
    var startIndex by remember(initial) {
        mutableStateOf((initial?.startIndex ?: BatchConfig.DEFAULT_START_INDEX).toString())
    }
    var indexWidth by remember(initial) {
        mutableStateOf((initial?.indexWidth ?: BatchConfig.DEFAULT_INDEX_WIDTH).toString())
    }
    var dateSubDir by remember(initial) { mutableStateOf(initial?.dateSubDir ?: false) }
    var namingMode by remember(initial) {
        mutableStateOf(initial?.namingMode ?: NamingMode.SEQUENCE)
    }
    var nameListText by remember(initial) {
        mutableStateOf(BatchConfig.formatNameList(initial?.nameList ?: emptyList()))
    }
    var note by remember(initial) { mutableStateOf(initial?.note ?: "") }

    val dirError = BatchConfig.validateDirName(dirName)
    val prefixError = BatchConfig.validatePrefix(prefix)

    // 目录名冲突会让两批照片混进同一个相册
    val duplicatedDir = existingBatches.any {
        it.id != initial?.id && it.safeDirName.equals(dirName.trim(), ignoreCase = true)
    }

    // 工作模式必须至少有一个名字
    val parsedNames = BatchConfig.parseNameList(nameListText)
    val nameListOk = namingMode != NamingMode.WORK || parsedNames.isNotEmpty()
    val canSubmit = name.isNotBlank() && dirError == null && prefixError == null &&
        !duplicatedDir && nameListOk

    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            BatchFormFields(
                name = name,
                onNameChange = { name = it },
                dirName = dirName,
                onDirNameChange = { dirName = it },
                prefix = prefix,
                onPrefixChange = { prefix = it },
                startIndex = startIndex,
                onStartIndexChange = { startIndex = it },
                indexWidth = indexWidth,
                onIndexWidthChange = { indexWidth = it },
                dateSubDir = dateSubDir,
                onDateSubDirChange = { dateSubDir = it },
                namingMode = namingMode,
                onNamingModeChange = { namingMode = it },
                nameListText = nameListText,
                onNameListTextChange = { nameListText = it },
                note = note,
                onNoteChange = { note = it },
                copyableBatches = existingBatches,
                modifier = Modifier.fillMaxWidth()
            )

            if (duplicatedDir) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "目录名 ${dirName.trim()} 已被其它批次占用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (initial != null) {
                Spacer(modifier = Modifier.height(16.dp))

                // 工作模式下如果已拍数量已超过名字个数，保存后仍会回落序号命名，
                // 提前说清楚，避免又出现"填了名字不生效"的困惑
                val willFallBack = namingMode == NamingMode.WORK &&
                    parsedNames.isNotEmpty() &&
                    namingMode == initial.namingMode &&
                    initial.counter >= parsedNames.size

                Text(
                    text = when {
                        willFallBack ->
                            "注意：该批次已拍到第 ${initial.counter} 个，而名字列表只有 " +
                                "${parsedNames.size} 个，保存后仍会回落序号命名。" +
                                "请用列表里的「重置序号」从第一个名字重新开始。"
                        namingMode != initial.namingMode ->
                            "已切换命名模式：保存后计数会重置，" +
                                "下一张为 ${namePreview(prefix, namingMode, parsedNames)}"
                        else ->
                            "编辑不会改变已拍张数（当前 ${initial.counter} 张）。" +
                                "如需从头编号，请用列表里的「重置序号」。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (willFallBack) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // 底部操作栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("取消")
            }
            Button(
                onClick = {
                    onSubmit(
                        name.trim(),
                        dirName.trim(),
                        prefix.trim(),
                        startIndex.toIntOrNull() ?: BatchConfig.DEFAULT_START_INDEX,
                        indexWidth.toIntOrNull()
                            ?.coerceIn(BatchConfig.MIN_INDEX_WIDTH, BatchConfig.MAX_INDEX_WIDTH)
                            ?: BatchConfig.DEFAULT_INDEX_WIDTH,
                        dateSubDir,
                        namingMode,
                        BatchConfig.parseNameList(nameListText),
                        note
                    )
                },
                enabled = canSubmit,
                modifier = Modifier.weight(1f)
            ) {
                Text(submitLabel)
            }
        }
    }
}
