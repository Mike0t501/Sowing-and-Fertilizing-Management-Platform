/** @author NIANXI @brief 配置应答匹配、拒绝回读与配号回归。 */
package com.nx.vfremake

import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.*
import com.nx.vfremake.funClass.DepthNodeProtocol as P
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DepthNodeCfgClientTest {
    @Test fun registeredBeforeSendAndMatchesAllFourFieldsNotNotifications() = runBlocking {
        val waiters = DepthNodeCfgWaiters()
        val frame = P.cfgRequest(0x680, P.READ, 0x2300, 9)
        val answer = waiters.sendAndAwait(frame, 0x680, send = {
            assertEquals(1, waiters.pendingCount())
            assertFalse(waiters.tryComplete(0x680, P.cfgData(0x46)))
            assertFalse(waiters.tryComplete(0x688, P.cfgData(P.READ, 0x2300, 9, 50)))
            assertFalse(waiters.tryComplete(0x680, P.cfgData(P.WRITE, 0x2300, 9, 50)))
            assertFalse(waiters.tryComplete(0x680, P.cfgData(P.READ, 0x2301, 9, 50)))
            assertFalse(waiters.tryComplete(0x680, P.cfgData(P.READ, 0x2300, 8, 50)))
            assertTrue(waiters.tryComplete(0x680, P.cfgData(P.READ, 0x2300, 9, 50)))
        }, timeoutMs = 20)
        assertEquals(50L, answer!!.value); assertEquals(0, waiters.pendingCount())
    }

    @Test fun cancellationAndTimeoutRemoveWaiter() = runBlocking {
        val waiters = DepthNodeCfgWaiters()
        val frame = P.cfgRequest(0x680, P.READ, 0x2300, 9)
        assertNull(waiters.sendAndAwait(frame, 0x680, {}, 10))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { waiters.sendAndAwait(frame, 0x680, {}) }
        assertEquals(1, waiters.pendingCount()); job.cancelAndJoin()
        assertEquals(0, waiters.pendingCount())
    }

    @Test fun rejectedWriteIsReadBackAndCannotPretendToApplyOnDirectFirmware() = runBlocking {
        val runtime = DepthNodeRuntime(DepthNodeStateStore())
        val simulator = DepthNodeSimulator({ runtime.onDepthNodeFrame(it.canId, it.data) }, runtime.nowMs, directOnly = true)
        val sent = mutableListOf<P.Frame>()
        val client = DepthNodeCfgClient(runtime, { sent.add(it); simulator.command(it) }, 10)
        val failure = runCatching { client.write(0x680, 0, 9) }
        assertTrue(failure.isFailure)
        assertEquals(listOf(P.WRITE, P.READ), sent.map { it.data[0].toInt() })
        assertEquals(1L, client.read(0x680, sub = 0))
        assertTrue(runCatching { client.read(0x680, sub = 99) }.isFailure)
    }

    @Test fun lostWriteAckConfirmedByReadAndBurnRequiresSuccess() = runBlocking {
        val runtime = DepthNodeRuntime(DepthNodeStateStore())
        var written = 50L
        val client = DepthNodeCfgClient(runtime, { frame ->
            val request = P.parseCfgReply(frame.data)!!
            if (request.cmd == P.WRITE) written = request.value
            if (request.cmd == P.READ) runtime.onDepthNodeFrame(0x680, P.cfgData(P.READ, request.index, request.subIndex, written))
            if (request.cmd == P.BURN) runtime.onDepthNodeFrame(0x680, P.cfgData(P.BURN, 0x0301, 8, 0))
        }, 10)
        client.write(0x680, 9, 20)
        assertEquals(20L, written)
        assertTrue(runCatching { client.burn(0x680) }.isFailure)
        assertEquals(0, runtime.waiters.pendingCount())
    }

    @Test fun filterWritesAllNineWordsAndChecksReadbackAndStatusCrc() = runBlocking {
        var clock = 1000L
        val runtime = DepthNodeRuntime(DepthNodeStateStore(), nowMs = { clock })
        val simulator = DepthNodeSimulator({ runtime.onDepthNodeFrame(it.canId, it.data) }, { clock }, 0..0)
        val sent = mutableListOf<P.Frame>()
        val client = DepthNodeCfgClient(runtime, { sent.add(it); simulator.command(it); clock += 50; simulator.tick(listOf(40f)) }, 20)
        val params = DepthNodeFilterParams(rankN = 1, smoother = 0)
        client.applyFilter(0x680, params)
        val writes = sent.mapNotNull { P.parseCfgReply(it.data) }.filter { it.cmd == P.WRITE }
        assertEquals((0..8).toList(), writes.map { it.subIndex })
        assertEquals(params.wireWords(), writes.map { it.value })
        assertEquals(0xD1, runtime.channel(0).cfgCrc)
        assertEquals(params, runtime.channel(0).filterParams)
    }

    @Test fun provisionResponseAndBurnUseNewBaseThenFreshInfoIsVerified() = runBlocking {
        var clock = 1000L
        val runtime = DepthNodeRuntime(DepthNodeStateStore(), nowMs = { clock })
        val simulator = DepthNodeSimulator({ runtime.onDepthNodeFrame(it.canId, it.data) }, { clock }, IntRange.EMPTY)
        simulator.addFactoryNode(); simulator.tick(emptyList())
        val sent = mutableListOf<P.Frame>()
        val client = DepthNodeCfgClient(runtime, { sent.add(it); simulator.command(it) }, 20)
        val ticker = launch { while (isActive) { delay(10); clock += 100; simulator.tick(emptyList()) } }
        try { withTimeout(1500) { client.provision(0) } } finally { ticker.cancelAndJoin() }
        assertEquals(0x6C1, sent.first().canId)
        assertEquals(listOf(0x681, 0x681), sent.drop(1).map { it.canId })
        assertEquals(P.BURN, sent.last().data[0].toInt())
        assertEquals(0x10, runtime.channel(0).icdVer)
    }

    @Test fun invalidWireValuesAreRejectedBeforeSend() {
        assertTrue(runCatching { DepthNodeCfgClient.validate(0x2300, 4, P.floatWord(Float.NaN)) }.isFailure)
        assertTrue(runCatching { DepthNodeCfgClient.validate(0, 0, 0x681) }.isFailure)
        assertTrue(runCatching { DepthNodeCfgClient.validate(0x2300, 9, 9) }.isFailure)
    }
}
