/** @author NIANXI @brief 监测接线、止点例外及事件记录时间基准。 */
package com.nx.vfremake

import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.*
import com.nx.vfremake.funClass.DepthNodeProtocol as P
import org.junit.Assert.*
import org.junit.Test

class DepthQualityServiceTest {
    private var clock = 0L
    private val store = DepthNodeStateStore()
    private val runtime = DepthNodeRuntime(store, nowMs = { clock })
    private val facade = MeasuredDepthFacade({ store.current() }, { clock })
    private val service = DepthQualityService(store, facade, { clock }, { 1_800_000_000_000L + clock }, { 40f }, { null })
    private var seq = 0
    init { store.updateChannel(0) { it.copy(fitValid = true, c0 = -12.5, c1 = 0.025) } }
    private fun step(at: Long, depth: Float, flags: Int = 0) {
        clock = at
        val mv = 500 + 40 * depth
        runtime.onDepthNodeFrame(0x682, P.statusData(mv, mv, 2f, 0x6C, flags, seq++))
        service.accept(runtime.channel(0))
    }
    private fun warm() { step(0, 40f); step(1000, 40f); step(2000, 40f) }

    @Test fun eventRecorderSettlesActiveSnapshotAndLeavesMonitoringActive() {
        warm(); step(2100, 20f); step(2400, 20f)
        assertTrue(DepthEventType.SHALLOW in facade.forRow(0).monitoring.events)
        val recording = service.beginRecording(listOf(0), 2450)
        step(2500, 20f)
        service.endRecording(recording)
        val event = recording.drain().single { it.type == DepthEventType.SHALLOW }
        assertEquals("RECORD_STOP", event.endReason)
        assertEquals(2100L, event.startMs); assertEquals(2500L, event.endMs)
        val columns = DepthEventSchema.row(event, recording.startedMs)
        assertEquals("-350", columns[2]); assertEquals("50", columns[3]); assertEquals("400", columns[6])
        assertTrue(DepthEventType.SHALLOW in facade.forRow(0).monitoring.events)
        assertEquals("SHALLOW", DepthRecordSchema.nodeColumns(runtime.channel(0), facade.forRow(0)).last())
    }

    @Test fun trustedShallowStopCanTriggerLiftButDoesNotCreateDepthStatistics() {
        step(0, 0f, P.Flag.STOP_LO.mask); step(100, 0f, P.Flag.STOP_LO.mask)
        service.tick()
        val sample = facade.forRow(0)
        assertNull(sample.depthMm)
        assertTrue(DepthEventType.LIFT_OFF in sample.monitoring.events)
        assertFalse(sample.monitoring.windowReady)
        val negative = runtime.channel(0).copy(c1 = -0.025, flags = P.Flag.STOP_HI.mask)
        assertTrue(DepthQualityService.trustedShallowStop(negative, sample))
        assertFalse(DepthQualityService.trustedShallowStop(negative.copy(flags = 12), sample))
        assertFalse(DepthQualityService.trustedShallowStop(negative.copy(icdVer = 0x20), sample))
    }

    @Test fun offlineTickEndsAtLastArrivalAndDoesNotInventMoreSamples() {
        warm(); step(2100, 20f); step(2400, 20f)
        val recording = service.beginRecording(listOf(0), 2000)
        clock = 4000; service.tick()
        val event = recording.drain().single { it.type == DepthEventType.SHALLOW }
        assertEquals(2400L, event.endMs)
        assertFalse(facade.forRow(0).monitoring.windowReady)
        assertTrue(facade.forRow(0).monitoring.events.isEmpty())
        service.endRecording(recording)
        assertTrue(recording.drain().isEmpty())
    }

    @Test fun wallClockChangesCannotMakeNegativeEventDuration() {
        val event = DepthQualityEvent(0, DepthEventType.LIFT_OFF, 1000, 1500, 10000, 5000,
            null, 0f, 3f, 40f, "RECOVERED")
        val columns = DepthEventSchema.row(event, 1000)
        assertEquals("500", columns[6]); assertEquals("", columns[7]); assertEquals("0.0", columns[8])
        assertEquals(DepthEventSchema.HEADER.size, columns.size)
    }
}
