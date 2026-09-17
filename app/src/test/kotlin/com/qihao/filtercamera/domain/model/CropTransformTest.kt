/**
 * CropTransformTest.kt - 图片编辑器裁剪的回归测试
 *
 * 背景：编辑器里的 1:1 / 4:3 / 16:9 按钮以前只改状态、不碰图片 ——
 * 点下去只是按钮高亮，保存出来的图和原图一模一样（而且 CropState.hasTransforms
 * 还没把 cropRatio 算进去，连"有变换"都不成立，等于整条裁剪链路是空转）。
 *
 * 这里锁住两件事：
 * 1. hasTransforms() 必须认为"只选了比例"也是一种变换
 * 2. 居中裁剪的矩形计算正确（裁长边、留居中）
 *
 * 纯计算，普通 JVM 单元测试即可。
 *
 * 注：CropState 里带着 android.graphics.RectF，所以 hasTransforms() 那两条
 * 需要 Robolectric 提供真实的 RectF 实现（否则 isEmpty 会报 not mocked）。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.domain.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 裁剪变换测试
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CropTransformTest {

    // ==================== hasTransforms ====================

    /**
     * 只选了裁剪比例也算有变换
     *
     * 这是那个"选比例没反应"的 bug 的关键断言。
     */
    @Test
    fun `只设置裁剪比例也算有变换`() {
        val state = CropState(cropRatio = CropRatio.RATIO_1_1)
        assertTrue("只选 1:1 也必须被判为有变换，否则不会触发裁剪", state.hasTransforms())
    }

    /**
     * 默认状态（自由比例、无旋转翻转）不算有变换
     */
    @Test
    fun `默认状态不算有变换`() {
        assertFalse(CropState().hasTransforms())
        assertFalse(CropState(cropRatio = CropRatio.FREE).hasTransforms())
    }

    // ==================== centerCropRect ====================

    /**
     * 横图裁成 1:1 -> 保留高度，裁掉左右
     */
    @Test
    fun `横图裁成正方形保留高度并居中`() {
        val rect = centerCropRect(4000, 3000, 1f)

        assertEquals("正方形边长应等于原图高度", 3000, rect!![2])
        assertEquals(3000, rect[3])
        assertEquals("水平居中：(4000-3000)/2", 500, rect[0])
        assertEquals("垂直无需裁剪", 0, rect[1])
    }

    /**
     * 竖图裁成 16:9 -> 保留宽度，裁掉上下
     */
    @Test
    fun `竖图裁成宽屏保留宽度并居中`() {
        val rect = centerCropRect(1080, 1920, 16f / 9f)

        assertEquals(1080, rect!![2])
        assertEquals("高度 = 宽 / (16/9)", (1080 / (16f / 9f)).toInt(), rect[3])
        assertEquals("水平无需裁剪", 0, rect[0])
        assertTrue("垂直需要裁剪", rect[1] > 0)
    }

    /**
     * 比例一致时不做任何裁剪
     */
    @Test
    fun `比例一致时返回 null 表示无需裁剪`() {
        assertNull(centerCropRect(4000, 3000, 4f / 3f))
        assertNull(centerCropRect(1920, 1080, 16f / 9f))
    }

    /**
     * FREE 比例（宽高比为 0）不裁剪
     */
    @Test
    fun `自由比例不裁剪`() {
        assertNull(centerCropRect(4000, 3000, CropRatio.FREE.getAspectRatio()))
    }

    /**
     * 异常输入不应崩，直接返回 null
     */
    @Test
    fun `非法尺寸返回 null`() {
        assertNull(centerCropRect(0, 100, 1f))
        assertNull(centerCropRect(100, 0, 1f))
        assertNull(centerCropRect(-10, 100, 1f))
        assertNull(centerCropRect(100, 100, -1f))
    }

    /**
     * 裁剪区域永远落在原图范围内，且比例确实等于目标比例
     */
    @Test
    fun `裁剪区域在范围内且符合目标比例`() {
        val sizes = listOf(4000 to 3000, 1080 to 1920, 1234 to 567)
        val ratios = listOf(CropRatio.RATIO_1_1, CropRatio.RATIO_4_3, CropRatio.RATIO_16_9, CropRatio.RATIO_9_16)

        for ((w, h) in sizes) {
            for (ratio in ratios) {
                val rect = centerCropRect(w, h, ratio.getAspectRatio()) ?: continue
                val (x, y, rw, rh) = listOf(rect[0], rect[1], rect[2], rect[3])

                assertTrue("x 不能为负 ($w x $h, ${ratio.displayName})", x >= 0)
                assertTrue("y 不能为负 ($w x $h, ${ratio.displayName})", y >= 0)
                assertTrue("右边界越界 ($w x $h, ${ratio.displayName})", x + rw <= w)
                assertTrue("下边界越界 ($w x $h, ${ratio.displayName})", y + rh <= h)

                // 允许 1 像素的取整误差
                val actual = rw.toFloat() / rh
                val expected = ratio.getAspectRatio()
                assertTrue(
                    "比例偏差过大 ($w x $h, ${ratio.displayName}): $actual vs $expected",
                    kotlin.math.abs(actual - expected) < 0.01f
                )
            }
        }
    }

    /**
     * 裁剪结果应为纯整型数组 [x, y, w, h]
     */
    @Test
    fun `返回四元组`() {
        val rect = centerCropRect(2000, 1000, 1f)
        assertArrayEquals(intArrayOf(500, 0, 1000, 1000), rect)
    }
}
