/**
 * WatermarkRenderer.kt - 水印渲染器
 *
 * 使用Canvas在Bitmap上绘制各种水印效果
 * 支持4种水印类型：时间戳、日期、设备信息、自定义
 *
 * 设计参考：
 * - 数码相机时间戳水印（橙色纯文字）
 * - 徕卡风格设备水印
 *
 * @author qihao
 * @since 2.0.0
 */
package com.qihao.filter.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 水印渲染器
 *
 * 提供静态方法在Bitmap上绘制各种水印
 */
object WatermarkRenderer {

    private const val TAG = "WatermarkRenderer"                               // 日志标签

    // 水印样式常量
    private const val WATERMARK_PADDING_RATIO = 0.025f                        // 水印边距比例
    private const val WATERMARK_TEXT_SIZE_RATIO = 0.028f                      // 主文字大小比例
    private const val WATERMARK_SUBTITLE_SIZE_RATIO = 0.020f                  // 副文字大小比例

    // 信息水印（多行面板）样式常量
    private const val INFO_TEXT_SIZE_RATIO = 0.024f                           // 信息水印文字大小比例
    private const val INFO_MIN_TEXT_SIZE = 16f                                // 信息水印文字下限（避免长地址缩得看不清）
    private const val INFO_LINE_SPACING = 1.45f                               // 信息水印行高倍数
    private const val INFO_PANEL_PADDING_RATIO = 0.9f                         // 底板内边距（相对字号）
    private const val INFO_PANEL_ALPHA = 150                                  // 信息水印底板不透明度（0~255）
    private const val MAX_SHRINK_ITERATIONS = 8                               // 字号收缩最大迭代次数（防死循环）

    /** 信息水印单项最多折几行（地址这类长文本够用，又不至于把面板撑太高） */
    private const val INFO_MAX_VALUE_LINES = 3

    /** 折行后的续行缩进（与标签等宽，视觉上仍属同一项） */
    private const val INFO_CONTINUATION_INDENT = "      "

    /** 水印大小倍率的默认值 */
    const val DEFAULT_SIZE_SCALE = 1.0f

    /** 允许的大小倍率范围（超出会被夹紧） */
    const val MIN_SIZE_SCALE = 0.5f
    const val MAX_SIZE_SCALE = 2.0f

    /**
     * 把倍率夹到允许范围
     *
     * 倍率直接来自用户设置，这里再兜一次底：过小会看不清，过大则可能把水印撑满画面。
     */
    private fun clampScale(scale: Float): Float =
        if (scale.isNaN()) DEFAULT_SIZE_SCALE else scale.coerceIn(MIN_SIZE_SCALE, MAX_SIZE_SCALE)

    // 数码相机水印颜色（橙黄色）
    private val DIGITAL_CAMERA_COLOR = Color.rgb(255, 165, 0)                 // 橙色

    /**
     * 水印类型枚举
     */
    enum class WatermarkType {
        TIMESTAMP,        // 时间戳（日期+时间）- 数码相机风格
        DATE,             // 仅日期 - 数码相机风格
        DEVICE,           // 设备信息（类似徕卡水印）
        CUSTOM,           // 自定义文字
        INFO              // 信息水印（经纬度/地址/时间/天气/备注 多行面板）
    }

    /**
     * 水印数据类
     *
     * 用于传递水印所需的各种信息
     *
     * 注意：经纬度、地址、天气、备注均可能取不到（无定位权限、无网络、逆地理编码失败等），
     * 取不到时对应行自动不绘制，而不是画一个空标签或占位符。
     */
    data class WatermarkData(
        val timestamp: Long = System.currentTimeMillis(),                     // 时间戳
        val customText: String = "",                                          // 自定义文字 / 备注
        val deviceModel: String = Build.MODEL,                                // 设备型号
        val deviceBrand: String = Build.BRAND,                                // 设备品牌
        val longitude: Double? = null,                                        // 经度
        val latitude: Double? = null,                                         // 纬度
        val address: String? = null,                                          // 逆地理编码得到的地址
        val weather: String? = null,                                          // 天气描述，如 "阴 21℃"
        val includeTimestamp: Boolean = true,                                 // 是否绘制「时间」行（其余行靠字段为空来省略）
        val sizeScale: Float = DEFAULT_SIZE_SCALE                             // 水印大小倍率（1.0 为基准）
    )

    /**
     * 应用水印到Bitmap
     *
     * @param sourceBitmap 源图片
     * @param watermarkType 水印类型
     * @param data 水印数据
     * @return 带水印的Bitmap（新创建的，不修改原图）
     */
    fun applyWatermark(
        sourceBitmap: Bitmap,
        watermarkType: WatermarkType,
        data: WatermarkData = WatermarkData()
    ): Bitmap {
        // 创建可编辑的副本
        val resultBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        // 根据水印类型绘制
        when (watermarkType) {
            WatermarkType.TIMESTAMP -> drawDigitalCameraTimestamp(canvas, resultBitmap, data)
            WatermarkType.DATE -> drawDigitalCameraDate(canvas, resultBitmap, data)
            WatermarkType.DEVICE -> drawDevice(canvas, resultBitmap, data)
            WatermarkType.CUSTOM -> drawCustom(canvas, resultBitmap, data)
            WatermarkType.INFO -> drawInfoPanel(canvas, resultBitmap, data)
        }

        return resultBitmap
    }

    /**
     * 绘制数码相机风格时间戳水印
     *
     * 格式：2026·01·16 14:30:25
     * 橙色纯文字，无边框背景
     */
    private fun drawDigitalCameraTimestamp(canvas: Canvas, bitmap: Bitmap, data: WatermarkData) {
        // 格式：2026·01·16 14:30:25
        val dateFormat = SimpleDateFormat("yyyy·MM·dd HH:mm:ss", Locale.getDefault())
        val text = dateFormat.format(Date(data.timestamp))
        drawDigitalCameraText(canvas, bitmap, text, data.sizeScale)
    }

    /**
     * 绘制数码相机风格日期水印
     *
     * 格式：2026·01·16
     * 橙色纯文字，无边框背景
     */
    private fun drawDigitalCameraDate(canvas: Canvas, bitmap: Bitmap, data: WatermarkData) {
        // 格式：2026·01·16
        val dateFormat = SimpleDateFormat("yyyy·MM·dd", Locale.getDefault())
        val text = dateFormat.format(Date(data.timestamp))
        drawDigitalCameraText(canvas, bitmap, text, data.sizeScale)
    }

    /**
     * 绘制数码相机风格文字（橙色纯文字，无背景）
     *
     * @param canvas 画布
     * @param bitmap Bitmap（用于计算尺寸）
     * @param text 要绘制的文字
     */
    private fun drawDigitalCameraText(canvas: Canvas, bitmap: Bitmap, text: String, scale: Float) {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val padding = (width * WATERMARK_PADDING_RATIO).coerceAtLeast(20f)
        val textSize = (width * WATERMARK_TEXT_SIZE_RATIO).coerceAtLeast(32f) *
            clampScale(scale)

        // 数码相机风格画笔：橙色文字 + 黑色描边阴影
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIGITAL_CAMERA_COLOR                                      // 橙色
            this.textSize = textSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)     // 等宽字体，更像数码相机
            letterSpacing = 0.05f                                             // 轻微字间距
            setShadowLayer(3f, 2f, 2f, Color.argb(200, 0, 0, 0))              // 黑色阴影增强可读性
        }

        // 计算文字位置（右下角）
        val textWidth = textPaint.measureText(text)
        val x = width - padding - textWidth
        val y = height - padding

        // 绘制文字
        canvas.drawText(text, x, y, textPaint)
    }

    /**
     * 绘制设备信息水印（徕卡风格）
     *
     * 格式：
     * Shot on Xiaomi 14 Pro
     * LEICA VARIO-SUMMILUX 1:1.4-3.2/14-75 ASPH.
     */
    private fun drawDevice(canvas: Canvas, bitmap: Bitmap, data: WatermarkData) {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val padding = (width * WATERMARK_PADDING_RATIO).coerceAtLeast(20f)
        val scale = clampScale(data.sizeScale)
        val textSize = (width * 0.035f).coerceAtLeast(36f) * scale            // 主标题大字号
        val subtitleSize = (width * 0.020f).coerceAtLeast(20f) * scale        // 副标题原有尺寸

        // 主标题画笔（白色 + 黑色阴影）
        val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(6f, 3f, 3f, Color.argb(220, 0, 0, 0))              // 黑色阴影立体效果
        }

        // 副标题画笔（白色 + 黑色阴影）
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = subtitleSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(5f, 2f, 2f, Color.argb(200, 0, 0, 0))              // 黑色阴影立体效果
        }

        // 获取友好的设备名称
        val deviceName = getDeviceMarketingName(data.deviceBrand, data.deviceModel)

        // 主文字：Shot on Xiaomi 14 Pro
        val mainText = "Shot on $deviceName"

        // 副文字：模拟镜头信息
        val subText = getLensDescription(data.deviceBrand)

        // 计算位置（右下角，右对齐）
        val mainTextWidth = mainPaint.measureText(mainText)
        val subTextWidth = subPaint.measureText(subText)

        // 主文字右对齐
        val mainX = width - padding - mainTextWidth
        // 副文字右对齐
        val subX = width - padding - subTextWidth

        val y = height - padding - subtitleSize - 12f

        // 绘制文字
        canvas.drawText(mainText, mainX, y, mainPaint)
        canvas.drawText(subText, subX, y + textSize + 6f, subPaint)
    }

    /**
     * 绘制自定义文字水印
     *
     * 白色文字，带阴影
     */
    private fun drawCustom(canvas: Canvas, bitmap: Bitmap, data: WatermarkData) {
        val text = data.customText.ifEmpty { "FilterCamera" }

        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val padding = (width * WATERMARK_PADDING_RATIO).coerceAtLeast(20f)
        val textSize = (width * WATERMARK_TEXT_SIZE_RATIO).coerceAtLeast(28f) *
            clampScale(data.sizeScale)

        // 白色文字画笔
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(4f, 2f, 2f, Color.argb(200, 0, 0, 0))
        }

        // 计算文字位置（右下角）
        val textWidth = textPaint.measureText(text)
        val x = width - padding - textWidth
        val y = height - padding

        // 绘制文字
        canvas.drawText(text, x, y, textPaint)
    }

    /**
     * 绘制信息水印（多行信息面板）
     *
     * 输出形如：
     * ```
     * 经度：106.518536
     * 纬度：29.794027
     * 地址：重庆市两江新区腾讯云计算数据中心
     * 时间：2026-09-16 15:32:59
     * 天气：阴 21℃
     * 备注：段嘉轩 13297470239
     * ```
     *
     * 规则：
     * - 取不到的字段整行不画（不画空标签、不画占位符）
     * - 长地址会自动整体缩小字号，保证不超出画面
     * - 左下角绘制半透明底板保证浅色照片上也能看清
     */
    private fun drawInfoPanel(canvas: Canvas, bitmap: Bitmap, data: WatermarkData) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        // 按固定顺序组装「标签 -> 值」，值为空的整行跳过
        val entries = buildList {
            data.longitude?.let { add("经度" to formatCoordinate(it)) }
            data.latitude?.let { add("纬度" to formatCoordinate(it)) }
            data.address?.takeIf { it.isNotBlank() }?.let { add("地址" to it.trim()) }
            if (data.includeTimestamp) {
                add("时间" to dateFormat.format(Date(data.timestamp)))
            }
            data.weather?.takeIf { it.isNotBlank() }?.let { add("天气" to it.trim()) }
            data.customText.takeIf { it.isNotBlank() }?.let { add("备注" to it.trim()) }
        }
        if (entries.isEmpty()) return

        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val padding = (width * WATERMARK_PADDING_RATIO).coerceAtLeast(16f)

        // 底板可用的横向空间
        val available = width - padding * 2
        // 倍率作用于基准字号；随后的"缩到放得下"逻辑仍会生效，
        // 所以放大后遇到长地址也不会溢出画面，只会被自动缩回来。
        val baseTextSize = (width * INFO_TEXT_SIZE_RATIO).coerceAtLeast(22f) *
            clampScale(data.sizeScale)
        var textSize = baseTextSize

        // 标签与值同字号，便于基线对齐
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIGITAL_CAMERA_COLOR
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(3f, 1.5f, 1.5f, Color.argb(200, 0, 0, 0))
        }
        val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(3f, 1.5f, 1.5f, Color.argb(200, 0, 0, 0))
        }

        // 缩小字号直到「最长行 + 两侧内边距」放得下
        // 注意内边距与字号成正比，所以不能只比较文字宽度，否则底板会被撑出画面。
        // 文字宽度与字号近似线性，迭代几次即可收敛。
        var guard = 0
        while (guard < MAX_SHRINK_ITERATIONS) {
            labelPaint.textSize = textSize
            valuePaint.textSize = textSize

            val widest = entries.maxOf { (label, value) -> labelPaint.measureText("$label：$value") }
            val panelPadding = textSize * INFO_PANEL_PADDING_RATIO
            val needed = widest + panelPadding * 2
            if (needed <= available) break

            val shrinkRatio = available / needed
            val next = (textSize * shrinkRatio).coerceAtLeast(INFO_MIN_TEXT_SIZE)
            if (next >= textSize) break                                     // 已到下限，停止收缩
            textSize = next
            guard++
        }
        labelPaint.textSize = textSize
        valuePaint.textSize = textSize

        // 长文本优先**折行**而不是缩小：地址这类内容换行显示既清楚又不牺牲字号。
        // 只有折到上限仍然放不下的极长内容，才截断加省略号。
        val panelPadding = textSize * INFO_PANEL_PADDING_RATIO
        val maxTextWidth = available - panelPadding * 2
        val displayEntries = buildList {
            entries.forEach { (label, value) ->
                val labelText = "$label："
                val valueSpace = (maxTextWidth - labelPaint.measureText(labelText)).coerceAtLeast(textSize)
                val valueLines = wrapText(value, valuePaint, valueSpace, INFO_MAX_VALUE_LINES)

                // 第一行带标签，后续行用等宽空白对齐（视觉上仍属于同一项）
                valueLines.forEachIndexed { lineIndex, lineText ->
                    if (lineIndex == 0) {
                        add(labelText to lineText)
                    } else {
                        add(INFO_CONTINUATION_INDENT + lineText to "")
                    }
                }
            }
        }

        // 折行后如果整体过高（行太多），按高度再收一次字号，避免面板顶出画面
        val maxPanelHeight = height - padding * 2
        var heightGuard = 0
        while (heightGuard < MAX_SHRINK_ITERATIONS) {
            val lh = textSize * INFO_LINE_SPACING
            val ph = displayEntries.size * lh + panelPadding * 2 - (lh - textSize)
            if (ph <= maxPanelHeight) break
            val next = (textSize * 0.9f).coerceAtLeast(INFO_MIN_TEXT_SIZE)
            if (next >= textSize) break
            textSize = next
            labelPaint.textSize = textSize
            valuePaint.textSize = textSize
            heightGuard++
        }

        val lineHeight = textSize * INFO_LINE_SPACING
        val contentWidth = displayEntries.maxOf { (labelText, value) ->
            labelPaint.measureText(labelText + value)
        }
        val panelWidth = (contentWidth + panelPadding * 2).coerceAtMost(available)
        val panelHeight = displayEntries.size * lineHeight + panelPadding * 2 - (lineHeight - textSize)

        // 左下角底板
        val left = padding
        val top = height - padding - panelHeight
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(INFO_PANEL_ALPHA, 0, 0, 0)
        }
        val corner = textSize * 0.4f
        canvas.drawRoundRect(
            RectF(left, top, left + panelWidth, top + panelHeight),
            corner,
            corner,
            panelPaint
        )

        // 逐行绘制「标签：值」
        displayEntries.forEachIndexed { index, (labelText, value) ->
            val baseline = top + panelPadding + textSize + index * lineHeight
            canvas.drawText(labelText, left + panelPadding, baseline, labelPaint)
            canvas.drawText(
                value,
                left + panelPadding + labelPaint.measureText(labelText),
                baseline,
                valuePaint
            )
        }

        Log.d(
            TAG,
            "drawInfoPanel: 已绘制 ${displayEntries.size} 行信息水印 " +
                "textSize=$textSize(基准=$baseTextSize), 底板宽=$panelWidth, 可用宽=$available"
        )
    }

    /**
     * 按可用宽度把文本折成多行
     *
     * 逐字符累加测量（中英文混排也能正确处理），最多折 [maxLines] 行；
     * 超过上限的部分做省略号截断，绝不溢出。
     *
     * @param text 原文
     * @param paint 画笔（字号必须已设好）
     * @param maxWidth 单行可用宽度
     * @param maxLines 最多折几行
     */
    private fun wrapText(text: String, paint: TextPaint, maxWidth: Float, maxLines: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        if (paint.measureText(text) <= maxWidth) return listOf(text)

        val lines = mutableListOf<String>()
        val current = StringBuilder()

        for (ch in text) {
            current.append(ch)
            if (paint.measureText(current.toString()) > maxWidth && current.length > 1) {
                // 当前行超宽：把最后一个字符挪到下一行
                val last = current.last()
                current.deleteCharAt(current.length - 1)
                lines.add(current.toString())
                current.clear()
                current.append(last)

                if (lines.size == maxLines) {
                    // 已经折到上限：剩余内容截断加省略号
                    val head = lines.removeAt(lines.size - 1)
                    lines.add(
                        TextUtils.ellipsize(
                            head + last + text.substringAfter(head + last, ""),
                            paint,
                            maxWidth,
                            TextUtils.TruncateAt.END
                        ).toString()
                    )
                    return lines
                }
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    /**
     * 经纬度格式化：固定 6 位小数，与常见地图/巡检记录习惯一致
     */
    private fun formatCoordinate(value: Double): String =
        String.format(Locale.US, "%.6f", value)

    /**
     * 获取设备市场名称（友好名称）
     *
     * 将设备编码转换为用户友好的市场名称
     *
     * @param brand 品牌
     * @param model 型号（可能是设备编码）
     * @return 友好的设备名称
     */
    private fun getDeviceMarketingName(brand: String, model: String): String {
        val brandCapitalized = brand.lowercase().replaceFirstChar { it.uppercase() }

        // 小米设备编码映射（常见机型）
        val xiaomiModels = mapOf(
            "24031PN0DC" to "14 Pro",
            "2304FPN6DC" to "13 Ultra",
            "23046RP50C" to "13 Pro",
            "2211133C" to "13",
            "2203121C" to "12 Pro",
            "2201123C" to "12",
            "2112123AC" to "12X",
            "21091116AC" to "Civi",
            "M2102K1AC" to "11 Ultra",
            "M2011K2C" to "11 Pro",
            "M2001J2C" to "10 Pro",
            "M2007J3SC" to "10 Ultra",
            "23116PN5BC" to "14",
            "2310DRK48C" to "14 Ultra"
        )

        // 华为设备编码映射
        val huaweiModels = mapOf(
            "NOH-AN00" to "Mate 40 Pro",
            "OCE-AN10" to "Mate 40",
            "ELS-AN00" to "P40 Pro",
            "ANA-AN00" to "P40"
        )

        // OPPO设备编码映射
        val oppoModels = mapOf(
            "PHQ110" to "Find X6 Pro",
            "PGFM10" to "Find X5 Pro"
        )

        // vivo设备编码映射
        val vivoModels = mapOf(
            "V2227A" to "X90 Pro+",
            "V2242A" to "X90 Pro"
        )

        // 尝试从映射表获取友好名称
        val friendlyModel = when (brand.lowercase()) {
            "xiaomi", "redmi" -> xiaomiModels[model]
            "huawei", "honor" -> huaweiModels[model]
            "oppo", "oneplus" -> oppoModels[model]
            "vivo" -> vivoModels[model]
            else -> null
        }

        return if (friendlyModel != null) {
            "$brandCapitalized $friendlyModel"
        } else {
            // 如果没有映射，检查model是否已经是友好名称
            if (model.any { it.isLetter() } && !model.all { it.isUpperCase() || it.isDigit() }) {
                // model包含小写字母，可能已经是友好名称
                "$brandCapitalized $model"
            } else {
                // model是纯编码，使用品牌名
                brandCapitalized
            }
        }
    }

    /**
     * 获取镜头描述（模拟不同品牌的风格）
     *
     * @param brand 品牌名称
     * @return 镜头描述文字
     */
    private fun getLensDescription(brand: String): String {
        return when (brand.lowercase()) {
            "xiaomi", "redmi" -> "LEICA VARIO-SUMMILUX 1:1.4-3.2/14-75 ASPH."
            "huawei", "honor" -> "XMAGE ULTRA APERTURE F/1.4"
            "oppo" -> "HASSELBLAD CAMERA FOR MOBILE"
            "vivo" -> "ZEISS OPTICS T* COATING"
            "oneplus" -> "HASSELBLAD CAMERA SYSTEM"
            "samsung" -> "EXPERTRAW F/1.8 OIS"
            "google" -> "PIXEL CAMERA HDR+ ENHANCED"
            "apple" -> "MAIN CAMERA ƒ/1.5"
            "sony" -> "ZEISS OPTICS T* ƒ/1.7"
            else -> "AI CAMERA F/1.8"
        }
    }
}
