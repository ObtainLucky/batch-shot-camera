/**
 * BatchFormData.kt - 批次表单的提交内容
 *
 * 表单字段已经有十来个了，此前通过位置参数逐层透传
 * （CreateBatchForm.onConfirm 一连串 String/Int/Boolean）。
 * 位置一旦对错，编译期看不出来、只会静默写出错误的批次配置，
 * 所以这里改成一个具名数据类：新增字段不会打乱已有调用方。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.presentation.common.components

import com.qihao.filtercamera.domain.model.NamingMode

/**
 * 批次表单填写的全部内容
 *
 * @param name 批次名
 * @param dirName 相册目录名（可多级，如 工作/设备上架）
 * @param prefix 文件名前缀（如 上架）
 * @param startIndex 起始序号（序号模式）
 * @param indexWidth 序号位数（序号模式）
 * @param dateSubDir 是否按日期分子目录
 * @param namingMode 命名模式
 * @param nameList 工作模式的名字列表（已清洗）
 * @param note 备注（进信息水印）
 * @param groupNames 分组名列表（已清洗，如 上架_4U、2U）
 * @param autoAdvanceGroup 一组拍完后是否自动切到下一组
 * @param includeGroupInFileName 文件名里是否带上分组名（上架_2U_xxx.jpg）
 */
data class BatchFormData(
    val name: String,
    val dirName: String,
    val prefix: String,
    val startIndex: Int,
    val indexWidth: Int,
    val dateSubDir: Boolean,
    val namingMode: NamingMode,
    val nameList: List<String>,
    val note: String,
    val groupNames: List<String> = emptyList(),
    val autoAdvanceGroup: Boolean = true,
    val includeGroupInFileName: Boolean = false
)
