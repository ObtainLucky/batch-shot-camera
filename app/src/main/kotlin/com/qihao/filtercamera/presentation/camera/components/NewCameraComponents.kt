/**
 * NewCameraComponents.kt - 新版相机UI组件
 *
 * 基于新的设计稿实现的相机UI组件
 * 使用ResponsiveDimens响应式尺寸系统和CameraTheme统一颜色
 *
 * @author Jules
 * @since 3.0.0
 */
package com.qihao.filtercamera.presentation.camera.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qihao.filtercamera.domain.model.CameraMode
import com.qihao.filtercamera.domain.model.AspectRatio
import com.qihao.filtercamera.domain.model.FlashMode
import com.qihao.filtercamera.domain.model.HdrMode
import com.qihao.filtercamera.domain.model.TimerMode
import com.qihao.filtercamera.presentation.common.theme.CameraTheme
import com.qihao.filtercamera.presentation.common.theme.rememberResponsiveDimens

@Composable
fun NewCameraTopBar(
    flashMode: FlashMode,
    hdrMode: HdrMode,
    timerMode: TimerMode,
    aspectRatio: AspectRatio,
    isFilterActive: Boolean = false,                                    // 滤镜是否激活
    onFlashClick: () -> Unit,
    onHdrClick: () -> Unit,
    onTimerClick: () -> Unit,
    onFilterClick: () -> Unit,                                          // 滤镜按钮点击回调
    onAspectRatioClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = rememberResponsiveDimens()                               // 响应式尺寸系统

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.spacing.lg),                     // 修改：减小水平间距避免裁剪
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.spacing.md)) {  // 修改：减小间距避免溢出
            IconButton(onClick = onFlashClick) {
                Icon(
                    imageVector = when (flashMode) {
                        FlashMode.ON -> Icons.Default.FlashOn
                        FlashMode.OFF -> Icons.Default.FlashOff
                        FlashMode.AUTO -> Icons.Default.FlashAuto
                        FlashMode.TORCH -> Icons.Default.Highlight
                    },
                    contentDescription = "闪光灯",
                    tint = if (flashMode == FlashMode.OFF) CameraTheme.Colors.textPrimary else CameraTheme.Colors.primary
                )
            }
            Button(
                onClick = onHdrClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (hdrMode == HdrMode.ON) CameraTheme.Colors.primary else CameraTheme.ModeSelector.background,
                ),
                shape = RoundedCornerShape(dimens.radius.small / 2),      // 响应式圆角
                modifier = Modifier.border(
                    1.dp,
                    if (hdrMode == HdrMode.ON) CameraTheme.Colors.primary else CameraTheme.Colors.textPrimary,
                    RoundedCornerShape(dimens.radius.small / 2)
                )
            ) {
                Text(
                    "HDR",
                    color = if (hdrMode == HdrMode.ON) CameraTheme.Colors.onPrimary else CameraTheme.Colors.textPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = onTimerClick) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = "定时器",
                    tint = if (timerMode != TimerMode.OFF) CameraTheme.Colors.primary else CameraTheme.Colors.textPrimary
                )
            }
            // 滤镜按钮（魔法棒图标）
            IconButton(onClick = onFilterClick) {
                Icon(
                    Icons.Default.AutoAwesome,                           // 魔法棒图标
                    contentDescription = "滤镜",
                    tint = if (isFilterActive) CameraTheme.Colors.primary else CameraTheme.Colors.textPrimary
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.spacing.sm)) {  // 修改：减小右侧间距
            Button(
                onClick = onAspectRatioClick,
                colors = ButtonDefaults.buttonColors(containerColor = CameraTheme.Colors.controlBackgroundLight),
                shape = CircleShape
            ) {
                Text(
                    aspectRatio.displayName,
                    color = CameraTheme.Colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = CameraTheme.Colors.textPrimary
                )
            }
        }
    }
}

/**
 * NewCameraBottomControls - 底部控制区
 *
 * 包含相册缩略图、快门按钮、切换镜头按钮
 * 使用响应式尺寸系统确保多设备适配
 *
 * 优化点：
 * - 增加控件间距，避免误触
 * - 快门按钮居中对齐
 * - 相册和切换按钮等宽，保持对称
 *
 * @param galleryThumbnail 相册缩略图（可空）
 * @param onGalleryClick 点击相册回调
 * @param onShutterClick 点击快门回调
 * @param onSwitchCameraClick 点击切换镜头回调
 */
@Composable
fun NewCameraBottomControls(
    galleryThumbnail: Bitmap?,
    onGalleryClick: () -> Unit,
    onShutterClick: () -> Unit,
    onSwitchCameraClick: () -> Unit,
    /**
     * 布局修饰符
     *
     * 竖屏传 fillMaxWidth（缩略图/快门/切换均分整行，快门居中，与旧版一致）；
     * 横屏不传，让整组按内容宽度靠右 —— 否则它会和模式条抢满宽，
     * 结果就是快门被顶到屏幕正中、模式条被挤没了。
     */
    modifier: Modifier = Modifier
) {
    val dimens = rememberResponsiveDimens()                               // 响应式尺寸系统

    // 计算响应式尺寸
    val gallerySize = dimens.overlayButtonSize + dimens.spacing.md        // 相册缩略图尺寸（减小以增加间距）
    val shutterOuter = dimens.shutterButtonSize                           // 快门外圈
    val shutterInner = dimens.shutterInnerSize                            // 快门内圈
    val switchButtonSize = dimens.minTouchTarget                          // 切换按钮尺寸

    Row(
        modifier = modifier.padding(
            horizontal = dimens.spacing.md,
            vertical = dimens.spacing.sm
        ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GalleryThumbButton(galleryThumbnail, gallerySize, onGalleryClick)
        ShutterButton(shutterOuter, shutterInner, dimens.shutterStrokeWidth, onShutterClick)
        SwitchCameraButton(switchButtonSize, dimens.iconSizeLarge, onSwitchCameraClick)
    }
}

/**
 * 竖排的底部控件组（横屏用）
 *
 * 横屏可用高度只有 400dp 出头，把这一组压在底边会吃掉三分之一以上的画面，
 * 所以横屏时整组竖排贴到右边缘（取景框两侧本来就是黑边，正好放控件），
 * 快门落在右侧中间 —— 各家相机的横屏都是这个位置。
 */
@Composable
fun NewCameraBottomControlsVertical(
    galleryThumbnail: Bitmap?,
    onGalleryClick: () -> Unit,
    onShutterClick: () -> Unit,
    onSwitchCameraClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = rememberResponsiveDimens()
    val gallerySize = dimens.overlayButtonSize + dimens.spacing.md
    val shutterOuter = dimens.shutterButtonSize
    val shutterInner = dimens.shutterInnerSize
    val switchButtonSize = dimens.minTouchTarget

    Column(
        modifier = modifier.padding(vertical = dimens.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.spacing.md)
    ) {
        // 切换相机在上、快门居中、缩略图在下：单手横持时拇指最顺的位置是快门
        SwitchCameraButton(switchButtonSize, dimens.iconSizeLarge, onSwitchCameraClick)
        ShutterButton(shutterOuter, shutterInner, dimens.shutterStrokeWidth, onShutterClick)
        GalleryThumbButton(galleryThumbnail, gallerySize, onGalleryClick)
    }
}

/** 相册预览缩略图 */
@Composable
private fun GalleryThumbButton(
    galleryThumbnail: Bitmap?,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .border(
                1.5.dp,
                CameraTheme.Colors.textSecondary.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .background(CameraTheme.Colors.controlBackgroundLight)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (galleryThumbnail != null) {
            androidx.compose.foundation.Image(
                bitmap = galleryThumbnail.asImageBitmap(),
                contentDescription = "相册预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/** 快门按钮 */
@Composable
private fun ShutterButton(
    outer: androidx.compose.ui.unit.Dp,
    inner: androidx.compose.ui.unit.Dp,
    strokeWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(outer)
            .clip(CircleShape)
            .border(strokeWidth, CameraTheme.Shutter.outer, CircleShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.size(inner),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = CameraTheme.Shutter.inner)
        ) {}
    }
}

/** 切换镜头按钮 */
@Composable
private fun SwitchCameraButton(
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(CameraTheme.Colors.controlBackgroundLight)
    ) {
        Icon(
            Icons.Default.Cached,
            contentDescription = "切换摄像头",
            tint = CameraTheme.Colors.iconActive,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * CameraModeSelector - 相机模式选择器
 *
 * 使用LazyRow实现水平滚动的模式选择器，防止小屏设备溢出裁剪
 * 支持响应式尺寸和统一主题颜色
 *
 * 优化点：
 * - 增加与预览区的间距，避免遮挡
 * - 调整选中指示器位置
 * - 改善触摸反馈区域
 *
 * @param currentMode 当前选中的模式
 * @param onModeSelected 模式选中回调
 */
@Composable
fun CameraModeSelector(
    currentMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = rememberResponsiveDimens()                               // 响应式尺寸系统
    val modes = CameraMode.getAllModes()                                  // 获取所有模式
    val listState = rememberLazyListState()                               // LazyRow状态

    // 自动滚动到当前选中的模式
    val currentIndex = modes.indexOf(currentMode)
    LaunchedEffect(currentMode) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(
                index = currentIndex,
                scrollOffset = -100                                       // 使选中项居中偏左
            )
        }
    }

    LazyRow(
        state = listState,
        // 注意 modifier 必须放在最前面：横屏时调用方会传 weight，
        // 若内部无条件 fillMaxWidth，它会独占整行、把右侧的快门挤没
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = dimens.spacing.sm)                        // 垂直间距：与预览区保持距离
            .height(dimens.modeSelectorHeight + dimens.spacing.sm),       // 响应式高度（减小以避免遮挡）
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = dimens.spacing.xl)    // 增加水平内边距
    ) {
        items(modes) { mode ->
            val isSelected = mode == currentMode

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = dimens.spacing.md)              // 水平间距：模式项之间
                    .clickable { onModeSelected(mode) }
            ) {
                Text(
                    text = mode.displayName,
                    color = if (isSelected) CameraTheme.ModeSelector.active else CameraTheme.ModeSelector.inactive,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    letterSpacing = 1.5.sp                                // 减小字间距，更紧凑
                )
                if (isSelected) {
                    Spacer(modifier = Modifier.height(dimens.spacing.xs))
                    Box(
                        modifier = Modifier
                            .size(dimens.modeIndicatorHeight + 2.dp)
                            .background(CameraTheme.ModeSelector.indicator, CircleShape)
                    )
                }
            }
        }
    }
}


/**
 * 竖排的相机模式选择器（横屏用）
 *
 * 横屏时模式条横排会占掉顶部或底部一整条高度，把取景画面压扁；
 * 竖排贴到左边缘（正好落在取景框左侧的黑边上）就不占画面了。
 * 各家相机的横屏版式都是这个思路：控件收进两侧，画面保持完整。
 */
@Composable
fun CameraModeSelectorVertical(
    currentMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = rememberResponsiveDimens()
    val modes = CameraMode.getAllModes()

    Column(
        modifier = modifier.padding(vertical = dimens.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.spacing.sm)
    ) {
        modes.forEach { mode ->
            val isSelected = mode == currentMode
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = mode.displayName,
                    color = if (isSelected) {
                        CameraTheme.ModeSelector.active
                    } else {
                        CameraTheme.ModeSelector.inactive
                    },
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
                if (isSelected) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .size(dimens.modeIndicatorHeight + 2.dp)
                            .background(CameraTheme.ModeSelector.indicator, CircleShape)
                    )
                }
            }
        }
    }
}
