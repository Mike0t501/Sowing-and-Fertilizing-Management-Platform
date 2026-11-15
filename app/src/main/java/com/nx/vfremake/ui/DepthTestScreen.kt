package com.nx.vfremake.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.coroutine.CanReceiveCoroutine
import com.nx.vfremake.coroutine.DepthTestCoroutine
import com.nx.vfremake.data.DepthNodeState
import com.nx.vfremake.data.DepthTestConfig
import com.nx.vfremake.data.DepthTestPhase
import com.nx.vfremake.data.DepthTestStatus
import com.nx.vfremake.data.DepthTrialFix
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.data.SowingDepthState
import com.nx.vfremake.data.activeSowingDepthMotorIndices
import com.nx.vfremake.funClass.DepthDisplayFormat
import com.nx.vfremake.funClass.DocuAndManageFun
import com.nx.vfremake.funClass.MeasuredDepthSample
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.funClass.WriteDirStatus
import com.nx.vfremake.isSystemRunning
import com.nx.vfremake.mSPParamData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun DepthTestScreen(
    viewModel: VariableFertViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.sowingDepthState.observeAsState(SowingDepthState())
    val nodes by viewModel.depthNodeState.observeAsState(DepthNodeState())
    val status by viewModel.depthTestStatus.observeAsState()
    val location by viewModel.depthTrialLocation.observeAsState()
    val workRunning by viewModel.workRunning.observeAsState(false)
    val trialActive by viewModel.depthTrialActive.observeAsState(false)
    val recording by viewModel.depthRecordingState.observeAsState(false)
    val activeMotors by viewModel.activeMotorsState.observeAsState(mSPParamData.activeMotors)
    val rows = activeSowingDepthMotorIndices(mSPParamData.rowNumber, activeMotors)
    val testMode = remember(context) {
        MySharedPreFun(context).getSpecificValue(R.string.testMode_Switch_name) == "1"
    }
    val runner = remember(viewModel) { DepthTestCoroutine() }

    var selectedRow by rememberSaveable { mutableIntStateOf(0) }
    var start by rememberSaveable { mutableStateOf("20") }
    var end by rememberSaveable { mutableStateOf("80") }
    var step by rememberSaveable { mutableStateOf("20") }
    var dwell by rememberSaveable { mutableStateOf("2.0") }
    var tolerance by rememberSaveable { mutableStateOf("0.5") }
    var speed by rememberSaveable { mutableStateOf(viewModel.currentSowingDepthState().positionSpeed.toString()) }
    var acceleration by rememberSaveable { mutableStateOf(viewModel.currentSowingDepthState().acceleration.toString()) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showFullSequence by rememberSaveable { mutableStateOf(false) }
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    var acceptedStart by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var leaveAfterStop by remember { mutableStateOf(false) }
    var writeDirStatus by remember { mutableStateOf<WriteDirStatus?>(null) }
    val running = acceptedStart || trialActive || status?.running == true
    val busy = running || stopping

    LaunchedEffect(rows) {
        if (selectedRow !in rows) rows.firstOrNull()?.let { selectedRow = it }
    }
    LaunchedEffect(context) {
        writeDirStatus = withContext(Dispatchers.IO) { DocuAndManageFun().getWriteDirInfo(context).status }
    }
    LaunchedEffect(acceptedStart, trialActive, status) {
        if (acceptedStart && !viewModel.isDepthTrialActive() && status?.running != true) acceptedStart = false
    }
    LaunchedEffect(running, recording, stopping) {
        if (stopping && !running && !recording) {
            stopping = false
            if (leaveAfterStop) onBack()
        }
    }
    DisposableEffect(runner) {
        onDispose { runner.shutdown() }
    }
    // 本页只保留接收租约供启动前查看伺服状态。执行器由试验协程独占。
    DisposableEffect(viewModel, workRunning, testMode) {
        val receiver = if (!workRunning && !isSystemRunning && !testMode &&
            MySerialPortFun.ensureCanPortOpen(context)
        ) CanReceiveCoroutine().also { it.start(viewModel, context) } else null
        onDispose { receiver?.shutdown() }
    }

    fun requestStop(leave: Boolean = false) {
        if (stopping) return
        leaveAfterStop = leave
        stopping = true
        runner.abort()
    }

    fun requestBack() {
        if (!busy && !viewModel.isDepthTrialActive()) onBack()
        else if (!stopping) showExitConfirmation = true
    }

    BackHandler(busy) { requestBack() }
    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("停止试验并返回？") },
            text = { Text("将停止当前试验，保存已采样的数据后返回。") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirmation = false
                    if (busy || viewModel.isDepthTrialActive()) requestStop(leave = true) else onBack()
                }) { Text("停止并返回") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmation = false }) { Text("留在试验页") }
            }
        )
    }

    val startMm = start.toFloatOrNull()
    val endMm = end.toFloatOrNull()
    val stepMm = step.toFloatOrNull()
    val dwellSeconds = dwell.toFloatOrNull()?.takeIf { it in 0f..600f }
    val toleranceMm = tolerance.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
    val speedRpm = speed.toIntOrNull()?.takeIf { it in 100..3000 }
    val accelerationRpm = acceleration.toIntOrNull()?.takeIf { it in 1000..60000 }
    val stages = remember(startMm, endMm, stepMm) {
        if (startMm == null || endMm == null || stepMm == null) emptyList()
        else DepthTestConfig.stageDepths(startMm, endMm, stepMm)
    }
    val motor = state.motors.getOrNull(selectedRow)
    val startBlocker = when {
        workRunning || isSystemRunning -> "请先停止主界面作业。"
        recording -> "请先结束其他记录，等待文件保存完成。"
        testMode -> "当前为测试模式。关闭测试模式后才能启动真实伺服试验。"
        state.masterEnabled -> "请先返回播深控制页关闭控制总开关。"
        selectedRow !in rows || motor == null -> "请先在参数设置中启用被测行。"
        !motor.limitsSet || !motor.fitValid -> "第 ${selectedRow + 1} 行需要完成伺服限位和深度标定。"
        motor.isOnline && motor.alarmCode != 0 -> "第 ${selectedRow + 1} 行伺服报警，请先排除故障。"
        stages.isEmpty() -> "检查起止深度及步长，步长至少 1 mm。"
        dwellSeconds == null -> "每级停留时间应在 0–600 秒内。"
        toleranceMm == null -> "到位容差应大于 0 mm。"
        speedRpm == null -> "运动速度应在 100–3000 RPM 内。"
        accelerationRpm == null -> "加减速度应在 1000–60000 RPM/s 内。"
        writeDirStatus == null -> "正在检查实验数据目录。"
        writeDirStatus != WriteDirStatus.OK -> "请在系统设置中选择可写的实验数据目录。"
        else -> null
    }

    fun startTrial() {
        if (busy || viewModel.isDepthTrialActive() || viewModel.workRunning.value == true || viewModel.isDepthRecording() ||
            startBlocker != null
        ) return
        val config = DepthTestConfig(
            motorIndex = selectedRow,
            startDepthMm = startMm ?: return,
            endDepthMm = endMm ?: return,
            stepMm = stepMm ?: return,
            dwellMs = ((dwellSeconds ?: return).toDouble() * 1000).toLong(),
            toleranceMm = toleranceMm ?: return,
            positionSpeed = speedRpm ?: return,
            acceleration = accelerationRpm ?: return,
            recordIntervalMs = state.recordIntervalMs.toLong()
        )
        if (runner.start(context, viewModel, config)) {
            acceptedStart = true
        } else {
            Toast.makeText(context, "当前无法启动试验，请查看试验状态。", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = { MyTopBar("变量播深试验", ::requestBack) },
        bottomBar = {
            Surface(elevation = 6.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val caption = when {
                        stopping -> "正在停止并保存已采样数据"
                        busy -> "第 ${(status?.motorIndex?.takeIf { it >= 0 } ?: selectedRow) + 1} 行 · 试验中"
                        startBlocker != null -> startBlocker
                        else -> "已选第 ${selectedRow + 1} 行 · ${stages.size} 级"
                    }
                    Text(caption, modifier = Modifier.weight(1f), fontSize = 14.sp, color = Color(0xFF455A64))
                    Button(
                        onClick = { if (busy) requestStop() else startTrial() },
                        enabled = !stopping && (busy || startBlocker == null),
                        modifier = Modifier.heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (busy) Color(0xFFC62828) else Color(0xFF176E49),
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            if (busy) "停止试验" else "开始试验",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 760.dp
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (busy) {
                    val row = status?.motorIndex?.takeIf { it >= 0 } ?: selectedRow
                    TrialLiveCard(
                        row = row,
                        status = status,
                        motor = state.motors.getOrNull(row),
                        sample = viewModel.measuredDepthFacade.forRow(row, nodes.channels.getOrNull(row)),
                        fix = location,
                        recording = recording,
                        stopping = stopping
                    )
                } else {
                    status?.takeUnless { it.phase == DepthTestPhase.IDLE }?.let { TrialResultCard(it) }

                    val rowPicker: @Composable (Modifier) -> Unit = { modifier ->
                        TrialCard("1  选择被测行", modifier) {
                            if (rows.isEmpty()) Text("参数设置中尚未启用播深单体。", fontSize = 15.sp)
                            rows.chunked(if (wide) 4 else 3).forEach { group ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    group.forEach { row ->
                                        TrialRowButton(
                                            row, state.motors[row], row == selectedRow,
                                            Modifier.weight(1f), onClick = { selectedRow = row }
                                        )
                                    }
                                    repeat((if (wide) 4 else 3) - group.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                            Text(
                                "每次只驱动一行。启动时验证伺服反馈，到位后按时间停留。无定位时继续采样，坐标留空。",
                                fontSize = 14.sp, color = Color(0xFF455A64)
                            )
                        }
                    }
                    val setup: @Composable (Modifier) -> Unit = { modifier ->
                        TrialCard("2  设置梯度", modifier) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TrialField("起始深度 (mm)", start, { start = it }, Modifier.weight(1f))
                                TrialField("终点深度 (mm)", end, { end = it }, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TrialField("步长 (mm)", step, { step = it }, Modifier.weight(1f))
                                TrialField("每级停留 (s)", dwell, { dwell = it }, Modifier.weight(1f))
                            }
                            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                                Text(if (showAdvanced) "收起到位与运动参数" else "到位与运动参数", fontSize = 15.sp)
                            }
                            if (showAdvanced) {
                                TrialField("到位容差 (mm)", tolerance, { tolerance = it }, Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TrialField("速度 (RPM)", speed, { speed = it }, Modifier.weight(1f), KeyboardType.Number)
                                    TrialField("加减速 (RPM/s)", acceleration, { acceleration = it }, Modifier.weight(1f), KeyboardType.Number)
                                }
                            }
                        }
                    }
                    if (wide) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowPicker(Modifier.weight(1f))
                            setup(Modifier.weight(1f))
                        }
                    } else {
                        rowPicker(Modifier.fillMaxWidth())
                        setup(Modifier.fillMaxWidth())
                    }
                    TrialCard("3  核对往返序列") {
                        if (stages.isEmpty()) {
                            Text("参数有效后显示往返序列。", color = Color(0xFF455A64), fontSize = 15.sp)
                        } else {
                            Text("共 ${stages.size} 级 · 深度单位 mm", fontSize = 14.sp, color = Color(0xFF455A64))
                            val format = { depth: Float -> depth.toString().removeSuffix(".0") }
                            val sequence = if (showFullSequence || stages.size <= 15) {
                                stages.joinToString(" → ", transform = format)
                            } else {
                                stages.take(7).joinToString(" → ", transform = format) +
                                    " → … → " + stages.takeLast(7).joinToString(" → ", transform = format)
                            }
                            Text(sequence, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            if (stages.size > 15) {
                                TextButton(onClick = { showFullSequence = !showFullSequence }) {
                                    Text(if (showFullSequence) "收起序列" else "展开完整序列")
                                }
                            }
                        }
                        Text(
                            "CSV 每 ${state.recordIntervalMs} ms 采样。保存后可在「实验数据」查看。",
                            fontSize = 14.sp, color = Color(0xFF455A64)
                        )
                    }
                }
            }
        }
    }

    val saveNotice by viewModel.writeSaveNotice.observeAsState()
    val showSaveError = remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf("") }
    LaunchedEffect(saveNotice) {
        saveNotice?.let { (failed, message) ->
            if (failed) {
                saveError = message
                showSaveError.value = true
            } else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.writeSaveNotice.value = null
        }
    }
    if (showSaveError.value) {
        ShowConfirmDialog("实验数据保存失败", saveError, showDialog = showSaveError, showDismiss = false)
    }
}

@Composable
private fun TrialLiveCard(
    row: Int,
    status: DepthTestStatus?,
    motor: ServoCalibration?,
    sample: MeasuredDepthSample,
    fix: DepthTrialFix?,
    recording: Boolean,
    stopping: Boolean
) {
    val phase = if (stopping) DepthTestPhase.STOPPING else status?.phase ?: DepthTestPhase.PREPARING
    val stage = status?.stageIndex ?: 0
    val count = status?.stageCount ?: 0
    val now = SystemClock.elapsedRealtime()
    val location = fix?.takeIf { it.ageAt(now) != null }
    val position = motor?.takeIf { it.isOnline && it.positionAtMs > 0L && now - it.positionAtMs in 0L..2_000L }
    val phaseLabel = when (phase) {
        DepthTestPhase.MOVING -> "前往目标"
        DepthTestPhase.DWELL -> "到位停留"
        DepthTestPhase.TIMEOUT -> "到位超时"
        DepthTestPhase.STOPPING -> "正在停止"
        DepthTestPhase.PREPARING, DepthTestPhase.IDLE -> "正在准备"
        else -> "正在收尾"
    }
    TrialCard("第 ${row + 1} 行 · $phaseLabel") {
        if (count > 0) {
            Text("进度  $stage / $count 级", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = (stage.toFloat() / count).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TrialReadout("目标", depthText(status?.targetDepth?.takeIf { stage > 0 }), Modifier.weight(1f))
            TrialReadout("伺服解算", depthText(position?.takeIf { it.fitValid }?.currentDepth), Modifier.weight(1f))
            TrialReadout("传感器实测", depthText(sample.depthMm), Modifier.weight(1f))
        }
        Text(
            "编码器 ${position?.currentPosition ?: "—"} · 传感器${DepthDisplayFormat.qualityLabel(sample.quality)}",
            fontSize = 15.sp
        )
        val gnss = when {
            location == null -> "无有效定位 · 坐标留空"
            location.rowLongitude != null && location.rowLatitude != null ->
                coordinateText("单体", location.rowLongitude, location.rowLatitude)
            else -> coordinateText("天线 · 等待航向", location.antennaLongitude, location.antennaLatitude)
        }
        Text(if (location?.simulated == true) "模拟 GNSS · $gnss" else "GNSS · $gnss", fontSize = 15.sp)
        Text(
            when {
                phase == DepthTestPhase.STOPPING -> "CSV 正在收尾"
                recording && phase != DepthTestPhase.PREPARING -> "CSV 正在采样"
                else -> "CSV 正在准备"
            },
            color = Color(0xFF176E49), fontWeight = FontWeight.Bold, fontSize = 15.sp
        )
        status?.message?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 15.sp) }
        status?.anomalies?.lastOrNull()?.let { Text(it, fontSize = 15.sp, color = Color(0xFFC62828)) }
    }
}

@Composable
private fun TrialResultCard(status: DepthTestStatus) {
    val title = when (status.phase) {
        DepthTestPhase.COMPLETE -> "上次试验已完成"
        DepthTestPhase.ABORTED -> "上次试验已停止"
        else -> "上次试验结果"
    }
    TrialCard(title) {
        if (status.message.isNotBlank()) Text(status.message, fontSize = 15.sp)
        if (status.anomalies.isNotEmpty()) {
            Text("异常 ${status.anomalies.size} 项 · ${status.anomalies.last()}", fontSize = 15.sp, color = Color(0xFFC62828))
        }
    }
}

@Composable
private fun TrialCard(
    title: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable () -> Unit
) {
    Card(modifier, shape = RoundedCornerShape(10.dp), elevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun TrialRowButton(row: Int, motor: ServoCalibration, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp).semantics { selected = isSelected },
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) Color(0xFF1565C0) else Color(0xFFB0BEC5)),
        colors = ButtonDefaults.outlinedButtonColors(backgroundColor = if (isSelected) Color(0xFFE3F2FD) else Color.White)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("第 ${row + 1} 行", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                if (motor.isOnline) motorStatusLabel(motor) else "待回读",
                color = if (motor.isOnline) motorStatusColor(motor) else Color(0xFF546E7A),
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun TrialField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Decimal
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun TrialReadout(label: String, value: String, modifier: Modifier) {
    Surface(modifier, color = Color(0xFFEAF1F4), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(8.dp)) {
            Text(label, fontSize = 14.sp, color = Color(0xFF455A64))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun depthText(value: Float?): String =
    value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.1f mm", it) } ?: "—"

private fun coordinateText(label: String, longitude: Double, latitude: Double): String =
    String.format(Locale.ROOT, "%s %.7f°, %.7f°", label, longitude, latitude)
