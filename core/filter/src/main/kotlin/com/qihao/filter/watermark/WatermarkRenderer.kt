/**
 * WatermarkRenderer.kt - 水印渲染器
 *
 * 使用Canvas在Bitmap上绘制各种水印效果
 * 支持8种水印类型：时间戳、日期、设备信息、自定义、信息面板、工程表格、大字时间、极简胶囊
 *
 * 设计参考：
 * - 数码相机时间戳水印（橙色纯文字）
 * - 徕卡风格设备水印
 * - 工程水印相机的"验收单"表格样式（GRID）
 * - 小米/华为系统相机的大字时间水印（STAMP）
 *
 * 信息类水印（INFO/GRID/STAMP/MINI）支持四角位置（[WatermarkPosition]），
 * 其余类型固定在右下角。
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

    // 工程表格水印样式常量
    private const val GRID_TITLE_RATIO = 1.35f                               // 时间行相对基准字号的放大倍数
    private const val GRID_LABEL_RATIO = 0.85f                               // 标签相对值的缩小倍数
    private const val GRID_LINE_SPACING = 1.55f                              // 行高倍数
    private const val GRID_LABEL_VALUE_GAP_RATIO = 0.35f                     // 标签与值的间隙（相对字号）
    private const val GRID_INNER_PADDING_RATIO = 0.9f                        // 表格内边距（相对字号）
    private const val GRID_MAX_ADDRESS_LINES = 2                             // 地址最多折几行
    private const val GRID_PANEL_ALPHA = 165                                 // 表格底板不透明度（0~255）
    private const val GRID_BORDER_ALPHA = 190                                // 表格线框不透明度
    private const val GRID_SEPARATOR_ALPHA = 60                              // 行分隔线不透明度

    /** 表格标签的灰色（工程单据观感，与白色值区分层级） */
    private val GRID_LABEL_COLOR = Color.rgb(204, 204, 204)

    // 大字时间水印样式常量
    private const val STAMP_MAIN_RATIO = 0.045f                              // 主时间字号比例
    private const val STAMP_MAIN_MIN_SIZE = 40f                              // 主时间字号下限
    private const val STAMP_SUB_RATIO = 0.022f                               // 副文字字号比例
    private const val STAMP_SUB_MIN_SIZE = 20f                               // 副文字字号下限
    private const val STAMP_LINE_SPACING = 1.35f                             // 行高倍数

    // 极简胶囊水印样式常量
    private const val MINI_TEXT_RATIO = 0.020f                               // 文字字号比例
    private const val MINI_TEXT_MIN_SIZE = 18f                               // 文字字号下限
    private const val MINI_PANEL_ALPHA = 140                                 // 胶囊底板不透明度

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
        INFO,             // 信息水印（经纬度/地址/时间/天气/备注 多行面板）
        GRID,             // 工程表格（线框表格：时间大字 + 经纬度并排 + 地址/天气/备注）
        STAMP,            // 大字时间（大号时间 + 小号地址/天气，无底板）
        MINI              // 极简胶囊（单行：时间·天气·备注）
    }

    /**
     * 水印位置
     *
     * 作用于信息类水印（INFO/GRID/STAMP/MINI）；其余类型固定在右下角。
     *
     * @param id 持久化用的稳定标识（不要随意改动，否则老配置会失效）
     * @param label 设置页显示名称
     */
    enum class WatermarkPosition(val id: String, val label: String) {
        BOTTOM_LEFT("bottom_left", "左下"),
        BOTTOM_RIGHT("bottom_right", "右下"),
        TOP_LEFT("top_left", "左上"),
        TOP_RIGHT("top_right", "右上");

        companion object {
            /** 默认位置（左下，与历史版本行为一致） */
            val DEFAULT: WatermarkPosition = BOTTOM_LEFT

            /**
             * 解析持久化的位置
             *
             * 无法识别时回落到默认，避免配置损坏导致水印跑到意外的角落。
             */
            fun fromId(raw: String?): WatermarkPosition =
                entries.firstOrNull { it.id == raw } ?: DEFAULT
        }
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
        val sizeScale: Float = DEFAULT_SIZE_SCALE,                            // 水印大小倍率（1.0 为基准）
        val position: WatermarkPosition = WatermarkPosition.DEFAULT,          // 水印位置（信息类水印生效）
        val edgeInsetTop: Float = 0f,                                         // 预览取景窗顶部裁掉的高度比例（成片恒为 0）
        val edgeInsetBottom: Float = 0f,                                      // 预览取景窗底部裁掉的高度比例（成片恒为 0）
        val edgeInsetLeft: Float = 0f,                                        // 预览取景窗左侧裁掉的宽度比例（成片恒为 0）
        val edgeInsetRight: Float = 0f                                        // 预览取景窗右侧裁掉的宽度比例（成片恒为 0）
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
            WatermarkType.GRID -> drawGridPanel(canvas, resultBitmap, data)
            WatermarkType.STAMP -> drawStamp(canvas, resultBitmap, data)
            WatermarkType.MINI -> drawMiniCapsule(canvas, resultBitmap, data)
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
        drawDigitalCameraText(canvas, bitmap, text, data)
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
        drawDigitalCameraText(canvas, bitmap, text, data)
    }

    /**
     * 绘制数码相机风格文字（橙色纯文字，无背景）
     *
     * @param canvas 画布
     * @param bitmap Bitmap（用于计算尺寸）
     * @param text 要绘制的文字
     * @param data 水印数据（大小倍率 + 预览可见区裁切）
     */
    private fun drawDigitalCameraText(canvas: Canvas, bitmap: Bitmap, text: String, data: WatermarkData) {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val padding = (width * WATERMARK_PADDING_RATIO).coerceAtLeast(20f)
        val textSize = (width * WATERMARK_TEXT_SIZE_RATIO).coerceAtLeast(32f) *
            clampScale(data.sizeScale)

        // 数码相机风格画笔：橙色文字 + 黑色描边阴影
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIGITAL_CAMERA_COLOR                                      // 橙色
            this.textSize = textSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)     // 等宽字体，更像数码相机
            letterSpacing = 0.05f                                             // 轻微字间距
            setShadowLayer(3f, 2f, 2f, Color.argb(200, 0, 0, 0))              // 黑色阴影增强可读性
        }

        // 计算文字位置（右下角，锚定在取景窗可见区内）
        val textWidth = textPaint.measureText(text)
        val x = data.visibleRight(width) - padding - textWidth
        val y = data.visibleBottom(height) - padding

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

        // 计算位置（右下角，右对齐，锚定在取景窗可见区内）
        val mainTextWidth = mainPaint.measureText(mainText)
        val subTextWidth = subPaint.measureText(subText)

        // 主文字右对齐
        val mainX = data.visibleRight(width) - padding - mainTextWidth
        // 副文字右对齐
        val subX = data.visibleRight(width) - padding - subTextWidth

        val y = data.visibleBottom(height) - padding - subtitleSize - 12f

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

        // 计算文字位置（右下角，锚定在取景窗可见区内）
        val textWidth = textPaint.measureText(text)
        val x = data.visibleRight(width) - padding - textWidth
        val y = data.visibleBottom(height) - padding

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
        val layout = cached(infoSlot, layoutKey(WatermarkType.INFO, data, bitmap)) {
            layoutInfoPanel(data, bitmap.width.toFloat(), bitmap.height.toFloat())
        } ?: return

        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val padding = layout.padding
        val panelWidth = layout.panelWidth
        val panelHeight = layout.panelHeight

        // 按设置的位置摆放（锚定在取景窗可见区内；默认左下，与历史版本一致）
        val rightSide = data.position.isRightSide()
        val topSide = data.position.isTopSide()
        val left = if (rightSide) {
            data.visibleRight(width) - padding - panelWidth
        } else {
            data.visibleLeft(width) + padding
        }
        val top = if (topSide) {
            data.visibleTop(height) + padding
        } else {
            data.visibleBottom(height) - padding - panelHeight
        }
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(INFO_PANEL_ALPHA, 0, 0, 0)
        }
        val corner = layout.textSize * 0.4f
        canvas.drawRoundRect(
            RectF(left, top, left + panelWidth, top + panelHeight),
            corner,
            corner,
            panelPaint
        )

        // 逐行绘制「标签：值」
        layout.displayEntries.forEachIndexed { index, (labelText, value) ->
            val baseline = top + layout.panelPadding + layout.textSize + index * layout.lineHeight
            canvas.drawText(labelText, left + layout.panelPadding, baseline, layout.labelPaint)
            canvas.drawText(
                value,
                left + layout.panelPadding + layout.labelPaint.measureText(labelText),
                baseline,
                layout.valuePaint
            )
        }
    }

    /** 信息面板量好的排版结果（字号、行、底板尺寸、画笔），可跨帧复用 */
    private class InfoPanelLayout(
        val displayEntries: List<Pair<String, String>>,
        val textSize: Float,
        val padding: Float,
        val panelPadding: Float,
        val lineHeight: Float,
        val panelWidth: Float,
        val panelHeight: Float,
        val labelPaint: TextPaint,
        val valuePaint: TextPaint
    )

    /**
     * 排版信息面板：组装行、收缩字号、折行，全部只依赖 data 和位图尺寸，
     * 与位图像素无关 —— 因此可以在内容不变（同一秒内）时跨帧复用。
     */
    private fun layoutInfoPanel(data: WatermarkData, width: Float, height: Float): InfoPanelLayout? {
        // 按固定顺序组装「标签 -> 值」，值为空的整行跳过
        val snap = buildInfoSnapshot(data)
        val entries = buildList {
            snap.longitude?.let { add("经度" to it) }
            snap.latitude?.let { add("纬度" to it) }
            snap.address?.let { add("地址" to it) }
            snap.time?.let { add("时间" to it) }
            snap.weather?.let { add("天气" to it) }
            snap.remark?.let { add("备注" to it) }
        }
        if (entries.isEmpty()) return null

        val padding = (width * WATERMARK_PADDING_RATIO).coerceAtLeast(16f)

        // 底板可用的横向空间（横屏全屏铺满时左右也可能被裁，收进可见区）
        val available = data.visibleRight(width) - data.visibleLeft(width) - padding * 2
        // 倍率作用于基准字号；随后的"缩到放得下"逻辑仍会生效，
        // 所以放大后遇到长地址也不会溢出画面，只会被自动缩回来。
        var textSize = (width * INFO_TEXT_SIZE_RATIO).coerceAtLeast(22f) *
            clampScale(data.sizeScale)

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

        // 折行后如果整体过高（行太多），按"可见区高度"再收一次字号，避免面板顶出画面
        val maxPanelHeight = data.visibleBottom(height) - data.visibleTop(height) - padding * 2
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

        return InfoPanelLayout(
            displayEntries = displayEntries,
            textSize = textSize,
            padding = padding,
            panelPadding = panelPadding,
            lineHeight = lineHeight,
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            labelPaint = labelPaint,
            valuePaint = valuePaint
        )
    }

    // ==================== 信息类水印共用 ====================

    /**
     * 信息类水印共用的字段快照
     *
     * INFO/GRID/STAMP/MINI 四种水印都从这份快照取内容；
     * 值为 null 表示该字段没取到（或被设置关掉），对应行整行不画。
     */
    private data class InfoSnapshot(
        val longitude: String?,
        val latitude: String?,
        val address: String?,
        val time: String?,
        val weather: String?,
        val remark: String?
    ) {
        /** 是否一项内容都没有（此时整个水印不画） */
        val isEmpty: Boolean
            get() = longitude == null && latitude == null && address == null &&
                time == null && weather == null && remark == null
    }

    /**
     * 组装字段快照
     *
     * @param timePattern 时间格式（信息面板用完整秒，大字时间/胶囊用分钟精度）
     */
    private fun buildInfoSnapshot(
        data: WatermarkData,
        timePattern: String = "yyyy-MM-dd HH:mm:ss"
    ): InfoSnapshot {
        val time = if (data.includeTimestamp) {
            SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(data.timestamp))
        } else {
            null
        }
        return InfoSnapshot(
            longitude = data.longitude?.let { formatCoordinate(it) },
            latitude = data.latitude?.let { formatCoordinate(it) },
            address = data.address?.trim()?.takeIf { it.isNotEmpty() },
            time = time,
            weather = data.weather?.trim()?.takeIf { it.isNotEmpty() },
            remark = data.customText.trim().takeIf { it.isNotEmpty() }
        )
    }

    /** 行内容是否靠画面右侧（按位置设置判断） */
    private fun WatermarkPosition.isRightSide(): Boolean =
        this == WatermarkPosition.BOTTOM_RIGHT || this == WatermarkPosition.TOP_RIGHT

    /** 行内容是否靠画面顶部（按位置设置判断） */
    private fun WatermarkPosition.isTopSide(): Boolean =
        this == WatermarkPosition.TOP_LEFT || this == WatermarkPosition.TOP_RIGHT

    /**
     * 取景窗可见区域的顶边
     *
     * 预览全屏铺满时，取景窗相对位图上下各裁掉一部分（ContentScale.Crop），
     * 水印锚点必须收进可见区，否则底部/顶部水印会被切掉一半。
     * 成片没有裁切，inset 恒为 0，此值退化为 0。
     */
    private fun WatermarkData.visibleTop(bitmapHeight: Float): Float =
        bitmapHeight * edgeInsetTop.coerceIn(0f, 0.45f)

    /** 取景窗可见区域的底边 */
    private fun WatermarkData.visibleBottom(bitmapHeight: Float): Float =
        bitmapHeight * (1f - edgeInsetBottom.coerceIn(0f, 0.45f))

    /** 取景窗可见区域的左边 */
    private fun WatermarkData.visibleLeft(bitmapWidth: Float): Float =
        bitmapWidth * edgeInsetLeft.coerceIn(0f, 0.45f)

    /** 取景窗可见区域的右边 */
    private fun WatermarkData.visibleRight(bitmapWidth: Float): Float =
        bitmapWidth * (1f - edgeInsetRight.coerceIn(0f, 0.45f))

    // ==================== 排版缓存 ====================
    //
    // 预览渲染最高 30fps，而水印内容一秒只变一次（时间秒数）。
    // 每帧重新排版（格式化时间、逐字折行、测量、收缩字号）纯属浪费，
    // 占掉了预览链路里相当大的 CPU。这里按"样式+内容+尺寸"缓存排版结果，
    // 内容不变的帧直接复用，只做真正的绘制。

    /** 排版缓存的键：数据类按值相等，时间戳量化到秒（同一秒内时间字符串不变） */
    private class LayoutKey(
        val type: WatermarkType,
        val data: WatermarkData,
        val width: Int,
        val height: Int
    )

    private fun layoutKey(type: WatermarkType, data: WatermarkData, bitmap: Bitmap) = LayoutKey(
        type,
        data.copy(timestamp = data.timestamp - data.timestamp % 1000),
        bitmap.width,
        bitmap.height
    )

    private class LayoutCacheEntry<T>(val key: LayoutKey, val layout: T?)

    private class LayoutCacheSlot<T> {
        @Volatile
        var entry: LayoutCacheEntry<T>? = null
    }

    private val infoSlot = LayoutCacheSlot<InfoPanelLayout>()
    private val gridSlot = LayoutCacheSlot<GridLayout>()
    private val stampSlot = LayoutCacheSlot<StampLayout>()
    private val miniSlot = LayoutCacheSlot<MiniLayout>()

    /** 命中缓存直接返回排版结果，未命中则排版并写入缓存槽 */
    private inline fun <T> cached(
        slot: LayoutCacheSlot<T>,
        key: LayoutKey,
        compute: () -> T?
    ): T? {
        val entry = slot.entry
        if (entry != null && entry.key == key) return entry.layout
        val layout = compute()
        slot.entry = LayoutCacheEntry(key, layout)
        return layout
    }

    // ==================== 工程表格水印 ====================

    /** 表格里量好的一格：标签（可空）+ 值 + 总宽度 */
    private class GridCell(
        val label: String?,
        val value: String,
        val width: Float
    )

    /** 表格里量好的一行：格子列表（多格并排）+ 行高 + 样式标记 */
    private class GridLine(
        val cells: List<GridCell>,
        val height: Float,
        val isTitle: Boolean,
        /** 是否紧接上一行的折行（折行上方不画分隔线） */
        val continuation: Boolean
    )

    /** 在给定字号下量好的表格布局 */
    private class GridLayout(
        val lines: List<GridLine>,
        val textSize: Float,
        val innerPadding: Float,
        val panelWidth: Float,
        val panelHeight: Float,
        val labelPaint: TextPaint,
        val valuePaint: TextPaint,
        val titlePaint: TextPaint,
        val gap: Float
    )

    /**
     * 绘制工程表格水印
     *
     * 输出形如（深色底 + 白色线框 + 行分隔线的"验收单"样式）：
     * ```
     * ┌──────────────────────────────────┐
     * │ 2026-09-16 15:32:59              │  ← 时间大字加粗
     * ├──────────────────────────────────┤
     * │ 经度 106.518536    纬度 29.794027 │  ← 经纬度并排一行省高度
     * ├──────────────────────────────────┤
     * │ 地址 重庆市两江新区…               │
     * ├──────────────────────────────────┤
     * │ 天气 阴 21℃                       │
     * ├──────────────────────────────────┤
     * │ 备注 段嘉轩 13297470239            │
     * └──────────────────────────────────┘
     * ```
     *
     * 规则与信息水印一致：取不到的字段整行不画，放不下的内容自动缩小字号。
     */
    private fun drawGridPanel(canvas: Canvas, bitmap: Bitmap, data: WatermarkData) {
        val layout = cached(gridSlot, layoutKey(WatermarkType.GRID, data, bitmap)) {
            layoutGridPanel(data, bitmap.width.toFloat(), bitmap.height.toFloat())
        } ?: return

        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val padding = (width * WATERMARK_PADDING_RATIO).coerceAtLeast(16f)

        val left = if (data.position.isRightSide()) {
            data.visibleRight(width) - padding - layout.panelWidth
        } else {
            data.visibleLeft(width) + padding
        }
        val top = if (data.position.isTopSide()) {
            data.visibleTop(height) + padding
        } else {
            data.visibleBottom(height) - padding - layout.panelHeight
        }

        // 深色底板 + 白色线框
        val panelRect = RectF(left, top, left + layout.panelWidth, top + layout.panelHeight)
        val corner = layout.textSize * 0.4f
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(GRID_PANEL_ALPHA, 0, 0, 0)
        }
        canvas.drawRoundRect(panelRect, corner, corner, fillPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (layout.textSize * 0.07f).coerceIn(1.5f, 3f)
            color = Color.argb(GRID_BORDER_ALPHA, 255, 255, 255)
        }
        canvas.drawRoundRect(panelRect, corner, corner, borderPaint)

        // 行分隔线
        val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1f
            color = Color.argb(GRID_SEPARATOR_ALPHA, 255, 255, 255)
        }

        // 逐行绘制：多格行按等分列摆放，行间画细分隔线（折行不画）
        val innerLeft = left + layout.innerPadding
        val innerWidth = layout.panelWidth - layout.innerPadding * 2
        var y = top + layout.innerPadding
        layout.lines.forEachIndexed { index, line ->
            if (index > 0 && !line.continuation) {
                canvas.drawLine(
                    left + layout.innerPadding * 0.5f,
                    y,
                    left + layout.panelWidth - layout.innerPadding * 0.5f,
                    y,
                    separatorPaint
                )
            }
            val mainPaint = if (line.isTitle) layout.titlePaint else layout.valuePaint
            val baseline = y + line.height / 2f + mainPaint.textSize * 0.32f
            val columnWidth = if (line.cells.size > 1) innerWidth / line.cells.size else 0f
            line.cells.forEachIndexed { cellIndex, cell ->
                val x = innerLeft + cellIndex * columnWidth
                var valueX = x
                cell.label?.let { label ->
                    canvas.drawText(label, x, baseline, layout.labelPaint)
                    valueX = x + layout.labelPaint.measureText(label) + layout.gap
                }
                canvas.drawText(cell.value, valueX, baseline, mainPaint)
            }
            y += line.height
        }
    }

    /**
     * 排版工程表格：组装行、收缩字号，只依赖 data 和位图尺寸，可跨帧复用。
     */
    private fun layoutGridPanel(
        data: WatermarkData,
        width: Float,
        height: Float
    ): GridLayout? {
        val snap = buildInfoSnapshot(data)
        if (snap.isEmpty) return null

        val padding = (width * WATERMARK_PADDING_RATIO).coerceAtLeast(16f)
        val available = data.visibleRight(width) - data.visibleLeft(width) - padding * 2
        // 表格必须装进取景窗可见区（预览全屏铺满时上下有裁切）
        val maxPanelHeight = data.visibleBottom(height) - data.visibleTop(height) - padding * 2

        // 与信息水印同一套"缩到放得下"保护：超宽/超高就整体缩小字号
        var textSize = (width * INFO_TEXT_SIZE_RATIO).coerceAtLeast(22f) *
            clampScale(data.sizeScale)
        var layout = layoutGrid(snap, textSize, gridInnerWidth(available, textSize))
        var guard = 0
        while (guard < MAX_SHRINK_ITERATIONS) {
            if (layout.panelWidth <= available && layout.panelHeight <= maxPanelHeight) break
            val wRatio = available / layout.panelWidth
            val hRatio = maxPanelHeight / layout.panelHeight
            val next = (textSize * minOf(wRatio, hRatio) * 0.95f)
                .coerceAtLeast(INFO_MIN_TEXT_SIZE)
            if (next >= textSize) break                                     // 已到下限，停止收缩
            textSize = next
            layout = layoutGrid(snap, textSize, gridInnerWidth(available, textSize))
            guard++
        }
        return layout
    }

    /** 表格内宽：可用宽度减去两侧内边距（内边距随字号缩放） */
    private fun gridInnerWidth(available: Float, textSize: Float): Float =
        available - textSize * GRID_INNER_PADDING_RATIO * 2

    /**
     * 在给定字号下量出表格布局
     *
     * @param snap 字段快照
     * @param textSize 基准字号（时间行/标签按比例缩放）
     * @param innerWidth 表格内宽（单行可用宽度）
     */
    private fun layoutGrid(snap: InfoSnapshot, textSize: Float, innerWidth: Float): GridLayout {
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GRID_LABEL_COLOR
            this.textSize = textSize * GRID_LABEL_RATIO
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(2f, 1f, 1f, Color.argb(180, 0, 0, 0))
        }
        val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(2f, 1f, 1f, Color.argb(180, 0, 0, 0))
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize * GRID_TITLE_RATIO
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(2f, 1f, 1f, Color.argb(180, 0, 0, 0))
        }
        val gap = textSize * GRID_LABEL_VALUE_GAP_RATIO

        val lines = mutableListOf<GridLine>()

        // 时间行：大字加粗，作为表格标题
        snap.time?.let { time ->
            lines.add(
                GridLine(
                    cells = listOf(GridCell(null, time, titlePaint.measureText(time))),
                    height = textSize * GRID_TITLE_RATIO * GRID_LINE_SPACING,
                    isTitle = true,
                    continuation = false
                )
            )
        }

        // 经纬度并排一行（只有其一时整行只放一个）；超长的值省略号截断
        val geoCells = listOfNotNull(
            snap.longitude?.let { Pair("经度", it) },
            snap.latitude?.let { Pair("纬度", it) }
        )
        if (geoCells.isNotEmpty()) {
            val columnWidth = innerWidth / geoCells.size
            val cells = geoCells.map { (label, value) ->
                val labelWidth = labelPaint.measureText(label)
                val valueSpace = (columnWidth - labelWidth - gap).coerceAtLeast(textSize)
                val clipped = ellipsizeByWidth(value, valuePaint, valueSpace)
                GridCell(label, clipped, labelWidth + gap + valuePaint.measureText(clipped))
            }
            lines.add(GridLine(cells, textSize * GRID_LINE_SPACING, false, false))
        }

        // 地址可折行（最多 2 行），续行不带标签、上方不画分隔线
        snap.address?.let { address ->
            val labelWidth = labelPaint.measureText("地址")
            val valueSpace = (innerWidth - labelWidth - gap).coerceAtLeast(textSize)
            val valueLines = wrapText(address, valuePaint, valueSpace, GRID_MAX_ADDRESS_LINES)
            valueLines.forEachIndexed { index, line ->
                val width = labelWidth + gap + valuePaint.measureText(line)
                lines.add(
                    GridLine(
                        cells = listOf(GridCell(if (index == 0) "地址" else null, line, width)),
                        height = textSize * GRID_LINE_SPACING,
                        isTitle = false,
                        continuation = index > 0
                    )
                )
            }
        }

        snap.weather?.let {
            lines.add(singleGridLine("天气", it, labelPaint, valuePaint, gap, textSize, innerWidth))
        }
        snap.remark?.let {
            lines.add(singleGridLine("备注", it, labelPaint, valuePaint, gap, textSize, innerWidth))
        }

        val innerPadding = textSize * GRID_INNER_PADDING_RATIO
        // 行的视觉宽度：多格行按等分列摆放，取最后一格的右边界
        val contentWidth = lines.maxOf { line ->
            if (line.cells.size > 1) {
                (line.cells.size - 1) * (innerWidth / line.cells.size) + line.cells.last().width
            } else {
                line.cells.first().width
            }
        }
        val panelWidth = contentWidth + innerPadding * 2
        val panelHeight = lines.sumOf { it.height.toDouble() }.toFloat() + innerPadding * 2

        return GridLayout(
            lines = lines,
            textSize = textSize,
            innerPadding = innerPadding,
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            labelPaint = labelPaint,
            valuePaint = valuePaint,
            titlePaint = titlePaint,
            gap = gap
        )
    }

    /** 量一条「标签 + 值」的单格行，值超宽时省略号截断 */
    private fun singleGridLine(
        label: String,
        value: String,
        labelPaint: TextPaint,
        valuePaint: TextPaint,
        gap: Float,
        textSize: Float,
        innerWidth: Float
    ): GridLine {
        val labelWidth = labelPaint.measureText(label)
        val valueSpace = (innerWidth - labelWidth - gap).coerceAtLeast(textSize)
        val clipped = ellipsizeByWidth(value, valuePaint, valueSpace)
        return GridLine(
            cells = listOf(
                GridCell(label, clipped, labelWidth + gap + valuePaint.measureText(clipped))
            ),
            height = textSize * GRID_LINE_SPACING,
            isTitle = false,
            continuation = false
        )
    }

    // ==================== 大字时间水印 ====================

    /**
     * 绘制大字时间水印（系统相机风格）
     *
     * 无底板：大号白色粗体时间 + 小号地址/天气/备注，靠阴影保证可读性。
     * 时间只精确到分钟——大字秒数会一直跳动，观感很差。
     */
    private fun drawStamp(canvas: Canvas, bitmap: Bitmap, data: WatermarkData) {
        val layout = cached(stampSlot, layoutKey(WatermarkType.STAMP, data, bitmap)) {
            layoutStamp(data, bitmap.width.toFloat())
        } ?: return
        val rows = layout.rows

        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val padding = (width * WATERMARK_PADDING_RATIO).coerceAtLeast(20f)

        val rightSide = data.position.isRightSide()
        val topSide = data.position.isTopSide()

        if (topSide) {
            // 顶部位置：自上而下堆叠（锚定在可见区顶边）
            var y = data.visibleTop(height) + padding + rows.first().second.textSize
            rows.forEach { (text, paint) ->
                val x = if (rightSide) {
                    data.visibleRight(width) - padding - paint.measureText(text)
                } else {
                    data.visibleLeft(width) + padding
                }
                canvas.drawText(text, x, y, paint)
                y += paint.textSize * STAMP_LINE_SPACING
            }
        } else {
            // 底部位置：自下而上堆叠（最后一行贴着可见区底边）
            var baseline = data.visibleBottom(height) - padding
            rows.reversed().forEach { (text, paint) ->
                val x = if (rightSide) {
                    data.visibleRight(width) - padding - paint.measureText(text)
                } else {
                    data.visibleLeft(width) + padding
                }
                canvas.drawText(text, x, baseline, paint)
                baseline -= paint.textSize * STAMP_LINE_SPACING
            }
        }
    }

    /** 大字时间的排版结果：预截断的行列表（行高用画笔字号计算） */
    private class StampLayout(val rows: List<Pair<String, TextPaint>>)

    /**
     * 排版大字时间：格式化时间、组装行并预截断，只依赖 data 和位图宽度，可跨帧复用。
     */
    private fun layoutStamp(data: WatermarkData, width: Float): StampLayout? {
        val snap = buildInfoSnapshot(data, timePattern = "yyyy-MM-dd HH:mm")

        val padding = (width * WATERMARK_PADDING_RATIO).coerceAtLeast(20f)
        val available = data.visibleRight(width) - data.visibleLeft(width) - padding * 2
        val scale = clampScale(data.sizeScale)

        val mainSize = (width * STAMP_MAIN_RATIO).coerceAtLeast(STAMP_MAIN_MIN_SIZE) * scale
        val subSize = (width * STAMP_SUB_RATIO).coerceAtLeast(STAMP_SUB_MIN_SIZE) * scale

        val mainPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = mainSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(6f, 3f, 3f, Color.argb(220, 0, 0, 0))
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = subSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(4f, 2f, 2f, Color.argb(200, 0, 0, 0))
        }

        // 行列表：时间大字一行；地址一行；天气与备注合一行（都有才用 · 连接）
        // 行高用画笔自身的字号计算，主副行天然区分
        val rows = buildList {
            snap.time?.let { time ->
                add(ellipsizeByWidth(time, mainPaint, available) to mainPaint)
            }
            snap.address?.let { address ->
                add(ellipsizeByWidth(address, subPaint, available) to subPaint)
            }
            listOfNotNull(snap.weather, snap.remark).takeIf { it.isNotEmpty() }?.let { parts ->
                add(ellipsizeByWidth(parts.joinToString(" · "), subPaint, available) to subPaint)
            }
        }
        if (rows.isEmpty()) return null
        return StampLayout(rows)
    }

    // ==================== 极简胶囊水印 ====================

    /**
     * 绘制极简胶囊水印
     *
     * 一条半透明圆角胶囊装下单行内容：`09-19 14:32 · 阴 21℃ · 备注`。
     * 极简样式始终水平居中，位置设置只决定靠上还是靠下。
     */
    private fun drawMiniCapsule(canvas: Canvas, bitmap: Bitmap, data: WatermarkData) {
        val layout = cached(miniSlot, layoutKey(WatermarkType.MINI, data, bitmap)) {
            layoutMiniCapsule(data, bitmap.width.toFloat(), bitmap.height.toFloat())
        } ?: return

        val height = bitmap.height.toFloat()

        val left = data.visibleLeft(layout.width) +
            (data.visibleRight(layout.width) - data.visibleLeft(layout.width) - layout.capsuleWidth) / 2f
        val top = if (data.position.isTopSide()) {
            data.visibleTop(height) + layout.padding
        } else {
            data.visibleBottom(height) - layout.padding - layout.capsuleHeight
        }

        val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(MINI_PANEL_ALPHA, 0, 0, 0)
        }
        canvas.drawRoundRect(
            RectF(left, top, left + layout.capsuleWidth, top + layout.capsuleHeight),
            layout.capsuleHeight / 2f,
            layout.capsuleHeight / 2f,
            capsulePaint
        )
        canvas.drawText(
            layout.shown,
            left + layout.capsulePadX,
            top + layout.capsuleHeight / 2f + layout.textSize * 0.32f,
            layout.textPaint
        )
    }

    /** 极简胶囊的排版结果 */
    private class MiniLayout(
        val shown: String,
        val textPaint: TextPaint,
        val textSize: Float,
        val padding: Float,
        val capsulePadX: Float,
        val capsuleWidth: Float,
        val capsuleHeight: Float,
        val width: Float
    )

    /**
     * 排版极简胶囊：组装单行内容并预截断，只依赖 data 和位图宽度，可跨帧复用。
     */
    private fun layoutMiniCapsule(data: WatermarkData, width: Float, height: Float): MiniLayout? {
        val snap = buildInfoSnapshot(data, timePattern = "MM-dd HH:mm")
        val parts = listOfNotNull(snap.time, snap.weather, snap.remark)
        if (parts.isEmpty()) return null
        val text = parts.joinToString(" · ")

        val padding = (width * WATERMARK_PADDING_RATIO).coerceAtLeast(16f)
        val available = data.visibleRight(width) - data.visibleLeft(width) - padding * 2
        val textSize = (width * MINI_TEXT_RATIO).coerceAtLeast(MINI_TEXT_MIN_SIZE) *
            clampScale(data.sizeScale)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(2f, 1f, 1f, Color.argb(160, 0, 0, 0))
        }

        val capsulePadX = textSize * 1.2f
        val shown = ellipsizeByWidth(text, textPaint, available - capsulePadX * 2)
        val textWidth = textPaint.measureText(shown)
        val capsuleWidth = textWidth + capsulePadX * 2
        val capsuleHeight = textSize * 2.3f

        return MiniLayout(
            shown = shown,
            textPaint = textPaint,
            textSize = textSize,
            padding = padding,
            capsulePadX = capsulePadX,
            capsuleWidth = capsuleWidth,
            capsuleHeight = capsuleHeight,
            width = width
        )
    }

    /**
     * 把文本截断到 [maxWidth] 内，超出部分以「…」结尾
     *
     * 不用 [android.text.TextUtils.ellipsize]：它依赖系统文本布局，
     * 在部分环境（如 Robolectric 原生图形模式）下不生效、直接返回原文，
     * 会导致水印溢出画面。这里用 measureText 逐字符二分收缩，
     * 行为在所有环境一致。
     */
    private fun ellipsizeByWidth(text: String, paint: TextPaint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paint.measureText(text.substring(0, mid) + ellipsis) <= maxWidth) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        return text.substring(0, lo) + ellipsis
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
                    // 已消费字符数 = 此前行总长 + 当前行已计入的字符 + 挪到下一行的最后一个字符
                    val consumed = lines.sumOf { it.length } + head.length + 1
                    lines.add(
                        ellipsizeByWidth(
                            head + last + text.substring(consumed.coerceAtMost(text.length)),
                            paint,
                            maxWidth
                        )
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
