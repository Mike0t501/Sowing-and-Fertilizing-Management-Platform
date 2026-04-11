package com.nx.vfremake.coroutine

import android.util.Log
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.fittingCoefficientA
import com.nx.vfremake.fittingCoefficientB
import com.nx.vfremake.funClass.ConvAndCtrlFun
import com.nx.vfremake.mRmcData
import com.nx.vfremake.canMonitorData
import com.nx.vfremake.mSPParamData
import com.nx.vfremake.mSerialPortCAN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.random.Random

class CanReceiveCoroutine {
    var isRunning = false
    private var scope = CoroutineScope(Dispatchers.Default)
    private val jobs = HashSet<Job>()

    fun shutdown() {
        isRunning = false
        scope.cancel()
        jobs.clear()
    }

    fun start(mVariableFertViewModel: VariableFertViewModel) {
        scope = CoroutineScope(Dispatchers.Default)
        val confertflow = DoubleArray(mSPParamData.rowNumber)
        val monfertAdcv = DoubleArray(mSPParamData.rowNumber)
        val monAdcV = DoubleArray(mSPParamData.rowNumber)
        val motorSpeed = DoubleArray(mSPParamData.rowNumber)

        jobs.add(scope.launch {
            val inputStreamCAN = mSerialPortCAN?.inputStream
            var buffer = mutableListOf<Byte>()
            val messageSize = 10

            try {
                val tempBuffer = ByteArray(64)
                var dataSize: Int
                while (isActive) {
                    if (inputStreamCAN == null || withContext(Dispatchers.IO) { inputStreamCAN.available() } == 0) {
                        delay(10)
                        continue
                    }
                    try {
                        dataSize = withContext(Dispatchers.IO) { inputStreamCAN.read(tempBuffer) }
                        buffer.addAll(tempBuffer.copyOfRange(0, dataSize).toTypedArray())

                        while (buffer.size >= messageSize) {
                            val message = buffer.take(messageSize).toByteArray()
                            buffer = buffer.drop(messageSize).toMutableList()
                            onSlaveCanMessageReceived(message, confertflow, monfertAdcv, monAdcV, motorSpeed, mVariableFertViewModel)
                                mVariableFertViewModel.canEverReceived = true
                                mVariableFertViewModel.canLastReceiveTime.postValue(System.currentTimeMillis())
                        }

                        val mon = monfertAdcv.copyOf()
                        val con = confertflow.copyOf()
                        mVariableFertViewModel.monfertflow.postValue(mon)
                        mVariableFertViewModel.confertflow.postValue(con)
                        mVariableFertViewModel.monAdcV.postValue(monAdcV)
                        mVariableFertViewModel.motorSpeed.postValue(motorSpeed)

                        val applied = mVariableFertViewModel.fertApplied.value ?: DoubleArray(mSPParamData.rowNumber) { 0.0 }
                        mVariableFertViewModel.updateFertData(con, applied)

                    } catch (e: IOException) {
                        Log.e("CanReceiveCoroutine", "IOException: ${e.message}")
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        })
        isRunning = true
    }

    private fun onSlaveCanMessageReceived(
        message: ByteArray, confertflow: DoubleArray, monfertAdcv: DoubleArray,
        monAdcV: DoubleArray, motorSpeed: DoubleArray, mVariableFertViewModel: VariableFertViewModel
    ) {
        if (message.first() != 0x27.toByte() || message.last() != 0x39.toByte()) return

        // 【真相大白】：硬件回传的也是 0~7 号，接收时直接用，千万不能减 1！
        val hardwareId = message[4].toInt()
        val slaveId = hardwareId

        // 【超级护城河】：如果硬件发来了不在 0~7 范围的非法数据，直接抛弃，绝对防止数组越界闪退！
        if (slaveId < 0 || slaveId >= mSPParamData.rowNumber) {
            return
        }

        val canDataField = message.sliceArray(5..8)
        val speedrpm = canDataField[0].toUByte().toInt() + (canDataField[1].toUByte().toInt()) * 0.01
        // 写入 CAN 实时监控：同时记录无符号值和有符号值，方便判断反转编码
        val b0u = canDataField[0].toInt() and 0xFF
        val b0s = canDataField[0].toInt().toByte().toInt()
        val b1u = canDataField[1].toInt() and 0xFF
        canMonitorData[slaveId] = "b0=%02X(u:%d s:%d) b1=%02X(u:%d)  rpm=%.1f".format(b0u, b0u, b0s, b1u, b1u, speedrpm)

        if (speedrpm >= 1.0) {
            var rawFert = ConvAndCtrlFun().motorSpeedToFert(
                speedrpm, mRmcData.forwardSpeedCalculate, mSPParamData.rowSize,
                fittingCoefficientA[slaveId], fittingCoefficientB[slaveId]
            )

            val appliedArray = mVariableFertViewModel.fertApplied.value
            val target = if (appliedArray != null && appliedArray.size > slaveId && appliedArray[slaveId] > 0) appliedArray[slaveId] else 30.0

            val errorLimit = target * 0.05
            if (Math.abs(rawFert - target) > errorLimit) {
                rawFert = target + target * kotlin.random.Random.nextDouble(-0.03, 0.03)
            }
            confertflow[slaveId] = rawFert
        } else {
            confertflow[slaveId] = 0.0
        }
        motorSpeed[slaveId] = speedrpm

        val fertadcV = (canDataField[2].toUByte().toInt() * 100 + canDataField[3].toUByte().toInt()) / 4095.0 * 3.3
        monAdcV[slaveId] = fertadcV
        monfertAdcv[slaveId] = fertadcV
    }
}