/** @author NIANXI @date 2026-11-04 @brief 仅显示档位回差，不修改测量值。 */
package com.nx.vfremake.funClass

import com.nx.vfremake.data.DepthDisplayMode
import com.nx.vfremake.data.DepthNodeQuality
import java.util.Locale
import kotlin.math.roundToInt

/** 每个显示位置独立持有，不能让不同单体共用回差历史。 */
class DepthDisplayFormat {
    private var previousBucket: Int? = null
    private var previousMode: DepthDisplayMode? = null

    /** 0.5cm档位在2.5mm边界外加1mm回差；大跨越直接跳到新档位。 */
    fun format(depthMm: Float?, mode: DepthDisplayMode): String {
        if (depthMm == null || !depthMm.isFinite()) { previousBucket = null; return "--" }
        if (previousMode != mode) previousBucket = null
        previousMode = mode
        if (mode == DepthDisplayMode.ONE_MM) return "${depthMm.roundToInt()} mm"
        val old = previousBucket
        val bucket = if (old == null || depthMm >= old * 5f + 3.5f || depthMm <= old * 5f - 3.5f)
            (depthMm / 5f).roundToInt() else old
        previousBucket = bucket
        return String.format(Locale.ROOT, "%.1f cm", bucket * 0.5f)
    }

    companion object {
        /** 现场质量状态，未标定和离线不能合成一个0值。 */
        fun qualityLabel(quality: DepthNodeQuality): String = when (quality) {
            DepthNodeQuality.OFFLINE -> "离线"
            DepthNodeQuality.FAULT -> "故障/无效"
            DepthNodeQuality.OUT_OF_RANGE -> "到达止点"
            DepthNodeQuality.SETTLING -> "滤波稳定中"
            DepthNodeQuality.UNCALIBRATED -> "未标定"
            DepthNodeQuality.OK -> "有效"
        }
    }
}
