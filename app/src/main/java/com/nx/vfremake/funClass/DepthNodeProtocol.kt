/**
 * @author NIANXI
 * @date 2026-11-04
 * @brief 播深检测节点 ICD v1.0 线上协议；纯 Kotlin，不依赖 Android 或 CANopen。
 */
package com.nx.vfremake.funClass

import kotlin.math.roundToInt

/** 电压保留一位小数 mV，节点从不直接输出深度。 */
object DepthNodeProtocol {
    const val ICD_VERSION = 0x10
    const val FACTORY_BASE = 0x6C0
    const val PARAM_INDEX = 0x2300
    const val READ = 1
    const val WRITE = 2
    const val NOT_FOUND = 5
    const val BURN = 30
    const val VERSION = 31
    const val CFG_RSP = 0
    const val CFG_REQ = 1
    const val STATUS = 2
    const val INFO = 3
    const val RESEARCH = 4
    const val INVALID_VOLTAGE = 0xFFFF

    /** ICD §3 位定义，STOP 按电压方向解释，不代表固定的浅/深。 */
    enum class Flag(val mask: Int) {
        FAULT(1), SAT(2), STOP_LO(4), STOP_HI(8), SETTLING(16),
        RESEARCH(32), CFG_DIRTY(64), OVERRUN(128)
    }

    data class Frame(val canId: Int, val data: ByteArray)
    data class Status(
        val uFiltMv: Float?, val uMeanMv: Float?, val spanMv: Float,
        val cfgCrc: Int, val flags: Int, val seq: Int
    ) {
        /** 判定位域；不能把有符号 Byte 直接与整数位掩码比较。 */
        fun has(flag: Flag): Boolean = flags and flag.mask != 0
    }
    data class Info(
        val icdVer: Int, val fwRev: Int, val uptimeS: Int, val vddaMv: Int,
        val mcuTempC: Int, val txDrop: Int
    ) {
        val compatible: Boolean get() = icdVer ushr 4 == ICD_VERSION ushr 4
    }
    data class CfgReply(val cmd: Int, val index: Int, val subIndex: Int, val value: Long)
    data class CoreVersion(val major: Int, val minor: Int, val build: Int)

    /** 八个工作基址；行号始终从 0 开始。 */
    fun baseForRow(row: Int): Int {
        require(row in 0..7) { "行号须为 0–7" }
        return 0x680 + row * 8
    }

    /** 整个节点段都必须吸收，包括请求回显、保留偏移和出厂节点。 */
    fun isDepthNodeId(canId: Int): Boolean = canId in 0x680..0x6C7

    /** 不接受不对齐地址，防止将 CFG_REQ 当作另一台节点的基址。 */
    fun isValidBase(base: Int): Boolean = base in 0x680..FACTORY_BASE && base % 8 == 0

    /** 从节点 CAN-ID 提取基址，段外返回 null。 */
    fun baseOf(canId: Int): Int? = if (isDepthNodeId(canId)) canId and 0x7F8 else null

    /** 出厂节点没有作业行，禁止意外绑定到第八行。 */
    fun rowOf(base: Int): Int? = if (isValidBase(base) && base < FACTORY_BASE) (base - 0x680) / 8 else null

    /** STATUS 必须恰好 8 字节；0xFFFF 单独映射为空值，0 mV 仍是合法数据。 */
    fun parseStatus(data: ByteArray): Status? = if (data.size != 8) null else Status(
        decodeMv(u16(data, 0)), decodeMv(u16(data, 2)), unsigned(data[4]) * 2f,
        unsigned(data[5]), unsigned(data[6]), unsigned(data[7])
    )

    /** MCU 温度是 i8，其他 INFO 单字节字段是 u8。 */
    fun parseInfo(data: ByteArray): Info? = if (data.size != 8) null else Info(
        unsigned(data[0]), unsigned(data[1]), u16(data, 2), u16(data, 4),
        data[6].toInt(), unsigned(data[7])
    )

    /** F/R/E 通告并非参数应答，不能叫醒正在等配置的协程。 */
    fun parseCfgReply(data: ByteArray): CfgReply? {
        if (data.size != 8 || unsigned(data[0]) !in setOf(READ, WRITE, NOT_FOUND, BURN, VERSION)) return null
        return CfgReply(unsigned(data[0]), u16(data, 1), unsigned(data[3]), u32(data, 4))
    }

    /** dingo Version 的 build 高字节在前；不套用参数值的小端解释。 */
    fun parseCoreVersion(data: ByteArray): CoreVersion? = if (
        data.size == 8 && unsigned(data[0]) == VERSION
    ) CoreVersion(unsigned(data[4]), unsigned(data[5]), unsigned(data[6]) * 256 + unsigned(data[7])) else null

    /** 参数值是线上原始 u32；Float 需要先用 floatWord() 保留 IEEE754 位型。 */
    fun cfgData(cmd: Int, index: Int = 0, subIndex: Int = 0, value: Long = 0): ByteArray {
        require(cmd in 0..255 && index in 0..0xFFFF && subIndex in 0..255 && value in 0..0xFFFFFFFFL)
        return byteArrayOf(cmd.toByte(), index.toByte(), (index ushr 8).toByte(), subIndex.toByte()) + wordBytes(value)
    }

    /** 构建发往 B+1 的 Read/Write 请求，线上值校验由调用方按参数表执行。 */
    fun cfgRequest(base: Int, cmd: Int, index: Int = 0, subIndex: Int = 0, value: Long = 0): Frame {
        require(isValidBase(base)) { "节点基址不合法" }
        return Frame(base + CFG_REQ, cfgData(cmd, index, subIndex, value))
    }

    /** ICD §6 的 Burn 魔数，不使用 CANopen 1010h 保存协议。 */
    fun burnRequest(base: Int): Frame = cfgRequest(base, BURN, 0x0301, 8)

    /** CRC-8/SMBUS：poly=0x07，init/xorout=0，不反射。 */
    fun crc8(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor unsigned(byte)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x80 != 0) 0x07 else 0) and 0xFF }
        }
        return crc
    }

    /** CRC 输入是 sub0–8 每项四字节线上值，共 36 字节，而非 Kotlin 文本表示。 */
    fun filterCrc(words: List<Long>): Int {
        require(words.size == 9)
        return crc8(words.flatMap { wordBytes(it).toList() }.toByteArray())
    }

    /** ICD 默认滤波配置的原始 u32 值，不代表真实节点已经使用它。 */
    val DEFAULT_FILTER_WORDS = listOf(9L, 50L, 2L, 10L,
        floatWord(0.15f), floatWord(2f), floatWord(100f), floatWord(4f), floatWord(3f))

    /** 只改变 ICD 指定的直出两项，其余保持默认，得到固定 CRC 0xD1。 */
    val DIRECT_FILTER_WORDS = DEFAULT_FILTER_WORDS.toMutableList().also { it[0] = 1; it[2] = 0 }.toList()

    /** 返回 Float 对应的无符号线上位型。 */
    fun floatWord(value: Float): Long = value.toRawBits().toLong() and 0xFFFFFFFFL

    /** 由线上位型恢复 Float，不作整数数值转换。 */
    fun wordFloat(value: Long): Float = Float.fromBits(value.toInt())

    /** u32 按小端编码，非法范围拒绝而非截断。 */
    fun wordBytes(value: Long): ByteArray {
        require(value in 0..0xFFFFFFFFL)
        return ByteArray(4) { (value ushr (it * 8)).toByte() }
    }

    /** 标准化 STATUS 实际周期，ICD 指定先按 10 ms 四舍五入。 */
    fun actualReportMs(requestedMs: Int): Int = ((requestedMs / 10.0).roundToInt().coerceIn(1, 100)) * 10

    /** 模拟器与金标准共用编码；无效值用保留码，有限电压不会编码成 0xFFFF。 */
    fun statusData(filteredMv: Float?, meanMv: Float?, spanMv: Float, crc: Int, flags: Int, seq: Int): ByteArray {
        val f = encodeMv(filteredMv); val m = encodeMv(meanMv)
        return byteArrayOf(f.toByte(), (f ushr 8).toByte(), m.toByte(), (m ushr 8).toByte(),
            (spanMv / 2).roundToInt().coerceIn(0, 255).toByte(), crc.toByte(), flags.toByte(), seq.toByte())
    }

    /** INFO 生成仅用于功能模拟与金标准，不伪造真实固件的回读。 */
    fun infoData(icdVer: Int, fwRev: Int, uptimeS: Int, vddaMv: Int, tempC: Int, drops: Int): ByteArray =
        byteArrayOf(icdVer.toByte(), fwRev.toByte(), uptimeS.toByte(), (uptimeS ushr 8).toByte(),
            vddaMv.toByte(), (vddaMv ushr 8).toByte(), tempC.toByte(), drops.toByte())

    /** 生成研究帧供模拟与测试；生产接收不解析三个样本。 */
    fun researchData(idx: Int, samplesMv: List<Float?>): ByteArray {
        require(samplesMv.size == 3)
        return byteArrayOf(idx.toByte(), (idx ushr 8).toByte()) + samplesMv.flatMap {
            val value = encodeMv(it); listOf(value.toByte(), (value ushr 8).toByte())
        }.toByteArray()
    }

    private fun unsigned(byte: Byte): Int = byte.toInt() and 0xFF
    private fun u16(data: ByteArray, offset: Int): Int = unsigned(data[offset]) or (unsigned(data[offset + 1]) shl 8)
    private fun u32(data: ByteArray, offset: Int): Long = (0..3).fold(0L) { result, i ->
        result or (unsigned(data[offset + i]).toLong() shl (i * 8))
    }
    private fun decodeMv(value: Int): Float? = if (value == INVALID_VOLTAGE) null else value / 10f
    private fun encodeMv(value: Float?): Int = if (value == null || !value.isFinite()) INVALID_VOLTAGE
        else (value.toDouble() * 10).coerceIn(0.0, 65534.0).roundToInt()
}
