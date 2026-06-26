package com.nx.vfremake.funClass

import android.util.Log
import com.nx.vfremake.SEED_NODE_START_INDEX
import com.nx.vfremake.lastSentMotorSpeed
import com.nx.vfremake.mSPParamData
import kotlin.math.roundToInt

class ConvAndCtrlFun {
    fun seedToMotorSpeed(forwardSpeedKmh: Double): Double {
        val spacingM = mSPParamData.seedPlantSpacing
        val holeCount = mSPParamData.seedHoleCount
        val ratio = mSPParamData.seedTransmissionRatio
        if (forwardSpeedKmh <= 0.0 || spacingM <= 0.0 || holeCount <= 0 || ratio <= 0.0) return 0.0
        val forwardSpeedMPerMin = forwardSpeedKmh * 1000.0 / 60.0
        val seedPlateRpm = forwardSpeedMPerMin / spacingM / holeCount
        return seedPlateRpm * ratio
    }

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
        val speedLimit = if (i < SEED_NODE_START_INDEX) 56.8 else 300.0
        val actualSpeed = if (isMotorActive) safeSpeed.coerceIn(0.0, speedLimit) else 0.0
        if (i < lastSentMotorSpeed.size) lastSentMotorSpeed[i] = actualSpeed

        val running = actualSpeed >= 1.0
        val hardwareId = i + 1
        val mode = if (running) 0x01 else 0x00
        val targetX10 = if (running) (actualSpeed * 10.0).roundToInt().coerceIn(0, 65535) else 0

        // UART-CAN wrapper + A5 extended command:
        // Fertilizer nodes 1..8, seed nodes 9..16, selector 1 = forward.
        val canMessageSend = ubyteArrayOf(
            0x27.toUByte(), 0x0B.toUByte(), 0x00.toUByte(), 0x00.toUByte(),
            0x27.toUByte(),
            0xA5.toUByte(),
            hardwareId.toUByte(),
            mode.toUByte(),
            0x01.toUByte(),
            (targetX10 and 0xFF).toUByte(),
            ((targetX10 shr 8) and 0xFF).toUByte(),
            0x00.toUByte(),
            0x00.toUByte(),
            0x39.toUByte()
        )
        MySerialPortFun().slaveCanMsgSend(canMessageSend.toByteArray())
    }
}
