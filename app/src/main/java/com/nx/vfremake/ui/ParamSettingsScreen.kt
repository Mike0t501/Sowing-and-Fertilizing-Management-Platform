/**
 ***********************************************************************************************************
 * @author  :NIANXI (Modified with Interactive Tractor UI)
 * @date    :2024年7月12日22:40:23
 * @file    :
 * @brief   :参数设置页面
 * ---------------------------------------------------------------------------------------------------------
 ***********************************************************************************************************
 */
package com.nx.vfremake.ui

import android.content.res.Configuration
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.R
import com.nx.vfremake.funClass.MySharedPreFun

@Composable
fun ParamSettingsScreen(onClickBack: () -> Unit = {}) {
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    if (isPortrait) {
        ParamSettingsPortraitScreen(onClickBack) { ParamSettingsTextSection() }
    } else {
        ParamSettingsLandscapeScreen(onClickBack) { ParamSettingsTextSection() }
    }
    ParamSettingsResetAndApply()
}

/**
 * 横竖屏布局共用参数设置文字部分
 */
/**
 * 横竖屏布局共用参数设置文字部分
 */
/**
 * 横竖屏布局共用参数设置文字部分
 */
/**
 * 横竖屏布局共用参数设置文字部分
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParamSettingsTextSection() {
    val context = LocalContext.current
    val sharedPre = MySharedPreFun(context).getMySharedPre()

    val rowSize = rememberSaveable { mutableStateOf(sharedPre.getString(context.getString(R.string.rowSize_name), "") ?: "") }
    val gnssDistanceVertical = rememberSaveable { mutableStateOf(sharedPre.getString(context.getString(R.string.gnssDistanceVertical_name), "") ?: "") }
    val gnssDistanceHorizontal = rememberSaveable { mutableStateOf(sharedPre.getString(context.getString(R.string.gnssDistanceHorizontal_name), "") ?: "") }
    val lagTime = rememberSaveable { mutableStateOf(sharedPre.getString(context.getString(R.string.lagTime_name), "") ?: "") }
    val forwardSpeed = rememberSaveable { mutableStateOf(sharedPre.getString(context.getString(R.string.forwardSpeed_name), "") ?: "") }
    val fertApplied = rememberSaveable { mutableStateOf(sharedPre.getString(context.getString(R.string.fertApplied_name), "") ?: "") }

    FlowRow(
        verticalArrangement = Arrangement.spacedBy(dimensionResource(id = R.dimen.middle_view_padding)),
    ) {
        TemplatesText_FieldUint(title = "行距RS", value = rowSize.value, uint = "cm") { newText ->
            rowSize.value = newText
            sharedPre.edit().putString(context.getString(R.string.rowSize_name), newText).apply()
        }
        TemplatesText_FieldUint(title = "GNSS距离L1", value = gnssDistanceVertical.value, uint = "cm") { newText ->
            gnssDistanceVertical.value = newText
            sharedPre.edit().putString(context.getString(R.string.gnssDistanceVertical_name), newText).apply()
        }
        TemplatesText_FieldUint(title = "GNSS距离L2", value = gnssDistanceHorizontal.value, uint = "cm") { newText ->
            gnssDistanceHorizontal.value = newText
            sharedPre.edit().putString(context.getString(R.string.gnssDistanceHorizontal_name), newText).apply()
        }
        TemplatesText_FieldUint(title = "滞后时间T", value = lagTime.value, uint = "s") { newText ->
            lagTime.value = newText
            sharedPre.edit().putString(context.getString(R.string.lagTime_name), newText).apply()
        }
        TemplatesText_FieldUint(title = "前进速度", value = forwardSpeed.value, uint = "km/h") { newText ->
            forwardSpeed.value = newText
            sharedPre.edit().putString(context.getString(R.string.forwardSpeed_name), newText).apply()
        }
        TemplatesText_FieldUint(title = "施肥量", value = fertApplied.value, uint = "kg/亩") { newText ->
            fertApplied.value = newText
            sharedPre.edit().putString(context.getString(R.string.fertApplied_name), newText).apply()
        }
    }
}

private var backPressedTime: Long = 0

@Composable
fun ParamSettingsLandscapeScreen(onClickBack: () -> Unit = {}, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            MyTopBar(title = "参数设置", onClickBack = {
                val currentTime = System.currentTimeMillis()
                if (currentTime - backPressedTime < 1000) return@MyTopBar
                backPressedTime = currentTime
                onClickBack()
            })
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier.padding(innerPadding).fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.fillMaxHeight().weight(1f), contentAlignment = Alignment.Center) {
                InteractiveTractorDiagram()
            }
            Box(modifier = Modifier.fillMaxHeight().weight(1f), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

@Composable
fun ParamSettingsPortraitScreen(onClickBack: () -> Unit = {}, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            MyTopBar(title = "参数设置", onClickBack = {
                val currentTime = System.currentTimeMillis()
                if (currentTime - backPressedTime < 1000) return@MyTopBar
                backPressedTime = currentTime
                onClickBack()
            })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth().weight(2f), contentAlignment = Alignment.Center) {
                InteractiveTractorDiagram()
            }
            Box(modifier = Modifier.fillMaxWidth().weight(3f), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

/**
 * 💡 核心组件：可交互的拖拉机大图与等距离交互矩形视图
 */
@Composable
fun InteractiveTractorDiagram() {
    val context = LocalContext.current
    val sharedPre = remember { MySharedPreFun(context).getMySharedPre() }

    // 读取当前的电机启停状态
    val activeMotorsState = remember {
        val str = sharedPre.getString("active_motors_state", "1,1,1,1,1,1,1,1") ?: "1,1,1,1,1,1,1,1"
        val list = str.split(",").map { it == "1" }.toMutableList()
        // 补齐 8 个，防止下标越界
        while (list.size < 8) list.add(true)
        mutableStateListOf(*list.toTypedArray())
    }

    // 读取当前机型行数（4/6/8），决定渲染多少个单体方块
    val rowNumberState = rememberSaveable {
        mutableStateOf(
            (sharedPre.getString(
                context.getString(R.string.rowNumber_name),
                context.getString(R.string.rowNumber_defeatValue)
            ) ?: "4").toIntOrNull()?.coerceIn(1, 8) ?: 4
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // 【修改1】将内部的垂直排列方式设为从底部对齐
        verticalArrangement = Arrangement.Bottom,
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. 拖拉机大图
        Image(
            painter = painterResource(id = R.drawable.ic_user_tractor),
            contentDescription = "拖拉机施肥作业大图",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f), // 依然占据所有剩余空间以防报错
            contentScale = ContentScale.Fit,
            // 【最核心修改】：强制图片内容在它的专属区域内沉底，不让下方产生空白间隙！
            alignment = Alignment.BottomCenter
        )

        // 1.5 机型行数选择（4/6/8）：决定单体数量与单体定位，改后需点“应用”生效
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "机型：",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 8.dp)
            )
            listOf(4, 6, 8).forEach { preset ->
                val selected = rowNumberState.value == preset
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Color(0xFF1976D2) else Color(0xFFE0E0E0))
                        .clickable {
                            rowNumberState.value = preset
                            sharedPre.edit()
                                .putString(context.getString(R.string.rowNumber_name), preset.toString())
                                .apply()
                        }
                        .border(
                            width = 2.dp,
                            color = if (selected) Color(0xFF0D47A1) else Color(0xFFBDBDBD),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${preset}行",
                        color = if (selected) Color.White else Color(0xFF424242),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 2. 按机型行数渲染交互矩形方块
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 【修改2】微调了外边距，让方块离上方图片更紧凑一点
                .padding(top = 8.dp, bottom = 12.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            for (i in 0 until rowNumberState.value) {
                val isActive = activeMotorsState[i]
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(60.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isActive) Color(0xFF4CAF50) else Color(0xFFE53935))
                        .clickable {
                            activeMotorsState[i] = !isActive
                            val newStateStr = activeMotorsState.joinToString(",") { if (it) "1" else "0" }
                            sharedPre.edit().putString("active_motors_state", newStateStr).apply()
                        }
                        .border(
                            width = 2.dp,
                            color = if (isActive) Color(0xFF388E3C) else Color(0xFFB71C1C),
                            shape = RoundedCornerShape(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${i + 1}",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ParamSettingsResetAndApply() {
    val context = LocalContext.current
    val sharedPreHelper = MySharedPreFun(context)
    val paramResetState = remember { mutableStateOf(false) }
    if (paramResetState.value) {
        ShowConfirmDialog(
            title = "重置参数",
            text = "你确实要重置参数吗？",
            onConfirm = {
                sharedPreHelper.resetConfig()
                sharedPreHelper.initSettingsParam()
                // 重置电机状态为全开
                sharedPreHelper.getMySharedPre().edit().putString("active_motors_state", "1,1,1,1,1,1,1,1").apply()
            },
            showDialog = paramResetState
        )
    }
    Box(modifier = Modifier.padding(16.dp).fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = {
                try { sharedPreHelper.initSettingsParam() } catch (_: Throwable) {}
                try {
                    val getList = listOf(R.raw.get1, R.raw.get2)
                    val idx = ((System.nanoTime() % 2 + 2) % 2).toInt()
                    val mp = android.media.MediaPlayer.create(context, getList[idx])
                    mp?.setOnCompletionListener { it.release() }
                    mp?.start()
                } catch (_: Throwable) {}
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Filled.DoneAll, contentDescription = "应用")
                    Text(text = "应用")
                }
            }
            IconButton(onClick = { paramResetState.value = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Filled.SettingsBackupRestore, contentDescription = "重置")
                    Text(text = "重置")
                }
            }
        }
    }
}