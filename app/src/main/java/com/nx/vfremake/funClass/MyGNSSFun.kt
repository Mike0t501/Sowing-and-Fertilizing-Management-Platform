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
import android.util.Log
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel

class MyGNSSFun {

    //...解析RMC语句有关
    /**
     * 分离并解析RMC语句
     * @param  gnssSentence:gnss语句
     * @param  forwardSpeed:sharedPre里的forwardSpeed
     * @param  mRmcData:储存解析rmc的data class
     * @param  mVariableFertViewModel:更新信息栏UI使用的viewmodel
     * @note   注意单位
     */
    fun extractAndParseRMC(
        gnssSentence: String,
        forwardSpeed: Double,
        mRmcData: RmcData,
        mVariableFertViewModel: VariableFertViewModel,
        context: Context
    ) {
        // 是\n还是\r\n根据GNSS来定
        val lines = gnssSentence.split("\n")
        // 通过检查是否以RMC开头过滤
        val rmcLines = lines.filter { it.startsWith("\$GPRMC") || it.startsWith("\$GNRMC") }
        // 对于过滤得到的RMC语句进行解析
        rmcLines.forEach { rmcSentence ->
            val parts = rmcSentence.split(",")
            if (parts.size > 9 && parts[0].endsWith("RMC") && parts[2] == "D" ||  parts.size > 9 && parts[0].endsWith("RMC") && parts[2] == "A") {
                mVariableFertViewModel.gnssIsGood.postValue(false)
                mRmcData.timeStamp = parts[1]
                mRmcData.status = parts[2]
                // 纬度
                mRmcData.latitude = parseLatitude(parts[3], parts[4]) ?: 0.0
                // 经度
                mRmcData.longitude = parseLongitude(parts[5], parts[6]) ?: 0.0
                // 如果用户设置了前进速度，则使用用户设置的值，否则根据GNSS信息
                if (forwardSpeed == -1.0 || forwardSpeed <= 0) {
                    // GNSS的速度单位是节，转换成km/h
                    mRmcData.forwardSpeed = (parts[7].toDoubleOrNull() ?: 0.0) * 1.852

                } else {
                    // 用户设置的前进速度
                    mRmcData.forwardSpeed = forwardSpeed
                }

                // 航向角GNSS里解析出来是角度，转换为弧度
                // 三角函数计算需要弧度，api里有些是需要角度的，涉及角度弧度的api自己查查
                mRmcData.directionDeg = parts[8].toDoubleOrNull() ?: 0.0
                mRmcData.directionRad = Math.toRadians(mRmcData.directionDeg)
                mRmcData.date = parts[9]
                mRmcData.magneticVariation = parts[10].toDoubleOrNull() ?: 0.0
                mRmcData.checksum = parts[11]
            } else {
                Log.e("RMCParse", "不是RMC语句或者定位信息无效")
            }
        }

        // 前进速度滑动窗口平均：每条 RMC 立即更新，解决冷启动延迟与停车延迟
        val cntCompare =
            (MySharedPreFun(context).getSpecificValue(R.string.forwardSpeedAverageNum_name)
                ?: context.getString(R.string.forwardSpeedAverageNum_defeatValue)).toInt()
        // 窗口大小与设置不符时重置缓冲区（用户修改了设置）
        if (mRmcData.speedBuffer.size != cntCompare) {
            mRmcData.speedBuffer = DoubleArray(cntCompare) { 0.0 }
            mRmcData.speedBufHead = 0
            mRmcData.speedBufCount = 0
        }
        // 写入当前速度到环形缓冲区
        mRmcData.speedBuffer[mRmcData.speedBufHead] = mRmcData.forwardSpeed
        mRmcData.speedBufHead = (mRmcData.speedBufHead + 1) % cntCompare
        if (mRmcData.speedBufCount < cntCompare) mRmcData.speedBufCount++
        // 每条消息立即计算均值：缓冲区未满时只均值已有样本（第一帧即有效，消除冷启动）
        val count = mRmcData.speedBufCount
        mRmcData.forwardSpeedCalculate = if (count < cntCompare) {
            (0 until count).sumOf { mRmcData.speedBuffer[it] } / count
        } else {
            mRmcData.speedBuffer.sum() / cntCompare
        }

        mVariableFertViewModel.loLaDidegData.postValue(
            Triple(mRmcData.longitude, mRmcData.latitude, mRmcData.directionDeg)
        )

        mVariableFertViewModel.forwardspeed.postValue(mRmcData.forwardSpeedCalculate)
    }

    /**
     * 解析纬度数据
     * @param  latVal:纬度数据
     * @param  latDir:所在半球
     * @return 解析后的纬度数据
     * @note   纬度固定前两位是整数部分，其他是小数部分
     */
    private fun parseLatitude(latVal: String, latDir: String): Double? {
        if (latVal.length < 2 || !latVal.matches("^[0-9]+(\\.[0-9]+)?$".toRegex())) {
            return null // 返回 null 或抛出异常，表示数据无效
        }
        val degrees = latVal.substring(0, 2).toInt()
        val minutes = latVal.substring(2).toDoubleOrNull() ?: return null // 如果小数部分转换失败，返回 null
        var latitude = degrees + minutes / 60
        if (latDir == "S") {
            latitude *= -1
        }
        return latitude
    }

    /**
     * 解析经度数据
     * @param  lonVal:经度数据
     * @param  lonDir:所在半球
     * @return 解析后的纬度数据
     * @note   经度固定前三位是整数部分，其他是小数部分
     */
    private fun parseLongitude(lonVal: String, lonDir: String): Double? {
        // 检查经度值是否至少有3个字符用于度数，并且剩余部分可以转换为小数
        if (lonVal.length < 3 || !lonVal.matches("^[0-9]+(\\.[0-9]+)?$".toRegex())) {
            return null // 返回 null 或抛出异常，表示数据无效
        }
        val degrees = lonVal.substring(0, 3).toInt()
        val minutes = lonVal.substring(3).toDoubleOrNull() ?: return null // 如果小数部分转换失败，返回 null
        var longitude = degrees + minutes / 60
        if (lonDir == "W") {
            longitude *= -1
        }
        return longitude
    }
    //...解析RMC语句有关结束
}
