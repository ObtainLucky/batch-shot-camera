/**
 * WatermarkStyleRenderTest.kt - 新增水印样式（工程表格/大字时间/极简胶囊）与四角位置的视觉验证
 *
 * 与 [WatermarkInfoPanelRenderTest] 同一套思路：水印是纯视觉产物，
 * 光编译通过说明不了"真的画上去了、画在该画的位置"。
 *
 * 这里用 Robolectric 的原生图形模式在 JVM 上真实执行 Canvas 绘制：
 * 1. 把渲染结果输出成 PNG，人可以直接看图确认版式
 * 2. 用像素差断言"水印确实改变了图像"，防止改坏成静默不绘制
 * 3. 用象素断言"位置设置真的把水印挪到了对应的角落"
 * 4. 用边距断言"任何样式 × 任何位置都不溢出画面"
 *
 * 运行：
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*WatermarkStyleRenderTest*"
 * ```
 * 输出目录：app/build/watermark-preview/
 *
 * @author qihao
 * @since 2.2.0
 */
package com.qihao.filtercamera.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.qihao.filter.watermark.WatermarkRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.TimeZone

/**
 * 新增水印样式渲染验证测试
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)                                   // 用真实图形栈，Canvas 才会真正出像素
class WatermarkStyleRenderTest {

    companion object {
        /** 固定时间，保证输出可复现（与信息面板测试同一时刻） */
        private const val FIXED_TIMESTAMP = 1789543979000L

        private const val IMAGE_WIDTH = 1080
        private const val IMAGE_HEIGHT = 1440

        /** 输出目录 */
        private const val OUTPUT_DIR = "build/watermark-preview"

        /** 水印像素占比下限：低于它说明根本没画上去 */
        private const val MIN_DIFF_RATIO = 0.001

        /** 水印像素占比上限：高于它说明底板尺寸算错、糊满整张图 */
        private const val MAX_DIFF_RATIO = 0.50

        /**
         * 画面最外圈安全边距（像素）
         *
         * 各样式的 padding 比例是 0.025（1080 宽即 27px，下限 16px）。
         * 取 15px 兼顾最小边距样式（极简/工程表格下限 16px），
         * 既不会误报，也足以发现溢出。
         */
        private const val SAFE_MARGIN_PX = 15
    }

    init {
        // 固定时区与语言，保证时间文本可复现
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        Locale.setDefault(Locale.CHINA)
    }

    // ==================== 工程表格 ====================

    /**
     * 工程表格：六个字段齐全
     *
     * 验收单样式：时间大字标题行 + 经纬度并排 + 地址/天气/备注
     */
    @Test
    fun renderGridWatermark_fullFields() {
        val output = renderAndSave(
            "grid-full.png",
            WatermarkRenderer.WatermarkType.GRID,
            fullFieldData()
        )
        assertWatermarkDrawn(output, "工程表格（六字段齐全）")
    }

    /** 工程表格：只有时间也要画出标题行（不能整张静默跳过） */
    @Test
    fun renderGridWatermark_onlyTime() {
        val data = WatermarkRenderer.WatermarkData(timestamp = FIXED_TIMESTAMP)
        val output = renderAndSave("grid-time-only.png", WatermarkRenderer.WatermarkType.GRID, data)
        assertWatermarkDrawn(output, "工程表格（仅时间）")
    }

    /** 工程表格：超长地址应折行/缩小而不是溢出画面 */
    @Test
    fun renderGridWatermark_longAddress() {
        val output = renderAndSave(
            "grid-long-address.png",
            WatermarkRenderer.WatermarkType.GRID,
            longAddressData()
        )
        assertWatermarkDrawn(output, "工程表格（超长地址）")
    }

    // ==================== 大字时间 ====================

    /** 大字时间：无底板堆叠样式，时间+地址+天气+备注 */
    @Test
    fun renderStampWatermark_fullFields() {
        val output = renderAndSave(
            "stamp-full.png",
            WatermarkRenderer.WatermarkType.STAMP,
            fullFieldData()
        )
        assertWatermarkDrawn(output, "大字时间（六字段齐全）")
    }

    // ==================== 极简胶囊 ====================

    /** 极简胶囊：单行 时间·天气·备注 */
    @Test
    fun renderMiniWatermark_fullFields() {
        val output = renderAndSave(
            "mini-full.png",
            WatermarkRenderer.WatermarkType.MINI,
            fullFieldData()
        )
        assertWatermarkDrawn(output, "极简胶囊（六字段齐全）")
    }

    /** 极简胶囊：超长备注省略号截断，胶囊不许超出画面 */
    @Test
    fun renderMiniWatermark_longRemark() {
        val data = fullFieldData().copy(
            customText = "段嘉轩 13297470239 重庆市两江新区大竹林街道金开大道西段互联网产业园二期五号楼三层东侧机房巡检联系人"
        )
        val output = renderAndSave("mini-long-remark.png", WatermarkRenderer.WatermarkType.MINI, data)
        assertWatermarkDrawn(output, "极简胶囊（超长备注）")
    }

    // ==================== 四角位置 ====================

    /**
     * 位置设置必须真的挪动水印
     *
     * 底部位置：下半象限有变化、上半象限纹丝不动；顶部位置反之。
     * 只断言垂直方向的分离，不断言水平方向——文字长度不该影响这条断言。
     */
    @Test
    fun position_movesWatermarkBetweenTopAndBottom() {
        val base = createFakePhoto()

        listOf(
            WatermarkRenderer.WatermarkType.GRID,
            WatermarkRenderer.WatermarkType.STAMP,
            WatermarkRenderer.WatermarkType.MINI,
            WatermarkRenderer.WatermarkType.INFO
        ).forEach { type ->
            val bottom = applyAndDiff(base, type, fullFieldData(position = WatermarkRenderer.WatermarkPosition.BOTTOM_LEFT))
            val top = applyAndDiff(base, type, fullFieldData(position = WatermarkRenderer.WatermarkPosition.TOP_LEFT))

            assertTrue(
                "$type 底部位置应在下半画面产生变化（bottomDiff=${bottom.bottomHalf}）",
                bottom.bottomHalf > 0
            )
            assertEquals(
                "$type 底部位置不应触碰上半画面",
                0.0, bottom.topHalf, 0.0
            )
            assertTrue(
                "$type 顶部位置应在上半画面产生变化（topDiff=${top.topHalf}）",
                top.topHalf > 0
            )
            assertEquals(
                "$type 顶部位置不应触碰下半画面",
                0.0, top.bottomHalf, 0.0
            )
        }
    }

    /** 任何样式 × 任何位置都不许溢出画面安全边距 */
    @Test
    fun position_allStyles_allCorners_neverOverflow() {
        listOf(
            WatermarkRenderer.WatermarkType.GRID,
            WatermarkRenderer.WatermarkType.STAMP,
            WatermarkRenderer.WatermarkType.MINI
        ).forEach { type ->
            WatermarkRenderer.WatermarkPosition.entries.forEach { position ->
                val data = if (type == WatermarkRenderer.WatermarkType.GRID) {
                    longAddressData(position = position)
                } else {
                    fullFieldData(position = position)
                }
                val output = renderAndSave(
                    "overflow-${type.name.lowercase()}-${position.id}.png",
                    type,
                    data
                )
                assertTrue(
                    "${type.name} @ ${position.id} 溢出了画面安全边距 " +
                        "(marginDiffRatio=${output.marginDiffRatio})",
                    output.marginDiffRatio == 0.0
                )
                assertTrue(
                    "${type.name} @ ${position.id} 渲染前后像素完全一致，水印没有画上去",
                    output.diffRatio > MIN_DIFF_RATIO
                )
            }
        }
    }

    // ==================== 位置解析 ====================

    /** fromId：合法 id 往返一致；非法/空值回落默认左下 */
    @Test
    fun watermarkPosition_fromId_parsesAndFallsBack() {
        WatermarkRenderer.WatermarkPosition.entries.forEach { position ->
            assertEquals(position, WatermarkRenderer.WatermarkPosition.fromId(position.id))
        }
        assertEquals(
            WatermarkRenderer.WatermarkPosition.DEFAULT,
            WatermarkRenderer.WatermarkPosition.fromId(null)
        )
        assertEquals(
            WatermarkRenderer.WatermarkPosition.DEFAULT,
            WatermarkRenderer.WatermarkPosition.fromId("")
        )
        assertEquals(
            WatermarkRenderer.WatermarkPosition.DEFAULT,
            WatermarkRenderer.WatermarkPosition.fromId("garbage_value")
        )
    }

    // ==================== 预览取景窗裁切（edgeInset） ====================

    /**
     * 全屏铺满的取景窗会上下裁掉位图一部分（横屏 2.2:1 对 4:3 位图约各裁 20%），
     * 水印必须锚定在可见区内：inset 对应的底部区域必须保持原样。
     */
    @Test
    fun previewEdgeInset_keepsWatermarkInVisibleArea() {
        listOf(
            WatermarkRenderer.WatermarkType.INFO,
            WatermarkRenderer.WatermarkType.GRID,
            WatermarkRenderer.WatermarkType.STAMP,
            WatermarkRenderer.WatermarkType.MINI,
            WatermarkRenderer.WatermarkType.TIMESTAMP
        ).forEach { type ->
            val base = createFakePhoto()
            val inset = 0.2f
            val data = fullFieldData().copy(edgeInsetBottom = inset, edgeInsetTop = inset)
            val watermarked = WatermarkRenderer.applyWatermark(base, type, data)

            val stripTop = (IMAGE_HEIGHT * (1f - inset)).toInt() + 1
            val stripHeight = IMAGE_HEIGHT - stripTop
            val bottomStripDiff = calculateRegionDiffRatio(
                base, watermarked, 0, stripTop, IMAGE_WIDTH, stripHeight
            )
            assertEquals(
                "${type.name} 底部被裁掉的区域（20%）不应有水印像素",
                0.0, bottomStripDiff, 0.0
            )
            val totalDiff = calculateDiffRatio(base, watermarked)
            assertTrue(
                "${type.name} 水印应完整画在可见区内（diffRatio=$totalDiff）",
                totalDiff > MIN_DIFF_RATIO
            )
        }
    }

    /** 成片路径：inset 为 0 时行为与历史版本完全一致（水印贴着底边） */
    @Test
    fun previewEdgeInset_zeroIsLegacyBehaviour() {
        listOf(
            WatermarkRenderer.WatermarkType.INFO,
            WatermarkRenderer.WatermarkType.TIMESTAMP
        ).forEach { type ->
            val output = renderAndSave("inset-zero-${type.name.lowercase()}.png", type, fullFieldData())
            assertWatermarkDrawn(output, "${type.name}（inset=0）")
        }
    }

    // ==================== 排版缓存 ====================

    /**
     * 排版缓存命中路径必须与排版路径输出完全一致：
     * 同一秒内连续渲染两次（第二次命中缓存），结果应逐像素相同。
     */
    @Test
    fun layoutCache_sameSecondRendersIdentically() {
        listOf(
            WatermarkRenderer.WatermarkType.INFO,
            WatermarkRenderer.WatermarkType.GRID,
            WatermarkRenderer.WatermarkType.STAMP,
            WatermarkRenderer.WatermarkType.MINI
        ).forEach { type ->
            val base = createFakePhoto()
            val data = fullFieldData()
            val first = WatermarkRenderer.applyWatermark(base, type, data)
            val second = WatermarkRenderer.applyWatermark(base, type, data)
            val diff = calculateDiffRatio(first, second)
            assertEquals(
                "${type.name} 同一秒内两次渲染应逐像素一致（排版缓存命中）",
                0.0, diff, 0.0
            )
        }
    }

    // ==================== 辅助 ====================

    /** 六字段齐全的测试数据 */
    private fun fullFieldData(
        position: WatermarkRenderer.WatermarkPosition = WatermarkRenderer.WatermarkPosition.BOTTOM_LEFT
    ) = WatermarkRenderer.WatermarkData(
        timestamp = FIXED_TIMESTAMP,
        customText = "段嘉轩 13297470239",
        longitude = 106.518536,
        latitude = 29.794027,
        address = "重庆市两江新区腾讯云计算数据中心",
        weather = "阴 21℃",
        position = position
    )

    /** 超长地址的测试数据（用于折行/缩小保护） */
    private fun longAddressData(
        position: WatermarkRenderer.WatermarkPosition = WatermarkRenderer.WatermarkPosition.BOTTOM_LEFT
    ) = WatermarkRenderer.WatermarkData(
        timestamp = FIXED_TIMESTAMP,
        customText = "段嘉轩 13297470239",
        longitude = 106.518536,
        latitude = 29.794027,
        address = "重庆市两江新区大竹林街道金开大道西段互联网产业园二期五号楼三层东侧会议室旁机房",
        weather = "阴 21℃",
        position = position
    )

    /** 渲染结果 */
    private data class RenderResult(
        val file: File,
        val diffRatio: Double,
        val marginDiffRatio: Double
    )

    /** 应用水印并保存 PNG（不与原图比对，只产出可视化文件） */
    private fun renderAndSave(
        fileName: String,
        type: WatermarkRenderer.WatermarkType,
        data: WatermarkRenderer.WatermarkData
    ): RenderResult {
        val photo = createFakePhoto()
        val before = photo.copy(Bitmap.Config.ARGB_8888, false)
        val watermarked = WatermarkRenderer.applyWatermark(photo, type, data)

        val outputDir = File(OUTPUT_DIR).apply { mkdirs() }
        val outputFile = File(outputDir, fileName)
        FileOutputStream(outputFile).use { fos ->
            watermarked.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        assertTrue("输出文件为空: ${outputFile.absolutePath}", outputFile.length() > 0)

        return RenderResult(
            file = outputFile,
            diffRatio = calculateDiffRatio(before, watermarked),
            marginDiffRatio = calculateMarginDiffRatio(before, watermarked, SAFE_MARGIN_PX)
        )
    }

    /** 应用水印并按上下半画面分别计算像素差异（不落盘） */
    private fun applyAndDiff(
        base: Bitmap,
        type: WatermarkRenderer.WatermarkType,
        data: WatermarkRenderer.WatermarkData
    ): HalfDiff {
        val watermarked = WatermarkRenderer.applyWatermark(base, type, data)
        return HalfDiff(
            topHalf = calculateRegionDiffRatio(base, watermarked, 0, 0, base.width, base.height / 2),
            bottomHalf = calculateRegionDiffRatio(
                base, watermarked, 0, base.height / 2, base.width, base.height / 2
            )
        )
    }

    /** 上下半画面的像素差异 */
    private data class HalfDiff(
        val topHalf: Double,
        val bottomHalf: Double
    )

    private fun assertWatermarkDrawn(result: RenderResult, label: String) {
        println("[水印验证] $label -> ${result.file.absolutePath}")
        println(
            "[水印验证] 有像素变化的占比 = " +
                String.format(Locale.US, "%.4f%%", result.diffRatio * 100)
        )
        assertTrue(
            "$label 渲染前后像素完全一致，水印没有真正画上去（diffRatio=${result.diffRatio}）",
            result.diffRatio > MIN_DIFF_RATIO
        )
        assertTrue(
            "$label 变化像素占比异常偏大（diffRatio=${result.diffRatio}），" +
                "多半是底板尺寸算错、铺满了整张图",
            result.diffRatio < MAX_DIFF_RATIO
        )
        assertTrue(
            "$label 溢出了画面安全边距（marginDiffRatio=${result.marginDiffRatio}）：" +
                "最外圈 ${SAFE_MARGIN_PX}px 本应保持原样，说明底板或文字算宽了",
            result.marginDiffRatio == 0.0
        )
    }

    /**
     * 计算 [x0,y0,x1,y1) 区域内的像素差异比例
     *
     * 用于"位置设置把水印挪到了对应角落"的断言。
     */
    private fun calculateRegionDiffRatio(
        a: Bitmap,
        b: Bitmap,
        x0: Int,
        y0: Int,
        regionWidth: Int,
        regionHeight: Int
    ): Double {
        require(a.width == b.width && a.height == b.height) { "尺寸不一致，无法比较" }
        val rowA = IntArray(regionWidth)
        val rowB = IntArray(regionWidth)
        var diff = 0L
        var total = 0L

        for (y in y0 until y0 + regionHeight) {
            a.getPixels(rowA, 0, regionWidth, x0, y, regionWidth, 1)
            b.getPixels(rowB, 0, regionWidth, x0, y, regionWidth, 1)
            for (x in 0 until regionWidth) {
                total++
                if (rowA[x] != rowB[x]) diff++
            }
        }
        return if (total == 0L) 0.0 else diff.toDouble() / total.toDouble()
    }

    /** 计算两张同尺寸 Bitmap 的像素差异比例 */
    private fun calculateDiffRatio(a: Bitmap, b: Bitmap): Double {
        require(a.width == b.width && a.height == b.height) { "尺寸不一致，无法比较" }
        val width = a.width
        val rowA = IntArray(width)
        val rowB = IntArray(width)
        var diff = 0L
        var total = 0L

        for (y in 0 until a.height) {
            a.getPixels(rowA, 0, width, 0, y, width, 1)
            b.getPixels(rowB, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if (rowA[x] != rowB[x]) diff++
            }
            total += width
        }
        return diff.toDouble() / total.toDouble()
    }

    /**
     * 计算画面最外圈 [margin] 像素内的差异比例
     *
     * 水印按 padding 留边绘制，最外圈应当完全不被触碰。
     * 一旦有像素变化，就说明底板或文字算宽了、画到了画面之外。
     */
    private fun calculateMarginDiffRatio(a: Bitmap, b: Bitmap, margin: Int): Double {
        val width = a.width
        val height = b.height
        val rowA = IntArray(width)
        val rowB = IntArray(width)
        var diff = 0L
        var total = 0L

        for (y in 0 until height) {
            val inVerticalMargin = y < margin || y >= height - margin
            a.getPixels(rowA, 0, width, 0, y, width, 1)
            b.getPixels(rowB, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val inHorizontalMargin = x < margin || x >= width - margin
                if (!inVerticalMargin && !inHorizontalMargin) continue
                total++
                if (rowA[x] != rowB[x]) diff++
            }
        }
        return if (total == 0L) 0.0 else diff.toDouble() / total.toDouble()
    }

    /**
     * 造一张"照片"：渐变背景 + 明暗色块，用来判断水印在明暗背景上的可读性
     */
    private fun createFakePhoto(): Bitmap {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 天空到地面的渐变
        val background = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, IMAGE_HEIGHT.toFloat(),
                Color.rgb(176, 206, 232),
                Color.rgb(74, 96, 78),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, IMAGE_WIDTH.toFloat(), IMAGE_HEIGHT.toFloat(), background)

        // 下半部分放一块亮色区域，检验无底板样式（大字时间）的可读性
        val bright = Paint().apply { color = Color.rgb(240, 238, 232) }
        canvas.drawRect(
            0f, IMAGE_HEIGHT * 0.72f, IMAGE_WIDTH * 0.85f, IMAGE_HEIGHT.toFloat(), bright
        )

        // 上半部分放一块深色区域，作为对照
        val dark = Paint().apply { color = Color.rgb(28, 30, 34) }
        canvas.drawRect(
            IMAGE_WIDTH * 0.6f, 0f, IMAGE_WIDTH.toFloat(), IMAGE_HEIGHT * 0.25f, dark
        )

        return bitmap
    }
}
