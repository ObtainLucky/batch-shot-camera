/**
 * ImageFormatConverter.kt - 图像格式转换工具类
 *
 * 提供 YUV_420_888、JPEG 等格式到 Bitmap 的转换功能
 * 统一处理图像旋转、镜像等变换操作
 *
 * 功能：
 * - YUV_420_888 转 NV21 字节数组
 * - NV21 压缩为 JPEG 并解码为 Bitmap
 * - ImageProxy 转 Bitmap（支持 JPEG/YUV 格式）
 * - 图像旋转和镜像处理
 *
 * @author qihao
 * @since 2.0.0
 */
package com.qihao.filtercamera.data.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 图像格式转换工具类
 *
 * 单例对象，提供线程安全的图像转换方法
 */
object ImageFormatConverter {

    private const val TAG = "ImageFormatConverter"                    // 日志标签

    /** UV 平面像素跨距（半平面 YUV_420_888 的常见取值，U/V 交错存放） */
    private const val UV_PIXEL_STRIDE = 2

    /**
     * YUV 转换配置
     *
     * @param quality JPEG 压缩质量（0-100）
     * @param handleRotation 是否处理旋转
     * @param handleMirror 是否处理镜像（前置摄像头）
     */
    data class ConversionConfig(
        val quality: Int = 85,                                        // 默认压缩质量
        val handleRotation: Boolean = true,                           // 默认处理旋转
        val handleMirror: Boolean = false                             // 默认不镜像
    ) {
        companion object {
            /** 预览配置：中等质量，处理旋转 */
            val PREVIEW = ConversionConfig(quality = 85, handleRotation = true)

            /** 拍照配置：高质量，处理旋转 */
            val CAPTURE = ConversionConfig(quality = 95, handleRotation = true)

            /** 缩略图配置：低质量，快速处理 */
            val THUMBNAIL = ConversionConfig(quality = 60, handleRotation = false)
        }
    }

    /**
     * 将 YUV_420_888 格式的 ImageProxy 转换为 NV21 字节数组
     *
     * 正确处理 rowStride 和 pixelStride，支持各种设备
     *
     * @param imageProxy YUV_420_888 格式的图像
     * @return NV21 格式的字节数组
     */
    fun yuvImageProxyToNv21(imageProxy: ImageProxy): ByteArray {
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        return yuv420ToNv21(
            yBuffer = yPlane.buffer,
            uBuffer = uPlane.buffer,
            vBuffer = vPlane.buffer,
            width = imageProxy.width,
            height = imageProxy.height,
            yRowStride = yPlane.rowStride,
            uvRowStride = uPlane.rowStride,
            uvPixelStride = uPlane.pixelStride
        )
    }

    /**
     * YUV_420_888 三个平面转 NV21（纯缓冲区运算，便于单元测试）
     *
     * 与 [yuvImageProxyToNv21] 的区别只是入参形态，逻辑完全一致。
     *
     * @param yBuffer Y 平面
     * @param uBuffer U 平面
     * @param vBuffer V 平面
     * @param width 图像宽度
     * @param height 图像高度
     * @param yRowStride Y 平面行跨距
     * @param uvRowStride UV 平面行跨距
     * @param uvPixelStride UV 平面像素跨距
     * @return NV21 格式的字节数组
     */
    internal fun yuv420ToNv21(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int
    ): ByteArray {
        // 创建 NV21 格式的字节数组（Y + VU 交错）
        val nv21 = ByteArray(width * height * 3 / 2)

        // 复制 Y 平面（考虑 rowStride）
        var pos = 0
        if (yRowStride == width) {
            // rowStride 等于 width，直接复制
            yBuffer.get(nv21, 0, width * height)
            pos = width * height
        } else {
            // rowStride 大于 width，需要逐行复制
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }

        // 复制 UV 平面（交错为 NV21 格式：VUVU...）
        val uvHeight = height / 2
        val uvWidth = width / 2

        // 性能优化：逐字节 ByteBuffer.get(index) 在 1200 万像素上有约 300 万次调用，
        // 每次都是带边界检查的方法调用，是这段转换的主要开销。
        // 改成按行 bulk 读入临时数组后按下标取，输出字节与逐字节读取完全一致。
        if (uvPixelStride == UV_PIXEL_STRIDE) {
            copyUvPlanesInterleavedFast(uBuffer, vBuffer, uvRowStride, uvWidth, uvHeight, nv21, pos)
        } else {
            // 非 2 步长（平面式 I420 等）保留通用实现
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * uvRowStride + col * uvPixelStride
                    // NV21 格式：先 V 后 U
                    nv21[pos++] = vBuffer.get(uvIndex)
                    nv21[pos++] = uBuffer.get(uvIndex)
                }
            }
        }

        return nv21
    }

    /**
     * UV 平面批量交错复制（pixelStride == 2 的常见情况）
     *
     * NV12 的 UV 平面是 U,V,U,V... 顺序，NV21 要求 V,U,V,U...，
     * 因此按行读入后交换相邻两字节写入。
     *
     * 每行只读真正需要的字节数（不含行尾填充），既省一次分配也避免依赖 rowStride。
     * 若某个 buffer 剩余长度不足（罕见的分片布局），该行退化为逐字节读取，
     * 而不是整段结果被静默截断。
     *
     * @param uBuffer U 平面缓冲
     * @param vBuffer V 平面缓冲
     * @param uvRowStride UV 平面行跨距
     * @param uvWidth UV 平面宽度（= 图像宽度 / 2）
     * @param uvHeight UV 平面高度（= 图像高度 / 2）
     * @param out 输出去（NV21）
     * @param outOffset 写入起始下标
     */
    private fun copyUvPlanesInterleavedFast(
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        uvRowStride: Int,
        uvWidth: Int,
        uvHeight: Int,
        out: ByteArray,
        outOffset: Int
    ) {
        var pos = outOffset
        val neededBytes = uvWidth * UV_PIXEL_STRIDE
        val uRow = ByteArray(neededBytes)
        val vRow = ByteArray(neededBytes)

        for (row in 0 until uvHeight) {
            val rowOffset = row * uvRowStride

            val uRead = readRow(uBuffer, rowOffset, uRow, neededBytes)
            val vRead = readRow(vBuffer, rowOffset, vRow, neededBytes)

            if (!uRead || !vRead) {
                // 该行无法批量读，退化为逐字节（与原实现一致）
                for (col in 0 until uvWidth) {
                    val index = rowOffset + col * UV_PIXEL_STRIDE
                    out[pos++] = vBuffer.get(index)
                    out[pos++] = uBuffer.get(index)
                }
                continue
            }

            for (col in 0 until uvWidth) {
                val index = col * UV_PIXEL_STRIDE
                out[pos++] = vRow[index]                                  // 先 V
                out[pos++] = uRow[index]                                  // 后 U
            }
        }
    }

    /**
     * 从 [offset] 起批量读出 [length] 字节
     *
     * @return 数据足够时返回 true；剩余长度不足返回 false（调用方需退化处理）
     */
    private fun readRow(
        buffer: ByteBuffer,
        offset: Int,
        target: ByteArray,
        length: Int
    ): Boolean {
        if (offset < 0 || offset + length > buffer.limit()) return false
        buffer.position(offset)
        buffer.get(target, 0, length)
        return true
    }

    /**
     * 将 NV21 字节数组压缩为 JPEG 并解码为 Bitmap
     *
     * @param nv21 NV21 格式的字节数组
     * @param width 图像宽度
     * @param height 图像高度
     * @param quality JPEG 压缩质量（0-100）
     * @return Bitmap，失败返回 null
     */
    fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int, quality: Int = 85): Bitmap? {
        return try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            ByteArrayOutputStream().use { out ->                      // 自动关闭流
                yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)
                val jpegBytes = out.toByteArray()
                BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "nv21ToBitmap: 转换失败", e)
            null
        }
    }

    /**
     * 将 YUV_420_888 格式的 ImageProxy 转换为 Bitmap
     *
     * 内部调用 yuvImageProxyToNv21 和 nv21ToBitmap
     *
     * @param imageProxy YUV_420_888 格式的图像
     * @param config 转换配置
     * @param isFrontCamera 是否前置摄像头（用于镜像处理）
     * @return Bitmap，失败返回 null
     */
    fun yuvImageProxyToBitmap(
        imageProxy: ImageProxy,
        config: ConversionConfig = ConversionConfig.PREVIEW,
        isFrontCamera: Boolean = false
    ): Bitmap? {
        return try {
            val nv21 = yuvImageProxyToNv21(imageProxy)
            val bitmap = nv21ToBitmap(nv21, imageProxy.width, imageProxy.height, config.quality)
                ?: return null

            // 处理旋转和镜像
            if (config.handleRotation) {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                applyTransformation(bitmap, rotationDegrees, isFrontCamera && config.handleMirror)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "yuvImageProxyToBitmap: 转换失败", e)
            null
        }
    }

    /**
     * 将 JPEG 格式的 ImageProxy 转换为 Bitmap
     *
     * @param imageProxy JPEG 格式的图像
     * @param handleRotation 是否处理旋转
     * @param isFrontCamera 是否前置摄像头
     * @return Bitmap，失败返回 null
     */
    fun jpegImageProxyToBitmap(
        imageProxy: ImageProxy,
        handleRotation: Boolean = true,
        isFrontCamera: Boolean = false
    ): Bitmap? {
        return try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return null

            if (handleRotation) {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                applyTransformation(bitmap, rotationDegrees, isFrontCamera)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "jpegImageProxyToBitmap: 转换失败", e)
            null
        }
    }

    /**
     * 将任意格式的 ImageProxy 转换为 Bitmap
     *
     * 自动检测格式并调用对应的转换方法
     *
     * @param imageProxy 图像代理
     * @param config 转换配置
     * @param isFrontCamera 是否前置摄像头
     * @return Bitmap，失败返回 null
     */
    fun imageProxyToBitmap(
        imageProxy: ImageProxy,
        config: ConversionConfig = ConversionConfig.CAPTURE,
        isFrontCamera: Boolean = false
    ): Bitmap? {
        return when (imageProxy.format) {
            ImageFormat.JPEG -> {
                Log.d(TAG, "imageProxyToBitmap: JPEG 格式 ${imageProxy.width}x${imageProxy.height}")
                jpegImageProxyToBitmap(imageProxy, config.handleRotation, isFrontCamera)
            }
            ImageFormat.YUV_420_888 -> {
                Log.d(TAG, "imageProxyToBitmap: YUV_420_888 格式 ${imageProxy.width}x${imageProxy.height}")
                yuvImageProxyToBitmap(imageProxy, config, isFrontCamera)
            }
            else -> {
                Log.w(TAG, "imageProxyToBitmap: 不支持的格式 ${imageProxy.format}")
                null
            }
        }
    }

    /**
     * 应用图像变换（旋转和镜像）
     *
     * @param bitmap 原始 Bitmap
     * @param rotationDegrees 旋转角度
     * @param mirror 是否镜像
     * @return 变换后的 Bitmap
     */
    fun applyTransformation(bitmap: Bitmap, rotationDegrees: Int, mirror: Boolean): Bitmap {
        if (rotationDegrees == 0 && !mirror) {
            return bitmap                                             // 无需变换
        }

        val matrix = Matrix()
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }
        if (mirror) {
            matrix.postScale(-1f, 1f)                                 // 水平镜像
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
