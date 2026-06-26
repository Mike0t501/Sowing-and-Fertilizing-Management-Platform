package com.nx.vfremake.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nx.vfremake.funClass.MydantiFertSharedPre
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.funClass.TableRow

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DantiFertSettings(onClickBack: () -> Unit = {}) {
    val context = LocalContext.current
    val tableRowsRemember = remember { mutableStateOf(listOf<TableRow>()) }

    LaunchedEffect(Unit) {
        val helper = MydantiFertSharedPre(context)
        val existingIds = helper.getTableRows().map { it.id }
        for (i in 0..16) {
            if (i !in existingIds) helper.saveTableRow(i, TableRow(i, "0.0", "0.0"))
        }
        tableRowsRemember.value = helper.getTableRows().sortedBy { it.id }
    }

    var showDialog by remember { mutableStateOf(false) }
    var editingRowId by remember { mutableStateOf(0) }
    var aState by remember { mutableStateOf("") }
    var bState by remember { mutableStateOf("") }
    var seedRatioState by remember { mutableStateOf("") }
    var seedHoleState by remember { mutableStateOf("") }
    var seedSpacingState by remember { mutableStateOf("") }

    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color.White, modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = when {
                            editingRowId == 0 -> "施肥全局默认"
                            editingRowId <= 8 -> "施肥单体 #$editingRowId"
                            else -> "播种参数"
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (editingRowId <= 8) {
                        OutlinedTextField(
                            value = aState,
                            onValueChange = { aState = it },
                            label = { Text("参数 a") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = bState,
                            onValueChange = { bState = it },
                            label = { Text("参数 b") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = seedRatioState,
                            onValueChange = { seedRatioState = it },
                            label = { Text("传动比") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = seedHoleState,
                            onValueChange = { seedHoleState = it },
                            label = { Text("型孔数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = seedSpacingState,
                            onValueChange = { seedSpacingState = it },
                            label = { Text("株距(cm)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { showDialog = false }) { Text("取消", color = Color.Gray) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            if (editingRowId <= 8) {
                                val helper = MydantiFertSharedPre(context)
                                val finalA = aState.ifEmpty { "0.0" }
                                val finalB = bState.ifEmpty { "0.0" }
                                helper.saveTableRow(editingRowId, TableRow(editingRowId, finalA, finalB))
                                if (editingRowId == 0) {
                                    for (i in 1..8) helper.saveTableRow(i, TableRow(i, finalA, finalB))
                                }
                                tableRowsRemember.value = helper.getTableRows().sortedBy { it.id }
                            } else {
                                MySharedPreFun(context).getMySharedPre().edit()
                                    .putString("seed_transmission_ratio", seedRatioState.ifEmpty { "1.0" })
                                    .putString("seed_hole_count", seedHoleState.ifEmpty { "12" })
                                    .putString("seed_plant_spacing_cm", seedSpacingState.ifEmpty { "20" })
                                    .apply()
                                MySharedPreFun(context).initSettingsParam()
                            }
                            showDialog = false
                        }) { Text("保存") }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
        TopAppBar(
            title = { Text("单体设置 (长按修改)", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onClickBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
            },
            backgroundColor = Color(0xFF1976D2),
            elevation = 4.dp
        )

        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            items(tableRowsRemember.value) { row ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    elevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                editingRowId = row.id
                                aState = row.a
                                bState = row.b
                                val sp = MySharedPreFun(context).getMySharedPre()
                                seedRatioState = sp.getString("seed_transmission_ratio", "1.0") ?: "1.0"
                                seedHoleState = sp.getString("seed_hole_count", "12") ?: "12"
                                seedSpacingState = sp.getString("seed_plant_spacing_cm", "20") ?: "20"
                                showDialog = true
                            }
                        )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    row.id == 0 -> "施肥全局默认"
                                    row.id <= 8 -> "施肥单体 #${row.id}"
                                    else -> "播种单体 #${row.id - 8}"
                                },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (row.id == 0) Color(0xFF1976D2) else Color(0xFF333333)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (row.id <= 8) {
                                    Text("a = ${row.a}", fontSize = 16.sp, color = Color.Gray)
                                    Text("b = ${row.b}", fontSize = 16.sp, color = Color.Gray)
                                } else {
                                    val sp = MySharedPreFun(context).getMySharedPre()
                                    Text("传动比 ${sp.getString("seed_transmission_ratio", "1.0")}", fontSize = 14.sp, color = Color.Gray)
                                    Text("型孔 ${sp.getString("seed_hole_count", "12")}", fontSize = 14.sp, color = Color.Gray)
                                    Text("株距 ${sp.getString("seed_plant_spacing_cm", "20")}cm", fontSize = 14.sp, color = Color.Gray)
                                }
                            }
                        }
                        Text("长按修改", fontSize = 14.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}
