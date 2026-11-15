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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.esri.arcgisruntime.geometry.Envelope
import com.esri.arcgisruntime.geometry.GeometryEngine
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.geometry.PolylineBuilder
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.concurrent.ListenableFuture
import com.esri.arcgisruntime.loadable.LoadStatus
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.Basemap
import com.esri.arcgisruntime.mapping.Viewpoint
import com.esri.arcgisruntime.mapping.view.DefaultMapViewOnTouchListener
import com.esri.arcgisruntime.mapping.view.Graphic
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.esri.arcgisruntime.mapping.view.IdentifyGraphicsOverlayResult
import com.esri.arcgisruntime.mapping.view.MapRotationChangedListener
import com.esri.arcgisruntime.mapping.view.MapView
import com.esri.arcgisruntime.symbology.PictureMarkerSymbol
import com.esri.arcgisruntime.symbology.SimpleLineSymbol
import com.esri.arcgisruntime.symbology.SimpleMarkerSymbol
import com.esri.arcgisruntime.symbology.TextSymbol
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.fittingCoefficientA
import com.nx.vfremake.fittingCoefficientB
import com.nx.vfremake.funClass.ConvAndCtrlFun
import com.nx.vfremake.funClass.DocuAndManageFun
import com.nx.vfremake.funClass.ExportGeoFun
import com.nx.vfremake.funClass.MyArcGisFun
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.isSystemRunning
import com.nx.vfremake.lastSentMotorSpeed
import com.nx.vfremake.mSPParamData
import com.nx.vfremake.selectLoadShpFileLauncher
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
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
    val depthMode by mVariableFertViewModel.depthPrescriptionMode.observeAsState(false)
    val workRunning by mVariableFertViewModel.workRunning.observeAsState(false)
    val depthMarkers = remember(mapView) { GraphicsOverlay() }
    var selectedDepthRow by rememberSaveable { mutableStateOf<Int?>(null) }
    BackHandler(enabled = depthMode && selectedDepthRow != null) { selectedDepthRow = null }
    LaunchedEffect(depthMode) {
        if (!depthMode) selectedDepthRow = null
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(LocalContext.current.getColor(R.color.background_color_night)))
    ) {
        val wideDepthPanel = maxWidth >= 820.dp
        val mapWidth = when {
            !depthMode -> 0.8f
            wideDepthPanel -> 0.65f
            else -> 0.57f
        }
        MapAndFunBontonVeiw(mapView, mVariableFertViewModel, depthMode, workRunning, mapWidth)
        if (depthMode) {
            DepthMainPanel(
                mapView = mapView,
                rowOverlay = depthMarkers,
                viewModel = mVariableFertViewModel,
                selectedRow = selectedDepthRow,
                onSelectRow = { selectedDepthRow = it },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(1f - mapWidth)
                    .fillMaxHeight()
                    .padding(start = 5.dp, top = 5.dp, end = 5.dp, bottom = if (wideDepthPanel) 64.dp else 138.dp)
            )
        } else {
            MsgScreenRight(mVariableFertViewModel)
            MsgScreenBottom(mVariableFertViewModel)
        }
        MenuBottomBar(mVariableFertViewModel, depthMode, workRunning, onClickSettigns, onClickParamSet, onClickSowingDepth)
        // 模拟GNSS地图取点浮层：z 序最上，仅取点模式下显示
        SimGnssPickOverlay(
            mapView,
            mVariableFertViewModel,
            onSimGnssReturnToConfig,
            depthMarkers = depthMarkers.takeIf { depthMode },
            onDepthRowTap = { selectedDepthRow = it }
        )
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
private fun SimGnssPickOverlay(
    mapView: MapView,
    mVariableFertViewModel: VariableFertViewModel,
    onReturnToConfig: () -> Unit,
    depthMarkers: GraphicsOverlay?,
    onDepthRowTap: (Int) -> Unit
) {
    val context = LocalContext.current
    val pickMode by mVariableFertViewModel.simGnssPickMode.observeAsState(0)
    val sharedPre = MySharedPreFun(context).getMySharedPre()
    val selectDepthRow by rememberUpdatedState(onDepthRowTap)
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

    // 进入取点时关跟随，退出时清理取点图形。
    LaunchedEffect(pickMode) {
        if (pickMode != 0) {
            if (!mapView.graphicsOverlays.contains(pickOverlay)) mapView.graphicsOverlays.add(pickOverlay)
            mVariableFertViewModel.navCenterIsRunning.value = false  // 取点期间停跟随，地图不乱动
            if (pickMode == 1) {
                pickOverlay.graphics.clear()
                mapView.post { mapView.invalidate() }
            }
        } else {
            pickOverlay.graphics.clear()
            mapView.graphicsOverlays.remove(pickOverlay)
        }
    }

    DisposableEffect(mapView, pickOverlay) {
        onDispose { mapView.graphicsOverlays.remove(pickOverlay) }
    }

    DisposableEffect(mapView, pickMode, depthMarkers) {
        var pendingTap: ListenableFuture<IdentifyGraphicsOverlayResult>? = null
        mapView.setOnTouchListener(object : DefaultMapViewOnTouchListener(context, mapView) {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (pickMode != 0) {
                    val mapPt = mapView.screenToLocation(android.graphics.Point(e.x.roundToInt(), e.y.roundToInt()))
                    val wgs = mapPt?.let { GeometryEngine.project(it, SpatialReferences.getWgs84()) as? Point }
                    if (wgs == null) {
                        Toast.makeText(context, "地图尚未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
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
                if (depthMarkers != null && !mVariableFertViewModel.depthRowPositions.value.isNullOrEmpty()) {
                    pendingTap?.cancel(true)
                    val tap = android.graphics.Point(e.x.roundToInt(), e.y.roundToInt())
                    val hit = mapView.identifyGraphicsOverlayAsync(
                        depthMarkers, tap, 8.0, false, 16
                    )
                    pendingTap = hit
                    hit.addDoneListener {
                        val candidates = runCatching { hit.get().graphics }.getOrNull() ?: return@addDoneListener
                        mapView.post {
                            if (pendingTap !== hit) return@post
                            val closest = candidates.asSequence().filter { it.isVisible }.minByOrNull { graphic ->
                                val screen = (graphic.geometry as? Point)?.let(mapView::locationToScreen)
                                if (screen == null) Double.POSITIVE_INFINITY else {
                                    val dx = screen.x.toDouble() - tap.x
                                    val dy = screen.y.toDouble() - tap.y
                                    dx * dx + dy * dy
                                }
                            }
                            val row = closest?.attributes?.get(DEPTH_MARKER_ROW) as? Int
                            if (row != null) selectDepthRow(row)
                        }
                    }
                }
                return super.onSingleTapConfirmed(e)
            }
        })
        onDispose {
            pendingTap?.cancel(true)
            pendingTap = null
            mapView.setOnTouchListener(DefaultMapViewOnTouchListener(context, mapView))
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
private fun MapAndFunBontonVeiw(
    mapView: MapView,
    mVariableFertViewModel: VariableFertViewModel,
    depthMode: Boolean,
    workRunning: Boolean,
    mapWidth: Float
) {

    val context = LocalContext.current
    val mArcGISMap by mVariableFertViewModel.mArcGISMap.observeAsState()
    val shapefileFeatureLayer by mVariableFertViewModel.shapefileFeatureLayer.observeAsState()
    val navMarkGraphicsOverlay by mVariableFertViewModel.navMarkGraphicsOverlay.observeAsState()
    val fertGraphicsOverlay by mVariableFertViewModel.fertGraphicsOverlay.observeAsState()
    val fertGraphicsOverlayExport by mVariableFertViewModel.fertGraphicsOverlayExport.observeAsState()
    val loLaDidegData by mVariableFertViewModel.loLaDidegData.observeAsState()
    val depthRowPositions by mVariableFertViewModel.depthRowPositions.observeAsState()
    val navCenterIsRunning by mVariableFertViewModel.navCenterIsRunning.observeAsState(false)
    val blankMap = remember {
        ArcGISMap(SpatialReferences.getWebMercator()).apply { basemap = Basemap() }
    }
    val visibleMap = mArcGISMap ?: blankMap
    var mapLoaded by remember(visibleMap) { mutableStateOf(visibleMap.loadStatus == LoadStatus.LOADED) }
    DisposableEffect(visibleMap) {
        val listener = Runnable { mapLoaded = visibleMap.loadStatus == LoadStatus.LOADED }
        visibleMap.addDoneLoadingListener(listener)
        onDispose { visibleMap.removeDoneLoadingListener(listener) }
    }

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

    val firstRow = depthRowPositions?.firstOrNull()
    val lastRow = depthRowPositions?.lastOrNull()
    val center = loLaDidegData?.let { (longitude, latitude, _) ->
        if (depthMode && firstRow != null && lastRow != null) {
            Point((firstRow.x + lastRow.x) / 2, (firstRow.y + lastRow.y) / 2, firstRow.spatialReference)
        } else {
            Point(longitude, latitude, SpatialReferences.getWgs84())
        }
    }
    val direction = loLaDidegData?.third ?: 0.0
    LaunchedEffect(navCenterIsRunning, mapLoaded, visibleMap, center, direction, deltaX.intValue, deltaY.intValue) {
        if (navCenterIsRunning && mapLoaded && center != null) {
            val horizontal = deltaX.intValue * 0.000001
            val vertical = deltaY.intValue * 0.000001
            val envelope = Envelope(
                center.x - horizontal, center.y - vertical,
                center.x + horizontal, center.y + vertical,
                center.spatialReference
            )
            mapView.setViewpointAsync(Viewpoint(envelope, direction), 0.15f)
        }
    }

    DisposableEffect(mapView, mVariableFertViewModel) {
        val listener = MapRotationChangedListener {
            val rotation = mapView.mapRotation
            mVariableFertViewModel.mapViewRotation.value = rotation
            val currentDirection = mVariableFertViewModel.loLaDidegData.value?.third
            if (currentDirection != null) {
                val tractor = mVariableFertViewModel.navMarkGraphicsOverlay.value?.graphics?.firstOrNull()
                (tractor?.symbol as? PictureMarkerSymbol)?.angle = (currentDirection - rotation).toFloat()
            }
        }
        mapView.addMapRotationChangedListener(listener)
        onDispose { mapView.removeMapRotationChangedListener(listener) }
    }

    // 跟踪当前位置、视图适应屏幕全图居中、清除fertGraphicsOverlay图层按钮
    //... 防止清除图层误触操作
    val clearFertOverlayState = remember { mutableStateOf(false) }
    if (!depthMode && clearFertOverlayState.value) {
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
    if (!depthMode && mergeFertOverlayState.value) {
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
    if (!depthMode && exportFertOverlayState.value) {
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

    val colorList by mVariableFertViewModel.colorList.observeAsState(initial = listOf())
    val classBreaksList by mVariableFertViewModel.classBreaksList.observeAsState(initial = listOf())

    Box(
        modifier = Modifier
            .padding(dimensionResource(id = R.dimen.small_custom_padding))
            .fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(mapWidth)
                .fillMaxHeight(if (depthMode) 0.90f else 0.8f),
        ) {
            AndroidView(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxHeight()
                    .fillMaxWidth(),
                factory = { mapView },
                update = {
                    if (mapView.map !== visibleMap) mapView.map = visibleMap
                    navMarkGraphicsOverlay?.let { overlay ->
                        if (!mapView.graphicsOverlays.contains(overlay)) mapView.graphicsOverlays.add(0, overlay)
                        overlay.isVisible = loLaDidegData != null
                    }
                    listOfNotNull(fertGraphicsOverlay, fertGraphicsOverlayExport).forEach { overlay ->
                        if (!mapView.graphicsOverlays.contains(overlay)) mapView.graphicsOverlays.add(overlay)
                        overlay.isVisible = !depthMode
                    }
                },
            )

            if (navCenterIsRunning && center != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
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

            loLaDidegData?.let { (longitude, latitude, _) ->
                Column(
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    TemplatesNav_LatiLonti(value = longitude, value1 = latitude, decimalPlaces = 8, unit = "°")
                    if (depthMode) {
                        val speed by mVariableFertViewModel.forwardspeed.observeAsState(0.0)
                        Text(String.format(Locale.ROOT, "%.1f km/h", speed), color = Color.Black, fontSize = 16.sp)
                    }
                }
            }

            Column(modifier = Modifier.padding(start = 8.dp, top = 60.dp)) {
                IconButton(
                    enabled = shapefileFeatureLayer?.fullExtent != null,
                    onClick = {
                        shapefileFeatureLayer?.fullExtent?.let { extent ->
                            mVariableFertViewModel.navCenterIsRunning.value = false
                            mapView.setViewpointAsync(Viewpoint(extent), 0.2f)
                        }
                    }
                ) {
                    Icon(imageVector = Icons.Filled.Fullscreen, contentDescription = "适应屏幕")
                }

                IconButton(onClick = {
                    mVariableFertViewModel.navCenterIsRunning.value = !navCenterIsRunning
                }) {
                    Icon(
                        imageVector = if (navCenterIsRunning) Icons.Filled.Explore
                        else Icons.Outlined.Explore,
                        contentDescription = "跟随当前位置"
                    )
                }
                if (!depthMode) {
                    IconButton(onClick = { clearFertOverlayState.value = true }) {
                        Icon(Icons.Filled.LayersClear, contentDescription = "清除已施肥区域图层")
                    }
                    IconButton(onClick = { mergeFertOverlayState.value = true }) {
                        Icon(Icons.Filled.Merge, contentDescription = "合并施肥区域图层")
                    }
                    IconButton(onClick = { exportFertOverlayState.value = true }) {
                        Icon(Icons.Filled.Upload, contentDescription = "导出施肥区域图层")
                    }
                }
            }

            if (shapefileFeatureLayer != null && colorList.isNotEmpty()) {
                val narrowDepthMap = depthMode && mapWidth < 0.6f
                Column(
                    modifier = Modifier
                        .align(if (narrowDepthMap) Alignment.TopStart else Alignment.CenterStart)
                        .padding(start = 60.dp, top = if (narrowDepthMap) 156.dp else 0.dp)
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (depthMode) {
                        Text("目标播深 (cm)", color = Color(0xFF22352A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    val colors = listOf(context.getColor(R.color.zeroZoneRender_color)) + colorList
                    colors.zip(classBreaksList).forEach { (color, range) ->
                        Box(
                            modifier = Modifier
                                .padding(dimensionResource(id = R.dimen.colorListTip_padding))
                                .background(Color(color)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = range, style = TextStyle(
                                    color = Color.Black,
                                    fontFamily = FontFamily(Font(R.font.youshehaoshenti)),
                                    fontSize = 12.sp
                                )
                            )
                        }
                    }
                }
            }

            val depthPositionNotice = when {
                !depthMode -> null
                center == null && !workRunning -> "启动作业后获取 GNSS 定位"
                center == null -> "等待 GNSS 定位"
                depthRowPositions.isNullOrEmpty() -> "等待行进航向以定位各单体"
                else -> null
            }
            if (depthPositionNotice != null) {
                Surface(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                    color = Color(0xE52C3531),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = depthPositionNotice,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            WorkModeSelector(
                depthMode = depthMode,
                enabled = !workRunning,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                onSelect = { enabled ->
                    if (enabled != depthMode && mVariableFertViewModel.workRunning.value != true) {
                        mVariableFertViewModel.setDepthPrescriptionMode(enabled)
                        mVariableFertViewModel.navCenterIsRunning.value = enabled
                        MyArcGisFun().loadShp(context, mVariableFertViewModel, enabled)
                    }
                }
            )
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
@Composable
private fun WorkModeSelector(
    depthMode: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Boolean) -> Unit
) {
    Surface(modifier = modifier, color = Color(0xED202826), shape = RoundedCornerShape(10.dp)) {
        Row(modifier = Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(false to "施肥", true to "播深").forEach { (mode, label) ->
                val selected = mode == depthMode
                Surface(
                    color = if (selected) Color(0xFF16764C) else Color.Transparent,
                    shape = RoundedCornerShape(7.dp)
                ) {
                    TextButton(
                        onClick = { onSelect(mode) },
                        enabled = enabled && !selected,
                        modifier = Modifier.width(70.dp)
                    ) {
                        Text(
                            label,
                            color = if (enabled || selected) Color.White else Color(0xFFB0BDB6),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MenuBottomBar(
    mVariableFertViewModel: VariableFertViewModel,
    depthMode: Boolean,
    workRunning: Boolean,
    onClickSettigns: () -> Unit,
    onClickParamSet: () -> Unit,
    onClickSowingDepth: () -> Unit
) {
    val context = LocalContext.current
    val mapFunctions = remember { MyArcGisFun() }
    val fieldKey = if (depthMode) R.string.depthQueryField_name else R.string.fertQueryField_name
    val fieldList by mVariableFertViewModel.fieldsList.observeAsState()
    val draftPath by mVariableFertViewModel.prescriptionDraftPath.observeAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    var showPrescriptionDialog by remember { mutableStateOf(false) }
    var selectedField by rememberSaveable(depthMode) { mutableStateOf("") }
    val workIdle = !workRunning

    val depthNotice by mVariableFertViewModel.depthControlNotice.observeAsState()
    var depthNoticeText by remember { mutableStateOf("") }
    val showDepthNotice = remember { mutableStateOf(false) }
    LaunchedEffect(depthMode, depthNotice) {
        if (depthMode) {
            depthNotice?.let { message ->
                depthNoticeText = message
                showDepthNotice.value = true
                mVariableFertViewModel.depthControlNotice.value = null
            }
        } else {
            showDepthNotice.value = false
        }
    }
    if (depthMode && showDepthNotice.value) {
        ShowConfirmDialog(
            title = "播深控制提示",
            text = depthNoticeText,
            showDialog = showDepthNotice,
            showDismiss = false
        )
    }

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
            mVariableFertViewModel.writeSaveNotice.value = null
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

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        TextButton(
            modifier = Modifier.padding(start = 8.dp),
            onClick = { menuExpanded = true }
        ) {
            Icon(Icons.Filled.Menu, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(4.dp))
            Text("菜单", color = Color.White)
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                enabled = workIdle,
                onClick = {
                    menuExpanded = false
                    onClickSettigns()
                }
            ) { Text("设置") }
            DropdownMenuItem(
                enabled = workIdle,
                onClick = {
                    menuExpanded = false
                    onClickParamSet()
                }
            ) { Text("参数设置") }
            if (depthMode) {
                DropdownMenuItem(
                    enabled = workIdle,
                    onClick = {
                        menuExpanded = false
                        onClickSowingDepth()
                    }
                ) { Text("播种深度设置") }
            }
            DropdownMenuItem(
                enabled = workIdle,
                onClick = {
                    selectedField = MySharedPreFun(context).getSpecificValue(fieldKey).orEmpty()
                    mapFunctions.getFieldList(mapFunctions.configuredShpPath(context, depthMode), mVariableFertViewModel)
                    menuExpanded = false
                    showPrescriptionDialog = true
                }
            ) { Text(if (depthMode) "播深处方图" else "施肥处方图") }
        }
    }

    if (showPrescriptionDialog && workIdle) {
        val path = draftPath
        val configuredPath = mapFunctions.configuredShpPath(context, depthMode)
        val title = if (depthMode) "播深处方图" else "施肥处方图"
        val fieldTitle = if (depthMode) "播种深度字段（cm）" else "施肥量字段（kg/亩）"
        val fieldAvailable = fieldList?.contains(selectedField) == true
        Dialog(onDismissRequest = { showPrescriptionDialog = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFF7F9FC), elevation = 12.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = path?.let { java.io.File(it).name } ?: "尚未选择处方图",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { DocuAndManageFun().selectShapefile(selectLoadShpFileLauncher) }) {
                            Text("浏览 .shp")
                        }
                    }
                    Text(
                        "若无法直接读取 .shp，请同时选择同名的 .shp、.shx、.dbf 及 .prj（如有）文件。",
                        color = Color(0xFF52635B),
                        fontSize = 13.sp
                    )
                    Text(fieldTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (fieldList.isNullOrEmpty()) {
                        Text(
                            when {
                                path == null -> "请先选择 .shp 文件"
                                fieldList == null -> "正在读取字段…"
                                else -> "未读取到可用的数值字段"
                            },
                            color = Color(0xFF52635B)
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            fieldList.orEmpty().forEach { field ->
                                val selected = selectedField == field
                                Surface(
                                    color = if (selected) Color(0xFF16764C) else Color(0xFFE4EBE7),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    TextButton(onClick = { selectedField = field }) {
                                        Text(field, color = if (selected) Color.White else Color(0xFF193327))
                                    }
                                }
                            }
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (configuredPath != null) {
                            TextButton(onClick = {
                                if (mVariableFertViewModel.workRunning.value != true) {
                                    mapFunctions.clearPrescription(context, mVariableFertViewModel, depthMode)
                                    showPrescriptionDialog = false
                                }
                            }) { Text(if (depthMode) "改用固定播深" else "移除处方图") }
                        }
                        TextButton(onClick = { showPrescriptionDialog = false }) { Text("取消") }
                        Button(
                            enabled = path != null && fieldAvailable,
                            onClick = {
                                if (path != null && mVariableFertViewModel.workRunning.value != true) {
                                    mapFunctions.savePrescription(context, mVariableFertViewModel, depthMode, path, selectedField)
                                    showPrescriptionDialog = false
                                }
                            }
                        ) { Text("应用处方图") }
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
    val loLaDiData by mVariableFertViewModel.loLaDidegData.observeAsState()
    val forwardspeed by mVariableFertViewModel.forwardspeed.observeAsState(0.0)
    val workRunning by mVariableFertViewModel.workRunning.observeAsState(false)

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
                    TemplatesText_Uint(value = loLaDiData?.third ?: 0.0, decimalPlaces = 2, uint = "°")
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
                                    enabled = !workRunning,
                                    onClick = send@{
                                        if (mVariableFertViewModel.workRunning.value == true) return@send
                                        val value = testSendRemember.value.toDoubleOrNull()?.takeIf { it.isFinite() }
                                        if (value == null) {
                                            Toast.makeText(context, "请输入有效的测试值", Toast.LENGTH_SHORT).show()
                                            return@send
                                        }
                                        if (!MySerialPortFun.ensureCanPortOpen(context)) {
                                            Toast.makeText(context, "CAN 串口未打开", Toast.LENGTH_SHORT).show()
                                            return@send
                                        }
                                        val converter = ConvAndCtrlFun()
                                        for (i in 0 until mSPParamData.rowNumber) {
                                            val rpm = if (isMotorRpmState.value) value else converter.fertflowToMotorSpeed(
                                                value, fittingCoefficientA[i], fittingCoefficientB[i]
                                            )
                                            converter.motorSpeedrpmSend(rpm, i)
                                        }
                                        if (lastSentMotorSpeed.any { it >= 1.0 }) {
                                            isSystemRunning = true
                                            mVariableFertViewModel.workRunning.value = true
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
private fun MsgScreenBottom(mVariableFertViewModel: VariableFertViewModel) {
    val safeSize = mSPParamData.rowNumber.takeIf { it > 0 } ?: 8
    val confertflow by mVariableFertViewModel.displayFertArray.observeAsState(DoubleArray(safeSize))
    val fertApplied by mVariableFertViewModel.fertApplied.observeAsState(DoubleArray(safeSize))
    val target = fertApplied.getOrNull(mSPParamData.rowNumber / 2) ?: 30.0

    Box(
        modifier = Modifier
            .padding(dimensionResource(id = R.dimen.small_custom_padding))
            .fillMaxSize()
    ) {
        BarChartWithTarget(
            barValues = confertflow,
            targetValue = target,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.8f)
                .fillMaxHeight(0.2f)
                .padding(start = 100.dp)
        )
    }
}
