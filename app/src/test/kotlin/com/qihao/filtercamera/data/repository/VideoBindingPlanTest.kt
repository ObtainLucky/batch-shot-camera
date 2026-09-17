/**
 * VideoBindingPlanTest.kt - 录像用例绑定组合的回归测试
 *
 * 背景：录像曾经**完全不可用**，一按就报"录像错误"。根因是
 * `CameraRepositoryImpl` 里 VideoCapture 虽然被创建了，却从未参与
 * `bindToLifecycle` —— Recorder 没接到相机上，prepareRecording().start()
 * 必然以 Finalize 错误收场。
 *
 * 同时 CameraX 的用例组合是互斥资源：Preview+ImageCapture+VideoCapture+
 * ImageAnalysis 四用例同绑只有 FULL 级设备支持，绝大多数手机（LIMITED）
 * 会直接抛异常。所以"按模式分别绑定 + 逐级降级"这两件事都必须被锁住，
 * 否则以后有人图省事写成"全都绑上"，录像会再次静默失效。
 *
 * 纯函数决策，普通 JVM 单元测试即可，不需要 Android 运行时。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.data.repository

import com.qihao.filtercamera.data.repository.CameraRepositoryImpl.Companion.useCaseBindingPlan
import com.qihao.filtercamera.data.repository.CameraRepositoryImpl.UseCaseSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录像/拍照绑定组合测试
 */
class VideoBindingPlanTest {

    /**
     * 录像模式的首选组合**必须**包含 VideoCapture
     *
     * 这是那条"录像完全不可用"的 bug 的直接断言。
     */
    @Test
    fun `录像模式首选组合包含 VideoCapture`() {
        val plan = useCaseBindingPlan(
            videoMode = true,
            hasImageCapture = true,
            hasVideoCapture = true,
            hasAnalysis = true
        )

        val first = plan.first()
        assertTrue("首选组合必须含 VideoCapture，实际=$first", first.contains(UseCaseSlot.VIDEO_CAPTURE))
        assertTrue("首选组合必须含 Preview，实际=$first", first.contains(UseCaseSlot.PREVIEW))
    }

    /**
     * 录像模式下不应绑定 ImageCapture
     *
     * 一是四用例同绑在 LIMITED 设备上不被支持，二是录像模式界面上根本没有快门。
     */
    @Test
    fun `录像模式任何组合都不含 ImageCapture`() {
        val plan = useCaseBindingPlan(
            videoMode = true,
            hasImageCapture = true,
            hasVideoCapture = true,
            hasAnalysis = true
        )

        plan.forEach { combination ->
            assertFalse(
                "录像模式的组合不该出现 ImageCapture，实际=$combination",
                combination.contains(UseCaseSlot.IMAGE_CAPTURE)
            )
        }
    }

    /**
     * 拍照模式必须含 ImageCapture，且不应含 VideoCapture
     */
    @Test
    fun `拍照模式组合含 ImageCapture 且不含 VideoCapture`() {
        val plan = useCaseBindingPlan(
            videoMode = false,
            hasImageCapture = true,
            hasVideoCapture = true,
            hasAnalysis = true
        )

        val first = plan.first()
        assertTrue("拍照首选组合必须有 ImageCapture，实际=$first", first.contains(UseCaseSlot.IMAGE_CAPTURE))
        plan.forEach { combination ->
            assertFalse("拍照模式的组合不该出现 VideoCapture，实际=$combination", combination.contains(UseCaseSlot.VIDEO_CAPTURE))
        }
    }

    /**
     * 录像模式下 VideoCapture 缺失时，不能假装能录像
     *
     * 此时方案里不含 VIDEO_CAPTURE，降级到只保预览，而 startRecording 会
     * 提前给出"VideoCapture未初始化"，不会让用户对着无声的错误码发呆。
     */
    @Test
    fun `录像模式缺 VideoCapture 时降级为仅预览`() {
        val plan = useCaseBindingPlan(
            videoMode = true,
            hasImageCapture = true,
            hasVideoCapture = false,
            hasAnalysis = true
        )

        plan.forEach { combination ->
            assertFalse("没有 VideoCapture 就不该出现在组合里", combination.contains(UseCaseSlot.VIDEO_CAPTURE))
        }
        assertTrue("必须保留预览兜底", plan.contains(listOf(UseCaseSlot.PREVIEW)))
    }

    /**
     * 无论什么模式，最后一个候选都是"仅预览"
     *
     * 保证任何机型上至少能看到取景画面，而不是黑屏。
     */
    @Test
    fun `最后一级兜底永远是仅预览`() {
        val cases = listOf(
            useCaseBindingPlan(true, true, true, true),
            useCaseBindingPlan(false, true, true, true),
            useCaseBindingPlan(true, false, false, false),
            useCaseBindingPlan(false, false, false, false)
        )

        cases.forEach { plan ->
            assertEquals("兜底组合必须是仅预览，实际=${plan.last()}", listOf(UseCaseSlot.PREVIEW), plan.last())
        }
    }

    /**
     * 有 ImageAnalysis 时优先尝试带分析的组合（实时滤镜预览依赖它）
     */
    @Test
    fun `有分析用例时优先尝试三用例组合`() {
        val photo = useCaseBindingPlan(false, true, true, true)
        val video = useCaseBindingPlan(true, true, true, true)

        assertTrue(photo.first().contains(UseCaseSlot.IMAGE_ANALYSIS))
        assertTrue(video.first().contains(UseCaseSlot.IMAGE_ANALYSIS))
        assertEquals("首选应是三个用例", 3, photo.first().size)
    }

    /**
     * 没有 ImageAnalysis 时降级为双用例组合
     */
    @Test
    fun `无分析用例时降级为双用例组合`() {
        val photo = useCaseBindingPlan(false, true, true, false)
        val video = useCaseBindingPlan(true, true, true, false)

        assertEquals(listOf(UseCaseSlot.PREVIEW, UseCaseSlot.IMAGE_CAPTURE), photo.first())
        assertEquals(listOf(UseCaseSlot.PREVIEW, UseCaseSlot.VIDEO_CAPTURE), video.first())
    }
}
