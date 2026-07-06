/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2026年7月4日
 * @file    :
 * @brief   :一键播种深度性能测试界面
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 选择被测电机 + 配置梯度/速度/加速度等参数，一键执行往返梯度测试（默认 20→80→20mm，梯度 20mm）
 * 测试全程 100ms 采样记录 CSV（depthTest_ 前缀），可在「查看已保存记录」界面查看
 ***********************************************************************************************************
 */

package com.nx.vfremake.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.coroutine.CanReceiveCoroutine
import com.nx.vfremake.coroutine.DepthTestCoroutine
import com.nx.vfremake.coroutine.SowingDepthCoroutine
import com.nx.vfremake.data.DepthTestConfig
import com.nx.vfremake.data.SowingDepthState
import com.nx.vfremake.data.activeSowingDepthMotorIndices
import com.nx.vfremake.funClass.DocuAndManageFun
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.isSystemRunning
import com.nx.vfremake.mSPParamData

/**
 * 生成梯度序列预览（与 DepthTestCoroutine.buildStageSequence 逻辑一致，仅用于界面展示）
 * 参数非法或级数过多时返回空列表
 */
private fun previewStages(start: Float?, end: Float?, step: Float?): List<Float> {
    if (start == null || end == null || step == null) return emptyList()
    if (step < 1f || start >= end) return emptyList()
    if ((end - start) / step > 50f) return emptyList()
    val up = mutableListOf<Float>()
    var d = start
    while (d < end - 0.01f) {
        up.add(d)
        d += step
    }
    up.add(end)
    return up + up.reversed().drop(1)
}

/** 深度数值展示：整数省略小数位 */
private fun fmtDepth(v: Float): String =
    if (v % 1f == 0f) "%.0f".format(v) else "%.1f".format(v)

/**
 * 一键播种深度性能测试界面
 *
 * 测试流程：运行到起始深度 → 按梯度逐级至终点 → 按梯度逐级返回起始深度，
 * 每级到位后停留设定时长。全程 100ms 采样记录编码器位置/深度/时间戳到 CSV。
 *
 * @param viewModel 共享 ViewModel（sowingDepthState / depthTestStatus / writeSaveNotice）
 * @param onBack    返回上级页面
 */
@Composable
fun DepthTestScreen(
    viewModel: VariableFertViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val state by viewModel.sowingDepthState.observeAsState(SowingDepthState())
    val status by viewModel.depthTestStatus.observeAsState()
    val activeMotorsState by viewModel.activeMotorsState.observeAsState(mSPParamData.activeMotors)
    val activeMotorIndices = activeSowingDepthMotorIndices(mSPParamData.rowNumber, activeMotorsState)

    // 一键测试协程实例（页面级持有；离开页面 shutdown 时记录器自动落盘保存）
    val testCoroutine = remember { DepthTestCoroutine() }

    // ── 参数输入 ─────────────────────────────────────────────────────────────
    var motorIndex by remember { mutableStateOf(0) }
    var startInput by remember { mutableStateOf("20") }
    var endInput   by remember { mutableStateOf("80") }
    var stepInput  by remember { mutableStateOf("20") }
    var dwellInput by remember { mutableStateOf("2.0") }
    var tolInput   by remember { mutableStateOf("0.5") }
    // 速度/加速度跟随全局设置初始化（测试启动时写回全局并持久化）
    var speedInput by remember(state.positionSpeed) { mutableStateOf(state.positionSpeed.toString()) }
    var accelInput by remember(state.acceleration) { mutableStateOf(state.acceleration.toString()) }

    // 测试进行中：点击启动即置 true；收到 running=false 的状态（完成/失败/中止）后清除
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        status?.let { busy = it.running }
    }
    LaunchedEffect(activeMotorIndices) {
        if (activeMotorIndices.isNotEmpty() && motorIndex !in activeMotorIndices) {
            motorIndex = activeMotorIndices.first()
        }
    }

    // ── 串口与协程生命周期（与 DepthCalibrationScreen 同模式）──────────────────
    // 本页需要 CanReceiveCoroutine 更新反馈 + SowingDepthCoroutine Phase 4 下发位置帧。
    // 施肥作业运行中时 MainActivity 已独占持有协程实例，本页跳过启动避免双实例竞争。
    // 离开页面不关串口：跨页 DisposableEffect 时序竞态，释放由 Activity 级生命周期负责。
    DisposableEffect(Unit) {
        val receiveCoroutine: CanReceiveCoroutine?
        val depthCoroutine: SowingDepthCoroutine?
        if (!isSystemRunning) {
            MySerialPortFun.ensureCanPortOpen(context)
            receiveCoroutine = CanReceiveCoroutine().also { it.start(viewModel, context) }
            depthCoroutine   = SowingDepthCoroutine().also { it.start(viewModel, context) }
        } else {
            receiveCoroutine = null
            depthCoroutine   = null
        }
        onDispose {
            // 测试中离开页面：目标深度回写当前深度，电机在下一次 Phase 4 下发时停在原地
            // （返回上一页后 SowingDepthScreen 会重启协程），再取消测试协程并落盘保存
            if (testCoroutine.isRunning) {
                val idx = viewModel.depthTestStatus.value?.motorIndex ?: motorIndex
                val cur = viewModel.currentSowingDepthState().motors.getOrNull(idx)?.currentDepth
                if (cur != null) {
                    viewModel.updateServoCalibration(idx) { it.copy(targetDepth = cur) }
                }
            }
            testCoroutine.shutdown()
            receiveCoroutine?.shutdown()
            depthCoroutine?.shutdown()
        }
    }

    // ── 启动测试 ─────────────────────────────────────────────────────────────
    fun startTest() {
        if (isSystemRunning) {
            Toast.makeText(context, "作业进行中，无法启动性能测试", Toast.LENGTH_SHORT).show()
            return
        }
        if (motorIndex !in activeMotorIndices) {
            Toast.makeText(context, "请先在参数设置页启用要测试的播种深度电机", Toast.LENGTH_SHORT).show()
            return
        }
        if (DocuAndManageFun().getWriteDirDocumentUri(context) == null) {
            Toast.makeText(
                context,
                "未设置保存目录，请先在主界面开启保存实验数据并授权目录",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val start = startInput.toFloatOrNull()
        val end   = endInput.toFloatOrNull()
        val step  = stepInput.toFloatOrNull()
        val dwell = dwellInput.toFloatOrNull()
        val tol   = tolInput.toFloatOrNull()
        val speed = speedInput.toIntOrNull()
        val accel = accelInput.toIntOrNull()
        if (start == null || end == null || step == null || dwell == null ||
            tol == null || speed == null || accel == null
        ) {
            Toast.makeText(context, "参数格式有误，请检查输入", Toast.LENGTH_SHORT).show()
            return
        }
        if (previewStages(start, end, step).isEmpty()) {
            Toast.makeText(context, "梯度序列无效：须满足 起始<终点、梯度≥1mm", Toast.LENGTH_SHORT).show()
            return
        }
        if (speed !in 100..3000) {
            Toast.makeText(context, "运动速度须在 100~3000 RPM 内", Toast.LENGTH_SHORT).show()
            return
        }
        if (accel !in 1000..60000) {
            Toast.makeText(context, "加速度须在 1000~60000 RPM/s 内", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        testCoroutine.start(
            context, viewModel,
            DepthTestConfig(
                motorIndex    = motorIndex,
                startDepthMm  = start,
                endDepthMm    = end,
                stepMm        = step,
                dwellMs       = (dwell * 1000).toLong().coerceAtLeast(0L),
                toleranceMm   = if (tol > 0f) tol else 0.5f,
                positionSpeed = speed,
                acceleration  = accel
            )
        )
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = { MyTopBar("深度性能测试", onBack) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 作业模式警告
            if (isSystemRunning) {
                Card(
                    backgroundColor = Color(0xFFFFF3CD),
                    elevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "⚠ 作业模式运行中，请停止作业后再进行性能测试",
                        color = Color(0xFF856404),
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp
                    )
                }
            }

            // ── 被测电机选择 ─────────────────────────────────────────────────
            Card(
                elevation = 2.dp,
                shape     = RoundedCornerShape(10.dp),
                modifier  = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("被测电机", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (activeMotorIndices.isEmpty()) {
                        Text(
                            "未启用播种深度电机，请先在参数设置页开启需要测试的行。",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                    } else {
                    activeMotorIndices.chunked(4).forEach { rowIndices ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowIndices.forEach { i ->
                                val cal = state.motors[i]
                                val selected = motorIndex == i
                                OutlinedButton(
                                    onClick        = { if (!busy) motorIndex = i },
                                    modifier       = Modifier.weight(1f),
                                    shape          = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 6.dp),
                                    border         = BorderStroke(
                                        if (selected) 2.dp else 1.dp,
                                        if (selected) Color(0xFF1565C0) else Color.LightGray
                                    ),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        backgroundColor =
                                            if (selected) Color(0xFFE3F2FD) else Color.White
                                    )
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "${i + 1}号",
                                            fontSize   = 14.sp,
                                            color      = Color.Black,
                                            fontWeight = if (selected) FontWeight.SemiBold
                                                         else FontWeight.Normal
                                        )
                                        Text(
                                            motorStatusLabel(cal),
                                            fontSize = 10.sp,
                                            color    = motorStatusColor(cal)
                                        )
                                    }
                                }
                            }
                            repeat(4 - rowIndices.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    }
                    // 选中电机实时反馈
                    val selectedMotorIndex = if (motorIndex in activeMotorIndices) {
                        motorIndex
                    } else {
                        activeMotorIndices.firstOrNull() ?: 0
                    }
                    val sel = state.motors[selectedMotorIndex]
                    Divider()
                    if (activeMotorIndices.isNotEmpty()) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("当前深度 %.2f mm".format(sel.currentDepth),
                            fontSize = 13.sp, color = Color.DarkGray)
                        Text("编码器 ${sel.currentPosition}",
                            fontSize = 13.sp, color = Color.DarkGray)
                        Text(
                            if (!sel.limitsSet || !sel.fitValid) "未标定" else "已标定",
                            fontSize = 13.sp,
                            color    = if (!sel.limitsSet || !sel.fitValid) Color(0xFFD32F2F)
                                       else Color(0xFF388E3C)
                        )
                    }
                    }
                }
            }

            // ── 测试参数 ─────────────────────────────────────────────────────
            Card(
                elevation = 2.dp,
                shape     = RoundedCornerShape(10.dp),
                modifier  = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("测试参数", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startInput, onValueChange = { startInput = it },
                            label = { Text("起始深度mm") }, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endInput, onValueChange = { endInput = it },
                            label = { Text("终点深度mm") }, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = stepInput, onValueChange = { stepInput = it },
                            label = { Text("梯度mm") }, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dwellInput, onValueChange = { dwellInput = it },
                            label = { Text("停留时间s") }, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = tolInput, onValueChange = { tolInput = it },
                            label = { Text("到位容差mm") }, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = speedInput, onValueChange = { speedInput = it },
                            label = { Text("运动速度RPM") }, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = accelInput, onValueChange = { accelInput = it },
                            label = { Text("加速度RPM/s") }, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    // 梯度序列预览
                    val stages = previewStages(
                        startInput.toFloatOrNull(), endInput.toFloatOrNull(), stepInput.toFloatOrNull()
                    )
                    Text(
                        if (stages.isEmpty()) "梯度序列无效（须满足 起始<终点、梯度≥1mm）"
                        else "序列：${stages.joinToString("→") { fmtDepth(it) }} mm（共 ${stages.size} 级）",
                        fontSize = 12.sp,
                        color    = if (stages.isEmpty()) Color(0xFFD32F2F) else Color.Gray
                    )
                }
            }

            // ── 测试状态 ─────────────────────────────────────────────────────
            status?.let { s ->
                Card(
                    elevation       = 2.dp,
                    shape           = RoundedCornerShape(10.dp),
                    modifier        = Modifier.fillMaxWidth(),
                    backgroundColor = when {
                        s.running   -> Color(0xFFE3F2FD)
                        s.finished  -> Color(0xFFE8F5E9)
                        else        -> Color(0xFFFFF3E0)
                    }
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text(
                                when {
                                    s.running  -> "测试进行中：电机 ${s.motorIndex + 1}"
                                    s.finished -> "测试完成：电机 ${s.motorIndex + 1}"
                                    else       -> "测试未完成"
                                },
                                fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                            )
                            if (s.stageCount > 0) {
                                Text("第 ${s.stageIndex}/${s.stageCount} 级", fontSize = 13.sp)
                            }
                        }
                        if (s.running) {
                            LinearProgressIndicator(
                                progress = if (s.stageCount > 0)
                                    s.stageIndex.toFloat() / s.stageCount else 0f,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${s.stageLabel} mm　目标 %.1f mm".format(s.targetDepth),
                                fontSize = 13.sp
                            )
                        }
                        if (s.message.isNotEmpty()) {
                            Text(s.message, fontSize = 13.sp, color = Color.DarkGray)
                        }
                        if (s.anomalies.isNotEmpty()) {
                            Text(
                                "异常：${s.anomalies.joinToString("；")}",
                                fontSize = 12.sp, color = Color(0xFFD32F2F)
                            )
                        }
                    }
                }
            }

            // ── 启动 / 中止 ──────────────────────────────────────────────────
            if (busy) {
                Button(
                    onClick  = { testCoroutine.abort() },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(8.dp),
                    colors   = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD32F2F)),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Text("中止测试", color = Color.White, fontSize = 16.sp)
                }
            } else {
                Button(
                    onClick  = { startTest() },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(8.dp),
                    colors   = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF388E3C)),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Text("开始测试", color = Color.White, fontSize = 16.sp)
                }
            }

            Text(
                "测试流程：先运行到起始深度，再按梯度逐级至终点后逐级返回；每级到位停留设定时长。\n" +
                    "全程 100ms 采样记录 CSV（depthTest_ 前缀），可在「设置→查看已保存记录」中查看。",
                fontSize = 12.sp, color = Color.Gray
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    // ── 实验数据保存结果一次性提示：成功 Toast，失败弹窗 ──────────────────────
    val writeSaveNotice by viewModel.writeSaveNotice.observeAsState()
    val writeSaveFailText = remember { mutableStateOf("") }
    val showWriteSaveFail = remember { mutableStateOf(false) }
    LaunchedEffect(writeSaveNotice) {
        writeSaveNotice?.let { (isFail, msg) ->
            if (isFail) {
                writeSaveFailText.value = msg
                showWriteSaveFail.value = true
            } else {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
            viewModel.writeSaveNotice.postValue(null)  // 消费一次，避免重复弹
        }
    }
    if (showWriteSaveFail.value) {
        ShowConfirmDialog(
            title = "实验数据保存失败",
            text = writeSaveFailText.value,
            showDialog = showWriteSaveFail,
            showDismiss = false
        )
    }
}
