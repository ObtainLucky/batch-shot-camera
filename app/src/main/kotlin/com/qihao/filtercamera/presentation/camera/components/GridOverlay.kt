/**
 * GridOverlay.kt - 取景网格线
 *
 * 按设置里的网格类型在预览上画辅助线：
 * - 九宫格：三等分
 * - 黄金分割：0.382 / 0.618
 * - 方形：四等分
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.presentation.camera.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.qihao.filtercamera.domain.repository.GridType

/** 网格线颜色 */
private val GRID_LINE_COLOR = Color(0x59FFFFFF)                            // 35% 白，避免喧宾夺主

/** 网格线粗细（像素） */
private const val GRID_LINE_WIDTH = 1.5f

/**
 * 取景网格线覆盖层
 *
 * @param gridType 网格类型，NONE 时不绘制任何内容
 * @param modifier 修饰符（应覆盖预览区域）
 */
@Composable
fun GridOverlay(
    gridType: GridType,
    modifier: Modifier = Modifier
) {
    if (gridType == GridType.NONE) return

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val stroke = Stroke(width = GRID_LINE_WIDTH)

        // 竖线位置（比例）
        val verticalRatios: List<Float> = when (gridType) {
            GridType.GOLDEN_RATIO -> listOf(0.382f, 0.618f)
            GridType.SQUARE -> listOf(0.25f, 0.5f, 0.75f)
            else -> listOf(1f / 3f, 2f / 3f)                              // 九宫格
        }

        // 横线位置（比例）
        val horizontalRatios: List<Float> = when (gridType) {
            GridType.GOLDEN_RATIO -> listOf(0.382f, 0.618f)
            GridType.SQUARE -> listOf(0.25f, 0.5f, 0.75f)
            else -> listOf(1f / 3f, 2f / 3f)
        }

        verticalRatios.forEach { ratio ->
            val x = width * ratio
            drawLine(
                color = GRID_LINE_COLOR,
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = stroke.width
            )
        }

        horizontalRatios.forEach { ratio ->
            val y = height * ratio
            drawLine(
                color = GRID_LINE_COLOR,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = stroke.width
            )
        }
    }
}
