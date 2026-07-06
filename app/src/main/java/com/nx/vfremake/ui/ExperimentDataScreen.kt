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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

private enum class ExperimentCsvType(
    val prefix: String,
    val label: String,
    val shortLabel: String,
    val description: String,
    val color: Color
) {
    Fertilizer(
        prefix = "fertMsg_",
        label = "施肥记录",
        shortLabel = "施肥",
        description = "变量施肥作业数据",
        color = Color(0xFF2E7D32)
    ),
    DepthRecord(
        prefix = "depthRec_",
        label = "播深记录",
        shortLabel = "播深",
        description = "播种深度手动记录",
        color = Color(0xFF1565C0)
    ),
    DepthTest(
        prefix = "depthTest_",
        label = "播深测试",
        shortLabel = "测试",
        description = "播种深度一键测试",
        color = Color(0xFFF57C00)
    );
}

private fun ExperimentCsvFile.csvType(): ExperimentCsvType =
    ExperimentCsvType.values().firstOrNull { displayName.startsWith(it.prefix) }
        ?: ExperimentCsvType.Fertilizer

@Composable
private fun ExperimentTypeFilterBar(
    selectedType: ExperimentCsvType?,
    totalCount: Int,
    typeCounts: Map<ExperimentCsvType, Int>,
    onSelect: (ExperimentCsvType?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExperimentTypeFilterButton(
                modifier = Modifier.weight(1f),
                label = "全部记录",
                count = totalCount,
                selected = selectedType == null,
                color = Color(0xFF455A64),
                onClick = { onSelect(null) }
            )
            ExperimentTypeFilterButton(
                modifier = Modifier.weight(1f),
                label = ExperimentCsvType.Fertilizer.label,
                count = typeCounts[ExperimentCsvType.Fertilizer] ?: 0,
                selected = selectedType == ExperimentCsvType.Fertilizer,
                color = ExperimentCsvType.Fertilizer.color,
                onClick = { onSelect(ExperimentCsvType.Fertilizer) }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExperimentTypeFilterButton(
                modifier = Modifier.weight(1f),
                label = ExperimentCsvType.DepthRecord.label,
                count = typeCounts[ExperimentCsvType.DepthRecord] ?: 0,
                selected = selectedType == ExperimentCsvType.DepthRecord,
                color = ExperimentCsvType.DepthRecord.color,
                onClick = { onSelect(ExperimentCsvType.DepthRecord) }
            )
            ExperimentTypeFilterButton(
                modifier = Modifier.weight(1f),
                label = ExperimentCsvType.DepthTest.label,
                count = typeCounts[ExperimentCsvType.DepthTest] ?: 0,
                selected = selectedType == ExperimentCsvType.DepthTest,
                color = ExperimentCsvType.DepthTest.color,
                onClick = { onSelect(ExperimentCsvType.DepthTest) }
            )
        }
    }
}

@Composable
private fun ExperimentTypeFilterButton(
    modifier: Modifier = Modifier,
    label: String,
    count: Int,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val backgroundColor = if (selected) color else Color.White
    val textColor = if (selected) Color.White else Color(0xFF333333)
    val countColor = if (selected) Color.White else color

    Box(
        modifier = modifier
            .height(58.dp)
            .clip(shape)
            .background(backgroundColor)
            .border(BorderStroke(1.dp, if (selected) color else Color(0xFFDADDE2)), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                textAlign = TextAlign.Center
            )
            Text(
                text = "$count 个文件",
                fontSize = 12.sp,
                color = countColor,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ExperimentTypeBadge(type: ExperimentCsvType) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(type.color.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, type.color.copy(alpha = 0.35f)), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = type.shortLabel,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = type.color
        )
    }
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
    var selectedType by remember { mutableStateOf<ExperimentCsvType?>(null) }

    val allFiles = files.orEmpty()
    val typeCounts = ExperimentCsvType.values().associateWith { type ->
        allFiles.count { it.csvType() == type }
    }
    val visibleFiles = selectedType?.let { type ->
        allFiles.filter { it.csvType() == type }
    } ?: allFiles

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
                            text = "共 ${allFiles.size} 个记录文件 · 施肥 ${typeCounts[ExperimentCsvType.Fertilizer] ?: 0} · 播深 ${typeCounts[ExperimentCsvType.DepthRecord] ?: 0} · 测试 ${typeCounts[ExperimentCsvType.DepthTest] ?: 0}",
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

            if (!loading && files != null && allFiles.isNotEmpty()) {
                ExperimentTypeFilterBar(
                    selectedType = selectedType,
                    totalCount = allFiles.size,
                    typeCounts = typeCounts,
                    onSelect = { selectedType = it }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

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
                visibleFiles.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "当前分类暂无记录\n可切换到其他分类查看",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visibleFiles, key = { it.documentUri.toString() }) { file ->
                        val type = file.csvType()
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ExperimentTypeBadge(type)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            modifier = Modifier.weight(1f),
                                            text = file.displayName,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF333333)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "类型：${type.description}",
                                        fontSize = 12.sp,
                                        color = type.color
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
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
