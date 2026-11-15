/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2024年6月27日10:57:15
 * @file    :
 * @brief   :RMC语句解析，更新UI
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * RMC解析存储
 ***********************************************************************************************************
 */
package com.nx.vfremake.funClass

import android.content.Context
import com.nx.vfremake.R

class MyGNSSFun {

    /**
     * 解析一条有定位的 RMC。无定位或坐标损坏时保留原数据，由接收循环作废本次定位。
     * 速度始终存为 km/h，停车时保留最近一次可靠航向。
     */
    fun extractAndParseRMC(
        gnssSentence: String,
        forwardSpeed: Double,
        mRmcData: RmcData,
        context: Context,
    ): Boolean {
        val parts = gnssSentence.split(",")
        if (parts.size < 12 || !parts[0].endsWith("RMC") || (parts[2] != "A" && parts[2] != "D")) {
            return false
        }
        val latitude = parseCoordinate(parts[3], parts[4], 2, 90, "N", "S") ?: return false
        val longitude = parseCoordinate(parts[5], parts[6], 3, 180, "E", "W") ?: return false
        mRmcData.timeStamp = parts[1]
        mRmcData.status = parts[2]
        mRmcData.latitude = latitude
        mRmcData.longitude = longitude
        val rawSpeed = parts[7].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        mRmcData.gnssRawSpeed = rawSpeed * 1.852
        mRmcData.forwardSpeed = if (forwardSpeed > 0.0) forwardSpeed else mRmcData.gnssRawSpeed

        val heading = parts[8].toDoubleOrNull()?.takeIf { it in 0.0..360.0 }
        if (mRmcData.gnssRawSpeed >= RmcData.STANDSTILL_SPEED_KMH && heading != null) {
            mRmcData.directionDeg = heading
            mRmcData.directionRad = Math.toRadians(heading)
            mRmcData.directionAvailable = true
        }
        mRmcData.date = parts[9]
        mRmcData.magneticVariation = parts[10].toDoubleOrNull() ?: 0.0
        mRmcData.checksum = parts[11]

        val countLimit =
            ((MySharedPreFun(context).getSpecificValue(R.string.forwardSpeedAverageNum_name)
                ?: context.getString(R.string.forwardSpeedAverageNum_defeatValue))
                .toIntOrNull() ?: 5).coerceAtLeast(1)
        if (mRmcData.speedBuffer.size != countLimit) {
            mRmcData.speedBuffer = DoubleArray(countLimit)
            mRmcData.speedBufHead = 0
            mRmcData.speedBufCount = 0
        }
        mRmcData.speedBuffer[mRmcData.speedBufHead] = mRmcData.forwardSpeed
        mRmcData.speedBufHead = (mRmcData.speedBufHead + 1) % countLimit
        if (mRmcData.speedBufCount < countLimit) mRmcData.speedBufCount++
        val count = mRmcData.speedBufCount
        mRmcData.forwardSpeedCalculate = (0 until count).sumOf { mRmcData.speedBuffer[it] } / count
        return true
    }

    private fun parseCoordinate(
        value: String,
        hemisphere: String,
        degreeDigits: Int,
        maxDegrees: Int,
        positive: String,
        negative: String,
    ): Double? {
        if (value.length < degreeDigits + 2 || (hemisphere != positive && hemisphere != negative)) {
            return null
        }
        val degrees = value.take(degreeDigits).toIntOrNull() ?: return null
        val minutes = value.drop(degreeDigits).toDoubleOrNull() ?: return null
        if (degrees < 0 || !minutes.isFinite() || minutes < 0.0 || minutes >= 60.0) return null
        val coordinate = degrees + minutes / 60.0
        if (coordinate > maxDegrees) return null
        return if (hemisphere == negative) -coordinate else coordinate
    }
}
