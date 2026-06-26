package com.nx.vfremake.coroutine

import android.util.Log
import com.nx.vfremake.FERT_NODE_COUNT
import com.nx.vfremake.FERT_NODE_START_INDEX
import com.nx.vfremake.SEED_NODE_COUNT
import com.nx.vfremake.SEED_NODE_START_INDEX
import com.nx.vfremake.TOTAL_NODE_COUNT
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.canMonitorData
import com.nx.vfremake.fittingCoefficientA
import com.nx.vfremake.fittingCoefficientB
import com.nx.vfremake.funClass.ConvAndCtrlFun
import com.nx.vfremake.mRmcData
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

        val confertflow = DoubleArray(FERT_NODE_COUNT)
        val monfertAdcv = DoubleArray(FERT_NODE_COUNT)
        val monAdcV = DoubleArray(FERT_NODE_COUNT)
        val motorSpeed = DoubleArray(TOTAL_NODE_COUNT)
        val fertMotorSpeed = DoubleArray(FERT_NODE_COUNT)
        val seedMotorSpeed = DoubleArray(SEED_NODE_COUNT)
        val seedSensorCount = IntArray(SEED_NODE_COUNT)
        val seedRawLast = IntArray(SEED_NODE_COUNT) { -1 }
        val motorCurrent = DoubleArray(TOTAL_NODE_COUNT)
        val motorVoltage = DoubleArray(TOTAL_NODE_COUNT)
        val motorElectricalValid = BooleanArray(TOTAL_NODE_COUNT)

        jobs.add(scope.launch {
            val inputStreamCAN = mSerialPortCAN?.inputStream
            var buffer = mutableListOf<Byte>()

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

                        while (buffer.isNotEmpty()) {
                            val start = buffer.indexOfFirst { it == 0x27.toByte() }
                            if (start < 0) {
                                buffer.clear()
                                break
                            }
                            if (start > 0) buffer = buffer.drop(start).toMutableList()
                            if (buffer.size < 2) break

                            val isExtended = (buffer[1].toInt() and 0xFF) == 0x0B
                            val frameSize = if (isExtended) 14 else 10
                            if (buffer.size < frameSize) break
                            if (buffer[frameSize - 1] != 0x39.toByte()) {
                                buffer.removeAt(0)
                                continue
                            }

                            val message = buffer.take(frameSize).toByteArray()
                            buffer = buffer.drop(frameSize).toMutableList()
                            if (isExtended) {
                                onWrappedCanMessageReceived(
                                    message.sliceArray(5..12),
                                    confertflow,
                                    monfertAdcv,
                                    monAdcV,
                                    motorSpeed,
                                    fertMotorSpeed,
                                    seedMotorSpeed,
                                    seedSensorCount,
                                    seedRawLast,
                                    motorCurrent,
                                    motorVoltage,
                                    motorElectricalValid
                                )
                            } else {
                                onLegacyCanMessageReceived(
                                    message,
                                    confertflow,
                                    monfertAdcv,
                                    monAdcV,
                                    motorSpeed,
                                    fertMotorSpeed,
                                    seedMotorSpeed,
                                    seedSensorCount,
                                    seedRawLast
                                )
                            }
                            mVariableFertViewModel.canEverReceived = true
                            mVariableFertViewModel.canLastReceiveTime.postValue(System.currentTimeMillis())
                        }

                        mVariableFertViewModel.monfertflow.postValue(monfertAdcv.copyOf())
                        mVariableFertViewModel.confertflow.postValue(confertflow.copyOf())
                        mVariableFertViewModel.monAdcV.postValue(monAdcV.copyOf())
                        mVariableFertViewModel.motorSpeed.postValue(motorSpeed.copyOf())
                        mVariableFertViewModel.fertMotorSpeed.postValue(fertMotorSpeed.copyOf())
                        mVariableFertViewModel.seedMotorSpeed.postValue(seedMotorSpeed.copyOf())
                        mVariableFertViewModel.seedSensorCount.postValue(seedSensorCount.copyOf())
                        mVariableFertViewModel.motorCurrent.postValue(motorCurrent.copyOf())
                        mVariableFertViewModel.motorVoltage.postValue(motorVoltage.copyOf())
                        mVariableFertViewModel.motorElectricalValid.postValue(motorElectricalValid.copyOf())

                        val applied = mVariableFertViewModel.fertApplied.value ?: DoubleArray(FERT_NODE_COUNT) { 0.0 }
                        mVariableFertViewModel.updateFertData(confertflow.copyOf(), applied)
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

    private fun updateFertilizerFromSpeed(
        nodeIndex: Int,
        speedRpm: Double,
        confertflow: DoubleArray,
        fertMotorSpeed: DoubleArray
    ) {
        val fertIndex = nodeIndex - FERT_NODE_START_INDEX
        if (fertIndex !in 0 until FERT_NODE_COUNT) return
        confertflow[fertIndex] = if (speedRpm >= 1.0) {
            ConvAndCtrlFun().motorSpeedToFert(
                speedRpm,
                mRmcData.forwardSpeedCalculate,
                mSPParamData.rowSize,
                fittingCoefficientA[fertIndex],
                fittingCoefficientB[fertIndex]
            )
        } else {
            0.0
        }
        fertMotorSpeed[fertIndex] = speedRpm
    }

    private fun onWrappedCanMessageReceived(
        data: ByteArray,
        confertflow: DoubleArray,
        monfertAdcv: DoubleArray,
        monAdcV: DoubleArray,
        motorSpeed: DoubleArray,
        fertMotorSpeed: DoubleArray,
        seedMotorSpeed: DoubleArray,
        seedSensorCount: IntArray,
        seedRawLast: IntArray,
        motorCurrent: DoubleArray,
        motorVoltage: DoubleArray,
        motorElectricalValid: BooleanArray
    ) {
        if (data.size != 8) return
        val d = IntArray(8) { data[it].toInt() and 0xFF }

        if (d[0] == 0xA8) {
            val nodeIndex = d[1] - 1
            if (nodeIndex !in FERT_NODE_START_INDEX until SEED_NODE_START_INDEX) return
            val fertIndex = nodeIndex - FERT_NODE_START_INDEX
            val rawAdc = d[2] or (d[3] shl 8)
            val voltage = (d[4] or (d[5] shl 8)) / 1000.0
            val valid = (d[7] and 0x01) != 0
            monAdcV[fertIndex] = if (valid) voltage else 0.0
            monfertAdcv[fertIndex] = if (valid) voltage else 0.0
            canMonitorData[nodeIndex] =
                "fertSensor raw=%d voltage=%.3fV level=%d%% valid=%s".format(
                    rawAdc, voltage, d[6], valid
                )
            return
        }

        if (d[0] == 0xA9) {
            val nodeIndex = d[1] - 1
            if (nodeIndex !in SEED_NODE_START_INDEX until TOTAL_NODE_COUNT) return
            val seedIndex = nodeIndex - SEED_NODE_START_INDEX
            val rawCount = d[2] or (d[3] shl 8)
            val last = seedRawLast[seedIndex]
            if (last >= 0) {
                val delta = if (rawCount >= last) rawCount - last else rawCount + 65536 - last
                if (delta in 1..999) seedSensorCount[seedIndex] += delta
            }
            seedRawLast[seedIndex] = rawCount
            canMonitorData[nodeIndex] =
                "seedCount=%d total=%d valid=%s".format(
                    rawCount, seedSensorCount[seedIndex], (d[4] and 0x01) != 0
                )
            return
        }

        if (d[0] == 0xA7) {
            val nodeIndex = d[1] - 1
            if (nodeIndex !in 0 until TOTAL_NODE_COUNT) return
            val currentMilliAmp = d[2] or (d[3] shl 8)
            val voltageCentiVolt = d[4] or (d[5] shl 8)
            motorCurrent[nodeIndex] = currentMilliAmp / 1000.0
            motorVoltage[nodeIndex] = voltageCentiVolt / 100.0
            motorElectricalValid[nodeIndex] = true
            canMonitorData[nodeIndex] =
                "current=%.3fA voltage=%.2fV flags=0x%02X".format(
                    motorCurrent[nodeIndex], motorVoltage[nodeIndex], d[6]
                )
            return
        }

        if (d[0] == 0xA6) {
            val nodeIndex = d[1] - 1
            if (nodeIndex !in 0 until TOTAL_NODE_COUNT) return
            val pwm = d[2] + (d[3] shl 8)
            val speedRpm = (d[4] + (d[5] shl 8)) / 100.0
            val mode = d[6] and 0x0F
            val direction = (d[6] shr 4) and 0x0F
            val running = (d[7] and 0x01) != 0
            val encoderValid = (d[7] and 0x02) != 0
            val fault = (d[7] and 0x04) != 0

            motorSpeed[nodeIndex] = speedRpm
            if (nodeIndex < SEED_NODE_START_INDEX) {
                updateFertilizerFromSpeed(nodeIndex, speedRpm, confertflow, fertMotorSpeed)
            } else {
                seedMotorSpeed[nodeIndex - SEED_NODE_START_INDEX] = speedRpm
            }
            canMonitorData[nodeIndex] =
                "rpm=%.2f pwm=%d mode=%d dir=%d run=%s enc=%s fault=%s".format(
                    speedRpm, pwm, mode, direction, running, encoderValid, fault
                )
            return
        }

        // New PID firmware also sends a legacy-format status payload inside a 14-byte wrapper.
        val nodeIndex = d[3] - 1
        if (nodeIndex !in 0 until TOTAL_NODE_COUNT || d[4] !in 0..1) return
        val speedRpm = d[0] + d[1] / 100.0
        motorSpeed[nodeIndex] = speedRpm
        if (nodeIndex < SEED_NODE_START_INDEX) {
            updateFertilizerFromSpeed(nodeIndex, speedRpm, confertflow, fertMotorSpeed)
        } else {
            seedMotorSpeed[nodeIndex - SEED_NODE_START_INDEX] = speedRpm
        }
        canMonitorData[nodeIndex] = "rpm=%.2f target=%d run=%d dir=%d".format(
            speedRpm, d[2], d[4], d[5]
        )
    }

    private fun onLegacyCanMessageReceived(
        message: ByteArray,
        confertflow: DoubleArray,
        monfertAdcv: DoubleArray,
        monAdcV: DoubleArray,
        motorSpeed: DoubleArray,
        fertMotorSpeed: DoubleArray,
        seedMotorSpeed: DoubleArray,
        seedSensorCount: IntArray,
        seedRawLast: IntArray
    ) {
        if (message.first() != 0x27.toByte() || message.last() != 0x39.toByte()) return

        val slaveId = message[4].toInt() and 0xFF
        if (slaveId !in 0 until TOTAL_NODE_COUNT) return

        val canDataField = message.sliceArray(5..8)
        val speedrpm = (canDataField[0].toInt() and 0xFF) + (canDataField[1].toInt() and 0xFF) * 0.01
        val b0u = canDataField[0].toInt() and 0xFF
        val b0s = canDataField[0].toInt().toByte().toInt()
        val b1u = canDataField[1].toInt() and 0xFF
        canMonitorData[slaveId] = "b0=%02X(u:%d s:%d) b1=%02X(u:%d)  rpm=%.1f".format(b0u, b0u, b0s, b1u, b1u, speedrpm)
        motorSpeed[slaveId] = speedrpm

        if (slaveId >= SEED_NODE_START_INDEX) {
            val seedIndex = slaveId - SEED_NODE_START_INDEX
            seedMotorSpeed[seedIndex] = speedrpm
            val rawCount = ((canDataField[2].toInt() and 0xFF) shl 8) or (canDataField[3].toInt() and 0xFF)
            val last = seedRawLast[seedIndex]
            if (last >= 0) {
                val delta = if (rawCount >= last) rawCount - last else rawCount + 65536 - last
                if (delta in 1..999) seedSensorCount[seedIndex] += delta
            }
            seedRawLast[seedIndex] = rawCount
            return
        }

        val fertIndex = slaveId - FERT_NODE_START_INDEX
        if (fertIndex !in 0 until FERT_NODE_COUNT) return

        if (speedrpm >= 1.0) {
            val rawFert = ConvAndCtrlFun().motorSpeedToFert(
                speedrpm,
                mRmcData.forwardSpeedCalculate,
                mSPParamData.rowSize,
                fittingCoefficientA[fertIndex],
                fittingCoefficientB[fertIndex]
            )

            confertflow[fertIndex] = rawFert
        } else {
            confertflow[fertIndex] = 0.0
        }
        fertMotorSpeed[fertIndex] = speedrpm

        val fertadcV = ((canDataField[2].toInt() and 0xFF) * 100 + (canDataField[3].toInt() and 0xFF)) / 4095.0 * 3.3
        monAdcV[fertIndex] = fertadcV
        monfertAdcv[fertIndex] = fertadcV
    }
}
