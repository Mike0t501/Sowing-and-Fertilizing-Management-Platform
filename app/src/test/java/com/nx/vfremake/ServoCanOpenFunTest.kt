package com.nx.vfremake

import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.ServoCanOpenFun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServoCanOpenFunTest {

    @Test
    fun edsCatalogContainsCoreAndManufacturerObjects() {
        assertTrue(ServoCanOpenFun.EDS_OBJECTS.size >= 100)
        assertNotNull(ServoCanOpenFun.EDS_OBJECTS.find { it.index == 0x6041 && it.subIndex == 0 })
        assertNotNull(ServoCanOpenFun.EDS_OBJECTS.find { it.index == 0x6064 && it.subIndex == 0 })
        assertNotNull(ServoCanOpenFun.EDS_OBJECTS.find { it.index == 0x260E && it.subIndex == 0 })
        assertNull("EDS 未声明 261Fh", ServoCanOpenFun.EDS_OBJECTS.find { it.index == 0x261F })
        assertNull("EDS 未声明 2620h", ServoCanOpenFun.EDS_OBJECTS.find { it.index == 0x2620 })
    }

    @Test
    fun tpdo1MappingMatchesEdsDefault() {
        val data = byteArrayOf(0x78, 0x56, 0x34, 0x12, 0x27, 0x04)
        val parsed = CanOpenFun.parseTpdo1(0x18B, data)
        assertNotNull(parsed)
        assertEquals(0x12345678, parsed!!.actualPos)
        assertEquals(0x0427, parsed.statusWord)
    }

    @Test
    fun expeditedSdoReplyParsingSupportsSignedObjects() {
        val reply = ServoCanOpenFun.parseSdoReply(
            byteArrayOf(0x43, 0x64, 0x60, 0x00, 0x30, 0xF8.toByte(), 0xFF.toByte(), 0xFF.toByte())
        )
        assertNotNull(reply)
        assertEquals(0x6064, reply!!.index)
        assertEquals(-2000, reply.value!!.toInt())
        val obj = ServoCanOpenFun.EDS_OBJECTS.first { it.key == "actualPosition" }
        assertTrue(ServoCanOpenFun.formatValue(obj, reply.value!!).startsWith("-2000 /"))
    }

    @Test
    fun blockedObjectsCannotBeWrittenFromGenericConsole() {
        val nodeAddress = ServoCanOpenFun.EDS_OBJECTS.first { it.key == "deviceAddress" }
        assertEquals(ServoCanOpenFun.WritePolicy.BLOCKED, nodeAddress.writePolicy)
        val result = runCatching { ServoCanOpenFun.buildWriteObjectFrame(11, nodeAddress, 12L) }
        assertTrue(result.isFailure)

        val profileVelocity = ServoCanOpenFun.EDS_OBJECTS.first { it.key == "profileVelocity" }
        assertFalse(runCatching { ServoCanOpenFun.buildWriteObjectFrame(11, profileVelocity, 500L) }.isFailure)
    }

    @Test
    fun identityMatchUsesEdsDefaults() {
        assertTrue(
            ServoCanOpenFun.identityMatches(
                mapOf("vendorId" to 817L, "productCode" to 1L, "revision" to 256L)
            )
        )
        assertFalse(
            ServoCanOpenFun.identityMatches(
                mapOf("vendorId" to 817L, "productCode" to 2L, "revision" to 256L)
            )
        )
    }
}
