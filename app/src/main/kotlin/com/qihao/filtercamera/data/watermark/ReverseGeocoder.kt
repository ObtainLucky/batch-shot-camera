/**
 * ReverseGeocoder.kt - 逆地理编码（经纬度 -> 地址）
 *
 * 用系统 Geocoder 把坐标转成可读地址，例如
 * "重庆市两江新区腾讯云计算数据中心"。
 *
 * 注意：
 * - Geocoder 依赖设备实现（部分机型/无 GMS 设备可能不可用或返回空），
 *   因此失败一律返回 null，由上层隐藏「地址」行，而不是抛异常。
 * - Android 13 起 Geocoder 提供带回调的异步接口，这里优先使用；
 *   低版本走阻塞接口并放到 IO 线程。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.data.watermark

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 逆地理编码器
 *
 * @param context 应用上下文
 */
@Singleton
class ReverseGeocoder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ReverseGeocoder"                      // 日志标签
        private const val MAX_RESULTS = 1                              // 只需要最精确的一条
    }

    /**
     * 坐标转地址
     *
     * @param latitude 纬度
     * @param longitude 经度
     * @return 地址文本，取不到返回 null
     */
    suspend fun reverse(latitude: Double, longitude: Double): String? {
        val geocoder = runCatching { Geocoder(context, Locale.getDefault()) }.getOrNull()
        if (geocoder == null) {
            Log.w(TAG, "reverse: Geocoder 不可用")
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Geocoder.isPresent()) {
            Log.w(TAG, "reverse: 设备未提供地理编码服务")
            return null
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                reverseAsync(geocoder, latitude, longitude)
            } else {
                reverseBlocking(geocoder, latitude, longitude)
            }
        } catch (e: Exception) {
            Log.w(TAG, "reverse: 逆地理编码失败", e)
            null
        }
    }

    /**
     * Android 13+ 异步接口
     */
    private suspend fun reverseAsync(
        geocoder: Geocoder,
        latitude: Double,
        longitude: Double
    ): String? = suspendCancellableCoroutine { continuation ->
        geocoder.getFromLocation(latitude, longitude, MAX_RESULTS) { addresses ->
            val address = addresses?.firstOrNull()
            if (continuation.isActive) {
                continuation.resume(address?.toDisplayText())
            }
        }
    }

    /**
     * Android 13 以下阻塞接口（放到 IO 线程）
     */
    @Suppress("DEPRECATION")
    private suspend fun reverseBlocking(
        geocoder: Geocoder,
        latitude: Double,
        longitude: Double
    ): String? = withContext(Dispatchers.IO) {
        val address = runCatching {
            geocoder.getFromLocation(latitude, longitude, MAX_RESULTS)?.firstOrNull()
        }.getOrNull()
        address?.toDisplayText()
    }

    /**
     * 提取地址显示文本
     *
     * 优先整行地址（getAddressLine(0)，最接近"重庆市两江新区腾讯云计算数据中心"这种形式），
     * 整行为空时用省市街道拼一个兜底。
     */
    private fun Address.toDisplayText(): String? {
        val full = getAddressLine(0)?.takeIf { it.isNotBlank() }
        if (full != null) return full

        val fallback = listOfNotNull(
            adminArea?.takeIf { it.isNotBlank() },
            locality?.takeIf { it.isNotBlank() && it != adminArea },
            subLocality?.takeIf { it.isNotBlank() },
            thoroughfare?.takeIf { it.isNotBlank() }
        ).joinToString("")
        return fallback.ifBlank { null }
    }
}
