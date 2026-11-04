/** @author NIANXI @date 2026-11-04 @brief depth_node_v1记录格式，纯函数供两种记录路径共用。 */
package com.nx.vfremake.funClass

import com.google.gson.GsonBuilder
import com.nx.vfremake.data.*
import java.util.Locale

object DepthRecordSchema {
    const val RECORD_SCHEMA = "depth_node_v1"
    const val DETECTION_SCHEME = "0–5 V 角度传感器 + dingoFW 变送节点"
    val ORIGINAL_HEADER = listOf("timestamp", "elapsed_ms", "motor_no", "test_stage", "target_depth_mm",
        "current_depth_mm", "encoder_position", "is_online", "alarm_code")
    val NODE_HEADER = listOf("ds_online", "ds_depth_mm", "ds_depth_raw_mm", "ds_span_mm", "ds_u_filt_mv",
        "ds_u_mean_mv", "ds_span_mv", "ds_flags", "ds_seq", "ds_cfg_crc", "ds_quality", "ds_event")
    val HEADER = ORIGINAL_HEADER + NODE_HEADER

    /** 非OK深度留空；0mm保留；flags/seq/CRC采用0–255十进制以便表格分析。 */
    fun nodeColumns(channel: DepthNodeChannel, sample: MeasuredDepthSample,
        event: String = sample.monitoring.events.sortedBy { it.ordinal }.joinToString("|") { it.name }): List<String> {
        val online = sample.quality != DepthNodeQuality.OFFLINE
        val ok = sample.quality == DepthNodeQuality.OK
        return listOf(if (online) "1" else "0", number(if (ok) sample.depthMm else null),
            number(if (ok) sample.rawDepthMm else null), number(if (ok) sample.spanMm else null),
            number(if (online) channel.uFiltMv else null), number(if (online) channel.uMeanMv else null),
            number(if (online) channel.spanMv else null), channel.flags.toString(), channel.seq?.toString() ?: "",
            channel.cfgCrc?.toString() ?: "", sample.quality.name, event)
    }

    /** 原始7个业务字段+节点12列，前两列时间戳由记录器补齐。 */
    fun completeRow(original: List<String>, channel: DepthNodeChannel, sample: MeasuredDepthSample,
        event: String = sample.monitoring.events.sortedBy { it.ordinal }.joinToString("|") { it.name }): List<String> {
        require(original.size == 7) { "播深原始记录字段数必须为7" }
        return original + nodeColumns(channel, sample, event)
    }

    /** 中文阶段、逗号、引号和换行都按CSV转义，不依赖默认系统区域。 */
    fun csvLine(fields: List<String>): String = fields.joinToString(",") { field ->
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${field.replace("\"", "\"\"")}\"" else field
    } + "\n"

    data class ConfigSnapshot(val elapsedMs: Long, val row: Int, val cfgCrc: Int?,
        val words: Map<Int, Long>, val filterCrcVerified: Boolean, val readErrors: List<String>)

    /** 一次真实回读快照。空项和错误必须显式保留，不以默认滤波配置替代。 */
    fun configSnapshot(readback: DepthNodeCfgClient.Readback, channel: DepthNodeChannel, elapsedMs: Long): ConfigSnapshot =
        ConfigSnapshot(elapsedMs, channel.row, channel.cfgCrc, readback.words,
            channel.filterParams?.crc == channel.cfgCrc && channel.filterParams != null, readback.errors)

    /** 可重复写入同一个meta文档，配置变化追加历史；历史CSV不改写。 */
    fun metadata(state: DepthNodeState, appVersion: String, startedAt: String, intervalMs: Long,
        history: List<ConfigSnapshot>, activeRows: List<Int>, eventFileName: String? = null): String {
        val metadata = linkedMapOf<String, Any?>(
            "record_schema" to RECORD_SCHEMA, "app_version" to appVersion,
            "detection_scheme" to DETECTION_SCHEME, "simulation" to state.simulated,
            "started_at" to startedAt, "interval_ms" to intervalMs, "active_rows" to activeRows.map { it + 1 },
            "event_file" to eventFileName,
            "display_mode" to state.displayMode.name, "quality_monitor_parameters" to state.qualityConfig.metadata(),
            "channels" to state.channels.map { channel ->
                val initial = history.firstOrNull { it.row == channel.row }
                val filter = initial?.words?.let { words -> (0..8).mapNotNull { words[it] }
                    .takeIf { it.size == 9 }?.let { DepthNodeFilterParams.fromWords(it) } }
                linkedMapOf<String, Any?>(
                "row" to channel.row + 1, "base_id" to channel.baseId, "icd_ver" to channel.icdVer,
                "fw_rev" to channel.fwRev, "online_at_start" to channel.isOnline,
                "calibration" to DepthNodeStorage.saved(channel),
                "filter_params_readback" to filter, "cfg_crc" to channel.cfgCrc,
                "report_ms" to initial?.words?.get(9)?.toInt()?.let { DepthNodeProtocol.actualReportMs(it) },
                "readback_error" to (initial?.readErrors ?: listOf("未参与本次记录")))
            }, "configuration_history" to history
        )
        return GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(metadata)
    }

    private fun number(value: Float?): String = value?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.ROOT, "%.1f", it) } ?: ""
}
