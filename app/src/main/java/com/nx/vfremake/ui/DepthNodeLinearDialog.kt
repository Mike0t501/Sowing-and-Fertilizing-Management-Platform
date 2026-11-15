package com.nx.vfremake.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.data.DepthNodeChannel
import com.nx.vfremake.data.DepthNodeQuality
import com.nx.vfremake.data.DepthNodeVoltageUnit
import com.nx.vfremake.funClass.DepthNodeCalib
import com.nx.vfremake.funClass.MeasuredDepthSample
import java.util.Locale
import kotlin.math.abs

internal fun externalLinearEquation(channel: DepthNodeChannel): String? {
    val unit = channel.linearVoltageUnit ?: return null
    val sign = if (channel.c0 < 0) "−" else "+"
    return "播深(mm) = ${channel.c1 * unit.mvPerUnit} × 滤波电压(${unit.label}) $sign ${abs(channel.c0)}"
}

@Composable
internal fun DepthNodeLinearDialog(channel: DepthNodeChannel, sample: MeasuredDepthSample, locked: Boolean,
    onDismiss: () -> Unit, onSave: (Double, Double, DepthNodeVoltageUnit) -> Unit) {
    val savedUnit = channel.linearVoltageUnit
    var unit by rememberSaveable(channel.row) { mutableStateOf(savedUnit ?: DepthNodeVoltageUnit.MV) }
    var aText by rememberSaveable(channel.row) { mutableStateOf(savedUnit?.let { (channel.c1 * it.mvPerUnit).toString() }.orEmpty()) }
    var bText by rememberSaveable(channel.row) { mutableStateOf(savedUnit?.let { channel.c0.toString() }.orEmpty()) }
    var reviewing by rememberSaveable(channel.row) { mutableStateOf(false) }
    var error by rememberSaveable(channel.row) { mutableStateOf<String?>(null) }
    val a = DepthNodeCalib.parseCoefficient(aText)
    val b = DepthNodeCalib.parseCoefficient(bText)
    val draft = remember(channel.row, a, b, unit) {
        runCatching {
            require(a != null && b != null) { "请输入斜率 a 和截距 b" }
            DepthNodeCalib.withLinearCoefficients(DepthNodeChannel(channel.row), a, b, unit, "")
        }
    }
    val preview = if (sample.quality == DepthNodeQuality.OK || sample.quality == DepthNodeQuality.UNCALIBRATED)
        sample.uFiltMv?.let { mv -> draft.getOrNull()?.let { DepthNodeCalib.depthFromMv(mv, it) } } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (reviewing) "确认保存第${channel.row + 1}行系数" else "第${channel.row + 1}行 · 输入线性系数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (reviewing) {
                    draft.getOrNull()?.let { Text(externalLinearEquation(it).orEmpty(), fontSize = 17.sp) }
                    if (channel.fitValid) Text("将替换本行标定、清除旧零点修正，并重新开始质量统计。", fontSize = 16.sp)
                } else {
                    Text("播深(mm) = a × 滤波电压(${unit.label}) + b", fontSize = 17.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DepthNodeVoltageUnit.entries.forEach { choice ->
                            OutlinedButton(enabled = !locked, onClick = {
                                if (unit != choice) {
                                    a?.takeIf { it.isFinite() }?.let {
                                        aText = (it / unit.mvPerUnit * choice.mvPerUnit).toString()
                                    }
                                    unit = choice; error = null
                                }
                            }) { Text(if (unit == choice) "✓ ${choice.label}" else choice.label, fontSize = 16.sp) }
                        }
                    }
                    OutlinedTextField(aText, { aText = it; error = null }, label = { Text("斜率 a（mm/${unit.label}）") },
                        enabled = !locked, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    OutlinedTextField(bText, { bText = it; error = null }, label = { Text("截距 b（mm）") },
                        enabled = !locked, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    val previewText = preview?.let { "%.1f mm".format(Locale.ROOT, it) } ?: "—"
                    Text("当前滤波电压 ${voltageText(sample.uFiltMv)} · 播深预览 $previewText", fontSize = 16.sp)
                }
                if (locked) Text("请先停止作业、试验和记录。", color = Color(0xFFC62828), fontSize = 16.sp)
                error?.let { Text(it, color = Color(0xFFC62828), fontSize = 16.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = !locked && (!reviewing || draft.isSuccess), onClick = {
                if (!reviewing) {
                    draft.onSuccess { reviewing = true; error = null }.onFailure { error = it.message }
                } else {
                    runCatching { onSave(requireNotNull(a), requireNotNull(b), unit) }
                        .onFailure { error = it.message ?: "保存失败" }
                }
            }) { Text(if (reviewing) "确认保存" else "核对公式", fontSize = 16.sp) }
        },
        dismissButton = {
            TextButton(onClick = { if (reviewing) { reviewing = false; error = null } else onDismiss() }) {
                Text(if (reviewing) "返回修改" else "取消", fontSize = 16.sp)
            }
        }
    )
}
