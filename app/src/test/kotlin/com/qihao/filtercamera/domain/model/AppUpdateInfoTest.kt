/**
 * AppUpdateInfoTest.kt - 版本比较与更新判定的回归测试
 *
 * 判定错了会有两种糟糕结果：把旧版当成新版让用户去"更新"（白折腾），
 * 或者新版发布了却永远提示"已是最新"（更新通道等于失效）。
 *
 * 纯逻辑，无需 Android 运行时。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较测试
 */
class AppUpdateInfoTest {

    /**
     * 常规比较：数值比大小，不是字符串比较
     */
    @Test
    fun `按数值比较版本号`() {
        assertTrue(AppUpdateInfo.compareVersion("2.1.0", "2.0.0") > 0)
        assertTrue(AppUpdateInfo.compareVersion("2.0.0", "2.1.0") < 0)
        assertEquals(0, AppUpdateInfo.compareVersion("2.1.0", "2.1.0"))
    }

    /**
     * 段数不同时短的补 0（不能出现 1.2 与 1.2.0 被判成不同）
     */
    @Test
    fun `段数不同时按0补齐`() {
        assertEquals(0, AppUpdateInfo.compareVersion("1.2", "1.2.0"))
        assertEquals(0, AppUpdateInfo.compareVersion("1.2.0", "1.2"))
        assertTrue(AppUpdateInfo.compareVersion("1.2.1", "1.2") > 0)
    }

    /**
     * 两位数字段要比数值：1.10.0 比 1.9.0 新（字符串比较会判反）
     */
    @Test
    fun `两位数版本段不会判反`() {
        assertTrue(
            "字符串比较会得出错误结论，这里必须按数值",
            AppUpdateInfo.compareVersion("1.10.0", "1.9.0") > 0
        )
        assertTrue(AppUpdateInfo.compareVersion("2.0.10", "2.0.9") > 0)
    }

    /**
     * tag 上的 v 前缀要忽略（GitHub 的 tag 通常写作 v2.1.0）
     */
    @Test
    fun `忽略v前缀`() {
        assertEquals(0, AppUpdateInfo.compareVersion("v2.1.0", "2.1.0"))
        assertTrue(AppUpdateInfo.compareVersion("v2.2.0", "2.1.0") > 0)
    }

    /**
     * 预发布后缀不参与比较（1.2.0-beta 与 1.2.0 视为同一版，不提示更新）
     */
    @Test
    fun `预发布后缀不参与比较`() {
        assertEquals(0, AppUpdateInfo.compareVersion("1.2.0-beta.1", "1.2.0"))
    }

    /**
     * 脏数据不应崩溃（空串、非数字段都当 0）
     */
    @Test
    fun `异常输入不崩溃`() {
        assertEquals(0, AppUpdateInfo.compareVersion("", ""))
        assertTrue(AppUpdateInfo.compareVersion("2.0.0", "") > 0)
        assertEquals(0, AppUpdateInfo.compareVersion("abc", "0"))
        assertTrue(AppUpdateInfo.compareVersion("2.x.0", "1.9.0") > 0)
    }

    /**
     * hasUpdate 的真正含义：远端比本机新
     */
    @Test
    fun `仅当远端更新时提示有更新`() {
        val newer = AppUpdateInfo(
            currentVersion = "2.1.0",
            latestVersion = "2.2.0",
            releaseUrl = "https://example.com",
            publishedAt = "2026-09-17T10:00:00Z",
            notes = ""
        )
        assertTrue(newer.hasUpdate)

        val same = newer.copy(latestVersion = "2.1.0")
        assertFalse("同版本不该提示更新", same.hasUpdate)

        // 本机比远端新（例如装的是自己编的测试包）也不该提示更新
        val older = newer.copy(latestVersion = "2.0.0")
        assertFalse("远端更旧时不该提示更新", older.hasUpdate)
    }

    /**
     * 限流错误要给出可读的重试时间，而不是"未知错误"
     */
    @Test
    fun `限流错误文案包含等待时间`() {
        val error = UpdateCheckError.RateLimited(retryAfterSeconds = 125)
        val message = error.message.orEmpty()
        assertTrue("实际=$message", message.contains("次数已用尽"))
        assertTrue("实际=$message", message.contains("分钟"))
        // 125 秒 -> 向上取整为 3 分钟
        assertTrue("实际=$message", message.contains("3 分钟"))
    }
}
