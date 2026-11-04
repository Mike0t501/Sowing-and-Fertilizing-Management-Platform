/** @author NIANXI @brief 用确定时间与距离序列验证质量事件，不依赖实物电机。 */
package com.nx.vfremake

import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.*
import org.junit.Assert.*
import org.junit.Test

class DepthQualityMonitorTest {
    private fun sample(time: Long, depth: Float?, target: Float = 40f, speed: Double? = null,
        quality: DepthNodeQuality = DepthNodeQuality.OK, stop: Boolean = false, continuous: Boolean = true) =
        DepthQualityMonitor.Input(time, 1_800_000_000_000L + time,
            MeasuredDepthSample(depth, depth, quality, 0, 1f), target, speed, stop, continuous)
    private fun warm(m: DepthQualityMonitor, speed: Double? = null) {
        m.update(sample(0, 40f, speed = speed)); m.update(sample(2000, 40f, speed = speed))
    }

    @Test fun spikeDoesNotTriggerButSustainedShallowDoesAndHysteresisControlsExit() {
        val m = DepthQualityMonitor(0); warm(m)
        assertFalse(DepthEventType.SHALLOW in m.update(sample(2100, 20f)).status.events)
        assertFalse(DepthEventType.SHALLOW in m.update(sample(2200, 40f)).status.events)
        m.update(sample(2300, 20f))
        assertFalse(DepthEventType.SHALLOW in m.update(sample(2599, 20f)).status.events)
        assertTrue(DepthEventType.SHALLOW in m.update(sample(2600, 20f)).status.events)
        assertTrue(DepthEventType.SHALLOW in m.update(sample(2700, 30.5f)).status.events)
        m.update(sample(2800, 31f))
        assertTrue(DepthEventType.SHALLOW in m.update(sample(2999, 31f)).status.events)
        val exit = m.update(sample(3000, 31f))
        assertFalse(DepthEventType.SHALLOW in exit.status.events)
        assertEquals(2300L, exit.completed.single().startMs)
        assertEquals(3000L, exit.completed.single().endMs)
        assertEquals(20f, exit.completed.single().minDepthMm!!, 0f)
    }

    @Test fun distanceThresholdUsesKilometersPerHourAndZeroSpeedDoesNotAccumulate() {
        val moving = DepthQualityMonitor(0); warm(moving, 3.6)
        moving.update(sample(2100, 60f, speed = 3.6))
        assertFalse(DepthEventType.DEEP in moving.update(sample(2399, 60f, speed = 3.6)).status.events)
        assertTrue(DepthEventType.DEEP in moving.update(sample(2400, 60f, speed = 3.6)).status.events)
        val stopped = DepthQualityMonitor(0); warm(stopped, 0.0)
        stopped.update(sample(2100, 20f, speed = 0.0))
        assertFalse(DepthEventType.SHALLOW in stopped.update(sample(10000, 20f, speed = 0.0)).status.events)
        assertFalse(stopped.update(sample(20000, 20f, speed = 0.0)).status.windowReady)
    }

    @Test fun liftAtOneHundredMillisecondsByDepthOrTrustedShallowStop() {
        val m = DepthQualityMonitor(0)
        m.update(sample(0, 3f))
        assertFalse(DepthEventType.LIFT_OFF in m.update(sample(99, 3f)).status.events)
        assertTrue(DepthEventType.LIFT_OFF in m.update(sample(100, 3f)).status.events)
        assertFalse(DepthEventType.LIFT_OFF in m.update(sample(150, 40f)).status.events)
        val stop = DepthQualityMonitor(1)
        stop.update(sample(0, null, quality = DepthNodeQuality.OUT_OF_RANGE, stop = true))
        assertTrue(DepthEventType.LIFT_OFF in stop.update(sample(100, null, quality = DepthNodeQuality.OUT_OF_RANGE, stop = true)).status.events)
        assertFalse(stop.update(sample(200, null, quality = DepthNodeQuality.FAULT, stop = true)).status.windowReady)
    }

    @Test fun targetChangeMasksTwoSecondsButNeverMasksLift() {
        val m = DepthQualityMonitor(0); warm(m)
        m.update(sample(2100, 20f)); m.update(sample(2400, 20f))
        assertFalse(DepthEventType.SHALLOW in m.update(sample(2500, 20f, target = 60f)).status.events)
        assertFalse(DepthEventType.SHALLOW in m.update(sample(4499, 20f, target = 60f)).status.events)
        m.update(sample(4500, 20f, target = 60f))
        assertTrue(DepthEventType.SHALLOW in m.update(sample(4800, 20f, target = 60f)).status.events)
        m.update(sample(5000, 3f, target = 80f))
        assertTrue(DepthEventType.LIFT_OFF in m.update(sample(5100, 3f, target = 80f)).status.events)
    }

    @Test fun fullWindowRequiredAndCvCanTriggerEvenWithAllSamplesInsideTolerance() {
        val m = DepthQualityMonitor(0); m.update(sample(0, 40f))
        var result = m.update(sample(2000, 31f))
        for (i in 1..199) result = m.update(sample(2000L + i * 50, if (i % 2 == 0) 31f else 49f))
        assertFalse(result.status.windowReady)
        result = m.update(sample(12000, 31f))
        assertTrue(result.status.windowReady)
        assertEquals(1f, result.status.passRate!!, 0f)
        assertTrue(result.status.coefficientOfVariation!! > 0.2f)
        assertTrue(DepthEventType.UNSTABLE in result.status.events)
        val invalidated = m.invalidate()
        assertFalse(invalidated.status.windowReady)
        assertEquals(12000L, invalidated.completed.first { it.type == DepthEventType.UNSTABLE }.endMs)
    }

    @Test fun invalidSamplesAndDataGapsResetCandidatesAndStopEventsAtLastKnownSample() {
        val m = DepthQualityMonitor(0); warm(m)
        m.update(sample(2100, 20f)); m.update(sample(2400, 20f))
        val invalid = m.update(sample(3000, null, quality = DepthNodeQuality.OFFLINE))
        assertEquals(2400L, invalid.completed.single().endMs)
        m.update(sample(3100, 20f))
        assertFalse(DepthEventType.SHALLOW in m.update(sample(3200, 20f)).status.events)
        assertFalse(DepthEventType.SHALLOW in m.update(sample(3500, 20f, continuous = false)).status.events)
        assertTrue(DepthEventType.SHALLOW in m.update(sample(3800, 20f)).status.events)
    }
}
