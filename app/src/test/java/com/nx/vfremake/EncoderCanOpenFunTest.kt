package com.nx.vfremake

import com.nx.vfremake.data.buildLinearFit
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.EncoderCanOpenFun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摆臂编码器（布瑞特 DS406）协议层纯 JVM 单元测试。
 *
 * 关键不变量：
 *   1. TPDO1 数据段为 4 字节 U32 小端（对象 6004h），与伺服 6 字节 TPDO 严格区分；
 *   2. SDO 配置帧字节与手册示例逐字节一致（save 帧 73 61 76 65、1800-05=50 帧
 *      2B 00 18 05 32 00、3001 写 ID 帧 2F）；
 *   3. 手册示例应答帧 `43 04 60 00 E8 03 00 00` 必须解析出 1000；
 *   4. 限幅滤波：单帧野值丢弃、连续超限接受（防真实快速变化被锁死）；
 *   5. 符号展开：置零点附近弹跳越过零点时 U32 回绕值展开为负数；
 *   6. 共用最小二乘 buildLinearFit 拟合正确。
 */
class EncoderCanOpenFunTest {

    // ── 辅助：解出 SDO 数据段并断言写入帧内容 ────────────────────────────────

    private fun unwrap(frame: ByteArray): Pair<Int, ByteArray> {
        val result = CanOpenFun.unwrapCanFrame(frame)
        assertNotNull("帧应能被 unwrapCanFrame 解析", result)
        return result!!
    }

    /** 断言一帧是发往 nodeId 的 SDO 帧且 8 字节数据段逐字节等于 expected */
    private fun assertSdoBytes(frame: ByteArray, nodeId: Int, vararg expected: Int) {
        val (canId, sdo) = unwrap(frame)
        assertEquals("SDO 请求 CAN-ID 应为 0x600+nodeId", 0x600 + nodeId, canId)
        assertEquals("SDO 数据段固定 8 字节", 8, sdo.size)
        expected.forEachIndexed { i, exp ->
            assertEquals("SDO 数据第 $i 字节", exp, sdo[i].toInt() and 0xFF)
        }
    }

    // ── TPDO1 解析（4 字节） ─────────────────────────────────────────────────

    @Test
    fun parseEncoderTpdoFourByteLittleEndian() {
        // Node-ID 21 → TPDO1 CAN-ID = 0x195；位置 1000 = E8 03 00 00
        val tpdo = EncoderCanOpenFun.parseEncoderTpdo(
            0x195, byteArrayOf(0xE8.toByte(), 0x03, 0x00, 0x00)
        )
        assertNotNull(tpdo)
        assertEquals(21, tpdo!!.nodeId)
        assertEquals(1000L, tpdo.rawPosition)
    }

    @Test
    fun parseEncoderTpdoFullRangeU32() {
        // 最高位不得被当符号位：FF FF FF FF → 4294967295
        val tpdo = EncoderCanOpenFun.parseEncoderTpdo(
            0x19C, ByteArray(4) { 0xFF.toByte() }
        )
        assertNotNull(tpdo)
        assertEquals(28, tpdo!!.nodeId)
        assertEquals(0xFFFFFFFFL, tpdo.rawPosition)
    }

    @Test
    fun parseEncoderTpdoRejectsNonEncoderNodes() {
        val data = byteArrayOf(0x01, 0x00, 0x00, 0x00)
        // 伺服节点 11（0x18B）与出厂默认节点 1（0x181）都不属于编码器 21~28
        assertNull(EncoderCanOpenFun.parseEncoderTpdo(0x18B, data))
        assertNull(EncoderCanOpenFun.parseEncoderTpdo(0x181, data))
        // 越界：20（0x194）与 29（0x19D）
        assertNull(EncoderCanOpenFun.parseEncoderTpdo(0x194, data))
        assertNull(EncoderCanOpenFun.parseEncoderTpdo(0x19D, data))
    }

    @Test
    fun parseEncoderTpdoRejectsShortData() {
        assertNull(EncoderCanOpenFun.parseEncoderTpdo(0x195, byteArrayOf(0x01, 0x02, 0x03)))
    }

    // ── SDO 配置帧（手册示例逐字节核对） ──────────────────────────────────────

    @Test
    fun saveParamsFrameMatchesManualExample() {
        // 手册第 3 例：600+ID / 23 10 10 01 73 61 76 65（"save"）
        assertSdoBytes(
            EncoderCanOpenFun.buildSaveParamsFrame(21), 21,
            0x23, 0x10, 0x10, 0x01, 0x73, 0x61, 0x76, 0x65
        )
    }

    @Test
    fun setEventTimeFrameMatchesManualExample() {
        // 手册第 7 例：600+ID / 2B 00 18 05 32 00 00 00（1800-05 = 50ms）
        assertSdoBytes(
            EncoderCanOpenFun.buildSetEventTimeFrame(22, 50), 22,
            0x2B, 0x00, 0x18, 0x05, 0x32, 0x00, 0x00, 0x00
        )
        // 默认参数即 50ms（带宽预算决策）
        assertSdoBytes(
            EncoderCanOpenFun.buildSetEventTimeFrame(22), 22,
            0x2B, 0x00, 0x18, 0x05, 0x32, 0x00, 0x00, 0x00
        )
    }

    @Test
    fun setNodeIdFrameSentToOldIdWithU8Value() {
        // 手册第 10 例：改 ID 帧发往【旧 ID】（出厂 1），2F 01 30 00 [新ID]
        assertSdoBytes(
            EncoderCanOpenFun.buildSetNodeIdFrame(1, 25), 1,
            0x2F, 0x01, 0x30, 0x00, 25, 0x00, 0x00, 0x00
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun setNodeIdFrameRejectsTargetOutsideEncoderRange() {
        EncoderCanOpenFun.buildSetNodeIdFrame(1, 11)  // 11 是伺服段，禁止
    }

    @Test
    fun presetZeroFrameWritesU32Zero() {
        // 6003-00 = 0（U32，CS=0x23）
        assertSdoBytes(
            EncoderCanOpenFun.buildPresetZeroFrame(23), 23,
            0x23, 0x03, 0x60, 0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    @Test
    fun readFramesUseCs40() {
        // 读 6501（物理分辨率）与 6004（当前位置）
        assertSdoBytes(
            EncoderCanOpenFun.buildReadResolutionFrame(1), 1,
            0x40, 0x01, 0x65, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        assertSdoBytes(
            EncoderCanOpenFun.buildReadPositionFrame(24), 24,
            0x40, 0x04, 0x60, 0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    @Test
    fun manualExampleSdoReadReplyParsesTo1000() {
        // 手册第 15 例应答：580+ID / 43 04 60 00 E8 03 00 00 → 1000
        val reply = byteArrayOf(0x43, 0x04, 0x60, 0x00, 0xE8.toByte(), 0x03, 0x00, 0x00)
        assertEquals(1000L, CanOpenFun.parseSdoResponse(reply))
    }

    // ── 符号展开 ─────────────────────────────────────────────────────────────

    @Test
    fun toSignedPositionUnwrapsAroundZero() {
        val res = 4096
        // 正方向小值：不变
        assertEquals(100, EncoderCanOpenFun.toSignedPosition(100L, res))
        // 弹跳越过零点：4095 → -1，4000 → -96
        assertEquals(-1, EncoderCanOpenFun.toSignedPosition(4095L, res))
        assertEquals(-96, EncoderCanOpenFun.toSignedPosition(4000L, res))
        // 中点边界：≤res/2 不展开
        assertEquals(2048, EncoderCanOpenFun.toSignedPosition(2048L, res))
        assertEquals(-2047, EncoderCanOpenFun.toSignedPosition(2049L, res))
    }

    @Test
    fun toSignedPositionWithoutResolutionPassesThrough() {
        assertEquals(4095, EncoderCanOpenFun.toSignedPosition(4095L, 0))
        assertEquals(1000, EncoderCanOpenFun.toSignedPosition(1000L, -1))
    }

    // ── 两级滤波 ─────────────────────────────────────────────────────────────

    @Test
    fun filterSlidingMeanOverWindow() {
        val f = EncoderCanOpenFun.EncoderFilter(windowSize = 4, spikeThreshold = 1000)
        assertEquals(100f, f.feed(100), 0.001f)
        assertEquals(150f, f.feed(200), 0.001f)          // (100+200)/2
        f.feed(300)
        assertEquals(250f, f.feed(400), 0.001f)          // (100+200+300+400)/4
        // 窗口满后滑动：最旧的 100 被挤出
        assertEquals(350f, f.feed(500), 0.001f)          // (200+300+400+500)/4
    }

    @Test
    fun filterRejectsSingleSpike() {
        val f = EncoderCanOpenFun.EncoderFilter(windowSize = 4, spikeThreshold = 50)
        f.feed(100)
        f.feed(100)
        // 单帧尖峰（压种轮过残茬弹跳）：丢弃，读数维持
        assertEquals(100f, f.feed(9999), 0.001f)
        // 尖峰后恢复正常值：继续正常滑动
        assertEquals(100f, f.feed(100), 0.001f)
    }

    @Test
    fun filterAcceptsSustainedChangeAfterConsecutiveFrames() {
        val f = EncoderCanOpenFun.EncoderFilter(windowSize = 4, spikeThreshold = 50)
        f.feed(100)
        // 连续超限 = 真实快速变化（机具升降）：第 3 帧起接受并重建窗口
        f.feed(9999)                                      // 第 1 帧超限，丢弃
        f.feed(9999)                                      // 第 2 帧超限，丢弃
        assertEquals(9999f, f.feed(9999), 0.001f)         // 第 3 帧接受，窗口重建
    }

    @Test
    fun filterResetClearsState() {
        val f = EncoderCanOpenFun.EncoderFilter(windowSize = 4, spikeThreshold = 50)
        f.feed(100)
        f.reset()
        assertEquals(0f, f.mean(), 0.001f)
        // reset 后首帧无条件接受（无 lastAccepted 参照）
        assertEquals(5000f, f.feed(5000), 0.001f)
    }

    @Test
    fun spikeThresholdScalesWithResolution() {
        assertEquals(204, EncoderCanOpenFun.spikeThresholdFor(4096))   // 5%
        assertEquals(1638, EncoderCanOpenFun.spikeThresholdFor(32768))
        assertEquals(16, EncoderCanOpenFun.spikeThresholdFor(100))     // 下限
        assertEquals(
            EncoderCanOpenFun.DEFAULT_SPIKE_THRESHOLD,
            EncoderCanOpenFun.spikeThresholdFor(0)                     // 未知分辨率
        )
    }

    // ── 共用最小二乘拟合 ──────────────────────────────────────────────────────

    @Test
    fun linearFitRecoversKnownLine() {
        // depth = 0.05 * pos + 10
        val points = listOf(
            Pair(0, 10f), Pair(1000, 60f), Pair(2000, 110f), Pair(3000, 160f)
        )
        val (a, b) = buildLinearFit(points)!!
        assertEquals(0.05f, a, 1e-4f)
        assertEquals(10f, b, 1e-2f)
    }

    @Test
    fun linearFitRequiresTwoValidPoints() {
        assertNull(buildLinearFit(emptyList()))
        assertNull(buildLinearFit(listOf(Pair(100, 20f))))
        // 深度 ≤0 的点视为未填写
        assertNull(buildLinearFit(listOf(Pair(100, 20f), Pair(200, 0f))))
    }

    @Test
    fun linearFitRejectsDegenerateSameX() {
        // 所有点同一编码值：分母为 0，拟合无解
        assertNull(buildLinearFit(listOf(Pair(100, 20f), Pair(100, 40f))))
    }
}
