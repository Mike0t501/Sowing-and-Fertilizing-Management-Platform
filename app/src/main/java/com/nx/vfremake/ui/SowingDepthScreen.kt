package com.nx.vfremake.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.Scaffold
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.contentColorFor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.graphics.Rect
import android.os.Build
import android.serialport.SerialPort
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
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
import com.nx.vfremake.data.DepthDisplayMode
import com.nx.vfremake.data.DepthQualityConfig
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
 *   - 逐行实测偏差与固定目标调节
 *   - 标定设置导航（选择电机后跳转 DepthCalibrationScreen）
 *   - 全部急停
 *
 * @param viewModel       共享 ViewModel
 * @param onClickBack     返回上级
 * @param onClickCalibrate 跳转到指定电机的校准界面
 * @param onClickDepthTest 跳转到变量播深试验界面
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
    val trialActive by viewModel.depthTrialActive.observeAsState(false)
    val workRunning by viewModel.workRunning.observeAsState(false)
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
        if (!isSystemRunning && viewModel.workRunning.value != true && !viewModel.isDepthTrialActive()) {
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
    val canControl = !workRunning && !isSystemRunning && !trialActive
    val canMaintain = canControl && !state.masterEnabled && !recordActive && !isRecording

    var settingsDialog by rememberSaveable { mutableStateOf<DepthSettingsPanel?>(null) }
    var motorDialogIndex by rememberSaveable { mutableStateOf<Int?>(null) }

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
        viewModel.setConfiguredGlobalDepth(depth, activeMotorIndices)
        saveGlobalSettings()
    }

    // ── 辅助：手动实验数据记录启停 ────────────────────────────────────────────
    fun startManualRecord() {
        if (isSystemRunning || viewModel.workRunning.value == true || viewModel.isDepthTrialActive()) {
            Toast.makeText(context, "作业或试验进行中，无法启动手动记录", Toast.LENGTH_SHORT).show()
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

    val colors = DepthDashboardColors
    val rows = (0 until mSPParamData.rowNumber.coerceIn(0, 8)).map { index ->
        val measurement = viewModel.measuredDepthFacade.forRow(index, nodeState.channels.getOrNull(index))
        if (index in activeMotorIndices) {
            val rowTarget = state.configuredRowTargetsMm[index]
            fixedDepthChartRow(index, measurement, rowTarget ?: state.globalTargetDepth, state.motors[index])
                .copy(targetSource = if (rowTarget == null) "整机" else "单行")
        } else {
            DepthChartRow(index, measurement, null, "停用", DepthTone.NEUTRAL, enabled = false)
        }
    }
    val summary: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DepthDashboardAction(
                title = "整机固定目标",
                detail = depthTargetReadout(state.globalTargetDepth),
                enabled = canControl,
                prominent = true
            ) { settingsDialog = DepthSettingsPanel.GLOBAL_DEPTH }
            val online = activeMotorIndices.count { state.motors[it].isOnline }
            val alarms = activeMotorIndices.count { state.motors[it].alarmCode != 0 }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.Panel,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, colors.Border)
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DepthDashboardReadout("伺服在线", "$online/${activeMotorIndices.size}", Modifier.weight(1f))
                    DepthDashboardReadout(
                        "伺服报警", "$alarms", Modifier.weight(1f),
                        color = if (alarms > 0) colors.Alert else colors.Text
                    )
                }
            }
            DepthDashboardAction(
                title = "变量播深试验",
                detail = "单行步进往返",
                enabled = canMaintain,
                onClick = onClickDepthTest
            )
            DepthDashboardAction(
                title = "设置与维护",
                detail = "运动参数、采样和标定",
                enabled = canControl
            ) { settingsDialog = DepthSettingsPanel.MAINTENANCE }
            if (canControl && !canMaintain) {
                Text(
                    "关闭深度控制总开关并停止手动记录后，可进入试验或标定。",
                    fontSize = 14.sp, color = colors.Muted
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("播种深度控制", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClickBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                backgroundColor = colors.Panel,
                contentColor = colors.Text,
                elevation = 0.dp
            )
        },
        backgroundColor = colors.Background,
        contentColor = colors.Text
    ) { paddingValues ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(paddingValues)) {
            val wide = maxWidth >= 900.dp
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DepthControlActions(
                    masterEnabled = state.masterEnabled,
                    canControl = canControl,
                    isRecording = isRecording,
                    onMasterChange = viewModel::updateMasterEnabled,
                    onStopAll = ::emergencyStopAll,
                    onStopRecording = ::stopManualRecord
                )
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!canControl) {
                        DepthNotice(
                            if (trialActive) "变量播深试验正在运行。" else "作业进行中，请先停止作业再调整播深。",
                            colors.Raised, colors.Warning
                        )
                    }
                    if (MySharedPreFun(context).getSpecificValue(R.string.testMode_Switch_name) == "1") {
                        DepthNotice("测试模式 · 电机默认在线，CAN 帧正常发送", colors.Raised, colors.Accent)
                    }
                    val chart: @Composable (Modifier) -> Unit = { modifier ->
                        Surface(
                            modifier = modifier,
                            color = colors.Panel,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, colors.Border)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("逐行播深", color = colors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "节点实测与各行固定目标对比",
                                    color = colors.Muted, fontSize = 14.sp
                                )
                                if (activeMotorIndices.isEmpty()) {
                                    DepthNotice(
                                        "未启用播种深度电机，请先在参数设置页开启需要作业的行。",
                                        colors.Raised, colors.Muted
                                    )
                                }
                                if (rows.isNotEmpty()) {
                                    DepthDashboardChart(rows, nodeState.displayMode, vertical = wide) {
                                        motorDialogIndex = it
                                    }
                                }
                            }
                        }
                    }
                    if (wide) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            chart(Modifier.weight(1f))
                            summary(Modifier.width(244.dp))
                        }
                    } else {
                        summary(Modifier.fillMaxWidth())
                        chart(Modifier.fillMaxWidth())
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
            DepthTargetDialog(
                title = "整机固定目标",
                initialDepth = state.globalTargetDepth,
                editable = canControl,
                onDismiss = { settingsDialog = null },
                onApply = ::applyGlobalDepth
            ) {
                Text("应用到全部已启用行，覆盖各行单独设定。", color = colors.Warning, fontSize = 15.sp)
            }
        }
        DepthSettingsPanel.MAINTENANCE -> {
            DepthControlDialog(title = "设置与维护", onDismiss = { settingsDialog = null }) {
                Text("运动参数", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DepthSettingValue(
                        "位置运动速度", "${state.positionSpeed} RPM", Modifier.weight(1f), enabled = canControl
                    ) { settingsDialog = DepthSettingsPanel.SPEED }
                    DepthSettingValue(
                        "加减速度", "${state.acceleration} RPM/s", Modifier.weight(1f), enabled = canControl
                    ) { settingsDialog = DepthSettingsPanel.ACCELERATION }
                }
                Divider()
                Text("实验记录与质量显示", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                DepthSettingValue(
                    "采样 ${state.recordIntervalMs} ms · 显示档位与阈值", "记录设置", Modifier.fillMaxWidth(), enabled = canControl
                ) { settingsDialog = DepthSettingsPanel.EXPERIMENT }
                OutlinedButton(
                    enabled = canControl && !recordActive && activeMotorIndices.isNotEmpty(),
                    onClick = { settingsDialog = null; startManualRecord() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("开始手动记录", fontSize = 15.sp) }
                Divider()
                DepthSettingValue(
                    "选择单行、导入或导出", "标定设置", Modifier.fillMaxWidth(),
                    enabled = canMaintain && activeMotorIndices.isNotEmpty()
                ) { settingsDialog = DepthSettingsPanel.CALIBRATION }
            }
        }
        DepthSettingsPanel.SPEED -> {
            MotionSettingDialog(
                title = "位置运动速度",
                value = state.positionSpeed,
                range = 100..3000,
                step = 10,
                unit = "RPM",
                onDismiss = { settingsDialog = DepthSettingsPanel.MAINTENANCE },
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
                onDismiss = { settingsDialog = DepthSettingsPanel.MAINTENANCE },
                onApply = {
                    viewModel.updateSowingDepthGlobalSettings(acceleration = it)
                    saveGlobalSettings()
                }
            )
        }
        DepthSettingsPanel.EXPERIMENT -> {
            DepthControlDialog(
                title = "记录与质量显示",
                onDismiss = { settingsDialog = DepthSettingsPanel.MAINTENANCE }
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
        DepthSettingsPanel.CALIBRATION, null -> Unit
    }

    motorDialogIndex?.takeIf { it in activeMotorIndices }?.let { index ->
        val cal = state.motors[index]
        val fixedTarget = state.configuredRowTargetsMm[index] ?: state.globalTargetDepth
        val row = rows[index]
        val measurement = row.sample
        DepthTargetDialog(
            title = "第 ${index + 1} 行 · 独立目标深度",
            initialDepth = fixedTarget,
            currentSource = if (index in state.configuredRowTargetsMm) "单行固定目标" else "沿用整机固定目标",
            editable = canControl,
            onDismiss = { motorDialogIndex = null },
            onApply = { depth -> viewModel.setConfiguredDepthTarget(index, depth) },
            extraAction = {
                OutlinedButton(
                    enabled = canMaintain,
                    onClick = {
                        motorDialogIndex = null
                        onClickCalibrate(index)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, colors.Border),
                    colors = ButtonDefaults.outlinedButtonColors(
                        backgroundColor = colors.Panel, contentColor = colors.Accent,
                        disabledContentColor = colors.Muted
                    )
                ) { Text("标定本行", fontSize = 15.sp) }
            },
            rowReadout = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DepthDashboardReadout(
                            "节点实测", rememberDepthDashboardMeasurement(row, nodeState.displayMode),
                            Modifier.weight(1f)
                        )
                        DepthDashboardReadout(
                            "相对当前目标", row.differenceText, Modifier.weight(1f), color = row.tone.color
                        )
                    }
                    DepthDashboardBadge(row.statusLabel, row.tone)
                }
            },
            rowDiagnostics = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("本行诊断", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Node-ID ${cal.nodeId} · 伺服${motorStatusLabel(cal)} · " +
                            if (cal.limitsSet) "已限位" else "未限位",
                        fontSize = 14.sp,
                        color = colors.Muted
                    )
                    DepthValueLine("伺服解算深度", servoDepthText(cal))
                    DepthVoltageReadout(measurement, showMean = true, fontSize = 14.sp,
                        color = colors.Muted, alertColor = colors.Alert)
                    motorAlarmText(cal.alarmCode)?.let {
                        DepthNotice(it, colors.Raised, colors.Alert)
                    }
                    val span = measurement.spanMm?.let { "%.1f mm".format(Locale.ROOT, it) } ?: "—"
                    DepthValueLine("周期波动", span)
                    val monitoring = measurement.monitoring
                    val passRate = monitoring.passRate?.takeIf { monitoring.windowReady }
                        ?.let { "%.1f%%".format(Locale.ROOT, it * 100f) } ?: "统计不足"
                    val cv = monitoring.coefficientOfVariation?.takeIf { monitoring.windowReady }
                        ?.let { if (it.isFinite()) "%.1f%%".format(Locale.ROOT, it * 100f) else "∞" } ?: "—"
                    DepthValueLine("监测合格率", passRate)
                    DepthValueLine("监测 CV", cv)
                }
            }
        )
    }

    if (settingsDialog == DepthSettingsPanel.CALIBRATION && canMaintain) {
        DepthControlDialog(
            title = "选择要标定的电机",
            onDismiss = { settingsDialog = DepthSettingsPanel.MAINTENANCE }
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
                                settingsDialog = null
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
                    onClick = { settingsDialog = null; calibIo.export() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("导出全部标定") }
                OutlinedButton(
                    onClick = { settingsDialog = null; calibIo.import() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("导入全部标定") }
            }
        }
    }
}

private enum class DepthSettingsPanel { GLOBAL_DEPTH, MAINTENANCE, SPEED, ACCELERATION, EXPERIMENT, CALIBRATION }

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
private fun DepthSettingValue(
    label: String,
    value: String,
    modifier: Modifier,
    enabled: Boolean = true,
    actionLabel: String = "设置$label",
    onClick: () -> Unit
) {
    Card(modifier = modifier, shape = RoundedCornerShape(8.dp), elevation = 1.dp) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(label, fontSize = 13.sp, color = Color(0xFF455A64))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val color = if (enabled) DepthControlBlue else Color(0xFF5D6870)
                Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
                if (enabled) Text("›", fontSize = 20.sp, color = color)
            }
        }
    }
}

@Composable
private fun DepthControlActions(
    masterEnabled: Boolean,
    canControl: Boolean,
    isRecording: Boolean,
    onMasterChange: (Boolean) -> Unit,
    onStopAll: () -> Unit,
    onStopRecording: () -> Unit
) {
    val colors = DepthDashboardColors
    Surface(
        color = colors.Panel,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, colors.Border)
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("深度控制总开关", color = colors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (masterEnabled) "运行中" else "已停止",
                        color = if (masterEnabled) colors.Good else colors.Muted,
                        fontSize = 14.sp
                    )
                }
                Switch(
                    checked = masterEnabled,
                    onCheckedChange = onMasterChange,
                    enabled = canControl,
                    modifier = Modifier.size(48.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.Accent,
                        uncheckedThumbColor = colors.Muted,
                        uncheckedTrackColor = colors.Muted
                    )
                )
                Button(
                    onClick = onStopAll,
                    enabled = canControl,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFFAA2929),
                        contentColor = Color.White,
                        disabledBackgroundColor = colors.Raised,
                        disabledContentColor = colors.Muted
                    )
                ) {
                    Text("全部停止", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (isRecording) {
                Divider(color = colors.Border)
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("正在记录实验数据", color = colors.Text, modifier = Modifier.weight(1f), fontSize = 15.sp)
                    OutlinedButton(
                        onClick = onStopRecording,
                        modifier = Modifier.heightIn(min = 48.dp),
                        border = BorderStroke(1.dp, colors.Alert),
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = colors.Panel,
                            contentColor = colors.Alert
                        )
                    ) { Text("停止记录", fontSize = 15.sp) }
                }
            }
        }
    }
}

@Composable
private fun DepthDashboardAction(
    title: String,
    detail: String,
    enabled: Boolean,
    prominent: Boolean = false,
    onClick: () -> Unit
) {
    val colors = DepthDashboardColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.Panel,
        border = BorderStroke(1.dp, if (prominent && enabled) colors.Accent else colors.Border)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = if (prominent) 72.dp else 56.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = if (enabled && !prominent) colors.Text else colors.Muted,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = if (enabled && prominent) colors.Accent else colors.Muted,
                    fontSize = if (prominent) 30.sp else 14.sp,
                    fontWeight = if (prominent) FontWeight.Bold else FontWeight.Normal)
            }
            if (enabled) Text("›", color = colors.Accent, fontSize = 24.sp)
        }
    }
}

@Composable
private fun DepthValueLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp, color = DepthDashboardColors.Muted)
        Text(value, fontSize = 15.sp, color = DepthDashboardColors.Text, fontWeight = FontWeight.SemiBold)
    }
}

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
    dashboard: Boolean = false,
    onApply: (() -> Unit)? = null,
    applyEnabled: Boolean = true,
    extraAction: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    val colors = DepthDashboardColors
    val actionColor = if (dashboard) colors.Accent else DepthControlBlue
    val surfaceColor = if (dashboard) colors.Panel else MaterialTheme.colors.surface
    val dialogBody: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = maxWidth).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = surfaceColor,
            contentColor = if (dashboard) colors.Text else contentColorFor(surfaceColor),
            elevation = if (dashboard) 0.dp else 8.dp,
            border = if (dashboard) BorderStroke(1.dp, colors.Border) else null
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
                        colors = ButtonDefaults.textButtonColors(contentColor = actionColor)
                    ) {
                        Text(if (onApply == null) "关闭" else "取消", fontSize = 15.sp)
                    }
                    if (onApply != null) {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onApply(); onDismiss() },
                            enabled = applyEnabled,
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = if (dashboard) ButtonDefaults.buttonColors(
                                backgroundColor = colors.Accent,
                                contentColor = colors.OnAccent,
                                disabledBackgroundColor = colors.Raised,
                                disabledContentColor = colors.Muted
                            ) else ButtonDefaults.buttonColors(backgroundColor = actionColor, contentColor = Color.White)
                        ) {
                            Text("应用", fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !dashboard)
    ) {
        if (dashboard) {
            val dismiss by rememberUpdatedState(onDismiss)
            val insets = WindowInsets.safeDrawing.union(WindowInsets(bottom = legacyDepthDialogBottomInset()))
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.matchParentSize().pointerInput(Unit) {
                    detectTapGestures { dismiss() }
                })
                Box(Modifier.fillMaxSize().windowInsetsPadding(insets), contentAlignment = Alignment.Center) {
                    dialogBody()
                }
            }
        } else {
            dialogBody()
        }
    }
}

@Composable
private fun legacyDepthDialogBottomInset(): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return 0
    val view = LocalView.current
    val keyboardClearancePx = with(LocalDensity.current) { 32.dp.roundToPx() }
    var bottomInset by remember(view) { mutableIntStateOf(0) }
    DisposableEffect(view, keyboardClearancePx) {
        val visible = Rect()
        val position = IntArray(2)
        // The legacy dialog can keep its full measured height when the IME shrinks its visible frame.
        val listener = ViewTreeObserver.OnPreDrawListener {
            view.getWindowVisibleDisplayFrame(visible)
            view.getLocationOnScreen(position)
            val overlap = maxOf(0, view.height - visible.height(), position[1] + view.height - visible.bottom)
            // Leave clearance for legacy window decor offsets while the keyboard is docked.
            bottomInset = if (overlap > view.height / 5) overlap + keyboardClearancePx else overlap
            true
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose { view.viewTreeObserver.removeOnPreDrawListener(listener) }
    }
    return bottomInset
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
            enabled = !isRecording && !recordActive,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            enabled = !isRecording && !recordActive,
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
        "$RECORD_INTERVAL_MIN_MS~$RECORD_INTERVAL_MAX_MS ms，与变量播深试验共用，须与伺服 TPDO 上报周期匹配。",
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

private val DEPTH_PRESETS = listOf(20f, 30f, 40f, 50f, 60f, 70f, 80f)

@Composable
private fun DepthTargetDialog(
    title: String,
    initialDepth: Float,
    currentSource: String? = null,
    editable: Boolean,
    onDismiss: () -> Unit,
    onApply: (Float) -> Unit,
    extraAction: @Composable () -> Unit = {},
    rowReadout: (@Composable () -> Unit)? = null,
    rowDiagnostics: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit = {}
) {
    var input by rememberSaveable {
        mutableStateOf(if (initialDepth.isFinite() && initialDepth > 0f) "%.1f".format(Locale.ROOT, initialDepth) else "")
    }
    val draft = input.trim().toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
    val colors = DepthDashboardColors
    DepthControlDialog(
        title = title,
        onDismiss = onDismiss,
        maxWidth = if (editable) 760.dp else 520.dp,
        dashboard = true,
        onApply = if (editable) ({ draft?.let(onApply) }) else null,
        applyEnabled = draft != null,
        extraAction = extraAction
    ) {
        val current: @Composable () -> Unit = {
            Surface(
                color = colors.Raised,
                contentColor = colors.Text,
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DepthDashboardReadout("当前目标", depthTargetReadout(initialDepth))
                    currentSource?.let { Text(it, color = colors.Muted, fontSize = 14.sp) }
                    rowReadout?.invoke()
                    content()
                }
            }
        }
        val editor: @Composable () -> Unit = {
            DepthTargetEditor(
                input = input,
                draft = draft,
                onInput = { input = it },
                onSelect = { input = "%.1f".format(Locale.ROOT, it) }
            )
        }
        val diagnostics: @Composable () -> Unit = {
            if (rowDiagnostics != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.Panel,
                    contentColor = colors.Text,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, colors.Border)
                ) {
                    Box(Modifier.padding(12.dp)) { rowDiagnostics() }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (editable && maxWidth >= 640.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        current()
                        diagnostics()
                    }
                    Box(Modifier.weight(1f)) { editor() }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    current()
                    if (editable) editor()
                    diagnostics()
                }
            }
        }
    }
}

@Composable
private fun DepthTargetEditor(
    input: String,
    draft: Float?,
    onInput: (String) -> Unit,
    onSelect: (Float) -> Unit
) {
    val colors = DepthDashboardColors
    val outline = BorderStroke(1.dp, colors.Border)
    val buttonColors = ButtonDefaults.outlinedButtonColors(
        backgroundColor = colors.Raised,
        contentColor = colors.Text,
        disabledContentColor = colors.Muted
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("待应用目标", color = colors.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                enabled = draft != null && draft > 1f,
                onClick = { draft?.let { onSelect(it - 1f) } },
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 6.dp),
                border = outline,
                colors = buttonColors
            ) { Text("−1 mm", fontSize = 15.sp) }
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                label = { Text("深度 (mm)") },
                isError = input.isNotBlank() && draft == null,
                textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = colors.Text,
                    backgroundColor = colors.Background,
                    cursorColor = colors.Accent,
                    focusedBorderColor = colors.Accent,
                    unfocusedBorderColor = colors.Border,
                    focusedLabelColor = colors.Accent,
                    unfocusedLabelColor = colors.Muted,
                    errorBorderColor = colors.Alert,
                    errorLabelColor = colors.Alert,
                    errorCursorColor = colors.Alert
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                enabled = draft != null,
                onClick = { draft?.let { onSelect(it + 1f) } },
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 6.dp),
                border = outline,
                colors = buttonColors
            ) { Text("+1 mm", fontSize = 15.sp) }
        }
        if (input.isNotBlank() && draft == null) {
            Text("请输入大于 0 的深度", fontSize = 14.sp, color = colors.Alert)
        }
        if (draft != null && draft !in DEPTH_PRESETS.first()..DEPTH_PRESETS.last()) {
            Text("当前输入超出常用范围 20–80 mm", fontSize = 14.sp, color = colors.Warning)
        }
        Text("常用深度 (mm)", color = colors.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        DEPTH_PRESETS.chunked(4).forEach { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowPresets.forEach { preset ->
                    val selected = draft != null && abs(draft - preset) < 0.05f
                    OutlinedButton(
                        onClick = { onSelect(preset) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(6.dp),
                        border = if (selected) BorderStroke(1.dp, colors.Accent) else outline,
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = if (selected) colors.Accent else colors.Raised,
                            contentColor = if (selected) colors.OnAccent else colors.Text
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text(preset.toInt().toString(), fontSize = 16.sp) }
                }
                repeat(4 - rowPresets.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
