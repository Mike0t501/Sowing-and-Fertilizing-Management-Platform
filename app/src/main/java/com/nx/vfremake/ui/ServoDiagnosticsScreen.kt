package com.nx.vfremake.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
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
import com.nx.vfremake.funClass.CanLinkStats
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.funClass.SdoReplyWaiters
import com.nx.vfremake.funClass.TpdoArrivalMonitor
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

// ── CAN 链路质量 ────────────────────────────────────────────────────────────
/** "最近 N 秒"窗口的采样拍数（每秒一拍） */
private const val LINK_WINDOW_SAMPLES = 5
/** 主动丢帧探测的帧数：50 帧在健康链路上约 1.5s，足以暴露 2% 量级的丢帧 */
private const val LINK_PROBE_COUNT = 50
/** 探测单帧超时：健康往返约 20~30ms，200ms 已是 6 倍余量，不用等到 1s */
private const val LINK_PROBE_TIMEOUT_MS = 200L

/** 2614h 保存标志轮询：驱动器写 EEPROM 一般 1s 内完成，留 10 次 × 300ms 余量。 */
private const val SERVO_SAVE_POLL_TIMES = 10
private const val SERVO_SAVE_POLL_INTERVAL_MS = 300L

/** 核验第一步为一路 TPDO 读回的通信参数快照（1800h 子索引 01/02/05） */
private data class TpdoNodeConfig(val cobId: Long?, val transType: Long?, val storedMs: Long?)

/** 本会话真正下发成功过的一次周期命令，供核验做 L1（下发值 vs 对象值）比对 */
private data class TpdoCommandRecord(val pdo: Int, val ms: Int, val nodes: List<Int>)

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

    // ── CAN 链路质量（每秒轮询一次计数器快照；保留最近几拍用于算"最近 5 秒"增量）──
    // 2026-09 现场为一个接触不良的 CAN 接口排查了数天，因为软件层面看不出在丢帧。
    var linkSnap by remember { mutableStateOf(CanLinkStats.snapshot()) }
    val linkHistory = remember { mutableStateListOf<CanLinkStats.Snapshot>() }
    LaunchedEffect(Unit) {
        while (true) {
            val s = CanLinkStats.snapshot()
            linkSnap = s
            linkHistory.add(s)
            if (linkHistory.size > LINK_WINDOW_SAMPLES + 1) linkHistory.removeAt(0)
            delay(1000)
        }
    }

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

    /**
     * 主动丢帧探测：对选定节点连发 [LINK_PROBE_COUNT] 次 6064h 读，逐帧等应答，
     * 统计收到几个、平均往返多久。
     *
     * 这是比被动计数更有说服力的链路测试：被动计数掺着各路业务流量，而这里是
     * 一问一答、逐帧配对，丢了就是丢了。只用读操作，作业运行中也安全；
     * 走 SdoReplyWaiters（全局 20ms 步调 + 请求互斥），不会挤掉控制回路的帧。
     * 50 帧在健康链路上约 1.5s 跑完；每帧超时 [LINK_PROBE_TIMEOUT_MS]，最坏 10s。
     */
    fun probeLinkLoss() {
        busy = true
        scope.launch(Dispatchers.IO) {
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    appendLog("✗ CAN 串口打开失败")
                    return@launch
                }
                appendLog("… 链路探测 Node $nodeId：连发 $LINK_PROBE_COUNT 帧 6064h 读")
                var replied = 0
                var rttSumMs = 0L
                var rttMaxMs = 0L
                val t0 = System.nanoTime()
                repeat(LINK_PROBE_COUNT) {
                    val sent = System.nanoTime()
                    val raw = SdoReplyWaiters.sendAndAwait(
                        CanOpenFun.buildReadPositionFrame(nodeId),
                        0x580 + nodeId,
                        LINK_PROBE_TIMEOUT_MS
                    )
                    if (raw != null) {
                        replied++
                        val rtt = (System.nanoTime() - sent) / 1_000_000L
                        rttSumMs += rtt
                        if (rtt > rttMaxMs) rttMaxMs = rtt
                    }
                }
                val totalMs = (System.nanoTime() - t0) / 1_000_000L
                val lost = LINK_PROBE_COUNT - replied
                val lossPct = lost * 100.0 / LINK_PROBE_COUNT
                val verdict = when {
                    lost == 0 -> "✓ 链路正常"
                    lossPct < 5 -> "⚠ 轻微丢帧"
                    else -> "✗ 链路异常，优先检查 CAN 接口/线缆/接触"
                }
                appendLog(
                    "$verdict  Node $nodeId：发 $LINK_PROBE_COUNT / 收 $replied，丢失 %.1f%%，".format(lossPct) +
                        if (replied > 0) "往返均值 ${rttSumMs / replied}ms 最大 ${rttMaxMs}ms，共 ${totalMs}ms"
                        else "全部超时，共 ${totalMs}ms"
                )
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

    // ── TPDO 上报周期快捷配置 ────────────────────────────────────────────
    // 现场最常见的诉求是把默认 100ms 改到 20ms 以提高控深采样率。改的是
    // 1800h~1803h 子索引 05（Event Timer），SDO 写入后立即生效、掉电丢失；
    // 需要固化时才走 2614h 专用保存路径（写入并回读校验成功后方可点亮）。
    var tpdoPdo by remember { mutableStateOf(1) }
    var tpdoPdoMenuOpen by remember { mutableStateOf(false) }
    var tpdoPeriodInput by remember { mutableStateOf("20") }
    var tpdoApplyAllOnline by remember { mutableStateOf(false) }
    var tpdoWriteArmed by remember { mutableStateOf(false) }
    var tpdoSaveArmed by remember { mutableStateOf(false) }
    var tpdoAppliedNodes by remember { mutableStateOf(emptyList<Int>()) }
    val tpdoEventMs = remember { mutableStateMapOf<Int, Long>() }
    val tpdoTransType = remember { mutableStateMapOf<Int, Long>() }
    val tpdoReadErrors = remember { mutableStateMapOf<Int, String>() }
    val tpdoCobId = remember { mutableStateMapOf<Int, Long>() }
    var tpdoEnableMsInput by remember { mutableStateOf("100") }
    var pdoToggleArmed by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var verifyWindowMs by remember { mutableStateOf(5000L) }
    var verifyRunning by remember { mutableStateOf(false) }
    var tpdoLastCommand by remember { mutableStateOf<TpdoCommandRecord?>(null) }
    val verifyResults = remember { mutableStateListOf<ServoCanOpenFun.TpdoVerifyResult>() }

    /** 切换目标电机后，上一台的 TPDO 快照与已武装的确认状态都必须作废。 */
    fun resetTpdoPanel() {
        tpdoEventMs.clear()
        tpdoTransType.clear()
        tpdoCobId.clear()
        tpdoReadErrors.clear()
        tpdoWriteArmed = false
        tpdoSaveArmed = false
        pdoToggleArmed = null
        tpdoAppliedNodes = emptyList()
    }

    /**
     * 面向任意节点的一次 SDO 问答（不限于当前选中电机，供「应用到全部在线」用）。
     * 超时/中止/格式错误统一写日志并返回 null，调用方只需判空。
     */
    suspend fun sdoExchange(node: Int, frame: ByteArray, label: String): ServoCanOpenFun.SdoReply? {
        val raw = SdoReplyWaiters.sendAndAwait(frame, 0x580 + node, SERVO_DEBUG_TIMEOUT_MS)
        if (raw == null) {
            appendLog("✗ $label 超时")
            return null
        }
        val reply = ServoCanOpenFun.parseSdoReply(raw)
        return when {
            reply == null -> {
                appendLog("✗ $label 应答格式无效")
                null
            }
            reply.abortCode != null -> {
                appendLog(
                    "✗ $label 中止 0x%08X：%s".format(
                        reply.abortCode,
                        ServoCanOpenFun.abortDescription(reply.abortCode)
                    )
                )
                null
            }
            else -> reply
        }
    }

    /** 读当前电机 TPDO1~4 的传输类型与事件周期，作为改周期前的现状快照。 */
    fun readTpdoConfig() {
        busy = true
        scope.launch(Dispatchers.IO) {
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    appendLog("✗ CAN 串口打开失败")
                    return@launch
                }
                appendLog("读取 M${selectedMotorIndex + 1} / Node $nodeId 的 TPDO1~4 通信参数")
                ServoCanOpenFun.TPDO_CHANNELS.forEach { ch ->
                    val cobIdValue = sdoExchange(
                        nodeId,
                        ServoCanOpenFun.buildReadTpdoCobIdFrame(nodeId, ch.pdo),
                        "读 ${ch.label} COB-ID"
                    )?.value
                    val typeValue = sdoExchange(
                        nodeId,
                        ServoCanOpenFun.buildReadTpdoTransmissionTypeFrame(nodeId, ch.pdo),
                        "读 ${ch.label} 传输类型"
                    )?.value
                    val eventValue = sdoExchange(
                        nodeId,
                        ServoCanOpenFun.buildReadTpdoEventTimerFrame(nodeId, ch.pdo),
                        "读 ${ch.label} 事件周期"
                    )?.value
                    withContext(Dispatchers.Main) {
                        if (cobIdValue != null) tpdoCobId[ch.pdo] = cobIdValue
                        else tpdoCobId.remove(ch.pdo)
                        if (typeValue != null) tpdoTransType[ch.pdo] = typeValue
                        else tpdoTransType.remove(ch.pdo)
                        if (eventValue != null) {
                            tpdoEventMs[ch.pdo] = eventValue
                            tpdoReadErrors.remove(ch.pdo)
                        } else {
                            tpdoEventMs.remove(ch.pdo)
                            tpdoReadErrors[ch.pdo] = "读取失败"
                        }
                    }
                    if (eventValue != null) {
                        appendLog(
                            "· ${ch.label} " +
                                (cobIdValue?.let {
                                    "COB-ID=0x%08X(%s) ".format(
                                        it,
                                        if (ServoCanOpenFun.isPdoEnabled(it)) "有效" else "已禁用"
                                    )
                                } ?: "") +
                                "周期=${eventValue}ms，传输类型=" +
                                (typeValue?.let { "$it ${ServoCanOpenFun.transmissionTypeDescription(it)}" } ?: "读取失败")
                        )
                    }
                }
                appendLog("✓ TPDO 参数读取完成")
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    /**
     * 写事件周期并逐节点回读校验。两步确认：第一次点击只武装，第二次才发帧。
     * 传输类型非 254/255 时事件周期不会生效，此处只告警不阻断（同步模式下用户
     * 可能就是想先把值备好，再单独改传输类型）。
     */
    fun applyTpdoPeriod() {
        val channel = ServoCanOpenFun.tpdoChannel(tpdoPdo)
        val ms = tpdoPeriodInput.trim().toIntOrNull()
        if (ms == null || ms !in ServoCanOpenFun.TPDO_EVENT_MS_MIN..ServoCanOpenFun.TPDO_EVENT_MS_MAX) {
            scope.launch {
                appendLog(
                    "✗ 周期无效：请输入 ${ServoCanOpenFun.TPDO_EVENT_MS_MIN}~" +
                        "${ServoCanOpenFun.TPDO_EVENT_MS_MAX} 的整数毫秒（0 = 关闭该通道上报）"
                )
            }
            return
        }
        val targets = if (tpdoApplyAllOnline) {
            state.motors.filter { it.isOnline }.map { it.nodeId }
        } else {
            listOf(nodeId)
        }
        if (targets.isEmpty()) {
            scope.launch { appendLog("✗ 当前没有在线电机，未发送任何写入") }
            return
        }
        if (!tpdoWriteArmed) {
            tpdoWriteArmed = true
            scope.launch {
                appendLog("⚠ 再次点击确认：把 Node ${targets.joinToString("/")} 的 ${channel.label} 周期改为 ${ms}ms")
            }
            return
        }
        busy = true
        tpdoWriteArmed = false
        tpdoSaveArmed = false
        scope.launch(Dispatchers.IO) {
            val succeeded = mutableListOf<Int>()
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    appendLog("✗ CAN 串口打开失败")
                    return@launch
                }
                for (node in targets) {
                    val typeValue = sdoExchange(
                        node,
                        ServoCanOpenFun.buildReadTpdoTransmissionTypeFrame(node, tpdoPdo),
                        "Node $node 读 ${channel.label} 传输类型"
                    )?.value
                    if (typeValue != null && !ServoCanOpenFun.isAsyncTransmissionType(typeValue)) {
                        appendLog(
                            "⚠ Node $node ${channel.label} 传输类型=$typeValue " +
                                "${ServoCanOpenFun.transmissionTypeDescription(typeValue)}，事件周期不会生效"
                        )
                    }
                    val wrote = sdoExchange(
                        node,
                        ServoCanOpenFun.buildSetTpdoEventTimerFrame(node, tpdoPdo, ms),
                        "Node $node 写 ${channel.label} 周期 = ${ms}ms"
                    )
                    if (wrote?.command != 0x60) {
                        appendLog("✗ Node $node 写入未被确认，跳过")
                        continue
                    }
                    val readBack = sdoExchange(
                        node,
                        ServoCanOpenFun.buildReadTpdoEventTimerFrame(node, tpdoPdo),
                        "Node $node 回读 ${channel.label} 周期"
                    )?.value
                    if (readBack == null) continue
                    if (readBack.toInt() != ms) {
                        appendLog("✗ Node $node 回读 ${readBack}ms 与写入 ${ms}ms 不符")
                        continue
                    }
                    appendLog("✓ Node $node ${channel.label} 上报周期 = ${ms}ms（已回读校验）")
                    succeeded.add(node)
                    if (node == nodeId) {
                        withContext(Dispatchers.Main) {
                            tpdoEventMs[tpdoPdo] = readBack
                            if (typeValue != null) tpdoTransType[tpdoPdo] = typeValue
                            tpdoReadErrors.remove(tpdoPdo)
                        }
                    }
                }
                appendLog(
                    if (succeeded.size == targets.size) {
                        "✓ ${succeeded.size} 个节点全部写入成功；该值掉电丢失，需固化请点「写入掉电存储」"
                    } else {
                        "⚠ 仅 ${succeeded.size}/${targets.size} 个节点写入成功"
                    }
                )
            } finally {
                withContext(Dispatchers.Main) {
                    tpdoAppliedNodes = succeeded
                    // 记录"上位机下发了什么"，供周期核验做 L1 比对；
                    // 与 tpdoAppliedNodes 分开保存，切换选中电机不清除已发生的事实。
                    if (succeeded.isNotEmpty()) {
                        tpdoLastCommand = TpdoCommandRecord(tpdoPdo, ms, succeeded.toList())
                    }
                    busy = false
                }
            }
        }
    }

    /**
     * 开启/关闭一路 TPDO：读-改-写 COB-ID 的 Bit31（有效位），并同步事件周期。
     *
     * 顺序按 CiA301 的安全边：
     *   开启 → 先写事件周期（此时 PDO 仍无效，改通信参数最不容易被拒），最后清 Bit31；
     *   关闭 → 先置 Bit31（立即停发），再把事件周期写 0。
     * 两个方向都满足前缀安全：序列中途失败也不会停在"以错误速率狂发"的状态。
     *
     * 报文 ID 已被改离 base+NodeID 的节点拒绝开启——接收路由按 base 段认领，
     * 开了也不会被正确解析，只会白占带宽。关闭则任何情况下都放行。
     */
    fun toggleTpdoChannel(pdo: Int, enable: Boolean) {
        val channel = ServoCanOpenFun.tpdoChannel(pdo)
        val ms = if (enable) tpdoEnableMsInput.trim().toIntOrNull() else 0
        if (enable && (ms == null || ms !in 1..ServoCanOpenFun.TPDO_EVENT_MS_MAX)) {
            scope.launch { appendLog("✗ 开启周期无效：请输入 1~${ServoCanOpenFun.TPDO_EVENT_MS_MAX} 的整数毫秒") }
            return
        }
        val targets = if (tpdoApplyAllOnline) {
            state.motors.filter { it.isOnline }.map { it.nodeId }
        } else {
            listOf(nodeId)
        }
        if (targets.isEmpty()) {
            scope.launch { appendLog("✗ 当前没有在线电机，未发送任何写入") }
            return
        }
        val request = pdo to enable
        if (pdoToggleArmed != request) {
            pdoToggleArmed = request
            scope.launch {
                appendLog(
                    "⚠ 再次点击确认：${if (enable) "开启" else "关闭"} Node ${targets.joinToString("/")} 的 " +
                        channel.label + if (enable) "（周期 ${ms}ms）" else ""
                )
            }
            return
        }
        busy = true
        pdoToggleArmed = null
        scope.launch(Dispatchers.IO) {
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    appendLog("✗ CAN 串口打开失败")
                    return@launch
                }
                for (node in targets) {
                    val currentCobId = sdoExchange(
                        node,
                        ServoCanOpenFun.buildReadTpdoCobIdFrame(node, pdo),
                        "Node $node 读 ${channel.label} COB-ID"
                    )?.value ?: continue
                    if (!ServoCanOpenFun.cobIdMatchesDefault(currentCobId, node, pdo)) {
                        val actual = ServoCanOpenFun.cobIdCanId(currentCobId)
                        if (enable) {
                            appendLog(
                                "✗ Node $node ${channel.label} 报文 ID 已被改为 0x%03X（应为 0x%03X），拒绝开启".format(
                                    actual, channel.cobIdBase + node
                                )
                            )
                            continue
                        }
                        appendLog("⚠ Node $node ${channel.label} 报文 ID 为 0x%03X（非默认），仍继续关闭".format(actual))
                    }
                    val targetCobId = ServoCanOpenFun.tpdoCobIdWithEnabled(currentCobId, enable)
                    val steps = if (enable) {
                        listOf(
                            ServoCanOpenFun.buildSetTpdoEventTimerFrame(node, pdo, ms!!) to
                                "Node $node 写 ${channel.label} 周期 = ${ms}ms",
                            ServoCanOpenFun.buildSetTpdoCobIdFrame(node, pdo, targetCobId) to
                                "Node $node 置 ${channel.label} COB-ID 有效（0x%08X）".format(targetCobId)
                        )
                    } else {
                        listOf(
                            ServoCanOpenFun.buildSetTpdoCobIdFrame(node, pdo, targetCobId) to
                                "Node $node 置 ${channel.label} COB-ID 无效（0x%08X）".format(targetCobId),
                            ServoCanOpenFun.buildSetTpdoEventTimerFrame(node, pdo, 0) to
                                "Node $node 写 ${channel.label} 周期 = 0"
                        )
                    }
                    var stepFailed = false
                    for ((frame, label) in steps) {
                        if (sdoExchange(node, frame, label)?.command != 0x60) {
                            appendLog("✗ $label 未被确认，停止该节点后续步骤")
                            stepFailed = true
                            break
                        }
                    }
                    if (stepFailed) continue
                    val backCobId = sdoExchange(
                        node,
                        ServoCanOpenFun.buildReadTpdoCobIdFrame(node, pdo),
                        "Node $node 回读 ${channel.label} COB-ID"
                    )?.value
                    val backMs = sdoExchange(
                        node,
                        ServoCanOpenFun.buildReadTpdoEventTimerFrame(node, pdo),
                        "Node $node 回读 ${channel.label} 周期"
                    )?.value
                    if (backCobId == null || ServoCanOpenFun.isPdoEnabled(backCobId) != enable) {
                        appendLog("✗ Node $node ${channel.label} 回读有效位与预期不符")
                        continue
                    }
                    appendLog(
                        "✓ Node $node ${channel.label} 已${if (enable) "开启" else "关闭"}" +
                            "（COB-ID=0x%08X，周期=%s）".format(backCobId, backMs?.let { "${it}ms" } ?: "?")
                    )
                    if (node == nodeId) {
                        withContext(Dispatchers.Main) {
                            tpdoCobId[pdo] = backCobId
                            if (backMs != null) {
                                tpdoEventMs[pdo] = backMs
                                tpdoReadErrors.remove(pdo)
                            }
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    /**
     * 把刚写成功的节点参数固化到掉电存储：2614h 写 1 触发，轮询读回 2 判完成。
     *
     * 【为什么两步确认且只对刚写成功的节点开放】2614h 保存的是驱动器【当前全部】
     * 参数，本页调试期间改过的任何值都会被一并固化。限定范围 + 二次确认，避免
     * 误触把调试态写死到 EEPROM。
     */
    fun saveTpdoParameters() {
        val targets = tpdoAppliedNodes
        if (targets.isEmpty()) return
        if (!tpdoSaveArmed) {
            tpdoSaveArmed = true
            scope.launch {
                appendLog("⚠ 再次点击确认：把 Node ${targets.joinToString("/")} 的【全部当前参数】写入掉电存储")
            }
            return
        }
        busy = true
        tpdoSaveArmed = false
        scope.launch(Dispatchers.IO) {
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    appendLog("✗ CAN 串口打开失败")
                    return@launch
                }
                for (node in targets) {
                    val triggered = sdoExchange(
                        node,
                        ServoCanOpenFun.buildSaveParametersFrame(node),
                        "Node $node 触发参数保存（2614h = 1）"
                    )
                    if (triggered?.command != 0x60) continue
                    var polls = 0
                    var done = false
                    while (!done && polls < SERVO_SAVE_POLL_TIMES) {
                        polls++
                        delay(SERVO_SAVE_POLL_INTERVAL_MS)
                        val raw = SdoReplyWaiters.sendAndAwait(
                            ServoCanOpenFun.buildReadSaveFlagFrame(node),
                            0x580 + node,
                            SERVO_DEBUG_TIMEOUT_MS
                        )
                        val flag = raw?.let(ServoCanOpenFun::parseSdoReply)?.value
                        if (flag == ServoCanOpenFun.SAVE_FLAG_DONE) done = true
                    }
                    appendLog(
                        if (done) "✓ Node $node 参数已保存到掉电存储"
                        else "⚠ Node $node 保存标志未回到 2，请断电重启后回读周期确认"
                    )
                }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    /**
     * TPDO 周期核验：读对象值 → 旁听实测到达间隔 → 三层合成结论。
     *
     * 一次核验覆盖【全部已配置节点】：报文本来就在总线上，采样是纯旁听，多测几台
     * 不增加任何总线负担，而多机同测才看得出带宽饱和导致的整体丢帧。
     * 期望周期逐节点现读 1800h-05，不假设各台一致。
     */
    fun runTpdoVerification() {
        val channel = ServoCanOpenFun.tpdoChannel(tpdoPdo)
        busy = true
        verifyRunning = true
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { verifyResults.clear() }
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    appendLog("✗ CAN 串口打开失败")
                    return@launch
                }
                appendLog("开始核验 ${channel.label}：先读各节点 1800h 组参数")

                // ── 第一步：逐节点读 COB-ID / 传输类型 / 事件周期 ──────────────
                val configs = LinkedHashMap<Int, TpdoNodeConfig>()
                for (motor in state.motors) {
                    val node = motor.nodeId
                    val cobId = sdoExchange(
                        node,
                        ServoCanOpenFun.buildReadTpdoCobIdFrame(node, tpdoPdo),
                        "Node $node 读 ${channel.label} COB-ID"
                    )?.value
                    if (cobId == null) {
                        // 读不到通常就是不在线；不必再发后两帧白占总线
                        appendLog("· Node $node 无应答，跳过核验")
                        continue
                    }
                    val transType = sdoExchange(
                        node,
                        ServoCanOpenFun.buildReadTpdoTransmissionTypeFrame(node, tpdoPdo),
                        "Node $node 读 ${channel.label} 传输类型"
                    )?.value
                    val storedMs = sdoExchange(
                        node,
                        ServoCanOpenFun.buildReadTpdoEventTimerFrame(node, tpdoPdo),
                        "Node $node 读 ${channel.label} 事件周期"
                    )?.value
                    configs[node] = TpdoNodeConfig(cobId, transType, storedMs)
                    if (node == nodeId) {
                        withContext(Dispatchers.Main) {
                            tpdoCobId[tpdoPdo] = cobId
                            if (transType != null) tpdoTransType[tpdoPdo] = transType
                            if (storedMs != null) {
                                tpdoEventMs[tpdoPdo] = storedMs
                                tpdoReadErrors.remove(tpdoPdo)
                            }
                        }
                    }
                }
                if (configs.isEmpty()) {
                    appendLog("✗ 没有任何节点应答，核验中止")
                    return@launch
                }

                // ── 第二步：旁听采样（SDO 问答已结束，窗口内本机不发任何帧）────
                appendLog("采样中：旁听 ${verifyWindowMs}ms…")
                TpdoArrivalMonitor.start()
                try {
                    delay(verifyWindowMs)
                } finally {
                    TpdoArrivalMonitor.stop()
                }

                // ── 第三步：逐节点合成结论 ────────────────────────────────
                val results = mutableListOf<ServoCanOpenFun.TpdoVerifyResult>()
                configs.forEach { (node, cfg) ->
                    // 期望值优先取本会话真正下发过的那个值（L1 才成立）；
                    // 没下发过就退化为"以对象回读值为期望"，只核验 L2/L3。
                    val commanded = tpdoLastCommand
                        ?.takeIf { it.pdo == tpdoPdo && node in it.nodes }
                        ?.ms
                    val expected = commanded ?: cfg.storedMs?.toInt() ?: return@forEach
                    val result = ServoCanOpenFun.verifyTpdoTiming(
                        nodeId = node,
                        pdo = tpdoPdo,
                        expectedMs = expected,
                        storedMs = cfg.storedMs,
                        transmissionType = cfg.transType,
                        cobIdValue = cfg.cobId,
                        timestampsMs = TpdoArrivalMonitor.snapshot(node, tpdoPdo)
                    )
                    results.add(result)
                    appendLog(
                        "${if (result.passed) "✓" else "✗"} Node $node ${channel.label}" +
                            (if (commanded == null) "（无本次下发记录，以对象值为期望）" else "") +
                            "：${result.message}"
                    )
                }
                withContext(Dispatchers.Main) { verifyResults.addAll(results) }
                val passCount = results.count { it.passed }
                appendLog(
                    if (passCount == results.size) "✓ 核验完成：${results.size} 个节点全部合格"
                    else "⚠ 核验完成：${passCount}/${results.size} 个节点合格"
                )
            } finally {
                TpdoArrivalMonitor.stop()
                withContext(Dispatchers.Main) {
                    verifyRunning = false
                    busy = false
                }
            }
        }
    }

    // 非作业状态下为本页启动接收路由，使 SDO ACK 能投递到 SdoReplyWaiters。
    DisposableEffect(Unit) {
        val receiveCoroutine = if (!isSystemRunning) {
            MySerialPortFun.ensureCanPortOpen(context)
            CanReceiveCoroutine().also { it.start(viewModel, context) }
        } else null
        onDispose {
            samplingJob?.cancel()
            TpdoArrivalMonitor.clearAll()
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
                    Text("• 「YZ-MOTOR 验证」③ 运动核对会让电机小行程往返，务必悬空；①只读核对随时可跑。", fontSize = 12.sp)
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
                                    resetTpdoPanel()
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

            // ── CAN 链路质量 ─────────────────────────────────────────────────
            // 排查"播深调不了"这类问题时先看这里：SDO 丢失率 >0 或帧头错位字节 >0，
            // 就先查 CAN 接口/线缆/接触，别急着怀疑逻辑。
            LinkQualityCard(
                snap = linkSnap,
                history = linkHistory,
                probeEnabled = !busy && !sampling,
                onProbe = { probeLinkLoss() },
                onReset = { CanLinkStats.reset(); linkHistory.clear() }
            )

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

            // ── YZ-MOTOR 验证：EDS vs 固件逐项实测 ─────────────────────────────
            YzMotorVerifyCard(
                viewModel = viewModel,
                nodeId = nodeId,
                motorLabel = "M${selectedMotorIndex + 1}",
                calibration = selectedMotor,
                depthState = state,
                busy = busy,
                onBusy = { busy = it },
                sampling = sampling,
                writesEnabled = writesEnabled,
                appendLog = { appendLog(it) }
            )

            Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TPDO 上报周期", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                    Text(
                        "改 1800h~1803h 子索引 05（事件周期 ms）。SDO 写入立即生效但掉电丢失；" +
                            "EDS 出厂 TPDO1 与 TPDO3 都是 100ms，TPDO3 的数据本 App 不解析，关掉可省一半带宽。",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { readTpdoConfig() },
                            enabled = !busy && !sampling
                        ) { Text("读取 TPDO1~4 现状", fontSize = 12.sp) }
                        if (busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 3.dp)
                    }

                    ServoCanOpenFun.TPDO_CHANNELS.forEach { ch ->
                        val eventMs = tpdoEventMs[ch.pdo]
                        val transType = tpdoTransType[ch.pdo]
                        val readError = tpdoReadErrors[ch.pdo]
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "%04X-05 %s · %s".format(ch.paramIndex, ch.label, ch.mapping),
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                when {
                                    eventMs != null ->
                                        (if (eventMs == 0L) "不上报" else "${eventMs}ms") +
                                            (transType?.takeIf { !ServoCanOpenFun.isAsyncTransmissionType(it) }
                                                ?.let { " ·类型$it 非异步" } ?: "")
                                    readError != null -> readError
                                    else -> "未读取（EDS 默认 ${ch.defaultEventMs}ms）"
                                },
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    readError != null -> Color(0xFFC62828)
                                    eventMs == null -> Color.Gray
                                    else -> Color(0xFF263238)
                                }
                            )
                        }
                        Divider(color = Color(0xFFE0E0E0))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column {
                            OutlinedButton(
                                onClick = { tpdoPdoMenuOpen = true },
                                enabled = !busy && !sampling,
                                border = BorderStroke(1.dp, Color(0xFF1565C0))
                            ) { Text("目标通道 TPDO$tpdoPdo", fontSize = 12.sp) }
                            DropdownMenu(expanded = tpdoPdoMenuOpen, onDismissRequest = { tpdoPdoMenuOpen = false }) {
                                ServoCanOpenFun.TPDO_CHANNELS.forEach { ch ->
                                    DropdownMenuItem(onClick = {
                                        tpdoPdo = ch.pdo
                                        tpdoPdoMenuOpen = false
                                        tpdoWriteArmed = false
                                    }) {
                                        Text("${ch.label}（0x%03X+ID）${ch.mapping}".format(ch.cobIdBase), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { tpdoApplyAllOnline = !tpdoApplyAllOnline; tpdoWriteArmed = false },
                            enabled = writesEnabled,
                            colors = ButtonDefaults.outlinedButtonColors(
                                backgroundColor = if (tpdoApplyAllOnline) Color(0xFFE3F2FD) else Color.Transparent
                            )
                        ) {
                            Text(
                                if (tpdoApplyAllOnline) "应用范围：全部在线电机" else "应用范围：仅 M${selectedMotorIndex + 1}",
                                fontSize = 12.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ServoCanOpenFun.TPDO_EVENT_PRESETS_MS.forEach { preset ->
                            val selected = tpdoPeriodInput.trim().toIntOrNull() == preset
                            OutlinedButton(
                                onClick = { tpdoPeriodInput = preset.toString(); tpdoWriteArmed = false },
                                enabled = writesEnabled,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    backgroundColor = if (selected) Color(0xFFE3F2FD) else Color.Transparent
                                ),
                                border = BorderStroke(1.dp, if (selected) Color(0xFF1565C0) else Color(0xFFBDBDBD))
                            ) { Text(if (preset == 0) "关闭" else "${preset}ms", fontSize = 12.sp) }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = tpdoPeriodInput,
                            onValueChange = { tpdoPeriodInput = it; tpdoWriteArmed = false },
                            label = { Text("上报周期 ms（0 = 关闭）") },
                            singleLine = true,
                            enabled = writesEnabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { applyTpdoPeriod() },
                            enabled = writesEnabled,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (tpdoWriteArmed) Color(0xFFC62828) else Color(0xFFEF6C00)
                            )
                        ) { Text(if (tpdoWriteArmed) "确认写入" else "写入周期", color = Color.White) }
                    }

                    val pendingMs = tpdoPeriodInput.trim().toIntOrNull()
                    val onlineServoCount = state.motors.count { it.isOnline }.coerceAtLeast(1)
                    val bridgeLoad = ServoCanOpenFun.estimateBridgeLoad(
                        ServoCanOpenFun.TPDO_CHANNELS.map { ch ->
                            val ms = if (ch.pdo == tpdoPdo && pendingMs != null) pendingMs
                            else tpdoEventMs[ch.pdo]?.toInt() ?: ch.defaultEventMs
                            ServoCanOpenFun.TpdoStream(onlineServoCount, ch.dataBytes, ms)
                        }
                    )
                    Text(
                        "带宽估算（按 %d 台在线伺服同周期计）：%.0f 帧/s，%.0f B/s，占 115200 串口 %.0f%%".format(
                            onlineServoCount,
                            bridgeLoad.framesPerSec,
                            bridgeLoad.bytesPerSec,
                            bridgeLoad.utilization * 100
                        ),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF455A64)
                    )
                    Text(
                        "未计入施肥电机帧、摆臂编码器 TPDO 与 SDO 问答，实际占用高于此值。",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    ServoCanOpenFun.bridgeLoadWarning(bridgeLoad)?.let { warning ->
                        Text(
                            warning,
                            fontSize = 12.sp,
                            color = when {
                                warning.startsWith("✗") -> Color(0xFFC62828)
                                warning.startsWith("⚠") -> Color(0xFFEF6C00)
                                else -> Color(0xFF455A64)
                            }
                        )
                    }

                    if (tpdoAppliedNodes.isNotEmpty()) {
                        Text(
                            "已写入 Node ${tpdoAppliedNodes.joinToString("/")}；不保存则断电后恢复原周期。",
                            fontSize = 12.sp,
                            color = Color(0xFF2E7D32)
                        )
                        Button(
                            onClick = { saveTpdoParameters() },
                            enabled = writesEnabled,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (tpdoSaveArmed) Color(0xFFC62828) else Color(0xFF455A64)
                            )
                        ) {
                            Text(
                                if (tpdoSaveArmed) "确认固化到 EEPROM" else "写入掉电存储（2614h）",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            "注意：2614h 会把驱动器当前【全部】参数一并固化，请确认本次调试未改坏其它对象。",
                            fontSize = 11.sp,
                            color = Color(0xFFC62828)
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TPDO2 / TPDO3 快捷开关", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                    Text(
                        "开关走 COB-ID Bit31 有效位（1801h/1802h 子索引 01），比只写周期 0 更彻底，" +
                            "并按读-改-写保留驱动器实际使用的报文 ID。",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Text(
                        "本 App 只解析 TPDO1。TPDO3 出厂就是 100ms 常开、数据被直接丢弃，关掉可省带宽；" +
                            "TPDO2 出厂周期 0，一般无需开启（映射与 TPDO1 完全相同）。",
                        fontSize = 12.sp,
                        color = Color(0xFFEF6C00)
                    )

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { tpdoApplyAllOnline = !tpdoApplyAllOnline; pdoToggleArmed = null; tpdoWriteArmed = false },
                            enabled = writesEnabled,
                            colors = ButtonDefaults.outlinedButtonColors(
                                backgroundColor = if (tpdoApplyAllOnline) Color(0xFFE3F2FD) else Color.Transparent
                            )
                        ) {
                            Text(
                                if (tpdoApplyAllOnline) "应用范围：全部在线电机" else "应用范围：仅 M${selectedMotorIndex + 1}",
                                fontSize = 12.sp
                            )
                        }
                        OutlinedTextField(
                            value = tpdoEnableMsInput,
                            onValueChange = { tpdoEnableMsInput = it; pdoToggleArmed = null },
                            label = { Text("开启时写入周期 ms", fontSize = 11.sp) },
                            singleLine = true,
                            enabled = writesEnabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    listOf(2, 3).forEach { pdo ->
                        val channel = ServoCanOpenFun.tpdoChannel(pdo)
                        val cobId = tpdoCobId[pdo]
                        val eventMs = tpdoEventMs[pdo]
                        val enabled = cobId?.let { ServoCanOpenFun.isPdoEnabled(it) }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "${channel.label}（0x%03X+ID）%s".format(channel.cobIdBase, channel.mapping),
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    when (enabled) {
                                        null -> "未读取"
                                        true -> "有效 · %s".format(
                                            if (eventMs == null || eventMs == 0L) "周期 0" else "${eventMs}ms"
                                        )
                                        false -> "已禁用"
                                    },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = when (enabled) {
                                        null -> Color.Gray
                                        true -> Color(0xFFEF6C00)
                                        false -> Color(0xFF2E7D32)
                                    }
                                )
                            }
                            if (cobId != null) {
                                Text(
                                    "COB-ID = 0x%08X（报文 ID 0x%03X%s）".format(
                                        cobId,
                                        ServoCanOpenFun.cobIdCanId(cobId),
                                        if (ServoCanOpenFun.cobIdMatchesDefault(cobId, nodeId, pdo)) "" else "，非默认"
                                    ),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (ServoCanOpenFun.cobIdMatchesDefault(cobId, nodeId, pdo)) {
                                        Color(0xFF455A64)
                                    } else {
                                        Color(0xFFC62828)
                                    }
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { toggleTpdoChannel(pdo, true) },
                                    enabled = writesEnabled,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (pdoToggleArmed == (pdo to true)) Color(0xFFC62828)
                                        else Color(0xFF546E7A)
                                    )
                                ) {
                                    Text(
                                        if (pdoToggleArmed == (pdo to true)) "确认开启 ${channel.label}" else "开启 ${channel.label}",
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                }
                                Button(
                                    onClick = { toggleTpdoChannel(pdo, false) },
                                    enabled = writesEnabled,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (pdoToggleArmed == (pdo to false)) Color(0xFFC62828)
                                        else Color(0xFF2E7D32)
                                    )
                                ) {
                                    Text(
                                        if (pdoToggleArmed == (pdo to false)) "确认关闭 ${channel.label}" else "关闭 ${channel.label}",
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        Divider(color = Color(0xFFE0E0E0))
                    }

                    Text(
                        "状态未读取时请先点上方「读取 TPDO1~4 现状」；若写入中止码提示 0x08000022，" +
                            "先点「预运行」再操作，完成后点「NMT 启动」。",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp, shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TPDO 周期核验", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                    Text(
                        "三层核验：① SDO 回读 1800h-05 是否等于下发值 ② 传输类型/有效位是否让它生效 " +
                            "③ 旁听总线，实测到达间隔是否真等于该值。只做 ① 等于自己读自己写的值，说明不了问题。",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Text(
                        "核验对象为「目标通道 TPDO$tpdoPdo」，一次覆盖全部已配置节点（旁听不占总线）。",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("采样时长", fontSize = 12.sp)
                        listOf(3000L, 5000L, 10000L).forEach { windowMs ->
                            val selected = verifyWindowMs == windowMs
                            OutlinedButton(
                                onClick = { verifyWindowMs = windowMs },
                                enabled = !busy && !sampling,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    backgroundColor = if (selected) Color(0xFFE3F2FD) else Color.Transparent
                                ),
                                border = BorderStroke(1.dp, if (selected) Color(0xFF1565C0) else Color(0xFFBDBDBD))
                            ) { Text("${windowMs / 1000}s", fontSize = 12.sp) }
                        }
                        OutlinedButton(
                            onClick = {
                                verifyWindowMs = ServoCanOpenFun.recommendedVerifyWindowMs(
                                    tpdoEventMs[tpdoPdo]?.toInt() ?: ServoCanOpenFun.tpdoChannel(tpdoPdo).defaultEventMs
                                )
                            },
                            enabled = !busy && !sampling
                        ) { Text("按周期推荐", fontSize = 12.sp) }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { runTpdoVerification() },
                            enabled = !busy && !sampling,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00695C))
                        ) {
                            Text(
                                if (verifyRunning) "核验中…" else "开始核验（${verifyWindowMs / 1000}s）",
                                color = Color.White
                            )
                        }
                        if (verifyRunning) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 3.dp)
                    }

                    tpdoLastCommand?.let { cmd ->
                        Text(
                            "本次下发记录：TPDO${cmd.pdo} = ${cmd.ms}ms → Node ${cmd.nodes.joinToString("/")}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF455A64)
                        )
                    }

                    verifyResults.forEach { result ->
                        val motorLabel = state.motors.indexOfFirst { it.nodeId == result.nodeId }
                            .let { if (it >= 0) "M${it + 1}" else "?" }
                        val badgeColor = when (result.verdict) {
                            ServoCanOpenFun.TpdoVerifyVerdict.PASS -> Color(0xFF2E7D32)
                            ServoCanOpenFun.TpdoVerifyVerdict.JITTERY,
                            ServoCanOpenFun.TpdoVerifyVerdict.LOSSY,
                            ServoCanOpenFun.TpdoVerifyVerdict.NO_DATA -> Color(0xFFEF6C00)
                            else -> Color(0xFFC62828)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "$motorLabel / Node ${result.nodeId}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    when (result.verdict) {
                                        ServoCanOpenFun.TpdoVerifyVerdict.PASS -> "✓ 合格"
                                        ServoCanOpenFun.TpdoVerifyVerdict.VALUE_MISMATCH -> "✗ 对象值不符"
                                        ServoCanOpenFun.TpdoVerifyVerdict.NOT_EFFECTIVE -> "✗ 未生效"
                                        ServoCanOpenFun.TpdoVerifyVerdict.RATE_MISMATCH -> "✗ 实测不符"
                                        ServoCanOpenFun.TpdoVerifyVerdict.JITTERY -> "⚠ 抖动大"
                                        ServoCanOpenFun.TpdoVerifyVerdict.LOSSY -> "⚠ 丢帧"
                                        ServoCanOpenFun.TpdoVerifyVerdict.NO_DATA -> "⚠ 数据不足"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor
                                )
                            }
                            Text(result.message, fontSize = 11.sp, color = badgeColor)
                            Text(
                                "期望 ${result.expectedMs}ms · 对象回读 ${result.storedMs?.let { "${it}ms" } ?: "失败"}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF455A64)
                            )
                            result.report?.let { report ->
                                Text(
                                    "实测 %d 帧 / %.1fs · 均隔 %.1fms · 中位 %dms · %d~%dms · 抖动 %.1fms · 漏收 %d".format(
                                        report.frameCount,
                                        report.windowMs / 1000.0,
                                        report.meanMs,
                                        report.medianMs,
                                        report.minMs,
                                        report.maxMs,
                                        report.jitterMs,
                                        report.droppedEstimate
                                    ),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF263238)
                                )
                            }
                        }
                        Divider(color = Color(0xFFE0E0E0))
                    }

                    if (verifyResults.isNotEmpty()) {
                        Text(
                            "判定容差：±%.0f%% 且不小于 ±%.0fms（UART 桥与串口读调度本身有几毫秒抖动）。".format(
                                ServoCanOpenFun.TPDO_VERIFY_TOLERANCE_RATIO * 100,
                                ServoCanOpenFun.TPDO_VERIFY_TOLERANCE_MIN_MS
                            ),
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                    if (isSystemRunning) {
                        Text(
                            "作业运行中：控深循环仍在发位置命令，实测抖动会偏大，判周期本身仍有效。",
                            fontSize = 11.sp,
                            color = Color(0xFFEF6C00)
                        )
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

// ─────────────────────────────────────────────────────────────────────────────
// CAN 链路质量卡片
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 链路质量卡片：全局 UART 健康度 + 每节点 SDO 请求/应答配对 + 最近 5 秒增量 + 主动探测。
 *
 * 判读口诀（也印在卡片上）：
 *   - SDO 丢失率应为 0%。>0 且持续增长 → 链路在丢帧；
 *   - 帧头错位字节 / 帧尾错 应恒为 0。>0 → UART 字节流本身在丢，比 SDO 丢失更底层；
 *   - Boot-up 次数 >0 → 驱动器在自复位（总线错误累积到 bus-off 后恢复也会发 Boot-up）。
 * 三者任一异常，先查 CAN 接口/线缆/接触，再怀疑逻辑。
 */
@Composable
private fun LinkQualityCard(
    snap: CanLinkStats.Snapshot,
    history: List<CanLinkStats.Snapshot>,
    probeEnabled: Boolean,
    onProbe: () -> Unit,
    onReset: () -> Unit
) {
    // "最近 N 秒"增量：拿窗口首拍与当前拍做差
    val base = history.firstOrNull()
    val winSec = if (base != null) ((snap.atMs - base.atMs) / 1000L).coerceAtLeast(1L) else 0L
    fun recent(sel: (CanLinkStats.Snapshot) -> Long): Long = if (base == null) 0L else sel(snap) - sel(base)
    fun recentNode(id: Int, sel: (CanLinkStats.NodeSnapshot) -> Long): Long {
        val now = snap.nodes[id] ?: return 0L
        val then = base?.nodes?.get(id)
        return sel(now) - (then?.let(sel) ?: 0L)
    }

    val uartOk = snap.uartClean
    val worstLoss = snap.nodes.values.maxOfOrNull { it.sdoLossRatio } ?: 0.0
    val headerColor = when {
        !uartOk || worstLoss >= 0.05 -> Color(0xFFC62828)
        worstLoss > 0.0 -> Color(0xFFEF6C00)
        else -> Color(0xFF2E7D32)
    }
    val headerVerdict = when {
        !uartOk -> "UART 帧错位——先查接口/线缆"
        worstLoss >= 0.05 -> "SDO 丢帧严重——先查接口/线缆"
        worstLoss > 0.0 -> "有少量 SDO 丢帧"
        snap.rxFrames == 0L -> "尚无数据"
        else -> "链路正常"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp,
        shape = RoundedCornerShape(10.dp),
        backgroundColor = if (uartOk && worstLoss == 0.0) Color.White else Color(0xFFFFF8E1)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("CAN 链路质量", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                Text(headerVerdict, fontWeight = FontWeight.SemiBold, color = headerColor, fontSize = 13.sp)
            }

            // 全局行（等宽字体，肉眼对齐）
            Text(
                "运行 %ds   发帧 %d   收帧 %d   最近%ds收帧 %d".format(
                    snap.uptimeMs / 1000, snap.txFrames, snap.rxFrames, winSec, recent { it.rxFrames }
                ),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp
            )
            Text(
                "帧头错位字节 %d   帧尾错 %d   解析异常 %d   串口IO异常 %d".format(
                    snap.resyncBytes, snap.badTailFrames, snap.parseErrors, snap.ioErrors
                ),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = if (uartOk) Color.Unspecified else Color(0xFFC62828)
            )

            Divider(color = Color(0xFFEEEEEE), thickness = 0.5.dp)

            // 每节点表
            if (snap.nodes.isEmpty()) {
                Text("尚未收到任何 CANopen 报文", fontSize = 12.sp, color = Color.Gray)
            } else {
                Text(
                    "节点  SDO请求  应答  丢失   丢失率  最近%ds丢失  TPDO1  心跳  Boot".format(winSec),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray
                )
                snap.nodes.values.forEach { n ->
                    val recentLost = recentNode(n.nodeId) { it.sdoRequests } - recentNode(n.nodeId) { it.sdoReplies }
                    val rowColor = when {
                        n.bootups > 0 || n.sdoLossRatio >= 0.05 -> Color(0xFFC62828)
                        n.sdoLossRatio > 0.0 -> Color(0xFFEF6C00)
                        else -> Color.Unspecified
                    }
                    Text(
                        "%-5d %7d %5d %5d %6.1f%% %10d %6d %5d %5d".format(
                            n.nodeId, n.sdoRequests, n.sdoReplies, n.sdoLost,
                            n.sdoLossRatio * 100, recentLost, n.tpdo1, n.heartbeats, n.bootups
                        ),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = rowColor
                    )
                }
            }

            Text(
                "判读：SDO 丢失率与帧头错位应恒为 0；Boot-up>0 说明驱动器在自复位。" +
                    "任一异常先查 CAN 接口/线缆/接触，再怀疑逻辑。6084h 已知不应答，不计入。",
                fontSize = 11.sp, color = Color.Gray
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onProbe,
                    enabled = probeEnabled,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0))
                ) { Text("丢帧探测 ×$LINK_PROBE_COUNT", color = Color.White) }
                OutlinedButton(onClick = onReset) { Text("重置计数") }
            }
        }
    }
}
