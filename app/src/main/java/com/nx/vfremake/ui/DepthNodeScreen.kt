/** @author NIANXI @date 2026-11-04 @brief 播深检测节点管理，不启动伺服运动控制。 */
package com.nx.vfremake.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.R
import com.nx.vfremake.isSystemRunning
import com.nx.vfremake.coroutine.CanReceiveCoroutine
import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale

/** 固定设置页入口；回读与写入分开反馈，选择预设不代表已经生效。 */
@Composable
fun DepthNodeScreen(viewModel: VariableFertViewModel, onBack: () -> Unit, onCalibrate: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.depthNodeState.observeAsState(DepthNodeState())
    val recording by viewModel.depthRecordingState.observeAsState(false)
    val working by viewModel.workRunning.observeAsState(false)
    val trialActive by viewModel.depthTrialActive.observeAsState(false)
    var selectedRow by rememberSaveable { mutableStateOf(0) }
    val channel = state.channels[selectedRow]
    val measurement = viewModel.measuredDepthFacade.forRow(selectedRow, channel)
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(DepthNodePresets.Preset.MEDIAN_EMA) }
    var showPresets by remember { mutableStateOf(false) }
    var reportInput by remember { mutableStateOf("50") }
    var researchRate by remember { mutableStateOf(2) }
    var showRates by remember { mutableStateOf(false) }
    var timeoutInput by remember { mutableStateOf("600") }
    var pendingBudget by remember { mutableStateOf<DepthResearchBudget.Budget?>(null) }
    var secondResearchConfirm by remember { mutableStateOf(false) }
    var showProvision by remember { mutableStateOf(false) }
    var showLinearDialog by rememberSaveable { mutableStateOf(false) }
    var observedRxFps by remember { mutableStateOf(0.0) }
    var observedTxFps by remember { mutableStateOf(0.0) }
    var servo100MsObserved by remember { mutableStateOf(false) }
    val writeEnabled = !busy && !recording && !isSystemRunning && channel.isOnline && channel.versionCompatible
    val calibrationLocked = recording || working || trialActive || isSystemRunning

    DisposableEffect(viewModel) {
        if (MySharedPreFun(context).getSpecificValue(R.string.testMode_Switch_name) != "1") MySerialPortFun.ensureCanPortOpen(context)
        val receiver = CanReceiveCoroutine().also { it.start(viewModel, context) }
        onDispose { receiver.shutdown() }
    }
    LaunchedEffect(selectedRow, channel.parameterWords[9]) { reportInput = (channel.parameterWords[9] ?: 50).toString() }
    LaunchedEffect(viewModel) {
        var previous = CanLinkStats.snapshot()
        while (true) {
            delay(1000)
            val now = viewModel.depthNodeRuntime.nowMs()
            val ids = viewModel.currentSowingDepthState().motors.filter { it.isOnline }.map { it.nodeId }
            val traffic = viewModel.depthBusTrafficMeter.snapshot(now, ids)
            observedRxFps = traffic.rxFps
            servo100MsObserved = traffic.servo100MsObserved
            val current = CanLinkStats.snapshot()
            observedTxFps = (current.txFrames - previous.txFrames).coerceAtLeast(0) * 1000.0 /
                (current.atMs - previous.atMs).coerceAtLeast(1)
            previous = current
        }
    }

    fun perform(writes: Boolean = true, block: suspend () -> Unit) {
        if (writes && (viewModel.isDepthRecording() || isSystemRunning)) { notice = "请先停止作业和记录，再修改节点配置"; return }
        busy = true; notice = "处理中…"
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }

    fun budget(): DepthResearchBudget.Budget {
        val current = viewModel.currentDepthNodeState()
        val channels = current.channels + listOf(current.factory).filter { it.isOnline }
        return DepthResearchBudget.calculate(channels, researchRate,
            if (current.simulated) 0.0 else observedRxFps,
            if (current.simulated) 0.0 else observedTxFps, current.simulated || servo100MsObserved,
            if (current.simulated) 0.0 else current.channels[selectedRow].researchFps.toDouble())
    }

    fun enableResearch() {
        val checkedBudget = budget()
        pendingBudget = null; secondResearchConfirm = false
        perform {
            val timeout = timeoutInput.toIntOrNull(); require(timeout != null && timeout in 0..3600) { "自动关闭时间须为0–3600秒" }
            viewModel.depthNodeManagement.enableResearch(channel.baseId, researchRate, timeout, checkedBudget)
            notice = "研究流已开启，完成抓包后请关闭"
        }
    }

    Scaffold(topBar = { MyTopBar("播深检测节点管理", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("0–5 V 角度传感器 + dingoFW 变送节点", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (state.simulated) Text("测试模式：合成帧走真实解析与配置路径，标定与真实数据隔离。", color = Color(0xFF9C6500), fontSize = 18.sp)
            if (calibrationLocked) Text("作业、试验或记录进行中，可查看实时诊断。", color = Color.Red, fontSize = 18.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..7).forEach { row -> OutlinedButton(enabled = !busy, onClick = { selectedRow = row }) {
                    Text((if (row == selectedRow) "✓ " else "") + "第${row + 1}行", fontSize = 16.sp)
                } }
            }
            Text("当前管理：第${selectedRow + 1}行 / 0x${channel.baseId.toString(16).uppercase()} / ${DepthDisplayFormat.qualityLabel(measurement.quality)}",
                fontSize = 20.sp, color = if (channel.versionCompatible) Color(0xFF1565C0) else Color.Red)
            DepthVoltageReadout(measurement, showMean = true, fontSize = 18.sp)
            externalLinearEquation(channel)?.let { Text("外部系数 · $it", fontSize = 16.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !busy && !calibrationLocked, onClick = { showLinearDialog = true }) {
                    Text("输入线性系数", fontSize = 16.sp)
                }
                OutlinedButton(enabled = !busy && !calibrationLocked, onClick = { onCalibrate(selectedRow) }) {
                    Text("本行节点标定", fontSize = 16.sp)
                }
            }
            if (!channel.versionCompatible) Text("ICD主版本不兼容：已停止解析STATUS，请核对固件。", color = Color.Red, fontSize = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !busy, onClick = { perform(false) {
                    val result = viewModel.depthNodeClient.readAll(channel.baseId)
                    notice = if (result.errors.isEmpty()) "18项参数已回读" else "部分参数回读失败：${result.errors.joinToString("；")}" }
                }) { Text("回读本行参数", fontSize = 16.sp) }
                OutlinedButton(enabled = !busy, onClick = { perform(false) {
                    for (node in viewModel.currentDepthNodeState().channels.filter { it.isOnline }) viewModel.depthNodeClient.readAll(node.baseId)
                    notice = "在线节点参数回读完成"
                } }) { Text("回读所有在线节点", fontSize = 16.sp) }
                OutlinedButton(enabled = !busy, onClick = { perform(false) {
                    val v = viewModel.depthNodeClient.version(channel.baseId); notice = "dingo核心版本：${v.major}.${v.minor}.${v.build}"
                } }) { Text("核心版本", fontSize = 16.sp) }
            }
            Card(Modifier.fillMaxWidth(), elevation = 2.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("滤波配置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("实际回读：${DepthNodePresets.currentName(channel)} · STATUS CRC=${channel.cfgCrc?.let { "0x%02X".format(it) } ?: "--"}", fontSize = 18.sp)
                    Text("当前精简固件可能只支持直出；其他预设若被拒绝，将显示真实回读结果，不会显示已生效。", fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            OutlinedButton(enabled = !busy, onClick = { showPresets = true }) { Text("待应用：${preset.label}", fontSize = 16.sp) }
                            DropdownMenu(showPresets, { showPresets = false }) {
                                DepthNodePresets.Preset.values().forEach { item -> DropdownMenuItem(
                                    enabled = item != DepthNodePresets.Preset.LOW_EMA || channel.fitValid,
                                    onClick = { preset = item; showPresets = false }) { Text(item.label, fontSize = 18.sp) } }
                            }
                        }
                        val expected = runCatching { DepthNodePresets.parameters(preset, channel) }.getOrNull()
                        Text("目标CRC：${expected?.crc?.let { "0x%02X".format(it) } ?: "需先标定"}", fontSize = 16.sp)
                        Button(enabled = writeEnabled && expected != null, onClick = { perform {
                            viewModel.depthNodeClient.applyFilter(channel.baseId, DepthNodePresets.parameters(preset, channel))
                            notice = "9项参数回读与STATUS CRC均一致，预设已生效（尚未Burn）"
                        } }) { Text("应用并核对", fontSize = 16.sp) }
                    }
                    channel.filterParams?.let { p -> Text("电压域分位 ${p.rankPct}% / 窗口 ${p.rankN} / 平滑器 ${p.smoother} / MA ${p.maN} / τ ${p.emaTauS}s / fc ${p.lpfFcHz}Hz / Q ${p.kfQ} / R ${p.kfR} / gate ${p.kfGate}", fontSize = 16.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(reportInput, { reportInput = it }, enabled = !busy && !recording,
                            label = { Text("请求报告周期ms") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.width(200.dp))
                        Text("10–1000ms，实际${channel.reportMs}ms（按10ms取整）", fontSize = 16.sp)
                        Button(enabled = writeEnabled, onClick = { perform {
                            val value = reportInput.toLongOrNull(); require(value != null && value in 10..1000) { "报告周期须为10–1000ms" }
                            viewModel.depthNodeClient.write(channel.baseId, 9, value)
                            check(viewModel.depthNodeClient.read(channel.baseId, sub = 9) == value) { "报告周期回读不一致" }
                            viewModel.depthNodeClient.readAll(channel.baseId); notice = "报告周期已生效，尚未Burn"
                        } }) { Text("应用周期", fontSize = 16.sp) }
                        OutlinedButton(enabled = writeEnabled, onClick = { perform {
                            viewModel.depthNodeClient.burn(channel.baseId); notice = "节点Flash保存成功"
                        } }) { Text("保存到节点Flash", fontSize = 16.sp) }
                    }
                }
            }
            Card(Modifier.fillMaxWidth(), elevation = 2.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("研究流（仅计数，原始抓包使用旁路USB-CAN）", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            val rates = listOf(1000, 500, 250, 100)
                            OutlinedButton(enabled = !busy, onClick = { showRates = true }) { Text("采样率 ${rates[researchRate - 1]} Hz", fontSize = 16.sp) }
                            DropdownMenu(showRates, { showRates = false }) { rates.forEachIndexed { index, hz ->
                                DropdownMenuItem(onClick = { researchRate = index + 1; showRates = false }) { Text("$hz Hz", fontSize = 18.sp) }
                            } }
                        }
                        OutlinedTextField(timeoutInput, { timeoutInput = it }, enabled = !busy && !recording,
                            label = { Text("自动关闭秒（0=不自动）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.width(230.dp))
                        Button(enabled = writeEnabled, onClick = { pendingBudget = budget(); secondResearchConfirm = false }) { Text("开启研究流", fontSize = 16.sp) }
                        OutlinedButton(enabled = !busy && !recording, onClick = { perform {
                            viewModel.depthNodeManagement.disableResearch(channel.baseId); notice = "研究流已关闭"
                        } }) { Text("关闭研究流", fontSize = 16.sp) }
                    }
                    Text("同一时刻最多1个节点开启；8行在线禁开。1000Hz只允许单检测节点，且在线伺服须已观察到约100ms的TPDO周期。", fontSize = 16.sp)
                }
            }
            Card(Modifier.fillMaxWidth(), backgroundColor = Color(0xFFFFF3CD), elevation = 1.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("新节点配号", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("总线上同一时刻只能有一个未配号节点（0x6C0）。目标第${selectedRow + 1}行必须空闲。", color = Color(0xFF8D3600), fontSize = 20.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = !busy && !recording && !isSystemRunning && !channel.isOnline,
                            onClick = { showProvision = true }) { Text("配到第${selectedRow + 1}行并保存", fontSize = 16.sp) }
                        if (state.simulated) {
                            OutlinedButton(enabled = !busy && !recording, onClick = {
                                runCatching { viewModel.depthNodeSimulator?.addFactoryNode() }.onFailure { notice = it.message ?: "接入失败" }
                            }) { Text("模拟接入出厂节点") }
                            OutlinedButton(enabled = !busy && !recording, onClick = { viewModel.depthNodeSimulator?.disconnect(selectedRow); notice = "模拟节点已断开，等待离线判定" }) { Text("模拟拔除本行节点") }
                        }
                    }
                    Text("出厂节点：${if (state.factory.isOnline) "在线" else "离线"} · ICD ${state.factory.icdVer?.let { "0x%02X".format(it) } ?: "--"} · fw ${state.factory.fwRev ?: "--"}", fontSize = 18.sp)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (notice.isNotEmpty()) Text(notice, color = Color(0xFF37474F), fontSize = 18.sp)
            Text("8行实时诊断", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            state.channels.forEach { node -> Card(Modifier.fillMaxWidth(), elevation = 1.dp) {
                val sample = viewModel.measuredDepthFacade.forRow(node.row, node)
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("第${node.row + 1}行  0x${node.baseId.toString(16).uppercase()}  ${DepthDisplayFormat.qualityLabel(sample.quality)}",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (node.versionCompatible) Color(0xFF1565C0) else Color.Red)
                    DepthVoltageReadout(sample, showMean = true, fontSize = 18.sp)
                    Text("ICD ${node.icdVer?.let { "0x%02X".format(it) } ?: "--"} / fw ${node.fwRev ?: "--"} · uptime ${node.uptimeS ?: "--"}s · VDDA ${node.vddaMv ?: "--"}mV · 温度 ${node.mcuTempC ?: "--"}℃ · tx_drop ${node.txDrop ?: "--"}", fontSize = 16.sp)
                    Text("近10s丢帧率 %.3f%% · 本次启动丢帧 ${node.lostFrames} · 研究流 %.1f帧/s".format(Locale.ROOT, node.lossRate10s * 100, node.researchFps), fontSize = 16.sp)
                    Text("CRC ${node.cfgCrc?.let { "0x%02X".format(it) } ?: "--"} · ${DepthNodePresets.currentName(node)} · flags 0x%02X".format(node.flags), fontSize = 16.sp)
                    if (!node.versionCompatible) Text("主版本不兼容，STATUS已停解析", color = Color.Red, fontSize = 18.sp)
                }
            } }
        }
    }

    if (showLinearDialog) DepthNodeLinearDialog(channel, measurement, calibrationLocked,
        onDismiss = { showLinearDialog = false }, onSave = { a, b, unit ->
            val savedAt = Instant.now().toString()
            viewModel.saveDepthNodeCalibration(context, selectedRow) {
                DepthNodeCalib.withLinearCoefficients(it, a, b, unit, savedAt)
            }
            showLinearDialog = false
            notice = "第${selectedRow + 1}行线性系数已保存"
        })

    pendingBudget?.let { calculated -> AlertDialog(onDismissRequest = { pendingBudget = null; secondResearchConfirm = false },
        title = { Text(if (secondResearchConfirm) "多节点研究流二次确认" else "研究流带宽预算") },
        text = { Text(if (secondResearchConfirm) "当前${calculated.onlineNodes}个检测节点在线，仅第${selectedRow + 1}行将开启研究流。请确认旁路抓包连接，其他节点研究流已关闭。"
            else "现有约 %.1f帧/s + 研究流 %.1f帧/s = %.1f帧/s\n预计UART占用 %.1f%% / CAN占用 %.1f%%\n按CSM100T转发研究流估算。\n%s".format(Locale.ROOT,
                calculated.baselineFps, calculated.researchFps, calculated.totalFps, calculated.uartPercent, calculated.canPercent,
                calculated.blockedReason ?: "带宽预算允许，仍需确认后开启"), fontSize = 18.sp) },
        confirmButton = { TextButton(enabled = calculated.blockedReason == null, onClick = {
            if (calculated.onlineNodes > 1 && !secondResearchConfirm) secondResearchConfirm = true else enableResearch()
        }) { Text(if (secondResearchConfirm || calculated.onlineNodes == 1) "确认开启" else "继续确认") } },
        dismissButton = { TextButton(onClick = { pendingBudget = null; secondResearchConfirm = false }) { Text("取消") } }) }
    if (showProvision) AlertDialog(onDismissRequest = { showProvision = false }, title = { Text("确认配号并保存") },
        text = { Text("请确认只有一个0x6C0节点，且第${selectedRow + 1}行没有其他节点。应答将立即转到新地址，随后保存并等待新INFO。", fontSize = 18.sp) },
        confirmButton = { TextButton(onClick = { showProvision = false; perform {
            viewModel.depthNodeClient.provision(selectedRow); notice = "配号、Flash保存和新地址INFO验证均成功"
        } }) { Text("确认配号") } }, dismissButton = { TextButton(onClick = { showProvision = false }) { Text("取消") } })
}
