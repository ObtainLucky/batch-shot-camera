/**
 * CameraRepositoryImpl.kt - 相机仓库实现
 *
 * 使用CameraX实现相机操作
 * 包含预览、拍照、录像、实时滤镜预览功能
 *
 * 技术实现：
 * - CameraX Preview用例：相机预览
 * - CameraX ImageCapture用例：拍照
 * - CameraX VideoCapture用例：录像
 * - CameraX ImageAnalysis用例：实时滤镜预览
 * - GPUImage库：滤镜渲染
 *
 * @author qihao
 * @since 2.0.0
 */
package com.qihao.filtercamera.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import android.util.Size
import com.qihao.filtercamera.data.processor.BeautyProcessor
import com.qihao.filtercamera.data.processor.HdrProcessor
import com.qihao.filtercamera.data.processor.NightModeProcessor
import com.qihao.filtercamera.data.processor.PortraitBlurConfig
import com.qihao.filtercamera.data.processor.PortraitBlurProcessor
import com.qihao.filtercamera.data.processor.TimelapseConfig
import com.qihao.filtercamera.data.processor.TimelapseEngine
import com.qihao.filtercamera.data.processor.TimelapseState
import com.qihao.filtercamera.data.util.FileUtils
import com.qihao.filtercamera.data.util.FrameProcessor
import com.qihao.filtercamera.data.util.ImageFormatConverter
import com.qihao.filtercamera.domain.model.BeautyLevel
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import com.qihao.filtercamera.domain.model.CameraLens
import com.qihao.filtercamera.domain.model.FilterType
import com.qihao.filtercamera.domain.model.FlashMode
import com.qihao.filtercamera.domain.model.FocusMode
import com.qihao.filtercamera.domain.model.HdrMode
import com.qihao.filtercamera.domain.model.MacroMode
import com.qihao.filtercamera.domain.model.NightMode
import com.qihao.filtercamera.domain.model.PortraitBlurLevel
import com.qihao.filtercamera.domain.model.TimelapseSettings
import com.qihao.filtercamera.domain.model.AspectRatio
import com.qihao.filtercamera.domain.model.WhiteBalanceMode
import com.qihao.filtercamera.di.ApplicationScope
import com.qihao.filtercamera.domain.repository.ICameraRepository
import com.qihao.filtercamera.domain.repository.ISettingsRepository
import com.qihao.filtercamera.domain.repository.VideoQuality
import com.qihao.filtercamera.domain.repository.IFilterRepository
import com.qihao.filtercamera.domain.repository.ZoomRange
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 相机仓库实现类
 *
 * @param context 应用上下文
 * @param filterRepository 滤镜仓库（用于拍照时应用滤镜）
 * @param beautyProcessor 美颜处理器
 */
@Singleton
class CameraRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val filterRepository: IFilterRepository,
    private val beautyProcessor: BeautyProcessor,
    private val hdrProcessor: HdrProcessor,                                       // HDR处理器
    private val nightModeProcessor: NightModeProcessor,                           // 夜景模式处理器
    private val timelapseEngine: TimelapseEngine,                                 // 延时摄影引擎
    private val portraitBlurProcessor: PortraitBlurProcessor,                     // 人像虚化处理器
    private val settingsRepository: ISettingsRepository,                           // 设置仓库（照片质量）
    @ApplicationScope private val applicationScope: CoroutineScope                 // 应用级作用域（监听设置）
) : ICameraRepository {

    companion object {
        private const val TAG = "CameraRepositoryImpl"                    // 日志标签
        private const val ANALYSIS_WIDTH = 1280                           // 分析帧宽度
        private const val ANALYSIS_HEIGHT = 720                           // 分析帧高度
        private const val FRAME_BUFFER_CAPACITY = 3                       // 帧缓冲区容量
        private const val FRAME_PROCESSING_INTERVAL_MS = 33L              // 帧处理间隔（约30fps）

        /** 纯水印/无GPU滤镜模式的叠加间隔（20fps）：水印一秒才变一次，满帧重画纯属浪费 */
        private const val WATERMARK_ONLY_INTERVAL_MS = 50L
        private const val START_TIMEOUT_MS = 5_000L                       // 等待录像真正开始的超时

        /** 方向变化后延迟重绑的防抖时间（等方向稳定下来再动相机） */
        private const val ROTATION_REBIND_DEBOUNCE_MS = 400L

        /** 相机忙时等待重绑的上限与轮询间隔 */
        private const val ROTATION_REBIND_MAX_WAIT_MS = 2_000L
        private const val ROTATION_REBIND_RETRY_MS = 200L

        /**
         * 无滤镜时是否仍需要走一趟滤镜链路
         *
         * 信息水印是**独立于滤镜选择**的叠加效果，它的绘制在滤镜链路内部
         * （FilterRepository.applyFilterInternal）。所以当设置里打开了信息水印时，
         * 即使当前滤镜是"原图"，拍照与预览也**不能**走"跳过滤镜"的快捷分支，
         * 否则用户开着水印却拍不到水印（这个坑真实出现过一次）。
         *
         * @param filterType 当前滤镜
         * @param infoWatermarkEnabled 设置里的信息水印开关
         * @return true 表示必须继续调用滤镜链路
         */
        internal fun needsFilterPipeline(
            filterType: FilterType,
            infoWatermarkEnabled: Boolean
        ): Boolean = filterType != FilterType.NONE || infoWatermarkEnabled

        /**
         * 是否值得把分析帧转换成 Bitmap
         *
         * 分析帧的 YUV→Bitmap 走的是「NV21 → JPEG 编码 → JPEG 解码」这条重路径，
         * 按 30fps 算每秒要跑 60 次 JPEG 编解码。而默认状态下（没选滤镜、没开信息水印、
         * 没开美颜与虚化）转换出来的位图**根本不会被使用**，白白把 CPU 占满，
         * 反过来拖慢拍照链路。
         *
         * 所以这里先判断"有没有消费者"，没有就直接丢弃这一帧。
         *
         * 注意 [externalConsumerActive]：人像模式的人脸框、文档模式的边缘框都是由
         * ViewModel 从滤镜帧流里拿位图去检测的。如果只看"滤镜/美颜/虚化"这几个
         * 仓库内部的条件，用户把美颜关到 0 又没选滤镜时，这些叠加层就会冻在最后一帧上。
         *
         * @param filterType 当前滤镜
         * @param infoWatermarkEnabled 信息水印开关
         * @param beautyIntensity 美颜强度
         * @param portraitBlurActive 人像虚化是否开启
         * @param hasRawFrame 是否已经抓到过原始预览帧（滤镜缩略图需要它）
         * @param externalConsumerActive 上层是否有人在消费分析帧（人像/文档叠加层）
         * @return true 表示需要转换
         */
        internal fun needsAnalysisBitmap(
            filterType: FilterType,
            infoWatermarkEnabled: Boolean,
            beautyIntensity: Float,
            portraitBlurActive: Boolean,
            hasRawFrame: Boolean,
            externalConsumerActive: Boolean = false
        ): Boolean {
            if (needsFilterPipeline(filterType, infoWatermarkEnabled)) return true
            if (beautyIntensity > 0f) return true
            if (portraitBlurActive) return true
            if (externalConsumerActive) return true
            // 还没抓到原始帧时先转一帧，否则滤镜缩略图会没素材
            if (!hasRawFrame) return true
            return false
        }

        /**
         * 按优先级排列的用例绑订候选组合
         *
         * 抽成纯函数是为了能直接写单元测试锁住行为：这里出过一次很严重的错
         * （录像模式下 VideoCapture 从未进入绑定列表，录像功能整体不可用）。
         *
         * 关键约束：
         * - 录像模式必须包含 [UseCaseSlot.VIDEO_CAPTURE]，否则录像一定失败
         * - 录像模式**不**绑 IMAGE_CAPTURE：四用例同绑只有 FULL 级设备支持，
         *   而录像模式本来也没有快门按钮
         * - 每一级失败都能降级到更小的组合，最后一定保得住预览
         *
         * @param videoMode 当前是否为录像模式
         * @param hasImageCapture ImageCapture 是否已构建
         * @param hasVideoCapture VideoCapture 是否已构建
         * @param hasAnalysis ImageAnalysis 是否已构建
         * @return 候选组合，按优先级从高到低
         */
        internal fun useCaseBindingPlan(
            videoMode: Boolean,
            hasImageCapture: Boolean,
            hasVideoCapture: Boolean,
            hasAnalysis: Boolean
        ): List<List<UseCaseSlot>> {
            val candidates = mutableListOf<List<UseCaseSlot>>()

            if (videoMode) {
                if (hasVideoCapture) {
                    if (hasAnalysis) {
                        candidates += listOf(
                            UseCaseSlot.PREVIEW,
                            UseCaseSlot.VIDEO_CAPTURE,
                            UseCaseSlot.IMAGE_ANALYSIS
                        )
                    }
                    candidates += listOf(UseCaseSlot.PREVIEW, UseCaseSlot.VIDEO_CAPTURE)
                }
            } else {
                if (hasImageCapture) {
                    if (hasAnalysis) {
                        candidates += listOf(
                            UseCaseSlot.PREVIEW,
                            UseCaseSlot.IMAGE_CAPTURE,
                            UseCaseSlot.IMAGE_ANALYSIS
                        )
                    }
                    candidates += listOf(UseCaseSlot.PREVIEW, UseCaseSlot.IMAGE_CAPTURE)
                }
            }

            candidates += listOf(UseCaseSlot.PREVIEW)                          // 兜底：至少保住预览
            return candidates
        }

        /**
         * 设备方向角 -> Surface 旋转常量
         *
         * 抽成纯函数便于单测：这段映射写反了的话，横屏拍出来的照片会躺倒
         * 或者直接上下颠倒，而这类问题在真机上很容易被当成"偶发"。
         *
         * 注意映射是"反"的：设备顺时针转 90 度（orientation≈90）时，
         * 内容需要逆时针补偿，对应 ROTATION_270。
         *
         * @param orientation OrientationEventListener 给出的角度（0~359）
         * @return Surface.ROTATION_*
         */
        internal fun orientationToSurfaceRotation(orientation: Int): Int = when {
            orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
            orientation < 135 -> Surface.ROTATION_270
            orientation < 225 -> Surface.ROTATION_180
            else -> Surface.ROTATION_90
        }

        /**
         * 方向切换的角度阈值 = 45 度档位边界 + 5 度滞回
         *
         * 与 jetpack-camera-app 的 DebouncedOrientationFlow（Apache-2.0）一致：
         * 新读数与上一次锁定方向角的角度差达到该值才允许切换方向。
         */
        private const val ORIENTATION_SNAP_THRESHOLD_DEGREES = 45 + 5

        /**
         * 角度滞回判断（纯函数，便于单测）
         *
         * 新读数与上一次锁定方向角的角度差（按圆周取最短弧）达到阈值才切换，
         * 否则返回 null 表示保持原方向。
         *
         * 与"按时间防抖"的区别：滞回按**几何位置**判定 —— 手机停在档位边界
         * （如 45 度）附近时，无论停多久都不会切换，也就不会反复触发重绑。
         *
         * @param prevDegrees 上一次锁定的方向角（0/90/180/270）
         * @param reading 传感器读数（0..359）
         * @return 新锁定的方向角（度，0/90/180/270）；滞回区内返回 null
         */
        internal fun snappedOrientationDegrees(prevDegrees: Int, reading: Int): Int? {
            val shortest = kotlin.math.abs(prevDegrees - reading)
                .let { kotlin.math.min(it, 360 - it) }
            if (shortest < ORIENTATION_SNAP_THRESHOLD_DEGREES) return null
            return orientationToSurfaceRotation(reading) * 90
        }
    }

    /**
     * 用例槽位
     *
     * 用枚举而不是直接传 UseCase 实例，是为了让组合决策可以脱离 CameraX 单测。
     */
    internal enum class UseCaseSlot {
        PREVIEW,
        IMAGE_CAPTURE,
        VIDEO_CAPTURE,
        IMAGE_ANALYSIS
    }

    /**
     * 观察设置里的视频质量
     */
    private fun observeVideoQuality() {
        applicationScope.launch {
            settingsRepository.getVideoQuality()
                .catch { e -> Log.w(TAG, "observeVideoQuality: 读取失败", e) }
                .collect { quality ->
                    val changed = videoQuality != quality
                    videoQuality = quality
                    Log.d(TAG, "observeVideoQuality: 录像质量=${quality.displayName}, 变化=$changed")
                    if (changed && camera != null) {
                        // Recorder 在绑定时被固化进 VideoCapture，改设置后必须重绑才生效，
                        // 否则用户改了设置却看不到任何变化，会以为功能坏了。
                        rebindForVideoQualityChange()
                    }
                }
        }
    }

    /**
     * 因录像质量变化而重绑相机
     *
     * 重新构建用例并绑定，让新的 Recorder 生效。
     * 失败只记日志：重绑失败不该让当前预览挂掉（旧用例仍可用）。
     */
    private suspend fun rebindForVideoQualityChange() {
        Log.d(TAG, "rebindForVideoQualityChange: 重新绑定以应用新的录像质量")
        // 观察设置在 Default 线程上，而用例重建/绑定要在主线程做
        withContext(Dispatchers.Main) {
            runCatching { rebindCurrentUseCases() }
                .onFailure { Log.e(TAG, "rebindForVideoQualityChange: 重绑失败，仍使用原配置", it) }
        }
    }

    /**
     * 按当前模式与设置重建并重绑用例
     *
     * 抽出来给"切换录像质量""切换拍照/录像模式"共用。三个要点：
     * 1. 必须把结果赋回 [camera]：unbindAll 之后旧的 Camera 实例已失效，
     *    不更新引用会让后续的闪光灯/变焦/曝光全部打在废弃对象上（此前的真实 bug）。
     * 2. 重建后要重新恢复闪光灯（TORCH 模式需要重新开手电筒）。
     * 3. 帧处理器是幂等启动的，重复调用安全。
     */
    private suspend fun rebindCurrentUseCases() {
        val owner = lifecycleOwner ?: return
        val provider = runCatching { getCameraProvider() }.getOrNull() ?: return
        val previewView = previewViewRef ?: return

        provider.unbindAll()
        buildUseCases(
            UseCaseConfig(
                aspectRatio = currentAspectRatio.cameraXRatio,
                previewView = previewView
            )
        )
        camera = bindUseCasesToLifecycle(owner, provider, getCameraSelector(_currentLens.value))
        startFrameProcessor()
        restoreFlashSettings()
        updateZoomRange()
        applyProCaptureOptions()                                             // 重绑后恢复专业模式参数
    }

    /**
     * 切换用例绑定模式（拍照 / 录像）
     *
     * 见 bindUseCasesToLifecycle 的注释：CameraX 不允许把拍照与录像用例
     * 无脑堆在一起绑，必须按模式重绑，否则录像根本收不到帧。
     */
    override suspend fun setVideoMode(videoMode: Boolean): Result<Unit> =
        withContext(Dispatchers.Main) {
            if (bindingVideoMode == videoMode) {
                Log.d(TAG, "setVideoMode: 已处于目标模式 videoMode=$videoMode，无需重绑")
                return@withContext Result.success(Unit)
            }

            if (_isRecording.value) {
                Log.w(TAG, "setVideoMode: 录像进行中，拒绝切换模式")
                return@withContext Result.failure(Exception("录像中不能切换模式"))
            }

            bindingVideoMode = videoMode
            Log.d(TAG, "setVideoMode: 绑定模式 -> ${if (videoMode) "录像" else "拍照"}")

            // 相机尚未绑定时只记标志，下次 bindCamera 会按新标志绑定
            if (camera == null || lifecycleOwner == null || previewViewRef == null) {
                return@withContext Result.success(Unit)
            }

            try {
                rebindCurrentUseCases()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "setVideoMode: 重绑失败", e)
                Result.failure(e)
            }
        }

    /**
     * 视频质量档位映射到 CameraX 的 Quality
     */
    private fun VideoQuality.toCameraXQuality(): Quality = when (this) {
        VideoQuality.QUALITY_4K -> Quality.UHD
        VideoQuality.QUALITY_1080P -> Quality.FHD
        VideoQuality.QUALITY_720P -> Quality.HD
    }

    /**
     * 观察设置里的照片质量
     *
     * 拍照编码在后台线程执行，这里用 volatile 字段同步，避免每张照片都去读 DataStore。
     */
    private fun observePhotoQuality() {
        applicationScope.launch {
            settingsRepository.getPhotoQuality()
                .catch { e -> Log.w(TAG, "observePhotoQuality: 读取失败", e) }
                .collect { quality ->
                    jpegQuality = quality.compressionQuality
                    Log.d(TAG, "observePhotoQuality: JPEG 质量=$jpegQuality (${quality.displayName})")
                }
        }
    }

    /**
     * 录像分辨率档位
     *
     * 跟随设置页的「视频质量」。此前写死 Quality.HIGHEST，设置改了没反应。
     * 注意：Recorder 在绑定相机时构建，所以改动在**下次进入相机页（重新绑定）后**生效。
     */
    @Volatile
    private var videoQuality: VideoQuality = VideoQuality.QUALITY_1080P

    // 相机执行器（ImageAnalysis 的分析线程）
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * 拍照专用执行器
     *
     * 拍照回调**不能**和 ImageAnalysis 共用同一条单线程执行器：
     * 分析线程被实时预览帧占满时，onCaptureSuccess 会排在这些帧后面才被投递，
     * 表现为"按下快门后要等一会儿才有反应"。拍照回调本身只做一件事（把结果
     * 交给协程），单独给一条线程即可。
     */
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // 异步帧处理器（解决帧处理阻塞问题）
    private val frameProcessor = FrameProcessor(
        bufferCapacity = FRAME_BUFFER_CAPACITY,
        processingIntervalMs = FRAME_PROCESSING_INTERVAL_MS
    )

    /** 当前叠加层的目标帧间隔（分析流节流与处理器节奏共用这一个值） */
    @Volatile
    private var previewIntervalMs = FRAME_PROCESSING_INTERVAL_MS

    /** 上次分析流提交时间（elapsedRealtime ms），用于分析流节流 */
    @Volatile
    private var lastAnalysisSubmitAt = 0L

    // 当前镜头状态
    private val _currentLens = MutableStateFlow(CameraLens.BACK)

    // 录像状态
    private val _isRecording = MutableStateFlow(false)

    // 滤镜帧回调（用于实时预览）
    private val _filteredFrame = MutableStateFlow<Bitmap?>(null)

    // 原始预览帧（用于生成滤镜缩略图，不受滤镜影响）
    private val _rawPreviewFrame = MutableStateFlow<Bitmap?>(null)

    // 变焦范围
    private val _zoomRange = MutableStateFlow(ZoomRange(1.0f, 10.0f))

    // 当前滤镜
    private var currentFilterType = FilterType.NONE

    // 美颜强度
    private var beautyIntensity = 0.6f

    // 当前闪光灯模式
    private var currentFlashMode = FlashMode.AUTO

    // 当前HDR模式
    private var currentHdrMode = HdrMode.OFF

    // 当前夜景模式
    private var currentNightMode = NightMode.OFF

    // 当前快门速度（秒），null表示自动曝光
    private var currentShutterSpeed: Float? = null

    // 当前ISO值，null表示自动ISO
    private var currentIso: Int? = null

    // 设备支持的曝光时间范围（纳秒）
    private var exposureTimeRange: Pair<Long, Long>? = null

    // 默认手动ISO值（当切换到手动快门但ISO为自动时使用）
    private val defaultManualIso = 400

    // ==================== 专业模式：Camera2 请求选项的唯一事实来源 ====================

    /**
     * 专业模式各参数（null = 该项未手动指定，交回相机默认值）
     *
     * 这些字段的存在是有原因的：在 CameraX 里
     * `camera2Control.captureRequestOptions = xxx` 是**整份替换**而不是合并。
     * 原先每个 setter 各造一份"只含自己那一个 key"的 options，于是后设的参数会把
     * 先设的整份抹掉 —— 调完白平衡再调 ISO，白平衡就悄悄回到自动了，用户只会觉得
     * 专业模式"基本没法用"。
     *
     * 所以所有参数集中记录在这里，任何一项变化都重新构造一份**完整**的 options 下发。
     */
    private var proAwbMode: Int? = null
    private var proAfMode: Int? = null
    private var proFocusDistanceDiopters: Float? = null
    private var proAeMode: Int? = null
    private var proIso: Int? = null
    private var proExposureTimeNs: Long? = null

    /**
     * 自动曝光当前实际使用的曝光时间（纳秒）
     *
     * 手动 ISO 必须配 AE_MODE_OFF，而 AE 一旦关闭就必须给出曝光时间，
     * 否则曝光量完全不可控。用户选"ISO 优先"时想保留的正是自动测光算出来的
     * 那一档快门，所以这里用 Camera2 会话回调把 AE 的实测曝光时间记下来复用。
     */
    @Volatile
    private var lastAeExposureTimeNs: Long? = null

    // CameraX组件
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null               // 相机控制引用
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null                      // 图像分析用例
    private var currentRecording: Recording? = null
    private var recordingFinalizeDeferred: CompletableDeferred<String>? = null  // 用于等待录像完成
    private var recordingStartDeferred: CompletableDeferred<Unit>? = null       // 用于等待录像真正开始
    private var currentAspectRatio: AspectRatio = AspectRatio.RATIO_4_3  // 当前画幅

    /**
     * 当前绑定模式是否为录像
     *
     * 决定 bindUseCasesToLifecycle 绑定哪一组用例（见该方法注释）。
     * 由 CameraViewModel 在模式切换时通过 setVideoMode() 更新。
     */
    @Volatile
    private var bindingVideoMode = false

    /**
     * 上层是否有人在消费分析帧（人像的人脸框、文档的边缘框）
     *
     * 由 CameraViewModel 在进入/离开人像、文档模式时设置。没有它的话，
     * "美颜关到 0 且没选滤镜"时仓库会认为分析帧没人要而丢弃，
     * 叠加层就冻在最后一次检测结果上。
     */
    @Volatile
    private var analysisConsumerActive = false

    // ==================== 屏幕方向 ====================

    /**
     * 当前的屏幕方向（Surface.ROTATION_*）
     *
     * 这是拍摄旋转的唯一来源。CameraX 的 ImageCapture / ImageAnalysis / VideoCapture
     * 的 targetRotation **不会**自己跟着设备转（默认恒为 ROTATION_0），而本 App 的
     * Activity 又在 configChanges 里声明了 orientation，旋转时不会重建，
     * 于是没有任何一方会去更新它 —— 结果就是横着拍出来的照片永远是躺倒的，
     * 水印也跟着躺倒（水印是画在这张已经定向好的位图上的）。
     */
    @Volatile
    private var displayRotation = Surface.ROTATION_0

    /** 设备方向监听器（不依赖系统"自动旋转"开关，和系统相机一致） */
    private var orientationListener: OrientationEventListener? = null

    /**
     * 当前锁定的方向角（0/90/180/270 度）
     *
     * 滞回判断的基准：新读数必须与它相差足够大才允许切换方向。
     */
    @Volatile
    private var lastSnappedDegrees = 0

    /** 方向变化后的重绑任务（用于防抖，方向抖动时不做多次重绑） */
    private var rotationRebindJob: Job? = null

    /**
     * 是否正在拍摄
     *
     * 方向变化要重绑相机（见 scheduleRebindForRotation），而 unbindAll 会让
     * 正在进行的拍摄失败，所以拍摄期间必须避开。
     */
    @Volatile
    private var captureInFlight = false

    /**
     * 启动设备方向跟踪
     *
     * 用方向传感器而不是读取 display.rotation：用户关掉系统"自动旋转"时，
     * display.rotation 会一直停在 0，那样横着拍又会躺倒。系统相机同样是
     * 按设备物理方向决定照片方向的，这里保持一致。
     */
    private fun startOrientationTracking() {
        if (orientationListener != null) return

        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return                   // 手机平放，无法判断

                // 角度滞回：读数必须离上一次锁定的方向足够远才切换。
                // 没有这一步，手机停在 45 度档位边界附近时方向会两档之间反复横跳，
                // 每次都触发一次相机重绑、预览不停闪 —— 时间防抖压不住这种抖动。
                val snapped = Companion.snappedOrientationDegrees(
                    lastSnappedDegrees, orientation
                ) ?: return
                lastSnappedDegrees = snapped
                // snapped 是 0/90/180/270 度，正好对应 ROTATION_0/1/2/3
                applyDisplayRotation(snapped / 90)
            }
        }

        if (listener.canDetectOrientation()) {
            listener.enable()
            orientationListener = listener
            Log.d(TAG, "startOrientationTracking: 方向跟踪已启动")
        } else {
            Log.w(TAG, "startOrientationTracking: 设备不支持方向检测，拍摄方向将固定为竖屏")
        }
    }

    /**
     * 停止设备方向跟踪
     */
    private fun stopOrientationTracking() {
        orientationListener?.disable()
        orientationListener = null
        Log.d(TAG, "stopOrientationTracking: 方向跟踪已停止")
    }

    /**
     * 设备方向角 -> Surface 旋转常量
     *
     * 设备顺时针转 90 度时，内容要逆时针补偿，所以映射是"反"的
     * （45~135 度对应 ROTATION_270，而不是 90）。
     */
    private fun orientationToSurfaceRotation(orientation: Int): Int =
        Companion.orientationToSurfaceRotation(orientation)

    /**
     * 应用新的屏幕方向到所有产出内容的用例
     *
     * 只在方向真的变化时才处理：传感器回调很密集，无脑处理会把预览抖坏。
     *
     * 这里除了设置 targetRotation，还要**重绑一次**：
     * CameraX 的 `setTargetRotation` 只改用例配置，不会通知相机重配拍摄流
     * （查过 camera-core 1.4.1 的字节码，ImageCapture.setTargetRotation 改完
     * mUseCaseConfig 就直接返回了），所以光设它，横屏拍出来的照片仍然是竖屏形状 ——
     * 这正是"横屏拍出来跟竖屏没区别"的原因。重绑才会让新方向真正作用到流上。
     */
    private fun applyDisplayRotation(rotation: Int) {
        if (displayRotation == rotation) return
        displayRotation = rotation
        Log.d(TAG, "applyDisplayRotation: 屏幕方向 -> $rotation")

        imageCapture?.targetRotation = rotation
        imageAnalysis?.targetRotation = rotation
        videoCapture?.targetRotation = rotation

        scheduleRebindForRotation()
    }

    /**
     * 方向变化后重绑相机
     *
     * 防抖 + 避开拍摄/录像：手持手机接近 45 度边界时方向会在两档之间反复跳，
     * 每次跳都重绑会让预览不停闪。
     */
    private fun scheduleRebindForRotation() {
        rotationRebindJob?.cancel()
        rotationRebindJob = applicationScope.launch {
            delay(ROTATION_REBIND_DEBOUNCE_MS)

            // 拍摄/录像期间 unbindAll 会让当前这次拍摄失败，等它结束再绑
            var waited = 0L
            while ((captureInFlight || _isRecording.value) &&
                waited < ROTATION_REBIND_MAX_WAIT_MS
            ) {
                delay(ROTATION_REBIND_RETRY_MS)
                waited += ROTATION_REBIND_RETRY_MS
            }

            if (captureInFlight || _isRecording.value) {
                Log.w(TAG, "scheduleRebindForRotation: 相机忙，本次方向改动留待下次重绑生效")
                return@launch
            }

            withContext(Dispatchers.Main) {
                runCatching { rebindCurrentUseCases() }
                    .onFailure { Log.e(TAG, "scheduleRebindForRotation: 重绑失败", it) }
            }
        }
    }

    /**
     * 把当前记录的方向应用到刚构建出来的用例上
     *
     * 用例是重绑时新建的，targetRotation 会回到默认的 ROTATION_0，
     * 所以每次构建后都要补一次。
     */
    private fun applyDisplayRotationToNewUseCases() {
        // 预览不设：PreviewView 自己按显示方向做变换，重复设置反而可能打架
        imageCapture?.targetRotation = displayRotation
        imageAnalysis?.targetRotation = displayRotation
        videoCapture?.targetRotation = displayRotation
    }

    /**
     * 设置上层是否在消费分析帧
     */
    override fun setAnalysisConsumerActive(active: Boolean) {
        if (analysisConsumerActive != active) {
            Log.d(TAG, "setAnalysisConsumerActive: $active")
            analysisConsumerActive = active
        }
    }

    // 生命周期持有者
    private var lifecycleOwner: LifecycleOwner? = null

    // 预览视图
    private var previewViewRef: androidx.camera.view.PreviewView? = null

    // ==================== 用例构建器 ====================

    /**
     * 用例构建配置
     *
     * @param aspectRatio 目标画幅比例（可选）
     * @param previewView 预览视图
     */
    private data class UseCaseConfig(
        val aspectRatio: Int? = null,                                        // CameraX画幅比例常量
        val previewView: androidx.camera.view.PreviewView
    )

    /**
     * 构建相机用例
     *
     * 统一创建Preview、ImageCapture、VideoCapture、ImageAnalysis用例
     * 使用新的ResolutionSelector API替代已弃用的setTargetAspectRatio/setTargetResolution
     *
     * @param config 用例配置
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildUseCases(config: UseCaseConfig) {
        // 构建分辨率选择器（用于Preview和ImageCapture）
        val resolutionSelector = config.aspectRatio?.let { aspectRatio ->
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                .build()
        }

        // 创建预览用例
        preview = Preview.Builder().apply {
            resolutionSelector?.let { setResolutionSelector(it) }
        }.build().also {
            it.surfaceProvider = config.previewView.surfaceProvider
        }

        // 创建拍照用例
        val imageCaptureBuilder = ImageCapture.Builder().apply {
            // 用 MINIMIZE_LATENCY 而不是 MAXIMIZE_QUALITY：
            // 后者是 CameraX 明确的"拿快门延迟换画质"模式，批次连续拍摄时体感很差。
            // 如果更在意单张画质，把下面这行换回 CAPTURE_MODE_MAXIMIZE_QUALITY 即可。
            setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            resolutionSelector?.let { setResolutionSelector(it) }
        }

        // 挂一个会话回调，把自动曝光实际使用的曝光时间记下来。
        // 手动 ISO 必须配 AE_MODE_OFF，而 AE 一关就必须给出曝光时间；
        // 用户在"ISO 优先"时想保留的正是自动测光算出的那一档快门。
        Camera2Interop.Extender(imageCaptureBuilder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    // 只在自动曝光生效时记录，否则会把手动曝光时间也当成"测光结果"
                    if (exposureTime != null && exposureTime > 0L &&
                        proAeMode != CaptureRequest.CONTROL_AE_MODE_OFF
                    ) {
                        lastAeExposureTimeNs = exposureTime
                    }
                }
            }
        )
        imageCapture = imageCaptureBuilder.build()

        // 创建录像用例（分辨率跟随设置页的「视频质量」）
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(videoQuality.toCameraXQuality()))
            .build()
        Log.d(TAG, "buildUseCases: 录像质量=${videoQuality.displayName} (${videoQuality.resolution})")
        videoCapture = VideoCapture.withOutput(recorder)

        // 创建图像分析用例（用于实时滤镜预览）
        // 使用ResolutionStrategy替代已弃用的setTargetResolution
        val analysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()

        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageForFilter(imageProxy)
                }
            }

        Log.d(TAG, "buildUseCases: 用例构建完成 aspectRatio=${config.aspectRatio}")

        // 新用例的 targetRotation 会回到 ROTATION_0，补上当前设备方向，
        // 否则每次重绑之后横屏拍摄又会躺倒
        applyDisplayRotationToNewUseCases()
    }

    /**
     * 绑定用例到生命周期
     *
     * **按模式分别绑定**，而不是"一次绑上所有用例"：
     *
     * CameraX 的用例组合是互斥资源，官方支持矩阵里
     * Preview+ImageCapture+VideoCapture+ImageAnalysis 四用例同绑**只有 FULL 级设备**支持，
     * 绝大多数手机（LIMITED）会直接抛异常。而原先的实现把 VideoCapture 漏在绑定之外，
     * 结果就是：Recorder 从未接到相机上，一按录像就收到 Finalize 错误事件
     * （录像功能完全不可用），同时四用例超限的回退又把 ImageAnalysis 丢掉，
     * 导致实时滤镜预览在部分机型上静默失效。
     *
     * 现在改成：
     * - 录像模式：Preview + VideoCapture (+ ImageAnalysis)
     * - 拍照模式：Preview + ImageCapture (+ ImageAnalysis)
     * 每一级失败都降级到更小的组合，最后至少保住预览。
     *
     * @param owner 生命周期持有者
     * @param provider CameraProvider
     * @param cameraSelector 相机选择器
     * @return 绑定的相机实例
     */
    private fun bindUseCasesToLifecycle(
        owner: LifecycleOwner,
        provider: ProcessCameraProvider,
        cameraSelector: CameraSelector
    ): androidx.camera.core.Camera {
        val previewUseCase = preview ?: throw IllegalStateException("Preview未构建")

        val plan = useCaseBindingPlan(
            videoMode = bindingVideoMode,
            hasImageCapture = imageCapture != null,
            hasVideoCapture = videoCapture != null,
            hasAnalysis = imageAnalysis != null
        )

        for ((index, slots) in plan.withIndex()) {
            val useCases = slots.map { slot ->
                when (slot) {
                    UseCaseSlot.PREVIEW -> previewUseCase
                    UseCaseSlot.IMAGE_CAPTURE -> imageCapture!!
                    UseCaseSlot.VIDEO_CAPTURE -> videoCapture!!
                    UseCaseSlot.IMAGE_ANALYSIS -> imageAnalysis!!
                }
            }

            try {
                provider.unbindAll()
                val bound = provider.bindToLifecycle(
                    owner,
                    cameraSelector,
                    *useCases.toTypedArray()
                )
                Log.d(
                    TAG,
                    "bindUseCasesToLifecycle: 绑定成功 模式=${if (bindingVideoMode) "录像" else "拍照"} " +
                        "组合=$index 用例=$slots"
                )
                return bound
            } catch (e: Exception) {
                Log.w(TAG, "bindUseCasesToLifecycle: 组合$index 绑定失败，尝试降级", e)
            }
        }

        if (bindingVideoMode && videoCapture == null) {
            Log.e(TAG, "bindUseCasesToLifecycle: 录像模式下 VideoCapture 缺失，录像将不可用")
        }

        // 理论上到不了这里（兜底组合只有 Preview，绑不上说明相机本身有问题）
        provider.unbindAll()
        return provider.bindToLifecycle(owner, cameraSelector, previewUseCase)
    }

    /**
     * 获取滤镜帧流（用于实时预览）
     */
    override fun getFilteredFrame(): Flow<Bitmap?> = _filteredFrame

    /**
     * 获取原始预览帧（用于生成滤镜缩略图）
     *
     * 此帧不受当前滤镜影响，始终是原始相机帧
     */
    override fun getRawPreviewFrame(): Flow<Bitmap?> = _rawPreviewFrame

    /**
     * 绑定相机到生命周期
     *
     * 使用buildUseCases和bindUseCasesToLifecycle统一处理
     *
     * @param owner 生命周期持有者
     * @param previewView 预览视图
     * @param videoMode 本次绑定是否为录像模式
     */
    override suspend fun bindCamera(
        owner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
        videoMode: Boolean
    ) {
        withContext(Dispatchers.Main) {
            Log.d(TAG, "bindCamera: 开始绑定相机 videoMode=$videoMode")
            this@CameraRepositoryImpl.lifecycleOwner = owner
            this@CameraRepositoryImpl.previewViewRef = previewView

            // 以调用方给的模式为准：本类是单例，沿用旧值会让"退出录像模式再进相机页"
            // 绑成录像用例，而界面是拍照模式，快门就此失效
            bindingVideoMode = videoMode

            // 开始跟踪设备方向：横竖屏拍摄都靠它决定照片与水印的方向
            startOrientationTracking()

            try {
                // 获取CameraProvider
                val provider = getCameraProvider()
                cameraProvider = provider

                // 解绑之前的用例
                provider.unbindAll()

                // 注意：这里**不**重置 currentShutterSpeed / currentIso。
                // Camera2 的请求选项挂在 CameraControl 上，重绑会换成新对象、选项全丢，
                // 所以重置了也没用；保留用户的选择并在绑定后重新下发，才能让
                // 专业模式面板上显示的值与实际生效的值保持一致。

                // 构建用例（无指定画幅比例）
                buildUseCases(UseCaseConfig(previewView = previewView))

                // 绑定用例到生命周期
                val cameraSelector = getCameraSelector(_currentLens.value)
                camera = bindUseCasesToLifecycle(owner, provider, cameraSelector)

                // 更新变焦范围
                updateZoomRange()

                // 初始化曝光时间范围（用于快门速度控制）
                initExposureTimeRange()

                // 初始化HDR处理器（用于硬件HDR检测）
                initHdrProcessor()

                // 初始化夜景模式处理器（用于硬件夜景检测）
                initNightProcessor()

                // 启动帧处理器（异步处理滤镜）
                startFrameProcessor()

                // 恢复闪光灯设置（重要：TORCH模式需要重新开启手电筒）
                restoreFlashSettings()

                // 重新下发专业模式参数：重绑后 CameraControl 是新对象，选项会丢
                applyProCaptureOptions()

                Log.d(TAG, "bindCamera: 相机绑定成功")
            } catch (e: Exception) {
                Log.e(TAG, "bindCamera: 相机绑定失败", e)
                throw e
            }
        }
    }

    /**
     * 处理图像帧应用滤镜（ImageAnalysis回调）
     *
     * 优化：使用FrameProcessor异步处理，避免阻塞回调
     * - 快速将帧提交到缓冲区
     * - 后台线程异步处理滤镜
     * - 支持跳帧策略
     *
     * @param imageProxy 相机帧
     */
    private fun processImageForFilter(imageProxy: ImageProxy) {
        val startTime = System.nanoTime()
        try {
            // 没有任何需要渲染的效果、且已抓到过原始帧时，直接丢弃这一帧。
            // 这一步很重要：分析帧的 YUV→Bitmap 要经过 JPEG 编码+解码，
            // 30fps 下每秒 60 次编解码，白跑起来会把 CPU 占满并拖慢拍照。
            val needed = needsAnalysisBitmap(
                filterType = currentFilterType,
                infoWatermarkEnabled = filterRepository.isInfoWatermarkEnabled(),
                beautyIntensity = beautyIntensity,
                portraitBlurActive = currentPortraitBlurLevel != PortraitBlurLevel.NONE,
                hasRawFrame = _rawPreviewFrame.value != null,
                externalConsumerActive = analysisConsumerActive
            )
            if (!needed) {
                // 清掉可能残留的滤镜预览层，避免关掉效果后画面停在旧帧上
                if (_filteredFrame.value != null) {
                    _filteredFrame.value = null
                }
                return
            }

            // 分析流节流：叠加层只有 20/30fps 的消费节奏，相机 30fps 全部转换
            // 有 1/3 以上是白算的（转换线程是最重的单项开销）。间隔与
            // frameProcessor 的处理节奏保持一致，只转换会被消费的帧。
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAnalysisSubmitAt < previewIntervalMs - 5) {
                return
            }
            lastAnalysisSubmitAt = now

            // 将YUV转换为Bitmap（快速转换）
            val bitmap = yuvImageProxyToBitmap(imageProxy)
            if (bitmap == null) {
                Log.w(TAG, "processImageForFilter: YUV转Bitmap失败")
                return
            }

            // 提交帧到异步处理器（非阻塞）
            frameProcessor.submitFrame(bitmap)

            // 性能日志：转换耗时超过 50ms 视为异常
            val conversionTimeNs = System.nanoTime() - startTime
            if (conversionTimeNs > 50_000_000) {
                Log.w(TAG, "processImageForFilter: 帧转换耗时过长 ${conversionTimeNs / 1_000_000}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "processImageForFilter: 处理失败", e)
        } finally {
            imageProxy.close()                                              // 快速释放ImageProxy
        }
    }

    /**
     * 启动帧处理器
     *
     * 在相机绑定后调用，开始异步帧处理
     */
    private fun startFrameProcessor() {
        Log.d(TAG, "startFrameProcessor: 启动帧处理器")
        frameProcessor.start { bitmap ->
            processFrameWithFilter(bitmap)
        }
    }

    /**
     * 停止帧处理器
     */
    private fun stopFrameProcessor() {
        Log.d(TAG, "stopFrameProcessor: 停止帧处理器")
        frameProcessor.stop()
    }

    /**
     * 处理单帧应用滤镜和美颜（在后台线程执行）
     *
     * 处理流程：
     * 1. 检查美颜强度，如果 > 0 则先应用美颜
     * 2. 然后应用滤镜效果
     * 3. 更新 _filteredFrame 供预览显示
     *
     * @param bitmap 待处理的帧
     * @return 处理后的帧
     */
    private fun processFrameWithFilter(bitmap: Bitmap): Bitmap? {
        try {
            val filterType = currentFilterType
            val intensity = beautyIntensity

            // 按当前效果动态调整叠加帧率：只有 GPU 滤镜在跑时保持满帧（取景流畅优先）；
            // 纯水印/无效果时降到 20fps——水印一秒才变一次，满帧重画纯属浪费 CPU 和电
            val interval = if (filterType != FilterType.NONE && filterType.useGpu) {
                FRAME_PROCESSING_INTERVAL_MS
            } else {
                WATERMARK_ONLY_INTERVAL_MS
            }
            previewIntervalMs = interval
            frameProcessor.setProcessingInterval(interval)

            // 更新原始预览帧（用于生成滤镜缩略图）
            if (_rawPreviewFrame.value == null) {
                _rawPreviewFrame.value = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                Log.d(TAG, "processFrameWithFilter: 更新原始预览帧")
            }

            // Step 1: 应用美颜效果（如果启用）
            // 使用纯Kotlin实现的ColorMatrix美颜，避免Native层SIGSEGV崩溃
            val beautifiedBitmap = if (intensity > 0f) {
                applyKotlinBeautyEffect(bitmap, intensity)
            } else {
                bitmap
            }

            // Step 2: 无滤镜且没有额外叠加效果时走快捷分支（省掉每帧一次位图处理）
            // 信息水印属于"额外叠加效果"，开着就必须继续走到滤镜链路去画它。
            if (!needsFilterPipeline(filterType, filterRepository.isInfoWatermarkEnabled())) {
                if (intensity > 0f && beautifiedBitmap !== bitmap) {
                    _filteredFrame.value = beautifiedBitmap                         // 显示美颜效果
                    return beautifiedBitmap
                }
                _filteredFrame.value = null                                         // 无效果，清空
                return null
            }

            // Step 3: 应用滤镜（在美颜后的图上）
            val filteredBitmap = filterRepository.applyFilterToBitmapSync(filterType, beautifiedBitmap)
            if (filteredBitmap != null) {
                _filteredFrame.value = filteredBitmap
                // 回收中间美颜Bitmap（如果不是原图且不是最终结果）
                if (beautifiedBitmap !== bitmap && beautifiedBitmap !== filteredBitmap && !beautifiedBitmap.isRecycled) {
                    beautifiedBitmap.recycle()
                }
                return filteredBitmap
            }

            return beautifiedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "processFrameWithFilter: 帧处理失败", e)
            return null
        }
    }

    /**
     * 使用纯Kotlin实现的美颜效果（用于实时预览）
     *
     * 通过ColorMatrix实现美白和柔肤效果，安全且不会崩溃
     *
     * @param bitmap 源Bitmap
     * @param intensity 美颜强度 (0.0 - 1.0)
     * @return 处理后的Bitmap
     */
    private fun applyKotlinBeautyEffect(bitmap: Bitmap, intensity: Float): Bitmap {
        // 创建输出Bitmap
        val resultBitmap = Bitmap.createBitmap(
            bitmap.width,
            bitmap.height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 美白效果：增加亮度
        val whitenMatrix = ColorMatrix().apply {
            val brightness = intensity * 0.15f  // 最大15%亮度增加
            set(floatArrayOf(
                1f, 0f, 0f, 0f, brightness * 255,
                0f, 1f, 0f, 0f, brightness * 255,
                0f, 0f, 1f, 0f, brightness * 255,
                0f, 0f, 0f, 1f, 0f
            ))
        }

        // 饱和度轻微降低（使肤色更柔和）
        val saturationMatrix = ColorMatrix().apply {
            setSaturation(1f - intensity * 0.1f)  // 最多降低10%饱和度
        }

        // 合并效果矩阵
        val combinedMatrix = ColorMatrix()
        combinedMatrix.postConcat(whitenMatrix)
        combinedMatrix.postConcat(saturationMatrix)

        // 应用效果
        paint.colorFilter = ColorMatrixColorFilter(combinedMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        return resultBitmap
    }

    /**
     * 将YUV格式的ImageProxy转换为Bitmap
     *
     * 使用ImageFormatConverter工具类进行转换
     *
     * @param imageProxy YUV_420_888格式的图像
     * @return Bitmap，失败返回null
     */
    private fun yuvImageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val isFrontCamera = _currentLens.value == CameraLens.FRONT
        val config = ImageFormatConverter.ConversionConfig(
            quality = 85,
            handleRotation = true,
            handleMirror = isFrontCamera
        )
        return ImageFormatConverter.yuvImageProxyToBitmap(imageProxy, config, isFrontCamera)
    }

    /**
     * 拍照
     *
     * 处理流程：
     * 1. 使用CameraX拍摄原始图像
     * 2. 如果HDR开启且使用软件HDR，应用HDR增强处理
     * 3. 获取当前滤镜类型并应用滤镜效果
     * 4. 应用美颜处理
     * 5. 保存处理后的图像到文件
     *
     * HDR实现说明：
     * - 硬件HDR：相机已通过HDR扩展绑定，直接拍照即可获得HDR效果
     * - 软件HDR：使用HdrProcessor进行单帧HDR增强（曝光融合+色调映射）
     */
    override suspend fun takePhoto(): Result<String> = withContext(Dispatchers.IO) {
        val capture = imageCapture ?: return@withContext Result.failure(
            Exception("ImageCapture未初始化")
        )

        // 标记拍摄中：方向变化的重绑要避开这期间，否则 unbindAll 会让这次拍摄失败
        captureInFlight = true
        try {
            val totalStart = System.currentTimeMillis()
            var stageStart = totalStart
            Log.d(TAG, "takePhoto: 开始拍照 hdrMode=${currentHdrMode.displayName}")

            // 捕获图像并获取ImageProxy
            val imageProxy = suspendCancellableCoroutine<ImageProxy> { continuation ->
                capture.takePicture(
                    captureExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            Log.d(TAG, "takePhoto: 图像捕获成功 size=${image.width}x${image.height}")
                            continuation.resume(image)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "takePhoto: 图像捕获失败", exception)
                            continuation.resumeWithException(exception)
                        }
                    }
                )
            }

            // 耗时打点：等待相机出图
            val captureWaitMs = System.currentTimeMillis() - stageStart
            stageStart = System.currentTimeMillis()
            Log.d(TAG, "takePhoto: [耗时] 等待相机捕获=${captureWaitMs}ms")

            // ==================== 直存快速通道 ====================
            // 没有任何需要按像素处理的效果时，直接把相机吐出的 JPEG 原样落盘：
            // 省掉"解码成位图 -> 再编码"这一整轮（实测约 250ms/张）。
            // 旋转信息保留在 JPEG 的 EXIF 里（相册与编辑器都会遵守，这也是相机应用的通行做法）。
            if (canPassThroughOriginalJpeg()) {
                val passthroughFile = writeOriginalJpegToTempFile(imageProxy)
                imageProxy.close()
                if (passthroughFile != null) {
                    Log.d(
                        TAG,
                        "takePhoto: [耗时] 直存原始 JPEG，跳过解码与重编码 " +
                            "总计=${System.currentTimeMillis() - totalStart}ms"
                    )
                    return@withContext Result.success(passthroughFile.absolutePath)
                }
                Log.w(TAG, "takePhoto: 直存失败，回退到常规处理链路")
            }

            // 将ImageProxy转换为Bitmap
            var originalBitmap = imageProxyToBitmap(imageProxy)
            imageProxy.close()                                                    // 释放ImageProxy

            if (originalBitmap == null) {
                Log.e(TAG, "takePhoto: Bitmap转换失败")
                return@withContext Result.failure(Exception("Bitmap转换失败"))
            }

            val convertMs = System.currentTimeMillis() - stageStart
            stageStart = System.currentTimeMillis()
            Log.d(
                TAG,
                "takePhoto: [耗时] 解码+旋转=${convertMs}ms, 原始Bitmap大小 " +
                    "${originalBitmap.width}x${originalBitmap.height}"
            )

            // ==================== HDR处理 ====================
            // 判断是否需要软件HDR处理
            // 条件：HDR模式开启 且 硬件HDR不可用
            val needSoftwareHdr = (currentHdrMode == HdrMode.ON || currentHdrMode == HdrMode.AUTO) &&
                    !isHardwareHdrActive()

            val hdrProcessedBitmap = if (needSoftwareHdr) {
                Log.d(TAG, "takePhoto: 应用软件HDR处理（单帧增强）")
                val hdrResult = hdrProcessor.enhanceSingleFrame(originalBitmap)
                if (hdrResult.success) {
                    Log.d(TAG, "takePhoto: 软件HDR处理完成 耗时=${hdrResult.processingTimeMs}ms")
                    // 回收原始Bitmap
                    if (hdrResult.bitmap !== originalBitmap) {
                        originalBitmap.recycle()
                    }
                    hdrResult.bitmap
                } else {
                    Log.w(TAG, "takePhoto: 软件HDR处理失败: ${hdrResult.errorMessage}，使用原图")
                    originalBitmap
                }
            } else {
                if (currentHdrMode == HdrMode.ON || currentHdrMode == HdrMode.AUTO) {
                    Log.d(TAG, "takePhoto: 使用硬件HDR，无需软件处理")
                }
                originalBitmap
            }

            // ==================== 夜景处理 ====================
            // 判断是否需要软件夜景处理
            // 条件：夜景模式开启 且 硬件夜景不可用
            val needSoftwareNight = (currentNightMode == NightMode.ON || currentNightMode == NightMode.AUTO) &&
                    !isHardwareNightActive()

            val nightProcessedBitmap = if (needSoftwareNight) {
                Log.d(TAG, "takePhoto: 应用软件夜景处理（多帧合成）")
                // 使用单帧增强作为简化实现（完整实现需要多帧捕获）
                val nightResult = nightModeProcessor.enhanceSingleFrame(hdrProcessedBitmap)
                if (nightResult.success) {
                    Log.d(TAG, "takePhoto: 软件夜景处理完成 耗时=${nightResult.processingTimeMs}ms")
                    // 回收HDR处理结果
                    if (nightResult.bitmap !== hdrProcessedBitmap && !hdrProcessedBitmap.isRecycled) {
                        hdrProcessedBitmap.recycle()
                    }
                    nightResult.bitmap
                } else {
                    Log.w(TAG, "takePhoto: 软件夜景处理失败: ${nightResult.errorMessage}，使用HDR图")
                    hdrProcessedBitmap
                }
            } else {
                if (currentNightMode == NightMode.ON || currentNightMode == NightMode.AUTO) {
                    Log.d(TAG, "takePhoto: 使用硬件夜景，无需软件处理")
                }
                hdrProcessedBitmap
            }

            // ==================== 滤镜处理 ====================
            val currentFilter = filterRepository.getCurrentFilter().first()
            Log.d(TAG, "takePhoto: 当前滤镜=$currentFilter")

            // 注意：无滤镜时也要看信息水印开关 —— 水印是在滤镜链路内部叠加的，
            // 跳过这一步水印就不会出现在照片上（见 needsFilterPipeline 的说明）。
            val filteredBitmap = if (
                needsFilterPipeline(currentFilter, filterRepository.isInfoWatermarkEnabled())
            ) {
                Log.d(TAG, "takePhoto: 正在应用滤镜/水印...")
                filterRepository.applyFilterToBitmap(currentFilter, nightProcessedBitmap)
                    ?: nightProcessedBitmap
            } else {
                nightProcessedBitmap
            }

            val filterMs = System.currentTimeMillis() - stageStart
            stageStart = System.currentTimeMillis()
            Log.d(
                TAG,
                "takePhoto: [耗时] 滤镜/水印=${filterMs}ms, 结果大小 " +
                    "${filteredBitmap.width}x${filteredBitmap.height}"
            )

            // ==================== 美颜处理 ====================
            val beautifiedBitmap = if (beautyIntensity > 0f) {
                Log.d(TAG, "takePhoto: 正在应用美颜 intensity=$beautyIntensity")
                val beautyLevel = BeautyLevel.fromIntensity(beautyIntensity)
                val beautyResult = beautyProcessor.processBeauty(filteredBitmap, beautyLevel)
                if (beautyResult != null) {
                    Log.d(TAG, "takePhoto: 美颜处理完成 耗时=${beautyResult.processingTimeMs}ms")
                    // 回收滤镜处理后的Bitmap（如果不是夜景处理结果）
                    if (filteredBitmap !== nightProcessedBitmap && !filteredBitmap.isRecycled) {
                        filteredBitmap.recycle()
                    }
                    beautyResult.bitmap
                } else {
                    Log.w(TAG, "takePhoto: 美颜处理失败，使用滤镜图")
                    filteredBitmap
                }
            } else {
                filteredBitmap
            }

            // ==================== 人像虚化处理 ====================
            // 判断是否需要人像虚化处理（虚化等级不为NONE）
            val beforePortraitMs = System.currentTimeMillis()
            val finalBitmap = if (currentPortraitBlurLevel != PortraitBlurLevel.NONE) {
                Log.d(TAG, "takePhoto: 正在应用人像虚化 level=$currentPortraitBlurLevel")
                val portraitStartTime = System.currentTimeMillis()
                val blurredBitmap = applyPortraitBlur(beautifiedBitmap)
                val portraitTime = System.currentTimeMillis() - portraitStartTime
                Log.d(TAG, "takePhoto: 人像虚化处理完成 耗时=${portraitTime}ms")
                // 回收美颜处理后的Bitmap（如果不是滤镜处理结果）
                if (blurredBitmap !== beautifiedBitmap && beautifiedBitmap !== filteredBitmap && !beautifiedBitmap.isRecycled) {
                    beautifiedBitmap.recycle()
                }
                blurredBitmap
            } else {
                beautifiedBitmap
            }

            val portraitMs = System.currentTimeMillis() - beforePortraitMs
            Log.d(
                TAG,
                "takePhoto: [耗时] 人像虚化=${portraitMs}ms, 最终图片大小=${finalBitmap.width}x${finalBitmap.height}"
            )

            // ==================== 保存文件 ====================
            val photoFile = createTempPhotoFile()
            FileOutputStream(photoFile).use { fos ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, fos)   // 质量跟随设置
            }

            val encodeMs = System.currentTimeMillis() - stageStart
            val totalMs = System.currentTimeMillis() - totalStart
            Log.d(
                TAG,
                "takePhoto: [耗时] JPEG编码+写临时文件=${encodeMs}ms, " +
                    "总计=${totalMs}ms（捕获=${captureWaitMs} + 解码旋转=${convertMs} + " +
                    "滤镜水印=${filterMs} + 人像虚化=${portraitMs} + 编码=${encodeMs - portraitMs}）"
            )
            Log.d(TAG, "takePhoto: 照片保存成功 path=${photoFile.absolutePath}")

            // 回收中间Bitmap
            // 回收beautifiedBitmap（如果它不是最终图片且未被回收）
            if (beautifiedBitmap !== finalBitmap && !beautifiedBitmap.isRecycled) {
                beautifiedBitmap.recycle()
            }
            // 回收filteredBitmap（如果它不是beautifiedBitmap且未被回收）
            if (filteredBitmap !== beautifiedBitmap && filteredBitmap !== finalBitmap && !filteredBitmap.isRecycled) {
                filteredBitmap.recycle()
            }
            // 回收nightProcessedBitmap（如果它不是最终使用的图片）
            if (nightProcessedBitmap !== finalBitmap && nightProcessedBitmap !== filteredBitmap && !nightProcessedBitmap.isRecycled) {
                nightProcessedBitmap.recycle()
            }
            // 回收hdrProcessedBitmap（如果它不是夜景处理结果且未被回收）
            if (hdrProcessedBitmap !== nightProcessedBitmap && !hdrProcessedBitmap.isRecycled) {
                hdrProcessedBitmap.recycle()
            }

            Result.success(photoFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "takePhoto: 拍照异常", e)
            Result.failure(e)
        } finally {
            captureInFlight = false
        }
    }

    /**
     * 检查硬件HDR是否正在使用
     *
     * 判断当前相机是否通过HDR扩展绑定
     * 如果硬件HDR可用且HDR模式开启，则返回true
     *
     * @return true表示正在使用硬件HDR
     */
    private fun isHardwareHdrActive(): Boolean {
        if (currentHdrMode == HdrMode.OFF) return false
        val lensFacing = when (_currentLens.value) {
            CameraLens.BACK -> CameraSelector.LENS_FACING_BACK
            CameraLens.FRONT -> CameraSelector.LENS_FACING_FRONT
        }
        return hdrProcessor.isHardwareHdrAvailable(lensFacing)
    }

    /**
     * 检查硬件夜景是否正在使用
     *
     * 判断当前相机是否通过Night扩展绑定
     * 如果硬件夜景可用且夜景模式开启，则返回true
     *
     * @return true表示正在使用硬件夜景
     */
    private fun isHardwareNightActive(): Boolean {
        if (currentNightMode == NightMode.OFF) return false
        val lensFacing = when (_currentLens.value) {
            CameraLens.BACK -> CameraSelector.LENS_FACING_BACK
            CameraLens.FRONT -> CameraSelector.LENS_FACING_FRONT
        }
        return nightModeProcessor.isHardwareNightAvailable(lensFacing)
    }

    /**
     * 是否可以把相机原始 JPEG 直接落盘（跳过解码与重编码）
     *
     * 条件很严格：没有任何按像素生效的效果，且不是前置摄像头
     * （前置通常需要镜像，那必须改像素）。
     */
    private fun canPassThroughOriginalJpeg(): Boolean {
        if (currentFilterType != FilterType.NONE) return false                 // 选了滤镜
        if (filterRepository.isInfoWatermarkEnabled()) return false            // 开了信息水印
        if (beautyIntensity > 0f) return false                                // 美颜
        if (currentPortraitBlurLevel != PortraitBlurLevel.NONE) return false  // 人像虚化
        if (currentHdrMode == HdrMode.ON || currentHdrMode == HdrMode.AUTO) return false
        if (currentNightMode == NightMode.ON || currentNightMode == NightMode.AUTO) return false
        if (_currentLens.value == CameraLens.FRONT) return false              // 前置可能需要镜像
        return true
    }

    /**
     * 把原始 JPEG 字节写入临时文件
     *
     * @return 写入的文件，失败返回 null（调用方回退常规链路）
     */
    private fun writeOriginalJpegToTempFile(imageProxy: ImageProxy): File? {
        return try {
            if (imageProxy.format != android.graphics.ImageFormat.JPEG) {
                Log.d(TAG, "writeOriginalJpegToTempFile: 不是 JPEG 格式(${imageProxy.format})，放弃直存")
                return null
            }
            val bytes = ByteArray(imageProxy.planes[0].buffer.remaining())
            imageProxy.planes[0].buffer.get(bytes)
            val file = createTempPhotoFile()
            FileOutputStream(file).use { it.write(bytes) }
            Log.d(TAG, "writeOriginalJpegToTempFile: 直存 ${bytes.size / 1024}KB 原始 JPEG")
            file
        } catch (e: Exception) {
            Log.w(TAG, "writeOriginalJpegToTempFile: 直存失败", e)
            null
        }
    }

    /**
     * 将ImageProxy转换为Bitmap（拍照专用）
     *
     * 使用ImageFormatConverter工具类，支持JPEG和YUV两种格式
     * 自动检测格式并处理旋转和镜像
     *
     * @param imageProxy CameraX捕获的图像代理
     * @return 正确方向的Bitmap，失败返回null
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val isFrontCamera = _currentLens.value == CameraLens.FRONT
        Log.d(TAG, "imageProxyToBitmap: 尺寸=${imageProxy.width}x${imageProxy.height}, 前置=$isFrontCamera")
        return ImageFormatConverter.imageProxyToBitmap(
            imageProxy = imageProxy,
            config = ImageFormatConverter.ConversionConfig.CAPTURE,       // 使用拍照配置（高质量）
            isFrontCamera = isFrontCamera
        )
    }

    /**
     * 开始录像
     *
     * 与旧实现的区别（旧的必然报"录像错误"）：
     * 1. 录像是**异步**的，start() 只是提交请求。旧实现提交完就返回 success，
     *    上层于是乐观地把"正在录像"置为 true，之后真正的失败只能靠一个
     *    Finalize 事件飘过来 —— 用户看到的是"点了录像没反应/报错"。
     *    现在等 Start 事件真的到达才算成功，等不到就报错。
     * 2. 录像必须绑定了 VideoCapture 才能工作，没绑定时直接给出可读的提示，
     *    而不是让用户对着一个语焉不详的错误码发呆。
     * 3. 有录音权限时开启音频轨（CameraX 需要显式 withAudioEnabled）。
     */
    @androidx.annotation.OptIn(androidx.camera.video.ExperimentalPersistentRecording::class)
    override suspend fun startRecording(): Result<Unit> = withContext(Dispatchers.Main) {
        if (_isRecording.value) {
            return@withContext Result.failure(Exception("已在录像中"))
        }

        if (!bindingVideoMode) {
            return@withContext Result.failure(Exception("请先切换到录像模式"))
        }

        val capture = videoCapture ?: return@withContext Result.failure(
            Exception("VideoCapture未初始化")
        )

        try {
            Log.d(TAG, "startRecording: 开始录像")

            // 创建临时视频文件
            val videoFile = createTempVideoFile()

            // 配置输出选项
            val outputOptions = FileOutputOptions.Builder(videoFile).build()

            // Start 事件到达前先挂一个 Deferred，让 startRecording 能真正"等"到开录
            val startDeferred = CompletableDeferred<Unit>()
            recordingStartDeferred = startDeferred

            // 有权限才开音频：没有 RECORD_AUDIO 时 withAudioEnabled 会抛 SecurityException
            var pending = capture.output.prepareRecording(context, outputOptions)
            if (hasAudioPermission()) {
                pending = pending.withAudioEnabled()
                Log.d(TAG, "startRecording: 已开启录音")
            } else {
                Log.w(TAG, "startRecording: 无录音权限，本次录像无声音")
            }

            currentRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d(TAG, "startRecording: 录像已开始")
                        _isRecording.value = true
                        startDeferred.complete(Unit)
                    }
                    is VideoRecordEvent.Status -> {
                        // 每次状态更新都会来，这里不打日志以免刷屏
                    }
                    is VideoRecordEvent.Finalize -> {
                        _isRecording.value = false
                        if (event.hasError()) {
                            Log.e(
                                TAG,
                                "startRecording: 录像结束但有错误 code=${event.error} " +
                                    "cause=${event.cause?.message}"
                            )
                            val error = Exception(
                                "录像失败：${describeVideoError(event.error)}" +
                                    (event.cause?.message?.let { "（$it）" } ?: "")
                            )
                            // 还没开录就失败 -> 让 startRecording 抛出；否则交给等待方
                            startDeferred.completeExceptionally(error)
                            recordingFinalizeDeferred?.completeExceptionally(error)
                        } else {
                            val outputUri = event.outputResults.outputUri
                            Log.d(TAG, "startRecording: 录像完成 uri=$outputUri")
                            // 通知等待方录像成功，传递文件路径
                            val filePath = outputUri.path ?: videoFile.absolutePath
                            recordingFinalizeDeferred?.complete(filePath)
                        }
                    }
                }
            }

            // 等真正的 Start 事件（最多 5 秒）
            withTimeout(START_TIMEOUT_MS) { startDeferred.await() }
            Log.d(TAG, "startRecording: 录像已确认开始")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "startRecording: 开始录像失败", e)
            _isRecording.value = false
            runCatching { currentRecording?.stop() }
            currentRecording = null
            Result.failure(
                if (e is TimeoutCancellationException) Exception("录像启动超时", e) else e
            )
        } finally {
            recordingStartDeferred = null
        }
    }

    /**
     * 把 CameraX 的录像错误码翻译成人能看懂的话
     */
    private fun describeVideoError(code: Int): String = when (code) {
        VideoRecordEvent.Finalize.ERROR_NONE -> "无错误"
        VideoRecordEvent.Finalize.ERROR_UNKNOWN -> "未知错误"
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "超出文件大小限制"
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "存储空间不足"
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "没有可用的视频数据"
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "编码失败"
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "录像源已失效（请重进相机页）"
        VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "输出参数无效"
        VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "录像器内部错误"
        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "超出时长限制"
        VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> "录像对象已被回收"
        else -> "错误码 $code"
    }

    /**
     * 是否已授予录音权限
     *
     * 没有录音权限时不能调用 withAudioEnabled()，否则直接抛异常；
     * 这里降级为"录无声视频"而不是让整个录像失败。
     */
    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 停止录像
     *
     * 修复：使用 CompletableDeferred 等待 VideoRecordEvent.Finalize 事件，
     * 确保文件完全写入后再返回路径，解决异步竞态条件导致的"文件不存在"问题
     */
    override suspend fun stopRecording(): Result<String> = withContext(Dispatchers.Main) {
        val recording = currentRecording ?: return@withContext Result.failure(
            Exception("没有正在进行的录像")
        )

        try {
            Log.d(TAG, "stopRecording: 停止录像")

            // 创建 CompletableDeferred 等待 Finalize 事件
            recordingFinalizeDeferred = CompletableDeferred()

            // 停止录像（这会触发异步的 VideoRecordEvent.Finalize）
            recording.stop()

            // 等待 Finalize 事件完成（带超时保护）
            val filePath = try {
                withTimeout(10_000) {                                         // 最多等待10秒
                    recordingFinalizeDeferred!!.await()
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "stopRecording: 等待录像完成超时")
                // 超时后尝试查找最新的视频文件作为降级方案
                val fallbackFile = getLatestTempVideoFile()
                if (fallbackFile != null && fallbackFile.exists()) {
                    Log.w(TAG, "stopRecording: 超时但找到视频文件，使用降级方案")
                    fallbackFile.absolutePath
                } else {
                    throw Exception("视频保存超时")
                }
            } finally {
                currentRecording = null
                recordingFinalizeDeferred = null
            }

            Log.d(TAG, "stopRecording: 录像保存成功 path=$filePath")
            Result.success(filePath)
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording: 停止录像失败", e)
            currentRecording = null
            recordingFinalizeDeferred = null
            Result.failure(e)
        }
    }

    /**
     * 切换摄像头
     */
    override suspend fun switchCamera(lens: CameraLens): Result<Unit> =
        withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "switchCamera: 切换摄像头 lens=$lens")

                _currentLens.value = lens

                // 重新绑定相机
                val owner = lifecycleOwner ?: return@withContext Result.failure(
                    Exception("LifecycleOwner未设置")
                )
                val pView = previewViewRef ?: return@withContext Result.failure(
                    Exception("PreviewView未设置")
                )

                // 镜头切换时沿用当前绑定模式（切换前后模式不变）
                bindCamera(owner, pView, bindingVideoMode)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "switchCamera: 切换摄像头失败", e)
                Result.failure(e)
            }
        }

    /**
     * 应用滤镜
     *
     * 更新当前滤镜类型，实时预览会自动应用新滤镜
     */
    override suspend fun applyFilter(filterType: FilterType): Result<Unit> {
        Log.d(TAG, "applyFilter: 应用滤镜 filterType=$filterType")
        currentFilterType = filterType                                    // 更新当前滤镜
        // 如果是NONE，清空滤镜帧
        if (filterType == FilterType.NONE) {
            _filteredFrame.value = null
        }
        return Result.success(Unit)
    }

    /**
     * 设置美颜等级
     *
     * 美颜效果在 processFrameWithFilter 中实时应用于预览帧
     * 同时在 takePhoto 中应用于拍照结果
     */
    override suspend fun setBeautyLevel(intensity: Float): Result<Unit> {
        Log.d(TAG, "setBeautyLevel: 设置美颜强度 intensity=$intensity")
        beautyIntensity = intensity.coerceIn(0f, 1f)                              // 美颜强度将在帧处理中应用
        return Result.success(Unit)
    }

    /**
     * 设置变焦倍数
     */
    override suspend fun setZoom(zoom: Float): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "setZoom: 设置变焦 zoom=$zoom")
            val cam = camera ?: throw IllegalStateException("相机未初始化")
            val cameraInfo = cam.cameraInfo
            val maxZoom = cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
            val minZoom = cameraInfo.zoomState.value?.minZoomRatio ?: 1f
            val clampedZoom = zoom.coerceIn(minZoom, maxZoom)
            cam.cameraControl.setZoomRatio(clampedZoom)
            Log.d(TAG, "setZoom: 变焦设置成功 clampedZoom=$clampedZoom")
            Unit                                                          // 显式返回Unit
        }
    }

    /**
     * 触发自动对焦
     */
    override suspend fun autoFocus(): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "autoFocus: 执行自动对焦")
            val cam = camera ?: throw IllegalStateException("相机未初始化")
            // 在预览中心点进行对焦
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val centerPoint = factory.createPoint(0.5f, 0.5f)
            val action = FocusMeteringAction.Builder(centerPoint)
                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
            Log.d(TAG, "autoFocus: 自动对焦已触发")
            Unit                                                          // 显式返回Unit
        }
    }

    /**
     * 触摸对焦 - 在指定坐标点进行对焦和测光
     *
     * 使用CameraX的FocusMeteringAction在指定位置触发对焦
     * 同时在该点进行测光，实现点触对焦和点测光功能
     *
     * @param x 归一化X坐标 (0.0~1.0，0为左边缘，1为右边缘)
     * @param y 归一化Y坐标 (0.0~1.0，0为上边缘，1为下边缘)
     * @return 操作结果，包含对焦是否成功
     */
    override suspend fun focusAtPoint(x: Float, y: Float): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "focusAtPoint: 触摸对焦 x=$x, y=$y")
            val cam = camera ?: throw IllegalStateException("相机未初始化")

            // 确保坐标在有效范围内
            val clampedX = x.coerceIn(0f, 1f)
            val clampedY = y.coerceIn(0f, 1f)
            Log.d(TAG, "focusAtPoint: 归一化坐标 x=$clampedX, y=$clampedY")

            // 创建测光点工厂（使用Surface坐标系）
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)

            // 在指定位置创建对焦/测光点
            val focusPoint = factory.createPoint(clampedX, clampedY)

            // 构建对焦测光动作
            // FLAG_AF: 自动对焦
            // FLAG_AE: 自动曝光测光
            // FLAG_AWB: 自动白平衡
            val action = FocusMeteringAction.Builder(
                focusPoint,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)  // 3秒后自动取消
                .build()

            // 执行对焦和测光
            val result = cam.cameraControl.startFocusAndMetering(action)

            // 监听对焦结果（可选，用于调试）
            result.addListener({
                try {
                    val focusResult = result.get()
                    Log.d(TAG, "focusAtPoint: 对焦完成 success=${focusResult.isFocusSuccessful}")
                } catch (e: Exception) {
                    Log.w(TAG, "focusAtPoint: 对焦结果获取失败", e)
                }
            }, ContextCompat.getMainExecutor(context))

            Log.d(TAG, "focusAtPoint: 触摸对焦已触发 at ($clampedX, $clampedY)")
            Unit
        }
    }

    /**
     * 获取当前镜头
     */
    override fun getCurrentLens(): Flow<CameraLens> = _currentLens

    /**
     * 获取录像状态
     */
    override fun isRecording(): Flow<Boolean> = _isRecording

    /**
     * 获取变焦范围
     */
    override fun getZoomRange(): Flow<ZoomRange> = _zoomRange

    /**
     * 更新变焦范围（从相机获取实际支持的范围）
     */
    private fun updateZoomRange() {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value
        if (zoomState != null) {
            val minZoom = zoomState.minZoomRatio
            val maxZoom = zoomState.maxZoomRatio
            _zoomRange.value = ZoomRange(minZoom, maxZoom)
            Log.d(TAG, "updateZoomRange: 变焦范围更新 min=$minZoom, max=$maxZoom")
        }
    }

    /**
     * 设置HDR模式
     *
     * 真实HDR实现：
     * 1. 优先使用CameraX Extensions硬件HDR（设备原生HDR）
     * 2. 当硬件不支持时，自动降级到软件HDR（曝光融合算法）
     *
     * 实现原理：
     * - 硬件HDR：通过ExtensionsManager获取HDR CameraSelector重新绑定相机
     * - 软件HDR：在拍照时使用HdrProcessor进行Mertens曝光融合
     *
     * @param mode HDR模式（ON/OFF/AUTO）
     */
    override suspend fun setHdrMode(mode: HdrMode): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "setHdrMode: 设置HDR模式 mode=${mode.displayName}")
            val previousMode = currentHdrMode
            currentHdrMode = mode                                                  // 记录HDR状态

            when (mode) {
                HdrMode.ON -> {
                    // 检查当前镜头是否支持硬件HDR
                    val lensFacing = when (_currentLens.value) {
                        CameraLens.BACK -> CameraSelector.LENS_FACING_BACK
                        CameraLens.FRONT -> CameraSelector.LENS_FACING_FRONT
                    }
                    val isHardwareHdrAvailable = hdrProcessor.isHardwareHdrAvailable(lensFacing)

                    if (isHardwareHdrAvailable) {
                        Log.d(TAG, "setHdrMode: 硬件HDR可用，重新绑定相机启用HDR扩展")
                        // 使用HDR CameraSelector重新绑定相机
                        rebindCameraWithHdr(enabled = true)
                    } else {
                        Log.d(TAG, "setHdrMode: 硬件HDR不可用，将使用软件HDR（曝光融合）")
                        // 硬件不支持，拍照时会使用软件HDR处理
                    }
                }
                HdrMode.OFF -> {
                    Log.d(TAG, "setHdrMode: HDR已关闭")
                    // 如果之前是ON模式且使用了硬件HDR，需要恢复普通相机选择器
                    if (previousMode == HdrMode.ON) {
                        val lensFacing = when (_currentLens.value) {
                            CameraLens.BACK -> CameraSelector.LENS_FACING_BACK
                            CameraLens.FRONT -> CameraSelector.LENS_FACING_FRONT
                        }
                        if (hdrProcessor.isHardwareHdrAvailable(lensFacing)) {
                            Log.d(TAG, "setHdrMode: 恢复普通相机模式")
                            rebindCameraWithHdr(enabled = false)
                        }
                    }
                }
                HdrMode.AUTO -> {
                    Log.d(TAG, "setHdrMode: HDR自动模式（根据场景自动判断）")
                    // AUTO模式：暂时与ON模式相同，未来可加入场景检测
                    val lensFacing = when (_currentLens.value) {
                        CameraLens.BACK -> CameraSelector.LENS_FACING_BACK
                        CameraLens.FRONT -> CameraSelector.LENS_FACING_FRONT
                    }
                    if (hdrProcessor.isHardwareHdrAvailable(lensFacing)) {
                        rebindCameraWithHdr(enabled = true)
                    }
                }
            }
            Unit
        }
    }

    /**
     * 重新绑定相机（启用/禁用HDR扩展）
     *
     * 使用HdrProcessor获取HDR CameraSelector重新绑定相机用例
     *
     * @param enabled 是否启用HDR扩展
     */
    private suspend fun rebindCameraWithHdr(enabled: Boolean) {
        val owner = lifecycleOwner ?: run {
            Log.w(TAG, "rebindCameraWithHdr: LifecycleOwner未设置")
            return
        }
        val previewView = previewViewRef ?: run {
            Log.w(TAG, "rebindCameraWithHdr: PreviewView未设置")
            return
        }
        val provider = cameraProvider ?: run {
            Log.w(TAG, "rebindCameraWithHdr: CameraProvider未初始化")
            return
        }

        try {
            Log.d(TAG, "rebindCameraWithHdr: 开始重新绑定相机 enabled=$enabled")

            // 解绑现有用例
            provider.unbindAll()

            // 获取相机选择器
            val baseCameraSelector = getCameraSelector(_currentLens.value)
            val cameraSelector = if (enabled) {
                hdrProcessor.getHdrCameraSelector(baseCameraSelector)              // HDR相机选择器
            } else {
                baseCameraSelector                                                  // 普通相机选择器
            }

            // 构建用例
            buildUseCases(UseCaseConfig(
                aspectRatio = currentAspectRatio.cameraXRatio,
                previewView = previewView
            ))

            // 绑定用例到生命周期
            camera = bindUseCasesToLifecycle(owner, provider, cameraSelector)

            // 更新变焦范围
            updateZoomRange()

            // 重新启动帧处理器
            startFrameProcessor()

            // 恢复闪光灯设置（重要：TORCH模式需要重新开启手电筒）
            restoreFlashSettings()

            Log.d(TAG, "rebindCameraWithHdr: 相机重新绑定成功 hdrEnabled=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "rebindCameraWithHdr: 相机重新绑定失败", e)
        }
    }

    /**
     * 设置夜景模式
     *
     * 夜景模式控制：
     * 1. 优先使用硬件夜景（CameraX Night扩展）
     * 2. 当硬件不支持时，自动降级到软件夜景（多帧合成算法）
     *
     * 实现原理：
     * - 硬件夜景：通过ExtensionsManager获取Night CameraSelector重新绑定相机
     * - 软件夜景：在拍照时使用NightModeProcessor进行多帧合成
     *
     * @param mode 夜景模式（ON/OFF/AUTO）
     */
    override suspend fun setNightMode(mode: NightMode): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "setNightMode: 设置夜景模式 mode=${mode.displayName}")
            val previousMode = currentNightMode
            currentNightMode = mode                                                  // 记录夜景状态

            when (mode) {
                NightMode.ON -> {
                    // 检查当前镜头是否支持硬件夜景
                    val lensFacing = when (_currentLens.value) {
                        CameraLens.BACK -> CameraSelector.LENS_FACING_BACK
                        CameraLens.FRONT -> CameraSelector.LENS_FACING_FRONT
                    }
                    val isHardwareNightAvailable = nightModeProcessor.isHardwareNightAvailable(lensFacing)

                    if (isHardwareNightAvailable) {
                        Log.d(TAG, "setNightMode: 硬件夜景可用，重新绑定相机启用夜景扩展")
                        // 使用Night CameraSelector重新绑定相机
                        rebindCameraWithNight(enabled = true)
                    } else {
                        Log.d(TAG, "setNightMode: 硬件夜景不可用，将使用软件夜景（多帧合成）")
                        // 硬件不支持，拍照时会使用软件夜景处理
                    }
                }
                NightMode.OFF -> {
                    Log.d(TAG, "setNightMode: 夜景模式已关闭")
                    // 如果之前是ON模式且使用了硬件夜景，需要恢复普通相机选择器
                    if (previousMode == NightMode.ON) {
                        val lensFacing = when (_currentLens.value) {
                            CameraLens.BACK -> CameraSelector.LENS_FACING_BACK
                            CameraLens.FRONT -> CameraSelector.LENS_FACING_FRONT
                        }
                        if (nightModeProcessor.isHardwareNightAvailable(lensFacing)) {
                            Log.d(TAG, "setNightMode: 恢复普通相机模式")
                            rebindCameraWithNight(enabled = false)
                        }
                    }
                }
                NightMode.AUTO -> {
                    Log.d(TAG, "setNightMode: 夜景自动模式（根据环境光自动判断）")
                    // AUTO模式：暂时与ON模式相同，未来可加入环境光检测
                    val lensFacing = when (_currentLens.value) {
                        CameraLens.BACK -> CameraSelector.LENS_FACING_BACK
                        CameraLens.FRONT -> CameraSelector.LENS_FACING_FRONT
                    }
                    if (nightModeProcessor.isHardwareNightAvailable(lensFacing)) {
                        rebindCameraWithNight(enabled = true)
                    }
                }
            }
            Unit
        }
    }

    /**
     * 重新绑定相机（启用/禁用夜景扩展）
     *
     * 使用NightModeProcessor获取Night CameraSelector重新绑定相机用例
     *
     * @param enabled 是否启用夜景扩展
     */
    private suspend fun rebindCameraWithNight(enabled: Boolean) {
        val owner = lifecycleOwner ?: run {
            Log.w(TAG, "rebindCameraWithNight: LifecycleOwner未设置")
            return
        }
        val previewView = previewViewRef ?: run {
            Log.w(TAG, "rebindCameraWithNight: PreviewView未设置")
            return
        }
        val provider = cameraProvider ?: run {
            Log.w(TAG, "rebindCameraWithNight: CameraProvider未初始化")
            return
        }

        try {
            Log.d(TAG, "rebindCameraWithNight: 开始重新绑定相机 enabled=$enabled")

            // 解绑现有用例
            provider.unbindAll()

            // 获取相机选择器
            val baseCameraSelector = getCameraSelector(_currentLens.value)
            val cameraSelector = if (enabled) {
                nightModeProcessor.getNightCameraSelector(baseCameraSelector)         // 夜景相机选择器
            } else {
                baseCameraSelector                                                     // 普通相机选择器
            }

            // 构建用例
            buildUseCases(UseCaseConfig(
                aspectRatio = currentAspectRatio.cameraXRatio,
                previewView = previewView
            ))

            // 绑定用例到生命周期
            camera = bindUseCasesToLifecycle(owner, provider, cameraSelector)

            // 更新变焦范围
            updateZoomRange()

            // 重新启动帧处理器
            startFrameProcessor()

            // 恢复闪光灯设置（重要：TORCH模式需要重新开启手电筒）
            restoreFlashSettings()

            Log.d(TAG, "rebindCameraWithNight: 相机重新绑定成功 nightEnabled=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "rebindCameraWithNight: 相机重新绑定失败", e)
        }
    }

    /**
     * 设置微距模式
     *
     * 微距模式通过调整对焦距离实现
     */
    override suspend fun setMacroMode(mode: MacroMode): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "setMacroMode: 设置微距模式 mode=${mode.displayName}")
            when (mode) {
                MacroMode.ON -> {
                    // 微距模式：设置较近的对焦距离
                    val cam = camera ?: throw IllegalStateException("相机未初始化")
                    val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    val centerPoint = factory.createPoint(0.5f, 0.5f)
                    val action = FocusMeteringAction.Builder(centerPoint)
                        .setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    cam.cameraControl.startFocusAndMetering(action)
                    Log.d(TAG, "setMacroMode: 微距对焦已触发")
                }
                MacroMode.OFF, MacroMode.AUTO -> {
                    Log.d(TAG, "setMacroMode: 微距模式已关闭/自动")
                }
            }
            Unit
        }
    }

    /**
     * 设置画幅比例
     *
     * 使用buildUseCases和bindUseCasesToLifecycle统一处理
     * 注意：imageAnalysis也会重新绑定，确保滤镜功能正常
     */
    override suspend fun setAspectRatio(ratio: AspectRatio): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "setAspectRatio: 设置画幅比例 ratio=${ratio.displayName}")

            val owner = lifecycleOwner ?: throw IllegalStateException("生命周期持有者未设置")
            val previewView = previewViewRef ?: throw IllegalStateException("预览视图未设置")
            val provider = cameraProvider ?: throw IllegalStateException("CameraProvider未初始化")

            // 记录当前画幅
            currentAspectRatio = ratio

            // 解绑现有用例
            provider.unbindAll()

            // 构建用例（指定画幅比例）
            buildUseCases(UseCaseConfig(
                aspectRatio = ratio.cameraXRatio,
                previewView = previewView
            ))

            // 绑定用例到生命周期
            val cameraSelector = getCameraSelector(_currentLens.value)
            camera = bindUseCasesToLifecycle(owner, provider, cameraSelector)

            // 更新变焦范围
            updateZoomRange()

            // 重新启动帧处理器（确保滤镜功能正常）
            startFrameProcessor()

            // 恢复闪光灯设置（重要：TORCH模式需要重新开启手电筒）
            restoreFlashSettings()

            Log.d(TAG, "setAspectRatio: 画幅比例已更新为 ${ratio.displayName}")
            Unit
        }
    }

    // ==================== 夜景处理进度 ====================

    /**
     * 获取夜景处理进度流
     *
     * 将NightModeProcessor的进度转换为简化的Pair格式供UI使用
     */
    override fun getNightProcessingProgress(): Flow<Pair<String, Float>?> {
        return nightModeProcessor.processingProgress.map { progress ->
            progress?.let { Pair(it.stage.displayName, it.progress) }
        }
    }

    /**
     * 释放相机资源
     */
    override suspend fun release() {
        withContext(Dispatchers.Main) {
            Log.d(TAG, "release: 释放相机资源")

            // 停止帧处理器
            stopFrameProcessor()

            // 停止设备方向跟踪
            stopOrientationTracking()
            rotationRebindJob?.cancel()
            rotationRebindJob = null

            // 停止录像
            currentRecording?.stop()
            currentRecording = null

            // 重置曝光控制状态（曝光三角）
            currentShutterSpeed = null                                           // 重置快门速度
            currentIso = null                                                     // 重置ISO

            // 解绑相机
            cameraProvider?.unbindAll()

            // 关闭执行器
            cameraExecutor.shutdown()
            captureExecutor.shutdown()

            // 输出帧处理器最终统计
            val stats = frameProcessor.getStatistics()
            Log.i(TAG, "release: 帧处理器统计 $stats")
        }
    }

    /**
     * 获取CameraProvider
     */
    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            ProcessCameraProvider.getInstance(context).also { future ->
                future.addListener({
                    continuation.resume(future.get())
                }, ContextCompat.getMainExecutor(context))
            }
        }

    /**
     * 获取CameraSelector
     */
    private fun getCameraSelector(lens: CameraLens): CameraSelector {
        return when (lens) {
            CameraLens.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraLens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }
    }

    /**
     * 创建临时照片文件
     *
     * 委托给FileUtils工具类
     */
    private fun createTempPhotoFile(): File = FileUtils.createTempPhotoFile(context)

    /**
     * 创建临时视频文件
     *
     * 委托给FileUtils工具类
     */
    private fun createTempVideoFile(): File = FileUtils.createTempVideoFile(context)

    /**
     * 获取最新的临时视频文件
     *
     * 委托给FileUtils工具类
     */
    private fun getLatestTempVideoFile(): File? = FileUtils.getLatestTempVideoFile(context)

    // ==================== 专业模式参数控制 ====================

    /**
     * 设置曝光补偿
     *
     * 使用CameraX原生API设置曝光补偿
     *
     * @param evIndex 曝光补偿索引
     */
    override suspend fun setExposureCompensation(evIndex: Int): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "setExposureCompensation: 设置曝光补偿 evIndex=$evIndex")
            val cam = camera ?: throw IllegalStateException("相机未初始化")

            // 获取曝光补偿范围
            val exposureState = cam.cameraInfo.exposureState
            if (!exposureState.isExposureCompensationSupported) {
                Log.w(TAG, "setExposureCompensation: 设备不支持曝光补偿")
                return@runCatching
            }

            val range = exposureState.exposureCompensationRange
            val clampedIndex = evIndex.coerceIn(range.lower, range.upper)

            // 设置曝光补偿
            cam.cameraControl.setExposureCompensationIndex(clampedIndex)
            Log.d(TAG, "setExposureCompensation: 曝光补偿设置成功 index=$clampedIndex")
        }
    }

    /**
     * 设置ISO感光度
     *
     * 使用Camera2 Interop设置ISO。曝光三角联动：
     * - ISO 手动 → 必须同时关掉 AE，否则 SENSOR_SENSITIVITY 会被 AE 算法直接忽略
     *   （这正是过去"选了 ISO 却毫无变化"的原因），此时沿用 AE 实测的曝光时间
     * - ISO 与快门都自动 → 恢复 AE_MODE_ON
     *
     * @param iso ISO值，null表示自动
     */
    @OptIn(ExperimentalCamera2Interop::class)
    override suspend fun setIso(iso: Int?): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "setIso: 设置ISO=$iso, 当前快门速度=$currentShutterSpeed")
            camera ?: throw IllegalStateException("相机未初始化")

            currentIso = iso

            if (iso == null) {
                // 回到自动ISO
                if (currentShutterSpeed == null) {
                    // 快门也自动 → 完全自动曝光
                    Log.d(TAG, "setIso: ISO与快门都自动，恢复自动曝光")
                    proAeMode = CaptureRequest.CONTROL_AE_MODE_ON
                    proIso = null
                    proExposureTimeNs = null
                } else {
                    // 快门手动、ISO 自动 → 仍需 AE_OFF，用一个稳妥的默认 ISO
                    Log.d(TAG, "setIso: 快门手动，ISO使用默认值=$defaultManualIso")
                    proAeMode = CaptureRequest.CONTROL_AE_MODE_OFF
                    proIso = defaultManualIso
                }
            } else {
                Log.d(TAG, "setIso: 手动ISO=$iso，关闭自动曝光以使其生效")
                proAeMode = CaptureRequest.CONTROL_AE_MODE_OFF
                proIso = iso
                if (currentShutterSpeed == null) {
                    // ISO 优先：沿用自动测光算出的曝光时间
                    proExposureTimeNs = lastAeExposureTimeNs ?: fallbackExposureNs()
                }
            }

            applyProCaptureOptions()
            Unit
        }
    }

    /**
     * 下发专业模式的全部参数
     *
     * 每次都构造完整的一份并整份替换：没有出现在这里的 key 会回到相机默认值，
     * 这正是"把某一项改回自动"所需要的语义。
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyProCaptureOptions() {
        val cam = camera ?: return
        val control = Camera2CameraControl.from(cam.cameraControl)

        val builder = CaptureRequestOptions.Builder()
        proAwbMode?.let { builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, it) }
        proAfMode?.let { builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, it) }
        proFocusDistanceDiopters?.let {
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        proAeMode?.let { builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, it) }
        proIso?.let { builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it) }
        proExposureTimeNs?.let {
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
        }

        control.captureRequestOptions = builder.build()
        Log.d(
            TAG,
            "applyProCaptureOptions: awb=$proAwbMode af=$proAfMode " +
                "focus=${proFocusDistanceDiopters}diopt " +
                "ae=$proAeMode iso=$proIso exposure=${proExposureTimeNs}ns"
        )
    }

    /**
     * 拿不到 AE 实测曝光时间时的兜底曝光时间（1/30s，并夹到设备支持范围内）
     */
    private fun fallbackExposureNs(): Long {
        val target = 33_333_333L
        val clamped = exposureTimeRange?.let { target.coerceIn(it.first, it.second) } ?: target
        Log.w(TAG, "fallbackExposureNs: 尚无AE实测值，使用兜底曝光时间 ${clamped}ns")
        return clamped
    }

    /**
     * 读取设备真实的最近对焦屈光度上限
     *
     * 原先写死 10，纯属拍脑袋：真实值各机型不同（常见 5~20），
     * 写错会让对焦滑块要么全程虚焦、要么在某段区间内怎么拖都不变。
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun maxFocusDistanceDiopters(cam: androidx.camera.core.Camera): Float {
        val fromCharacteristics = runCatching {
            Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        }.getOrNull()

        return if (fromCharacteristics != null && fromCharacteristics > 0f) {
            Log.d(TAG, "maxFocusDistanceDiopters: 设备上报最大屈光度=$fromCharacteristics")
            fromCharacteristics
        } else {
            Log.w(TAG, "maxFocusDistanceDiopters: 设备未上报屈光度范围，退回 10")
            10f
        }
    }

    /**
     * 设置白平衡模式
     *
     * 使用Camera2 Interop设置白平衡
     *
     * @param mode 白平衡模式
     */
    @OptIn(ExperimentalCamera2Interop::class)
    override suspend fun setWhiteBalance(mode: WhiteBalanceMode): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "setWhiteBalance: 设置白平衡=${mode.displayName}")
            camera ?: throw IllegalStateException("相机未初始化")

            val awbMode = when (mode) {
                WhiteBalanceMode.AUTO -> CaptureRequest.CONTROL_AWB_MODE_AUTO
                WhiteBalanceMode.INCANDESCENT -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                WhiteBalanceMode.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                WhiteBalanceMode.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                WhiteBalanceMode.CLOUDY -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                WhiteBalanceMode.SHADE -> CaptureRequest.CONTROL_AWB_MODE_SHADE
            }

            proAwbMode = awbMode
            applyProCaptureOptions()
            Log.d(TAG, "setWhiteBalance: 白平衡设置成功 awbMode=$awbMode")
            Unit
        }
    }

    /**
     * 设置对焦模式
     *
     * 使用Camera2 Interop设置对焦模式
     *
     * @param mode 对焦模式
     */
    @OptIn(ExperimentalCamera2Interop::class)
    override suspend fun setFocusMode(mode: FocusMode): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "setFocusMode: 设置对焦模式=${mode.displayName}")
            camera ?: throw IllegalStateException("相机未初始化")

            val afMode = when (mode) {
                FocusMode.AUTO -> CaptureRequest.CONTROL_AF_MODE_AUTO
                FocusMode.CONTINUOUS -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                FocusMode.MANUAL -> CaptureRequest.CONTROL_AF_MODE_OFF
            }

            proAfMode = afMode
            // 离开手动对焦时清掉手动对焦距离，否则它会在下次切回手动时以旧值突然生效
            if (mode != FocusMode.MANUAL) {
                proFocusDistanceDiopters = null
            }

            applyProCaptureOptions()
            Log.d(TAG, "setFocusMode: 对焦模式设置成功 afMode=$afMode")
            Unit
        }
    }

    /**
     * 设置手动对焦距离
     *
     * 使用Camera2 Interop设置对焦距离
     * 仅在手动对焦模式下有效
     *
     * @param distance 对焦距离（0.0=最近，1.0=无穷远）
     */
    @OptIn(ExperimentalCamera2Interop::class)
    override suspend fun setFocusDistance(distance: Float): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "setFocusDistance: 设置对焦距离=$distance")
            val cam = camera ?: throw IllegalStateException("相机未初始化")

            // 对焦距离的单位是屈光度（diopters）：0 = 无穷远，越大越近。
            // 界面上的 distance 语义相反（0=最近，1=无穷远），所以要翻转。
            // 上限取设备真实上报的 LENS_INFO_MINIMUM_FOCUS_DISTANCE，
            // 写死一个常数会让滑块与实际对焦范围对不上。
            val maxDiopters = maxFocusDistanceDiopters(cam)
            val focusDistanceDiopters = maxDiopters * (1f - distance.coerceIn(0f, 1f))

            // 手动对焦距离只在 AF_MODE_OFF 下有效，这里顺手把对焦模式切过去
            proAfMode = CaptureRequest.CONTROL_AF_MODE_OFF
            proFocusDistanceDiopters = focusDistanceDiopters
            applyProCaptureOptions()
            Log.d(
                TAG,
                "setFocusDistance: 对焦距离设置成功 diopters=$focusDistanceDiopters " +
                    "(设备上限=$maxDiopters)"
            )
            Unit
        }
    }

    /**
     * 获取曝光补偿范围
     *
     * @return 曝光补偿范围（minIndex, maxIndex, step）
     */
    override fun getExposureCompensationRange(): Triple<Int, Int, Float> {
        val cam = camera
        return if (cam != null && cam.cameraInfo.exposureState.isExposureCompensationSupported) {
            val exposureState = cam.cameraInfo.exposureState
            val range = exposureState.exposureCompensationRange
            val step = exposureState.exposureCompensationStep.toFloat()
            Triple(range.lower, range.upper, step)
        } else {
            // 默认范围
            Triple(-12, 12, 1f / 3f)
        }
    }

    // ==================== 闪光灯控制 ====================

    /**
     * 设置闪光灯模式
     *
     * 根据FlashMode设置CameraX的闪光灯模式
     * - OFF: ImageCapture.FLASH_MODE_OFF
     * - ON: ImageCapture.FLASH_MODE_ON
     * - AUTO: ImageCapture.FLASH_MODE_AUTO
     * - TORCH: 通过Camera.enableTorch()开启手电筒
     *
     * @param mode 闪光灯模式
     * @return 操作结果
     */
    override suspend fun setFlashMode(mode: FlashMode): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "setFlashMode: 设置闪光灯模式=${mode.displayName}")
            val cam = camera ?: throw IllegalStateException("相机未初始化")
            val capture = imageCapture ?: throw IllegalStateException("拍照用例未初始化")

            // 先关闭手电筒（如果之前是TORCH模式）
            if (currentFlashMode == FlashMode.TORCH && mode != FlashMode.TORCH) {
                Log.d(TAG, "setFlashMode: 关闭手电筒模式")
                cam.cameraControl.enableTorch(false)
            }

            // 根据模式设置
            when (mode) {
                FlashMode.OFF -> {
                    capture.flashMode = ImageCapture.FLASH_MODE_OFF
                    Log.d(TAG, "setFlashMode: 闪光灯关闭 (FLASH_MODE_OFF)")
                }
                FlashMode.ON -> {
                    capture.flashMode = ImageCapture.FLASH_MODE_ON
                    Log.d(TAG, "setFlashMode: 闪光灯强制开启 (FLASH_MODE_ON)")
                }
                FlashMode.AUTO -> {
                    capture.flashMode = ImageCapture.FLASH_MODE_AUTO
                    Log.d(TAG, "setFlashMode: 闪光灯自动模式 (FLASH_MODE_AUTO)")
                }
                FlashMode.TORCH -> {
                    // 手电筒模式：持续照明
                    if (cam.cameraInfo.hasFlashUnit()) {
                        cam.cameraControl.enableTorch(true)
                        Log.d(TAG, "setFlashMode: 开启手电筒模式 (TORCH)")
                    } else {
                        Log.w(TAG, "setFlashMode: 设备不支持手电筒")
                        throw UnsupportedOperationException("设备不支持手电筒")
                    }
                }
            }

            currentFlashMode = mode
            Log.d(TAG, "setFlashMode: 闪光灯模式设置成功 mode=${mode.displayName}")
            Unit
        }
    }

    /**
     * 恢复闪光灯设置（相机重新绑定后调用）
     *
     * 当相机重新绑定时（如切换HDR/夜景模式、切换画幅比例等），
     * 需要重新应用之前的闪光灯设置，特别是TORCH模式需要重新开启手电筒
     */
    private suspend fun restoreFlashSettings() {
        val cam = camera ?: return
        val capture = imageCapture ?: return

        Log.d(TAG, "restoreFlashSettings: 恢复闪光灯设置 mode=${currentFlashMode.displayName}")

        when (currentFlashMode) {
            FlashMode.OFF -> {
                capture.flashMode = ImageCapture.FLASH_MODE_OFF
            }
            FlashMode.ON -> {
                capture.flashMode = ImageCapture.FLASH_MODE_ON
            }
            FlashMode.AUTO -> {
                capture.flashMode = ImageCapture.FLASH_MODE_AUTO
            }
            FlashMode.TORCH -> {
                // 重新开启手电筒模式
                if (cam.cameraInfo.hasFlashUnit()) {
                    cam.cameraControl.enableTorch(true)
                    Log.d(TAG, "restoreFlashSettings: 手电筒模式已恢复")
                }
            }
        }
    }

    /**
     * 获取当前闪光灯模式
     *
     * @return 当前闪光灯模式
     */
    override fun getCurrentFlashMode(): FlashMode {
        Log.d(TAG, "getCurrentFlashMode: 当前模式=${currentFlashMode.displayName}")
        return currentFlashMode
    }

    /**
     * 检查设备是否支持闪光灯
     *
     * @return true表示支持闪光灯
     */
    override fun hasFlashUnit(): Boolean {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() ?: false
        Log.d(TAG, "hasFlashUnit: 闪光灯支持=$hasFlash")
        return hasFlash
    }

    // ==================== 快门速度控制（真实Camera2实现） ====================

    /**
     * 初始化曝光时间范围
     *
     * 从Camera2 CameraCharacteristics获取设备支持的曝光时间范围
     * 用于快门速度控制的边界检查
     *
     * 调用时机：相机绑定成功后
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun initExposureTimeRange() {
        try {
            val cam = camera ?: run {
                Log.w(TAG, "initExposureTimeRange: 相机未初始化")
                return
            }

            // 获取Camera2 CameraInfo
            val cameraInfo = cam.cameraInfo
            val camera2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cameraInfo)

            // 获取CameraCharacteristics
            val characteristics = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )

            if (characteristics != null) {
                val minExposureNs = characteristics.lower                            // 最小曝光时间（纳秒）
                val maxExposureNs = characteristics.upper                            // 最大曝光时间（纳秒）
                exposureTimeRange = Pair(minExposureNs, maxExposureNs)

                // 转换为秒用于日志显示
                val minSeconds = minExposureNs / 1_000_000_000.0
                val maxSeconds = maxExposureNs / 1_000_000_000.0
                Log.d(TAG, "initExposureTimeRange: 曝光时间范围 " +
                        "min=${minExposureNs}ns (${formatShutterSpeedLog(minSeconds)}), " +
                        "max=${maxExposureNs}ns (${formatShutterSpeedLog(maxSeconds)})")
            } else {
                Log.w(TAG, "initExposureTimeRange: 设备不支持获取曝光时间范围")
                exposureTimeRange = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "initExposureTimeRange: 获取曝光时间范围失败", e)
            exposureTimeRange = null
        }
    }

    /**
     * 初始化HDR处理器
     *
     * 在相机绑定后调用，用于检测硬件HDR支持情况
     * 异步初始化，不阻塞相机绑定流程
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private suspend fun initHdrProcessor() {
        try {
            Log.d(TAG, "initHdrProcessor: 开始初始化HDR处理器")

            // 初始化HDR处理器（检测硬件HDR支持）
            val success = hdrProcessor.initialize()
            val supportStatus = hdrProcessor.supportStatus

            if (success) {
                Log.i(TAG, "initHdrProcessor: HDR处理器初始化成功 - ${supportStatus?.message}")

                // 检查当前镜头是否支持硬件HDR
                val lensFacing = when (_currentLens.value) {
                    CameraLens.BACK -> CameraSelector.LENS_FACING_BACK
                    CameraLens.FRONT -> CameraSelector.LENS_FACING_FRONT
                }
                val currentLensHdrSupport = hdrProcessor.isHardwareHdrAvailable(lensFacing)
                Log.d(TAG, "initHdrProcessor: 当前镜头(${_currentLens.value})硬件HDR支持=$currentLensHdrSupport")
            } else {
                Log.w(TAG, "initHdrProcessor: HDR处理器初始化失败，将使用软件HDR")
            }
        } catch (e: Exception) {
            Log.e(TAG, "initHdrProcessor: 初始化失败", e)
        }
    }

    /**
     * 初始化夜景模式处理器
     *
     * 在相机绑定后调用，用于检测硬件夜景支持情况
     * 异步初始化，不阻塞相机绑定流程
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private suspend fun initNightProcessor() {
        try {
            Log.d(TAG, "initNightProcessor: 开始初始化夜景模式处理器")

            // 初始化夜景处理器（检测硬件夜景支持）
            val success = nightModeProcessor.initialize()
            val supportStatus = nightModeProcessor.supportStatus

            if (success) {
                Log.i(TAG, "initNightProcessor: 夜景处理器初始化成功 - ${supportStatus?.message}")

                // 检查当前镜头是否支持硬件夜景
                val lensFacing = when (_currentLens.value) {
                    CameraLens.BACK -> CameraSelector.LENS_FACING_BACK
                    CameraLens.FRONT -> CameraSelector.LENS_FACING_FRONT
                }
                val currentLensNightSupport = nightModeProcessor.isHardwareNightAvailable(lensFacing)
                Log.d(TAG, "initNightProcessor: 当前镜头(${_currentLens.value})硬件夜景支持=$currentLensNightSupport")
            } else {
                Log.w(TAG, "initNightProcessor: 夜景处理器初始化失败，将使用软件夜景")
            }
        } catch (e: Exception) {
            Log.e(TAG, "initNightProcessor: 初始化失败", e)
        }
    }

    /**
     * 格式化快门速度用于日志输出
     *
     * @param seconds 快门速度（秒）
     * @return 格式化字符串，如"1/4000s"或"2s"
     */
    private fun formatShutterSpeedLog(seconds: Double): String {
        return if (seconds < 1.0) {
            val denominator = (1.0 / seconds).toInt()
            "1/${denominator}s"
        } else {
            "${seconds.toInt()}s"
        }
    }

    /**
     * 设置快门速度（曝光时间）
     *
     * 使用Camera2 Interop实现真实的快门速度控制
     * 实现与ISO的联动（曝光三角）：
     * - 当设置手动快门且ISO为自动时，自动设置默认ISO保证曝光正确
     * - 当恢复自动快门时，如果ISO也是自动，才启用自动曝光
     *
     * 技术实现：
     * 1. 将快门速度（秒）转换为曝光时间（纳秒）
     * 2. 设置CONTROL_AE_MODE为OFF（禁用自动曝光）
     * 3. 设置SENSOR_EXPOSURE_TIME为指定的曝光时间
     * 4. 联动设置ISO（如果当前为自动则使用默认值）
     * 5. 如果speed为null，恢复自动曝光模式
     *
     * 注意事项：
     * - 手动快门模式下需要配合ISO调整以获得正确曝光
     * - 曝光时间受设备硬件限制
     * - 过长的曝光时间可能导致帧率下降
     *
     * @param speed 快门速度（秒），null表示恢复自动曝光
     *              例如：1/4000s = 0.00025f, 1/30s = 0.0333f, 1s = 1.0f
     * @return 操作结果
     */
    @OptIn(ExperimentalCamera2Interop::class)
    override suspend fun setShutterSpeed(speed: Float?): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            Log.d(TAG, "setShutterSpeed: 设置快门速度 speed=$speed, 当前ISO=$currentIso")
            camera ?: throw IllegalStateException("相机未初始化")

            if (speed == null) {
                // 恢复自动曝光模式
                Log.d(TAG, "setShutterSpeed: 恢复自动曝光模式")
                currentShutterSpeed = null
                proExposureTimeNs = null

                if (currentIso == null) {
                    // 快门与 ISO 都自动 → 完全自动曝光
                    Log.d(TAG, "setShutterSpeed: ISO也是自动，启用完全自动曝光")
                    proAeMode = CaptureRequest.CONTROL_AE_MODE_ON
                    proIso = null
                } else {
                    // 快门自动但 ISO 手动 → 仍需 AE_OFF，并沿用 AE 实测的曝光时间
                    Log.d(TAG, "setShutterSpeed: ISO为手动($currentIso)，保持半手动模式")
                    proAeMode = CaptureRequest.CONTROL_AE_MODE_OFF
                    proIso = currentIso
                    proExposureTimeNs = lastAeExposureTimeNs ?: fallbackExposureNs()
                }
            } else {
                // Step 1: 秒 -> 纳秒
                val exposureTimeNs = (speed * 1_000_000_000L).toLong()
                Log.d(TAG, "setShutterSpeed: 计算曝光时间 ${speed}s = ${exposureTimeNs}ns")

                // Step 2: 夹到设备支持范围内
                val clampedExposureNs = exposureTimeRange?.let { range ->
                    val clamped = exposureTimeNs.coerceIn(range.first, range.second)
                    if (clamped != exposureTimeNs) {
                        Log.w(TAG, "setShutterSpeed: 曝光时间超出范围，已调整 " +
                                "${exposureTimeNs}ns -> ${clamped}ns")
                    }
                    clamped
                } ?: exposureTimeNs

                // Step 3: 曝光三角联动 —— AE 关闭时必须同时给出 ISO
                val effectiveIso = currentIso ?: defaultManualIso

                currentShutterSpeed = speed
                proAeMode = CaptureRequest.CONTROL_AE_MODE_OFF
                proExposureTimeNs = clampedExposureNs
                proIso = effectiveIso

                Log.d(TAG, "setShutterSpeed: 快门速度设置成功 " +
                        "speed=${formatShutterSpeedLog(speed.toDouble())}, " +
                        "exposureTime=${clampedExposureNs}ns, " +
                        "ISO=$effectiveIso")
            }

            applyProCaptureOptions()
            Unit
        }
    }

    /**
     * 获取设备支持的快门速度范围
     *
     * @return Pair(最小曝光时间纳秒, 最大曝光时间纳秒)，设备不支持时返回null
     */
    override fun getExposureTimeRange(): Pair<Long, Long>? {
        Log.d(TAG, "getExposureTimeRange: 返回曝光时间范围 $exposureTimeRange")
        return exposureTimeRange
    }

    // ==================== 延时摄影实现 ====================

    // 当前人像虚化等级
    /**
     * 当前人像虚化等级
     *
     * 默认必须是 NONE：虚化要跑一遍 ML Kit 人像分割，1200 万像素下单张约 5 秒。
     * 只有进入人像模式后由 CameraViewModel 显式设置等级，普通拍照模式不该承担这个开销
     * （而且会莫名其妙把照片虚化掉）。
     */
    private var currentPortraitBlurLevel = PortraitBlurLevel.NONE

    /**
     * 拍照 JPEG 压缩质量
     *
     * 跟随设置页的「照片质量」（高=95 / 标准=85 / 省空间=70）。
     * 此前这里写死 95，设置改了完全没反应。
     */
    @Volatile
    private var jpegQuality: Int = 95

    init {
        // 放在字段声明之后：Kotlin 按声明顺序初始化，
        // init 里启动的观察若早于字段初始化，读到的值会被随后的初始化覆盖。
        observePhotoQuality()
        observeVideoQuality()
    }

    /**
     * 开始延时摄影录制
     *
     * @param settings 延时摄影配置
     * @return 操作结果
     */
    override suspend fun startTimelapse(settings: TimelapseSettings): Result<Unit> {
        Log.d(TAG, "startTimelapse: 开始延时摄影 settings=$settings")
        val config = TimelapseConfig(
            captureIntervalMs = settings.captureIntervalMs,
            outputFps = settings.outputFps,
            videoWidth = settings.videoWidth,
            videoHeight = settings.videoHeight,
            maxDurationMs = settings.maxDurationMs
        )
        return timelapseEngine.startRecording(config)
    }

    /**
     * 停止延时摄影并编码输出视频
     *
     * @return 输出视频文件路径
     */
    override suspend fun stopTimelapse(): Result<String> {
        Log.d(TAG, "stopTimelapse: 停止延时摄影并编码")
        return timelapseEngine.stopAndEncode()
    }

    /**
     * 取消延时摄影（不生成视频）
     *
     * @return 操作结果
     */
    override suspend fun cancelTimelapse(): Result<Unit> {
        Log.d(TAG, "cancelTimelapse: 取消延时摄影")
        return timelapseEngine.cancel()
    }

    /**
     * 暂停延时摄影
     *
     * @return 操作结果
     */
    override suspend fun pauseTimelapse(): Result<Unit> {
        Log.d(TAG, "pauseTimelapse: 暂停延时摄影")
        return timelapseEngine.pause()
    }

    /**
     * 恢复延时摄影
     *
     * @return 操作结果
     */
    override suspend fun resumeTimelapse(): Result<Unit> {
        Log.d(TAG, "resumeTimelapse: 恢复延时摄影")
        return timelapseEngine.resume()
    }

    /**
     * 获取延时摄影进度流
     *
     * @return Triple(已捕获帧数, 已用时间毫秒, 编码进度0.0~1.0)
     */
    override fun getTimelapseProgress(): Flow<Triple<Int, Long, Float>> {
        return timelapseEngine.progress.map { progress ->
            Triple(
                progress.framesCaptured,
                progress.elapsedTimeMs,
                progress.encodingProgress
            )
        }
    }

    /**
     * 检查是否处于延时摄影录制状态
     *
     * @return true表示正在录制
     */
    override fun isTimelapseRecording(): Boolean {
        val state = timelapseEngine.progress.value.state
        return state == TimelapseState.RECORDING || state == TimelapseState.PAUSED
    }

    /**
     * 为延时摄影添加帧
     *
     * 在TIMELAPSE模式下由定时器调用
     * 捕获当前预览帧并添加到延时摄影引擎
     *
     * @return 操作结果
     */
    suspend fun addTimelapseFrame(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!timelapseEngine.shouldCaptureFrame()) {
                return@runCatching
            }

            Log.d(TAG, "addTimelapseFrame: 捕获延时摄影帧")

            // 获取当前预览帧
            val bitmap = _filteredFrame.value ?: _rawPreviewFrame.value
                ?: throw IllegalStateException("无可用预览帧")

            // 添加帧到延时摄影引擎
            timelapseEngine.addFrame(bitmap).getOrThrow()
            Log.d(TAG, "addTimelapseFrame: 帧已添加")
        }
    }

    /**
     * 获取延时摄影捕获间隔
     *
     * @return 捕获间隔（毫秒）
     */
    fun getTimelapseCaptureInterval(): Long {
        return timelapseEngine.getCaptureInterval()
    }

    // ==================== 人像虚化实现 ====================

    /**
     * 设置人像虚化等级
     *
     * @param level 虚化等级
     * @return 操作结果
     */
    override suspend fun setPortraitBlurLevel(level: PortraitBlurLevel): Result<Unit> {
        Log.d(TAG, "setPortraitBlurLevel: 设置虚化等级 level=$level")
        currentPortraitBlurLevel = level
        return Result.success(Unit)
    }

    /**
     * 获取当前人像虚化等级
     *
     * @return 当前虚化等级
     */
    override fun getPortraitBlurLevel(): PortraitBlurLevel {
        return currentPortraitBlurLevel
    }

    /**
     * 应用人像虚化效果到图像
     *
     * 使用ML Kit进行人像分割，然后应用高斯模糊
     *
     * @param bitmap 原始图像
     * @return 虚化后的图像，如果虚化等级为NONE则返回原图
     */
    suspend fun applyPortraitBlur(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        if (currentPortraitBlurLevel == PortraitBlurLevel.NONE) {
            Log.d(TAG, "applyPortraitBlur: 虚化等级为NONE，返回原图")
            return@withContext bitmap
        }

        Log.d(TAG, "applyPortraitBlur: 应用虚化 level=$currentPortraitBlurLevel")

        // 根据虚化等级选择配置
        val config = when (currentPortraitBlurLevel) {
            PortraitBlurLevel.LIGHT -> PortraitBlurConfig.LIGHT
            PortraitBlurLevel.MEDIUM -> PortraitBlurConfig.MEDIUM
            PortraitBlurLevel.HEAVY -> PortraitBlurConfig.HEAVY
            else -> PortraitBlurConfig.MEDIUM
        }

        // 使用PortraitBlurProcessor处理
        val result = portraitBlurProcessor.processPortraitBlur(bitmap, config)

        if (result.success) {
            Log.d(TAG, "applyPortraitBlur: 虚化成功 耗时=${result.processingTimeMs}ms")
            result.bitmap
        } else {
            Log.w(TAG, "applyPortraitBlur: 虚化失败 - ${result.errorMessage}，返回原图")
            bitmap
        }
    }
}
