package com.nx.vfremake

import com.nx.vfremake.data.DepthEventType
import com.nx.vfremake.data.DepthNodeQuality
import com.nx.vfremake.data.DepthQualityStatus
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.funClass.MeasuredDepthSample
import com.nx.vfremake.ui.DepthTone
import com.nx.vfremake.ui.depthDifferenceMm
import com.nx.vfremake.ui.fixedDepthChartRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepthDashboardTest {
    private val servo = ServoCalibration(motorIndex = 0, currentDepth = 52f, isOnline = true)

    @Test
    fun deviationUsesUnroundedNodeMeasurementAndTheSuppliedRowTarget() {
        val measured = sample(47.25f)
        assertEquals(-2.75f, depthDifferenceMm(measured, 50f))
        assertEquals(7.25f, depthDifferenceMm(measured, 40f))
        assertEquals(0f, depthDifferenceMm(measured, 47.25f))
        assertEquals("0.0 mm", fixedDepthChartRow(0, measured, 47.25f, servo).differenceText)
    }

    @Test
    fun largeDeviationKeepsItsValueBeyondTheChartScale() {
        val shallow = fixedDepthChartRow(0, sample(20f), 50f, servo)
        val deep = fixedDepthChartRow(0, sample(75f), 50f, servo)
        assertEquals("−30.0 mm", shallow.differenceText)
        assertEquals("偏浅", shallow.statusLabel)
        assertEquals("+25.0 mm", deep.differenceText)
        assertEquals("偏深", deep.statusLabel)
        assertEquals(DepthTone.WARNING, deep.tone)
    }

    @Test
    fun invalidMeasurementOrMissingTargetHasNoDeviation() {
        for (quality in DepthNodeQuality.entries.filterNot { it == DepthNodeQuality.OK }) {
            val row = fixedDepthChartRow(0, sample(50f, quality), 50f, servo)
            assertNull(row.differenceMm)
            assertEquals("—", row.differenceText)
        }
        for (target in listOf(null, 0f, Float.NaN)) {
            val row = fixedDepthChartRow(0, sample(50f), target, servo)
            assertNull(row.differenceMm)
            assertEquals("待目标", row.statusLabel)
        }
    }

    @Test
    fun fixedTargetUsesItsOwnToleranceAndStillShowsServoAlarm() {
        val measured = sample(35f).copy(monitoring = DepthQualityStatus(events = setOf(DepthEventType.SHALLOW)))
        assertEquals("达标", fixedDepthChartRow(0, measured, 30f, servo).statusLabel)
        assertEquals("偏深", fixedDepthChartRow(0, measured, 29f, servo).statusLabel)
        assertEquals("伺服报警", fixedDepthChartRow(0, measured, 30f, servo.copy(alarmCode = 1)).statusLabel)
    }

    private fun sample(depth: Float, quality: DepthNodeQuality = DepthNodeQuality.OK) =
        MeasuredDepthSample(depthMm = depth, rawDepthMm = depth, quality = quality, ageMs = 0L)
}
