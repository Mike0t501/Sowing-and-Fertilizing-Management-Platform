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
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import com.esri.arcgisruntime.data.QueryParameters
import com.esri.arcgisruntime.data.ShapefileFeatureTable
import com.esri.arcgisruntime.geometry.GeometryEngine
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.geometry.Polygon
import com.esri.arcgisruntime.geometry.PolygonBuilder
import com.esri.arcgisruntime.geometry.SpatialReference
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.layers.FeatureLayer
import com.esri.arcgisruntime.loadable.LoadStatus
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.Basemap
import com.esri.arcgisruntime.mapping.view.Graphic
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.esri.arcgisruntime.symbology.ClassBreaksRenderer
import com.esri.arcgisruntime.symbology.PictureMarkerSymbol
import com.esri.arcgisruntime.symbology.SimpleFillSymbol
import com.esri.arcgisruntime.symbology.SimpleLineSymbol
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.fertQueryField
import com.nx.vfremake.fittingCoefficientA
import com.nx.vfremake.fittingCoefficientB
import com.nx.vfremake.isSystemRunning
import com.nx.vfremake.mRmcData
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin


class MyArcGisFun {

    //...加载渲染地图有关
    /**
     * 加载总成
     * @param   context:上下文
     * @param   mVariableFertViewModel:更新UI使用的viewmodel
     * @return
     * @note
     */
    fun loadShp(context: Context, mVariableFertViewModel: VariableFertViewModel) {
        val shpPath =
            MySharedPreFun(context).getSpecificValue(R.string.myLoadShpFile_Path_name)
        val queryField =
            MySharedPreFun(context).getSpecificValue(R.string.fertQueryField_name)
        if (!shpPath.isNullOrEmpty() && !queryField.isNullOrEmpty()) {
            // 检查加载使用的字段是否属于加载路径下处方图字段集里
            val shapefileFeatureTable = ShapefileFeatureTable(shpPath)
            shapefileFeatureTable.loadAsync()
            // 异步加载监听
            shapefileFeatureTable.addDoneLoadingListener {
                if (shapefileFeatureTable.loadStatus == LoadStatus.LOADED) {
                    try {
                        val fieldListString = shapefileFeatureTable.fields.map { it.name }
                        // 如果属于则加载
                        if (fieldListString.contains(queryField)) {
                            fertQueryField = queryField
                            MyArcGisFun().loadShapefile(
                                shpPath,
                                fertQueryField,
                                mVariableFertViewModel,
                                context
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("shapefileFeatureTable", "错误: " + e.message)
                    }
                }
            }
        }
    }

    /**
     * 获取字段列表，使用viewmodel存储
     * @param  shpFilePath:shp存储路径
     * @param   mVariableFertViewModel:更新UI使用的viewmodel
     * @return
     * @note
     */
    fun getFieldList(shpFilePath: String?, mVariableFertViewModel: VariableFertViewModel) {
        if (!shpFilePath.isNullOrEmpty()) {
            // 构建ShapefileFeatureTable，引入本地存储的shapefile文件
            val shapefileFeatureTable = ShapefileFeatureTable(shpFilePath)
            shapefileFeatureTable.loadAsync()
            // 异步加载监听
            shapefileFeatureTable.addDoneLoadingListener {
                if (shapefileFeatureTable.loadStatus == LoadStatus.LOADED) {
                    try {
                        val fieldListString = shapefileFeatureTable.fields.map { it.name }
                        mVariableFertViewModel.fieldsList.postValue(fieldListString)
                    } catch (e: Exception) {
                        Log.e("shapefileFeatureTable", "获取字段错误: " + e.message)
                    }
                }
            }
        }
    }

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
        val classInterval: Double = range / steps
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

    /**
     *  加载离线地图(处方图)
     * @param   shpFilePath:地图路径
     * @param   fertQueryField:施肥量所在字段
     * @param   mVariableFertViewModel:更新UI使用的viewmodel
     * @param   context:上下文，获取strings.xml里的资源值
     * @note
     */
    private fun loadShapefile(
        shpFilePath: String,
        fertQueryField: String,
        mVariableFertViewModel: VariableFertViewModel,
        context: Context
    ) {
        val mArcGISMap = ArcGISMap()
        // 构建ShapefileFeatureTable，引入本地存储的shapefile文件
        val shapefileFeatureTable = ShapefileFeatureTable(shpFilePath)
        shapefileFeatureTable.loadAsync()
        // 异步加载监听
        shapefileFeatureTable.addDoneLoadingListener {
            if (shapefileFeatureTable.loadStatus == LoadStatus.LOADED) {
                // 构建FeatureLayer
                val shapefileFeatureLayer = FeatureLayer(shapefileFeatureTable)
                shapefileFeatureLayer.loadAsync()

                // 设置Shapefile文件的渲染方式

                //...第一种渲染方式黄色轮廓绿色实心方块
//                val lineSymbol = SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, Color.YELLOW, 1.0f)
//                val fillSymbol =
//                    SimpleFillSymbol(SimpleFillSymbol.Style.SOLID, Color.GREEN, lineSymbol)
//                val renderer = SimpleRenderer(fillSymbol)
                //...

                //...第二种渲染方式，分级渲染
                // 分级渲染非零地块，零区域在分级函数里已经应用
                try {
                    val queryParameters = QueryParameters().apply {
                        whereClause = fertQueryField // 指定字段
                    }
                    var maxValue = Double.MIN_VALUE
                    var minValue = Double.MAX_VALUE
                    val shapefileFuture =
                        shapefileFeatureTable.queryFeaturesAsync(queryParameters)
                    shapefileFuture.addDoneListener {
                        val result = shapefileFuture.get()

                        for (feature in result) {
                            val attribute = feature.attributes[fertQueryField]
                            if (attribute == 0.0) {
                                // 如果当前字段的值为0，则跳过本次遍历
                                // 创建分级渲染器时已经考虑到0值，这里跳过避免清一色
                                continue
                            }
                            // 检索的是shptable数据中的 栏，根据实际修改
                            // 因为制作的shp字段数据有浮点也有双精度，判断浮点还是双精度
                            val currentValue: Double = when (attribute) {
                                is Float -> {
                                    attribute.toDouble()
                                }

                                is Int -> {
                                    attribute.toDouble()
                                }

                                is Double -> {
                                    attribute
                                }

                                else -> {
                                    throw IllegalArgumentException("字段信息不是双精度、浮点或者整形类型")
                                }
                            }
                            // 执行遍历
                            if (currentValue > maxValue) {
                                maxValue = currentValue
                            }
                            if (currentValue < minValue) {
                                minValue = currentValue
                            }
                        }
                        //...遍历测试代码
                        Log.d("FeatureResult", "max: $maxValue   min:$minValue")
                        //...

                        // colorList的颜色提示用
                        val stepSharedPre =
                            MySharedPreFun(context).getSpecificValue(R.string.colorStep_name)
                        val step = if (!stepSharedPre.isNullOrEmpty()) {
                            stepSharedPre.toInt()
                        } else {
                            context.resources.getInteger(R.integer.colorStep_value)
                        }
                        val colorList = MyArcGisFun().generateGradient(
                            context.getColor(R.color.start_color_green),
                            context.getColor(R.color.end_color_yellow),
                            step
                        )
                        val classBreaks = MyArcGisFun().calculateBreaks(
                            minValue,
                            maxValue,
                            colorList,
                            step,
                            context
                        )
                        mVariableFertViewModel.colorList.value = colorList
                        for (i in colorList.indices) {
                            val co = colorList[i]
                            Log.d("ColorList", "colorlist $i: $co")
                        }
                        // classBreaks的颜色提示用
                        val combinedList = mutableListOf<String>()
                        for (i in classBreaks.indices) {
                            val cb = classBreaks[i]
                            val min = MyArcGisFun().formatNumber(cb.minValue)
                            val max = MyArcGisFun().formatNumber(cb.maxValue)
                            val combined = "$min - $max"
                            combinedList.add(combined)
                            Log.d(
                                "ClassBreakList",
                                "classBreak $i: $min - $max " + cb.description
                            )
                        }
                        mVariableFertViewModel.classBreaksList.value = combinedList.toList()

                        // 设置分级渲染器
                        val renderer = ClassBreaksRenderer(
                            fertQueryField,
                            classBreaks
                        )
                        shapefileFeatureLayer.renderer = renderer
                    }
                } catch (e: Exception) {
                    Log.e(
                        "ClassBreaksRenderer",
                        "设置分级渲染器失败: " + e.message
                    )
                }
                //...第二种渲染方式，分级渲染结束
                // 添加到地图的图层组中
                val basemap = Basemap()
                mArcGISMap.basemap = basemap

                mArcGISMap.operationalLayers.add(shapefileFeatureLayer)

                // 观测mArcGISMap
                mVariableFertViewModel.shapefileFeatureLayer.value = shapefileFeatureLayer
                mVariableFertViewModel.shapefileFeatureTable.value = shapefileFeatureTable
                mVariableFertViewModel.mArcGISMap.value = mArcGISMap

            } else {
                Log.e(
                    "ShapefileFeatureTable",
                    "ShapefileFeatureTable加载失败: " + shapefileFeatureTable.loadError
                )
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
        context: Context
    ) {
        // 转换Drawable资源到BitmapDescriptor
        val navmarkDrawable =
            ResourcesCompat.getDrawable(
                context.resources,
                R.drawable.navigation,
                null
            ) as BitmapDrawable
        val navmarkCompensationDrawable =
            ResourcesCompat.getDrawable(
                context.resources,
                R.drawable.navigation_compensation,
                null
            ) as BitmapDrawable
        // 创建点对象
        // SpatialReference.create(4326)和SpatialReferences.getWgs84()并无区别，地理坐标系
        val pointLLGeo =
            Point(mRmcData.longitude, mRmcData.latitude, SpatialReferences.getWgs84())
        // 创建一个图像标记符号
        val symbolFuture = PictureMarkerSymbol.createAsync(navmarkDrawable)
        symbolFuture.addDoneListener {
            try {
                val symbol = symbolFuture.get()
                // symbolFuture.angle使用的是角度
                val mapViewRotation = mVariableFertViewModel.mapViewRotation.value ?: 0.0
                if (mVariableFertViewModel.navCenterIsRunning.value != true) {
                    symbol.angle = (mRmcData.directionDeg - mapViewRotation).toFloat()
                }
                // 使用符号和几何图形创建图像
                val graphic = Graphic(pointLLGeo, symbol)

                // 清除navMark图层先前的所有图形，该图层只用于navMark
                navMarkGraphicsOverlay.graphics?.clear()
                // 在地图上添加图像
                navMarkGraphicsOverlay.graphics?.add(graphic)

                if (MySharedPreFun(context).getSpecificValue(R.string.navMarkCompensated_Switch_name) == "1") {
                    // 处理第二个点的符号和图形
                    val compensationSymbolFuture =
                        PictureMarkerSymbol.createAsync(navmarkCompensationDrawable)
                    compensationSymbolFuture.addDoneListener {
                        try {
                            val compensationSymbol = compensationSymbolFuture.get()

                            if (mVariableFertViewModel.navCenterIsRunning.value != true) {
                                compensationSymbol.angle =
                                    (mRmcData.directionDeg - mapViewRotation).toFloat()
                            }

                            val compensationGraphic =
                                Graphic(pointCompensationLLGeo, compensationSymbol)

                            // 添加第二个点的图形
                            navMarkGraphicsOverlay.graphics?.add(compensationGraphic)

                        } catch (e: Exception) {
                            Log.e("MarktoMap", "添加compensation图标失败: " + e.message)
                        }
                    }
                }
                mVariableFertViewModel.navMarkGraphicsOverlay.postValue(navMarkGraphicsOverlay)
            } catch (e: Exception) {
                Log.e("MarktoMap", "添加navigation图标失败: " + e.message)
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
    ): Array<Array<Point>> {

        val n = mSPParamData.rowNumber
        val dantiLLGeo = Array(n) { Point(0.0, 0.0, SpatialReferences.getWgs84()) }
        var dantiXPro: Double
        var dantiYPro: Double
        var fertApplied = DoubleArray(n) { 0.0 }

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
        // 如果用户设置的参数为-1，则说明需要自动查询施肥量，>=0，手动设置
        if (mSPParamData.fertApplied == -1.0 || mSPParamData.fertApplied <= 0.0) {
            val table = mVariableFertViewModel.shapefileFeatureTable.value
            for (i in 0 until n) {
                // 查询施肥量代码搬在这里，因为查询是异步的，addDoneListener里执行后续代码，不好分出去单写一个函数调用形式
                // 设置查询参数
                val queryParameters = QueryParameters().apply {
                    // 查询指定点
                    geometry = dantiLLGeo[i]
                    // 设定字段，也可以加上限定条件
                    whereClause = fertQueryField
                }
                // 执行查询
                val shapefileFuture = table?.queryFeaturesAsync(queryParameters)
                shapefileFuture?.addDoneListener {
                    try {
                        // 使用 get() 方法等待查询完成并获取结果
                        val result = shapefileFuture.get()
                        var fert = 0.0
                        // 遍历查询结果
                        result.forEach { feature ->
                            fert = feature.attributes[fertQueryField].toString().toDouble()
                        }
                        fertApplied[i] = fert

                        Log.d(
                            "fertQueryField",
                            dantiLLGeo[i].x.toString() + "  " + dantiLLGeo[i].y.toString() + "  " + fert.toString() + "  " + mRmcData.forwardSpeed.toString() + "  " + mRmcData.forwardSpeedCalculate.toString()
                        )
                        if (fert >= 0 && isSystemRunning) {
                            val motorSpeedrpm = ConvAndCtrlFun().fertToMotorSpeed(
                                fert,
                                mRmcData.forwardSpeedCalculate,
                                mSPParamData.rowSize,
                                fittingCoefficientA[i],
                                fittingCoefficientB[i]
                            )

//                            Log.d("CtrlMsg", "电机转速: $motorSpeedrpm")
                            ConvAndCtrlFun().motorSpeedrpmSend(
                                motorSpeedrpm, i
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    mVariableFertViewModel.fertApplied.postValue(fertApplied)
                }
            }
        } else {
            fertApplied = DoubleArray(n) { mSPParamData.fertApplied }
            mVariableFertViewModel.fertApplied.postValue(fertApplied)
            for (i in 0 until n) {
                if (!isSystemRunning) break
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
