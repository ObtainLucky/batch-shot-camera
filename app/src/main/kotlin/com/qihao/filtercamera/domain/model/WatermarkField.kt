/**
 * WatermarkField.kt - 信息水印的可配置字段
 *
 * 信息水印是一套「模板」：字段固定为这几项，其中
 * - 经度/纬度/地址/时间/天气 是实时获取的，用户可以决定"要不要显示"，但不能改内容
 * - 备注 的内容由用户自己填
 *
 * 因此设置页给的是"显示开关"，而不是文本编辑。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.domain.model

/**
 * 信息水印字段
 *
 * @param id 持久化用的稳定标识（不要随意改动，否则老配置会失效）
 * @param label 显示名称
 * @param liveData 是否为实时获取的数据（不可编辑，只能开关）
 */
enum class WatermarkField(
    val id: String,
    val label: String,
    val liveData: Boolean
) {
    LONGITUDE("longitude", "经度", true),
    LATITUDE("latitude", "纬度", true),
    ADDRESS("address", "地址", true),
    TIME("time", "时间", true),
    WEATHER("weather", "天气", true),
    REMARK("remark", "备注", false);

    companion object {
        /** 默认全部显示 */
        val DEFAULT: Set<WatermarkField> = entries.toSet()

        /** 默认值的持久化形式 */
        val DEFAULT_IDS: String = toIds(DEFAULT)

        /**
         * 解析持久化的字段集合
         *
         * 无法识别的 id 直接忽略；结果为空时回落到默认全开，
         * 避免配置损坏导致水印整个消失。
         */
        fun fromIds(raw: String?): Set<WatermarkField> {
            if (raw.isNullOrBlank()) return DEFAULT
            val parsed = raw.split(',')
                .mapNotNull { id -> entries.firstOrNull { it.id == id.trim() } }
                .toSet()
            return parsed.ifEmpty { DEFAULT }
        }

        /**
         * 序列化为持久化字符串
         */
        fun toIds(fields: Set<WatermarkField>): String {
            // 按枚举声明顺序输出，保证相同集合产生相同字符串
            return entries.filter { it in fields }.joinToString(",") { it.id }
        }
    }
}
