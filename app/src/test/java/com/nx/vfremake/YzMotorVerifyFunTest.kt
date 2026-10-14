package com.nx.vfremake

import com.nx.vfremake.coroutine.YzMotorVerifyRunner
import com.nx.vfremake.coroutine.YzVerifyIo
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.YzMotorVerifyFun
import com.nx.vfremake.funClass.YzMotorVerifyFun.Group
import com.nx.vfremake.funClass.YzMotorVerifyFun.SdoOutcome
import com.nx.vfremake.funClass.YzMotorVerifyFun.Verdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YzMotorVerifyFunTest {

    // ── SDO 应答分类 ────────────────────────────────────────────────────

    @Test
    fun classifyCoversEveryReplyShape() {
        assertTrue(YzMotorVerifyFun.classify(null) is SdoOutcome.Timeout)
        val v4 = YzMotorVerifyFun.classify(readReply(0x6064, 0, -2000L, 4)) as SdoOutcome.Value
        assertEquals(4, v4.length)
        assertEquals(0x43, v4.cs)
        assertEquals(-2000, v4.value.toInt())
        val v2 = YzMotorVerifyFun.classify(readReply(0x606C, 0, 0x1234L, 2)) as SdoOutcome.Value
        assertEquals(2, v2.length)
        assertEquals(0x4B, v2.cs)
        val v1 = YzMotorVerifyFun.classify(readReply(0x6060, 0, 1L, 1)) as SdoOutcome.Value
        assertEquals(1, v1.length)
        assertTrue(YzMotorVerifyFun.classify(writeAck(0x6040, 0)) is SdoOutcome.WriteAck)
        val abort = YzMotorVerifyFun.classify(abortReply(0x6084, 0, 0x06020000L)) as SdoOutcome.Abort
        assertEquals(0x06020000L, abort.code)
        assertTrue(YzMotorVerifyFun.classify(byteArrayOf(1, 2, 3)) is SdoOutcome.Invalid)
    }

    @Test
    fun unknownObjectVerdictThreeWay() {
        assertEquals(Verdict.PASS, YzMotorVerifyFun.unknownObjectVerdict(SdoOutcome.Abort(0x06020000L)).first)
        assertEquals(Verdict.DIFF, YzMotorVerifyFun.unknownObjectVerdict(SdoOutcome.Timeout).first)
        assertEquals(Verdict.DIFF, YzMotorVerifyFun.unknownObjectVerdict(SdoOutcome.Value(5L, 4, 0x43)).first)
        assertEquals(Verdict.DIFF, YzMotorVerifyFun.unknownObjectVerdict(SdoOutcome.WriteAck).first)
    }

    // ── EDS 期望表 ──────────────────────────────────────────────────────

    @Test
    fun edsDefaultsFollowNodeIdAndMatchEdsFile() {
        assertEquals(0x180L + 11, YzMotorVerifyFun.edsExpected(0x1800, 1, 11))
        assertEquals(0x380L + 13, YzMotorVerifyFun.edsExpected(0x1802, 1, 13))
        assertEquals(0x200L + 11, YzMotorVerifyFun.edsExpected(0x1400, 1, 11))
        assertEquals(100L, YzMotorVerifyFun.edsExpected(0x1800, 5, 11))
        assertEquals(0L, YzMotorVerifyFun.edsExpected(0x1801, 5, 11))
        assertEquals(0x60640020L, YzMotorVerifyFun.edsExpected(0x1A00, 1, 11))
        assertEquals(0x60410010L, YzMotorVerifyFun.edsExpected(0x1A00, 2, 11))
        assertEquals(0x606C0020L, YzMotorVerifyFun.edsExpected(0x1A02, 2, 11))
        assertEquals(32768L, YzMotorVerifyFun.edsExpected(0x260A, 0, 11))
        assertEquals(8192L, YzMotorVerifyFun.edsExpected(0x260B, 0, 11))
        assertEquals(7003L, YzMotorVerifyFun.edsExpected(0x261D, 0, 11))
        assertEquals(0x20192L, YzMotorVerifyFun.edsExpected(0x1000, 0, 11))
        assertNull("1001h 在 EDS 中无默认值", YzMotorVerifyFun.edsExpected(0x1001, 0, 11))
        // 每个有默认值的对象都必须在 EDS 目录里（否则名称/类型对不上）
        YzMotorVerifyFun.EDS_DEFAULTS.keys.forEach { k ->
            assertNotNull("EDS_OBJECTS 缺 %04X-%02X".format(k shr 8, k and 0xFF), YzMotorVerifyFun.edsObject(k shr 8, k and 0xFF))
        }
    }

    @Test
    fun decodePdoMappingReadsIndexSubBits() {
        assertEquals("6064h-00 ×32bit", YzMotorVerifyFun.decodePdoMapping(0x60640020L))
        assertEquals("6041h-00 ×16bit", YzMotorVerifyFun.decodePdoMapping(0x60410010L))
        assertEquals("6060h-00 ×8bit", YzMotorVerifyFun.decodePdoMapping(0x60600008L))
    }

    @Test
    fun compareToEdsFlagsLengthMismatchAsDiff() {
        val item = YzMotorVerifyFun.compareToEds(
            Group.A, "x", 0x606C, 0, 11, SdoOutcome.Value(100L, 2, 0x4B)
        )
        assertEquals(Verdict.DIFF, item.verdict)
        val ok = YzMotorVerifyFun.compareToEds(
            Group.A, "y", 0x1017, 0, 11, SdoOutcome.Value(1000L, 2, 0x4B)
        )
        assertEquals(Verdict.PASS, ok.verdict)
        val changed = YzMotorVerifyFun.compareToEds(
            Group.A, "z", 0x1017, 0, 11, SdoOutcome.Value(500L, 2, 0x4B), Verdict.INFO
        )
        assertEquals(Verdict.INFO, changed.verdict)
    }

    // ── DS402 逐步判定 ──────────────────────────────────────────────────

    @Test
    fun judgeStepFieldStatusWordFailsEveryStandardState() {
        // 2026-09 实机值：bit0/bit1 恒 0，任何一步都不该匹配
        YzMotorVerifyFun.DS402_STEPS.forEach { step ->
            assertEquals(Verdict.DIFF, YzMotorVerifyFun.judgeStep(step, 0x0434).first)
        }
        // 标准值逐步 PASS
        assertEquals(Verdict.PASS, YzMotorVerifyFun.judgeStep(YzMotorVerifyFun.DS402_STEPS[0], 0x0421).first)
        assertEquals(Verdict.PASS, YzMotorVerifyFun.judgeStep(YzMotorVerifyFun.DS402_STEPS[1], 0x0423).first)
        assertEquals(Verdict.PASS, YzMotorVerifyFun.judgeStep(YzMotorVerifyFun.DS402_STEPS[2], 0x0427).first)
        assertEquals(Verdict.PASS, YzMotorVerifyFun.judgeStep(YzMotorVerifyFun.DS402_STEPS[4], 0x0421).first)
    }

    // ── 速度单位 / RPDO 帧 / 行程规划 ───────────────────────────────────

    @Test
    fun velocityUnitEstimateRecognisesRpm() {
        // 100rpm ≈ 54613 脉冲/s
        val text = YzMotorVerifyFun.velocityUnitEstimate(100, 54613, 1000)
        assertTrue(text, text.contains("606Ch ≈ rpm"))
        val notRpm = YzMotorVerifyFun.velocityUnitEstimate(5000, 54613, 1000)
        assertTrue(notRpm, notRpm.contains("不是 rpm"))
    }

    @Test
    fun rpdo1FrameLayoutMatchesEdsMapping() {
        val frame = YzMotorVerifyFun.buildRpdo1Frame(11, 0x003F, 1, -2000)
        val (canId, data) = CanOpenFun.unwrapCanFrame(frame)!!
        assertEquals(0x20B, canId)
        assertEquals(7, data.size)
        assertEquals(0x3F, data[0].toInt() and 0xFF)
        assertEquals(0x00, data[1].toInt() and 0xFF)
        assertEquals(0x01, data[2].toInt() and 0xFF)
        val target = (data[3].toInt() and 0xFF) or ((data[4].toInt() and 0xFF) shl 8) or
            ((data[5].toInt() and 0xFF) shl 16) or ((data[6].toInt() and 0xFF) shl 24)
        assertEquals(-2000, target)
    }

    @Test
    fun planTravelKeepsTwoTravelsInsideLimits() {
        // 未标定：正向
        assertEquals(1 to 16554, YzMotorVerifyFun.planTravel(10000, 6554, false, 0, 0))
        // limitMin < limitMax：优先朝 limitMin（负向）
        assertEquals(-1 to 13446, YzMotorVerifyFun.planTravel(20000, 6554, true, 0, 100000)!!)
        // 靠近 limitMin，负向余量不足 → 换正向
        assertEquals(1 to 8554, YzMotorVerifyFun.planTravel(2000, 6554, true, 0, 100000)!!)
        // 两头都不够 → 拒绝
        assertNull(YzMotorVerifyFun.planTravel(5000, 6554, true, 0, 10000))
    }

    // ── 报告 ────────────────────────────────────────────────────────────

    @Test
    fun reportTextHasHeaderGroupsAndSummary() {
        val report = YzMotorVerifyFun.VerifyReport(
            nodeId = 11, motorLabel = "M1", startedAtMs = 0L, appVersion = "3.1.0",
            items = listOf(
                YzMotorVerifyFun.CheckItem(Group.A, "A1", "身份", Verdict.PASS, "817", "817"),
                YzMotorVerifyFun.CheckItem(Group.A, "A2", "1001h", Verdict.DIFF, "可读", "超时", "Mandatory 无应答"),
                YzMotorVerifyFun.CheckItem(Group.B, "B1", "模式", Verdict.FAIL, null, "超时")
            )
        )
        val text = report.toText()
        assertTrue(text.contains("Node 11"))
        assertTrue(text.contains("== A 只读核对"))
        assertTrue(text.contains("== B 状态机核对"))
        assertTrue(text.contains("≠ [A2] 1001h"))
        assertTrue(text.contains("说明: Mandatory 无应答"))
        assertTrue(text.contains("符合 1，不符 1，失败 1"))
        // 不符项排在符合项前面
        assertTrue(text.indexOf("[A2]") < text.indexOf("[A1]"))
    }

    // ── Runner A 组：脚本化假 IO 复现 2026-09 现场固件 ─────────────────

    @Test
    fun groupAReproducesFieldFindingsAgainstScriptedFirmware() = runBlocking {
        val node = 11
        val io = ScriptedIo(node).apply {
            read[key(0x1000, 0)] = { readReply(0x1000, 0, 0x20192L, 4) }
            read[key(0x1018, 1)] = { readReply(0x1018, 1, 817L, 4) }
            read[key(0x1018, 2)] = { readReply(0x1018, 2, 1L, 4) }
            read[key(0x1018, 3)] = { readReply(0x1018, 3, 256L, 4) }
            read[key(0x1018, 4)] = { readReply(0x1018, 4, 77L, 4) }
            read[key(0x1001, 0)] = { null }                                   // Mandatory 却超时
            read[key(0x606C, 0)] = { readReply(0x606C, 0, 0L, 2) }            // EDS 4B，固件 2B
            read[key(0x6078, 0)] = { readReply(0x6078, 0, 12L, 2) }
            read[key(0x6084, 0)] = { null }                                   // 静默忽略
            read[key(0x1008, 0)] = { abortReply(0x1008, 0, 0x06020000L) }
            read[key(0x6061, 0)] = { null }
            read[key(0x6041, 0)] = { readReply(0x6041, 0, 0x0434L, 2) }       // bit0/bit1 恒 0
            read[key(0x6040, 0)] = { readReply(0x6040, 0, 0x0006L, 2) }
            read[key(0x2601, 0)] = { readReply(0x2601, 0, 7L, 2) }
            read[key(0x6060, 0)] = { readReply(0x6060, 0, 1L, 1) }
            read[key(0x6064, 0)] = { readReply(0x6064, 0, -9570507L, 4) }
            read[key(0x1017, 0)] = { readReply(0x1017, 0, 1000L, 2) }
            read[key(0x260C, 0)] = { readReply(0x260C, 0, 0L, 4) }
            read[key(0x260E, 0)] = { readReply(0x260E, 0, 0L, 2) }
            read[key(0x2612, 0)] = { readReply(0x2612, 0, 31L, 2) }
            read[key(0x2613, 0)] = { readReply(0x2613, 0, 0L, 2) }
            // 其余对象：按 EDS 默认值应答（TPDO/RPDO/厂商对象）
            YzMotorVerifyFun.EDS_DEFAULTS.forEach { (k, d) ->
                val idx = k shr 8
                val sub = k and 0xFF
                if (!read.containsKey(k)) {
                    val obj = YzMotorVerifyFun.edsObject(idx, sub)!!
                    read[k] = { readReply(idx, sub, d.expectedFor(node)!!, obj.type.byteLength) }
                }
            }
            tpdo[1] = LongArray(30) { 1000L + it * 100 } to byteArrayOf(0x35, 0xF8.toByte(), 0x6D, 0xFF.toByte(), 0x34, 0x04)
            tpdo[3] = LongArray(30) { 1000L + it * 100 } to byteArrayOf(0x78, 0xF8.toByte(), 0x6D, 0xFF.toByte())   // 4B！
            heartbeat = LongArray(3) { 1000L + it * 1000 }
            hbState = 0x05
        }
        val runner = YzMotorVerifyRunner(
            io, node, ServoCalibration(0, node), 500, 10000,
            YzMotorVerifyFun.VerifyOptions(listenMs = 10L)
        )
        runner.runGroupA()
        val byId = runner.items.associateBy { it.id }
        assertEquals(Verdict.PASS, byId.getValue("A1").verdict)
        assertEquals(Verdict.DIFF, byId.getValue("A2").verdict)
        assertEquals(Verdict.DIFF, byId.getValue("A3").verdict)
        assertEquals(Verdict.PASS, byId.getValue("A4").verdict)
        assertEquals(Verdict.DIFF, byId.getValue("A5").verdict)
        assertEquals(Verdict.PASS, byId.getValue("A6a").verdict)
        assertEquals(Verdict.DIFF, byId.getValue("A6b").verdict)
        assertEquals(Verdict.DIFF, byId.getValue("A7").verdict)
        assertEquals(Verdict.PASS, byId.getValue("A8").verdict)   // 6060h=1 等于 EDS 默认
        assertEquals(Verdict.PASS, byId.getValue("A9.1").verdict)
        assertEquals(Verdict.PASS, byId.getValue("A10a").verdict)
        assertEquals(Verdict.PASS, byId.getValue("A12a").verdict)
        assertEquals(Verdict.PASS, byId.getValue("A14a").verdict)
        assertEquals("TPDO3 4 字节应判 DIFF", Verdict.DIFF, byId.getValue("A14b").verdict)
        assertEquals(Verdict.PASS, byId.getValue("A14c").verdict)
        assertEquals(Verdict.PASS, byId.getValue("A14e").verdict)
        assertTrue(runner.items.none { it.verdict == Verdict.FAIL })
    }

    @Test
    fun groupBReportsNonStandardStatusWordAndSilentDeceleration() = runBlocking {
        val node = 11
        val io = ScriptedIo(node).apply {
            read[key(0x6041, 0)] = { readReply(0x6041, 0, 0x0434L, 2) }
            read[key(0x6040, 0)] = { readReply(0x6040, 0, lastControlword, 2) }
            read[key(0x6060, 0)] = { readReply(0x6060, 0, 1L, 1) }
            read[key(0x6083, 0)] = { readReply(0x6083, 0, 2000L, 4) }
            write[key(0x6084, 0)] = { null }                                 // 静默丢弃
        }
        val runner = YzMotorVerifyRunner(
            io, node, ServoCalibration(0, node), 500, 10000, YzMotorVerifyFun.VerifyOptions()
        )
        runner.runGroupB()
        val byId = runner.items.associateBy { it.id }
        assertEquals(Verdict.SKIP, byId.getValue("B0").verdict)
        assertEquals(Verdict.PASS, byId.getValue("B1").verdict)
        (1..5).forEach { assertEquals(Verdict.DIFF, byId.getValue("B2.$it").verdict) }
        assertEquals(Verdict.DIFF, byId.getValue("B3").verdict)
        // 结束停在 Shutdown
        assertEquals(0x0006L, io.lastControlword)
    }

    // ── 测试工具 ────────────────────────────────────────────────────────

    private fun key(index: Int, sub: Int) = (index shl 8) or sub

    private fun readReply(index: Int, sub: Int, value: Long, len: Int): ByteArray {
        val cs = when (len) { 1 -> 0x4F; 2 -> 0x4B; else -> 0x43 }
        val d = ByteArray(8)
        d[0] = cs.toByte(); d[1] = (index and 0xFF).toByte(); d[2] = ((index shr 8) and 0xFF).toByte(); d[3] = sub.toByte()
        for (i in 0 until len) d[4 + i] = ((value shr (8 * i)) and 0xFF).toByte()
        return d
    }

    private fun writeAck(index: Int, sub: Int): ByteArray =
        byteArrayOf(0x60, (index and 0xFF).toByte(), ((index shr 8) and 0xFF).toByte(), sub.toByte(), 0, 0, 0, 0)

    private fun abortReply(index: Int, sub: Int, code: Long): ByteArray {
        val d = ByteArray(8)
        d[0] = 0x80.toByte(); d[1] = (index and 0xFF).toByte(); d[2] = ((index shr 8) and 0xFF).toByte(); d[3] = sub.toByte()
        for (i in 0 until 4) d[4 + i] = ((code shr (8 * i)) and 0xFF).toByte()
        return d
    }

    /**
     * 脚本化假 IO：按 (index, sub) 决定读/写应答；未脚本化的读 → abort 0x06020000，写 → ACK。
     * 时间用手工推进的计数器，sleep 不真等。
     */
    private inner class ScriptedIo(private val node: Int) : YzVerifyIo {
        val read = HashMap<Int, () -> ByteArray?>()
        val write = HashMap<Int, () -> ByteArray?>()
        val tpdo = HashMap<Int, Pair<LongArray, ByteArray>>()
        var heartbeat = LongArray(0)
        var hbState: Int? = null
        var lastControlword = 0L
        private var clock = 0L

        override suspend fun sdo(frame: ByteArray, replyCanId: Int, timeoutMs: Long): ByteArray? {
            val (canId, data) = CanOpenFun.unwrapCanFrame(frame)!!
            assertEquals(0x600 + node, canId)
            val cs = data[0].toInt() and 0xFF
            val index = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
            val sub = data[3].toInt() and 0xFF
            val k = key(index, sub)
            clock += 20
            return if (cs == 0x40) {
                if (read.containsKey(k)) read.getValue(k).invoke() else abortReply(index, sub, 0x06020000L)
            } else {
                if (index == 0x6040) {
                    lastControlword = (data[4].toLong() and 0xFF) or ((data[5].toLong() and 0xFF) shl 8)
                }
                if (write.containsKey(k)) write.getValue(k).invoke() else writeAck(index, sub)
            }
        }

        override suspend fun sendRaw(frame: ByteArray) { clock += 20 }
        override fun listenStart() {}
        override fun listenStop() {}
        override fun tpdoTimestamps(nodeId: Int, pdo: Int): LongArray = tpdo[pdo]?.first ?: LongArray(0)
        override fun tpdoPayload(nodeId: Int, pdo: Int): ByteArray? = tpdo[pdo]?.second
        override fun tpdoLengths(nodeId: Int, pdo: Int): Set<Int> = tpdo[pdo]?.let { setOf(it.second.size) } ?: emptySet()
        override fun heartbeatTimestamps(nodeId: Int): LongArray = heartbeat
        override fun heartbeatState(nodeId: Int): Int? = hbState
        override fun bootupCount(nodeId: Int): Int = 0
        override suspend fun sleep(ms: Long) { clock += ms }
        override fun nowMs(): Long = clock
    }
}
