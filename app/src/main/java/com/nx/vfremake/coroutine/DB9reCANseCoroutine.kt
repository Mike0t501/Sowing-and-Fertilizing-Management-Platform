package com.nx.vfremake.coroutine

import android.serialport.SerialPort
import com.nx.vfremake.data.DepthTrialFix
import java.io.File

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.funClass.ConvAndCtrlFun
import com.nx.vfremake.funClass.MyArcGisFun
import com.nx.vfremake.funClass.MyGNSSFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.funClass.RmcData
import com.nx.vfremake.isSystemRunning
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 接收RMC解析存储并查询单体施肥量发送控制信息协程
 * @note   我有一些值的初始化放在类里了，因为协程类我一般都是先初始化，不会lateinit或者null
 */
class DB9reCANseCoroutine {
    private data class TrialLocationSession(val row: Int, val simulated: Boolean, val rmc: RmcData = RmcData())
    private var trialLocation: TrialLocationSession? = null
    // 因为我在Mainactivity顶层类里初始化DB9reCANseCoroutine()，可以这么写
    // 固定按最大行数 8 分配，避免运行中机型行数增大时下标越界（rowNumber 上限为 8）
    private val polyPointExport = Array(8) { arrayOfNulls<Point>(4) }

    // 每单体已落笔网格集合：键为按 ~DRAW_GRID_M 米量化的网格 id（经纬度空间哈希）。
    // 某单体只在首次进入某网格时落笔，重复压线/来回作业不再叠加多边形——把绘制量级从
    // 「随里程/时间线性、无上限」收敛到「= 实际驶过的网格数」，与车速、作业时长、重叠压线全部无关，
    // 彻底消除长时间作业越画越多最终 OOM/ANR 的隐患。固定按最大行数 8 分配，防越界。
    private val drawnCells = Array(8) { HashSet<Long>() }

    // 绘制图形需要持续调用函数，这个得放函数外面，才可以持续 add(Graphic)
    private val navMarkGraphicsOverlay = GraphicsOverlay() // 拖拉机和补偿点复用图层
    // 已施肥区域图层：作业期间持续累积多边形（从不清除）。用 STATIC 渲染模式——ArcGIS 对大量静态
    // 图形以后台线程缓存渲染、按需批量刷新，避免 DYNAMIC 每帧重绘上万个多边形拖垮渲染线程（田间
    // 实测约 10 分钟后卡死 ANR 的主因）。
    // 渲染模式只能在构造时指定（ArcGIS 的 renderingMode 为只读属性）
    private val fertGraphicsOverlayExport = GraphicsOverlay(GraphicsOverlay.RenderingMode.STATIC)

    // 定义一个协程作用域，保存作业引用的HashSet，以便于管理
    private var scope = CoroutineScope(Dispatchers.Default)
    private val jobs = HashSet<Job>()
    private var currentViewModel: VariableFertViewModel? = null
    private val fixRevision = AtomicLong()

    // 开始标志位
    var isRunning = false

    companion object {
        private const val GNSS_FIX_TIMEOUT_MS = 2500L

        // 绘制去重网格边长（米）：处方图渔网 2.4×4m，2m 量化既够细腻又与之相称。落笔按此网格去重，
        // 使已施肥多边形数量只与「实际驶过的网格数」相关，与 RMC 频率、车速、作业时长、重叠压线均无关，
        // 配合 STATIC 渲染从根本上消除长时间作业多边形无界累积导致的卡死/崩溃问题。
        private const val DRAW_GRID_M = 2.0

        // 纬度 1° 对应的米数（用于经纬度→米的网格量化）
        private const val METERS_PER_DEG_LAT = 111320.0

        // 由一条绘制带的两端点（WGS84 经纬度）算出其中点所在网格的唯一键。
        // 纬向按定值换算，经向乘 cos(lat) 修正；gx/gy 各取 32 位打包成 Long。
        private fun cellKeyOf(p0: Point, p1: Point): Long {
            val midLon = (p0.x + p1.x) / 2.0
            val midLat = (p0.y + p1.y) / 2.0
            val gy = floor(midLat * METERS_PER_DEG_LAT / DRAW_GRID_M).toLong()
            val gx = floor(
                midLon * METERS_PER_DEG_LAT * cos(Math.toRadians(midLat)) / DRAW_GRID_M
            ).toLong()
            return (gx shl 32) xor (gy and 0xffffffffL)
        }
    }

    fun shutdown() {
        scope.cancel() // 取消整个作用域，包括任何卡在 read() 里的 job
        currentViewModel?.let { clearGnssFix(it) }
        currentViewModel = null
        jobs.clear()
        drawnCells.forEach { it.clear() } // 释放去重集，下次作业从空覆盖开始
        isRunning = false
    }

    private fun startGnssSession(viewModel: VariableFertViewModel) {
        scope = CoroutineScope(Dispatchers.Default)
        currentViewModel = viewModel
        clearGnssFix(viewModel)
        if (trialLocation == null) mRmcData.directionAvailable = false
        drawnCells.forEach { it.clear() }
    }

    private fun clearGnssFix(viewModel: VariableFertViewModel, stopFertilizer: Boolean = false) {
        trialLocation?.let { trial ->
            trial.rmc.lastFixAtMs = 0
            trial.rmc.speedBufCount = 0
            trial.rmc.speedBufHead = 0
            trial.rmc.directionAvailable = false
            viewModel.updateDepthTrialLocation(null)
            return
        }
        val hadFix = mRmcData.lastFixAtMs != 0L
        fixRevision.incrementAndGet()
        mRmcData.lastFixAtMs = 0L
        mRmcData.status = ""
        mRmcData.speedBufCount = 0
        mRmcData.speedBufHead = 0
        mRmcData.forwardSpeed = 0.0
        mRmcData.forwardSpeedCalculate = 0.0
        mRmcData.gnssRawSpeed = 0.0
        viewModel.loLaDidegData.postValue(null)
        viewModel.gnssIsGood.postValue(true)
        viewModel.forwardspeed.postValue(0.0)
        viewModel.depthRowPositions.postValue(emptyList())
        viewModel.dantiLLGeo.postValue(null)
        viewModel.drawLLGeo.postValue(null)
        viewModel.invalidateDepthPrescriptionLocation()
        polyPointExport.forEach { it.fill(null) }
        if (hadFix && stopFertilizer && isSystemRunning && viewModel.depthPrescriptionMode.value != true) {
            for (row in 0 until mSPParamData.rowNumber) {
                if (!isSystemRunning) break
                ConvAndCtrlFun().motorSpeedrpmSend(0.0, row)
            }
            viewModel.fertApplied.postValue(DoubleArray(mSPParamData.rowNumber))
        }
    }

    /** 试验单独接收定位，不写作业定位、处方目标或 CAN。 */
    fun startTrial(context: Context, viewModel: VariableFertViewModel, row: Int, simulated: Boolean) {
        trialLocation = TrialLocationSession(row, simulated)
        if (simulated) start1Simulated(context, viewModel) else start1(context, viewModel)
    }

    suspend fun stopTrialAndJoin() {
        val running = jobs.toList()
        shutdown()
        running.forEach { it.join() }
    }

    private fun openTrialGnssPort(context: Context): SerialPort? {
        val preferences = MySharedPreFun(context)
        val port = preferences.getSpecificValue(R.string.serial_db9_port_name)
            ?: context.getString(R.string.serial_db9_port_defValue)
        val canPort = preferences.getSpecificValue(R.string.serial_can_port_name)
            ?: context.getString(R.string.serial_can_port_defValue)
        if (port.isBlank() || port == canPort) return null
        val baud = preferences.getSpecificValue(R.string.serial_db9_baud_name)?.toIntOrNull() ?: 230400
        return try {
            SerialPort(File("/dev/$port"), baud)
        } catch (e: Exception) {
            Log.w("DepthTrialGnss", "定位串口无法打开", e)
            null
        }
    }

    fun start1(
        context: Context,
        mVariableFertViewModel: VariableFertViewModel
    ) {
        startGnssSession(mVariableFertViewModel)

        // 在协程作用域内启动协程
        jobs.add(scope.launch(Dispatchers.IO) {
            val rmc = trialLocation?.rmc ?: mRmcData
            val existingPort = mSerialPortDB9
            val ownedPort = if (trialLocation != null && existingPort == null) openTrialGnssPort(context) else null
            try {
                val inputStreamDB9 = (existingPort ?: ownedPort)?.inputStream
                val buffer = ByteArray(80)  // 根据语句长度调整缓冲区大小
                var bytesRead: Int
                val sb = StringBuilder()

                while (isActive) {
                    val lastFix = rmc.lastFixAtMs
                    if (lastFix != 0L && SystemClock.elapsedRealtime() - lastFix > GNSS_FIX_TIMEOUT_MS) {
                        clearGnssFix(mVariableFertViewModel, stopFertilizer = true)
                    }
                    if (inputStreamDB9 == null || withContext(Dispatchers.IO) { inputStreamDB9.available() } == 0) {
                        // 如果CAN输入流没有消息，休息10ms停止执行后续代码，重新while
                        delay(10)
                        continue
                    }
                    try {
                        bytesRead = withContext(Dispatchers.IO) { inputStreamDB9.read(buffer) }
                        if (bytesRead < 0) break
                        // 将读取的数据转换为String并添加到StringBuilder中，sb会自动拼接字符，粘包后解析sb
                        sb.append(String(buffer, 0, bytesRead))

                        // 检查sb中是否存在'\n'，若存在，处理每行数据
                        while (sb.indexOf("\n") != -1) {
                            val endOfLineIndex = sb.indexOf("\n")
                            val line = sb.substring(0, endOfLineIndex).trim() // 获取一整行数据
                            // 检查行是否以RMC开头，若是，则处理
                            if (line.startsWith("\$GPRMC") || line.startsWith("\$GNRMC")) {
                                Log.d("GNSS", line)
                                // 兜底：任何单条坏报文/越界等异常都不得杀死控制协程；CancellationException 须放行
                                try {
                                    onGNSSMsgReceived(line, context, mVariableFertViewModel) { isActive }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    Log.e("DB9reCANseCoroutine", "onGNSSMsgReceived 异常（已跳过该帧）: ${t.message}", t)
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
            } finally {
                try { ownedPort?.tryClose() } catch (e: IOException) {
                    Log.w("DepthTrialGnss", "定位串口关闭失败", e)
                }
                if (isActive) clearGnssFix(mVariableFertViewModel, stopFertilizer = true)
            }
        })
        isRunning = true
    }

    /**
     * 模拟GNSS定位协程：无真实 GNSS 时，按设置界面配置的起点→终点航线，
     * 以设定速度匀速插值生成 RMC 语句，复用 onGNSSMsgReceived 整条处理链路。
     *
     * totalSteps / interval 不再写死：interval 为固定 tick（默认 400ms，可在
     * 高级项调整），用 haversine 计算起止距离，按设定速度推导 totalSteps，
     * 使模拟车辆严格按设定速度前进、到终点自动停（或循环重跑）。
     *
     * @note onGNSSMsgReceived 是本类私有方法，故模拟逻辑必须留在本类内。
     */
    fun start1Simulated(
        context: Context,
        mVariableFertViewModel: VariableFertViewModel
    ) {
        startGnssSession(mVariableFertViewModel)

        // 十进制度 → RMC 的「度分」格式（纬度2位度 / 经度3位度），沿用旧实现
        fun decimalToRMCFormat(coordinate: Double, isLatitude: Boolean): String {
            val degrees = abs(coordinate).toInt()
            val minutes = (abs(coordinate) - degrees) * 60
            return if (isLatitude) {
                String.format(Locale.US, "%02d%07.4f", degrees, minutes)
            } else {
                String.format(Locale.US, "%03d%07.4f", degrees, minutes)
            }
        }

        // 两经纬度间大圆距离（米）
        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val rEarth = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val sLat = sin(dLat / 2)
            val sLon = sin(dLon / 2)
            val a = sLat * sLat +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sLon * sLon
            return 2 * rEarth * atan2(sqrt(a), sqrt(1 - a))
        }

        // 读取模拟航线配置（缺失/非法时回退默认值）
        val sp = MySharedPreFun(context)
        fun cfg(nameId: Int, defId: Int): String =
            sp.getSpecificValue(nameId) ?: context.getString(defId)

        val startLat = cfg(R.string.simGnss_startLat_name, R.string.simGnss_startLat_defeatValue)
            .toDoubleOrNull() ?: 36.850825
        val startLon = cfg(R.string.simGnss_startLon_name, R.string.simGnss_startLon_defeatValue)
            .toDoubleOrNull() ?: 115.007949
        val endLat = cfg(R.string.simGnss_endLat_name, R.string.simGnss_endLat_defeatValue)
            .toDoubleOrNull() ?: 36.853154
        val endLon = cfg(R.string.simGnss_endLon_name, R.string.simGnss_endLon_defeatValue)
            .toDoubleOrNull() ?: 115.008574
        val speedKmh = (cfg(R.string.simGnss_speed_name, R.string.simGnss_speed_defeatValue)
            .toDoubleOrNull() ?: 15.0).coerceAtLeast(0.1)
        val interval = (cfg(R.string.simGnss_interval_name, R.string.simGnss_interval_defeatValue)
            .toLongOrNull() ?: 400L).coerceAtLeast(50L)
        val loop = cfg(R.string.simGnss_loop_name, R.string.simGnss_loop_defeatValue) == "1"

        // 由距离 + 速度推导步数：固定 tick，匀速从起点走到终点
        val distM = haversineMeters(startLat, startLon, endLat, endLon)
        val vMps = speedKmh / 3.6
        val tickS = interval / 1000.0
        val totalSteps = max(1, ceil(distM / (vMps * tickS)).toInt())
        val speedKnots = speedKmh / 1.852
        val lat1 = Math.toRadians(startLat)
        val lat2 = Math.toRadians(endLat)
        val lonDelta = Math.toRadians(endLon - startLon)
        val course = (Math.toDegrees(atan2(
            sin(lonDelta) * cos(lat2),
            cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lonDelta)
        )) + 360.0) % 360.0

        Log.i(
            "DB9reCANseCoroutine",
            "模拟GNSS：dist=%.1fm speed=%.1fkm/h steps=%d interval=%dms loop=%b"
                .format(distM, speedKmh, totalSteps, interval, loop)
        )

        jobs.add(scope.launch {
            do {
                for (step in 0..totalSteps) {
                    if (!isActive) return@launch
                    val fraction = step / totalSteps.toFloat()
                    val currentLat = startLat + (endLat - startLat) * fraction
                    val currentLon = startLon + (endLon - startLon) * fraction

                    val line = String.format(
                        Locale.US,
                        "\$GPRMC,171012.000,A,%s,%s,%s,%s,%.3f,%.4f,151216,,D*49",
                        decimalToRMCFormat(currentLat, true),
                        if (currentLat < 0.0) "S" else "N",
                        decimalToRMCFormat(currentLon, false),
                        if (currentLon < 0.0) "W" else "E",
                        speedKnots,
                        course,
                    )
                    Log.d("GNSS", line)
                    try {
                        onGNSSMsgReceived(line, context, mVariableFertViewModel) { isActive }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.e("DB9reCANseCoroutine", "onGNSSMsgReceived 异常（已跳过该帧）: ${t.message}", t)
                    }
                    delay(interval)
                }
            } while (loop && isActive)
            if (isActive) clearGnssFix(mVariableFertViewModel, stopFertilizer = true)
        })
        isRunning = true
    }
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
        mVariableFertViewModel: VariableFertViewModel,
        isRunActive: () -> Boolean,
    ) {
        if (!isRunActive()) return
        val trial = trialLocation
        val rmc = trial?.rmc ?: mRmcData
        val valid = MyGNSSFun().extractAndParseRMC(
            gnssSentence = gnssString,
            forwardSpeed = mSPParamData.forwardSpeed,
            mRmcData = rmc,
            context = context
        )
        if (!isRunActive()) return
        if (!valid) {
            if (rmc.lastFixAtMs != 0L) clearGnssFix(mVariableFertViewModel, stopFertilizer = true)
            return
        }
        val fixAt = SystemClock.elapsedRealtime()
        rmc.lastFixAtMs = fixAt
        if (trial != null) {
            val antenna = Point(rmc.longitude, rmc.latitude, SpatialReferences.getWgs84())
            val rowPoint = if (rmc.directionAvailable) runCatching {
                MyArcGisFun().depthRowPositions(antenna, rmc.directionDeg, mSPParamData).getOrNull(trial.row)
            }.getOrNull() else null
            if (isRunActive() && mVariableFertViewModel.isDepthTrialActive()) {
                mVariableFertViewModel.updateDepthTrialLocation(
                    DepthTrialFix(fixAt, antenna.x, antenna.y, rowPoint?.x, rowPoint?.y, trial.simulated)
                )
            }
            return
        }
        val revision = fixRevision.get()
        mVariableFertViewModel.rtkEverReceived = true
        mVariableFertViewModel.gnssIsGood.postValue(false)
        mVariableFertViewModel.loLaDidegData.postValue(
            Triple(mRmcData.longitude, mRmcData.latitude, mRmcData.directionDeg)
        )
        mVariableFertViewModel.forwardspeed.postValue(mRmcData.forwardSpeedCalculate)

        val arcGis = MyArcGisFun()
        val point = Point(mRmcData.longitude, mRmcData.latitude, SpatialReferences.getWgs84())
        val isSameFix = { isRunActive() && revision == fixRevision.get() && mRmcData.lastFixAtMs == fixAt }
        if (mVariableFertViewModel.depthPrescriptionMode.value == true) {
            val heading = mRmcData.directionDeg
            val lagMeters = mRmcData.forwardSpeed * mSPParamData.lagTime / 3.6
            val compensatedAntenna = if (mRmcData.directionAvailable) {
                arcGis.offsetGeographicPosition(point, heading, lagMeters)
            } else point
            arcGis.addMarkerToMap(mRmcData, compensatedAntenna, navMarkGraphicsOverlay,
                mVariableFertViewModel, context, isSameFix)
            if (!mRmcData.directionAvailable || !isSameFix()) return
            val rowPositions = arcGis.depthRowPositions(point, heading, mSPParamData)
            val targetPositions = if (lagMeters == 0.0) rowPositions
                else arcGis.depthRowPositions(point, heading, mSPParamData, lagMeters)
            if (!isSameFix()) return
            mVariableFertViewModel.depthRowPositions.postValue(rowPositions.asList())
            mVariableFertViewModel.dantiLLGeo.postValue(rowPositions)
            arcGis.queryDepthTargets(targetPositions, mVariableFertViewModel)
            return
        }

        // 位置补偿
        val wgsCompensatedPoint = arcGis.calculateCompensatedPosition(
            pointXY = arcGis.doProjection(point),
            forwardspeed = mRmcData.forwardSpeed,
            directionRad = mRmcData.directionRad,
            lagTime = mSPParamData.lagTime
        )

        // 添加当前位置图标
        arcGis.addMarkerToMap(
            mRmcData = mRmcData,
            pointCompensationLLGeo = arcGis.doGeographic(wgsCompensatedPoint),
            navMarkGraphicsOverlay = navMarkGraphicsOverlay,
            mVariableFertViewModel = mVariableFertViewModel,
            context = context,
            isCurrentFix = isSameFix,
        )
        // 计算单体位置生成并发送控制信息
        val pointi = arcGis.dantiPositionAndCtrl(
            pointXY = wgsCompensatedPoint,
            directionRad = mRmcData.directionRad,
            mSPParamData = mSPParamData,
            mVariableFertViewModel = mVariableFertViewModel,
            isGnssActive = { isRunActive() && revision == fixRevision.get() },
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

        // 绘制已施肥区域：只画开启的单体、按真实位置；关闭的单体留空
        // （不再绘制整幅绿色带，避免只开部分单体时仍铺满整机宽度）
        // 停车/低速门控：近零速时 RTK 航向角不可靠，幅宽横排会绕定位点旋转，逐帧缝合会把
        // 条形带画成发散的圆疙瘩。此时暂停绘制——polyPointExport 保持上次末端不变，恢复前进后
        // 下一次绘制自然从"上次末端 → 新位置"桥接续上，无空洞；同时避免停车时图层无限累积退化四边形。
        // 施肥指令已在前面处理，这里只门控绘制。
        val fertApplied = mVariableFertViewModel.fertApplied.value
        // 绘制去重：每单体只在首次进入某网格（~DRAW_GRID_M 米）时落笔。原绘制频率与 RMC 频率挂钩，
        // 高频/慢速时同一处堆叠上万个多边形，约 10 分钟拖垮地图渲染导致卡死；即便按里程抽稀，反复压线/
        // 来回作业仍会随面积无界累积。改为按网格去重后，多边形数量上界 = 实际驶过的网格数，与车速、
        // 作业时长、重叠压线全部无关，从根本上消除长时间作业崩溃隐患。
        if (mRmcData.gnssRawSpeed >= RmcData.STANDSTILL_SPEED_KMH) {
            for (i in 0 until mSPParamData.rowNumber) {
                if (!(mSPParamData.activeMotors.getOrNull(i) ?: true)) continue // 关闭的单体不绘制
                val p0 = pointi[1][i]
                val p1 = pointi[1][i + 1]
                // 该单体本帧绘制带的网格键；已画过则跳过，但仍把 polyPointExport 起点推进到当前条末端，
                // 避免下一次落笔从旧覆盖区跨越式拉出一条长四边形。
                val key = cellKeyOf(p0, p1)
                if (!drawnCells[i].add(key)) {
                    polyPointExport[i][0] = p0
                    polyPointExport[i][1] = p1
                    continue
                }
                val exportMsg = doubleArrayOf(
                    mVariableFertViewModel.fertApplied.value?.get(i) ?: 0.0,
                    mVariableFertViewModel.confertflow.value?.get(i) ?: 0.0,
                    mVariableFertViewModel.monfertflow.value?.get(i) ?: 0.0,
                    mVariableFertViewModel.forwardspeed.value ?: 0.0
                )
                MyArcGisFun().drawPolyExport(
                    polyPoint = polyPointExport[i],
                    point = arrayOf(p0, p1),
                    exportMsg = exportMsg,
                    fertGraphicsOverlay = fertGraphicsOverlayExport,
                    mVariableFertViewModel = mVariableFertViewModel,
                    context = context
                )
            }
        }
        mVariableFertViewModel.fertAppliedLast.postValue(fertApplied)
    }
}
//...
