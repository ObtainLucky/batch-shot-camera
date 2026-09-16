/**
 * WeatherProvider.kt - 天气获取
 *
 * 使用 Open-Meteo 免费接口（无需 API Key、无需注册），按经纬度查询实时天气，
 * 输出中文描述 + 温度，例如 "阴 21℃"。
 *
 * 为什么不用第三方商业天气 API：
 * 本项目不应强制用户申请 Key 才能用水印；Open-Meteo 免 Key 且返回标准 WMO 天气码，
 * 足够支撑水印上"天气"这一行的精度要求。
 *
 * 网络实现用 HttpURLConnection，不引入 OkHttp/Retrofit 依赖。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.data.watermark

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 天气提供者
 */
@Singleton
class WeatherProvider @Inject constructor() {

    companion object {
        private const val TAG = "WeatherProvider"                      // 日志标签

        /** Open-Meteo 实时天气接口（免 Key） */
        private const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"

        /** 连接/读取超时（毫秒）：拍照链路不能被天气拖住 */
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 4_000

        /**
         * WMO 天气码 -> 中文描述
         *
         * 参考 Open-Meteo 文档的 weather_code 定义。
         */
        private val WEATHER_CODE_CN = mapOf(
            0 to "晴",
            1 to "晴间多云",
            2 to "多云",
            3 to "阴",
            45 to "雾",
            48 to "雾凇",
            51 to "毛毛雨",
            53 to "小雨",
            55 to "中雨",
            56 to "冻毛毛雨",
            57 to "冻雨",
            61 to "小雨",
            63 to "中雨",
            65 to "大雨",
            66 to "冻雨",
            67 to "强冻雨",
            71 to "小雪",
            73 to "中雪",
            75 to "大雪",
            77 to "雪粒",
            80 to "阵雨",
            81 to "强阵雨",
            82 to "暴雨",
            85 to "阵雪",
            86 to "强阵雪",
            95 to "雷阵雨",
            96 to "雷阵雨伴冰雹",
            99 to "强雷阵雨伴冰雹"
        )
    }

    /**
     * 查询实时天气
     *
     * @param latitude 纬度
     * @param longitude 经度
     * @return 形如 "阴 21℃"，失败返回 null（上层会隐藏「天气」行）
     */
    suspend fun getWeather(latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            try {
                val url = buildString {
                    append(ENDPOINT)
                    append("?latitude=").append(String.format(Locale.US, "%.4f", latitude))
                    append("&longitude=").append(String.format(Locale.US, "%.4f", longitude))
                    append("&current=temperature_2m,weather_code")
                    append("&timezone=auto")
                }

                val body = httpGet(url) ?: return@withContext null
                val result = parseWeather(body)
                Log.d(TAG, "getWeather: 天气=$result")
                result
            } catch (e: Exception) {
                Log.w(TAG, "getWeather: 获取失败", e)
                null
            }
        }

    /**
     * 简易 GET 请求
     */
    private fun httpGet(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "httpGet: HTTP ${connection.responseCode}")
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "httpGet: 请求异常", e)
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * 解析 Open-Meteo 返回
     *
     * 返回结构形如：
     * {"current":{"time":"...","temperature_2m":21.0,"weather_code":3}}
     */
    private fun parseWeather(body: String): String? {
        val root = JSONObject(body)

        // 优先读 current 节点，兼容老接口的 current_weather 节点
        val current = root.optJSONObject("current") ?: root.optJSONObject("current_weather")
            ?: return null

        val temperature = if (current.has("temperature_2m")) {
            current.optDouble("temperature_2m")
        } else {
            current.optDouble("temperature")
        }
        if (temperature.isNaN()) return null

        val code = current.optInt("weather_code", -1)
        val description = WEATHER_CODE_CN[code] ?: "未知"

        // "阴 21℃"：整数温度即可，水印不需要小数
        return "$description ${temperature.roundToInt()}℃"
    }
}
