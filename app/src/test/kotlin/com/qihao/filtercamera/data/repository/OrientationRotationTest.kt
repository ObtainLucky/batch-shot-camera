/**
 * OrientationRotationTest.kt - 设备方向角到 Surface 旋转的映射测试
 *
 * 背景：App 之前被 AndroidManifest 里的 screenOrientation="portrait" 锁死在竖屏，
 * 于是"横屏拍照"整件事是坏的 —— 而且不只是界面不能转，拍出来的照片会躺倒，
 * 水印也跟着躺倒（水印是画在这张已经定向好的位图上的）。
 *
 * 解开锁定后必须有人把设备方向告诉 CameraX：它的 ImageCapture / ImageAnalysis /
 * VideoCapture 的 targetRotation 不会自己跟着设备转。这个映射就是那一步，
 * 写反了会让照片旋转 90 度或上下颠倒，且在真机上很容易被误判成"偶发"。
 *
 * 纯计算，普通 JVM 单元测试即可（Surface.ROTATION_* 是编译期常量，会被内联）。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.data.repository

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 屏幕方向映射测试
 */
class OrientationRotationTest {

    /**
     * 竖直正持（0 度）-> 不旋转
     */
    @Test
    fun `竖直正持映射为 ROTATION_0`() {
        assertEquals(Surface.ROTATION_0, CameraRepositoryImpl.orientationToSurfaceRotation(0))
        assertEquals(Surface.ROTATION_0, CameraRepositoryImpl.orientationToSurfaceRotation(20))
    }

    /**
     * 逆时针横持（设备顶部朝右，orientation≈90）-> ROTATION_270
     *
     * 映射是"反"的：设备顺时针转，内容要逆时针补偿。
     */
    @Test
    fun `逆时针横持映射为 ROTATION_270`() {
        assertEquals(Surface.ROTATION_270, CameraRepositoryImpl.orientationToSurfaceRotation(90))
        assertEquals(Surface.ROTATION_270, CameraRepositoryImpl.orientationToSurfaceRotation(46))
    }

    /**
     * 倒置（180 度）-> ROTATION_180
     */
    @Test
    fun `倒置映射为 ROTATION_180`() {
        assertEquals(Surface.ROTATION_180, CameraRepositoryImpl.orientationToSurfaceRotation(180))
        assertEquals(Surface.ROTATION_180, CameraRepositoryImpl.orientationToSurfaceRotation(200))
    }

    /**
     * 顺时针横持（orientation≈270）-> ROTATION_90
     */
    @Test
    fun `顺时针横持映射为 ROTATION_90`() {
        assertEquals(Surface.ROTATION_90, CameraRepositoryImpl.orientationToSurfaceRotation(270))
        assertEquals(Surface.ROTATION_90, CameraRepositoryImpl.orientationToSurfaceRotation(300))
    }

    /**
     * 360 度回绕后仍等价于正持
     */
    @Test
    fun `接近360度时回到 ROTATION_0`() {
        assertEquals(Surface.ROTATION_0, CameraRepositoryImpl.orientationToSurfaceRotation(359))
        assertEquals(Surface.ROTATION_0, CameraRepositoryImpl.orientationToSurfaceRotation(315))
    }

    /**
     * 四个象限边界值逐一核对
     *
     * 边界写错（比如把 45 归到 ROTATION_0）会让照片在斜着拿手机时频繁翻转。
     */
    @Test
    fun `象限边界映射正确`() {
        assertEquals("44 度仍在正持区间", Surface.ROTATION_0, CameraRepositoryImpl.orientationToSurfaceRotation(44))
        assertEquals("45 度进入横持区间", Surface.ROTATION_270, CameraRepositoryImpl.orientationToSurfaceRotation(45))
        assertEquals("134 度仍是 ROTATION_270", Surface.ROTATION_270, CameraRepositoryImpl.orientationToSurfaceRotation(134))
        assertEquals("135 度进入倒置区间", Surface.ROTATION_180, CameraRepositoryImpl.orientationToSurfaceRotation(135))
        assertEquals("224 度仍是 ROTATION_180", Surface.ROTATION_180, CameraRepositoryImpl.orientationToSurfaceRotation(224))
        assertEquals("225 度进入 ROTATION_90", Surface.ROTATION_90, CameraRepositoryImpl.orientationToSurfaceRotation(225))
        assertEquals("314 度仍是 ROTATION_90", Surface.ROTATION_90, CameraRepositoryImpl.orientationToSurfaceRotation(314))
    }

    /**
     * 全角度范围都必须落在合法取值内，且四个方向都能取到
     */
    @Test
    fun `全角度覆盖且取值合法`() {
        val expected = setOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270
        )
        val seen = mutableSetOf<Int>()

        for (orientation in 0 until 360) {
            val rotation = CameraRepositoryImpl.orientationToSurfaceRotation(orientation)
            seen += rotation
            assertEquals(
                "角度 $orientation 映射出了非法的旋转值 $rotation",
                true,
                rotation in expected
            )
        }

        assertEquals("四个方向都应该能被取到", expected, seen)
    }
}
