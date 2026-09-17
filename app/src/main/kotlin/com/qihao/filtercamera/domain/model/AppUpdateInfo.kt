/**
 * AppUpdateInfo.kt - 应用更新信息
 *
 * 更新通道就是 GitHub Releases：查 latest release 的 tag，与本机版本比对。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.domain.model

/**
 * 检查更新的结果
 *
 * @param currentVersion 本机版本（如 2.1.0）
 * @param latestVersion 远端最新版本（已去掉 tag 的 v 前缀）
 * @param releaseUrl 该发行版页面地址
 * @param publishedAt 发布时间（原样展示，不做本地化）
 * @param notes 发行说明（Markdown 原文，界面上按纯文本展示）
 * @param assetName 安装包文件名（可能为空）
 * @param assetUrl 安装包下载地址（可能为空）
 * @param assetSizeBytes 安装包大小（字节，0 表示未知）
 */
data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseUrl: String,
    val publishedAt: String,
    val notes: String,
    val assetName: String = "",
    val assetUrl: String = "",
    val assetSizeBytes: Long = 0L
) {
    /** 是否有新版本 */
    val hasUpdate: Boolean
        get() = compareVersion(latestVersion, currentVersion) > 0

    companion object {
        /**
         * 比较版本号大小
         *
         * 按"."切段做数值比较（1.10.0 > 1.9.0），前缀 v 会被忽略，
         * 段数不一致时缺的段按 0 处理（1.2 == 1.2.0）。
         *
         * @return 正数表示 a 更新，负数表示 b 更新，0 表示相同
         */
        fun compareVersion(a: String, b: String): Int {
            fun parse(v: String): List<Int> = v.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')                                  // 忽略 1.2.3-beta 这类后缀
                .split('.')
                .map { it.trim().toIntOrNull() ?: 0 }

            val left = parse(a)
            val right = parse(b)
            val size = maxOf(left.size, right.size)
            for (i in 0 until size) {
                val l = left.getOrElse(i) { 0 }
                val r = right.getOrElse(i) { 0 }
                if (l != r) return l - r
            }
            return 0
        }
    }
}

/**
 * 检查更新可能失败的几种情况
 *
 * 单独建模是因为"GitHub 匿名接口限流"必须给用户看得懂的解释，
 * 不能笼统报一句"网络错误" —— 那会让人一直重试、把额度耗得更快。
 */
sealed class UpdateCheckError(message: String) : Exception(message) {
    /** 距离下次可用还需等待的秒数（限流时给出） */
    class RateLimited(val retryAfterSeconds: Long) :
        UpdateCheckError("GitHub 接口调用次数已用尽，请 ${retryAfterSeconds / 60 + 1} 分钟后再试")

    /** 网络不可达（含 GitHub 被墙/超时） */
    class Network(cause: String) : UpdateCheckError("网络不可用：$cause")

    /** 返回内容无法解析 */
    class Parse(cause: String) : UpdateCheckError("无法解析 GitHub 返回：$cause")
}
