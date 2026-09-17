/**
 * GroupInFileNameTest.kt - 「文件名带分组」的回归测试
 *
 * 需求：前缀是「上架」，分组是「2U」，希望文件名是 上架_2U_小推车状态.jpg；
 * 开关关掉时保持 上架_小推车状态.jpg。
 *
 * 两处容易出错的地方，这里都钉住：
 * 1. **重复拼接**：分组本身就叫「上架_4U」时，不能再拼成 上架_上架_4U_xxx.jpg
 * 2. **按组匹配**：开了这个开关后，文件名里带的是**各自组**的名字，
 *    所以按磁盘同步、找缩略图时都必须按"正在处理的那个组"算期望文件名，
 *    否则会拿当前组的前缀去匹配别的组的文件，判定全部落空
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
 * 文件名带分组的测试
 */
class GroupInFileNameTest {

    private val names = listOf("小推车状态", "sn号")

    private fun orderBatch(
        prefix: String = "上架",
        groups: List<String> = listOf("2U", "4U"),
        includeGroup: Boolean = true
    ) = BatchConfig(
        name = "今日工单",
        dirName = "工作",
        namePrefix = prefix,
        namingMode = NamingMode.WORK,
        nameList = names,
        dateSubDir = true,
        groupNames = groups,
        includeGroupInFileName = includeGroup
    ).normalized()

    // ==================== 开关的两种结果 ====================

    /**
     * 开关打开：前缀_组名_名字.jpg
     */
    @Test
    fun `开关打开时文件名带上分组`() {
        assertEquals("上架_2U_小推车状态.jpg", orderBatch().nextFileName())
    }

    /**
     * 开关关闭：保持旧命名（前缀_名字.jpg）
     */
    @Test
    fun `开关关闭时文件名为旧格式`() {
        assertEquals("上架_小推车状态.jpg", orderBatch(includeGroup = false).nextFileName())
    }

    /**
     * 切到另一组，文件名跟着换成那一组
     */
    @Test
    fun `切组后文件名里的组名跟着变`() {
        val second = orderBatch().withActiveGroup(1)
        assertEquals("上架_4U_小推车状态.jpg", second.nextFileName())
    }

    /**
     * 与分组无关的模式/场景不应受影响
     */
    @Test
    fun `前缀为空时直接用组名`() {
        // 表单里留空前缀时，normalized() 会补上默认前缀 IMG —— 这是既有行为，如实断言
        assertEquals("IMG_2U_小推车状态.jpg", orderBatch(prefix = "").nextFileName())

        // 前缀确实为空（未经归一化）时，文件名里只留组名
        val raw = orderBatch().copy(namePrefix = "")
        assertEquals(
            "2U_小推车状态.jpg",
            raw.buildCustomFileName("小推车状态", groupName = "2U")
        )
    }

    /**
     * 没启用分组时开关无效（不会凭空多出一段）
     */
    @Test
    fun `未启用分组时开关不生效`() {
        val noGroup = BatchConfig(
            name = "设备上架",
            dirName = "WorkA",
            namePrefix = "上架",
            namingMode = NamingMode.WORK,
            nameList = names,
            includeGroupInFileName = true
        ).normalized()
        assertEquals("上架_小推车状态.jpg", noGroup.nextFileName())
    }

    // ==================== 避免重复拼接 ====================

    /**
     * 分组本身就带前缀（上架_4U）时不再重复拼
     */
    @Test
    fun `组名已含前缀时不重复拼接`() {
        val batch = orderBatch(groups = listOf("上架_4U"))
        assertEquals("上架_4U_小推车状态.jpg", batch.nextFileName())
        assertFalse(
            "不该出现 上架_上架_4U",
            batch.nextFileName().startsWith("上架_上架")
        )
    }

    /**
     * 组名与前缀完全相同时也不重复
     */
    @Test
    fun `组名与前缀完全相同不重复拼接`() {
        val batch = orderBatch(prefix = "上架", groups = listOf("上架"))
        assertEquals("上架_小推车状态.jpg", batch.nextFileName())
    }

    /**
     * effectiveFilePrefix 的三条分支
     */
    @Test
    fun `前缀拼接规则`() {
        val b = orderBatch()                                     // 前缀 上架，分组 2U/4U
        assertEquals("上架_2U", b.effectiveFilePrefix("2U"))
        assertEquals("上架_4U", b.effectiveFilePrefix("4U"))
        assertEquals("上架_上架_4U".let { "上架_4U" }, orderBatch(groups = listOf("上架_4U")).effectiveFilePrefix("上架_4U"))
    }

    // ==================== 按组匹配（同步/缩略图） ====================

    /**
     * 同步时按各自组的前缀匹配：两个组各有自己的文件名，都要算对
     *
     * 这里是最容易出事的地方——若统一用"当前组"的前缀去匹配，
     * 另一组的文件会全部对不上，进度被判成未拍。
     */
    @Test
    fun `同步按各自组的前缀匹配进度`() {
        val batch = orderBatch()
        val synced = batch.syncedWithDiskGroups(
            mapOf(
                "2U" to mapOf(1 to listOf("上架_2U_小推车状态.jpg", "上架_2U_sn号.jpg")),
                "4U" to mapOf(1 to listOf("上架_4U_小推车状态.jpg"))
            )
        )

        assertEquals("2U 组应认出 2 项", 2, synced.groupShotCountOf("2U"))
        assertEquals("4U 组应认出 1 项", 1, synced.groupShotCountOf("4U"))
    }

    /**
     * 同步后重复执行结果不变（幂等）
     */
    @Test
    fun `带分组的文件名同步幂等`() {
        val batch = orderBatch()
        val disk = mapOf(
            "2U" to mapOf(1 to listOf("上架_2U_小推车状态.jpg")),
            "4U" to mapOf(1 to listOf("上架_4U_sn号.jpg"))
        )
        val once = batch.syncedWithDiskGroups(disk)
        assertEquals(once, once.syncedWithDiskGroups(disk))
    }

    /**
     * 关掉开关时，带组名的旧文件不该被误认为已拍
     * （命名规则变了，就该按新规则重拍，而不是把旧文件算进来）
     */
    @Test
    fun `关掉开关时带组名的文件不再匹配`() {
        val batch = orderBatch(includeGroup = false)
        val synced = batch.syncedWithDiskGroups(
            mapOf("2U" to mapOf(1 to listOf("上架_2U_小推车状态.jpg")))
        )
        assertEquals("命名规则已改，不该算作已拍", 0, synced.groupShotCountOf("2U"))
    }

    /**
     * 缩略图查找同样按当前组的前缀，能按文件名找到原图
     */
    @Test
    fun `缩略图查找按当前组前缀匹配`() {
        val batch = orderBatch()
        val diskNames = listOf("上架_2U_小推车状态.jpg", "上架_4U_小推车状态.jpg")

        assertEquals(
            "当前是 2U 组，应找到 2U 的那张",
            "上架_2U_小推车状态.jpg",
            batch.findDiskNameFor(0, diskNames)
        )
        assertEquals(
            "切到 4U 组后应找到 4U 的那张",
            "上架_4U_小推车状态.jpg",
            batch.withActiveGroup(1).findDiskNameFor(0, diskNames)
        )
    }

    /**
     * 重名去重后缀 " (1)" 在带组名的文件名上依然容错
     */
    @Test
    fun `带组名时仍容忍去重后缀`() {
        val batch = orderBatch()
        assertEquals(
            "上架_2U_小推车状态 (1).jpg",
            batch.findDiskNameFor(0, listOf("上架_2U_小推车状态 (1).jpg"))
        )
    }

    /**
     * 组名里的非法字符先被清洗，再进文件名，不会带出斜杠
     */
    @Test
    fun `组名清洗后再进文件名`() {
        val batch = orderBatch(groups = listOf("2U"))
        val weird = batch.effectiveFilePrefix("2U/3U")
        assertFalse("文件名里不能出现斜杠: $weird", weird.contains('/'))
        assertTrue("实际=$weird", weird.startsWith("上架_"))
    }
}
