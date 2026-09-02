/**
 ***********************************************************************************************************
 * @brief   :BRT CANopen 编码器软件内调试面板——节点发现、EDS 快照、NMT/SYNC、实时采样与对象读写
 ***********************************************************************************************************
 */
package com.nx.vfremake.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
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
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.EncoderCanOpenFun
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.funClass.SdoReplyWaiters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEBUG_SDO_TIMEOUT_MS = 900L
private const val SCAN_SDO_TIMEOUT_MS = 250L
private const val SAMPLE_INTERVAL_MS = 250L

private data class DebugSdoResult(
    val reply: EncoderCanOpenFun.SdoReply? = null,
    val error: String? = null
)

/**
 * BRT 编码器现场调试面板。所有读写均走全局 CAN 发送步调和按 CAN-ID 匹配的 SDO waiter，
 * 可与作业中的伺服报文共存；写操作只允许 EDS 标记为 RW 的对象，并对节点 ID/波特率加锁。
 */
@Composable
fun EncoderDiagnosticsPanel(
    viewModel: VariableFertViewModel,
    externalBusy: Boolean,
    onExternalBusyChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedbackState by viewModel.encoderFeedbackState.observeAsState()

    var nodeInput by remember { mutableStateOf("21") }
    var busy by remember { mutableStateOf(false) }
    var sampling by remember { mutableStateOf(false) }
    var samplingJob by remember { mutableStateOf<Job?>(null) }
    var objectMenuOpen by remember { mutableStateOf(false) }
    var selectedObject by remember {
        mutableStateOf(EncoderCanOpenFun.EDS_OBJECTS.first { it.key == "position" })
    }
    var writeValueInput by remember { mutableStateOf("0") }
    var writeArmed by remember { mutableStateOf(false) }
    var sampleCount by remember { mutableStateOf(0) }
    var sampleCurrent by remember { mutableStateOf<Long?>(null) }
    var sampleMin by remember { mutableStateOf<Long?>(null) }
    var sampleMax by remember { mutableStateOf<Long?>(null) }

    val foundNodes = remember { mutableStateListOf<Int>() }
    val values = remember { mutableStateMapOf<String, Long>() }
    val valueErrors = remember { mutableStateMapOf<String, String>() }
    val logLines = remember { mutableStateListOf<String>() }

    fun setPanelBusy(value: Boolean) {
        busy = value
        onExternalBusyChange(value)
    }

    fun parseNode(): Int? = nodeInput.trim().toIntOrNull()?.takeIf { it in 1..127 }

    suspend fun appendLog(message: String) {
        withContext(Dispatchers.Main) {
            logLines.add(message)
            if (logLines.size > 30) logLines.removeAt(0)
        }
    }

    suspend fun ensureCanReady(): Boolean {
        val ready = MySerialPortFun.ensureCanPortOpen(context)
        if (!ready) appendLog("✗ CAN 串口打开失败，请检查串口配置")
        return ready
    }

    suspend fun awaitObject(
        nodeId: Int,
        obj: EncoderCanOpenFun.EdsObject,
        timeoutMs: Long = DEBUG_SDO_TIMEOUT_MS
    ): DebugSdoResult {
        val raw = SdoReplyWaiters.sendAndAwait(
            EncoderCanOpenFun.buildReadObjectFrame(nodeId, obj),
            0x580 + nodeId,
            timeoutMs
        ) ?: return DebugSdoResult(error = "超时，无 SDO 应答")
        val parsed = EncoderCanOpenFun.parseSdoReply(raw)
            ?: return DebugSdoResult(error = "应答长度或命令符无效")
        if (parsed.index != obj.index || parsed.subIndex != obj.subIndex) {
            return DebugSdoResult(
                error = "应答对象不匹配：%04X-%02X".format(parsed.index, parsed.subIndex)
            )
        }
        parsed.abortCode?.let { code ->
            return DebugSdoResult(
                reply = parsed,
                error = "0x%08X %s".format(code, EncoderCanOpenFun.abortDescription(code))
            )
        }
        if (parsed.value == null) return DebugSdoResult(error = "应答未携带数值")
        return DebugSdoResult(reply = parsed)
    }

    fun runNodeScan() {
        val extra = parseNode()
        setPanelBusy(true)
        foundNodes.clear()
        logLines.clear()
        scope.launch(Dispatchers.IO) {
            try {
                if (!ensureCanReady()) return@launch
                appendLog("扫描常用节点：出厂 ID 1、工作 ID 21~28…")
                val deviceType = EncoderCanOpenFun.EDS_OBJECTS.first { it.key == "deviceType" }
                val discovered = mutableListOf<Int>()
                val candidates = (listOfNotNull(extra, 1) + (21..28)).distinct()
                candidates.forEach { nodeId ->
                    val result = awaitObject(nodeId, deviceType, SCAN_SDO_TIMEOUT_MS)
                    val value = result.reply?.value
                    if (value != null) {
                        discovered.add(nodeId)
                        withContext(Dispatchers.Main) { foundNodes.add(nodeId) }
                        appendLog("✓ 发现节点 $nodeId，设备类型=0x${value.toString(16).uppercase()}")
                    }
                }
                if (discovered.isEmpty()) appendLog("✗ 未发现编码器，请检查供电、CAN-H/L、终端电阻和波特率")
                else appendLog("扫描完成：${discovered.joinToString()}")
            } finally {
                withContext(Dispatchers.Main) { setPanelBusy(false) }
            }
        }
    }

    fun runQuickDiagnostic() {
        val nodeId = parseNode()
        if (nodeId == null) {
            scope.launch { appendLog("✗ Node-ID 必须为 1~127") }
            return
        }
        setPanelBusy(true)
        values.clear()
        valueErrors.clear()
        logLines.clear()
        scope.launch(Dispatchers.IO) {
            try {
                if (!ensureCanReady()) return@launch
                appendLog("读取节点 $nodeId 的 EDS 快速诊断快照…")
                val rawValues = mutableMapOf<String, Long>()
                for ((position, obj) in EncoderCanOpenFun.QUICK_DIAGNOSTIC_OBJECTS.withIndex()) {
                    val result = awaitObject(nodeId, obj)
                    val value = result.reply?.value
                    if (value != null) rawValues[obj.key] = value
                    withContext(Dispatchers.Main) {
                        if (value != null) values[obj.key] = value
                        else valueErrors[obj.key] = result.error ?: "读取失败"
                    }
                    if (position == 0 && value == null) {
                        appendLog("✗ 节点 $nodeId 无法连接：${result.error}")
                        return@launch
                    }
                }
                val vendorOk = rawValues["vendorId"] == 0xFFFFL
                val productOk = rawValues["productCode"] == 0x10L
                val baud = rawValues["baudRate"]
                appendLog(
                    if (vendorOk && productOk) "✓ 身份匹配 BRT EDS（Vendor=0xFFFF, Product=0x10）"
                    else "⚠ 节点有应答，但身份与本 EDS 默认值不一致，请核对型号"
                )
                baud?.let {
                    appendLog(
                        if (it == 6L) "✓ CAN 波特率：${EncoderCanOpenFun.baudRateDescription(it)}"
                        else "⚠ CAN 波特率代码=$it（${EncoderCanOpenFun.baudRateDescription(it)}），本机要求代码 6"
                    )
                }

                // 已配置工作节点读到物理分辨率后同步到 App，供回绕展开与滤波阈值使用。
                val resolution = rawValues["physicalResolution"]?.toInt() ?: 0
                val encIndex = viewModel.currentEncoderFeedbackState().encoders
                    .indexOfFirst { it.nodeId == nodeId }
                if (encIndex >= 0 && resolution > 0) {
                    viewModel.updateEncoderCalibration(encIndex) { it.copy(singleTurnResolution = resolution) }
                    viewModel.currentEncoderFeedbackState().encoders.getOrNull(encIndex)?.let {
                        MySharedPreFun(context).saveEncoderCalibration(it)
                    }
                }
                appendLog("✓ 快照完成；红色条目表示设备不支持或读取失败")
            } finally {
                withContext(Dispatchers.Main) { setPanelBusy(false) }
            }
        }
    }

    fun readSelectedObject() {
        val nodeId = parseNode()
        if (nodeId == null) {
            scope.launch { appendLog("✗ Node-ID 必须为 1~127") }
            return
        }
        setPanelBusy(true)
        writeArmed = false
        scope.launch(Dispatchers.IO) {
            try {
                if (!ensureCanReady()) return@launch
                val result = awaitObject(nodeId, selectedObject)
                val value = result.reply?.value
                if (value != null) {
                    withContext(Dispatchers.Main) {
                        values[selectedObject.key] = value
                        valueErrors.remove(selectedObject.key)
                        writeValueInput = value.toString()
                    }
                    appendLog("✓ 读 ${selectedObject.address} ${selectedObject.title} = ${EncoderCanOpenFun.formatEdsValue(selectedObject, value)}")
                } else {
                    appendLog("✗ 读 ${selectedObject.address} 失败：${result.error}")
                }
            } finally {
                withContext(Dispatchers.Main) { setPanelBusy(false) }
            }
        }
    }

    fun parseInputValue(text: String): Long? {
        val cleaned = text.trim()
        return when {
            cleaned.startsWith("0x", ignoreCase = true) -> cleaned.drop(2).toLongOrNull(16)
            else -> cleaned.toLongOrNull()
        }
    }

    fun valueFits(obj: EncoderCanOpenFun.EdsObject, value: Long): Boolean = when (obj.type) {
        EncoderCanOpenFun.EdsDataType.U8 -> value in 0L..0xFFL
        EncoderCanOpenFun.EdsDataType.U16 -> value in 0L..0xFFFFL
        EncoderCanOpenFun.EdsDataType.U32 -> value in 0L..0xFFFFFFFFL
        EncoderCanOpenFun.EdsDataType.I32 -> value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
    }

    fun writeSelectedObject() {
        val nodeId = parseNode()
        val value = parseInputValue(writeValueInput)
        when {
            nodeId == null -> scope.launch { appendLog("✗ Node-ID 必须为 1~127") }
            selectedObject.access != EncoderCanOpenFun.EdsAccess.RW ->
                scope.launch { appendLog("✗ ${selectedObject.address} 是 EDS 只读对象") }
            selectedObject.index == EncoderCanOpenFun.OD_BAUDRATE ->
                scope.launch { appendLog("✗ 波特率在本机锁定为 500 kbit/s，禁止软件修改") }
            selectedObject.index == EncoderCanOpenFun.OD_NODE_ID ->
                scope.launch { appendLog("✗ 节点 ID 请使用下方逐台配置流程，确保保存和重启验证") }
            value == null || !valueFits(selectedObject, value) ->
                scope.launch { appendLog("✗ 写入值无效或超出 ${selectedObject.type} 范围") }
            !writeArmed -> {
                writeArmed = true
                scope.launch { appendLog("⚠ 已准备写 ${selectedObject.address} = $value；再次点击确认") }
            }
            else -> {
                setPanelBusy(true)
                writeArmed = false
                scope.launch(Dispatchers.IO) {
                    try {
                        if (!ensureCanReady()) return@launch
                        val raw = SdoReplyWaiters.sendAndAwait(
                            EncoderCanOpenFun.buildWriteObjectFrame(nodeId, selectedObject, value),
                            0x580 + nodeId,
                            DEBUG_SDO_TIMEOUT_MS
                        )
                        val reply = raw?.let(EncoderCanOpenFun::parseSdoReply)
                        when {
                            raw == null -> appendLog("✗ 写入超时，无 SDO 应答")
                            reply == null -> appendLog("✗ 写入应答无效")
                            reply.abortCode != null -> {
                                val code = reply.abortCode
                                appendLog("✗ 写入中止 0x%08X：%s".format(code, EncoderCanOpenFun.abortDescription(code)))
                            }
                            !reply.isWriteAck || reply.index != selectedObject.index ||
                                reply.subIndex != selectedObject.subIndex -> appendLog("✗ 写入应答对象或命令符不匹配")
                            else -> appendLog("✓ 已写入 RAM：${selectedObject.address} = $value；需要长期保存时点击“保存参数”")
                        }
                    } finally {
                        withContext(Dispatchers.Main) { setPanelBusy(false) }
                    }
                }
            }
        }
    }

    fun sendNoReplyFrame(frame: ByteArray, label: String) {
        setPanelBusy(true)
        scope.launch(Dispatchers.IO) {
            try {
                if (!ensureCanReady()) return@launch
                CanOpenFun.sendFrameSequenced(frame)
                appendLog("✓ 已发送 $label（该类 CANopen 帧无 SDO 应答）")
            } finally {
                withContext(Dispatchers.Main) { setPanelBusy(false) }
            }
        }
    }

    fun sendNmt(command: Int, label: String) {
        val nodeId = parseNode()
        if (nodeId == null) {
            scope.launch { appendLog("✗ Node-ID 必须为 1~127") }
            return
        }
        sendNoReplyFrame(CanOpenFun.buildNmtFrame(command, nodeId), "NMT $label → 节点 $nodeId")
    }

    fun saveParameters() {
        val nodeId = parseNode()
        if (nodeId == null) {
            scope.launch { appendLog("✗ Node-ID 必须为 1~127") }
            return
        }
        setPanelBusy(true)
        scope.launch(Dispatchers.IO) {
            try {
                if (!ensureCanReady()) return@launch
                val raw = SdoReplyWaiters.sendAndAwait(
                    EncoderCanOpenFun.buildSaveParamsFrame(nodeId), 0x580 + nodeId, DEBUG_SDO_TIMEOUT_MS
                )
                val reply = raw?.let(EncoderCanOpenFun::parseSdoReply)
                when {
                    raw == null -> appendLog("✗ 保存超时")
                    reply?.abortCode != null -> {
                        val code = reply.abortCode
                        appendLog("✗ 保存中止 0x%08X：%s".format(code, EncoderCanOpenFun.abortDescription(code)))
                    }
                    reply?.isWriteAck == true &&
                        reply.index == EncoderCanOpenFun.OD_STORE_PARAMS &&
                        reply.subIndex == EncoderCanOpenFun.SUB_STORE ->
                        appendLog("✓ 参数已保存；通信参数通常需断电重启后生效")
                    else -> appendLog("✗ 保存应答无效")
                }
            } finally {
                withContext(Dispatchers.Main) { setPanelBusy(false) }
            }
        }
    }

    fun startSampling() {
        val nodeId = parseNode()
        if (nodeId == null) {
            scope.launch { appendLog("✗ Node-ID 必须为 1~127") }
            return
        }
        sampleCount = 0
        sampleCurrent = null
        sampleMin = null
        sampleMax = null
        sampling = true
        onExternalBusyChange(true)
        val positionObject = EncoderCanOpenFun.EDS_OBJECTS.first { it.key == "position" }
        samplingJob = scope.launch(Dispatchers.IO) {
            try {
                if (!ensureCanReady()) return@launch
                appendLog("开始 SDO 连续采样 6004h（约 ${SAMPLE_INTERVAL_MS}ms/次）")
                while (isActive) {
                    val result = awaitObject(nodeId, positionObject)
                    val value = result.reply?.value
                    if (value != null) {
                        withContext(Dispatchers.Main) {
                            sampleCurrent = value
                            sampleMin = sampleMin?.let { minOf(it, value) } ?: value
                            sampleMax = sampleMax?.let { maxOf(it, value) } ?: value
                            sampleCount++
                        }
                    } else {
                        appendLog("⚠ 采样失败：${result.error}")
                    }
                    delay(SAMPLE_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                withContext(Dispatchers.Main) {
                    sampling = false
                    onExternalBusyChange(false)
                }
            }
        }
    }

    fun stopSampling() {
        samplingJob?.cancel()
        samplingJob = null
        sampling = false
        onExternalBusyChange(false)
        scope.launch { appendLog("已停止采样，共 $sampleCount 点") }
    }

    DisposableEffect(Unit) {
        onDispose {
            samplingJob?.cancel()
            samplingJob = null
            onExternalBusyChange(false)
        }
    }

    val parsedNode = parseNode()
    val mappedFeedback = feedbackState?.encoders?.firstOrNull { it.nodeId == parsedNode }
    val controlsEnabled = !busy && !externalBusy && !sampling

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设备诊断与 EDS 调试", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1))
        Text(
            "支持节点发现、身份核验、对象字典读写、NMT/SYNC 和位置采样。写操作仅对 EDS 的 RW 对象开放；波特率固定 500 kbit/s。",
            fontSize = 12.sp,
            color = Color(0xFF555555)
        )

        Card(elevation = 2.dp, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nodeInput,
                        onValueChange = { nodeInput = it; writeArmed = false },
                        label = { Text("当前 Node-ID（1~127）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = controlsEnabled,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { runNodeScan() },
                        enabled = controlsEnabled,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64))
                    ) { Text("扫描", color = Color.White) }
                    Button(
                        onClick = { runQuickDiagnostic() },
                        enabled = controlsEnabled,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0))
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("一键诊断", color = Color.White)
                    }
                }
                if (foundNodes.isNotEmpty()) {
                    Text("已发现：${foundNodes.joinToString { "Node-$it" }}", color = Color(0xFF2E7D32), fontSize = 13.sp)
                }
                mappedFeedback?.let {
                    val onlineText = if (it.isOnline) "在线" else "离线"
                    Text(
                        "App 实时反馈：$onlineText　原始=${it.rawPosition}　滤波=${"%.1f".format(it.filteredPosition)}　实测深度=${"%.1f".format(it.measuredDepth)} mm",
                        color = if (it.isOnline) Color(0xFF2E7D32) else Color(0xFFC62828),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (values.isNotEmpty() || valueErrors.isNotEmpty()) {
            Card(elevation = 2.dp, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("EDS 快照", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                    EncoderCanOpenFun.QUICK_DIAGNOSTIC_OBJECTS.forEach { obj ->
                        val value = values[obj.key]
                        val error = valueErrors[obj.key]
                        if (value != null || error != null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${obj.address} ${obj.title}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(
                                    when {
                                        value == null -> error ?: "失败"
                                        obj.key == "baudRate" -> "${EncoderCanOpenFun.formatEdsValue(obj, value)}  ${EncoderCanOpenFun.baudRateDescription(value)}"
                                        else -> EncoderCanOpenFun.formatEdsValue(obj, value)
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
        }

        Card(elevation = 2.dp, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("CANopen 状态与位置采样", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                Text("预运行会暂停 PDO；复位通信会短暂离线。调试时请确认机具处于安全静止状态。", fontSize = 11.sp, color = Color(0xFFC62828))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sendNmt(CanOpenFun.Nmt.OPERATIONAL, "启动") }, enabled = controlsEnabled, modifier = Modifier.weight(1f)) { Text("NMT 启动", fontSize = 12.sp) }
                    OutlinedButton(onClick = { sendNmt(CanOpenFun.Nmt.STOPPED, "停止") }, enabled = controlsEnabled, modifier = Modifier.weight(1f)) { Text("NMT 停止", fontSize = 12.sp) }
                    OutlinedButton(onClick = { sendNmt(CanOpenFun.Nmt.PRE_OPERATIONAL, "预运行") }, enabled = controlsEnabled, modifier = Modifier.weight(1f)) { Text("预运行", fontSize = 12.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sendNmt(CanOpenFun.Nmt.RESET_NODE, "复位节点") }, enabled = controlsEnabled, modifier = Modifier.weight(1f)) { Text("复位节点", fontSize = 12.sp) }
                    OutlinedButton(onClick = { sendNmt(CanOpenFun.Nmt.RESET_COMM, "复位通信") }, enabled = controlsEnabled, modifier = Modifier.weight(1f)) { Text("复位通信", fontSize = 12.sp) }
                    OutlinedButton(onClick = { sendNoReplyFrame(EncoderCanOpenFun.buildSyncFrame(), "SYNC") }, enabled = controlsEnabled, modifier = Modifier.weight(1f)) { Text("发 SYNC", fontSize = 12.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { if (sampling) stopSampling() else startSampling() },
                        enabled = sampling || (!busy && !externalBusy),
                        colors = ButtonDefaults.buttonColors(backgroundColor = if (sampling) Color(0xFFC62828) else Color(0xFF2E7D32))
                    ) { Text(if (sampling) "停止采样" else "连续采样 6004h", color = Color.White) }
                    Text(
                        "点数=$sampleCount　当前=${sampleCurrent ?: "--"}　最小=${sampleMin ?: "--"}　最大=${sampleMax ?: "--"}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Card(elevation = 2.dp, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("EDS 对象控制台", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                Text("目录包含 EDS 中可 expedited SDO 访问的全部数字对象；字符串对象需分段 SDO，未在此开放。", fontSize = 11.sp, color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).wrapContentSize(Alignment.TopStart)) {
                        OutlinedButton(
                            onClick = { objectMenuOpen = true },
                            enabled = controlsEnabled,
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, Color(0xFF1565C0))
                        ) {
                            Text("${selectedObject.address} ${selectedObject.title} [${selectedObject.type}/${selectedObject.access}]", fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = objectMenuOpen, onDismissRequest = { objectMenuOpen = false }) {
                            EncoderCanOpenFun.EDS_OBJECTS.forEach { obj ->
                                DropdownMenuItem(onClick = {
                                    selectedObject = obj
                                    objectMenuOpen = false
                                    writeArmed = false
                                    values[obj.key]?.let { writeValueInput = it.toString() }
                                }) {
                                    Text("${obj.address} ${obj.title} [${obj.type}/${obj.access}]", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Button(onClick = { readSelectedObject() }, enabled = controlsEnabled, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0))) {
                        Text("读取", color = Color.White)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = writeValueInput,
                        onValueChange = { writeValueInput = it; writeArmed = false },
                        label = { Text("写入值（十进制或 0x 十六进制）") },
                        singleLine = true,
                        enabled = controlsEnabled && selectedObject.access == EncoderCanOpenFun.EdsAccess.RW,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { writeSelectedObject() },
                        enabled = controlsEnabled && selectedObject.access == EncoderCanOpenFun.EdsAccess.RW,
                        colors = ButtonDefaults.buttonColors(backgroundColor = if (writeArmed) Color(0xFFC62828) else Color(0xFFEF6C00))
                    ) { Text(if (writeArmed) "确认写入" else "准备写入", color = Color.White) }
                    OutlinedButton(onClick = { saveParameters() }, enabled = controlsEnabled) { Text("保存参数") }
                }
                if (selectedObject.index == EncoderCanOpenFun.OD_BAUDRATE || selectedObject.index == EncoderCanOpenFun.OD_NODE_ID) {
                    Text("该对象在通用控制台中写保护：波特率禁止修改；节点 ID 使用下方专用配置流程。", color = Color(0xFFC62828), fontSize = 11.sp)
                }
            }
        }

        if (logLines.isNotEmpty()) {
            Card(elevation = 0.dp, modifier = Modifier.fillMaxWidth(), backgroundColor = Color(0xFF263238)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("调试日志", color = Color(0xFF90CAF9), fontWeight = FontWeight.Bold, fontSize = 12.sp)
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

        Divider(color = Color(0xFF90A4AE), thickness = 2.dp)
        Spacer(Modifier.height(2.dp))
    }
}
