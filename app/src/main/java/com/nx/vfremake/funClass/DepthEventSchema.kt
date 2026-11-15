/** @author NIANXI @date 2026-11-04 @brief 事件文件使用与主记录同一elapsed基准。 */
package com.nx.vfremake.funClass

import java.time.Instant
import java.util.Locale

object DepthEventSchema {
    val HEADER = listOf("row", "type", "start_elapsed_ms", "end_elapsed_ms", "start_time", "end_time",
        "duration_ms", "distance_m", "min_depth_mm", "max_depth_mm", "target_depth_mm", "end_reason")

    /** 持续时间只用单调时钟；墙钟校时不会造成负的持续时间。 */
    fun row(event: DepthQualityEvent, recordStartMs: Long): List<String> = listOf(
        (event.row + 1).toString(), event.type.name, (event.startMs - recordStartMs).toString(),
        (event.endMs - recordStartMs).toString(), Instant.ofEpochMilli(event.startEpochMs).toString(),
        Instant.ofEpochMilli(event.endEpochMs).toString(), (event.endMs - event.startMs).coerceAtLeast(0).toString(),
        event.distanceM?.let { "%.3f".format(Locale.ROOT, it) } ?: "",
        event.minDepthMm?.let { "%.1f".format(Locale.ROOT, it) } ?: "",
        event.maxDepthMm?.let { "%.1f".format(Locale.ROOT, it) } ?: "",
        if (event.targetDepthMm.isFinite()) "%.1f".format(Locale.ROOT, event.targetDepthMm) else "", event.endReason
    )
}
