/**
 * BatchManageViewModel.kt - 批次管理页ViewModel
 *
 * 负责批次的增删改查与选中状态，全部委托给 IBatchRepository。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.presentation.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qihao.filtercamera.presentation.common.components.BatchFormData
import com.qihao.filtercamera.domain.model.BatchConfig
import com.qihao.filtercamera.domain.model.NamingMode
import com.qihao.filtercamera.domain.repository.IBatchRepository
import com.qihao.filtercamera.domain.repository.IMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 批次管理页 UI 状态
 *
 * @param message 顶部一次性提示（成功/失败），展示后需调用 [BatchManageViewModel.consumeMessage] 清空
 * @param editingBatch 正在编辑的批次，null 表示没有打开编辑表单
 * @param deleteTarget 待确认删除的批次，null 表示没有打开删除确认框
 * @param newRoundTarget 待确认开始新一轮的批次
 * @param syncTarget 待确认"按磁盘同步"的批次
 * @param isCreating 是否正在新建（打开新建表单）
 */
data class BatchManageUiState(
    val message: String? = null,
    val isMessageError: Boolean = false,
    val editingBatch: BatchConfig? = null,
    val deleteTarget: BatchConfig? = null,
    val newRoundTarget: BatchConfig? = null,
    val syncTarget: BatchConfig? = null,
    val isCreating: Boolean = false
)

/**
 * 批次管理页ViewModel
 *
 * @param batchRepository 批次仓库
 */
@HiltViewModel
class BatchManageViewModel @Inject constructor(
    private val batchRepository: IBatchRepository,
    private val mediaRepository: IMediaRepository
) : ViewModel() {

    companion object {
        private const val TAG = "BatchManageViewModel"                    // 日志标签
    }

    /** 全部批次 */
    val batches: StateFlow<List<BatchConfig>> = batchRepository.batches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前选中批次id */
    val currentBatchId: StateFlow<String?> = batchRepository.currentBatchId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 页面交互状态 */
    private val _uiState = MutableStateFlow(BatchManageUiState())
    val uiState: StateFlow<BatchManageUiState> = _uiState.asStateFlow()

    // ==================== 表单开关 ====================

    /**
     * 打开新建表单
     */
    fun startCreate() {
        Log.d(TAG, "startCreate: 打开新建批次表单")
        _uiState.update { it.copy(isCreating = true, editingBatch = null) }
    }

    /**
     * 打开编辑表单
     */
    fun startEdit(batch: BatchConfig) {
        Log.d(TAG, "startEdit: 打开编辑表单 id=${batch.id}")
        _uiState.update { it.copy(editingBatch = batch, isCreating = false) }
    }

    /**
     * 关闭表单（新建/编辑共用）
     */
    fun dismissForm() {
        _uiState.update { it.copy(isCreating = false, editingBatch = null) }
    }

    // ==================== 增删改 ====================

    /**
     * 新建批次
     */
    fun createBatch(data: BatchFormData) {
        val name = data.name
        val dirName = data.dirName
        val namePrefix = data.prefix
        val startIndex = data.startIndex
        val indexWidth = data.indexWidth
        val dateSubDir = data.dateSubDir
        val namingMode = data.namingMode
        val nameList = data.nameList
        val note = data.note
        val groupNames = data.groupNames
        val autoAdvanceGroup = data.autoAdvanceGroup
        val includeGroupInFileName = data.includeGroupInFileName
        val conflict = findDirConflict(dirName, excludeId = null)
        if (conflict != null) {
            showMessage("目录名 ${dirName.trim()} 已被批次「${conflict.name}」占用", isError = true)
            return
        }

        val batch = BatchConfig.create(
            name = name,
            dirName = dirName,
            namePrefix = namePrefix,
            startIndex = startIndex,
            indexWidth = indexWidth,
            dateSubDir = dateSubDir
        ).copy(
            namingMode = namingMode,
            nameList = nameList,
            note = note.trim(),
            // 组名会直接变成目录名，逐个清洗，别让斜杠或非法字符钻进去
            groupNames = groupNames.map { BatchConfig.sanitizeGroupName(it) }.filter { it.isNotEmpty() },
            autoAdvanceGroup = autoAdvanceGroup,
            includeGroupInFileName = includeGroupInFileName
        ).normalized()

        viewModelScope.launch {
            batchRepository.addBatch(batch)
                .onSuccess {
                    Log.d(TAG, "createBatch: 新建成功 id=${batch.id}")
                    _uiState.update { it.copy(isCreating = false) }
                    showMessage("批次「${batch.name}」已创建")
                }
                .onFailure { error ->
                    Log.e(TAG, "createBatch: 新建失败", error)
                    showMessage("新建失败：${error.message ?: "未知错误"}", isError = true)
                }
        }
    }

    /**
     * 保存编辑
     *
     * 注意：只更新批次定义字段，counter 沿用原值（编辑名称不应该让序号倒退）。
     */
    fun updateBatch(batch: BatchConfig, data: BatchFormData) {
        val name = data.name
        val dirName = data.dirName
        val namePrefix = data.prefix
        val startIndex = data.startIndex
        val indexWidth = data.indexWidth
        val dateSubDir = data.dateSubDir
        val namingMode = data.namingMode
        val nameList = data.nameList
        val note = data.note
        val groupNames = data.groupNames
        val autoAdvanceGroup = data.autoAdvanceGroup
        val includeGroupInFileName = data.includeGroupInFileName
        val conflict = findDirConflict(dirName, excludeId = batch.id)
        if (conflict != null) {
            showMessage("目录名 ${dirName.trim()} 已被批次「${conflict.name}」占用", isError = true)
            return
        }

        // 切换命名模式必须走 withNamingMode：两种模式下 counter 含义不同，
        // 不归零会让工作模式从列表中间开始、甚至越界回落到序号命名。
        val updated = batch.copy(
            name = name.trim().ifEmpty { BatchConfig.DEFAULT_NAME },
            dirName = dirName.trim(),
            namePrefix = namePrefix.trim(),
            startIndex = startIndex,
            indexWidth = indexWidth,
            dateSubDir = dateSubDir,
            note = note.trim(),
            groupNames = groupNames.map { BatchConfig.sanitizeGroupName(it) }.filter { it.isNotEmpty() },
            autoAdvanceGroup = autoAdvanceGroup,
            includeGroupInFileName = includeGroupInFileName
        ).withNamingMode(namingMode, nameList)

        val modeChanged = batch.namingMode != namingMode

        viewModelScope.launch {
            batchRepository.updateBatch(updated)
                .onSuccess {
                    Log.d(TAG, "updateBatch: 保存成功 id=${updated.id}, 模式切换=$modeChanged, " +
                        "下一张=${updated.nextFileName()}")
                    _uiState.update { it.copy(editingBatch = null) }
                    showMessage(
                        if (modeChanged) {
                            "已切换为${namingMode.displayName}，计数已重置；下一张 ${updated.nextFileName()}"
                        } else {
                            "批次「${updated.name}」已保存，下一张 ${updated.nextFileName()}"
                        }
                    )
                }
                .onFailure { error ->
                    Log.e(TAG, "updateBatch: 保存失败", error)
                    showMessage("保存失败：${error.message ?: "未知错误"}", isError = true)
                }
        }
    }

    /**
     * 请求开始新一轮（弹确认框）
     *
     * 新一轮会把名字指针归零，所以先确认一下，避免误触让拍摄顺序从头开始。
     */
    fun requestNewRound(batch: BatchConfig) {
        Log.d(TAG, "requestNewRound: 请求新一轮 id=${batch.id}, 当前第 ${batch.round} 轮")
        _uiState.update { it.copy(newRoundTarget = batch) }
    }

    /**
     * 取消新一轮
     */
    fun cancelNewRound() {
        _uiState.update { it.copy(newRoundTarget = null) }
    }

    /**
     * 确认开始新一轮
     */
    fun confirmNewRound() {
        val target = _uiState.value.newRoundTarget ?: return
        _uiState.update { it.copy(newRoundTarget = null) }
        startNewRound(target)
    }

    /**
     * 开始新一轮
     *
     * 工作模式的名字是固定的，重复拍摄会重名；新一轮把文件落到 第N轮/ 下，
     * 从而可以拿着同一套名字从头再拍一遍。
     */
    fun startNewRound(batch: BatchConfig) {
        viewModelScope.launch {
            batchRepository.startNewRound(batch.id)
                .onSuccess {
                    val next = batch.withNewRound()
                    Log.d(TAG, "startNewRound: ${batch.name} -> 第 ${next.round} 轮")
                    showMessage(
                        "「${batch.name}」已开始第 ${next.round} 轮，下一张 ${next.nextFileName()}"
                    )
                }
                .onFailure { error ->
                    Log.e(TAG, "startNewRound: 失败", error)
                    showMessage("开始新一轮失败：${error.message ?: "未知错误"}", isError = true)
                }
        }
    }

    /**
     * 请求按磁盘同步（弹确认框）
     */
    fun requestSyncFromDisk(batch: BatchConfig) {
        Log.d(TAG, "requestSyncFromDisk: 请求同步 id=${batch.id}, 当前第 ${batch.round} 轮")
        _uiState.update { it.copy(syncTarget = batch) }
    }

    /**
     * 取消按磁盘同步
     */
    fun cancelSyncFromDisk() {
        _uiState.update { it.copy(syncTarget = null) }
    }

    /**
     * 确认按磁盘同步
     */
    fun confirmSyncFromDisk() {
        val target = _uiState.value.syncTarget ?: return
        _uiState.update { it.copy(syncTarget = null) }
        syncFromDisk(target)
    }

    /**
     * 按磁盘上的实际文件重建轮次与进度
     *
     * 轮次和进度存在 DataStore 里，用户在文件管理器里删掉或替换某个轮次目录后，
     * App 完全不知情，界面显示的轮次就与实际存盘的目录对不上。
     * 这里反向对齐：扫该批次目录下的照片 -> 按轮次分组 -> 用文件名反推已拍项。
     *
     * 只读磁盘、不改照片，安全；扫不到任何照片时不做任何改动（避免把状态误清空）。
     */
    fun syncFromDisk(batch: BatchConfig) {
        viewModelScope.launch {
            try {
                // 1. 取磁盘事实：该批次目录下的照片
                val photos = mediaRepository.getPhotosInDir(batch.safeDirName)
                val dateStamp = if (batch.dateSubDir) BatchConfig.dateStampFor() else null

                // 开了日期子目录时只认当天的：否则会把昨天的第 5 轮当成今天的现状
                val scoped = photos.filter { file ->
                    dateStamp == null || file.path.contains(dateStamp)
                }

                if (scoped.isEmpty()) {
                    Log.w(TAG, "syncFromDisk: 未扫到照片 id=${batch.id}, 扫到总数=${photos.size}")
                    showMessage(
                        "没有在 Pictures/${batch.safeDirName}/" +
                            (dateStamp?.let { "$it/" } ?: "") +
                            " 下找到照片，未做改动（也可能是缺少相册读取权限）"
                    )
                    return@launch
                }

                // 2. 解析成"分层结构"，再交给纯函数重建状态。
                //    这里刻意只喂磁盘数据，不掺当前进度 —— 否则点第二次结果就会变。
                var ignored = 0
                val ignoredNames = mutableListOf<String>()
                val unknownGroups = mutableSetOf<String>()
                val synced = when {
                    // 2a. 启用分组：组名 -> 轮次 -> 文件名
                    batch.usesGroups -> {
                        val parsed = scoped.mapNotNull { file ->
                            val group = BatchConfig.parseGroupFromPath(
                                file.path, file.name, batch.groupNames
                            )
                            val round = BatchConfig.parseRoundFromPath(file.path) ?: 1
                            when {
                                group == null -> {                                // 路径里没有已知组目录
                                    ignored++
                                    ignoredNames += file.path
                                    null
                                }
                                group !in batch.groupNames -> {                   // 磁盘上有、批次里没登记的组
                                    unknownGroups += group
                                    Triple(group, round, file.name)              // 交给同步认下来
                                }
                                else -> Triple(group, round, file.name)
                            }
                        }
                        if (parsed.isEmpty()) {
                            Log.w(TAG, "syncFromDisk: 分组目录无法识别 id=${batch.id}")
                            showMessage("照片的目录结构与批次的分组对不上，未做改动")
                            return@launch
                        }
                        val groupRounds = parsed
                            .groupBy({ it.first }, { it.second to it.third })
                            .mapValues { (_, list) -> list.groupBy({ it.first }, { it.second }) }
                        batch.syncedWithDiskGroups(groupRounds)
                    }

                    // 2b. 工作模式未分组：第N轮 -> 文件名
                    batch.isWorkMode && batch.effectiveNameList.isNotEmpty() -> {
                        // 没有轮次目录的文件按第 1 轮算：直接放在批次目录下的照片
                        // 仍然是这批工作的一部分，不该被丢掉（丢掉会让它显示成未拍，
                        // 再拍一张就成了同名副本）
                        val byRound = scoped.map { file ->
                            (BatchConfig.parseRoundFromPath(file.path) ?: 1) to file.name
                        }
                        batch.syncedWithDiskRounds(byRound.groupBy({ it.first }, { it.second }))
                    }

                    // 2c. 序号模式：按磁盘上的最大序号重建计数器
                    else -> {
                        val maxSeq = scoped.mapNotNull {
                            BatchConfig.parseSequenceFromFileName(it.name)
                        }.maxOrNull()
                        if (maxSeq == null) {
                            Log.w(TAG, "syncFromDisk: 文件名里没有序号 id=${batch.id}")
                            showMessage("照片文件名里没有序号，未做改动")
                            return@launch
                        }
                        batch.syncedWithDiskSequence(maxSeq)
                    }
                }

                // 3. 写回并如实汇报
                batchRepository.updateBatch(synced)
                    .onSuccess {
                        val tail = buildString {
                            if (ignored > 0) {
                                append("；另有 $ignored 张不在任何已知组目录下，已忽略，例如：")
                                append(ignoredNames.take(2).joinToString("；"))
                                if (ignoredNames.size > 2) append(" 等")
                                append("（可用「编辑」补上对应分组名）")
                            }
                            if (unknownGroups.isNotEmpty()) {
                                append("；已把磁盘上的新组目录纳入分组：")
                                append(unknownGroups.sorted().joinToString("、"))
                            }
                        }
                        val body = if (synced.usesGroups) {
                            "当前组 ${synced.activeGroupName}，" +
                                "已拍 ${synced.shotCount}/${synced.effectiveNameList.size}"
                        } else if (synced.isWorkMode && synced.effectiveNameList.isNotEmpty()) {
                            "现在是第 ${synced.round} 轮，" +
                                "已拍 ${synced.shotCount}/${synced.effectiveNameList.size}"
                        } else {
                            "已拍 ${synced.counter} 张"
                        }
                        Log.d(TAG, "syncFromDisk: 同步成功 id=${batch.id}, $body$tail")
                        showMessage(
                            "已按磁盘同步：「${synced.name}」$body，下一张 ${synced.nextFileName()}$tail"
                        )
                    }
                    .onFailure { error ->
                        Log.e(TAG, "syncFromDisk: 保存失败", error)
                        showMessage("同步失败：${error.message ?: "未知错误"}", isError = true)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "syncFromDisk: 扫描失败", e)
                showMessage("同步失败：${e.message ?: "未知错误"}", isError = true)
            }
        }
    }

    /**
     * 请求删除（弹出确认框）
     */
    fun requestDelete(batch: BatchConfig) {
        Log.d(TAG, "requestDelete: 请求删除 id=${batch.id}, counter=${batch.counter}")
        _uiState.update { it.copy(deleteTarget = batch) }
    }

    /**
     * 取消删除
     */
    fun cancelDelete() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    /**
     * 确认删除批次定义
     *
     * 已拍摄的照片留在相册里不受影响，这一点在确认框里向用户说明。
     */
    fun confirmDelete() {
        val target = _uiState.value.deleteTarget ?: return
        viewModelScope.launch {
            batchRepository.deleteBatch(target.id)
                .onSuccess {
                    Log.d(TAG, "confirmDelete: 删除成功 id=${target.id}")
                    _uiState.update { it.copy(deleteTarget = null) }
                    showMessage("批次「${target.name}」已删除")
                }
                .onFailure { error ->
                    Log.e(TAG, "confirmDelete: 删除失败", error)
                    _uiState.update { it.copy(deleteTarget = null) }
                    showMessage("删除失败：${error.message ?: "未知错误"}", isError = true)
                }
        }
    }

    // ==================== 选中与计数 ====================

    /**
     * 设为当前批次
     */
    fun selectBatch(id: String) {
        viewModelScope.launch {
            batchRepository.selectBatch(id)
                .onFailure { error ->
                    Log.e(TAG, "selectBatch: 选中失败 id=$id", error)
                    showMessage("切换批次失败：${error.message ?: "未知错误"}", isError = true)
                }
        }
    }

    /**
     * 取消批次选择
     */
    fun clearSelection() {
        viewModelScope.launch {
            batchRepository.clearSelection()
                .onFailure { error ->
                    Log.e(TAG, "clearSelection: 取消失败", error)
                    showMessage("取消批次失败：${error.message ?: "未知错误"}", isError = true)
                }
        }
    }

    /**
     * 重置进度（回到本轮起点，用于重拍本轮）
     *
     * 注意提示文案里要用 withProgressReset() 之后的下一张：直接用原对象算
     * 会把"本轮已拍完"的状态算进去，显示出下一轮的名字，与实际存盘不符。
     */
    fun resetCounter(batch: BatchConfig) {
        viewModelScope.launch {
            batchRepository.resetCounter(batch.id)
                .onSuccess {
                    val reset = batch.withProgressReset()
                    Log.d(TAG, "resetCounter: 重置成功 id=${batch.id}, 工作模式=${batch.isWorkMode}")
                    showMessage(
                        if (batch.isWorkMode) {
                            "「${batch.name}」第 ${batch.round} 轮进度已清空，" +
                                "下一张 ${reset.nextFileName()}"
                        } else {
                            "「${batch.name}」已重置，下一张 ${reset.nextFileName()}"
                        }
                    )
                }
                .onFailure { error ->
                    Log.e(TAG, "resetCounter: 重置失败", error)
                    showMessage("重置失败：${error.message ?: "未知错误"}", isError = true)
                }
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 查找目录名冲突
     *
     * @param dirName 待检查目录名
     * @param excludeId 编辑场景下排除自身
     */
    private fun findDirConflict(dirName: String, excludeId: String?): BatchConfig? {
        val target = BatchConfig.sanitizeDirName(dirName).lowercase()
        return batches.value.firstOrNull {
            it.id != excludeId && it.safeDirName.lowercase() == target
        }
    }

    /**
     * 推送一条提示
     */
    private fun showMessage(message: String, isError: Boolean = false) {
        _uiState.update { it.copy(message = message, isMessageError = isError) }
    }

    /**
     * 消费提示（Snackbar 展示后调用）
     */
    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
