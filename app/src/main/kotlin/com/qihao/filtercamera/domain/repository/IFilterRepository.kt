/**
 * IFilterRepository.kt - 滤镜仓库接口
 *
 * 定义滤镜操作的抽象接口
 * 包含滤镜加载、应用和管理功能
 *
 * @author qihao
 * @since 2.0.0
 */
package com.qihao.filtercamera.domain.repository

import android.graphics.Bitmap
import com.qihao.filtercamera.domain.model.FilterType
import kotlinx.coroutines.flow.Flow

/**
 * 滤镜仓库接口
 * 管理滤镜资源和效果应用
 */
interface IFilterRepository {

    /**
     * 获取所有可用滤镜列表
     * @return 滤镜类型列表
     */
    fun getAvailableFilters(): List<FilterType>

    /**
     * 获取当前选中的滤镜
     * @return 当前滤镜类型Flow
     */
    fun getCurrentFilter(): Flow<FilterType>

    /**
     * 设置当前滤镜
     * @param filterType 滤镜类型
     */
    suspend fun setCurrentFilter(filterType: FilterType)

    /**
     * 设置滤镜强度
     *
     * 控制滤镜效果的强度（0.0~1.0）
     * 实现原理：将原图与滤镜处理后的图混合
     * intensity=0.0 表示完全原图，intensity=1.0 表示完全滤镜效果
     *
     * @param intensity 滤镜强度（0.0~1.0）
     */
    suspend fun setFilterIntensity(intensity: Float)

    /**
     * 获取当前滤镜强度
     * @return 当前强度值（0.0~1.0）
     */
    fun getCurrentIntensity(): Float

    /**
     * 获取滤镜预览缩略图
     * @param filterType 滤镜类型
     * @param sourceBitmap 源图像
     * @return 应用滤镜后的缩略图
     */
    suspend fun getFilterThumbnail(filterType: FilterType, sourceBitmap: Bitmap): Bitmap?

    /**
     * 应用滤镜到图像
     * @param filterType 滤镜类型
     * @param sourceBitmap 源图像
     * @return 应用滤镜后的图像
     */
    suspend fun applyFilterToBitmap(filterType: FilterType, sourceBitmap: Bitmap): Bitmap?

    /**
     * 同步应用滤镜到图像（非挂起版本）
     *
     * 用于需要在非协程上下文中调用的场景
     * 注意：此方法会在调用线程同步执行，可能会阻塞
     *
     * @param filterType 滤镜类型
     * @param sourceBitmap 源图像
     * @return 应用滤镜后的图像
     */
    fun applyFilterToBitmapSync(filterType: FilterType, sourceBitmap: Bitmap): Bitmap?

    /**
     * 同步应用滤镜到图像（指定强度）
     *
     * 编辑器必须走这个重载：不带强度的版本读的是仓库里的单例强度
     * （相机页在用），编辑器调它等于"用相机的强度渲染编辑器的图"，
     * 界面上的强度滑块怎么拖都没反应。
     *
     * @param filterType 滤镜类型
     * @param sourceBitmap 源图像
     * @param intensity 滤镜强度（0.0~1.0）
     * @return 应用滤镜后的图像
     */
    fun applyFilterToBitmapSync(
        filterType: FilterType,
        sourceBitmap: Bitmap,
        intensity: Float
    ): Bitmap?

    /**
     * 设置页的「信息水印」开关是否打开
     *
     * 信息水印是独立于滤镜选择的叠加效果：开关打开时，即使当前滤镜是"原图"
     * （FilterType.NONE）也要叠加。因此拍照与预览链路在"无滤镜"的快捷分支里，
     * 需要先问一下这里，不能直接跳过。
     *
     * @return true 表示需要叠加信息水印
     */
    fun isInfoWatermarkEnabled(): Boolean

    /**
     * 上报预览取景框的实际显示尺寸（px）
     *
     * 全屏铺满等场景下，取景框与水印位图比例不一致，Crop 会裁掉位图边缘，
     * 水印必须锚定在"看得见的区域"内。裁切比例取决于取景框的真实尺寸，
     * 只有 UI 层测得到，这里由界面在尺寸变化时上报。
     *
     * @param widthPx 取景框宽度（0 表示尚未测量，水印按无裁切处理）
     * @param heightPx 取景框高度
     */
    fun setPreviewBoxSize(widthPx: Int, heightPx: Int)

    /**
     * 初始化滤镜引擎
     * @param width 渲染宽度
     * @param height 渲染高度
     */
    suspend fun initFilterEngine(width: Int, height: Int)

    /**
     * 释放滤镜资源
     */
    suspend fun releaseFilterEngine()
}
