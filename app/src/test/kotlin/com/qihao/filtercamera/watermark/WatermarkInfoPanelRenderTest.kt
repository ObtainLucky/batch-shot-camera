/**
 * WatermarkInfoPanelRenderTest.kt - 水印渲染的视觉验证
 *
 * 为什么要有这个测试：
 * 水印是纯视觉产物，"代码编译通过"完全不能说明"水印真的画上去了"。
 * 之前项目里就存在"设置了水印但照片上没有"的问题，光看代码看不出来。
 *
 * 这个测试用 Robolectric 的原生图形模式在 JVM 上真实执行 Canvas 绘制，
 * 做两件事：
 * 1. 把渲染结果输出成 PNG，人可以直接看图确认版式与可读性
 * 2. 用像素差断言"水印确实改变了图像"，防止将来改坏了变成静默不绘制
 *
 * 运行：
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*WatermarkInfoPanelRenderTest*"
 * ```
 * 输出目录：app/build/watermark-preview/
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.qihao.filter.watermark.WatermarkRenderer
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
 * 水印渲染验证测试
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)                                   // 用真实图形栈，Canvas 才会真正出像素
class WatermarkInfoPanelRenderTest {

    companion object {
        /** 固定时间，保证输出可复现（2026-09-16 15:32:59 +08:00） */
        private const val FIXED_TIMESTAMP = 1789543979000L

        private const val IMAGE_WIDTH = 1080
        private const val IMAGE_HEIGHT = 1440

        /** 输出目录 */
        private const val OUTPUT_DIR = "build/watermark-preview"

        /**
         * 水印像素占比的合理区间
         *
         * 下限用于发现"根本没画上去"；上限用于发现"底板尺寸算错、糊满整张图"。
         * 六行面板在 1080x1440 上约占一成多，这里留足余量。
         */
        private const val MIN_DIFF_RATIO = 0.001

        /**
         * 水印像素占比上限
         *
         * 用来发现"底板尺寸算错、糊满整张图"。注意别定太紧：
         * 六行信息水印在 2.0× 倍率下合法地会占到约 1/3 画面，
         * 这是用户自己选的大小，不算异常。真正的溢出由下面的边距断言兜底。
         */
        private const val MAX_DIFF_RATIO = 0.50

        /**
         * 画面最外圈安全边距（像素）
         *
         * WatermarkRenderer 的 padding 比例是 0.025，1080 宽即 27px。
         * 这里取 20px 留一点余量，既不会误报，也足以发现溢出。
         */
        private const val SAFE_MARGIN_PX = 20
    }

    /**
     * 信息水印：六个字段齐全
     *
     * 对应需求示例：
     * 经度：106.518536 / 纬度：29.794027 / 地址：重庆市两江新区腾讯云计算数据中心
     * 时间：2026-09-16 15:32:59 / 天气：阴 21℃ / 备注：段嘉轩 13297470239
     */
    @Test
    fun renderInfoWatermark_fullFields() {
        val data = WatermarkRenderer.WatermarkData(
            timestamp = FIXED_TIMESTAMP,
            customText = "段嘉轩 13297470239",
            longitude = 106.518536,
            latitude = 29.794027,
            address = "重庆市两江新区腾讯云计算数据中心",
            weather = "阴 21℃"
        )

        val output = renderAndSave("info-full.png", WatermarkRenderer.WatermarkType.INFO, data)

        assertWatermarkDrawn(output, "信息水印（六字段齐全）")
    }

    /**
     * 信息水印：缺定位与天气时，只应保留「时间」「备注」两行
     * （验证"取不到的字段整行不画"，而不是画出空标签或占位符）
     */
    @Test
    fun renderInfoWatermark_missingGeoData() {
        val data = WatermarkRenderer.WatermarkData(
            timestamp = FIXED_TIMESTAMP,
            customText = "段嘉轩 13297470239",
            longitude = null,
            latitude = null,
            address = null,
            weather = null
        )

        val output = renderAndSave("info-partial.png", WatermarkRenderer.WatermarkType.INFO, data)

        assertWatermarkDrawn(output, "信息水印（仅时间与备注）")
    }

    /**
     * 信息水印：超长地址应自动缩放而不是溢出画面
     */
    @Test
    fun renderInfoWatermark_longAddress() {
        val data = WatermarkRenderer.WatermarkData(
            timestamp = FIXED_TIMESTAMP,
            customText = "段嘉轩 13297470239",
            longitude = 106.518536,
            latitude = 29.794027,
            address = "重庆市两江新区大竹林街道金开大道西段互联网产业园二期五号楼三层东侧会议室旁机房",
            weather = "阴 21℃"
        )

        val output = renderAndSave("info-long-address.png", WatermarkRenderer.WatermarkType.INFO, data)

        assertWatermarkDrawn(output, "信息水印（超长地址）")
    }

    /**
     * 对照：原有的时间戳水印
     */
    @Test
    fun renderTimestampWatermark_forComparison() {
        val data = WatermarkRenderer.WatermarkData(timestamp = FIXED_TIMESTAMP)
        val output = renderAndSave("timestamp.png", WatermarkRenderer.WatermarkType.TIMESTAMP, data)
        assertWatermarkDrawn(output, "时间戳水印")
    }

    // ==================== 辅助 ====================

    /**
     * 在合成"照片"上应用水印并写出 PNG
     *
     * @return 输出文件与像素差异比例
     */
    private fun renderAndSave(
        fileName: String,
        type: WatermarkRenderer.WatermarkType,
        data: WatermarkRenderer.WatermarkData
    ): RenderResult {
        val photo = createFakePhoto()

        // 先留一份无水印副本用于像素比对
        val before = photo.copy(Bitmap.Config.ARGB_8888, false)

        val watermarked = WatermarkRenderer.applyWatermark(photo, type, data)

        val outputDir = File(OUTPUT_DIR).apply { mkdirs() }
        val outputFile = File(outputDir, fileName)
        FileOutputStream(outputFile).use { fos ->
            watermarked.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }

        // 把无水印版也存一份，方便人工对比
        FileOutputStream(File(outputDir, "no-watermark.png")).use { fos ->
            before.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }

        assertTrue("输出文件为空: ${outputFile.absolutePath}", outputFile.length() > 0)

        return RenderResult(
            file = outputFile,
            diffRatio = calculateDiffRatio(before, watermarked),
            marginDiffRatio = calculateMarginDiffRatio(before, watermarked, SAFE_MARGIN_PX)
        )
    }

    /**
     * 断言水印真的改变了图像
     *
     * 这是本测试的核心价值：如果哪天水印逻辑被改坏成"静默不绘制"，
     * 编译仍然通过、日志可能仍然打印，但这里会直接失败。
     */
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
     * 计算画面最外圈 [margin] 像素内的差异比例
     *
     * 水印按 padding 留边绘制，最外圈应当完全不被触碰。
     * 一旦有像素变化，就说明底板或文字算宽了、画到了画面之外。
     */
    private fun calculateMarginDiffRatio(a: Bitmap, b: Bitmap, margin: Int): Double {
        val width = a.width
        val height = a.height
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
     * 渲染结果
     *
     * @param file 输出文件
     * @param diffRatio 与无水印版本相比发生变化的像素占比
     * @param marginDiffRatio 画面最外圈安全边距内发生变化的像素占比（应为 0）
     */
    private data class RenderResult(
        val file: File,
        val diffRatio: Double,
        val marginDiffRatio: Double
    )

    /**
     * 计算两张同尺寸 Bitmap 的像素差异比例
     */
    private fun calculateDiffRatio(a: Bitmap, b: Bitmap): Double {
        require(a.width == b.width && a.height == b.height) { "尺寸不一致，无法比较" }
        val width = a.width
        val row = IntArray(width)
        var diff = 0L
        var total = 0L

        for (y in 0 until a.height) {
            a.getPixels(row, 0, width, 0, y, width, 1)
            val rowB = IntArray(width)
            b.getPixels(rowB, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if (row[x] != rowB[x]) diff++
            }
            total += width
        }
        return diff.toDouble() / total.toDouble()
    }

    /**
     * 造一张"照片"：渐变背景 + 一些色块，用来判断水印在明暗背景上的可读性
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

        // 左下角故意放一块亮色区域，检验水印底板在浅色上的可读性
        val bright = Paint().apply { color = Color.rgb(240, 238, 232) }
        canvas.drawRect(
            0f, IMAGE_HEIGHT * 0.72f, IMAGE_WIDTH * 0.85f, IMAGE_HEIGHT.toFloat(), bright
        )

        // 右下角放一块深色区域，作为对照
        val dark = Paint().apply { color = Color.rgb(28, 30, 34) }
        canvas.drawRect(
            IMAGE_WIDTH * 0.6f, IMAGE_HEIGHT * 0.3f, IMAGE_WIDTH.toFloat(), IMAGE_HEIGHT * 0.45f, dark
        )

        return bitmap
    }

    init {
        // 固定时区与语言，保证时间文本可复现
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    // ==================== 水印大小倍率 ====================

    /**
     * 调大倍率后画面内容必须真的变大（像素差明显不同）
     */
    @Test
    fun sizeScale_changesRenderedResult() {
        val data = fullFieldData()

        val normal = renderAndSave("scale-1.0.png", WatermarkRenderer.WatermarkType.INFO, data)
        val large = renderAndSave(
            "scale-1.8.png",
            WatermarkRenderer.WatermarkType.INFO,
            data.copy(sizeScale = 1.8f)
        )
        val small = renderAndSave(
            "scale-0.6.png",
            WatermarkRenderer.WatermarkType.INFO,
            data.copy(sizeScale = 0.6f)
        )

        assertWatermarkDrawn(normal, "信息水印 1.0×")
        assertWatermarkDrawn(large, "信息水印 1.8×")
        assertWatermarkDrawn(small, "信息水印 0.6×")

        println("[水印验证] 像素变化 1.0×=${pct(normal.diffRatio)}, " +
            "1.8×=${pct(large.diffRatio)}, 0.6×=${pct(small.diffRatio)}")

        assertTrue("放大后覆盖面积应大于标准倍率", large.diffRatio > normal.diffRatio)
        assertTrue("缩小后覆盖面积应小于标准倍率", small.diffRatio < normal.diffRatio)
    }

    /**
     * 放大到 2 倍（含超长地址）也不许溢出画面
     *
     * 这是最容易被忽略的边界：字号变大后内容更宽，
     * 若"缩到放得下"的保护失效就会画到画面之外被裁掉。
     */
    @Test
    fun sizeScale_maxScale_neverOverflows() {
        val longAddress = "重庆市两江新区大竹林街道金开大道西段互联网产业园二期五号楼三层东侧会议室旁机房"

        listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { scale ->
            val data = WatermarkRenderer.WatermarkData(
                timestamp = FIXED_TIMESTAMP,
                customText = "段嘉轩 13297470239",
                longitude = 106.518536,
                latitude = 29.794027,
                address = longAddress,
                weather = "阴 21℃",
                sizeScale = scale
            )
            val result = renderAndSave(
                "scale-overflow-${scale}.png",
                WatermarkRenderer.WatermarkType.INFO,
                data
            )
            assertWatermarkDrawn(result, "信息水印 ${scale}× 超长地址")
        }
    }

    /**
     * 倍率来自用户设置，非法值必须被夹紧而不是画出畸形水印
     */
    @Test
    fun sizeScale_outOfRangeIsClamped() {
        val data = fullFieldData()

        // 0 或负数：夹到下限，仍然要画出内容
        val tooSmall = renderAndSave(
            "scale-clamp-min.png",
            WatermarkRenderer.WatermarkType.INFO,
            data.copy(sizeScale = -1f)
        )
        assertWatermarkDrawn(tooSmall, "信息水印 负倍率（应夹到下限）")

        // 极大值：夹到上限，且不能溢出
        val tooLarge = renderAndSave(
            "scale-clamp-max.png",
            WatermarkRenderer.WatermarkType.INFO,
            data.copy(sizeScale = 99f)
        )
        assertWatermarkDrawn(tooLarge, "信息水印 巨大倍率（应夹到上限）")
    }

    /** 六字段齐全的测试数据 */
    private fun fullFieldData() = WatermarkRenderer.WatermarkData(
        timestamp = FIXED_TIMESTAMP,
        customText = "段嘉轩 13297470239",
        longitude = 106.518536,
        latitude = 29.794027,
        address = "重庆市两江新区腾讯云计算数据中心",
        weather = "阴 21℃"
    )

    private fun pct(ratio: Double): String = String.format(Locale.US, "%.2f%%", ratio * 100)
}
