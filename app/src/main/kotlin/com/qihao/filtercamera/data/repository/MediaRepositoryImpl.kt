/**
 * MediaRepositoryImpl.kt - 媒体仓库实现
 *
 * 使用MediaStore API实现媒体文件存储
 * 适配Android 10+ Scoped Storage
 *
 * @author qihao
 * @since 2.0.0
 */
package com.qihao.filtercamera.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import com.qihao.filtercamera.di.ApplicationScope
import com.qihao.filtercamera.domain.model.BatchConfig
import com.qihao.filtercamera.domain.repository.IBatchRepository
import com.qihao.filtercamera.domain.repository.IMediaRepository
import com.qihao.filtercamera.domain.repository.ISettingsRepository
import com.qihao.filtercamera.domain.repository.SaveLocation
import com.qihao.filtercamera.domain.repository.MediaFile
import com.qihao.filtercamera.domain.repository.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 媒体仓库实现类
 *
 * @param context 应用上下文
 * @param batchRepository 批次仓库 - 用于把批次目录纳入相册查询范围
 * @param applicationScope 应用级协程作用域 - 用于常驻监听批次目录变化
 */
@Singleton
class MediaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val batchRepository: IBatchRepository,
    private val settingsRepository: ISettingsRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) : IMediaRepository {

    companion object {
        private const val TAG = "MediaRepositoryImpl"     // 日志标签
        private const val PHOTO_PREFIX = "IMG_"          // 照片文件名前缀
        private const val VIDEO_PREFIX = "VID_"          // 视频文件名前缀
        private const val PHOTO_EXTENSION = ".jpg"       // 照片扩展名
        private const val VIDEO_EXTENSION = ".mp4"       // 视频扩展名
        private const val ALBUM_NAME = "FilterCamera"    // 相册名称

        /** 未选批次时的兜底保存目录：Pictures/FilterCamera */
        private val FALLBACK_RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"

        /** JPEG 压缩质量（兜底值，实际跟随设置页） */
        private const val JPEG_QUALITY = 95

        /** 「自动保存」关闭时照片的私有目录名 */
        private const val PRIVATE_UNSAVED_DIR = "unsaved_shots"
    }

    /**
     * 未选批次时的默认保存目录（跟随设置页的「保存位置」）
     *
     * 相册 -> DCIM/FilterCamera，图片 -> Pictures/FilterCamera，
     * 自定义 -> 用户填写的相对路径（非法或为空则回落「图片」）。
     * 注意：选中批次时**批次目录优先**，批次的意义就是按批归档，
     * 不能被全局保存位置覆盖。
     */
    @Volatile
    private var defaultRelativePath: String = FALLBACK_RELATIVE_PATH

    /** 拍照/编辑保存的 JPEG 质量（跟随设置页的「照片质量」） */
    @Volatile
    private var jpegQuality: Int = JPEG_QUALITY

    // 最后保存的媒体Uri状态流
    private val _lastSavedMediaUri = MutableStateFlow<Uri?>(null)

    /**
     * 属于本应用的相册目录名集合（FilterCamera + 所有批次目录）
     *
     * 读取侧要用它构造查询条件，否则存到 Pictures/BatchA/ 的批次照片
     * 会因为原来的 "只查 FilterCamera 目录" 过滤而在应用相册里看不见。
     */
    @Volatile
    private var albumDirs: List<String> = listOf(ALBUM_NAME)

    /** 最近一次读到的批次列表（供路径解析复用） */
    @Volatile
    private var cachedBatches: List<com.qihao.filtercamera.domain.model.BatchConfig> = emptyList()

    /** 当前保存位置（用于自定义路径变更时重算） */
    @Volatile
    private var currentSaveLocation: SaveLocation = SaveLocation.PICTURES

    /** 用户填写的自定义保存路径 */
    @Volatile
    private var customSavePath: String = ""

    /**
     * 刷新"属于本应用的相册目录"集合
     *
     * 读取侧靠它构造查询条件。除了固定相册名，还要把批次目录、
     * 以及自定义保存路径的最后一级目录纳入，否则这些照片在 App 相册里看不到。
     */
    private fun refreshAlbumDirs() {
        val dirs = buildList {
            add(ALBUM_NAME)
            cachedBatches.forEach { batch ->
                val dir = batch.safeDirName
                if (dir.isNotEmpty() && dir !in this) add(dir)
            }
            // 自定义路径的最后一级目录（DCIM/Pictures 分支的目录名就是 FilterCamera，已在上面）
            defaultRelativePath.substringAfterLast('/')
                .takeIf { it.isNotBlank() && it != ALBUM_NAME && it !in this }
                ?.let { add(it) }
        }
        albumDirs = dirs
        Log.d(TAG, "refreshAlbumDirs: 相册目录=$dirs")
    }

    /**
     * 观察保存位置与照片质量设置
     */
    private fun observeSettings() {
        applicationScope.launch {
            settingsRepository.getSaveLocation()
                .catch { e -> Log.w(TAG, "observeSettings: 读取保存位置失败", e) }
                .collect { location ->
                    currentSaveLocation = location
                    defaultRelativePath = resolveSavePath(location)
                    refreshAlbumDirs()
                    Log.d(TAG, "observeSettings: 保存位置=${location.displayName} -> $defaultRelativePath")
                }
        }

        applicationScope.launch {
            settingsRepository.getCustomSavePath()
                .catch { e -> Log.w(TAG, "observeSettings: 读取自定义路径失败", e) }
                .collect { path ->
                    customSavePath = path
                    defaultRelativePath = resolveSavePath(currentSaveLocation)
                    refreshAlbumDirs()
                    Log.d(TAG, "observeSettings: 自定义路径=$path -> $defaultRelativePath")
                }
        }

        applicationScope.launch {
            settingsRepository.getPhotoQuality()
                .catch { e -> Log.w(TAG, "observeSettings: 读取照片质量失败", e) }
                .collect { quality ->
                    jpegQuality = quality.compressionQuality
                    Log.d(TAG, "observeSettings: JPEG 质量=$jpegQuality")
                }
        }
    }

    /**
     * 解析保存位置对应的相对路径
     */
    private fun resolveSavePath(location: SaveLocation): String = when (location) {
        SaveLocation.DCIM -> "${Environment.DIRECTORY_DCIM}/$ALBUM_NAME"
        SaveLocation.PICTURES -> "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"
        SaveLocation.CUSTOM -> customSavePath
            .trim()
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?.let { sanitizeRelativePath(it) }
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_RELATIVE_PATH
    }

    /**
     * 清理自定义路径：去掉非法字符与 .. 片段，避免目录穿越
     */
    private fun sanitizeRelativePath(raw: String): String =
        raw.split('/')
            .map { it.replace(Regex("[\\:*?\"<>|]"), "").trim('.', ' ') }
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")

    /**
     * 常驻监听批次列表，保持 [albumDirs] 最新
     */
    init {
        observeSettings()
        applicationScope.launch {
            batchRepository.batches
                .catch { e -> Log.e(TAG, "init: 监听批次目录失败", e) }
                .collect { batches ->
                    cachedBatches = batches
                    refreshAlbumDirs()
                }
        }
    }

    /**
     * 相册归属判断使用的列名
     *
     * Android 10+ 用 RELATIVE_PATH；Android 9 及以下该列不存在，只能用 DATA 绝对路径。
     */
    private val albumPathColumn: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            MediaStore.MediaColumns.DATA
        }

    /**
     * 构造"本应用相册"的查询条件
     *
     * 形如：relative_path LIKE ? OR relative_path LIKE ?
     * 参数形如：%/FilterCamera/%, %/BatchA/%
     *
     * 用 "/目录/" 前后包裹，既匹配目录直属文件（RELATIVE_PATH 以 / 结尾），
     * 也匹配按日期分子目录的文件（Pictures/BatchA/2026-09-16/）。
     *
     * @return 选择条件与参数
     */
    private fun buildAlbumSelection(): Pair<String, Array<String>> {
        val column = albumPathColumn
        val dirs = albumDirs                                              // 一次性读取，避免多线程下取到不同快照
        val selection = dirs.joinToString(" OR ") { "$column LIKE ?" }
        val args = dirs.map { "%/$it/%" }.toTypedArray()
        return selection to args
    }

    /**
     * 保存照片到相册（从Bitmap）
     *
     * 默认命名：IMG_yyyyMMdd_HHmmss.jpg，存 Pictures/FilterCamera/
     */
    override suspend fun savePhoto(bitmap: Bitmap, fileName: String): Result<Uri> =
        saveBitmap(bitmap, "$fileName$PHOTO_EXTENSION", defaultRelativePath)

    /**
     * 保存照片到指定批次（从Bitmap）
     *
     * 文件名：batch.nextFileName()
     * 序号模式 -> BA_001.jpg；工作模式 -> 设备上架_拖车.jpg（按预设名字顺序）
     * 目录：Pictures/{dirName}/（dateSubDir 为 true 时再加 yyyy-MM-dd 子目录）
     */
    override suspend fun savePhoto(bitmap: Bitmap, batch: BatchConfig): Result<Uri> {
        // 名字拍完时自动进入下一轮：命名与目录都要用"生效配置"，
        // 否则会出现界面显示的下一张与实际存盘不一致
        val effective = batch.effectiveForNextShot()
        if (effective.round != batch.round) {
            Log.d(TAG, "savePhoto: 本轮已拍完，自动进入第 ${effective.round} 轮")
        }
        return saveBitmap(bitmap, effective.nextFileName(), batchRelativePath(effective))
    }

    /**
     * 保存照片到相册（从字节数组）
     *
     * 默认命名：IMG_yyyyMMdd_HHmmss.jpg，存 Pictures/FilterCamera/
     */
    override suspend fun savePhoto(imageData: ByteArray, fileName: String): Result<Uri> =
        saveImageBytes(imageData, "$fileName$PHOTO_EXTENSION", defaultRelativePath)

    /**
     * 保存照片到指定批次（从字节数组）
     *
     * 相机拍照链路走的是字节数组版本（CameraX 先落临时文件再读字节）。
     */
    override suspend fun savePhoto(imageData: ByteArray, batch: BatchConfig): Result<Uri> {
        val effective = batch.effectiveForNextShot()
        if (effective.round != batch.round) {
            Log.d(TAG, "savePhoto: 本轮已拍完，自动进入第 ${effective.round} 轮")
        }
        return saveImageBytes(imageData, effective.nextFileName(), batchRelativePath(effective))
    }

    /**
     * 保存到应用私有目录（不进系统相册）
     *
     * 对应「自动保存」关闭的情况。放在 filesDir 下而不是 cacheDir，
     * 避免被系统在空间不足时清掉。
     */
    override suspend fun savePhotoToAppPrivate(
        imageData: ByteArray,
        fileName: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, PRIVATE_UNSAVED_DIR)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, fileName)
            file.outputStream().use { out -> out.write(imageData) }

            Log.d(TAG, "savePhotoToAppPrivate: 已保存到私有目录 ${file.absolutePath}")
            Result.success(Uri.fromFile(file))
        } catch (e: Exception) {
            Log.e(TAG, "savePhotoToAppPrivate: 保存失败", e)
            Result.failure(e)
        }
    }

    /**
     * 计算批次的相对保存目录
     *
     * @param batch 批次配置
     * @return 如 "Pictures/BatchA" 或 "Pictures/BatchA/2026-09-16"
     */
    private fun batchRelativePath(batch: BatchConfig): String {
        // 层级顺序由 BatchConfig.relativeSubPath 统一决定：目录 / 日期 / 轮次
        // （日期在外、轮次在内，按天翻看时同一天的各轮次收在同一个日期目录下）
        // 日期戳走 BatchConfig.dateStampFor：存盘、界面显示、按磁盘同步都必须同格式
        val dateStamp = if (batch.dateSubDir) BatchConfig.dateStampFor() else null
        return "${Environment.DIRECTORY_PICTURES}/${batch.relativeSubPath(dateStamp)}"
    }

    /**
     * 写入Bitmap到MediaStore（内部实现）
     *
     * @param bitmap 图像数据
     * @param displayName 完整文件名（含扩展名）
     * @param relativePath 相对路径，如 Pictures/BatchA
     */
    private suspend fun saveBitmap(
        bitmap: Bitmap,
        displayName: String,
        relativePath: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "saveBitmap: 开始保存照片 displayName=$displayName, path=$relativePath")
            val contentValues = buildImageContentValues(displayName, relativePath)
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return@withContext Result.failure(Exception("创建媒体文件失败"))

            Log.d(TAG, "saveBitmap: 创建Uri成功 uri=$uri")

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream)
                Log.d(TAG, "saveBitmap: 图像压缩写入成功")
            } ?: return@withContext Result.failure(Exception("打开输出流失败"))

            finishPendingInsert(uri, contentValues)

            _lastSavedMediaUri.value = uri
            Log.d(TAG, "saveBitmap: 照片保存成功 uri=$uri")
            Result.success(uri)
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmap: 保存照片失败", e)
            Result.failure(e)
        }
    }

    /**
     * 写入JPEG字节到MediaStore（内部实现）
     *
     * @param imageData JPEG图像字节数据
     * @param displayName 完整文件名（含扩展名）
     * @param relativePath 相对路径，如 Pictures/BatchA
     */
    private suspend fun saveImageBytes(
        imageData: ByteArray,
        displayName: String,
        relativePath: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "saveImageBytes: 开始保存照片 displayName=$displayName, path=$relativePath, size=${imageData.size}")
            val contentValues = buildImageContentValues(displayName, relativePath)
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return@withContext Result.failure(Exception("创建媒体文件失败"))

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(imageData)
                Log.d(TAG, "saveImageBytes: 字节数据写入成功")
            } ?: return@withContext Result.failure(Exception("打开输出流失败"))

            finishPendingInsert(uri, contentValues)

            _lastSavedMediaUri.value = uri
            Log.d(TAG, "saveImageBytes: 照片保存成功 uri=$uri")
            Result.success(uri)
        } catch (e: Exception) {
            Log.e(TAG, "saveImageBytes: 保存照片失败", e)
            Result.failure(e)
        }
    }

    /**
     * 保存视频到相册
     */
    override suspend fun saveVideo(videoPath: String, fileName: String): Result<Uri> =
        saveVideoInternal(videoPath, fileName, defaultVideoRelativePath())

    /**
     * 保存视频到相册（批次模式）
     *
     * 批次子目录沿用照片那套（目录/日期/轮次），只是顶级目录换成 Movies ——
     * Scoped Storage 不接受把视频写进 Pictures。日期戳的算法与照片保持一致，
     * 免得同一天的批次在照片和视频里落到不同目录。
     */
    override suspend fun saveVideo(
        videoPath: String,
        fileName: String,
        batch: BatchConfig?
    ): Result<Uri> {
        if (batch == null) return saveVideo(videoPath, fileName)

        val dateStamp = if (batch.dateSubDir) BatchConfig.dateStampFor() else null
        val relativePath =
            "${Environment.DIRECTORY_MOVIES}/${batch.relativeSubPath(dateStamp)}"
        return saveVideoInternal(videoPath, fileName, relativePath)
    }

    /**
     * 视频默认保存目录（未选批次时）
     */
    private fun defaultVideoRelativePath(): String =
        "${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME"

    /**
     * 保存视频到相册（内部实现）
     */
    private suspend fun saveVideoInternal(
        videoPath: String,
        fileName: String,
        relativePath: String
    ): Result<Uri> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(
                    TAG,
                    "saveVideo: 开始保存视频 videoPath=$videoPath, fileName=$fileName, " +
                        "path=$relativePath"
                )
                val sourceFile = File(videoPath)
                if (!sourceFile.exists()) {
                    return@withContext Result.failure(Exception("视频源文件不存在: $videoPath"))
                }

                val contentValues = createVideoContentValues(fileName, relativePath)
                val uri = context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: return@withContext Result.failure(Exception("创建媒体文件失败"))

                Log.d(TAG, "saveVideo: 创建Uri成功 uri=$uri")

                // 复制视频文件
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(sourceFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    Log.d(TAG, "saveVideo: 视频复制成功")
                } ?: return@withContext Result.failure(Exception("打开输出流失败"))

                // 更新IS_PENDING状态（Android 10+）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                }

                // 删除临时文件
                sourceFile.delete()
                Log.d(TAG, "saveVideo: 临时文件已删除")

                _lastSavedMediaUri.value = uri
                Log.d(TAG, "saveVideo: 视频保存成功 uri=$uri")
                Result.success(uri)
            } catch (e: Exception) {
                Log.e(TAG, "saveVideo: 保存视频失败", e)
                Result.failure(e)
            }
        }

    /**
     * 查询指定目录下的照片（按拍摄时间升序，用于导出清单）
     */
    override suspend fun getPhotosInDir(relativePathHint: String): List<MediaFile> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<MediaFile>()
            try {
                val column = albumPathColumn
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED
                )
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "$column LIKE ?",
                    arrayOf("%${relativePathHint.trim('/')}%"),
                    "${MediaStore.Images.Media.DATE_ADDED} ASC"          // 清单按拍摄先后排列
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        result.add(
                            MediaFile(
                                uri = Uri.withAppendedPath(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                                ),
                                path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)) ?: "",
                                name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: "",
                                mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: "image/jpeg",
                                size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)),
                                dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)) * 1000
                            )
                        )
                    }
                }
                Log.d(TAG, "getPhotosInDir: $relativePathHint -> ${result.size} 张")
            } catch (e: Exception) {
                Log.e(TAG, "getPhotosInDir: 查询失败", e)
            }
            result
        }

    /**
     * 保存文本文件到「文档」目录（导出 CSV 清单用）
     */
    override suspend fun saveTextDocument(fileName: String, content: String): Result<Uri> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = content.toByteArray(Charsets.UTF_8)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    } else {
                        val dir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                            ALBUM_NAME
                        )
                        if (!dir.exists()) dir.mkdirs()
                        put(MediaStore.MediaColumns.DATA, File(dir, fileName).absolutePath)
                    }
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Files.getContentUri("external"), values
                ) ?: return@withContext Result.failure(Exception("创建文件失败"))

                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: return@withContext Result.failure(Exception("打开输出流失败"))

                finishPendingInsert(uri, values)
                Log.d(TAG, "saveTextDocument: 已导出 $fileName (${bytes.size} 字节) -> $uri")
                Result.success(uri)
            } catch (e: Exception) {
                Log.e(TAG, "saveTextDocument: 导出失败", e)
                Result.failure(e)
            }
        }

    /**
     * 获取最近的媒体文件
     */
    override suspend fun getRecentMedia(limit: Int): List<MediaFile> =
        withContext(Dispatchers.IO) {
            val mediaFiles = mutableListOf<MediaFile>()
            try {
                Log.d(TAG, "getRecentMedia: 查询最近媒体 limit=$limit")

                // 查询图片
                val imageProjection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED
                )

                // 相册范围：默认目录 + 所有批次目录
                val (albumSelection, albumSelectionArgs) = buildAlbumSelection()

                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imageProjection,
                    albumSelection,
                    albumSelectionArgs,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext() && mediaFiles.size < limit) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                        mediaFiles.add(
                            MediaFile(
                                uri = uri,
                                path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)) ?: "",
                                name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: "",
                                mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: "image/jpeg",
                                size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)),
                                dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)) * 1000
                            )
                        )
                    }
                }

                Log.d(TAG, "getRecentMedia: 查询到 ${mediaFiles.size} 个媒体文件")
            } catch (e: Exception) {
                Log.e(TAG, "getRecentMedia: 查询失败", e)
            }
            mediaFiles
        }

    /**
     * 删除媒体文件
     */
    override suspend fun deleteMedia(uri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "deleteMedia: 删除媒体 uri=$uri")

                // 私有目录的照片是 file:// 形式，ContentResolver 删不掉，直接删文件
                if (uri.scheme == "file") {
                    val file = uri.path?.let { File(it) }
                    val ok = file?.exists() == true && file.delete()
                    Log.d(TAG, "deleteMedia: 私有目录文件删除结果=$ok")
                    return@withContext if (ok) Result.success(Unit)
                    else Result.failure(Exception("删除失败"))
                }

                val deleted = context.contentResolver.delete(uri, null, null)
                if (deleted > 0) {
                    Log.d(TAG, "deleteMedia: 删除成功")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("删除失败"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteMedia: 删除失败", e)
                Result.failure(e)
            }
        }

    /**
     * 获取最后保存的媒体文件Uri
     */
    override fun getLastSavedMediaUri(): Flow<Uri?> = _lastSavedMediaUri

    /**
     * 生成照片文件名
     */
    override fun generatePhotoFileName(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "$PHOTO_PREFIX${dateFormat.format(Date())}"
    }

    /**
     * 生成视频文件名
     */
    override fun generateVideoFileName(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "$VIDEO_PREFIX${dateFormat.format(Date())}"
    }

    // ==================== 图片编辑功能扩展实现 ====================

    /**
     * 从Uri加载Bitmap图像
     *
     * 支持content://和file://类型的Uri
     * 自动处理图片方向（EXIF信息）
     *
     * @param uri 图片Uri
     * @return 加载的Bitmap，失败返回null
     */
    override suspend fun loadBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "loadBitmap: 开始加载图片 uri=$uri")

            // 打开输入流并解码Bitmap
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // 先获取图片尺寸
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)

                // 计算合适的采样率（限制最大尺寸为4096）
                val maxSize = 4096
                options.inSampleSize = calculateInSampleSize(options, maxSize, maxSize)
                options.inJustDecodeBounds = false

                // 重新打开流解码完整Bitmap
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream, null, options)
                    if (bitmap != null) {
                        // 处理图片方向（EXIF）
                        val orientedBitmap = handleOrientation(context, uri, bitmap)
                        Log.d(TAG, "loadBitmap: 图片加载成功 ${orientedBitmap.width}x${orientedBitmap.height}")
                        orientedBitmap
                    } else {
                        Log.e(TAG, "loadBitmap: 解码失败")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadBitmap: 加载图片失败", e)
            null
        }
    }

    /**
     * 处理图片方向（根据EXIF信息旋转）
     */
    private fun handleOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(inputStream)                                     // 使用 androidx ExifInterface
            inputStream.close()

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = android.graphics.Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bitmap
            }

            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Log.e(TAG, "handleOrientation: 处理方向失败", e)
            bitmap
        }
    }

    /**
     * 创建图片ContentValues
     *
     * Android 10+：用 RELATIVE_PATH 指定相册目录（Scoped Storage，无需存储权限）
     * Android 9及以下：没有 RELATIVE_PATH 列，必须用 DATA 绝对路径
     *
     * @param displayName 完整文件名（含扩展名），如 BA_001.jpg
     * @param relativePath 相对路径，如 Pictures/BatchA
     */
    private fun buildImageContentValues(displayName: String, relativePath: String): ContentValues {
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)   // API 29+
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                put(MediaStore.MediaColumns.DATA, buildLegacyAbsolutePath(relativePath, displayName))
            }
        }
    }

    /**
     * 构造 Android 9及以下的绝对路径，并确保目录已创建
     *
     * 旧版本 MediaStore 只认 DATA 列，不建目录会导致 insert 失败或落到默认目录。
     */
    @Suppress("DEPRECATION")
    private fun buildLegacyAbsolutePath(relativePath: String, displayName: String): String {
        val dir = File(Environment.getExternalStorageDirectory(), relativePath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, displayName).absolutePath
    }

    /**
     * 结束 IS_PENDING 状态（Android 10+），让文件对其它应用可见
     */
    private fun finishPendingInsert(uri: Uri, contentValues: ContentValues) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
        }
    }

    /**
     * 创建视频ContentValues
     */
    private fun createVideoContentValues(fileName: String, relativePath: String): ContentValues {
        return ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$fileName$VIDEO_EXTENSION")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
    }

    // ==================== 相册功能扩展实现 ====================

    /**
     * 分页获取媒体文件
     *
     * @param type 媒体类型筛选
     * @param offset 偏移量（跳过的数量）
     * @param limit 每页数量
     * @return 媒体文件列表
     */
    override suspend fun getMediaPaged(
        type: MediaType,
        offset: Int,
        limit: Int
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val mediaFiles = mutableListOf<MediaFile>()
        try {
            Log.d(TAG, "getMediaPaged: 分页查询媒体 type=$type, offset=$offset, limit=$limit")

            // 根据类型查询图片
            if (type == MediaType.ALL || type == MediaType.PHOTO) {
                queryImages(mediaFiles, type == MediaType.PHOTO, offset, limit)
            }

            // 根据类型查询视频
            if (type == MediaType.ALL || type == MediaType.VIDEO) {
                val videoOffset = if (type == MediaType.ALL) {
                    maxOf(0, offset - getImageCount())                         // 计算视频偏移量
                } else offset
                val videoLimit = if (type == MediaType.ALL) {
                    limit - mediaFiles.size                                    // 计算剩余需要的视频数量
                } else limit
                if (videoLimit > 0) {
                    queryVideos(mediaFiles, videoOffset, videoLimit)
                }
            }

            // 按添加时间排序（降序）
            mediaFiles.sortByDescending { it.dateAdded }

            Log.d(TAG, "getMediaPaged: 查询到 ${mediaFiles.size} 个媒体文件")
        } catch (e: Exception) {
            Log.e(TAG, "getMediaPaged: 查询失败", e)
        }
        mediaFiles
    }

    /**
     * 查询图片文件
     *
     * @param result 结果列表
     * @param applyPaging 是否应用分页
     * @param offset 偏移量
     * @param limit 数量限制
     */
    private fun queryImages(
        result: MutableList<MediaFile>,
        applyPaging: Boolean,
        offset: Int,
        limit: Int
    ) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        // 相册范围：默认目录 + 所有批次目录
        val (albumSelection, albumSelectionArgs) = buildAlbumSelection()
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            albumSelection,
            albumSelectionArgs,
            sortOrder
        )?.use { cursor ->
            var skipped = 0                                                    // 已跳过数量
            var added = 0                                                      // 已添加数量
            val targetOffset = if (applyPaging) offset else 0
            val targetLimit = if (applyPaging) limit else Int.MAX_VALUE

            while (cursor.moveToNext()) {
                if (skipped < targetOffset) {                                  // 跳过offset数量
                    skipped++
                    continue
                }
                if (added >= targetLimit) break                                // 达到limit数量

                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                result.add(
                    MediaFile(
                        uri = uri,
                        path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)) ?: "",
                        name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: "",
                        mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: "image/jpeg",
                        size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)),
                        dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)) * 1000,
                        width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)),
                        height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)),
                        duration = 0,
                        isVideo = false
                    )
                )
                added++
            }
            Log.d(TAG, "queryImages: 查询到 $added 张图片")
        }

        // 「自动保存」关闭时拍的照片落在应用私有目录，系统相册看不到；
        // 这里把它们一并纳入，否则那些照片在 App 里也是黑洞。
        //
        // 只在第一页并入：翻页时每页都会重跑这个函数，无条件追加会让同一批私有照片
        // 在列表里重复出现（还会撞上 LazyVerticalGrid 的重复 key 直接崩）。
        if (!applyPaging || offset == 0) {
            result.addAll(listPrivateShots())
        }
    }

    /**
     * 列出应用私有目录里的照片（未写入系统相册的那些）
     */
    private fun listPrivateShots(): List<MediaFile> {
        val dir = File(context.filesDir, PRIVATE_UNSAVED_DIR)
        if (!dir.isDirectory) return emptyList()

        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                MediaFile(
                    uri = Uri.fromFile(file),
                    path = file.absolutePath,
                    name = file.name,
                    mimeType = "image/jpeg",
                    size = file.length(),
                    dateAdded = file.lastModified(),
                    isVideo = false
                )
            }
            ?: emptyList()
    }

    /**
     * 查询视频文件
     *
     * @param result 结果列表
     * @param offset 偏移量
     * @param limit 数量限制
     */
    private fun queryVideos(
        result: MutableList<MediaFile>,
        offset: Int,
        limit: Int
    ) {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DURATION
        )

        // 相册范围：默认目录 + 所有批次目录
        val (albumSelection, albumSelectionArgs) = buildAlbumSelection()
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            albumSelection,
            albumSelectionArgs,
            sortOrder
        )?.use { cursor ->
            var skipped = 0                                                    // 已跳过数量
            var added = 0                                                      // 已添加数量

            while (cursor.moveToNext()) {
                if (skipped < offset) {                                        // 跳过offset数量
                    skipped++
                    continue
                }
                if (added >= limit) break                                      // 达到limit数量

                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                result.add(
                    MediaFile(
                        uri = uri,
                        path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)) ?: "",
                        name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: "",
                        mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)) ?: "video/mp4",
                        size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)),
                        dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)) * 1000,
                        width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)),
                        height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)),
                        duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)),
                        isVideo = true
                    )
                )
                added++
            }
            Log.d(TAG, "queryVideos: 查询到 $added 个视频")
        }
    }

    /**
     * 获取图片总数（内部方法）
     */
    private fun getImageCount(): Int {
        val (albumSelection, albumSelectionArgs) = buildAlbumSelection()

        val mediaStoreCount = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            albumSelection,
            albumSelectionArgs,
            null
        )?.use { it.count } ?: 0

        // 私有目录里的照片也算总数：否则列表里明明有它们，标题却少算，
        // hasMoreData 的判断也会跟着偏
        return mediaStoreCount + listPrivateShots().size
    }

    /**
     * 获取视频总数（内部方法）
     */
    private fun getVideoCount(): Int {
        val (albumSelection, albumSelectionArgs) = buildAlbumSelection()

        return context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID),
            albumSelection,
            albumSelectionArgs,
            null
        )?.use { it.count } ?: 0
    }

    /**
     * 获取媒体文件总数
     *
     * @param type 媒体类型筛选
     * @return 媒体文件总数
     */
    override suspend fun getMediaCount(type: MediaType): Int = withContext(Dispatchers.IO) {
        try {
            val count = when (type) {
                MediaType.PHOTO -> getImageCount()
                MediaType.VIDEO -> getVideoCount()
                MediaType.ALL -> getImageCount() + getVideoCount()
            }
            Log.d(TAG, "getMediaCount: type=$type, count=$count")
            count
        } catch (e: Exception) {
            Log.e(TAG, "getMediaCount: 查询失败", e)
            0
        }
    }

    /**
     * 加载媒体缩略图
     *
     * @param uri 媒体文件Uri
     * @param width 目标宽度
     * @param height 目标高度
     * @return 缩略图Bitmap，失败返回null
     */
    override suspend fun loadThumbnail(uri: Uri, width: Int, height: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "loadThumbnail: 加载缩略图 uri=$uri, size=${width}x$height")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ 使用 loadThumbnail API
                    context.contentResolver.loadThumbnail(uri, Size(width, height), null)
                } else {
                    // Android 9及以下使用传统方式
                    loadThumbnailLegacy(uri, width, height)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadThumbnail: 加载失败", e)
                null
            }
        }

    /**
     * 传统方式加载缩略图（Android 9及以下）
     */
    @Suppress("DEPRECATION")
    private fun loadThumbnailLegacy(uri: Uri, width: Int, height: Int): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // 先获取图片尺寸
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)

                // 计算采样率
                options.inSampleSize = calculateInSampleSize(options, width, height)
                options.inJustDecodeBounds = false

                // 重新打开流解码
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadThumbnailLegacy: 加载失败", e)
            null
        }
    }

    /**
     * 计算缩略图采样率
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 获取相册媒体文件响应式流
     *
     * 当相册内容变化时自动更新
     *
     * @param type 媒体类型筛选
     * @return 媒体文件列表Flow
     */
    override fun observeMedia(type: MediaType): Flow<List<MediaFile>> = callbackFlow {
        Log.d(TAG, "observeMedia: 开始监听媒体变化 type=$type")

        // 创建内容观察者
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                Log.d(TAG, "observeMedia: 媒体内容变化")
                // 重新查询并发送
                trySend(queryAllMedia(type))
            }
        }

        // 注册图片内容观察者
        if (type == MediaType.ALL || type == MediaType.PHOTO) {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        }

        // 注册视频内容观察者
        if (type == MediaType.ALL || type == MediaType.VIDEO) {
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        }

        // 发送初始数据
        trySend(queryAllMedia(type))

        // 等待关闭时取消注册
        awaitClose {
            Log.d(TAG, "observeMedia: 停止监听媒体变化")
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    /**
     * 查询所有媒体（用于observeMedia）
     */
    private fun queryAllMedia(type: MediaType): List<MediaFile> {
        val mediaFiles = mutableListOf<MediaFile>()

        if (type == MediaType.ALL || type == MediaType.PHOTO) {
            queryImages(mediaFiles, false, 0, Int.MAX_VALUE)
        }

        if (type == MediaType.ALL || type == MediaType.VIDEO) {
            queryVideos(mediaFiles, 0, Int.MAX_VALUE)
        }

        // 按添加时间排序（降序）
        mediaFiles.sortByDescending { it.dateAdded }

        return mediaFiles
    }
}
