/**
 * LocationProvider.kt - 定位提供者
 *
 * 用系统 LocationManager 获取一次当前位置（无 Google Play 服务依赖）。
 * 优先返回缓存位置，缓存过期或没有缓存时才真正发起一次定位请求。
 *
 * 设计取舍：
 * - 不用 FusedLocationProvider，避免为一次拍照记录引入 GMS 依赖；
 * - 超时短（默认 6 秒），拿不到就返回 null，绝不阻塞拍照；
 * - 权限未授予时直接返回 null，由上层决定隐藏经纬度行。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.data.watermark

import android.os.Looper
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 定位提供者
 *
 * @param context 应用上下文
 */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "LocationProvider"                    // 日志标签

        /**
         * 缓存位置可接受的最大年龄
         *
         * 水印相机的经纬度要"跟着人走"，所以窗口必须短。
         * 早期版本这里给了 5 分钟，导致取到一个点后五分钟内都不再更新，
         * 用户走动时经纬度看起来是"固定"的。
         */
        private const val MAX_CACHE_AGE_MS = 30 * 1000L

        /** 持续定位的最小间隔（毫秒） */
        private const val LIVE_UPDATE_MIN_TIME_MS = 10_000L

        /** 持续定位的最小位移（米） */
        private const val LIVE_UPDATE_MIN_DISTANCE_M = 5f

        /** 单次定位超时（毫秒） */
        private const val LOCATE_TIMEOUT_MS = 6_000L
    }

    /** 系统定位服务 */
    private val locationManager: LocationManager? by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    /** 持续定位监听的持有者（用于停止更新） */
    private var updatesListener: LocationListener? = null

    /**
     * 开始持续定位
     *
     * 注册在 GPS 与网络定位两个 provider 上，谁先给出结果就先用谁的 ——
     * 只订阅 GPS 会在室内长时间没有更新，只订阅网络则精度差。
     *
     * @param onLocation 位置更新回调（主线程）
     * @return 是否成功注册
     */
    @SuppressLint("MissingPermission")                                // 内部已检查权限
    fun startUpdates(
        minTimeMs: Long = LIVE_UPDATE_MIN_TIME_MS,
        minDistanceM: Float = LIVE_UPDATE_MIN_DISTANCE_M,
        onLocation: (Location) -> Unit
    ): Boolean {
        if (!hasPermission()) {
            Log.d(TAG, "startUpdates: 未授予定位权限")
            return false
        }
        val manager = locationManager ?: return false

        stopUpdates()                                                  // 先清掉旧的注册，避免重复

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        if (providers.isEmpty()) {
            Log.w(TAG, "startUpdates: GPS 与网络定位均不可用")
            return false
        }

        val listener = LocationListener { location -> onLocation(location) }
        return try {
            providers.forEach { provider ->
                // 最后一个参数是 Looper。传 null 表示"用**调用线程**的 Looper"，
                // 而这里是在后台协程（DefaultDispatcher）里调的，没有 Looper，
                // 于是必然抛 "Can't create handler inside thread ... that has not
                // called Looper.prepare()"，订阅 100% 失败 —— 持续定位等于从未生效，
                // 水印里的经纬度一直停在 getLastKnownLocation 的那一个点上。
                // 回调本身很轻（只更新坐标与时间戳），走主线程即可。
                manager.requestLocationUpdates(
                    provider, minTimeMs, minDistanceM, listener, Looper.getMainLooper()
                )
            }
            updatesListener = listener
            Log.d(TAG, "startUpdates: 已订阅持续定位 providers=$providers, minTime=${minTimeMs}ms")
            true
        } catch (e: Exception) {
            Log.w(TAG, "startUpdates: 订阅失败", e)
            runCatching { manager.removeUpdates(listener) }
            false
        }
    }

    /**
     * 停止持续定位
     */
    fun stopUpdates() {
        val listener = updatesListener ?: return
        runCatching { locationManager?.removeUpdates(listener) }
        updatesListener = null
        Log.d(TAG, "stopUpdates: 已停止持续定位")
    }

    /**
     * 是否已获得定位权限
     */
    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * 获取当前位置
     *
     * 先取缓存（未过期直接用），否则发起一次请求。
     * 任何失败都会返回 null，不抛异常。
     */
    suspend fun getLocation(): Location? {
        if (!hasPermission()) {
            Log.d(TAG, "getLocation: 未授予定位权限，跳过")
            return null
        }

        val manager = locationManager ?: run {
            Log.w(TAG, "getLocation: 定位服务不可用")
            return null
        }

        // 1. 先看缓存位置
        val cached = getBestCachedLocation(manager)
        if (cached != null && SystemClock.elapsedRealtimeNanos() - cached.elapsedRealtimeNanos <
            MAX_CACHE_AGE_MS * 1_000_000
        ) {
            Log.d(TAG, "getLocation: 使用缓存位置 ${cached.latitude}, ${cached.longitude}")
            return cached
        }

        // 2. 缓存过期或没有缓存，发起一次定位
        val fresh = requestSingleLocation(manager)
        if (fresh != null) {
            Log.d(TAG, "getLocation: 定位成功 ${fresh.latitude}, ${fresh.longitude}")
            return fresh
        }

        // 3. 定位失败时退回过期缓存（比什么都没有强）
        Log.d(TAG, "getLocation: 实时定位失败，回退缓存位置")
        return cached
    }

    /**
     * 在所有可用 provider 中挑一个最新的缓存位置
     */
    @SuppressLint("MissingPermission")                                // 调用前已检查权限
    private fun getBestCachedLocation(manager: LocationManager): Location? {
        return try {
            manager.allProviders
                .mapNotNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
                .maxByOrNull { it.elapsedRealtimeNanos }
        } catch (e: Exception) {
            Log.w(TAG, "getBestCachedLocation: 读取缓存失败", e)
            null
        }
    }

    /**
     * 发起一次定位请求
     *
     * Android 11+ 用 getCurrentLocation；更低版本用 requestSingleUpdate。
     */
    @SuppressLint("MissingPermission")                                // 调用前已检查权限
    private suspend fun requestSingleLocation(manager: LocationManager): Location? {
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            Log.w(TAG, "requestSingleLocation: 没有可用的定位 provider（GPS/网络定位均关闭）")
            return null
        }

        return try {
            withTimeout(LOCATE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val listener = LocationListener { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            // Android 11+：直接拿一个当前位置，系统负责生命周期
                            manager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                                if (continuation.isActive) continuation.resume(location)
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            manager.requestSingleUpdate(provider, listener, null)
                        }
                    } catch (e: SecurityException) {
                        Log.w(TAG, "requestSingleLocation: 权限被拒绝", e)
                        if (continuation.isActive) continuation.resume(null)
                    } catch (e: Exception) {
                        Log.w(TAG, "requestSingleLocation: 请求失败", e)
                        if (continuation.isActive) continuation.resume(null)
                    }

                    continuation.invokeOnCancellation {
                        // 低版本 requestSingleUpdate 必须显式注销，否则监听会泄漏
                        runCatching { manager.removeUpdates(listener) }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.d(TAG, "requestSingleLocation: 定位超时（${LOCATE_TIMEOUT_MS}ms）")
            null
        } catch (e: Exception) {
            Log.w(TAG, "requestSingleLocation: 定位异常", e)
            null
        }
    }
}
