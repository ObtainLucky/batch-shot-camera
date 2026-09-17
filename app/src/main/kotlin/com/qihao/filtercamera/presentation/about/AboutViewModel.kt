/**
 * AboutViewModel.kt - 关于页状态
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.presentation.about

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qihao.filtercamera.BuildConfig
import com.qihao.filtercamera.domain.model.AppUpdateInfo
import com.qihao.filtercamera.domain.repository.IUpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 关于页 UI 状态
 *
 * @param checking 是否正在检查
 * @param update 上次/本次的检查结果
 * @param message 一次性提示（错误或说明）
 */
data class AboutUiState(
    val checking: Boolean = false,
    val update: AppUpdateInfo? = null,
    val message: String? = null,
    val lastCheckAt: Long = 0L
)

/**
 * 关于页ViewModel
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val updateRepository: IUpdateRepository
) : ViewModel() {

    private companion object {
        const val TAG = "AboutViewModel"

        /** 缓存有效期，与仓库侧保持一致：10 分钟内不重复发请求 */
        const val CACHE_TTL_MS = 10 * 60 * 1000L
    }

    /** 本机版本号（由构建脚本写入，避免两处维护） */
    val currentVersion: String = BuildConfig.VERSION_NAME

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    init {
        // 进页面先展示上次的检查结果，不发请求（省 GitHub 额度）
        viewModelScope.launch {
            val cached = updateRepository.cachedUpdate(currentVersion)
            val lastCheck = updateRepository.lastCheckAt()
            Log.d(TAG, "init: 缓存版本=${cached?.latestVersion}, 上次检查=$lastCheck")
            _uiState.update { it.copy(update = cached, lastCheckAt = lastCheck) }
        }
    }

    /**
     * 检查更新
     *
     * @param force true 表示用户已在提示里确认要忽略缓存、强制发请求
     */
    fun checkForUpdate(force: Boolean = false) {
        if (_uiState.value.checking) return
        _uiState.update { it.copy(checking = true, message = null) }

        viewModelScope.launch {
            updateRepository.checkForUpdate(currentVersion, force)
                .onSuccess { info ->
                    Log.d(TAG, "checkForUpdate: 最新=${info.latestVersion}, 有更新=${info.hasUpdate}")
                    _uiState.update {
                        it.copy(
                            checking = false,
                            update = info,
                            lastCheckAt = System.currentTimeMillis(),
                            message = if (info.hasUpdate) {
                                "发现新版本 ${info.latestVersion}"
                            } else {
                                "已是最新版本"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "checkForUpdate: ${error.message}")
                    // 限流等失败原因已经写成用户能看懂的话，直接展示
                    _uiState.update {
                        it.copy(checking = false, message = error.message ?: "检查失败")
                    }
                }
        }
    }

    /** 消费一次性提示 */
    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /**
     * 缓存是否还没过期
     *
     * 界面据此决定要不要先问用户"刚查过，仍要再查吗"，
     * 避免连点把匿名额度耗光。
     */
    fun isCacheFresh(): Boolean {
        val last = _uiState.value.lastCheckAt
        return last > 0 && System.currentTimeMillis() - last < CACHE_TTL_MS
    }

    /** 距离缓存过期还剩多少秒 */
    fun secondsUntilCacheExpires(): Long {
        val last = _uiState.value.lastCheckAt
        if (last <= 0) return 0
        val remain = CACHE_TTL_MS - (System.currentTimeMillis() - last)
        return (remain / 1000).coerceAtLeast(0)
    }
}
