/** @author NIANXI @date 2026-11-04 @brief 节点持久化白名单，禁止把运行时在线状态恢复到开机。 */
package com.nx.vfremake.funClass

import com.google.gson.Gson
import com.nx.vfremake.data.DepthNodeChannel
import com.nx.vfremake.data.DepthNodeVoltageUnit

/** 仅包含标定和地址；实际滤波参数必须重新回读，不能从缓存假定生效。 */
object DepthNodeStorage {
    private val gson = Gson()
    data class Saved(
        val baseId: Int = 0,
        val points: List<Point> = emptyList(), val stdMv: List<Float> = emptyList(),
        val fitOrder: Int = 2, val c0: Double = 0.0, val c1: Double = 0.0, val c2: Double = 0.0,
        val fitValid: Boolean = false, val maxResidualMm: Float = 0f,
        val calibratedAt: String = "", val offsetTrimMm: Float = 0f, val zeroCheckedAt: String = "",
        val stopLoMv: Int = 0, val stopHiMv: Int = 0,
        val linearVoltageUnit: DepthNodeVoltageUnit? = null
    )
    data class Point(val mv: Float, val mm: Float)

    /** 从运行时通道建立持久化白名单。 */
    fun saved(channel: DepthNodeChannel): Saved = Saved(channel.baseId,
        channel.calibrationPoints.map { Point(it.first, it.second) }, channel.calibrationStdMv,
        channel.fitOrder, channel.c0, channel.c1, channel.c2, channel.fitValid, channel.fitMaxResidualMm,
        channel.calibratedAt, channel.offsetTrimMm, channel.zeroCheckedAt, channel.stopLoMv, channel.stopHiMv,
        channel.linearVoltageUnit)

    /** SharedPreferences 中每行一个 dn_N_calibration JSON，不写入运行时字段。 */
    fun encode(channel: DepthNodeChannel): String = gson.toJson(saved(channel))

    /** 存储损坏时该行恢复为未标定默认态，禁止恢复 NaN 或跨行地址。 */
    fun decode(json: String?, row: Int): DepthNodeChannel = runCatching {
        require(row in 0..7)
        if (json == null) return@runCatching DepthNodeChannel(row)
        val value = gson.fromJson(json, Saved::class.java) ?: return@runCatching DepthNodeChannel(row)
        require(value.baseId == DepthNodeProtocol.baseForRow(row))
        require(value.fitOrder in 1..2 && listOf(value.c0, value.c1, value.c2).all { it.isFinite() })
        require(value.points.size <= 64 && value.points.all { it.mv.isFinite() && it.mv in 0f..5000f && it.mm.isFinite() && it.mm >= 0f })
        require(value.stdMv.all { it.isFinite() && it >= 0 })
        require(value.stopLoMv in 0..5000 && value.stopHiMv in 0..5000 && value.offsetTrimMm.isFinite())
        require(value.maxResidualMm.isFinite() && value.maxResidualMm >= 0)
        val unit = value.linearVoltageUnit
        if (unit == null) {
            require(!value.fitValid || value.points.size >= if (value.fitOrder == 1) 3 else 4)
        } else {
            require(value.fitValid && value.fitOrder == 1 && value.c2 == 0.0 && value.points.isEmpty() && value.stdMv.isEmpty())
            require(value.calibratedAt.isNotBlank() && (value.c1 * unit.mvPerUnit).isFinite() &&
                DepthNodeCalib.isValidLinear(value.c1, value.c0, value.offsetTrimMm))
        }
        apply(value, DepthNodeChannel(row))
    }.getOrElse { DepthNodeChannel(row) }

    /** 恢复只覆盖持久化字段，保留接收协程已经更新的电压与诊断。 */
    fun apply(saved: Saved, runtime: DepthNodeChannel): DepthNodeChannel = runtime.copy(
        baseId = saved.baseId, calibrationPoints = saved.points.map { it.mv to it.mm },
        calibrationStdMv = saved.stdMv, fitOrder = saved.fitOrder,
        c0 = saved.c0, c1 = saved.c1, c2 = saved.c2, fitValid = saved.fitValid,
        fitMaxResidualMm = saved.maxResidualMm, calibratedAt = saved.calibratedAt,
        offsetTrimMm = saved.offsetTrimMm, zeroCheckedAt = saved.zeroCheckedAt,
        stopLoMv = saved.stopLoMv, stopHiMv = saved.stopHiMv, linearVoltageUnit = saved.linearVoltageUnit
    )
}
