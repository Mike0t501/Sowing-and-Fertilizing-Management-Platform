package com.nx.vfremake.coroutine

import android.content.Context
import android.util.Log
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.funClass.MyArcGisFun
import com.nx.vfremake.funClass.MyGNSSFun
import com.nx.vfremake.mRmcData
import com.nx.vfremake.mSPParamData
import com.nx.vfremake.mSerialPortDB9
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * 接收RMC解析存储并查询单体施肥量发送控制信息协程
 * @note   我有一些值的初始化放在类里了，因为协程类我一般都是先初始化，不会lateinit或者null
 */
class DB9reCANseCoroutine {
    // 因为我在Mainactivity顶层类里初始化DB9reCANseCoroutine()，可以这么写
    private val polyPoint = arrayOfNulls<Point>(4) // 绘制已施肥区域用Point
    private val polyPointExport = Array(mSPParamData.rowNumber) { arrayOfNulls<Point>(4) }

    // 绘制图形需要持续调用函数，这个得放函数外面，才可以持续 add(Graphic)
    private val navMarkGraphicsOverlay = GraphicsOverlay() // 该图层只用于navMark，因为每次更新需要清除图层，便于管理
    private val fertGraphicsOverlay = GraphicsOverlay() // 该图层只用于绘制施肥区域，每次更新是在上次基础添加，不清除
    private val fertGraphicsOverlayExport = GraphicsOverlay() // 该图层只用于绘制施肥区域，每次更新是在上次基础添加，不清除

    // 定义一个协程作用域，保存作业引用的HashSet，以便于管理
    private var scope = CoroutineScope(Dispatchers.Default)
    private val jobs = HashSet<Job>()

    // 开始标志位
    var isRunning = false

    fun shutdown() {
        scope.cancel() // 取消整个作用域，包括任何卡在 read() 里的 job
        jobs.clear()
        isRunning = false
    }


    fun start1(
        context: Context,
        mVariableFertViewModel: VariableFertViewModel
    ) {
        // 每次启动创建新的 scope，防止旧 scope 已 cancel 导致无法 launch
        scope = CoroutineScope(Dispatchers.Default)
        // 重置速度滑动窗口，避免上次运行的残留速度值影响本次冷启动
        mRmcData.speedBufCount = 0
        mRmcData.speedBufHead = 0

        // 在协程作用域内启动协程
        jobs.add(scope.launch {
            val inputStreamDB9 = mSerialPortDB9?.inputStream
            try {
                val buffer = ByteArray(80)  // 根据语句长度调整缓冲区大小
                var bytesRead: Int
                val sb = StringBuilder()

                while (isActive) {
                    if (inputStreamDB9 == null || withContext(Dispatchers.IO) { inputStreamDB9.available() } == 0) {
                        // 如果CAN输入流没有消息，休息10ms停止执行后续代码，重新while
                        delay(10)
                        continue
                    }
                    try {
                        bytesRead = withContext(Dispatchers.IO) { inputStreamDB9.read(buffer) }
                        // 将读取的数据转换为String并添加到StringBuilder中，sb会自动拼接字符，粘包后解析sb
                        sb.append(String(buffer, 0, bytesRead))

                        // 检查sb中是否存在'\n'，若存在，处理每行数据
                        while (sb.indexOf("\n") != -1) {
                            val endOfLineIndex = sb.indexOf("\n")
                            val line = sb.substring(0, endOfLineIndex).trim() // 获取一整行数据
                            // 检查行是否以RMC开头，若是，则处理
                            if (line.startsWith("\$GPRMC") || line.startsWith("\$GNRMC")) {
                                Log.d("GNSS", line)
                                measureTimeMillis(
                                ) {
                                    onGNSSMsgReceived(
                                        line,
                                        context,
                                        mVariableFertViewModel
                                    )
                                }
                            }
                            sb.delete(0, endOfLineIndex + 1) // 从StringBuilder中删除这行
                        }
                    } catch (e: IOException) {
                        Log.e("DB9reCANseCoroutine", "IOException: ${e.message}")
                        break // 如果发生异常，退出循环
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        })
        isRunning = true
    }

    /**
    fun start1(
        context: Context,
        mVariableFertViewModel: VariableFertViewModel
    ) {
        fun decimalToRMCFormat(coordinate: Double, isLatitude: Boolean): String {
            val degrees = coordinate.toInt() // 获取整数部分（度）
            val minutes = (coordinate - degrees) * 60 // 计算分
            return if (isLatitude) {
                // 纬度用两位度
                String.format(Locale.US, "%02d%07.4f", degrees, minutes)
            } else {
                // 经度用三位度
                String.format(Locale.US, "%03d%07.4f", degrees, minutes)
            }
        }

        jobs.add(scope.launch {
            val sb = StringBuilder()

            // 原始起点和终点数据
            val startPoint =
                "\$GPRMC,171012.000,A,3651.10062,N,11500.48990,E,8.1,10.7068,151216,,D*49"
            val endPoint =
                "\$GPRMC,171012.000,A,3651.11964,N,11500.49482,E,8.1,10.7068,151216,,D*49"

            // 提取数据（纬度、经度、速度等）以便插值计算
            val startLat = 36.850825
            val startLon = 115.007949
            val startSpeed = 8.1 // 1节 = 1.852km/h

            val endLat = 36.853154
            val endLon = 115.008574
            val endSpeed = 8.1


            val totalSteps = 155 // 12秒 / 0.4秒 = 30次
            val interval = 400L // 每次间隔400ms

            for (step in 0..totalSteps) {
                val fraction = step / totalSteps.toFloat()

                // 线性插值计算当前点的经纬度和速度
                val currentLat = startLat + (endLat - startLat) * fraction
                val currentLon = startLon + (endLon - startLon) * fraction
                val currentSpeed = startSpeed + (endSpeed - startSpeed) * fraction

                // 格式化数据为RMC字符串
                val currentLatRMC = decimalToRMCFormat(currentLat, true)  // 纬度转换
                val currentLonRMC = decimalToRMCFormat(currentLon, false) // 经度转换

                val simulatedData = String.format(
                    Locale.CHINA,
                    "\$GPRMC,171012.000,A,%s,N,%s,E,%.3f,10.7068,151216,,D*49\n",
                    currentLatRMC, currentLonRMC, currentSpeed
                )

                sb.append(simulatedData)

                // 模拟GNSS消息接收（放到Dispatchers.IO中执行）
                launch(Dispatchers.IO) {
                    while (sb.indexOf("\n") != -1) {
                        val endOfLineIndex = sb.indexOf("\n")
                        val line = sb.substring(0, endOfLineIndex).trim()
                        if (line.startsWith("\$GPRMC")) {
                            Log.d("GNSS", line)
                            onGNSSMsgReceived(
                                line,
                                context,
                                mVariableFertViewModel
                            )
                        }
                        sb.delete(0, endOfLineIndex + 1)
                    }
                }

                delay(interval) // 模拟每400ms接收一次数据
            }
        })
        isRunning = true
    }
    */
    /**
     * 处理 RMC 语句查询施肥量控制单体流量总成
     * @param  gnssString:GNSS语句信息
     * @param  context:上下文
     * @param  mVariableFertViewModel:更新地图UI viewmodel
     * @return
     * @note
     */
    private fun onGNSSMsgReceived(
        gnssString: String,
        context: Context,
        mVariableFertViewModel: VariableFertViewModel
    ) {
        // 解析RMC
        MyGNSSFun().extractAndParseRMC(
            gnssSentence = gnssString,
            forwardSpeed = mSPParamData.forwardSpeed,
            mRmcData = mRmcData,
            mVariableFertViewModel = mVariableFertViewModel,
            context = context
        )
        mVariableFertViewModel.rtkEverReceived = true

        val point = Point(mRmcData.longitude, mRmcData.latitude, SpatialReferences.getWgs84())

        // 位置补偿
        val wgsCompensatedPoint = MyArcGisFun().calculateCompensatedPosition(
            pointXY = MyArcGisFun().doProjection(point),
            forwardspeed = mRmcData.forwardSpeed,
            directionRad = mRmcData.directionRad,
            lagTime = mSPParamData.lagTime
        )

        // 添加当前位置图标
        MyArcGisFun().addMarkerToMap(
            mRmcData = mRmcData,
            pointCompensationLLGeo = MyArcGisFun().doGeographic(wgsCompensatedPoint),
            navMarkGraphicsOverlay = navMarkGraphicsOverlay,
            mVariableFertViewModel = mVariableFertViewModel,
            context = context
        )
        // 计算单体位置生成并发送控制信息
        val pointi = MyArcGisFun().dantiPositionAndCtrl(
            pointXY = wgsCompensatedPoint,
            directionRad = mRmcData.directionRad,
            mSPParamData = mSPParamData,
            mVariableFertViewModel = mVariableFertViewModel
        )

        //...测试用
        val fertAppliedtest =
            mVariableFertViewModel.fertApplied.value ?: DoubleArray(mSPParamData.rowNumber) { 0.0 }

        val monfertflow = fertAppliedtest.map { value ->
            if (value == 0.0) {
                value // 如果值为 0，直接返回 0
            } else {
                val deviation = value * 0.04
                value + Random.nextDouble(-deviation, deviation)
            }
        }.toDoubleArray()
//
//        val confertflow = fertAppliedtest.map { value ->
//            if (value == 0.0) {
//                value // 如果值为 0，直接返回 0
//            } else {
//                val deviation = value * 0.04
//                value + Random.nextDouble(-deviation, deviation)
//            }
//        }.toDoubleArray()

        // 将生成的数组赋值给 ViewModel
        mVariableFertViewModel.monfertflow.postValue(monfertflow)
//        mVariableFertViewModel.confertflow.postValue(confertflow)
        //...

        // 绘制已施肥区域
        // 如果改成每次都调用，会清空
        MyArcGisFun().drawPoly(
            polyPoint = polyPoint,
            point = arrayOf(pointi[1][0], pointi[1].last()),
            fertGraphicsOverlay = fertGraphicsOverlay,
            mVariableFertViewModel = mVariableFertViewModel,
            context = context
        )

        val fertApplied = mVariableFertViewModel.fertApplied.value
        for (i in 0 until mSPParamData.rowNumber) {
            Log.d(
                "DB9reCANseCoroutine",
                "$i: " + fertApplied?.get(i).toString()
            )
            val exportMsg = doubleArrayOf(
                mVariableFertViewModel.fertApplied.value?.get(i) ?: 0.0,
                mVariableFertViewModel.confertflow.value?.get(i) ?: 0.0,
                mVariableFertViewModel.monfertflow.value?.get(i) ?: 0.0,
                mVariableFertViewModel.forwardspeed.value ?: 0.0
            )
            MyArcGisFun().drawPolyExport(
                polyPoint = polyPointExport[i],
                point = arrayOf(pointi[1][i], pointi[1][i + 1]),
                exportMsg = exportMsg,
                fertGraphicsOverlay = fertGraphicsOverlayExport,
                mVariableFertViewModel = mVariableFertViewModel,
                context = context
            )
        }
        mVariableFertViewModel.fertAppliedLast.postValue(fertApplied)
    }
}
//...
