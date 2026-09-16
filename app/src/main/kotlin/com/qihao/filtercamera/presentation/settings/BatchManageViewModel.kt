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
import com.qihao.filtercamera.domain.model.BatchConfig
import com.qihao.filtercamera.domain.model.NamingMode
import com.qihao.filtercamera.domain.repository.IBatchRepository
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
 * @param isCreating 是否正在新建（打开新建表单）
 */
data class BatchManageUiState(
    val message: String? = null,
    val isMessageError: Boolean = false,
    val editingBatch: BatchConfig? = null,
    val deleteTarget: BatchConfig? = null,
    val newRoundTarget: BatchConfig? = null,
    val isCreating: Boolean = false
)

/**
 * 批次管理页ViewModel
 *
 * @param batchRepository 批次仓库
 */
@HiltViewModel
class BatchManageViewModel @Inject constructor(
    private val batchRepository: IBatchRepository
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
    fun createBatch(
        name: String,
        dirName: String,
        namePrefix: String,
        startIndex: Int,
        indexWidth: Int,
        dateSubDir: Boolean,
        namingMode: NamingMode = NamingMode.SEQUENCE,
        nameList: List<String> = emptyList(),
        note: String = ""
    ) {
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
        ).copy(namingMode = namingMode, nameList = nameList, note = note.trim()).normalized()

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
    fun updateBatch(
        batch: BatchConfig,
        name: String,
        dirName: String,
        namePrefix: String,
        startIndex: Int,
        indexWidth: Int,
        dateSubDir: Boolean,
        namingMode: NamingMode = NamingMode.SEQUENCE,
        nameList: List<String> = emptyList(),
        note: String = ""
    ) {
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
            note = note.trim()
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
     * 重置序号（回到起始序号，用于重拍整批）
     */
    fun resetCounter(batch: BatchConfig) {
        viewModelScope.launch {
            batchRepository.resetCounter(batch.id)
                .onSuccess {
                    Log.d(TAG, "resetCounter: 重置成功 id=${batch.id}")
                    // 重置后下一张回到起始（序号模式回到起始序号，工作模式回到第一个名字）
                    val nextName = batch.copy(counter = 0).nextFileName()
                    showMessage("「${batch.name}」已重置，下一张 $nextName")
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
