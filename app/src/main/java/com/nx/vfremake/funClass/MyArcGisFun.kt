/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2024年6月29日15:29:28
 * @file    :
 * @brief   :shp地图、图层...操作有关
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 加载渲染shp地图
 * 添加当前位置mark
 * 绘制已施肥区域
 * 合并施肥区域图层
 ***********************************************************************************************************
 */
package com.nx.vfremake.funClass

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.esri.arcgisruntime.data.Field
import com.esri.arcgisruntime.data.QueryParameters
import com.esri.arcgisruntime.data.ShapefileFeatureTable
import com.esri.arcgisruntime.geometry.AngularUnit
import com.esri.arcgisruntime.geometry.AngularUnitId
import com.esri.arcgisruntime.geometry.GeodeticCurveType
import com.esri.arcgisruntime.geometry.GeometryEngine
import com.esri.arcgisruntime.geometry.LinearUnit
import com.esri.arcgisruntime.geometry.LinearUnitId
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.geometry.Polygon
import com.esri.arcgisruntime.geometry.PolygonBuilder
import com.esri.arcgisruntime.geometry.SpatialReference
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.layers.FeatureLayer
import com.esri.arcgisruntime.loadable.LoadStatus
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.Basemap
import com.esri.arcgisruntime.mapping.Viewpoint
import com.esri.arcgisruntime.mapping.view.Graphic
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.esri.arcgisruntime.symbology.ClassBreaksRenderer
import com.esri.arcgisruntime.symbology.PictureMarkerSymbol
import com.esri.arcgisruntime.symbology.SimpleFillSymbol
import com.esri.arcgisruntime.symbology.SimpleLineSymbol
import com.nx.vfremake.DEPTH_MAP_CM_TO_MM
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.data.activeSowingDepthMotorIndices
import com.nx.vfremake.data.DepthPrescriptionSource
import com.nx.vfremake.depthQueryField
import com.nx.vfremake.fertQueryField
import com.nx.vfremake.fittingCoefficientA
import com.nx.vfremake.fittingCoefficientB
import com.nx.vfremake.isSystemRunning
import com.nx.vfremake.mRmcData
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin


class MyArcGisFun {

    companion object {
        private const val CLEARED_PATH = "-1"
        private val mapLoadRevision = AtomicLong()
        private val fieldListRevision = AtomicLong()
        private val markerHandler by lazy { Handler(Looper.getMainLooper()) }
        private val numericFieldTypes = setOf(
            Field.Type.SHORT, Field.Type.INTEGER, Field.Type.FLOAT, Field.Type.DOUBLE
        )
        private val meters by lazy { LinearUnit(LinearUnitId.METERS) }
        private val degrees by lazy { AngularUnit(AngularUnitId.DEGREES) }
    }

    private fun pathKey(depthMode: Boolean) =
        if (depthMode) R.string.depthShpFilePath_name else R.string.myLoadShpFile_Path_name

    private fun fieldKey(depthMode: Boolean) =
        if (depthMode) R.string.depthQueryField_name else R.string.fertQueryField_name

    fun configuredShpPath(context: Context, depthMode: Boolean): String? =
        MySharedPreFun(context).getSpecificValue(pathKey(depthMode))
            ?.takeIf { it.isNotBlank() && it != CLEARED_PATH }

    fun savePrescription(
        context: Context,
        viewModel: VariableFertViewModel,
        depthMode: Boolean,
        path: String,
        field: String,
    ) {
        MySharedPreFun(context).getMySharedPre().edit()
            .putString(context.getString(pathKey(depthMode)), path)
            .putString(context.getString(fieldKey(depthMode)), field)
            .apply()
        loadShp(context, viewModel, depthMode)
    }

    fun clearPrescription(context: Context, viewModel: VariableFertViewModel, depthMode: Boolean) {
        // 保留显式清除标记，避免默认配置恢复旧图或再次迁移旧版共享图。
        MySharedPreFun(context).getMySharedPre().edit()
            .putString(context.getString(pathKey(depthMode)), CLEARED_PATH)
            .apply()
        getFieldList(null, viewModel)
        loadShp(context, viewModel, depthMode)
    }

    //...加载渲染地图有关
    /**
     * 加载总成
     * @param   context:上下文
     * @param   mVariableFertViewModel:更新UI使用的viewmodel
     * @return
     * @note
     */
    fun loadShp(
        context: Context,
        mVariableFertViewModel: VariableFertViewModel,
        depthMode: Boolean = mVariableFertViewModel.depthPrescriptionMode.value == true,
    ) {
        val preferences = MySharedPreFun(context)
        val renderField = preferences.getSpecificValue(fieldKey(depthMode)).orEmpty()
        val explicitPath = configuredShpPath(context, depthMode)
        val legacyDepthPath = if (depthMode && renderField.isNotBlank() &&
            preferences.getSpecificValue(pathKey(true)) == null
        ) configuredShpPath(context, false) else null
        val shpPath = explicitPath ?: legacyDepthPath
        val revision = mapLoadRevision.incrementAndGet()
        val viewModel = mVariableFertViewModel

        viewModel.shapefileFeatureTable.value = null
        viewModel.shapefileFeatureLayer.value = null
        viewModel.mArcGISMap.value = null
        viewModel.colorList.value = emptyList()
        viewModel.classBreaksList.value = emptyList()
        fertQueryField = ""
        depthQueryField = ""
        if (depthMode) {
            viewModel.setDepthPrescriptionSource(
                if (shpPath == null) DepthPrescriptionSource.FIXED else DepthPrescriptionSource.LOADING
            )
        }
        if (shpPath == null) return

        val table = ShapefileFeatureTable(shpPath)
        table.addDoneLoadingListener {
            if (mapLoadRevision.get() != revision) return@addDoneLoadingListener
            if (table.loadStatus != LoadStatus.LOADED) {
                Log.e("ShapefileFeatureTable", "处方图加载失败", table.loadError)
                if (depthMode) viewModel.setDepthPrescriptionSource(
                    if (explicitPath == null) DepthPrescriptionSource.FIXED else DepthPrescriptionSource.UNAVAILABLE
                )
                return@addDoneLoadingListener
            }
            if (renderField !in prescriptionFields(table)) {
                if (depthMode) viewModel.setDepthPrescriptionSource(
                    if (explicitPath == null) DepthPrescriptionSource.FIXED else DepthPrescriptionSource.MISSING_FIELD
                )
                return@addDoneLoadingListener
            }
            if (depthMode) {
                depthQueryField = renderField
                if (explicitPath == null) preferences.getMySharedPre().edit()
                    .putString(context.getString(pathKey(true)), shpPath)
                    .apply()
            } else {
                fertQueryField = renderField
            }

            val layer = FeatureLayer(table)
            val map = ArcGISMap(table.spatialReference ?: SpatialReferences.getWgs84()).apply {
                basemap = Basemap()
                operationalLayers.add(layer)
                table.extent?.takeUnless { it.isEmpty }?.let { initialViewpoint = Viewpoint(it) }
            }
            viewModel.shapefileFeatureLayer.value = layer
            viewModel.shapefileFeatureTable.value = table
            viewModel.mArcGISMap.value = map
            if (depthMode) viewModel.setDepthPrescriptionSource(DepthPrescriptionSource.PRESCRIPTION)
            renderPrescription(table, layer, renderField, viewModel, context, revision)
        }
        table.loadAsync()
    }

    /**
     * 获取字段列表，使用viewmodel存储
     * @param  shpFilePath:shp存储路径
     * @param   mVariableFertViewModel:更新UI使用的viewmodel
     * @return
     * @note
     */
    fun getFieldList(shpFilePath: String?, mVariableFertViewModel: VariableFertViewModel) {
        val revision = fieldListRevision.incrementAndGet()
        mVariableFertViewModel.prescriptionDraftPath.value = shpFilePath
        mVariableFertViewModel.fieldsList.value = if (shpFilePath.isNullOrBlank()) emptyList() else null
        if (shpFilePath.isNullOrBlank()) return

        val table = ShapefileFeatureTable(shpFilePath)
        table.addDoneLoadingListener {
            if (fieldListRevision.get() == revision) {
                mVariableFertViewModel.fieldsList.value = if (table.loadStatus == LoadStatus.LOADED)
                    prescriptionFields(table) else emptyList()
            }
        }
        table.loadAsync()
    }

    private fun prescriptionFields(table: ShapefileFeatureTable): List<String> =
        table.fields.filter { it.fieldType in numericFieldTypes }.map { it.name }

    /**
     * 数字格式化
     * @param  number:要格式化的数
     * @return 格式化后的数字
     * @note   形式00.00，施肥量不过百，过百重写
     */
     fun formatNumber(number: Double): String {
        // 考虑到显示的时候的四舍五入
        return if (number < 9.995) "0" + String.format(Locale.CHINA, "%.2f", number)
        else String.format(Locale.CHINA, "%.2f", number)
    }

    /**
     *  颜色分级，渲染器设置需要分级颜色
     * @param   startColor:开始颜色
     * @param   endColor:终止颜色
     * @param   steps:分级数
     * @return  colors:#颜色值，标黄不用管，需要改颜色在调用的地方改
     * @note    使用方法：val colorList = generateGradient("B2FF66", "FFFF66", 5)，不用带#
     */
    private fun generateGradient(
        startColor: Int, endColor: Int, steps: Int
    ): List<Int> {
        if (steps == 1) return listOf(startColor)
        // 解析开始颜色和结束颜色的RGB值
        val startA = (startColor shr 24) and 0xff
        val startR = (startColor shr 16) and 0xff
        val startG = (startColor shr 8) and 0xff
        val startB = startColor and 0xff

        val endA = (endColor shr 24) and 0xff
        val endR = (endColor shr 16) and 0xff
        val endG = (endColor shr 8) and 0xff
        val endB = endColor and 0xff

        // 计算每个步骤之间的ARGB差值
        val stepA = (endA - startA) / (steps - 1.0f)
        val stepR = (endR - startR) / (steps - 1.0f)
        val stepG = (endG - startG) / (steps - 1.0f)
        val stepB = (endB - startB) / (steps - 1.0f)

        // 存储生成的颜色
        val colors = ArrayList<Int>()
        for (i in 0 until steps) {
            // 计算每个步骤的颜色
            val a = (startA + (i * stepA)).roundToInt()
            val r = (startR + (i * stepR)).roundToInt()
            val g = (startG + (i * stepG)).roundToInt()
            val b = (startB + (i * stepB)).roundToInt()

            // 组合成新的颜色值
            val color = (a shl 24) or (r shl 16) or (g shl 8) or b
            colors.add(color)
        }
        return colors
    }

    /**
     *  分级函数，用于table里的数据分级
     * @param   max:最大值
     * @param   min:最小值
     * @param   colors:颜色List
     * @param   steps:分级数
     * @note    标黄不用管，需要改参数在调用的地方改
     */
    private fun calculateBreaks(
        min: Double, max: Double, colors: List<Int>, steps: Int, context: Context
    ): List<ClassBreaksRenderer.ClassBreak> {
        val range = max - min
        val classInterval = if (steps > 0) range / steps else 0.0
        // 施肥量越多越黄
        val classBreaks = ArrayList<ClassBreaksRenderer.ClassBreak>()

        // 考虑到有些零散地块并没有被置入数据默认0.0，将0值单独渲染
        val lineRenderColor = context.getColor(R.color.end_color_yellow)
        val zeroZoneRenderColor = context.getColor(R.color.zeroZoneRender_color)
        val lineSymbolForZero =
            SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, lineRenderColor, 1.0f)
        val symbolForZero = SimpleFillSymbol(
            SimpleFillSymbol.Style.SOLID, zeroZoneRenderColor, lineSymbolForZero
        )
        classBreaks.add(
            ClassBreaksRenderer.ClassBreak("0", "0", 0.0, 0.0, symbolForZero)
        )

        for (i in 0 until steps) {
            var minVal = min + classInterval * i
            // 我也不清楚什么情况，加上0值单独分级渲染代码，非0最小值所在地块无法渲染
            // 所以第一个非零区间最小值稍微减小一点
            if (i == 0 && minVal > 0.0001) minVal -= 0.0001

            val maxVal = min + classInterval * (i + 1)
            val lineSymbol =
                SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, lineRenderColor, 1.0f)
            val symbol = SimpleFillSymbol(
                SimpleFillSymbol.Style.SOLID, colors[i], lineSymbol
            )
            classBreaks.add(
                ClassBreaksRenderer.ClassBreak(
                    "$minVal - $maxVal",
                    "$minVal - $maxVal",
                    minVal,
                    maxVal,
                    symbol
                )
            )
        }
        return classBreaks
    }

    private fun renderPrescription(
        table: ShapefileFeatureTable,
        layer: FeatureLayer,
        field: String,
        viewModel: VariableFertViewModel,
        context: Context,
        revision: Long,
    ) {
        val query = QueryParameters().apply {
            whereClause = "1=1"
            isReturnGeometry = false
        }
        val future = table.queryFeaturesAsync(query)
        future.addDoneListener {
            if (mapLoadRevision.get() != revision) return@addDoneListener
            try {
                var minValue = Double.POSITIVE_INFINITY
                var maxValue = Double.NEGATIVE_INFINITY
                for (feature in future.get()) {
                    val value = (feature.attributes[field] as? Number)?.toDouble() ?: continue
                    if (!value.isFinite() || value <= 0.0) continue
                    minValue = minOf(minValue, value)
                    maxValue = maxOf(maxValue, value)
                }
                val steps = when {
                    !minValue.isFinite() -> 0
                    minValue == maxValue -> 1
                    else -> (MySharedPreFun(context).getSpecificValue(R.string.colorStep_name)
                        ?.toIntOrNull() ?: context.resources.getInteger(R.integer.colorStep_value))
                        .coerceAtLeast(1)
                }
                val colors = if (steps == 0) emptyList() else generateGradient(
                    context.getColor(R.color.start_color_green),
                    context.getColor(R.color.end_color_yellow),
                    steps
                )
                val breaks = calculateBreaks(minValue, maxValue, colors, steps, context)
                layer.renderer = ClassBreaksRenderer(field, breaks)
                viewModel.classBreaksList.value = breaks.map {
                    "${formatNumber(it.minValue)} - ${formatNumber(it.maxValue)}"
                }
                viewModel.colorList.value = colors
            } catch (e: Exception) {
                Log.e("ClassBreaksRenderer", "设置处方图分级渲染器失败", e)
            }
        }
    }
    //...加载渲染地图有关结束

    /**
     * 标记GNSS位置
     * @param  mRmcData:RmcData
     * @param  pointCompensationLLGeo:补偿点
     * @param  navMarkGraphicsOverlay:mark所在图层
     * @param  mVariableFertViewModel:更新地图UI viewmodel
     * @param  context:上下文
     * @return
     * @note   这里指示GNSS的位置，没有加补偿
     */
    fun addMarkerToMap(
        mRmcData: RmcData,
        pointCompensationLLGeo: Point,
        navMarkGraphicsOverlay: GraphicsOverlay,
        mVariableFertViewModel: VariableFertViewModel,
        context: Context,
        isCurrentFix: () -> Boolean,
    ) {
        val antenna = Point(mRmcData.longitude, mRmcData.latitude, SpatialReferences.getWgs84())
        val direction = mRmcData.directionDeg
        val showCompensation = MySharedPreFun(context)
            .getSpecificValue(R.string.navMarkCompensated_Switch_name) == "1"
        markerHandler.post {
            if (!isCurrentFix()) return@post
            val graphics = navMarkGraphicsOverlay.graphics
            val tractor = graphics.firstOrNull() ?: Graphic(antenna, MapMarkerArtwork.tractor(context.resources))
                .apply { zIndex = 1 }
                .also { graphics.add(it) }
            tractor.geometry = antenna
            val mapRotation = mVariableFertViewModel.mapViewRotation.value ?: 0.0
            (tractor.symbol as PictureMarkerSymbol).angle = (direction - mapRotation).toFloat()

            var compensation = graphics.getOrNull(1)
            if (showCompensation && compensation == null) {
                compensation = Graphic(pointCompensationLLGeo, MapMarkerArtwork.compensation(context.resources))
                    .also { graphics.add(it) }
            }
            compensation?.let {
                it.geometry = pointCompensationLLGeo
                it.isVisible = showCompensation
            }
            if (mVariableFertViewModel.navMarkGraphicsOverlay.value !== navMarkGraphicsOverlay) {
                mVariableFertViewModel.navMarkGraphicsOverlay.value = navMarkGraphicsOverlay
            }
        }
    }

    /**
     * 绘制已施肥区域
     * @param  polyPoint:存储4角点的数组
     * @param  point:同行起始终止点
     * @param  fertGraphicsOverlay:绘制所在图层
     * @param  mVariableFertViewModel:更新UI使用的viewmodel
     * @return
     * @note   注意polyPoint一定需要在初始化后重新调用不会清零的类里，当然最简单的是public
     */
    fun drawPoly(
        polyPoint: Array<Point?>,
        point: Array<Point>,
        fertGraphicsOverlay: GraphicsOverlay,
        mVariableFertViewModel: VariableFertViewModel,
        context: Context
    ) {
        //...绘制已施肥区域
        // 初始时0、1没有被赋值，绘制不了图形
        if (polyPoint[0] == null) {
            polyPoint[0] = point[0]
        } else if (polyPoint[1] == null) {
            polyPoint[1] = point[1]
        }
        // 2、3点来自GNSS重新定位后的单体首尾位置
        polyPoint[2] = point[1]
        polyPoint[3] = point[0]
        // 使用已有的点创建 PolygonBuilder
        val polygonBuilder = PolygonBuilder(SpatialReferences.getWgs84())
        try {
            // 添加点到 PolygonBuilder
            polyPoint.forEach { it?.let { polygonBuilder.addPoint(it) } }
            // 使用 PolygonBuilder 创建 Polygon
            val polygon = polygonBuilder.toGeometry()
            // 创建一个半透明的蓝色
            val drawcolor = context.getColor(R.color.overlayDraw_green)
            // 使用 Geometry 得到一个 Graphic
            val fillSymbol =
                SimpleFillSymbol(SimpleFillSymbol.Style.SOLID, drawcolor, null)
            val polygonGraphic = Graphic(polygon, fillSymbol)
            // 将 Graphic 添加到 GraphicsOverlay
            fertGraphicsOverlay.graphics.add(polygonGraphic)
            mVariableFertViewModel.fertGraphicsOverlay.postValue(fertGraphicsOverlay)
        } catch (e: Exception) {
            Log.e("drawPoly", "绘制施肥区域失败: " + e.message)
        }
        //...绘制已施肥区域结束
        // 更新0、1点来自上次绘制图形末端
        polyPoint[0] = polyPoint[3]
        polyPoint[1] = polyPoint[2]
    }

    /**
     * 绘制已施肥区域
     * @param  polyPoint:存储4角点的数组
     * @param  point:同行起始终止点
     * @param  fertGraphicsOverlay:绘制所在图层
     * @param  mVariableFertViewModel:更新UI使用的viewmodel
     * @return
     * @note   注意polyPoint一定需要在初始化后重新调用不会清零的类里，当然最简单的是public
     */
    fun drawPolyExport(
        polyPoint: Array<Point?>,
        point: Array<Point>,
        fertGraphicsOverlay: GraphicsOverlay,
        exportMsg: DoubleArray,
        mVariableFertViewModel: VariableFertViewModel,
        context: Context
    ) {
        //...绘制已施肥区域
        // 初始时0、1没有被赋值，绘制不了图形
        if (polyPoint[0] == null) {
            polyPoint[0] = point[0]
        } else if (polyPoint[1] == null) {
            polyPoint[1] = point[1]
        }
        // 2、3点来自GNSS重新定位后的单体首尾位置
        polyPoint[2] = point[1]
        polyPoint[3] = point[0]
        // 使用已有的点创建 PolygonBuilder
        val polygonBuilder = PolygonBuilder(SpatialReferences.getWgs84())
        try {
            // 添加点到 PolygonBuilder
            polyPoint.forEach { it?.let { polygonBuilder.addPoint(it) } }
            // 使用 PolygonBuilder 创建 Polygon
            val polygon = polygonBuilder.toGeometry()
            // 绘制所选颜色
            val rate = (exportMsg[1] - exportMsg[0]) / exportMsg[0]
            val drawcolor = if (rate < -0.03) {
                context.getColor(R.color.overlayDraw_darkgreen)
//            } else if (rate > 0.03) {
//                context.getColor(R.color.overlayDraw_yellow)
            } else {
                context.getColor(R.color.overlayDraw_green)
            }
            // 使用 Geometry 得到一个 Graphic
            val fillSymbol =
                SimpleFillSymbol(SimpleFillSymbol.Style.SOLID, drawcolor, null)
            val polygonGraphic = Graphic(polygon, fillSymbol)

            polygonGraphic.attributes[context.getString(R.string.fertapplied_ExportFeild)] =
                exportMsg[0]
            polygonGraphic.attributes[context.getString(R.string.convertfert_ExportFeild)] =
                exportMsg[1]
            polygonGraphic.attributes[context.getString(R.string.monitorfert_ExportFeild)] =
                exportMsg[2]
            polygonGraphic.attributes[context.getString(R.string.forwardspeed_ExportFeild)] =
                exportMsg[3]

            // 将 Graphic 添加到 GraphicsOverlay
            fertGraphicsOverlay.graphics.add(polygonGraphic)
            mVariableFertViewModel.fertGraphicsOverlayExport.postValue(fertGraphicsOverlay)
        } catch (e: Exception) {
            Log.e("drawPoly", "绘制施肥区域失败: " + e.message)
        }
        //...绘制已施肥区域结束
        // 更新0、1点来自上次绘制图形末端
        polyPoint[0] = polyPoint[3]
        polyPoint[1] = polyPoint[2]
    }

    /**
     * 检查点是否位于已施肥图层内
     * @param  point:点
     * @param  fertGraphicsOverlay:施肥图层
     * @return
     * @note
     */
    fun isPointInGraphicsOverlay(point: Point, fertGraphicsOverlay: GraphicsOverlay): Boolean {
        return fertGraphicsOverlay.graphics.any {
            GeometryEngine.contains(it.geometry, point)
        }
    }

    /**
     * 合并减少层叠graphics
     * @param  overlay:要合并的graphics所在图层
     * @param  bufferDistance:缓冲区大小
     * @param  context:上下文
     * @return
     * @note
     */
    fun mergeGraphicsWithBuffer(
        overlay: GraphicsOverlay,
        bufferDistance: Double,
        context: Context
    ) {
        val bufferedGeometries = overlay.graphics.map {
            // 为每个几何对象创建缓冲区
            GeometryEngine.buffer(it.geometry, bufferDistance)
        }

        // 合并所有缓冲区几何对象以减少重合面
        val combinedGeometry = bufferedGeometries.reduce { acc, geometry ->
            GeometryEngine.union(acc, geometry) as Polygon?
        }
        val drawcolor = context.getColor(R.color.overlayDraw_yellow)
        combinedGeometry?.let { combinedGeom ->
            // 创建一个半透明的蓝色
            // 使用 Geometry 得到一个 Graphic
            val fillSymbol = SimpleFillSymbol(SimpleFillSymbol.Style.SOLID, drawcolor, null)
            val combinedGraphic = Graphic(combinedGeom, fillSymbol)

            overlay.graphics.clear() // 清除旧图形
            overlay.graphics.add(combinedGraphic) // 添加合并后的图形
        }
    }

    //...计算各行单体位置有关
    /**
     * 转换成投影坐标系
     * @param  pointLL:地理点Point(longti,lati)
     * @return 投影点Point(x,y)
     * @note   计算距离时需要转换成投影坐标系，WGS84地理坐标to西安80投影坐标系
     */
    fun doProjection(pointLL: Point): Point {
        val geometry = GeometryEngine.project(
            pointLL, SpatialReference.create(2363), null
        )
        return geometry as Point
    }

    /**
     * 转换成地理坐标系
     * @param  pointXY:投影点Point(x,y)
     * @return 地理点Point(longti,lati)
     * @note   经纬度查询需要转换成地理坐标系，西安80投影坐标系toWGS84地理坐标
     */
    fun doGeographic(pointXY: Point): Point {
        val geometry = GeometryEngine.project(
            pointXY, SpatialReferences.getWgs84(), null
        )
        return geometry as Point
    }

    /**
     * 位置补偿
     * @param  pointXY:投影点Point(x,y)
     * @param  forwardspeed:前进速度
     * @param  directionRad:航向角
     * @param  lagTime:滞后时间
     * @return 补偿后的投影Point(x,y)
     * @note
     */
    fun calculateCompensatedPosition(
        pointXY: Point, forwardspeed: Double, directionRad: Double, lagTime: Double
    ): Point {
        val mapPointX =
            pointXY.x + forwardspeed * sin(directionRad) * lagTime / 3.6
        val mapPointY =
            pointXY.y + forwardspeed * cos(directionRad) * lagTime / 3.6
        return Point(mapPointX, mapPointY, SpatialReference.create(2363))
    }

    fun offsetGeographicPosition(
        antenna: Point,
        directionDeg: Double,
        forwardMeters: Double,
        rightMeters: Double = 0.0,
    ): Point {
        val distance = hypot(forwardMeters, rightMeters)
        if (distance == 0.0) return antenna
        val bearing = (directionDeg + Math.toDegrees(atan2(rightMeters, forwardMeters)) + 360.0) % 360.0
        return GeometryEngine.moveGeodetic(antenna, distance, meters, bearing, degrees, GeodeticCurveType.GEODESIC)
    }

    /** 播深单体位置按 WGS84 距离偏移，适用于不同地区与无处方图地图。 */
    fun depthRowPositions(
        antenna: Point,
        directionDeg: Double,
        parameters: SPParamData,
        lagCompensationMeters: Double = 0.0,
    ): Array<Point> = Array(parameters.rowNumber) { row ->
        val rowOffset = (row - (parameters.rowNumber - 1) / 2.0) * parameters.rowSize
        offsetGeographicPosition(
            antenna,
            directionDeg,
            lagCompensationMeters - parameters.gnssDistanceVertical,
            rowOffset - parameters.gnssDistanceHorizontal,
        )
    }

    fun queryDepthTargets(dantiLLGeo: Array<Point>, viewModel: VariableFertViewModel) {
        val query = viewModel.beginDepthPrescriptionQuery() ?: return
        val table = viewModel.shapefileFeatureTable.value ?: return
        val field = depthQueryField.takeIf { it.isNotBlank() } ?: return
        val activeRows = activeSowingDepthMotorIndices(dantiLLGeo.size, viewModel.activeMotorsState.value)
        if (activeRows.isEmpty()) return

        val depthsMm = AtomicReferenceArray<Float>(dantiLLGeo.size)
        val remaining = AtomicInteger(activeRows.size)
        fun completeRow() {
            if (remaining.decrementAndGet() == 0) {
                viewModel.publishDepthPrescriptionQuery(query, List(dantiLLGeo.size) { depthsMm.get(it) })
            }
        }

        for (row in activeRows) {
            try {
                val parameters = QueryParameters().apply {
                    geometry = dantiLLGeo[row]
                    spatialRelationship = QueryParameters.SpatialRelationship.INTERSECTS
                    whereClause = "1=1"
                    isReturnGeometry = false
                }
                val future = table.queryFeaturesAsync(parameters)
                future.addDoneListener {
                    try {
                        val matchingDepths = future.get().mapNotNull { feature ->
                            feature.attributes[field]?.toString()?.toDoubleOrNull()
                                ?.let { (it * DEPTH_MAP_CM_TO_MM).toFloat() }
                                ?.takeIf { it.isFinite() && it > 0f }
                        }.toSet()
                        // 重叠图斑如果目标不同，暂不下发该行的目标。
                        depthsMm.set(row, matchingDepths.singleOrNull())
                    } catch (e: Exception) {
                        Log.w("depthQueryField", "播深处方图查询失败，第 ${row + 1} 行", e)
                    } finally {
                        completeRow()
                    }
                }
            } catch (e: Exception) {
                Log.w("depthQueryField", "无法查询播深处方图，第 ${row + 1} 行", e)
                completeRow()
            }
        }
    }

    /**
     * 单体位置和控制
     * @param  pointXY:补偿后的投影点Point(x,y)
     * @param  directionRad:航向角
     * @param  mSPParamData:用户设置数据
     * @param  mVariableFertViewModel:更新地图所用viewmodel
     * @return Array<Array<Point>>:Array[0]:单体地理点；Array[1]:幅宽整行地理点（n+1 个）
     * @note   滞后距离已经加在mappoint里了，这里不用管
     */
    fun dantiPositionAndCtrl(
        pointXY: Point,
        directionRad: Double,
        mSPParamData: SPParamData,
        mVariableFertViewModel: VariableFertViewModel,
        isGnssActive: () -> Boolean,
    ): Array<Array<Point>> {

        val n = mSPParamData.rowNumber
        val dantiLLGeo = Array(n) { Point(0.0, 0.0, SpatialReferences.getWgs84()) }
        var dantiXPro: Double
        var dantiYPro: Double
        var fertApplied = DoubleArray(n) { 0.0 }
        fun canSendFertilizer() = isSystemRunning && isGnssActive() &&
            mVariableFertViewModel.depthPrescriptionMode.value != true

        //...计算单体投影位置
        for (i in 0 until n) {
            dantiXPro =
                pointXY.x - mSPParamData.gnssDistanceHorizontal * cos(directionRad) -
                        mSPParamData.gnssDistanceVertical * sin(directionRad) -
                        ((n - 2 * i - 1) * mSPParamData.rowSize / 2) * cos(directionRad)
            dantiYPro =
                pointXY.y + mSPParamData.gnssDistanceHorizontal * sin(directionRad) -
                        mSPParamData.gnssDistanceVertical * cos(directionRad) +
                        ((n - 2 * i - 1) * mSPParamData.rowSize / 2) * sin(directionRad)
            // 将单体投影点转换为地理点
            dantiLLGeo[i] = MyArcGisFun().doGeographic(
                Point(dantiXPro, dantiYPro, SpatialReference.create(2363))
            )
        }
        //...计算单体投影位置结束

        //...查询单体所在地块施肥量（补偿后）
        // 施肥定额下发（fert-manual）：抽成 lambda 供两处复用，杜绝逻辑分叉
        val sendFertManual = {
            fertApplied = DoubleArray(n) { mSPParamData.fertApplied }
            mVariableFertViewModel.fertApplied.postValue(fertApplied)
            for (i in 0 until n) {
                if (!canSendFertilizer()) break
                val motorSpeedrpm = ConvAndCtrlFun().fertToMotorSpeed(
                    mSPParamData.fertApplied,
                    mRmcData.forwardSpeedCalculate,
                    mSPParamData.rowSize,
                    fittingCoefficientA[i],
                    fittingCoefficientB[i]
                )
                ConvAndCtrlFun().motorSpeedrpmSend(
                    motorSpeedrpm,
                    i
                )
            }
        }

        // 处方图工作模式（拮抗/互斥）：控深开 → 只控播深；控深关 → 只控施肥
        val table = mVariableFertViewModel.shapefileFeatureTable.value
        val field = fertQueryField

        if (canSendFertilizer()) {
            // 停车门控（田间实测修复：停车时 3 号电机空转不停）：以真实对地速度 gnssRawSpeed 判停。
            // 近零速时一律对所有排肥电机发 0 并跳过本帧查询/下发——forwardSpeed→0 时 fertToMotorSpeed
            // 返回 -B/A，某路标定 B 与 A 异号时是正的低转速，会让该电机停车空转；固定前进速度模式下
            // forwardSpeedCalculate 恒为定值更会让全部电机停不下来；且停车继续排肥会原地堆肥。
            // 用 gnssRawSpeed 判停不受“固定前进速度”覆盖影响。
            val standstill = mRmcData.gnssRawSpeed < RmcData.STANDSTILL_SPEED_KMH
            // 如果用户设置的参数为-1，则说明需要自动查询施肥量，>=0，手动设置
            val fertAuto = mSPParamData.fertApplied == -1.0 || mSPParamData.fertApplied <= 0.0
            if (standstill) {
                for (i in 0 until n) {
                    if (!canSendFertilizer()) break
                    ConvAndCtrlFun().motorSpeedrpmSend(0.0, i)
                }
                fertApplied = DoubleArray(n) { 0.0 }
                mVariableFertViewModel.fertApplied.postValue(fertApplied)
            } else if (fertAuto && table != null && field.isNotBlank()) {
                for (i in 0 until n) {
                    val idx = i // 捕获，避免 addDoneListener 闭包错位
                    val queryParameters = QueryParameters().apply {
                        geometry = dantiLLGeo[idx]
                        whereClause = "1=1"
                        spatialRelationship = QueryParameters.SpatialRelationship.INTERSECTS
                    }
                    val shapefileFuture = table.queryFeaturesAsync(queryParameters)
                    shapefileFuture?.addDoneListener {
                        try {
                            // 施肥量字段 null 安全解析，与播深字段读取保持一致：
                            // 浮点/双精度列 toDoubleOrNull 完整保留小数（如 "12.5" -> 12.5）。
                            // featureFound 区分「定位点落在图斑之外」与「查到要素但字段缺失/非法」：
                            //   · 图斑之外（无要素）          -> 不施肥区，停机（转速 0）
                            //   · 字段值 ≤0（合法的 0 值图斑）-> 不施肥区，停机（转速 0）
                            //   · 字段值 >0                   -> 正常折算下发
                            //   · 查到要素但字段缺失/非法(NaN)-> 数据异常，保持上次指令（沿用容错设计）
                            var fert = Double.NaN
                            var featureFound = false
                            shapefileFuture.get().forEach { feature ->
                                featureFound = true
                                fert = feature.attributes[field]
                                    ?.toString()?.toDoubleOrNull() ?: Double.NaN
                            }
                            if (canSendFertilizer() && table === mVariableFertViewModel.shapefileFeatureTable.value &&
                                mRmcData.gnssRawSpeed >= RmcData.STANDSTILL_SPEED_KMH
                            ) {
                                when {
                                    // ① 图斑之外：该处不施肥 -> 停机
                                    !featureFound -> {
                                        fertApplied[idx] = 0.0
                                        ConvAndCtrlFun().motorSpeedrpmSend(0.0, idx)
                                    }
                                    // ② 字段值 ≤0（合法不施肥区）-> 停机
                                    !fert.isNaN() && fert <= 0.0 -> {
                                        fertApplied[idx] = 0.0
                                        ConvAndCtrlFun().motorSpeedrpmSend(0.0, idx)
                                    }
                                    // ③ 正常施肥量 -> 按拟合折算下发
                                    !fert.isNaN() -> {
                                        fertApplied[idx] = fert
                                        Log.d(
                                            "fertQueryField",
                                            dantiLLGeo[idx].x.toString() + "  " + dantiLLGeo[idx].y.toString() + "  " + fert.toString() + "  " + mRmcData.forwardSpeed.toString() + "  " + mRmcData.forwardSpeedCalculate.toString()
                                        )
                                        val motorSpeedrpm = ConvAndCtrlFun().fertToMotorSpeed(
                                            fert,
                                            mRmcData.forwardSpeedCalculate,
                                            mSPParamData.rowSize,
                                            fittingCoefficientA[idx],
                                            fittingCoefficientB[idx]
                                        )
                                        ConvAndCtrlFun().motorSpeedrpmSend(
                                            motorSpeedrpm, idx
                                        )
                                    }
                                    // ④ 查到要素但字段缺失/非法：保持上次指令，不更新、不下发
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        if (canSendFertilizer() && table === mVariableFertViewModel.shapefileFeatureTable.value) {
                            mVariableFertViewModel.fertApplied.postValue(fertApplied)
                        }
                    }
                }
            } else {
                // 纯施肥手动（深度关）：原 else 块行为不变
                sendFertManual()
            }
        }

        mVariableFertViewModel.dantiLLGeo.postValue(dantiLLGeo)
        //...查询单体所在地块施肥量（补偿后）结束

        // 绘制已施肥区域所用点
        val drawLLGeo = Array(n + 1) { Point(0.0, 0.0, SpatialReferences.getWgs84()) }
        for (i in 0 until (n + 1)) {
            dantiXPro =
                pointXY.x - mSPParamData.gnssDistanceHorizontal * cos(directionRad) -
                        mSPParamData.gnssDistanceVertical * sin(directionRad) -
                        ((n - 2 * i) * mSPParamData.rowSize / 2) * cos(directionRad)
            dantiYPro =
                pointXY.y + mSPParamData.gnssDistanceHorizontal * sin(directionRad) -
                        mSPParamData.gnssDistanceVertical * cos(directionRad) +
                        ((n - 2 * i) * mSPParamData.rowSize / 2) * sin(directionRad)
            drawLLGeo[i] =
                doGeographic(Point(dantiXPro, dantiYPro, SpatialReference.create(2363)))
        }
        mVariableFertViewModel.drawLLGeo.postValue(drawLLGeo)

        return arrayOf(dantiLLGeo, drawLLGeo)
    }
    //...计算各行单体位置有关结束
}
