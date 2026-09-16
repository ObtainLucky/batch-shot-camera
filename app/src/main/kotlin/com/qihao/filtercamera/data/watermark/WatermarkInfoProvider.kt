/**
 * WatermarkInfoProvider.kt - 信息水印数据聚合
 *
 * 把信息水印需要的各项数据收敛到一处，供渲染时同步取用：
 * - 经纬度（定位）
 * - 地址（逆地理编码）
 * - 天气（网络接口）
 * - 备注（设置页填写）
 *
 * 关键设计：渲染发生在预览帧循环里（最高 30fps），**绝不能同步等待网络或定位**。
 * 因此这里维护一份内存快照，对外只提供同步读取；数据的拉取在后台按需进行。
 * 拉取策略：
 * - 启动后立即拉一次
 * - 还没有定位数据时，每 15 秒重试一次（比如用户刚授权定位）
 * - 已有数据后，每 5 分钟刷新一次
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.data.watermark

import android.os.SystemClock
import android.util.Log
import com.qihao.filter.watermark.WatermarkRenderer
import com.qihao.filtercamera.di.ApplicationScope
import com.qihao.filtercamera.domain.model.WatermarkField
import com.qihao.filtercamera.domain.repository.IBatchRepository
import com.qihao.filtercamera.domain.repository.ISettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 信息水印数据提供者
 *
 * @param locationProvider 定位
 * @param reverseGeocoder 逆地理编码
 * @param weatherProvider 天气
 * @param settingsRepository 设置仓库（备注文字、信息水印开关）
 * @param applicationScope 应用级协程作用域
 */
@Singleton
class WatermarkInfoProvider @Inject constructor(
    private val locationProvider: LocationProvider,
    private val reverseGeocoder: ReverseGeocoder,
    private val weatherProvider: WeatherProvider,
    private val settingsRepository: ISettingsRepository,
    private val batchRepository: IBatchRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) {

    companion object {
        private const val TAG = "WatermarkInfoProvider"                // 日志标签

        /** 尚未取到定位时的重试间隔（用户可能刚授权） */
        private const val RETRY_WHEN_EMPTY_MS = 15_000L

        /** 已有定位数据后的兜底轮询间隔（持续定位可用时不会走到这里） */
        private const val REFRESH_INTERVAL_MS = 60 * 1000L

        /** 地址/天气的最大缓存时长（超过就重新请求） */
        private const val EXTRAS_MAX_AGE_MS = 2 * 60 * 1000L

        /** 位移超过该距离（米）就重新请求地址/天气 */
        private const val EXTRAS_DISTANCE_THRESHOLD_M = 200f

        /** 后台定时刷新周期 */
        private const val BACKGROUND_REFRESH_MS = 5 * 60 * 1000L
    }

    /** 位置/地址/天气快照（不含备注与时间戳，那两项在读取时即时拼） */
    @Volatile
    private var baseSnapshot: WatermarkRenderer.WatermarkData = WatermarkRenderer.WatermarkData()

    /** 上次成功拉取的时间（elapsedRealtime） */
    @Volatile
    private var lastRefreshAt: Long = 0L

    /** 是否已订阅持续定位 */
    @Volatile
    private var liveActive: Boolean = false

    /** 最近一次做过逆地理/天气的坐标锚点 */
    @Volatile
    private var extrasAnchor: android.location.Location? = null

    /** 最近一次拉取地址/天气的时间 */
    @Volatile
    private var extrasAt: Long = 0L

    /** 是否正在拉取，避免并发重复请求 */
    private val refreshing = AtomicBoolean(false)

    /** 设置页的「信息水印」开关 */
    @Volatile
    private var infoWatermarkEnabled: Boolean = false

    /** 设置页填写的备注文字 */
    @Volatile
    private var remark: String = ""

    /** 水印大小倍率（1.0 为基准） */
    @Volatile
    private var sizeScale: Float = WatermarkRenderer.DEFAULT_SIZE_SCALE

    /**
     * 当前批次自己的备注
     *
     * 现场每个批次/每轮的联系人、项目名往往不同，所以批次备注优先于全局备注；
     * 批次备注为空时回落到设置页里的全局备注。
     */
    @Volatile
    private var batchNote: String = ""

    /**
     * 设置页「位置信息」开关
     *
     * 这个开关此前没有任何消费者：用户关掉它，水印照样会去定位并把经纬度写进照片。
     * 从隐私角度必须堵上 —— 关掉时既不订阅定位，也不在快照里暴露坐标。
     */
    @Volatile
    private var locationEnabled: Boolean = false

    /**
     * 启用的信息水印字段
     *
     * 未启用的字段在组装快照时被清空，渲染层因此不用关心"开关"这件事 ——
     * 对它来说"没启用"和"没取到数据"是同一种情况：整行不画。
     */
    @Volatile
    private var enabledFields: Set<WatermarkField> = WatermarkField.DEFAULT

    init {
        observeSettings()
        applicationScope.launch {
            while (isActive) {
                refreshNow(force = true)
                delay(BACKGROUND_REFRESH_MS)
            }
        }
        Log.d(TAG, "init: 信息水印数据提供者已启动")
    }

    // ==================== 对外读取（同步，供渲染链路使用） ====================

    /**
     * 取当前快照
     *
     * 同步返回，不会阻塞；缺数据的字段为 null，渲染时对应行自动跳过。
     * 这里同时按设置里的字段开关做过滤：关掉的字段一律清空。
     */
    fun snapshot(): WatermarkRenderer.WatermarkData {
        val base = baseSnapshot
        // 位置开关关掉时，坐标/地址/天气一律不暴露（即使快照里还留着上一次的值）
        val geoAllowed = locationEnabled
        return base.copy(
            longitude = base.longitude.takeIf { geoAllowed && WatermarkField.LONGITUDE in enabledFields },
            latitude = base.latitude.takeIf { geoAllowed && WatermarkField.LATITUDE in enabledFields },
            address = base.address.takeIf { geoAllowed && WatermarkField.ADDRESS in enabledFields },
            weather = base.weather.takeIf { geoAllowed && WatermarkField.WEATHER in enabledFields },
            // 批次备注优先，为空时用全局备注
            customText = batchNote.ifBlank { remark }
                .takeIf { WatermarkField.REMARK in enabledFields } ?: "",
            includeTimestamp = WatermarkField.TIME in enabledFields,
            sizeScale = sizeScale
        )
    }

    /**
     * 设置页的「信息水印」开关是否打开
     */
    fun isInfoWatermarkEnabled(): Boolean = infoWatermarkEnabled

    // ==================== 持续定位 ====================

    /**
     * 开始持续定位（相机页可见时调用）
     *
     * 这是"经纬度跟着人走"的关键：一次性定位取到一个点后就不再变化，
     * 用户走动时坐标看起来是固定的。订阅式更新则每移动一定距离/间隔就推送新坐标。
     */
    fun startLiveUpdates() {
        if (liveActive) return
        if (!locationEnabled) {
            // 设置里没开位置信息：不订阅定位（隐私 + 省电）
            Log.d(TAG, "startLiveUpdates: 位置信息开关关闭，跳过订阅")
            return
        }

        val started = locationProvider.startUpdates { location -> onLiveLocation(location) }
        liveActive = started
        Log.d(TAG, "startLiveUpdates: 持续定位已启动=$started")

        if (!started) {
            // 没有定位权限或 provider 不可用：退回按需轮询
            refreshNow(force = true)
        }
    }

    /**
     * 停止持续定位（离开相机页时调用，避免后台耗电）
     */
    fun stopLiveUpdates() {
        if (!liveActive) {
            locationProvider.stopUpdates()                             // 兜底清理
            return
        }
        liveActive = false
        locationProvider.stopUpdates()
        Log.d(TAG, "stopLiveUpdates: 持续定位已停止")
    }

    /**
     * 收到一次实时定位
     *
     * 经纬度立即更新（纯内存操作，代价可忽略）；
     * 地址与天气要发网络请求，所以按"位移够大或缓存过期"才重新拉。
     */
    private fun onLiveLocation(location: android.location.Location) {
        baseSnapshot = baseSnapshot.copy(
            longitude = location.longitude,
            latitude = location.latitude
        )
        lastRefreshAt = SystemClock.elapsedRealtime()

        if (needsExtrasRefresh(location)) {
            applicationScope.launch { fetchExtras(location) }
        }
    }

    /**
     * 是否需要重新拉取地址与天气
     */
    private fun needsExtrasRefresh(location: android.location.Location): Boolean {
        val anchor = extrasAnchor ?: return true
        if (SystemClock.elapsedRealtime() - extrasAt > EXTRAS_MAX_AGE_MS) return true
        return anchor.distanceTo(location) > EXTRAS_DISTANCE_THRESHOLD_M
    }

    // ==================== 数据拉取 ====================

    /**
     * 按需刷新
     *
     * 渲染链路每帧都会调用，因此这里必须先做廉价判断再决定是否起协程：
     * 还没定位就 15 秒重试一次，已有数据就 5 分钟刷一次。
     */
    fun refreshIfStale() {
        if (liveActive) return                                        // 持续定位已在推送，无需轮询
        val elapsed = SystemClock.elapsedRealtime() - lastRefreshAt
        val interval = if (hasLocationData()) REFRESH_INTERVAL_MS else RETRY_WHEN_EMPTY_MS
        if (elapsed >= interval) {
            refreshNow()
        }
    }

    /**
     * 立即刷新（force=false 时仍受时间窗口限制）
     */
    fun refreshNow(force: Boolean = false) {
        if (!force) {
            val elapsed = SystemClock.elapsedRealtime() - lastRefreshAt
            val interval = if (hasLocationData()) REFRESH_INTERVAL_MS else RETRY_WHEN_EMPTY_MS
            if (elapsed < interval) return
        }
        if (!refreshing.compareAndSet(false, true)) return                 // 已有请求在跑

        applicationScope.launch {
            try {
                fetchAll()
            } catch (e: Exception) {
                Log.w(TAG, "refreshNow: 拉取异常", e)
            } finally {
                lastRefreshAt = SystemClock.elapsedRealtime()
                refreshing.set(false)
            }
        }
    }

    /**
     * 拉取定位 -> 地址 -> 天气
     */
    private suspend fun fetchAll() {
        if (!locationEnabled) {
            Log.d(TAG, "fetchAll: 位置信息开关关闭，跳过定位与天气请求")
            return
        }
        val location = locationProvider.getLocation()
        if (location == null) {
            Log.d(TAG, "fetchAll: 未取得定位，经纬度/地址/天气三行将不显示")
            baseSnapshot = baseSnapshot.copy(
                longitude = null,
                latitude = null,
                address = null,
                weather = null
            )
            return
        }

        fetchExtras(location)
    }

    /**
     * 拉取地址与天气并写入快照
     */
    private suspend fun fetchExtras(location: android.location.Location) {
        val address = reverseGeocoder.reverse(location.latitude, location.longitude)
        val weather = weatherProvider.getWeather(location.latitude, location.longitude)

        extrasAnchor = location
        extrasAt = SystemClock.elapsedRealtime()
        baseSnapshot = baseSnapshot.copy(
            longitude = location.longitude,
            latitude = location.latitude,
            address = address,
            weather = weather
        )
        Log.d(
            TAG,
            "fetchExtras: 已更新 定位=(${location.latitude}, ${location.longitude}), " +
                "地址=${address ?: "无"}, 天气=${weather ?: "无"}"
        )
    }

    /**
     * 是否已有定位数据
     */
    private fun hasLocationData(): Boolean = baseSnapshot.latitude != null

    // ==================== 设置监听 ====================

    /**
     * 监听设置页的「信息水印」开关与备注文字
     *
     * 这正是原先断掉的那条线：设置里的水印开关和文字此前没有任何消费者，
     * 用户填了也不生效。这里把它们接进实际渲染数据。
     */
    private fun observeSettings() {
        applicationScope.launch {
            settingsRepository.isWatermarkEnabled()
                .catch { Log.w(TAG, "observeSettings: 读取水印开关失败", it) }
                .collect { enabled ->
                    infoWatermarkEnabled = enabled
                    Log.d(TAG, "observeSettings: 信息水印开关=$enabled")
                }
        }

        applicationScope.launch {
            settingsRepository.getWatermarkText()
                .catch { Log.w(TAG, "observeSettings: 读取备注文字失败", it) }
                .collect { text ->
                    remark = text.trim()
                    Log.d(TAG, "observeSettings: 备注已更新 length=${remark.length}")
                }
        }

        applicationScope.launch {
            settingsRepository.isLocationEnabled()
                .catch { Log.w(TAG, "observeSettings: 读取位置开关失败", it) }
                .collect { enabled ->
                    val wasEnabled = locationEnabled
                    locationEnabled = enabled
                    Log.d(TAG, "observeSettings: 位置信息开关=$enabled")
                    if (wasEnabled != enabled) {
                        if (enabled) startLiveUpdates() else stopLiveUpdates()
                    }
                }
        }

        applicationScope.launch {
            batchRepository.currentBatch
                .catch { Log.w(TAG, "observeSettings: 读取当前批次失败", it) }
                .collect { batch ->
                    batchNote = batch?.note?.trim().orEmpty()
                    Log.d(
                        TAG,
                        "observeSettings: 批次备注=" +
                            (batchNote.ifBlank { "(空，使用全局备注)" })
                    )
                }
        }

        applicationScope.launch {
            settingsRepository.getWatermarkSizeScale()
                .catch { Log.w(TAG, "observeSettings: 读取水印大小失败", it) }
                .collect { scale ->
                    sizeScale = scale
                    Log.d(TAG, "observeSettings: 水印大小倍率=$scale")
                }
        }

        applicationScope.launch {
            settingsRepository.getWatermarkFields()
                .catch { Log.w(TAG, "observeSettings: 读取水印字段开关失败", it) }
                .collect { fields ->
                    enabledFields = fields
                    Log.d(
                        TAG,
                        "observeSettings: 启用字段=" +
                            fields.joinToString(",") { it.id }
                    )
                }
        }
    }
}
