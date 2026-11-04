/** @author NIANXI @brief 合成帧节拍、独立挡块和研究流功能回归。 */
package com.nx.vfremake

import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.*
import com.nx.vfremake.funClass.DepthNodeProtocol as P
import org.junit.Assert.*
import org.junit.Test

class DepthNodeSimulatorTest {
    @Test fun defaultTwentyHzStatusAndOneHzInfoReachRealParser() {
        var clock = 1000L
        val runtime = DepthNodeRuntime(DepthNodeStateStore(), nowMs = { clock })
        val frames = mutableListOf<Pair<Long, P.Frame>>()
        val simulator = DepthNodeSimulator({ frames.add(clock to it); runtime.onDepthNodeFrame(it.canId, it.data) }, { clock }, 0..0)
        repeat(101) { simulator.tick(listOf(40f)); clock += 10 }
        val statuses = frames.filter { it.second.canId == 0x682 }
        assertEquals(21, statuses.size)
        statuses.zipWithNext().forEach { assertEquals(50L, it.second.first - it.first.first) }
        assertEquals(2, frames.count { it.second.canId == 0x683 })
        assertTrue(runtime.channel(0).isOnline)
        assertEquals(DepthNodeQuality.UNCALIBRATED, runtime.channel(0).quality)
        assertNull(runtime.channel(0).depthMm)
    }

    @Test fun heldGaugeBlockIsIndependentAndLiftAppearsAfterSixtySeconds() {
        var clock = 1000L
        val runtime = DepthNodeRuntime(DepthNodeStateStore(), nowMs = { clock })
        val simulator = DepthNodeSimulator({ runtime.onDepthNodeFrame(it.canId, it.data) }, { clock }, 0..0)
        simulator.holdDepth(0, 0f); simulator.tick(listOf(80f))
        assertEquals(500f, runtime.channel(0).uMeanMv!!, 1f)
        simulator.holdDepth(0, null)
        clock = 61_000L; simulator.tick(listOf(80f))
        assertEquals(620f, runtime.channel(0).uFiltMv!!, 3f)
        clock = 62_100L; simulator.tick(listOf(80f))
        assertTrue(runtime.channel(0).uFiltMv!! > 3500f)
    }

    @Test fun researchAutoClosesAndReservedFramesDoNotCreateDepth() {
        var clock = 1000L
        val frames = mutableListOf<P.Frame>()
        val simulator = DepthNodeSimulator({ frames.add(it) }, { clock }, 0..0)
        simulator.command(P.cfgRequest(0x680, P.WRITE, P.PARAM_INDEX, 11, 1))
        simulator.command(P.cfgRequest(0x680, P.WRITE, P.PARAM_INDEX, 10, 1))
        repeat(101) { simulator.tick(listOf(40f)); clock += 10 }
        assertTrue(frames.count { it.canId == 0x684 } in 325..333)
        val lastStatus = P.parseStatus(frames.last { it.canId == 0x682 }.data)!!
        assertFalse(lastStatus.has(P.Flag.RESEARCH))
    }
}
