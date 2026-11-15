/** @author NIANXI @brief ICD v1.0 金标准，不依赖设备。 */
package com.nx.vfremake

import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.DepthNodeProtocol as P
import org.junit.Assert.*
import org.junit.Test

class DepthNodeProtocolTest {
    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun fourGoldenFramesAreByteExact() {
        val status = P.statusData(2345.6f, 2350f, 12f, 0x6C, 0, 42)
        assertArrayEquals(bytes("A0 5B CC 5B 06 6C 00 2A"), status)
        assertArrayEquals(bytes("27 0B 00 06 82 A0 5B CC 5B 06 6C 00 2A 39"),
            CanOpenFun.wrapCanFrame(0x682, status))
        assertArrayEquals(bytes("10 01 10 0E E5 0C 23 00"), P.infoData(0x10, 1, 3600, 3301, 35, 0))
        assertArrayEquals(bytes("B0 04 A0 5B A5 5B 99 5B"), P.researchData(1200, listOf(2345.6f, 2346.1f, 2344.9f)))
        val write = P.cfgRequest(0x680, P.WRITE, 0x2300, 9, 20)
        assertEquals(0x681, write.canId)
        assertArrayEquals(bytes("02 00 23 09 14 00 00 00"), write.data)
        assertEquals(P.CfgReply(2, 0x2300, 9, 20), P.parseCfgReply(write.data))
    }

    @Test fun crcMatchesAllThreeIndependentVectors() {
        assertEquals(0xF4, P.crc8("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0x6C, P.filterCrc(P.DEFAULT_FILTER_WORDS))
        assertEquals(0xD1, P.filterCrc(P.DIRECT_FILTER_WORDS))
    }

    @Test fun statusUnitsAndFlagsAndInvalidSentinel() {
        val status = P.parseStatus(bytes("A0 5B CC 5B FF 6C FF FF"))!!
        assertEquals(2345.6f, status.uFiltMv!!, 0.01f)
        assertEquals(2350f, status.uMeanMv!!, 0.01f)
        assertEquals(510f, status.spanMv, 0f)
        assertEquals(255, status.seq)
        P.Flag.values().forEach { assertTrue(status.has(it)) }
        val invalid = P.parseStatus(P.statusData(null, Float.NaN, 0f, 0, 0, 0))!!
        assertNull(invalid.uFiltMv); assertNull(invalid.uMeanMv)
        assertEquals(0f, P.parseStatus(P.statusData(0f, 0f, 0f, 0, 0, 0))!!.uFiltMv!!, 0f)
        assertEquals(6553.4f, P.parseStatus(P.statusData(1e10f, 1e10f, 900f, 0, 0, 0))!!.uFiltMv!!, 0.01f)
    }

    @Test fun infoUsesSignedTemperatureAndMajorCompatibility() {
        val info = P.parseInfo(P.infoData(0x1F, 200, 65535, 3300, -20, 255))!!
        assertEquals(-20, info.mcuTempC); assertEquals(200, info.fwRev)
        assertEquals(65535, info.uptimeS); assertTrue(info.compatible)
        assertFalse(info.copy(icdVer = 0x20).compatible)
        assertEquals(P.CoreVersion(0, 5, 0x1234), P.parseCoreVersion(bytes("1F 00 00 00 00 05 12 34")))
    }

    @Test fun addressRangeIncludesFactoryAndEveryReservedOffset() {
        for (id in 0x680..0x6C7) {
            assertTrue(P.isDepthNodeId(id)); assertTrue(P.isValidBase(P.baseOf(id)!!))
        }
        for (row in 0..7) assertEquals(row, P.rowOf(P.baseForRow(row)))
        assertNull(P.rowOf(P.FACTORY_BASE)); assertFalse(P.isValidBase(0x681))
        assertFalse(P.isDepthNodeId(0x67F)); assertFalse(P.isDepthNodeId(0x6C8))
        assertNull(P.baseOf(0x6C8))
    }

    @Test fun strictDlcAndNotificationsDoNotParseAsCfg() {
        for (size in listOf(0, 1, 7, 9)) {
            assertNull(P.parseInfo(ByteArray(size))); assertNull(P.parseStatus(ByteArray(size)))
            assertNull(P.parseCfgReply(ByteArray(size)))
        }
        for (cmd in listOf(0x46, 0x52, 0x45)) assertNull(P.parseCfgReply(P.cfgData(cmd)))
        assertArrayEquals(bytes("1E 01 03 08 00 00 00 00"), P.burnRequest(0x680).data)
        assertEquals(0xFFFFFFFFL, P.parseCfgReply(P.cfgData(P.READ, value = 0xFFFFFFFFL))!!.value)
        assertEquals(20, P.actualReportMs(15)); assertEquals(10, P.actualReportMs(10))
    }
}
