/**
 * RoundJumpTest.kt - 任意轮次跳转的回归测试
 *
 * 现场情形：第 2 轮拍到一半被临时叫去拍第 3 轮，忙完要回到第 2 轮接着拍。
 * 因此每一轮的进度必须各存一份，跳过去再跳回来不能丢；
 * 而此前只有"回到第 1 轮"（而且是清空进度的那种），没有跳到任意轮的入口。
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
 * 轮次跳转测试
 */
class RoundJumpTest {

    private val names = listOf("小推车状态", "sn号", "配电箱")

    private fun batch(round: Int = 1) = BatchConfig(
        name = "设备上架",
        dirName = "工作",
        namePrefix = "上架",
        namingMode = NamingMode.WORK,
        nameList = names,
        dateSubDir = true,
        round = round
    ).normalized()

    private fun groupBatch() = BatchConfig(
        name = "今日工单",
        dirName = "工作",
        namePrefix = "上架",
        namingMode = NamingMode.WORK,
        nameList = names,
        dateSubDir = true,
        groupNames = listOf("上架_4U", "上架_2U")
    ).normalized()

    // ==================== 跳到任意轮次 ====================

    /**
     * 第 2 轮拍一半 -> 跳去第 3 轮 -> 回到第 2 轮，进度还在
     *
     * 这是用户描述的那个现场流程。
     */
    @Test
    fun `跳到其它轮次后回到原轮进度仍在`() {
        // 第 2 轮拍了两项
        var b = batch(round = 2)
        b = b.advancedAfterShot()
        b = b.advancedAfterShot()
        assertEquals("前提：第 2 轮已拍 2 项", 2, b.shotCount)

        // 紧急切到第 3 轮
        val third = b.withRound(3)
        assertEquals(3, third.round)
        assertEquals("第 3 轮是全新的", 0, third.shotCount)
        assertEquals("上架_小推车状态.jpg", third.nextFileName())
        assertEquals("工作/2026-09-17/第3轮", third.relativeSubPath("2026-09-17"))

        // 忙完回到第 2 轮
        val back = third.withRound(2)
        assertEquals("应回到第 2 轮", 2, back.round)
        assertEquals("第 2 轮已拍进度不能丢", 2, back.shotCount)
        assertEquals("下一张接在原来的位置", "上架_配电箱.jpg", back.nextFileName())
        assertEquals("目录也要跟着回第 2 轮", "工作/2026-09-17/第2轮", back.relativeSubPath("2026-09-17"))
    }

    /**
     * 可以跳到从未拍过的任意轮次（比如直接从第 1 轮跳到第 5 轮）
     */
    @Test
    fun `可以跳到未拍过的任意轮次`() {
        val jumped = batch(round = 1).withRound(5)

        assertEquals(5, jumped.round)
        assertEquals(0, jumped.shotCount)
        assertEquals("工作/2026-09-17/第5轮", jumped.relativeSubPath("2026-09-17"))
    }

    /**
     * 只跳到同一轮时不做任何改动（幂等感）
     */
    @Test
    fun `跳到当前轮不改变状态`() {
        val b = batch(round = 2)
        assertEquals(b, b.withRound(2))
    }

    /**
     * 非法轮次被夹住，不会崩、也不会出现"第0轮"目录
     */
    @Test
    fun `轮次下限为1`() {
        assertEquals(1, batch(round = 3).withRound(0).round)
        assertEquals(1, batch(round = 3).withRound(-9).round)
        assertFalse(batch(round = 3).withRound(0).relativeSubPath("2026-09-17").contains("第0轮"))
    }

    /**
     * 同一轮跳走再跳回来，已拍项不会被重复计算
     */
    @Test
    fun `反复跳轮不会累加已拍项`() {
        var b = batch(round = 1)
        b = b.advancedAfterShot()
        val once = b.shotCount

        b = b.withRound(4).withRound(1).withRound(7).withRound(1)
        assertEquals("来回跳不应改变已拍项数", once, b.shotCount)
    }

    // ==================== 各轮互不干扰 ====================

    /**
     * 两轮各自拍不同的项，互不覆盖
     */
    @Test
    fun `各轮进度互相独立`() {
        var first = batch(round = 1).advancedAfterShot()          // 第1轮 拍了 第1项
        val second = first.withRound(2).advancedAfterShot()       // 第2轮 拍了 第1项

        assertEquals("第 1 轮 1 项", 1, second.withRound(1).shotCount)
        assertEquals("第 2 轮 1 项", 1, second.shotCount)
        // 第 2 轮已拍 1 项，所以下一张是第 2 项
        assertEquals("上架_sn号.jpg", second.nextFileName())
        assertEquals("上架_sn号.jpg", second.withRound(1).nextFileName())
    }

    /**
     * 可用轮次列表至少包含当前轮与下一轮，便于界面直接列出来
     */
    @Test
    fun `可用轮次列表包含当前轮与下一轮`() {
        val rounds = batch(round = 2).withRound(4).usedRounds

        assertTrue("实际=$rounds", rounds.contains(1))
        assertTrue("实际=$rounds", rounds.contains(4))
        assertTrue("实际=$rounds", rounds.contains(5))
        assertEquals("应升序", rounds.sorted(), rounds)
    }

    /**
     * 各轮已拍项数可分别查询（界面上要显示"第2轮 已拍 2/3"）
     */
    @Test
    fun `可查询指定轮的已拍项数`() {
        var b = batch(round = 1)
        b = b.advancedAfterShot()
        b = b.withRound(3).advancedAfterShot().advancedAfterShot()

        assertEquals("第 1 轮已拍 1 项", 1, b.roundShotCountOf(1))
        assertEquals("第 3 轮已拍 2 项", 2, b.roundShotCountOf(3))
        assertEquals("没拍过的轮次为 0", 0, b.roundShotCountOf(9))
    }

    // ==================== 与分组共存 ====================

    /**
     * 分组内的轮次跳转：只在当前组里换轮，不会串到别的组
     */
    @Test
    fun `分组内的轮次跳转互相独立`() {
        var b = groupBatch()
        b = b.advancedAfterShot()                                  // 上架_4U 第1轮 1 项
        b = b.withRound(2).advancedAfterShot()                      // 上架_4U 第2轮 1 项

        assertEquals("上架_4U", b.activeGroupName)
        assertEquals(2, b.round)
        assertEquals("工作/2026-09-17/上架_4U/第2轮", b.relativeSubPath("2026-09-17"))

        // 切到另一组：它自己的轮次是 1，且进度独立
        val other = b.withActiveGroup(1)
        assertEquals("上架_2U", other.activeGroupName)
        assertEquals(1, other.round)
        assertEquals(0, other.shotCount)

        // 切回去：轮到第 2 轮、进度仍在
        val back = other.withActiveGroup(0)
        assertEquals(2, back.round)
        assertEquals(1, back.shotCount)
    }

    /**
     * 每组各自的轮次不会互相污染
     */
    @Test
    fun `不同组可以停在不同轮次`() {
        var b = groupBatch()
        b = b.withRound(3)                                          // 上架_4U -> 第3轮
        b = b.withActiveGroup(1)                                    // 上架_2U 仍是第1轮
        assertEquals(1, b.round)

        b = b.withRound(5)                                          // 上架_2U -> 第5轮
        assertEquals(5, b.round)

        assertEquals("切回上架_4U 应还在第 3 轮", 3, b.withActiveGroup(0).round)
    }
}
