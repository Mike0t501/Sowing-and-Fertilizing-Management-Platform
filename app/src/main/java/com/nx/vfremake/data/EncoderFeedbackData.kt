/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2026年7月7日
 * @file    :
 * @brief   :摆臂式绝对值编码器实测播深反馈——数据类（标定持久化字段 + 运行时字段）
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 首版：播种深度实测反馈子系统（测量/标定/显示/记录，不做闭环修正）
 ***********************************************************************************************************
 */

package com.nx.vfremake.data

/**
 * 单路摆臂编码器的标定配置与运行时状态。
 *
 * 设计说明（对齐 [ServoCalibration]）：
 *   - 持久化字段（nodeId / zeroSet / singleTurnResolution / calibrationPoints / fit*）
 *     由 MySharedPreFun 读写（键前缀 enc_N_*）
 *   - 运行时字段（rawPosition / filteredPosition / measuredDepth / isOnline / lastHeardMs）
 *     仅内存维护，不落盘
 *   - 数据类使用 val，通过 copy() 更新，配合 LiveData 实现 Compose 响应式刷新
 *
 * 编码器与伺服一一对应：编码器 nodeId 默认 21+motorIndex（21~28），
 * 测量 motorIndex 对应行（伺服 11+motorIndex）的真实入土深度。
 * 实测深度只做测量/显示/记录，【不】写回伺服 targetDepth（闭环修正明确不做，
 * 数据通路已为将来闭环留好接口——读 measuredDepth 即可）。
 *
 * @param motorIndex 行号 0~7（数组下标，对应界面第 1~8 路）
 * @param nodeId     编码器 CAN Node-ID，默认 21+motorIndex，可在配置工具中改写
 */
data class EncoderCalibration(
    val motorIndex: Int,
    val nodeId: Int = 21 + motorIndex,

    // ── 标定（持久化）───────────────────────────────────────────────────
    val zeroSet: Boolean = false,          // 是否已做零位预设（写 6003=0 并保存）
    val singleTurnResolution: Int = 0,     // 物理单圈分辨率（配置工具读 6501h 写入；0=未知，
                                           // 符号展开与按比例限幅阈值退化为默认行为）

    // ── 标定点 [滤波后编码值, 实测深度(mm)]，2~5 点 ────────────────────
    val calibrationPoints: List<Pair<Int, Float>> = emptyList(),

    // ── 线性拟合系数 depth_mm = fitA * encoderPos + fitB ────────────────
    val fitA: Float = 0f,
    val fitB: Float = 0f,
    val fitValid: Boolean = false,

    // ── 运行时状态（不持久化，由 CanReceiveCoroutine 更新）──────────────
    val rawPosition: Int = 0,              // 最新原始编码值（已符号展开）
    val filteredPosition: Float = 0f,      // 限幅+滑动均值后的编码值
    val measuredDepth: Float = 0f,         // 换算后实测深度 mm（fitValid 时有效）
    val isOnline: Boolean = false,         // 以 TPDO 到达判断在线（不启用编码器心跳）
    val lastHeardMs: Long = 0L             // 最后收到该编码器任意 CAN 帧的时间戳（ms）
)

/**
 * 全局编码器反馈状态（ViewModel 中以 AtomicReference + LiveData 投影持有，
 * 线程模型与 SowingDepthState 相同：后台读取一律走 currentEncoderFeedbackState()，
 * 禁止读 LiveData.value——postValue 有主线程刷新滞后，并发写会互相覆盖）。
 *
 * 独立于 [SowingDepthState] 的原因：编码器 TPDO 高频写入（8 路 × 50ms）若并入
 * 伺服状态容器，会放大伺服侧 CAS 重试与 Compose 重组范围；且需求约定不改动
 * 伺服现有行为。
 *
 * @param encoders 8 路编码器标定与状态列表，下标与 motorIndex 一致
 */
data class EncoderFeedbackState(
    val encoders: List<EncoderCalibration> = List(8) { EncoderCalibration(motorIndex = it) }
)
