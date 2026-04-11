package com.nx.vfremake.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
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
import com.nx.vfremake.funClass.TableRow

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DantiFertSettings(onClickBack: () -> Unit = {}) {
    val context = LocalContext.current
    val tableRowsRemember = remember { mutableStateOf(listOf<TableRow>()) }

    LaunchedEffect(Unit) {
        val helper = MydantiFertSharedPre(context)
        val existingRows = helper.getTableRows()
        val existingIds = existingRows.map { it.id }

        for (i in 0..8) {
            if (i !in existingIds) {
                helper.saveTableRow(i, TableRow(i, "0.0", "0.0"))
            }
        }
        tableRowsRemember.value = helper.getTableRows().sortedBy { it.id }
    }

    var showDialog by remember { mutableStateOf(false) }
    var editingRowId by remember { mutableStateOf(0) }
    var aState by remember { mutableStateOf("") }
    var bState by remember { mutableStateOf("") }

    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.padding(16.dp)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = if (editingRowId == 0) "配置全局默认单体" else "配置单体 #$editingRowId",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = aState, onValueChange = { aState = it },
                        label = { Text("参数 a") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bState, onValueChange = { bState = it },
                        label = { Text("参数 b") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { showDialog = false }) { Text("取消", color = Color.Gray) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            val helper = MydantiFertSharedPre(context)
                            val finalA = aState.ifEmpty { "0.0" }
                            val finalB = bState.ifEmpty { "0.0" }

                            // 1. 先保存当前正在修改的这个单体
                            helper.saveTableRow(editingRowId, TableRow(editingRowId, finalA, finalB))

                            // 2. 【核心修复】：如果是修改 0 号全局单体，则强制覆盖 1 到 8 号所有单体！
                            if (editingRowId == 0) {
                                for (i in 1..8) {
                                    helper.saveTableRow(i, TableRow(i, finalA, finalB))
                                }
                            }

                            // 3. 重新读取数据并刷新界面列表
                            tableRowsRemember.value = helper.getTableRows().sortedBy { it.id }
                            showDialog = false
                        }) { Text("保存") }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
        TopAppBar(
            title = { Text("单体参数设置 (长按修改)", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onClickBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
            },
            backgroundColor = Color(0xFF1976D2), elevation = 4.dp
        )

        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            items(tableRowsRemember.value) { row ->
                Card(
                    shape = RoundedCornerShape(12.dp), elevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .combinedClickable(
                            onClick = { /* 短按忽略 */ },
                            onLongClick = {
                                editingRowId = row.id
                                aState = row.a
                                bState = row.b
                                showDialog = true
                            }
                        )
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (row.id == 0) "全局默认单体" else "单体 #${row.id}",
                                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (row.id == 0) Color(0xFF1976D2) else Color(0xFF333333)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(text = "a = ${row.a}", fontSize = 16.sp, color = Color.Gray)
                                Text(text = "b = ${row.b}", fontSize = 16.sp, color = Color.Gray)
                            }
                        }
                        Text(text = "长按修改", fontSize = 14.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
}