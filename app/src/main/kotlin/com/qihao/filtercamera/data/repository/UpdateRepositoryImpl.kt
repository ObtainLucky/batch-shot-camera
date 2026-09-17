/**
 * UpdateRepositoryImpl.kt - 检查更新（GitHub Releases）
 *
 * ## 为什么需要缓存与节流
 *
 * 更新通道是 GitHub 的公开 REST 接口，**匿名调用按 IP 限流：每小时 60 次**。
 * 手机在运营商 NAT 后面往往与成千上万用户共用出口 IP，实际可用额度非常紧张，
 * 稍不注意就会 403。所以这里做了三层保护：
 *
 * 1. 不做"打开就查"：只有用户点「检查更新」才发请求
 * 2. 结果落盘缓存：默认 10 分钟内不再发请求，直接回缓存（force=true 可绕过，
 *    但界面会先提示剩余等待时间）
 * 3. 命中限流时把 `X-RateLimit-Reset` 换算成"还需等待多久"如实告诉用户，
 *    而不是笼统报网络错误让人反复重试
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qihao.filtercamera.domain.model.AppUpdateInfo
import com.qihao.filtercamera.domain.model.UpdateCheckError
import com.qihao.filtercamera.domain.repository.IUpdateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Context 扩展属性 - 更新检查缓存（与设置、批次分开存放） */
private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "filter_camera_update"
)

/**
 * 检查更新仓库实现
 */
@Singleton
class UpdateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : IUpdateRepository {

    companion object {
        private const val TAG = "UpdateRepositoryImpl"

        /** 仓库地址（更新通道） */
        private const val OWNER = "ObtainLucky"
        private const val REPO = "batch-shot-camera"
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

        /** 缓存有效期：10 分钟。匿名接口每小时只有 60 次，不能点一次查一次 */
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        /** 连接与读取超时（GitHub 在国内经常需要重试，超时给足一点） */
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000

        private val KEY_LAST_CHECK_AT = longPreferencesKey("last_check_at")
        private val KEY_LATEST_VERSION = stringPreferencesKey("latest_version")
        private val KEY_RELEASE_URL = stringPreferencesKey("release_url")
        private val KEY_PUBLISHED_AT = stringPreferencesKey("published_at")
        private val KEY_NOTES = stringPreferencesKey("notes")
        private val KEY_ASSET_NAME = stringPreferencesKey("asset_name")
        private val KEY_ASSET_URL = stringPreferencesKey("asset_url")
        private val KEY_ASSET_SIZE = longPreferencesKey("asset_size")

        private val json = Json { ignoreUnknownKeys = true }
    }

    override suspend fun lastCheckAt(): Long = withContext(Dispatchers.IO) {
        runCatching { context.updateDataStore.data.first()[KEY_LAST_CHECK_AT] ?: 0L }
            .getOrDefault(0L)
    }

    override suspend fun cachedUpdate(currentVersion: String): AppUpdateInfo? =
        withContext(Dispatchers.IO) {
            val prefs = runCatching { context.updateDataStore.data.first() }.getOrNull()
                ?: return@withContext null
            val version = prefs[KEY_LATEST_VERSION] ?: return@withContext null
            AppUpdateInfo(
                currentVersion = currentVersion,
                latestVersion = version,
                releaseUrl = prefs[KEY_RELEASE_URL].orEmpty(),
                publishedAt = prefs[KEY_PUBLISHED_AT].orEmpty(),
                notes = prefs[KEY_NOTES].orEmpty(),
                assetName = prefs[KEY_ASSET_NAME].orEmpty(),
                assetUrl = prefs[KEY_ASSET_URL].orEmpty(),
                assetSizeBytes = prefs[KEY_ASSET_SIZE] ?: 0L
            )
        }

    override suspend fun checkForUpdate(
        currentVersion: String,
        force: Boolean
    ): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val lastCheck = lastCheckAt()
            val elapsed = System.currentTimeMillis() - lastCheck

            // 未过期就直接回缓存，一个请求都不发
            if (!force && lastCheck > 0 && elapsed < CACHE_TTL_MS) {
                cachedUpdate(currentVersion)?.let { cached ->
                    Log.d(TAG, "checkForUpdate: 命中缓存（${elapsed / 1000}s 前查过）")
                    return@withContext Result.success(cached)
                }
            }

            Log.d(TAG, "checkForUpdate: 请求 $LATEST_RELEASE_API")
            val info = fetchLatestRelease(currentVersion)
            saveCache(info)
            Result.success(info)
        } catch (e: UpdateCheckError) {
            Log.w(TAG, "checkForUpdate: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "checkForUpdate: 失败", e)
            Result.failure(UpdateCheckError.Network(e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * 请求 GitHub 的 latest release
     */
    private fun fetchLatestRelease(currentVersion: String): AppUpdateInfo {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // GitHub 要求带 UA，不带会直接 403
            setRequestProperty("User-Agent", "FilterCamera-Updater")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

        try {
            val code = connection.responseCode
            Log.d(
                TAG,
                "fetchLatestRelease: HTTP $code, 剩余额度=${connection.getHeaderField("X-RateLimit-Remaining")}"
            )

            // 限流：GitHub 用 403/429 表示，并给出重置时间戳
            if (code == 403 || code == 429) {
                val resetSeconds = connection.getHeaderField("X-RateLimit-Reset")
                    ?.toLongOrNull()
                    ?.let { (it * 1000 - System.currentTimeMillis()) / 1000 }
                    ?.coerceAtLeast(1)
                    ?: 60L
                throw UpdateCheckError.RateLimited(resetSeconds)
            }

            if (code == 404) {
                // 仓库还没有发行版
                throw UpdateCheckError.Parse("仓库暂无发行版（HTTP 404）")
            }
            if (code !in 200..299) {
                throw UpdateCheckError.Network("HTTP $code")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = json.parseToJsonElement(body).jsonObject

            val tag = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
            if (tag.isBlank()) throw UpdateCheckError.Parse("缺少 tag_name")

            // 取安装包：优先 arm64 的 apk，没有就用第一个
            val assets = root["assets"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
            val apk = assets
                .map { it.jsonObject }
                .firstOrNull { it["name"]?.jsonPrimitive?.content?.contains("arm64") == true }
                ?: assets.firstOrNull()?.jsonObject

            return AppUpdateInfo(
                currentVersion = currentVersion,
                latestVersion = tag.removePrefix("v").removePrefix("V"),
                releaseUrl = root["html_url"]?.jsonPrimitive?.content.orEmpty(),
                publishedAt = root["published_at"]?.jsonPrimitive?.content.orEmpty(),
                notes = root["body"]?.jsonPrimitive?.content.orEmpty(),
                assetName = apk?.get("name")?.jsonPrimitive?.content.orEmpty(),
                assetUrl = apk?.get("browser_download_url")?.jsonPrimitive?.content.orEmpty(),
                assetSizeBytes = apk?.get("size")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            )
        } catch (e: UpdateCheckError) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw UpdateCheckError.Network("连接 GitHub 超时，请检查网络或代理")
        } catch (e: java.net.UnknownHostException) {
            throw UpdateCheckError.Network("无法解析 api.github.com")
        } catch (e: Exception) {
            throw UpdateCheckError.Network(e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 落盘缓存，供下次直接读取并展示"上次检查时间"
     */
    private suspend fun saveCache(info: AppUpdateInfo) {
        runCatching {
            context.updateDataStore.edit { prefs ->
                prefs[KEY_LAST_CHECK_AT] = System.currentTimeMillis()
                prefs[KEY_LATEST_VERSION] = info.latestVersion
                prefs[KEY_RELEASE_URL] = info.releaseUrl
                prefs[KEY_PUBLISHED_AT] = info.publishedAt
                prefs[KEY_NOTES] = info.notes
                prefs[KEY_ASSET_NAME] = info.assetName
                prefs[KEY_ASSET_URL] = info.assetUrl
                prefs[KEY_ASSET_SIZE] = info.assetSizeBytes
            }
            Unit
        }.onFailure { Log.w(TAG, "saveCache: 写入缓存失败", it) }
    }
}
