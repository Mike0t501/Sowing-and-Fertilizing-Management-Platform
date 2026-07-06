package com.nx.vfremake

import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.JogSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CANopen 帧构建与点动会话的纯 JVM 单元测试。
 *
 * 覆盖点动稳定性修复的关键不变量：
 *   1. 点动启动序列以 0x0006（Shutdown）开头——冷驱动器（Switch On Disabled）
 *      缺此帧会直接忽略后续 0x0007，现场表现为点动按钮无反应；
 *   2. 使能帧 0x000F 必须是启动序列最后一帧（前缀安全：序列被取消只发出前缀时
 *      电机不可能启动）；
 *   3. 点动停止兜底帧（Disable Operation）不含 0x000F，退出 Operation Enabled
 *      后即使 v=0 全部丢失电机也转不了；
 *   4. JogSession 的 CAS 语义：迟到的旧 stop 清不掉新按下建立的会话。
 */
class CanOpenFunTest {

    @Before
    fun resetJogSession() {
        JogSession.clearAll()
    }

    // ── 辅助：解出 SDO 数据段并断言写入帧内容 ────────────────────────────────

    private fun unwrap(frame: ByteArray): Pair<Int, ByteArray> {
        val result = CanOpenFun.unwrapCanFrame(frame)
        assertNotNull("帧应能被 unwrapCanFrame 解析", result)
        return result!!
    }

    /** 断言一帧是发往 nodeId 的 SDO 写：index/subIndex/dataLen/value 全匹配 */
    private fun assertSdoWrite(
        frame: ByteArray,
        nodeId: Int,
        index: Int,
        dataLen: Int,
        value: Long
    ) {
        val (canId, sdo) = unwrap(frame)
        assertEquals("SDO 请求 CAN-ID 应为 0x600+nodeId", 0x600 + nodeId, canId)
        assertEquals("SDO 数据段固定 8 字节", 8, sdo.size)
        val expectedCs = when (dataLen) {
            1 -> 0x2F; 2 -> 0x2B; 4 -> 0x23
            else -> error("bad dataLen")
        }
        assertEquals("CS 命令符", expectedCs, sdo[0].toInt() and 0xFF)
        assertEquals("索引低字节", index and 0xFF, sdo[1].toInt() and 0xFF)
        assertEquals("索引高字节", (index ushr 8) and 0xFF, sdo[2].toInt() and 0xFF)
        assertEquals("子索引", 0x00, sdo[3].toInt() and 0xFF)
        for (i in 0 until dataLen) {
            assertEquals(
                "数据第 $i 字节（小端）",
                ((value ushr (8 * i)) and 0xFF).toInt(),
                sdo[4 + i].toInt() and 0xFF
            )
        }
    }

    // ── 帧封装 ───────────────────────────────────────────────────────────────

    @Test
    fun wrapUnwrapRoundTrip() {
        val data = byteArrayOf(0x2B, 0x40, 0x60, 0x00, 0x0F, 0x00, 0x00, 0x00)
        val frame = CanOpenFun.wrapCanFrame(0x60B, data)
        assertEquals(0x27.toByte(), frame.first())
        assertEquals(0x39.toByte(), frame.last())
        val (canId, out) = unwrap(frame)
        assertEquals(0x60B, canId)
        assertArrayEquals(data, out)
    }

    // ── 点动启动序列 ─────────────────────────────────────────────────────────

    @Test
    fun jogStartSequenceBeginsWithShutdownAndEndsWithEnable() {
        val nodeId = 11
        val frames = CanOpenFun.buildJogStartSequence(nodeId, 2000)
        assertEquals("启动序列应为 5 帧", 5, frames.size)
        assertSdoWrite(frames[0], nodeId, 0x6040, 2, 0x0006L)   // Shutdown（冷启动必需）
        assertSdoWrite(frames[1], nodeId, 0x6040, 2, 0x0007L)   // Switch On
        assertSdoWrite(frames[2], nodeId, 0x6060, 1, 0x03L)     // 速度模式
        assertSdoWrite(frames[3], nodeId, 0x60FF, 4, 2000L)     // 目标速度
        assertSdoWrite(frames[4], nodeId, 0x6040, 2, 0x000FL)   // Enable 必须最后（前缀安全）
    }

    @Test
    fun jogStartSequenceEncodesNegativeSpeedLittleEndian() {
        val frames = CanOpenFun.buildJogStartSequence(18, -2000)
        // -2000 的 32 位补码 = 0xFFFFF830，小端 [0x30, 0xF8, 0xFF, 0xFF]
        val (_, sdo) = unwrap(frames[3])
        assertEquals(0x23, sdo[0].toInt() and 0xFF)
        assertEquals(0x30, sdo[4].toInt() and 0xFF)
        assertEquals(0xF8, sdo[5].toInt() and 0xFF)
        assertEquals(0xFF, sdo[6].toInt() and 0xFF)
        assertEquals(0xFF, sdo[7].toInt() and 0xFF)
    }

    // ── 点动停止帧 ───────────────────────────────────────────────────────────

    @Test
    fun jogVelocityZeroFrame() {
        assertSdoWrite(CanOpenFun.buildJogVelocityZeroFrame(12), 12, 0x60FF, 4, 0L)
    }

    @Test
    fun jogStopDisableFramesContainNoEnable() {
        val nodeId = 15
        val frames = CanOpenFun.buildJogStopDisableFrames(nodeId)
        assertEquals("兜底序列应为 2 帧", 2, frames.size)
        assertSdoWrite(frames[0], nodeId, 0x6040, 2, 0x0007L)   // Disable Operation
        assertSdoWrite(frames[1], nodeId, 0x6060, 1, 0x01L)     // 预切位置模式
        // 不变量：兜底序列中绝不能出现使能控制字 0x000F
        for (frame in frames) {
            val (_, sdo) = unwrap(frame)
            val index = (sdo[1].toInt() and 0xFF) or ((sdo[2].toInt() and 0xFF) shl 8)
            if (index == 0x6040) {
                val value = (sdo[4].toInt() and 0xFF) or ((sdo[5].toInt() and 0xFF) shl 8)
                assertTrue("兜底控制字不得为使能值 0x000F", value != 0x000F)
            }
        }
    }

    // ── JogSession CAS 语义 ─────────────────────────────────────────────────

    @Test
    fun jogSessionBasicBeginEnd() {
        assertFalse(JogSession.isJogging(11))
        JogSession.begin(11)
        assertTrue(JogSession.isJogging(11))
        assertFalse(JogSession.isJogging(12))
        JogSession.end(11)
        assertFalse(JogSession.isJogging(11))
    }

    @Test
    fun jogSessionStaleEndCannotClearNewerSession() {
        // 场景：电机 11 的旧 stop 收尾迟到，此时用户已开始点动电机 12
        JogSession.begin(11)
        JogSession.begin(12)          // 新按下接管
        JogSession.end(11)            // 迟到的旧收尾
        assertTrue("旧 end 不得清掉新会话", JogSession.isJogging(12))
        JogSession.end(12)
        assertFalse(JogSession.isJogging(12))
    }

    @Test
    fun jogSessionClearAll() {
        JogSession.begin(13)
        JogSession.clearAll()
        assertFalse(JogSession.isJogging(13))
    }
}
