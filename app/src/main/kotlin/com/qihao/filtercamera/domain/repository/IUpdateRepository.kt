/**
 * IUpdateRepository.kt - 检查更新仓库接口
 *
 * 更新通道是 GitHub Releases（公开 REST 接口，匿名调用按 IP 限流）。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.domain.repository

import com.qihao.filtercamera.domain.model.AppUpdateInfo

/**
 * 检查更新仓库
 */
interface IUpdateRepository {

    /**
     * 上次检查成功的时间戳（毫秒），0 表示从未检查
     */
    suspend fun lastCheckAt(): Long

    /**
     * 读取上次检查的结果（不发起网络请求）
     *
     * 用于进入关于页时先展示已有信息，而不是空白等网络。
     */
    suspend fun cachedUpdate(currentVersion: String): AppUpdateInfo?

    /**
     * 检查更新
     *
     * @param currentVersion 本机版本号（如 2.1.0）
     * @param force true 表示忽略缓存有效期，强制发请求；
     *              默认 false 时若缓存未过期则直接返回缓存，避免浪费 GitHub 额度
     * @return 失败时抛出 [com.qihao.filtercamera.domain.model.UpdateCheckError] 的子类
     */
    suspend fun checkForUpdate(
        currentVersion: String,
        force: Boolean = false
    ): Result<AppUpdateInfo>
}
