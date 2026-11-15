/** @author NIANXI @brief 新CSV与元数据契约测试，历史文件不参与迁移。 */
package com.nx.vfremake

import com.google.gson.JsonParser
import com.nx.vfremake.coroutine.MyWriteSaveFun
import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.*
import com.nx.vfremake.funClass.DepthNodeProtocol as P
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class DepthRecordSchemaTest {
    @Test fun exactHeaderRetainsOriginalNineAndHasOnlyTwelveNewFields() {
        assertEquals(listOf("timestamp", "elapsed_ms", "motor_no", "test_stage", "target_depth_mm", "current_depth_mm",
            "encoder_position", "is_online", "alarm_code", "ds_online", "ds_depth_mm", "ds_depth_raw_mm", "ds_span_mm",
            "ds_u_filt_mv", "ds_u_mean_mv", "ds_span_mv", "ds_flags", "ds_seq", "ds_cfg_crc", "ds_quality", "ds_event"), DepthRecordSchema.HEADER)
        assertFalse(DepthRecordSchema.HEADER.any { it in listOf("measured_depth_mm", "enc_position", "enc_online", "ds_source") })
    }

    @Test fun manualAndTestRowsShareMappingAndZeroIsNotMissing() {
        val channel = DepthNodeChannel(0, isOnline = true, seq = 42, cfgCrc = 108, uFiltMv = 2345.6f,
            uMeanMv = 2350f, spanMv = 12f)
        val sample = MeasuredDepthSample(0f, 0.1f, DepthNodeQuality.OK, 0, 0.2f,
            uFiltMv = channel.uFiltMv, uMeanMv = channel.uMeanMv)
        val row = listOf("1", "手动", "40.00", "39.90", "123", "1", "0")
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val manual = DepthRecordSchema.completeRow(row, channel, sample)
            val auto = DepthRecordSchema.completeRow(row.toMutableList().also { it[1] = "运行20→40" }, channel, sample)
            assertEquals(manual.drop(7), auto.drop(7))
            assertEquals(listOf("1", "0.0", "0.1", "0.2", "2345.6", "2350.0", "12.0", "0", "42", "108", "OK", ""), manual.drop(7))
            assertEquals(21, (listOf("12:00:00.000", "0") + manual).size)
            val invalid = DepthRecordSchema.nodeColumns(channel, sample.copy(quality = DepthNodeQuality.FAULT))
            assertEquals(listOf("", "", ""), invalid.subList(1, 4))
        } finally { Locale.setDefault(originalLocale) }
    }

    @Test fun workAndDepthRecordsSharePerRowVoltageValuesEvenWithoutCalibration() {
        var now = 100L
        val store = DepthNodeStateStore()
        val runtime = DepthNodeRuntime(store, nowMs = { now })
        val measured = MeasuredDepthFacade(store::current) { now }
        runtime.onDepthNodeFrame(P.baseForRow(0) + P.STATUS, P.statusData(0f, 2.5f, 2f, 23, 0, 5))
        runtime.onDepthNodeFrame(P.baseForRow(1) + P.STATUS, P.statusData(2540f, null, 2f, 24, P.Flag.SAT.mask, 6))
        val first = measured.forRow(0)
        val second = measured.forRow(1)
        assertEquals(DepthNodeQuality.UNCALIBRATED, first.quality)
        assertEquals(DepthNodeQuality.FAULT, second.quality)
        assertEquals(P.Flag.SAT.mask, second.voltageFlags)
        assertEquals(listOf("0.0", "2.5", "0", "5", "23", "UNCALIBRATED"), DepthRecordSchema.workVoltageColumns(runtime.channel(0), first))
        assertEquals(listOf("2540.0", "", "2", "6", "24", "FAULT"), DepthRecordSchema.workVoltageColumns(runtime.channel(1), second))
        assertEquals(listOf("0.0", "2.5"), DepthRecordSchema.nodeColumns(runtime.channel(0), first).subList(4, 6))
        now += 1001L
        val expired = measured.forRow(0)
        assertNull(expired.uFiltMv)
        assertEquals(listOf("", ""), DepthRecordSchema.nodeColumns(runtime.channel(0), expired).subList(4, 6))
        assertEquals(listOf("", ""), DepthRecordSchema.workVoltageColumns(runtime.channel(0), expired).take(2))
    }

    @Test fun workVoltageColumnsAreAppendedOnlyToTheDepthGroup() {
        val recorder = MyWriteSaveFun()
        val expected = listOf("ds_u_filt_mv", "ds_u_mean_mv", "ds_flags", "ds_seq", "ds_cfg_crc", "ds_quality")
        assertEquals(expected, recorder.buildSaveHeader(includeFert = true, includeDepth = true).takeLast(6))
        assertEquals(expected, recorder.buildSaveHeader(includeFert = false, includeDepth = true).takeLast(6))
        assertFalse(recorder.buildSaveHeader(includeFert = true, includeDepth = false).any { it in expected })
    }

    @Test fun csvEscapingPreservesChineseStageAndQuotedFields() {
        assertEquals("1,\"运行,阶段\",\"a\"\"b\",\"x\ny\"\n", DepthRecordSchema.csvLine(listOf("1", "运行,阶段", "a\"b", "x\ny")))
    }

    @Test fun metadataContainsRealReadbackAndErrorsInsteadOfDefaultsAndKeepsCrcHistory() {
        val params = DepthNodeFilterParams(rankN = 1, smoother = 0)
        val channel = DepthNodeChannel(0, cfgCrc = 0xD1, filterParams = params, icdVer = 16, fwRev = 1)
        val words = params.wireWords().mapIndexed { i, v -> i to v }.toMap() + (9 to 50L)
        val good = DepthRecordSchema.configSnapshot(DepthNodeCfgClient.Readback(0x680, words, emptyList(), 1000), channel, 0)
        val bad = DepthRecordSchema.configSnapshot(DepthNodeCfgClient.Readback(0x688, emptyMap(), listOf("离线"), 1000), DepthNodeChannel(1), 0)
        val json = DepthRecordSchema.metadata(DepthNodeState(channels = listOf(channel) + (1..7).map { DepthNodeChannel(it) }, simulated = true),
            "3.1.0", "2026-11-04T00:00:00Z", 100, listOf(good, bad, good.copy(elapsedMs = 1000)), listOf(0, 1))
        val parsed = JsonParser.parseString(json).asJsonObject
        assertEquals("depth_node_v1", parsed["record_schema"].asString)
        assertTrue(parsed["simulation"].asBoolean)
        assertEquals(3, parsed["configuration_history"].asJsonArray.size())
        val channels = parsed["channels"].asJsonArray
        assertEquals(1, channels[0].asJsonObject["filter_params_readback"].asJsonObject["rankN"].asInt)
        assertEquals("UNCALIBRATED", channels[0].asJsonObject["calibration_source"].asString)
        assertTrue(channels[1].asJsonObject["filter_params_readback"].isJsonNull)
        assertTrue(channels[1].asJsonObject["report_ms"].isJsonNull)
        assertEquals(0.2f, parsed["quality_monitor_parameters"].asJsonObject["cv_threshold"].asFloat, 0f)
        assertTrue(good.filterCrcVerified); assertFalse(bad.filterCrcVerified)
    }
}
