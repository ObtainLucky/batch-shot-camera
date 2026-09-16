/**
 * WorkModeNamingTest.kt - 工作模式命名逻辑测试
 *
 * 「工作模式」= 按预设名字顺序给照片取名，例如
 * 前缀「设备上架」+ 名字列表「拖车」「sn号」 -> 设备上架_拖车.jpg、设备上架_sn号.jpg
 *
 * 命名一旦错了，现场拍完才发现（照片名不对/相互覆盖），所以这里把规则钉死：
 * 1. 按列表顺序逐张取用，顺序固定
 * 2. counter 同时充当列表下标，跨重启继续（持久化由 BatchConfig 的 counter 保证）
 * 3. 列表用完后回落到序号命名，**绝不重名覆盖**
 * 4. 序号模式的行为完全不受影响
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
 * 工作模式命名测试
 */
class WorkModeNamingTest {

    /** 造一个工作模式批次 */
    private fun workBatch(
        counter: Int = 0,
        names: List<String> = listOf("拖车", "sn号", "配电箱")
    ) = BatchConfig(
        name = "设备上架",
        dirName = "WorkA",
        namePrefix = "设备上架",
        namingMode = NamingMode.WORK,
        nameList = names,
        counter = counter,
        // 顺序拍了 counter 项：已拍 0..counter-1，下一张是 counter
        shotIndexes = (0 until counter.coerceAtMost(names.size)).toList(),
        nextIndex = counter.coerceAtMost((names.size - 1).coerceAtLeast(0))
    ).normalized()

    // ==================== 基本命名 ====================

    /**
     * 按列表顺序取名，正是需求里的例子
     */
    @Test
    fun workMode_namesFollowListOrder() {
        assertEquals("设备上架_拖车.jpg", workBatch(counter = 0).nextFileName())
        assertEquals("设备上架_sn号.jpg", workBatch(counter = 1).nextFileName())
        assertEquals("设备上架_配电箱.jpg", workBatch(counter = 2).nextFileName())
    }

    /**
     * 每拍一张 counter +1，取名就前进一个 —— 这是"顺序自定义"的核心
     */
    @Test
    fun workMode_advancesWithCounter() {
        var b = workBatch(counter = 0)
        val produced = mutableListOf<String>()
        repeat(3) {
            produced.add(b.nextFileName())
            b = b.advancedAfterShot()                           // 模拟存盘成功后推进
        }
        assertEquals(
            listOf("设备上架_拖车.jpg", "设备上架_sn号.jpg", "设备上架_配电箱.jpg"),
            produced
        )
    }

    /**
     * 前缀为空时只用名字本身
     */
    @Test
    fun workMode_withoutPrefix_usesNameOnly() {
        val b = workBatch(names = listOf("拖车")).copy(namePrefix = "")
        assertEquals("拖车.jpg", b.nextFileName())
    }

    // ==================== 兜底：列表用完 ====================

    /**
     * 列表用完必须回落到序号命名，而不是重复最后一个名字造成覆盖
     */
    @Test
    fun workMode_whenListExhausted_continuesInNextRound() {
        // 早期实现是"回落到序号命名"，那样文件名就不再是用户定义的名字；
        // 现在改成拍完自动进入下一轮，继续用同一套名字。
        val b = workBatch(counter = 2, names = listOf("拖车", "sn号"))

        val name = b.nextFileName()

        assertEquals("应重新用第一个名字", "设备上架_拖车.jpg", name)
        assertTrue("应标记本轮已拍完（供界面提示）", b.isNameListExhausted)
        assertEquals("落盘用的是下一轮的目录", 2, b.effectiveForNextShot().round)
    }

    /**
     * 工作模式但名字列表为空：同样回落到序号，不能产出空名字
     */
    @Test
    fun workMode_withEmptyList_fallsBackToSequence() {
        val b = workBatch(names = emptyList())
        assertEquals("设备上架_001.jpg", b.nextFileName())
    }

    // ==================== 进度与状态 ====================

    @Test
    fun workMode_reportsProgress() {
        // 进度从 1 开始数，且带上轮次（多轮时才知道自己在第几轮）
        assertEquals("第1轮 · 第 1/3 个名字", workBatch(counter = 0).workProgressLabel)
        assertEquals("第1轮 · 第 3/3 个名字", workBatch(counter = 2).workProgressLabel)
        assertEquals(
            "第2轮 · 第 1/3 个名字",
            workBatch(counter = 0).withNewRound().workProgressLabel
        )
        assertEquals("未设置名字列表", workBatch(names = emptyList()).workProgressLabel)
        // 序号模式没有这个进度概念
        assertEquals(null, sequenceBatch().workProgressLabel)
    }

    // ==================== 序号模式不受影响 ====================

    @Test
    fun sequenceMode_stillUsesIndexNaming() {
        val b = sequenceBatch()
        assertEquals("BA_001.jpg", b.nextFileName())
        assertEquals("BA_002.jpg", b.copy(counter = 1).nextFileName())
        assertFalse(b.isWorkMode)
    }

    private fun sequenceBatch() = BatchConfig(
        name = "A批",
        dirName = "BatchA",
        namePrefix = "BA",
        namingMode = NamingMode.SEQUENCE,
        counter = 0
    )

    // ==================== 名字清洗 ====================

    /**
     * 名字里的非法字符要去掉，顺手写上的扩展名也要去掉，
     * 否则会生成「设备上架_拖车.jpg.jpg」这种名字
     */
    @Test
    fun nameEntries_areSanitized() {
        assertEquals("拖车", BatchConfig.sanitizeNameEntry(" 拖车 "))
        assertEquals("拖车", BatchConfig.sanitizeNameEntry("拖车.jpg"))
        assertEquals("拖车", BatchConfig.sanitizeNameEntry("拖车.png"))
        assertEquals("拖车", BatchConfig.sanitizeNameEntry("拖/车"))       // 路径分隔符必须去掉
        assertEquals("", BatchConfig.sanitizeNameEntry("   "))

        val b = workBatch(names = listOf("拖车.jpg", "  ", "sn号"))
        assertEquals("设备上架_拖车.jpg", b.nextFileName())
        assertEquals("设备上架_sn号.jpg", b.advancedAfterShot().nextFileName())
        assertTrue("空名字应被剔除，effectiveNameList 只剩 2 项", b.effectiveNameList.size == 2)
    }

    /**
     * 归一化会把名字列表清洗后落库，保证存进去的就是干净的
     */
    @Test
    fun normalized_cleansNameList() {
        val b = workBatch(names = listOf(" 拖车.jpg ", "", "sn号", "   "))
        val normalized = b.normalized()
        assertEquals(listOf("拖车", "sn号"), normalized.nameList)
    }

    // ==================== 列表文本解析 ====================

    /**
     * 编辑框里每行一个名字（也兼容逗号分隔），空行忽略
     */
    @Test
    fun parseNameList_supportsLinesAndCommas() {
        val text = "拖车\nsn号\n\n配电箱"
        assertEquals(listOf("拖车", "sn号", "配电箱"), BatchConfig.parseNameList(text))

        assertEquals(
            listOf("拖车", "sn号"),
            BatchConfig.parseNameList("拖车，sn号")
        )
        assertEquals(emptyList<String>(), BatchConfig.parseNameList("   \n  "))
    }

    /**
     * 列表 -> 编辑文本 -> 列表 应当稳定（编辑保存不会丢名字）
     */
    @Test
    fun nameListRoundTripIsStable() {
        val names = listOf("拖车", "sn号", "配电箱")
        val text = BatchConfig.formatNameList(names)
        assertEquals(names, BatchConfig.parseNameList(text))
    }

    // ==================== 切换命名模式（用户实际踩到的坑） ====================

    /**
     * 已经拍过照片的序号批次切到工作模式，必须从第一个名字重新开始
     *
     * 这是真实反馈的 bug：counter 在两种模式下含义不同，
     * 不归零会直接从列表中间开始，甚至越界后静默回落到序号命名，
     * 表现为"明明填了名字，拍出来却没按名字命名"。
     */
    @Test
    fun switchToWorkMode_resetsCounterAndStartsFromFirstName() {
        val takenFiveInSequence = sequenceBatch().copy(counter = 5, startIndex = 1)

        val switched = takenFiveInSequence.withNamingMode(
            NamingMode.WORK,
            listOf("拖车", "sn号")
        )

        assertEquals("切换模式后计数应归零", 0, switched.counter)
        assertEquals("下一张应是第一个名字", "设备上架_拖车.jpg",
            switched.copy(namePrefix = "设备上架").nextFileName())
    }

    /**
     * 归零前如果下标越界，会出现"回落序号命名"的错误表现（回归防护）
     */
    /**
     * 模式没变时不应重置计数（只是改了名字列表）
     */
    @Test
    fun sameMode_keepsCounter() {
        val b = workBatch(counter = 1)
        val updated = b.withNamingMode(NamingMode.WORK, listOf("拖车", "sn号", "配电箱"))

        assertEquals("模式未变，计数必须保留", 1, updated.counter)
        assertEquals("设备上架_sn号.jpg", updated.nextFileName())
    }

    /**
     * 工作模式切回序号模式也要归零（两种模式的计数含义不同）
     */
    @Test
    fun switchBackToSequenceMode_resetsCounter() {
        val b = workBatch(counter = 3)
        val switched = b.withNamingMode(NamingMode.SEQUENCE)

        assertEquals(0, switched.counter)
        assertEquals("设备上架_001.jpg", switched.nextFileName())
        assertFalse(switched.isWorkMode)
    }

    // ==================== 列表用完的提示文案 ====================

    /**
     * 列表用完时不能显示"第 N/N 个名字"，那会让人以为还在按名字命名
     */
    @Test
    fun exhaustedLabel_tellsUserNextRoundIsAutomatic() {
        val exhausted = workBatch(counter = 3, names = listOf("拖车", "sn号", "配电箱"))

        val label = exhausted.workProgressLabel

        assertTrue(
            "应说明会继续拍摄会自动进入下一轮，实际=$label",
            label?.contains("自动进入第2轮") == true
        )
        assertFalse("不应再出现「回落序号」的旧说法", label?.contains("回落") == true)
    }

    /**
     * 正常情况下的进度从 1 开始数（更符合直觉）
     */
    @Test
    fun progressLabel_isOneBased() {
        assertEquals("第1轮 · 第 1/3 个名字", workBatch(counter = 0).workProgressLabel)
        assertEquals("第1轮 · 第 3/3 个名字", workBatch(counter = 2).workProgressLabel)
    }

    // ==================== 轮次（名字拍完之后继续拍同一套） ====================

    /**
     * 开始新一轮：轮次 +1、名字指针归零
     */
    @Test
    fun newRound_advancesRoundAndResetsPointer() {
        val finishedFirstRound = workBatch(counter = 3)          // 3 个名字已拍完
        assertTrue("前提：此时名字列表已用完", finishedFirstRound.isNameListExhausted)

        val second = finishedFirstRound.withNewRound()

        assertEquals("轮次应 +1", 2, second.round)
        assertEquals("名字指针应归零", 0, second.counter)
        assertFalse("新一轮不再是用完状态", second.isNameListExhausted)
        assertEquals("新一轮从第一个名字重新开始", "设备上架_拖车.jpg", second.nextFileName())
    }

    /**
     * 文件按轮次分目录，避免"同一套名字重复轮次必然重名"
     */
    @Test
    fun newRound_filesGoToSeparateRoundDir() {
        val first = workBatch(counter = 0)
        val second = first.withNewRound().withNewRound()          // 第 3 轮

        assertEquals("第1轮", first.roundDirName)
        assertEquals("第3轮", second.roundDirName)
        assertTrue("工作模式应启用轮次子目录", first.usesRoundSubDir)
        assertTrue("两轮落在不同目录，文件名可以完全相同",
            first.roundDirName != second.roundDirName)
    }

    /**
     * 序号模式不需要轮次分目录（文件名自带序号，不会重名）
     */
    @Test
    fun sequenceMode_doesNotUseRoundSubDir() {
        assertFalse(sequenceBatch().usesRoundSubDir)
    }

    /**
     * 工作模式但名字列表为空时也不分轮次（此时走序号命名）
     */
    @Test
    fun workModeWithoutNames_doesNotUseRoundSubDir() {
        assertFalse(workBatch(names = emptyList()).usesRoundSubDir)
    }

    /**
     * 轮次不允许小于 1（防止越界数据造出"第0轮"）
     */
    @Test
    fun round_isClampedToAtLeastOne() {
        val invalid = workBatch().copy(round = 0)
        assertEquals("第1轮", invalid.normalized().roundDirName)
        assertEquals("第1轮", workBatch().copy(round = -5).normalized().roundDirName)
    }

    // ==================== 作废上一张（回退一位） ====================

    /**
     * 作废后名字指针回退一位，下一张用同一个名字重拍
     */
    @Test
    fun decrementCounter_reusesPreviousName() {
        val atThird = workBatch(counter = 2)                      // 下一张是"配电箱"
        assertEquals("设备上架_配电箱.jpg", atThird.nextFileName())

        val undone = atThird.withCounterDecremented()

        assertEquals(1, undone.counter)
        assertEquals("回退后应重新用「sn号」这个名字", "设备上架_sn号.jpg", undone.nextFileName())
    }

    /**
     * 回退不会跌破 0（反复作废也不会出现负序号）
     */
    @Test
    fun decrementCounter_neverGoesBelowZero() {
        val alreadyZero = workBatch(counter = 0)
        assertEquals(0, alreadyZero.withCounterDecremented().counter)
        assertEquals(0, alreadyZero.withCounterDecremented().withCounterDecremented().counter)
        assertEquals("设备上架_拖车.jpg", alreadyZero.withCounterDecremented().nextFileName())
    }

    // ==================== 目录层级：目录 / 日期 / 轮次 ====================

    /**
     * 顺序必须是 目录/日期/轮次（日期在外、轮次在内）
     *
     * 早期实现把轮次放在日期外面（工作/第1轮/2026-09-16），
     * 按天翻看时同一天的多个轮次被拆散在两个地方，不符合使用习惯。
     */
    @Test
    fun relativePath_isDirThenDateThenRound() {
        val b = workBatch().copy(dirName = "工作", dateSubDir = true)

        assertEquals("工作/2026-09-16/第1轮", b.relativeSubPath("2026-09-16"))

        val secondRound = b.withNewRound()
        assertEquals("工作/2026-09-16/第2轮", secondRound.relativeSubPath("2026-09-16"))
    }

    /**
     * 不按日期分层时就是 目录/轮次
     */
    @Test
    fun relativePath_withoutDate_skipsDateSegment() {
        val b = workBatch().copy(dirName = "工作", dateSubDir = false)
        assertEquals("工作/第1轮", b.relativeSubPath(null))
    }

    /**
     * 目录名支持多级，层级原样保留
     */
    @Test
    fun relativePath_keepsMultiLevelDirName() {
        val b = workBatch().copy(dirName = "工作/设备上架", dateSubDir = true)
        assertEquals("工作/设备上架/2026-09-16/第1轮", b.relativeSubPath("2026-09-16"))
    }

    /**
     * 序号模式不带轮次段
     */
    @Test
    fun relativePath_sequenceModeHasNoRoundSegment() {
        val b = sequenceBatch().copy(dirName = "A批")
        assertEquals("A批", b.relativeSubPath(null))
        assertEquals("A批/2026-09-16", b.relativeSubPath("2026-09-16"))
    }

    // ==================== 多级目录名的清洗与校验 ====================

    @Test
    fun multiLevelDirName_isSanitizedPerSegment() {
        assertEquals("工作/设备上架", BatchConfig.sanitizeDirName("工作/设备上架"))
        assertEquals("工作/设备上架", BatchConfig.sanitizeDirName(" 工作 / 设备上架 "))
        assertEquals("工作/设备上架", BatchConfig.sanitizeDirName("/工作//设备上架/"))
        // 目录穿越必须被吃掉，而不是保留成可用的上级跳转
        assertEquals("工作/机密", BatchConfig.sanitizeDirName("工作/../机密"))
        assertEquals("工作", BatchConfig.sanitizeDirName("../工作"))
    }

    /**
     * 目录名/前缀两侧的空格必须被剥掉，不能变成下划线
     *
     * 清洗顺序写错（先做 空格->下划线 再 trim）会产出 "_工作_" 这种目录名，
     * 这个 bug 真实出现过，这里钉住。
     */
    @Test
    fun surroundingSpaces_areTrimmedNotUnderscored() {
        assertEquals("工作", BatchConfig.sanitizeDirName(" 工作 "))
        assertEquals("工作/设备上架", BatchConfig.sanitizeDirName(" 工作 / 设备上架 "))
        assertEquals("BA", BatchConfig.sanitizePrefix(" BA "))
        assertEquals("工作", BatchConfig.sanitizeNameEntry(" 工作 "))
        // 中间的空格仍然转下划线
        assertEquals("设备_上架", BatchConfig.sanitizeDirName("设备 上架"))
    }

    @Test
    fun multiLevelDirName_validation() {
        assertEquals(null, BatchConfig.validateDirName("工作/设备上架"))
        assertEquals(null, BatchConfig.validateDirName("工作"))
        assertTrue("空段要拦下", BatchConfig.validateDirName("工作//设备") != null)
        assertTrue("目录穿越要拦下", BatchConfig.validateDirName("工作/../机密") != null)
        assertTrue("空目录要拦下", BatchConfig.validateDirName("   ") != null)
    }

    // ==================== 拍完自动进入下一轮（无需手动切换） ====================

    /**
     * 本轮拍完后继续拍：自动进入下一轮，并重新从第一个名字开始
     *
     * 这是需求"没有手动切换的话自动创建轮次"的核心。
     */
    @Test
    fun exhausted_thenContinueShooting_rollsIntoNextRound() {
        val firstRoundDone = workBatch(counter = 3, names = listOf("拖车", "sn号", "配电箱"))
        assertTrue("前提：本轮已拍完", firstRoundDone.isNameListExhausted)

        val effective = firstRoundDone.effectiveForNextShot()

        assertEquals("自动进入第 2 轮", 2, effective.round)
        assertEquals("名字指针归零", 0, effective.counter)
        assertEquals("用第一个名字", "设备上架_拖车.jpg", effective.nextFileName())
        assertEquals(
            "落盘目录是新的轮次目录",
            "WorkA/第2轮",
            effective.relativeSubPath(null)
        )
    }

    /**
     * 拍完之后要持久化的状态：轮次已进、指针落在第二个名字上
     */
    @Test
    fun advancedAfterShot_persistsRollover() {
        val firstRoundDone = workBatch(counter = 3, names = listOf("拖车", "sn号", "配电箱"))

        val next = firstRoundDone.advancedAfterShot()

        assertEquals("轮次已推进", 2, next.round)
        assertEquals("计数为 1（新轮第一张已拍）", 1, next.counter)
        assertEquals("接着该拍第二个名字", "设备上架_sn号.jpg", next.nextFileName())
    }

    /**
     * 没拍完时照常计数，不该提前换轮
     */
    @Test
    fun notExhausted_advancesNormally() {
        val b = workBatch(counter = 0, names = listOf("拖车", "sn号"))
        val next = b.advancedAfterShot()

        assertEquals("轮次不变", 1, next.round)
        assertEquals(1, next.counter)
        assertEquals("设备上架_sn号.jpg", next.nextFileName())
    }

    /**
     * 空名字列表的工作模式不会无限换轮，仍走序号命名
     */
    @Test
    fun emptyNameList_neverRollsOver() {
        val b = workBatch(counter = 5, names = emptyList())
        assertEquals("没有名字可拍就不该换轮", 1, b.effectiveForNextShot().round)
        // advancedAfterShot 先计数再取名：counter 5 -> 6，序号 = 起始1 + 6 = 007
        assertEquals("设备上架_007.jpg", b.advancedAfterShot().nextFileName())
    }

    /**
     * 名字指针在显示与落盘上是同一个值（防止界面与成片对不上）
     */
    @Test
    fun displayedNextName_matchesSavedName() {
        listOf(0, 1, 2, 3, 4).forEach { c ->
            val b = workBatch(counter = c, names = listOf("拖车", "sn号", "配电箱"))
            val effective = b.effectiveForNextShot()
            assertEquals(
                "counter=$c 时显示与落盘必须一致",
                b.nextFileName(),
                effective.nextFileName()
            )
        }
    }

    // ==================== 批次备注 ====================

    /**
     * 备注跟批次走：批次有备注时优先，为空则由全局备注兜底（兜底在 Provider 里做）
     */
    @Test
    fun note_belongsToBatch() {
        val withNote = workBatch().copy(note = "段嘉轩 13297470239")
        assertEquals("段嘉轩 13297470239", withNote.note)

        val blank = workBatch().copy(note = "   ")
        assertEquals("未填备注时保持原样，由上层决定是否回落到全局备注", "   ", blank.note)
    }

    // ==================== 名字指针（清单跳拍） ====================

    /**
     * 指到某一项后，下一张就是那一项
     */
    @Test
    fun withNamePointer_chooseWhichItemToShootNext() {
        val b = workBatch(names = listOf("拖车", "sn号", "配电箱"))

        assertEquals("设备上架_拖车.jpg", b.withNamePointer(0).nextFileName())
        assertEquals("设备上架_sn号.jpg", b.withNamePointer(1).nextFileName())
        assertEquals("设备上架_配电箱.jpg", b.withNamePointer(2).nextFileName())
    }

    /**
     * 指针会被夹到合法区间，越界不会崩
     */
    @Test
    fun withNamePointer_isClamped() {
        val b = workBatch(names = listOf("拖车", "sn号"))

        assertEquals("负数夹到 0", 0, b.withNamePointer(-3).effectiveNextIndex)
        assertEquals("超上限夹到最后一项", 1, b.withNamePointer(99).effectiveNextIndex)
        assertEquals("设备上架_sn号.jpg", b.withNamePointer(99).nextFileName())
        assertEquals("跳拍不该改动已拍状态", 0, b.withNamePointer(99).shotCount)
    }

    /**
     * 清单里的"已拍/未拍"由指针推导
     */
    @Test
    fun isNameShot_reflectsPointer() {
        val b = workBatch(counter = 2, names = listOf("拖车", "sn号", "配电箱"))

        assertTrue("第 1 项已拍", b.isNameShot(0))
        assertTrue("第 2 项已拍", b.isNameShot(1))
        assertFalse("第 3 项未拍", b.isNameShot(2))
    }

    /**
     * 空名字列表时指针操作不做越界的事
     */
    @Test
    fun withNamePointer_onEmptyList_isSafe() {
        val b = workBatch(names = emptyList())
        assertEquals(0, b.withNamePointer(5).counter)
        assertEquals("设备上架_001.jpg", b.withNamePointer(5).nextFileName())
    }

    // ==================== 跳拍、补拍、重拍（逐项状态） ====================

    /**
     * 跳着拍：先拍第 3 项，前两项必须保持"未拍"等待补拍
     *
     * 这是需求核心：不能用"指针之前的都算已拍"来推导。
     */
    @Test
    fun skipAhead_earlierItemsStayUnshot() {
        val fresh = workBatch(counter = 0, names = listOf("拖车", "sn号", "配电箱"))

        // 点第 3 项 -> 指针指过去，但 1、2 项仍是未拍
        val jumped = fresh.withNamePointer(2)

        assertEquals("设备上架_配电箱.jpg", jumped.nextFileName())
        assertFalse("第 1 项应保持未拍", jumped.isNameShot(0))
        assertFalse("第 2 项应保持未拍", jumped.isNameShot(1))
        assertFalse("第 3 项还没拍", jumped.isNameShot(2))
        // 被跳过的是指针前面的两项（第 3 项是"下一张"，不算待补拍）
        assertEquals("前两项待补拍", listOf(0, 1), jumped.pendingIndexes)
    }

    /**
     * 拍完跳过去的那一项后，指针自动回头去补拍前面漏的
     */
    @Test
    fun afterSkippedShot_pointerGoesBackToPending() {
        val fresh = workBatch(counter = 0, names = listOf("拖车", "sn号", "配电箱"))
        val jumped = fresh.withNamePointer(2)                       // 先拍第 3 项

        val afterShot = jumped.advancedAfterShot()

        assertTrue("第 3 项已拍", afterShot.isNameShot(2))
        assertEquals("指针应回头指向第 1 项", 0, afterShot.effectiveNextIndex)
        assertEquals("设备上架_拖车.jpg", afterShot.nextFileName())
        // 指针已回头指向第 1 项，所以没有"被跳过且落在指针之前"的项
        assertTrue("指针之前的都被补掉了", afterShot.pendingIndexes.isEmpty())
    }

    /**
     * 顺序拍摄时状态正常推进，不会出现待补拍
     */
    @Test
    fun sequentialShooting_hasNoPending() {
        var b = workBatch(counter = 0, names = listOf("拖车", "sn号", "配电箱"))
        assertEquals("设备上架_拖车.jpg", b.nextFileName())

        b = b.advancedAfterShot()
        assertEquals("设备上架_sn号.jpg", b.nextFileName())
        assertEquals("已拍 1 项", 1, b.shotCount)
        assertTrue("顺序拍不应有待补拍", b.pendingIndexes.isEmpty())

        b = b.advancedAfterShot()
        assertEquals("设备上架_配电箱.jpg", b.nextFileName())
        assertTrue(b.pendingIndexes.isEmpty())
    }

    /**
     * 点已拍的项 = 重拍：把它从已拍里移除并指过去
     */
    @Test
    fun reshootShotItem_unmarksAndPointsToIt() {
        val b = workBatch(counter = 3, names = listOf("拖车", "sn号", "配电箱"))
        assertTrue("前提：三项都拍过", b.isRoundComplete)

        val reshoot = b.withReshoot(1)                              // 重拍第 2 项

        assertFalse("第 2 项应回到未拍", reshoot.isNameShot(1))
        assertTrue("第 1 项仍算已拍", reshoot.isNameShot(0))
        assertTrue("第 3 项仍算已拍", reshoot.isNameShot(2))
        assertEquals("下一张就是被重拍的那一项", 1, reshoot.effectiveNextIndex)
        assertEquals("设备上架_sn号.jpg", reshoot.nextFileName())
    }

    /**
     * 重拍后本轮不再是"完成"状态（否则会误判为要进下一轮）
     */
    @Test
    fun reshoot_makesRoundIncompleteAgain() {
        val done = workBatch(counter = 3, names = listOf("拖车", "sn号", "配电箱"))
        assertTrue(done.isRoundComplete)

        val reshoot = done.withReshoot(2)
        assertFalse("重拍后本轮不算完成", reshoot.isRoundComplete)
        assertFalse("也不该自动进入下一轮", reshoot.effectiveForNextShot().round == 2)
    }

    /**
     * 重拍后再拍一张，该项重新变回已拍
     */
    @Test
    fun reshootThenShoot_marksShotAgain() {
        val done = workBatch(counter = 3, names = listOf("拖车", "sn号", "配电箱"))
        val after = done.withReshoot(0).advancedAfterShot()

        assertTrue("重拍后应重新标记为已拍", after.isNameShot(0))
        assertEquals("本轮重新完成", 3, after.shotCount)
        assertTrue("再次完成本轮", after.isRoundComplete)
    }

    /**
     * 作废上一张：移除最后拍的那一项，并指回它
     */
    @Test
    fun undoLastShot_removesLastShotItem() {
        val b = workBatch(counter = 0, names = listOf("拖车", "sn号", "配电箱"))
            .withNamePointer(2).advancedAfterShot()                 // 先拍第 3 项
            .advancedAfterShot()                                    // 再补拍第 1 项

        val undone = b.withCounterDecremented()

        assertFalse("最后拍的（第 1 项）应回到未拍", undone.isNameShot(0))
        assertTrue("之前拍的第 3 项不受影响", undone.isNameShot(2))
        assertEquals("指针指回被作废的那一项", 0, undone.effectiveNextIndex)
    }

    /**
     * 旧数据迁移：以前只有 counter，升级后已拍状态不能丢
     */
    @Test
    fun legacyCounter_isMigratedToShotIndexes() {
        val legacy = BatchConfig(
            name = "设备上架",
            dirName = "WorkA",
            namePrefix = "设备上架",
            namingMode = NamingMode.WORK,
            nameList = listOf("拖车", "sn号", "配电箱"),
            counter = 2,                       // 旧字段：已拍 2 张
            shotIndexes = emptyList(),
            nextIndex = 0
        ).normalized()

        assertEquals("应迁移为前两项已拍", listOf(0, 1), legacy.shotIndexes)
        assertEquals("下一张应接着第 3 项", 2, legacy.effectiveNextIndex)
        assertEquals("设备上架_配电箱.jpg", legacy.nextFileName())
        assertEquals("counter 同步为已拍项数", 2, legacy.counter)
    }
}
