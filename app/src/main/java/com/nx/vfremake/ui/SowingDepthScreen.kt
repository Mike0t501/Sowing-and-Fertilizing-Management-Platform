package com.nx.vfremake.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import android.serialport.SerialPort
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.data.RECORD_INTERVAL_MAX_MS
import com.nx.vfremake.data.RECORD_INTERVAL_MIN_MS
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.data.SowingDepthState
import com.nx.vfremake.data.DepthNodeState
import com.nx.vfremake.data.DepthNodeQuality
import com.nx.vfremake.data.DepthDisplayMode
import com.nx.vfremake.data.DepthQualityConfig
import com.nx.vfremake.funClass.DepthDisplayFormat
import com.nx.vfremake.funClass.MeasuredDepthSample
import com.nx.vfremake.data.SERVO_ALARM_DRIVE_FAULT
import com.nx.vfremake.data.SERVO_ALARM_INTERNAL_LIMIT
import com.nx.vfremake.data.SERVO_ALARM_SDO_ABORT
import com.nx.vfremake.data.activeSowingDepthMotorIndices
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.mSPParamData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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

    // 手动实验数据记录器（采样周期见 state.recordIntervalMs，8 路电机；离开页面自动落盘保存）
    val depthRecorder = remember { DepthRecordFun() }
    var isRecording by remember { mutableStateOf(false) }
    val recordActive by viewModel.depthRecordingState.observeAsState(false)
    LaunchedEffect(recordActive) {
        if (!recordActive && !depthRecorder.isRunning) isRecording = false
    }

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
                if (portOpenedHere && !CanReceiveCoroutine.hasActiveClients()) {
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
    val nodeState by viewModel.depthNodeState.observeAsState(DepthNodeState())
    val activeMotorsState by viewModel.activeMotorsState.observeAsState(mSPParamData.activeMotors)
    val activeMotorIndices = activeSowingDepthMotorIndices(mSPParamData.rowNumber, activeMotorsState)

    var settingsDialog by remember { mutableStateOf<DepthSettingsPanel?>(null) }
    var motorDialogIndex by remember { mutableStateOf<Int?>(null) }
    var showCalibrateDialog by remember { mutableStateOf(false) }

    // 整机标定导入/导出：导出启用行中已标定的各路，导入按文件来源路号回填
    val calibIo = rememberServoCalibIo(
        viewModel     = viewModel,
        singleMotor   = null,
        targetIndices = activeMotorIndices
    )

    fun saveGlobalSettings(recordIntervalMs: Int? = null) {
        val current = viewModel.currentSowingDepthState()
        MySharedPreFun(context).saveSowingDepthGlobalSettings(
            jogSpeed = current.jogSpeed,
            positionSpeed = current.positionSpeed,
            acceleration = current.acceleration,
            globalTargetDepth = current.globalTargetDepth,
            recordIntervalMs = recordIntervalMs
        )
    }

    fun applyGlobalDepth(depth: Float) {
        viewModel.updateSowingDepthGlobalSettings(globalTargetDepth = depth)
        val updates = activeMotorIndices.associate { i ->
            i to { cal: ServoCalibration -> cal.copy(targetDepth = depth) }
        }
        viewModel.updateMultipleServos(updates)
        saveGlobalSettings()
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
                "保存目录未设置或已失效，请在系统设置中选择实验数据保存目录",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val accepted = depthRecorder.start(
            context = context,
            mVariableFertViewModel = viewModel,
            filePrefix = "depthRec",
            header = DepthRecordFun.buildDepthRecordHeader(),
            // 原子真源：后台读状态用 currentSowingDepthState()，不读 LiveData 的 .value
            intervalMs = viewModel.currentSowingDepthState().recordIntervalMs.toLong()
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
                    "%.2f".format(Locale.ROOT, m.targetDepth),
                    "%.2f".format(Locale.ROOT, m.currentDepth),
                    m.currentPosition.toString(),
                    if (m.isOnline) "1" else "0",
                    m.alarmCode.toString()
                )
            }
        }
        isRecording = accepted
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

    Scaffold(
        topBar = { MyTopBar("播种深度控制", onClickBack) },
        backgroundColor = Color(0xFFF0F3F5)
    ) { paddingValues ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(paddingValues)) {
            val wide = maxWidth >= 560.dp
            val columns = if (wide) 4 else 2
            val tilePadding = if (maxHeight >= 660.dp) 14.dp else 8.dp
            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val controlColor = if (state.masterEnabled) Color(0xFF236631) else Color(0xFF455A64)
                    val controlBackground = if (state.masterEnabled) Color(0xFFE5F4E8) else Color.White
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = controlBackground
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp).heightIn(min = 52.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("深度控制总开关", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (state.masterEnabled) "运行中" else "已停止",
                                    fontSize = 13.sp,
                                    color = controlColor
                                )
                            }
                            Switch(
                                checked = state.masterEnabled,
                                onCheckedChange = viewModel::updateMasterEnabled,
                                enabled = !isSystemRunning
                            )
                        }
                    }
                    Button(
                        onClick = ::emergencyStopAll,
                        modifier = Modifier.heightIn(min = 52.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828))
                    ) {
                        Text("全部停止", fontSize = 16.sp, color = Color.White)
                    }
                }

                if (isSystemRunning) {
                    DepthNotice(
                        "施肥系统运行中，请先停止作业再开启手动深度控制。",
                        Color(0xFFFFEBD8), Color(0xFF9B3C00)
                    )
                }
                if (MySharedPreFun(context).getSpecificValue(R.string.testMode_Switch_name) == "1") {
                    DepthNotice(
                        "测试模式 · 电机默认在线，CAN 帧正常发送",
                        Color(0xFFE3F2FD), DepthControlBlue
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DepthSettingValue(
                        "全局目标深度", "%.1f mm".format(Locale.ROOT, state.globalTargetDepth),
                        Modifier.weight(1f)
                    ) { settingsDialog = DepthSettingsPanel.GLOBAL_DEPTH }
                    DepthSettingValue(
                        "位置运动速度", "${state.positionSpeed} RPM",
                        Modifier.weight(1f)
                    ) { settingsDialog = DepthSettingsPanel.SPEED }
                    DepthSettingValue(
                        "加减速度", "${state.acceleration} RPM/s",
                        Modifier.weight(1f)
                    ) { settingsDialog = DepthSettingsPanel.ACCELERATION }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("逐行播深", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (activeMotorIndices.isNotEmpty()) {
                        val online = activeMotorIndices.count { state.motors[it].isOnline }
                        val alarms = activeMotorIndices.count { state.motors[it].alarmCode != 0 }
                        Text(
                            "伺服在线 $online/${activeMotorIndices.size} · 报警 $alarms",
                            fontSize = 14.sp,
                            color = if (alarms > 0) Color(0xFFB00020) else Color(0xFF455A64)
                        )
                    }
                }
                if (activeMotorIndices.isEmpty()) {
                    DepthNotice(
                        "未启用播种深度电机，请先在参数设置页开启需要作业的行。",
                        Color.White, Color(0xFF455A64)
                    )
                } else {
                    activeMotorIndices.chunked(columns).forEach { indices ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            indices.forEach { index ->
                                key(index) {
                                    MotorStatusTile(
                                        cal = state.motors[index],
                                        measurement = viewModel.measuredDepthFacade.forRow(
                                            index, nodeState.channels.getOrNull(index)
                                        ),
                                        displayMode = nodeState.displayMode,
                                        verticalPadding = tilePadding,
                                        modifier = Modifier.weight(1f),
                                        onClick = { motorDialogIndex = index }
                                    )
                                }
                            }
                            repeat(columns - indices.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                val actions = listOf(
                    "标定设置" to { showCalibrateDialog = true },
                    "性能测试" to onClickDepthTest,
                    "实验设置" to { settingsDialog = DepthSettingsPanel.EXPERIMENT },
                    (if (isRecording) "停止记录" else "开始记录") to {
                        if (isRecording) stopManualRecord() else startManualRecord()
                    }
                )
                actions.chunked(columns).forEach { group ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        group.forEach { (label, onClick) ->
                            val recordingAction = isRecording && label == "停止记录"
                            OutlinedButton(
                                onClick = onClick,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    backgroundColor = if (recordingAction) Color(0xFFFFEBEE) else Color.White,
                                    contentColor = if (recordingAction) Color(0xFFB00020) else DepthControlBlue
                                ),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                Text(label, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
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

    when (settingsDialog) {
        DepthSettingsPanel.GLOBAL_DEPTH -> {
            DepthControlDialog(
                title = "全局目标深度",
                onDismiss = { settingsDialog = null }
            ) {
                Text("应用到全部已启用行，覆盖各行单独设定。", fontSize = 15.sp)
                DepthTargetEditor(
                    initialDepth = state.globalTargetDepth,
                    applyLabel = "全部应用",
                    onApply = ::applyGlobalDepth
                )
            }
        }
        DepthSettingsPanel.SPEED -> {
            MotionSettingDialog(
                title = "位置运动速度",
                value = state.positionSpeed,
                range = 100..3000,
                step = 10,
                unit = "RPM",
                onDismiss = { settingsDialog = null },
                onApply = {
                    viewModel.updateSowingDepthGlobalSettings(positionSpeed = it)
                    saveGlobalSettings()
                }
            )
        }
        DepthSettingsPanel.ACCELERATION -> {
            MotionSettingDialog(
                title = "加减速度",
                value = state.acceleration,
                range = 1000..60000,
                step = 1000,
                unit = "RPM/s",
                onDismiss = { settingsDialog = null },
                onApply = {
                    viewModel.updateSowingDepthGlobalSettings(acceleration = it)
                    saveGlobalSettings()
                }
            )
        }
        DepthSettingsPanel.EXPERIMENT -> {
            DepthControlDialog(
                title = "实验设置",
                onDismiss = { settingsDialog = null }
            ) {
                DepthExperimentSettings(
                    state = state,
                    nodeState = nodeState,
                    isRecording = isRecording,
                    recordActive = recordActive,
                    onIntervalChange = { interval ->
                        viewModel.updateSowingDepthGlobalSettings(recordIntervalMs = interval)
                        saveGlobalSettings(interval)
                    },
                    onDisplayModeChange = { mode ->
                        viewModel.depthNodeStore.update { it.copy(displayMode = mode) }
                        MySharedPreFun(context).saveDepthDisplayMode(mode)
                    },
                    onCvChange = { percent ->
                        val config = DepthQualityConfig(percent / 100)
                        viewModel.depthNodeStore.update { it.copy(qualityConfig = config) }
                        MySharedPreFun(context).saveDepthQualityConfig(config)
                    }
                )
            }
        }
        null -> Unit
    }

    motorDialogIndex?.takeIf { it in activeMotorIndices }?.let { index ->
        val cal = state.motors[index]
        val measurement = viewModel.measuredDepthFacade.forRow(index, nodeState.channels.getOrNull(index))
        DepthControlDialog(
            title = "第 ${index + 1} 行 · 独立目标深度",
            onDismiss = { motorDialogIndex = null },
            maxWidth = 760.dp,
            extraAction = {
                OutlinedButton(
                    onClick = {
                        motorDialogIndex = null
                        onClickCalibrate(index)
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("标定本行", fontSize = 15.sp) }
            }
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val details: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Node-ID ${cal.nodeId} · 伺服${motorStatusLabel(cal)} · " +
                                if (cal.limitsSet) "已限位" else "未限位",
                            fontSize = 14.sp,
                            color = Color(0xFF455A64)
                        )
                        DepthValueLine("伺服换算深度", servoDepthText(cal), DepthControlBlue)
                        motorAlarmText(cal.alarmCode)?.let {
                            DepthNotice(it, Color(0xFFFFEBEE), Color(0xFFB00020))
                        }
                        MeasuredDepthReadout(measurement, cal.targetDepth, nodeState.displayMode)
                    }
                }
                val editor: @Composable () -> Unit = {
                    DepthTargetEditor(initialDepth = cal.targetDepth, applyLabel = "应用") { depth ->
                        viewModel.updateServoCalibration(index) { it.copy(targetDepth = depth) }
                    }
                }
                if (maxWidth >= 640.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f)) { details() }
                        Column(Modifier.weight(1f)) { editor() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        details()
                        Divider()
                        editor()
                    }
                }
            }
        }
    }

    if (showCalibrateDialog) {
        DepthControlDialog(
            title = "选择要标定的电机",
            onDismiss = { showCalibrateDialog = false }
        ) {
            if (activeMotorIndices.isEmpty()) {
                Text("暂无已启用行", fontSize = 15.sp)
            }
            activeMotorIndices.chunked(2).forEach { indices ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    indices.forEach { index ->
                        val cal = state.motors[index]
                        OutlinedButton(
                            onClick = {
                                showCalibrateDialog = false
                                onClickCalibrate(index)
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(motorStatusColor(cal)))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "第 ${index + 1} 行 · " + if (cal.limitsSet) "已限位" else "未限位",
                                fontSize = 14.sp
                            )
                        }
                    }
                    if (indices.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Divider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showCalibrateDialog = false; calibIo.export() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("导出全部标定") }
                OutlinedButton(
                    onClick = { showCalibrateDialog = false; calibIo.import() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("导入全部标定") }
            }
        }
    }
}

private enum class DepthSettingsPanel { GLOBAL_DEPTH, SPEED, ACCELERATION, EXPERIMENT }

private val DepthControlBlue = Color(0xFF1565C0)

@Composable
private fun DepthNotice(text: String, background: Color, foreground: Color) {
    Surface(
        color = background,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, color = foreground, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun DepthSettingValue(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier = modifier, shape = RoundedCornerShape(8.dp), elevation = 1.dp) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "设置$label", onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(label, fontSize = 13.sp, color = Color(0xFF455A64))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DepthControlBlue)
                Text("›", fontSize = 16.sp, color = DepthControlBlue)
            }
        }
    }
}

@Composable
private fun MotorStatusTile(
    cal: ServoCalibration,
    measurement: MeasuredDepthSample,
    displayMode: DepthDisplayMode,
    verticalPadding: Dp,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val formatter = remember { DepthDisplayFormat() }
    val measuredDepth = formatter.format(measurement.depthMm, displayMode)
    val measuredText = when (measurement.quality) {
        DepthNodeQuality.OK -> measuredDepth
        DepthNodeQuality.SETTLING -> "稳定中"
        else -> DepthDisplayFormat.qualityLabel(measurement.quality)
    }
    val statusColor = when {
        cal.alarmCode != 0 -> Color(0xFFB00020)
        !cal.isOnline -> Color(0xFF455A64)
        !cal.isEnabled -> Color(0xFF8A5100)
        else -> Color(0xFF236631)
    }
    Card(modifier = modifier, shape = RoundedCornerShape(8.dp), elevation = 1.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(
                role = Role.Button, onClickLabel = "设置本行播深", onClick = onClick
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(statusColor.copy(alpha = 0.09f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("第 ${cal.motorIndex + 1} 行", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${motorStatusLabel(cal)} ›", fontSize = 13.sp, color = statusColor)
            }
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = verticalPadding),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                DepthValueLine("节点实测", measuredText, measuredDepthColor(measurement, cal.targetDepth))
                DepthValueLine("目标", targetDepthText(cal.targetDepth), DepthControlBlue)
                DepthValueLine("伺服换算", servoDepthText(cal), Color(0xFF455A64))
            }
        }
    }
}

@Composable
private fun DepthValueLine(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, color = Color(0xFF455A64))
        Text(value, fontSize = 15.sp, color = valueColor, fontWeight = FontWeight.SemiBold)
    }
}

private fun targetDepthText(depth: Float): String =
    if (depth > 0f) "%.1f mm".format(Locale.ROOT, depth) else "未设置"

private fun servoDepthText(cal: ServoCalibration): String = when {
    !cal.fitValid -> "未校准"
    !cal.isOnline -> "—"
    else -> "%.1f mm".format(Locale.ROOT, cal.currentDepth)
}

private fun motorAlarmText(alarmCode: Int): String? = when (alarmCode) {
    0 -> null
    SERVO_ALARM_DRIVE_FAULT -> "驱动器故障（已 Quick Stop）"
    SERVO_ALARM_INTERNAL_LIMIT -> "驱动器内部限位激活（已 Quick Stop）"
    SERVO_ALARM_SDO_ABORT -> "SDO 通信错误"
    else -> "报警码 $alarmCode"
}

@Composable
private fun DepthControlDialog(
    title: String,
    onDismiss: () -> Unit,
    maxWidth: Dp = 520.dp,
    onApply: (() -> Unit)? = null,
    extraAction: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = maxWidth).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    extraAction()
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = DepthControlBlue)
                    ) {
                        Text(if (onApply == null) "关闭" else "取消", fontSize = 15.sp)
                    }
                    if (onApply != null) {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onApply(); onDismiss() },
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = DepthControlBlue)
                        ) {
                            Text("应用", fontSize = 15.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MotionSettingDialog(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    unit: String,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit
) {
    var draft by remember { mutableStateOf(value) }
    DepthControlDialog(title = title, onDismiss = onDismiss, onApply = { onApply(draft) }) {
        Text("$draft $unit", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = DepthControlBlue)
        Slider(
            value = draft.coerceIn(range).toFloat(),
            onValueChange = { draft = (it / step).roundToInt() * step },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            colors = SliderDefaults.colors(thumbColor = DepthControlBlue, activeTrackColor = DepthControlBlue)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${range.first} $unit", fontSize = 14.sp)
            Text("${range.last} $unit", fontSize = 14.sp)
        }
    }
}

@Composable
private fun DepthExperimentSettings(
    state: SowingDepthState,
    nodeState: DepthNodeState,
    isRecording: Boolean,
    recordActive: Boolean,
    onIntervalChange: (Int) -> Unit,
    onDisplayModeChange: (DepthDisplayMode) -> Unit,
    onCvChange: (Float) -> Unit
) {
    val context = LocalContext.current
    var intervalInput by remember(state.recordIntervalMs) { mutableStateOf(state.recordIntervalMs.toString()) }
    var cvInput by remember(nodeState.qualityConfig.cvThreshold) {
        mutableStateOf("%.1f".format(Locale.ROOT, nodeState.qualityConfig.cvThreshold * 100))
    }
    Text("实验数据记录", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = intervalInput,
            onValueChange = { intervalInput = it },
            label = { Text("采样周期 (ms)") },
            enabled = !isRecording,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            enabled = !isRecording,
            modifier = Modifier.heightIn(min = 48.dp),
            onClick = {
                val interval = intervalInput.trim().toIntOrNull()
                if (interval == null || interval !in RECORD_INTERVAL_MIN_MS..RECORD_INTERVAL_MAX_MS) {
                    intervalInput = state.recordIntervalMs.toString()
                    Toast.makeText(context, "采样周期须为 $RECORD_INTERVAL_MIN_MS~$RECORD_INTERVAL_MAX_MS ms 的整数",
                        Toast.LENGTH_SHORT).show()
                } else {
                    onIntervalChange(interval)
                }
            }
        ) { Text("应用周期") }
    }
    Text(
        "$RECORD_INTERVAL_MIN_MS~$RECORD_INTERVAL_MAX_MS ms，与一键性能测试共用，须与伺服/编码器 TPDO 上报周期匹配。",
        fontSize = 13.sp, color = Color(0xFF455A64)
    )
    Divider()
    Text("实测显示档位（仅影响显示）", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DepthDisplayMode.values().forEach { mode ->
            OutlinedButton(
                onClick = { onDisplayModeChange(mode) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                Text(
                    (if (nodeState.displayMode == mode) "✓ " else "") +
                        (if (mode == DepthDisplayMode.HALF_CM) "0.5 cm" else "1 mm"),
                    fontSize = 15.sp
                )
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = cvInput,
            onValueChange = { cvInput = it },
            enabled = !isRecording && !recordActive,
            label = { Text("不稳定 CV 阈值 (%)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            enabled = !isRecording && !recordActive,
            modifier = Modifier.heightIn(min = 48.dp),
            onClick = {
                val percent = cvInput.toFloatOrNull()
                if (percent != null && percent in 1f..200f) {
                    onCvChange(percent)
                } else {
                    Toast.makeText(context, "CV 阈值须为 1–200%", Toast.LENGTH_SHORT).show()
                }
            }
        ) { Text("应用阈值") }
    }
    Text("默认 20% 为工程初值，需按试验修订。记录使用实际阈值。", fontSize = 13.sp, color = Color(0xFF455A64))
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

// ─────────────────────────────────────────────────────────────────────────────
// 目标深度多方式输入（手打 / 滑块 / 一键预设）
// ─────────────────────────────────────────────────────────────────────────────
// 田间工控触摸屏调软键盘代价高，而目标深度日常只在 20~80mm 之间调整，故提供三种入口：
//   ① 手打 + 「应用」——覆盖标定量块 100mm、处方图控深等超出常用区间的场景（不硬限幅）
//   ② 滑块（5mm 精度）——拖动中只改本地显示，松手（onValueChangeFinished）才下发，
//      与本页 位置运动速度 / 加减速度 滑块写法一致：拖动过程绝不刷 CAN 总线
//   ③ 一键预设（10mm 步长）——点一下即下发，最省操作，按钮做成 48dp 大触点便于戴手套操作
// 三条路径都汇聚到调用方的同一个提交出口（applyGlobalDepth / applyMotorDepth）。
private const val DEPTH_QUICK_MIN = 20f
private const val DEPTH_QUICK_MAX = 80f
// 滑块 5mm 精度：(80-20)/5 = 12 段 → 中间断点数 steps = 11
private const val DEPTH_QUICK_STEPS = 11
// 一键预设：20~80mm，步长 10mm
private val DEPTH_PRESETS = listOf(20f, 30f, 40f, 50f, 60f, 70f, 80f)

@Composable
private fun DepthTargetEditor(initialDepth: Float, applyLabel: String, onApply: (Float) -> Unit) {
    val context = LocalContext.current
    var input by remember {
        mutableStateOf(
            if (initialDepth.isFinite() && initialDepth > 0f) "%.1f".format(Locale.ROOT, initialDepth) else ""
        )
    }
    var slider by remember {
        mutableStateOf(if (initialDepth.isFinite()) initialDepth.coerceIn(DEPTH_QUICK_MIN, DEPTH_QUICK_MAX)
            else DEPTH_QUICK_MIN)
    }
    fun applyDepth(depth: Float) {
        input = "%.1f".format(Locale.ROOT, depth)
        slider = depth.coerceIn(DEPTH_QUICK_MIN, DEPTH_QUICK_MAX)
        onApply(depth)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("滑块松手或点击预设时立即应用。", fontSize = 14.sp, color = Color(0xFF455A64))
        DepthQuickInput(
            textValue = input,
            onTextChange = { input = it },
            sliderValue = slider,
            onSliderChange = { slider = it },
            onCommit = ::applyDepth,
            onCommitTyped = {
                val depth = input.toFloatOrNull()
                if (depth == null || !depth.isFinite() || depth <= 0f) {
                    Toast.makeText(context, "请输入有效的目标深度", Toast.LENGTH_SHORT).show()
                } else {
                    // 常用区间仅用于快捷操作，允许标定量块等场景手动输入更大的深度。
                    if (depth !in DEPTH_QUICK_MIN..DEPTH_QUICK_MAX) {
                        Toast.makeText(
                            context,
                            "目标深度 %.1f mm 超出常用范围 %.0f~%.0f mm"
                                .format(Locale.ROOT, depth, DEPTH_QUICK_MIN, DEPTH_QUICK_MAX),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    applyDepth(depth)
                }
            },
            applyLabel = applyLabel
        )
    }
}

@Composable
private fun DepthQuickInput(
    textValue: String,
    onTextChange: (String) -> Unit,
    sliderValue: Float,
    onSliderChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onCommitTyped: () -> Unit,
    applyLabel: String
) {
    Column {
        // ① 手动键入 ────────────────────────────────────────────────────────
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value           = textValue,
                onValueChange   = onTextChange,
                label           = { Text("目标深度 (mm)", fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine      = true,
                modifier        = Modifier.weight(1f)
            )
            Button(
                onClick        = onCommitTyped,
                colors         = ButtonDefaults.buttonColors(backgroundColor = DepthControlBlue),
                shape          = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(applyLabel, color = Color.White, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ② 滑块（5mm 精度，松手才下发）──────────────────────────────────────
        Text("滑动设置: %.0f mm".format(sliderValue), fontSize = 14.sp)
        Slider(
            value                 = sliderValue,
            // Slider 的 steps 本身已吸附到 5mm，取整只为消除浮点毛刺
            onValueChange         = { onSliderChange((it / 5f).roundToInt() * 5f) },
            onValueChangeFinished = { onCommit(sliderValue) },
            valueRange            = DEPTH_QUICK_MIN..DEPTH_QUICK_MAX,
            steps                 = DEPTH_QUICK_STEPS,
            colors                = SliderDefaults.colors(
                thumbColor       = DepthControlBlue,
                activeTrackColor = DepthControlBlue
            )
        )

        Spacer(Modifier.height(4.dp))

        // ③ 一键预设（10mm 步长）────────────────────────────────────────────
        Text("一键设置 (mm)", fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        val current = textValue.toFloatOrNull()
        DEPTH_PRESETS.chunked(4).forEach { rowPresets ->
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowPresets.forEach { preset ->
                    val selected = current != null && abs(current - preset) < 0.05f
                    if (selected) {
                        Button(
                            onClick        = { onCommit(preset) },
                            modifier       = Modifier.weight(1f).height(48.dp),
                            colors         = ButtonDefaults.buttonColors(
                                backgroundColor = DepthControlBlue
                            ),
                            shape          = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                "%.0f".format(preset),
                                color      = Color.White,
                                fontSize   = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick        = { onCommit(preset) },
                            modifier       = Modifier.weight(1f).height(48.dp),
                            shape          = RoundedCornerShape(8.dp),
                            colors         = ButtonDefaults.outlinedButtonColors(contentColor = DepthControlBlue),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("%.0f".format(preset), fontSize = 16.sp)
                        }
                    }
                }
                // 末行不足 4 个时补空位，保证按钮宽度与上一行对齐
                repeat(4 - rowPresets.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
