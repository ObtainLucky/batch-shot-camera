/**
 * CameraScreen.kt - 相机页面（小米风格重构版）
 *
 * 相机主界面，组合各个UI组件
 * 包含：相机预览、小米风格底部控制栏、滤镜选择器、实时滤镜预览
 *
 * 设计特点：
 * - 全屏相机预览
 * - 实时滤镜预览叠加层
 * - 左下角：相册缩略图（显示最新照片，点击打开系统相册）
 * - 中间：拍照按钮
 * - 右下角：切换镜头按钮
 * - 上方：滚动模式TAB（拍照、录像、人像、文档、专业）
 * - 预览区右侧：滤镜按钮（魔法棒图标）
 * - 拍照闪屏动画
 * - 人像模式：人脸检测框
 * - 文档模式：文档边界框
 * - 专业模式：参数控制面板
 *
 * @author qihao
 * @since 2.0.0
 */
package com.qihao.filtercamera.presentation.camera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.qihao.filtercamera.domain.model.BatchConfig
import com.qihao.filtercamera.domain.model.CameraEvent
import com.qihao.filtercamera.domain.model.CameraMode
import com.qihao.filtercamera.domain.model.FilterType
import com.qihao.filtercamera.domain.model.AspectRatio
import com.qihao.filtercamera.domain.model.HdrMode
import com.qihao.filtercamera.presentation.common.components.PhotoPreviewDialog
import com.qihao.filtercamera.presentation.common.components.RoundPickerDialog
import com.qihao.filtercamera.presentation.camera.components.NewCameraBottomControlsVertical
import com.qihao.filtercamera.presentation.camera.components.CameraModeSelectorVertical
import com.qihao.filtercamera.presentation.camera.components.CameraModeSelector
import com.qihao.filtercamera.presentation.camera.components.BatchBar
import com.qihao.filtercamera.presentation.camera.components.BatchSelectorSheet
import com.qihao.filtercamera.presentation.camera.components.CompactCameraTopBar
import com.qihao.filtercamera.presentation.camera.components.CompactHistogramView
import com.qihao.filtercamera.presentation.camera.components.DocumentBoundsOverlay
import com.qihao.filtercamera.presentation.camera.components.DocumentModeHint
import com.qihao.filtercamera.presentation.camera.components.DocumentScanModeSelector
import com.qihao.filtercamera.presentation.camera.components.DocumentScanModeUI
import com.qihao.filtercamera.presentation.camera.components.FaceDetectionOverlay
import com.qihao.filtercamera.presentation.camera.components.FaceTrackingStateIndicator
import com.qihao.filtercamera.presentation.camera.components.FocusIndicator
import com.qihao.filtercamera.presentation.camera.components.GridOverlay
import com.qihao.filtercamera.presentation.camera.components.ShotListSheet
import com.qihao.filtercamera.presentation.camera.components.NewCameraBottomControls
import com.qihao.filtercamera.presentation.camera.components.NightModeHint
import com.qihao.filtercamera.presentation.camera.components.NightProcessingIndicator
import com.qihao.filtercamera.presentation.camera.components.PermissionRequest
import com.qihao.filtercamera.presentation.camera.components.PortraitBlurProcessingIndicator
import com.qihao.filtercamera.presentation.camera.components.CompactPortraitControls
import com.qihao.filtercamera.presentation.camera.components.PortraitModeHint
import com.qihao.filtercamera.presentation.camera.components.ProModeControlPanel
import com.qihao.filtercamera.presentation.camera.components.TimelapseControlPanel
import com.qihao.filtercamera.presentation.camera.components.TimelapseEncodingIndicator
import com.qihao.filtercamera.presentation.camera.components.TimelapseModeHint
import com.qihao.filtercamera.presentation.camera.components.TimerCountdownOverlay
import com.qihao.filtercamera.presentation.camera.components.ZoomIndicator
import com.qihao.filtercamera.presentation.camera.components.ZoomSlider
import com.qihao.filtercamera.presentation.camera.components.iOSFilterSelector
import com.qihao.filtercamera.presentation.common.theme.CameraTheme
import com.qihao.filtercamera.presentation.common.theme.rememberResponsiveDimens
import com.qihao.filtercamera.data.processor.ScanState
import com.qihao.filtercamera.data.processor.MLKitScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "CameraScreen"  // 日志标签

/**
 * 相机页面
 *
 * @param viewModel 相机ViewModel
 * @param onNavigateToGallery 导航到相册回调（可选）
 * @param onNavigateToSettings 导航到设置页面回调（可选）
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel = hiltViewModel(),
    onNavigateToGallery: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // 权限请求 - 根据Android版本请求不同的存储权限
    val permissionsState = rememberMultiplePermissionsState(
        permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            // 信息水印需要定位来填经纬度与地址；拒绝也不影响拍照，只是水印少两行
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            // Android 13+ 使用细粒度媒体权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                // Android 12及以下使用传统存储权限
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    )

    // 处理事件（拍照完成、录像完成、错误等）
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is CameraEvent.PhotoCaptured -> {                         // 拍照成功
                    Log.d(TAG, "PhotoCaptured: ${event.filePath}")
                    Toast.makeText(context, "照片已保存", Toast.LENGTH_SHORT).show()
                }
                is CameraEvent.VideoRecorded -> {                         // 录像成功
                    Log.d(TAG, "VideoRecorded: ${event.filePath}")
                    Toast.makeText(context, "视频已保存", Toast.LENGTH_SHORT).show()
                }
                is CameraEvent.Error -> {                                 // 错误
                    Log.e(TAG, "Error: ${event.message}")
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is CameraEvent.Info -> {                                  // 提示（非错误，如批次创建成功）
                    Log.d(TAG, "Info: ${event.message}")
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is CameraEvent.CameraSwitched -> {                        // 摄像头切换
                    Log.d(TAG, "CameraSwitched")
                }
            }
        }
    }

    // 请求权限
    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    // ML Kit 文档扫描器 ActivityResultLauncher
    val mlKitScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        Log.d(TAG, "ML Kit Scanner result: resultCode=${result.resultCode}")
        viewModel.handleMLKitScanResult(result.resultCode, result.data)
    }

    // 观察 ML Kit 扫描状态
    val mlKitScanState by viewModel.mlKitScanState.collectAsState()

    // 协程作用域用于保存文件
    val scope = rememberCoroutineScope()

    // 处理 ML Kit 扫描结果
    LaunchedEffect(mlKitScanState) {
        when (val state = mlKitScanState) {
            is ScanState.Success -> {
                val result = state.result
                Log.d(TAG, "ML Kit 扫描成功: pages=${result.pageCount} hasPdf=${result.pdfUri != null}")

                // 保存扫描的图片到相册
                scope.launch {
                    try {
                        var savedCount = 0
                        for ((index, pageUri) in result.pages.withIndex()) {
                            val saved = saveScannedImageToGallery(context, pageUri, index + 1)
                            if (saved) savedCount++
                        }

                        // 保存 PDF（如果有）
                        var pdfSaved = false
                        if (result.pdfUri != null) {
                            pdfSaved = saveScannedPdfToDocuments(context, result.pdfUri)
                        }

                        withContext(Dispatchers.Main) {
                            val message = buildString {
                                append("扫描完成！")
                                if (savedCount > 0) append(" $savedCount 张图片已保存到相册")
                                if (pdfSaved) append("，PDF已保存到文档")
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "保存扫描结果失败", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                viewModel.resetMLKitScanState()                              // 重置状态
            }
            is ScanState.Error -> {
                Log.e(TAG, "ML Kit 扫描错误: ${state.message}")
                Toast.makeText(context, "扫描失败: ${state.message}", Toast.LENGTH_SHORT).show()
                viewModel.resetMLKitScanState()
            }
            is ScanState.Cancelled -> {
                Log.d(TAG, "ML Kit 扫描取消")
                viewModel.resetMLKitScanState()
            }
            else -> { /* Idle 或 Scanning 状态不处理 */ }
        }
    }

    // 主界面容器
    Box(modifier = Modifier.fillMaxSize()) {
        // 修复：使用firstOrNull避免NoSuchElementException
        val hasCameraPermission = permissionsState.permissions
            .firstOrNull { it.permission == Manifest.permission.CAMERA }
            ?.status?.isGranted ?: false

        if (hasCameraPermission) {
            CameraContent(                                                // 相机内容
                uiState = uiState,
                viewModel = viewModel,
                onNavigateToGallery = onNavigateToGallery,
                onNavigateToSettings = onNavigateToSettings,
                mlKitScannerLauncher = mlKitScannerLauncher               // ML Kit 扫描器
            )
        } else {
            PermissionRequest(                                            // 权限请求UI
                onRequestPermission = {
                    permissionsState.launchMultiplePermissionRequest()
                },
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * 相机内容（已授权后显示）
 *
 * 小米风格布局结构：
 * - 相机预览（全屏）
 * - 滤镜预览叠加层（当有滤镜时显示）
 * - 预览区右侧中间：滤镜按钮（魔法棒图标）
 * - 底部：模式TAB选择器（滚动）
 * - 底部：[相册] [快门] [切换镜头]
 * - 滤镜选择器（AnimatedVisibility从底部滑出）
 * - 拍照闪屏动画
 */
@Composable
private fun CameraContent(
    uiState: com.qihao.filtercamera.domain.model.CameraState,
    viewModel: CameraViewModel,
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    mlKitScannerLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current                                    // 获取Context用于打开相册
    val dimens = rememberResponsiveDimens()                               // 响应式尺寸系统

    // 订阅滤镜帧
    val filteredFrame by viewModel.filteredFrame.collectAsState()

    // 订阅滤镜缩略图
    val filterThumbnails by viewModel.filterThumbnails.collectAsState()

    // 订阅相册缩略图
    val galleryThumbnail by viewModel.galleryThumbnail.collectAsState()

    // 订阅直方图数据（专业模式）
    val histogramData by viewModel.histogramData.collectAsState()

    // 订阅取景网格与镜像预览状态
    val gridType by viewModel.gridType.collectAsState()
    val isPreviewMirrored by viewModel.isPreviewMirrored.collectAsState()

    // 作废上一张的可用性与确认框（删除照片不可撤销，必须二次确认）
    val canUndoLastShot by viewModel.canUndoLastShot.collectAsState()
    val isShotListVisible by viewModel.isShotListVisible.collectAsState()
    val shotListThumbs by viewModel.shotListThumbs.collectAsState()
    val isRoundPickerVisible by viewModel.isRoundPickerVisible.collectAsState()
    // 清单里点开的照片（uri + 文件名），非空即显示全屏预览
    var previewUri by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var showUndoConfirm by remember { mutableStateOf(false) }
    // 待确认重拍的名字下标（会删除原照片，所以先问一下）
    var pendingReshootIndex by remember { mutableStateOf<Int?>(null) }

    // 订阅批次状态（批次拍摄）
    val currentBatch by viewModel.currentBatch.collectAsState()
    val batches by viewModel.batches.collectAsState()
    val isBatchSheetVisible by viewModel.isBatchSheetVisible.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CameraTheme.Colors.background)                    // 使用主题背景色
    ) {
        // 屏幕方向：横竖屏的控件排布完全不同，先算出来
        val isLandscape = LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE

        // 横屏时屏幕左右两侧被"模式列 / 控件列"占住，顶部这两条要让开，
        // 否则工具栏的 ⚙️ 会和变焦叠在一起、批次条的「选择」会压在快门组上。
        // 让开之后，这两条正好只覆盖取景画面那一块，文字位置也就归位了。
        val topStartInset = if (isLandscape) 56.dp else dimens.spacing.lg
        val topEndInset = if (isLandscape) 112.dp else dimens.spacing.lg

        // 1. 相机预览容器 - 根据画幅比例调整大小
        //
        // 比例必须跟着屏幕方向换：手机横过来之后，同一个 4:3 画幅在界面上就是 4:3
        // （宽>高），而不是竖屏时的 3:4。原来写死竖屏比例 + fillMaxWidth，
        // 横屏下高度会算成屏幕宽度的 4/3 倍，直接把预览撑出屏幕、控件全被挤走。
        //
        // 这里不再用 fillMaxWidth，改成只用 aspectRatio：它会自动挑一个
        // "在约束内尽可能大且符合比例"的尺寸（宽放不下就改按高来算），
        // 竖屏横屏都成立。
        val targetRatio = uiState.advancedSettings.aspectRatio
        val portraitRatio: Float? = when (targetRatio) {
            AspectRatio.RATIO_1_1 -> 1f                                   // 1:1 两个方向相同
            AspectRatio.RATIO_3_2 -> 2f / 3f                              // 竖屏 宽:高 = 2:3
            AspectRatio.RATIO_4_3 -> 3f / 4f                              // 竖屏 宽:高 = 3:4
            AspectRatio.RATIO_16_9 -> 9f / 16f                            // 竖屏 宽:高 = 9:16
            AspectRatio.RATIO_FULL -> null                                // 全屏：不限制比例
        }
        val previewModifier = if (portraitRatio == null || isLandscape) {
            // 全屏铺满的两种情况：
            // 1. 画幅=全屏（portraitRatio == null）
            // 2. 横屏——4:3/16:9 的比例窗口横过来放，在 20:9 长屏上两侧各留 ~470px 黑边，
            //    取景面积观感比竖屏还小；铺满后画面占满全宽，两侧控制列直接叠在画面上。
            //    画幅设置仍决定成片比例，横屏预览显示的是成片中央的裁切（与系统相机一致）。
            Modifier.fillMaxSize()
        } else {
            // 竖屏：按画幅比例居中。竖屏时比例窗口本来就占满整屏宽，
            // 只有上下留边，不存在"面积被浪费"的观感问题。
            Modifier
                .aspectRatio(portraitRatio)
                .align(Alignment.Center)
        }

        // 预览区域Box（包含相机预览和滤镜叠加层）
        // onSizeChanged：把取景框实际尺寸上报给水印渲染链——
        // 全屏铺满时取景框与位图比例不一致，水印要锚定在裁切后的可见区内
        Box(
            modifier = previewModifier.onSizeChanged { size ->
                viewModel.onPreviewBoxSizeChanged(size.width, size.height)
            },
            contentAlignment = Alignment.Center
        ) {
            // 1.1 相机预览（前置 + 开启镜像时水平翻转）
            CameraPreview(
                viewModel = viewModel,
                isMirrored = isPreviewMirrored,
                modifier = Modifier.fillMaxSize()
            )

            // 1.15 取景网格线（设置页可切换类型）
            GridOverlay(
                gridType = gridType,
                modifier = Modifier.fillMaxSize()
            )

            // 1.2 滤镜预览叠加层（当有滤镜时显示处理后的帧）
            filteredFrame?.let { bitmap ->
                FilteredFrameOverlay(
                    bitmap = bitmap,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 1.3 触摸对焦检测层 - 限制在预览区域内，仅响应预览区域的触摸
            // 修复：将触摸检测移到预览Box内，解决画幅外可点击对焦的问题
            if (!uiState.isSettingsPanelExpanded && !uiState.isFilterSelectorVisible &&
                !uiState.isZoomSliderVisible && !uiState.isModeMenuVisible &&
                !(uiState.mode == CameraMode.PRO && uiState.isProPanelVisible)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                // 等待按下事件
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downOffset = down.position
                                // 预览被镜像时，用户看到的左右与实际画面相反，
                                // 对焦坐标必须一起翻转，否则点左边会对到右边
                                val rawX = downOffset.x / size.width
                                val normalizedX = if (isPreviewMirrored) 1f - rawX else rawX
                                val normalizedY = downOffset.y / size.height
                                // 按下时立即显示对焦框
                                viewModel.updateFocusPointPreview(normalizedX, normalizedY)

                                var lastOffset = downOffset
                                // 追踪拖动过程
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull()
                                    if (change != null && change.pressed) {
                                        // 手指移动时更新对焦框位置
                                        lastOffset = change.position
                                        val dragRawX = lastOffset.x / size.width
                                        val dragNormalizedX = if (isPreviewMirrored) 1f - dragRawX else dragRawX
                                        val dragNormalizedY = lastOffset.y / size.height
                                        viewModel.updateFocusPointPreview(dragNormalizedX, dragNormalizedY)
                                    }
                                } while (event.changes.any { it.pressed })

                                // 手指抬起时触发实际对焦
                                val releaseRawX = lastOffset.x / size.width
                                val releaseNormalizedX = if (isPreviewMirrored) 1f - releaseRawX else releaseRawX
                                val releaseNormalizedY = lastOffset.y / size.height
                                Log.d(TAG, "触摸对焦: 抬起位置=($releaseNormalizedX, $releaseNormalizedY)")
                                viewModel.onPreviewTouchFocus(releaseNormalizedX, releaseNormalizedY)
                            }
                        }
                )
            }
        }

        // 1.5 横屏两侧控制列（必须声明在预览层**之后**）
        //
        // Compose 的 Box 按声明顺序绘制，后声明的画在上层。
        // 预览全屏铺满后是不透明位图，若控制列声明在它之前，会被整层盖住——
        // 快门、切换相机、模式列全部"消失"。放在预览之后即压在画面上。
        //
        // 控件直接叠在画面上（与系统相机一致）；快门落在右侧中部，
        // 单手横持时拇指最顺的位置。
        if (isLandscape) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = dimens.spacing.xs)
            ) {
                CameraModeSelectorVertical(
                    currentMode = uiState.mode,
                    onModeSelected = viewModel::selectMode
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = dimens.spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(dimens.spacing.sm)
            ) {
                ZoomIndicator(
                    currentZoom = uiState.advancedSettings.zoomLevel,
                    isExpanded = uiState.isZoomSliderVisible,
                    onClick = viewModel::toggleZoomSlider
                )
                NewCameraBottomControlsVertical(
                    galleryThumbnail = galleryThumbnail,
                    onGalleryClick = onNavigateToGallery,
                    onShutterClick = {
                        if (CameraMode.isVideoMode(uiState.mode)) {
                            viewModel.toggleRecording()
                        } else {
                            viewModel.takePhoto()
                        }
                    },
                    onSwitchCameraClick = viewModel::switchCamera,
                    modifier = Modifier
                )
            }
        }

        // 2. 对焦指示器覆盖层 - 显示黄色对焦框 + 亮度调节滑块
        FocusIndicator(
            focusPoint = uiState.focusPoint,
            isFocusing = uiState.isFocusing,
            exposureCompensation = uiState.focusExposureCompensation, // 曝光补偿值
            showExposureSlider = uiState.focusPoint != null,         // 有对焦点时显示滑块
            onExposureChange = viewModel::setFocusExposureCompensation, // 曝光调节回调
            modifier = Modifier.fillMaxSize()
        )

        // 2.1 透明点击层 - 点击预览区域空白处关闭所有弹窗
        // 仅当有弹窗展开时才显示此层
        if (uiState.isSettingsPanelExpanded || uiState.isFilterSelectorVisible ||
            uiState.isZoomSliderVisible || uiState.isModeMenuVisible ||              // 变焦滑块/模式菜单展开时
            (uiState.mode == CameraMode.PRO && uiState.isProPanelVisible) ||
            (uiState.mode == CameraMode.PORTRAIT && uiState.isPortraitOverlayVisible)) { // 人像模式覆盖层可见时
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,                                    // 无点击涟漪效果
                        onClick = { viewModel.onPreviewTapped() }             // 关闭所有弹窗
                    )
            )
        }

        // 3. 精简版 TopBar - 整合功能到模式菜单，根据模式显示不同功能
        CompactCameraTopBar(
            cameraMode = uiState.mode,                                    // 新增：传递当前相机模式
            flashMode = uiState.advancedSettings.flashMode,
            hdrMode = uiState.advancedSettings.hdrMode,
            timerMode = uiState.timerMode,
            aspectRatio = uiState.advancedSettings.aspectRatio,
            isFilterActive = uiState.filterType != FilterType.NONE,       // 滤镜非NONE时激活
            isModeMenuVisible = uiState.isModeMenuVisible,
            onFlashClick = { viewModel.toggleFlashMode() },
            onModeMenuClick = { viewModel.toggleModeMenu() },
            onHdrClick = { viewModel.setHdrMode(if (uiState.advancedSettings.hdrMode == HdrMode.ON) HdrMode.OFF else HdrMode.ON) },
            onTimerClick = { viewModel.toggleTimerMode() },
            onAspectRatioClick = {
                // 使用AspectRatio.next()循环切换所有画幅比例(4:3→16:9→全屏→1:1→3:2→4:3)
                viewModel.setAspectRatio(AspectRatio.next(uiState.advancedSettings.aspectRatio))
            },
            onFilterClick = { viewModel.toggleFilterSelector() },       // 滤镜按钮点击
            onSettingsClick = onNavigateToSettings,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(
                    // 横屏可用高度小，顶部多留一点就会把取景画面压扁
                    top = if (isLandscape) dimens.spacing.xs else dimens.spacing.lg,
                    start = topStartInset,
                    end = topEndInset
                )
        )

        // 4. 批次条 - 位于 TopBar 下方，显示当前批次与"下一张"文件名
        //
        // 只在拍照模式显示：人像/文档/夜景/延时模式各自有一条居中提示条占着同一位置，
        // 同屏显示会互相压住。批次命名本身对所有走 takePhoto() 的模式都生效，
        // 这里收窄的只是入口的显示范围。
        if (uiState.mode == CameraMode.PHOTO) {
            BatchBar(
                current = currentBatch,
                onClick = viewModel::showBatchSheet,
                canUndoLastShot = canUndoLastShot,
                onUndoLastShot = { showUndoConfirm = true },
                onOpenShotList = { viewModel.showShotList(true) },
                onSelectGroup = viewModel::selectGroup,
                onOpenRoundPicker = { viewModel.showRoundPicker(true) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(
                        // 横屏只去掉多余的那一点间隙，仍需排在 TopBar 下方 ——
                        // 之前压成 spacing.xs 会直接和 TopBar 竖着叠在一起
                        top = if (isLandscape) dimens.topBarHeight else {
                            dimens.topBarHeight + dimens.spacing.sm        // 紧贴 TopBar 下方
                        },
                        start = topStartInset,
                        end = topEndInset
                    )
            )
        }

        // 4.05 拍摄清单（现场核对"还剩哪几个没拍"，点某项即从该项续拍）
        ShotListSheet(
            visible = isShotListVisible,
            batch = currentBatch,
            thumbUris = shotListThumbs,
            onJumpTo = { index ->
                // 已拍过的项=重拍：删除原照片不可恢复，先确认；未拍过的直接跳过去
                if (currentBatch?.isNameShot(index) == true) {
                    pendingReshootIndex = index
                } else {
                    viewModel.selectNameFromList(index)
                }
            },
            onPreview = { uri, title -> previewUri = uri to title },
            onDismiss = { viewModel.showShotList(false) }
        )

        // 轮次选择：轮次切换是拍摄过程中的动作，入口就在批次条那一行
        if (isRoundPickerVisible) {
            currentBatch?.let { batch ->
                RoundPickerDialog(
                    target = batch,
                    onSelect = { round -> viewModel.selectRound(round) },
                    onResetToFirst = viewModel::resetRoundToFirst,
                    onDismiss = { viewModel.showRoundPicker(false) }
                )
            }
        }

        // 点清单里的缩略图 -> 全屏核对这张是否拍错
        previewUri?.let { (uri, title) ->
            PhotoPreviewDialog(uri = uri, title = title, onDismiss = { previewUri = null })
        }

        // 4.1 批次切换底部弹窗
        BatchSelectorSheet(
            visible = isBatchSheetVisible,
            batches = batches,
            currentBatchId = currentBatch?.id,
            onSelect = viewModel::selectBatch,
            onClear = viewModel::clearBatch,
            onCreate = { form -> viewModel.createBatch(form) },
            onDismiss = viewModel::hideBatchSheet
        )

        // 4.15 重拍确认（会永久删除该项原来的照片）
        pendingReshootIndex?.let { index ->
            val names = currentBatch?.effectiveNameList ?: emptyList()
            val targetName = names.getOrNull(index)
            AlertDialog(
                onDismissRequest = { pendingReshootIndex = null },
                title = { Text("重拍这一项？") },
                text = {
                    Text(
                        "将删除「${targetName ?: ""}」原来那张照片，然后重新拍这一项。" +
                            "\n\n删除后无法恢复。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingReshootIndex = null
                        viewModel.selectNameFromList(index)
                    }) {
                        Text("删除并重拍")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingReshootIndex = null }) { Text("取消") }
                }
            )
        }

        // 4.2 作废上一张的二次确认（会永久删除刚拍的照片）
        if (showUndoConfirm) {
            AlertDialog(
                onDismissRequest = { showUndoConfirm = false },
                title = { Text("作废上一张？") },
                text = {
                    Text(
                        "将删除刚拍的这张照片，并把名字序号回退一位，" +
                            "下一张用同一个名字重拍。\n\n删除后无法恢复。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showUndoConfirm = false
                        viewModel.undoLastShot()
                    }) {
                        Text("作废并回退")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUndoConfirm = false }) { Text("取消") }
                }
            )
        }

        // 5. 人像模式：人脸检测框覆盖层
        if (uiState.mode == CameraMode.PORTRAIT && uiState.detectedFaces.isNotEmpty()) {
            FaceDetectionOverlay(
                faces = uiState.detectedFaces,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 6. 文档模式：文档边界框覆盖层
        if (uiState.mode == CameraMode.DOCUMENT) {
            DocumentBoundsOverlay(
                bounds = uiState.documentBounds,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 6.5. 定时拍照倒计时覆盖层（全屏显示大数字）
        TimerCountdownOverlay(
            isVisible = uiState.isCountingDown,
            countdownSeconds = uiState.countdownSeconds,
            onCancel = viewModel::cancelCountdown,
            modifier = Modifier.fillMaxSize()
        )


        // 8. 人像模式提示（顶部状态栏下方）
        if (uiState.mode == CameraMode.PORTRAIT) {
            PortraitModeHint(
                faceCount = uiState.detectedFaces.size,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = dimens.topBarHeight + dimens.spacing.xl) // 响应式：TopBar高度+间距
            )

            // 人像模式紧凑控制条（底部模式选择器上方）- 简洁设计，不遮挡预览
            CompactPortraitControls(
                beautyLevel = uiState.beautyLevel,
                blurLevel = uiState.portraitBlurLevel,
                onBeautyToggle = viewModel::toggleBeautyLevel,
                onBlurToggle = viewModel::togglePortraitBlurLevel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = dimens.modeSelectorHeight + dimens.bottomBarHeight + dimens.spacing.xl)
            )

            // 人脸追踪状态指示器（预览区右上角）
            FaceTrackingStateIndicator(
                trackingState = uiState.faceTrackingState,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = dimens.topBarHeight + dimens.spacing.xl, end = dimens.spacing.lg)
            )
        }

        // 8.5 人像虚化处理进度指示器（全屏遮罩，处理时显示）
        PortraitBlurProcessingIndicator(
            isProcessing = uiState.isPortraitBlurProcessing,              // 是否正在虚化处理
            progress = uiState.portraitBlurProgress,                      // 处理进度（0.0~1.0）
            modifier = Modifier.fillMaxSize()                             // 全屏覆盖
        )

        // 9. 文档模式提示（顶部状态栏下方）
        if (uiState.mode == CameraMode.DOCUMENT) {
            DocumentModeHint(
                isDetected = uiState.documentBounds != null,
                confidence = uiState.documentBounds?.confidence ?: 0f,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = dimens.spacing.lg)                     // 响应式间距
            )

            // 文档扫描模式切换按钮（预览区左侧中间）
            IconButton(
                onClick = viewModel::toggleDocumentScanModeSelector,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (uiState.isDocumentScanModeSelectorVisible)
                        CameraTheme.Colors.primary.copy(alpha = 0.8f)     // 激活时使用主题色
                    else
                        CameraTheme.Colors.controlBackground              // 未激活时使用控件背景色
                ),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = dimens.spacing.lg)
            ) {
                Icon(
                    imageVector = Icons.Filled.BarChart,
                    contentDescription = "切换扫描模式",
                    tint = CameraTheme.Colors.textPrimary                  // 使用主题文字色
                )
            }
        }

        // 10. 夜景模式提示（顶部状态栏下方）
        if (uiState.mode == CameraMode.NIGHT) {
            NightModeHint(
                isOptimizing = uiState.isCapturing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = dimens.spacing.lg)                     // 响应式间距
            )
        }

        // 10.5 夜景处理进度指示器（全屏遮罩，处理时显示）
        NightProcessingIndicator(
            isProcessing = uiState.isNightProcessing,                     // 是否正在夜景处理
            progress = uiState.nightProcessingProgress,                   // 处理进度（0.0~1.0）
            modifier = Modifier.fillMaxSize()                             // 全屏覆盖
        )

        // 10.6 延时摄影模式提示（顶部状态栏下方）
        if (uiState.mode == CameraMode.TIMELAPSE) {
            TimelapseModeHint(
                isRecording = uiState.isTimelapseRecording,
                framesCaptured = uiState.timelapseFramesCaptured,
                elapsedMs = uiState.timelapseElapsedMs,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = dimens.topBarHeight + dimens.spacing.xl) // 响应式：TopBar高度+间距
            )
        }

        // 10.7 延时摄影编码进度指示器（全屏遮罩，编码时显示）
        TimelapseEncodingIndicator(
            isEncoding = uiState.isTimelapseEncoding,                     // 是否正在编码
            progress = uiState.timelapseEncodingProgress,                 // 编码进度（0.0~1.0）
            modifier = Modifier.fillMaxSize()                             // 全屏覆盖
        )

        // 11. 实时直方图显示（专业模式右上角）
        AnimatedVisibility(
            visible = uiState.isHistogramVisible && uiState.mode == CameraMode.PRO,
            enter = fadeIn(animationSpec = tween(dimens.animation.fast)),
            exit = fadeOut(animationSpec = tween(dimens.animation.instant + 50)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = dimens.topBarHeight + dimens.spacing.xl, end = dimens.spacing.lg)
        ) {
            CompactHistogramView(
                histogramData = histogramData
            )
        }

        // 12. 直方图切换按钮（专业模式）
        if (uiState.mode == CameraMode.PRO) {
            IconButton(
                onClick = viewModel::toggleHistogram,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (uiState.isHistogramVisible)
                        CameraTheme.Colors.primary.copy(alpha = 0.8f)     // 激活时使用主题色
                    else
                        CameraTheme.Colors.controlBackground              // 未激活时使用控件背景色
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(
                        top = if (uiState.isHistogramVisible)
                            dimens.topBarHeight + dimens.histogramHeight + dimens.spacing.xl + dimens.spacing.md
                        else
                            dimens.topBarHeight + dimens.spacing.xl,
                        end = dimens.spacing.lg
                    )
            ) {
                Icon(
                    imageVector = Icons.Filled.BarChart,
                    contentDescription = "切换直方图",
                    tint = CameraTheme.Colors.textPrimary                  // 使用主题文字色
                )
            }
        }

        // 9. 底部控制区域（包含专业模式面板、滤镜选择器和控制栏）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()                                  // 导航栏安全区域
        ) {
            // 专业模式控制面板（从下往上弹出动画）
            AnimatedVisibility(
                visible = uiState.mode == CameraMode.PRO && uiState.isProPanelVisible,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(dimens.animation.normal, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(dimens.animation.fast)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(dimens.animation.fast + 100, easing = FastOutLinearInEasing)
                ) + fadeOut(animationSpec = tween(dimens.animation.instant + 50))
            ) {
                ProModeControlPanel(
                    settings = uiState.proSettings,
                    onIsoChanged = viewModel::setProIso,
                    onShutterChanged = viewModel::setProShutterSpeed,
                    onEvChanged = viewModel::setProExposureCompensation,
                    onWbChanged = viewModel::setProWhiteBalance,
                    onFocusModeChanged = viewModel::setProFocusMode,
                    onFocusDistanceChanged = viewModel::setProFocusDistance
                )
            }

            // 延时摄影控制面板（从下往上弹出动画）
            AnimatedVisibility(
                visible = uiState.mode == CameraMode.TIMELAPSE,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(dimens.animation.normal, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(dimens.animation.fast)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(dimens.animation.fast + 100, easing = FastOutLinearInEasing)
                ) + fadeOut(animationSpec = tween(dimens.animation.instant + 50))
            ) {
                TimelapseControlPanel(
                    isRecording = uiState.isTimelapseRecording,           // 是否正在录制
                    framesCaptured = uiState.timelapseFramesCaptured,     // 已捕获帧数
                    elapsedMs = uiState.timelapseElapsedMs,               // 已用时间（毫秒）
                    onStart = viewModel::startTimelapse,                  // 开始录制回调
                    onStop = viewModel::stopTimelapse,                    // 停止录制回调
                    onCancel = viewModel::cancelTimelapse                 // 取消录制回调
                )
            }

            // 文档扫描模式选择器（从下往上弹出动画）
            AnimatedVisibility(
                visible = uiState.mode == CameraMode.DOCUMENT && uiState.isDocumentScanModeSelectorVisible,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(dimens.animation.normal, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(dimens.animation.fast)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(dimens.animation.fast + 100, easing = FastOutLinearInEasing)
                ) + fadeOut(animationSpec = tween(dimens.animation.instant + 50))
            ) {
                DocumentScanModeSelector(
                    currentMode = DocumentScanModeUI.fromDataMode(uiState.documentScanMode),
                    onModeSelected = { uiMode ->
                        viewModel.setDocumentScanMode(uiMode.toDataMode())// 转换并设置
                        viewModel.showDocumentScanModeSelector(false)     // 选择后关闭面板
                    },
                    onAdvancedScanClick = {
                        // 启动 ML Kit 高级文档扫描
                        val activity = context as? Activity
                        if (activity != null) {
                            viewModel.showDocumentScanModeSelector(false) // 关闭选择器面板
                            viewModel.mlKitDocumentScanner.startScan(
                                activity = activity,
                                launcher = mlKitScannerLauncher
                            )
                        } else {
                            Log.w(TAG, "无法获取Activity实例")
                        }
                    }
                )
            }

            // 滤镜选择器（从下往上弹出动画）
            AnimatedVisibility(
                visible = uiState.isFilterSelectorVisible,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(dimens.animation.normal, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(dimens.animation.fast)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(dimens.animation.fast + 100, easing = FastOutLinearInEasing)
                ) + fadeOut(animationSpec = tween(dimens.animation.instant + 50))
            ) {
                iOSFilterSelector(
                    groups = viewModel.availableGroups,
                    selectedGroup = uiState.selectedFilterGroup,
                    filters = viewModel.getFiltersByGroup(uiState.selectedFilterGroup),
                    selectedFilter = uiState.filterType,
                    thumbnails = filterThumbnails,
                    filterIntensity = uiState.filterIntensity,
                    onGroupSelected = { group ->
                        viewModel.selectFilterGroup(group)
                        viewModel.generateFilterThumbnails()              // 切换分组时重新生成缩略图
                    },
                    onFilterSelected = viewModel::selectFilter,
                    onIntensityChanged = viewModel::setFilterIntensity
                )
            }

            // 变焦滑块（展开时显示，位于滤镜选择器下方）
            AnimatedVisibility(
                visible = uiState.isZoomSliderVisible,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(dimens.animation.normal, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(dimens.animation.fast)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(dimens.animation.fast + 100, easing = FastOutLinearInEasing)
                ) + fadeOut(animationSpec = tween(dimens.animation.instant + 50))
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    ZoomSlider(
                        currentZoom = uiState.advancedSettings.zoomLevel,
                        zoomRange = uiState.zoomRange,
                        onZoomChanged = viewModel::setZoomLevel
                    )
                }
            }

            // 变焦指示器
            //
            // 竖屏：单独一行，位于滤镜选择器下方、模式选择器上方。
            // 横屏：并入底部那一行（见下），否则它会浮在画面中间 ——
            // 横屏可用高度小，上方多一行就把它顶到取景框中间去了。
            if (!isLandscape) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = dimens.spacing.sm),
                    contentAlignment = Alignment.Center
                ) {
                    ZoomIndicator(
                        currentZoom = uiState.advancedSettings.zoomLevel,
                        isExpanded = uiState.isZoomSliderVisible,
                        onClick = viewModel::toggleZoomSlider
                    )
                }
            }

            // 模式选择器 + 底部控制栏
            //
            // 竖屏：竖向堆叠（模式条在快门上方的常规排布），此时取景框比屏幕矮，
            // 控件落在黑边上，不遮挡画面。
            //
            // 横屏：**必须排成一行**。横屏可用高度只有 400dp 出头，竖着堆叠要吃掉
            // 近一半屏幕，快门与模式条会被顶到画面中部 —— 各家相机在横屏下都是
            // "模式在左、快门在右、沿底边排一行"，这里跟它对齐。
            val bottomControls: @Composable (Modifier) -> Unit = { controlsModifier ->
                NewCameraBottomControls(
                    galleryThumbnail = galleryThumbnail,
                    // 打开 App 自己的相册页（网格浏览 / 搜索 / 删除 / 进编辑器）
                    //
                    // 这里原来发的是 Intent.ACTION_PICK —— 那是"挑一张图交给调用方"的选图器，
                    // 选完结果没人接收，所以点开只看到选图界面、看不到刚拍的照片，
                    // 完全不像正常相机"点缩略图看照片"的行为。
                    // 相册页本来就有，onNavigateToGallery 也一路传进来了，只是之前没接上。
                    onGalleryClick = onNavigateToGallery,
                    onShutterClick = {
                        if (CameraMode.isVideoMode(uiState.mode)) {
                            viewModel.toggleRecording()
                        } else {
                            viewModel.takePhoto()
                        }
                    },
                    onSwitchCameraClick = viewModel::switchCamera,
                    modifier = controlsModifier
                )
            }

            if (!isLandscape) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CameraModeSelector(
                        currentMode = uiState.mode,
                        onModeSelected = viewModel::selectMode
                    )
                    // 竖屏保持原样：整行铺满、三项均分、快门居中
                    bottomControls(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * 滤镜预览叠加层
 *
 * 显示应用滤镜后的Bitmap，覆盖在相机预览之上
 *
 * @param bitmap 滤镜处理后的Bitmap
 * @param modifier 修饰符
 */
@Composable
private fun FilteredFrameOverlay(
    bitmap: Bitmap,
    modifier: Modifier = Modifier
) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "滤镜预览",
        modifier = modifier,
        contentScale = ContentScale.Crop                                  // 裁剪填充，保持比例
    )
}

/**
 * 相机预览组件
 *
 * 使用CameraX PreviewView显示相机画面
 *
 * @param viewModel 相机ViewModel，用于绑定相机
 * @param modifier 修饰符
 */
@Composable
fun CameraPreview(
    viewModel: CameraViewModel,
    isMirrored: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER                 // 填充中心，保持比例
        }
    }

    // 镜像预览：只翻转显示，不动相机数据流
    LaunchedEffect(isMirrored) {
        previewView.scaleX = if (isMirrored) -1f else 1f
    }

    // 绑定相机到PreviewView
    LaunchedEffect(previewView) {
        Log.d(TAG, "CameraPreview: 开始绑定相机到PreviewView")
        viewModel.bindCamera(lifecycleOwner, previewView)
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

// ==================== ML Kit 扫描结果保存辅助函数 ====================

/**
 * 保存扫描的图片到相册
 *
 * 将 ML Kit 扫描结果的图片 Uri 复制到系统相册
 *
 * @param context 上下文
 * @param sourceUri ML Kit 返回的图片 Uri
 * @param pageIndex 页码（用于文件命名）
 * @return 是否保存成功
 */
private suspend fun saveScannedImageToGallery(
    context: android.content.Context,
    sourceUri: Uri,
    pageIndex: Int
): Boolean = withContext(Dispatchers.IO) {
    try {
        // 生成文件名
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "SCAN_${timestamp}_P${pageIndex}.jpg"

        // 使用 MediaStore API 保存到相册
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FilterCamera/Scans")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return@withContext false

        // 复制图片数据
        resolver.openInputStream(sourceUri)?.use { input ->
            resolver.openOutputStream(uri)?.use { output ->
                input.copyTo(output)
            }
        }

        // Android Q+ 标记完成
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        Log.d(TAG, "saveScannedImageToGallery: 图片保存成功 $fileName")
        true
    } catch (e: Exception) {
        Log.e(TAG, "saveScannedImageToGallery: 保存失败", e)
        false
    }
}

/**
 * 保存扫描的 PDF 到文档目录
 *
 * 将 ML Kit 扫描结果的 PDF Uri 复制到 Documents 目录
 *
 * @param context 上下文
 * @param sourceUri ML Kit 返回的 PDF Uri
 * @return 是否保存成功
 */
private suspend fun saveScannedPdfToDocuments(
    context: android.content.Context,
    sourceUri: Uri
): Boolean = withContext(Dispatchers.IO) {
    try {
        // 生成文件名
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "SCAN_${timestamp}.pdf"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android Q+ 使用 MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/FilterCamera/Scans")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext false

            // 复制 PDF 数据
            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output)
                }
            }

            // 标记完成
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            Log.d(TAG, "saveScannedPdfToDocuments: PDF 保存成功 (MediaStore) $fileName")
        } else {
            // Android Q 以下使用传统文件操作
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val scanDir = File(documentsDir, "FilterCamera/Scans")
            if (!scanDir.exists()) scanDir.mkdirs()

            val outputFile = File(scanDir, fileName)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "saveScannedPdfToDocuments: PDF 保存成功 (File) ${outputFile.absolutePath}")
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "saveScannedPdfToDocuments: 保存失败", e)
        false
    }
}
