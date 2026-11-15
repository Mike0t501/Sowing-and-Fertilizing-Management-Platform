/** @author NIANXI @date 2026-11-04 @brief 节点标定JSON，独立于伺服标定格式。 */
package com.nx.vfremake.funClass

import com.google.gson.GsonBuilder
import com.nx.vfremake.data.DepthNodeChannel
import kotlin.math.abs

object DepthNodeCalibIoFun {
    const val FORMAT = "vfremake.depthNodeCalibration"
    private const val POINTS_VERSION = 1
    private const val EXTERNAL_LINEAR_VERSION = 2
    private val gson = GsonBuilder().setPrettyPrinting().create()
    data class FileDto(val format: String? = null, val version: Int? = null,
        val exportedAt: String? = null, val appVersion: String? = null,
        val sourceRow: Int? = null, val calibration: DepthNodeStorage.Saved? = null)

    /** 导出标定原值及采集标准差，不导出在线、测量值或固件配置假设。 */
    fun encode(channel: DepthNodeChannel, appVersion: String, exportedAt: String): String = gson.toJson(
        FileDto(FORMAT, if (channel.linearVoltageUnit == null) POINTS_VERSION else EXTERNAL_LINEAR_VERSION,
            exportedAt, appVersion, channel.row, DepthNodeStorage.saved(channel)))

    /** 多点标定校验采点和重算结果，外部系数按无采点的线性标定校验。 */
    fun decode(json: String): Result<FileDto> = runCatching {
        val file = gson.fromJson(json, FileDto::class.java) ?: error("文件为空")
        require(file.format == FORMAT && (file.version == POINTS_VERSION || file.version == EXTERNAL_LINEAR_VERSION)) {
            "不是支持的检测节点标定文件"
        }
        require(file.sourceRow in 0..7) { "来源行号非法" }
        val saved = file.calibration ?: error("缺少标定数据")
        val channel = DepthNodeStorage.decode(gson.toJson(saved), file.sourceRow!!)
        require(channel.fitValid) { "标定点、系数或地址不合法" }
        require((channel.linearVoltageUnit != null) == (file.version == EXTERNAL_LINEAR_VERSION)) { "文件版本与标定来源不一致" }
        if (file.version == EXTERNAL_LINEAR_VERSION) return@runCatching file
        require(saved.stdMv.size == saved.points.size) { "缺少标定点采集标准差" }
        val fit = DepthCalibrationFit.fit(channel.calibrationPoints, channel.fitOrder).getOrThrow()
        require(channel.calibrationPoints.all { (mv, _) ->
            abs(fit.at(mv.toDouble()) - (channel.c0 + channel.c1 * mv + channel.c2 * mv * mv)) <= 0.1
        }) { "系数与标定点不一致，请检查文件" }
        require(DepthCalibrationFit.unstableIndices(fit, saved.points.map { it.mv }, saved.stdMv).isEmpty()) { "文件包含不稳定标定点" }
        require(saved.calibratedAt.isNotBlank()) { "缺少标定时间" }
        file
    }

    /** 导入只应用平板标定；保留目标地址和运行时反馈，不隐式写节点止点或Burn。 */
    fun apply(file: FileDto, target: DepthNodeChannel): DepthNodeChannel {
        val saved = requireNotNull(file.calibration).copy(baseId = target.baseId)
        if (saved.linearVoltageUnit != null) return DepthNodeStorage.apply(saved, target)
        val fit = DepthCalibrationFit.fit(saved.points.map { it.mv to it.mm }, saved.fitOrder).getOrThrow()
        return DepthNodeStorage.apply(saved.copy(maxResidualMm = fit.maxResidualMm), target)
    }
}
