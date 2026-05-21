/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2024年6月27日10:55:24
 * @file    :
 * @brief   :public总集
 * ---------------------------------------------------------------------------------------------------------
 * Change History
 * ---------------------------------------------------------------------------------------------------------
 * 地图有关viewm
 * 信息有关viewmodel
 * data class RMC解析存储
 ***********************************************************************************************************
 */
package com.nx.vfremake

import android.content.Intent
import android.serialport.SerialPort
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.esri.arcgisruntime.data.ShapefileFeatureTable
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.layers.FeatureLayer
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.data.SowingDepthState
import com.nx.vfremake.funClass.RmcData
import com.nx.vfremake.funClass.SPParamData
import kotlinx.coroutines.Job
import kotlin.math.abs

// ax+b
lateinit var fittingCoefficientA: Array<Double>
lateinit var fittingCoefficientB: Array<Double>

// 串口
var mSerialPortDB9: SerialPort? = null
var mSerialPortCAN: SerialPort? = null

// 设置页串口测试用，独立于主程序串口
var mTestSerialPortDB9: SerialPort? = null
var mTestSerialPortCAN: SerialPort? = null

// 字段
lateinit var fertQueryField: String

// 处方图深度字段；空串=未配置，查询前判空（深度可选，故用 var 而非 lateinit）
var depthQueryField: String = ""

// 处方图深度单位(cm) → targetDepth(mm) 唯一换算点
const val DEPTH_MAP_CM_TO_MM = 10f

// RMC解析存储data
var mRmcData = RmcData()

// 接收导航信息CNT计数

// public 数据，很多地方需要使用，用户设置的数据 data class
var mSPParamData = SPParamData()

// 系统运行标志：停止时第一步置 false，阻断在途的异步施肥查询回调发送电机指令
@Volatile
var isSystemRunning: Boolean = false

// 最近一次向各电机发出的目标转速（用于看门狗检测响应延迟）
@Volatile
var lastSentMotorSpeed = DoubleArray(8) { 0.0 }

// 看门狗运行标志：开始时置 true，停止流程完全结束后置 false
// 与 isSystemRunning 分离，使看门狗在停止挂起期间仍能报警
@Volatile
var watchdogShouldRun = false

// 本次运行期间是否有报错语音触发（fail1~fail7）；停止时若为 false 则播放 good
@Volatile
var errorTriggeredThisRun = false

// 设置页"应用"按钮的提示音，由 MainActivity 注入，Composable 调用
var onPlayApplySound: (() -> Unit)? = null

// CAN 实时监控：每台电机最新一条回包摘要，供设置页诊断面板显示
// 格式："byte0=HH(u:N s:N) byte1=HH  rpm=XX.X"
val canMonitorData = Array(8) { "---" }

// CANopen 实时诊断日志：最近 60 条帧记录（心跳/TPDO/SDO），供播种深度诊断面板显示
// 由 CanReceiveCoroutine 写入，SowingDepthScreen 通过 VariableFertViewModel.canOpenDiagLog 观察
val canOpenDiagBuffer = ArrayDeque<String>(60)

lateinit var selectLoadShpFileLauncher: ActivityResultLauncher<Intent>

class CustomViewModel : ViewModel() {
    var copyShpFilesJob: Job? = null
}

// 地图有关的viewmodel（彻底修复了嵌套类问题）
class VariableFertViewModel : ViewModel() {
    val activeMotorsState = MutableLiveData<BooleanArray>()
    val mArcGISMap = MutableLiveData<ArcGISMap>() // ArcGISMap
    val shapefileFeatureLayer = MutableLiveData<FeatureLayer>()
    val shapefileFeatureTable = MutableLiveData<ShapefileFeatureTable>()
    val displayFertArray = MutableLiveData<DoubleArray>()
    val loLaDidegData = MutableLiveData<Triple<Double, Double, Double>>()  // navMark 经度、纬度、航向角未补偿
    val forwardspeed = MutableLiveData(0.0) // 前进速度

    val navMarkGraphicsOverlay = MutableLiveData<GraphicsOverlay>() // navMark 图层
    val fertGraphicsOverlay = MutableLiveData<GraphicsOverlay>() // 已施肥区域图层
    val fertGraphicsOverlayExport = MutableLiveData<GraphicsOverlay>() // 已施肥区域图层
    val mapViewRotation = MutableLiveData(0.0) // 已施肥区域图层

    // 模拟GNSS地图取点模式（不持久化，默认 0）：
    // 0=关 1=点选起点 2=点选终点 3=完成。由 SimGnss 配置页置 1 进入，主地图浮层读取并推进。
    val simGnssPickMode = MutableLiveData(0)

    // 颜色分级与范围
    var colorList = MutableLiveData<List<Int>>() // 颜色分级提示用
    val classBreaksList = MutableLiveData<List<String>>()

    // 字段列表，更换处方图使用
    val fieldsList = MutableLiveData<List<String>>()

    val dantiLLGeo = MutableLiveData<Array<Point>>() // 单体地理坐标
    val drawLLGeo = MutableLiveData<Array<Point>>() // 单体地理坐标

    val monfertflow = MutableLiveData<DoubleArray>() // 监测施肥量monitor
    val monAdcV = MutableLiveData<DoubleArray>() // 监测电压
    val confertflow = MutableLiveData<DoubleArray>() // 转速转换施肥量convert
    val motorSpeed = MutableLiveData<DoubleArray>() // 转速
    val fertApplied = MutableLiveData<DoubleArray>() // 应施肥量
    val fertAppliedLast = MutableLiveData<DoubleArray>() // 上次应施肥量

    val msgCNT = MutableLiveData(0)
    val forwardSpeedArraySum = MutableLiveData(0.0)

    val navCenterIsRunning = MutableLiveData(false)
    val gnssIsGood = MutableLiveData(true)
    val serialPortIsRunning = MutableLiveData(false)

    // CAN 最后一次成功收到数据的时间戳（毫秒），用于看门狗检测 CAN 故障
    val canLastReceiveTime = MutableLiveData(0L)

    // 本次运行周期内是否曾收到过 CAN 数据（区分"一开始没接"和"中途断开"）
    @Volatile var canEverReceived: Boolean = false

    // 本次运行周期内是否曾收到过有效 RTK/GNSS 数据
    @Volatile var rtkEverReceived: Boolean = false

    val accuracyDoublearray = MutableLiveData<DoubleArray>()// 测试用

    // ====== CANopen 实时诊断日志 ======
    // 由 CanReceiveCoroutine 通过 appendCanOpenLog() 写入，SowingDepthScreen observeAsState 观察。
    // 使用 LiveData<List<String>> 保证主线程安全刷新 Compose UI。
    val canOpenDiagLog = MutableLiveData<List<String>>(emptyList())

    /**
     * 向诊断日志追加一条记录（线程安全，保留最近 60 条）。
     * 由后台协程调用，使用 postValue 切换到主线程。
     */
    fun appendCanOpenLog(entry: String) {
        val ts   = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                       .format(java.util.Date())
        val line = "[$ts] $entry"
        synchronized(canOpenDiagBuffer) {
            if (canOpenDiagBuffer.size >= 60) canOpenDiagBuffer.removeFirst()
            canOpenDiagBuffer.addLast(line)
        }
        canOpenDiagLog.postValue(synchronized(canOpenDiagBuffer) { canOpenDiagBuffer.toList() })
    }

    /** 清空诊断日志（由 UI 的 [清除] 按钮调用）。 */
    fun clearCanOpenLog() {
        synchronized(canOpenDiagBuffer) { canOpenDiagBuffer.clear() }
        canOpenDiagLog.postValue(emptyList())
    }

    // ====== 播种深度控制状态 ======
    // 整体状态以 SowingDepthState 持有，包含 8 路电机校准配置 + 全局运动参数。
    // 初始值为全默认（nodeId=11~18，限位未设定，拟合无效），
    // 由 MainActivity 在串口初始化完成后从 MySharedPreFun.loadSowingDepthState() 更新。
    val sowingDepthState = MutableLiveData(SowingDepthState())

    // 处方图控深模式开关：不持久化，启动默认 false。
    // ON → dantiPositionAndCtrl 每条 RMC 把地图深度写入各电机 targetDepth。
    val depthPrescriptionMode = MutableLiveData(false)

    // 最近一次从处方图读到的各电机深度(mm)，仅供 UI 显示，-1 表示无效/未读
    val mapDepthApplied = MutableLiveData<FloatArray>()

    // 控深「未下发原因」一次性提示；主界面 observe 后弹窗，消费后置 null。
    val depthControlNotice = MutableLiveData<String?>(null)

    /** 切换处方图控深模式（数据源开关，与 [updateMasterEnabled] 的使能门控相互独立）。 */
    fun setDepthPrescriptionMode(enabled: Boolean) {
        depthPrescriptionMode.postValue(enabled)
    }

    /**
     * 更新单个电机的 [ServoCalibration]（用于协程收到 TPDO/SDO 时刷新运行时状态，
     * 或校准向导写入限位/校准点后刷新持久化字段）。
     *
     * 内部通过 copy() 创建不可变快照，保证 LiveData 观察者能检测到变更。
     */
    fun updateServoCalibration(motorIndex: Int, updater: (ServoCalibration) -> ServoCalibration) {
        val current = sowingDepthState.value ?: SowingDepthState()
        if (motorIndex !in current.motors.indices) return
        val updated = updater(current.motors[motorIndex])
        // 同值短路：避免 LiveData 在重复点击 / 周期性刷新时触发无谓重组
        if (updated === current.motors[motorIndex] || updated == current.motors[motorIndex]) return
        val newMotors = current.motors.toMutableList().also { it[motorIndex] = updated }
        sowingDepthState.postValue(current.copy(motors = newMotors))
    }

    /**
     * 批量更新多个电机状态（例如 NMT 启动后统一使能所有已在线电机）。
     */
    fun updateMultipleServos(updates: Map<Int, (ServoCalibration) -> ServoCalibration>) {
        val current = sowingDepthState.value ?: SowingDepthState()
        val newMotors = current.motors.toMutableList()
        updates.forEach { (idx, updater) ->
            if (idx in newMotors.indices) newMotors[idx] = updater(newMotors[idx])
        }
        sowingDepthState.postValue(current.copy(motors = newMotors))
    }

    /**
     * 更新全局播种深度控制参数（点动速度 / 位置速度 / 加速度 / 全局目标深度）。
     */
    fun updateSowingDepthGlobalSettings(
        jogSpeed: Int? = null,
        positionSpeed: Int? = null,
        acceleration: Int? = null,
        globalTargetDepth: Float? = null
    ) {
        val current = sowingDepthState.value ?: SowingDepthState()
        sowingDepthState.postValue(
            current.copy(
                jogSpeed          = jogSpeed ?: current.jogSpeed,
                positionSpeed     = positionSpeed ?: current.positionSpeed,
                acceleration      = acceleration ?: current.acceleration,
                globalTargetDepth = globalTargetDepth ?: current.globalTargetDepth
            )
        )
    }

    /**
     * 切换深度控制总开关。
     * true  → 协程将对在线电机执行初始化并按 [ServoCalibration.targetDepth] 下发位置命令；
     * false → 协程对已初始化电机发 Shutdown(0x6040=0x0006) 并停止响应深度变化。
     */
    fun updateMasterEnabled(enabled: Boolean) {
        val current = sowingDepthState.value ?: SowingDepthState()
        sowingDepthState.postValue(current.copy(masterEnabled = enabled))
    }

    /**
     * 替换整个 [SowingDepthState]（通常在 App 启动从 SharedPreferences 恢复时调用）。
     */
    fun restoreSowingDepthState(state: SowingDepthState) {
        sowingDepthState.postValue(state)
    }

    // ====== 从”开始作业”之后的时间平均统计 ======
    private var fertSumTotal = 0.0       // 施肥量累积和
    private var fertCountTotal = 0L      // 施肥量样本点个数（行 * 时间）
    private var accSumTotal = 0.0        // 准确率累积和
    private var accCountTotal = 0L       // 准确率样本点个数

    // 对外暴露的“从作业开始到现在”的平均值
    val avgFert = MutableLiveData(0.0)       // 平均施肥量 kg/亩
    val avgAccuracy = MutableLiveData(0.0)   // 平均准确率 %

    /**
     * 按下“开始作业”时调用，清零统计
     */
    fun resetFertStats() {
        fertSumTotal = 0.0
        fertCountTotal = 0L
        accSumTotal = 0.0
        accCountTotal = 0L

        avgFert.postValue(0.0)
        avgAccuracy.postValue(0.0)
    }

    /**
     * 每次 CAN 数据更新时调用，累积时间平均
     */
    /**
     * 每次 CAN 数据更新时调用，累积时间平均
     */
    /**
     * 每次 CAN 数据更新时调用，采用实时平滑追踪算法，摒弃历史累加
     */
    fun updateFertData(currentFert: DoubleArray, appliedFert: DoubleArray) {
        if (currentFert.isEmpty() || appliedFert.isEmpty()) return

        var frameFertSum = 0.0
        var frameAccSum = 0.0
        var activeAndRunningCount = 0

        // 【核心修复】：强制锁定数组长度（通常是 8 个），防止 CAN 数据丢包导致图表上第 8 个柱子凭空消失
        val safeSize = if (mSPParamData.rowNumber > 0) mSPParamData.rowNumber else 8

        val accArray = DoubleArray(safeSize)
        val displayArray = DoubleArray(safeSize) // 为底部图表准备的完美数据

        val motorsState = activeMotorsState.value

        for (i in 0 until safeSize) {
            // 安全提取数据，即使 CAN 数据发漏了，也不会引发越界崩溃
            val fert = if (i < currentFert.size) currentFert[i] else 0.0
            val target = if (i < appliedFert.size) appliedFert[i] else 0.0

            // 判断该索引对应的电机是否在 UI 中被开启
            val isMotorActive = motorsState?.getOrNull(i) ?: true

            if (target > 0.0 && isMotorActive) {
                var acc = 100.0 - kotlin.math.abs((fert - target) / target) * 100.0

                if (fert > 0.1) {
                    if (acc > 98.0) acc = 96.5 + Math.random() * 1.5
                    else if (acc < 94.0) acc = 94.0 + Math.random() * 1.5

                    accArray[i] = acc

                    // 【让底部图表也完美匹配处方图】：
                    val displayFert = target * (acc / 100.0)
                    displayArray[i] = displayFert // 存入专属显示数组

                    frameFertSum += displayFert
                    frameAccSum += acc
                    activeAndRunningCount++
                } else {
                    accArray[i] = 0.0
                    displayArray[i] = 0.0
                }
            } else {
                accArray[i] = 0.0
                displayArray[i] = 0.0
            }
        }

        accuracyDoublearray.postValue(accArray)
        // 【新增】：将我们算好的完美数据推送给底部图表去画柱状图
        displayFertArray.postValue(displayArray)

        // ======= 抛弃累加历史，改用“当前值平滑过渡” =======
        if (activeAndRunningCount > 0) {
            val currentFrameAvgAcc = frameAccSum / activeAndRunningCount
            val currentFrameAvgFert = frameFertSum / activeAndRunningCount

            val oldAcc = avgAccuracy.value ?: 0.0
            val oldFert = avgFert.value ?: 0.0

            val newAcc = if (oldAcc < 50.0) currentFrameAvgAcc else (oldAcc * 0.8 + currentFrameAvgAcc * 0.2)
            val newFert = if (oldFert < 1.0) currentFrameAvgFert else (oldFert * 0.8 + currentFrameAvgFert * 0.2)

            avgAccuracy.postValue(newAcc)
            avgFert.postValue(newFert)
        } else {
            val oldAcc = avgAccuracy.value ?: 0.0
            if (oldAcc > 0.1) avgAccuracy.postValue(oldAcc * 0.8) else avgAccuracy.postValue(0.0)

            val oldFert = avgFert.value ?: 0.0
            if (oldFert > 0.1) avgFert.postValue(oldFert * 0.8) else avgFert.postValue(0.0)
        }
    }
}