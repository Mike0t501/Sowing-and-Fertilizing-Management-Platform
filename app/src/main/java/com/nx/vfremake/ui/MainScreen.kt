/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2024年6月29日15:17:24
 * @file    :
 * @brief   :主界面compose
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 左右侧信息栏
 * mapview
 * 全屏视图、位置跟踪、清除、合并已施肥区域图层
 * 菜单栏
 * 更换处方图悬浮dialog
 ***********************************************************************************************************
 */
package com.nx.vfremake.ui

import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.esri.arcgisruntime.geometry.Envelope
import com.esri.arcgisruntime.geometry.GeometryEngine
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.geometry.PolylineBuilder
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.mapping.Viewpoint
import com.esri.arcgisruntime.mapping.view.DefaultMapViewOnTouchListener
import com.esri.arcgisruntime.mapping.view.Graphic
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.esri.arcgisruntime.mapping.view.MapView
import com.esri.arcgisruntime.symbology.PictureMarkerSymbol
import com.esri.arcgisruntime.symbology.SimpleLineSymbol
import com.esri.arcgisruntime.symbology.SimpleMarkerSymbol
import com.esri.arcgisruntime.symbology.TextSymbol
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.data.SowingDepthState
import com.nx.vfremake.data.activeSowingDepthMotorIndices
import com.nx.vfremake.fittingCoefficientA
import com.nx.vfremake.fittingCoefficientB
import com.nx.vfremake.funClass.ConvAndCtrlFun
import com.nx.vfremake.funClass.DocuAndManageFun
import com.nx.vfremake.funClass.ExportGeoFun
import com.nx.vfremake.funClass.MyArcGisFun
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.mSPParamData
import com.nx.vfremake.selectLoadShpFileLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.Surface
import androidx.compose.material.Divider
import androidx.compose.material.Switch
import androidx.compose.material.TextButton
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults

// 视图集合
/**
 * 主视图合集
 * @param  mapView:mapview视图
 * @param  mVariableFertViewModel:更新地图UI viewmodel
 * @param  mVariableFertViewModel:更新信息栏UI viewmodel
 * @param  onClickSettigns:从设置界面返回所要实现功能
 * @param  onClickParamSet:从参数设置界面返回所要实现功能
 * @return
 * @note   mapview视图为了保证更改才重建，从顶层compose里注入
 */
@Composable
fun MainScreen(
    mapView: MapView,
    mVariableFertViewModel: VariableFertViewModel,
    onClickSettigns: () -> Unit = {},
    onClickParamSet: () -> Unit = {},
    onClickSowingDepth: () -> Unit = {},
    onSimGnssReturnToConfig: () -> Unit = {}
) {
    Box(modifier = Modifier.background(Color(LocalContext.current.getColor(R.color.background_color_night)))) {
        MapAndFunBontonVeiw(mapView, mVariableFertViewModel)
        MsgScreenRight(mVariableFertViewModel)
        MsgScreenBottom(mVariableFertViewModel)
        MenuBottomBar(mapView, mVariableFertViewModel, onClickSettigns, onClickParamSet, onClickSowingDepth)
        // 模拟GNSS地图取点浮层：z 序最上，仅取点模式下显示
        SimGnssPickOverlay(mapView, mVariableFertViewModel, onSimGnssReturnToConfig)
    }
}

/**
 * 模拟GNSS地图取点浮层
 * @param  mapView:主地图（处方图已加载其上）
 * @param  mVariableFertViewModel:读取/推进取点模式
 * @param  onReturnToConfig:取点结束回到模拟GNSS配置页
 * @note   仅 simGnssPickMode != 0 时安装单击监听并显示提示浮层；点中即写 SharedPreferences。
 *         经纬度复用 simGnss_startLat/Lon、simGnss_endLat/Lon 键，与配置页一致。
 */
@Composable
fun SimGnssPickOverlay(
    mapView: MapView,
    mVariableFertViewModel: VariableFertViewModel,
    onReturnToConfig: () -> Unit
) {
    val context = LocalContext.current
    val pickMode by mVariableFertViewModel.simGnssPickMode.observeAsState(0)
    val sharedPre = MySharedPreFun(context).getMySharedPre()
    // 起/终标记与连线专用图层（独立于业务图层，取消时单独清除）
    val pickOverlay = remember { GraphicsOverlay() }

    // 经纬度即点即存（6 位小数，约 0.1m 精度，与默认值格式一致）
    fun writeCoord(latKey: Int, lonKey: Int, lat: Double, lon: Double) {
        sharedPre.edit()
            .putString(context.getString(latKey), String.format(Locale.US, "%.6f", lat))
            .putString(context.getString(lonKey), String.format(Locale.US, "%.6f", lon))
            .apply()
    }
    // 画终点连线时从 SharedPreferences 取回起点，避免跨监听器闭包传值
    fun readStartPoint(): Point? {
        val la = sharedPre.getString(context.getString(R.string.simGnss_startLat_name), null)?.toDoubleOrNull()
        val lo = sharedPre.getString(context.getString(R.string.simGnss_startLon_name), null)?.toDoubleOrNull()
        return if (la != null && lo != null) Point(lo, la, SpatialReferences.getWgs84()) else null
    }
    fun drawMarker(p: Point, isStart: Boolean) {
        val fillColor = if (isStart) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        val textColor = if (isStart) 0xFF1B5E20.toInt() else 0xFFB71C1C.toInt()
        pickOverlay.graphics.add(Graphic(p, SimpleMarkerSymbol(SimpleMarkerSymbol.Style.CIRCLE, fillColor, 16f)))
        val label = TextSymbol(
            14f, if (isStart) "起" else "终", textColor,
            TextSymbol.HorizontalAlignment.CENTER, TextSymbol.VerticalAlignment.BOTTOM
        ).apply { offsetY = 12f }
        pickOverlay.graphics.add(Graphic(p, label))
        mapView.post { mapView.invalidate() }
    }
    fun drawLine(start: Point?, end: Point) {
        if (start == null) return
        val line = PolylineBuilder(SpatialReferences.getWgs84()).apply {
            addPoint(start); addPoint(end)
        }.toGeometry()
        pickOverlay.graphics.add(
            Graphic(line, SimpleLineSymbol(SimpleLineSymbol.Style.DASH, 0xFF1565C0.toInt(), 2f))
        )
        mapView.post { mapView.invalidate() }
    }

    // 安装/卸载单击监听 + 进入时清旧标记、关跟随
    LaunchedEffect(pickMode) {
        if (pickMode != 0) {
            if (!mapView.graphicsOverlays.contains(pickOverlay)) mapView.graphicsOverlays.add(pickOverlay)
            mVariableFertViewModel.navCenterIsRunning.value = false  // 取点期间停跟随，地图不乱动
            if (pickMode == 1) {
                pickOverlay.graphics.clear()
                mapView.post { mapView.invalidate() }
            }
            mapView.setOnTouchListener(object : DefaultMapViewOnTouchListener(context, mapView) {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val mapPt = mapView.screenToLocation(android.graphics.Point(e.x.roundToInt(), e.y.roundToInt()))
                    val wgs = mapPt?.let { GeometryEngine.project(it, SpatialReferences.getWgs84()) as? Point }
                    if (wgs == null) {
                        Toast.makeText(context, "请先加载处方图", Toast.LENGTH_SHORT).show()
                        return true
                    }
                    val lat = wgs.y
                    val lon = wgs.x
                    when (mVariableFertViewModel.simGnssPickMode.value) {
                        1 -> {
                            writeCoord(R.string.simGnss_startLat_name, R.string.simGnss_startLon_name, lat, lon)
                            drawMarker(wgs, isStart = true)
                            mVariableFertViewModel.simGnssPickMode.value = 2
                        }
                        2 -> {
                            writeCoord(R.string.simGnss_endLat_name, R.string.simGnss_endLon_name, lat, lon)
                            drawMarker(wgs, isStart = false)
                            drawLine(readStartPoint(), wgs)
                            mVariableFertViewModel.simGnssPickMode.value = 3
                        }
                    }
                    return true
                }
            })
        } else {
            // 退出取点：还原默认触摸 + 兜底清除残留标记/连线
            mapView.setOnTouchListener(DefaultMapViewOnTouchListener(context, mapView))
            if (pickOverlay.graphics.isNotEmpty()) {
                pickOverlay.graphics.clear()
                mapView.post { mapView.invalidate() }
            }
        }
    }

    // 顶部提示浮层
    if (pickMode != 0) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xCC000000), elevation = 8.dp) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when (pickMode) {
                            1 -> "请点击地图选择【起点】"
                            2 -> "请点击地图选择【终点】"
                            else -> "✓ 取点完成"
                        },
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (pickMode == 3) {
                            Button(
                                onClick = {
                                    // 同步清除标记/连线：pickOverlay 挂在长生命周期 mapView 上，
                                    // 不能只靠 LaunchedEffect（导航离开后本 composable 可能已销毁不再执行）
                                    pickOverlay.graphics.clear()
                                    mapView.post { mapView.invalidate() }
                                    mVariableFertViewModel.simGnssPickMode.value = 0
                                    onReturnToConfig()
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4CAF50))
                            ) { Text("返回配置", color = Color.White) }
                            Button(
                                onClick = { mVariableFertViewModel.simGnssPickMode.value = 1 },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                            ) { Text("重新取点", color = Color.White) }
                        } else {
                            Button(
                                onClick = {
                                    pickOverlay.graphics.clear()
                                    mapView.post { mapView.invalidate() }
                                    mVariableFertViewModel.simGnssPickMode.value = 0
                                    onReturnToConfig()
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF757575))
                            ) { Text("取消", color = Color.White) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * mamview视图和小功能按钮视图
 * @param  mapView:mapview视图
 * @param  mVariableFertViewModel:更新地图UI viewmodel
 * @return
 * @note   mapview视图为了保证更改才重建，从顶层compose里注入
 */
@Composable
fun MapAndFunBontonVeiw(mapView: MapView, mVariableFertViewModel: VariableFertViewModel) {

    val context = LocalContext.current
    val mArcGISMap by mVariableFertViewModel.mArcGISMap.observeAsState()
    val shapefileFeatureLayer by mVariableFertViewModel.shapefileFeatureLayer.observeAsState()
    val navMarkGraphicsOverlay by mVariableFertViewModel.navMarkGraphicsOverlay.observeAsState()
    val fertGraphicsOverlay by mVariableFertViewModel.fertGraphicsOverlay.observeAsState()
    val fertGraphicsOverlayExport by mVariableFertViewModel.fertGraphicsOverlayExport.observeAsState()
    val loLaDidegData by mVariableFertViewModel.loLaDidegData.observeAsState(Triple(0.0, 0.0, 0.0))

    // 当前位置跟踪标志位
    val navCenterIsRunning = remember { mutableStateOf(false) }

    //...检查是否设置sharedpre：跟随缩放比例 deltaX/deltaY，可在地图右上角实时增减并持久化
    val deltaX = remember {
        mutableIntStateOf(
            MySharedPreFun(context).getSpecificValue(R.string.deltaX_name)?.toIntOrNull()
                ?: context.resources.getInteger(R.integer.deltaX_value)
        )
    }
    val deltaY = remember {
        mutableIntStateOf(
            MySharedPreFun(context).getSpecificValue(R.string.deltaY_name)?.toIntOrNull()
                ?: context.resources.getInteger(R.integer.deltaY_value)
        )
    }
    //...检查是否设置sharedpre

    // 使用 LaunchedEffect 来响应 loLaDidegData 的变化
    LaunchedEffect(loLaDidegData) {
        while (navCenterIsRunning.value) {
            loLaDidegData?.let { (longitude, latitude, direction) ->
                val point = Point(longitude, latitude, SpatialReferences.getWgs84())
                val deltaXCacu = 0.000001 * deltaX.intValue
                val deltaYCacu = 0.000001 * deltaY.intValue
                // Viewpoint需要传入的参数是Envelope，这里采用显示区域来设置，上述delta修改可以改表区域大小
                val envelope = Envelope(
                    point.x - deltaXCacu, point.y - deltaYCacu,  // 最小 X 和 Y
                    point.x + deltaXCacu, point.y + deltaYCacu   // 最大 X 和 Y
                    , SpatialReferences.getWgs84()
                )
                val viewpoint = Viewpoint(envelope, direction)
                mapView.setViewpointAsync(viewpoint, 0.3f)
                delay(300)
            }
        }
    }

    // 监听mapview视图旋转角度
    LaunchedEffect(mapView) {
        mapView.addMapRotationChangedListener {
            val rotation = mapView.mapRotation
            mVariableFertViewModel.mapViewRotation.value = rotation
            val graphics = navMarkGraphicsOverlay?.graphics
            if (graphics != null) {
                try {
                    for (graphic in graphics) {
                        if (graphic.symbol is PictureMarkerSymbol) {
                            (graphic.symbol as PictureMarkerSymbol).angle =
                                (loLaDidegData.third - rotation).toFloat()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("跟随当前位置", "graphics集合异常")
                }
            }
        }
    }

    // 跟踪当前位置、视图适应屏幕全图居中、清除fertGraphicsOverlay图层按钮
    //... 防止清除图层误触操作
    val clearFertOverlayState = remember { mutableStateOf(false) }
    if (clearFertOverlayState.value) {
        ShowConfirmDialog(
            title = "清除已施肥区域图层",
            text = "你确实要清除已施肥区域的绘制图层吗？",
            onConfirm = {
                // 运行时 drawPoly→fertGraphicsOverlay、drawPolyExport→fertGraphicsOverlayExport
                // 两个图层都画，必须都清，否则黄色施肥轨迹残留
                if (mapView.graphicsOverlays.contains(fertGraphicsOverlay) && fertGraphicsOverlay != null) {
                    fertGraphicsOverlay?.graphics?.clear()
                }
                if (mapView.graphicsOverlays.contains(fertGraphicsOverlayExport) && fertGraphicsOverlayExport != null) {
                    fertGraphicsOverlayExport?.graphics?.clear()
                }
                mapView.post { mapView.invalidate() }
            },
            showDialog = clearFertOverlayState
        )
    }
    //... 防止清除图层误触操作结束

    //... 防止合并图层误触操作
    val mergeFertOverlayState = remember { mutableStateOf(false) }
    if (mergeFertOverlayState.value) {
        ShowConfirmDialog(
            title = "合并已施肥区域图层",
            text = "你确实要合并已施肥区域的绘制图层吗？",
            onConfirm = {
                if (mapView.graphicsOverlays.contains(fertGraphicsOverlay) && fertGraphicsOverlay != null) {
                    // 0.3m 对应的角度制为0.000002703，这个只在最后的时候使用，因为每次调用都会大一圈
                    fertGraphicsOverlay?.let {
                        MyArcGisFun().mergeGraphicsWithBuffer(
                            it,
                            0.0,
                            context
                        )
                    }
                }
            },
            showDialog = mergeFertOverlayState
        )
    }
    //... 防止合并图层误触操作结束
    //... 防止合并图层误触操作
    val exportFertOverlayState = remember { mutableStateOf(false) }
    if (exportFertOverlayState.value) {
        ShowConfirmDialog(
            title = "导出已施肥区域图层",
            text = "你确实要导出已施肥区域的绘制图层吗？",
            onConfirm = {
//                if (mapView.graphicsOverlays.contains(fertGraphicsOverlay) && fertGraphicsOverlay != null) {
//                    fertGraphicsOverlay?.let {
//                        Log.d("Geodatabase Export", "导出开始")
//                        ExportGeoFun(context).createGeodatabase(it)
//                    }
//                    mapView.post { mapView.invalidate() }
//                }
                if (mapView.graphicsOverlays.contains(fertGraphicsOverlayExport) && fertGraphicsOverlayExport != null) {
                    fertGraphicsOverlayExport?.let {
                        Log.d("Geodatabase Export", "导出开始")
                        ExportGeoFun(context).createGeodatabase(it)
                    }
                    mapView.post { mapView.invalidate() }
                }
            },
            showDialog = exportFertOverlayState
        )
    }
    //... 防止合并图层误触操作结束

    val loLaDiData by mVariableFertViewModel.loLaDidegData.observeAsState(Triple(0.0, 0.0, 0.0))

    val colorList by mVariableFertViewModel.colorList.observeAsState(initial = listOf())
    val classBreaksList by mVariableFertViewModel.classBreaksList.observeAsState(initial = listOf())
    // start初始值false，当变为true更新ui
    var shpLoadDone by remember { mutableStateOf(false) }
    // 两个都变化则开始进行 if{}
    LaunchedEffect(colorList, classBreaksList) {
        if (colorList.isNotEmpty() && classBreaksList.isNotEmpty()) {
            shpLoadDone = true
        }
    }

    Box(
        modifier = Modifier
            .padding(dimensionResource(id = R.dimen.small_custom_padding))
            .fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(0.8f)
                .fillMaxHeight(0.8f),
        ) {
            AndroidView(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxHeight()
                    .fillMaxWidth(),
                factory = { mapView },
                // 当mapview某个观测元素变化时， updata包含的变化元素会自动更新
                update = {
                    // 检查图层是否存在，不存在新建，存在避免重复添加同名图层
                    if (!mapView.graphicsOverlays.contains(navMarkGraphicsOverlay)) {
                        if (navMarkGraphicsOverlay != null) {
                            mapView.graphicsOverlays.add(navMarkGraphicsOverlay)
                        }
                    }

                    if (!mapView.graphicsOverlays.contains(fertGraphicsOverlay)) {
                        if (fertGraphicsOverlay != null) {
                            mapView.graphicsOverlays.add(fertGraphicsOverlay)
                        }
                    }
                    if (!mapView.graphicsOverlays.contains(fertGraphicsOverlayExport)) {
                        if (fertGraphicsOverlayExport != null) {
                            mapView.graphicsOverlays.add(fertGraphicsOverlayExport)
                        }
                    }
                    mArcGISMap.let {
                        mapView.map = it
                        mapView.invalidate()
                    }
                    navMarkGraphicsOverlay.let {
                        // 调用invalidate()方法重绘MapView
                        mapView.invalidate()
                    }
                    fertGraphicsOverlay.let {
                        // 调用invalidate()方法重绘MapView
                        mapView.invalidate()
                    }
                    fertGraphicsOverlayExport.let {
                        // 调用invalidate()方法重绘MapView
                        mapView.invalidate()
                    }
                },
            )

            // 处方图右上角：跟随缩放比例加减控件（仅跟随开启时显示）
            // ＋放大=减小delta（视野更近），－缩小=增大delta；X、Y同步，步长±10，范围10~500，写回SharedPreferences
            if (navCenterIsRunning.value) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color(0x88000000), RoundedCornerShape(8.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(onClick = {
                        if (deltaX.intValue > 10) {
                            deltaX.intValue -= 10
                            deltaY.intValue -= 10
                            MySharedPreFun(context).getMySharedPre().edit()
                                .putString(context.getString(R.string.deltaX_name), deltaX.intValue.toString())
                                .putString(context.getString(R.string.deltaY_name), deltaY.intValue.toString())
                                .apply()
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "放大", tint = Color.White)
                    }
                    IconButton(onClick = {
                        if (deltaX.intValue < 500) {
                            deltaX.intValue += 10
                            deltaY.intValue += 10
                            MySharedPreFun(context).getMySharedPre().edit()
                                .putString(context.getString(R.string.deltaX_name), deltaX.intValue.toString())
                                .putString(context.getString(R.string.deltaY_name), deltaY.intValue.toString())
                                .apply()
                        }
                    }) {
                        Icon(Icons.Filled.Remove, contentDescription = "缩小", tint = Color.White)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .padding(start = dimensionResource(id = R.dimen.big_view_padding))
//                    .align(Alignment.CenterStart),
            ) {

                TemplatesNav_LatiLonti(
                    value = loLaDiData.first,
                    value1 = loLaDiData.second,
                    decimalPlaces = 8,
                    unit = "°"
                )

                Spacer(modifier = Modifier.height(30.dp))

                IconButton(
                    // 因为对视图的操作不是在主线程操作，compose会自动响应变化
                    // 要想手动刷新需要mapView.post { mapView.invalidate() }
                    // 不能直接 mapView.invalidate()，不在主线程操作需要post否则没反应
                    onClick = {
                        if (shapefileFeatureLayer?.fullExtent != null) {
                            val viewpoint = Viewpoint(shapefileFeatureLayer?.fullExtent)
                            mapView.setViewpointAsync(viewpoint, 0.2f)
                            mapView.post { mapView.invalidate() }
                            navCenterIsRunning.value = false
                            mVariableFertViewModel.navCenterIsRunning.value = false
                        }
                    }
                ) {
                    Icon(imageVector = Icons.Filled.Fullscreen, contentDescription = "适应屏幕")
                }

                IconButton(onClick = {
                    navCenterIsRunning.value = !navCenterIsRunning.value
                    mVariableFertViewModel.navCenterIsRunning.value =
                        mVariableFertViewModel.navCenterIsRunning.value != true
                }) {
                    Icon(
                        imageVector = if (navCenterIsRunning.value) Icons.Filled.Explore
                        else Icons.Outlined.Explore,
                        contentDescription = "跟随当前位置"
                    )
                }
                IconButton(onClick = { clearFertOverlayState.value = true }) {
                    Icon(
                        imageVector = Icons.Filled.LayersClear,
                        contentDescription = "清除已施肥区域图层"
                    )
                }

                IconButton(onClick = { mergeFertOverlayState.value = true }) {
                    Icon(imageVector = Icons.Filled.Merge, contentDescription = "合并施肥区域图层")
                }
                IconButton(onClick = { exportFertOverlayState.value = true }) {
                    Icon(imageVector = Icons.Filled.Upload, contentDescription = "导出施肥区域图层")
                }

                if (shpLoadDone) {
                    // 将List类型转换成可编辑类型，要在头部增加一个红色
                    val colors = colorList.toMutableList()
                    // 将Color.RED，0值渲染色插入到color0
                    colors.add(0, context.getColor(R.color.zeroZoneRender_color))
                    // 转换成compose色系，Android原生色系不兼容
                    val parsedColors = colors.map { Color(it) }
                    parsedColors.forEachIndexed { index, color ->
                        Box(
                            modifier = Modifier
                                .padding(dimensionResource(id = R.dimen.colorListTip_padding))
                                .background(color),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = classBreaksList[index], style = TextStyle(
                                    color = Color.Black,
                                    fontFamily = FontFamily(Font(R.font.youshehaoshenti)),
                                    fontSize = 12.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 底部菜单
 * @param  mapView:mapView
 * @param  mVariableFertViewModel:更新地图UI viewmodel
 * @param  onClickSettigns:导航页面至设置界面
 * @param  onClickParamSet:导航页面至参数设置界面
 * @return
 * @note
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MenuBottomBar(
    mapView: MapView,
    mVariableFertViewModel: VariableFertViewModel,
    onClickSettigns: () -> Unit = {},
    onClickParamSet: () -> Unit = {},
    onClickSowingDepth: () -> Unit = {}
) {
    val context = LocalContext.current
    val sharedPre = MySharedPreFun(context).getMySharedPre()
    val editor = sharedPre.edit()
    val fertValueFieldResKey = context.getString(R.string.fertQueryField_name)
    val depthValueFieldResKey = context.getString(R.string.depthQueryField_name)

    // 展开/收起菜单逻辑
    val menuIsExpanded = remember { mutableStateOf(false) }
    // 更换处方图界面Dialog
    val showShpMsgDialog = remember { mutableStateOf(false) }

    // 输入字段
    val valueField =
        rememberSaveable {
            mutableStateOf(sharedPre.getString(fertValueFieldResKey, null) ?: "")
        }
    // 处方图深度字段（可选）
    val depthValueField =
        rememberSaveable {
            mutableStateOf(sharedPre.getString(depthValueFieldResKey, null) ?: "")
        }
    // 处方图控深模式开关
    val depthPrescriptionMode by mVariableFertViewModel.depthPrescriptionMode.observeAsState(false)

    // 从viewmodel加载字段列表
    val fieldList by mVariableFertViewModel.fieldsList.observeAsState()
    // 如果viewmodel字段列表已经赋值完成则加载
    val shpLoadDone = remember { mutableStateOf(false) }
    LaunchedEffect(fieldList) {
        if (!fieldList.isNullOrEmpty()) {
            shpLoadDone.value = true
        }
    }

    //... 如果添加了新shp却没有选择字段则警告开始
    val showNoFieldDialogAlert = remember { mutableStateOf(false) }
    if (showNoFieldDialogAlert.value) {
        ShowConfirmDialog(
            title = "警告",
            text = "请在字段列表中选择任意一项。",
            showDialog = showNoFieldDialogAlert,
            showDismiss = false
        )
    }
    //... 如果添加了新shp却没有选择字段则警告结束

    //... 处方图控深「未下发原因」一次性提示弹窗
    val depthNotice by mVariableFertViewModel.depthControlNotice.observeAsState()
    val depthNoticeText = remember { mutableStateOf("") }
    val showDepthNotice = remember { mutableStateOf(false) }
    LaunchedEffect(depthNotice) {
        depthNotice?.let {
            depthNoticeText.value = it
            showDepthNotice.value = true
            mVariableFertViewModel.depthControlNotice.postValue(null)  // 消费一次，避免重复弹
        }
    }
    if (showDepthNotice.value) {
        ShowConfirmDialog(
            title = "处方图控深提示",
            text = depthNoticeText.value,
            showDialog = showDepthNotice,
            showDismiss = false
        )
    }
    //... 处方图控深提示结束

    //... 实验数据保存结果一次性提示：成功 Toast，失败弹窗
    val writeSaveNotice by mVariableFertViewModel.writeSaveNotice.observeAsState()
    val writeSaveFailText = remember { mutableStateOf("") }
    val showWriteSaveFail = remember { mutableStateOf(false) }
    LaunchedEffect(writeSaveNotice) {
        writeSaveNotice?.let { (isFail, msg) ->
            if (isFail) {
                writeSaveFailText.value = msg
                showWriteSaveFail.value = true
            } else {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
            mVariableFertViewModel.writeSaveNotice.postValue(null)  // 消费一次，避免重复弹
        }
    }
    if (showWriteSaveFail.value) {
        ShowConfirmDialog(
            title = "实验数据保存失败",
            text = writeSaveFailText.value,
            showDialog = showWriteSaveFail,
            showDismiss = false
        )
    }
    //... 实验数据保存提示结束

    // 菜单展开栏
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.BottomStart
    ) {
        IconButton(
            modifier = Modifier.padding(start = dimensionResource(id = R.dimen.middle_view_padding)),
            onClick = { menuIsExpanded.value = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "菜单",
                    tint = Color.White
                )
                Text(text = "菜单", color = Color.White)
            }
        }
        DropdownMenu(
            expanded = menuIsExpanded.value,
            onDismissRequest = { menuIsExpanded.value = false }
        ) {
            DropdownMenuItem(
                onClick = {
                    onClickSettigns()
                    menuIsExpanded.value = false // 手动关闭 DropdownMenu
                }
            ) {
                Text("设置")
            }
            DropdownMenuItem(
                onClick = {
                    onClickParamSet()
                    menuIsExpanded.value = false
                }
            ) {
                Text("参数设置")
            }
            DropdownMenuItem(
                onClick = {
                    onClickSowingDepth()
                    menuIsExpanded.value = false
                }
            ) {
                Text("播种深度控制")
            }
            DropdownMenuItem(onClick = {
                // 点击更换处方图时，显示悬浮界面
                val shpPath =
                    MySharedPreFun(context).getSpecificValue(R.string.myLoadShpFile_Path_name)
                MyArcGisFun().getFieldList(shpPath, mVariableFertViewModel)
                showShpMsgDialog.value = true
                menuIsExpanded.value = false
            }) {
                Text("更换处方图")
            }
        }
    }

    // 当点击更换处方图后，显示的悬浮界面
    // 当点击更换处方图后，显示的悬浮界面
    // 当点击更换处方图后，显示的悬浮界面
    // 当点击更换处方图后，显示的悬浮界面
    if (showShpMsgDialog.value) {
        // 动态获取当前选择的文件名，增加 UI 友好度
        val shpFilePath = sharedPre.getString(context.getString(R.string.myLoadShpFile_Path_name), "") ?: ""
        val displayFileName = if (shpFilePath.isNotEmpty()) shpFilePath.substringAfterLast("/") else "尚未选择处方图"

        Dialog(
            onDismissRequest = {
                val fertFieldResValue = sharedPre.getString(fertValueFieldResKey, null)
                val fieldListValue = fieldList
                if (fieldListValue?.contains(fertFieldResValue) != true) {
                    showNoFieldDialogAlert.value = true
                    return@Dialog
                }
                showShpMsgDialog.value = false
            }
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp), // 更大的圆角
                color = Color(0xFFF7F9FC), // 极浅的蓝灰背景，比纯白更有质感
                elevation = 12.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // ================= 标题区 =================
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(bottom = 24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Explore,
                            contentDescription = null,
                            tint = Color(0xFF1976D2),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "处方图配置",
                            style = TextStyle(
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                letterSpacing = 2.sp,
                                color = Color(0xFF2C3E50)
                            )
                        )
                    }

                    // ================= 步骤 1：选择文件 =================
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "步骤 1：选择文件 (.shp)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1976D2),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = displayFileName,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (shpFilePath.isNotEmpty()) Color(0xFF333333) else Color.Gray,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { DocuAndManageFun().selectShapefile(selectLoadShpFileLauncher) },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE3F2FD)),
                                    elevation = ButtonDefaults.elevation(0.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("浏览", color = Color(0xFF1976D2), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // ================= 步骤 2：选择字段 =================
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "步骤 2：选择渲染字段",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (shpLoadDone.value) Color(0xFF1976D2) else Color.Gray,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (!shpLoadDone.value) {
                                // 还没选文件时的空状态占位
                                Text(
                                    text = "请先在上方选择处方图文件",
                                    fontSize = 14.sp,
                                    color = Color.LightGray,
                                    modifier = Modifier.padding(16.dp),
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                // 选完文件后显示的字段列表
                                FlowRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    fieldList?.forEach { field ->
                                        val isSelected = valueField.value == field
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSelected) Color(0xFF1976D2) else Color(0xFFF5F5F5),
                                            border = androidx.compose.foundation.BorderStroke(
                                                width = 1.dp,
                                                color = if (isSelected) Color(0xFF1976D2) else Color.Transparent
                                            ),
                                            modifier = Modifier.clickable {
                                                valueField.value = field
                                                editor.putString(fertValueFieldResKey, field).apply()
                                            }
                                        ) {
                                            Text(
                                                text = field,
                                                color = if (isSelected) Color.White else Color(0xFF555555),
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                                fontSize = 14.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // ================= 步骤 3：播种深度字段 + 控深模式 =================
                    Text(
                        text = "步骤 3：选择播种深度字段（可选，单位 cm）",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (shpLoadDone.value) Color(0xFF1976D2) else Color.Gray,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (!shpLoadDone.value) {
                            Text(
                                text = "请先在上方选择处方图文件",
                                fontSize = 14.sp,
                                color = Color.LightGray,
                                modifier = Modifier.padding(16.dp),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                fieldList?.forEach { field ->
                                    val isSelected = depthValueField.value == field
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) Color(0xFF2E7D32) else Color(0xFFF5F5F5),
                                        border = androidx.compose.foundation.BorderStroke(
                                            width = 1.dp,
                                            color = if (isSelected) Color(0xFF2E7D32) else Color.Transparent
                                        ),
                                        modifier = Modifier.clickable {
                                            depthValueField.value = field
                                            editor.putString(depthValueFieldResKey, field).apply()
                                        }
                                    ) {
                                        Text(
                                            text = field,
                                            color = if (isSelected) Color.White else Color(0xFF555555),
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (depthPrescriptionMode) "处方图工作模式：播深" else "处方图工作模式：施肥",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF333333)
                            )
                            Text(
                                text = "关=施肥 / 开=播深，二者互斥：同一时刻只控制一类电机并显示对应字段的处方图。" +
                                    "播深模式下按\"开始\"自动使能深度电机、\"停止\"自动断使能。",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = depthPrescriptionMode,
                            onCheckedChange = { mVariableFertViewModel.setDepthPrescriptionMode(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // ================= 底部操作按钮区 =================
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                // 按工作模式校验对应字段：播深查深度字段，施肥查施肥字段
                                val activeFieldResKey =
                                    if (depthPrescriptionMode) depthValueFieldResKey else fertValueFieldResKey
                                val activeFieldResValue = sharedPre.getString(activeFieldResKey, null)
                                val fieldListValue = fieldList
                                if (fieldListValue?.contains(activeFieldResValue) != true) {
                                    showNoFieldDialogAlert.value = true
                                    return@TextButton
                                }
                                showShpMsgDialog.value = false
                            }
                        ) {
                            Text("取消", color = Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Button(
                            onClick = {
                                // 【核心修复】：点击渲染前，先检查是否选择了处方图文件！
                                val currentShpPath = sharedPre.getString(context.getString(R.string.myLoadShpFile_Path_name), "") ?: ""
                                if (currentShpPath.isEmpty()) {
                                    android.widget.Toast.makeText(context, "请先浏览并选择 .shp 处方图文件！", android.widget.Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                // 检查是否选择了当前工作模式所需字段
                                val fieldListValue = fieldList
                                if (depthPrescriptionMode) {
                                    // 播深模式：必须选播种深度字段
                                    val depthFieldResValue = sharedPre.getString(depthValueFieldResKey, null) ?: ""
                                    if (fieldListValue?.contains(depthFieldResValue) != true) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "播深模式：请先在步骤 3 选择播种深度字段！",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        return@Button
                                    }
                                } else {
                                    // 施肥模式：必须选施肥字段
                                    val fertFieldResValue = sharedPre.getString(fertValueFieldResKey, null) ?: ""
                                    if (fieldListValue?.contains(fertFieldResValue) != true) {
                                        showNoFieldDialogAlert.value = true
                                        return@Button
                                    }
                                }

                                // 移除旧业务图层和覆盖层
                                mapView.map.operationalLayers.toList().forEach { mapView.map.operationalLayers.remove(it) }
                                mapView.graphicsOverlays.forEach { it.graphics.clear() }
                                mapView.graphicsOverlays.toList().forEach { mapView.graphicsOverlays.remove(it) }

                                // 使用CoroutineScope启动新协程加载
                                CoroutineScope(Dispatchers.Main).launch {
                                    MyArcGisFun().loadShp(context, mVariableFertViewModel)
                                }
                                showShpMsgDialog.value = false // 成功加载后自动关闭弹窗
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2)),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "加载渲染", tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("加载渲染", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 左右侧信息栏
 * @param  mVariableFertViewModel:更新地图UI viewmodel
 * @param  mVariableFertViewModel:更新信息栏UI viewmodel
 * @return
 * @note   因为这里有信息栏，也有图层提示，所以有两个viewmodel
 */
//@Preview(
//    widthDp = 1712,
//    heightDp = 1072
//)
@Composable
fun MsgScreenRight(
    mVariableFertViewModel: VariableFertViewModel = VariableFertViewModel(),
) {
    val loLaDiData by mVariableFertViewModel.loLaDidegData.observeAsState(Triple(0.0, 0.0, 0.0))
    val forwardspeed by mVariableFertViewModel.forwardspeed.observeAsState(0.0)

    // 直接读取 ViewModel 在后台算好的平均值，绝对不卡！
    val avgFert by mVariableFertViewModel.avgFert.observeAsState(0.0)
    val avgAcc by mVariableFertViewModel.avgAccuracy.observeAsState(0.0)

    val context = LocalContext.current
    val sharedPre = MySharedPreFun(context).getMySharedPre()
    val testSendRemember = remember {
        mutableStateOf(
            MySharedPreFun(context).getSpecificValue(R.string.testMode_testSend_name)
                ?: context.getString(R.string.testMode_testSend_defeatValue)
        )
    }
    val isMotorRpmState = remember {
        mutableStateOf(
            (MySharedPreFun(context).getSpecificValue(R.string.testMode_testSendMode_name)
                ?: context.getString(R.string.testMode_testSendMode_defeatValue)) == "0"
        )
    }

    var currentTime by remember { mutableStateOf(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            currentTime = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }
    }

    var isVisible by remember { mutableStateOf(true) }
    LaunchedEffect(mVariableFertViewModel.gnssIsGood.value) {
        while (mVariableFertViewModel.gnssIsGood.value == true) {
            isVisible = !isVisible
            delay(500L)
        }
        if (mVariableFertViewModel.gnssIsGood.value == false) {
            isVisible = true
        }
    }

    Box(
        modifier = Modifier
            .padding(dimensionResource(id = R.dimen.small_custom_padding))
            .fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.2f)
                .fillMaxHeight(0.8f),
        ) {
            Column(
                modifier = Modifier.padding(start = 5.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.small_view_padding))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Yellow, shape = RoundedCornerShape(8.dp)),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TemplatesText_Uint(value = forwardspeed, decimalPlaces = 2, uint = "km/h")
                    TemplatesText_Uint(value = loLaDiData.third, decimalPlaces = 2, uint = "°")
                    Column(
                        modifier = Modifier.padding(5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text = currentTime, style = TextStyle(fontSize = 16.sp))
                        if (isVisible) {
                            Icon(
                                modifier = Modifier.padding(top = 3.dp),
                                imageVector = Icons.Filled.CellTower,
                                contentDescription = "GNSS信号强度",
                            )
                        }
                    }
                }

                // UI 直接渲染已经算好的均值，不再执行 for 循环
                TemplatesRightMsgText_Value(title = "平均施肥量(kg/亩)", doubleArray = doubleArrayOf(avgFert))
                TemplatesRightMsgText_Value(title = "平均准确率(%)", doubleArray = doubleArrayOf(avgAcc))

                if (MySharedPreFun(context).getSpecificValue(R.string.testMode_Switch_name) == "1") {
                    Box(modifier = Modifier.background(Color.White, RoundedCornerShape(8.dp))) {
                        Column(
                            modifier = Modifier.padding(start = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.small_view_padding))
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(top = dimensionResource(id = (R.dimen.big_view_padding))),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(dimensionResource(id = R.dimen.small_custom_padding))
                            ) {
                                MyDiyTextField(
                                    modifier = Modifier.width(100.dp),
                                    value = testSendRemember.value,
                                    onValueChange = { newText ->
                                        testSendRemember.value = newText
                                        sharedPre.edit()
                                            .putString(context.getString(R.string.testMode_testSend_name), newText).apply()
                                    }
                                )
                                Column(
                                    modifier = Modifier.padding(end = dimensionResource(id = R.dimen.middle_view_padding)),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    IconButton(
                                        modifier = Modifier.size(20.dp),
                                        onClick = {
                                            testSendRemember.value = (testSendRemember.value.toInt() + 5).toString()
                                            sharedPre.edit().putString(context.getString(R.string.testMode_testSend_name), testSendRemember.value).apply()
                                        }
                                    ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "+") }

                                    IconButton(
                                        modifier = Modifier.size(20.dp),
                                        onClick = {
                                            testSendRemember.value = (testSendRemember.value.toInt() - 5).toString()
                                            sharedPre.edit().putString(context.getString(R.string.testMode_testSend_name), testSendRemember.value).apply()
                                        }
                                    ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "-") }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(dimensionResource(id = R.dimen.small_custom_padding))
                            ) {
                                Text(
                                    modifier = Modifier.clickable {
                                        isMotorRpmState.value = !isMotorRpmState.value
                                        sharedPre.edit().putString(context.getString(R.string.testMode_testSendMode_name), if (isMotorRpmState.value) "0" else "1").apply()
                                    },
                                    text = if (isMotorRpmState.value) "rpm" else "g/s",
                                    style = TextStyle(color = Color.Black, fontFamily = FontFamily(Font(R.font.youshehaoshenti)), fontSize = 20.sp)
                                )
                                IconButton(
                                    onClick = {
                                        if (mVariableFertViewModel.serialPortIsRunning.value == false) {
                                            MySerialPortFun().openSerialPort(context, mVariableFertViewModel)
                                        }
                                        if (isMotorRpmState.value) {
                                            for (i in 0 until mSPParamData.rowNumber) {
                                                ConvAndCtrlFun().motorSpeedrpmSend(testSendRemember.value.toDouble(), i)
                                            }
                                        } else {
                                            for (i in 0 until mSPParamData.rowNumber) {
                                                val flowtoRpm = ConvAndCtrlFun().fertflowToMotorSpeed(
                                                    testSendRemember.value.toDouble(),
                                                    fittingCoefficientA[i],
                                                    fittingCoefficientB[i]
                                                )
                                                ConvAndCtrlFun().motorSpeedrpmSend(flowtoRpm, i)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 2024年12月12日16:31:34，下诉代码为界面重构修改的代码

@Composable
fun MsgScreenBottom(mVariableFertViewModel: VariableFertViewModel = VariableFertViewModel()) {
    // 初始化时通过判断 rowNumber 保证数组至少有长度，避免一开始为0时崩溃
    val safeSize = if (mSPParamData.rowNumber > 0) mSPParamData.rowNumber else 8

    // 让底部图表直接使用完美计算的显示数据，取代底层抖动的 confertflow
    val confertflow by mVariableFertViewModel.displayFertArray.observeAsState(DoubleArray(safeSize) { 0.0 })
    val fertApplied by mVariableFertViewModel.fertApplied.observeAsState(DoubleArray(safeSize) { 0.0 })
    val sowingDepthState by mVariableFertViewModel.sowingDepthState.observeAsState(SowingDepthState())
    val activeMotorsState by mVariableFertViewModel.activeMotorsState.observeAsState(mSPParamData.activeMotors)

    Box(
        modifier = Modifier
            .padding(dimensionResource(id = R.dimen.small_custom_padding))
            .fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.8f)
                .fillMaxHeight(0.2f),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 100.dp)
                    .fillMaxSize() // 【核心修复】：必须加上 fillMaxSize，否则高度会变成 0！
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(), // 【核心修复】：加上 fillMaxSize 撑开画布高度！
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 安全地获取目标基准值
                    val midIndex = if (mSPParamData.rowNumber > 0) mSPParamData.rowNumber / 2 else 0
                    val targetValue = if (fertApplied.isNotEmpty() && fertApplied.size > midIndex) {
                        fertApplied[midIndex]
                    } else {
                        30.0 // 默认保底基准值
                    }

                    BarChartWithTarget(
                        barValues = confertflow, // 使用动态跳动反馈数组
                        targetValue = targetValue,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight() // 强制图表拉伸到最高，图表彻底恢复显示！
                    )
                    SowingDepthRealtimePanel(
                        state = sowingDepthState,
                        rowNumber = mSPParamData.rowNumber,
                        activeMotors = activeMotorsState,
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun SowingDepthRealtimePanel(
    state: SowingDepthState,
    rowNumber: Int,
    activeMotors: BooleanArray?,
    modifier: Modifier = Modifier
) {
    val activeIndices = activeSowingDepthMotorIndices(rowNumber, activeMotors)
    val activeMotorList = activeIndices.mapNotNull { state.motors.getOrNull(it) }
    val validMotors = activeMotorList.filter { it.isOnline && it.fitValid }
    val avgCurrentDepth = validMotors.takeIf { it.isNotEmpty() }
        ?.map { it.currentDepth.toDouble() }
        ?.average()
    val avgTargetDepth = validMotors
        .map { it.targetDepth }
        .filter { it > 0f }
        .takeIf { it.isNotEmpty() }
        ?.map { it.toDouble() }
        ?.average()
        ?: state.globalTargetDepth.toDouble()
    val onlineCount = activeMotorList.count { it.isOnline }
    val alarmCount = activeMotorList.count { it.alarmCode != 0 }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1F2520))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "播种深度(mm)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (state.masterEnabled) "运行" else "待机",
                color = if (state.masterEnabled) Color(0xFF69F0AE) else Color(0xFFFFD54F),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            DepthSummaryText(
                label = "均深",
                value = avgCurrentDepth?.let { "%.1f".format(Locale.CHINA, it) } ?: "--"
            )
            DepthSummaryText(
                label = "目标",
                value = "%.1f".format(Locale.CHINA, avgTargetDepth)
            )
            DepthSummaryText(
                label = "在线",
                value = "$onlineCount/${activeIndices.size}"
            )
            if (alarmCount > 0) {
                DepthSummaryText(
                    label = "报警",
                    value = alarmCount.toString(),
                    valueColor = Color(0xFFFF5252)
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (activeIndices.isEmpty()) {
                Text(
                    text = "未启用",
                    color = Color(0xFFB0BEC5),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            } else {
                activeIndices.chunked(4).forEach { rowIndices ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowIndices.forEach { index ->
                        DepthMotorCell(
                            motorIndex = index,
                            state = state,
                            modifier = Modifier.weight(1f)
                        )
                    }
                        repeat(4 - rowIndices.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DepthSummaryText(
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = Color(0xFFB0BEC5),
            fontSize = 11.sp,
            maxLines = 1
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun DepthMotorCell(
    motorIndex: Int,
    state: SowingDepthState,
    modifier: Modifier = Modifier
) {
    val motor = state.motors.getOrNull(motorIndex)
    val statusColor = when {
        motor == null || !motor.isOnline -> Color(0xFF616161)
        motor.alarmCode != 0 -> Color(0xFFD32F2F)
        !motor.limitsSet || !motor.fitValid -> Color(0xFFFBC02D)
        else -> Color(0xFF43A047)
    }
    val textColor = if (statusColor == Color(0xFFFBC02D)) Color.Black else Color.White
    val depthText = if (motor?.isOnline == true) {
        "%.1f".format(Locale.CHINA, motor.currentDepth)
    } else {
        "--"
    }

    Row(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(statusColor)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "${motorIndex + 1}",
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = depthText,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.End
        )
    }
}
