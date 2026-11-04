/** @author NIANXI @brief 持久化白名单和并发状态回归。 */
package com.nx.vfremake

import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.DepthNodeStorage
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class DepthNodeStateTest {
    @Test fun concurrentUpdatesNeverOverwriteCalibrationOrCounters() {
        val store = DepthNodeStateStore()
        val gate = CountDownLatch(1)
        val writers = List(4) { index -> Thread {
            gate.await()
            repeat(500) {
                if (index == 0) store.updateChannel(0) { it.copy(offsetTrimMm = it.offsetTrimMm + 1f) }
                else store.updateChannel(0) { it.copy(receivedFrames = it.receivedFrames + 1) }
            }
        }.also { it.start() } }
        gate.countDown(); writers.forEach { it.join() }
        assertEquals(500f, store.current().channels[0].offsetTrimMm, 0f)
        assertEquals(1500L, store.current().channels[0].receivedFrames)
    }

    @Test fun savedCalibrationRetainsZeroPointButNotRuntimeOrFilterAssumptions() {
        val original = DepthNodeChannel(0, calibrationPoints = listOf(500f to 0f, 1500f to 25f, 2500f to 50f),
            fitOrder = 1, c0 = -12.5, c1 = 0.025, fitValid = true, offsetTrimMm = 0.5f,
            isOnline = true, hasStatus = true, lastHeardMs = 999, depthMm = 50f,
            filterParams = DepthNodeFilterParams(), parameterWords = mapOf(0 to 9L))
        val json = DepthNodeStorage.encode(original)
        val restored = DepthNodeStorage.decode(json, 0)
        assertEquals(original.calibrationPoints, restored.calibrationPoints)
        assertTrue(restored.fitValid); assertFalse(restored.isOnline); assertFalse(restored.hasStatus)
        assertNull(restored.depthMm); assertNull(restored.filterParams); assertTrue(restored.parameterWords.isEmpty())
        assertFalse(json.contains("isOnline")); assertFalse(json.contains("parameterWords"))
    }

    @Test fun corruptOrCrossRowCalibrationDoesNotBecomeValidOnRestart() {
        for (json in listOf(null, "not json", "{}", "{\"baseId\":1664,\"points\":null}")) {
            assertFalse(DepthNodeStorage.decode(json, 0).fitValid)
        }
        assertFalse(DepthNodeStorage.decode(DepthNodeStorage.encode(DepthNodeChannel(1)), 0).fitValid)
        assertEquals(0x680, DepthNodeStorage.decode(DepthNodeStorage.encode(DepthNodeChannel(1)), 0).baseId)
    }

    @Test fun persistentMergePreservesLiveFeedback() {
        val runtime = DepthNodeChannel(0, isOnline = true, uMeanMv = 1234f, seq = 7)
        val saved = DepthNodeStorage.saved(DepthNodeChannel(0, offsetTrimMm = 2f))
        val merged = DepthNodeStorage.apply(saved, runtime)
        assertTrue(merged.isOnline); assertEquals(7, merged.seq)
        assertEquals(1234f, merged.uMeanMv!!, 0f); assertEquals(2f, merged.offsetTrimMm, 0f)
    }

    @Test fun filterReadbackRequiresNineValidWireValues() {
        val params = DepthNodeFilterParams()
        assertEquals(params, DepthNodeFilterParams.fromWords(params.wireWords()))
        assertNull(DepthNodeFilterParams.fromWords(params.wireWords().dropLast(1)))
        assertNull(DepthNodeFilterParams.fromWords(params.wireWords().toMutableList().also { it[0] = 32 }))
        assertNull(DepthNodeFilterParams.fromWords(params.wireWords().toMutableList().also {
            it[4] = com.nx.vfremake.funClass.DepthNodeProtocol.floatWord(Float.NaN)
        }))
    }
}
