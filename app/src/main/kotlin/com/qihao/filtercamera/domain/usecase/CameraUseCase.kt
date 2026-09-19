/**
 * CameraUseCase.kt - 统一相机用例
 *
 * 合并所有相机相关操作到单一用例类，提供简洁优雅的API
 * 包含：拍照、录像、切换摄像头、滤镜应用、美颜设置
 *
 * 设计原则：
 * - 单一入口点，降低调用复杂度
 * - 使用扩展函数提供便捷操作
 * - 保持职责清晰，委托给对应Repository
 *
 * @author qihao
 * @since 2.0.0
 */
package com.qihao.filtercamera.domain.usecase

import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.qihao.filtercamera.domain.model.BatchConfig
import com.qihao.filtercamera.domain.model.BeautyLevel
import com.qihao.filtercamera.domain.model.CameraLens
import com.qihao.filtercamera.domain.model.FilterType
import com.qihao.filtercamera.domain.repository.IBatchRepository
import com.qihao.filtercamera.domain.repository.ICameraRepository
import com.qihao.filtercamera.domain.repository.IFilterRepository
import com.qihao.filtercamera.domain.repository.IMediaRepository
import com.qihao.filtercamera.domain.repository.ISettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一相机用例
 *
 * 整合所有相机操作，提供简洁的API接口
 *
 * @param camera 相机仓库 - 负责CameraX操作
 * @param filter 滤镜仓库 - 负责滤镜状态管理（水印也是滤镜的一种，在拍照时已烘焙进图像）
 * @param media 媒体仓库 - 负责文件存储
 * @param batchRepository 批次仓库 - 负责批次命名与序号计数
 */
@Singleton
class CameraUseCase @Inject constructor(
    private val camera: ICameraRepository,
    private val filter: IFilterRepository,
    private val media: IMediaRepository,
    private val batchRepository: IBatchRepository,
    private val settingsRepository: ISettingsRepository
) {
    // ==================== 相机绑定 ====================

    /**
     * 绑定相机到生命周期和预览视图
     *
     * @param videoMode 本次绑定是否为录像模式（见 ICameraRepository.bindCamera）
     */
    suspend fun bindCamera(
        owner: LifecycleOwner,
        previewView: PreviewView,
        videoMode: Boolean
    ) = camera.bindCamera(owner, previewView, videoMode)

    // ==================== 拍照操作 ====================

    /**
     * 执行拍照并保存到相册
     *
     * 批次拍摄流程：
     * 1. 取当前选中批次（未选批次时为 null）
     * 2. CameraX 拍照（滤镜、美颜、水印均在 CameraRepository.takePhoto 内烘焙进图像）
     * 3. 按批次命名 + 归档目录存盘；无批次时回落默认命名
     * 4. **存盘成功之后**才把批次序号 +1 并持久化
     *
     * 第 4 步的顺序是硬约束：
     * 若先递增再存盘，存图失败就会跳号；而序号一旦跳号，
     * 后续照片的文件名会出现空缺，且 MediaStore 遇到重名会自动加 " (1)" 后缀，
     * 反而把连续编号彻底打乱。
     *
     * @return 照片Uri的Result
     */
    suspend fun takePhoto(): Result<Uri> = runCatching {
        val totalStart = System.currentTimeMillis()
        val batch = currentBatchOrNull()                              // 拍照前取当前批次
        val photoPath = camera.takePhoto().getOrThrow()               // 调用CameraX拍照
        val captureMs = System.currentTimeMillis() - totalStart
        val photoFile = File(photoPath)                               // 获取临时文件
        require(photoFile.exists()) { "照片文件不存在: $photoPath" }   // 校验文件存在
        val imageData = photoFile.readBytes()                         // 读取图片数据

        // 「自动保存」关闭时不写入系统相册，改存到应用私有目录。
        // 仍然要把照片留下 —— 静默丢弃比"没保存"更糟。
        val autoSave = isAutoSaveEnabled()
        val uri = if (autoSave) {
            // 按批次命名与归档；未选批次时回落默认命名 IMG_yyyyMMdd_HHmmss.jpg
            if (batch != null) {
                media.savePhoto(imageData, batch).getOrThrow()
            } else {
                media.savePhoto(imageData, media.generatePhotoFileName()).getOrThrow()
            }
        } else {
            val name = if (batch != null) {
                batch.nextFileName()
            } else {
                "${media.generatePhotoFileName()}.jpg"
            }
            media.savePhotoToAppPrivate(imageData, name).getOrThrow()
        }

        photoFile.delete()                                            // 清理临时文件
        android.util.Log.d(
            "CameraUseCase",
            "takePhoto: [耗时] 拍照处理=${captureMs}ms, 写入相册=${System.currentTimeMillis() - totalStart - captureMs}ms, " +
                "总计=${System.currentTimeMillis() - totalStart}ms, 图片大小=${imageData.size / 1024}KB, 路径=$uri"
        )

        // 存盘成功后才递增序号
        if (batch != null) {
            advanceAfterShotQuietly(batch)
        }

        uri                                                           // 返回保存的Uri
    }

    /**
     * 是否启用自动保存（写入系统相册）
     *
     * 读失败时按"开启"处理：宁可能存下来，也不要因为读设置失败把照片弄丢。
     */
    private suspend fun isAutoSaveEnabled(): Boolean = try {
        settingsRepository.isAutoSaveEnabled().first()
    } catch (e: Exception) {
        android.util.Log.w("CameraUseCase", "isAutoSaveEnabled: 读取失败，按开启处理", e)
        true
    }

    /**
     * 作废上一张照片
     *
     * 语义是"这张拍坏了，用同一个名字重拍"，所以两件事必须一起做：
     * 1. 删掉刚存的那张废片（否则重拍会与它同名，MediaStore 会加 " (1)" 后缀）
     * 2. 把批次的名字序号回退一位
     *
     * 顺序：先删文件再回退计数。若先回退，而删文件失败，
     * 就会出现"序号退了但废片还在"——重拍时又撞名。反过来则最多是
     * "照片删了但序号没退"，重拍时序号跳一号，不会重名、不会丢照片。
     *
     * @return 操作结果
     */
    suspend fun undoLastShot(): Result<Unit> = runCatching {
        val lastUri = media.getLastSavedMediaUri().first()
            ?: throw IllegalStateException("没有可作废的照片（应用重启后无法追溯到上一张）")

        media.deleteMedia(lastUri).getOrThrow()
        android.util.Log.d("CameraUseCase", "undoLastShot: 已删除废片 $lastUri")

        val batch = currentBatchOrNull()
        if (batch != null) {
            batchRepository.decrementCounter(batch.id).getOrThrow()
            android.util.Log.d(
                "CameraUseCase",
                "undoLastShot: 批次「${batch.name}」序号已回退，下一张 ${batch.withCounterDecremented().nextFileName()}"
            )
        }
    }

    /**
     * 重拍某一项
     *
     * 与"跳拍"的区别：这一项已经拍过，所以先删掉原来那张照片，再把指针指回它。
     * 顺序：先删照片再改状态 —— 删除失败就不该动状态，
     * 否则会出现"状态说没拍、但旧照片还在"，重拍时撞名。
     *
     * @param batch 当前批次
     * @param index 要重拍的名字下标
     * @return 是否找到了旧照片（true 表示已删除；false 表示本来就没拍过）
     */
    suspend fun reshootName(batch: com.qihao.filtercamera.domain.model.BatchConfig, index: Int): Result<Boolean> =
        runCatching {
            val names = batch.effectiveNameList
            require(index in names.indices) { "名字下标越界: $index" }

            val expectedName = batch.buildCustomFileName(names[index])
            val deleted = deletePhotosNamed(batch, expectedName)

            batchRepository.markForReshoot(batch.id, index).getOrThrow()
            android.util.Log.d(
                "CameraUseCase",
                "reshootName: 第 $index 项(${names[index]}) 标记为待重拍，删除旧照片=$deleted"
            )
            deleted
        }

    /**
     * 按文件名删除批次目录下对应的照片
     *
     * 名称被 MediaStore 加过 " (1)" 后缀的也一并清理，
     * 否则重拍后相册里会残留几张同名废片。
     *
     * @return 是否删除过至少一张
     */
    private suspend fun deletePhotosNamed(
        batch: com.qihao.filtercamera.domain.model.BatchConfig,
        expectedName: String
    ): Boolean {
        val stem = expectedName.substringBeforeLast('.')
        val photos = media.getPhotosInDir(batch.safeDirName)
        val targets = photos.filter { photo ->
            photo.name == expectedName ||
                (photo.name.startsWith("$stem (") && photo.name.endsWith(".jpg"))
        }
        targets.forEach { media.deleteMedia(it.uri) }
        android.util.Log.d(
            "CameraUseCase",
            "deletePhotosNamed: $expectedName 匹配 ${targets.size} 张 -> ${targets.map { it.name }}"
        )
        return targets.isNotEmpty()
    }

    /**
     * 是否可以作废上一张
     *
     * 只有本进程内拍过照片才可以 —— 重启后拿不到"上一张"的 Uri，
     * 盲目按序号回退可能会删错文件，所以宁可禁用。
     */
    suspend fun canUndoLastShot(): Boolean = try {
        media.getLastSavedMediaUri().first() != null
    } catch (e: Exception) {
        false
    }

    /**
     * 开始新一轮拍摄
     */
    suspend fun startNewRound(batchId: String): Result<Unit> =
        batchRepository.startNewRound(batchId)

    /**
     * 读取当前选中批次
     *
     * 持久层读失败不应阻断拍照，降级为"不使用批次"。
     */
    private suspend fun currentBatchOrNull(): BatchConfig? = try {
        batchRepository.currentBatch.first()
    } catch (e: Exception) {
        null
    }

    /**
     * 递增批次已拍张数
     *
     * 照片此时已经落盘，计数器写失败属于极小概率事件（磁盘满/IO异常）。
     * 这里选择"吞掉异常并记录"而不是让整次拍照报失败 —— 向用户谎报"拍照失败"
     * 比极少数情况下文件名重复更糟；真的失败了日志里也有据可查。
     */
    private suspend fun advanceAfterShotQuietly(batch: BatchConfig) {
        batchRepository.advanceAfterShot(batch.id).onFailure { error ->
            android.util.Log.e(
                "CameraUseCase",
                "advanceAfterShotQuietly: 批次计数推进失败 id=${batch.id}，" +
                    "该照片已保存为 ${batch.nextFileName()}，" +
                    "下一张可能出现重名（MediaStore 会追加 \" (1)\" 后缀）",
                error
            )
        }
    }

    // ==================== 录像操作 ====================

    /**
     * 切换用例绑定模式（拍照 / 录像）
     *
     * 相机页切换模式时调用。CameraX 的拍照与录像用例不能无脑同绑，
     * 必须在模式切换时重绑，详见 ICameraRepository.setVideoMode。
     */
    suspend fun setVideoMode(videoMode: Boolean): Result<Unit> = camera.setVideoMode(videoMode)

    /**
     * 声明上层是否在消费分析帧（人像/文档模式的叠加层需要）
     */
    fun setAnalysisConsumerActive(active: Boolean) = camera.setAnalysisConsumerActive(active)

    /**
     * 开始录像
     * @return 操作结果
     */
    suspend fun startRecording(): Result<Unit> = camera.startRecording()

    /**
     * 停止录像并保存到相册
     *
     * 选中批次时，视频与照片落在**同一个批次目录**里 —— 素材散到别的目录会让
     * 事后按批次整理变得很痛苦；但视频不参与照片的序号命名，用带时间戳的
     * VID_ 文件名，也不消耗序号（否则一段视频会顶掉一个拍摄项）。
     *
     * @return 视频Uri的Result
     */
    suspend fun stopRecording(): Result<Uri> = runCatching {
        val videoPath = camera.stopRecording().getOrThrow()           // 停止录像获取路径
        val fileName = media.generateVideoFileName()                  // 生成文件名
        media.saveVideo(videoPath, fileName, currentBatchOrNull())    // 保存到MediaStore
            .getOrThrow()
    }

    /**
     * 获取录像状态Flow
     */
    fun isRecording(): Flow<Boolean> = camera.isRecording()

    // ==================== 摄像头操作 ====================

    /**
     * 切换到指定摄像头
     * @param lens 目标镜头
     */
    suspend fun switchTo(lens: CameraLens): Result<Unit> = camera.switchCamera(lens)

    /**
     * 前后摄像头切换
     * @return 操作结果
     */
    suspend fun toggleCamera(): Result<Unit> = runCatching {
        val current = camera.getCurrentLens().first()                 // 获取当前镜头
        val target = if (current == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        camera.switchCamera(target).getOrThrow()                      // 切换到目标镜头
    }

    /**
     * 获取当前镜头Flow
     */
    fun currentLens(): Flow<CameraLens> = camera.getCurrentLens()

    // ==================== 滤镜操作 ====================

    /**
     * 应用滤镜
     * @param type 滤镜类型
     */
    suspend fun applyFilter(type: FilterType): Result<Unit> = runCatching {
        filter.setCurrentFilter(type)                                 // 更新滤镜状态
        camera.applyFilter(type).getOrThrow()                         // 应用到相机预览
    }

    /**
     * 获取当前滤镜Flow
     */
    fun currentFilter(): Flow<FilterType> = filter.getCurrentFilter()

    /**
     * 获取所有可用滤镜
     */
    fun availableFilters(): List<FilterType> = filter.getAvailableFilters()

    /**
     * 获取指定分组的滤镜列表
     */
    fun filtersByGroup(group: com.qihao.filtercamera.domain.model.FilterGroup): List<FilterType> =
        FilterType.getFiltersByGroup(group)

    /**
     * 上报预览取景框的实际显示尺寸（供预览水印锚定可见区，见 IFilterRepository）
     */
    fun setPreviewBoxSize(widthPx: Int, heightPx: Int) =
        filter.setPreviewBoxSize(widthPx, heightPx)

    /**
     * 获取滤镜帧流（用于实时预览显示）
     *
     * 当应用滤镜时返回处理后的Bitmap，否则返回null
     */
    fun filteredFrame(): Flow<Bitmap?> = camera.getFilteredFrame()

    /**
     * 获取原始预览帧流（用于生成滤镜缩略图）
     *
     * 此帧不受当前滤镜影响，可用于生成各种滤镜的预览缩略图
     */
    fun rawPreviewFrame(): Flow<Bitmap?> = camera.getRawPreviewFrame()

    /**
     * 获取滤镜预览缩略图
     *
     * 使用GPUImage为指定滤镜生成预览缩略图
     *
     * @param filterType 滤镜类型
     * @param sourceBitmap 源图片（建议使用60x60小图）
     * @return 应用滤镜后的缩略图
     */
    suspend fun getFilterThumbnail(filterType: FilterType, sourceBitmap: Bitmap): Bitmap? =
        filter.getFilterThumbnail(filterType, sourceBitmap)

    /**
     * 设置滤镜强度
     *
     * 控制滤镜效果的强度（0.0~1.0）
     * 0.0 = 原图（无滤镜效果）
     * 1.0 = 全强度滤镜效果
     *
     * @param intensity 滤镜强度（0.0~1.0）
     */
    suspend fun setFilterIntensity(intensity: Float) {
        filter.setFilterIntensity(intensity.coerceIn(0f, 1f))
    }

    // ==================== 美颜操作 ====================

    /**
     * 设置美颜等级
     * @param level 美颜等级枚举
     */
    suspend fun setBeauty(level: BeautyLevel): Result<Unit> =
        camera.setBeautyLevel(level.intensity)

    /**
     * 设置美颜强度（直接数值）
     * @param intensity 强度值（0.0-1.0）
     */
    suspend fun setBeautyIntensity(intensity: Float): Result<Unit> =
        camera.setBeautyLevel(intensity.coerceIn(0f, 1f))

    // ==================== 变焦与对焦控制 ====================

    /**
     * 设置变焦倍数
     * @param zoom 变焦倍数
     */
    suspend fun setZoom(zoom: Float): Result<Unit> =
        camera.setZoom(zoom)

    /**
     * 触发自动对焦
     * 在预览中心点进行自动对焦
     */
    suspend fun autoFocus(): Result<Unit> =
        camera.autoFocus()

    /**
     * 触摸对焦 - 在指定坐标点进行对焦和测光
     *
     * 用户点击预览区域时调用此方法，实现点触对焦功能
     * 同时在该点进行测光，优化曝光效果
     *
     * @param x 归一化X坐标 (0.0~1.0，0为左边缘，1为右边缘)
     * @param y 归一化Y坐标 (0.0~1.0，0为上边缘，1为下边缘)
     * @return 操作结果
     */
    suspend fun focusAtPoint(x: Float, y: Float): Result<Unit> =
        camera.focusAtPoint(x, y)

    /**
     * 获取变焦范围
     * @return 设备支持的变焦范围Flow
     */
    fun getZoomRange(): Flow<com.qihao.filtercamera.domain.repository.ZoomRange> =
        camera.getZoomRange()

    // ==================== 高级设置控制 ====================

    /**
     * 设置HDR模式
     */
    suspend fun setHdrMode(mode: com.qihao.filtercamera.domain.model.HdrMode): Result<Unit> =
        camera.setHdrMode(mode)

    /**
     * 设置夜景模式
     *
     * 控制夜景拍摄功能：
     * - ON: 强制开启夜景模式
     * - OFF: 关闭夜景模式
     * - AUTO: 根据环境光自动判断是否启用
     *
     * @param mode 夜景模式
     * @return 操作结果
     */
    suspend fun setNightMode(mode: com.qihao.filtercamera.domain.model.NightMode): Result<Unit> =
        camera.setNightMode(mode)

    /**
     * 获取夜景处理进度流
     *
     * 返回当前夜景处理的阶段名称和进度（0.0~1.0）
     * null表示当前没有正在进行的夜景处理
     *
     * @return Pair<处理阶段名称, 进度>
     */
    fun getNightProcessingProgress(): Flow<Pair<String, Float>?> =
        camera.getNightProcessingProgress()

    /**
     * 设置微距模式
     */
    suspend fun setMacroMode(mode: com.qihao.filtercamera.domain.model.MacroMode): Result<Unit> =
        camera.setMacroMode(mode)

    /**
     * 设置画幅比例
     */
    suspend fun setAspectRatio(ratio: com.qihao.filtercamera.domain.model.AspectRatio): Result<Unit> =
        camera.setAspectRatio(ratio)

    // ==================== 专业模式参数控制 ====================

    /**
     * 设置曝光补偿
     * @param evIndex 曝光补偿索引（设备范围内的索引值）
     * @return 操作结果
     */
    suspend fun setExposureCompensation(evIndex: Int): Result<Unit> =
        camera.setExposureCompensation(evIndex)

    /**
     * 设置ISO感光度
     * @param iso ISO值，null表示自动ISO
     * @return 操作结果
     */
    suspend fun setIso(iso: Int?): Result<Unit> =
        camera.setIso(iso)

    /**
     * 设置白平衡模式
     * @param mode 白平衡模式枚举
     * @return 操作结果
     */
    suspend fun setWhiteBalance(mode: com.qihao.filtercamera.domain.model.WhiteBalanceMode): Result<Unit> =
        camera.setWhiteBalance(mode)

    /**
     * 设置对焦模式
     * @param mode 对焦模式枚举
     * @return 操作结果
     */
    suspend fun setFocusMode(mode: com.qihao.filtercamera.domain.model.FocusMode): Result<Unit> =
        camera.setFocusMode(mode)

    /**
     * 设置手动对焦距离
     * @param distance 对焦距离（0.0=最近，1.0=无穷远）
     * @return 操作结果
     */
    suspend fun setFocusDistance(distance: Float): Result<Unit> =
        camera.setFocusDistance(distance.coerceIn(0f, 1f))

    /**
     * 获取曝光补偿范围
     * @return Triple(最小索引, 最大索引, 步进值)
     */
    fun getExposureCompensationRange(): Triple<Int, Int, Float> =
        camera.getExposureCompensationRange()

    /**
     * 设置快门速度（曝光时间）
     *
     * 使用Camera2 SENSOR_EXPOSURE_TIME实现真实快门速度控制
     * 设置手动快门需要禁用自动曝光，建议配合ISO调整以获得正确曝光
     *
     * @param speed 快门速度（秒），null表示恢复自动曝光
     *              例如：1/4000s = 0.00025f, 1/30s = 0.0333f, 1s = 1.0f
     * @return 操作结果
     */
    suspend fun setShutterSpeed(speed: Float?): Result<Unit> =
        camera.setShutterSpeed(speed)

    /**
     * 获取设备支持的快门速度范围
     *
     * @return Pair(最小曝光时间纳秒, 最大曝光时间纳秒)，设备不支持时返回null
     */
    fun getExposureTimeRange(): Pair<Long, Long>? =
        camera.getExposureTimeRange()

    // ==================== 闪光灯控制 ====================

    /**
     * 设置闪光灯模式
     *
     * @param mode 闪光灯模式枚举
     * @return 操作结果
     */
    suspend fun setFlashMode(mode: com.qihao.filtercamera.domain.model.FlashMode): Result<Unit> =
        camera.setFlashMode(mode)

    /**
     * 获取当前闪光灯模式
     *
     * @return 当前闪光灯模式
     */
    fun getCurrentFlashMode(): com.qihao.filtercamera.domain.model.FlashMode =
        camera.getCurrentFlashMode()

    /**
     * 检查设备是否支持闪光灯
     *
     * @return true表示支持
     */
    fun hasFlashUnit(): Boolean =
        camera.hasFlashUnit()

    /**
     * 切换到下一个闪光灯模式
     *
     * 循环切换：OFF -> ON -> AUTO -> OFF
     *
     * @return 操作结果
     */
    suspend fun toggleFlashMode(): Result<com.qihao.filtercamera.domain.model.FlashMode> = runCatching {
        val current = camera.getCurrentFlashMode()
        val next = com.qihao.filtercamera.domain.model.FlashMode.next(current)
        camera.setFlashMode(next).getOrThrow()
        next
    }

    // ==================== 资源管理 ====================

    /**
     * 释放相机资源
     */
    suspend fun release() {
        camera.release()                                              // 释放CameraX资源
        filter.releaseFilterEngine()                                  // 释放滤镜引擎
    }

    // ==================== 延时摄影控制 ====================

    /**
     * 开始延时摄影录制
     *
     * @param settings 延时摄影配置
     * @return 操作结果
     */
    suspend fun startTimelapse(settings: com.qihao.filtercamera.domain.model.TimelapseSettings): Result<Unit> =
        camera.startTimelapse(settings)

    /**
     * 停止延时摄影并编码输出视频
     *
     * @return 输出视频文件路径Result
     */
    suspend fun stopTimelapse(): Result<String> =
        camera.stopTimelapse()

    /**
     * 取消延时摄影（不生成视频）
     *
     * @return 操作结果
     */
    suspend fun cancelTimelapse(): Result<Unit> =
        camera.cancelTimelapse()

    /**
     * 暂停延时摄影
     *
     * @return 操作结果
     */
    suspend fun pauseTimelapse(): Result<Unit> =
        camera.pauseTimelapse()

    /**
     * 恢复延时摄影
     *
     * @return 操作结果
     */
    suspend fun resumeTimelapse(): Result<Unit> =
        camera.resumeTimelapse()

    /**
     * 获取延时摄影进度流
     *
     * @return Triple(已捕获帧数, 已用时间毫秒, 编码进度0.0~1.0)
     */
    fun getTimelapseProgress(): Flow<Triple<Int, Long, Float>> =
        camera.getTimelapseProgress()

    /**
     * 检查是否处于延时摄影录制状态
     *
     * @return true表示正在录制
     */
    fun isTimelapseRecording(): Boolean =
        camera.isTimelapseRecording()

    // ==================== 人像虚化控制 ====================

    /**
     * 设置人像虚化等级
     *
     * @param level 虚化等级
     * @return 操作结果
     */
    suspend fun setPortraitBlurLevel(level: com.qihao.filtercamera.domain.model.PortraitBlurLevel): Result<Unit> =
        camera.setPortraitBlurLevel(level)

    /**
     * 获取当前人像虚化等级
     *
     * @return 当前虚化等级
     */
    fun getPortraitBlurLevel(): com.qihao.filtercamera.domain.model.PortraitBlurLevel =
        camera.getPortraitBlurLevel()

    // ==================== 相册操作 ====================

    /**
     * 获取最新的相册缩略图
     *
     * 从MediaStore查询最近的照片，生成缩略图供左下角预览显示
     * 如果相册为空则返回null
     *
     * @param width 缩略图宽度（默认100px）
     * @param height 缩略图高度（默认100px）
     * @return 缩略图Bitmap，相册为空时返回null
     */
    suspend fun getLatestGalleryThumbnail(width: Int = 100, height: Int = 100): Bitmap? {
        val recentMedia = media.getRecentMedia(limit = 1)       // 只查询最近1张照片
        if (recentMedia.isEmpty()) return null                   // 相册为空
        val latestUri = recentMedia.first().uri                  // 获取最新照片Uri
        return media.loadThumbnail(latestUri, width, height)     // 加载缩略图
    }
}
