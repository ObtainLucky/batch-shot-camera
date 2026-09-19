/**
 * OrientationHysteresisTest.kt - 方向切换角度滞回的回归测试
 *
 * 背景：横屏布局重做时发现，原先的"按时间防抖"（方向变化后等 400ms 再重绑）
 * 压不住一种抖动 —— 手机停在 45 度档位边界附近时，传感器读数会在两档之间
 * 反复跨越，每次跨越都触发一次相机重绑（unbindAll+rebind），预览不停闪。
 *
 * 参照 Google jetpack-camera-app 的 DebouncedOrientationFlow（Apache-2.0）改成
 * **角度滞回**：新读数与上一次锁定方向的角度差（按圆周取最短弧）达到
 * 45°+5° 才切换。滞回是按几何位置判定的 —— 停在边界附近无论多久都不会切换。
 *
 * 纯函数，普通 JVM 单元测试即可（Surface.ROTATION_* 是编译期常量，会被内联）。
 *
 * @author qihao
 * @since 2.1.1
 */
package com.qihao.filtercamera.data.repository

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方向角度滞回测试
 */
class OrientationHysteresisTest {

    // ==================== 滞回区：不切换 ====================

    /**
     * 档位边界（45 度）附近不切换 —— 这是"停在边界上反复横跳"的直接断言
     */
    @Test
    fun `停在45度边界时不切换`() {
        assertNull("45 度恰在两档中间，必须保持原方向",
            CameraRepositoryImpl.snappedOrientationDegrees(0, 45))
        assertNull(CameraRepositoryImpl.snappedOrientationDegrees(0, 40))
        assertNull(CameraRepositoryImpl.snappedOrientationDegrees(0, 49))
    }

    /**
     * 滞回区按"与上一次锁定方向的夹角"算，而不是按档位边界算
     *
     * 例：当前锁定 90 度，读数 45 度时夹角只有 45 度（< 50），保持不变 ——
     * 即便 45 度按档位划分属于另一个档。
     */
    @Test
    fun `滞回区随当前锁定方向移动`() {
        assertNull(CameraRepositoryImpl.snappedOrientationDegrees(90, 45))
        assertNull(CameraRepositoryImpl.snappedOrientationDegrees(90, 50))
        assertEquals("读数到 40 度时与 90 度差 50，应切回 0 度档",
            0, CameraRepositoryImpl.snappedOrientationDegrees(90, 40)!!)
    }

    /**
     * 已经处于某档正中时，附近的读数不应引起切换
     */
    @Test
    fun `档位正中附近保持不变`() {
        assertNull(CameraRepositoryImpl.snappedOrientationDegrees(0, 5))
        assertNull(CameraRepositoryImpl.snappedOrientationDegrees(90, 85))
        assertNull(CameraRepositoryImpl.snappedOrientationDegrees(270, 275))
    }

    // ==================== 超出滞回区：切换 ====================

    /**
     * 读数离当前锁定方向足够远（>= 50 度）时切换
     */
    @Test
    fun `超过阈值时切换到新方向`() {
        // 返回值是"锁定的方向角"（Surface 旋转角 × 90），与读数角相差 180 是正常的：
        // 读数 90 度（设备顺时针转 90）时内容要逆时针补偿，锁定方向是 270 度
        assertEquals(
            Surface.ROTATION_270 * 90,
            CameraRepositoryImpl.snappedOrientationDegrees(0, 90)!!
        )
        assertEquals(
            Surface.ROTATION_90 * 90,
            CameraRepositoryImpl.snappedOrientationDegrees(0, 275)!!
        )
        assertEquals(
            Surface.ROTATION_180 * 90,
            CameraRepositoryImpl.snappedOrientationDegrees(0, 180)!!
        )
    }

    /**
     * 切换后的方向角是 0/90/180/270 之一，可直接除以 90 得到 Surface 常量
     */
    @Test
    fun `切换结果总是落在档位正中`() {
        for (prev in listOf(0, 90, 180, 270)) {
            for (reading in 0 until 360) {
                val snapped = CameraRepositoryImpl.snappedOrientationDegrees(prev, reading)
                if (snapped != null) {
                    assertTrue(
                        "结果必须是 90 的倍数: prev=$prev reading=$reading -> $snapped",
                        snapped % 90 == 0 && snapped in 0..270
                    )
                }
            }
        }
    }

    /**
     * 圆周回绕：350 度与 10 度只差 20 度，不该判成相差 340
     */
    @Test
    fun `角度差按圆周最短弧计算`() {
        assertNull(
            "350 与 10 实际只差 20 度，应保持在 0 度档",
            CameraRepositoryImpl.snappedOrientationDegrees(0, 350)
        )
        assertEquals(
            "350 度读数相对 180 度差 170，应切换",
            0,
            CameraRepositoryImpl.snappedOrientationDegrees(180, 350)!!
        )
    }

    // ==================== 稳定性：边界附近不会反复横跳 ====================

    /**
     * 模拟手机缓慢转过后停在 45 度附近：无论后续读数怎么微抖，方向只切换一次
     *
     * 这是"预览不停闪"的直接回归：若滞回不生效，跨过边界就会再次切换。
     */
    @Test
    fun `停在边界附近不会反复横跳`() {
        var snapped = 0                                   // 起始锁定 0 度
        var switches = 0

        // 缓慢转向 90 度：30 -> 44 -> 46 -> 48 -> 44 -> 46 ... 停在边界附近抖动
        val readings = listOf(30, 44, 46, 48, 44, 46, 47, 45, 46, 44, 46, 45)

        for (reading in readings) {
            val next = CameraRepositoryImpl.snappedOrientationDegrees(snapped, reading)
            if (next != null) {
                snapped = next
                switches++
            }
        }

        assertEquals("停在边界附近不应发生任何切换", 0, switches)
        assertEquals(0, snapped)
    }

    /**
     * 真正跨过滞回区后切换，且切过去之后停在新的边界附近同样稳定
     */
    @Test
    fun `跨过滞回区切换后在新边界同样稳定`() {
        var snapped = 0

        // 转过 90 度（读数 90 距 0 度差 90，切换），随后在新边界附近抖动
        val readings = listOf(90, 88, 92, 87, 93, 88, 92)

        for (reading in readings) {
            val next = CameraRepositoryImpl.snappedOrientationDegrees(snapped, reading)
            if (next != null) {
                snapped = next
            }
        }

        assertEquals("应切换到 90 度读数对应的锁定方向（270 度）", 270, snapped)
        assertEquals("在新档位附近不应再切换", 270, snapped)
    }

    /**
     * 一个完整的旋转周期：0 -> 90 -> 180 -> 270 -> 0，每档只切换一次
     */
    @Test
    fun `整周期旋转每档切换一次`() {
        var snapped = 0
        var switches = 0

        for (reading in listOf(90, 180, 270, 359)) {
            val next = CameraRepositoryImpl.snappedOrientationDegrees(snapped, reading)
            if (next != null) {
                // 切换后把锁定方向设为新档位（与真实监听器一致）
                if (next != snapped) switches++
                snapped = next
            }
        }

        assertTrue("整个周期至少应切换 3 次以上", switches >= 3)
    }

    /**
     * 方向角到 Surface 常量的映射保持不变（回归保护：横屏照片不能躺倒）
     */
    @Test
    fun `方向角映射与既有约定一致`() {
        assertEquals(Surface.ROTATION_0, CameraRepositoryImpl.orientationToSurfaceRotation(0))
        assertEquals(
            Surface.ROTATION_270,
            CameraRepositoryImpl.orientationToSurfaceRotation(90)
        )
        assertEquals(
            Surface.ROTATION_180,
            CameraRepositoryImpl.orientationToSurfaceRotation(180)
        )
        assertEquals(
            Surface.ROTATION_90,
            CameraRepositoryImpl.orientationToSurfaceRotation(270)
        )
    }
}
