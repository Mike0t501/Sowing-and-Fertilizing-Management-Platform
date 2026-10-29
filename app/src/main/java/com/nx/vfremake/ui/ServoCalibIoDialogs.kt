/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2026年10月14日
 * @file    :ServoCalibIoDialogs.kt
 * @brief   :伺服标定 JSON 导入/导出的 UI 流程（文件选择器 + 备注名/选择/覆盖确认对话框）
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * DepthCalibrationScreen（单体）与 SowingDepthScreen（整机）共用，避免两处各写一套对话框。
 ***********************************************************************************************************
 */

package com.nx.vfremake.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.funClass.DocuAndManageFun
import com.nx.vfremake.funClass.JogSession
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.funClass.ServoCalibIoFun
import com.nx.vfremake.funClass.ServoCalibIoFun.applyTo
import com.nx.vfremake.funClass.ServoCalibUnitDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 页面按钮调用的两个入口 */
class ServoCalibIoActions(val export: () -> Unit, val import: () -> Unit)

/** 待确认的导入：目标电机下标 → 要回填的单体 */
private data class PendingImport(val assignments: Map<Int, ServoCalibUnitDto>, val skipped: List<String>)

/**
 * 伺服标定导入/导出流程。在页面组合中调用一次，对话框随之渲染；按钮调用返回的 actions。
 *
 * @param singleMotor   非 null = 单体模式（标定页）：导出/导入只针对这一路，导入可来自任意来源路号；
 *                      null = 整机模式：导出 [targetIndices] 中已标定的各路，导入按来源路号回填
 * @param targetIndices 整机模式下允许导出/导入的电机下标（当前启用行）
 * @param onImported    导入成功后回调（参数为被覆盖的电机下标），标定页用来跳到步骤 2
 */
@Composable
fun rememberServoCalibIo(
    viewModel: VariableFertViewModel,
    singleMotor: Int?,
    targetIndices: List<Int>,
    onImported: (List<Int>) -> Unit = {}
): ServoCalibIoActions {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val currentTargets by rememberUpdatedState(targetIndices)
    val currentOnImported by rememberUpdatedState(onImported)

    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    var showLabelDialog   by remember { mutableStateOf(false) }
    var labelInput        by remember { mutableStateOf("") }
    var pickCandidates    by remember { mutableStateOf<List<ServoCalibUnitDto>>(emptyList()) }
    var pendingImport     by remember { mutableStateOf<PendingImport?>(null) }
    var errorText         by remember { mutableStateOf<String?>(null) }

    // ── 导出：CreateDocument 选位置/改名 → IO 写入 ───────────────────────────
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = DocuAndManageFun().writeTextToUri(context, uri, json)
            withContext(Dispatchers.Main) {
                if (ok) toast(context, "标定数据已导出")
                else errorText = "导出失败：文件写入出错，请换一个保存位置重试"
            }
        }
    }

    fun launchExport(motors: List<ServoCalibration>, label: String) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        pendingExportJson = ServoCalibIoFun.encode(
            motors.map { ServoCalibIoFun.toUnitDto(it, label) }, exportedAt, appVersionName(context)
        )
        val safeLabel = ServoCalibIoFun.sanitizeLabel(label)
        val name = if (singleMotor != null) {
            "servoCalib_M${singleMotor + 1}" + (if (safeLabel.isNotEmpty()) "_$safeLabel" else "") + "_$stamp.json"
        } else {
            "servoCalib_all_$stamp.json"
        }
        exportLauncher.launch(name)
    }

    // ── 导入：OpenDocument → IO 读取解析 → 选择/确认 ──────────────────────────
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val text = DocuAndManageFun().readTextFromUri(context, uri)
            val result = if (text == null) {
                Result.failure(IllegalArgumentException("无法读取文件（文件不可访问或超过 1MB）"))
            } else {
                ServoCalibIoFun.decode(text)
            }
            withContext(Dispatchers.Main) {
                val file = result.getOrElse {
                    errorText = "导入失败：${it.message}"
                    return@withContext
                }
                val units = file.units.orEmpty()
                if (singleMotor != null) {
                    if (units.size == 1) {
                        pendingImport = PendingImport(mapOf(singleMotor to units[0]), emptyList())
                    } else {
                        pickCandidates = units
                    }
                } else {
                    val targets = currentTargets.toSet()
                    val assignments = mutableMapOf<Int, ServoCalibUnitDto>()
                    val skipped = mutableListOf<String>()
                    units.forEachIndexed { i, u ->
                        val src = u.sourceMotorIndex
                        when {
                            src == null -> skipped += "${ServoCalibIoFun.describeUnit(u, i)}：无来源路号"
                            src !in targets -> skipped += "${ServoCalibIoFun.describeUnit(u, i)}：${src + 1} 号未启用"
                            src in assignments -> skipped += "${ServoCalibIoFun.describeUnit(u, i)}：与前一条重复"
                            else -> assignments[src] = u
                        }
                    }
                    if (assignments.isEmpty()) {
                        errorText = "文件中没有可回填到当前启用电机的数据。\n" + skipped.joinToString("\n")
                    } else {
                        pendingImport = PendingImport(assignments, skipped)
                    }
                }
            }
        }
    }

    /** 导入门控：总开关开着时 Phase 4 会立刻按新限位/拟合下发位置命令（突跳）；点动中禁止改限位 */
    fun importBlockedReason(indices: Collection<Int>): String? {
        val st = viewModel.currentSowingDepthState()
        if (st.masterEnabled) return "深度控制总开关已打开，请先关闭总开关再导入标定"
        val jogging = indices.mapNotNull { st.motors.getOrNull(it) }
            .firstOrNull { JogSession.isJogging(it.nodeId) }
        if (jogging != null) return "电机 ${jogging.motorIndex + 1} 正在点动，请先停止点动"
        return null
    }

    fun applyImport(p: PendingImport) {
        importBlockedReason(p.assignments.keys)?.let { errorText = it; return }
        viewModel.updateMultipleServos(
            p.assignments.mapValues { (_, dto) -> { cal: ServoCalibration -> dto.applyTo(cal) } }
        )
        val indices = p.assignments.keys.sorted()
        scope.launch(Dispatchers.IO) {
            val st = viewModel.currentSowingDepthState()
            val prefs = MySharedPreFun(context)
            indices.forEach { idx -> st.motors.getOrNull(idx)?.let { prefs.saveSowingDepthCalibration(it) } }
            withContext(Dispatchers.Main) {
                toast(context, "已导入 ${indices.joinToString("、") { "${it + 1}号" }} 标定，请移动到任一挡位核对实测深度")
                currentOnImported(indices)
            }
        }
    }

    val actions = remember(singleMotor) {
        ServoCalibIoActions(
            export = {
                val st = viewModel.currentSowingDepthState()
                if (singleMotor != null) {
                    val cal = st.motors.getOrNull(singleMotor)
                    if (cal == null || !ServoCalibIoFun.hasExportableData(cal)) {
                        toast(context, "该电机尚未完成限位或拟合标定，无可导出数据")
                    } else {
                        labelInput = ""
                        showLabelDialog = true
                    }
                } else {
                    val motors = currentTargets.mapNotNull { st.motors.getOrNull(it) }
                        .filter { ServoCalibIoFun.hasExportableData(it) }
                    if (motors.isEmpty()) toast(context, "启用的电机均未标定，无可导出数据")
                    else launchExport(motors, "")
                }
            },
            import = {
                val indices = if (singleMotor != null) listOf(singleMotor) else currentTargets
                val blocked = importBlockedReason(indices)
                if (blocked != null) errorText = blocked
                else importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
        )
    }

    // ── 对话框 ───────────────────────────────────────────────────────────────
    if (showLabelDialog && singleMotor != null) {
        AlertDialog(
            onDismissRequest = { showLabelDialog = false },
            title = { Text("导出电机 ${singleMotor + 1} 标定") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("填写单体备注名（如机身编号），用于文件名和归档，可留空。", fontSize = 13.sp)
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it.take(24) },
                        singleLine = true,
                        label = { Text("单体备注名") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showLabelDialog = false
                    val cal = viewModel.currentSowingDepthState().motors.getOrNull(singleMotor)
                    if (cal != null) launchExport(listOf(cal), labelInput)
                }) { Text("选择保存位置") }
            },
            dismissButton = { TextButton(onClick = { showLabelDialog = false }) { Text("取消") } }
        )
    }

    if (pickCandidates.isNotEmpty() && singleMotor != null) {
        AlertDialog(
            onDismissRequest = { pickCandidates = emptyList() },
            title = { Text("文件含 ${pickCandidates.size} 个单体，选择导入到电机 ${singleMotor + 1} 的一个") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    pickCandidates.forEachIndexed { i, u ->
                        OutlinedButton(
                            onClick = {
                                pickCandidates = emptyList()
                                pendingImport = PendingImport(mapOf(singleMotor to u), emptyList())
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                ServoCalibIoFun.describeUnit(u, i) + "\n" +
                                    ServoCalibIoFun.fitSummary(u.fitValid == true, u.fitA ?: 0f, u.fitB ?: 0f),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickCandidates = emptyList() }) { Text("取消") } }
        )
    }

    pendingImport?.let { p ->
        val st = viewModel.currentSowingDepthState()
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("确认覆盖标定") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    p.assignments.toSortedMap().forEach { (idx, u) ->
                        val old = st.motors.getOrNull(idx) ?: return@forEach
                        Text(
                            "电机 ${idx + 1} ← ${ServoCalibIoFun.describeUnit(u)}\n" +
                                "  限位：${ServoCalibIoFun.limitSummary(old.limitsSet, old.limitMin, old.limitMax)}" +
                                " → ${ServoCalibIoFun.limitSummary(u.limitsSet == true, u.limitMin ?: 0, u.limitMax ?: 0)}\n" +
                                "  拟合：${ServoCalibIoFun.fitSummary(old.fitValid, old.fitA, old.fitB)}\n" +
                                "     → ${ServoCalibIoFun.fitSummary(u.fitValid == true, u.fitA ?: 0f, u.fitB ?: 0f)}",
                            fontSize = 13.sp
                        )
                    }
                    if (p.skipped.isNotEmpty()) {
                        Text("跳过：\n" + p.skipped.joinToString("\n"), fontSize = 12.sp, color = Color.Gray)
                    }
                    Text(
                        "⚠ 限位与拟合都是编码器绝对脉冲值，仅在同一单体、伺服未重新设零/未更换电机或丝杆时有效。" +
                            "导入后请移动到一个挡块核对实测深度。",
                        fontSize = 12.sp,
                        color = Color(0xFFB00020)
                    )
                }
            },
            confirmButton = {
                Button(onClick = { pendingImport = null; applyImport(p) }) { Text("确认导入") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } }
        )
    }

    errorText?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text("标定导入/导出") },
            text = { Text(msg) },
            confirmButton = { Button(onClick = { errorText = null }) { Text("确定") } }
        )
    }

    return actions
}

private fun toast(context: Context, msg: String) =
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

private fun appVersionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull() ?: ""
