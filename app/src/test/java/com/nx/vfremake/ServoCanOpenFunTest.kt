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

    @Test
    fun tpdoChannelsMatchEdsDefaults() {
        // EDS 出厂：TPDO1/TPDO3 = 100ms 周期上报，TPDO2/TPDO4 = 0（不上报）
        assertEquals(4, ServoCanOpenFun.TPDO_CHANNELS.size)
        assertEquals(100, ServoCanOpenFun.tpdoChannel(1).defaultEventMs)
        assertEquals(0, ServoCanOpenFun.tpdoChannel(2).defaultEventMs)
        assertEquals(100, ServoCanOpenFun.tpdoChannel(3).defaultEventMs)
        assertEquals(0, ServoCanOpenFun.tpdoChannel(4).defaultEventMs)
        assertEquals(0x1802, ServoCanOpenFun.tpdoChannel(3).paramIndex)
        // TPDO1 = 6064h(4B) + 6041h(2B)，与 parseTpdo1 的 6 字节解析一致
        assertEquals(6, ServoCanOpenFun.tpdoChannel(1).dataBytes)
        assertTrue(runCatching { ServoCanOpenFun.tpdoChannel(5) }.isFailure)
    }

    @Test
    fun setTpdoEventTimerFrameMatchesCanopenLayout() {
        // Node 11 / TPDO1 / 20ms → SDO 写 1800-05，2 字节（CS=0x2B）
        val frame = ServoCanOpenFun.buildSetTpdoEventTimerFrame(11, 1, 20)
        val unwrapped = CanOpenFun.unwrapCanFrame(frame)
        assertNotNull(unwrapped)
        assertEquals(0x60B, unwrapped!!.first)
        val sdo = unwrapped.second
        assertEquals(8, sdo.size)
        assertEquals(0x2B, sdo[0].toInt() and 0xFF)
        assertEquals(0x00, sdo[1].toInt() and 0xFF)   // index 低字节
        assertEquals(0x18, sdo[2].toInt() and 0xFF)   // index 高字节
        assertEquals(0x05, sdo[3].toInt() and 0xFF)   // 子索引 = Event Timer
        assertEquals(20, sdo[4].toInt() and 0xFF)
        assertEquals(0, sdo[5].toInt() and 0xFF)
    }

    @Test
    fun tpdoEventTimerRejectsOutOfRangeValues() {
        assertTrue(runCatching { ServoCanOpenFun.buildSetTpdoEventTimerFrame(11, 1, -1) }.isFailure)
        assertTrue(runCatching { ServoCanOpenFun.buildSetTpdoEventTimerFrame(11, 1, 70000) }.isFailure)
        // 0 = 关闭该通道周期上报，是合法值
        assertFalse(runCatching { ServoCanOpenFun.buildSetTpdoEventTimerFrame(11, 3, 0) }.isFailure)
    }

    @Test
    fun saveParametersFrameTargetsManufacturerSaveFlag() {
        val frame = ServoCanOpenFun.buildSaveParametersFrame(12)
        val sdo = CanOpenFun.unwrapCanFrame(frame)!!.second
        assertEquals(0x2B, sdo[0].toInt() and 0xFF)   // 2 字节写
        assertEquals(0x14, sdo[1].toInt() and 0xFF)   // 2614h 低字节
        assertEquals(0x26, sdo[2].toInt() and 0xFF)   // 2614h 高字节
        assertEquals(1, sdo[4].toInt() and 0xFF)      // 写 1 触发保存
        // 通用控制台仍然禁止写 2614h，只有本文件的专用保存路径可发这一帧
        val saveFlag = ServoCanOpenFun.EDS_OBJECTS.first { it.key == "saveFlag" }
        assertEquals(ServoCanOpenFun.WritePolicy.BLOCKED, saveFlag.writePolicy)
        assertTrue(runCatching { ServoCanOpenFun.buildWriteObjectFrame(12, saveFlag, 1L) }.isFailure)
    }

    @Test
    fun transmissionTypeDecidesWhetherEventTimerApplies() {
        assertTrue(ServoCanOpenFun.isAsyncTransmissionType(255L))
        assertTrue(ServoCanOpenFun.isAsyncTransmissionType(254L))
        assertFalse(ServoCanOpenFun.isAsyncTransmissionType(1L))
        assertFalse(ServoCanOpenFun.isAsyncTransmissionType(0L))
    }

    @Test
    fun bridgeLoadEstimateMatchesHandCalculation() {
        // 8 台伺服 TPDO1（6B 数据 → 串口 12B）每 20ms 一帧：400 帧/s、4800 B/s
        val load = ServoCanOpenFun.estimateBridgeLoad(
            listOf(ServoCanOpenFun.TpdoStream(nodeCount = 8, dataBytes = 6, periodMs = 20))
        )
        assertEquals(400.0, load.framesPerSec, 0.01)
        assertEquals(4800.0, load.bytesPerSec, 0.01)
        assertEquals(4800.0 / 11520.0, load.utilization, 1e-6)

        // 周期 0（关闭上报）与 0 节点的流不计入
        val idle = ServoCanOpenFun.estimateBridgeLoad(
            listOf(
                ServoCanOpenFun.TpdoStream(8, 6, 0),
                ServoCanOpenFun.TpdoStream(0, 6, 20)
            )
        )
        assertEquals(0.0, idle.bytesPerSec, 0.01)
        assertNull(ServoCanOpenFun.bridgeLoadWarning(idle))
    }

    @Test
    fun bridgeLoadWarnsBeforeSaturatingTheUartBridge() {
        // 出厂 TPDO1+TPDO3 各 100ms × 8 台仍有余量
        val stock = ServoCanOpenFun.estimateBridgeLoad(
            ServoCanOpenFun.TPDO_CHANNELS.map {
                ServoCanOpenFun.TpdoStream(8, it.dataBytes, it.defaultEventMs)
            }
        )
        assertTrue(stock.utilization < 0.35)

        // 两条流都拉到 10ms × 8 台会超出 115200 串口承载
        val overloaded = ServoCanOpenFun.estimateBridgeLoad(
            ServoCanOpenFun.TPDO_CHANNELS.map {
                ServoCanOpenFun.TpdoStream(8, it.dataBytes, if (it.defaultEventMs > 0) 10 else 0)
            }
        )
        assertTrue(overloaded.utilization >= 0.85)
        assertTrue(ServoCanOpenFun.bridgeLoadWarning(overloaded)!!.startsWith("✗"))
    }

    @Test
    fun tpdoMappingLengthsMatchEds1A00Group() {
        // 1A00/1A01/1A03 = 6064h(32bit) + 6041h(16bit) → 6 字节
        // 1A02          = 6064h(32bit) + 606Ch(32bit) → 8 字节
        // 三个 sub0 的 Number of Entries 都是 2——TPDO4 不是单条映射
        assertEquals(6, ServoCanOpenFun.tpdoChannel(1).dataBytes)
        assertEquals(6, ServoCanOpenFun.tpdoChannel(2).dataBytes)
        assertEquals(8, ServoCanOpenFun.tpdoChannel(3).dataBytes)
        assertEquals(6, ServoCanOpenFun.tpdoChannel(4).dataBytes)
    }

    @Test
    fun tpdoCobIdBasesMatchEdsNodeIdFormulas() {
        // EDS DefaultValue=$NODEID+0X180 / 0X280 / 0X380 / 0X480
        assertEquals(0x180, ServoCanOpenFun.tpdoChannel(1).cobIdBase)
        assertEquals(0x280, ServoCanOpenFun.tpdoChannel(2).cobIdBase)
        assertEquals(0x380, ServoCanOpenFun.tpdoChannel(3).cobIdBase)
        assertEquals(0x480, ServoCanOpenFun.tpdoChannel(4).cobIdBase)
        // Node 11 的 TPDO3 报文 ID = 0x38B，与 CanReceiveCoroutine 的 0x381..0x3FF 段吻合
        assertTrue(ServoCanOpenFun.cobIdMatchesDefault(0x38BL, 11, 3))
        assertFalse(ServoCanOpenFun.cobIdMatchesDefault(0x38CL, 11, 3))
        // Bit31 置位（已禁用）不影响报文 ID 判定
        assertTrue(ServoCanOpenFun.cobIdMatchesDefault(0x8000038BL, 11, 3))
    }

    @Test
    fun pdoValidBitToggleKeepsLowBitsIntact() {
        val enabled = 0x0000038BL
        val disabled = ServoCanOpenFun.tpdoCobIdWithEnabled(enabled, false)
        assertEquals(0x8000038BL, disabled)
        assertFalse(ServoCanOpenFun.isPdoEnabled(disabled))
        assertEquals(0x38B, ServoCanOpenFun.cobIdCanId(disabled))

        val reEnabled = ServoCanOpenFun.tpdoCobIdWithEnabled(disabled, true)
        assertEquals(enabled, reEnabled)
        assertTrue(ServoCanOpenFun.isPdoEnabled(reEnabled))

        // 幂等：重复关闭/开启不会污染其它位（如 Bit30 RTR 禁止位）
        assertEquals(disabled, ServoCanOpenFun.tpdoCobIdWithEnabled(disabled, false))
        assertEquals(0xC000038BL, ServoCanOpenFun.tpdoCobIdWithEnabled(0x4000038BL, false))
        assertEquals(0x4000038BL, ServoCanOpenFun.tpdoCobIdWithEnabled(0xC000038BL, true))
    }

    @Test
    fun setTpdoCobIdFrameMatchesCanopenLayout() {
        // Node 11 / TPDO3 禁用 → SDO 写 1802-01 = 0x8000038B，4 字节（CS=0x23）
        val frame = ServoCanOpenFun.buildSetTpdoCobIdFrame(11, 3, 0x8000038BL)
        val unwrapped = CanOpenFun.unwrapCanFrame(frame)
        assertNotNull(unwrapped)
        assertEquals(0x60B, unwrapped!!.first)
        val sdo = unwrapped.second
        assertEquals(0x23, sdo[0].toInt() and 0xFF)
        assertEquals(0x02, sdo[1].toInt() and 0xFF)   // 1802h 低字节
        assertEquals(0x18, sdo[2].toInt() and 0xFF)   // 1802h 高字节
        assertEquals(0x01, sdo[3].toInt() and 0xFF)   // 子索引 = COB-ID
        assertEquals(0x8B, sdo[4].toInt() and 0xFF)   // 小端
        assertEquals(0x03, sdo[5].toInt() and 0xFF)
        assertEquals(0x00, sdo[6].toInt() and 0xFF)
        assertEquals(0x80, sdo[7].toInt() and 0xFF)   // Bit31 = PDO 无效
    }

    @Test
    fun pdoStateAbortCodesAreExplained() {
        // 改 1800h 组通信参数被拒时最常见的两个中止码必须有可读解释
        assertFalse(ServoCanOpenFun.abortDescription(0x08000022L).contains("未知"))
        assertFalse(ServoCanOpenFun.abortDescription(0x08000021L).contains("未知"))
        assertFalse(ServoCanOpenFun.abortDescription(0x06070012L).contains("未知"))
        assertEquals("未知中止码", ServoCanOpenFun.abortDescription(0x0BADC0DEL))
    }

    /** 造一串到达时刻：起点 1000ms，间隔按 [intervals] 依次累加。 */
    private fun arrivals(vararg intervals: Long): LongArray {
        val out = ArrayList<Long>(intervals.size + 1)
        var t = 1000L
        out.add(t)
        intervals.forEach { t += it; out.add(t) }
        return out.toLongArray()
    }

    private fun steady(periodMs: Long, count: Int): LongArray =
        arrivals(*LongArray(count) { periodMs })

    @Test
    fun timingAnalysisComputesIntervalStatistics() {
        val report = ServoCanOpenFun.analyzeTpdoTiming(arrivals(20, 20, 20, 20), 20)
        assertNotNull(report)
        assertEquals(5, report!!.frameCount)
        assertEquals(4, report.intervalCount)
        assertEquals(20.0, report.meanMs, 0.001)
        assertEquals(20L, report.minMs)
        assertEquals(20L, report.maxMs)
        assertEquals(0.0, report.jitterMs, 0.001)
        assertEquals(0, report.droppedEstimate)
        assertEquals(80L, report.windowMs)

        // 少于 2 帧构不成间隔
        assertNull(ServoCanOpenFun.analyzeTpdoTiming(longArrayOf(1000L), 20))
        assertNull(ServoCanOpenFun.analyzeTpdoTiming(LongArray(0), 20))
    }

    @Test
    fun droppedFramesInferredFromOversizedGaps() {
        // 20ms 周期里出现一个 60ms 空档 = 中间少收了 2 帧
        val report = ServoCanOpenFun.analyzeTpdoTiming(arrivals(20, 60, 20, 20), 20)
        assertNotNull(report)
        assertEquals(2, report!!.droppedEstimate)
        assertEquals(60L, report.maxMs)
        // 轻微超时（1.2 倍）不算漏收，否则正常抖动会被误判成丢帧
        assertEquals(0, ServoCanOpenFun.analyzeTpdoTiming(arrivals(20, 24, 20, 22), 20)!!.droppedEstimate)
    }

    @Test
    fun verifyPassesWhenObjectValueAndMeasuredRateBothMatch() {
        val result = ServoCanOpenFun.verifyTpdoTiming(
            nodeId = 11, pdo = 1, expectedMs = 20,
            storedMs = 20L, transmissionType = 255L, cobIdValue = 0x18BL,
            timestampsMs = steady(20, 100)
        )
        assertEquals(ServoCanOpenFun.TpdoVerifyVerdict.PASS, result.verdict)
        assertTrue(result.passed)
        assertEquals(20, result.expectedMs)
    }

    @Test
    fun verifyCatchesObjectValueThatNeverTookTheCommandedValue() {
        // 下发 20，设备里还是 100 —— 写入根本没落地
        val result = ServoCanOpenFun.verifyTpdoTiming(
            nodeId = 11, pdo = 1, expectedMs = 20,
            storedMs = 100L, transmissionType = 255L, cobIdValue = 0x18BL,
            timestampsMs = steady(100, 50)
        )
        assertEquals(ServoCanOpenFun.TpdoVerifyVerdict.VALUE_MISMATCH, result.verdict)
        assertTrue(result.message.contains("100ms"))
    }

    @Test
    fun verifyCatchesStoredValueThatCannotTakeEffect() {
        // 对象值对，但 COB-ID Bit31=1：这条 PDO 压根不发
        val disabled = ServoCanOpenFun.verifyTpdoTiming(
            nodeId = 11, pdo = 3, expectedMs = 20,
            storedMs = 20L, transmissionType = 255L, cobIdValue = 0x8000038BL,
            timestampsMs = LongArray(0)
        )
        assertEquals(ServoCanOpenFun.TpdoVerifyVerdict.NOT_EFFECTIVE, disabled.verdict)

        // 对象值对，但传输类型是同步：上报由 SYNC 驱动，事件周期被忽略
        val sync = ServoCanOpenFun.verifyTpdoTiming(
            nodeId = 11, pdo = 1, expectedMs = 20,
            storedMs = 20L, transmissionType = 1L, cobIdValue = 0x18BL,
            timestampsMs = steady(20, 100)
        )
        assertEquals(ServoCanOpenFun.TpdoVerifyVerdict.NOT_EFFECTIVE, sync.verdict)
    }

    @Test
    fun verifyCatchesDeviceIgnoringTheCommandedRate() {
        // 对象值写成 20 了，设备实际还在按 100ms 发 —— 只做 SDO 回读永远发现不了
        val result = ServoCanOpenFun.verifyTpdoTiming(
            nodeId = 11, pdo = 1, expectedMs = 20,
            storedMs = 20L, transmissionType = 255L, cobIdValue = 0x18BL,
            timestampsMs = steady(100, 50)
        )
        assertEquals(ServoCanOpenFun.TpdoVerifyVerdict.RATE_MISMATCH, result.verdict)
        assertEquals(100.0, result.report!!.meanMs, 0.001)
    }

    @Test
    fun verifyToleratesRealisticBridgeJitterAt20ms() {
        // 20ms 档的容差下限是 ±3ms：19/21/22/18 这种正常抖动必须判合格
        val result = ServoCanOpenFun.verifyTpdoTiming(
            nodeId = 11, pdo = 1, expectedMs = 20,
            storedMs = 20L, transmissionType = 255L, cobIdValue = 0x18BL,
            timestampsMs = arrivals(19, 21, 22, 18, 20, 21, 19, 20, 22, 18)
        )
        assertEquals(ServoCanOpenFun.TpdoVerifyVerdict.PASS, result.verdict)
        // 20ms 及以上按 ±20% 比例走；15ms 以下比例太小，由 ±3ms 绝对下限兜底
        assertEquals(4.0, ServoCanOpenFun.tpdoVerifyToleranceMs(20), 0.001)
        assertEquals(20.0, ServoCanOpenFun.tpdoVerifyToleranceMs(100), 0.001)
        assertEquals(3.0, ServoCanOpenFun.tpdoVerifyToleranceMs(10), 0.001)
    }

    @Test
    fun verifyReportsLossAndJitterSeparatelyFromRate() {
        // 均值仍在容差内，但周期性大空档 → 判丢帧，指向带宽而不是周期设错
        val lossy = ServoCanOpenFun.verifyTpdoTiming(
            nodeId = 11, pdo = 1, expectedMs = 20,
            storedMs = 20L, transmissionType = 255L, cobIdValue = 0x18BL,
            timestampsMs = arrivals(20, 20, 60, 20, 20, 20, 20, 20, 20, 20, 20, 20)
        )
        assertEquals(ServoCanOpenFun.TpdoVerifyVerdict.LOSSY, lossy.verdict)
        assertEquals(2, lossy.report!!.droppedEstimate)
    }

    @Test
    fun verifyNeedsEnoughSamplesBeforeJudging() {
        val result = ServoCanOpenFun.verifyTpdoTiming(
            nodeId = 11, pdo = 1, expectedMs = 20,
            storedMs = 20L, transmissionType = 255L, cobIdValue = 0x18BL,
            timestampsMs = arrivals(20, 20)
        )
        assertEquals(ServoCanOpenFun.TpdoVerifyVerdict.NO_DATA, result.verdict)

        // 对象值都没读到时同样不下结论
        val unread = ServoCanOpenFun.verifyTpdoTiming(
            nodeId = 11, pdo = 1, expectedMs = 20,
            storedMs = null, transmissionType = 255L, cobIdValue = 0x18BL,
            timestampsMs = steady(20, 100)
        )
        assertEquals(ServoCanOpenFun.TpdoVerifyVerdict.NO_DATA, unread.verdict)
    }

    @Test
    fun verifyTreatsZeroPeriodAsSilenceExpected() {
        // 关闭成功：窗口内一帧都收不到才算合格
        val silent = ServoCanOpenFun.verifyTpdoTiming(
            nodeId = 11, pdo = 3, expectedMs = 0,
            storedMs = 0L, transmissionType = 255L, cobIdValue = 0x38BL,
            timestampsMs = LongArray(0)
        )
        assertEquals(ServoCanOpenFun.TpdoVerifyVerdict.PASS, silent.verdict)

        // 写了 0 却还在发 —— 正是"只写周期 0 关不干净"的那种设备
        val stillTalking = ServoCanOpenFun.verifyTpdoTiming(
            nodeId = 11, pdo = 3, expectedMs = 0,
            storedMs = 0L, transmissionType = 255L, cobIdValue = 0x38BL,
            timestampsMs = steady(100, 20)
        )
        assertEquals(ServoCanOpenFun.TpdoVerifyVerdict.RATE_MISMATCH, stillTalking.verdict)
    }

    @Test
    fun recommendedWindowCoversAboutSixtyFrames() {
        assertEquals(2000L, ServoCanOpenFun.recommendedVerifyWindowMs(10))   // 下限 2s
        assertEquals(3000L, ServoCanOpenFun.recommendedVerifyWindowMs(50))
        assertEquals(6000L, ServoCanOpenFun.recommendedVerifyWindowMs(100))
        assertEquals(15000L, ServoCanOpenFun.recommendedVerifyWindowMs(1000)) // 上限 15s
    }
}
