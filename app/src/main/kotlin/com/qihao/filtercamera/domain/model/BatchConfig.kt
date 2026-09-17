/**
 * BatchConfig.kt - 批次配置领域模型
 *
 * 「批次拍摄」的核心概念：一次分组拍摄的定义。
 * 用户在拍摄前选中某个批次，之后每一张照片都按该批次的规则命名，
 * 并归入该批次对应的相册目录。
 *
 * 命名规则：前缀 + 序号，如 BA_001.jpg / BA_002.jpg ...
 * 目录规则：Pictures/{dirName}/，可选再按 yyyy-MM-dd 分子目录
 *
 * 关键约束：
 * 序号由 [counter] 自行维护并持久化，绝不依赖文件系统或 MediaStore 去重
 * （MediaStore 遇到重名文件会自动追加 " (1)" 后缀，会打乱连续编号）
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.domain.model

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 批次配置
 *
 * @param id 批次唯一标识（持久化后不可变）
 * @param name 批次显示名，如 "A批-8月货"
 * @param dirName 相册目录名，如 "BatchA" -> Pictures/BatchA/
 * @param namePrefix 文件名前缀，如 "BA" -> BA_001.jpg
 * @param startIndex 起始序号，如 101 -> XJ_0101.jpg
 * @param indexWidth 序号补零位数，3 -> 001
 * @param counter 已拍张数（持久化，跨重启累加，不重置）
 * @param dateSubDir 是否再按日期分子目录，即 Pictures/BatchA/2026-09-16/
 */
/**
 * 单个组的进度快照
 *
 * 只用于"切走之后还能切回来接着拍"，是 [BatchConfig.groupStates] 的元素。
 *
 * @param name 组名，如「上架_4U」
 * @param round 该组的第几遍（只有同一组要重拍时才会大于 1）
 * @param shotIndexes 该组已拍的名单下标
 * @param nextIndex 该组下一张要拍的名字下标
 */
@Serializable
data class BatchGroupState(
    val name: String,
    val round: Int = 1,
    val shotIndexes: List<Int> = emptyList(),
    val nextIndex: Int = 0
)

/**
 * 单轮的进度快照
 *
 * 用于"哪一轮拍到一半被临时打断、之后要回到那一轮继续拍"：
 * 第 2 轮没拍完 -> 紧急切到第 3 轮 -> 回来时第 2 轮的进度还在。
 *
 * @param group 所属分组名（未启用分组时为 null）
 * @param round 第几轮
 * @param shotIndexes 该轮已拍下标
 * @param nextIndex 该轮下一张要拍的名字下标
 */
@Serializable
data class BatchRoundState(
    val group: String? = null,
    val round: Int = 1,
    val shotIndexes: List<Int> = emptyList(),
    val nextIndex: Int = 0
)

/**
 * 批次命名模式
 *
 * @param displayName 显示名称
 * @param description 说明文案
 */
@Serializable
enum class NamingMode(
    val displayName: String,
    val description: String
) {
    /** 前缀 + 递增序号，如 设备上架_001.jpg */
    SEQUENCE("序号模式", "前缀 + 递增序号"),

    /**
     * 工作模式：按预设名字顺序取名
     *
     * 例如名称列表填「拖车」「sn号」、前缀为「设备上架」，
     * 则依次生成 设备上架_拖车.jpg、设备上架_sn号.jpg。
     * 列表用完后回落到序号命名，绝不重名覆盖已拍照片。
     */
    WORK("工作模式", "按预设名字顺序取名")
}

@Serializable
data class BatchConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val dirName: String,
    val namePrefix: String,
    val startIndex: Int = DEFAULT_START_INDEX,
    val indexWidth: Int = DEFAULT_INDEX_WIDTH,
    val counter: Int = 0,
    val dateSubDir: Boolean = false,
    /** 命名模式：序号 / 工作模式（按预设名字顺序） */
    val namingMode: NamingMode = NamingMode.SEQUENCE,
    /** 工作模式下的名字列表（按拍摄顺序取用），counter 同时充当列表下标 */
    val nameList: List<String> = emptyList(),
    /**
     * 轮次（第几轮拍摄）
     *
     * 工作模式的名字是固定的，重复拍摄必然重名，所以用轮次把它们分开归档：
     * Pictures/WorkA/第1轮/设备上架_拖车.jpg、Pictures/WorkA/第2轮/设备上架_拖车.jpg
     */
    val round: Int = 1,
    /**
     * 本批次的备注（水印里的「备注」行）
     *
     * 全局备注适合"设备型号"这类通用内容，但现场每个批次/每轮的联系人、项目名往往不同，
     * 所以备注跟批次走：批次备注非空时优先于全局备注。
     */
    val note: String = "",
    /**
     * 本轮**已拍**的名字下标（按拍摄先后追加，工作模式使用）
     *
     * 不能用"指针之前的都算已拍"来推导：跳着拍（先拍第 3 项）时，
     * 前面第 1、2 项其实还没拍，必须保持未拍等待补拍。
     */
    val shotIndexes: List<Int> = emptyList(),
    /**
     * 下一张要拍的名字下标（工作模式使用）
     *
     * 正常拍完一项后会自动前移到"第一个未拍的名字"，于是跳过的项会被自动回头补拍；
     * 手动点某一项则直接指到那一项。
     */
    val nextIndex: Int = 0,

    // ==================== 分组（工单里的"每组"） ====================

    /**
     * 分组名列表（有序），如 ["上架_4U", "上架_2U", "下架_4U"]
     *
     * 现场流程：一天的工单里，上架/下架各自还可能按 U 数分成几组（4U、2U），
     * 每组都要把同一份拍摄清单走一遍。这跟"轮次"不是一回事 ——
     * 轮次是"同一组拍第二遍"，分组是"并列的几个组"。
     *
     * 空列表表示不使用分组，行为与旧版本完全一致。
     */
    val groupNames: List<String> = emptyList(),

    /**
     * 当前所在组的下标
     */
    val activeGroupIndex: Int = 0,

    /**
     * 各组进度快照（**包含当前组**）
     *
     * 当前组的进度真正的存放处是 [round] / [shotIndexes] / [nextIndex]，
     * 这里同时留一份快照，是为了切组时能原样恢复、切回来接着拍。
     * 由 [normalized] 统一刷新，调用方不需要手动维护。
     */
    val groupStates: List<BatchGroupState> = emptyList(),

    /**
     * 各轮进度快照（**包含当前轮**）
     *
     * 与 [groupStates] 同理：当前轮的进度真正的存放处是
     * [round] / [shotIndexes] / [nextIndex]，这里留一份快照，
     * 是为了能在轮次之间来回跳而各自保留进度。
     * 由 [normalized] 统一刷新。
     */
    val roundStates: List<BatchRoundState> = emptyList(),

    /**
     * 一组拍完后是否自动切到下一组
     *
     * 现场默认是"切"（4U 拍完接着拍 2U），但也允许关掉 ——
     * 有时需要先留在本组把漏拍的补完再走。
     */
    val autoAdvanceGroup: Boolean = true,

    /**
     * 文件名里是否带上分组名
     *
     * 打开后：前缀「上架」+ 分组「2U」 -> 上架_2U_小推车状态.jpg
     * 关闭后：上架_小推车状态.jpg（与旧版本一致）
     *
     * 分组已经包含前缀时不会重复拼接（分组叫「上架_4U」、前缀也叫「上架」时，
     * 结果仍是 上架_4U_xxx.jpg，而不是 上架_上架_4U_xxx.jpg）。
     */
    val includeGroupInFileName: Boolean = false
) {

    /**
     * 生成文件名，如 BA_001.jpg
     *
     * @param seq 序号（通常传 [nextSeq]）
     * @param ext 扩展名，默认 jpg
     */
    fun buildFileName(seq: Int, ext: String = PHOTO_EXTENSION): String {
        val width = indexWidth.coerceIn(MIN_INDEX_WIDTH, MAX_INDEX_WIDTH)
        val number = seq.coerceAtLeast(0).toString().padStart(width, '0')
        return "${sanitizePrefix(namePrefix)}_$number.$ext"
    }

    /**
     * 下一张的序号 = 起始序号 + 已拍张数
     */
    fun nextSeq(): Int = startIndex + counter

    /**
     * 下一张的文件名，如 BA_001.jpg
     *
     * 这是「批次条」上展示"下一张"的核心 API。
     */
    fun nextFileName(): String {
        if (isWorkMode) {
            workNameFor(effectiveForNextShot())?.let { return it }
        }
        return buildFileName(nextSeq())
    }

    /**
     * 下一张要拍哪一个名字（工作模式；完成本轮时指向下一轮的第一个）
     */
    val effectiveNextIndex: Int
        get() {
            val total = effectiveNameList.size
            if (total == 0) return 0
            return nextIndex.coerceIn(0, total - 1)
        }

    /**
     * 拍下一张时**生效**的配置
     *
     * 工作模式的名字列表拍完后不需要手动点「新一轮」：
     * 继续拍会自动进入下一轮（轮次 +1、名字指针归零），文件落到新的轮次目录。
     * 例：工作/2026-09-16/第1轮/ 拍完 -> 继续拍 -> 工作/2026-09-16/第2轮/
     *
     * 命名与落盘路径都必须基于这个"生效配置"，否则会出现
     * "界面显示的下一张"与"实际存盘"不一致。
     */
    fun effectiveForNextShot(): BatchConfig =
        if (isWorkMode && isRoundComplete) withNewRound() else this

    /**
     * 取指定配置下本轮要用的名字（名字列表为空时返回 null）
     */
    private fun workNameFor(config: BatchConfig): String? =
        config.effectiveNameList.getOrNull(config.effectiveNextIndex)
            ?.let { config.buildCustomFileName(it) }

    /**
     * 一张拍完后应持久化的状态
     *
     * 内含"自动进入下一轮"：本轮已拍完时先把轮次推进一步再计数，
     * 于是下一张自然就是新一轮的第一个名字。
     */
    fun advancedAfterShot(): BatchConfig {
        val effective = effectiveForNextShot()

        // 名字列表为空的工作模式实际是在走序号命名，这里要按序号模式推进，
        // 否则计数永远不动、文件名一直是 001。
        if (!effective.isWorkMode || effective.effectiveNameList.isEmpty()) {
            return effective.copy(counter = effective.counter + 1).normalized()
        }

        // 工作模式：把这一项记为已拍，然后把指针前移到"第一个未拍的名字"——
        // 于是手动跳过的那几项会在后续自动被回头补拍。
        val shot = (effective.shotIndexes + effective.effectiveNextIndex).distinct()
        val nextUnshot = effective.effectiveNameList.indices.firstOrNull { it !in shot } ?: 0
        val recorded = effective.copy(
            shotIndexes = shot,
            nextIndex = nextUnshot
        ).normalized()

        // 本组拍完 + 还有下一组 + 开着自动切组 -> 直接切到下一组。
        // 现场流程就是这样：4U 拍完接着拍 2U，而不是原地再拍一遍 4U。
        if (recorded.usesGroups &&
            recorded.autoAdvanceGroup &&
            recorded.hasNextGroup &&
            recorded.isRoundComplete
        ) {
            return recorded.withActiveGroup(recorded.safeActiveGroupIndex + 1)
        }

        return recorded
    }

    /**
     * 生成「前缀_自定义文字」形式的文件名
     *
     * @param item 自定义文字，如「拖车」
     */
    fun buildCustomFileName(
        item: String,
        ext: String = PHOTO_EXTENSION,
        groupName: String? = activeGroupName
    ): String {
        val prefix = effectiveFilePrefix(groupName)
        val safeItem = sanitizeNameEntry(item)
        return when {
            prefix.isEmpty() -> "$safeItem.$ext"
            safeItem.isEmpty() -> "$prefix.$ext"
            else -> "${prefix}_$safeItem.$ext"
        }
    }

    /**
     * 实际用于文件名的前缀
     *
     * 开启 [includeGroupInFileName] 且当前有分组时，把组名接在前缀后面：
     * 上架 + 2U -> 上架_2U。
     *
     * 两种避免重复的处理：
     * - 组名本身就是 上架_4U（已含前缀）-> 直接用组名，不重复拼
     * - 前缀为空 -> 直接用组名
     *
     * @param groupName 目标分组名，默认取当前组
     */
    fun effectiveFilePrefix(groupName: String? = activeGroupName): String {
        val prefix = sanitizePrefix(namePrefix)
        if (!includeGroupInFileName || !usesGroups) return prefix

        val group = sanitizeGroupName(groupName ?: return prefix)
        if (group.isEmpty()) return prefix
        if (prefix.isEmpty()) return group

        // 组名已包含前缀（上架_4U 对前缀 上架）时不再重复拼接
        val alreadyPrefixed = group.equals(prefix, ignoreCase = true) ||
            group.startsWith("${prefix}_", ignoreCase = true)
        return if (alreadyPrefixed) group else "${prefix}_$group"
    }

    /** 清洗后的名字列表（去掉空项与非法字符） */
    val effectiveNameList: List<String>
        get() = nameList.map { sanitizeNameEntry(it) }.filter { it.isNotEmpty() }

    /** 是否为工作模式 */
    val isWorkMode: Boolean
        get() = namingMode == NamingMode.WORK

    /** 本轮是否已全部拍完（仅工作模式可能为 true） */
    val isRoundComplete: Boolean
        get() = isWorkMode && effectiveNameList.isNotEmpty() &&
            shotIndexes.distinct().size >= effectiveNameList.size

    /** 兼容旧命名：本轮是否已拍完 */
    val isNameListExhausted: Boolean
        get() = isRoundComplete

    /** 已拍项数（工作模式即本轮进度） */
    val shotCount: Int
        get() = if (isWorkMode) shotIndexes.distinct().size else counter

    /**
     * 待补拍的名字下标：**被跳过**的项（未拍，且排在当前指针之前）
     *
     * 指针之后的未拍项只是"还没轮到"，不算待补拍，
     * 否则清单里满屏都是"待补拍"，看不出真正漏掉的是哪几项。
     */
    val pendingIndexes: List<Int>
        get() = if (!isWorkMode) emptyList()
        else effectiveNameList.indices.filter {
            it !in shotIndexes && it < effectiveNextIndex
        }

    /** 工作模式进度文案；非工作模式返回 null */
    val workProgressLabel: String?
        get() = if (!isWorkMode) null else {
            val total = effectiveNameList.size
            // 有分组时文案里要带组名：现场关心的是"哪一组拍到哪了"
            val where = activeGroupName?.let { if (round > 1) "$it·第${round}轮" else it }
                ?: "第${round}轮"
            when {
                total == 0 -> "未设置名字列表"
                isRoundComplete -> when {
                    // 还有下一组：继续拍会自动切过去
                    hasNextGroup && autoAdvanceGroup -> "$where 已拍完 · 继续拍自动进入下一组"
                    hasNextGroup -> "$where 已拍完 · 请手动切到下一组"
                    else -> "$where 已拍完 · 继续拍自动进入第${round + 1}轮"
                }
                // 用 shotCount 而不是 counter：counter 只是 normalized() 从已拍下标
                // 反推出来的**镜像值**，旧的 resetCounter 正是"只写 counter"，
                // 导致它与真实进度脱节、进度显示乱掉
                else -> "$where · 第 ${shotCount + 1}/$total 个名字"
            }
        }

    /**
     * 模式无关的进度摘要，供界面统一取用
     *
     * 序号模式看的是一个真实的计数器，工作模式看的是本轮已拍项数，
     * 两者不能混用同一个字段（混用正是"统计不对"的来源）。
     */
    val progressSummary: String
        get() = workProgressLabel ?: "已拍 $counter 张"

    /**
     * 轮次子目录名，如「第2轮」
     *
     * 仅工作模式使用；序号模式的文件名自带序号，不会重名，不需要分层。
     */
    val roundDirName: String
        get() = "第${round.coerceAtLeast(1)}轮"

    /**
     * 是否需要在批次目录下再分轮次子目录
     */
    val usesRoundSubDir: Boolean
        get() = isWorkMode && effectiveNameList.isNotEmpty()

    // ==================== 分组 ====================

    /** 是否启用了分组（分组名列表非空） */
    val usesGroups: Boolean
        get() = groupNames.isNotEmpty()

    /** 当前组下标（夹到合法区间） */
    val safeActiveGroupIndex: Int
        get() = activeGroupIndex.coerceIn(0, (groupNames.size - 1).coerceAtLeast(0))

    /** 当前组名；未启用分组时为 null */
    val activeGroupName: String?
        get() = if (usesGroups) groupNames[safeActiveGroupIndex] else null

    /** 是否还有下一组（决定拍完本组是切组还是原地开新一轮） */
    val hasNextGroup: Boolean
        get() = usesGroups && safeActiveGroupIndex < groupNames.size - 1

    /**
     * 取某一组的进度快照（没有记录时返回一个全新的空进度）
     */
    fun groupStateOf(name: String): BatchGroupState =
        groupStates.firstOrNull { it.name == name } ?: BatchGroupState(name)

    /**
     * 取某一组拍完的项数（用于界面上的各组进度展示）
     */
    fun groupShotCountOf(name: String): Int =
        groupStateOf(name).shotIndexes.distinct().size

    /**
     * 切换到指定分组
     *
     * 切走前先把当前组的进度存成快照，再把目标组的快照恢复出来，
     * 于是"拍了 4U 一组 -> 切到 2U -> 又切回 4U"能接着原来的进度，
     * 而不是把 4U 已拍的全部丢掉。
     *
     * @param index 目标组下标（自动夹到合法区间）
     */
    fun withActiveGroup(index: Int): BatchConfig {
        if (!usesGroups) return this
        val target = index.coerceIn(0, groupNames.size - 1)
        if (target == safeActiveGroupIndex) return this

        // 先归一化，保证 groupStates 里当前组的快照是最新的
        val canonical = normalized()
        val restored = canonical.groupStates.firstOrNull { it.name == groupNames[target] }

        return canonical.copy(
            activeGroupIndex = target,
            round = restored?.round ?: 1,
            shotIndexes = restored?.shotIndexes ?: emptyList(),
            nextIndex = restored?.nextIndex ?: 0,
            counter = 0
        ).normalized()
    }

    /**
     * 把当前组的进度刷进 groupStates（顺带清掉已被删掉的组的残留）
     */
    private fun refreshedGroupStates(): List<BatchGroupState> {
        val name = activeGroupName ?: return groupStates
        val snapshot = BatchGroupState(
            name = name,
            round = round.coerceAtLeast(1),
            shotIndexes = shotIndexes,
            nextIndex = nextIndex
        )
        return (groupStates.filterNot { it.name == name } + snapshot)
            .filter { it.name in groupNames }
            .sortedBy { groupNames.indexOf(it.name) }
    }

    /**
     * 批次目录之下的相对路径（不含 Pictures/ 前缀）
     *
     * 层级顺序：**目录 / 日期 / 组名 / 轮次**
     * - 未启用分组：目录 / 日期 / 第N轮（与旧版本一致）
     * - 启用分组：目录 / 日期 / 组名，且第 1 遍不再多一层「第1轮」
     *   （只拍一遍的组不需要轮次层；真正重拍时才出现 第2轮）
     *
     * 目录名本身可以写成多级（如「工作/设备上架」），会被原样保留层级。
     *
     * @param dateStamp 日期字符串（如 2026-09-16），为空表示不按日期分层
     */
    fun relativeSubPath(dateStamp: String? = null): String {
        val segments = buildList {
            add(safeDirName.ifEmpty { DEFAULT_DIR_NAME })
            dateStamp?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (usesGroups) {
                activeGroupName?.let { add(sanitizeGroupName(it)) }
                if (round > 1) add(roundDirName)
            } else if (usesRoundSubDir) {
                add(roundDirName)
            }
        }
        return segments.joinToString("/")
    }

    /**
     * 开始新一轮
     *
     * 轮次 +1 并把名字序号归零，于是可以拿着同一套名字从头再拍一遍，
     * 文件落在新的轮次子目录里，不与上一轮冲突。
     */
    fun withNewRound(): BatchConfig = copy(
        round = round.coerceAtLeast(1) + 1,
        counter = 0,
        shotIndexes = emptyList(),
        nextIndex = 0
    ).normalized()

    /**
     * 跳到指定轮次（保留那一轮已有的进度）
     *
     * 现场用途：第 2 轮拍到一半被临时叫去拍第 3 轮，忙完要回到第 2 轮接着拍。
     * 每轮的进度各存一份快照，跳过去再跳回来不会丢。
     *
     * @param target 目标轮次（小于 1 会被夹到 1）
     */
    fun withRound(target: Int): BatchConfig {
        val safe = target.coerceAtLeast(1)
        if (safe == round.coerceAtLeast(1)) return this

        // 先归一化，保证 roundStates 里当前轮的快照是最新的
        val canonical = normalized()
        val group = canonical.activeGroupName
        val restored = canonical.roundStates
            .firstOrNull { it.round == safe && it.group == group }

        return canonical.copy(
            round = safe,
            shotIndexes = restored?.shotIndexes ?: emptyList(),
            nextIndex = restored?.nextIndex ?: 0,
            counter = 0
        ).normalized()
    }

    /**
     * 当前已用过的轮次（升序），用于界面列出可跳转的轮次
     *
     * 至少包含 1 与当前轮，这样"跳到还没拍过的下一轮"也能直接选。
     */
    val usedRounds: List<Int>
        get() {
            val current = round.coerceAtLeast(1)
            // 已用过的轮次 + 第 1 轮 + 当前轮 + 当前轮的下一轮：
            // 这样列表里始终能直接选到"下一轮"，不用手输
            return (roundStates.map { it.round } + current + 1 + (current + 1))
                .distinct()
                .sorted()
        }

    /**
     * 取某一轮的已拍项数（用于界面展示各轮进度）
     */
    fun roundShotCountOf(target: Int): Int {
        val group = activeGroupName
        val state = roundStates.firstOrNull { it.round == target && it.group == group }
        return if (target == round.coerceAtLeast(1)) shotCount else state?.shotIndexes?.size ?: 0
    }

    /**
     * 清空本轮进度，从本轮第一个名字重新开始（轮次保持不变）
     *
     * 工作模式的真实进度在 [shotIndexes] / [nextIndex] 里，[counter] 只是
     * normalized() 反推出的镜像。所以"只把 counter 归零"是一次**假重置**：
     * 已拍清单纹丝不动、指针也不动，下一张还会因为本轮已拍完而直接跳到下一轮
     * —— 用户看到的就是"点了重置却没有任何变化，轮次一路往下延续"。
     *
     * 已拍的照片不会被删除，重拍会由 MediaStore 追加 " (1)" 后缀区分。
     */
    fun withProgressReset(): BatchConfig = copy(
        counter = 0,
        shotIndexes = emptyList(),
        nextIndex = 0
    ).normalized()

    /**
     * 轮次归 1 并清空进度（真正意义上的"从头再来"）
     *
     * [withNewRound] 只会 +1，轮次因此只增不减。跨天继续用同一个批次时，
     * 会接着前一天的轮次继续数下去，于是当天目录里直接出现「第5轮」这种
     * 没有前几轮的怪目录，也没有任何入口能把它调回来。
     */
    fun withRoundReset(): BatchConfig = withProgressReset().copy(round = 1)

    /**
     * 按磁盘上的照片重建状态（同步的唯一入口）
     *
     * ## 幂等性
     * 这三个函数只读"磁盘事实 + 不会因同步而改变的配置"（目录名、名字列表、前缀、分组名），
     * **不读 round / shotIndexes / nextIndex / activeGroupIndex**。
     * 所以连点多次结果完全一致 —— 这是刻意的：早先的实现里，解析不出组名的文件会
     * 兜底归到"当前组"，而当前组正好会被上一次同步改掉，于是点第二次结果就变了。
     */

    /**
     * 序号模式：按磁盘上出现过的最大序号重建计数器
     *
     * 取最大序号而不是文件个数：删掉中间某张时序号不会倒退、不会覆盖已有文件。
     */
    fun syncedWithDiskSequence(maxSequence: Int): BatchConfig {
        val counter = (maxSequence - startIndex + 1).coerceAtLeast(0)
        return copy(counter = counter).normalized()
    }

    /**
     * 工作模式（未分组）：按「第N轮」重建轮次与本轮进度
     *
     * 以磁盘上存在的最大轮次为准 —— 用户删掉第 3 轮就回到第 2 轮，
     * 而不是继续按内存里的第 3 轮往新目录写。
     *
     * @param roundFiles 轮次号 -> 该轮目录下的文件名
     */
    fun syncedWithDiskRounds(roundFiles: Map<Int, List<String>>): BatchConfig {
        if (roundFiles.isEmpty()) return this

        val targetRound = roundFiles.keys.max()
        val shot = shotIndexesMatching(roundFiles[targetRound].orEmpty())

        return copy(
            round = targetRound.coerceAtLeast(1),
            shotIndexes = shot,
            nextIndex = firstUnshotIndex(shot),
            counter = 0
        ).normalized()
    }

    /**
     * 工作模式（启用分组）：按组重建各组进度
     *
     * @param groupRounds 组名 -> (轮次 -> 文件名)
     *
     * 每组的轮次也取自磁盘：照片在 组名/第2轮/ 里，同步后就该停在第 2 轮，
     * 否则下一张会写到 组名/ 下，和已有照片分家。
     */
    fun syncedWithDiskGroups(groupRounds: Map<String, Map<Int, List<String>>>): BatchConfig {
        if (!usesGroups || groupRounds.isEmpty()) return this

        // 磁盘上存在、但批次里没登记的组目录也要**认下来**：
        // 现场换个批次复用同一个目录、或手工建了组目录时，只认已登记的那几个
        // 会让这些照片全部"认不出来"，接着就会被重拍成同名副本。
        // 新增的组按名字排序追加，保证同样的磁盘状态得到同样的结果（幂等）。
        val extraGroups = groupRounds.keys.filterNot { it in groupNames }.sorted()
        val allGroups = groupNames + extraGroups

        val total = effectiveNameList.size
        val states = allGroups.map { name ->
            val rounds = groupRounds[name]
            if (rounds.isNullOrEmpty()) {
                return@map BatchGroupState(name)                              // 磁盘上没有 -> 未拍
            }
            val round = rounds.keys.max().coerceAtLeast(1)
            val shot = shotIndexesMatching(rounds[round].orEmpty(), groupName = name)
            BatchGroupState(
                name = name,
                round = round,
                shotIndexes = shot,
                nextIndex = firstUnshotIndex(shot)
            )
        }

        // 停在第一个还没拍完的组（现场就该去那儿接着拍）；全拍完则停在最后一组
        val active = states.indexOfFirst { it.shotIndexes.size < total }
            .let { if (it >= 0) it else states.lastIndex.coerceAtLeast(0) }
        val restored = states[active]

        return copy(
            groupNames = allGroups,
            groupStates = states,
            activeGroupIndex = active,
            round = restored.round,
            shotIndexes = restored.shotIndexes,
            nextIndex = restored.nextIndex,
            counter = 0
        ).normalized()
    }

    /**
     * 找出第 index 项对应的磁盘文件名
     *
     * 用于清单里给每项配一张缩略图：现场要能一眼看出这张是不是拍错了。
     * 容忍 MediaStore 的去重后缀 " (1)" 与大小写差异（与同步用同一套判定）。
     *
     * @param index 名字下标
     * @param diskNames 磁盘上的文件名集合
     * @return 命中的磁盘文件名；该项没有照片时返回 null
     */
    fun findDiskNameFor(
        index: Int,
        diskNames: Collection<String>,
        groupName: String? = activeGroupName
    ): String? {
        val item = effectiveNameList.getOrNull(index) ?: return null
        val expected = buildCustomFileName(item, groupName = groupName)
        return diskNames.firstOrNull { matchesExpectedName(it, expected) }
    }

    /**
     * 从文件名列表里反推出"哪几项已拍"
     *
     * 按名字匹配而不是按文件时间：用户拷回来的旧数据时间戳是乱的。
     */
    private fun shotIndexesMatching(
        fileNames: List<String>,
        groupName: String? = activeGroupName
    ): List<Int> =
        effectiveNameList.indices.filter { index ->
            // 前缀可能包含组名，所以必须按"正在匹配的那个组"来算期望文件名
            val expected = buildCustomFileName(effectiveNameList[index], groupName = groupName)
            fileNames.any { matchesExpectedName(it, expected) }
        }

    /**
     * 下一张该拍哪一项 = 第一个没拍过的
     *
     * 与 advancedAfterShot 的推进规则一致。早先这里写成"最后一个已拍的下标 + 1"，
     * 跳着拍时会把指针指到已拍过的项上。
     */
    private fun firstUnshotIndex(shot: List<Int>): Int =
        effectiveNameList.indices.firstOrNull { it !in shot } ?: 0

    /**
     * 磁盘文件名是否对应某个期望的文件名
     *
     * 两个容差：
     * - MediaStore 遇到重名会追加 " (1)"、"(2)" 后缀，这类文件仍算同一项已拍
     * - 大小写不敏感（部分设备的相册会改写大小写）
     */
    private fun matchesExpectedName(diskName: String, expected: String): Boolean {
        if (diskName.equals(expected, ignoreCase = true)) return true

        // 去掉扩展名，再剥掉 " (N)" 形式的去重后缀
        val dot = diskName.lastIndexOf('.')
        val stem = if (dot > 0) diskName.substring(0, dot) else diskName
        val withoutSuffix = stem.replace(Regex("\\s*\\(\\d+\\)$"), "")
        return withoutSuffix.equals(
            expected.substringBeforeLast('.'),
            ignoreCase = true
        )
    }

    /**
     * 把名字指针指到指定下标（用于清单里"从这一项开始拍"）
     *
     * 下标会夹到 [0, 名字个数] 区间：允许指到末尾之后没有意义，
     * 指到末尾之前等于"跳过前面几项"。
     *
     * @param index 目标下标（0 表示第一个名字）
     */
    fun withNamePointer(index: Int): BatchConfig {
        val total = effectiveNameList.size
        val upper = if (total > 0) total - 1 else 0
        return copy(nextIndex = index.coerceIn(0, upper)).normalized()
    }

    /**
     * 标记某一项需要重拍
     *
     * 把它从"已拍"里移除并把指针指过去 —— 调用方需要同时删掉原来那张照片，
     * 否则重拍会生成同名文件（系统会自动加 " (1)" 后缀）。
     */
    fun withReshoot(index: Int): BatchConfig =
        withNamePointer(index).let { it.copy(shotIndexes = it.shotIndexes.filterNot { i -> i == index }) }

    /**
     * 某个名字是否已经拍过（用于清单展示）
     *
     * 判定依据是名字指针：下标小于指针的视为已拍。
     */
    fun isNameShot(index: Int): Boolean = index in shotIndexes

    /**
     * 作废上一张：名字序号回退一位
     *
     * 用途是"这张拍坏了，用同一个名字重拍"。计数不会退到 0 以下。
     * 注意：调用方还应删掉那张作废的照片，否则会与重拍的文件同名。
     */
    fun withCounterDecremented(): BatchConfig =
        if (!isWorkMode) {
            copy(counter = (counter - 1).coerceAtLeast(0))
        } else {
            // 工作模式：移除"最后拍的那一项"，并把指针指回它
            val lastShot = shotIndexes.lastOrNull()
            if (lastShot == null) {
                this
            } else {
                copy(
                    shotIndexes = shotIndexes.filterNot { it == lastShot },
                    nextIndex = lastShot
                ).normalized()
            }
        }

    /**
     * 切换命名模式（或同时更新名字列表）
     *
     * counter 在两种模式下含义不同：
     * - 序号模式：已拍张数，决定序号
     * - 工作模式：已取用的名字个数，同时是列表下标
     *
     * 所以**模式发生变化时必须把 counter 归零**。否则：
     * 一个已经拍过 5 张的序号批次切到工作模式后，下标直接是 5，
     * 若名字列表不足 6 项就会越界并回落到序号命名 ——
     * 用户看到的现象就是"明明填了名字，拍出来却没按名字命名"。
     *
     * @param mode 目标命名模式
     * @param list 目标名字列表（默认沿用当前）
     * @return 新的批次配置
     */
    fun withNamingMode(
        mode: NamingMode,
        list: List<String> = nameList
    ): BatchConfig {
        val modeChanged = mode != namingMode
        return copy(
            namingMode = mode,
            nameList = list,
            counter = if (modeChanged) 0 else counter
        ).normalized()
    }

    /**
     * 可直接用于存储路径的目录名
     *
     * 已过滤路径分隔符与文件系统非法字符，避免目录穿越与建目录失败。
     */
    val safeDirName: String
        get() = sanitizeDirName(dirName)

    /**
     * 目录名是否被清洗过（用于 UI 提示用户）
     */
    val isDirNameSanitized: Boolean
        get() = safeDirName != dirName

    /**
     * 归一化：把可能越界的字段收拢到合法范围，并清洗目录名/前缀
     *
     * 持久化之前统一调用，保证数据库里永远只存合法配置。
     */
    fun normalized(): BatchConfig {
        val base = copy(
            name = name.trim().ifEmpty { DEFAULT_NAME },
            dirName = safeDirName.ifEmpty { DEFAULT_DIR_NAME },
            namePrefix = sanitizePrefix(namePrefix).ifEmpty { DEFAULT_PREFIX },
            startIndex = startIndex.coerceAtLeast(0),
            indexWidth = indexWidth.coerceIn(MIN_INDEX_WIDTH, MAX_INDEX_WIDTH),
            // 只有"真正在工作模式下"才用已拍项数覆盖 counter；
            // 名字列表为空时实际走序号命名，counter 必须保留，否则计数会被清零。
            counter = if (isWorkMode && effectiveNameList.isNotEmpty()) {
                migratedShotState().first.size
            } else {
                counter.coerceAtLeast(0)
            },
            nameList = effectiveNameList,
            round = round.coerceAtLeast(1),
            shotIndexes = migratedShotState().first,
            nextIndex = migratedShotState().second,
            activeGroupIndex = safeActiveGroupIndex
        )

        // 快照随归一化一起刷新，调用方不必手动维护：
        // 任何一次写入（拍照推进、重置、切组、跳轮）之后快照都与当前状态一致。
        val withRounds = if (base.isWorkMode && base.effectiveNameList.isNotEmpty()) {
            base.copy(roundStates = base.refreshedRoundStates())
        } else {
            base
        }
        return if (withRounds.usesGroups) {
            withRounds.copy(groupStates = withRounds.refreshedGroupStates())
        } else {
            withRounds
        }
    }

    /**
     * 把当前轮的进度刷进 roundStates（顺带清掉已删组、已删轮的残留）
     */
    private fun refreshedRoundStates(): List<BatchRoundState> {
        val group = activeGroupName
        val snapshot = BatchRoundState(
            group = group,
            round = round.coerceAtLeast(1),
            shotIndexes = shotIndexes,
            nextIndex = nextIndex
        )
        return (roundStates.filterNot { it.round == snapshot.round && it.group == group } + snapshot)
            .filter { it.group == null || it.group in groupNames }
            .sortedWith(compareBy({ it.group ?: "" }, { it.round }))
    }

    /**
     * 归一化工作模式的"已拍下标 + 下一张下标"
     *
     * 1. 清洗越界下标、去重排序
     * 2. 兼容旧数据：早期版本只有 counter（含义是"指针之前的都算已拍"），
     *    这里转成显式状态，老批次升级后进度不丢
     * 3. counter 同步为已拍项数，界面上的"已拍 N 张"继续可用
     *
     * @return Pair(已拍下标, 下一张下标)
     */
    private fun migratedShotState(): Pair<List<Int>, Int> {
        val total = effectiveNameList.size
        if (!isWorkMode || total == 0) return emptyList<Int>() to 0

        // 旧数据：没有显式已拍记录，但有 counter
        val legacy = shotIndexes.isEmpty() && counter > 0 && nextIndex == 0
        // 注意：不能排序 —— "作废上一张"依赖"最后拍的是哪一项"，
        // 排序会把这个先后顺序抹掉。distinct 保留首次出现的顺序即可。
        val shot = (if (legacy) (0 until counter.coerceAtMost(total)).toList() else shotIndexes)
            .filter { it in 0 until total }
            .distinct()

        // 旧数据的"下一张"就是原指针；否则沿用显式下标
        val next = (if (legacy) counter else nextIndex).coerceIn(0, total - 1)
        return shot to next
    }

    companion object {
        /** 照片扩展名 */
        const val PHOTO_EXTENSION = "jpg"

        /** 日期子目录的格式（存盘/显示/同步三处共用） */
        const val DATE_STAMP_PATTERN = "yyyy-MM-dd"

        /** 默认起始序号 */
        const val DEFAULT_START_INDEX = 1

        /** 默认序号位数 */
        const val DEFAULT_INDEX_WIDTH = 3

        /** 序号位数下限 */
        const val MIN_INDEX_WIDTH = 1

        /** 序号位数上限 */
        const val MAX_INDEX_WIDTH = 9

        /** 默认批次名 */
        const val DEFAULT_NAME = "新批次"

        /** 默认目录名 */
        const val DEFAULT_DIR_NAME = "BatchNew"

        /** 默认文件名前缀 */
        const val DEFAULT_PREFIX = "IMG"

        /** 文件系统与路径中不允许出现的字符 */
        private val ILLEGAL_PATH_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

        /** 轮次目录名，如「第2轮」 */
        private val ROUND_DIR_REGEX = Regex("第(\\d+)轮")

        /**
         * 从磁盘路径里解析出轮次号
         *
         * 路径形如 Pictures/工作/设备上架/2026-09-17/第2轮/设备上架_拖车.jpg，
         * 取自 MediaStore 的 RELATIVE_PATH 或 DATA 都适用。
         *
         * @return 轮次号；路径里没有轮次目录（例如老数据或序号模式）时返回 null
         */
        fun parseRoundFromPath(path: String): Int? =
            ROUND_DIR_REGEX.find(path)?.groupValues?.get(1)?.toIntOrNull()

        /**
         * 日期子目录名（如 2026-09-17）
         *
         * 存盘路径、界面显示、按磁盘同步三处必须用同一个格式，
         * 否则会出现"界面显示的目录"与"实际写入的目录"对不上的情况。
         */
        fun dateStampFor(date: Date = Date()): String =
            SimpleDateFormat(DATE_STAMP_PATTERN, Locale.US).format(date)

        /**
         * 判断一张照片属于哪一个分组
         *
         * 两步，先松后紧：
         * 1. **看目录**：路径里出现 `/组名/` 这一层就算
         * 2. **看文件名**：目录认不出来时，退回看文件名里有没有这个组名
         *    （现场的前缀通常就带方向和 U 数，如 下架_4U_拖车.jpg）
         *
         * 为什么要有第 2 步：目录结构一旦与预期不完全一致，第 1 步就会全部落空，
         * 照片被整批判成"不识别"，接着被当成没拍过重拍一遍、生成同名副本。
         * 而照片叫什么名字是**确定的**：名字对得上就说明它确实拍过，
         * 不该因为所在目录的层级判断失败而否掉它。
         *
         * 两步都要求组名作为一个完整片段出现（两侧是分隔符），
         * 避免组名 "A" 被文件名 "BA_001.jpg" 误命中。
         *
         * @param path 照片路径（MediaStore 的 RELATIVE_PATH 或 DATA 均可）
         * @param fileName 照片文件名（含扩展名）
         * @param groupNames 批次上配置的分组名
         * @return 命中的组名；两步都认不出时返回 null
         */
        fun parseGroupFromPath(
            path: String,
            fileName: String,
            groupNames: List<String>
        ): String? {
            val candidates = groupNames
                .filter { it.isNotBlank() }
                // 组名有包含关系时（如 "4U" 与 "上架_4U"）取最长的，避免误判
                .sortedByDescending { it.length }
            if (candidates.isEmpty()) return null

            val normalizedPath = path.replace('\\', '/')
            candidates.firstOrNull { normalizedPath.contains("/$it/") }?.let { return it }

            val stem = fileName.substringBeforeLast('.')
            return candidates.firstOrNull { name ->
                stem == name ||
                    stem.startsWith("${name}_") ||
                    stem.endsWith("_$name") ||
                    stem.contains("_${name}_")
            }
        }

        /**
         * 从文件名里解析序号（序号模式同步用）
         *
         * 取扩展名之前的末尾数字：BA_007.jpg -> 7、IMG_out_12.jpg -> 12。
         * 只在序号模式下使用 —— 工作模式的文件名里带的是名字而不是序号。
         *
         * @return 解析出的序号；文件名里没有数字时返回 null
         */
        fun parseSequenceFromFileName(fileName: String): Int? =
            fileName.substringBeforeLast('.').takeLastWhile { it.isDigit() }
                .toIntOrNull()

        /**
         * 预设的 U 数选项
         *
         * 现场绝大多数就是 2U / 4U，其余（1U、8U、整机柜…）允许自由输入。
         */
        val PRESET_GROUP_OPTIONS: List<String> = listOf("2U", "4U")

        /** MediaStore DISPLAY_NAME 中建议清理的字符 */
        private val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

        /**
         * 清洗组名
         *
         * 与 [sanitizeDirName] 的区别：组名必须是**单独一层**目录，
         * 所以斜杠要换成下划线，不能像目录名那样保留层级 ——
         * 否则一个叫「上架/4U」的组会凭空多出一层目录，跟分组的本意就冲突了。
         */
        fun sanitizeGroupName(raw: String): String =
            sanitizeDirName(raw).replace('/', '_').trim('_')

        /**
         * 清洗目录名
         *
         * - 去掉路径分隔符与非法字符
         * - 空格转下划线
         * - 去掉首尾的点（避免 "." / ".." 目录穿越）
         */
        fun sanitizeDirName(raw: String): String = raw
            .split('/', '\\')
            .map { segment ->
                segment.trim().trim('.')
                    .replace(ILLEGAL_PATH_CHARS, "")
                    .replace(Regex("\\s+"), "_")
            }
            .filter { it.isNotEmpty() }
            .joinToString("/")

        /**
         * 清洗文件名前缀
         */
        fun sanitizePrefix(raw: String): String = raw
            .trim().trim('.')
            .replace(ILLEGAL_NAME_CHARS, "")
            .replace(Regex("\\s+"), "_")

        /**
         * 清洗名字列表里的单个名字
         *
         * 除非法字符外，还会去掉用户顺手写上的扩展名 ——
         * 否则会生成 设备上架_拖车.jpg.jpg 这种名字。
         */
        fun sanitizeNameEntry(raw: String): String = raw
            .trim()
            .removeSuffix(".jpg")
            .removeSuffix(".jpeg")
            .removeSuffix(".png")
            .replace(ILLEGAL_NAME_CHARS, "")
            .trim('.', ' ')

        /**
         * 解析名字列表文本（每行一个，也兼容中英文逗号/分号分隔）
         */
        fun parseNameList(raw: String): List<String> = raw
            .split('\n', ',', '，', ';', '；')
            .map { sanitizeNameEntry(it) }
            .filter { it.isNotEmpty() }

        /**
         * 名字列表转编辑用文本（每行一个）
         */
        fun formatNameList(list: List<String>): String = list.joinToString("\n")

        /**
         * 校验目录名是否合法（供表单实时提示使用）
         *
         * @return 错误提示文案，合法时返回 null
         */
        fun validateDirName(raw: String): String? {
            val trimmed = raw.trim().trim('/')
            val segments = trimmed.split('/')
            return when {
                trimmed.isEmpty() -> "目录名不能为空"
                // 支持多级目录（用 / 分隔），但不允许空段与目录穿越
                segments.any { it.isBlank() } -> "目录层级之间不能为空（不要出现 //）"
                segments.any { it == "." || it == ".." } -> "目录名不能包含 . 或 .."
                sanitizeDirName(trimmed) != trimmed ->
                    "目录名不能包含非法字符或空格"
                else -> null
            }
        }

        /**
         * 校验文件名前缀是否合法
         *
         * @return 错误提示文案，合法时返回 null
         */
        fun validatePrefix(raw: String): String? {
            val trimmed = raw.trim()
            return when {
                trimmed.isEmpty() -> "文件名前缀不能为空"
                sanitizePrefix(trimmed) != trimmed -> "前缀不能包含 \\ / : * ? \" < > | 或空格"
                else -> null
            }
        }

        /**
         * 创建新批次（自动生成 id，并归一化字段）
         */
        fun create(
            name: String,
            dirName: String,
            namePrefix: String,
            startIndex: Int = DEFAULT_START_INDEX,
            indexWidth: Int = DEFAULT_INDEX_WIDTH,
            dateSubDir: Boolean = false
        ): BatchConfig = BatchConfig(
            name = name,
            dirName = dirName,
            namePrefix = namePrefix,
            startIndex = startIndex,
            indexWidth = indexWidth,
            counter = 0,
            dateSubDir = dateSubDir
        ).normalized()

        /**
         * 依据已有批次推导下一个建议命名
         *
         * 从 BatchA/BA 递增到 BatchB/BB ... BatchZ/BZ，用完后追加序号避免撞车。
         * 新建批次表单用它预填默认值，让用户通常只需要填个批次名。
         *
         * @param existing 已有批次列表
         * @return Triple(批次名, 目录名, 文件名前缀)
         */
        fun suggestNextNaming(existing: List<BatchConfig>): Triple<String, String, String> {
            val usedDirs = existing.map { it.safeDirName.lowercase() }.toSet()

            for (letter in 'A'..'Z') {
                val dir = "Batch$letter"
                if (dir.lowercase() !in usedDirs) {
                    return Triple("$letter 批", dir, "$letter$letter")
                }
            }

            var index = existing.size + 1
            while ("Batch$index".lowercase() in usedDirs) index++
            return Triple("批次 $index", "Batch$index", "P$index")
        }
    }
}
