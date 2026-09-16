/**
 * BatchBarComponents.kt - 批次拍摄UI组件
 *
 * 包含两个组件：
 * - [BatchBar]：相机页顶部「批次条」，显示当前批次与下一张文件名
 * - [BatchSelectorSheet]：切换批次的底部弹窗（列表 / 新建 / 不使用批次）
 *
 * 设计要点：把「下一张会叫什么」摆在按快门之前显眼的位置 ——
 * 拍摄前的确定性正是批次拍摄的核心体验。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.presentation.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qihao.filtercamera.domain.model.BatchConfig
import com.qihao.filtercamera.domain.model.NamingMode
import com.qihao.filtercamera.presentation.common.components.BatchFormFields
import com.qihao.filtercamera.presentation.common.theme.CameraTheme
import kotlinx.coroutines.launch

/**
 * 批次条（相机页顶部）
 *
 * 未选批次：显示「未选批次（默认命名）」
 * 已选批次：显示批次名 + 「下一张: BA_001.jpg」+ 已拍张数
 *
 * @param current 当前批次，null 表示未选批次
 * @param onClick 点击整条时的回调（打开切换弹窗）
 * @param modifier 修饰符
 */
@Composable
fun BatchBar(
    current: BatchConfig?,
    onClick: () -> Unit,
    canUndoLastShot: Boolean = false,
    onUndoLastShot: () -> Unit = {},
    onOpenShotList: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CameraTheme.Colors.controlBackground, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (current == null) Icons.Default.FolderOff else Icons.Default.Folder,
            contentDescription = null,
            tint = if (current == null) CameraTheme.Colors.iconInactive else CameraTheme.Colors.primary,
            modifier = Modifier.size(18.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = current?.name ?: "未选批次（默认命名）",
                color = CameraTheme.Colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            // 第二行可点：打开拍摄清单核对进度
            Text(
                text = if (current != null) {
                    buildString {
                        // 拍完会自动进入下一轮，这里直接展示下一张（新一轮的第一个名字）
                        append("下一张: ").append(current.nextFileName())
                        append(" · ")
                        append(current.workProgressLabel ?: "已拍 ${current.counter} 张")
                    }
                } else {
                    "点此选择批次，自动命名并归档"
                },
                color = CameraTheme.Colors.textSecondary,
                fontSize = 11.sp,
                fontFamily = if (current != null) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                modifier = if (current != null && current.effectiveNameList.isNotEmpty()) {
                    Modifier.clickable(onClick = onOpenShotList)
                } else {
                    Modifier
                }
            )
        }

        // 拍坏了就地作废重拍：只在"本进程内刚拍过"时出现，避免误删历史照片
        if (canUndoLastShot && current != null) {
            Text(
                text = "作废重拍",
                color = CameraTheme.Colors.warning,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(onClick = onUndoLastShot)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Text(
            text = if (current == null) "选择" else "切换",
            color = CameraTheme.Colors.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/**
 * 批次切换底部弹窗
 *
 * 三种操作：选中某个批次 / 不使用批次 / 新建批次。
 * 点「新建批次」后弹窗内容切换成表单，避免在相机页再叠一层对话框。
 *
 * @param visible 是否显示
 * @param batches 全部批次
 * @param currentBatchId 当前选中批次id
 * @param onSelect 选中批次
 * @param onClear 不使用批次
 * @param onCreate 新建批次（批次名 / 目录名 / 前缀 / 起始序号 / 位数 / 日期子目录）
 * @param onDismiss 关闭弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchSelectorSheet(
    visible: Boolean,
    batches: List<BatchConfig>,
    currentBatchId: String?,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onCreate: (String, String, String, Int, Int, Boolean, NamingMode, List<String>, String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 弹窗内部两种视图：批次列表 / 新建表单
    var showCreateForm by remember { mutableStateOf(false) }

    // 先播收起动画再通知 ViewModel，否则弹窗会瞬间消失
    val dismissWithAnimation: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = dismissWithAnimation,
        sheetState = sheetState,
        containerColor = CameraTheme.Colors.surface,
        contentColor = CameraTheme.Colors.textPrimary
    ) {
        if (showCreateForm) {
            CreateBatchForm(
                existingBatches = batches,
                onCancel = { showCreateForm = false },
                onConfirm = { name, dirName, prefix, startIndex, indexWidth, dateSubDir,
                               mode, listText, noteText ->
                    // namingMode / nameListText / note 由 CreateBatchForm 内部维护，这里只透传
                    onCreate(
                        name, dirName, prefix, startIndex, indexWidth, dateSubDir,
                        mode, BatchConfig.parseNameList(listText), noteText
                    )
                    // 动作失败时 ViewModel 会抛错误提示，弹窗保持打开让用户重试；
                    // 成功则由这里收起，带下滑动画
                    dismissWithAnimation()
                }
            )
        } else {
            BatchListContent(
                batches = batches,
                currentBatchId = currentBatchId,
                onSelect = { id ->
                    onSelect(id)
                    dismissWithAnimation()
                },
                onClear = {
                    onClear()
                    dismissWithAnimation()
                },
                onCreateClick = { showCreateForm = true }
            )
        }
    }
}

/**
 * 批次列表内容
 */
@Composable
private fun BatchListContent(
    batches: List<BatchConfig>,
    currentBatchId: String?,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onCreateClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "选择批次",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "拍摄的照片会按批次规则命名，并归入对应相册目录",
            style = MaterialTheme.typography.bodySmall,
            color = CameraTheme.Colors.textSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (batches.isEmpty()) {
            Text(
                text = "还没有批次，点下方「新建批次」创建第一个",
                style = MaterialTheme.typography.bodyMedium,
                color = CameraTheme.Colors.textSecondary,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(items = batches, key = { it.id }) { batch ->
                    BatchListItem(
                        batch = batch,
                        selected = batch.id == currentBatchId,
                        onClick = { onSelect(batch.id) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = CameraTheme.Colors.divider)
        Spacer(modifier = Modifier.height(8.dp))

        // 不使用批次
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClear)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.FolderOff,
                contentDescription = null,
                tint = CameraTheme.Colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "不使用批次", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "回落默认命名 IMG_yyyyMMdd_HHmmss.jpg",
                    style = MaterialTheme.typography.bodySmall,
                    color = CameraTheme.Colors.textSecondary
                )
            }
            if (currentBatchId == null) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "当前使用",
                    tint = CameraTheme.Colors.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = onCreateClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("新建批次")
        }
    }
}

/**
 * 批次列表项
 */
@Composable
private fun BatchListItem(
    batch: BatchConfig,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = if (selected) CameraTheme.Colors.primary else CameraTheme.Colors.iconInactive,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = batch.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) CameraTheme.Colors.primary else CameraTheme.Colors.textPrimary
            )
            Text(
                text = "下一张 ${batch.nextFileName()} · " +
                    (batch.workProgressLabel ?: "已拍 ${batch.counter} 张") +
                    " · Pictures/${batch.safeDirName}/",
                style = MaterialTheme.typography.bodySmall,
                color = CameraTheme.Colors.textSecondary,
                maxLines = 1
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已选中",
                tint = CameraTheme.Colors.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 拍摄清单弹窗
 *
 * 列出当前批次的全部名字，标出已拍/未拍，并显示"下一张拍哪个"。
 * 点某一项 = 从该项开始拍（把名字指针指过去）。
 *
 * @param visible 是否显示
 * @param batch 当前批次
 * @param onJumpTo 选择某一项（下标从 0 开始）
 * @param onDismiss 关闭
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotListSheet(
    visible: Boolean,
    batch: BatchConfig?,
    onJumpTo: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible || batch == null) return

    val names = batch.effectiveNameList
    if (names.isEmpty()) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismissWithAnimation: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = dismissWithAnimation,
        sheetState = sheetState,
        containerColor = CameraTheme.Colors.surface,
        contentColor = CameraTheme.Colors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = batch.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "第 ${batch.round} 轮 · 已拍 ${batch.shotCount}/${names.size}" +
                    " · 下一张：${batch.nextFileName()}",
                style = MaterialTheme.typography.bodySmall,
                color = CameraTheme.Colors.textSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                itemsIndexed(names) { index, name ->
                    val shot = batch.isNameShot(index)
                    val isNext = index == batch.effectiveNextIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJumpTo(index) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (shot) Icons.Default.Check else Icons.Default.Add,
                            contentDescription = null,
                            tint = when {
                                shot -> CameraTheme.Colors.success
                                isNext -> CameraTheme.Colors.primary
                                else -> CameraTheme.Colors.iconInactive
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${index + 1}. ${batch.buildCustomFileName(name)}",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isNext) {
                                    CameraTheme.Colors.primary
                                } else {
                                    CameraTheme.Colors.textPrimary
                                }
                            )
                            Text(
                                text = when {
                                    isNext && shot -> "下一张重拍这一项（会先删掉原照片）"
                                    isNext -> "下一张拍这个"
                                    shot -> "已拍 · 点这里可重拍"
                                    index < batch.effectiveNextIndex -> "待补拍 · 点这里跳过去拍"
                                    else -> "未拍 · 点这里跳过去拍"
                                },
                                fontSize = 11.sp,
                                color = CameraTheme.Colors.textSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = CameraTheme.Colors.divider)
            Text(
                text = "点未拍的项 = 跳过去先拍它（跳过的项会保持未拍，之后自动回头补拍）；" +
                    "点已拍的项 = 重拍（会先删掉原来那张照片，请谨慎）。",
                style = MaterialTheme.typography.bodySmall,
                color = CameraTheme.Colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * 新建批次表单
 *
 * 目录名与前缀会根据已有批次自动给出建议值（BatchA/BatchB...、BA/BB...），
 * 大多数场景下用户只需要填个批次名。
 */
@Composable
private fun CreateBatchForm(
    existingBatches: List<BatchConfig>,
    onCancel: () -> Unit,
    onConfirm: (String, String, String, Int, Int, Boolean, NamingMode, String, String) -> Unit
) {
    // 依据已有批次推导建议值：BatchA -> 下一个 BatchB
    val suggestion = remember(existingBatches) { BatchConfig.suggestNextNaming(existingBatches) }

    var name by remember { mutableStateOf("") }
    var dirName by remember { mutableStateOf(suggestion.second) }
    var prefix by remember { mutableStateOf(suggestion.third) }
    var startIndex by remember { mutableStateOf(BatchConfig.DEFAULT_START_INDEX.toString()) }
    var indexWidth by remember { mutableStateOf(BatchConfig.DEFAULT_INDEX_WIDTH.toString()) }
    var dateSubDir by remember { mutableStateOf(false) }
    var namingMode by remember { mutableStateOf(NamingMode.SEQUENCE) }
    var nameListText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val dirError = BatchConfig.validateDirName(dirName)
    val prefixError = BatchConfig.validatePrefix(prefix)

    // 目录名重复会让两批照片混进同一个相册，这里提前拦住
    val duplicatedDir = existingBatches.any {
        it.safeDirName.equals(dirName.trim(), ignoreCase = true)
    }

    // 工作模式必须至少有一个名字
    val names = BatchConfig.parseNameList(nameListText)
    val nameListOk = namingMode != NamingMode.WORK || names.isNotEmpty()
    val canSubmit = name.isNotBlank() && dirError == null && prefixError == null &&
        !duplicatedDir && nameListOk

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "新建批次",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "更完整的设置可在「设置 → 批次管理」中调整",
            style = MaterialTheme.typography.bodySmall,
            color = CameraTheme.Colors.textSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 字段区必须限制高度（weight + fill=false）：
        // 否则可滚动 Column 会吃掉整个弹窗高度，把下面的「创建并选中」按钮顶出屏幕 ——
        // 表单短时看不出来，加上工作模式的名字列表后就会变成"填了没地方保存"。
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
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
                    color = CameraTheme.Colors.error
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("返回")
            }
            Button(
                onClick = {
                    onConfirm(
                        name.trim(),
                        dirName.trim(),
                        prefix.trim(),
                        startIndex.toIntOrNull() ?: BatchConfig.DEFAULT_START_INDEX,
                        indexWidth.toIntOrNull()
                            ?.coerceIn(BatchConfig.MIN_INDEX_WIDTH, BatchConfig.MAX_INDEX_WIDTH)
                            ?: BatchConfig.DEFAULT_INDEX_WIDTH,
                        dateSubDir,
                        namingMode,
                        nameListText,
                        note
                    )
                },
                enabled = canSubmit,
                modifier = Modifier.weight(1f)
            ) {
                Text("创建并选中")
            }
        }
    }
}
