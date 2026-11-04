/** @author NIANXI @brief 预设方向与研究流限制必须可在无硬件环境复现。 */
package com.nx.vfremake

import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.*
import com.nx.vfremake.funClass.DepthNodeProtocol as P
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DepthNodeManagementTest {
    @Test fun presetsWriteAllNineDefaultsAndInvertOnlyDepthPercentile() {
        val positive = DepthNodeChannel(0, fitValid = true, c1 = 0.025)
        val negative = positive.copy(c1 = -0.025)
        val low = DepthNodePresets.Preset.LOW_EMA
        assertEquals(25, DepthNodePresets.parameters(low, positive).rankPct)
        assertEquals(75, DepthNodePresets.parameters(low, negative).rankPct)
        assertTrue(runCatching { DepthNodePresets.parameters(low, positive.copy(fitValid = false)) }.isFailure)
        for (preset in DepthNodePresets.Preset.values()) {
            val values = DepthNodePresets.parameters(preset, positive)
            assertEquals(9, values.wireWords().size)
            assertEquals(100f, values.kfQ, 0f); assertEquals(4f, values.kfR, 0f)
            assertEquals(preset.label, DepthNodePresets.currentName(positive.copy(filterParams = values)))
        }
        assertEquals(0xD1, DepthNodePresets.parameters(DepthNodePresets.Preset.DIRECT, positive).crc)
        assertEquals(0x6C, DepthNodePresets.parameters(DepthNodePresets.Preset.MEDIAN_EMA, positive).crc)
    }

    @Test fun budgetRejectsEightNodesUnknownReportHighLoadAndUnverifiedServo() {
        fun nodes(count: Int) = List(count) { DepthNodeChannel(it, isOnline = true, parameterWords = mapOf(9 to 50L)) }
        assertNull(DepthResearchBudget.calculate(nodes(4), 2, 0.0, 0.0, true).blockedReason)
        assertNotNull(DepthResearchBudget.calculate(nodes(8), 4, 0.0, 0.0, true).blockedReason)
        assertNotNull(DepthResearchBudget.calculate(nodes(1), 1, 0.0, 0.0, false).blockedReason)
        assertNotNull(DepthResearchBudget.calculate(nodes(2), 1, 0.0, 0.0, true).blockedReason)
        assertNotNull(DepthResearchBudget.calculate(nodes(1), 4, 600.0, 0.0, true).blockedReason)
        assertNotNull(DepthResearchBudget.calculate(listOf(DepthNodeChannel(0, isOnline = true)), 4, 0.0, 0.0, true).blockedReason)
        val single = DepthResearchBudget.calculate(nodes(1), 1, 0.0, 0.0, true)
        assertNull(single.blockedReason); assertEquals(354.333, single.totalFps, 0.001)
    }

    @Test fun observedServoPeriodMustActuallyBeNearOneHundredMilliseconds() {
        val fast = DepthBusTrafficMeter(); val slow = DepthBusTrafficMeter()
        for (time in 0L..2000L step 20) fast.record(0x18B, time)
        for (time in 0L..2000L step 100) slow.record(0x18B, time)
        assertFalse(fast.snapshot(2000, listOf(11)).servo100MsObserved)
        assertTrue(slow.snapshot(2000, listOf(11)).servo100MsObserved)
        assertFalse(slow.snapshot(2000, listOf(11, 12)).servo100MsObserved)
        assertFalse(slow.snapshot(5000, listOf(11)).servo100MsObserved)
    }

    @Test fun secondResearchNodeIsRejectedEvenBeforeFirstStatusFlagArrives() = runBlocking {
        var clock = 1000L
        val runtime = DepthNodeRuntime(DepthNodeStateStore(), nowMs = { clock })
        val simulator = DepthNodeSimulator({ runtime.onDepthNodeFrame(it.canId, it.data) }, { clock }, 0..1)
        simulator.tick(listOf(40f, 40f)); clock += 100
        val client = DepthNodeCfgClient(runtime, { simulator.command(it) }, 20)
        client.readAll(0x680); client.readAll(0x688)
        val management = DepthNodeManagement(runtime, client)
        val budget = DepthResearchBudget.calculate(runtime.store.current().channels, 2, 0.0, 0.0, true)
        management.enableResearch(0x680, 2, 600, budget)
        assertTrue(runCatching { management.enableResearch(0x688, 2, 600, budget) }.isFailure)
        management.disableResearch(0x680)
        management.enableResearch(0x688, 2, 600, budget)
        assertEquals(2L, runtime.channel(1).parameterWords[10])
        assertEquals(P.CoreVersion(0, 5, 8), client.version(0x688))
    }
}
