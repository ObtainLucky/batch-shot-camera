/**
 * DiskSyncIdempotencyTest.kt - "按磁盘同步"必须幂等且忠实
 *
 * 用户实测反馈："点两次同步磁盘会变，似乎同步的不准确"。查下来是三个缺陷：
 *
 * 1. **不幂等**：解析不出组名的文件被兜底归到"当前组"，而当前组正好会被上一次
 *    同步改掉 —— 于是点第二次结果就变了。
 * 2. **不忠实**：分组版的轮次被写死成 1，照片明明在 组名/第2轮/ 里，
 *    同步后却把轮次归 1，下一张会写到 组名/ 下，与已有照片分家。
 * 3. **下一张不准**：分组版把 nextIndex 算成"最后一个已拍的下标 + 1"，
 *    跳着拍时会把指针指到已拍过的项上（模型里的规则是"第一个没拍的"）。
 *
 * 另外序号模式此前被同步成了 counter=0，等于把序号清空重来。
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
 * 按磁盘同步的幂等性与忠实性测试
 */
class DiskSyncIdempotencyTest {

    private val names = listOf("拖车", "sn号", "配电箱")

    private fun workBatch(round: Int = 1) = BatchConfig(
        name = "设备上架",
        dirName = "工作/设备上架",
        namePrefix = "设备上架",
        namingMode = NamingMode.WORK,
        nameList = names,
        dateSubDir = true,
        round = round
    ).normalized()

    private fun orderBatch(groups: List<String> = listOf("上架_4U", "上架_2U")) = BatchConfig(
        name = "今日工单",
        dirName = "工作",
        namePrefix = "上架",
        namingMode = NamingMode.WORK,
        nameList = names,
        dateSubDir = true,
        groupNames = groups
    ).normalized()

    private fun seqBatch(counter: Int = 0) = BatchConfig(
        name = "批次A",
        dirName = "BatchA",
        namePrefix = "BA",
        counter = counter
    ).normalized()

    // ==================== 幂等 ====================

    /**
     * 连续同步两次必须完全一致（这就是用户遇到的那个问题）
     */
    @Test
    fun `轮次模式重复同步结果不变`() {
        val disk = mapOf(
            1 to listOf("设备上架_拖车.jpg", "设备上架_sn号.jpg"),
            2 to listOf("设备上架_拖车.jpg")
        )
        val once = workBatch(round = 5).syncedWithDiskRounds(disk)
        val twice = once.syncedWithDiskRounds(disk)

        assertEquals("第二次同步不应改变任何东西", once, twice)
    }

    /**
     * 分组模式重复同步结果不变
     */
    @Test
    fun `分组模式重复同步结果不变`() {
        val disk = mapOf(
            "上架_4U" to mapOf(1 to listOf("上架_拖车.jpg", "上架_sn号.jpg", "上架_配电箱.jpg")),
            "上架_2U" to mapOf(1 to listOf("上架_拖车.jpg"))
        )
        val once = orderBatch().syncedWithDiskGroups(disk)
        val twice = once.syncedWithDiskGroups(disk)
        val thrice = twice.syncedWithDiskGroups(disk)

        assertEquals("第二次同步不应改变任何东西", once, twice)
        assertEquals("第三次同样", twice, thrice)
    }

    /**
     * 序号模式重复同步结果不变
     */
    @Test
    fun `序号模式重复同步结果不变`() {
        val once = seqBatch(counter = 0).syncedWithDiskSequence(7)
        val twice = once.syncedWithDiskSequence(7)

        assertEquals(once, twice)
        assertEquals("counter = 最大序号 - 起始序号 + 1", 7, once.counter)
    }

    // ==================== 忠实：轮次取自磁盘 ====================

    /**
     * 照片在 第2轮 里，同步后就该停在第 2 轮
     *
     * 早先写死成 1，导致下一张写到 组名/ 下，与已有的 组名/第2轮/ 分家。
     */
    @Test
    fun `未分组时轮次取自磁盘最大轮次`() {
        val synced = workBatch(round = 1).syncedWithDiskRounds(
            mapOf(
                1 to listOf("设备上架_拖车.jpg"),
                2 to listOf("设备上架_拖车.jpg", "设备上架_sn号.jpg")
            )
        )
        assertEquals(2, synced.round)
        assertEquals(2, synced.shotCount)
        assertEquals("下一张应是第三个名字", "设备上架_配电箱.jpg", synced.nextFileName())
    }

    /**
     * 分组模式下每组的轮次也取自磁盘
     */
    @Test
    fun `分组模式轮次取自磁盘`() {
        val synced = orderBatch().syncedWithDiskGroups(
            mapOf(
                "上架_4U" to mapOf(1 to listOf("上架_拖车.jpg"), 2 to listOf("上架_拖车.jpg")),
                "上架_2U" to mapOf(1 to listOf("上架_拖车.jpg"))
            )
        )

        assertEquals("上架_4U 应停在第 2 轮", 2, synced.groupStateOf("上架_4U").round)
        assertEquals("上架_2U 是第 1 轮", 1, synced.groupStateOf("上架_2U").round)

        // 两组都没拍完，按规则停在**第一个**没拍完的组（组顺序即现场的拍摄顺序）
        assertEquals("应停在第一个未拍完的组", "上架_4U", synced.activeGroupName)
        assertEquals(
            "上一张停在第 2 轮，目录就要带轮次层",
            "工作/2026-09-17/上架_4U/第2轮",
            synced.relativeSubPath("2026-09-17")
        )
    }

    /**
     * 前一组拍完时，应停到后面那个还没拍完的组
     */
    @Test
    fun `前一组拍完时停到未拍完的组`() {
        val full = listOf("上架_拖车.jpg", "上架_sn号.jpg", "上架_配电箱.jpg")
        val synced = orderBatch().syncedWithDiskGroups(
            mapOf(
                "上架_4U" to mapOf(1 to full),                                // 拍满
                "上架_2U" to mapOf(1 to listOf("上架_拖车.jpg"))              // 只拍了 1 项
            )
        )

        assertEquals("上架_2U", synced.activeGroupName)
        assertEquals(
            "工作/2026-09-17/上架_2U",
            synced.relativeSubPath("2026-09-17")
        )
    }

    /**
     * 切到停在 第2轮 的组时，目录要带轮次层
     */
    @Test
    fun `停在第二轮时目录带轮次层`() {
        val synced = orderBatch(groups = listOf("上架_4U")).syncedWithDiskGroups(
            mapOf("上架_4U" to mapOf(2 to listOf("上架_拖车.jpg")))
        )
        assertEquals(
            "工作/2026-09-17/上架_4U/第2轮",
            synced.relativeSubPath("2026-09-17")
        )
    }

    // ==================== 忠实：下一张 = 第一个没拍的 ====================

    /**
     * 跳着拍时，下一张应指向第一个没拍的项，而不是"最后一个已拍 + 1"
     */
    @Test
    fun `跳拍时下一张指向第一个未拍项`() {
        // 只拍了第 3 项（配电箱）
        val synced = orderBatch(groups = listOf("上架_4U")).syncedWithDiskGroups(
            mapOf("上架_4U" to mapOf(1 to listOf("上架_配电箱.jpg")))
        )

        assertEquals("只算第 3 项已拍", listOf(2), synced.shotIndexes)
        assertEquals(
            "下一张应回到第一个未拍的（拖车），而不是越界或指向已拍项",
            "上架_拖车.jpg",
            synced.nextFileName()
        )
    }

    /**
     * 未分组模式同样是"第一个未拍"
     */
    @Test
    fun `未分组跳拍时也是第一个未拍`() {
        val synced = workBatch().syncedWithDiskRounds(
            mapOf(1 to listOf("设备上架_配电箱.jpg"))
        )
        assertEquals("设备上架_拖车.jpg", synced.nextFileName())
    }

    // ==================== 分组的分派 ====================

    /**
     * 全部拍完时停在最后一组
     */
    @Test
    fun `全部拍完停在最后一组`() {
        val full = listOf("上架_拖车.jpg", "上架_sn号.jpg", "上架_配电箱.jpg")
        val synced = orderBatch().syncedWithDiskGroups(
            mapOf("上架_4U" to mapOf(1 to full), "上架_2U" to mapOf(1 to full))
        )
        assertEquals("上架_2U", synced.activeGroupName)
    }

    /**
     * 磁盘上没出现的组视为未拍，任何组都没出现时停在第一组
     */
    @Test
    fun `磁盘上未出现的组视为未拍`() {
        val synced = orderBatch().syncedWithDiskGroups(
            mapOf("上架_2U" to mapOf(1 to listOf("上架_拖车.jpg")))
        )
        assertEquals("上架_4U 磁盘上没有 -> 未拍", 0, synced.groupShotCountOf("上架_4U"))
        assertEquals("应停回第一个未拍的组", "上架_4U", synced.activeGroupName)
    }

    /**
     * 重名去重后缀 " (1)" 仍算同一项（用户重新拷入旧数据时常出现）
     */
    @Test
    fun `重名后缀不影响同步判定`() {
        val synced = orderBatch(groups = listOf("上架_4U")).syncedWithDiskGroups(
            mapOf("上架_4U" to mapOf(1 to listOf("上架_拖车.jpg", "上架_sn号 (1).jpg")))
        )
        assertEquals(2, synced.shotCount)
    }

    /**
     * 与名字列表对不上的杂项文件不计入进度
     */
    @Test
    fun `无关文件不计入进度`() {
        val synced = orderBatch(groups = listOf("上架_4U")).syncedWithDiskGroups(
            mapOf("上架_4U" to mapOf(1 to listOf("上架_拖车.jpg", "Screenshot_1.png")))
        )
        assertEquals(1, synced.shotCount)
    }

    // ==================== 序号解析 ====================

    /**
     * 序号从文件名末尾数字解析
     */
    @Test
    fun `解析文件名序号`() {
        assertEquals(7, BatchConfig.parseSequenceFromFileName("BA_007.jpg"))
        assertEquals(12, BatchConfig.parseSequenceFromFileName("IMG_out_12.jpg"))
        assertEquals(1, BatchConfig.parseSequenceFromFileName("BA_1.jpg"))
        assertNull("没有数字应返回 null", BatchConfig.parseSequenceFromFileName("BA_.jpg"))
    }

    /**
     * 序号模式同步后，下一张接着磁盘上的最大序号
     */
    @Test
    fun `序号模式同步后接着最大序号`() {
        val synced = seqBatch(counter = 0).syncedWithDiskSequence(
            BatchConfig.parseSequenceFromFileName("BA_009.jpg")!!
        )
        assertEquals(9, synced.counter)
        assertEquals("BA_010.jpg", synced.nextFileName())
    }

    /**
     * 起始序号不是 1 时，counter 的换算要按 startIndex 走
     */
    @Test
    fun `起始序号非1时换算正确`() {
        val batch = BatchConfig(
            name = "批次A",
            dirName = "BatchA",
            namePrefix = "BA",
            startIndex = 100,
            counter = 0
        ).normalized()

        val synced = batch.syncedWithDiskSequence(107)
        assertEquals("107 - 100 + 1 = 8", 8, synced.counter)
        assertEquals("BA_108.jpg", synced.nextFileName())
    }

    /**
     * 磁盘上的序号比起始序号还小（不该发生）时不产生负数计数
     */
    @Test
    fun `序号小于起始序号时计数不为负`() {
        val synced = seqBatch().syncedWithDiskSequence(0)
        assertTrue("counter 不能为负", synced.counter >= 0)
    }

    // ==================== 边界 ====================

    /**
     * 空输入不做任何改动（扫不到东西时宁可不动）
     */
    @Test
    fun `空输入原样返回`() {
        val work = workBatch()
        assertEquals(work, work.syncedWithDiskRounds(emptyMap()))

        val order = orderBatch()
        assertEquals(order, order.syncedWithDiskGroups(emptyMap()))
    }

    /**
     * 未启用分组时分组同步不做任何事（防止误用）
     */
    @Test
    fun `未启用分组时分组同步无效`() {
        val work = workBatch()
        assertEquals(
            work,
            work.syncedWithDiskGroups(mapOf("甲" to mapOf(1 to listOf("x.jpg"))))
        )
    }
}
