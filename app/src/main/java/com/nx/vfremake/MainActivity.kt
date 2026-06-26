/**
 ************************************************************************************************************
 * @author  :NIANXI
 * @date    :2024-05-02
 * @file    :
 * @brief   :变量施肥
 * ---------------------------------------------------------------------------------------------------------
 * Change History
 * ---------------------------------------------------------------------------------------------------------
 * 分级渲染
 * 增加颜色施肥量提示
 * 位置补偿并求单体位置查询施肥量
 * DB9通讯 CAN通讯，通过按钮启停通讯线程
 * 数据保存、按钮启停保存数据协程
 ***********************************************************************************************************
 */

package com.nx.vfremake

import android.app.Activity
import android.content.Context
import android.media.MediaPlayer
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.esri.arcgisruntime.ArcGISRuntimeEnvironment
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.mapping.view.BackgroundGrid
import com.esri.arcgisruntime.mapping.view.MapView
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.Basemap
import com.esri.arcgisruntime.mapping.Viewpoint
import com.nx.vfremake.funClass.ExportGeoFun
import com.nx.vfremake.funClass.TableRow
import org.json.JSONArray
import com.nx.vfremake.coroutine.CanReceiveCoroutine
import com.nx.vfremake.coroutine.DB9reCANseCoroutine
import com.nx.vfremake.coroutine.MyWriteSaveFun
import com.nx.vfremake.coroutine.TestModeCoroutine
import com.nx.vfremake.funClass.ConvAndCtrlFun
import com.nx.vfremake.funClass.DocuAndManageFun
import com.nx.vfremake.funClass.MyArcGisFun
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.funClass.MydantiFertSharedPre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nx.vfremake.lastSentMotorSpeed
import com.nx.vfremake.ui.runDiagnosticTest
import com.nx.vfremake.ui.scanSerialDevices
import com.nx.vfremake.ui.sendSimulatedForwardSpeed
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    // viewmodel
    private val customViewModel: CustomViewModel by viewModels()
    private val mVariableFertViewModel: VariableFertViewModel by viewModels()

    private lateinit var mapView: MapView

    // 协程
    private val myWriteSaveFun = MyWriteSaveFun()
    private val myTestModeCoroutine = TestModeCoroutine()

    // 因为需要rowNumber初始化参数，必须mSPParamData初始化完成才初始化
    private lateinit var mDB9reCANseCoroutine: DB9reCANseCoroutine
    private var mCanReceiveCoroutine = CanReceiveCoroutine()

    // 定义ActivityResultLauncher用于对象选择
    private lateinit var selectWriteDirectoryLauncher: ActivityResultLauncher<Uri?>

    // ===== WebView UI 集成 =====
    private val isRunningState = mutableStateOf(false)   // 运行标志（TestModeCoroutine 需要 MutableState）
    private var watchdogJob: Job? = null
    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private val pushHandler = Handler(Looper.getMainLooper())
    @Volatile private var mapTracking = false   // 地图位置跟踪开关
    @Volatile private var currentPage = "home"  // 当前页面，按页推送数据

    //...oncreat开始
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        selectWriteDirectoryLauncher =
            selectDirectoryLauncher(R.string.myWriteDir_DocumentUri_name, this)
        selectLoadShpFileLauncher =
            selectShpFileLauncher(R.string.myLoadShpFile_Path_name, this)

        // 注入"应用"按钮提示音，由 Composable 调用，保证与报错音走同一条播放链路
        onPlayApplySound = {
            val getList = listOf(R.raw.get1, R.raw.get2)
            val idx = ((System.nanoTime() % 2 + 2) % 2).toInt()
            enqueueErrorSound(getList[idx], isError = false)
        }

        // ===== 用 WebView 承载新版网页 UI；ArcGIS 地图浮在中间“留白”区 =====
        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)  // 透明，露出下层 ArcGIS 地图
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // 启用 App 内透明模式（中间透出底层地图）+ 让网页上报 .map 实际区域用于精确定位
                    view?.evaluateJavascript(
                        "document.body.classList.add('inapp');if(window.reportMapRect)window.reportMapRect();",
                        null
                    )
                    pushInitSettings()
                    startPushLoop()
                    // 界面显示后再初始化较重的 ArcGIS 地图，避免开机卡死被系统杀
                    pushHandler.postDelayed({ setupMapDeferred() }, 600)
                }
            }
            addJavascriptInterface(
                WebBridge(
                    onSetRunning = { run -> runOnUiThread { if (run) startWork() else stopWork() } },
                    onLoadShp = { field -> runOnUiThread { onWebLoadShp(field) } },
                    onSelectShp = { runOnUiThread { DocuAndManageFun().selectShapefile(selectLoadShpFileLauncher) } },
                    onSaveParams = { json -> runOnUiThread { onWebSaveParams(json) } },
                    onRunDiagnostic = { runOnUiThread { runDiagnostics() } },
                    onPage = { page -> runOnUiThread { currentPage = page; if (::mapView.isInitialized) mapView.visibility = if (page == "home") View.VISIBLE else View.GONE } },
                    onSaveDanti = { json -> runOnUiThread { onWebSaveDanti(json) } },
                    onMapOp = { op -> runOnUiThread { onMapOp(op) } },
                    onSetMapRect = { l, t, w, h -> runOnUiThread { setMapBounds(l, t, w, h) } },
                    onSendTest = { value, isRpm -> runOnUiThread { onWebSendTest(value, isRpm) } },
                    onMapVisible = { vis -> runOnUiThread { if (::mapView.isInitialized) mapView.visibility = if (vis && currentPage == "home") View.VISIBLE else View.GONE } }
                ),
                "NativeBridge"
            )
            loadUrl("file:///android_asset/web/index.html")
        }

        root = FrameLayout(this)
        root.setBackgroundColor(0xFFEAF1F6.toInt())   // 浅色底，避免透明区露黑边
        // 只先放 WebView，让界面秒开；ArcGIS 地图稍后由 setupMapDeferred() 加上
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        // 处方图字段列表更新 → 推给网页“步骤2”字段块
        mVariableFertViewModel.fieldsList.observe(this) { list ->
            if (::webView.isInitialized && !list.isNullOrEmpty()) {
                val arr = JSONArray(); list.forEach { arr.put(it) }
                webView.evaluateJavascript("if(window.setRxFields)window.setRxFields(${JSONObject.quote(arr.toString())})", null)
            }
        }

        lifecycleScope.launch {
            delay(350)
            playStartupSound()
        }

    }
    //...oncreat结束

    // ====== 延迟初始化 ArcGIS 地图：界面显示后再做，避免开机卡死被系统杀 ======
    private var mapReady = false
    private fun setupMapDeferred() {
        if (mapReady || isFinishing) return
        mapReady = true
        try {
            ArcGISRuntimeEnvironment.setApiKey(BuildConfig.API_KEY)
            val colorArgbBackgrand = getColor(R.color.background_color_default)
            mapView = MapView(this).apply {
                isAttributionTextVisible = false
                setBackgroundColor(colorArgbBackgrand)
                backgroundGrid = BackgroundGrid(colorArgbBackgrand, colorArgbBackgrand, 0f, 2f)
                map = ArcGISMap(Basemap())
            }
            val dn = resources.displayMetrics.density
            val mapLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins((104 * dn).toInt(), (58 * dn).toInt(), (28 * dn).toInt(), (96 * dn).toInt())
            }
            root.addView(mapView, 0, mapLp)   // index 0 = 放到 WebView 下层，透明网页透出地图
            mapView.visibility = if (currentPage == "home") View.VISIBLE else View.GONE

            // 关键：把加载好的处方图地图挂到 mapView（原 Compose 是 mapView.map = mArcGISMap）
            mVariableFertViewModel.mArcGISMap.observe(this) { m ->
                if (m != null && ::mapView.isInitialized) {
                    mapView.map = m
                    // 适应到处方图地块范围，避免视点停在空白处
                    val layer = mVariableFertViewModel.shapefileFeatureLayer.value
                    val fit = Runnable {
                        val ext = layer?.fullExtent
                        if (ext != null) { try { mapView.setViewpointAsync(Viewpoint(ext), 0.5f) } catch (_: Throwable) {} }
                    }
                    fit.run()
                    layer?.addDoneLoadingListener { fit.run() }
                }
            }

            webView.evaluateJavascript("if(window.reportMapRect)window.reportMapRect()", null)

            // 复制并加载默认处方图
            customViewModel.copyShpFilesJob = lifecycleScope.launch(Dispatchers.Default) {
                DocuAndManageFun().copyShpFilesToExternalStorage(this@MainActivity)
                withContext(Dispatchers.Main) {
                    if (::mapView.isInitialized) MyArcGisFun().loadShp(this@MainActivity, mVariableFertViewModel)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun playStartupSound() {
        try {
            val beginList = listOf(R.raw.begin1, R.raw.begin2, R.raw.begin3)
            val idx = ((System.nanoTime() % 3 + 3) % 3).toInt()
            val mp = MediaPlayer.create(this, beginList[idx])
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
        } catch (_: Exception) {}
    }

    // ====== 网页数据推送：把 ViewModel 实时数据喂给网页灵动岛 ======
    private val pushRunnable = object : Runnable {
        override fun run() {
            pushToWeb()
            pushHandler.postDelayed(this, 450)
        }
    }

    private fun startPushLoop() {
        pushHandler.removeCallbacks(pushRunnable)
        pushHandler.post(pushRunnable)
    }

    private fun pushToWeb() {
        if (!::webView.isInitialized) return
        when (currentPage) {
            "home" -> {
                val rpm = mVariableFertViewModel.motorSpeed.value ?: DoubleArray(TOTAL_NODE_COUNT)
                val sb = StringBuilder("[")
                for (i in 0 until TOTAL_NODE_COUNT) { if (i > 0) sb.append(","); sb.append(if (i < rpm.size) rpm[i] else 0.0) }
                sb.append("]")
                val runCount = (0 until TOTAL_NODE_COUNT).count { (rpm.getOrNull(it) ?: 0.0) >= 1.0 }
                val avg = mVariableFertViewModel.avgFert.value ?: 0.0
                webView.evaluateJavascript("if(window.pushMotors)window.pushMotors($sb)", null)
                webView.evaluateJavascript("if(window.pushStatus)window.pushStatus($isSystemRunning,$runCount,$avg)", null)

                if (mapTracking && ::mapView.isInitialized) {
                    val ll = mVariableFertViewModel.loLaDidegData.value
                    if (ll != null && (ll.first != 0.0 || ll.second != 0.0)) {
                        try {
                            val p = Point(ll.first, ll.second, SpatialReferences.getWgs84())
                            mapView.setViewpointAsync(Viewpoint(p, mapView.mapScale), 0.25f)
                        } catch (_: Throwable) {}
                    }
                }

                val flow = mVariableFertViewModel.confertflow.value ?: DoubleArray(0)
                val cnt = mVariableFertViewModel.seedSensorCount.value ?: IntArray(0)
                val cur = mVariableFertViewModel.motorCurrent.value ?: DoubleArray(0)
                val vol = mVariableFertViewModel.motorVoltage.value ?: DoubleArray(0)
                val acc = mVariableFertViewModel.avgAccuracy.value ?: 0.0
                fun darr(d: DoubleArray, n: Int) = (0 until n).joinToString(",", "[", "]") { (d.getOrNull(it) ?: 0.0).toString() }
                fun iarr(d: IntArray, n: Int) = (0 until n).joinToString(",", "[", "]") { (d.getOrNull(it) ?: 0).toString() }
                val detail = "{\"flow\":${darr(flow, 8)},\"count\":${iarr(cnt, 8)},\"cur\":${darr(cur, 16)},\"vol\":${darr(vol, 16)},\"acc\":$acc}"
                webView.evaluateJavascript("if(window.pushDetail)window.pushDetail($detail)", null)
            }
            "set" -> {
                val sbc = StringBuilder()
                for (i in 0 until TOTAL_NODE_COUNT) sbc.append("节点").append(i + 1).append("  ").append(canMonitorData.getOrElse(i) { "---" }).append("\n")
                webView.evaluateJavascript("if(window.setCan)window.setCan(${JSONObject.quote(sbc.toString())})", null)
            }
        }
    }

    private fun pushInitSettings() {
        if (!::webView.isInitialized) return
        val pre = MySharedPreFun(this)
        val o = JSONObject()
        o.put("testMode", pre.getSpecificValue(R.string.testMode_Switch_name) == "1")
        o.put("writeSaveData", pre.getSpecificValue(R.string.writeSaveData_Switch_name) == "1")
        o.put("navMark", pre.getSpecificValue(R.string.navMarkCompensated_Switch_name) == "1")
        o.put("errorSound", pre.getSpecificValue(R.string.errorSound_Switch_name) == "1")
        o.put("speedAvg", pre.getSpecificValue(R.string.forwardSpeedAverageNum_name) ?: "5")
        o.put("colorStep", pre.getSpecificValue(R.string.colorStep_name) ?: "3")
        o.put("deltaXY", pre.getSpecificValue(R.string.deltaX_name) ?: "50")
        val sp2 = pre.getMySharedPre()
        o.put("db9Port", sp2.getString(getString(R.string.serial_db9_port_name), getString(R.string.serial_db9_port_defValue)))
        o.put("db9Baud", sp2.getString(getString(R.string.serial_db9_baud_name), getString(R.string.serial_db9_baud_defValue)))
        o.put("canPort", sp2.getString(getString(R.string.serial_can_port_name), getString(R.string.serial_can_port_defValue)))
        o.put("canBaud", sp2.getString(getString(R.string.serial_can_baud_name), getString(R.string.serial_can_baud_defValue)))
        webView.evaluateJavascript("if(window.initSettings)window.initSettings(${JSONObject.quote(o.toString())})", null)

        // 串口枚举较慢（sysfs/shell），放后台线程，完成后再推给网页，避免拖慢启动
        lifecycleScope.launch(Dispatchers.Default) {
            val ports = try { scanSerialDevices() } catch (_: Throwable) { emptyList() }
            val pArr = JSONArray(); ports.forEach { pArr.put(it) }
            withContext(Dispatchers.Main) {
                if (::webView.isInitialized)
                    webView.evaluateJavascript("if(window.setPorts)window.setPorts(${JSONObject.quote(pArr.toString())})", null)
            }
        }

        // 单体设置回显（施肥 a/b + 排种 传动比/型孔/株距）
        val rows = MydantiFertSharedPre(this).getTableRows().associateBy { it.id }
        val fertArr = JSONArray()
        for (id in 0..8) {
            val r = rows[id]
            fertArr.put(JSONObject().put("id", id).put("a", r?.a ?: "0.0").put("b", r?.b ?: "0.0"))
        }
        val danti = JSONObject()
            .put("fert", fertArr)
            .put("seed", JSONObject()
                .put("ratio", sp2.getString("seed_transmission_ratio", "1.0"))
                .put("hole", sp2.getString("seed_hole_count", "12"))
                .put("spacing", sp2.getString("seed_plant_spacing_cm", "20")))
        webView.evaluateJavascript("if(window.initDanti)window.initDanti(${JSONObject.quote(danti.toString())})", null)
    }

    private fun onWebLoadShp(field: String) {
        // 写入所选渲染字段（同原程序 fertQueryField_name），再重新加载处方图
        if (field.isNotEmpty()) MySharedPreFun(this).getMySharedPre().edit()
            .putString(getString(R.string.fertQueryField_name), field).apply()
        lifecycleScope.launch { MyArcGisFun().loadShp(this@MainActivity, mVariableFertViewModel) }
    }

    // 网页“应用参数/测试模式开关”回调：写回 SharedPreferences（键同原程序）
    private fun onWebSaveParams(json: String) {
        try {
            val o = JSONObject(json)
            val ed = MySharedPreFun(this).getMySharedPre().edit()
            if (o.has("testMode")) ed.putString(getString(R.string.testMode_Switch_name), if (o.getBoolean("testMode")) "1" else "0")
            if (o.has("writeSaveData")) ed.putString(getString(R.string.writeSaveData_Switch_name), if (o.getBoolean("writeSaveData")) "1" else "0")
            if (o.has("navMark")) ed.putString(getString(R.string.navMarkCompensated_Switch_name), if (o.getBoolean("navMark")) "1" else "0")
            if (o.has("errorSound")) ed.putString(getString(R.string.errorSound_Switch_name), if (o.getBoolean("errorSound")) "1" else "0")
            if (o.has("speedAvg")) ed.putString(getString(R.string.forwardSpeedAverageNum_name), o.getString("speedAvg"))
            if (o.has("colorStep")) ed.putString(getString(R.string.colorStep_name), o.getString("colorStep"))
            if (o.has("deltaXY")) {
                val v = o.getString("deltaXY")
                ed.putString(getString(R.string.deltaX_name), v)
                ed.putString(getString(R.string.deltaY_name), v)
            }
            if (o.has("active")) {
                val arr = o.getJSONArray("active")
                val csv = (0 until arr.length()).joinToString(",") { arr.getInt(it).toString() }
                ed.putString("active_motors_state", csv)
            }
            if (o.has("rowSize")) ed.putString(getString(R.string.rowSize_name), o.getString("rowSize"))
            if (o.has("gnssL1")) ed.putString(getString(R.string.gnssDistanceVertical_name), o.getString("gnssL1"))
            if (o.has("gnssL2")) ed.putString(getString(R.string.gnssDistanceHorizontal_name), o.getString("gnssL2"))
            if (o.has("lagTime")) ed.putString(getString(R.string.lagTime_name), o.getString("lagTime"))
            if (o.has("forwardSpeed")) ed.putString(getString(R.string.forwardSpeed_name), o.getString("forwardSpeed"))
            if (o.has("fertApplied")) ed.putString(getString(R.string.fertApplied_name), o.getString("fertApplied"))
            if (o.has("db9Port")) ed.putString(getString(R.string.serial_db9_port_name), o.getString("db9Port"))
            if (o.has("db9Baud")) ed.putString(getString(R.string.serial_db9_baud_name), o.getString("db9Baud"))
            if (o.has("canPort")) ed.putString(getString(R.string.serial_can_port_name), o.getString("canPort"))
            if (o.has("canBaud")) ed.putString(getString(R.string.serial_can_baud_name), o.getString("canBaud"))
            ed.apply()
            MySharedPreFun(this).initSettingsParam()
        } catch (_: Throwable) {}
    }

    // 网页“一键检测”回调：跑原程序真实诊断，进度/报告实时推回网页
    private fun runDiagnostics() {
        lifecycleScope.launch {
            runDiagnosticTest(
                this@MainActivity,
                onProgress = { line ->
                    runOnUiThread {
                        if (::webView.isInitialized)
                            webView.evaluateJavascript("if(window.pushDiag)window.pushDiag(${JSONObject.quote(line)})", null)
                    }
                },
                onComplete = { report, _ ->
                    runOnUiThread {
                        if (::webView.isInitialized)
                            webView.evaluateJavascript("if(window.setDiag)window.setDiag(${JSONObject.quote(report)})", null)
                    }
                }
            )
        }
    }

    // 网页“单体设置”保存：施肥 a/b 拟合系数 / 排种 传动比·型孔·株距
    private fun onWebSaveDanti(json: String) {
        try {
            val o = JSONObject(json)
            if (o.optBoolean("seed", false)) {
                MySharedPreFun(this).getMySharedPre().edit()
                    .putString("seed_transmission_ratio", o.optString("ratio", "1.0"))
                    .putString("seed_hole_count", o.optString("hole", "12"))
                    .putString("seed_plant_spacing_cm", o.optString("spacing", "20"))
                    .apply()
            } else {
                val id = o.getInt("id")
                val a = o.optString("a", "0.0")
                val b = o.optString("b", "0.0")
                val helper = MydantiFertSharedPre(this)
                helper.saveTableRow(id, TableRow(id, a, b))
                if (id == 0) for (i in 1..8) helper.saveTableRow(i, TableRow(i, a, b))
            }
            MySharedPreFun(this).initSettingsParam()
            initFittingCoefficient()   // a/b 改后立即重算拟合系数
        } catch (_: Throwable) {}
    }

    // 网页地图工具：全屏 / 位置跟踪 / 清除已施肥 / 合并 / 导出（逻辑同原 MainScreen）
    private fun onMapOp(op: String) {
        if (!::mapView.isInitialized) return
        try {
            when (op) {
                "fullscreen" -> {
                    val ext = mVariableFertViewModel.shapefileFeatureLayer.value?.fullExtent
                    if (ext != null) { mapView.setViewpointAsync(Viewpoint(ext), 0.3f); mapView.post { mapView.invalidate() } }
                }
                "track" -> {
                    mapTracking = !mapTracking
                    mVariableFertViewModel.navCenterIsRunning.value = mapTracking
                }
                "clear" -> {
                    mVariableFertViewModel.fertGraphicsOverlayExport.value?.graphics?.clear()
                    mapView.post { mapView.invalidate() }
                }
                "merge" -> {
                    mVariableFertViewModel.fertGraphicsOverlay.value?.let { MyArcGisFun().mergeGraphicsWithBuffer(it, 0.0, this) }
                }
                "export" -> {
                    mVariableFertViewModel.fertGraphicsOverlayExport.value?.let { ExportGeoFun(this).createGeodatabase(it) }
                }
                "zerocount" -> {
                    mVariableFertViewModel.seedSensorCount.postValue(IntArray(SEED_NODE_COUNT) { 0 })
                }
            }
        } catch (_: Throwable) {}
    }

    // 网页“测试输出”：测试模式下发送 rpm（给所有电机）或 车速km/h（同原主页测试控件）
    private fun onWebSendTest(value: Double, isRpm: Boolean) {
        try {
            MySharedPreFun(this).getMySharedPre().edit()
                .putString(getString(R.string.testMode_testSend_name), value.toString())
                .putString(getString(R.string.testMode_testSendMode_name), if (isRpm) "0" else "1")
                .apply()
            if (mVariableFertViewModel.serialPortIsRunning.value != true) {
                MySerialPortFun().openSerialPort(this, mVariableFertViewModel)
            }
            if (isRpm) {
                for (i in 0 until TOTAL_NODE_COUNT) ConvAndCtrlFun().motorSpeedrpmSend(value, i)
            } else {
                sendSimulatedForwardSpeed(value, mVariableFertViewModel)
            }
        } catch (_: Throwable) {}
    }

    // 网页上报 .map 区域（CSS px）→ 把底层地图精确放到右侧栏中间那块
    private fun setMapBounds(l: Int, t: Int, w: Int, h: Int) {
        if (!::mapView.isInitialized || w <= 0 || h <= 0) return
        val d = resources.displayMetrics.density
        val lp = FrameLayout.LayoutParams((w * d).toInt(), (h * d).toInt())
        lp.leftMargin = (l * d).toInt()
        lp.topMargin = (t * d).toInt()
        mapView.layoutParams = lp
    }

    // ====== 开始作业（由网页 NativeBridge.setRunning(true) 调用，逻辑同原开始按钮） ======
    fun startWork() {
        val context = this@MainActivity
        val coroutineScope = lifecycleScope
        if (!isRunningState.value) {
            val isTestMode = MySharedPreFun(context).getSpecificValue(R.string.testMode_Switch_name) == "1"

            if (!isTestMode) {
                DocuAndManageFun().cheekDirectoryUriPermission(
                    R.string.myWriteDir_DocumentUri_name, selectWriteDirectoryLauncher, context
                )
                if (MySharedPreFun(context).getSpecificValue(R.string.writeSaveData_Switch_name) == "1") {
                    myWriteSaveFun.start(context = context, getData = {
                        myWriteSaveFun.getMySaveData(mSPParamData.rowNumber, mVariableFertViewModel)
                    })
                }
            }

            if (mVariableFertViewModel.serialPortIsRunning.value == false) {
                MySerialPortFun().openSerialPort(context, mVariableFertViewModel)
            }

            MySharedPreFun(context).initSettingsParam()

            val sharedPre = MySharedPreFun(context).getMySharedPre()
            val defaultActive = List(TOTAL_NODE_COUNT) { "1" }.joinToString(",")
            val activeStr = sharedPre.getString("active_motors_state", defaultActive) ?: defaultActive
            val activeList = activeStr.split(",").map { it == "1" }.toMutableList()
            while (activeList.size < TOTAL_NODE_COUNT) activeList.add(true)
            val activeArray = activeList.take(TOTAL_NODE_COUNT).toBooleanArray()
            mVariableFertViewModel.activeMotorsState.postValue(activeArray)

            mVariableFertViewModel.resetFertStats()
            errorTriggeredThisRun = false

            val soundEnabledAtStart = MySharedPreFun(context).getSpecificValue(R.string.errorSound_Switch_name) == "1"
            if (soundEnabledAtStart && fittingCoefficientA.all { it == 0.0 } && fittingCoefficientB.all { it == 0.0 }) {
                enqueueErrorSound(R.raw.fail6)
            }
            if (soundEnabledAtStart && !isTestMode && mRmcData.latitude == 0.0 && mRmcData.longitude == 0.0) {
                enqueueErrorSound(R.raw.fail7)
            }

            isSystemRunning = true
            isRunningState.value = true

            if (isTestMode) {
                if (!myTestModeCoroutine.isRunning) myTestModeCoroutine.start(context, isRunningState)
                if (!mCanReceiveCoroutine.isRunning) mCanReceiveCoroutine.start(mVariableFertViewModel)
            } else {
                if (!mDB9reCANseCoroutine.isRunning) mDB9reCANseCoroutine.start1(context, mVariableFertViewModel)
                if (!mCanReceiveCoroutine.isRunning) mCanReceiveCoroutine.start(mVariableFertViewModel)
            }

            mVariableFertViewModel.canEverReceived = false
            mVariableFertViewModel.rtkEverReceived = false
            mVariableFertViewModel.canLastReceiveTime.postValue(System.currentTimeMillis())
            watchdogJob?.cancel()
            watchdogShouldRun = true
            watchdogJob = coroutineScope.launch {
                var motorDelayStartTime = 0L
                var canReadyPlayed = false
                var rtkReadyPlayed = false
                var noResponseCooldownTime = 0L
                var noResponseStartTime = 0L
                while (isActive && watchdogShouldRun) {
                    delay(2000)
                    if (!watchdogShouldRun) break
                    val errorSoundOn = MySharedPreFun(context).getSpecificValue(R.string.errorSound_Switch_name) == "1"
                    if (!errorSoundOn) { motorDelayStartTime = 0L; continue }
                    if (mVariableFertViewModel.canEverReceived && !canReadyPlayed) {
                        canReadyPlayed = true
                        enqueueErrorSound(R.raw.ready1, isError = false)
                    }
                    if (mVariableFertViewModel.rtkEverReceived && !rtkReadyPlayed) {
                        rtkReadyPlayed = true
                        enqueueErrorSound(R.raw.ready2, isError = false)
                    }
                    val anyMotorCommanded = lastSentMotorSpeed.any { it >= 1.0 }
                    val lastReceive = mVariableFertViewModel.canLastReceiveTime.value ?: 0L
                    if (anyMotorCommanded && System.currentTimeMillis() - lastReceive > 5000L) {
                        val resId = if (mVariableFertViewModel.canEverReceived) R.raw.fail2 else R.raw.fail3
                        enqueueErrorSound(resId)
                        motorDelayStartTime = 0L
                        delay(8000)
                        continue
                    }
                    val returnSpeeds = mVariableFertViewModel.motorSpeed.value
                    val anyCommanded = lastSentMotorSpeed.any { it >= 1.0 }
                    if (anyCommanded && System.currentTimeMillis() - noResponseCooldownTime > 8000L) {
                        val noResponseCount = (0 until TOTAL_NODE_COUNT).count { i ->
                            val active: Boolean = mSPParamData.activeMotors.getOrNull(i) ?: true
                            active && (returnSpeeds?.getOrNull(i) ?: 0.0) == 0.0
                        }
                        if (noResponseCount >= 2) {
                            if (noResponseStartTime == 0L) noResponseStartTime = System.currentTimeMillis()
                            if (System.currentTimeMillis() - noResponseStartTime > 3000L) {
                                if (noResponseCount == 2) enqueueErrorSound(R.raw.fail4) else enqueueErrorSound(R.raw.fail5)
                                noResponseCooldownTime = System.currentTimeMillis()
                                noResponseStartTime = 0L
                                motorDelayStartTime = 0L; delay(8000); continue
                            }
                        } else {
                            noResponseStartTime = 0L
                        }
                    } else {
                        noResponseStartTime = 0L
                    }
                    val hasDelay = (0 until TOTAL_NODE_COUNT).any { i ->
                        val sent = if (i < lastSentMotorSpeed.size) lastSentMotorSpeed[i] else 0.0
                        val actual = returnSpeeds?.getOrNull(i) ?: 0.0
                        sent >= 1.0 && actual > 0.0 && actual < sent * 0.7
                    }
                    if (hasDelay) {
                        if (motorDelayStartTime == 0L) motorDelayStartTime = System.currentTimeMillis()
                        if (System.currentTimeMillis() - motorDelayStartTime > 3000L) {
                            enqueueErrorSound(R.raw.fail1)
                            motorDelayStartTime = 0L
                            delay(8000)
                        }
                    } else {
                        motorDelayStartTime = 0L
                    }
                }
            }
        }
    }

    // ====== 停止作业（由网页 NativeBridge.setRunning(false) 调用，逻辑同原停止按钮） ======
    fun stopWork() {
        val context = this@MainActivity
        val coroutineScope = lifecycleScope
        coroutineScope.launch {
            isSystemRunning = false
            if (mDB9reCANseCoroutine.isRunning) mDB9reCANseCoroutine.shutdown()
            if (myTestModeCoroutine.isRunning) myTestModeCoroutine.shutdown()
            if (myWriteSaveFun.isRunning) myWriteSaveFun.shutdown()
            delay(100)
            if (!isSystemRunning) {
                withContext(Dispatchers.IO) {
                    for (i in 0 until TOTAL_NODE_COUNT) {
                        if (isSystemRunning) break
                        ConvAndCtrlFun().motorSpeedrpmSend(0.0, i)
                        delay(15)
                    }
                    if (!isSystemRunning) delay(150)
                }
            }
            if (!isSystemRunning) {
                releaseResource(mVariableFertViewModel)
                if (isRunningState.value) isRunningState.value = false
                watchdogShouldRun = false
                val errorSoundOn = MySharedPreFun(context).getSpecificValue(R.string.errorSound_Switch_name) == "1"
                if (errorSoundOn && !errorTriggeredThisRun) {
                    enqueueErrorSound(R.raw.good, isError = false)
                }
            }
        }
    }

    private fun selectDirectoryLauncher(
        resId: Int,
        context: Context,
    ): ActivityResultLauncher<Uri?> {
        return registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.also {
                val takeFlags: Int =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)

                val sharedPreHelper = MySharedPreFun(context)
                sharedPreHelper.persistDirectoryUriPermission(
                    uri,
                    takeFlags,
                    context.getString(resId)
                )
                MyWriteSaveFun().writeFileToSelectedDirectory(
                    "writetest.txt",
                    "Hello, World!",
                    context.getString(resId),
                    context
                )
            }
        }
    }

    private fun selectShpFileLauncher(
        resId: Int,
        context: Context,
    ): ActivityResultLauncher<Intent> {
        return registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data
                uri?.let {
                    if (uri.toString().endsWith(".shp")) {
                        val filePath = DocuAndManageFun().getPathFromContentUri(context, uri)
                        Log.d("SharedPre", filePath.toString())
                        MySharedPreFun(context).getMySharedPre().edit()
                            .putString(context.getString(resId), filePath).apply()
                        val shpName = filePath?.substringAfterLast('/') ?: ""
                        if (::webView.isInitialized && shpName.isNotEmpty())
                            webView.evaluateJavascript("if(window.setRxFile)window.setRxFile(${JSONObject.quote(shpName)})", null)
                        MyArcGisFun().getFieldList(filePath, mVariableFertViewModel)
                    } else {
                        AlertDialog.Builder(context)
                            .setTitle("文件选择错误")
                            .setMessage("请选择 .shp 文件")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            }
        }
    }
    @OptIn(ExperimentalLayoutApi::class)
    @Composable

    fun StartAndStopBottom() {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope() // 用于控制安全关闭串口

        Box(
            modifier = Modifier
                .padding(dimensionResource(id = R.dimen.small_custom_padding))
                .fillMaxSize()
        ) {
            val isRunningState = remember { mutableStateOf(false) }
            val watchdogJobRef = remember { object { var job: Job? = null } }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 106.dp, bottom = 2.dp)
                    .width(194.dp)
                    .height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ================== 开始按钮 ==================
                Surface(
                    color = if (isRunningState.value) androidx.compose.ui.graphics.Color.Gray else androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .width(90.dp)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(dimensionResource(id = R.dimen.small_roundedCornerShape)),
                ) {
                    IconButton(
                        modifier = Modifier.fillMaxSize(),
                        onClick = {
                            if (!isRunningState.value) {
                                val isTestMode = MySharedPreFun(context).getSpecificValue(R.string.testMode_Switch_name) == "1"

                                // 1. 【修复】：测试模式下跳过申请文件夹，不要弹出文件选择器！
                                if (!isTestMode) {
                                    DocuAndManageFun().cheekDirectoryUriPermission(
                                        R.string.myWriteDir_DocumentUri_name, selectWriteDirectoryLauncher, context
                                    )
                                    if (MySharedPreFun(context).getSpecificValue(R.string.writeSaveData_Switch_name) == "1") {
                                        myWriteSaveFun.start(context = context, getData = {
                                            myWriteSaveFun.getMySaveData(mSPParamData.rowNumber, mVariableFertViewModel)
                                        })
                                    }
                                }

                                // 2. 打开串口
                                if (mVariableFertViewModel.serialPortIsRunning.value == false) {
                                    MySerialPortFun().openSerialPort(context, mVariableFertViewModel)
                                }

                                // 3. 每次开始重新读取 UI 最新参数 (包括单体启停控制)
                                MySharedPreFun(context).initSettingsParam()

                                // ================= 【核心修复：开始】 =================
                                // 读取单体启停状态字符串，转成 Boolean 数组并传给 ViewModel，
                                // 让 ViewModel 在计算准确率时跳过那些被关闭的电机
                                val sharedPre = MySharedPreFun(context).getMySharedPre()
                                val defaultActive = List(TOTAL_NODE_COUNT) { "1" }.joinToString(",")
                                val activeStr = sharedPre.getString("active_motors_state", defaultActive) ?: defaultActive
                                val activeList = activeStr.split(",").map { it == "1" }.toMutableList()
                                while (activeList.size < TOTAL_NODE_COUNT) activeList.add(true)
                                val activeArray = activeList.take(TOTAL_NODE_COUNT).toBooleanArray()
                                mVariableFertViewModel.activeMotorsState.postValue(activeArray)
                                // ================= 【核心修复：结束】 =================

                                // 4. 【修复】：清空平均施肥量和准确率统计，从头算
                                mVariableFertViewModel.resetFertStats()
                                // 重置本次运行报错标志
                                errorTriggeredThisRun = false

                                val soundEnabledAtStart = MySharedPreFun(context).getSpecificValue(R.string.errorSound_Switch_name) == "1"
                                // 4a. 开始前提示：排肥系数未配置（A/B 全为 0）
                                if (soundEnabledAtStart && fittingCoefficientA.all { it == 0.0 } && fittingCoefficientB.all { it == 0.0 }) {
                                    enqueueErrorSound(R.raw.fail6)
                                }

                                // 4b. 开始前提示：非测试模式下没有 RTK 数据
                                if (soundEnabledAtStart && !isTestMode && mRmcData.latitude == 0.0 && mRmcData.longitude == 0.0) {
                                    enqueueErrorSound(R.raw.fail7)
                                }

                                isSystemRunning = true
                                isRunningState.value = true

                                // 5. 模式隔离
                                if (isTestMode) {
                                    if (!myTestModeCoroutine.isRunning) myTestModeCoroutine.start(context, isRunningState)
                                    // 【修复】：测试模式必须也打开接收，这样底部图表才能收到反馈发生变化！
                                    if (!mCanReceiveCoroutine.isRunning) mCanReceiveCoroutine.start(mVariableFertViewModel)
                                } else {
                                    if (!mDB9reCANseCoroutine.isRunning) mDB9reCANseCoroutine.start1(context, mVariableFertViewModel)
                                    if (!mCanReceiveCoroutine.isRunning) mCanReceiveCoroutine.start(mVariableFertViewModel)
                                }

                                // 6. 启动 CAN 看门狗（测试模式和正常模式都启动）
                                // fail3：从未收到数据（一开始没接好 / 测试模式未插CAN）
                                // fail2：曾经收到过、后续中断（电机不再返回字节）
                                mVariableFertViewModel.canEverReceived = false
                                mVariableFertViewModel.rtkEverReceived = false
                                mVariableFertViewModel.canLastReceiveTime.postValue(System.currentTimeMillis())
                                watchdogJobRef.job?.cancel()
                                watchdogShouldRun = true
                                watchdogJobRef.job = coroutineScope.launch {
                                    var motorDelayStartTime = 0L
                                    var canReadyPlayed = false
                                    var rtkReadyPlayed = false
                                    var noResponseCooldownTime = 0L
                                    var noResponseStartTime = 0L
                                    while (isActive && watchdogShouldRun) {
                                        delay(2000)
                                        if (!watchdogShouldRun) break

                                        // 语音提示开关：关闭时跳过所有语音
                                        val errorSoundOn = MySharedPreFun(context).getSpecificValue(R.string.errorSound_Switch_name) == "1"
                                        if (!errorSoundOn) { motorDelayStartTime = 0L; continue }

                                        // --- ready1/ready2：首次连接提示（不算报错） ---
                                        if (mVariableFertViewModel.canEverReceived && !canReadyPlayed) {
                                            canReadyPlayed = true
                                            enqueueErrorSound(R.raw.ready1, isError = false)
                                        }
                                        if (mVariableFertViewModel.rtkEverReceived && !rtkReadyPlayed) {
                                            rtkReadyPlayed = true
                                            enqueueErrorSound(R.raw.ready2, isError = false)
                                        }

                                        // --- fail2/fail3：CAN 超时（5秒无回包）---
                                        // 仅在有电机被命令转动时检查：停车或刚启动时电机不回传属正常
                                        val anyMotorCommanded = lastSentMotorSpeed.any { it >= 1.0 }
                                        val lastReceive = mVariableFertViewModel.canLastReceiveTime.value ?: 0L
                                        if (anyMotorCommanded && System.currentTimeMillis() - lastReceive > 5000L) {
                                            val resId = if (mVariableFertViewModel.canEverReceived) R.raw.fail2 else R.raw.fail3
                                            enqueueErrorSound(resId)
                                            motorDelayStartTime = 0L
                                            delay(8000)
                                            continue
                                        }

                                        // --- fail4/fail5：部分已启用电机无回传（持续3秒）---
                                        val returnSpeeds = mVariableFertViewModel.motorSpeed.value
                                        val anyCommanded = lastSentMotorSpeed.any { it >= 1.0 }
                                        if (anyCommanded && System.currentTimeMillis() - noResponseCooldownTime > 8000L) {
                                            val noResponseCount = (0 until TOTAL_NODE_COUNT).count { i ->
                                                val isActive: Boolean = mSPParamData.activeMotors.getOrNull(i) ?: true
                                                isActive && (returnSpeeds?.getOrNull(i) ?: 0.0) == 0.0
                                            }
                                            if (noResponseCount >= 2) {
                                                if (noResponseStartTime == 0L) noResponseStartTime = System.currentTimeMillis()
                                                if (System.currentTimeMillis() - noResponseStartTime > 3000L) {
                                                    if (noResponseCount == 2) enqueueErrorSound(R.raw.fail4) else enqueueErrorSound(R.raw.fail5)
                                                    noResponseCooldownTime = System.currentTimeMillis()
                                                    noResponseStartTime = 0L
                                                    motorDelayStartTime = 0L; delay(8000); continue
                                                }
                                            } else {
                                                noResponseStartTime = 0L
                                            }
                                        } else {
                                            noResponseStartTime = 0L
                                        }

                                        // --- fail1：电机响应延迟（发出转速 > 1rpm 但回传 < 70% 且持续 3 秒）---
                                        val hasDelay = (0 until TOTAL_NODE_COUNT).any { i ->
                                            val sent = if (i < lastSentMotorSpeed.size) lastSentMotorSpeed[i] else 0.0
                                            val actual = returnSpeeds?.getOrNull(i) ?: 0.0
                                            // actual > 0：只检测有回传的电机，无回传（反转电机）跳过
                                            sent >= 1.0 && actual > 0.0 && actual < sent * 0.7
                                        }
                                        if (hasDelay) {
                                            if (motorDelayStartTime == 0L) motorDelayStartTime = System.currentTimeMillis()
                                            if (System.currentTimeMillis() - motorDelayStartTime > 3000L) {
                                                enqueueErrorSound(R.raw.fail1)
                                                motorDelayStartTime = 0L
                                                delay(8000)
                                            }
                                        } else {
                                            motorDelayStartTime = 0L
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayCircle, contentDescription = "开始")
                            Text("开始")
                        }
                    }
                }

                // ================== 停止按钮 ==================
                Surface(
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .width(90.dp)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(dimensionResource(id = R.dimen.small_roundedCornerShape)),
                ) {
                    IconButton(
                        modifier = Modifier.fillMaxSize(),
                        onClick = {
                            // 【修复】：立刻切断串口导致0转速命令发不出去。改用协程保障缓冲发完！
                            coroutineScope.launch {
                                // 0. 第一时间置 false，阻断所有异步施肥查询回调再发送电机指令
                                isSystemRunning = false
                                // 注意：不在此处取消看门狗，让它继续监控停止过程中的 CAN 状态

                                // 1. 先关闭所有发数据的后台线程
                                if (mDB9reCANseCoroutine.isRunning) mDB9reCANseCoroutine.shutdown()
                                if (myTestModeCoroutine.isRunning) myTestModeCoroutine.shutdown()
                                if (myWriteSaveFun.isRunning) myWriteSaveFun.shutdown()

                                // 2. 稍作延迟，避开总线最高峰
                                kotlinx.coroutines.delay(100)

                                // 3. 强制清零所有电机
                                // 【修复】：每步检查 isSystemRunning，若用户已重新开始则立即中断，
                                // 避免与新周期的发送协程争抢 synchronized(outputStream) 造成卡顿
                                if (!isSystemRunning) {
                                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                                        for (i in 0 until TOTAL_NODE_COUNT) {
                                            if (isSystemRunning) break // 发送途中又开始了，立即终止
                                            ConvAndCtrlFun().motorSpeedrpmSend(0.0, i)
                                            kotlinx.coroutines.delay(15)
                                        }
                                        if (!isSystemRunning) kotlinx.coroutines.delay(150)
                                    }
                                }

                                // 4. 彻底确保指令出去了，再把串口拉闸关闭
                                // 【修复】：仅当系统未重新启动时才释放，避免关掉新周期的串口和误改 UI 状态
                                if (!isSystemRunning) {
                                    releaseResource(mVariableFertViewModel)
                                    if (isRunningState.value) isRunningState.value = false
                                    watchdogShouldRun = false
                                    // 本次运行无任何报错语音 → 播放 good
                                    val errorSoundOn = MySharedPreFun(context).getSpecificValue(R.string.errorSound_Switch_name) == "1"
                                    if (errorSoundOn && !errorTriggeredThisRun) {
                                        enqueueErrorSound(R.raw.good, isError = false)
                                    }
                                }
                            }
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.StopCircle, contentDescription = "停止")
                            Text(text = "停止")
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mDB9reCANseCoroutine = DB9reCANseCoroutine()
        // 启动期读取 SharedPreferences/系数，任意一处坏值都不致命，避免每次启动崩溃需清存储
        try {
            val sharedPreHelper = MySharedPreFun(this)
            sharedPreHelper.updataNewConfig()

            val resKey = this.getString(R.string.myLoadShpFile_Path_name)
            val keyValue = sharedPreHelper.getMySharedPre().getString(resKey, "")
            if (keyValue.equals("-1")) {
                Log.d("SharedPreferences", "$resKey 不存在或为空")
            } else {
                Log.d("SharedPreferences", "$resKey 已存在: $keyValue")
            }

            sharedPreHelper.initSettingsParam()
            initFittingCoefficient()
        } catch (e: Throwable) {
            Log.e("onStart", "启动读取失败（已忽略）: " + e.message)
        }
    }

    private fun initFittingCoefficient() {
        fittingCoefficientA = Array(FERT_NODE_COUNT) { 0.0 }
        fittingCoefficientB = Array(FERT_NODE_COUNT) { 0.0 }

        val tableRows = MydantiFertSharedPre(this).getTableRows()
        tableRows.forEach { row ->
            if (row.id <= FERT_NODE_COUNT && row.id != 0) {
                fittingCoefficientA[row.id - 1] = row.a.toDoubleOrNull() ?: 0.0
                fittingCoefficientB[row.id - 1] = row.b.toDoubleOrNull() ?: 0.0
            }
        }
        val defaultCoefficientA = tableRows.find { it.id == 0 }?.a?.toDoubleOrNull() ?: 0.0
        val defaultCoefficientB = tableRows.find { it.id == 0 }?.b?.toDoubleOrNull() ?: 0.0

        fittingCoefficientA.forEachIndexed { index, value ->
            if (value == 0.0) {
                fittingCoefficientA[index] = defaultCoefficientA
            }
        }
        fittingCoefficientB.forEachIndexed { index, value ->
            if (value == 0.0) {
                fittingCoefficientB[index] = defaultCoefficientB
            }
        }
    }

    // ── 顺序语音队列：报错/提示音依次播放，不互相打断 ──
    private val errorSoundQueue = ArrayDeque<Int>()
    private var errorSoundPlayer: MediaPlayer? = null

    /** 将一个音频加入队列，若当前没有音频在播放则立即开始 */
    private fun enqueueErrorSound(resId: Int, isError: Boolean = true) {
        if (isError) errorTriggeredThisRun = true
        errorSoundQueue.addLast(resId)
        if (errorSoundPlayer == null) playNextErrorSound()
    }

    /** 播放队列里的下一条；播完自动继续下一条 */
    private fun playNextErrorSound() {
        val resId = errorSoundQueue.removeFirstOrNull() ?: return
        errorSoundPlayer = try {
            MediaPlayer.create(this, resId)?.apply {
                setOnCompletionListener {
                    it.release()
                    errorSoundPlayer = null
                    playNextErrorSound()
                }
                start()
            }
        } catch (_: Throwable) { null }
        // 创建失败就跳过，继续播下一条
        if (errorSoundPlayer == null) playNextErrorSound()
    }

    /** 停止并清空队列（系统停止/销毁时调用） */
    private fun clearErrorSounds() {
        errorSoundQueue.clear()
        try { errorSoundPlayer?.stop(); errorSoundPlayer?.release() } catch (_: Exception) {}
        errorSoundPlayer = null
    }

    private var lastBackPressTime: Long = 0
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 1000) {
                finish()
                return true
            } else {
                lastBackPressTime = currentTime
                Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        pushHandler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) webView.destroy()
        releaseResource(mVariableFertViewModel)
        if (::mapView.isInitialized) mapView.dispose()
    }

    private fun releaseResource(viewModel: VariableFertViewModel) {
        clearErrorSounds()
        if (mDB9reCANseCoroutine.isRunning || mCanReceiveCoroutine.isRunning) {
            mDB9reCANseCoroutine.shutdown()
            mCanReceiveCoroutine.shutdown()
        }
        if (viewModel.serialPortIsRunning.value == true) {
            MySerialPortFun().releaseSerialPort(mVariableFertViewModel)
        }
    }
}
