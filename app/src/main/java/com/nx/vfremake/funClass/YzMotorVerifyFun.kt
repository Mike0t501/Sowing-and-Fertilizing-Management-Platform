/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2026年9月13日
 * @file    :YzMotorVerifyFun.kt
 * @brief   :YZ-MOTOR 验证——EDS 期望值、SDO 应答分类、DS402 逐步判定、报告格式化（纯函数，可单测）
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 2026-09 对照 docs/YZ_MOTOR_SN2.eds 与实机采样，发现该固件是"部分实现"：1001h 无应答、606Ch 只回
 * 2 字节、6084h 写入静默丢弃、6041h bit0/bit1 恒为 0、Bit4 不带沿也能触发……电机能正常跑，但无法
 * 判断 EDS 与固件谁是准的。本文件把这些已知不符点与电机核心功能都做成可逐项实测的检测项，
 * 实机跑一遍即可拿到一份"EDS vs 实测"的证据报告（docs/YZ_SERVO_FIELD_FINDINGS.md §5 待验证项）。
 ***********************************************************************************************************
 */

package com.nx.vfremake.funClass

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * YZ-MOTOR 验证的纯函数层：不发帧、不挂起、不依赖 Android，全部可单元测试。
 * 发帧与流程编排在 coroutine/YzMotorVerifyRunner.kt。
 */
object YzMotorVerifyFun {

    // ─────────────────────────────────────────────────────────────────────
    // 结果模型
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 单项判定。DIFF 是本功能的核心输出——它就是"电机与 EDS/标准不一致"的实机证据；
     * FAIL 是功能本身没跑通（超时/未到位）；INFO 只记录、不下结论（现场可配置的值、单位推断等）。
     */
    enum class Verdict(val label: String, val symbol: String) {
        PASS("符合", "✓"),
        DIFF("与EDS/标准不符", "≠"),
        FAIL("功能失败", "✗"),
        INFO("记录", "·"),
        SKIP("跳过", "–")
    }

    enum class Group(val title: String, val subtitle: String) {
        A("A 只读核对", "SDO 读 + 旁听总线，无副作用"),
        B("B 状态机核对", "写 6040h/6060h/6084h，不产生运动"),
        C("C 运动核对", "电机小行程往返，需机具悬空")
    }

    data class CheckItem(
        val group: Group,
        val id: String,
        val title: String,
        val verdict: Verdict,
        /** EDS / 标准的期望值，null = 本项没有期望值（纯记录） */
        val expected: String?,
        val observed: String,
        val note: String? = null
    )

    data class VerifyReport(
        val nodeId: Int,
        val motorLabel: String,
        val startedAtMs: Long,
        val appVersion: String,
        val items: List<CheckItem>
    ) {
        fun count(v: Verdict): Int = items.count { it.verdict == v }

        fun summary(): String =
            "共 ${items.size} 项：符合 ${count(Verdict.PASS)}，不符 ${count(Verdict.DIFF)}，" +
                "失败 ${count(Verdict.FAIL)}，记录 ${count(Verdict.INFO)}，跳过 ${count(Verdict.SKIP)}"

        /**
         * 纯文本报告，直接粘贴即可读。不符/失败项排在各组最前，读报告的人先看到问题。
         */
        fun toText(): String {
            val sb = StringBuilder()
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(startedAtMs))
            sb.appendLine("YZ-MOTOR 验证报告")
            sb.appendLine("时间 $ts    App $appVersion    电机 $motorLabel / Node $nodeId")
            sb.appendLine("EDS: YZ_MOTOR_SN2.eds (Vendor 817 / Product 1 / Revision 256)")
            sb.appendLine(summary())
            sb.appendLine("符号：✓符合  ≠与EDS/标准不符  ✗功能失败  ·记录  –跳过")
            Group.values().forEach { g ->
                val inGroup = items.filter { it.group == g }
                if (inGroup.isEmpty()) return@forEach
                sb.appendLine()
                sb.appendLine("== ${g.title}（${g.subtitle}）==")
                val ordered = inGroup.sortedBy { rank(it.verdict) }
                ordered.forEach { item ->
                    sb.appendLine("${item.verdict.symbol} [${item.id}] ${item.title}")
                    item.expected?.let { sb.appendLine("    期望: $it") }
                    sb.appendLine("    实测: ${item.observed}")
                    item.note?.let { sb.appendLine("    说明: $it") }
                }
            }
            return sb.toString()
        }

        private fun rank(v: Verdict) = when (v) {
            Verdict.FAIL -> 0
            Verdict.DIFF -> 1
            Verdict.INFO -> 2
            Verdict.PASS -> 3
            Verdict.SKIP -> 4
        }
    }

    /** 运行参数（由调试页选项行提供） */
    data class VerifyOptions(
        /** A14 旁听时长 */
        val listenMs: Long = 3000L,
        /** C 组单次位移（脉冲）。默认 6554 ≈ 1mm @ 5mm 导程（32768 脉冲/圈） */
        val travelPulses: Int = DEFAULT_TRAVEL_PULSES,
        /** C5 RPDO1 单帧试验（默认关；打开才会向 0x200+Node 发任何 PDO） */
        val includeRpdo: Boolean = false
    )

    /** 编码器 32768 脉冲/圈（SOWING_DEPTH_IMPLEMENTATION.md §2.6） */
    const val PULSES_PER_REV = 32768
    /** 5mm 导程下 1mm 行程 */
    const val DEFAULT_TRAVEL_PULSES = 6554
    const val TRAVEL_PULSES_MIN = 1000
    const val TRAVEL_PULSES_MAX = 32768

    // ─────────────────────────────────────────────────────────────────────
    // SDO 应答分类
    // ─────────────────────────────────────────────────────────────────────

    /** 一次 SDO 问答的全部可能结局；比 [ServoCanOpenFun.SdoReply] 多出"超时"这一档，判定要用 */
    sealed class SdoOutcome {
        object Timeout : SdoOutcome()
        data class Abort(val code: Long) : SdoOutcome()
        data class Value(val value: Long, val length: Int, val cs: Int) : SdoOutcome()
        object WriteAck : SdoOutcome()
        data class Invalid(val raw: ByteArray) : SdoOutcome()

        fun describe(): String = when (this) {
            Timeout -> "超时无应答"
            is Abort -> "abort 0x%08X %s".format(code, ServoCanOpenFun.abortDescription(code))
            is Value -> "值=$value (0x%X, ${length}B, CS=0x%02X)".format(value, cs)
            WriteAck -> "写入 ACK(0x60)"
            is Invalid -> "应答格式无效 [" + raw.joinToString(" ") { "%02X".format(it) } + "]"
        }

        val valueOrNull: Long? get() = (this as? Value)?.value
    }

    fun classify(raw: ByteArray?): SdoOutcome {
        if (raw == null) return SdoOutcome.Timeout
        val reply = ServoCanOpenFun.parseSdoReply(raw) ?: return SdoOutcome.Invalid(raw)
        return when {
            reply.abortCode != null -> SdoOutcome.Abort(reply.abortCode)
            reply.command == 0x60 -> SdoOutcome.WriteAck
            reply.value != null -> SdoOutcome.Value(reply.value, reply.dataLength, reply.command)
            else -> SdoOutcome.Invalid(raw)
        }
    }

    /** 按 EDS 类型把无符号原始值转成有符号显示（I8/I16/I32） */
    fun signed(value: Long, type: ServoCanOpenFun.EdsDataType): Long = when (type) {
        ServoCanOpenFun.EdsDataType.I8 -> value.toByte().toLong()
        ServoCanOpenFun.EdsDataType.I16 -> value.toShort().toLong()
        ServoCanOpenFun.EdsDataType.I32 -> value.toInt().toLong()
        else -> value
    }

    // ─────────────────────────────────────────────────────────────────────
    // EDS 期望值（逐项转录自 docs/YZ_MOTOR_SN2.eds 的 DefaultValue）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * @param default   EDS DefaultValue；null = EDS 未给默认值（如 1001h）
     * @param nodeBase  `$NODEID+0xNNN` 形式的默认值：期望 = nodeBase + nodeId
     */
    data class EdsDefault(val default: Long? = null, val nodeBase: Int? = null) {
        fun expectedFor(nodeId: Int): Long? = nodeBase?.let { (it + nodeId).toLong() } ?: default
    }

    private fun key(index: Int, sub: Int) = (index shl 8) or sub

    val EDS_DEFAULTS: Map<Int, EdsDefault> = mapOf(
        key(0x1000, 0) to EdsDefault(0x20192L),
        key(0x1018, 0) to EdsDefault(4L),
        key(0x1018, 1) to EdsDefault(817L),
        key(0x1018, 2) to EdsDefault(1L),
        key(0x1018, 3) to EdsDefault(256L),
        key(0x1018, 4) to EdsDefault(2L),           // 序列号因设备而异，只作记录
        key(0x1005, 0) to EdsDefault(128L),
        key(0x1006, 0) to EdsDefault(0L),
        key(0x1016, 1) to EdsDefault(0x7F03E8L),
        key(0x1017, 0) to EdsDefault(1000L),
        // RPDO 通信参数
        key(0x1400, 1) to EdsDefault(nodeBase = 0x200),
        key(0x1400, 2) to EdsDefault(255L),
        key(0x1401, 1) to EdsDefault(nodeBase = 0x300),
        key(0x1401, 2) to EdsDefault(255L),
        key(0x1402, 1) to EdsDefault(nodeBase = 0x400),
        key(0x1402, 2) to EdsDefault(255L),
        key(0x1403, 1) to EdsDefault(nodeBase = 0x500),
        key(0x1403, 2) to EdsDefault(255L),
        // RPDO1 映射：6040h:16 + 6060h:8 + 607Ah:32 = 7 字节
        key(0x1600, 0) to EdsDefault(3L),
        key(0x1600, 1) to EdsDefault(0x60400010L),
        key(0x1600, 2) to EdsDefault(0x60600008L),
        key(0x1600, 3) to EdsDefault(0x607A0020L),
        // TPDO 通信参数
        key(0x1800, 1) to EdsDefault(nodeBase = 0x180),
        key(0x1800, 2) to EdsDefault(255L),
        key(0x1800, 5) to EdsDefault(100L),
        key(0x1801, 1) to EdsDefault(nodeBase = 0x280),
        key(0x1801, 2) to EdsDefault(255L),
        key(0x1801, 5) to EdsDefault(0L),
        key(0x1802, 1) to EdsDefault(nodeBase = 0x380),
        key(0x1802, 2) to EdsDefault(255L),
        key(0x1802, 5) to EdsDefault(100L),
        key(0x1803, 1) to EdsDefault(nodeBase = 0x480),
        key(0x1803, 2) to EdsDefault(255L),
        key(0x1803, 5) to EdsDefault(0L),
        // TPDO 映射
        key(0x1A00, 0) to EdsDefault(2L),
        key(0x1A00, 1) to EdsDefault(0x60640020L),
        key(0x1A00, 2) to EdsDefault(0x60410010L),
        key(0x1A02, 0) to EdsDefault(2L),
        key(0x1A02, 1) to EdsDefault(0x60640020L),
        key(0x1A02, 2) to EdsDefault(0x606C0020L),
        // DS402
        key(0x6060, 0) to EdsDefault(1L),
        // 厂商对象
        key(0x2600, 0) to EdsDefault(1L),
        key(0x2601, 0) to EdsDefault(1L),
        key(0x2604, 0) to EdsDefault(505L),
        key(0x2609, 0) to EdsDefault(1L),
        key(0x260A, 0) to EdsDefault(32768L),
        key(0x260B, 0) to EdsDefault(8192L),
        key(0x2614, 0) to EdsDefault(0L),
        key(0x2615, 0) to EdsDefault(1L),
        key(0x2618, 0) to EdsDefault(506L),
        key(0x2619, 0) to EdsDefault(0L),
        key(0x261C, 0) to EdsDefault(0L),
        key(0x261D, 0) to EdsDefault(7003L)
    )

    fun edsDefault(index: Int, sub: Int): EdsDefault? = EDS_DEFAULTS[key(index, sub)]

    fun edsExpected(index: Int, sub: Int, nodeId: Int): Long? = edsDefault(index, sub)?.expectedFor(nodeId)

    fun edsObject(index: Int, sub: Int): ServoCanOpenFun.EdsObject? =
        ServoCanOpenFun.EDS_OBJECTS.firstOrNull { it.index == index && it.subIndex == sub }

    fun address(index: Int, sub: Int) = "%04X-%02X".format(index, sub)

    /**
     * 通用"读值 vs EDS 默认值"比较。
     *
     * @param mismatchVerdict  值不等于 EDS 默认时给的判定：DIFF（该值不该变，如 COB-ID）或 INFO（现场可配置的值）
     * @param edsObj           用于名称/类型/位长校验；null 时只比值
     */
    fun compareToEds(
        group: Group,
        id: String,
        index: Int,
        sub: Int,
        nodeId: Int,
        outcome: SdoOutcome,
        mismatchVerdict: Verdict = Verdict.INFO,
        edsObj: ServoCanOpenFun.EdsObject? = edsObject(index, sub),
        note: String? = null
    ): CheckItem {
        val title = "${address(index, sub)} ${edsObj?.title ?: ""}".trim()
        val expectedValue = edsExpected(index, sub, nodeId)
        val expectedText = buildString {
            expectedValue?.let { append("EDS 默认 $it (0x%X)".format(it)) } ?: append("EDS 无默认值")
            edsObj?.let { append("，${it.type.byteLength}B") }
        }
        return when (outcome) {
            is SdoOutcome.Value -> {
                val lengthOk = edsObj == null || edsObj.type.byteLength == outcome.length
                val shown = if (edsObj != null) ServoCanOpenFun.formatValue(edsObj, outcome.value) else "${outcome.value}"
                val valueOk = expectedValue == null || expectedValue == outcome.value
                val verdict = when {
                    !lengthOk -> Verdict.DIFF
                    !valueOk -> mismatchVerdict
                    expectedValue == null -> Verdict.INFO
                    else -> Verdict.PASS
                }
                CheckItem(
                    group, id, title, verdict, expectedText,
                    "$shown（${outcome.length}B）",
                    listOfNotNull(
                        if (!lengthOk) "应答长度 ${outcome.length}B ≠ EDS ${edsObj!!.type.byteLength}B" else null,
                        note
                    ).joinToString("；").ifEmpty { null }
                )
            }
            SdoOutcome.Timeout -> CheckItem(group, id, title, Verdict.FAIL, expectedText, outcome.describe(), note)
            is SdoOutcome.Abort -> CheckItem(group, id, title, Verdict.DIFF, expectedText, outcome.describe(), note)
            else -> CheckItem(group, id, title, Verdict.FAIL, expectedText, outcome.describe(), note)
        }
    }

    /** PDO 映射条目解码：0x60640020 → "6064h-00 ×32bit" */
    fun decodePdoMapping(value: Long): String {
        val index = ((value ushr 16) and 0xFFFF).toInt()
        val sub = ((value ushr 8) and 0xFF).toInt()
        val bits = (value and 0xFF).toInt()
        return "%04Xh-%02X ×%dbit".format(index, sub, bits)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 未知/EDS 外对象的固件行为分类（A5 / A6 / B3）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * CiA301：访问对象字典中不存在的对象应 abort 0x06020000。
     *   abort  → PASS（按标准拒绝，且说明固件对未知对象会应答）
     *   有值/ACK → DIFF（实现了 EDS 未声明的对象：EDS 不完整）
     *   超时   → DIFF（静默忽略：既不执行也不拒绝，上位机无法区分"丢帧"与"不支持"——最麻烦的一种）
     */
    fun unknownObjectVerdict(outcome: SdoOutcome): Pair<Verdict, String> = when (outcome) {
        is SdoOutcome.Abort -> Verdict.PASS to "按 CiA301 以 abort 拒绝（${ServoCanOpenFun.abortDescription(outcome.code)}）"
        is SdoOutcome.Value -> Verdict.DIFF to "固件实现了 EDS 未声明的对象（EDS 不完整）"
        SdoOutcome.WriteAck -> Verdict.DIFF to "固件接受了对 EDS 未声明对象的写入（EDS 不完整）"
        SdoOutcome.Timeout -> Verdict.DIFF to "静默忽略：不 ACK 也不 abort，上位机无法区分丢帧与不支持"
        is SdoOutcome.Invalid -> Verdict.FAIL to "应答格式无效"
    }

    // ─────────────────────────────────────────────────────────────────────
    // 状态字 / DS402 逐步跳变（A7 / B2）
    // ─────────────────────────────────────────────────────────────────────

    /** 逐位展开 bit0~bit12（CiA402 命名），供报告对照 */
    fun statusBits(sw: Int): String {
        val names = listOf(
            0 to "RTSO", 1 to "SwOn", 2 to "OpEn", 3 to "Fault", 4 to "Volt", 5 to "QS",
            6 to "SOD", 7 to "Warn", 9 to "Remote", 10 to "TgtReached", 11 to "IntLimit", 12 to "SetpointAck"
        )
        return names.joinToString(" ") { (bit, name) -> "b$bit$name=${(sw shr bit) and 1}" }
    }

    /** B2 的一步：写入的控制字 → CiA402 期望的状态字低位（掩码 0x6F） */
    data class Ds402Step(val controlword: Int, val name: String, val expectedLow: Int) {
        val mask: Int get() = 0x006F
    }

    /**
     * 0x0006 → 0x0007 → 0x000F → 0x0007 → 0x0006：正向拉起再退回，两个方向的跳变都覆盖，
     * 结束停在 Ready to switch on（与总开关 OFF 一致，不留使能）。
     */
    val DS402_STEPS: List<Ds402Step> = listOf(
        Ds402Step(0x0006, "Shutdown → Ready to switch on", 0x0021),
        Ds402Step(0x0007, "Switch on → Switched on", 0x0023),
        Ds402Step(0x000F, "Enable operation → Operation enabled", 0x0027),
        Ds402Step(0x0007, "Disable operation → Switched on", 0x0023),
        Ds402Step(0x0006, "Shutdown → Ready to switch on", 0x0021)
    )

    /** 该步状态字是否符合 CiA402 期望；不符时说明差在哪几位 */
    fun judgeStep(step: Ds402Step, sw: Int): Pair<Verdict, String> {
        val low = sw and step.mask
        if (low == step.expectedLow) return Verdict.PASS to "低位 0x%02X 符合 CiA402".format(low)
        val diffBits = (low xor step.expectedLow)
        val bitList = (0..6).filter { (diffBits shr it) and 1 == 1 }.joinToString(",") { "bit$it" }
        return Verdict.DIFF to "低位 0x%02X ≠ 期望 0x%02X（差异 $bitList）；App 的 driveStateDescription=「%s」".format(
            low, step.expectedLow, CanOpenFun.driveStateDescription(sw)
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // 速度单位推断（C4）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 由位置差推算实际转速，与 606Ch 读值对照，回答"606Ch 的 2 字节值是不是 rpm"。
     * 100rpm ≈ 100×32768/60 ≈ 54613 脉冲/s。
     */
    fun velocityUnitEstimate(v606c: Int, deltaPulses: Int, deltaMs: Long): String {
        if (deltaMs <= 0) return "时间差无效"
        val pulsesPerSec = deltaPulses * 1000.0 / deltaMs
        val rpmFromPosition = pulsesPerSec * 60.0 / PULSES_PER_REV
        val ratio = if (v606c != 0) rpmFromPosition / v606c else Double.NaN
        val unitGuess = when {
            v606c == 0 -> "606Ch 读值为 0，无法判单位"
            ratio.isNaN() -> "无法判单位"
            ratio in 0.8..1.25 -> "606Ch ≈ rpm（比值 %.2f）".format(ratio)
            ratio in -1.25..-0.8 -> "606Ch ≈ rpm 但符号相反（比值 %.2f）".format(ratio)
            else -> "606Ch 不是 rpm（位置推算 rpm / 606Ch = %.3f）".format(ratio)
        }
        return "位置推算 %.1f 脉冲/s ≈ %.1f rpm；606Ch=%d → %s".format(pulsesPerSec, rpmFromPosition, v606c, unitGuess)
    }

    // ─────────────────────────────────────────────────────────────────────
    // RPDO1 单帧位置命令（C5）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 按 EDS 1600h 默认映射 [6040h:16][6060h:8][607Ah:32] 组 7 字节 RPDO1 载荷，CAN-ID = 0x200+Node。
     * 只有 [VerifyOptions.includeRpdo] 打开时 Runner 才会发出。
     */
    fun buildRpdo1Frame(nodeId: Int, controlword: Int, mode: Int, targetPulse: Int): ByteArray {
        val data = ByteArray(7)
        data[0] = (controlword and 0xFF).toByte()
        data[1] = ((controlword ushr 8) and 0xFF).toByte()
        data[2] = (mode and 0xFF).toByte()
        for (i in 0 until 4) data[3 + i] = ((targetPulse ushr (8 * i)) and 0xFF).toByte()
        return CanOpenFun.wrapCanFrame(0x200 + nodeId, data)
    }

    // ─────────────────────────────────────────────────────────────────────
    // C 组行程规划
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 决定 C 组的运动方向与目标，并做限位安全检查。
     *
     * 方向：已标定限位时朝 limitMin（最浅）方向；未标定则正向并提示。
     * 余量：C4 点动的 60FFh 正负与脉冲增减关系未知，最坏情况会再多走一个行程，
     *       所以已标定时要求 pos0 ± 2×travel 都落在限位内，否则换方向，两个方向都不行就拒绝运动。
     *
     * @return (direction, target) 或 null（拒绝运动）
     */
    fun planTravel(pos0: Int, travel: Int, limitsSet: Boolean, limitMin: Int, limitMax: Int): Pair<Int, Int>? {
        if (!limitsSet || limitMin == limitMax) return 1 to (pos0 + travel)
        val lo = minOf(limitMin, limitMax)
        val hi = maxOf(limitMin, limitMax)
        val preferred = if (limitMin < limitMax) -1 else 1
        for (dir in listOf(preferred, -preferred)) {
            val far = pos0 + dir * 2 * travel
            val target = pos0 + dir * travel
            if (far in lo..hi && target in lo..hi) return dir to target
        }
        return null
    }

    /** 到位容差：脉冲 100 或行程 2% 取大 */
    fun arrivalTolerance(travel: Int): Int = maxOf(100, travel / 50)
}
