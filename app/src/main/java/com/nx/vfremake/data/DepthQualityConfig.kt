/** @author NIANXI @date 2026-11-04 @brief 质量监测门限集中定义与记录溯源。 */
package com.nx.vfremake.data

/** CV=20%为待试验修订的工程初值，不能声称是国标要求。 */
data class DepthQualityConfig(val cvThreshold: Float = 0.20f) {
    /** 元数据写出完整有效门限，避免离线分析依赖另一版App的默认值。 */
    fun metadata(): Map<String, Number> = linkedMapOf(
        "cv_threshold" to cvThreshold, "pass_rate_min" to PASS_RATE_MIN,
        "target_split_mm" to TARGET_SPLIT_MM, "tolerance_low_mm" to TOL_LOW_MM,
        "tolerance_high_mm" to TOL_HIGH_MM, "enter_distance_m" to ENTER_DISTANCE_M,
        "enter_seconds" to ENTER_SECONDS, "exit_distance_m" to EXIT_DISTANCE_M,
        "exit_seconds" to EXIT_SECONDS, "hysteresis_mm" to HYSTERESIS_MM,
        "lift_depth_mm" to LIFT_DEPTH_MM, "lift_seconds" to LIFT_SECONDS,
        "window_distance_m" to WINDOW_DISTANCE_M, "window_seconds" to WINDOW_SECONDS,
        "target_settle_seconds" to TARGET_SETTLE_SECONDS
    )
    companion object {
        // 容差与合格率按任务书§3.10（其引用GB/T6973、NY/T1768，仍以标准原文为准）。
        const val TARGET_SPLIT_MM = 30f
        const val TOL_LOW_MM = 5f
        const val TOL_HIGH_MM = 10f
        const val PASS_RATE_MIN = 0.75f
        // 持续距离、回差和过渡期均为任务书指定的工程判据。
        const val ENTER_DISTANCE_M = 0.3
        const val ENTER_SECONDS = 0.3
        const val EXIT_DISTANCE_M = 0.2
        const val EXIT_SECONDS = 0.2
        const val HYSTERESIS_MM = 1f
        const val LIFT_DEPTH_MM = 10f
        const val LIFT_SECONDS = 0.1
        const val WINDOW_DISTANCE_M = 10.0
        const val WINDOW_SECONDS = 10.0
        const val TARGET_SETTLE_SECONDS = 2.0

        /** 显示、统计和偏深偏浅判定共用容差。 */
        fun toleranceMm(targetMm: Float): Float = if (targetMm >= TARGET_SPLIT_MM) TOL_HIGH_MM else TOL_LOW_MM
    }
}
