package com.nx.vfremake.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.coroutine.CanReceiveCoroutine
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.funClass.SdoReplyWaiters
import com.nx.vfremake.funClass.ServoCanOpenFun
import com.nx.vfremake.isSystemRunning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SERVO_DEBUG_TIMEOUT_MS = 1000L
private const val SERVO_SAMPLE_INTERVAL_MS = 300L

/**
 * YZ-AIM 播种深度伺服现场调试工具。
 *
 * 读取目录严格来自 YZ_MOTOR_SN2.eds；高风险通信/电子齿轮/限流/保存对象只读展示，
 * 不允许从通用控制台写入。作业系统运行期间也只允许读取，防止调试命令与控深循环竞争。
 */
@Composable
fun ServoDiagnosticsScreen(
    viewModel: VariableFertViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val depthState by viewModel.sowingDepthState.observeAsState()

    var selectedMotorIndex by remember { mutableStateOf(0) }
    var motorMenuOpen by remember { mutableStateOf(false) }
    var objectMenuOpen by remember { mutableStateOf(false) }
    var selectedObject by remember {
        mutableStateOf(ServoCanOpenFun.EDS_OBJECTS.first { it.key == "actualPosition" })
    }
    var writeValueInput by remember { mutableStateOf("0") }
    var writeArmed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var sampling by remember { mutableStateOf(false) }
    var samplingJob by remember { mutableStateOf<Job?>(null) }

    val values = remember { mutableStateMapOf<String, Long>() }
    val valueErrors = remember { mutableStateMapOf<String, String>() }
    val logLines = remember { mutableStateListOf<String>() }

    val state = depthState ?: viewModel.currentSowingDepthState()
    val selectedMotor = state.motors.getOrNull(selectedMotorIndex) ?: state.motors.first()
    val nodeId = selectedMotor.nodeId
    val writesEnabled = !busy && !sampling && !isSystemRunning

    suspend fun appendLog(message: String) {
        withContext(Dispatchers.Main) {
            logLines.add(message)
            if (logLines.size > 40) logLines.removeAt(0)
        }
    }

    suspend fun requestObject(obj: ServoCanOpenFun.EdsObject): ServoCanOpenFun.SdoReply? {
        val raw = SdoReplyWaiters.sendAndAwait(
            ServoCanOpenFun.buildReadObjectFrame(nodeId, obj),
            0x580 + nodeId,
            SERVO_DEBUG_TIMEOUT_MS
        )
        if (raw == null) {
            withContext(Dispatchers.Main) { valueErrors[obj.key] = "超时" }
            return null
        }
        val reply = ServoCanOpenFun.parseSdoReply(raw)
        val error = when {
            reply == null -> "应答格式无效"
            reply.index != obj.index || reply.subIndex != obj.subIndex -> "应答地址不匹配"
            reply.abortCode != null -> "0x%08X %s".format(
                reply.abortCode,
                ServoCanOpenFun.abortDescription(reply.abortCode)
            )
            reply.value == null -> "应答不含数值"
            reply.dataLength != obj.type.byteLength ->
                "数据长度 ${reply.dataLength}B 与 EDS ${obj.type.byteLength}B 不符"
            else -> null
        }
        withContext(Dispatchers.Main) {
            if (error == null) {
                values[obj.key] = reply!!.value!!
                valueErrors.remove(obj.key)
            } else {
                valueErrors[obj.key] = error
            }
        }
        return if (error == null) reply else null
    }

    fun runQuickDiagnostic() {
        busy = true
        writeArmed = false
        values.clear()
        valueErrors.clear()
        scope.launch(Dispatchers.IO) {
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    appendLog("✗ CAN 串口打开失败，请检查设置")
                    return@launch
                }
                appendLog("开始诊断 M${selectedMotorIndex + 1} / Node $nodeId")
                ServoCanOpenFun.QUICK_DIAGNOSTIC_OBJECTS.forEach { obj ->
                    val reply = requestObject(obj)
                    if (reply == null) {
                        appendLog("✗ ${obj.address} ${obj.title}: 无有效应答（见快照错误）")
                    }
                }
                val snapshot = withContext(Dispatchers.Main) { values.toMap() }
                if (ServoCanOpenFun.identityMatches(snapshot)) {
                    appendLog("✓ 身份匹配 YZ EDS（Vendor=817, Product=1, Revision=256）")
                } else {
                    appendLog("⚠ 身份与 YZ EDS 默认值不完全匹配，请核对电机型号")
                }
                snapshot["statusword"]?.let {
                    val sw = it.toInt()
                    val flags = CanOpenFun.parseStatusWord(sw)
                    appendLog(
                        "状态 0x%04X：%s，fault=%s，internalLimit=%s，targetReached=%s".format(
                            sw and 0xFFFF,
                            CanOpenFun.driveStateDescription(sw),
                            flags.fault,
                            flags.internalLimitActive,
                            flags.targetReached
                        )
                    )
                }
                appendLog("✓ 快速诊断完成")
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    fun readSelectedObject() {
        busy = true
        writeArmed = false
        scope.launch(Dispatchers.IO) {
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    appendLog("✗ CAN 串口打开失败")
                    return@launch
                }
                val reply = requestObject(selectedObject)
                if (reply != null) {
                    appendLog(
                        "✓ 读 ${selectedObject.address} ${selectedObject.title} = " +
                            ServoCanOpenFun.formatValue(selectedObject, reply.value!!)
                    )
                } else {
                    appendLog("✗ 读 ${selectedObject.address}: 无有效应答（见快照错误）")
                }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    /** 6084h 是常见 DS402 减速度对象，但不在本机 EDS 中；只读探测，不做写入。 */
    fun probeProfileDeceleration() {
        busy = true
        scope.launch(Dispatchers.IO) {
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    appendLog("✗ CAN 串口打开失败")
                    return@launch
                }
                val raw = SdoReplyWaiters.sendAndAwait(
                    CanOpenFun.buildSdoReadFrame(nodeId, 0x6084, 0x00),
                    0x580 + nodeId,
                    SERVO_DEBUG_TIMEOUT_MS
                )
                val reply = raw?.let(ServoCanOpenFun::parseSdoReply)
                when {
                    raw == null -> appendLog("✗ 6084h 兼容性探测超时")
                    reply?.abortCode != null -> appendLog(
                        "⚠ 6084h 未实现/拒绝访问：0x%08X %s".format(
                            reply.abortCode,
                            ServoCanOpenFun.abortDescription(reply.abortCode)
                        )
                    )
                    reply?.value != null -> appendLog(
                        "✓ 设备实现 EDS 外扩展 6084h，当前值=${reply.value} / 0x%08X".format(reply.value)
                    )
                    else -> appendLog("✗ 6084h 应答格式无效")
                }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    fun writeSelectedObject() {
        if (selectedObject.access != ServoCanOpenFun.EdsAccess.RW ||
            selectedObject.writePolicy == ServoCanOpenFun.WritePolicy.BLOCKED
        ) return
        val value = ServoCanOpenFun.parseInput(writeValueInput)
        if (value == null || !ServoCanOpenFun.valueFits(selectedObject, value)) {
            scope.launch { appendLog("✗ 写入值无效或超出 ${selectedObject.type} 范围") }
            return
        }
        if (!writeArmed) {
            writeArmed = true
            scope.launch { appendLog("⚠ 再次点击确认写入 ${selectedObject.address} = $value") }
            return
        }
        busy = true
        writeArmed = false
        scope.launch(Dispatchers.IO) {
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    appendLog("✗ CAN 串口打开失败")
                    return@launch
                }
                val raw = SdoReplyWaiters.sendAndAwait(
                    ServoCanOpenFun.buildWriteObjectFrame(nodeId, selectedObject, value),
                    0x580 + nodeId,
                    SERVO_DEBUG_TIMEOUT_MS
                )
                val reply = raw?.let(ServoCanOpenFun::parseSdoReply)
                when {
                    raw == null -> appendLog("✗ 写入超时，无 SDO 应答")
                    reply == null -> appendLog("✗ 写入应答格式无效")
                    reply.abortCode != null -> appendLog(
                        "✗ 写入中止 0x%08X：%s".format(
                            reply.abortCode,
                            ServoCanOpenFun.abortDescription(reply.abortCode)
                        )
                    )
                    reply.command == 0x60 && reply.index == selectedObject.index &&
                        reply.subIndex == selectedObject.subIndex -> {
                        appendLog("✓ 写入成功 ${selectedObject.address} = $value")
                        requestObject(selectedObject)
                    }
                    else -> appendLog("✗ 写入应答与请求不匹配")
                }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    fun sendNmt(command: Int, label: String) {
        busy = true
        scope.launch(Dispatchers.IO) {
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    appendLog("✗ CAN 串口打开失败")
                    return@launch
                }
                CanOpenFun.sendFrameSequenced(CanOpenFun.buildNmtFrame(command, nodeId))
                appendLog("✓ 已发送 NMT $label → Node $nodeId（NMT 无 SDO ACK）")
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    suspend fun sendWriteAndCheck(frame: ByteArray, label: String): Boolean {
        val raw = SdoReplyWaiters.sendAndAwait(frame, 0x580 + nodeId, SERVO_DEBUG_TIMEOUT_MS)
        val reply = raw?.let(ServoCanOpenFun::parseSdoReply)
        return when {
            raw == null -> {
                appendLog("✗ $label 超时")
                false
            }
            reply?.abortCode != null -> {
                appendLog("✗ $label 中止 0x%08X：%s".format(reply.abortCode, ServoCanOpenFun.abortDescription(reply.abortCode)))
                false
            }
            reply?.command == 0x60 -> {
                appendLog("✓ $label")
                true
            }
            else -> {
                appendLog("✗ $label 应答无效")
                false
            }
        }
    }

    fun runControlSequence(frames: List<ByteArray>, label: String) {
        busy = true
        scope.launch(Dispatchers.IO) {
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    appendLog("✗ CAN 串口打开失败")
                    return@launch
                }
                appendLog("开始：$label")
                for ((index, frame) in frames.withIndex()) {
                    if (!sendWriteAndCheck(frame, "$label ${index + 1}/${frames.size}")) return@launch
                }
                appendLog("✓ $label 完成")
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    fun startSampling() {
        sampling = true
        samplingJob = scope.launch(Dispatchers.IO) {
            if (!MySerialPortFun.ensureCanPortOpen(context)) {
                appendLog("✗ CAN 串口打开失败")
                withContext(Dispatchers.Main) { sampling = false }
                return@launch
            }
            appendLog("开始连续采样 6064h（${SERVO_SAMPLE_INTERVAL_MS}ms/次）")
            val positionObject = ServoCanOpenFun.EDS_OBJECTS.first { it.key == "actualPosition" }
            while (isActive) {
                requestObject(positionObject)
                delay(SERVO_SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stopSampling() {
        samplingJob?.cancel()
        samplingJob = null
        sampling = false
        scope.launch { appendLog("连续采样已停止") }
    }

    // 非作业状态下为本页启动接收路由，使 SDO ACK 能投递到 SdoReplyWaiters。
    DisposableEffect(Unit) {
        val receiveCoroutine = if (!isSystemRunning) {
            MySerialPortFun.ensureCanPortOpen(context)
            CanReceiveCoroutine().also { it.start(viewModel, context) }
        } else null
        onDispose {
            samplingJob?.cancel()
            receiveCoroutine?.shutdown()
            SdoReplyWaiters.clearAll()
        }
    }

    Scaffold(topBar = { MyTopBar("播种深度伺服电机调试工具", onBack) }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = Color(0xFFFFF3E0),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("安全与 EDS 审查提示", fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                    Text("• 调试前让机具悬空、远离丝杆和限深机构；作业运行中仅允许读取。", fontSize = 12.sp)
                    Text("• TPDO1 默认映射：6064h 实际位置 + 6041h 状态字（现有 6 字节解析正确）。", fontSize = 12.sp)
                    Text("• EDS 未声明 261Fh/2620h；App 限位仅为软件限幅，不再向未知对象写入。", fontSize = 12.sp)
                    Text("• 6041h Bit14/15 为厂商位，不能解释为正/负限位；Bit11 才是内部限位。", fontSize = 12.sp)
                    Text("• 6084h 未列入本 EDS；运行代码保留兼容写入，现场可通过 SDO abort 日志判断设备是否实现。", fontSize = 12.sp)
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("目标电机", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                    Column {
                        OutlinedButton(onClick = { motorMenuOpen = true }, enabled = !busy && !sampling) {
                            Text("M${selectedMotorIndex + 1} / Node $nodeId")
                        }
                        DropdownMenu(expanded = motorMenuOpen, onDismissRequest = { motorMenuOpen = false }) {
                            state.motors.forEachIndexed { index, motor ->
                                DropdownMenuItem(onClick = {
                                    selectedMotorIndex = index
                                    motorMenuOpen = false
                                    values.clear()
                                    valueErrors.clear()
                                    writeArmed = false
                                }) {
                                    Text("M${index + 1} / Node ${motor.nodeId} / ${if (motor.isOnline) "在线" else "离线"}")
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("在线：${if (selectedMotor.isOnline) "是" else "否"}")
                        Text("使能：${if (selectedMotor.isEnabled) "是" else "否"}")
                        Text("位置：${selectedMotor.currentPosition}", fontFamily = FontFamily.Monospace)
                        Text("报警：${selectedMotor.alarmCode}")
                    }
                    if (isSystemRunning) {
                        Text("施肥/控深系统正在运行：控制和写入按钮已锁定。", color = Color(0xFFC62828), fontSize = 12.sp)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("一键 EDS 快速诊断", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { runQuickDiagnostic() },
                            enabled = !busy && !sampling,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0))
                        ) { Text("读取身份/状态/运动/厂商快照", color = Color.White) }
                        if (busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 3.dp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { if (sampling) stopSampling() else startSampling() },
                            enabled = sampling || !busy
                        ) { Text(if (sampling) "停止位置采样" else "连续采样 6064h") }
                        OutlinedButton(
                            onClick = { probeProfileDeceleration() },
                            enabled = !busy && !sampling
                        ) { Text("只读探测 6084h") }
                    }
                    ServoCanOpenFun.QUICK_DIAGNOSTIC_OBJECTS.forEach { obj ->
                        val value = values[obj.key]
                        val error = valueErrors[obj.key]
                        if (value != null || error != null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${obj.address} ${obj.title}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(
                                    when {
                                        value == null -> error ?: "失败"
                                        obj.key == "mode" -> "${ServoCanOpenFun.formatValue(obj, value)} ${ServoCanOpenFun.modeDescription(value)}"
                                        obj.key == "statusword" -> "${ServoCanOpenFun.formatValue(obj, value)} ${CanOpenFun.driveStateDescription(value.toInt())}"
                                        else -> ServoCanOpenFun.formatValue(obj, value)
                                    },
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (value == null) Color(0xFFC62828) else Color(0xFF263238)
                                )
                            }
                            Divider(color = Color(0xFFE0E0E0))
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DS402 状态机调试", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                    Text("控制命令逐帧等待 SDO ACK；任一步超时或中止都会停止序列。", fontSize = 12.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { sendNmt(CanOpenFun.Nmt.OPERATIONAL, "启动") },
                            enabled = writesEnabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("NMT 启动", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { sendNmt(CanOpenFun.Nmt.PRE_OPERATIONAL, "预运行") },
                            enabled = writesEnabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("预运行", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { sendNmt(CanOpenFun.Nmt.RESET_COMM, "复位通信") },
                            enabled = writesEnabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("复位通信", fontSize = 12.sp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                runControlSequence(
                                    listOf(CanOpenFun.buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x0080L)),
                                    "故障复位"
                                )
                            },
                            enabled = writesEnabled,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFEF6C00))
                        ) { Text("故障复位", color = Color.White, fontSize = 12.sp) }
                        Button(
                            onClick = { runControlSequence(CanOpenFun.buildMotorInitSequence(nodeId), "位置模式使能") },
                            enabled = writesEnabled,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32))
                        ) { Text("位置模式使能", color = Color.White, fontSize = 12.sp) }
                        Button(
                            onClick = { runControlSequence(listOf(CanOpenFun.buildQuickStopFrame(nodeId)), "DS402 Quick Stop") },
                            enabled = writesEnabled,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFC62828))
                        ) { Text("Quick Stop", color = Color.White, fontSize = 12.sp) }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("EDS 对象控制台", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                    Text("目录包含 EDS 中全部数字 VAR/子对象；高风险对象可读但锁定写入。", fontSize = 12.sp, color = Color.Gray)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { objectMenuOpen = true },
                                enabled = !busy && !sampling,
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, Color(0xFF1565C0))
                            ) {
                                Text(
                                    "${selectedObject.address} ${selectedObject.title} " +
                                        "[${selectedObject.type}/${selectedObject.access}]",
                                    fontSize = 12.sp
                                )
                            }
                            DropdownMenu(expanded = objectMenuOpen, onDismissRequest = { objectMenuOpen = false }) {
                                ServoCanOpenFun.EDS_OBJECTS.forEach { obj ->
                                    DropdownMenuItem(onClick = {
                                        selectedObject = obj
                                        objectMenuOpen = false
                                        writeArmed = false
                                        values[obj.key]?.let { writeValueInput = it.toString() }
                                    }) {
                                        Text("${obj.group} · ${obj.address} ${obj.title} [${obj.type}/${obj.access}]", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = { readSelectedObject() },
                            enabled = !busy && !sampling,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0))
                        ) { Text("读取", color = Color.White) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = writeValueInput,
                            onValueChange = { writeValueInput = it; writeArmed = false },
                            label = { Text("写入值（十进制或 0x 十六进制）") },
                            singleLine = true,
                            enabled = writesEnabled && selectedObject.access == ServoCanOpenFun.EdsAccess.RW &&
                                selectedObject.writePolicy != ServoCanOpenFun.WritePolicy.BLOCKED,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { writeSelectedObject() },
                            enabled = writesEnabled && selectedObject.access == ServoCanOpenFun.EdsAccess.RW &&
                                selectedObject.writePolicy != ServoCanOpenFun.WritePolicy.BLOCKED,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (writeArmed) Color(0xFFC62828) else Color(0xFFEF6C00)
                            )
                        ) { Text(if (writeArmed) "确认写入" else "准备写入", color = Color.White) }
                    }
                    if (selectedObject.writePolicy == ServoCanOpenFun.WritePolicy.BLOCKED) {
                        Text(
                            "该对象会改变通信、持久参数或机械安全配置，调试控制台禁止写入。",
                            color = Color(0xFFC62828),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (logLines.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), backgroundColor = Color(0xFF263238)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("调试日志", color = Color(0xFF90CAF9), fontWeight = FontWeight.Bold)
                        logLines.forEach { line ->
                            Text(
                                line,
                                color = when {
                                    line.startsWith("✗") -> Color(0xFFFF8A80)
                                    line.startsWith("⚠") -> Color(0xFFFFD180)
                                    line.startsWith("✓") -> Color(0xFFB9F6CA)
                                    else -> Color.White
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
