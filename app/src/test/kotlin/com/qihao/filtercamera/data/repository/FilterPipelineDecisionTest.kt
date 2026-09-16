/**
 * FilterPipelineDecisionTest.kt - "要不要走滤镜链路"这个判断的回归测试
 *
 * 为什么单独测这个三行的判断：
 * 信息水印的绘制代码在滤镜链路内部，而拍照和预览都会在"无滤镜"时走快捷分支。
 * 一旦这个判断退回成 `filterType != NONE`，就会出现
 * **"设置里开着信息水印、但没选滤镜 ⇒ 完全看不到水印"** ——
 * 这个 bug 真实发生过一次，而且从代码上很难一眼看出来。
 *
 * 纯函数判断，普通 JVM 单元测试即可，不需要 Android 运行时。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.data.repository

import com.qihao.filtercamera.domain.model.FilterType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滤镜链路决策测试
 */
class FilterPipelineDecisionTest {

    /**
     * 开关打开 + 原图 ⇒ 必须继续走滤镜链路（水印在里面画）
     */
    @Test
    fun watermarkEnabled_withNoneFilter_mustUsePipeline() {
        assertTrue(
            "信息水印打开、滤镜为原图时仍必须走滤镜链路，否则拍不到水印",
            CameraRepositoryImpl.needsFilterPipeline(
                filterType = FilterType.NONE,
                infoWatermarkEnabled = true
            )
        )
    }

    /**
     * 开关关闭 + 原图 ⇒ 可以走快捷分支（不做无谓的位图处理）
     */
    @Test
    fun watermarkDisabled_withNoneFilter_canSkipPipeline() {
        assertFalse(
            "没开信息水印又没有滤镜时应当跳过滤镜链路",
            CameraRepositoryImpl.needsFilterPipeline(
                filterType = FilterType.NONE,
                infoWatermarkEnabled = false
            )
        )
    }

    /**
     * 选了滤镜 ⇒ 无论如何都要走链路
     */
    @Test
    fun selectedFilter_alwaysUsesPipeline() {
        assertTrue(
            CameraRepositoryImpl.needsFilterPipeline(
                filterType = FilterType.WATERMARK_TIMESTAMP,
                infoWatermarkEnabled = false
            )
        )
        assertTrue(
            CameraRepositoryImpl.needsFilterPipeline(
                filterType = FilterType.WATERMARK_INFO,
                infoWatermarkEnabled = false
            )
        )
    }

    // ==================== 分析帧是否需要转成 Bitmap ====================

    /**
     * 默认状态（无滤镜/无水印/无美颜/无虚化）且已有原始帧 ⇒ 不需要转换
     *
     * 这是拍照慢的主因之一：分析帧的 YUV→Bitmap 要经过 JPEG 编码+解码，
     * 30fps 下每秒 60 次，而转换结果根本没人用。
     */
    @Test
    fun idleState_skipsAnalysisConversion() {
        assertFalse(
            "没有任何激活效果时不应转换分析帧（否则白跑每秒 60 次 JPEG 编解码）",
            CameraRepositoryImpl.needsAnalysisBitmap(
                filterType = FilterType.NONE,
                infoWatermarkEnabled = false,
                beautyIntensity = 0f,
                portraitBlurActive = false,
                hasRawFrame = true
            )
        )
    }

    /**
     * 还没抓到原始预览帧时必须转一帧，否则滤镜缩略图没有素材
     */
    @Test
    fun withoutRawFrame_stillConvertsOnce() {
        assertTrue(
            CameraRepositoryImpl.needsAnalysisBitmap(
                filterType = FilterType.NONE,
                infoWatermarkEnabled = false,
                beautyIntensity = 0f,
                portraitBlurActive = false,
                hasRawFrame = false
            )
        )
    }

    /**
     * 任一效果激活都必须继续转换
     */
    @Test
    fun anyActiveEffect_keepsConverting() {
        assertTrue(
            "选了滤镜必须转换",
            CameraRepositoryImpl.needsAnalysisBitmap(
                filterType = FilterType.FAIRYTALE, infoWatermarkEnabled = false,
                beautyIntensity = 0f, portraitBlurActive = false, hasRawFrame = true
            )
        )
        assertTrue(
            "开了信息水印必须转换",
            CameraRepositoryImpl.needsAnalysisBitmap(
                filterType = FilterType.NONE, infoWatermarkEnabled = true,
                beautyIntensity = 0f, portraitBlurActive = false, hasRawFrame = true
            )
        )
        assertTrue(
            "开了美颜必须转换",
            CameraRepositoryImpl.needsAnalysisBitmap(
                filterType = FilterType.NONE, infoWatermarkEnabled = false,
                beautyIntensity = 0.6f, portraitBlurActive = false, hasRawFrame = true
            )
        )
        assertTrue(
            "开了人像虚化必须转换",
            CameraRepositoryImpl.needsAnalysisBitmap(
                filterType = FilterType.NONE, infoWatermarkEnabled = false,
                beautyIntensity = 0f, portraitBlurActive = true, hasRawFrame = true
            )
        )
    }
}
