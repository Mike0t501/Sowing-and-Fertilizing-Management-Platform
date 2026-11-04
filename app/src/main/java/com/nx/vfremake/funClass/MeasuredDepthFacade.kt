/** @author NIANXI @date 2026-11-04 @brief 显示、记录、监测的统一实测出口。 */
package com.nx.vfremake.funClass

import com.nx.vfremake.data.DepthNodeQuality
import com.nx.vfremake.data.DepthNodeState
import com.nx.vfremake.data.DepthNodeChannel
import com.nx.vfremake.data.DepthQualityStatus

data class MeasuredDepthSample(val depthMm: Float?, val rawDepthMm: Float?, val quality: DepthNodeQuality,
    val ageMs: Long, val spanMm: Float? = null, val monitoring: DepthQualityStatus = DepthQualityStatus())

/** 只读检测节点，禁止用伺服换算值回退成所谓实测深度。 */
class MeasuredDepthFacade(private val state: () -> DepthNodeState, private val nowMs: () -> Long) {
    /** 即使看门狗尚未调度，读取时也检查数据年龄。 */
    fun forRow(row: Int, snapshot: DepthNodeChannel? = null): MeasuredDepthSample {
        val channel = snapshot?.takeIf { it.row == row } ?: state().channels.getOrNull(row)
            ?: return MeasuredDepthSample(null, null, DepthNodeQuality.OFFLINE, Long.MAX_VALUE)
        val now = nowMs()
        val fresh = DepthNodeRuntime.refresh(channel, now)
        return MeasuredDepthSample(fresh.depthMm, fresh.depthRawMm, fresh.quality,
            if (fresh.hasStatus) (now - fresh.lastHeardMs).coerceAtLeast(0) else Long.MAX_VALUE, fresh.spanMm,
            if (fresh.quality in setOf(DepthNodeQuality.OK, DepthNodeQuality.OUT_OF_RANGE)) fresh.monitoring else DepthQualityStatus())
    }
}
