package com.nx.vfremake.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import android.serialport.SerialPort
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import com.nx.vfremake.R
import com.nx.vfremake.isSystemRunning
import com.nx.vfremake.mSerialPortCAN
import com.nx.vfremake.coroutine.CanReceiveCoroutine
import com.nx.vfremake.coroutine.DepthRecordFun
import com.nx.vfremake.coroutine.SowingDepthCoroutine
import com.nx.vfremake.funClass.DocuAndManageFun
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.data.SowingDepthState
import com.nx.vfremake.data.activeSowingDepthMotorIndices
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.mSPParamData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 播种深度主控制界面
 *
 * 功能：
 *   - 全局目标深度设置（同步到全部在线电机）
 *   - 8 路电机状态卡片：在线/离线/报警、当前深度、目标深度、单独设置
 *   - 标定设置导航（选择电机后跳转 DepthCalibrationScreen）
 *   - 全部急停
 *
 * @param viewModel       共享 ViewModel
 * @param onClickBack     返回上级
 * @param onClickCalibrate 跳转到指定电机的校准界面
 * @param onClickDepthTest 跳转到一键深度性能测试界面
 */
@Composable
fun SowingDepthScreen(
    viewModel: VariableFertViewModel,
    onClickBack: () -> Unit,
    onClickCalibrate: (motorIndex: Int) -> Unit,
    onClickDepthTest: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // 手动实验数据记录器（100ms 采样 8 路电机；离开页面自动落盘保存）
    val depthRecorder = remember { DepthRecordFun() }
    var isRecording by remember { mutableStateOf(false) }

    // ── 独立串口与协程生命周期 ───────────────────────────────────────────────────
    // 进入页面时自动开启 CAN 串口 + 接收/控制协程；离开时自动关闭。
    // 若施肥系统正在运行（isSystemRunning=true），跳过开启：此时 SowingDepthCoroutine
    // 由 MainActivity 独占持有（处方图控深模式），本页再启一份会造成双实例同时写
    // 同一 CAN 串口、重复 DS402 初始化与冲突的绝对位移帧。CAN 串口本身可共享
    // （施肥帧与 CANopen 帧共用 MySerialPortFun.CAN_TX_LOCK 字节级互斥，CANopen
    // 帧另经 CanOpenFun 全局串行化步调），跳过的真实原因是避免重复协程实例。
    DisposableEffect(Unit) {
        if (!isSystemRunning) {
            val sp       = MySharedPreFun(context).getMySharedPre()
            val portName = sp.getString(
                context.getString(R.string.serial_can_port_name),
                context.getString(R.string.serial_can_port_defValue)
            ) ?: ""
            val baud = sp.getString(
                context.getString(R.string.serial_can_baud_name),
                context.getString(R.string.serial_can_baud_defValue)
            )?.toIntOrNull() ?: 115200

            val portOpenedHere = if (mSerialPortCAN == null && portName.isNotEmpty()) {
                try { mSerialPortCAN = SerialPort(File("/dev/$portName"), baud); true }
                catch (_: Exception) { false }
            } else { false }

            val receiveCoroutine = CanReceiveCoroutine().also { it.start(viewModel, context) }
            val depthCoroutine   = SowingDepthCoroutine().also { it.start(viewModel, context) }

            onDispose {
                // 记录中离开页面：自动停止并落盘保存
                if (depthRecorder.isRunning) depthRecorder.shutdown()
                receiveCoroutine.shutdown()
                depthCoroutine.shutdown()
                if (portOpenedHere) {
                    try { mSerialPortCAN?.tryClose() } catch (_: Exception) {}
                    mSerialPortCAN = null
                }
            }
        } else {
            onDispose {
                if (depthRecorder.isRunning) depthRecorder.shutdown()
            }
        }
    }

    val state by viewModel.sowingDepthState.observeAsState(SowingDepthState())
    val activeMotorsState by viewModel.activeMotorsState.observeAsState(mSPParamData.activeMotors)
    val activeMotorIndices = activeSowingDepthMotorIndices(mSPParamData.rowNumber, activeMotorsState)

    // 全局深度输入框的临时值（初始同步自 state，不随 state 每次变化而强制覆盖）
    var globalDepthInput by remember { mutableStateOf("%.1f".format(state.globalTargetDepth)) }

    // 位置运动速度本地状态（初始同步自 state，持久化到 SharedPreferences）
    var positionSpeed by remember { mutableStateOf(state.positionSpeed) }

    // 加减速度本地状态（初始同步自 state，持久化到 SharedPreferences）
    var acceleration by remember { mutableStateOf(state.acceleration) }

    // 单独设置弹窗：哪路电机正在设置（null = 未打开）
    var motorDialogIndex by remember { mutableStateOf<Int?>(null) }
    var motorDialogInput by remember { mutableStateOf("") }

    // 标定设置弹窗（选择电机后跳转）
    var showCalibrateDialog by remember { mutableStateOf(false) }

    // ── 辅助：全部应用 ────────────────────────────────────────────────────────
    fun applyGlobalDepth() {
        val depth = globalDepthInput.toFloatOrNull() ?: return
        if (depth <= 0f) return
        // 更新全局目标深度
        viewModel.updateSowingDepthGlobalSettings(globalTargetDepth = depth)
        // 同步到每路电机的 targetDepth
        val updates = activeMotorIndices.associate { i ->
            i to { cal: ServoCalibration -> cal.copy(targetDepth = depth) }
        }
        viewModel.updateMultipleServos(updates)
        // 持久化
        scope.launch(Dispatchers.IO) {
            MySharedPreFun(context).saveSowingDepthGlobalSettings(
                jogSpeed          = state.jogSpeed,
                positionSpeed     = state.positionSpeed,
                acceleration      = state.acceleration,
                globalTargetDepth = depth
            )
        }
    }

    // ── 辅助：单独设置确认 ────────────────────────────────────────────────────
    fun applyMotorDepth(motorIndex: Int) {
        val depth = motorDialogInput.toFloatOrNull() ?: return
        if (depth <= 0f) return
        viewModel.updateServoCalibration(motorIndex) { it.copy(targetDepth = depth) }
        motorDialogIndex = null
    }

    // ── 辅助：手动实验数据记录启停 ────────────────────────────────────────────
    fun startManualRecord() {
        if (isSystemRunning) {
            Toast.makeText(context, "作业进行中，实验数据由主界面记录", Toast.LENGTH_SHORT).show()
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
        depthRecorder.start(
            context = context,
            mVariableFertViewModel = viewModel,
            filePrefix = "depthRec",
            header = DepthRecordFun.buildDepthRecordHeader(),
            intervalMs = 100L
        ) { _ ->
            // 每次采样写出 8 路电机各一行（长表格式，Origin 按电机号筛选）
            val motors = viewModel.currentSowingDepthState().motors
            activeSowingDepthMotorIndices(
                mSPParamData.rowNumber,
                viewModel.activeMotorsState.value ?: mSPParamData.activeMotors
            ).mapNotNull { index -> motors.getOrNull(index) }.map { m ->
                listOf(
                    (m.motorIndex + 1).toString(),
                    "手动",
                    "%.2f".format(m.targetDepth),
                    "%.2f".format(m.currentDepth),
                    m.currentPosition.toString(),
                    if (m.isOnline) "1" else "0",
                    m.alarmCode.toString()
                )
            }
        }
        isRecording = true
    }

    fun stopManualRecord() {
        depthRecorder.shutdown()
        isRecording = false
    }

    // ── 辅助：全部急停 ────────────────────────────────────────────────────────
    fun emergencyStopAll() {
        scope.launch(Dispatchers.IO) {
            activeMotorIndices.mapNotNull { state.motors.getOrNull(it) }.forEach { cal ->
                if (cal.isOnline) {
                    // sendFrameSequenced：走全局串行化步调，急停帧不会与其他发送方的帧相撞被丢
                    CanOpenFun.sendFrameSequenced(CanOpenFun.buildQuickStopFrame(cal.nodeId))
                }
            }
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = { MyTopBar("播种深度控制", onClickBack) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 互斥警告（施肥系统运行中时显示）────────────────────────────────
            if (isSystemRunning) {
                Card(
                    elevation         = 2.dp,
                    shape             = RoundedCornerShape(10.dp),
                    modifier          = Modifier.fillMaxWidth(),
                    backgroundColor   = Color(0xFFFFF3E0)
                ) {
                    Text(
                        "⚠ 施肥系统运行中，播种深度控制不可用（同一 CAN 串口）。请先停止作业。",
                        fontSize = 13.sp,
                        color    = Color(0xFFE65100),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // ── 测试模式提示（测试模式开启时显示）───────────────────────────────
            val isTestMode = MySharedPreFun(context).getSpecificValue(
                R.string.testMode_Switch_name) == "1"
            if (isTestMode) {
                Card(
                    elevation       = 2.dp,
                    shape           = RoundedCornerShape(10.dp),
                    modifier        = Modifier.fillMaxWidth(),
                    backgroundColor = Color(0xFFE3F2FD)
                ) {
                    Text(
                        "测试模式：所有电机默认在线，CAN 帧将正常发送（用于通讯排查）",
                        fontSize = 13.sp,
                        color    = Color(0xFF1565C0),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // ── 深度控制总开关（红/绿背景指示状态）─────────────────────────────
            val masterEnabled = state.masterEnabled
            Card(
                elevation       = 3.dp,
                shape           = RoundedCornerShape(10.dp),
                modifier        = Modifier.fillMaxWidth(),
                backgroundColor = if (masterEnabled) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
            ) {
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "深度控制总开关",
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 16.sp,
                            color      = if (masterEnabled) Color(0xFF2E7D32) else Color(0xFFC62828)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (masterEnabled) "● 运行中：电机响应深度设置"
                            else                "○ 已停止：电机已禁用",
                            fontSize = 12.sp,
                            color    = if (masterEnabled) Color(0xFF388E3C) else Color(0xFF757575)
                        )
                    }
                    Switch(
                        checked         = masterEnabled,
                        onCheckedChange = { viewModel.updateMasterEnabled(it) },
                        modifier        = Modifier.scale(1.3f),
                        enabled         = !isSystemRunning   // 施肥系统运行时禁用（CAN 串口互斥）
                    )
                }
            }

            // ── 全局目标深度 ─────────────────────────────────────────────────
            Card(
                elevation = 2.dp,
                shape     = RoundedCornerShape(10.dp),
                modifier  = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("全局目标深度", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value          = globalDepthInput,
                            onValueChange  = { globalDepthInput = it },
                            label          = { Text("目标深度 (mm)", fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine     = true,
                            modifier       = Modifier.weight(1f)
                        )
                        Button(
                            onClick  = { applyGlobalDepth() },
                            colors   = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0)),
                            shape    = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text("全部应用", color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }

            // ── 运动参数（位置速度）──────────────────────────────────────────
            Card(
                elevation = 2.dp,
                shape     = RoundedCornerShape(10.dp),
                modifier  = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("运动参数", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    // steps=289 → (3000-100)/10 - 1，步长 10 RPM
                    Text("位置运动速度: $positionSpeed RPM", fontSize = 14.sp)
                    Slider(
                        value                 = positionSpeed.toFloat(),
                        onValueChange         = { positionSpeed = it.toInt() },
                        onValueChangeFinished = {
                            viewModel.updateSowingDepthGlobalSettings(positionSpeed = positionSpeed)
                            scope.launch(Dispatchers.IO) {
                                MySharedPreFun(context).saveSowingDepthGlobalSettings(
                                    jogSpeed          = state.jogSpeed,
                                    positionSpeed     = positionSpeed,
                                    acceleration      = acceleration,
                                    globalTargetDepth = state.globalTargetDepth
                                )
                            }
                        },
                        valueRange = 100f..3000f,
                        steps      = 289,
                        colors     = SliderDefaults.colors(
                            thumbColor       = Color(0xFF1565C0),
                            activeTrackColor = Color(0xFF1565C0)
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    // steps=58 → 步长 1000 RPM/s，范围 1000~60000
                    Text("加减速度: $acceleration RPM/s", fontSize = 14.sp)
                    Slider(
                        value                 = acceleration.toFloat(),
                        onValueChange         = { acceleration = it.toInt() },
                        onValueChangeFinished = {
                            viewModel.updateSowingDepthGlobalSettings(acceleration = acceleration)
                            scope.launch(Dispatchers.IO) {
                                MySharedPreFun(context).saveSowingDepthGlobalSettings(
                                    jogSpeed          = state.jogSpeed,
                                    positionSpeed     = positionSpeed,
                                    acceleration      = acceleration,
                                    globalTargetDepth = state.globalTargetDepth
                                )
                            }
                        },
                        valueRange = 1000f..60000f,
                        steps      = 58,
                        colors     = SliderDefaults.colors(
                            thumbColor       = Color(0xFF1565C0),
                            activeTrackColor = Color(0xFF1565C0)
                        )
                    )
                }
            }

            // ── 实验数据记录（手动）─────────────────────────────────────────
            Card(
                elevation       = 2.dp,
                shape           = RoundedCornerShape(10.dp),
                modifier        = Modifier.fillMaxWidth(),
                backgroundColor = if (isRecording) Color(0xFFFFF8E1) else Color.White
            ) {
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("实验数据记录", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (isRecording) "● 记录中（100ms 采样，8 路电机）"
                            else             "记录编码器位置/深度到 CSV，用于 Origin/Excel 分析",
                            fontSize = 12.sp,
                            color    = if (isRecording) Color(0xFFE65100) else Color.Gray
                        )
                    }
                    Button(
                        onClick = { if (isRecording) stopManualRecord() else startManualRecord() },
                        colors  = ButtonDefaults.buttonColors(
                            backgroundColor = if (isRecording) Color(0xFFD32F2F) else Color(0xFF388E3C)
                        ),
                        shape          = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            if (isRecording) "停止记录" else "开始记录",
                            color = Color.White, fontSize = 14.sp
                        )
                    }
                }
            }

            // ── 8 路电机状态卡片 ─────────────────────────────────────────────
            Text(
                "电机状态",
                fontWeight = FontWeight.SemiBold,
                fontSize   = 14.sp,
                color      = Color.Gray
            )

            if (activeMotorIndices.isEmpty()) {
                Text(
                    "未启用播种深度电机，请先在参数设置页开启需要作业的行。",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            } else {
                activeMotorIndices.forEach { i ->
                    val cal = state.motors[i]
                    MotorStatusCard(
                        cal             = cal,
                        motorIndex      = i,
                        onSingleSet     = {
                            motorDialogInput = "%.1f".format(cal.targetDepth)
                            motorDialogIndex = i
                        },
                        onCalibrate     = { onClickCalibrate(i) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── 底部操作按钮 ─────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick  = { showCalibrateDialog = true },
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Text("标定设置", fontSize = 14.sp)
                }
                OutlinedButton(
                    onClick  = { onClickDepthTest() },
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    border   = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1565C0))
                ) {
                    Text("性能测试", color = Color(0xFF1565C0), fontSize = 14.sp)
                }
                Button(
                    onClick  = { emergencyStopAll() },
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD32F2F)),
                    shape    = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Text("全部停止", color = Color.White, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // ── 实验数据保存结果一次性提示：成功 Toast，失败弹窗 ──────────────────────
    // 注：与 MainScreen 的同名消费互不冲突——Navigation 下同一时刻只有一个页面在组合中
    val writeSaveNotice by viewModel.writeSaveNotice.observeAsState()
    val writeSaveFailText = remember { mutableStateOf("") }
    val showWriteSaveFail = remember { mutableStateOf(false) }
    LaunchedEffect(writeSaveNotice) {
        writeSaveNotice?.let { (isFail, msg) ->
            if (isFail) {
                writeSaveFailText.value = msg
                showWriteSaveFail.value = true
                isRecording = false  // 保存失败时记录协程已自行终止，同步按钮状态
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

    // ── 单独设置弹窗 ─────────────────────────────────────────────────────────
    motorDialogIndex?.let { idx ->
        AlertDialog(
            onDismissRequest = { motorDialogIndex = null },
            title            = { Text("电机 ${idx + 1} 独立目标深度") },
            text             = {
                OutlinedTextField(
                    value           = motorDialogInput,
                    onValueChange   = { motorDialogInput = it },
                    label           = { Text("目标深度 (mm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine      = true
                )
            },
            confirmButton = {
                Button(onClick = { applyMotorDepth(idx) }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { motorDialogIndex = null }) { Text("取消") }
            }
        )
    }

    // ── 标定设置：选择电机弹窗 ────────────────────────────────────────────────
    if (showCalibrateDialog) {
        AlertDialog(
            onDismissRequest = { showCalibrateDialog = false },
            title            = { Text("选择要标定的电机") },
            text             = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    activeMotorIndices.forEach { i ->
                        val cal = state.motors[i]
                        val statusColor = motorStatusColor(cal)
                        OutlinedButton(
                            onClick  = { showCalibrateDialog = false; onClickCalibrate(i) },
                            modifier = Modifier.fillMaxWidth(),
                            shape    = RoundedCornerShape(6.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "电机 ${i + 1}（Node-ID: ${cal.nodeId}）" +
                                if (cal.limitsSet) "  已限位" else "  未限位",
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            },
            confirmButton   = {},
            dismissButton   = {
                TextButton(onClick = { showCalibrateDialog = false }) { Text("取消") }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 单路电机状态卡片
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MotorStatusCard(
    cal:         ServoCalibration,
    motorIndex:  Int,
    onSingleSet: () -> Unit,
    onCalibrate: () -> Unit
) {
    val statusColor   = motorStatusColor(cal)
    val statusLabel   = motorStatusLabel(cal)

    Card(
        elevation = 1.dp,
        shape     = RoundedCornerShape(8.dp),
        modifier  = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 标题行：状态圆点 + 电机号 + Node-ID + 状态文字
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    "电机 ${motorIndex + 1}",
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Node-ID: ${cal.nodeId}",
                    fontSize = 12.sp,
                    color    = Color.Gray
                )
                Spacer(Modifier.weight(1f))
                Text(
                    statusLabel,
                    fontSize = 12.sp,
                    color    = statusColor,
                    fontWeight = FontWeight.Medium
                )
            }

            Divider(
                color     = Color(0xFFEEEEEE),
                thickness = 0.5.dp,
                modifier  = Modifier.padding(vertical = 8.dp)
            )

            // 深度信息行
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                // 当前深度
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("当前深度", fontSize = 11.sp, color = Color.Gray)
                    if (cal.fitValid && cal.isOnline) {
                        Text(
                            "%.1f mm".format(cal.currentDepth),
                            fontSize   = 18.sp,
                            color      = Color(0xFF1565C0),
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            if (!cal.fitValid) "未校准" else "---",
                            fontSize = 14.sp,
                            color    = Color.Gray
                        )
                    }
                }

                // 目标深度
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("目标深度", fontSize = 11.sp, color = Color.Gray)
                    if (cal.targetDepth > 0f) {
                        Text(
                            "%.1f mm".format(cal.targetDepth),
                            fontSize   = 18.sp,
                            color      = Color(0xFF388E3C),
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text("未设置", fontSize = 14.sp, color = Color.Gray)
                    }
                }

                // 操作按钮列
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick         = onSingleSet,
                        colors          = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                        shape           = RoundedCornerShape(6.dp),
                        contentPadding  = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("单独设置", color = Color.White, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick         = onCalibrate,
                        shape           = RoundedCornerShape(6.dp),
                        contentPadding  = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        border          = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1565C0))
                    ) {
                        Text("标定", color = Color(0xFF1565C0), fontSize = 12.sp)
                    }
                }
            }

            // 报警信息（仅在报警时显示）
            if (cal.alarmCode != 0) {
                Spacer(Modifier.height(6.dp))
                val alarmText = when (cal.alarmCode) {
                    1    -> "⚠ 正向限位触发（已急停）"
                    2    -> "⚠ 负向限位触发（已急停）"
                    -1   -> "⚠ SDO 通信错误"
                    else -> "⚠ 报警码: ${cal.alarmCode}"
                }
                Text(
                    alarmText,
                    fontSize = 12.sp,
                    color    = Color(0xFFB00020),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFE0E0), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 状态颜色 / 状态文字
// ─────────────────────────────────────────────────────────────────────────────

internal fun motorStatusColor(cal: ServoCalibration): Color = when {
    cal.alarmCode != 0         -> Color(0xFFD32F2F)   // 报警 → 红
    !cal.isOnline              -> Color(0xFF9E9E9E)   // 离线 → 灰
    cal.isOnline && !cal.isEnabled -> Color(0xFFFF8F00) // 在线未使能 → 琥珀
    else                       -> Color(0xFF388E3C)   // 在线已使能 → 绿
}

internal fun motorStatusLabel(cal: ServoCalibration): String = when {
    cal.alarmCode != 0         -> "报警"
    !cal.isOnline              -> "离线"
    cal.isOnline && !cal.isEnabled -> "未使能"
    else                       -> "运行中"
}
