package com.nx.vfremake.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.R
import com.nx.vfremake.FERT_NODE_START_INDEX
import com.nx.vfremake.SEED_NODE_COUNT
import com.nx.vfremake.SEED_NODE_START_INDEX
import com.nx.vfremake.TOTAL_NODE_COUNT
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
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(id = R.dimen.middle_view_padding))
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

@Composable
fun InteractiveTractorDiagram() {
    val context = LocalContext.current
    val sharedPre = remember { MySharedPreFun(context).getMySharedPre() }
    val activeMotorsState = remember {
        val defaultState = List(TOTAL_NODE_COUNT) { "1" }.joinToString(",")
        val str = sharedPre.getString("active_motors_state", defaultState) ?: defaultState
        val list = str.split(",").map { it == "1" }.toMutableList()
        while (list.size < TOTAL_NODE_COUNT) list.add(true)
        mutableStateListOf(*list.take(TOTAL_NODE_COUNT).toTypedArray())
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
        modifier = Modifier.fillMaxSize()
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_user_tractor),
            contentDescription = "拖拉机作业示意图",
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomCenter
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StateLegend(Color(0xFF43A047), "绿色：启用")
            Spacer(Modifier.width(14.dp))
            StateLegend(Color(0xFFE53935), "红色：关断")
        }

        MotorStateRow(
            title = "施肥",
            startIndex = FERT_NODE_START_INDEX,
            activeMotorsState = activeMotorsState,
            onStateChanged = {
                sharedPre.edit().putString(
                    "active_motors_state",
                    activeMotorsState.joinToString(",") { if (it) "1" else "0" }
                ).apply()
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        MotorStateRow(
            title = "播种",
            startIndex = SEED_NODE_START_INDEX,
            activeMotorsState = activeMotorsState,
            onStateChanged = {
                sharedPre.edit().putString(
                    "active_motors_state",
                    activeMotorsState.joinToString(",") { if (it) "1" else "0" }
                ).apply()
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun MotorStateRow(
    title: String,
    startIndex: Int,
    activeMotorsState: MutableList<Boolean>,
    onStateChanged: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF222222), modifier = Modifier.width(42.dp))
        for (col in 0 until SEED_NODE_COUNT) {
            val index = startIndex + col
            val isActive = activeMotorsState[index]
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isActive) Color(0xFF4CAF50) else Color(0xFFE53935))
                    .clickable {
                        activeMotorsState[index] = !isActive
                        onStateChanged()
                    }
                    .border(
                        width = 2.dp,
                        color = if (isActive) Color(0xFF388E3C) else Color(0xFFB71C1C),
                        shape = RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${col + 1}",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "ID${index + 1}",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isActive) "启用" else "关断",
                        color = Color.White,
                        fontSize = 8.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StateLegend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(10.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.sp, color = Color(0xFF424A46))
    }
}

@Composable
fun ParamSettingsResetAndApply() {
    val context = LocalContext.current
    val sharedPreHelper = MySharedPreFun(context)
    val paramResetState: MutableState<Boolean> = remember { mutableStateOf(false) }
    if (paramResetState.value) {
        ShowConfirmDialog(
            title = "重置参数",
            text = "确定要重置参数吗？",
            onConfirm = {
                sharedPreHelper.resetConfig()
                sharedPreHelper.initSettingsParam()
                sharedPreHelper.getMySharedPre().edit()
                    .putString("active_motors_state", List(TOTAL_NODE_COUNT) { "1" }.joinToString(","))
                    .apply()
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
