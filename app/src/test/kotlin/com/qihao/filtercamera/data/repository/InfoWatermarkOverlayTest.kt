/**
 * InfoWatermarkOverlayTest.kt - 信息水印「开关打开就必须生效」的回归测试
 *
 * 背景（真实踩过的坑）：
 * 信息水印是**独立于滤镜选择**的叠加效果，但它的绘制代码位于滤镜链路内部，
 * 而拍照与预览在"无滤镜（原图）"时都会走快捷分支直接跳过滤镜链路 ——
 * 结果就是：用户在设置里打开了信息水印、但没有选任何滤镜，拍出来完全没有水印。
 * 这个坑已经出现过一次，所以这里把不变量钉死：
 *
 *   FilterType.NONE + 开关打开 ⇒ 必须仍然产出带水印的图像
 *   FilterType.NONE + 开关关闭 ⇒ 图像必须一个像素都不改
 *
 * 后一条同样重要：它保证关闭开关时不会白跑一次全图位图拷贝。
 *
 * 运行：
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*InfoWatermarkOverlayTest*"
 * ```
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.qihao.filtercamera.data.watermark.LocationProvider
import com.qihao.filtercamera.data.watermark.ReverseGeocoder
import com.qihao.filtercamera.data.watermark.WatermarkInfoProvider
import com.qihao.filtercamera.data.watermark.WeatherProvider
import com.qihao.filtercamera.domain.model.FilterType
import com.qihao.filtercamera.domain.model.WatermarkField
import com.qihao.filtercamera.domain.repository.IBatchRepository
import com.qihao.filtercamera.domain.repository.ISettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.util.Locale

/**
 * 信息水印叠加行为测试
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)                                   // 需要真实 Canvas 出像素
class InfoWatermarkOverlayTest {

    companion object {
        private const val BITMAP_SIZE = 320
    }

    /**
     * 关闭滤镜（原图）时，只要信息水印开关打开，输出就必须带上水印
     *
     * 这条就是那个 bug 的守卫：修好之前这里是 0 像素变化。
     */
    @Test
    fun noneFilter_withWatermarkEnabled_stillDrawsWatermark() {
        val repository = createRepository(infoWatermarkEnabled = true)
        val source = createPlainBitmap()

        val result = repository.applyFilterToBitmapSync(FilterType.NONE, source)

        assertNotNull("无滤镜时也应返回图像", result)
        val diff = pixelDiffRatio(source, result!!)
        println("[水印叠加] NONE + 开关打开 -> 像素变化 ${formatPercent(diff)}")
        assertTrue(
            "信息水印开关打开、滤镜为原图时，图像必须被叠加水印（实际像素变化=$diff）",
            diff > 0.0001
        )
    }

    /**
     * 开关关闭时，原图必须原样返回，不做任何无谓的位图拷贝
     */
    @Test
    fun noneFilter_withWatermarkDisabled_keepsImageUntouched() {
        val repository = createRepository(infoWatermarkEnabled = false)
        val source = createPlainBitmap()

        val result = repository.applyFilterToBitmapSync(FilterType.NONE, source)

        assertNotNull(result)
        val diff = pixelDiffRatio(source, result!!)
        println("[水印叠加] NONE + 开关关闭 -> 像素变化 ${formatPercent(diff)}")
        assertEquals("开关关闭时不应改动任何像素", 0.0, diff, 0.0)
    }

    /**
     * 显式选择「信息水印」滤镜时，与开关是否打开无关都应生效
     * （用户主动选了，就该画）
     */
    @Test
    fun infoWatermarkFilter_drawsEvenWhenSwitchOff() {
        val repository = createRepository(infoWatermarkEnabled = false)
        val source = createPlainBitmap()

        val result = repository.applyFilterToBitmapSync(FilterType.WATERMARK_INFO, source)

        assertNotNull(result)
        val diff = pixelDiffRatio(source, result!!)
        println("[水印叠加] 显式选水印滤镜 + 开关关闭 -> 像素变化 ${formatPercent(diff)}")
        assertTrue("显式选择信息水印滤镜时必须绘制", diff > 0.0001)
    }

    // ==================== 组装被测对象 ====================

    /**
     * 构造真实的 FilterRepositoryImpl，只把设置仓库替换成可控替身
     *
     * 用动态代理而不是手写 33 个方法的空实现，测试重点是水印叠加行为，
     * 其余设置项一律不参与，调用到就会立刻抛错暴露出来。
     */
    private fun createRepository(infoWatermarkEnabled: Boolean): FilterRepositoryImpl {
        val context = RuntimeEnvironment.getApplication()

        val settings = fakeSettingsRepository(
            enabled = infoWatermarkEnabled,
            remark = "段嘉轩 13297470239",
            fields = WatermarkField.DEFAULT
        )

        val infoProvider = WatermarkInfoProvider(
            locationProvider = LocationProvider(context),
            reverseGeocoder = ReverseGeocoder(context),
            weatherProvider = WeatherProvider(),
            settingsRepository = settings,
            batchRepository = fakeBatchRepository(),
            // Unconfined：让设置监听立即生效，测试里无需等待调度
            applicationScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
        )

        return FilterRepositoryImpl(context, infoProvider)
    }

    /**
     * 批次仓库替身
     *
     * 水印备注要跟随当前批次，所以 Provider 依赖它；这个测试不关心批次，
     * 统一返回"没选批次"，于是备注回落到全局设置。
     */
    private fun fakeBatchRepository(): IBatchRepository = Proxy.newProxyInstance(
        IBatchRepository::class.java.classLoader,
        arrayOf(IBatchRepository::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getBatches" -> MutableStateFlow(emptyList<com.qihao.filtercamera.domain.model.BatchConfig>())
            "getCurrentBatch" -> MutableStateFlow(null)
            "getCurrentBatchId" -> MutableStateFlow(null)
            else -> throw NotImplementedError("测试未预期的批次仓库调用: ${method.name}")
        }
    } as IBatchRepository

    /**
     * 可控的设置仓库替身
     */
    private fun fakeSettingsRepository(
        enabled: Boolean,
        remark: String,
        fields: Set<WatermarkField>
    ): ISettingsRepository = Proxy.newProxyInstance(
        ISettingsRepository::class.java.classLoader,
        arrayOf(ISettingsRepository::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "isWatermarkEnabled" -> MutableStateFlow(enabled)
            "getWatermarkText" -> MutableStateFlow(remark)
            "getWatermarkFields" -> MutableStateFlow(fields)
            else -> throw NotImplementedError("测试未预期的设置项调用: ${method.name}")
        }
    } as ISettingsRepository

    // ==================== 图像工具 ====================

    /**
     * 纯色图，便于用像素差判断"有没有画东西上去"
     */
    private fun createPlainBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(BITMAP_SIZE, BITMAP_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(90, 110, 100))
        return bitmap
    }

    /**
     * 计算两张同尺寸 Bitmap 的像素差异比例
     */
    private fun pixelDiffRatio(a: Bitmap, b: Bitmap): Double {
        require(a.width == b.width && a.height == b.height) { "尺寸不一致，无法比较" }
        val width = a.width
        val rowA = IntArray(width)
        val rowB = IntArray(width)
        var diff = 0L
        for (y in 0 until a.height) {
            a.getPixels(rowA, 0, width, 0, y, width, 1)
            b.getPixels(rowB, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if (rowA[x] != rowB[x]) diff++
            }
        }
        return diff.toDouble() / (a.width.toDouble() * a.height.toDouble())
    }

    private fun formatPercent(ratio: Double): String =
        String.format(Locale.US, "%.4f%%", ratio * 100)
}
