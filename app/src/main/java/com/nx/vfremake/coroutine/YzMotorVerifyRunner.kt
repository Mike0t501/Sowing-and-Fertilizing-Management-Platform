/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2026年9月13日
 * @file    :YzMotorVerifyRunner.kt
 * @brief   :YZ-MOTOR 验证流程编排：A 只读核对 / B 状态机核对 / C 运动核对，逐项产出 CheckItem
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 2026-09 电机能正常跑，但 EDS 与固件多处不符、谁是准的无法判断（见 YzMotorVerifyFun 文件头）。
 * 本 Runner 把每个疑点变成一次可复现的实机检测。全部 SDO 走 SdoReplyWaiters（全局 20ms 步调 +
 * 请求互斥），与控深回路共用同一条发送纪律；C 组会动电机，只允许在非作业、非点动时运行。
 ***********************************************************************************************************
 */

package com.nx.vfremake.coroutine

import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.SdoReplyWaiters
import com.nx.vfremake.funClass.ServoCanOpenFun
import com.nx.vfremake.funClass.TpdoArrivalMonitor
import com.nx.vfremake.funClass.YzMotorVerifyFun
import com.nx.vfremake.funClass.YzMotorVerifyFun.CheckItem
import com.nx.vfremake.funClass.YzMotorVerifyFun.Group
import com.nx.vfremake.funClass.YzMotorVerifyFun.SdoOutcome
import com.nx.vfremake.funClass.YzMotorVerifyFun.Verdict
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Runner 对外部世界的全部依赖。生产实现 [LiveYzVerifyIo]；单元测试用脚本化假实现，
 * 这样 A/B/C 的判定逻辑可以离机验证。
 */
interface YzVerifyIo {
    /** 发一帧并等 SDO 应答；超时返回 null */
    suspend fun sdo(frame: ByteArray, replyCanId: Int, timeoutMs: Long): ByteArray?
    /** 发一帧不等应答（NMT / RPDO），仍走全局串行化步调 */
    suspend fun sendRaw(frame: ByteArray)
    fun listenStart()
    fun listenStop()
    fun tpdoTimestamps(nodeId: Int, pdo: Int): LongArray
    fun tpdoPayload(nodeId: Int, pdo: Int): ByteArray?
    fun tpdoLengths(nodeId: Int, pdo: Int): Set<Int>
    fun heartbeatTimestamps(nodeId: Int): LongArray
    fun heartbeatState(nodeId: Int): Int?
    fun bootupCount(nodeId: Int): Int
    suspend fun sleep(ms: Long)
    fun nowMs(): Long
}

/** 生产实现：SdoReplyWaiters + CanOpenFun 串行化发送 + TpdoArrivalMonitor 旁听。 */
object LiveYzVerifyIo : YzVerifyIo {
    override suspend fun sdo(frame: ByteArray, replyCanId: Int, timeoutMs: Long): ByteArray? =
        SdoReplyWaiters.sendAndAwait(frame, replyCanId, timeoutMs)

    override suspend fun sendRaw(frame: ByteArray) = CanOpenFun.sendFrameSequenced(frame)
    override fun listenStart() = TpdoArrivalMonitor.start()
    override fun listenStop() = TpdoArrivalMonitor.stop()
    override fun tpdoTimestamps(nodeId: Int, pdo: Int): LongArray = TpdoArrivalMonitor.snapshot(nodeId, pdo)
    override fun tpdoPayload(nodeId: Int, pdo: Int): ByteArray? = TpdoArrivalMonitor.lastPayload(nodeId, pdo)
    override fun tpdoLengths(nodeId: Int, pdo: Int): Set<Int> = TpdoArrivalMonitor.seenLengths(nodeId, pdo)
    override fun heartbeatTimestamps(nodeId: Int): LongArray = TpdoArrivalMonitor.heartbeatSnapshot(nodeId)
    override fun heartbeatState(nodeId: Int): Int? = TpdoArrivalMonitor.lastHeartbeatState(nodeId)
    override fun bootupCount(nodeId: Int): Int = TpdoArrivalMonitor.bootupCount(nodeId)
    override suspend fun sleep(ms: Long) = delay(ms)
    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()
}

/**
 * YZ-MOTOR 验证流程。三组可单独调用；调用方负责：作业/点动互斥、二次确认、取消时调用 [safeStop]。
 *
 * @param calibration   目标电机的标定（限位用于 C 组行程规划）
 * @param positionSpeed 位置运动梯形速度 RPM（沿用 SowingDepthState）
 * @param acceleration  加减速 RPM/s（沿用 SowingDepthState）
 */
class YzMotorVerifyRunner(
    private val io: YzVerifyIo,
    private val nodeId: Int,
    private val calibration: ServoCalibration,
    private val positionSpeed: Int,
    private val acceleration: Int,
    private val options: YzMotorVerifyFun.VerifyOptions,
    private val onProgress: suspend (String) -> Unit = {},
    private val onItem: suspend (CheckItem) -> Unit = {}
) {
    companion object {
        const val SDO_TIMEOUT_MS = 1000L
        /** 明知可能不应答的探测（6084h 等）用短超时，别让一轮验证白等好几秒 */
        const val PROBE_TIMEOUT_MS = 600L
        const val POLL_MS = 100L
        const val MOVE_TIMEOUT_MS = 5000L
        /** C4 点动转速：30rpm × 400ms ≈ 0.2 圈 ≈ 6554 脉冲，与默认行程同量级 */
        const val JOG_RPM = 30
        const val JOG_MIN_MS = 300L
        const val JOG_MAX_MS = 1500L
        /** 位置稳定判据（脉冲） */
        const val STILL_PULSES = 20
        const val NMT_OPERATIONAL = 0x05
    }

    enum class MotionPhase { NONE, POSITION, VELOCITY }

    private val replyId = 0x580 + nodeId

    /** 已产出的全部检测项（按执行顺序） */
    val items: MutableList<CheckItem> = mutableListOf()

    /** B0 故障复位失败后置位：B/C 其余项一律 SKIP，绝不带着故障动电机 */
    var faultBlocked: Boolean = false
        private set

    /** 当前运动阶段，供 [safeStop] 决定怎么停 */
    @Volatile
    var motionPhase: MotionPhase = MotionPhase.NONE
        private set

    /** B/C 是否碰过控制字（决定 safeStop 结尾是否需要 Shutdown） */
    @Volatile
    private var driveTouched = false

    // A 组沉淀给后续组用的读数
    private var lastStatusWord: Int? = null
    private var sdoVelocity: SdoOutcome? = null
    private var sdoPosition: Int? = null
    private val tpdoEventMs = mutableMapOf<Int, Long>()
    private val tpdoEnabled = mutableMapOf<Int, Boolean>()
    private var heartbeatMsFromSdo: Long? = null

    // ─────────────────────────────────────────────────────────────────────
    // 基础问答
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun emit(item: CheckItem) {
        items += item
        onItem(item)
    }

    private suspend fun progress(text: String) = onProgress(text)

    private suspend fun send(frame: ByteArray, timeoutMs: Long = SDO_TIMEOUT_MS): SdoOutcome =
        YzMotorVerifyFun.classify(io.sdo(frame, replyId, timeoutMs))

    private suspend fun read(index: Int, sub: Int, timeoutMs: Long = SDO_TIMEOUT_MS): SdoOutcome =
        send(CanOpenFun.buildSdoReadFrame(nodeId, index, sub), timeoutMs)

    private suspend fun write(index: Int, sub: Int, len: Int, value: Long, timeoutMs: Long = SDO_TIMEOUT_MS): SdoOutcome =
        send(CanOpenFun.buildSdoWriteFrame(nodeId, index, sub, len, value), timeoutMs)

    private suspend fun readPosition(): Int? = read(0x6064, 0).valueOrNull?.toInt()

    private suspend fun readStatusWord(): Int? = read(0x6041, 0).valueOrNull?.toInt()?.and(0xFFFF)

    // hex() 为文件级函数，MoveResult 等嵌套类也要用

    private fun swText(sw: Int) = "6041h=${hex(sw.toLong(), 4)}（${CanOpenFun.driveStateDescription(sw)}）" +
        " ${YzMotorVerifyFun.statusBits(sw)}"

    // ─────────────────────────────────────────────────────────────────────
    // A 组：只读核对
    // ─────────────────────────────────────────────────────────────────────

    suspend fun runGroupA() {
        try {
            progress("A1 身份")
            checkIdentity()
            progress("A2 错误寄存器 1001h")
            checkErrorRegister()
            progress("A3/A4 应答长度")
            sdoVelocity = read(0x606C, 0)
            emit(lengthItem("A3", 0x606C, sdoVelocity!!))
            emit(lengthItem("A4", 0x6078, read(0x6078, 0)))
            progress("A5/A6 EDS 外对象")
            emit(unknownProbeItem("A5", 0x6084, "轮廓减速度（DS402 常见，不在 EDS）", read(0x6084, 0, PROBE_TIMEOUT_MS)))
            emit(unknownProbeItem("A6a", 0x1008, "设备名称（CiA301 可选，不在 EDS）", read(0x1008, 0, PROBE_TIMEOUT_MS)))
            emit(unknownProbeItem("A6b", 0x6061, "工作模式显示（DS402，不在 EDS）", read(0x6061, 0, PROBE_TIMEOUT_MS)))
            progress("A7 状态字")
            checkStatusWord()
            progress("A8 工作模式")
            emit(YzMotorVerifyFun.compareToEds(Group.A, "A8", 0x6060, 0, nodeId, read(0x6060, 0), Verdict.INFO,
                note = "1=位置 3=速度，两者都合理"))
            progress("A9 TPDO 通信参数")
            checkTpdoParams()
            progress("A10 TPDO 映射")
            checkPdoMapping("A10a", 0x1A00, 2, Verdict.DIFF)
            checkPdoMapping("A10b", 0x1A02, 2, Verdict.DIFF)
            progress("A11 RPDO 参数/映射")
            checkRpdoParams()
            checkPdoMapping("A11e", 0x1600, 3, Verdict.INFO)
            progress("A12 通信对象")
            heartbeatMsFromSdo = emitCompare("A12a", 0x1017, 0, Verdict.INFO)
            emitCompare("A12b", 0x1005, 0, Verdict.INFO)
            emitCompare("A12c", 0x1006, 0, Verdict.INFO)
            emitCompare("A12d", 0x1016, 1, Verdict.INFO)
            progress("A13 厂商对象")
            checkManufacturerObjects()
            progress("A14 旁听 ${options.listenMs}ms")
            listenAndCheck()
        } finally {
            io.listenStop()
        }
    }

    private suspend fun checkIdentity() {
        val parts = listOf(0x1000 to 0, 0x1018 to 1, 0x1018 to 2, 0x1018 to 3)
        val outcomes = parts.map { (i, s) -> Triple(i, s, read(i, s)) }
        val serial = read(0x1018, 4)
        val allMatch = outcomes.all { (i, s, o) -> o.valueOrNull == YzMotorVerifyFun.edsExpected(i, s, nodeId) }
        val anyTimeout = outcomes.any { it.third is SdoOutcome.Timeout }
        val observed = outcomes.joinToString("；") { (i, s, o) ->
            "${YzMotorVerifyFun.address(i, s)}=" + (o.valueOrNull?.let { "$it(${hex(it, 1)})" } ?: o.describe())
        } + "；序列号=" + (serial.valueOrNull?.toString() ?: serial.describe())
        emit(
            CheckItem(
                Group.A, "A1", "身份 1000h / 1018h",
                when {
                    allMatch -> Verdict.PASS
                    anyTimeout -> Verdict.FAIL
                    else -> Verdict.DIFF
                },
                "设备类型 0x20192，Vendor 817，Product 1，Revision 256（序列号因设备而异）",
                observed
            )
        )
    }

    private suspend fun checkErrorRegister() {
        val o = read(0x1001, 0, PROBE_TIMEOUT_MS)
        val (verdict, note) = when (o) {
            is SdoOutcome.Value -> Verdict.PASS to "Mandatory 对象有应答"
            SdoOutcome.Timeout -> Verdict.DIFF to "CiA301 Mandatory 对象却无应答（复现 2026-09 现场结论）"
            is SdoOutcome.Abort -> Verdict.DIFF to "Mandatory 对象被 abort"
            else -> Verdict.FAIL to null
        }
        emit(CheckItem(Group.A, "A2", "1001h 错误寄存器（Mandatory）", verdict, "U8，必须可读", o.describe(), note))
    }

    private fun lengthItem(id: String, index: Int, o: SdoOutcome): CheckItem {
        val obj = YzMotorVerifyFun.edsObject(index, 0)!!
        val expectedLen = obj.type.byteLength
        val expected = "${obj.type}（${expectedLen}B，CS 0x%02X）".format(if (expectedLen == 4) 0x43 else if (expectedLen == 2) 0x4B else 0x4F)
        return when (o) {
            is SdoOutcome.Value -> {
                val ok = o.length == expectedLen
                val signedValue = YzMotorVerifyFun.signed(o.value, obj.type)
                CheckItem(
                    Group.A, id, "${obj.address} ${obj.title} 应答长度", if (ok) Verdict.PASS else Verdict.DIFF, expected,
                    "${o.length}B，CS=0x%02X，值=%d".format(o.cs, if (ok) signedValue else o.value),
                    if (!ok) "固件按 ${o.length}B 返回，EDS 声明 ${expectedLen}B；ServoDiagnostics 快照会把它标成长度不符" else null
                )
            }
            else -> CheckItem(Group.A, id, "${obj.address} ${obj.title} 应答长度", Verdict.FAIL, expected, o.describe())
        }
    }

    private fun unknownProbeItem(id: String, index: Int, title: String, o: SdoOutcome): CheckItem {
        val (verdict, note) = YzMotorVerifyFun.unknownObjectVerdict(o)
        return CheckItem(
            Group.A, id, "${YzMotorVerifyFun.address(index, 0)} $title", verdict,
            "CiA301：不存在的对象应 abort 0x06020000", o.describe(), note
        )
    }

    private suspend fun checkStatusWord() {
        val sw = read(0x6041, 0)
        val cw = read(0x6040, 0)
        val en = read(0x2601, 0)
        val swv = sw.valueOrNull?.toInt()?.and(0xFFFF)
        lastStatusWord = swv
        if (swv == null) {
            emit(CheckItem(Group.A, "A7", "6041h 状态字解码", Verdict.FAIL, "可读，且低位能匹配某一 CiA402 状态", sw.describe()))
            return
        }
        val state = CanOpenFun.driveStateDescription(swv)
        val unknown = state == "未知状态"
        val cwText = cw.valueOrNull?.let { hex(it, 4) } ?: cw.describe()
        val enText = en.valueOrNull?.toString() ?: en.describe()
        val flags = CanOpenFun.parseStatusWord(swv)
        val notes = mutableListOf<String>()
        if (unknown) notes += "bit0/bit1 组合不落在任何 CiA402 状态上，App 的状态判定不能依赖低位掩码"
        if (flags.fault) notes += "bit3=1 疑似故障，B 组会先尝试 0x0080 复位"
        if (cw.valueOrNull == 0x0006L && flags.operationEnabled) notes += "控制字为 Shutdown(0x0006) 但 bit2 仍为 1：bit2 不是 Operation enabled"
        emit(
            CheckItem(
                Group.A, "A7", "6041h 状态字解码", if (unknown) Verdict.DIFF else Verdict.PASS,
                "低位 &0x6F 应匹配 0x21/0x23/0x27 等 CiA402 状态；bit3=Fault bit10=到位 bit11=内部限位",
                "${swText(swv)}；6040h=$cwText；2601h 驱动器使能=$enText",
                notes.joinToString("；").ifEmpty { null }
            )
        )
    }

    private suspend fun emitCompare(id: String, index: Int, sub: Int, mismatch: Verdict, note: String? = null): Long? {
        val o = read(index, sub)
        emit(YzMotorVerifyFun.compareToEds(Group.A, id, index, sub, nodeId, o, mismatch, note = note))
        return o.valueOrNull
    }

    private suspend fun checkTpdoParams() {
        for (ch in ServoCanOpenFun.TPDO_CHANNELS) {
            val cob = read(ch.paramIndex, ServoCanOpenFun.SUB_TPDO_COB_ID)
            val type = read(ch.paramIndex, ServoCanOpenFun.SUB_TPDO_TRANSMISSION_TYPE)
            val ev = read(ch.paramIndex, ServoCanOpenFun.SUB_TPDO_EVENT_TIMER)
            val cobV = cob.valueOrNull
            val typeV = type.valueOrNull
            val evV = ev.valueOrNull
            if (cobV != null) tpdoEnabled[ch.pdo] = ServoCanOpenFun.isPdoEnabled(cobV)
            if (evV != null) tpdoEventMs[ch.pdo] = evV
            val expectedCanId = ch.cobIdBase + nodeId
            val notes = mutableListOf<String>()
            var verdict = Verdict.PASS
            if (cobV == null || typeV == null || evV == null) verdict = Verdict.FAIL
            if (cobV != null && ServoCanOpenFun.cobIdCanId(cobV) != expectedCanId) {
                verdict = Verdict.DIFF
                notes += "报文 ID 0x%03X ≠ 默认 0x%03X，接收路由按默认段认领，会失配".format(ServoCanOpenFun.cobIdCanId(cobV), expectedCanId)
            }
            if (typeV != null && !ServoCanOpenFun.isAsyncTransmissionType(typeV)) {
                verdict = Verdict.DIFF
                notes += "传输类型非 255，事件周期不生效"
            }
            if (verdict == Verdict.PASS && evV != null && evV.toInt() != ch.defaultEventMs) {
                verdict = Verdict.INFO
                notes += "周期已被现场改过（EDS 出厂 ${ch.defaultEventMs}ms）"
            }
            emit(
                CheckItem(
                    Group.A, "A9.${ch.pdo}", "${"%04X".format(ch.paramIndex)}h ${ch.label} 通信参数", verdict,
                    "COB-ID 0x%03X（有效），类型 255，周期 %dms".format(expectedCanId, ch.defaultEventMs),
                    "COB-ID=" + (cobV?.let { "%s（ID 0x%03X，%s）".format(hex(it, 8), ServoCanOpenFun.cobIdCanId(it), if (ServoCanOpenFun.isPdoEnabled(it)) "有效" else "已禁用") } ?: cob.describe()) +
                        "，类型=" + (typeV?.toString() ?: type.describe()) +
                        "，周期=" + (evV?.let { "${it}ms" } ?: ev.describe()),
                    notes.joinToString("；").ifEmpty { null }
                )
            )
        }
    }

    private suspend fun checkPdoMapping(id: String, index: Int, entries: Int, mismatch: Verdict) {
        val count = read(index, 0)
        val maps = (1..entries).map { read(index, it) }
        val expectedCount = YzMotorVerifyFun.edsExpected(index, 0, nodeId)
        val expectedMaps = (1..entries).map { YzMotorVerifyFun.edsExpected(index, it, nodeId) }
        val anyTimeout = count is SdoOutcome.Timeout || maps.any { it is SdoOutcome.Timeout }
        val match = count.valueOrNull == expectedCount &&
            maps.mapIndexed { i, o -> o.valueOrNull == expectedMaps[i] }.all { it }
        val title = YzMotorVerifyFun.edsObject(index, 0)?.title?.replace(" 映射数量", " 映射") ?: "%04Xh 映射".format(index)
        emit(
            CheckItem(
                Group.A, id, "%04Xh $title".format(index),
                when {
                    match -> Verdict.PASS
                    anyTimeout -> Verdict.FAIL
                    else -> mismatch
                },
                "数量 $expectedCount：" + expectedMaps.joinToString(" + ") { it?.let(YzMotorVerifyFun::decodePdoMapping) ?: "?" },
                "数量 " + (count.valueOrNull?.toString() ?: count.describe()) + "：" +
                    maps.joinToString(" + ") { o -> o.valueOrNull?.let { YzMotorVerifyFun.decodePdoMapping(it) } ?: o.describe() }
            )
        )
    }

    private suspend fun checkRpdoParams() {
        val bases = listOf(0x1400 to 0x200, 0x1401 to 0x300, 0x1402 to 0x400, 0x1403 to 0x500)
        bases.forEachIndexed { i, (index, base) ->
            val cob = read(index, 1)
            val type = read(index, 2)
            val cobV = cob.valueOrNull
            val expectedCanId = base + nodeId
            val ok = cobV != null && ServoCanOpenFun.cobIdCanId(cobV) == expectedCanId && type.valueOrNull == 255L
            emit(
                CheckItem(
                    Group.A, "A11${'a' + i}", "%04Xh RPDO${i + 1} 通信参数".format(index),
                    when {
                        ok -> Verdict.PASS
                        cobV == null || type.valueOrNull == null -> Verdict.FAIL
                        else -> Verdict.INFO
                    },
                    "COB-ID 0x%03X，类型 255".format(expectedCanId),
                    "COB-ID=" + (cobV?.let { "%s（ID 0x%03X，%s）".format(hex(it, 8), ServoCanOpenFun.cobIdCanId(it), if (ServoCanOpenFun.isPdoEnabled(it)) "有效" else "已禁用") } ?: cob.describe()) +
                        "，类型=" + (type.valueOrNull?.toString() ?: type.describe()),
                    "本 App 不用 RPDO；C5 RPDO1 试验依赖 RPDO1 有效且映射为 6040h/6060h/607Ah"
                )
            )
        }
    }

    private suspend fun checkManufacturerObjects() {
        val rw = listOf(0x2600, 0x2601, 0x2604, 0x2609, 0x260A, 0x260B, 0x2614, 0x2615, 0x2618, 0x2619, 0x261C, 0x261D)
        rw.forEachIndexed { i, index ->
            val o = read(index, 0)
            val note = when (index) {
                0x2615 -> o.valueOrNull?.let { if (it.toInt() == nodeId) "等于本机 Node-ID $nodeId" else "≠ 本机 Node-ID $nodeId（EDS 默认 1）" }
                0x260A, 0x260B -> "电子齿轮：App 以 32768 脉冲/圈换算深度，改动会让标定失效"
                else -> null
            }
            emit(YzMotorVerifyFun.compareToEds(Group.A, "A13.${i + 1}", index, 0, nodeId, o, Verdict.INFO, note = note))
        }
        val ro = listOf(0x260C, 0x260E, 0x2612, 0x2613)
        ro.forEachIndexed { i, index ->
            val obj = YzMotorVerifyFun.edsObject(index, 0)!!
            val o = read(index, 0)
            val verdict = when {
                o !is SdoOutcome.Value -> Verdict.FAIL
                index == 0x260E && o.value != 0L -> Verdict.FAIL
                else -> Verdict.INFO
            }
            emit(
                CheckItem(
                    Group.A, "A13.${rw.size + i + 1}", "${obj.address} ${obj.title}", verdict,
                    if (index == 0x260E) "应为 0（无厂商错误）" else null,
                    (o as? SdoOutcome.Value)?.let { ServoCanOpenFun.formatValue(obj, it.value) + "（${it.length}B）" } ?: o.describe()
                )
            )
        }
    }

    private suspend fun listenAndCheck() {
        io.listenStart()
        try {
            io.sleep(options.listenMs)
        } finally {
            io.listenStop()
        }
        sdoPosition = readPosition()

        // TPDO1：6B [6064h I32][6041h U16]
        checkTpdoStream(
            id = "A14a", pdo = 1, expectedLen = 6,
            decode = { d ->
                val pos = le(d, 0, 4).toInt()
                val sw = le(d, 4, 2).toInt()
                val posNote = sdoPosition?.let { " SDO 6064h=$it（差 ${pos - it}）" } ?: ""
                val swNote = lastStatusWord?.let { if (it == sw) " 与 SDO 读值一致" else " ≠ SDO 读值 ${hex(it.toLong(), 4)}" } ?: ""
                "pos=$pos$posNote，sw=${hex(sw.toLong(), 4)}$swNote"
            }
        )
        // TPDO3：EDS 8B [6064h I32][606Ch I32]；现场见过 4B
        checkTpdoStream(
            id = "A14b", pdo = 3, expectedLen = 8,
            decode = { d ->
                val pos = le(d, 0, 4).toInt()
                if (d.size >= 8) {
                    val vel = le(d, 4, 4).toInt()
                    val sdoVel = (sdoVelocity as? SdoOutcome.Value)?.let { YzMotorVerifyFun.signed(it.value, if (it.length == 2) ServoCanOpenFun.EdsDataType.I16 else ServoCanOpenFun.EdsDataType.I32) }
                    "pos=$pos，606Ch(PDO 4B)=$vel" + (sdoVel?.let { "，SDO 606Ch=$it" } ?: "")
                } else {
                    "pos=$pos（只有 6064h，无 606Ch 字段）"
                }
            }
        )
        // TPDO2 / TPDO4：出厂周期 0，不该有帧
        for (pdo in listOf(2, 4)) {
            val ts = io.tpdoTimestamps(nodeId, pdo)
            val expectedMs = tpdoEventMs[pdo]
            val enabled = tpdoEnabled[pdo] ?: true
            val shouldSend = enabled && (expectedMs ?: 0L) > 0L
            emit(
                CheckItem(
                    Group.A, if (pdo == 2) "A14c" else "A14d", "TPDO$pdo 旁听",
                    when {
                        ts.isEmpty() && !shouldSend -> Verdict.PASS
                        ts.isNotEmpty() && shouldSend -> Verdict.PASS
                        ts.isEmpty() -> Verdict.FAIL
                        else -> Verdict.INFO
                    },
                    if (shouldSend) "周期 ${expectedMs}ms，应有帧" else "EDS 出厂周期 0，不应有帧",
                    "${ts.size} 帧，载荷长度 ${io.tpdoLengths(nodeId, pdo)}"
                )
            )
        }
        // 心跳
        val hb = io.heartbeatTimestamps(nodeId)
        val expectedHb = heartbeatMsFromSdo ?: 1000L
        val state = io.heartbeatState(nodeId)
        val boot = io.bootupCount(nodeId)
        val report = ServoCanOpenFun.analyzeTpdoTiming(hb, expectedHb.toInt())
        val stateText = when (state) {
            null -> "无"
            0x05 -> "0x05 Operational"
            0x7F -> "0x7F Pre-operational"
            0x04 -> "0x04 Stopped"
            0x00 -> "0x00 Boot-up"
            else -> hex(state.toLong(), 2)
        }
        val notes = mutableListOf<String>()
        var verdict = Verdict.PASS
        if (boot > 0) {
            verdict = Verdict.FAIL
            notes += "窗口内出现 $boot 次 Boot-up：驱动器在自复位（掉电/bus-off 恢复）"
        }
        if (hb.isEmpty()) {
            if (expectedHb > 0) verdict = Verdict.FAIL
        } else if (report != null && expectedHb > 0 &&
            abs(report.meanMs - expectedHb) > ServoCanOpenFun.tpdoVerifyToleranceMs(expectedHb.toInt())
        ) {
            if (verdict != Verdict.FAIL) verdict = Verdict.DIFF
            notes += "实测间隔与 1017h 不符"
        }
        if (state != null && state != NMT_OPERATIONAL && verdict == Verdict.PASS) {
            verdict = Verdict.INFO
            notes += "NMT 非 Operational，PDO 不会发；控深协程启动时会广播 NMT 启动"
        }
        emit(
            CheckItem(
                Group.A, "A14e", "心跳 0x700+ID 旁听", verdict,
                "间隔 = 1017h（${expectedHb}ms），状态 0x05，无 Boot-up",
                "${hb.size} 帧" + (report?.let { "，均隔 %.0fms（%d~%dms）".format(it.meanMs, it.minMs, it.maxMs) } ?: "") +
                    "，状态=$stateText，Boot-up=$boot",
                notes.joinToString("；").ifEmpty { null }
            )
        )
    }

    private suspend fun checkTpdoStream(id: String, pdo: Int, expectedLen: Int, decode: (ByteArray) -> String) {
        val ch = ServoCanOpenFun.tpdoChannel(pdo)
        val ts = io.tpdoTimestamps(nodeId, pdo)
        val lens = io.tpdoLengths(nodeId, pdo)
        val payload = io.tpdoPayload(nodeId, pdo)
        val enabled = tpdoEnabled[pdo] ?: true
        val expectedMs = (tpdoEventMs[pdo] ?: ch.defaultEventMs.toLong()).toInt()
        val expected = "载荷 ${expectedLen}B（${ch.mapping}），周期 ${expectedMs}ms" + if (!enabled) "（当前 COB-ID 已禁用，不应有帧）" else ""
        if (ts.isEmpty()) {
            val silentOk = !enabled || expectedMs == 0
            emit(
                CheckItem(
                    Group.A, id, "${ch.label} 旁听", if (silentOk) Verdict.PASS else Verdict.FAIL, expected,
                    "窗口内未收到", if (silentOk) "已关闭/周期 0，未收到符合预期" else "应有上报却没收到：检查 NMT 状态与链路"
                )
            )
            return
        }
        val report = ServoCanOpenFun.analyzeTpdoTiming(ts, expectedMs)
        val notes = mutableListOf<String>()
        var verdict = Verdict.PASS
        if (lens != setOf(expectedLen)) {
            verdict = Verdict.DIFF
            notes += "载荷长度 $lens ≠ EDS 映射 ${expectedLen}B"
        }
        if (!enabled) {
            verdict = Verdict.DIFF
            notes += "COB-ID Bit31=1 却仍在发"
        }
        if (report != null && expectedMs > 0 && report.intervalCount >= 3 &&
            abs(report.meanMs - expectedMs) > ServoCanOpenFun.tpdoVerifyToleranceMs(expectedMs)
        ) {
            verdict = Verdict.DIFF
            notes += "实测周期 %.1fms ≠ 对象值 %dms".format(report.meanMs, expectedMs)
        }
        val raw = payload?.joinToString(" ") { "%02X".format(it) } ?: "?"
        val decoded = payload?.let { if (it.size >= 4) decode(it) else "载荷不足 4B" } ?: ""
        emit(
            CheckItem(
                Group.A, id, "${ch.label} 旁听", verdict, expected,
                "${ts.size} 帧" + (report?.let { "，均隔 %.1fms 抖动 %.1fms".format(it.meanMs, it.jitterMs) } ?: "") +
                    "，长度 $lens，最近帧 [$raw] → $decoded",
                notes.joinToString("；").ifEmpty { null }
            )
        )
    }

    private fun le(d: ByteArray, offset: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) v = v or ((d[offset + i].toLong() and 0xFF) shl (8 * i))
        // 符号扩展
        return when (len) {
            2 -> v.toShort().toLong()
            4 -> v.toInt().toLong()
            else -> v
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // B 组：状态机核对（写入、无运动）
    // ─────────────────────────────────────────────────────────────────────

    suspend fun runGroupB() {
        driveTouched = true
        progress("B0 故障检查")
        if (!ensureNoFault("B0")) return

        progress("B1 模式写入")
        val ack = write(0x6060, 0, 1, 1L)
        val back = read(0x6060, 0)
        emit(
            CheckItem(
                Group.B, "B1", "6060h 写 1（位置模式）并回读",
                if (ack is SdoOutcome.WriteAck && back.valueOrNull == 1L) Verdict.PASS else Verdict.FAIL,
                "ACK 且回读 = 1", "写入 ${ack.describe()}；回读 ${back.describe()}"
            )
        )

        progress("B2 DS402 逐步跳变")
        for ((i, step) in YzMotorVerifyFun.DS402_STEPS.withIndex()) {
            val id = "B2.${i + 1}"
            val wr = write(0x6040, 0, 2, step.controlword.toLong())
            if (wr !is SdoOutcome.WriteAck) {
                emit(CheckItem(Group.B, id, "写 6040h=${hex(step.controlword.toLong(), 4)} ${step.name}", Verdict.FAIL,
                    "ACK", wr.describe(), "控制字写入未确认，B2 中止"))
                return
            }
            io.sleep(150)
            val sw = readStatusWord()
            val cwBack = read(0x6040, 0).valueOrNull
            if (sw == null) {
                emit(CheckItem(Group.B, id, "写 6040h=${hex(step.controlword.toLong(), 4)} ${step.name}", Verdict.FAIL,
                    "状态字低位 ${hex(step.expectedLow.toLong(), 2)}", "状态字读取失败"))
                continue
            }
            lastStatusWord = sw
            val (verdict, judged) = YzMotorVerifyFun.judgeStep(step, sw)
            val notes = mutableListOf(judged)
            var v = verdict
            if (cwBack != null && cwBack != step.controlword.toLong()) {
                v = Verdict.DIFF
                notes += "6040h 回读 ${hex(cwBack, 4)} ≠ 写入值"
            }
            if (step.controlword == 0x0006 && (sw and 0x4) != 0) notes += "Shutdown 后 bit2 仍为 1（bit2 非 Operation enabled）"
            if (step.controlword == 0x000F && (sw and 0x4) == 0) notes += "Enable operation 后 bit2 为 0"
            emit(
                CheckItem(
                    Group.B, id, "写 6040h=${hex(step.controlword.toLong(), 4)} ${step.name}", v,
                    "6041h&0x6F = ${hex(step.expectedLow.toLong(), 2)}，6040h 回读 = 写入值",
                    swText(sw) + "；6040h 回读=" + (cwBack?.let { hex(it, 4) } ?: "失败"),
                    notes.joinToString("；")
                )
            )
        }

        progress("B3 6084h 写入探测")
        val acc = read(0x6083, 0)
        if (acc !is SdoOutcome.Value) {
            emit(CheckItem(Group.B, "B3", "6084h 写入（与 6083h 同值）", Verdict.FAIL, "先能读到 6083h", "6083h ${acc.describe()}"))
        } else {
            val w = write(0x6084, 0, 4, acc.value, PROBE_TIMEOUT_MS)
            val (verdict, note) = YzMotorVerifyFun.unknownObjectVerdict(w)
            emit(
                CheckItem(
                    Group.B, "B3", "6084h 写入（与 6083h 同值 ${acc.value}）", verdict,
                    "不在 EDS：CiA301 应 abort", w.describe(),
                    "$note；buildSetAccelDecelFrames 每次点动都会写这一帧，减速斜率实际只靠 6083h"
                )
            )
        }
    }

    /** bit3 置位时尝试 0x0080 复位；复位不掉则 faultBlocked，后续 B/C 全部 SKIP。 */
    private suspend fun ensureNoFault(id: String): Boolean {
        val sw = lastStatusWord ?: readStatusWord()
        if (sw == null) {
            emit(CheckItem(Group.B, id, "故障检查", Verdict.FAIL, "可读 6041h", "状态字读取失败，B/C 组中止"))
            faultBlocked = true
            return false
        }
        if ((sw and 0x8) == 0) {
            emit(CheckItem(Group.B, id, "故障检查", Verdict.SKIP, "bit3=0", swText(sw), "无故障，未发复位"))
            return true
        }
        val wr = write(0x6040, 0, 2, 0x0080L)
        io.sleep(300)
        val after = readStatusWord()
        lastStatusWord = after
        val cleared = after != null && (after and 0x8) == 0
        emit(
            CheckItem(
                Group.B, id, "故障复位 6040h=0x0080", if (cleared) Verdict.PASS else Verdict.FAIL,
                "bit3 清零", "复位 ${wr.describe()}；复位后 " + (after?.let { swText(it) } ?: "读取失败"),
                if (cleared) "bit3 确为可复位的 Fault 位" else "bit3 不清零：可能是真故障，也可能 bit3 不是 Fault——B/C 其余项跳过"
            )
        )
        if (!cleared) faultBlocked = true
        return cleared
    }

    // ─────────────────────────────────────────────────────────────────────
    // C 组：运动核对（电机会动）
    // ─────────────────────────────────────────────────────────────────────

    private data class MoveResult(
        val moved: Boolean,
        val reached: Boolean,
        val elapsedMs: Long,
        val bit10: Boolean,
        val bit12Seen: Boolean,
        val finalPos: Int?,
        val lastSw: Int?
    ) {
        fun text(target: Int): String =
            (if (reached) "到位" else if (moved) "动了但未到位" else "未动") +
                "，${elapsedMs}ms，终点 ${finalPos ?: "?"}（目标 $target，差 ${finalPos?.let { it - target } ?: "?"}）" +
                "，bit10=${if (bit10) 1 else 0}，bit12 曾置位=${bit12Seen}" +
                (lastSw?.let { "，末态 6041h=${hex(it.toLong(), 4)}" } ?: "")
    }

    suspend fun runGroupC() {
        if (faultBlocked) {
            emit(CheckItem(Group.C, "C", "运动核对", Verdict.SKIP, null, "B0 故障未清除，跳过全部运动"))
            return
        }
        driveTouched = true
        val travel = options.travelPulses
        val tol = YzMotorVerifyFun.arrivalTolerance(travel)

        progress("C0 使能与行程规划")
        val pos0 = readPosition()
        if (pos0 == null) {
            emit(CheckItem(Group.C, "C0", "使能与行程规划", Verdict.FAIL, "可读 6064h", "位置读取失败，C 组中止"))
            return
        }
        val plan = YzMotorVerifyFun.planTravel(pos0, travel, calibration.limitsSet, calibration.limitMin, calibration.limitMax)
        if (plan == null) {
            emit(CheckItem(Group.C, "C0", "使能与行程规划", Verdict.FAIL,
                "pos0 ± 2×行程 落在限位 [${calibration.limitMin}, ${calibration.limitMax}] 内",
                "pos0=$pos0，行程 $travel：两个方向余量都不足，拒绝运动", "把机构点动到限位中间再试，或减小位移量"))
            return
        }
        val (dir, target) = plan
        if (!ensureNoFault("C0.fault")) return

        // C0：使能序列 + 速度/加速度
        val initFrames = CanOpenFun.buildMotorInitSequence(nodeId)
        for ((i, f) in initFrames.withIndex()) {
            val o = send(f)
            if (o !is SdoOutcome.WriteAck) {
                emit(CheckItem(Group.C, "C0", "使能序列 ${i + 1}/${initFrames.size}", Verdict.FAIL, "ACK", o.describe(), "使能未确认，C 组中止"))
                return
            }
        }
        write(0x6081, 0, 4, positionSpeed.toLong())
        write(0x6083, 0, 4, acceleration.toLong())
        io.sleep(200)
        val swAfterEnable = readStatusWord()
        lastStatusWord = swAfterEnable
        emit(
            CheckItem(
                Group.C, "C0", "使能（6060h=1 → 0x0006 → 0x0007 → 0x000F）+ 6081h=$positionSpeed 6083h=$acceleration", Verdict.PASS,
                "4 帧全部 ACK；6041h 低位 0x27", "pos0=$pos0，方向 ${if (dir > 0) "+" else "-"}，目标 $target" +
                    (if (!calibration.limitsSet) "（限位未标定，默认正向，请确认机构有余量）" else "") +
                    "；" + (swAfterEnable?.let { swText(it) } ?: "状态字读取失败")
            )
        )

        // C1：生产序列 0x000F → 0x002F（无 Bit4 沿）
        progress("C1 生产序列触发")
        motionPhase = MotionPhase.POSITION
        var productionWorks = false
        var edgeWorks = false
        run {
            val frames = CanOpenFun.buildAbsoluteMoveFrames(nodeId, target, 0)
            val acks = frames.map { send(it) }
            val r = waitArrival(from = pos0, target = target, travel = travel, tol = tol)
            val cwAfter = read(0x6040, 0).valueOrNull
            productionWorks = r.reached
            emit(
                CheckItem(
                    Group.C, "C1", "生产序列：607A=$target → 6040h=0x000F → 0x002F（两者 Bit4 均为 0）",
                    if (r.reached) Verdict.PASS else if (r.moved) Verdict.INFO else Verdict.FAIL,
                    "按 CiA402 无 Bit4 0→1 沿不应触发；现行代码却一直在用它控深",
                    "ACK ${acks.count { it is SdoOutcome.WriteAck }}/${acks.size}；" + r.text(target) +
                        "；结束后 6040h=" + (cwAfter?.let { hex(it, 4) } ?: "?"),
                    if (r.reached) "无 Bit4 沿也触发：固件把 607A 写入或 Bit5 当作触发，与 CiA402 不同" else null
                )
            )
        }

        // C2：只写 607A 回 pos0，控制字保持 0x002F
        progress("C2 只写 607A")
        var edgeNeededForC2 = false
        run {
            val from = readPosition() ?: target
            val ack = write(0x607A, 0, 4, pos0.toLong())
            var r = waitArrival(from = from, target = pos0, travel = travel, tol = tol, timeoutMs = 3000L)
            var note: String
            if (r.moved) {
                note = "607A 写入本身就触发运动：任何一帧 607A 丢失都会让驱动器拿旧目标执行——与 2026-09 现场丢帧现象吻合"
            } else {
                edgeNeededForC2 = true
                write(0x6040, 0, 2, 0x002FL)
                write(0x6040, 0, 2, 0x003FL)
                r = waitArrival(from = from, target = pos0, travel = travel, tol = tol)
                note = "只写 607A 不动；补发 0x002F→0x003F 沿后：" + r.text(pos0)
                if (r.reached) edgeWorks = true
            }
            emit(
                CheckItem(
                    Group.C, "C2", "只写 607A=$pos0（控制字不动）",
                    if (edgeNeededForC2) Verdict.PASS else Verdict.DIFF,
                    "CiA402：仅写 607A 不应触发，需 Bit4 沿",
                    "607A ${ack.describe()}；" + (if (edgeNeededForC2) "仅写 607A 未动" else r.text(pos0)),
                    note
                )
            )
        }

        // C3：真 Bit4 沿 0x002F → 0x003F
        progress("C3 Bit4 沿触发")
        run {
            val from = readPosition() ?: pos0
            val acks = listOf(
                write(0x607A, 0, 4, target.toLong()),
                write(0x6040, 0, 2, 0x002FL),
                write(0x6040, 0, 2, 0x003FL)
            )
            val r = waitArrival(from = from, target = target, travel = travel, tol = tol)
            val cwAfter = read(0x6040, 0).valueOrNull
            if (r.reached) edgeWorks = true
            val bit4Cleared = cwAfter?.let { (it and 0x10L) == 0L }
            emit(
                CheckItem(
                    Group.C, "C3", "标准触发：607A=$target → 0x002F → 0x003F（Bit4 0→1）",
                    if (r.reached) Verdict.PASS else if (r.moved) Verdict.INFO else Verdict.FAIL,
                    "触发运动；手册称 Bit4 自动清零；到位 bit10=1；bit12 Set-point ack 应出现",
                    "ACK ${acks.count { it is SdoOutcome.WriteAck }}/3；" + r.text(target) +
                        "；结束后 6040h=" + (cwAfter?.let { hex(it, 4) } ?: "?") +
                        (bit4Cleared?.let { if (it) "（Bit4 已自动清零）" else "（Bit4 仍为 1，未自动清零）" } ?: ""),
                    if (!r.bit12Seen) "整个运动过程 bit12 从未置位：固件不实现 Set-point acknowledge 握手" else null
                )
            )
        }

        // C4：速度模式点动，回答 606Ch 单位与 60FF 符号关系
        progress("C4 速度模式点动")
        run {
            val before = readPosition() ?: target
            val jogRpm = -dir * JOG_RPM   // 朝 pos0 方向；60FF 正负与脉冲增减的关系本身就是待验证项
            val pulsesPerMs = JOG_RPM * YzMotorVerifyFun.PULSES_PER_REV / 60000.0
            val jogMs = (travel / pulsesPerMs).toLong().coerceIn(JOG_MIN_MS, JOG_MAX_MS)
            CanOpenFun.buildSetAccelDecelFrames(nodeId, acceleration).forEachIndexed { i, f ->
                send(f, if (i == 1) PROBE_TIMEOUT_MS else SDO_TIMEOUT_MS)
            }
            val startAcks = CanOpenFun.buildJogStartSequence(nodeId, jogRpm).map { send(it) }
            motionPhase = MotionPhase.VELOCITY
            val t0 = io.nowMs()
            val samples = mutableListOf<Triple<Long, Int?, Int?>>()  // t, 606C, 6064
            var bit10DuringJog = false
            while (io.nowMs() - t0 < jogMs) {
                io.sleep(POLL_MS)
                val v = read(0x606C, 0, 400)
                val vSigned = (v as? SdoOutcome.Value)?.let {
                    YzMotorVerifyFun.signed(it.value, if (it.length == 2) ServoCanOpenFun.EdsDataType.I16 else ServoCanOpenFun.EdsDataType.I32).toInt()
                }
                val p = read(0x6064, 0, 400).valueOrNull?.toInt()
                val sw = read(0x6041, 0, 400).valueOrNull?.toInt()
                if (sw != null && (sw and 0x400) != 0) bit10DuringJog = true
                samples += Triple(io.nowMs() - t0, vSigned, p)
            }
            // 停止：v=0 ×3（幂等、抗丢帧）→ 等减速 → 位置稳定校验 → 退到 Switched On
            repeat(3) { write(0x60FF, 0, 4, 0L, 400) }
            io.sleep(JOG_RPM * 1000L / acceleration.coerceAtLeast(1) + 250)
            var pA = readPosition()
            io.sleep(150)
            var pB = readPosition()
            var stable = pA != null && pB != null && abs(pA - pB) <= STILL_PULSES
            if (!stable) {
                repeat(3) { write(0x60FF, 0, 4, 0L, 400) }
                io.sleep(400)
                pA = readPosition(); io.sleep(150); pB = readPosition()
                stable = pA != null && pB != null && abs(pA - pB) <= STILL_PULSES
            }
            CanOpenFun.buildJogStopDisableFrames(nodeId).forEach { send(it) }
            motionPhase = MotionPhase.NONE
            val after = pB ?: pA
            val delta = if (after != null) after - before else 0
            val moved = abs(delta) >= tol
            val validV = samples.mapNotNull { it.second }.filter { it != 0 }
            val vTypical = if (validV.isEmpty()) 0 else validV.sorted()[validV.size / 2]
            val firstP = samples.firstOrNull { it.third != null }
            val lastP = samples.lastOrNull { it.third != null }
            val unitText = if (firstP != null && lastP != null && lastP.first > firstP.first) {
                YzMotorVerifyFun.velocityUnitEstimate(vTypical, lastP.third!! - firstP.third!!, lastP.first - firstP.first)
            } else "位置样本不足，无法推算"
            val signText = when {
                !moved -> "未产生足够位移"
                (delta > 0) == (jogRpm > 0) -> "60FF ${if (jogRpm > 0) "正" else "负"}值 → 位置${if (delta > 0) "增" else "减"}（同号）"
                else -> "60FF ${if (jogRpm > 0) "正" else "负"}值 → 位置${if (delta > 0) "增" else "减"}（异号）"
            }
            emit(
                CheckItem(
                    Group.C, "C4", "速度模式点动 60FF=$jogRpm rpm × ${jogMs}ms，v=0×3 停止",
                    if (moved && stable) Verdict.PASS else Verdict.FAIL,
                    "启动 5 帧 ACK；位移约 ${(pulsesPerMs * jogMs).toInt()} 脉冲；606Ch 单位 rpm；停止后位置稳定",
                    "启动 ACK ${startAcks.count { it is SdoOutcome.WriteAck }}/${startAcks.size}；位移 $delta（$before→${after ?: "?"}）；" +
                        "$signText；${samples.size} 个样本，606Ch=" + samples.mapNotNull { it.second }.joinToString(",") +
                        "；bit10 点动中=${bit10DuringJog}；停止后稳定=$stable",
                    unitText
                )
            )
        }

        // C5：RPDO1 单帧位置命令（可选）
        if (options.includeRpdo) {
            progress("C5 RPDO1 单帧")
            val from = readPosition() ?: target
            val hbState = io.heartbeatState(nodeId)
            var nmtNote = ""
            if (hbState != NMT_OPERATIONAL) {
                io.sendRaw(CanOpenFun.buildNmtFrame(CanOpenFun.Nmt.OPERATIONAL, nodeId))
                io.sleep(200)
                nmtNote = "心跳状态 ${hbState?.let { hex(it.toLong(), 2) } ?: "未知"}，已先发 NMT 启动；"
            }
            motionPhase = MotionPhase.POSITION
            io.sendRaw(YzMotorVerifyFun.buildRpdo1Frame(nodeId, 0x000F, 1, pos0))
            io.sleep(60)
            io.sendRaw(YzMotorVerifyFun.buildRpdo1Frame(nodeId, 0x002F, 1, pos0))
            io.sleep(60)
            io.sendRaw(YzMotorVerifyFun.buildRpdo1Frame(nodeId, 0x003F, 1, pos0))
            val r = waitArrival(from = from, target = pos0, travel = travel, tol = tol)
            emit(
                CheckItem(
                    Group.C, "C5", "RPDO1（0x200+ID）单帧 [6040h][6060h][607A=$pos0] ×3（0x000F/0x002F/0x003F）",
                    if (r.reached) Verdict.PASS else Verdict.INFO,
                    "EDS 1600h 映射下 RPDO1 应能驱动位置运动",
                    nmtNote + r.text(pos0),
                    if (r.reached) "RPDO1 可用：位置命令可由 5 帧 SDO 压成 1 帧 PDO，天然抗丢帧" else "RPDO1 未产生运动：可能需 Operational、映射/长度不符或固件未实现 RPDO"
                )
            )
        }

        // C6：回家并退使能
        progress("C6 回到起点")
        run {
            val cur = readPosition()
            var r: MoveResult? = null
            if (cur != null && abs(cur - pos0) > tol) {
                motionPhase = MotionPhase.POSITION
                if (productionWorks || !edgeWorks) {
                    CanOpenFun.buildAbsoluteMoveFrames(nodeId, pos0, 0).forEach { send(it) }
                } else {
                    write(0x607A, 0, 4, pos0.toLong())
                    write(0x6040, 0, 2, 0x002FL)
                    write(0x6040, 0, 2, 0x003FL)
                }
                r = waitArrival(from = cur, target = pos0, travel = travel, tol = tol)
            }
            motionPhase = MotionPhase.NONE
            write(0x6040, 0, 2, 0x0007L)
            write(0x6040, 0, 2, 0x0006L)
            val finalPos = readPosition()
            val ok = finalPos != null && abs(finalPos - pos0) <= tol
            emit(
                CheckItem(
                    Group.C, "C6", "回到起点 $pos0 并 Shutdown（0x0007 → 0x0006）",
                    if (ok) Verdict.PASS else Verdict.FAIL,
                    "终点误差 ≤ $tol 脉冲", "终点 ${finalPos ?: "?"}，误差 ${finalPos?.let { it - pos0 } ?: "?"}" +
                        (r?.let { "；回程 " + it.text(pos0) } ?: "；已在起点，无需回程"),
                    "结束停在 Ready to switch on，与总开关 OFF 一致"
                )
            )
        }
    }

    /**
     * 每 100ms 读 6064h/6041h，直到到位（且 bit10 或连续两拍静止）或超时。
     * moved 判据：离起点 ≥ max(tol, travel/4)。
     */
    private suspend fun waitArrival(from: Int, target: Int, travel: Int, tol: Int, timeoutMs: Long = MOVE_TIMEOUT_MS): MoveResult {
        val t0 = io.nowMs()
        var bit12Seen = false
        var bit10 = false
        var lastSw: Int? = null
        var lastPos: Int? = null
        var prevPos: Int?
        var reached = false
        val moveThreshold = maxOf(tol, travel / 4)
        while (io.nowMs() - t0 < timeoutMs) {
            io.sleep(POLL_MS)
            val p = read(0x6064, 0, 400).valueOrNull?.toInt()
            val sw = read(0x6041, 0, 400).valueOrNull?.toInt()
            if (sw != null) {
                lastSw = sw
                if ((sw and 0x1000) != 0) bit12Seen = true
                bit10 = (sw and 0x400) != 0
            }
            if (p != null) {
                prevPos = lastPos
                lastPos = p
                if (abs(p - target) <= tol) {
                    reached = true
                    val still = prevPos != null && abs(p - prevPos) <= STILL_PULSES
                    if (bit10 || still) break
                }
            }
        }
        val moved = lastPos != null && abs(lastPos - from) >= moveThreshold
        return MoveResult(moved, reached, io.nowMs() - t0, bit10, bit12Seen, lastPos, lastSw)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 取消 / 异常时的安全收尾（调用方在 NonCancellable 里调用）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 按当前运动阶段停机：
     *   速度模式 → v=0 ×3 + 退到 Switched On（与点动松开一致）；
     *   位置模式 → 把目标改成当前位置平滑停（不用 Quick Stop：会把驱动器踹出使能且无人拉回）；
     * 只要碰过控制字，最后统一 Shutdown 0x0006。全部用短超时，收尾不能卡住 UI。
     */
    suspend fun safeStop() {
        try {
            when (motionPhase) {
                MotionPhase.VELOCITY -> {
                    repeat(3) { write(0x60FF, 0, 4, 0L, 300) }
                    io.sleep(400)
                    CanOpenFun.buildJogStopDisableFrames(nodeId).forEach { send(it, 300) }
                }
                MotionPhase.POSITION -> {
                    val p = read(0x6064, 0, 300).valueOrNull
                    if (p != null) {
                        write(0x607A, 0, 4, p, 300)
                        write(0x6040, 0, 2, 0x000FL, 300)
                        write(0x6040, 0, 2, 0x002FL, 300)
                        io.sleep(300)
                    }
                    write(0x6040, 0, 2, 0x0007L, 300)
                }
                MotionPhase.NONE -> Unit
            }
            if (driveTouched) write(0x6040, 0, 2, 0x0006L, 300)
        } finally {
            motionPhase = MotionPhase.NONE
            io.listenStop()
        }
    }
}

/** 0x 前缀大写十六进制，补足位数 */
private fun hex(v: Long, digits: Int): String = "0x" + v.toString(16).uppercase().padStart(digits, '0')
