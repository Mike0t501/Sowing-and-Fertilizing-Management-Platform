/**
 ***********************************************************************************************************
 * @brief   :模拟GNSS定位配置界面
 * ---------------------------------------------------------------------------------------------------------
 * 无真实 GNSS 时，设置起点→终点航线与速度，由 DB9reCANseCoroutine.start1Simulated 按设定
 * 速度匀速插值生成 RMC 测试施肥电机。totalSteps / interval 不写死：固定 tick（高级项可调），
 * 用 haversine 算起止距离 + 速度推导步数，界面实时显示推导结果。
 ***********************************************************************************************************
 */
package com.nx.vfremake.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.Scaffold
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.R
import com.nx.vfremake.funClass.MySharedPreFun
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private var backPressedTime: Long = 0

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val rEarth = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val sLat = sin(dLat / 2)
    val sLon = sin(dLon / 2)
    val a = sLat * sLat + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sLon * sLon
    return 2 * rEarth * atan2(sqrt(a), sqrt(1 - a))
}

@Composable
fun SimGnssScreen(onClickBack: () -> Unit = {}, onPickOnMap: () -> Unit = {}) {
    val context = LocalContext.current
    val sharedPre = MySharedPreFun(context).getMySharedPre()

    // 读取配置（缺失时回退字符串资源默认值）
    fun load(nameId: Int, defId: Int): String =
        sharedPre.getString(context.getString(nameId), null) ?: context.getString(defId)

    var startLat by remember {
        mutableStateOf(load(R.string.simGnss_startLat_name, R.string.simGnss_startLat_defeatValue))
    }
    var startLon by remember {
        mutableStateOf(load(R.string.simGnss_startLon_name, R.string.simGnss_startLon_defeatValue))
    }
    var endLat by remember {
        mutableStateOf(load(R.string.simGnss_endLat_name, R.string.simGnss_endLat_defeatValue))
    }
    var endLon by remember {
        mutableStateOf(load(R.string.simGnss_endLon_name, R.string.simGnss_endLon_defeatValue))
    }
    var speed by remember {
        mutableStateOf(load(R.string.simGnss_speed_name, R.string.simGnss_speed_defeatValue))
    }
    var interval by remember {
        mutableStateOf(load(R.string.simGnss_interval_name, R.string.simGnss_interval_defeatValue))
    }
    var loopOn by remember {
        mutableStateOf(load(R.string.simGnss_loop_name, R.string.simGnss_loop_defeatValue) == "1")
    }
    var advancedOpen by remember { mutableStateOf(false) }

    fun save(nameId: Int, value: String) {
        sharedPre.edit().putString(context.getString(nameId), value).apply()
    }

    Scaffold(
        topBar = {
            MyTopBar(title = "模拟GNSS定位", onClickBack = {
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 卡片一：航线坐标
            Card(shape = RoundedCornerShape(16.dp), elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    AnimatedSettingRow(title = "起点纬度", subtitle = "十进制度，如 36.850825") {
                        MyDiyTextField(modifier = Modifier.width(180.dp), value = startLat,
                            onValueChange = { startLat = it; save(R.string.simGnss_startLat_name, it) })
                    }
                    Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                    AnimatedSettingRow(title = "起点经度", subtitle = "十进制度，如 115.007949") {
                        MyDiyTextField(modifier = Modifier.width(180.dp), value = startLon,
                            onValueChange = { startLon = it; save(R.string.simGnss_startLon_name, it) })
                    }
                    Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                    AnimatedSettingRow(title = "终点纬度", subtitle = "十进制度，如 36.853154") {
                        MyDiyTextField(modifier = Modifier.width(180.dp), value = endLat,
                            onValueChange = { endLat = it; save(R.string.simGnss_endLat_name, it) })
                    }
                    Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                    AnimatedSettingRow(title = "终点经度", subtitle = "十进制度，如 115.008574") {
                        MyDiyTextField(modifier = Modifier.width(180.dp), value = endLon,
                            onValueChange = { endLon = it; save(R.string.simGnss_endLon_name, it) })
                    }
                }
            }

            // 在地图上取点：跳到主地图处方图，依次点选起点、终点，自动回填上方经纬度
            Button(
                onClick = onPickOnMap,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4CAF50))
            ) {
                Text("🖐 在地图上取点", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Text(
                text = "点击后跳到主地图，在处方图上依次点选起点、终点，自动回填经纬度。",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // 卡片二：运动参数
            Card(shape = RoundedCornerShape(16.dp), elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    AnimatedSettingRow(title = "速度", subtitle = "单位 km/h，模拟车辆匀速前进") {
                        MyDiyTextField(modifier = Modifier.width(120.dp), value = speed,
                            onValueChange = { speed = it; save(R.string.simGnss_speed_name, it) })
                    }
                    Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                    AnimatedSettingRow(title = "循环", subtitle = "关：到终点即停；开：回起点循环重跑") {
                        Switch(
                            checked = loopOn,
                            onCheckedChange = {
                                loopOn = it
                                save(R.string.simGnss_loop_name, if (it) "1" else "0")
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50))
                        )
                    }
                    Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                    AnimatedSettingRow(
                        title = "高级",
                        subtitle = "tick 间隔（GNSS 上报周期，默认 400ms）",
                        onClick = { advancedOpen = !advancedOpen }
                    ) {
                        Text(if (advancedOpen) "收起 ▴" else "展开 ▾", fontSize = 14.sp, color = Color.Gray)
                    }
                    AnimatedVisibility(
                        visible = advancedOpen,
                        enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)),
                        exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessLow))
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFF0FDF4)).padding(16.dp)) {
                            MyDiyTextField(
                                label = "tick 间隔(ms): ", modifier = Modifier.width(220.dp), value = interval,
                                onValueChange = { interval = it; save(R.string.simGnss_interval_name, it) }
                            )
                        }
                    }
                }
            }

            // 推导结果：实时显示距离 / 预计耗时 / 步数，替代写死的 totalSteps/interval
            Card(shape = RoundedCornerShape(16.dp), elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                val sLat = startLat.toDoubleOrNull()
                val sLon = startLon.toDoubleOrNull()
                val eLat = endLat.toDoubleOrNull()
                val eLon = endLon.toDoubleOrNull()
                val v = speed.toDoubleOrNull()
                val tickMs = interval.toLongOrNull() ?: 400L
                val hint: String = if (sLat == null || sLon == null || eLat == null || eLon == null || v == null || v <= 0.0) {
                    "参数无效：请检查经纬度与速度填写"
                } else {
                    val dist = haversineMeters(sLat, sLon, eLat, eLon)
                    val secs = dist / (v / 3.6)
                    val tickS = tickMs.coerceAtLeast(50L) / 1000.0
                    val steps = max(1, ceil(dist / ((v / 3.6) * tickS)).toInt())
                    "距离 ≈ %.1f m   预计 ≈ %.1f s   (%d 步 @%dms)".format(dist, secs, steps, tickMs)
                }
                Text(
                    text = hint,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1565C0),
                    modifier = Modifier.padding(16.dp)
                )
            }

            Text(
                text = "提示：在「系统设置」打开「模拟GNSS定位」开关后，回主界面点「开始」即按本航线运行。",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
