/**
 * YuvToNv21ConversionTest.kt - YUV 转 NV21 的字节级校验
 *
 * 背景：
 * `yuv420ToNv21` 里 UV 平面原本是逐字节调用 `ByteBuffer.get(index)`，
 * 在 1200 万像素的照片上有约 300 万次调用，是拍照耗时的主要来源之一。
 * 改成"按行批量读取 + 按下标取"之后，必须保证**输出字节完全不变** ——
 * 一旦 NV21 的 V/U 顺序或行跨距处理错了，照片会整张花掉，而且很难一眼看出来。
 *
 * 所以这里用独立写的参考实现（逐字节版）对拍，覆盖几种典型设备布局：
 * 1. 半平面 YUV + rowStride 带填充（很多设备如此）
 * 2. 半平面 YUV + 紧凑布局
 * 3. 平面式 YUV（pixelStride = 1）
 *
 * 运行：
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*YuvToNv21ConversionTest*"
 * ```
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.data.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * YUV_420_888 -> NV21 转换测试
 *
 * 纯 ByteBuffer 运算，不需要 Android 运行时，普通 JVM 单元测试即可。
 */
class YuvToNv21ConversionTest {

    /**
     * 半平面 YUV（pixelStride=2）+ 带填充的 rowStride，最常见的设备布局
     */
    @Test
    fun semiPlanarWithPadding_matchesByteByByteReference() {
        val layout = Layout(
            width = 8,
            height = 4,
            yRowStride = 10,          // 行尾 2 字节填充
            uvRowStride = 10,         // UV 行 = uvWidth*2 + 填充
            uvPixelStride = 2,
            sharedUvBuffer = true
        )
        assertMatchesReference(layout)
    }

    /**
     * 半平面 YUV + rowStride 恰好等于 width（无填充）
     */
    @Test
    fun semiPlanarWithoutPadding_matchesByteByByteReference() {
        val layout = Layout(
            width = 16,
            height = 6,
            yRowStride = 16,
            uvRowStride = 16,
            uvPixelStride = 2,
            sharedUvBuffer = true
        )
        assertMatchesReference(layout)
    }

    /**
     * 平面式 YUV（pixelStride=1，U/V 各自连续存放）
     *
     * 走的是非批量分支，同样要求字节一致。
     */
    @Test
    fun planar_matchesByteByByteReference() {
        val layout = Layout(
            width = 8,
            height = 4,
            yRowStride = 8,
            uvRowStride = 4,
            uvPixelStride = 1,
            sharedUvBuffer = false
        )
        assertMatchesReference(layout)
    }

    /**
     * 输出长度必须是 width*height*3/2，且 Y 段逐字节等于输入
     */
    @Test
    fun outputKeepsYPlaneUnchanged() {
        val layout = Layout(
            width = 8,
            height = 4,
            yRowStride = 10,
            uvRowStride = 10,
            uvPixelStride = 2,
            sharedUvBuffer = true
        )
        val planes = layout.buildPlanes()
        val actual = ImageFormatConverter.yuv420ToNv21(
            yBuffer = planes.y,
            uBuffer = planes.u,
            vBuffer = planes.v,
            width = layout.width,
            height = layout.height,
            yRowStride = layout.yRowStride,
            uvRowStride = layout.uvRowStride,
            uvPixelStride = layout.uvPixelStride
        )

        assertEquals("NV21 长度应为 width*height*3/2", layout.width * layout.height * 3 / 2, actual.size)

        // Y 段应当与输入 Y 平面逐行一致（去掉行尾填充）
        var index = 0
        for (row in 0 until layout.height) {
            for (col in 0 until layout.width) {
                val expected = planes.y.get(row * layout.yRowStride + col)
                assertEquals("Y 段第 $row 行第 $col 列不一致", expected, actual[index])
                index++
            }
        }
    }

    // ==================== 对拍逻辑 ====================

    /**
     * 用逐字节参考实现算出期望值，与实际实现的结果对比
     */
    private fun assertMatchesReference(layout: Layout) {
        val planes = layout.buildPlanes()

        val expected = referenceNv21(layout, planes)
        val actual = ImageFormatConverter.yuv420ToNv21(
            yBuffer = planes.y,
            uBuffer = planes.u,
            vBuffer = planes.v,
            width = layout.width,
            height = layout.height,
            yRowStride = layout.yRowStride,
            uvRowStride = layout.uvRowStride,
            uvPixelStride = layout.uvPixelStride
        )

        assertArrayEquals(
            "YUV 转 NV21 结果与逐字节参考实现不一致：$layout",
            expected,
            actual
        )
        println("[YUV验证] 字节一致 ✔ $layout")
    }

    /**
     * 参考实现：与改动前的逐字节逻辑**完全一致**的写法
     *
     * 这就是"事实来源"：只要新实现在同样的输入下产出同样的字节，就等于没有改变行为。
     */
    private fun referenceNv21(layout: Layout, planes: Planes): ByteArray {
        val yBuffer = planes.y
        val uBuffer = planes.u
        val vBuffer = planes.v
        val width = layout.width
        val height = layout.height

        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0

        // Y 平面
        for (row in 0 until height) {
            for (col in 0 until width) {
                nv21[pos++] = yBuffer.get(row * layout.yRowStride + col)
            }
        }

        // UV 平面：先 V 后 U
        val uvHeight = height / 2
        val uvWidth = width / 2
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uvIndex = row * layout.uvRowStride + col * layout.uvPixelStride
                nv21[pos++] = vBuffer.get(uvIndex)
                nv21[pos++] = uBuffer.get(uvIndex)
            }
        }

        return nv21
    }

    // ==================== 测试数据构造 ====================

    /**
     * 一种 YUV 平面布局描述
     */
    private data class Layout(
        val width: Int,
        val height: Int,
        val yRowStride: Int,
        val uvRowStride: Int,
        val uvPixelStride: Int,
        /** U/V 是否交错在同一个底层数组（半平面）；false 表示各自独立（平面式） */
        val sharedUvBuffer: Boolean
    ) {
        val uvWidth: Int get() = width / 2
        val uvHeight: Int get() = height / 2

        /**
         * 按布局生成三个平面
         *
         * 数据用固定公式生成，便于定位是哪个下标错了。
         *
         * 半平面布局下要特别注意 V 平面缓冲的语义：真实 ImageProxy 里 U/V 指向同一块内存，
         * 而 V 平面缓冲**以第一个 V 为原点**（索引 0 就是 V），因此这里用 slice() 构造，
         * 而不是 duplicate().position(1) —— 后者索引 0 仍指向 U，会和真实行为不一致。
         */
        fun buildPlanes(): Planes {
            // Y 平面
            val yBytes = ByteArray(yRowStride * height)
            for (row in 0 until height) {
                for (col in 0 until width) {
                    yBytes[row * yRowStride + col] = (row * 31 + col * 7 + 16).toByte()
                }
            }

            if (sharedUvBuffer) {
                // 半平面：U、V 交错在一个数组里（偶数位 U，奇数位 V）
                val shared = ByteArray(uvRowStride * uvHeight)
                for (row in 0 until uvHeight) {
                    for (col in 0 until uvWidth) {
                        val index = row * uvRowStride + col * uvPixelStride
                        shared[index] = (100 + row * 11 + col * 5).toByte()          // U
                        shared[index + 1] = (200 - row * 13 - col * 3).toByte()      // V
                    }
                }
                return Planes(
                    y = ByteBuffer.wrap(yBytes),
                    u = ByteBuffer.wrap(shared),                                  // 原点为第一个 U
                    v = ByteBuffer.wrap(shared).apply { position(1) }.slice()     // 原点为第一个 V
                )
            }

            // 平面式：U、V 各自连续
            val uBytes = ByteArray(uvRowStride * uvHeight)
            val vBytes = ByteArray(uvRowStride * uvHeight)
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val index = row * uvRowStride + col * uvPixelStride
                    uBytes[index] = (100 + row * 11 + col * 5).toByte()
                    vBytes[index] = (200 - row * 13 - col * 3).toByte()
                }
            }
            return Planes(
                y = ByteBuffer.wrap(yBytes),
                u = ByteBuffer.wrap(uBytes),
                v = ByteBuffer.wrap(vBytes)
            )
        }
    }

    /** 三个平面 */
    private data class Planes(val y: ByteBuffer, val u: ByteBuffer, val v: ByteBuffer)
}
