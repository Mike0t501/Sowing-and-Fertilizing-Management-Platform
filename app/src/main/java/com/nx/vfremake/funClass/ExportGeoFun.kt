/**
 ************************************************************************************************************
 * @author  :NIANXI
 * @date    :2024年9月24日08:37:47
 * @file    :
 * @brief   :导出施肥图层
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 导出施肥图层，格式.geodatabase
 * 从.geodatabase导入图层数据
 ***********************************************************************************************************
 */
package com.nx.vfremake.funClass

import android.content.Context
import android.util.Log
import com.esri.arcgisruntime.concurrent.ListenableFuture
import com.esri.arcgisruntime.data.Field
import com.esri.arcgisruntime.data.FieldDescription
import com.esri.arcgisruntime.data.Geodatabase
import com.esri.arcgisruntime.data.QueryParameters
import com.esri.arcgisruntime.data.TableDescription
import com.esri.arcgisruntime.geometry.GeometryType
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.mapping.view.Graphic
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.esri.arcgisruntime.mapping.view.MapView
import com.esri.arcgisruntime.symbology.SimpleFillSymbol
import com.nx.vfremake.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportGeoFun(context: Context) {
    private val appContext: Context = context.applicationContext
    private var geodatabase: Geodatabase? = null
    private val graphicsOverlay = GraphicsOverlay()

    // 创建地理数据库并设置地图
    fun createGeodatabase(graphicLayer: GraphicsOverlay) {
        // 定义地理数据库文件的路径和名称
        val timeStampfilename =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "fertMsg_$timeStampfilename.geodatabase"
        val file =
            File(appContext.getExternalFilesDir(null)?.path + "/$fileName")
        if (file.exists()) {
            file.delete() // 如果文件存在，删除它
        }

        // 创建地理数据库
        geodatabase?.close()
        val geoDatabaseFuture = Geodatabase.createAsync(file.path)
        geoDatabaseFuture.addDoneListener {
            geodatabase = geoDatabaseFuture.get() // 获取Geodatabase
            if (geodatabase == null) {
                Log.e("Geodatabase Export", "创建 Geodatabase 失败")
                return@addDoneListener
            }

            // 创建表
            val tableDescription = TableDescription(
                "PolygonLocationHistory",
                SpatialReferences.getWgs84(),
                GeometryType.POLYGON
            )
            // 添加字段描述，一定要和fun drawPolyExport对应
            tableDescription.fieldDescriptions.addAll(
                listOf(
                    FieldDescription(
                        appContext.getString(R.string.fertapplied_ExportFeild),
                        Field.Type.DOUBLE
                    ),
                    FieldDescription(
                        appContext.getString(R.string.convertfert_ExportFeild),
                        Field.Type.DOUBLE
                    ),
                    FieldDescription(
                        appContext.getString(R.string.monitorfert_ExportFeild),
                        Field.Type.DOUBLE
                    ),
                    FieldDescription(
                        appContext.getString(R.string.forwardspeed_ExportFeild),
                        Field.Type.DOUBLE
                    ),
                )
            )
            // 设置表的其他属性
            tableDescription.apply {
                setHasAttachments(false)
                setHasM(false)
                setHasZ(false)
            }

            // 异步添加表到地理数据库
            val geoTableFuture = geodatabase?.createTableAsync(tableDescription)
            geoTableFuture?.addDoneListener {
                val featureTable = geoTableFuture.get() // 获取FeatureTable
                if (featureTable != null) {
                    // 遍历graphicLayer中的所有Graphic对象并添加到FeatureTable
                    val graphics = graphicLayer.graphics
                    val addFeatureFutures = mutableListOf<ListenableFuture<Void>>()
                    for (graphic in graphics) {
                        val feature = featureTable.createFeature()
                        feature.geometry = graphic.geometry
                        feature.attributes.putAll(graphic.attributes)
                        // 异步添加 Feature 到 FeatureTable
                        val addFeatureFuture = featureTable.addFeatureAsync(feature)
                        addFeatureFuture.addDoneListener {
                            addFeatureFutures.add(addFeatureFuture)
                        }
                    }
                    // 等待所有要素添加完成
                    addFeatureFutures.forEach { future ->
                        future.addDoneListener {
                            try {
                                future.get() // 等待每个要素添加操作完成
                            } catch (e: Exception) {
                                Log.e("Geodatabase Export", "FeatureTable 添加失败: " + e.message)
                            }
                        }
                    }
                } else {
                    Log.e("Geodatabase Export", "FeatureTable 创建失败")
                }
            }
        }
    }

    private fun setupMapFromGeodatabase(mapView: MapView, geodatabasePath: String) {
        // 创建 Geodatabase
        val geodatabaseFuture = Geodatabase.createAsync(geodatabasePath)
        geodatabaseFuture.addDoneListener {
            val geodatabase = geodatabaseFuture.get()
            if (geodatabase == null) {
                Log.e("Geodatabase Export", "Geodatabase 创建失败")
                return@addDoneListener
            }

            // 获取 FeatureTable
            val featureTable =
                geodatabaseFuture.get().getGeodatabaseFeatureTable("PolygonLocationHistory")
            if (featureTable == null) {
                Log.e("Geodatabase Export", "FeatureTable 获取失败")
                return@addDoneListener
            }

            // 查询所有要素
            val queryParameters = QueryParameters().apply {
                whereClause = "1=1" // 指定字段
            }
            val query = featureTable.queryFeaturesAsync(queryParameters)
            query.addDoneListener {
                try {
                    val features = query.get()
                    features.forEach { feature ->
                        // 创建 Graphic
                        val graphic = Graphic(feature.geometry, feature.attributes)

                        // 创建一个半透明的蓝色
                        val drawcolor = appContext.getColor(R.color.overlayDraw_yellow)
                        val fillSymbol =
                            SimpleFillSymbol(SimpleFillSymbol.Style.SOLID, drawcolor, null)

                        graphic.symbol = fillSymbol

                        // 添加到 GraphicsOverlay
                        graphicsOverlay.graphics.add(graphic)
                    }
                    mapView.graphicsOverlays.add(graphicsOverlay)
                } catch (e: Exception) {
                    Log.e("Geodatabase Export", "features 查询失败: " + e.message)
                }
            }
        }
    }
}
