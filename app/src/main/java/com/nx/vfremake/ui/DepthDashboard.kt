package com.nx.vfremake.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.data.DepthDisplayMode
import com.nx.vfremake.data.DepthEventType
import com.nx.vfremake.data.DepthNodeQuality
import com.nx.vfremake.data.DepthQualityConfig
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.funClass.DepthDisplayFormat
import com.nx.vfremake.funClass.MeasuredDepthSample
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

internal object DepthDashboardColors {
    val Background = Color(0xFF111916)
    val Panel = Color(0xFF1B2823)
    val Raised = Color(0xFF253730)
    val Border = Color(0xFF4C665A)
    val Text = Color(0xFFF3F8F4)
    val Muted = Color(0xFFB9CCC1)
    val Accent = Color(0xFF5CE0B6)
    val Alert = Color(0xFFFF9696)
    val Warning = Color(0xFFFFCB76)
    val Good = Color(0xFF85E8A1)
    val Neutral = Color(0xFFBBC7C0)
    val OnAccent = Color(0xFF09291E)
}

internal enum class DepthTone(val color: Color) {
    GOOD(DepthDashboardColors.Good),
    WARNING(DepthDashboardColors.Warning),
    ALERT(DepthDashboardColors.Alert),
    NEUTRAL(DepthDashboardColors.Neutral)
}

private const val DEPTH_CHART_LIMIT_MM = 20f
private val depthNumberStyle = TextStyle(fontFeatureSettings = "tnum")
private val depthDeltaWidth = 76.dp
private val depthChartColumnHeight = 124.dp
private val depthStatusWidth = 84.dp

internal data class DepthChartRow(
    val index: Int,
    val sample: MeasuredDepthSample,
    val targetMm: Float?,
    val statusLabel: String,
    val tone: DepthTone,
    val enabled: Boolean = true,
    val targetSource: String? = null
) {
    val differenceMm = depthDifferenceMm(sample, targetMm)
    val differenceText = differenceMm?.let {
        if (it == 0f) "0.0 mm" else String.format(Locale.ROOT, "%+.1f mm", it).replace('-', '−')
    } ?: "—"
}

internal fun depthDifferenceMm(sample: MeasuredDepthSample, targetMm: Float?): Float? {
    if (sample.quality != DepthNodeQuality.OK || targetMm == null || !targetMm.isFinite() || targetMm <= 0f) return null
    return sample.depthMm?.let { it - targetMm }?.takeIf { it.isFinite() }
}

internal fun depthTargetReadout(mm: Float?): String = mm?.takeIf { it.isFinite() && it > 0f }
    ?.let { String.format(Locale.ROOT, "%.1f mm", it) } ?: "—"

internal fun fixedDepthChartRow(
    index: Int,
    sample: MeasuredDepthSample,
    fixedTargetMm: Float?,
    servo: ServoCalibration
): DepthChartRow {
    val difference = depthDifferenceMm(sample, fixedTargetMm)
    val tolerance = fixedTargetMm?.let { DepthQualityConfig.toleranceMm(it) }
    // The monitor's shallow/deep/unstable events may refer to a different work target.
    val (label, tone) = when {
        DepthEventType.LIFT_OFF in sample.monitoring.events -> "腾空" to DepthTone.ALERT
        sample.quality == DepthNodeQuality.FAULT || sample.quality == DepthNodeQuality.OUT_OF_RANGE ->
            DepthDisplayFormat.qualityLabel(sample.quality) to DepthTone.ALERT
        servo.alarmCode != 0 -> "伺服报警" to DepthTone.ALERT
        sample.quality != DepthNodeQuality.OK -> DepthDisplayFormat.qualityLabel(sample.quality) to DepthTone.NEUTRAL
        sample.depthMm?.isFinite() != true -> "待实测" to DepthTone.NEUTRAL
        difference == null || tolerance == null -> "待目标" to DepthTone.NEUTRAL
        difference < -tolerance -> "偏浅" to DepthTone.WARNING
        difference > tolerance -> "偏深" to DepthTone.WARNING
        else -> "达标" to DepthTone.GOOD
    }
    return DepthChartRow(index, sample, fixedTargetMm, label, tone)
}

@Composable
internal fun rememberDepthDashboardMeasurement(row: DepthChartRow, displayMode: DepthDisplayMode): String {
    val formatter = remember(row.index) { DepthDisplayFormat() }
    val depth = row.sample.depthMm.takeIf { row.enabled && row.sample.quality == DepthNodeQuality.OK }
    val formatted = formatter.format(depth, displayMode)
    return if (formatted == "--") "—" else formatted
}

@Composable
internal fun DepthDashboardBadge(label: String, tone: DepthTone, modifier: Modifier = Modifier) {
    Text(
        text = label,
        modifier = modifier
            .background(tone.color.copy(alpha = 0.13f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        color = tone.color,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center
    )
}

@Composable
internal fun DepthDashboardReadout(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = DepthDashboardColors.Text
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = DepthDashboardColors.Muted, fontSize = 14.sp, lineHeight = 18.sp)
        Text(value, color = color, fontSize = 27.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, style = depthNumberStyle)
    }
}

@Composable
internal fun DepthDashboardChart(
    rows: List<DepthChartRow>,
    displayMode: DepthDisplayMode,
    vertical: Boolean,
    modifier: Modifier = Modifier,
    onSelectRow: (Int) -> Unit
) {
    if (rows.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (vertical) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DepthChartAxisLabel("偏差 (mm)")
                DepthChartAxisLabel("上方偏深 · 下方偏浅")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.width(29.dp)) {
                    Spacer(Modifier.height(31.dp))
                    Box(Modifier.fillMaxWidth().height(depthChartColumnHeight)) {
                        DepthChartAxisLabel("+20", Modifier.align(Alignment.TopCenter))
                        DepthChartAxisLabel("0", Modifier.align(Alignment.Center))
                        DepthChartAxisLabel("−20", Modifier.align(Alignment.BottomCenter))
                    }
                }
                rows.forEach { row ->
                    key(row.index) {
                        DepthChartColumn(row, displayMode, Modifier.weight(1f)) { onSelectRow(row.index) }
                    }
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth >= 268.dp * LocalDensity.current.fontScale
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (compact) DepthChartAxisLabel("浅 ←   → 深", Modifier.width(depthStatusWidth))
                        Box(Modifier.weight(1f)) {
                            DepthChartAxisLabel(if (compact) "−20" else "−20 偏浅", Modifier.align(Alignment.CenterStart))
                            DepthChartAxisLabel("0", Modifier.align(Alignment.Center))
                            DepthChartAxisLabel(if (compact) "+20" else "+20 偏深", Modifier.align(Alignment.CenterEnd))
                        }
                        DepthChartAxisLabel("偏差 (mm)", Modifier.width(depthDeltaWidth), TextAlign.End)
                    }
                    rows.forEach { row ->
                        key(row.index) {
                            DepthChartStrip(row, displayMode, compact) { onSelectRow(row.index) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DepthChartAxisLabel(text: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    Text(text, modifier, color = DepthDashboardColors.Muted, fontSize = 13.sp, lineHeight = 16.sp, textAlign = textAlign)
}

@Composable
private fun DepthChartColumn(
    row: DepthChartRow,
    displayMode: DepthDisplayMode,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val measurement = rememberDepthDashboardMeasurement(row, displayMode)
    val measurementUnit = if (displayMode == DepthDisplayMode.ONE_MM) "mm" else "cm"
    Column(
        modifier = modifier.depthRowTarget(row, onClick).padding(horizontal = 3.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        DepthChartRowLabel(row)
        DepthDeviationBar(row, vertical = true, Modifier.fillMaxWidth().height(depthChartColumnHeight))
        Text(row.differenceText.substringBeforeLast(' '), color = row.tone.color, fontSize = 15.sp,
            textAlign = TextAlign.Center, style = depthNumberStyle)
        Text(measurement.substringBeforeLast(' '), color = DepthDashboardColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, style = depthNumberStyle)
        Text("实测 $measurementUnit", color = DepthDashboardColors.Muted, fontSize = 12.sp)
        Text(depthTargetReadout(row.targetMm).substringBeforeLast(' '), color = DepthDashboardColors.Text, fontSize = 14.sp,
            textAlign = TextAlign.Center, style = depthNumberStyle)
        Text("${row.targetSource ?: "目标"} mm", color = DepthDashboardColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        DepthDashboardBadge(row.statusLabel, row.tone)
    }
}

@Composable
private fun DepthChartStrip(row: DepthChartRow, displayMode: DepthDisplayMode, compact: Boolean, onClick: () -> Unit) {
    val measurement = rememberDepthDashboardMeasurement(row, displayMode)
    Column(
        modifier = Modifier.fillMaxWidth().depthRowTarget(row, onClick).padding(horizontal = 9.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (!compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                DepthChartRowLabel(row)
                DepthDashboardBadge(row.statusLabel, row.tone)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (compact) DepthChartRowLabel(row)
            Text("实测 $measurement", Modifier.weight(1f), color = DepthDashboardColors.Text,
                fontSize = 17.sp, fontWeight = FontWeight.Bold, style = depthNumberStyle)
            Text("${row.targetSource ?: "目标"} ${depthTargetReadout(row.targetMm)}", color = DepthDashboardColors.Muted,
                fontSize = 13.sp, textAlign = TextAlign.End, style = depthNumberStyle)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (compact) {
                Box(Modifier.width(depthStatusWidth)) {
                    DepthDashboardBadge(row.statusLabel, row.tone)
                }
            }
            DepthDeviationBar(row, vertical = false, Modifier.weight(1f).height(15.dp))
            Text(row.differenceText, Modifier.width(depthDeltaWidth), color = row.tone.color, fontSize = 14.sp,
                textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold, style = depthNumberStyle)
        }
    }
}

@Composable
private fun DepthChartRowLabel(row: DepthChartRow) {
    Text("第 ${row.index + 1} 行", color = DepthDashboardColors.Text, fontSize = 15.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Bold)
}

private fun Modifier.depthRowTarget(row: DepthChartRow, onClick: () -> Unit): Modifier = this
    .clip(RoundedCornerShape(5.dp))
    .background(DepthDashboardColors.Raised)
    .clickable(enabled = row.enabled, role = Role.Button, onClickLabel = "查看第 ${row.index + 1} 行", onClick = onClick)
    .heightIn(min = 48.dp)

@Composable
private fun DepthDeviationBar(row: DepthChartRow, vertical: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val inset = 3.dp.toPx()
        val axisLength = if (vertical) size.height else size.width
        val crossLength = if (vertical) size.width else size.height
        val crossCenter = crossLength / 2f
        val zero = axisLength / 2f
        val halfLength = (zero - inset).coerceAtLeast(0f)
        val halfBar = min(crossCenter, if (vertical) 9.dp.toPx() else 4.dp.toPx())
        fun point(axis: Float, cross: Float) = if (vertical) Offset(cross, axis) else Offset(axis, cross)

        drawLine(DepthDashboardColors.Border, point(inset, crossCenter), point(axisLength - inset, crossCenter), 1.dp.toPx())
        drawLine(DepthDashboardColors.Muted, point(zero, 0f), point(zero, crossLength), 1.dp.toPx())

        val difference = row.differenceMm ?: return@Canvas
        if (difference == 0f) {
            drawCircle(row.tone.color, radius = min(halfBar, 3.dp.toPx()), center = point(zero, crossCenter))
            return@Canvas
        }
        val direction = if ((difference > 0f) == vertical) -1f else 1f
        val ratio = (abs(difference) / DEPTH_CHART_LIMIT_MM).coerceAtMost(1f)
        val tip = zero + direction * halfLength * ratio
        val clipped = abs(difference) > DEPTH_CHART_LIMIT_MM
        val end = if (clipped) tip - direction * min(6.dp.toPx(), halfLength) else tip
        val length = abs(end - zero)
        drawRect(
            color = row.tone.color,
            topLeft = point(min(zero, end), crossCenter - halfBar),
            size = if (vertical) Size(halfBar * 2f, length) else Size(length, halfBar * 2f)
        )
        if (clipped) {
            val start = point(end, crossCenter - halfBar)
            val arrow = point(tip, crossCenter)
            val finish = point(end, crossCenter + halfBar)
            drawPath(Path().apply {
                moveTo(start.x, start.y)
                lineTo(arrow.x, arrow.y)
                lineTo(finish.x, finish.y)
                close()
            }, row.tone.color)
        }
    }
}
