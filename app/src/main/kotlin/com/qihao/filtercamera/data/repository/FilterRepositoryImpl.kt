/**
 * FilterRepositoryImpl.kt - 滤镜仓库实现
 *
 * 管理滤镜状态和滤镜应用逻辑
 * 使用GPUImage库实现GPU滤镜渲染
 *
 * 技术实现：
 * - GPUImage库进行GPU滤镜渲染
 * - 支持72种滤镜类型
 * - 支持实时预览和图片处理
 * - 提供同步和异步两种滤镜应用方法
 * - 支持滤镜强度控制（0.0~1.0）
 *
 * @author qihao
 * @since 2.0.0
 */
package com.qihao.filtercamera.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import com.qihao.filter.factory.GPUImageFilterFactory
import com.qihao.filter.watermark.WatermarkRenderer
import com.qihao.filtercamera.data.watermark.WatermarkInfoProvider
import com.qihao.filtercamera.domain.model.FilterType
import com.qihao.filtercamera.domain.repository.IFilterRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 滤镜仓库实现类
 *
 * 使用GPUImage库实现滤镜渲染
 *
 * @param context 应用上下文
 * @param watermarkInfoProvider 信息水印数据（定位/地址/天气/备注）
 */
@Singleton
class FilterRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watermarkInfoProvider: WatermarkInfoProvider
) : IFilterRepository {

    companion object {
        private const val TAG = "FilterRepositoryImpl"  // 日志标签
    }

    // 当前滤镜状态流
    private val _currentFilter = MutableStateFlow(FilterType.NONE)

    // 当前滤镜强度（0.0~1.0，默认1.0全强度）
    private var _currentIntensity: Float = 1.0f

    // GPUImage实例（用于图片处理）
    private var gpuImage: GPUImage? = null

    // 当前GPUImageFilter实例
    private var currentGpuFilter: GPUImageFilter? = null

    // 滤镜引擎是否已初始化
    private var isEngineInitialized = false

    // 渲染尺寸
    private var renderWidth = 0
    private var renderHeight = 0

    /**
     * 获取所有可用滤镜列表
     */
    override fun getAvailableFilters(): List<FilterType> {
        Log.d(TAG, "getAvailableFilters: 获取相机滤镜列表")
        return FilterType.getCameraFilters()
    }

    /**
     * 获取当前选中的滤镜
     */
    override fun getCurrentFilter(): Flow<FilterType> = _currentFilter

    /**
     * 设置当前滤镜
     *
     * 同时更新GPUImageFilter实例
     */
    override suspend fun setCurrentFilter(filterType: FilterType) {
        Log.d(TAG, "setCurrentFilter: 设置滤镜 $filterType")
        _currentFilter.value = filterType

        // 创建对应的GPUImageFilter
        currentGpuFilter = GPUImageFilterFactory.createFilter(filterType.name, context)

        // 如果GPUImage已初始化，更新滤镜
        gpuImage?.setFilter(currentGpuFilter)
        Log.d(TAG, "setCurrentFilter: GPUImageFilter已更新")
    }

    /**
     * 设置滤镜强度
     *
     * 控制滤镜效果的强度（0.0~1.0）
     * 0.0 = 完全原图
     * 1.0 = 完全滤镜效果
     *
     * @param intensity 滤镜强度（0.0~1.0）
     */
    override suspend fun setFilterIntensity(intensity: Float) {
        val clampedIntensity = intensity.coerceIn(0f, 1f)
        Log.d(TAG, "setFilterIntensity: 设置强度 $clampedIntensity")
        _currentIntensity = clampedIntensity
    }

    /**
     * 获取当前滤镜强度
     *
     * @return 当前强度值（0.0~1.0）
     */
    override fun getCurrentIntensity(): Float = _currentIntensity

    /**
     * 获取滤镜预览缩略图
     *
     * 使用GPUImage生成滤镜效果的缩略图
     *
     * @param filterType 滤镜类型
     * @param sourceBitmap 源图片
     * @return 应用滤镜后的缩略图
     */
    override suspend fun getFilterThumbnail(
        filterType: FilterType,
        sourceBitmap: Bitmap
    ): Bitmap? = withContext(Dispatchers.Default) {
        Log.d(TAG, "getFilterThumbnail: 生成滤镜缩略图 filterType=$filterType")
        // 走 applyFilterCore 而不是 applyGpuFilterCore：
        // 水印类滤镜 useGpu=false，走 GPU 分支只会得到原图，
        // 导致选择器里所有水印项缩略图一模一样、看不出效果。
        // 也刻意不走 applyFilterInternal：那里会叠加设置里开启的信息水印，
        // 会让每一个滤镜缩略图都糊上一块信息面板。
        applyFilterCore(filterType, sourceBitmap).also { result ->
            if (result != null) {
                Log.d(TAG, "getFilterThumbnail: 缩略图生成成功")
            } else {
                Log.e(TAG, "getFilterThumbnail: 缩略图生成失败")
            }
        }
    }

    /**
     * 应用滤镜到图像
     *
     * 使用GPUImage将滤镜效果应用到Bitmap
     *
     * @param filterType 滤镜类型
     * @param sourceBitmap 源图片
     * @return 应用滤镜后的图片
     */
    override suspend fun applyFilterToBitmap(
        filterType: FilterType,
        sourceBitmap: Bitmap
    ): Bitmap? = withContext(Dispatchers.Default) {
        Log.d(TAG, "applyFilterToBitmap: 应用滤镜 filterType=$filterType")
        applyFilterInternal(filterType, sourceBitmap)
    }

    /**
     * 滤镜应用的内部实现（统一逻辑）
     *
     * 处理NONE滤镜、水印类型和GPU滤镜
     * 支持滤镜强度控制（0.0~1.0）
     *
     * @param filterType 滤镜类型
     * @param sourceBitmap 源图片
     * @return 应用滤镜后的图片
     */
    private fun applyFilterInternal(
        filterType: FilterType,
        sourceBitmap: Bitmap,
        forPreview: Boolean = false
    ): Bitmap? {
        // 第一步：算滤镜结果
        val filtered = applyFilterCore(filterType, sourceBitmap)
        // 第二步：按设置叠加信息水印（与滤镜选择相互独立，类似相机的日期印字）
        return decorateWithInfoWatermarkIfNeeded(filterType, filtered, forPreview)
    }

    /**
     * 滤镜核心逻辑（不含信息水印叠加）
     *
     * 处理NONE滤镜、水印类型和GPU滤镜
     * 支持滤镜强度控制（0.0~1.0）
     *
     * @param filterType 滤镜类型
     * @param sourceBitmap 源图片
     * @return 应用滤镜后的图片
     */
    private fun applyFilterCore(filterType: FilterType, sourceBitmap: Bitmap): Bitmap? {
        // 如果是原图滤镜，直接返回原图
        if (filterType == FilterType.NONE) {
            Log.d(TAG, "applyFilterCore: 原图滤镜，直接返回")
            return sourceBitmap
        }

        // 水印类型用Canvas绘制，且不受滤镜强度影响
        // 注意：这个判断必须在「强度为0」之前。水印自己不支持强度调节，
        // 若先判强度，用户把强度拖到 0 就会连水印一起消失。
        if (FilterType.isWatermarkType(filterType)) {
            Log.d(TAG, "applyFilterCore: 水印滤镜，应用Canvas水印")
            return applyWatermarkToBitmap(filterType, sourceBitmap)
        }

        // 如果强度为0，直接返回原图
        if (_currentIntensity <= 0f) {
            Log.d(TAG, "applyFilterCore: 强度为0，返回原图")
            return sourceBitmap
        }

        // 应用GPU滤镜
        val filteredBitmap = applyGpuFilterCore(filterType, sourceBitmap) ?: return sourceBitmap

        // 如果强度为1，直接返回滤镜图
        if (_currentIntensity >= 1f) {
            Log.d(TAG, "applyFilterCore: 强度为1，返回滤镜图")
            return filteredBitmap
        }

        // 强度混合：将原图和滤镜图按比例混合
        Log.d(TAG, "applyFilterCore: 强度混合 intensity=$_currentIntensity")
        return blendBitmaps(sourceBitmap, filteredBitmap, _currentIntensity)
    }

    /**
     * 按设置叠加信息水印
     *
     * 设置页「信息水印」开关打开时，给每张照片（含预览）叠加经纬度/地址/时间/天气/备注面板。
     * 若用户已经显式选了某个水印滤镜，则不再叠加，避免两层水印打架。
     */
    private fun decorateWithInfoWatermarkIfNeeded(
        filterType: FilterType,
        bitmap: Bitmap?,
        forPreview: Boolean = false
    ): Bitmap? {
        if (bitmap == null) return null
        if (!watermarkInfoProvider.isInfoWatermarkEnabled()) return bitmap
        if (FilterType.isWatermarkType(filterType)) return bitmap            // 已选水印，避免重复叠加

        // 预览链路每帧调用，不打日志
        return applyWatermarkToBitmap(FilterType.WATERMARK_INFO, bitmap, forPreview)
    }

    /**
     * 混合两张Bitmap
     *
     * 使用Canvas和Paint的alpha混合实现
     * result = original * (1 - intensity) + filtered * intensity
     *
     * @param original 原图
     * @param filtered 滤镜处理后的图
     * @param intensity 滤镜强度（0.0~1.0）
     * @return 混合后的图片
     */
    private fun blendBitmaps(original: Bitmap, filtered: Bitmap, intensity: Float): Bitmap {
        // 创建结果Bitmap（使用原图尺寸）
        val result = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // 先绘制原图（底层）
        canvas.drawBitmap(original, 0f, 0f, null)

        // 再绘制滤镜图（带透明度覆盖）
        val paint = Paint().apply {
            alpha = (intensity * 255).toInt()                          // 设置滤镜层透明度
        }
        canvas.drawBitmap(filtered, 0f, 0f, paint)

        Log.d(TAG, "blendBitmaps: 混合完成 intensity=$intensity alpha=${paint.alpha}")
        return result
    }

    /**
     * 带强度参数的滤镜应用（用于实时预览）
     *
     * 供CameraRepository调用，支持动态强度调整
     *
     * @param filterType 滤镜类型
     * @param sourceBitmap 源图片
     * @param intensity 滤镜强度（0.0~1.0）
     * @return 应用滤镜后的图片
     */
    fun applyFilterWithIntensity(filterType: FilterType, sourceBitmap: Bitmap, intensity: Float): Bitmap? {
        val originalIntensity = _currentIntensity
        _currentIntensity = intensity.coerceIn(0f, 1f)
        val result = applyFilterInternal(filterType, sourceBitmap)
        _currentIntensity = originalIntensity                          // 恢复原强度
        return result
    }

    /**
     * GPU滤镜应用核心方法
     *
     * 创建临时GPUImage实例应用滤镜，避免线程竞争
     *
     * @param filterType 滤镜类型
     * @param sourceBitmap 源图片
     * @return 应用滤镜后的图片，失败返回null
     */
    private fun applyGpuFilterCore(filterType: FilterType, sourceBitmap: Bitmap): Bitmap? {
        return try {
            val tempGpuImage = GPUImage(context)                              // 创建临时实例避免线程竞争
            tempGpuImage.setImage(sourceBitmap)
            val filter = GPUImageFilterFactory.createFilter(filterType.name, context)
            tempGpuImage.setFilter(filter)
            val result = tempGpuImage.bitmapWithFilterApplied
            Log.d(TAG, "applyGpuFilterCore: 滤镜应用成功 size=${result?.width}x${result?.height}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "applyGpuFilterCore: 滤镜应用失败", e)
            null
        }
    }

    /**
     * 初始化滤镜引擎
     *
     * 创建GPUImage实例，准备滤镜渲染
     */
    override suspend fun initFilterEngine(width: Int, height: Int) {
        withContext(Dispatchers.Main) {
            Log.d(TAG, "initFilterEngine: 初始化滤镜引擎 ${width}x${height}")
            renderWidth = width
            renderHeight = height

            // 初始化GPUImage
            if (gpuImage == null) {
                gpuImage = GPUImage(context)
            }

            // 设置默认滤镜
            currentGpuFilter = GPUImageFilterFactory.createFilter(FilterType.NONE.name, context)
            gpuImage?.setFilter(currentGpuFilter)

            isEngineInitialized = true
            Log.d(TAG, "initFilterEngine: 滤镜引擎初始化完成")
        }
    }

    /**
     * 释放滤镜资源
     */
    override suspend fun releaseFilterEngine() {
        withContext(Dispatchers.Main) {
            Log.d(TAG, "releaseFilterEngine: 释放滤镜引擎")

            // 清理GPUImage
            gpuImage?.deleteImage()
            gpuImage = null
            currentGpuFilter = null

            isEngineInitialized = false
            Log.d(TAG, "releaseFilterEngine: 滤镜引擎已释放")
        }
    }

    /**
     * 获取当前GPUImageFilter实例
     *
     * 供外部使用（如GPUImageView）
     */
    fun getCurrentGpuFilter(): GPUImageFilter? = currentGpuFilter

    /**
     * 获取GPUImage实例
     *
     * 供外部使用
     */
    fun getGpuImage(): GPUImage? = gpuImage

    /**
     * 同步应用滤镜到图像（用于实时预览，避免协程开销）
     *
     * 直接调用applyFilterInternal，无协程包装
     *
     * @param filterType 滤镜类型
     * @param sourceBitmap 源图片
     * @return 应用滤镜后的图片
     */
    override fun applyFilterToBitmapSync(filterType: FilterType, sourceBitmap: Bitmap): Bitmap? {
        // 预览链路每帧调用，不打日志；forPreview=true 让水印锚定在取景窗可见区内
        return applyFilterInternal(filterType, sourceBitmap, forPreview = true)
    }

    /**
     * 同步应用滤镜到图像（指定强度）
     *
     * 编辑器专用。加锁是因为 applyFilterWithIntensity 会临时改写共享的
     * _currentIntensity，不加锁时并发调用会互相看到对方的强度。
     */
    @Synchronized
    override fun applyFilterToBitmapSync(
        filterType: FilterType,
        sourceBitmap: Bitmap,
        intensity: Float
    ): Bitmap? {
        Log.d(
            TAG,
            "applyFilterToBitmapSync: 同步应用滤镜 filterType=$filterType, intensity=$intensity"
        )
        return applyFilterWithIntensity(filterType, sourceBitmap, intensity)
    }

    /**
     * 信息水印开关是否打开
     */
    override fun isInfoWatermarkEnabled(): Boolean = watermarkInfoProvider.isInfoWatermarkEnabled()

    // ==================== 水印相关 ====================

    /**
     * 运行期临时覆盖备注文字（null 表示使用设置页里填的备注）
     *
     * 保留这个方法是为了给后续"按批次设置备注"留口子，
     * 正常情况下备注统一来自设置页，由 WatermarkInfoProvider 监听。
     */
    private var customTextOverride: String? = null

    /**
     * 设置自定义水印文字（覆盖设置页的备注）
     *
     * @param text 自定义文字，传空串表示恢复使用设置页备注
     */
    fun setCustomWatermarkText(text: String) {
        customTextOverride = text.takeIf { it.isNotBlank() }
        Log.d(TAG, "setCustomWatermarkText: 覆盖备注=${customTextOverride ?: "(恢复设置页备注)"}")
    }

    /**
     * 取当前水印数据
     *
     * 数据由 WatermarkInfoProvider 在后台维护成快照，这里同步读取，不阻塞渲染。
     * 同时顺手触发一次按需刷新（内部有频率保护，未过期时直接返回）。
     */
    private fun currentWatermarkData(): WatermarkRenderer.WatermarkData {
        watermarkInfoProvider.refreshIfStale()
        val snapshot = watermarkInfoProvider.snapshot()
        return customTextOverride?.let { snapshot.copy(customText = it) } ?: snapshot
    }

    /**
     * 应用水印到Bitmap
     *
     * 将FilterType转换为WatermarkType并调用WatermarkRenderer
     *
     * @param filterType 滤镜类型（必须是水印类型）
     * @param sourceBitmap 源图片
     * @return 带水印的图片
     */
    private fun applyWatermarkToBitmap(
        filterType: FilterType,
        sourceBitmap: Bitmap,
        forPreview: Boolean = false
    ): Bitmap {
        // 时间戳取拍照/预览当下的时间，其余字段（经纬度/地址/天气/备注）取快照。
        // 预览路径把取景窗四边裁切比例传给渲染器，水印锚点收进可见区；
        // 成片没有裁切，inset 恒为 0。
        val insets = if (forPreview) previewEdgeInsets(sourceBitmap) else null
        val data = currentWatermarkData().copy(
            timestamp = System.currentTimeMillis(),
            edgeInsetTop = insets?.top ?: 0f,
            edgeInsetBottom = insets?.bottom ?: 0f,
            edgeInsetLeft = insets?.left ?: 0f,
            edgeInsetRight = insets?.right ?: 0f
        )

        // 转换FilterType到WatermarkType
        val watermarkType = filterTypeToWatermarkType(filterType)

        // 预览链路每帧都会走到这里，不再打日志（30fps × 2 条日志本身就是可观的开销）

        return WatermarkRenderer.applyWatermark(sourceBitmap, watermarkType, data)
    }

    /** 预览取景框实际尺寸（px），UI 层测量后上报；0 表示尚未测量 */
    @Volatile
    private var previewBoxWidth = 0

    @Volatile
    private var previewBoxHeight = 0

    override fun setPreviewBoxSize(widthPx: Int, heightPx: Int) {
        if (widthPx != previewBoxWidth || heightPx != previewBoxHeight) {
            Log.d(TAG, "setPreviewBoxSize: ${widthPx}x$heightPx")
        }
        previewBoxWidth = widthPx
        previewBoxHeight = heightPx
    }

    /** 取景窗相对位图四边的裁切比例 */
    private class PreviewEdgeInsets(
        val top: Float,
        val bottom: Float,
        val left: Float,
        val right: Float
    )

    /**
     * 预览取景窗相对分析流位图的四边裁切比例
     *
     * 取景窗与位图比例不一致时，ContentScale.Crop 会裁掉超出部分：
     * 横屏全屏铺满的窗口对 4:3 位图上下各裁约 20%，全屏竖屏则左右裁。
     * 水印必须锚定在可见区内，否则会被切掉一半。
     *
     * 裁切比例用 UI 层上报的取景框实际尺寸计算（[setPreviewBoxSize]）；
     * 尚未上报时按"无裁切"处理——宁可水印贴角，也不要在明明没裁切的
     * 场景里把它推离角落（竖屏 4:3 取景框与位图同比例，无任何裁切）。
     *
     * @return 每边裁切比例（0 ~ 0.45）
     */
    private fun previewEdgeInsets(bitmap: Bitmap): PreviewEdgeInsets {
        val boxW = previewBoxWidth.toFloat()
        val boxH = previewBoxHeight.toFloat()
        val bitmapW = bitmap.width.toFloat()
        val bitmapH = bitmap.height.toFloat()
        if (boxW <= 0f || boxH <= 0f || bitmapW <= 0f || bitmapH <= 0f) {
            return PreviewEdgeInsets(0f, 0f, 0f, 0f)
        }
        val scale = maxOf(boxW / bitmapW, boxH / bitmapH)
        val displayedW = bitmapW * scale
        val displayedH = bitmapH * scale
        val horizontal = ((displayedW - boxW) / displayedW / 2f).coerceIn(0f, 0.45f)
        val vertical = ((displayedH - boxH) / displayedH / 2f).coerceIn(0f, 0.45f)
        // 注意：这里只补偿"取景窗裁切"，不避让两侧控制列——
        // 水印必须贴在取景窗左下角（用户明确的预期）；控制列声明在预览层之后，
        // 按钮画在水印上方、照常可点。
        return PreviewEdgeInsets(vertical, vertical, horizontal, horizontal)
    }

    /**
     * FilterType到WatermarkType的映射
     *
     * @param filterType 滤镜类型
     * @return 对应的水印类型
     */
    private fun filterTypeToWatermarkType(filterType: FilterType): WatermarkRenderer.WatermarkType {
        return when (filterType) {
            FilterType.WATERMARK_TIMESTAMP -> WatermarkRenderer.WatermarkType.TIMESTAMP
            FilterType.WATERMARK_DATE -> WatermarkRenderer.WatermarkType.DATE
            FilterType.WATERMARK_DEVICE -> WatermarkRenderer.WatermarkType.DEVICE
            FilterType.WATERMARK_CUSTOM -> WatermarkRenderer.WatermarkType.CUSTOM
            FilterType.WATERMARK_INFO -> WatermarkRenderer.WatermarkType.INFO
            FilterType.WATERMARK_GRID -> WatermarkRenderer.WatermarkType.GRID
            FilterType.WATERMARK_STAMP -> WatermarkRenderer.WatermarkType.STAMP
            FilterType.WATERMARK_MINI -> WatermarkRenderer.WatermarkType.MINI
            else -> WatermarkRenderer.WatermarkType.TIMESTAMP                 // 默认时间戳
        }
    }
}
