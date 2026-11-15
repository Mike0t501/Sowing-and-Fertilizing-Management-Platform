/** @author NIANXI @date 2026-11-04 @brief 检测节点电压→深度唯一换算入口。 */
package com.nx.vfremake.funClass

import com.nx.vfremake.data.DepthNodeChannel
import com.nx.vfremake.data.DepthNodeVoltageUnit
import java.text.Normalizer

object DepthNodeCalib {
    private const val MAX_STATUS_VOLTAGE_MV = (DepthNodeProtocol.INVALID_VOLTAGE - 1) / 10.0

    fun parseCoefficient(text: String): Double? = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .trim().replace('−', '-').toDoubleOrNull()

    fun withLinearCoefficients(channel: DepthNodeChannel, a: Double, b: Double,
        unit: DepthNodeVoltageUnit, calibratedAt: String): DepthNodeChannel {
        val slope = a / unit.mvPerUnit
        require(isValidLinear(slope, b) && a.isFinite()) { "请输入有限的 a、b，且斜率 a 不能为 0，换算结果须在数值范围内" }
        return channel.copy(calibrationPoints = emptyList(), calibrationStdMv = emptyList(), fitOrder = 1,
            c0 = b, c1 = slope, c2 = 0.0, fitValid = true, fitMaxResidualMm = 0f,
            calibratedAt = calibratedAt, offsetTrimMm = 0f, zeroCheckedAt = "", linearVoltageUnit = unit)
    }

    /** 校验整个STATUS可编码电压区间，防止系数保存后使有效帧溢出。 */
    internal fun isValidLinear(slope: Double, intercept: Double, trim: Float = 0f): Boolean =
        slope.isFinite() && slope != 0.0 && intercept.isFinite() &&
            (intercept + trim).toFloat().isFinite() &&
            (intercept + slope * MAX_STATUS_VOLTAGE_MV + trim).toFloat().isFinite()

    /** 不夹紧负深度或把无效值替换成 0；浅端越界由质量层明确表示。 */
    fun depthFromMv(mv: Float, channel: DepthNodeChannel): Float? {
        if (!channel.fitValid || !mv.isFinite()) return null
        return (channel.c0 + channel.c1 * mv + channel.c2 * mv * mv + channel.offsetTrimMm)
            .toFloat().takeIf { it.isFinite() }
    }

    /** span 按 u_mean 处局部斜率折算，止点方向解释也使用同一导数。 */
    fun derivative(mv: Float, channel: DepthNodeChannel): Double = channel.c1 + 2 * channel.c2 * mv
}
