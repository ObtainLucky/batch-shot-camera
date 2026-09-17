/**
 * DiskSyncTest.kt - "按磁盘同步"的回归测试
 *
 * 场景（用户实测遇到的）：批次状态存在 DataStore 里，用户在文件管理器/相册里
 * 删掉或替换了某个轮次目录之后，App 毫不知情，仍然按旧轮次显示"下一张"，
 * 于是**界面显示的轮次与磁盘上实际存在的目录对不上**：
 *
 *   磁盘上只剩 第1轮、第2轮（第3轮被删了）
 *   App 却还认为自己在第 3 轮 -> 继续拍会新建一个"第3轮"，与已有内容错位
 *
 * 同步的做法是反向对齐：扫磁盘上的照片 -> 按轮次分组 -> 取磁盘上存在的最大轮次，
 * 并用**文件名匹配**反推本轮已拍项（不按文件时间，因为拷回来的旧数据时间戳是乱的）。
 *
 * 纯逻辑，无需 Android 运行时。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按磁盘同步测试
 */
class DiskSyncTest {

    private val names = listOf("拖车", "sn号", "配电箱")
    private val prefix = "设备上架"

    private fun batch(round: Int = 3, shot: Int = 0) = BatchConfig(
        name = "设备上架",
        dirName = "工作/设备上架",
        namePrefix = prefix,
        namingMode = NamingMode.WORK,
        nameList = names,
        dateSubDir = true,
        round = round,
        counter = shot,
        shotIndexes = (0 until shot.coerceAtMost(names.size)).toList(),
        nextIndex = shot.coerceAtMost((names.size - 1).coerceAtLeast(0))
    ).normalized()

    /** 生成某个轮次目录下的文件名 */
    private fun file(item: String, round: Int) = "$prefix" + "_$item.jpg"

    // ==================== 路径解析 ====================

    /**
     * 从 MediaStore 给出的路径里解析轮次号
     */
    @Test
    fun `从路径解析轮次号`() {
        assertEquals(
            2,
            BatchConfig.parseRoundFromPath(
                "/storage/emulated/0/Pictures/工作/设备上架/2026-09-17/第2轮/设备上架_拖车.jpg"
            )
        )
        assertEquals(
            12,
            BatchConfig.parseRoundFromPath("Pictures/WorkA/2026-09-17/第12轮/x.jpg")
        )
    }

    /**
     * 没有轮次目录的路径（老数据、序号模式）应该解析为 null，
     * 由调用方兜底成第 1 轮，而不是抛异常或变成 0
     */
    @Test
    fun `无轮次目录时解析为null`() {
        assertNull(BatchConfig.parseRoundFromPath("Pictures/BatchA/BA_001.jpg"))
        assertNull(BatchConfig.parseRoundFromPath(""))
    }

    // ==================== 核心：轮次被删掉后回到磁盘现状 ====================

    /**
     * 磁盘上删掉了第 3 轮 -> 同步后回到第 2 轮
     *
     * 这是用户报告的核心问题。
     */
    @Test
    fun `磁盘上没有第3轮时同步回到第2轮`() {
        val current = batch(round = 3, shot = 0)
        val synced = current.syncedWithDiskRounds(
            mapOf(
                1 to listOf(file("拖车", 1), file("sn号", 1), file("配电箱", 1)),
                2 to listOf(file("拖车", 2))
            )
        )

        assertEquals("应回到磁盘上存在的最大轮次", 2, synced.round)
        assertEquals("第2轮只拍了第一个", 1, synced.shotCount)
        assertEquals("下一张是第二个名字", "$prefix" + "_sn号.jpg", synced.nextFileName())
        assertEquals("目录也跟着回到第2轮", "第2轮", synced.roundDirName)
    }

    /**
     * 同步后的存盘目录必须与界面显示一致（这是"文件夹与显示不一致"的直接断言）
     */
    @Test
    fun `同步后存盘目录与显示一致`() {
        val synced = batch(round = 5, shot = 0).syncedWithDiskRounds(
            mapOf(2 to listOf(file("拖车", 2)))
        )

        val path = synced.relativeSubPath("2026-09-17")
        assertEquals("工作/设备上架/2026-09-17/第2轮", path)
        assertTrue("显示用的轮次目录也要是第2轮", path.contains(synced.roundDirName))
    }

    /**
     * 磁盘上只剩第 1 轮 -> 回到第 1 轮
     */
    @Test
    fun `磁盘上只剩第1轮时回到第1轮`() {
        val synced = batch(round = 4, shot = 3).syncedWithDiskRounds(
            mapOf(1 to listOf(file("拖车", 1)))
        )

        assertEquals(1, synced.round)
        assertEquals(1, synced.shotCount)
    }

    /**
     * 反推进度时按文件名匹配，忽略 MediaStore 的重名去重后缀 " (1)"
     *
     * 用户删掉目录后重新拷入旧数据、或系统加过后缀的情况下都还能对上。
     */
    @Test
    fun `重名后缀不影响已拍判定`() {
        val synced = batch(round = 2).syncedWithDiskRounds(
            mapOf(
                2 to listOf(
                    "$prefix" + "_拖车.jpg",
                    "$prefix" + "_sn号 (1).jpg"
                )
            )
        )

        assertEquals("两项都应算作已拍", 2, synced.shotCount)
    }

    /**
     * 文件名大小写不同也算同一项（个别设备相册会改写大小写）
     */
    @Test
    fun `文件名大小写不敏感`() {
        val lower = BatchConfig(
            name = "批次",
            dirName = "BatchA",
            namePrefix = "IMG",
            namingMode = NamingMode.WORK,
            nameList = listOf("trailer")
        ).normalized()

        val synced = lower.syncedWithDiskRounds(mapOf(1 to listOf("img_TRAILER.jpg")))
        assertEquals(1, synced.shotCount)
    }

    /**
     * 只有部分名字对得上时，只把这些算作已拍，其余保持未拍等待补拍
     */
    @Test
    fun `只匹配部分文件时其余仍未拍`() {
        val synced = batch(round = 2).syncedWithDiskRounds(
            mapOf(2 to listOf("$prefix" + "_配电箱.jpg"))
        )

        assertEquals("只有第 3 项对上", listOf(2), synced.shotIndexes)
        assertEquals("下一张回到第一个未拍的名字", "$prefix" + "_拖车.jpg", synced.nextFileName())
    }

    /**
     * 磁盘上没有任何可识别的文件时**不做改动**
     *
     * 宁可不动，也不能因为扫不到（比如没权限）就把用户的进度清空。
     */
    @Test
    fun `扫不到文件时原样返回`() {
        val current = batch(round = 3, shot = 2)
        val synced = current.syncedWithDiskRounds(emptyMap())

        assertEquals("轮次不变", current.round, synced.round)
        assertEquals("进度不变", current.shotIndexes, synced.shotIndexes)
        assertEquals("指针不变", current.nextIndex, synced.nextIndex)
    }

    /**
     * 目录里的杂项文件（非本批次命名规则）不会污染进度
     */
    @Test
    fun `无关文件不计入进度`() {
        val synced = batch(round = 2).syncedWithDiskRounds(
            mapOf(
                2 to listOf(
                    "$prefix" + "_拖车.jpg",
                    "Screenshot_20260917.png",
                    "IMG_0001.jpg"
                )
            )
        )

        assertEquals("只有符合命名的那个算已拍", 1, synced.shotCount)
    }

    /**
     * 同步后计数器与已拍项数保持一致（counter 是镜像，不能带坏值）
     */
    @Test
    fun `同步后计数器与进度一致`() {
        val synced = batch(round = 2).syncedWithDiskRounds(
            mapOf(2 to listOf(file("拖车", 2), file("sn号", 2)))
        )

        assertEquals(2, synced.shotCount)
        assertEquals("counter 应同步为已拍项数", 2, synced.counter)
    }

    /**
     * 序号模式（名字列表为空）同步轮次时不误判进度
     */
    @Test
    fun `序号模式按文件名序号重建计数器`() {
        val seq = BatchConfig(
            name = "批次A",
            dirName = "BatchA",
            namePrefix = "BA"
        ).normalized()

        // 序号模式不看轮次目录，而是从文件名里取最大序号：
        // 早先这里被同步成 counter=0（等于把序号清空重来），是错的
        val synced = seq.syncedWithDiskSequence(2)
        assertEquals(2, synced.counter)
        assertEquals("BA_003.jpg", synced.nextFileName())
    }

    // ==================== 日期格式一致性 ====================

    /**
     * 界面显示用的日期戳必须与实际存盘用的一致
     *
     * 之前管理页把字面量 "yyyy-MM-dd" 当成日期戳传给 relativeSubPath，
     * 界面上直接显示占位符，与真实目录对不上。
     */
    @Test
    fun `日期戳格式为yyyy-MM-dd`() {
        val stamp = BatchConfig.dateStampFor()
        assertTrue("实际=$stamp", Regex("\\d{4}-\\d{2}-\\d{2}").matches(stamp))
        assertEquals("格式常量为 yyyy-MM-dd", "yyyy-MM-dd", BatchConfig.DATE_STAMP_PATTERN)
    }

    /**
     * 用真实日期戳拼出来的路径里能反解出轮次，往返一致
     */
    @Test
    fun `路径拼接与解析可往返`() {
        val synced = batch(round = 2)
        val path = synced.relativeSubPath(BatchConfig.dateStampFor())

        assertEquals("应能反解出同一轮次", 2, BatchConfig.parseRoundFromPath("$path/x.jpg"))
    }
}
