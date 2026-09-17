/**
 * EditViewModel.kt - 图片编辑ViewModel
 *
 * 管理图片编辑的业务逻辑和状态
 * 支持：裁剪、调整、滤镜、撤销/重做
 *
 * 技术实现：
 * - GPUImage 用于滤镜和调整效果
 * - Canvas 用于裁剪和变换
 * - StateFlow 管理状态
 * - SharedFlow 处理一次性事件
 *
 * @author qihao
 * @since 2.0.0
 */
package com.qihao.filtercamera.presentation.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qihao.filtercamera.domain.model.AdjustParams
import com.qihao.filtercamera.domain.model.AdjustType
import com.qihao.filtercamera.domain.model.CropRatio
import com.qihao.filtercamera.domain.model.CropState
import com.qihao.filtercamera.domain.model.EditEvent
import com.qihao.filtercamera.domain.model.EditHistoryItem
import com.qihao.filtercamera.domain.model.EditMode
import com.qihao.filtercamera.domain.model.EditState
import com.qihao.filtercamera.domain.model.FilterType
import com.qihao.filtercamera.domain.model.centerCropRect
import com.qihao.filtercamera.domain.repository.IFilterRepository
import com.qihao.filtercamera.domain.repository.IMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageBrightnessFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageContrastFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSaturationFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSharpenFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageVignetteFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageWhiteBalanceFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageHighlightShadowFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class EditViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val filterRepository: IFilterRepository,
    private val mediaRepository: IMediaRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "EditViewModel"                               // 日志标签
        private const val MAX_HISTORY_SIZE = 20                               // 最大历史记录数

        /** 连续型操作（滑块拖动）合并进同一条历史的窗口 */
        private const val HISTORY_COALESCE_MS = 800L
    }

    /** 上一次写入历史的时间戳（elapsedRealtime），用于滑块拖动的历史合并 */
    private var lastHistoryPushAt = 0L

    // ==================== 状态管理 ====================

    private val _uiState = MutableStateFlow(EditState())
    val uiState: StateFlow<EditState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EditEvent>()
    val events = _events.asSharedFlow()

    // GPUImage 实例
    private var gpuImage: GPUImage? = null

    // 从导航参数获取图片URI
    init {
        savedStateHandle.get<String>("imageUri")?.let { uriString ->
            Log.d(TAG, "init: 从导航参数获取图片URI: $uriString")
            loadImage(Uri.parse(uriString))
        }
    }

    // ==================== 图片加载 ====================

    /**
     * 加载图片
     *
     * @param uri 图片URI
     */
    fun loadImage(uri: Uri) {
        Log.d(TAG, "loadImage: 开始加载图片 uri=$uri")
        _uiState.update { it.copy(isLoading = true, sourceUri = uri) }

        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    mediaRepository.loadBitmap(uri)
                }

                if (bitmap != null) {
                    Log.d(TAG, "loadImage: 图片加载成功 ${bitmap.width}x${bitmap.height}")

                    // 初始化 GPUImage
                    gpuImage = GPUImage(context).apply {
                        setImage(bitmap)
                    }

                    _uiState.update {
                        it.copy(
                            sourceBitmap = bitmap,
                            previewBitmap = bitmap,
                            isLoading = false
                        )
                    }

                    // 添加初始历史记录
                    addHistoryItem()

                    _events.emit(EditEvent.LoadSuccess)
                } else {
                    Log.e(TAG, "loadImage: 图片加载失败，返回null")
                    _uiState.update { it.copy(isLoading = false, errorMessage = "无法加载图片") }
                    _events.emit(EditEvent.Error("无法加载图片"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadImage: 加载图片异常", e)
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
                _events.emit(EditEvent.Error(e.message ?: "加载图片失败"))
            }
        }
    }

    // ==================== 模式切换 ====================

    /**
     * 切换编辑模式
     */
    fun setEditMode(mode: EditMode) {
        Log.d(TAG, "setEditMode: 切换模式 -> ${mode.displayName}")
        _uiState.update { it.copy(currentMode = mode, showFilterSelector = false) }
    }

    /**
     * 选择调整类型
     */
    fun selectAdjustType(type: AdjustType) {
        Log.d(TAG, "selectAdjustType: 选择调整类型 -> ${type.displayName}")
        _uiState.update { it.copy(selectedAdjustType = type) }
    }

    // ==================== 调整参数 ====================

    /**
     * 更新调整参数
     */
    fun updateAdjustParam(type: AdjustType, value: Float) {
        val clampedValue = value.coerceIn(-1f, 1f)
        Log.d(TAG, "updateAdjustParam: ${type.displayName} = $clampedValue")

        val currentParams = _uiState.value.adjustParams
        val newParams = when (type) {
            AdjustType.BRIGHTNESS -> currentParams.copy(brightness = clampedValue)
            AdjustType.CONTRAST -> currentParams.copy(contrast = clampedValue)
            AdjustType.SATURATION -> currentParams.copy(saturation = clampedValue)
            AdjustType.SHARPNESS -> currentParams.copy(sharpness = clampedValue)
            AdjustType.WARMTH -> currentParams.copy(warmth = clampedValue)
            AdjustType.VIGNETTE -> currentParams.copy(vignette = clampedValue.coerceIn(0f, 1f))
            AdjustType.HIGHLIGHTS -> currentParams.copy(highlights = clampedValue)
            AdjustType.SHADOWS -> currentParams.copy(shadows = clampedValue)
        }

        _uiState.update { it.copy(adjustParams = newParams) }
        applyEdits()
        pushHistory(coalesce = true)                                      // 滑块：连续拖动合并成一步
    }

    /**
     * 获取当前调整参数值
     */
    fun getAdjustParamValue(type: AdjustType): Float {
        val params = _uiState.value.adjustParams
        return when (type) {
            AdjustType.BRIGHTNESS -> params.brightness
            AdjustType.CONTRAST -> params.contrast
            AdjustType.SATURATION -> params.saturation
            AdjustType.SHARPNESS -> params.sharpness
            AdjustType.WARMTH -> params.warmth
            AdjustType.VIGNETTE -> params.vignette
            AdjustType.HIGHLIGHTS -> params.highlights
            AdjustType.SHADOWS -> params.shadows
        }
    }

    /**
     * 重置调整参数
     */
    fun resetAdjustParams() {
        Log.d(TAG, "resetAdjustParams: 重置所有调整参数")
        _uiState.update { it.copy(adjustParams = AdjustParams()) }
        applyEdits()
        pushHistory()
    }

    // ==================== 裁剪功能 ====================

    /**
     * 设置裁剪比例
     */
    fun setCropRatio(ratio: CropRatio) {
        Log.d(TAG, "setCropRatio: 设置裁剪比例 -> ${ratio.displayName}")
        _uiState.update {
            it.copy(cropState = it.cropState.copy(cropRatio = ratio))
        }
        applyEdits()                                                      // 比例要真的裁到图上
        pushHistory()
    }

    /**
     * 更新裁剪框
     */
    fun updateCropRect(rect: RectF) {
        _uiState.update {
            it.copy(cropState = it.cropState.copy(cropRect = rect))
        }
    }

    /**
     * 旋转图片（顺时针90度）
     */
    fun rotateImage() {
        val currentRotation = _uiState.value.cropState.rotation
        val newRotation = (currentRotation + 90f) % 360f
        Log.d(TAG, "rotateImage: 旋转 $currentRotation -> $newRotation")

        _uiState.update {
            it.copy(cropState = it.cropState.copy(rotation = newRotation))
        }
        applyEdits()
        pushHistory()
    }

    /**
     * 水平翻转图片
     */
    fun flipHorizontal() {
        val currentFlip = _uiState.value.cropState.isFlippedHorizontal
        Log.d(TAG, "flipHorizontal: ${!currentFlip}")

        _uiState.update {
            it.copy(cropState = it.cropState.copy(isFlippedHorizontal = !currentFlip))
        }
        applyEdits()
        pushHistory()
    }

    /**
     * 垂直翻转图片
     */
    fun flipVertical() {
        val currentFlip = _uiState.value.cropState.isFlippedVertical
        Log.d(TAG, "flipVertical: ${!currentFlip}")

        _uiState.update {
            it.copy(cropState = it.cropState.copy(isFlippedVertical = !currentFlip))
        }
        applyEdits()
        pushHistory()
    }

    /**
     * 应用裁剪
     */
    fun applyCrop() {
        Log.d(TAG, "applyCrop: 应用裁剪")
        addHistoryItem()
    }

    // ==================== 滤镜功能 ====================

    /**
     * 切换滤镜选择器显示
     */
    fun toggleFilterSelector() {
        _uiState.update { it.copy(showFilterSelector = !it.showFilterSelector) }
    }

    /**
     * 选择滤镜
     */
    fun selectFilter(filterType: FilterType) {
        Log.d(TAG, "selectFilter: 选择滤镜 -> ${filterType.name}")
        _uiState.update { it.copy(filterType = filterType) }
        applyEdits()
        pushHistory()
    }

    /**
     * 设置滤镜强度
     */
    fun setFilterIntensity(intensity: Float) {
        val clampedIntensity = intensity.coerceIn(0f, 1f)
        Log.d(TAG, "setFilterIntensity: 设置强度 $clampedIntensity")
        _uiState.update { it.copy(filterIntensity = clampedIntensity) }
        applyEdits()
        pushHistory(coalesce = true)                                      // 滑块：连续拖动合并成一步
    }

    // ==================== 编辑应用 ====================

    /**
     * 应用所有编辑效果到预览图
     */
    private fun applyEdits() {
        val state = _uiState.value
        val sourceBitmap = state.sourceBitmap ?: return

        viewModelScope.launch {
            try {
                val resultBitmap = withContext(Dispatchers.Default) {
                    var bitmap = sourceBitmap

                    // 1. 应用裁剪/旋转/翻转
                    bitmap = applyCropTransform(bitmap, state.cropState)

                    // 2. 应用调整参数
                    if (state.adjustParams.hasAdjustments()) {
                        bitmap = applyAdjustments(bitmap, state.adjustParams)
                    }

                    // 3. 应用滤镜
                    if (state.filterType != FilterType.NONE) {
                        bitmap = applyFilter(bitmap, state.filterType, state.filterIntensity)
                    }

                    bitmap
                }

                _uiState.update { it.copy(previewBitmap = resultBitmap) }
            } catch (e: Exception) {
                Log.e(TAG, "applyEdits: 应用编辑失败", e)
            }
        }
    }

    /**
     * 应用裁剪变换
     *
     * 顺序很重要：先旋转/翻转，再裁剪。反过来的话，旋转会把已经裁好的矩形
     * 一起转过去，得到的位置就全错位了。
     *
     * 裁剪范围有两种来源：
     * - cropRect 非空：按比例坐标（0~1）裁，留给以后做手势裁剪框用
     * - 否则按所选宽高比居中裁剪（1:1 / 4:3 / 16:9 ...）
     */
    private fun applyCropTransform(source: Bitmap, cropState: CropState): Bitmap {
        if (!cropState.hasTransforms()) return source

        val matrix = Matrix()

        // 旋转
        if (cropState.rotation != 0f) {
            matrix.postRotate(cropState.rotation, source.width / 2f, source.height / 2f)
        }

        // 翻转
        if (cropState.isFlippedHorizontal) {
            matrix.postScale(-1f, 1f, source.width / 2f, source.height / 2f)
        }
        if (cropState.isFlippedVertical) {
            matrix.postScale(1f, -1f, source.width / 2f, source.height / 2f)
        }

        val transformed =
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)

        val rect = cropState.cropRect
        if (rect != null && !rect.isEmpty && rect.width() > 0f && rect.height() > 0f) {
            val w = transformed.width
            val h = transformed.height
            val left = (rect.left * w).toInt().coerceIn(0, w - 1)
            val top = (rect.top * h).toInt().coerceIn(0, h - 1)
            val right = (rect.right * w).toInt().coerceIn(left + 1, w)
            val bottom = (rect.bottom * h).toInt().coerceIn(top + 1, h)
            Log.d(
                TAG,
                "applyCropTransform: 按裁剪框裁剪 rect=$rect -> " +
                    "${right - left}x${bottom - top}"
            )
            return Bitmap.createBitmap(transformed, left, top, right - left, bottom - top)
        }

        return cropToAspect(transformed, cropState.cropRatio)
    }

    /**
     * 按目标宽高比居中裁剪
     *
     * 具体的裁剪矩形由 centerCropRect 这个纯函数算（可单测），
     * 这里只负责把结果落到 Bitmap 上。
     */
    private fun cropToAspect(source: Bitmap, ratio: CropRatio): Bitmap {
        val rect = centerCropRect(source.width, source.height, ratio.getAspectRatio())
        if (rect == null) return source

        Log.d(
            TAG,
            "cropToAspect: ${ratio.displayName} ${source.width}x${source.height} " +
                "-> ${rect[2]}x${rect[3]} @ (${rect[0]}, ${rect[1]})"
        )
        return Bitmap.createBitmap(source, rect[0], rect[1], rect[2], rect[3])
    }

    /**
     * 应用调整参数
     */
    private fun applyAdjustments(source: Bitmap, params: AdjustParams): Bitmap {
        val gpuImage = GPUImage(context)
        gpuImage.setImage(source)

        // 创建滤镜组
        val filters = mutableListOf<jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter>()

        // 亮度 (-1 ~ 1 映射到 -1 ~ 1)
        if (params.brightness != 0f) {
            filters.add(GPUImageBrightnessFilter(params.brightness))
        }

        // 对比度 (-1 ~ 1 映射到 0 ~ 4，默认1)
        if (params.contrast != 0f) {
            val contrastValue = 1f + params.contrast * (if (params.contrast > 0) 3f else 0.9f)
            filters.add(GPUImageContrastFilter(contrastValue))
        }

        // 饱和度 (-1 ~ 1 映射到 0 ~ 2，默认1)
        if (params.saturation != 0f) {
            val saturationValue = 1f + params.saturation
            filters.add(GPUImageSaturationFilter(saturationValue))
        }

        // 锐度 (-1 ~ 1 映射到 -4 ~ 4)
        if (params.sharpness != 0f) {
            filters.add(GPUImageSharpenFilter(params.sharpness * 4f))
        }

        // 色温 (-1 ~ 1 映射到 4000K ~ 8000K，默认5000K)
        if (params.warmth != 0f) {
            val temperature = 5000f + params.warmth * 2000f
            filters.add(GPUImageWhiteBalanceFilter(temperature, 0f))
        }

        // 暗角 (0 ~ 1)
        if (params.vignette > 0f) {
            filters.add(GPUImageVignetteFilter().apply {
                setVignetteStart(0.3f)
                setVignetteEnd(0.75f + params.vignette * 0.25f)
            })
        }

        // 高光和阴影
        if (params.highlights != 0f || params.shadows != 0f) {
            val shadowsValue = 1f + params.shadows * 0.5f
            val highlightsValue = 1f - params.highlights * 0.5f
            filters.add(GPUImageHighlightShadowFilter(shadowsValue, highlightsValue))
        }

        if (filters.isEmpty()) return source

        gpuImage.setFilter(GPUImageFilterGroup(filters))
        return gpuImage.bitmapWithFilterApplied
    }

    /**
     * 应用滤镜
     */
    private fun applyFilter(source: Bitmap, filterType: FilterType, intensity: Float): Bitmap {
        // 必须带上 intensity：不带强度的重载读的是仓库里的单例强度（相机页在用），
        // 编辑器调它等于"用相机的强度渲染编辑器的图"，滑块拖了也没反应
        return filterRepository.applyFilterToBitmapSync(filterType, source, intensity) ?: source
    }

    // ==================== 对比原图 ====================

    /**
     * 开始对比原图
     */
    fun startCompare() {
        _uiState.update { it.copy(isComparing = true) }
    }

    /**
     * 结束对比原图
     */
    fun endCompare() {
        _uiState.update { it.copy(isComparing = false) }
    }

    // ==================== 历史记录 ====================

    /**
     * 添加历史记录
     */
    private fun addHistoryItem() {
        val state = _uiState.value
        val item = EditHistoryItem(
            adjustParams = state.adjustParams,
            cropState = state.cropState,
            filterType = state.filterType,
            filterIntensity = state.filterIntensity
        )

        val newHistory = if (state.historyIndex < state.history.size - 1) {
            // 如果不是在历史末尾，删除后面的记录
            state.history.subList(0, state.historyIndex + 1).toMutableList()
        } else {
            state.history.toMutableList()
        }

        newHistory.add(item)

        // 限制历史记录数量
        if (newHistory.size > MAX_HISTORY_SIZE) {
            newHistory.removeAt(0)
        }

        _uiState.update {
            it.copy(
                history = newHistory,
                historyIndex = newHistory.size - 1
            )
        }
    }

    /**
     * 记录一次历史（连续型操作合并为一条）
     *
     * 抽这一层是因为滑块：拖动一次会调用 updateAdjustParam 几十遍，每次都入栈
     * 的话"撤销"要点几十下才退回上一步，而且 MAX_HISTORY_SIZE 会被瞬间冲光。
     * 所以连续型操作在 [HISTORY_COALESCE_MS] 窗口内只覆盖栈顶，不新增。
     *
     * 旋转/翻转/选滤镜这类离散操作传 coalesce=false，各自成为独立的一步。
     *
     * @param coalesce 是否允许与上一条历史合并
     */
    private fun pushHistory(coalesce: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val shouldCoalesce = coalesce &&
            _uiState.value.history.isNotEmpty() &&
            _uiState.value.historyIndex >= 0 &&
            now - lastHistoryPushAt < HISTORY_COALESCE_MS

        lastHistoryPushAt = now

        if (shouldCoalesce) {
            // 覆盖栈顶：把当前状态写回最后一条，而不是新增一条
            val state = _uiState.value
            val newHistory = state.history.toMutableList()
            newHistory[state.historyIndex] = EditHistoryItem(
                adjustParams = state.adjustParams,
                cropState = state.cropState,
                filterType = state.filterType,
                filterIntensity = state.filterIntensity
            )
            _uiState.update { it.copy(history = newHistory) }
        } else {
            addHistoryItem()
        }
    }

    /**
     * 撤销
     */
    fun undo() {
        val state = _uiState.value
        if (!state.canUndo()) return

        val newIndex = state.historyIndex - 1
        val item = state.history[newIndex]

        Log.d(TAG, "undo: 撤销到历史记录 $newIndex")

        _uiState.update {
            it.copy(
                adjustParams = item.adjustParams,
                cropState = item.cropState,
                filterType = item.filterType,
                filterIntensity = item.filterIntensity,
                historyIndex = newIndex
            )
        }
        applyEdits()
    }

    /**
     * 重做
     */
    fun redo() {
        val state = _uiState.value
        if (!state.canRedo()) return

        val newIndex = state.historyIndex + 1
        val item = state.history[newIndex]

        Log.d(TAG, "redo: 重做到历史记录 $newIndex")

        _uiState.update {
            it.copy(
                adjustParams = item.adjustParams,
                cropState = item.cropState,
                filterType = item.filterType,
                filterIntensity = item.filterIntensity,
                historyIndex = newIndex
            )
        }
        applyEdits()
    }

    // ==================== 保存 ====================

    /**
     * 保存编辑后的图片
     */
    fun saveImage() {
        val previewBitmap = _uiState.value.previewBitmap ?: return

        Log.d(TAG, "saveImage: 开始保存图片")
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    mediaRepository.savePhoto(previewBitmap, "FilterCamera_Edit_${System.currentTimeMillis()}")
                }

                result.fold(
                    onSuccess = { outputUri ->
                        Log.d(TAG, "saveImage: 保存成功 uri=$outputUri")
                        _uiState.update { it.copy(isSaving = false) }
                        _events.emit(EditEvent.SaveSuccess(outputUri))
                    },
                    onFailure = { error ->
                        Log.e(TAG, "saveImage: 保存失败", error)
                        _uiState.update { it.copy(isSaving = false) }
                        _events.emit(EditEvent.SaveFailed(error.message ?: "保存失败"))
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "saveImage: 保存异常", e)
                _uiState.update { it.copy(isSaving = false) }
                _events.emit(EditEvent.SaveFailed(e.message ?: "保存失败"))
            }
        }
    }

    /**
     * 重置所有编辑
     */
    fun resetAll() {
        Log.d(TAG, "resetAll: 重置所有编辑")
        _uiState.update {
            it.copy(
                adjustParams = AdjustParams(),
                cropState = CropState(),
                filterType = FilterType.NONE,
                filterIntensity = 1.0f,
                previewBitmap = it.sourceBitmap
            )
        }
        addHistoryItem()
    }

    override fun onCleared() {
        super.onCleared()
        gpuImage?.deleteImage()
        gpuImage = null
        Log.d(TAG, "onCleared: ViewModel 已清理")
    }
}
