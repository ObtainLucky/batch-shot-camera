/**
 * RoundResetTest.kt - 轮次进度与重置的回归测试
 *
 * 背景：用户反馈"轮次统计一直是延续状态，无法重置这个轮次"。查下来是三个具体缺陷：
 *
 * 1. 工作模式的真实进度存在 shotIndexes / nextIndex 里，counter 只是 normalized()
 *    反推出来的**镜像值**。而 resetCounter 只写了 `copy(counter = 0)` —— 于是按
 *    "重置"是个假动作：已拍清单纹丝不动，指针也不动，下一张还会因为本轮已拍完
 *    而直接跳到下一轮，看起来就是"重置不了、轮次一路往下延续"。
 * 2. 轮次只增不减（withNewRound 只会 +1），跨天复用同一批次时会接着前一天的
 *    轮次数下去，当天目录里直接出现「第5轮」，且没有任何入口能调回来。
 * 3. 进度文案读的是 counter 这个镜像值，任何"只写 counter"的改动都会让它与
 *    真实进度脱节。
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
 * 轮次与进度重置测试
 */
class RoundResetTest {

    private val names = listOf("拖车", "sn号", "配电箱")

    /** 造一个工作模式批次，已顺序拍完 [shot] 项 */
    private fun workBatch(
        shot: Int = 0,
        round: Int = 1,
        names: List<String> = this.names
    ) = BatchConfig(
        name = "设备上架",
        dirName = "WorkA",
        namePrefix = "设备上架",
        namingMode = NamingMode.WORK,
        nameList = names,
        round = round,
        counter = shot,
        shotIndexes = (0 until shot.coerceAtMost(names.size)).toList(),
        nextIndex = shot.coerceAtMost((names.size - 1).coerceAtLeast(0))
    ).normalized()

    // ==================== 进度统计 ====================

    /**
     * 进度文案读的是真实已拍项数，随拍摄推进（沿用"第 N/total 个名字"的一基序号）
     */
    @Test
    fun `进度文案随实际拍摄推进`() {
        assertEquals("第1轮 · 第 1/3 个名字", workBatch(shot = 0).workProgressLabel)
        assertEquals("第1轮 · 第 2/3 个名字", workBatch(shot = 1).workProgressLabel)
        assertEquals("第1轮 · 第 3/3 个名字", workBatch(shot = 2).workProgressLabel)
    }

    /**
     * 本轮拍完后文案要说明"继续拍会进入下一轮"，而不是停在某个奇怪的数字上
     */
    @Test
    fun `本轮拍完后文案提示自动进入下一轮`() {
        val label = workBatch(shot = 3).workProgressLabel
        assertTrue("实际=$label", label!!.contains("已拍完"))
        assertTrue("实际=$label", label.contains("自动进入第2轮"))
    }

    /**
     * 进度文案必须来自已拍下标，而不是 counter 这个镜像值
     *
     * 这一条是那个"统计不对"的核心：手工构造一个 counter 与真实进度不一致的
     * 对象（旧 resetCounter 留下的就是这种状态），文案要反映真实进度。
     */
    @Test
    fun `进度文案不依赖 counter 镜像值`() {
        val inconsistent = workBatch(shot = 3).copy(counter = 0)   // 镜像被写坏，真实进度仍是 3/3
        val label = inconsistent.workProgressLabel!!

        assertTrue("实际=$label，应按真实进度显示已拍完", label.contains("已拍完"))
        assertFalse("实际=$label，不应被坏掉的 counter 带回第 1 个", label.contains("第 1/3"))
    }

    /**
     * 跳着拍时进度按已拍项数走，指针位置也如实反映
     */
    @Test
    fun `跳拍后进度与待补拍项都正确`() {
        // 直接点了第 3 项拍（下标 2），前两项未拍
        val skipped = workBatch(shot = 0).copy(
            shotIndexes = listOf(2),
            nextIndex = 1
        ).normalized()

        assertEquals("已拍 1 项", 1, skipped.shotCount)
        assertEquals("指针在第 2 项", "第1轮 · 第 2/3 个名字", skipped.workProgressLabel)
        assertEquals("第 1 项等待补拍", listOf(0), skipped.pendingIndexes)
    }

    /**
     * 序号模式的摘要仍然看计数器（两种模式的进度语义不同）
     */
    @Test
    fun `序号模式摘要为已拍张数`() {
        val seq = BatchConfig(
            name = "批次A",
            dirName = "BatchA",
            namePrefix = "BA",
            counter = 7
        ).normalized()

        assertNull("序号模式没有轮次概念，工作模式文案应为 null", seq.workProgressLabel)
        assertEquals("已拍 7 张", seq.progressSummary)
    }

    // ==================== 重拍本轮（withProgressReset） ====================

    /**
     * 重置必须清掉"已拍下标 + 名字指针"，而不只是把 counter 归零
     *
     * 这是"无法重置这个轮次"的直接回归：只清 counter 的话，已拍清单还在，
     * 下一张依然会被判定为本轮已拍完。
     */
    @Test
    fun `重置本轮会清空已拍清单与指针`() {
        val reset = workBatch(shot = 3).withProgressReset()

        assertTrue("已拍下标必须清空，实际=${reset.shotIndexes}", reset.shotIndexes.isEmpty())
        assertEquals(0, reset.nextIndex)
        assertEquals(0, reset.counter)
        assertEquals(0, reset.shotCount)
        assertFalse("重置后不应再被判定为已拍完", reset.isRoundComplete)
    }

    /**
     * 重置后下一张要回到**本轮**第一个名字，而不是跳到下一轮
     *
     * 旧实现下这里会返回下一轮的第一个名字（因为 isRoundComplete 仍为 true），
     * 用户看到的就是"重置没用，还接着往下延续"。
     */
    @Test
    fun `重置后下一张回到本轮第一个名字且轮次不变`() {
        val batch = workBatch(shot = 3, round = 4)
        val reset = batch.withProgressReset()

        assertEquals("轮次不应改变", 4, reset.round)
        assertEquals("设备上架_拖车.jpg", reset.nextFileName())
        assertEquals("目录仍在本轮", "第4轮", reset.roundDirName)
    }

    /**
     * 重置后继续拍，会留在同一轮里，不会因为"已拍完"而自动推进
     */
    @Test
    fun `重置后继续拍留在同一轮`() {
        val reset = workBatch(shot = 3, round = 2).withProgressReset()
        val afterShot = reset.advancedAfterShot()

        assertEquals("拍一张后轮次不应变化", 2, afterShot.round)
        assertEquals("已拍一项", 1, afterShot.shotCount)
        assertEquals("设备上架_sn号.jpg", afterShot.nextFileName())
    }

    /**
     * 未拍完时重置同样安全（幂等感：再重置一次结果不变）
     */
    @Test
    fun `未拍完时重置也回到起点`() {
        val batch = workBatch(shot = 2, round = 3)
        val once = batch.withProgressReset()
        val twice = once.withProgressReset()

        assertEquals(once, twice)
        assertEquals("设备上架_拖车.jpg", twice.nextFileName())
    }

    // ==================== 回到第 1 轮（withRoundReset） ====================

    /**
     * 轮次归 1 并清空进度
     *
     * 轮次只增不减，跨天复用同一批次时会一路延续（当天目录里直接是「第5轮」），
     * 这个操作是唯一的归位入口。
     */
    @Test
    fun `回到第1轮会同时清空进度`() {
        val reset = workBatch(shot = 3, round = 5).withRoundReset()

        assertEquals(1, reset.round)
        assertEquals(0, reset.shotCount)
        assertTrue(reset.shotIndexes.isEmpty())
        assertEquals(0, reset.nextIndex)
        assertEquals("设备上架_拖车.jpg", reset.nextFileName())
        assertEquals("第1轮", reset.roundDirName)
    }

    /**
     * 已经在第 1 轮时再归位不会出问题
     */
    @Test
    fun `已是第1轮时归位结果不变`() {
        val batch = workBatch(shot = 1, round = 1)
        val reset = batch.withRoundReset()

        assertEquals(1, reset.round)
        assertEquals(0, reset.shotCount)
    }

    /**
     * 轮次永远不会被压到 0 或负数（目录名不能出现「第0轮」）
     */
    @Test
    fun `轮次下限为1`() {
        val weird = workBatch(shot = 0, round = 0)
        assertTrue("normalized 应把轮次夹到至少 1", weird.round >= 1)
        assertEquals("第1轮", weird.withRoundReset().roundDirName)
    }

    // ==================== 目录层级 ====================

    /**
     * 轮次目录跟随日期，顺序是 目录 / 日期 / 轮次
     */
    @Test
    fun `轮次目录拼在日期之后`() {
        val batch = workBatch(shot = 0, round = 3).copy(dateSubDir = true, dirName = "工作/设备上架")
        assertEquals("工作/设备上架/2026-09-17/第3轮", batch.relativeSubPath("2026-09-17"))
    }

    /**
     * 归位到第 1 轮后，当天目录重新从「第1轮」开始
     */
    @Test
    fun `归位当天目录重新从第1轮开始`() {
        val reset = workBatch(shot = 3, round = 5)
            .copy(dateSubDir = true, dirName = "工作/设备上架")
            .withRoundReset()

        assertEquals("工作/设备上架/2026-09-18/第1轮", reset.relativeSubPath("2026-09-18"))
    }
}
