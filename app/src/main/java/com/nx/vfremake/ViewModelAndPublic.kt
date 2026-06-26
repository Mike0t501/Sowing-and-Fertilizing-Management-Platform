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
var lastSentMotorSpeed = DoubleArray(TOTAL_NODE_COUNT) { 0.0 }

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
val canMonitorData = Array(TOTAL_NODE_COUNT) { "---" }

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
    val seedMotorSpeed = MutableLiveData(DoubleArray(SEED_NODE_COUNT) { 0.0 })
    val seedSensorCount = MutableLiveData(IntArray(SEED_NODE_COUNT) { 0 })
    val seedTargetRpm = MutableLiveData(DoubleArray(SEED_NODE_COUNT) { 0.0 })
    val fertMotorSpeed = MutableLiveData(DoubleArray(FERT_NODE_COUNT) { 0.0 })
    val motorCurrent = MutableLiveData(DoubleArray(TOTAL_NODE_COUNT) { 0.0 })
    val motorVoltage = MutableLiveData(DoubleArray(TOTAL_NODE_COUNT) { 0.0 })
    val motorElectricalValid = MutableLiveData(BooleanArray(TOTAL_NODE_COUNT) { false })
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

    // ====== 从“开始作业”之后的时间平均统计 ======
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
                if (fert > 0.1) {
                    val acc = (100.0 - kotlin.math.abs((fert - target) / target) * 100.0)
                        .coerceIn(0.0, 100.0)
                    accArray[i] = acc
                    displayArray[i] = fert
                    frameFertSum += fert
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
