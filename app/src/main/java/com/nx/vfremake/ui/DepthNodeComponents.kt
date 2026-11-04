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
        (sample.depthMm ?: 0f) < 10f -> Color(0xFFC62828)
        abs((sample.depthMm ?: target) - target) > (if (target >= 30f) 10f else 5f) -> Color(0xFF9C6500)
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
            Text("状态：${DepthDisplayFormat.qualityLabel(sample.quality)}", color = color, fontSize = 16.sp)
            Text("目标差值：" + (sample.depthMm?.let { String.format(Locale.ROOT, "%+.1f mm", it - target) } ?: "--"), fontSize = 16.sp)
            Text("周期波动：" + (sample.spanMm?.let { String.format(Locale.ROOT, "%.1f mm", it) } ?: "--"), fontSize = 16.sp)
        }
    }
}
