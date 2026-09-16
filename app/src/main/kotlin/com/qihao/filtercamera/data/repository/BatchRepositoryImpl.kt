/**
 * BatchRepositoryImpl.kt - 批次仓库实现
 *
 * 使用 DataStore(Preferences) + kotlinx.serialization(JSON) 持久化批次配置。
 *
 * 选型说明：
 * 批次数量少（几十个），不需要 Room；把 List<BatchConfig> 序列化成 JSON
 * 字符串存进 Preferences 即可，轻量且无数据库迁移负担。
 *
 * 存储结构（DataStore 文件名 filter_camera_batches）：
 * - "batches_json"     -> List<BatchConfig> 的 JSON
 * - "current_batch_id" -> 当前选中批次 id
 *
 * 并发安全：
 * DataStore 的 edit 是串行化的读-改-写事务，
 * 因此「读列表 -> 改 counter -> 写回」不会丢更新。
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
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qihao.filtercamera.di.IoDispatcher
import com.qihao.filtercamera.domain.model.BatchConfig
import com.qihao.filtercamera.domain.repository.IBatchRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Context 扩展属性 - 批次 DataStore 实例
 *
 * 与设置项（filter_camera_settings）分开存放，
 * 避免批次列表 JSON 与设置项互相干扰。
 */
private val Context.batchDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "filter_camera_batches"
)

/**
 * 批次仓库实现类
 *
 * @param context 应用上下文
 * @param ioDispatcher IO 调度器（DataStore 读写走 IO）
 */
@Singleton
class BatchRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : IBatchRepository {

    companion object {
        private const val TAG = "BatchRepositoryImpl"                 // 日志标签

        /** 批次列表 JSON 键 */
        private val KEY_BATCHES_JSON = stringPreferencesKey("batches_json")

        /** 当前选中批次 id 键 */
        private val KEY_CURRENT_BATCH_ID = stringPreferencesKey("current_batch_id")
    }

    /** DataStore 实例 */
    private val dataStore = context.batchDataStore

    /**
     * JSON 编解码器
     *
     * ignoreUnknownKeys：老版本写入的字段被移除后仍能反序列化
     * encodeDefaults：默认值也写入，方便人工排查 / 后续迁移
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** List<BatchConfig> 序列化器（显式声明，避免依赖 reified 扩展的版本差异） */
    private val listSerializer = ListSerializer(BatchConfig.serializer())

    // ==================== 读取 ====================

    /**
     * 全部批次列表
     */
    override val batches: Flow<List<BatchConfig>> = dataStore.data
        .catch { exception ->
            Log.e(TAG, "batches: 读取失败", exception)
            if (exception is IOException) {
                emit(emptyPreferences())                               // 磁盘读失败时降级为空列表
            } else {
                throw exception
            }
        }
        .map { preferences -> decodeBatches(preferences[KEY_BATCHES_JSON]) }

    /**
     * 当前选中批次
     */
    override val currentBatch: Flow<BatchConfig?> = dataStore.data
        .catch { exception ->
            Log.e(TAG, "currentBatch: 读取失败", exception)
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val id = preferences[KEY_CURRENT_BATCH_ID]
            if (id.isNullOrBlank()) {
                null                                                    // 未选择批次
            } else {
                decodeBatches(preferences[KEY_BATCHES_JSON]).firstOrNull { it.id == id }
            }
        }

    /**
     * 当前选中批次 id
     */
    override val currentBatchId: Flow<String?> = dataStore.data
        .catch { exception ->
            Log.e(TAG, "currentBatchId: 读取失败", exception)
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[KEY_CURRENT_BATCH_ID]?.takeIf { it.isNotBlank() } }

    /**
     * 按 id 读取单个批次
     */
    override suspend fun getBatch(id: String): BatchConfig? = withContext(ioDispatcher) {
        try {
            val preferences = dataStore.data.first()
            decodeBatches(preferences[KEY_BATCHES_JSON]).firstOrNull { it.id == id }
        } catch (e: Exception) {
            Log.e(TAG, "getBatch: 读取失败 id=$id", e)
            null
        }
    }

    // ==================== 写入 ====================

    /**
     * 新增批次
     *
     * 若同 id 已存在则视为失败，避免重复插入。
     */
    override suspend fun addBatch(batch: BatchConfig): Result<Unit> = runCatching<Unit> {
        // 显式 runCatching<Unit>：块内最后一句 Log.d 返回 Int，
        // 不锁定类型会让整个表达式变成 Result<Int>
        val normalized = batch.normalized()
        withContext(ioDispatcher) {
            var duplicated = false
            dataStore.edit { preferences ->
                val list = decodeBatches(preferences[KEY_BATCHES_JSON])
                if (list.any { it.id == normalized.id }) {
                    duplicated = true
                    return@edit
                }
                preferences[KEY_BATCHES_JSON] = encodeBatches(list + normalized)
            }
            if (duplicated) throw IllegalStateException("批次已存在: ${normalized.id}")
            Log.d(TAG, "addBatch: 新增成功 id=${normalized.id}, name=${normalized.name}")
        }
    }

    /**
     * 更新批次
     *
     * 按 id 覆盖；id 不存在时返回失败，防止静默丢失用户编辑。
     * 注意：counter 以传入的 batch 为准，调用方不应随意改动它。
     */
    override suspend fun updateBatch(batch: BatchConfig): Result<Unit> = runCatching<Unit> {
        val normalized = batch.normalized()
        withContext(ioDispatcher) {
            var found = false
            dataStore.edit { preferences ->
                val list = decodeBatches(preferences[KEY_BATCHES_JSON])
                val index = list.indexOfFirst { it.id == normalized.id }
                if (index < 0) {
                    return@edit
                }
                found = true
                val updated = list.toMutableList().apply { this[index] = normalized }
                preferences[KEY_BATCHES_JSON] = encodeBatches(updated)
            }
            if (!found) throw IllegalStateException("批次不存在: ${normalized.id}")
            Log.d(TAG, "updateBatch: 更新成功 id=${normalized.id}")
        }
    }

    /**
     * 删除批次
     *
     * 若删除的是当前选中批次，一并清空选中状态（回落默认命名）。
     * 已拍摄的照片不受影响，它们已经落在对应目录里。
     */
    override suspend fun deleteBatch(id: String): Result<Unit> = runCatching<Unit> {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                val list = decodeBatches(preferences[KEY_BATCHES_JSON])
                val remaining = list.filterNot { it.id == id }
                preferences[KEY_BATCHES_JSON] = encodeBatches(remaining)

                // 删掉的正是当前选中批次 -> 清空选中，避免 currentBatch 长期为 null 却留着脏 id
                if (preferences[KEY_CURRENT_BATCH_ID] == id) {
                    preferences.remove(KEY_CURRENT_BATCH_ID)
                }
            }
            Log.d(TAG, "deleteBatch: 删除成功 id=$id")
        }
    }

    /**
     * 选中批次
     */
    override suspend fun selectBatch(id: String): Result<Unit> = runCatching<Unit> {
        withContext(ioDispatcher) {
            var found = false
            dataStore.edit { preferences ->
                val list = decodeBatches(preferences[KEY_BATCHES_JSON])
                if (list.none { it.id == id }) {
                    return@edit
                }
                found = true
                preferences[KEY_CURRENT_BATCH_ID] = id
            }
            if (!found) throw IllegalStateException("批次不存在: $id")
            Log.d(TAG, "selectBatch: 选中批次 id=$id")
        }
    }

    /**
     * 取消选中批次
     */
    override suspend fun clearSelection(): Result<Unit> = runCatching<Unit> {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                preferences.remove(KEY_CURRENT_BATCH_ID)
            }
            Log.d(TAG, "clearSelection: 已取消批次选择")
        }
    }

    /**
     * 一张拍完后的计数推进
     *
     * 在 DataStore 事务内读-改-写：既保证并发拍照不丢计数，
     * 也让"名字拍完自动进入下一轮"与计数在同一次写入中完成。
     */
    override suspend fun advanceAfterShot(id: String): Result<Unit> = runCatching<Unit> {
        withContext(ioDispatcher) {
            var found = false
            dataStore.edit { preferences ->
                val list = decodeBatches(preferences[KEY_BATCHES_JSON])
                val index = list.indexOfFirst { it.id == id }
                if (index < 0) {
                    return@edit
                }
                found = true
                val target = list[index]
                val advanced = target.advancedAfterShot()          // 内含"自动进入下一轮"
                val updated = list.toMutableList().apply { this[index] = advanced }
                preferences[KEY_BATCHES_JSON] = encodeBatches(updated)
                if (advanced.round != target.round) {
                    Log.d(TAG, "advanceAfterShot: 批次 $id 已自动进入第 ${advanced.round} 轮")
                }
            }
            if (!found) throw IllegalStateException("批次不存在: $id")
            Log.d(TAG, "advanceAfterShot: 批次 $id 计数已推进")
        }
    }

    /**
     * 重置已拍张数
     */
    override suspend fun resetCounter(id: String): Result<Unit> = runCatching<Unit> {
        withContext(ioDispatcher) {
            var found = false
            dataStore.edit { preferences ->
                val list = decodeBatches(preferences[KEY_BATCHES_JSON])
                val index = list.indexOfFirst { it.id == id }
                if (index < 0) {
                    return@edit
                }
                found = true
                val updated = list.toMutableList().apply {
                    this[index] = list[index].copy(counter = 0)
                }
                preferences[KEY_BATCHES_JSON] = encodeBatches(updated)
            }
            if (!found) throw IllegalStateException("批次不存在: $id")
            Log.d(TAG, "resetCounter: 批次 $id 计数已重置")
        }
    }

    /**
     * 开始新一轮
     */
    override suspend fun startNewRound(id: String): Result<Unit> = runCatching<Unit> {
        withContext(ioDispatcher) {
            var found = false
            dataStore.edit { preferences ->
                val list = decodeBatches(preferences[KEY_BATCHES_JSON])
                val index = list.indexOfFirst { it.id == id }
                if (index < 0) {
                    return@edit
                }
                found = true
                val target = list[index]
                val updated = list.toMutableList().apply { this[index] = target.withNewRound() }
                preferences[KEY_BATCHES_JSON] = encodeBatches(updated)
                Log.d(
                    TAG,
                    "startNewRound: 批次 $id 进入第 ${target.round + 1} 轮，" +
                        "下一张=${target.withNewRound().nextFileName()}"
                )
            }
            if (!found) throw IllegalStateException("批次不存在: $id")
        }
    }

    /**
     * 把名字指针指到指定下标
     */
    override suspend fun setNamePointer(id: String, index: Int): Result<Unit> = runCatching<Unit> {
        withContext(ioDispatcher) {
            var found = false
            dataStore.edit { preferences ->
                val list = decodeBatches(preferences[KEY_BATCHES_JSON])
                val pos = list.indexOfFirst { it.id == id }
                if (pos < 0) {
                    return@edit
                }
                found = true
                val target = list[pos]
                val updated = list.toMutableList().apply {
                    this[pos] = target.withNamePointer(index)
                }
                preferences[KEY_BATCHES_JSON] = encodeBatches(updated)
                Log.d(TAG, "setNamePointer: 批次 $id 指针 -> ${updated[pos].counter}")
            }
            if (!found) throw IllegalStateException("批次不存在: $id")
        }
    }

    /**
     * 标记某一项需要重拍
     */
    override suspend fun markForReshoot(id: String, index: Int): Result<Unit> = runCatching<Unit> {
        withContext(ioDispatcher) {
            var found = false
            dataStore.edit { preferences ->
                val list = decodeBatches(preferences[KEY_BATCHES_JSON])
                val pos = list.indexOfFirst { it.id == id }
                if (pos < 0) {
                    return@edit
                }
                found = true
                val target = list[pos]
                val updated = list.toMutableList().apply {
                    this[pos] = target.withReshoot(index)
                }
                preferences[KEY_BATCHES_JSON] = encodeBatches(updated)
                Log.d(TAG, "markForReshoot: 批次 $id 第 $index 项标记为重拍")
            }
            if (!found) throw IllegalStateException("批次不存在: $id")
        }
    }

    /**
     * 作废上一张：名字序号回退一位
     */
    override suspend fun decrementCounter(id: String): Result<Unit> = runCatching<Unit> {
        withContext(ioDispatcher) {
            var found = false
            dataStore.edit { preferences ->
                val list = decodeBatches(preferences[KEY_BATCHES_JSON])
                val index = list.indexOfFirst { it.id == id }
                if (index < 0) {
                    return@edit
                }
                found = true
                val target = list[index]
                val updated = list.toMutableList().apply {
                    this[index] = target.withCounterDecremented()
                }
                preferences[KEY_BATCHES_JSON] = encodeBatches(updated)
                Log.d(TAG, "decrementCounter: 批次 $id 回退一位 counter=${target.counter - 1}")
            }
            if (!found) throw IllegalStateException("批次不存在: $id")
        }
    }

    // ==================== JSON 编解码 ====================

    /**
     * 解码批次列表
     *
     * JSON 损坏时返回空列表而不是抛异常 —— 批次数据坏掉不应该让相机页面打不开。
     */
    private fun decodeBatches(raw: String?): List<BatchConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(listSerializer, raw)
        } catch (e: Exception) {
            Log.e(TAG, "decodeBatches: JSON 解析失败，降级为空列表", e)
            emptyList()
        }
    }

    /**
     * 编码批次列表
     */
    private fun encodeBatches(list: List<BatchConfig>): String =
        json.encodeToString(listSerializer, list)
}
