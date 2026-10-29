package com.nx.vfremake

import com.nx.vfremake.funClass.CanLinkStats
import com.nx.vfremake.funClass.CanOpenFun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CAN 链路质量计数器的纯 JVM 单元测试。
 *
 * 关键不变量：
 *   1. 只有 SDO 请求帧（0x600+ID）计入"请求"，NMT/其它帧不计；
 *   2. 对 0x6084 的请求不计入——YZ 驱动器对它既不 ACK 也不 abort，计入会让丢失率永远不为 0；
 *   3. 应答含 abort 一起算"应答"（abort 也证明帧到了、驱动器答了），另单独计 abort 数；
 *   4. 丢失率 = (请求 - 应答)/请求，无请求时为 0，应答多于请求时钳到 0（重置后收到旧应答）。
 */
class CanLinkStatsTest {

    @Before
    fun reset() {
        CanLinkStats.reset()
    }

    @Test
    fun sdoRequestIsCountedPerNode() {
        CanLinkStats.onFrameSent(CanOpenFun.buildReadPositionFrame(11))
        CanLinkStats.onFrameSent(CanOpenFun.buildReadPositionFrame(11))
        CanLinkStats.onFrameSent(CanOpenFun.buildSdoWriteFrame(12, 0x6040, 0x00, 2, 0x000FL))
        val s = CanLinkStats.snapshot()
        assertEquals(3L, s.txFrames)
        assertEquals(2L, s.nodes[11]!!.sdoRequests)
        assertEquals(1L, s.nodes[12]!!.sdoRequests)
    }

    @Test
    fun nmtFrameCountsAsTxButNotAsSdoRequest() {
        CanLinkStats.onFrameSent(CanOpenFun.buildNmtFrame(CanOpenFun.Nmt.OPERATIONAL, 0x00))
        val s = CanLinkStats.snapshot()
        assertEquals(1L, s.txFrames)
        assertTrue("NMT 不是 SDO 请求，不该产生节点条目", s.nodes.isEmpty())
    }

    @Test
    fun writesTo0x6084AreNotCountedAsRequests() {
        // 驱动器对 6084h 静默丢弃（docs/CANframes2.txt 实测），计入会制造永久性的假丢失
        CanLinkStats.onFrameSent(CanOpenFun.buildSdoWriteFrame(11, 0x6084, 0x00, 4, 10000L))
        CanLinkStats.onFrameSent(CanOpenFun.buildSdoReadFrame(11, 0x6084, 0x00))
        CanLinkStats.onFrameSent(CanOpenFun.buildSdoWriteFrame(11, 0x6083, 0x00, 4, 10000L))
        val n = CanLinkStats.snapshot().nodes[11]!!
        assertEquals("只有 6083h 那一帧算请求", 1L, n.sdoRequests)
    }

    @Test
    fun lossRatioPairsRequestsWithRepliesIncludingAborts() {
        repeat(10) { CanLinkStats.onFrameSent(CanOpenFun.buildReadPositionFrame(11)) }
        repeat(7) { CanLinkStats.onSdoReply(11, isAbort = false) }
        CanLinkStats.onSdoReply(11, isAbort = true)
        val n = CanLinkStats.snapshot().nodes[11]!!
        assertEquals(10L, n.sdoRequests)
        assertEquals(8L, n.sdoReplies)
        assertEquals(1L, n.sdoAborts)
        assertEquals(2L, n.sdoLost)
        assertEquals(0.2, n.sdoLossRatio, 1e-9)
    }

    @Test
    fun lossRatioIsZeroWithoutRequestsAndClampedWhenRepliesExceedRequests() {
        // 重置后收到旧请求的应答：应答 > 请求，不能算负丢失率
        CanLinkStats.onSdoReply(11, isAbort = false)
        val n = CanLinkStats.snapshot().nodes[11]!!
        assertEquals(0.0, n.sdoLossRatio, 1e-9)
        assertEquals(-1L, n.sdoLost)   // 原始差值保留给排查用
    }

    @Test
    fun uartHealthCountersAndCleanFlag() {
        assertTrue(CanLinkStats.snapshot().uartClean)
        CanLinkStats.onFrameParsed()
        CanLinkStats.onFrameParsed()
        CanLinkStats.onResyncByte()
        val s = CanLinkStats.snapshot()
        assertEquals(2L, s.rxFrames)
        assertEquals(1L, s.resyncBytes)
        assertFalse("有错位字节就不算干净", s.uartClean)

        CanLinkStats.reset()
        CanLinkStats.onBadTail()
        assertFalse(CanLinkStats.snapshot().uartClean)

        CanLinkStats.reset()
        CanLinkStats.onParseError()
        assertFalse(CanLinkStats.snapshot().uartClean)

        CanLinkStats.reset()
        CanLinkStats.onIoError()
        // IO 异常是端口层的事（关/重开），不算 UART 字节流错位
        assertTrue(CanLinkStats.snapshot().uartClean)
        assertEquals(1L, CanLinkStats.snapshot().ioErrors)
    }

    @Test
    fun heartbeatAndBootupAndTpdoPerNode() {
        CanLinkStats.onHeartbeat(11, isBootup = false)
        CanLinkStats.onHeartbeat(11, isBootup = true)
        CanLinkStats.onTpdo1(11)
        CanLinkStats.onTpdo1(11)
        CanLinkStats.onTpdo1(11)
        val n = CanLinkStats.snapshot().nodes[11]!!
        assertEquals(2L, n.heartbeats)
        assertEquals(1L, n.bootups)
        assertEquals(3L, n.tpdo1)
    }

    @Test
    fun resetClearsEverything() {
        CanLinkStats.onFrameSent(CanOpenFun.buildReadPositionFrame(11))
        CanLinkStats.onResyncByte()
        CanLinkStats.reset()
        val s = CanLinkStats.snapshot()
        assertEquals(0L, s.txFrames)
        assertEquals(0L, s.resyncBytes)
        assertTrue(s.nodes.isEmpty())
        assertTrue(s.uptimeMs < 1000)
    }

    @Test
    fun snapshotNodesAreSortedByNodeId() {
        CanLinkStats.onTpdo1(18)
        CanLinkStats.onTpdo1(11)
        CanLinkStats.onTpdo1(14)
        assertEquals(listOf(11, 14, 18), CanLinkStats.snapshot().nodes.keys.toList())
    }
}
