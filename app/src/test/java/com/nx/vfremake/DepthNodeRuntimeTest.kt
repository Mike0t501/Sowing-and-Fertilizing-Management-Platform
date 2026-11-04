/** @author NIANXI @brief 同一处理器覆盖实板帧和模拟帧。 */
package com.nx.vfremake

import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.*
import com.nx.vfremake.funClass.DepthNodeProtocol as P
import org.junit.Assert.*
import org.junit.Test

class DepthNodeRuntimeTest {
    private var clock = 1000L
    private val store = DepthNodeStateStore()
    private val runtime = DepthNodeRuntime(store, nowMs = { clock })
    private fun status(seq: Int = 1, flags: Int = 0, filtered: Float? = 2500f) {
        runtime.onDepthNodeFrame(0x682, P.statusData(filtered, 2500f, 12f, 0x6C, flags, seq))
    }

    @Test fun timestampOfflineDetectionIgnoresInfoAndCfgActivity() {
        status(); assertTrue(runtime.channel(0).isOnline)
        clock += 1001
        runtime.onDepthNodeFrame(0x683, P.infoData(0x10, 1, 2, 3300, 30, 0))
        runtime.onDepthNodeFrame(0x680, P.cfgData(P.READ, 0x2300, 9, 50))
        runtime.tick(); assertEquals(DepthNodeQuality.OFFLINE, runtime.channel(0).quality)
        status(2); assertTrue(runtime.channel(0).isOnline)
        store.updateChannel(0) { it.copy(reportMs = 1000) }
        clock += 3000; runtime.tick(); assertTrue(runtime.channel(0).isOnline)
        clock++; runtime.tick(); assertFalse(runtime.channel(0).isOnline)
    }

    @Test fun priorityAndZeroDepthAndNoTabletFilter() {
        store.updateChannel(0) { it.copy(fitValid = true, c0 = -62.5, c1 = 0.025) }
        status(); assertEquals(0f, runtime.channel(0).depthMm!!, 0.001f)
        status(2, P.Flag.SETTLING.mask); assertEquals(DepthNodeQuality.SETTLING, runtime.channel(0).quality)
        status(3, P.Flag.SETTLING.mask or P.Flag.STOP_LO.mask)
        assertEquals(DepthNodeQuality.OUT_OF_RANGE, runtime.channel(0).quality)
        status(4, 255); assertEquals(DepthNodeQuality.FAULT, runtime.channel(0).quality)
        status(5, filtered = null); assertNull(runtime.channel(0).depthMm)
        status(6, filtered = 3500f)
        assertEquals(25f, runtime.channel(0).depthMm!!, 0.001f)
        assertEquals(0.3f, runtime.channel(0).spanMm!!, 0.001f)
        assertEquals(3500f, runtime.channel(0).uFiltMv!!, 0f)
    }

    @Test fun sequenceWrapDuplicateResetAndUptimeWrap() {
        status(254); status(255); status(0); status(2); status(2)
        assertEquals(1L, runtime.channel(0).lostFrames)
        assertEquals(4L, runtime.channel(0).receivedFrames)
        runtime.onDepthNodeFrame(0x683, P.infoData(0x10, 1, 65535, 3300, 30, 0))
        clock += 1000
        runtime.onDepthNodeFrame(0x683, P.infoData(0x10, 1, 0, 3300, 30, 0))
        assertEquals(2, runtime.channel(0).seq)
        runtime.onDepthNodeFrame(0x683, P.infoData(0x10, 1, 100, 3300, 30, 0))
        clock += 1000
        runtime.onDepthNodeFrame(0x683, P.infoData(0x10, 1, 1, 3300, 30, 0))
        assertNull(runtime.channel(0).seq)
        status(0); assertEquals(1L, runtime.channel(0).lostFrames)
    }

    @Test fun unknownVersionAcceptedUntilInfoThenIncompatibleStatusIsStopped() {
        status(); assertTrue(runtime.channel(0).hasStatus)
        runtime.onDepthNodeFrame(0x683, P.infoData(0x20, 1, 1, 3300, 30, 0))
        val previous = runtime.channel(0)
        clock += 100
        status(2, filtered = 4000f)
        assertEquals(previous, runtime.channel(0))
        assertEquals(DepthNodeQuality.FAULT, previous.quality)
        clock += 1001; runtime.tick(); assertEquals(DepthNodeQuality.OFFLINE, runtime.channel(0).quality)
    }

    @Test fun malformedAndFactoryAndResearchNeverOverwriteWorkingRows() {
        val initial = store.current().channels
        runtime.onDepthNodeFrame(0x682, ByteArray(7))
        runtime.onDepthNodeFrame(0x6C2, P.statusData(2000f, 2000f, 2f, 0xD1, 0, 1))
        repeat(12) { runtime.onDepthNodeFrame(0x6C4, ByteArray(8)) }
        runtime.tick()
        assertTrue(runtime.channel(-1).isOnline)
        assertEquals(12f, runtime.channel(-1).researchFps, 0f)
        assertEquals(initial, store.current().channels)
        clock += 1001; runtime.tick(); assertEquals(0f, runtime.channel(-1).researchFps, 0f)
    }
}
