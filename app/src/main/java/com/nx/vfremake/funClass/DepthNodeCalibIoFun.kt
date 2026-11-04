/** @author NIANXI @date 2026-11-04 @brief 节点标定JSON schema v1，独立于伺服标定格式。 */
package com.nx.vfremake.funClass

import com.google.gson.GsonBuilder
import com.nx.vfremake.data.DepthNodeChannel
import kotlin.math.abs

object DepthNodeCalibIoFun {
    const val FORMAT = "vfremake.depthNodeCalibration"
    const val VERSION = 1
    private val gson = GsonBuilder().setPrettyPrinting().create()
    data class FileDto(val format: String? = null, val version: Int? = null,
        val exportedAt: String? = null, val appVersion: String? = null,
        val sourceRow: Int? = null, val calibration: DepthNodeStorage.Saved? = null)

    /** 导出标定原值及采集标准差，不导出在线、测量值或固件配置假设。 */
    fun encode(channel: DepthNodeChannel, appVersion: String, exportedAt: String): String = gson.toJson(
        FileDto(FORMAT, VERSION, exportedAt, appVersion, channel.row, DepthNodeStorage.saved(channel)))

    /** JSON中系数须与标定点重算结果一致；不接受其他格式或未来版本。 */
    fun decode(json: String): Result<FileDto> = runCatching {
        val file = gson.fromJson(json, FileDto::class.java) ?: error("文件为空")
        require(file.format == FORMAT && file.version == VERSION) { "不是支持的检测节点标定文件（schema v1）" }
        require(file.sourceRow in 0..7) { "来源行号非法" }
        val saved = file.calibration ?: error("缺少标定数据")
        val channel = DepthNodeStorage.decode(gson.toJson(saved), file.sourceRow!!)
        require(channel.fitValid) { "标定点、系数或地址不合法" }
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
        val fit = DepthCalibrationFit.fit(saved.points.map { it.mv to it.mm }, saved.fitOrder).getOrThrow()
        return DepthNodeStorage.apply(saved.copy(maxResidualMm = fit.maxResidualMm), target)
    }
}
