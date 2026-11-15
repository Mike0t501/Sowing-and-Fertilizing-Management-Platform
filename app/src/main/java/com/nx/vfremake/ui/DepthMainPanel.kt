package com.nx.vfremake.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.mapping.view.Graphic
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.esri.arcgisruntime.mapping.view.MapView
import com.esri.arcgisruntime.mapping.view.ViewpointChangedListener
import com.esri.arcgisruntime.symbology.MarkerSymbol
import com.esri.arcgisruntime.symbology.TextSymbol
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.data.DepthDisplayMode
import com.nx.vfremake.data.DepthEventType
import com.nx.vfremake.data.DepthNodeQuality
import com.nx.vfremake.data.DepthNodeState
import com.nx.vfremake.data.DepthPrescriptionSource
import com.nx.vfremake.data.DepthQualityConfig
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.data.SowingDepthState
import com.nx.vfremake.data.activeSowingDepthMotorIndices
import com.nx.vfremake.funClass.DepthDisplayFormat
import com.nx.vfremake.funClass.DepthNodeRuntime
import com.nx.vfremake.funClass.MapMarkerArtwork
import com.nx.vfremake.funClass.MeasuredDepthSample
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.mSPParamData
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.roundToInt

internal const val DEPTH_MARKER_ROW = "depthRow"

private val depthMarkerColors = mapOf(
    DepthTone.GOOD to Color(0xFF237A35),
    DepthTone.WARNING to Color(0xFF9C6500),
    DepthTone.ALERT to Color(0xFFB52A2A),
    DepthTone.NEUTRAL to Color(0xFF56615B)
)

private data class DepthRowDisplay(
    val index: Int,
    val enabled: Boolean,
    val sample: MeasuredDepthSample,
    val targetMm: Float?,
    val targetSource: String,
    val servo: ServoCalibration,
    val servoUnavailable: Boolean
) {
    val passRateText = sample.monitoring.takeIf { enabled && it.windowReady }?.passRate
        ?.let { "${(it * 100).roundToInt()}%" } ?: if (enabled) "统计不足" else "—"
    private val events = sample.monitoring.events
    private val tolerance = targetMm?.let { DepthQualityConfig.toleranceMm(it) }
    private val differenceMm = depthDifferenceMm(sample, targetMm)

    private val status = when {
        !enabled -> DepthTone.NEUTRAL to "停用"
        DepthEventType.LIFT_OFF in events -> DepthTone.ALERT to "腾空"
        sample.quality == DepthNodeQuality.FAULT || sample.quality == DepthNodeQuality.OUT_OF_RANGE ->
            DepthTone.ALERT to DepthDisplayFormat.qualityLabel(sample.quality)
        servo.alarmCode != 0 -> DepthTone.ALERT to "伺服报警"
        sample.quality != DepthNodeQuality.OK -> DepthTone.NEUTRAL to DepthDisplayFormat.qualityLabel(sample.quality)
        servoUnavailable -> DepthTone.ALERT to "伺服离线"
        sample.depthMm?.isFinite() != true -> DepthTone.NEUTRAL to "待实测"
        differenceMm == null || tolerance == null -> DepthTone.NEUTRAL to "待目标"
        DepthEventType.UNSTABLE in events -> DepthTone.WARNING to "不稳定"
        DepthEventType.SHALLOW in events || differenceMm < -tolerance -> DepthTone.WARNING to "偏浅"
        DepthEventType.DEEP in events || differenceMm > tolerance -> DepthTone.WARNING to "偏深"
        else -> DepthTone.GOOD to "达标"
    }
    val chart = DepthChartRow(
        index = index,
        sample = if (enabled) sample else sample.copy(depthMm = null),
        targetMm = targetMm,
        statusLabel = status.second,
        tone = status.first
    )

    fun needsAttention(workRunning: Boolean): Boolean = enabled && (
        chart.tone == DepthTone.ALERT || chart.tone == DepthTone.WARNING ||
            sample.quality == DepthNodeQuality.UNCALIBRATED ||
            (workRunning && (sample.quality == DepthNodeQuality.OFFLINE || servoUnavailable || targetMm == null))
        )
}

@Composable
internal fun DepthMainPanel(
    mapView: MapView,
    rowOverlay: GraphicsOverlay,
    viewModel: VariableFertViewModel,
    selectedRow: Int?,
    onSelectRow: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.sowingDepthState.observeAsState(SowingDepthState())
    val nodes by viewModel.depthNodeState.observeAsState(DepthNodeState())
    val workRunning by viewModel.workRunning.observeAsState(false)
    val activeMotors by viewModel.activeMotorsState.observeAsState(mSPParamData.activeMotors)
    val positions by viewModel.depthRowPositions.observeAsState(emptyList())
    val fix by viewModel.loLaDidegData.observeAsState()
    val context = LocalContext.current
    val simulatedGnss = remember(context, workRunning) {
        MySharedPreFun(context).getSpecificValue(R.string.simGnss_Switch_name) == "1"
    }
    // 接收协程停止后也要在最后一帧到期时清掉实测值。
    val sampleExpiryTimes = remember(nodes) {
        nodes.channels.filter { it.hasStatus && it.isOnline }
            .map { it.lastHeardMs + DepthNodeRuntime.offlineTimeout(it) + 1L }
            .distinct().sorted()
    }
    var lastExpiredAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(workRunning, sampleExpiryTimes) {
        if (!workRunning) {
            sampleExpiryTimes.forEach { expiresAt ->
                val remainingMs = expiresAt - viewModel.depthNodeRuntime.nowMs()
                if (remainingMs > 0L) delay(remainingMs)
                lastExpiredAt = expiresAt
            }
        }
    }
    val samples = remember(nodes, workRunning, lastExpiredAt) {
        nodes.channels.map { viewModel.measuredDepthFacade.forRow(it.row, it) }
    }
    val prescriptionSource = state.depthPrescription.source
    val source = when (prescriptionSource) {
        DepthPrescriptionSource.FIXED -> "固定播深"
        DepthPrescriptionSource.LOADING -> "正在读取处方图"
        DepthPrescriptionSource.UNAVAILABLE -> "处方图加载失败"
        DepthPrescriptionSource.MISSING_FIELD -> "处方图缺少播深字段"
        DepthPrescriptionSource.PRESCRIPTION -> "处方图控深"
    }
    val activeRows = activeSowingDepthMotorIndices(mSPParamData.rowNumber, activeMotors)
    val rows = (0 until mSPParamData.rowNumber.coerceIn(0, 8)).map { row ->
        val enabled = row in activeRows
        val targetMm = if (enabled) state.depthTargetMm(row, usePrescription = true) else null
        DepthRowDisplay(
            index = row,
            enabled = enabled,
            sample = samples[row],
            targetMm = targetMm,
            targetSource = when {
                !enabled -> "停用"
                prescriptionSource == DepthPrescriptionSource.FIXED ->
                    if (row in state.configuredRowTargetsMm) "单行固定目标" else "整机固定目标"
                prescriptionSource == DepthPrescriptionSource.PRESCRIPTION && targetMm == null ->
                    "处方图 · 待有效目标"
                else -> source
            },
            servo = state.motors[row],
            servoUnavailable = workRunning && !nodes.simulated && !state.motors[row].isOnline
        )
    }
    DepthPositionMarkers(mapView, rowOverlay, positions, rows, selectedRow, fix?.third ?: 0.0, viewModel)

    val online = rows.count { it.enabled && it.sample.quality != DepthNodeQuality.OFFLINE }
    val abnormalRows = rows.count { it.needsAttention(workRunning) }
    val sourceTone = when (prescriptionSource) {
        DepthPrescriptionSource.UNAVAILABLE, DepthPrescriptionSource.MISSING_FIELD -> DepthTone.ALERT
        DepthPrescriptionSource.LOADING -> DepthTone.WARNING
        else -> DepthTone.NEUTRAL
    }
    val gnssSource = if (simulatedGnss) "模拟 GNSS" else "GNSS"
    val gnssStatus = when {
        fix == null -> "未定位"
        positions.isEmpty() -> "待航向"
        else -> "已定位"
    }
    val scroll = rememberScrollState()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = DepthDashboardColors.Background,
        contentColor = DepthDashboardColors.Text,
        border = BorderStroke(1.dp, DepthDashboardColors.Border)
    ) {
        val selected = selectedRow?.let { rows.getOrNull(it) }
        if (selected != null) {
            key(selected.index) {
                DepthRowDetails(
                    row = selected,
                    displayMode = nodes.displayMode,
                    simulated = nodes.simulated,
                    canSelectNext = selected.index < rows.lastIndex,
                    onSelectRow = onSelectRow
                )
            }
        } else {
            Column(
                modifier = Modifier.verticalScroll(scroll).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("播深质量", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    val workStatus = when {
                        !workRunning -> "待机"
                        nodes.simulated -> "模拟运行"
                        state.masterEnabled -> "作业中"
                        else -> "停止中"
                    }
                    DepthDashboardBadge(
                        workStatus,
                        if (workRunning && (nodes.simulated || state.masterEnabled)) DepthTone.GOOD else DepthTone.NEUTRAL
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "作业目标 · $source",
                        color = if (sourceTone == DepthTone.NEUTRAL) DepthDashboardColors.Muted else sourceTone.color,
                        fontSize = 14.sp
                    )
                    Text(
                        "$gnssSource $gnssStatus",
                        color = if (fix == null || positions.isEmpty()) DepthDashboardColors.Warning else DepthDashboardColors.Muted,
                        fontSize = 14.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DepthMapMetric(
                        label = if (nodes.simulated) "模拟检测" else "检测在线",
                        value = "$online/${activeRows.size}",
                        color = if (online > 0) DepthDashboardColors.Accent else DepthDashboardColors.Text,
                        modifier = Modifier.weight(1f)
                    )
                    DepthMapMetric(
                        label = "需关注行数",
                        value = "$abnormalRows",
                        color = if (abnormalRows > 0) DepthDashboardColors.Warning else DepthDashboardColors.Text,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (activeRows.isEmpty()) {
                    Text("未启用作业单体", color = DepthDashboardColors.Muted, fontSize = 16.sp)
                }
                if (rows.isNotEmpty()) {
                    DepthDashboardChart(
                        rows = rows.map { it.chart },
                        displayMode = nodes.displayMode,
                        vertical = false,
                        onSelectRow = onSelectRow
                    )
                }
            }
        }
    }
}

@Composable
private fun DepthMapMetric(label: String, value: String, color: Color, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = DepthDashboardColors.Panel,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, DepthDashboardColors.Border)
    ) {
        DepthDashboardReadout(label, value, Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color)
    }
}

@Composable
private fun DepthRowDetails(
    row: DepthRowDisplay,
    displayMode: DepthDisplayMode,
    simulated: Boolean,
    canSelectNext: Boolean,
    onSelectRow: (Int?) -> Unit
) {
    val servo = row.servo
    val chart = row.chart
    val measured = rememberDepthDashboardMeasurement(chart, displayMode)
    Column(modifier = Modifier.padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSelectRow(null) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回播深总览")
            }
            Text("第 ${row.index + 1} 行", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            DepthDashboardBadge(chart.statusLabel, chart.tone)
        }
        Column(
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(color = DepthDashboardColors.Panel, shape = RoundedCornerShape(8.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val target = depthTargetReadout(row.targetMm)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 210.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DepthDashboardReadout("节点实测", measured, Modifier.weight(1f), chart.tone.color)
                                DepthDashboardReadout("作业目标", target, Modifier.weight(1f))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                DepthDashboardReadout("节点实测", measured, color = chart.tone.color)
                                DepthDashboardReadout("作业目标", target)
                            }
                        }
                    }
                    DepthDetailValue("目标来源", row.targetSource)
                    DepthDetailValue("实测偏差", chart.differenceText)
                    DepthDetailValue("合格率", row.passRateText)
                }
            }
            Surface(color = DepthDashboardColors.Panel, shape = RoundedCornerShape(8.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("检测与伺服", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    DepthDetailValue(if (simulated) "模拟检测" else "检测节点", DepthDisplayFormat.qualityLabel(row.sample.quality))
                    DepthVoltageReadout(
                        row.sample, showMean = true, fontSize = 14.sp,
                        color = DepthDashboardColors.Muted, alertColor = DepthDashboardColors.Alert
                    )
                    DepthDetailValue("控深伺服", "${motorStatusLabel(servo)} · 节点 ${servo.nodeId}")
                    if (servo.alarmCode != 0) DepthDetailValue("伺服报警码", servo.alarmCode.toString())
                    DepthDetailValue("伺服标定", if (servo.limitsSet && servo.fitValid) "已完成" else "未完成")
                }
            }
        }
        val navigationColors = ButtonDefaults.textButtonColors(
            contentColor = DepthDashboardColors.Accent,
            disabledContentColor = DepthDashboardColors.Neutral
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                enabled = row.index > 0,
                onClick = { onSelectRow(row.index - 1) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = navigationColors
            ) {
                Text("上一行", fontSize = 16.sp, textAlign = TextAlign.Center)
            }
            TextButton(
                enabled = canSelectNext,
                onClick = { onSelectRow(row.index + 1) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = navigationColors
            ) {
                Text("下一行", fontSize = 16.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun DepthDetailValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = DepthDashboardColors.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = DepthDashboardColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f)
        )
    }
}

@Composable
private fun DepthPositionMarkers(
    mapView: MapView,
    overlay: GraphicsOverlay,
    positions: List<Point>,
    rows: List<DepthRowDisplay>,
    selectedRow: Int?,
    heading: Double,
    viewModel: VariableFertViewModel
) {
    val resources = LocalContext.current.resources
    val density = LocalDensity.current.density
    val currentPositions by rememberUpdatedState(positions)
    val symbols = remember(overlay, resources) {
        depthMarkerColors.mapValues { (_, color) ->
            MapMarkerArtwork.rowUnit(resources, color.toArgb(), selected = false) to
                MapMarkerArtwork.rowUnit(resources, color.toArgb(), selected = true)
        }
    }
    val markers = remember(overlay) {
        List(8) { index ->
            val marker = Graphic().apply {
                attributes[DEPTH_MARKER_ROW] = index
                isVisible = false
            }
            val label = Graphic().apply {
                this.symbol = TextSymbol(
                    11f, "${index + 1}", Color.White.toArgb(),
                    TextSymbol.HorizontalAlignment.CENTER, TextSymbol.VerticalAlignment.MIDDLE
                ).apply {
                    fontWeight = TextSymbol.FontWeight.BOLD
                    angleAlignment = MarkerSymbol.AngleAlignment.SCREEN
                }
                attributes[DEPTH_MARKER_ROW] = index
                isVisible = false
                zIndex = 2
            }
            marker to label
        }
    }
    fun fitSymbolsToRowSpacing() {
        val first = currentPositions.getOrNull(0)?.let(mapView::locationToScreen) ?: return
        val second = currentPositions.getOrNull(1)?.let(mapView::locationToScreen) ?: return
        val spacing = hypot(second.x.toDouble() - first.x, second.y.toDouble() - first.y) / density
        val width = (spacing - 1).roundToInt().coerceIn(11, 20).toFloat()
        val height = (width * 1.2f).coerceAtLeast(19f)
        symbols.values.forEach { (ordinary, selected) ->
            if (ordinary.width != width) {
                ordinary.width = width
                ordinary.height = height
                selected.width = width
                selected.height = height
            }
        }
    }

    DisposableEffect(mapView, overlay, symbols, density) {
        val listener = ViewpointChangedListener { fitSymbolsToRowSpacing() }
        markers.forEach { (marker, label) ->
            overlay.graphics.add(marker)
            overlay.graphics.add(label)
        }
        mapView.graphicsOverlays.add(overlay)
        mapView.addViewpointChangedListener(listener)
        onDispose {
            mapView.removeViewpointChangedListener(listener)
            mapView.graphicsOverlays.remove(overlay)
            overlay.graphics.clear()
        }
    }
    val rotation by viewModel.mapViewRotation.observeAsState(0.0)
    LaunchedEffect(symbols, heading, rotation) {
        val angle = (heading - rotation).toFloat()
        symbols.values.forEach { (ordinary, selected) ->
            ordinary.angle = angle
            selected.angle = angle
        }
    }
    val statuses = rows.map { it.chart.tone }
    LaunchedEffect(positions, statuses, selectedRow, symbols) {
        fitSymbolsToRowSpacing()
        markers.forEachIndexed { index, (marker, label) ->
            val point = positions.getOrNull(index)
            val style = statuses.getOrNull(index)?.let(symbols::getValue)
            val visible = point != null && style != null
            if (point != null && style != null) {
                val selected = index == selectedRow
                val symbol = if (selected) style.second else style.first
                if (marker.symbol !== symbol) marker.symbol = symbol
                marker.zIndex = if (selected) 1 else 0
                marker.geometry = point
                label.geometry = point
            }
            marker.isVisible = visible
            label.isVisible = visible
        }
    }
}
