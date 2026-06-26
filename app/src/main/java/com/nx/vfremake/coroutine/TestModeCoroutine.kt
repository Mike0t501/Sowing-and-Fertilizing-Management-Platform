package com.nx.vfremake.coroutine

import android.content.Context
import androidx.compose.runtime.MutableState
import com.nx.vfremake.R
import com.nx.vfremake.FERT_NODE_COUNT
import com.nx.vfremake.SEED_NODE_COUNT
import com.nx.vfremake.FERT_NODE_START_INDEX
import com.nx.vfremake.SEED_NODE_START_INDEX
import com.nx.vfremake.TOTAL_NODE_COUNT
import com.nx.vfremake.fittingCoefficientA
import com.nx.vfremake.fittingCoefficientB
import com.nx.vfremake.funClass.ConvAndCtrlFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.mSPParamData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TestModeCoroutine {
    // 定义一个协程作用域，保存作业引用的HashSet，以便于管理
    // 【修复】：改为 var，每次 start() 重建，防止旧 scope 因异常取消后 launch 变成静默空转
    private var scope = CoroutineScope(Dispatchers.Default)
    private val jobs = HashSet<Job>()

    // 开始标志位
    var isRunning = false

    fun shutdown() {
        scope.cancel() // 取消整个 scope，与 DB9reCANseCoroutine 保持一致
        jobs.clear()
        isRunning = false
    }

    fun start(context: Context, isRunningState: MutableState<Boolean>) {
        // 【修复】：每次启动重建 scope，防止上次因异常取消的 scope 导致 launch 永远无效
        scope = CoroutineScope(Dispatchers.Default)
        var elapsedTime = 0L
        val testModeDurationTime = ((
                MySharedPreFun(context).getSpecificValue(R.string.testMode_testModeDurationTime_name)
                    ?.toIntOrNull()
                    ?: context.getString(R.string.testMode_testModeDurationTime_defeatValue).toInt()
                ) * 1000).toLong()
        val testSend =
            (MySharedPreFun(context).getSpecificValue(R.string.testMode_testSend_name)
                ?: context.getString(R.string.testMode_testSend_defeatValue))
                .toDouble()
        val testSendMode =
            (MySharedPreFun(context).getSpecificValue(R.string.testMode_testSendMode_name))
                ?: context.getString(R.string.testMode_testSendMode_defeatValue)

        jobs.add(scope.launch {
            isRunning = true
            while (elapsedTime < testModeDurationTime) {
                delay(200) // 每200毫秒发送一次数据
                elapsedTime += 200
                if (testSendMode == "0") {
                    for (i in 0 until TOTAL_NODE_COUNT) {
                        ConvAndCtrlFun().motorSpeedrpmSend(
                            testSend,
                            i
                        )
                    }
                } else {
                    val simulatedSpeed = testSend.coerceIn(0.0, 30.0)
                    val seedRpm = ConvAndCtrlFun().seedToMotorSpeed(simulatedSpeed)
                    for (i in 0 until SEED_NODE_COUNT) {
                        ConvAndCtrlFun().motorSpeedrpmSend(seedRpm, i + SEED_NODE_START_INDEX)
                    }

                    val targetFert = mSPParamData.fertApplied.coerceAtLeast(0.0)
                    for (i in 0 until FERT_NODE_COUNT) {
                        val a = runCatching { fittingCoefficientA[i] }.getOrDefault(0.0)
                        val b = runCatching { fittingCoefficientB[i] }.getOrDefault(0.0)
                        val flowtoRpm = ConvAndCtrlFun().fertToMotorSpeed(
                            targetFert,
                            simulatedSpeed,
                            mSPParamData.rowSize,
                            a,
                            b
                        )
                        ConvAndCtrlFun().motorSpeedrpmSend(
                            flowtoRpm,
                            i + FERT_NODE_START_INDEX
                        )
                    }
                }
            }
            // 停止时电机也停止
            for (i in 0 until TOTAL_NODE_COUNT) {
                ConvAndCtrlFun().motorSpeedrpmSend(
                    0.0,
                    i
                )
            }
            shutdown()
            isRunningState.value = false
        })
    }
}
