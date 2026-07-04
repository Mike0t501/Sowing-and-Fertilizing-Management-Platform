/**
 ***********************************************************************************************************
 * @brief   :实验数据记录界面
 * ---------------------------------------------------------------------------------------------------------
 * 列出保存目录下的实验数据CSV文件（fertMsg_*.csv），展示文件名、保存时间、大小、数据行数，
 * 支持删除（作业运行中拦截）。行数在后台逐文件统计，未出结果前显示"统计中…"。
 ***********************************************************************************************************
 */
package com.nx.vfremake.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.R
import com.nx.vfremake.funClass.DocuAndManageFun
import com.nx.vfremake.funClass.ExperimentCsvFile
import com.nx.vfremake.isSystemRunning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private var backPressedTime: Long = 0

/** 文件大小格式化：B / KB / MB */
private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
fun ExperimentDataScreen(onClickBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val numFont = FontFamily(Font(R.font.youshehaoshenti))

    // files == null 且不在加载中 → 未设置保存目录（或授权已失效）
    var files by remember { mutableStateOf<List<ExperimentCsvFile>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val rowCounts = remember { mutableStateMapOf<String, Int>() }
    var dirPath by remember { mutableStateOf<String?>(null) }

    val showDeleteDialog = remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ExperimentCsvFile?>(null) }

    LaunchedEffect(refreshTick) {
        loading = true
        rowCounts.clear()
        val fn = DocuAndManageFun()
        val list = withContext(Dispatchers.IO) {
            val dirUri = fn.getWriteDirDocumentUri(context)
            dirPath = dirUri?.let { fn.getPathFromContentUri(context, it) ?: it.path }
            fn.listExperimentCsvFiles(context)
        }
        files = list
        loading = false
        // 行数统计耗时随文件增大，列表先展示，后台逐文件补齐
        list?.forEach { f ->
            val cnt = withContext(Dispatchers.IO) { fn.countCsvDataRows(context, f.documentUri) }
            rowCounts[f.displayName] = cnt
        }
    }

    Scaffold(
        topBar = {
            MyTopBar(title = "实验数据记录", onClickBack = {
                val now = System.currentTimeMillis()
                if (now - backPressedTime < 1000) return@MyTopBar
                backPressedTime = now
                onClickBack()
            })
        },
        backgroundColor = Color(0xFFF5F7FA)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // ── 顶部信息卡：保存目录 + 文件总数 + 刷新 ──────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = 2.dp,
                backgroundColor = Color.White
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "保存目录：${dirPath ?: "未设置"}",
                            fontSize = 13.sp,
                            color = Color(0xFF666666)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "共 ${files?.size ?: 0} 个记录文件",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF333333)
                        )
                    }
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = Color(0xFF4CAF50)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                // ── 加载中 ─────────────────────────────────────────────────
                loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF4CAF50))
                }
                // ── 未设置保存目录 / 授权失效 ──────────────────────────────
                files == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "尚未设置保存目录\n开启\"保存实验数据\"后，首次开始作业时选择保存文件夹",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }
                // ── 目录内无记录 ───────────────────────────────────────────
                files!!.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无实验数据记录\n开启\"保存实验数据\"并完成一次作业后可在此查看",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }
                // ── 文件列表 ───────────────────────────────────────────────
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files!!, key = { it.documentUri.toString() }) { file ->
                        val timeText = SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
                        ).format(Date(file.lastModified))
                        val rowCount = rowCounts[file.displayName]
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                            elevation = 2.dp,
                            backgroundColor = Color.White
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.displayName,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF333333)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "保存时间：$timeText",
                                        fontSize = 12.sp,
                                        color = Color(0xFF666666)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = formatFileSize(file.sizeBytes),
                                            fontSize = 13.sp,
                                            fontFamily = numFont,
                                            color = Color(0xFF1B7F4D)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = when {
                                                rowCount == null -> "统计中…"
                                                rowCount < 0 -> "行数读取失败"
                                                else -> "$rowCount 条记录"
                                            },
                                            fontSize = 13.sp,
                                            fontFamily = numFont,
                                            color = if (rowCount != null && rowCount < 0)
                                                Color(0xFFD32F2F) else Color(0xFF1B7F4D)
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    if (isSystemRunning) {
                                        Toast.makeText(context, "作业进行中，无法删除", Toast.LENGTH_SHORT).show()
                                    } else {
                                        pendingDelete = file
                                        showDeleteDialog.value = true
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "删除",
                                        tint = Color(0xFFD32F2F)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 删除确认对话框 ──────────────────────────────────────────────────
    if (showDeleteDialog.value) {
        ShowConfirmDialog(
            title = "删除确认",
            text = "确定删除 ${pendingDelete?.displayName ?: ""} 吗？删除后无法恢复。",
            onConfirm = {
                val target = pendingDelete ?: return@ShowConfirmDialog
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        DocuAndManageFun().deleteExperimentCsvFile(context, target.documentUri)
                    }
                    Toast.makeText(
                        context, if (ok) "已删除 ${target.displayName}" else "删除失败", Toast.LENGTH_SHORT
                    ).show()
                    if (ok) refreshTick++
                }
            },
            showDialog = showDeleteDialog
        )
    }
}
