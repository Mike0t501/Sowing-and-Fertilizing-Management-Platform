package com.nx.vfremake.funClass

import android.util.Log
import com.nx.vfremake.lastSentMotorSpeed
import com.nx.vfremake.mSPParamData

class ConvAndCtrlFun {

    fun motorSpeedToFert(
        motorSpeedrpm: Double, forwardSpeed: Double, rowSize: Double,
        fittingCoefficientA: Double, fittingCoefficientB: Double
    ): Double {
        val fertflow: Double = fittingCoefficientA * motorSpeedrpm + fittingCoefficientB
        val safeSpeed = if (forwardSpeed <= 0.1) 1.0 else forwardSpeed
        return fertflow * 3.6 / (1000.0 * rowSize * 0.0015 * safeSpeed)
    }

    fun fertToMotorSpeed(
        fert: Double, forwardSpeed: Double, rowSize: Double,
        fittingCoefficientA: Double, fittingCoefficientB: Double
    ): Double {
        if (fittingCoefficientA == 0.0) return 0.0
        val fertflow = fert * 1000.0 * rowSize * 0.0015 * forwardSpeed / 3.6
        return (fertflow - fittingCoefficientB) / fittingCoefficientA
    }

    fun fertflowToMotorSpeed(fertflow: Double, fittingCoefficientA: Double, fittingCoefficientB: Double): Double {
        if (fittingCoefficientA == 0.0) return 0.0
        return (fertflow - fittingCoefficientB) / fittingCoefficientA
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun motorSpeedrpmSend(motorSpeedrpm: Double, i: Int) {

        val isMotorActive: Boolean = mSPParamData.activeMotors.getOrNull(i) ?: true
        val safeSpeed = if (motorSpeedrpm.isNaN() || motorSpeedrpm.isInfinite()) 0.0 else motorSpeedrpm
        val actualSpeed = if (isMotorActive) safeSpeed else 0.0
        if (i < lastSentMotorSpeed.size) lastSentMotorSpeed[i] = actualSpeed

        val motorStartFlag = if (actualSpeed >= 1.0) 0x01.toUByte() else 0x00.toUByte()
        val ctrlIntPart = actualSpeed.toInt()
        val ctrlDecimalPart = ((actualSpeed - ctrlIntPart) * 100).toInt()

        val ctrlIntPartByte = if (motorStartFlag == 0x00.toUByte()) 0x00.toUByte() else ctrlIntPart.toUByte()
        val ctrlDecimalPartByte = if (motorStartFlag == 0x00.toUByte()) 0x00.toUByte() else ctrlDecimalPart.toUByte()

        // 【真相大白】：硬件底层的ID本来就是 0~7！千万不能加 1！
        val hardwareId = i

        val canMessageSend = ubyteArrayOf(
            0x27.toUByte(), 0x08.toUByte(), 0x00.toUByte(), 0x00.toUByte(),
            0x27.toUByte(), 0x00.toUByte(), hardwareId.toUByte(), motorStartFlag,
            ctrlIntPartByte, ctrlDecimalPartByte, 0x39.toUByte()
        )
        MySerialPortFun().slaveCanMsgSend(canMessageSend.toByteArray())
    }
}