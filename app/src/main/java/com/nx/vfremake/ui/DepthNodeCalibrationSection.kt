/** @author NIANXI @date 2026-11-04 @brief 检测节点标定区；独立于伺服限位和深度换算。 */
package com.nx.vfremake.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.nx.vfremake.BuildConfig
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

/** 仅本区管理节点标定；不发送伺服运动命令，也不写targetDepth。 */
@Composable
internal fun DepthNodeCalibrationSection(viewModel: VariableFertViewModel, row: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.depthNodeState.observeAsState(DepthNodeState())
    val recording by viewModel.depthRecordingState.observeAsState(false)
    val channel = state.channels[row]
    val session = remember(viewModel) { DepthNodeCalibrationSession(viewModel.depthNodeRuntime) }
    val heights = remember(row) { mutableStateListOf<String>().also { list ->
        if (channel.calibrationPoints.isEmpty()) list.addAll(listOf("0", "20", "40", "60", "80", "100"))
        else list.addAll(channel.calibrationPoints.map { it.second.toString() })
    } }
    val captures = remember(row) { mutableStateMapOf<Int, DepthCalibrationFit.Capture>().also { map ->
        channel.calibrationPoints.forEachIndexed { i, point -> map[i] = DepthCalibrationFit.Capture(
            point.first, channel.calibrationStdMv.getOrNull(i) ?: Float.NaN, 0) }
    } }
    var order by remember(row) { mutableStateOf(channel.fitOrder) }
    var fit by remember(row) { mutableStateOf(if (channel.fitValid)
        DepthCalibrationFit.fit(channel.calibrationPoints, channel.fitOrder).getOrNull() else null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var verifyInput by remember { mutableStateOf("30") }
    var zeroInput by remember { mutableStateOf("0") }
    var stopA by remember { mutableStateOf<Float?>(null) }
    var stopB by remember { mutableStateOf<Float?>(null) }
    var pendingImport by remember { mutableStateOf<DepthNodeCalibIoFun.FileDto?>(null) }
    val canCapture = channel.isOnline && channel.versionCompatible && channel.quality != DepthNodeQuality.FAULT && !busy && !recording

    fun action(block: suspend () -> Unit) {
        if (viewModel.isDepthRecording()) { notice = "请先停止记录，再修改标定"; return }
        busy = true; notice = "处理中…"
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }

    fun persist(updater: (DepthNodeChannel) -> DepthNodeChannel) {
        val now = viewModel.depthNodeRuntime.nowMs()
        viewModel.updateDepthNodeChannel(row) { DepthNodeRuntime.refresh(updater(it), now) }
        if (!viewModel.currentDepthNodeState().simulated) MySharedPreFun(context)
            .saveDepthNodeCalibration(viewModel.currentDepthNodeState().channels[row])
    }

    fun points(): List<Pair<Float, Float>> = captures.toSortedMap().map { (index, capture) ->
        val height = heights[index].toFloatOrNull()
        require(height != null && height.isFinite() && height >= 0f) { "点${index + 1}高度无效" }
        capture.meanMv to height
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) action {
            val json = DepthNodeCalibIoFun.encode(viewModel.currentDepthNodeState().channels[row], BuildConfig.VERSION_NAME, Instant.now().toString())
            withContext(Dispatchers.IO) {
                checkNotNull(context.contentResolver.openOutputStream(uri)) { "无法创建标定文件" }
                    .bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            }
            notice = "节点标定已导出"
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action {
            val json = withContext(Dispatchers.IO) {
                checkNotNull(context.contentResolver.openInputStream(uri)) { "无法读取标定文件" }
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            pendingImport = DepthNodeCalibIoFun.decode(json).getOrThrow()
            notice = "文件校验通过，请核对后导入"
        }
    }

    Card(Modifier.fillMaxWidth(), elevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("步骤3：检测节点标定", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (recording) Text("记录进行中，请停止记录后修改标定。", color = Color.Red, fontSize = 18.sp)
            Text("第${row + 1}行 · 基址 0x${channel.baseId.toString(16).uppercase()} · ${DepthDisplayFormat.qualityLabel(channel.quality)}",
                color = if (canCapture) Color(0xFF237A35) else Color(0xFFC62828), fontSize = 18.sp)
            Text("节点输出传感器端子电压。摆臂、传感器和节点需成套重新标定；不使用旧编码器标定。", fontSize = 16.sp)
            Text("滤波 ${channel.uFiltMv?.let { "%.1f".format(Locale.ROOT, it) } ?: "--"} mV · 周期均值 ${channel.uMeanMv?.let { "%.1f".format(Locale.ROOT, it) } ?: "--"} mV · 极差 ${channel.spanMv ?: "--"} mV", fontSize = 18.sp)
            if (!channel.isOnline) Text("节点离线：检查接线和地址；未配号设备请先到设置页“播深检测节点管理”。", color = Color.Red, fontSize = 16.sp)
            if (state.simulated) Text("测试模式：采点时按该挡块高度保持模拟信号；标定仅保存在本次模拟会话，不覆盖真实标定。", color = Color(0xFF9C6500), fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(1, 2).forEach { degree -> OutlinedButton(enabled = !busy, onClick = { order = degree; fit = null }) {
                    Text((if (order == degree) "✓ " else "") + "${degree}次拟合", fontSize = 16.sp)
                } }
            }
            Text("① 多点采集（每点2秒，标准差须≤0.3mm）", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            heights.indices.forEach { index ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = heights[index], enabled = !busy,
                        onValueChange = { heights[index] = it; fit = null },
                        label = { Text("点${index + 1}高度mm") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(160.dp))
                    val capture = captures[index]
                    Text(capture?.let { "%.2f mV  σ=%.3f mV".format(Locale.ROOT, it.meanMv, it.stdMv) } ?: "待采集",
                        modifier = Modifier.weight(1f), fontSize = 16.sp)
                    Button(enabled = canCapture, onClick = {
                        action {
                            val height = heights[index].toFloatOrNull()
                            require(height != null && height.isFinite() && height >= 0f) { "请输入有效挡块高度" }
                            viewModel.depthNodeSimulator?.holdDepth(row, height)
                            val captured = session.capture(row)
                            captures[index] = captured; fit = null
                            notice = "点${index + 1}采集完成：${captured.count}帧" + if (!channel.fitValid) "，稳定性将在拟合后统一检查" else ""
                        }
                    }) { Text("采集2秒", fontSize = 16.sp) }
                    OutlinedButton(enabled = !busy && capture != null, onClick = { captures.remove(index); fit = null }) { Text("清除") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !busy && captures.size >= order + 2, onClick = {
                    try {
                        val ps = points()
                        val result = DepthCalibrationFit.fit(ps, order).getOrThrow()
                        val bad = DepthCalibrationFit.unstableIndices(result, ps.map { it.first }, captures.toSortedMap().values.map { it.stdMv })
                        check(bad.isEmpty()) { "读数不稳：第${bad.map { it + 1 }}个已采点超过0.3mm，请重采" }
                        fit = result; notice = "拟合完成，请核对残差并使用独立挡块验证"
                    } catch (e: Exception) { fit = null; notice = e.message ?: "拟合失败" }
                }) { Text("计算拟合", fontSize = 16.sp) }
                Button(enabled = !busy && !recording && fit != null, onClick = {
                    val result = fit ?: return@Button
                    val ps = points(); val deviations = captures.toSortedMap().values.map { it.stdMv }
                    val stampedAt = Instant.now().toString()
                    persist { it.copy(calibrationPoints = ps, calibrationStdMv = deviations,
                        fitOrder = result.order, c0 = result.c0, c1 = result.c1, c2 = result.c2,
                        fitValid = true, fitMaxResidualMm = result.maxResidualMm,
                        calibratedAt = stampedAt, offsetTrimMm = 0f, zeroCheckedAt = "") }
                    notice = "检测节点标定已保存"; viewModel.depthNodeSimulator?.holdDepth(row, null)
                }) { Text("保存标定", fontSize = 16.sp) }
            }
            fit?.let { result ->
                Text("h = %.8g + %.8g·U + %.8g·U²".format(Locale.ROOT, result.c0, result.c1, result.c2), fontSize = 16.sp)
                Text("R²=%.6f  最大残差=%.3f mm".format(Locale.ROOT, result.rSquared, result.maxResidualMm), fontSize = 18.sp,
                    color = if (result.maxResidualMm <= 2f) Color(0xFF237A35) else Color.Red)
                Text("各点残差(mm)：" + result.residualsMm.joinToString(" / ") { "%.3f".format(Locale.ROOT, it) }, fontSize = 16.sp)
            }
            if (channel.fitValid) Text("已保存：${channel.fitOrder}次 · 最大残差${channel.fitMaxResidualMm}mm · ${channel.calibratedAt}", fontSize = 16.sp)
            Divider()
            Text("② 独立验证点（不参与拟合）", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(verifyInput, { verifyInput = it }, label = { Text("验证挡块mm") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(180.dp))
                val expected = verifyInput.toFloatOrNull()
                val measured = viewModel.measuredDepthFacade.forRow(row).rawDepthMm
                Text("验证误差：" + (if (expected != null && measured != null) "%+.2f mm".format(Locale.ROOT, measured - expected) else "--"), fontSize = 20.sp)
                if (state.simulated) OutlinedButton(enabled = !busy, onClick = {
                    runCatching { viewModel.depthNodeSimulator?.holdDepth(row, verifyInput.toFloat()) }.onFailure { notice = "验证高度须为0–100mm" }
                }) { Text("模拟放置挡块") }
            }
            Divider()
            Text("③ 零点复核（仅修正平板偏移）", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(zeroInput, { zeroInput = it }, label = { Text("复核深度mm") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(180.dp))
                Button(enabled = canCapture && channel.fitValid, onClick = { action {
                    val expected = zeroInput.toFloatOrNull(); require(expected != null && expected.isFinite() && expected >= 0f) { "复核深度无效" }
                    viewModel.depthNodeSimulator?.holdDepth(row, expected)
                    val capture = session.capture(row)
                    val stampedAt = Instant.now().toString()
                    persist { current -> current.copy(offsetTrimMm = current.offsetTrimMm + expected -
                        requireNotNull(DepthNodeCalib.depthFromMv(capture.meanMv, current)), zeroCheckedAt = stampedAt) }
                    notice = "零点偏移已更新"; viewModel.depthNodeSimulator?.holdDepth(row, null)
                } }) { Text("采集并修正", fontSize = 16.sp) }
                Text("当前修正：%.2f mm".format(Locale.ROOT, channel.offsetTrimMm), fontSize = 16.sp)
            }
            Divider()
            Text("④ 两端止点（分别压到两端后采集）", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = canCapture && channel.fitValid, onClick = { action {
                    viewModel.depthNodeSimulator?.holdDepth(row, 0f); stopA = session.capture(row).meanMv; notice = "止点A已采集"
                } }) { Text("采集止点A：${stopA?.roundToInt() ?: "--"}mV") }
                Button(enabled = canCapture && channel.fitValid, onClick = { action {
                    viewModel.depthNodeSimulator?.holdDepth(row, 100f); stopB = session.capture(row).meanMv; notice = "止点B已采集"
                } }) { Text("采集止点B：${stopB?.roundToInt() ?: "--"}mV") }
                Button(enabled = canCapture && stopA != null && stopB != null, onClick = { action {
                    val lo = minOf(stopA!!, stopB!!).roundToInt(); val hi = maxOf(stopA!!, stopB!!).roundToInt()
                    require(lo > 0 && hi > lo && hi <= 5000) { "止点电压须不同并在有效范围" }
                    viewModel.depthNodeClient.write(channel.baseId, 12, lo.toLong())
                    check(viewModel.depthNodeClient.read(channel.baseId, sub = 12) == lo.toLong()) { "低止点回读不一致" }
                    viewModel.depthNodeClient.write(channel.baseId, 13, hi.toLong())
                    check(viewModel.depthNodeClient.read(channel.baseId, sub = 13) == hi.toLong()) { "高止点回读不一致" }
                    viewModel.depthNodeClient.burn(channel.baseId)
                    persist { it.copy(stopLoMv = lo, stopHiMv = hi) }
                    notice = "止点已写入并保存节点Flash"; viewModel.depthNodeSimulator?.holdDepth(row, null)
                } }) { Text("写入止点并保存", fontSize = 16.sp) }
            }
            if (channel.fitValid) Text("浅端对应：" + (if (DepthNodeCalib.derivative(channel.uMeanMv ?: 2500f, channel) > 0) "低电压止点" else "高电压止点") +
                "；STOP按电压定义，不改变伺服限位。", fontSize = 16.sp)
            Divider()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !busy && channel.fitValid, onClick = {
                    exportLauncher.launch("depthNodeCalib_row${row + 1}.json")
                }) { Text("导出节点标定JSON", fontSize = 16.sp) }
                OutlinedButton(enabled = !busy && !recording, onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }) {
                    Text("导入节点标定JSON", fontSize = 16.sp)
                }
                if (state.simulated) OutlinedButton(enabled = !busy, onClick = {
                    viewModel.depthNodeSimulator?.holdDepth(row, null); notice = "模拟信号已恢复跟随目标"
                }) { Text("恢复模拟作业") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (notice.isNotEmpty()) Text(notice, fontSize = 18.sp, color = Color(0xFF37474F))
        }
    }
    pendingImport?.let { file -> AlertDialog(onDismissRequest = { pendingImport = null },
        title = { Text("导入检测节点标定") },
        text = { Text("将来源第${(file.sourceRow ?: 0) + 1}行的标定应用到第${row + 1}行，保留目标地址。\n导入只恢复平板标定；节点止点需要重新写入。") },
        confirmButton = { TextButton(onClick = {
            persist { DepthNodeCalibIoFun.apply(file, it) }
            val saved = viewModel.currentDepthNodeState().channels[row]
            heights.clear(); heights.addAll(saved.calibrationPoints.map { it.second.toString() })
            captures.clear(); saved.calibrationPoints.forEachIndexed { i, point -> captures[i] =
                DepthCalibrationFit.Capture(point.first, saved.calibrationStdMv[i], 0) }
            order = saved.fitOrder; fit = null; pendingImport = null; notice = "节点标定已导入"
        }) { Text("确认导入") } }, dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } }) }
}
