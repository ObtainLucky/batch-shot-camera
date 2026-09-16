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
    val nextIndex: Int = 0
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
        return effective.copy(
            shotIndexes = shot,
            nextIndex = nextUnshot
        ).normalized()
    }

    /**
     * 生成「前缀_自定义文字」形式的文件名
     *
     * @param item 自定义文字，如「拖车」
     */
    fun buildCustomFileName(item: String, ext: String = PHOTO_EXTENSION): String {
        val prefix = sanitizePrefix(namePrefix)
        val safeItem = sanitizeNameEntry(item)
        return when {
            prefix.isEmpty() -> "$safeItem.$ext"
            safeItem.isEmpty() -> "$prefix.$ext"
            else -> "${prefix}_$safeItem.$ext"
        }
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
            when {
                total == 0 -> "未设置名字列表"
                // 拍完不等于结束：继续拍会自动进入下一轮，文案要说清楚
                counter >= total -> "第${round}轮已拍完 · 继续拍自动进入第${round + 1}轮"
                else -> "第${round}轮 · 第 ${counter + 1}/$total 个名字"
            }
        }

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

    /**
     * 批次目录之下的相对路径（不含 Pictures/ 前缀）
     *
     * 层级顺序固定为：**目录 / 日期 / 轮次**
     * 例如 工作/2026-09-16/第1轮 —— 日期在外、轮次在内，
     * 这样按天翻看时，同一天的各轮次都收在同一个日期目录下。
     *
     * 目录名本身可以写成多级（如「工作/设备上架」），会被原样保留层级。
     *
     * @param dateStamp 日期字符串（如 2026-09-16），为空表示不按日期分层
     */
    fun relativeSubPath(dateStamp: String? = null): String {
        val segments = buildList {
            add(safeDirName.ifEmpty { DEFAULT_DIR_NAME })
            dateStamp?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (usesRoundSubDir) add(roundDirName)
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
    fun normalized(): BatchConfig = copy(
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
        nextIndex = migratedShotState().second
    )

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

        /** MediaStore DISPLAY_NAME 中建议清理的字符 */
        private val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

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
