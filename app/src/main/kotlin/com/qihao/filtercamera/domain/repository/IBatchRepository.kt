/**
 * IBatchRepository.kt - 批次仓库接口
 *
 * 「批次拍摄」的持久化抽象：
 * - 维护批次列表（增删改查）
 * - 维护「当前选中批次」
 * - 维护每个批次的已拍张数（序号计数器）
 *
 * 所有写操作返回 [Result]，因为批次配置一旦写失败，
 * 用户会误以为"批次已创建/已切换"，必须让 UI 能感知失败。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.domain.repository

import com.qihao.filtercamera.domain.model.BatchConfig
import kotlinx.coroutines.flow.Flow

/**
 * 批次仓库接口
 */
interface IBatchRepository {

    /**
     * 全部批次列表（响应式，写入后自动推送）
     */
    val batches: Flow<List<BatchConfig>>

    /**
     * 当前选中的批次
     *
     * 未选择批次、或选中的批次已被删除时返回 null。
     * 拍照时用它决定文件名与归档目录。
     */
    val currentBatch: Flow<BatchConfig?>

    /**
     * 当前选中批次的 id（null 表示"不使用批次"）
     */
    val currentBatchId: Flow<String?>

    /**
     * 新增批次
     *
     * @param batch 批次配置（内部会归一化字段）
     */
    suspend fun addBatch(batch: BatchConfig): Result<Unit>

    /**
     * 更新批次
     *
     * 按 id 覆盖；若 id 不存在则不做任何修改并返回失败。
     */
    suspend fun updateBatch(batch: BatchConfig): Result<Unit>

    /**
     * 删除批次
     *
     * 只删除批次定义，不影响已经存进相册的照片。
     * 若删除的是当前选中批次，会同时清空选中状态。
     */
    suspend fun deleteBatch(id: String): Result<Unit>

    /**
     * 选中批次（拍照时生效）
     */
    suspend fun selectBatch(id: String): Result<Unit>

    /**
     * 取消选中（回落默认命名 IMG_yyyyMMdd_HHmmss.jpg）
     */
    suspend fun clearSelection(): Result<Unit>

    /**
     * 一张拍完后的计数推进（已拍张数 +1，名字拍完时自动进入下一轮）
     *
     * 必须在照片「存盘成功之后」调用，否则存图失败会留下空号。
     *
     * "自动进入下一轮"必须在这里一并完成：命名用的名字指针与轮次是同一份状态，
     * 分两次写会留下不一致的中间态。
     */
    suspend fun advanceAfterShot(id: String): Result<Unit>

    /**
     * 重置进度（回到本轮的起点，用于重拍本轮）
     *
     * 工作模式要清掉的是"已拍下标 + 名字指针"，不能只把 counter 归零。
     */
    suspend fun resetCounter(id: String): Result<Unit>

    /**
     * 轮次归 1 并清空进度
     *
     * [startNewRound] 只会 +1，轮次因此只增不减；跨天复用同一批次时会一路延续
     * （当天目录里直接是「第N轮」），需要这个显式归位。
     */
    suspend fun resetRound(id: String): Result<Unit>

    /**
     * 开始新一轮：轮次 +1 并把名字序号归零
     *
     * 工作模式的名字固定，重复拍摄会重名；新一轮会把文件落到
     * Pictures/{目录}/第N轮/ 下，从而可以拿着同一套名字从头再拍。
     */
    suspend fun startNewRound(id: String): Result<Unit>

    /**
     * 把名字指针指到指定下标（清单里"从这一项开始拍"）
     *
     * 只改"下一张拍哪个"，不动已拍状态 —— 所以跳过的那几项仍是未拍，后续会被回头补拍。
     * 下标会自动夹到合法区间。
     */
    suspend fun setNamePointer(id: String, index: Int): Result<Unit>

    /**
     * 跳到指定轮次
     *
     * 每轮各自保留进度：第 2 轮拍到一半被打断、跳去第 3 轮，回来时第 2 轮还在原处。
     */
    suspend fun setRound(id: String, round: Int): Result<Unit>

    /**
     * 切换到指定分组
     *
     * 切走前保存当前组进度、切回来时恢复，所以来回切不会丢已拍记录。
     *
     * @param index 目标组下标（自动夹到合法区间）
     */
    suspend fun setActiveGroup(id: String, index: Int): Result<Unit>

    /**
     * 标记某一项需要重拍：把它从"已拍"里移除并把指针指过去
     *
     * 调用方需要同时删掉原来那张照片（按文件名在相册里查），
     * 否则重拍会生成同名文件、被系统加 " (1)" 后缀。
     */
    suspend fun markForReshoot(id: String, index: Int): Result<Unit>

    /**
     * 作废上一张：名字序号回退一位（不会低于 0）
     *
     * 用于"这张拍坏了，用同一个名字重拍"。
     * 调用方需要同时删掉那张废片，否则重拍会与其同名。
     */
    suspend fun decrementCounter(id: String): Result<Unit>

    /**
     * 按 id 读取单个批次（不订阅 Flow 的一次性读取）
     */
    suspend fun getBatch(id: String): BatchConfig?
}
