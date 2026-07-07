/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2026年7月7日
 * @file    :
 * @brief   :摆臂编码器一次性配置工具（Provisioning）——出厂 ID=1 的编码器逐台分配 Node-ID(21~28)、
 *           设置 TPDO 发送间隔 50ms、保存参数；重启后按新 ID 验证并持久化分辨率
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 首版：播种深度实测反馈子系统（开发/维护用，入口在设置页深处）
 ***********************************************************************************************************
 */

package com.nx.vfremake.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.coroutine.CanReceiveCoroutine
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.EncoderCanOpenFun
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.funClass.SdoReplyWaiters
import com.nx.vfremake.isSystemRunning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 配置序列每步的 SDO 应答等待超时（ms）：出厂设备 SDO 响应实测 <1ms，1.5s 足够容错 */
private const val PROVISION_STEP_TIMEOUT_MS = 1500L

/**
 * 摆臂编码器一次性配置界面。
 *
 * 使用场景：编码器出厂 Node-ID=1，必须逐台单独接入总线改到 21~28 后才能与
 * 伺服/施肥共存（出厂 TPDO 0x181 会跌落施肥解析造成数据污染，见
 * [EncoderCanOpenFun.ENCODER_NODE_ID_RANGE] 注释）。
 *
 * 操作序列（每步经 [SdoReplyWaiters] 等应答，超时报错中止）：
 *   ① 读 6501h 验证在线并暂存物理分辨率
 *   ② 写 3001h = 目标 ID（应答仍按【旧 ID】匹配——设备保存重启前不换 ID）
 *   ③ 写 1800-05 = 50ms（带宽预算，见 [EncoderCanOpenFun.TPDO1_EVENT_TIME_MS]）
 *   ④ 写 1010h = "save"（放序列最后，前缀安全）
 *   ⑤ 断电重启后按新 ID 读 6004h 验证，成功才把 nodeId/分辨率持久化到该行
 */
@Composable
fun EncoderProvisioningScreen(
    viewModel: VariableFertViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var selectedRow by remember { mutableStateOf(0) }            // 0~7 → 目标 ID 21~28
    var oldIdInput  by remember { mutableStateOf("1") }          // 出厂默认 1，可改（重配场景）
    var busy        by remember { mutableStateOf(false) }
    var configDone  by remember { mutableStateOf(false) }        // ①~④ 已完成，待重启验证
    var readResolution by remember { mutableStateOf<Int?>(null) }
    val stepLog = remember { mutableStateListOf<String>() }

    suspend fun log(msg: String) = withContext(Dispatchers.Main) { stepLog.add(msg) }

    /** 校验一条 SDO 应答：null=超时；CS 不符=SDO 错误。返回错误文案或 null（通过）。 */
    fun checkReply(reply: ByteArray?, expectCs: Int): String? = when {
        reply == null -> "无应答（超时）"
        (reply[0].toInt() and 0xFF) == 0x80 -> "设备返回 SDO 错误"
        (reply[0].toInt() and 0xFF) != expectCs -> "应答命令符异常"
        else -> null
    }

    fun runProvisioning() {
        val oldId = oldIdInput.toIntOrNull()
        if (oldId == null || oldId !in 1..127) {
            scope.launch { log("✗ 旧 ID 无效（1~127）") }
            return
        }
        val targetId = EncoderCanOpenFun.ENCODER_NODE_ID_BASE + selectedRow
        busy = true
        configDone = false
        stepLog.clear()
        scope.launch(Dispatchers.IO) {
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    log("✗ CAN 串口打开失败，请检查串口配置")
                    return@launch
                }
                val replyOldId = 0x580 + oldId

                log("① 读物理分辨率 6501h（ID=$oldId）…")
                val r1 = SdoReplyWaiters.sendAndAwait(
                    EncoderCanOpenFun.buildReadResolutionFrame(oldId),
                    replyOldId, PROVISION_STEP_TIMEOUT_MS
                )
                checkReply(r1, 0x43)?.let {
                    log("✗ $it。请确认：编码器已上电、总线上只有这一台未配置设备")
                    return@launch
                }
                val res = CanOpenFun.parseSdoResponse(r1!!)?.toInt()
                if (res == null || res <= 0) {
                    log("✗ 分辨率解析失败")
                    return@launch
                }
                readResolution = res
                log("✓ 设备在线，单圈分辨率 = $res")

                log("② 写节点 ID 3001h ← $targetId…")
                // 注意：写 3001 后设备在保存并断电重启前仍用旧 ID 应答（手册示例），
                // 故本步及后续 ③④ 的发送与应答匹配全部沿用旧 ID
                val r2 = SdoReplyWaiters.sendAndAwait(
                    EncoderCanOpenFun.buildSetNodeIdFrame(oldId, targetId),
                    replyOldId, PROVISION_STEP_TIMEOUT_MS
                )
                checkReply(r2, 0x60)?.let { log("✗ 写节点 ID 失败：$it"); return@launch }
                log("✓ 节点 ID 已写入（重启前仍以旧 ID 应答）")

                log("③ 写 TPDO 发送间隔 1800-05 ← ${EncoderCanOpenFun.TPDO1_EVENT_TIME_MS}ms…")
                val r3 = SdoReplyWaiters.sendAndAwait(
                    EncoderCanOpenFun.buildSetEventTimeFrame(oldId),
                    replyOldId, PROVISION_STEP_TIMEOUT_MS
                )
                checkReply(r3, 0x60)?.let { log("✗ 写发送间隔失败：$it"); return@launch }
                log("✓ 发送间隔已写入")

                log("④ 保存参数 1010h ← \"save\"…")
                val r4 = SdoReplyWaiters.sendAndAwait(
                    EncoderCanOpenFun.buildSaveParamsFrame(oldId),
                    replyOldId, PROVISION_STEP_TIMEOUT_MS
                )
                checkReply(r4, 0x60)?.let { log("✗ 保存失败：$it（参数未持久化，请重试）"); return@launch }
                log("✓ 保存成功")
                log("请断电重启编码器电源，然后点击「⑤ 验证」")
                withContext(Dispatchers.Main) { configDone = true }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    fun runVerify() {
        val targetId = EncoderCanOpenFun.ENCODER_NODE_ID_BASE + selectedRow
        busy = true
        scope.launch(Dispatchers.IO) {
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    log("✗ CAN 串口打开失败")
                    return@launch
                }
                log("⑤ 按新 ID=$targetId 读当前位置 6004h…")
                val r = SdoReplyWaiters.sendAndAwait(
                    EncoderCanOpenFun.buildReadPositionFrame(targetId),
                    0x580 + targetId, PROVISION_STEP_TIMEOUT_MS
                )
                checkReply(r, 0x43)?.let {
                    log("✗ 验证失败：$it。请确认编码器已断电重启")
                    return@launch
                }
                val pos = CanOpenFun.parseSdoResponse(r!!)
                log("✓ 验证成功！当前位置 = $pos")
                // 验证通过才把 nodeId 与分辨率持久化到该行（分辨率供符号展开/限幅阈值用）
                viewModel.updateEncoderCalibration(selectedRow) { cal ->
                    cal.copy(
                        nodeId = targetId,
                        singleTurnResolution = readResolution ?: cal.singleTurnResolution
                    )
                }
                val updated = viewModel.currentEncoderFeedbackState()
                    .encoders.getOrNull(selectedRow)
                if (updated != null) {
                    MySharedPreFun(context).saveEncoderCalibration(updated)
                    log("✓ 已保存到第 ${selectedRow + 1} 行（Node-ID=$targetId, 分辨率=${updated.singleTurnResolution}）")
                }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    // 页面生命周期：非作业时启动接收协程（SDO 应答经 dispatchFrame → SdoReplyWaiters）
    DisposableEffect(Unit) {
        val receiveCoroutine: CanReceiveCoroutine? = if (!isSystemRunning) {
            MySerialPortFun.ensureCanPortOpen(context)
            CanReceiveCoroutine().also { it.start(viewModel, context) }
        } else {
            null
        }
        onDispose {
            receiveCoroutine?.shutdown()
            SdoReplyWaiters.clearAll()   // 离页兜底：挂起的等待自然超时
        }
    }

    Scaffold(
        topBar = { MyTopBar("编码器配置工具（维护）", onBack) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 醒目警示
            Card(
                backgroundColor = Color(0xFFFFF3CD),
                elevation       = 0.dp,
                modifier        = Modifier.fillMaxWidth()
            ) {
                Text(
                    "⚠ 重要：同一时刻总线上只能接入一台未配置的编码器（出厂 Node-ID=1）！\n" +
                        "多台未配置设备同时在线会互相冲突。请逐台接入 → 配置 → 断电重启 → 验证。",
                    color    = Color(0xFF856404),
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp
                )
            }

            Card(elevation = 2.dp, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("目标行号（对应编码器 Node-ID 21~28）", fontSize = 14.sp, color = Color(0xFF1565C0))

                    // 行号选择：两行 4 列按钮
                    (0 until 8).chunked(4).forEach { rowIndices ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowIndices.forEach { i ->
                                val selected = selectedRow == i
                                OutlinedButton(
                                    onClick  = { if (!busy) { selectedRow = i; configDone = false } },
                                    modifier = Modifier.weight(1f),
                                    border   = BorderStroke(
                                        if (selected) 2.dp else 1.dp,
                                        if (selected) Color(0xFF1565C0) else Color.Gray
                                    ),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        backgroundColor = if (selected) Color(0xFFE3F2FD) else Color.Transparent
                                    ),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        "第${i + 1}行→${EncoderCanOpenFun.ENCODER_NODE_ID_BASE + i}",
                                        fontSize = 12.sp,
                                        color    = if (selected) Color(0xFF1565C0) else Color.Gray
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value           = oldIdInput,
                            onValueChange   = { oldIdInput = it },
                            label           = { Text("设备当前 ID（出厂为 1）", fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine      = true,
                            enabled         = !busy,
                            modifier        = Modifier.weight(1f)
                        )
                        readResolution?.let {
                            Text(
                                "分辨率: $it",
                                fontSize   = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color      = Color(0xFF388E3C)
                            )
                        }
                    }

                    Divider()

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick  = { runProvisioning() },
                            enabled  = !busy,
                            modifier = Modifier.weight(1f),
                            colors   = ButtonDefaults.buttonColors(
                                backgroundColor         = Color(0xFF1565C0),
                                disabledBackgroundColor = Color(0xFFBBBBBB)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier    = Modifier.size(16.dp),
                                    color       = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("①~④ 开始配置", color = Color.White, fontSize = 13.sp)
                        }
                        Button(
                            onClick  = { runVerify() },
                            enabled  = !busy && configDone,
                            modifier = Modifier.weight(1f),
                            colors   = ButtonDefaults.buttonColors(
                                backgroundColor         = Color(0xFF388E3C),
                                disabledBackgroundColor = Color(0xFFBBBBBB)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("⑤ 验证（重启后）", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }

            // 步骤日志
            if (stepLog.isNotEmpty()) {
                Card(elevation = 2.dp, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF263238))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        stepLog.forEach { line ->
                            Text(
                                line,
                                fontSize   = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color      = when {
                                    line.startsWith("✗") -> Color(0xFFFF8A80)
                                    line.startsWith("✓") -> Color(0xFFB9F6CA)
                                    else                 -> Color(0xFFECEFF1)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
