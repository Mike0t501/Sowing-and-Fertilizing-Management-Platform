/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2026年9月13日
 * @file    :YzMotorVerifyCard.kt
 * @brief   :伺服调试页「YZ-MOTOR 验证」卡片：A/B/C 三组检测按钮、进度、结果列表、报告复制/保存
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 2026-09 EDS 与固件谁是准的判不出来，靠聊天回忆现场读数效率太低。这张卡片把全部疑点做成一键检测，
 * 报告一键复制即可带回讨论。C 组会让电机小行程往返，必须两步确认并保留随时「停止」。
 ***********************************************************************************************************
 */

package com.nx.vfremake.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.BuildConfig
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.coroutine.LiveYzVerifyIo
import com.nx.vfremake.coroutine.YzMotorVerifyRunner
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.data.SowingDepthState
import com.nx.vfremake.funClass.DocuAndManageFun
import com.nx.vfremake.funClass.JogSession
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.funClass.YzMotorVerifyFun
import com.nx.vfremake.funClass.YzMotorVerifyFun.CheckItem
import com.nx.vfremake.funClass.YzMotorVerifyFun.Group
import com.nx.vfremake.funClass.YzMotorVerifyFun.Verdict
import com.nx.vfremake.isSystemRunning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val YZ_BLUE = Color(0xFF1565C0)
private val YZ_GREEN = Color(0xFF2E7D32)
private val YZ_ORANGE = Color(0xFFEF6C00)
private val YZ_RED = Color(0xFFC62828)
private val YZ_GREY = Color(0xFF607D8B)

/**
 * YZ-MOTOR 验证卡片。
 *
 * @param busy          页面级忙标志（与其它卡片的按钮互斥）
 * @param onBusy        运行开始/结束时回写页面级忙标志
 * @param writesEnabled 页面级"允许写入"（非作业、非采样、非忙）
 * @param appendLog     写入页面底部调试日志
 */
@Composable
fun YzMotorVerifyCard(
    viewModel: VariableFertViewModel,
    nodeId: Int,
    motorLabel: String,
    calibration: ServoCalibration,
    depthState: SowingDepthState,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    sampling: Boolean,
    writesEnabled: Boolean,
    appendLog: suspend (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 切换目标电机后结果作废：不同电机的报告不能混在一起
    val items = remember(nodeId) { mutableStateListOf<CheckItem>() }
    var startedAtMs by remember(nodeId) { mutableStateOf(0L) }
    var running by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var armedGroups by remember { mutableStateOf<List<Group>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    var listenMs by remember { mutableStateOf(3000L) }
    var travelInput by remember { mutableStateOf(YzMotorVerifyFun.DEFAULT_TRAVEL_PULSES.toString()) }
    var includeRpdo by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    fun buildReport(): YzMotorVerifyFun.VerifyReport = YzMotorVerifyFun.VerifyReport(
        nodeId = nodeId,
        motorLabel = motorLabel,
        startedAtMs = if (startedAtMs == 0L) System.currentTimeMillis() else startedAtMs,
        appVersion = BuildConfig.VERSION_NAME,
        items = items.toList()
    )

    /**
     * 启动一次验证。B/C 组要求非作业、非点动；C 组两步确认在调用方（按钮）完成。
     * 重跑某组时只清该组旧结果，A 组的读数可以留给报告里的 B/C 对照看。
     */
    fun launchRun(groups: List<Group>) {
        val travel = travelInput.trim().toIntOrNull()
        if (Group.C in groups && (travel == null || travel !in YzMotorVerifyFun.TRAVEL_PULSES_MIN..YzMotorVerifyFun.TRAVEL_PULSES_MAX)) {
            scope.launch { appendLog("✗ 位移量无效：请输入 ${YzMotorVerifyFun.TRAVEL_PULSES_MIN}~${YzMotorVerifyFun.TRAVEL_PULSES_MAX} 脉冲") }
            return
        }
        if (groups.any { it != Group.A } && (isSystemRunning || JogSession.isJogging(nodeId))) {
            scope.launch { appendLog("✗ 作业运行或点动中，B/C 组禁止运行") }
            return
        }
        val options = YzMotorVerifyFun.VerifyOptions(
            listenMs = listenMs,
            travelPulses = travel ?: YzMotorVerifyFun.DEFAULT_TRAVEL_PULSES,
            includeRpdo = includeRpdo
        )
        items.removeAll { it.group in groups }
        if (items.isEmpty() || startedAtMs == 0L) startedAtMs = System.currentTimeMillis()
        running = true
        armedGroups = null
        onBusy(true)
        job = scope.launch(Dispatchers.IO) {
            val runner = YzMotorVerifyRunner(
                io = LiveYzVerifyIo,
                nodeId = nodeId,
                calibration = calibration,
                positionSpeed = depthState.positionSpeed,
                acceleration = depthState.acceleration,
                options = options,
                onProgress = { text -> withContext(Dispatchers.Main) { progressText = text } },
                onItem = { item -> withContext(Dispatchers.Main) { items.add(item) } }
            )
            try {
                if (!MySerialPortFun.ensureCanPortOpen(context)) {
                    appendLog("✗ CAN 串口打开失败")
                    return@launch
                }
                if (Group.C in groups) JogSession.begin(nodeId)
                appendLog("开始 YZ-MOTOR 验证 $motorLabel / Node $nodeId：" + groups.joinToString("→") { it.name })
                for (g in groups) {
                    when (g) {
                        Group.A -> runner.runGroupA()
                        Group.B -> runner.runGroupB()
                        Group.C -> runner.runGroupC()
                    }
                }
                val report = withContext(Dispatchers.Main) { buildReport() }
                appendLog("✓ YZ-MOTOR 验证完成：${report.summary()}")
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    runner.safeStop()
                    appendLog("⚠ 验证已停止，电机已安全收尾（Shutdown）")
                }
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    runner.safeStop()
                    appendLog("✗ 验证异常：${e.message}，电机已安全收尾")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    JogSession.end(nodeId)
                    running = false
                    progressText = ""
                    onBusy(false)
                }
            }
        }
    }

    /** C 组（含「全部运行」）两步确认：第一次点只武装并提示，第二次才真的动电机。 */
    fun requestRun(groups: List<Group>) {
        if (Group.C !in groups) {
            launchRun(groups)
            return
        }
        if (armedGroups != groups) {
            armedGroups = groups
            scope.launch {
                appendLog("⚠ 再次点击确认：电机将小行程往返（约 ${travelInput.trim()} 脉冲 ×3 + 低速点动）。请确认机具悬空、远离限位。")
            }
            return
        }
        launchRun(groups)
    }

    fun copyReport() {
        val text = buildReport().toText()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("YZ-MOTOR 验证报告", text))
        scope.launch { appendLog("✓ 报告已复制到剪贴板（${text.length} 字）") }
    }

    fun saveReport() {
        val report = buildReport()
        val fileName = "yzVerify_N${nodeId}_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(report.startedAtMs)) + ".txt"
        scope.launch(Dispatchers.IO) {
            try {
                DocuAndManageFun().createExperimentFileWriter(context, fileName, "text/plain").use { w ->
                    w.write(0xFEFF)   // UTF-8 BOM，Windows 记事本/Excel 直接识别中文
                    w.write(report.toText())
                }
                viewModel.writeSaveNotice.postValue(false to "验证报告已保存：$fileName")
                appendLog("✓ 报告已保存：$fileName")
            } catch (e: Exception) {
                viewModel.writeSaveNotice.postValue(true to "验证报告保存失败：${e.message}")
                appendLog("✗ 报告保存失败：${e.message}")
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("YZ-MOTOR 验证", fontWeight = FontWeight.Bold, color = YZ_BLUE)
            Text(
                "把电机核心功能与已知的「EDS vs 固件」不符点逐项实测：A 只读核对（无副作用，作业中可跑）；" +
                    "B 状态机核对（写控制字，不动）；C 运动核对（小行程往返 + 低速点动，电机会动）。" +
                    "报告里的 ≠ 项就是电机与 EDS/标准不一致的实机证据，复制后可直接贴回讨论。",
                fontSize = 12.sp, color = Color.Gray
            )
            Text(
                "运行 C 组前务必：机具悬空、远离丝杆两端限位；位移量默认 6554 脉冲 ≈ 1mm（5mm 导程）。" +
                    "未标定限位时默认正向运动。",
                fontSize = 12.sp, color = YZ_ORANGE
            )

            // ── 选项 ────────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("旁听", fontSize = 12.sp)
                listOf(3000L, 5000L).forEach { ms ->
                    val selected = listenMs == ms
                    OutlinedButton(
                        onClick = { listenMs = ms },
                        enabled = !running,
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = if (selected) Color(0xFFE3F2FD) else Color.Transparent
                        ),
                        border = BorderStroke(1.dp, if (selected) YZ_BLUE else Color(0xFFBDBDBD))
                    ) { Text("${ms / 1000}s", fontSize = 12.sp) }
                }
                OutlinedTextField(
                    value = travelInput,
                    onValueChange = { travelInput = it; armedGroups = null },
                    label = { Text("C 组位移量（脉冲）", fontSize = 11.sp) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(150.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { includeRpdo = !includeRpdo; armedGroups = null },
                    enabled = !running,
                    colors = ButtonDefaults.outlinedButtonColors(
                        backgroundColor = if (includeRpdo) Color(0xFFFFF3E0) else Color.Transparent
                    ),
                    border = BorderStroke(1.dp, if (includeRpdo) YZ_ORANGE else Color(0xFFBDBDBD))
                ) { Text(if (includeRpdo) "C5 RPDO1 单帧试验：开" else "C5 RPDO1 单帧试验：关", fontSize = 12.sp) }
                Text(
                    "开启后 C 组会向 0x200+ID 发 3 帧 RPDO；若可用，位置命令可从 5 帧 SDO 变 1 帧 PDO",
                    fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f)
                )
            }

            // ── 按钮 ────────────────────────────────────────────────────────
            val readOnlyEnabled = !busy && !sampling && !running
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { requestRun(listOf(Group.A)) },
                    enabled = readOnlyEnabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(backgroundColor = YZ_BLUE)
                ) { Text("① 只读核对", color = Color.White, fontSize = 12.sp) }
                Button(
                    onClick = { requestRun(listOf(Group.B)) },
                    enabled = writesEnabled && !running,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(backgroundColor = YZ_GREY)
                ) { Text("② 状态机核对", color = Color.White, fontSize = 12.sp) }
                Button(
                    onClick = { requestRun(listOf(Group.C)) },
                    enabled = writesEnabled && !running,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (armedGroups == listOf(Group.C)) YZ_RED else YZ_ORANGE
                    )
                ) { Text(if (armedGroups == listOf(Group.C)) "确认运动" else "③ 运动核对", color = Color.White, fontSize = 12.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val all = listOf(Group.A, Group.B, Group.C)
                Button(
                    onClick = { requestRun(all) },
                    enabled = writesEnabled && !running,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (armedGroups == all) YZ_RED else Color(0xFF00695C)
                    )
                ) { Text(if (armedGroups == all) "确认全部运行（含运动）" else "全部运行 A→B→C", color = Color.White, fontSize = 12.sp) }
                if (running) {
                    Button(
                        onClick = { job?.cancel() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = YZ_RED)
                    ) { Text("停止", color = Color.White, fontSize = 12.sp) }
                    CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 3.dp)
                }
            }
            if (running && progressText.isNotEmpty()) {
                Text("进行中：$progressText（已完成 ${items.size} 项）", fontSize = 12.sp, color = YZ_BLUE)
            }
            if (isSystemRunning) {
                Text("作业运行中：只允许 ① 只读核对。", fontSize = 11.sp, color = YZ_RED)
            }

            // ── 结果 ────────────────────────────────────────────────────────
            if (items.isNotEmpty()) {
                Divider(color = Color(0xFFEEEEEE))
                val report = buildReport()
                Text(report.summary(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF263238))
                Group.values().forEach { g ->
                    val inGroup = items.filter { it.group == g }
                    if (inGroup.isEmpty()) return@forEach
                    Text("${g.title}（${g.subtitle}）", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = YZ_BLUE)
                    inGroup.forEach { item -> VerifyItemRow(item) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { copyReport() },
                        enabled = !running,
                        colors = ButtonDefaults.buttonColors(backgroundColor = YZ_BLUE)
                    ) { Text("复制报告", color = Color.White, fontSize = 12.sp) }
                    OutlinedButton(onClick = { saveReport() }, enabled = !running) {
                        Text("保存文件", fontSize = 12.sp)
                    }
                }
                Text(
                    "报告保存到实验数据目录：yzVerify_N${nodeId}_日期时间.txt（不在实验数据列表中显示，用文件管理器查看）。",
                    fontSize = 11.sp, color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun VerifyItemRow(item: CheckItem) {
    val color = when (item.verdict) {
        Verdict.PASS -> YZ_GREEN
        Verdict.DIFF -> YZ_ORANGE
        Verdict.FAIL -> YZ_RED
        Verdict.INFO -> YZ_GREY
        Verdict.SKIP -> Color.Gray
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("[${item.id}] ${item.title}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${item.verdict.symbol} ${item.verdict.label}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
        }
        item.expected?.let {
            Text("期望：$it", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF455A64))
        }
        Text("实测：${item.observed}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF263238))
        item.note?.let {
            Text("说明：$it", fontSize = 11.sp, color = color)
        }
        Divider(color = Color(0xFFE0E0E0))
    }
}
