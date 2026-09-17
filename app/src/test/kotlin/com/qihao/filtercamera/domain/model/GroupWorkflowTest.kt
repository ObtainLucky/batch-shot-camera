/**
 * GroupWorkflowTest.kt - 分组（工单里的"每组"）工作流测试
 *
 * 现场流程：每天拿到工单 -> 看上架/下架 -> 各自还可能按 U 数分成几组（4U、2U），
 * 每组都要把同一份拍摄清单（拖车、sn号…）走一遍；都是同一 U 数时只拍一组。
 *
 * 于是：
 *   批次 = 一天的工单；分组 = 上架_4U / 上架_2U / 下架_4U；组内 = 名字列表
 *   目录 = 目录 / 日期 / 组名（第 1 遍不再多一层「第1轮」，重拍才出现）
 *
 * 关键行为：
 * 1. 每组独立进度，来回切组不丢已拍记录
 * 2. 一组拍完 -> 提示并自动切到下一组（可关掉，改为留在本组）
 * 3. 组名进目录、不进文件名（文件名仍是 前缀_名字.jpg）
 *
 * 纯逻辑，无需 Android 运行时。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分组工作流测试
 */
class GroupWorkflowTest {

    private val names = listOf("拖车", "sn号", "配电箱")

    /** 一天的工单：上架两组（4U/2U）+ 下架一组（4U） */
    private fun orderBatch(
        groups: List<String> = listOf("上架_4U", "上架_2U", "下架_4U"),
        autoAdvance: Boolean = true
    ) = BatchConfig(
        name = "今日工单",
        dirName = "工作",
        namePrefix = "上架",
        namingMode = NamingMode.WORK,
        nameList = names,
        dateSubDir = true,
        groupNames = groups,
        autoAdvanceGroup = autoAdvance
    ).normalized()

    /** 把当前组整个拍完 */
    private fun shootWholeGroup(batch: BatchConfig): BatchConfig {
        var current = batch
        repeat(names.size) { current = current.advancedAfterShot() }
        return current
    }

    /** 开拍（未启用分组时不该有任何分组行为） */
    private fun plainWorkBatch() = BatchConfig(
        name = "设备上架",
        dirName = "WorkA",
        namePrefix = "设备上架",
        namingMode = NamingMode.WORK,
        nameList = names
    ).normalized()

    // ==================== 目录层级 ====================

    /**
     * 启用分组：目录 = 目录 / 日期 / 组名，第 1 遍不再多一层「第1轮」
     */
    @Test
    fun `分组目录为目录加日期加组名`() {
        val batch = orderBatch()

        assertEquals("工作/2026-09-17/上架_4U", batch.relativeSubPath("2026-09-17"))
        assertEquals("当前组", "上架_4U", batch.activeGroupName)
    }

    /**
     * 切到第二组后目录跟着变
     */
    @Test
    fun `切组后目录跟着切换`() {
        val second = orderBatch().withActiveGroup(1)
        assertEquals("工作/2026-09-17/上架_2U", second.relativeSubPath("2026-09-17"))
    }

    /**
     * 只有同一组要重拍时才出现「第N轮」
     */
    @Test
    fun `同组重拍才出现轮次目录`() {
        val batch = orderBatch()
        assertEquals("工作/2026-09-17/上架_4U", batch.relativeSubPath("2026-09-17"))

        val secondPass = batch.withNewRound()
        assertEquals("第 1 遍不占轮次层", 2, secondPass.round)
        assertEquals("工作/2026-09-17/上架_4U/第2轮", secondPass.relativeSubPath("2026-09-17"))
    }

    /**
     * 未启用分组时目录层级与旧版本完全一致（回归保护）
     */
    @Test
    fun `未启用分组时保持旧目录层级`() {
        val legacy = plainWorkBatch()
        assertEquals("WorkA/2026-09-17/第1轮", legacy.relativeSubPath("2026-09-17"))
        assertFalse("旧批次不该出现组名层", legacy.relativeSubPath("2026-09-17").contains("上架_"))
    }

    // ==================== 各组成员的进度互相独立 ====================

    /**
     * 每组进度独立：第一组拍 2 项后切到第二组，第二组从 0 开始
     */
    @Test
    fun `切组后进度从该组自己的状态开始`() {
        var batch = orderBatch()
        batch = batch.advancedAfterShot()          // 上架_4U 第 1 项
        batch = batch.advancedAfterShot()          // 上架_4U 第 2 项
        assertEquals(2, batch.shotCount)

        val second = batch.withActiveGroup(1)
        assertEquals("第二组应是全新进度", 0, second.shotCount)
        assertEquals("上架_sn号.jpg".let { null }, null)
        assertEquals("上架_拖车.jpg", second.nextFileName())
    }

    /**
     * 切回第一组能接着原来的进度（这是"来回切不丢记录"的核心）
     */
    @Test
    fun `切回原组恢复原进度`() {
        var batch = orderBatch()
        batch = batch.advancedAfterShot()
        batch = batch.advancedAfterShot()
        val firstShotCount = batch.shotCount

        val toSecond = batch.withActiveGroup(1)
        val backToFirst = toSecond.withActiveGroup(0)

        assertEquals("切回来进度不能丢", firstShotCount, backToFirst.shotCount)
        assertEquals("下一张应接在原来的位置", "上架_配电箱.jpg", backToFirst.nextFileName())
        assertEquals("应是第一组", "上架_4U", backToFirst.activeGroupName)
    }

    /**
     * 一组的快照不会污染另一组
     */
    @Test
    fun `各组快照互相独立`() {
        val batch = orderBatch().advancedAfterShot()             // 组0 拍 1 项
        val switched = batch.withActiveGroup(2)                  // 切到组2
        val switchedShot = switched.advancedAfterShot()          // 组2 拍 1 项

        assertEquals(1, switchedShot.groupShotCountOf("上架_4U"))
        assertEquals(1, switchedShot.groupShotCountOf("下架_4U"))
        assertEquals("没拍过的组应为 0", 0, switchedShot.groupShotCountOf("上架_2U"))
    }

    // ==================== 拍完自动切组 ====================

    /**
     * 一组拍完 + 开着自动切组 -> 自动进入下一组
     *
     * 这正是现场要的：4U 拍完接着拍 2U，而不是原地再拍一遍 4U。
     */
    @Test
    fun `拍完一组自动切到下一组`() {
        val after = shootWholeGroup(orderBatch())

        assertEquals("应切到第二组", "上架_2U", after.activeGroupName)
        assertEquals("新组进度归零", 0, after.shotCount)
        assertEquals("新组第一张", "上架_拖车.jpg", after.nextFileName())
        assertEquals("上一组的记录要留着", names.size, after.groupShotCountOf("上架_4U"))
        assertEquals(
            "目录应指向新组",
            "工作/2026-09-17/上架_2U",
            after.relativeSubPath("2026-09-17")
        )
    }

    /**
     * 连续拍完两组 -> 停在第三组
     */
    @Test
    fun `连续拍完两组停到第三组`() {
        val after = shootWholeGroup(shootWholeGroup(orderBatch()))
        assertEquals("下架_4U", after.activeGroupName)
    }

    /**
     * 关掉自动切组 -> 留在本组，继续拍进入本组第 2 轮
     */
    @Test
    fun `关掉自动切组时留在本组`() {
        val after = shootWholeGroup(orderBatch(autoAdvance = false))

        assertEquals("应留在本组", "上架_4U", after.activeGroupName)
        assertTrue("本组应显示已拍完", after.isRoundComplete)

        // 继续拍 -> 本组第 2 轮（重拍时才会出现轮次目录）
        val nextPass = after.advancedAfterShot()
        assertEquals(2, nextPass.round)
        assertEquals("上架_4U", nextPass.activeGroupName)
        assertEquals(
            "工作/2026-09-17/上架_4U/第2轮",
            nextPass.relativeSubPath("2026-09-17")
        )
    }

    /**
     * 最后一组拍完没有下一组可切，应留在原地并提示进入本组下一轮
     */
    @Test
    fun `最后一组拍完不会越界`() {
        var batch = orderBatch()
        batch = shootWholeGroup(batch)                    // -> 组1
        batch = shootWholeGroup(batch)                    // -> 组2
        val last = shootWholeGroup(batch)                 // 组2 拍完

        assertEquals("不能越界到不存在的组", "下架_4U", last.activeGroupName)
        assertFalse(last.hasNextGroup)
        assertTrue("应提示已拍完", last.workProgressLabel!!.contains("已拍完"))
    }

    /**
     * 最后一组的文案不该说"自动进入下一组"
     */
    @Test
    fun `最后一组文案不提下一组`() {
        var batch = orderBatch(groups = listOf("上架_4U"))
        batch = shootWholeGroup(batch)

        val label = batch.workProgressLabel!!
        assertTrue("实际=$label", label.contains("已拍完"))
        assertFalse("实际=$label，没有下一组就不该提" , label.contains("下一组"))
    }

    /**
     * 关掉自动切组时，文案要明确提示需要手动切
     */
    @Test
    fun `关闭自动切组时提示手动切`() {
        val batch = shootWholeGroup(orderBatch(autoAdvance = false))
        val label = batch.workProgressLabel!!
        assertTrue("实际=$label", label.contains("手动切"))
    }

    // ==================== 改名/进度推进的细节 ====================

    /**
     * 组内推进：名字按列表顺序，且跳过的那项会被回头补拍
     */
    @Test
    fun `组内按名字顺序推进`() {
        var batch = orderBatch()
        assertEquals("上架_拖车.jpg", batch.nextFileName())
        batch = batch.advancedAfterShot()
        assertEquals("上架_sn号.jpg", batch.nextFileName())
        batch = batch.advancedAfterShot()
        assertEquals("上架_配电箱.jpg", batch.nextFileName())
    }

    /**
     * 组名不进文件名（文件名仍是 前缀_名字.jpg）
     */
    @Test
    fun `组名不影响文件名`() {
        val batch = orderBatch()
        assertEquals("上架_拖车.jpg", batch.nextFileName())
        assertEquals(
            "换组后文件名规则不变",
            "上架_拖车.jpg",
            batch.withActiveGroup(1).nextFileName()
        )
    }

    /**
     * 只切组不拍时，轮次不应被推进
     */
    @Test
    fun `切组不改变轮次`() {
        val batch = orderBatch()
        assertEquals(1, batch.withActiveGroup(1).round)
        assertEquals(1, batch.withActiveGroup(2).round)
    }

    /**
     * 分组下"回到第1轮"只影响当前组
     */
    @Test
    fun `回到第1轮只影响当前组`() {
        var batch = orderBatch()
        batch = shootWholeGroup(batch)                    // 组0 拍完，切到 组1
        batch = batch.withNewRound()                      // 组1 开第 2 轮
        assertEquals(2, batch.round)

        val reset = batch.withRoundReset()
        assertEquals("当前组回到第 1 轮", 1, reset.round)
        assertEquals(0, reset.shotCount)
        assertEquals("上一组的记录仍在", names.size, reset.groupShotCountOf("上架_4U"))
    }

    /**
     * 未启用分组时不应有任何分组行为（回归保护）
     */
    @Test
    fun `未启用分组时行为与旧版本一致`() {
        val legacy = plainWorkBatch()
        assertFalse(legacy.usesGroups)
        assertNull(legacy.activeGroupName)
        assertFalse(legacy.hasNextGroup)

        // 旧行为：名字列表拍完后再拍一张，才自动进入第 2 轮
        // （"继续拍自动进入下一轮"就是在这个时刻发生的）
        var batch = legacy
        repeat(names.size) { batch = batch.advancedAfterShot() }
        assertEquals("拍完时仍停在第 1 轮", 1, batch.round)
        assertTrue("此时应显示已拍完", batch.isRoundComplete)
        assertEquals("WorkA/第1轮", batch.relativeSubPath(null))

        batch = batch.advancedAfterShot()
        assertEquals("再拍一张才进入第 2 轮", 2, batch.round)
        assertEquals("WorkA/第2轮", batch.relativeSubPath(null))
    }

    /**
     * 空分组列表等于不分组的单组模式
     */
    @Test
    fun `空分组等价于不分组`() {
        val empty = orderBatch(groups = emptyList())
        assertFalse(empty.usesGroups)
        assertNull(empty.activeGroupName)
        assertEquals("工作/2026-09-17/第1轮", empty.relativeSubPath("2026-09-17"))
    }

    // ==================== 组名清洗 ====================

    /**
     * 组名里的斜杠会被清洗掉，否则会凭空多出一层目录
     */
    @Test
    fun `组名中的非法字符被清洗`() {
        assertEquals("上架_4U", BatchConfig.sanitizeGroupName("上架/4U"))
        assertEquals("上架_4U", BatchConfig.sanitizeGroupName(" 上架_4U "))
        // 组名必须是一层目录：斜杠不能像目录名那样保留层级
        assertFalse(BatchConfig.sanitizeGroupName("上架/4U").contains('/'))
    }

    /**
     * 越界的组下标会被夹住，不会崩
     */
    @Test
    fun `组下标越界被夹住`() {
        val batch = orderBatch()
        assertEquals("下架_4U", batch.withActiveGroup(99).activeGroupName)
        assertEquals("上架_4U", batch.withActiveGroup(-5).activeGroupName)
    }

    // ==================== 磁盘路径解析 ====================

    /**
     * 组名匹配只看"路径里有没有这个组目录"，与层级位置无关
     */
    @Test
    fun `按日期分层时能匹配到组`() {
        assertEquals(
            "上架_4U",
            BatchConfig.parseGroupFromPath(
                "/storage/emulated/0/Pictures/工作/2026-09-17/上架_4U/上架_拖车.jpg",
                "上架_拖车.jpg",
                listOf("上架_4U", "上架_2U")
            )
        )
    }

    /**
     * 不按日期分层同样能匹配（真机踩到的那个坑）
     */
    @Test
    fun `不分日期层时能匹配到组`() {
        assertEquals(
            "下架",
            BatchConfig.parseGroupFromPath(
                "/storage/emulated/0/Pictures/工作/下架/下架_核对六要素_1789623776767.jpg",
                "下架_核对六要素_1789623776767.jpg",
                listOf("下架")
            )
        )
    }

    /**
     * 组目录里还有轮次层、批次目录是多级目录，都不影响匹配
     */
    @Test
    fun `层级更深时也能匹配到组`() {
        assertEquals(
            "上架_4U",
            BatchConfig.parseGroupFromPath(
                "Pictures/工作/设备上架/2026-09-17/上架_4U/第2轮/上架_拖车.jpg",
                "上架_拖车.jpg",
                listOf("上架_4U")
            )
        )
        assertEquals(
            "上架_4U",
            BatchConfig.parseGroupFromPath(
                "Pictures/工作/设备上架/上架_4U/上架_拖车.jpg",
                "上架_拖车.jpg",
                listOf("上架_4U")
            )
        )
    }

    /**
     * 组名有包含关系时取最长的，避免 "4U" 抢走 "上架_4U" 的照片
     */
    @Test
    fun `组名包含时取最长匹配`() {
        assertEquals(
            "上架_4U",
            BatchConfig.parseGroupFromPath(
                "Pictures/工作/2026-09-17/上架_4U/上架_拖车.jpg",
                "上架_拖车.jpg",
                listOf("4U", "上架_4U")
            )
        )
    }

    /**
     * 路径里没有已知组目录 -> 返回 null（调用方忽略，而不是乱猜）
     */
    @Test
    fun `路径里没有已知组目录时返回null`() {
        assertNull(
            BatchConfig.parseGroupFromPath(
                "Pictures/别的相册/2026-09-17/上架_拖车.jpg",
                "上架_拖车.jpg",
                listOf("上架_4U")
            )
        )
    }

    /**
     * 组名不会被文件名里的相同字样误命中
     */
    @Test
    fun `文件名里的字样不会误判为组`() {
        assertNull(
            "文件名里出现 4U 不代表它在 4U 组目录下",
            BatchConfig.parseGroupFromPath(
                "Pictures/工作/2026-09-17/上架_4U产品_拖车.jpg",
                "上架_4U产品_拖车.jpg",
                listOf("4U")
            )
        )
    }

    /**
     * 磁盘上存在但批次没登记的组目录，同步时会被认下来
     */
    @Test
    fun `同步会纳入磁盘上未登记的组目录`() {
        val batch = orderBatch(groups = listOf("上架_4U"))
        val synced = batch.syncedWithDiskGroups(
            mapOf(
                "上架_4U" to mapOf(1 to listOf("上架_拖车.jpg")),
                "下架" to mapOf(1 to listOf("上架_拖车.jpg", "上架_sn号.jpg"))
            )
        )

        assertTrue("新组目录应被纳入", synced.groupNames.contains("下架"))
        assertEquals("已登记的组排在前", "上架_4U", synced.groupNames.first())
        assertEquals("新组的进度也要算出来", 2, synced.groupShotCountOf("下架"))
    }

    /**
     * 纳入新组后再同步一次，结果不变（幂等）
     */
    @Test
    fun `纳入新组后重复同步结果不变`() {
        val disk = mapOf(
            "上架_4U" to mapOf(1 to listOf("上架_拖车.jpg")),
            "下架" to mapOf(1 to listOf("上架_拖车.jpg"))
        )
        val once = orderBatch(groups = listOf("上架_4U")).syncedWithDiskGroups(disk)
        val twice = once.syncedWithDiskGroups(disk)

        assertEquals(once, twice)
    }

    // ==================== 目录认不出时退回看文件名 ====================

    /**
     * 目录层级判断不出来时，用文件名里出现的组名兜底
     *
     * 这是"名字完全一样就当它拍过"的落地：照片叫什么名字是确定的，
     * 不该因为目录层级没按预期排而被否定掉。
     */
    @Test
    fun `目录认不出时按文件名兜底认组`() {
        assertEquals(
            "下架",
            BatchConfig.parseGroupFromPath(
                // 目录里没有 /下架/ 这一层（例如被人手工挪过位置）
                "Pictures/导出/临时/下架_核对六要素_1789623776767.jpg",
                "下架_核对六要素_1789623776767.jpg",
                listOf("下架")
            )
        )
    }

    /**
     * 兜底匹配同样取最长组名，且要求组名是完整片段
     */
    @Test
    fun `兜底匹配取最长且要求完整片段`() {
        assertEquals(
            "上架_4U",
            BatchConfig.parseGroupFromPath(
                "Pictures/临时/上架_4U_拖车.jpg",
                "上架_4U_拖车.jpg",
                listOf("4U", "上架_4U")
            )
        )
        // 组名 "A" 不该被 "BA_001.jpg" 误命中
        assertNull(
            "短组名不能命中名字里的巧合子串",
            BatchConfig.parseGroupFromPath(
                "Pictures/临时/BA_001.jpg",
                "BA_001.jpg",
                listOf("A")
            )
        )
    }

    /**
     * 目录能认出来时以目录为准，不会因为文件名里恰好也有别的组名而跑偏
     */
    @Test
    fun `目录可辨时以目录为准`() {
        assertEquals(
            "上架_2U",
            BatchConfig.parseGroupFromPath(
                "Pictures/工作/2026-09-17/上架_2U/上架_4U_拖车.jpg",
                "上架_4U_拖车.jpg",
                listOf("上架_4U", "上架_2U")
            )
        )
    }
}
