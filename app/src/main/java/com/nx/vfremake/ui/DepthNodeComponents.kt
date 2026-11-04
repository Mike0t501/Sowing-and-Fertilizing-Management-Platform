/** @author NIANXI @date 2026-11-04 @brief 节点显示组件，保留现场大字与高对比。 */
package com.nx.vfremake.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.DepthDisplayFormat
import com.nx.vfremake.funClass.MeasuredDepthSample
import java.util.Locale
import kotlin.math.abs

/** 质量优先于深度差值；未标定、离线不能显示为正常绿色。 */
internal fun measuredDepthColor(sample: MeasuredDepthSample, target: Float): Color = when (sample.quality) {
    DepthNodeQuality.OFFLINE, DepthNodeQuality.UNCALIBRATED, DepthNodeQuality.SETTLING -> Color(0xFF757575)
    DepthNodeQuality.FAULT, DepthNodeQuality.OUT_OF_RANGE -> Color(0xFFC62828)
    DepthNodeQuality.OK -> when {
        DepthEventType.LIFT_OFF in sample.monitoring.events -> Color(0xFFC62828)
        sample.monitoring.events.isNotEmpty() -> Color(0xFF9C6500)
        target > 0f && abs((sample.depthMm ?: target) - target) > DepthQualityConfig.toleranceMm(target) -> Color(0xFF9C6500)
        else -> Color(0xFF237A35)
    }
}

/** 与伺服换算值明确区分；回差历史只存在显示组件内部。 */
@Composable
internal fun MeasuredDepthReadout(sample: MeasuredDepthSample, target: Float, mode: DepthDisplayMode) {
    val formatter = remember { DepthDisplayFormat() }
    val color = measuredDepthColor(sample, target)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("节点实测播深", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            val text = formatter.format(sample.depthMm, mode)
            Text(if (sample.quality == DepthNodeQuality.OK) text else DepthDisplayFormat.qualityLabel(sample.quality),
                fontSize = 30.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Column(horizontalAlignment = Alignment.End) {
            val eventLabels = sample.monitoring.events.sortedBy { it.ordinal }.joinToString(" / ") { when (it) {
                DepthEventType.SHALLOW -> "偏浅"; DepthEventType.DEEP -> "偏深"
                DepthEventType.LIFT_OFF -> "腾空"; DepthEventType.UNSTABLE -> "不稳定"
            } }
            Text("状态：${eventLabels.ifEmpty { DepthDisplayFormat.qualityLabel(sample.quality) }}", color = color, fontSize = 16.sp)
            Text("目标差值：" + (sample.depthMm?.let { String.format(Locale.ROOT, "%+.1f mm", it - target) } ?: "--"), fontSize = 16.sp)
            Text("周期波动：" + (sample.spanMm?.let { String.format(Locale.ROOT, "%.1f mm", it) } ?: "--"), fontSize = 16.sp)
            val monitoring = sample.monitoring
            if (monitoring.windowReady) {
                val cv = monitoring.coefficientOfVariation?.let { if (it.isFinite()) "%.1f%%".format(Locale.ROOT, it * 100) else "∞" } ?: "--"
                Text("合格率 %.1f%% · CV %s".format(Locale.ROOT, (monitoring.passRate ?: 0f) * 100, cv), fontSize = 16.sp)
            } else Text("质量窗口：统计不足", fontSize = 16.sp, color = Color.Gray)
        }
    }
}
