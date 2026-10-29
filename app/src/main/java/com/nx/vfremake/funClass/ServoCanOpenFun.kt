package com.nx.vfremake.funClass

/**
 * YZ-AIM 播种深度伺服的 EDS 语义层。
 *
 * 对象目录逐项来自 docs/YZ_MOTOR_SN2.eds。这里只收录可用 expedited SDO
 * 访问的数字 VAR/子对象；RECORD/ARRAY 容器本身不作为可读写项列出。
 */
object ServoCanOpenFun {

    enum class EdsDataType(val byteLength: Int, val signed: Boolean) {
        I8(1, true), I16(2, true), I32(4, true),
        U8(1, false), U16(2, false), U32(4, false)
    }

    enum class EdsAccess { RO, RW }

    /**
     * UI 写入策略。BLOCKED 对象仍可读取，但不允许从通用调试控制台修改。
     * 这些对象会改变 CAN 通信、节点地址、电子齿轮、限流或持久参数，误写可能导致失联或机械危险。
     */
    enum class WritePolicy { NONE, OPERATIONAL, CONFIGURATION, BLOCKED }

    data class EdsObject(
        val key: String,
        val index: Int,
        val subIndex: Int,
        val title: String,
        val type: EdsDataType,
        val access: EdsAccess,
        val group: String,
        val writePolicy: WritePolicy = if (access == EdsAccess.RO) WritePolicy.NONE else WritePolicy.CONFIGURATION
    ) {
        val address: String
            get() = "%04X-%02X".format(index, subIndex)
    }

    private fun ro(key: String, index: Int, sub: Int, title: String, type: EdsDataType, group: String) =
        EdsObject(key, index, sub, title, type, EdsAccess.RO, group, WritePolicy.NONE)

    private fun rw(
        key: String,
        index: Int,
        sub: Int,
        title: String,
        type: EdsDataType,
        group: String,
        policy: WritePolicy = WritePolicy.CONFIGURATION
    ) = EdsObject(key, index, sub, title, type, EdsAccess.RW, group, policy)

    /** EDS 中全部可 expedited SDO 访问的数字对象。 */
    val EDS_OBJECTS: List<EdsObject> = listOf(
        ro("deviceType", 0x1000, 0x00, "设备类型", EdsDataType.U32, "设备身份"),
        ro("errorRegister", 0x1001, 0x00, "错误寄存器", EdsDataType.U8, "设备身份"),
        ro("identityCount", 0x1018, 0x00, "身份子项数", EdsDataType.U8, "设备身份"),
        ro("vendorId", 0x1018, 0x01, "厂商 ID", EdsDataType.U32, "设备身份"),
        ro("productCode", 0x1018, 0x02, "产品代码", EdsDataType.U32, "设备身份"),
        ro("revision", 0x1018, 0x03, "修订号", EdsDataType.U32, "设备身份"),
        ro("serialNumber", 0x1018, 0x04, "序列号", EdsDataType.U32, "设备身份"),

        rw("syncCobId", 0x1005, 0x00, "SYNC COB-ID", EdsDataType.U32, "通信", WritePolicy.BLOCKED),
        rw("communicationCycle", 0x1006, 0x00, "通信周期", EdsDataType.U32, "通信"),
        ro("consumerHeartbeatCount", 0x1016, 0x00, "消费心跳子项数", EdsDataType.U8, "通信"),
        rw("consumerHeartbeat", 0x1016, 0x01, "消费心跳时间", EdsDataType.U32, "通信"),
        rw("producerHeartbeat", 0x1017, 0x00, "生产心跳时间(ms)", EdsDataType.U16, "通信"),

        ro("rpdo1ParamCount", 0x1400, 0x00, "RPDO1 参数子项数", EdsDataType.U8, "PDO"),
        rw("rpdo1CobId", 0x1400, 0x01, "RPDO1 COB-ID", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("rpdo1Type", 0x1400, 0x02, "RPDO1 传输类型", EdsDataType.U8, "PDO"),
        ro("rpdo2ParamCount", 0x1401, 0x00, "RPDO2 参数子项数", EdsDataType.U8, "PDO"),
        rw("rpdo2CobId", 0x1401, 0x01, "RPDO2 COB-ID", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("rpdo2Type", 0x1401, 0x02, "RPDO2 传输类型", EdsDataType.U8, "PDO"),
        ro("rpdo3ParamCount", 0x1402, 0x00, "RPDO3 参数子项数", EdsDataType.U8, "PDO"),
        rw("rpdo3CobId", 0x1402, 0x01, "RPDO3 COB-ID", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("rpdo3Type", 0x1402, 0x02, "RPDO3 传输类型", EdsDataType.U8, "PDO"),
        ro("rpdo4ParamCount", 0x1403, 0x00, "RPDO4 参数子项数", EdsDataType.U8, "PDO"),
        rw("rpdo4CobId", 0x1403, 0x01, "RPDO4 COB-ID", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("rpdo4Type", 0x1403, 0x02, "RPDO4 传输类型", EdsDataType.U8, "PDO"),

        rw("rpdo1MapCount", 0x1600, 0x00, "RPDO1 映射数量", EdsDataType.U8, "PDO", WritePolicy.BLOCKED),
        rw("rpdo1Map1", 0x1600, 0x01, "RPDO1 映射 1", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("rpdo1Map2", 0x1600, 0x02, "RPDO1 映射 2", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("rpdo1Map3", 0x1600, 0x03, "RPDO1 映射 3", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("rpdo2MapCount", 0x1601, 0x00, "RPDO2 映射数量", EdsDataType.U8, "PDO", WritePolicy.BLOCKED),
        rw("rpdo2Map1", 0x1601, 0x01, "RPDO2 映射 1", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("rpdo2Map2", 0x1601, 0x02, "RPDO2 映射 2", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("rpdo3MapCount", 0x1602, 0x00, "RPDO3 映射数量", EdsDataType.U8, "PDO", WritePolicy.BLOCKED),
        rw("rpdo3Map1", 0x1602, 0x01, "RPDO3 映射 1", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("rpdo3Map2", 0x1602, 0x02, "RPDO3 映射 2", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("rpdo3Map3", 0x1602, 0x03, "RPDO3 映射 3", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("rpdo4MapCount", 0x1603, 0x00, "RPDO4 映射数量", EdsDataType.U8, "PDO", WritePolicy.BLOCKED),
        rw("rpdo4Map1", 0x1603, 0x01, "RPDO4 映射 1", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),

        ro("tpdo1ParamCount", 0x1800, 0x00, "TPDO1 参数子项数", EdsDataType.U8, "PDO"),
        rw("tpdo1CobId", 0x1800, 0x01, "TPDO1 COB-ID", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("tpdo1Type", 0x1800, 0x02, "TPDO1 传输类型", EdsDataType.U8, "PDO"),
        rw("tpdo1Inhibit", 0x1800, 0x03, "TPDO1 禁止时间(100μs)", EdsDataType.U16, "PDO"),
        rw("tpdo1Compat", 0x1800, 0x04, "TPDO1 兼容项", EdsDataType.U8, "PDO"),
        rw("tpdo1Event", 0x1800, 0x05, "TPDO1 事件周期(ms)", EdsDataType.U16, "PDO"),
        ro("tpdo2ParamCount", 0x1801, 0x00, "TPDO2 参数子项数", EdsDataType.U8, "PDO"),
        rw("tpdo2CobId", 0x1801, 0x01, "TPDO2 COB-ID", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("tpdo2Type", 0x1801, 0x02, "TPDO2 传输类型", EdsDataType.U8, "PDO"),
        rw("tpdo2Inhibit", 0x1801, 0x03, "TPDO2 禁止时间(100μs)", EdsDataType.U16, "PDO"),
        rw("tpdo2Compat", 0x1801, 0x04, "TPDO2 兼容项", EdsDataType.U8, "PDO"),
        rw("tpdo2Event", 0x1801, 0x05, "TPDO2 事件周期(ms)", EdsDataType.U16, "PDO"),
        ro("tpdo3ParamCount", 0x1802, 0x00, "TPDO3 参数子项数", EdsDataType.U8, "PDO"),
        rw("tpdo3CobId", 0x1802, 0x01, "TPDO3 COB-ID", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("tpdo3Type", 0x1802, 0x02, "TPDO3 传输类型", EdsDataType.U8, "PDO"),
        rw("tpdo3Inhibit", 0x1802, 0x03, "TPDO3 禁止时间(100μs)", EdsDataType.U16, "PDO"),
        rw("tpdo3Compat", 0x1802, 0x04, "TPDO3 兼容项", EdsDataType.U8, "PDO"),
        rw("tpdo3Event", 0x1802, 0x05, "TPDO3 事件周期(ms)", EdsDataType.U16, "PDO"),
        ro("tpdo4ParamCount", 0x1803, 0x00, "TPDO4 参数子项数", EdsDataType.U8, "PDO"),
        rw("tpdo4CobId", 0x1803, 0x01, "TPDO4 COB-ID", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("tpdo4Type", 0x1803, 0x02, "TPDO4 传输类型", EdsDataType.U8, "PDO"),
        rw("tpdo4Inhibit", 0x1803, 0x03, "TPDO4 禁止时间(100μs)", EdsDataType.U16, "PDO"),
        rw("tpdo4Compat", 0x1803, 0x04, "TPDO4 兼容项", EdsDataType.U8, "PDO"),
        rw("tpdo4Event", 0x1803, 0x05, "TPDO4 事件周期(ms)", EdsDataType.U16, "PDO"),

        rw("tpdo1MapCount", 0x1A00, 0x00, "TPDO1 映射数量", EdsDataType.U8, "PDO", WritePolicy.BLOCKED),
        rw("tpdo1Map1", 0x1A00, 0x01, "TPDO1 映射 1", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("tpdo1Map2", 0x1A00, 0x02, "TPDO1 映射 2", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("tpdo2MapCount", 0x1A01, 0x00, "TPDO2 映射数量", EdsDataType.U8, "PDO", WritePolicy.BLOCKED),
        rw("tpdo2Map1", 0x1A01, 0x01, "TPDO2 映射 1", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("tpdo2Map2", 0x1A01, 0x02, "TPDO2 映射 2", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("tpdo3MapCount", 0x1A02, 0x00, "TPDO3 映射数量", EdsDataType.U8, "PDO", WritePolicy.BLOCKED),
        rw("tpdo3Map1", 0x1A02, 0x01, "TPDO3 映射 1", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("tpdo3Map2", 0x1A02, 0x02, "TPDO3 映射 2", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("tpdo4MapCount", 0x1A03, 0x00, "TPDO4 映射数量", EdsDataType.U8, "PDO", WritePolicy.BLOCKED),
        rw("tpdo4Map1", 0x1A03, 0x01, "TPDO4 映射 1", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),
        rw("tpdo4Map2", 0x1A03, 0x02, "TPDO4 映射 2", EdsDataType.U32, "PDO", WritePolicy.BLOCKED),

        rw("controlword", 0x6040, 0x00, "控制字", EdsDataType.U16, "DS402", WritePolicy.OPERATIONAL),
        ro("statusword", 0x6041, 0x00, "状态字", EdsDataType.U16, "DS402"),
        rw("mode", 0x6060, 0x00, "工作模式", EdsDataType.I8, "DS402", WritePolicy.OPERATIONAL),
        ro("actualPosition", 0x6064, 0x00, "实际位置", EdsDataType.I32, "DS402"),
        ro("actualVelocity", 0x606C, 0x00, "实际速度", EdsDataType.I32, "DS402"),
        ro("actualCurrent", 0x6078, 0x00, "实际电流", EdsDataType.I16, "DS402"),
        rw("targetPosition", 0x607A, 0x00, "目标位置", EdsDataType.I32, "DS402", WritePolicy.OPERATIONAL),
        rw("profileVelocity", 0x6081, 0x00, "轮廓速度", EdsDataType.U32, "DS402", WritePolicy.OPERATIONAL),
        rw("profileAcceleration", 0x6083, 0x00, "轮廓加速度", EdsDataType.U32, "DS402", WritePolicy.OPERATIONAL),
        ro("velocityGainCount", 0x60F9, 0x00, "速度环子项数", EdsDataType.U8, "DS402"),
        rw("velocityPGain", 0x60F9, 0x01, "速度环 P 增益", EdsDataType.I16, "DS402"),
        rw("velocityIGain", 0x60F9, 0x02, "速度环 I 增益", EdsDataType.I16, "DS402"),
        ro("positionGainCount", 0x60FB, 0x00, "位置环子项数", EdsDataType.U8, "DS402"),
        rw("positionPGain", 0x60FB, 0x01, "位置环 P 增益", EdsDataType.I16, "DS402"),
        rw("targetVelocity", 0x60FF, 0x00, "目标速度", EdsDataType.I32, "DS402", WritePolicy.OPERATIONAL),

        rw("modbusEnable", 0x2600, 0x00, "Modbus 使能", EdsDataType.U16, "厂商对象", WritePolicy.BLOCKED),
        rw("driverEnable", 0x2601, 0x00, "驱动器使能", EdsDataType.U16, "厂商对象", WritePolicy.CONFIGURATION),
        rw("weakMagneticAngle", 0x2604, 0x00, "弱磁角度", EdsDataType.U16, "厂商对象", WritePolicy.BLOCKED),
        rw("direction", 0x2609, 0x00, "方向", EdsDataType.U16, "厂商对象", WritePolicy.BLOCKED),
        rw("gearNumerator", 0x260A, 0x00, "电子齿轮分子", EdsDataType.U16, "厂商对象", WritePolicy.BLOCKED),
        rw("gearDenominator", 0x260B, 0x00, "电子齿轮分母", EdsDataType.U16, "厂商对象", WritePolicy.BLOCKED),
        ro("incrementalPosition", 0x260C, 0x00, "增量位置", EdsDataType.I32, "厂商对象"),
        ro("manufacturerError", 0x260E, 0x00, "厂商错误码", EdsDataType.U16, "厂商对象"),
        ro("temperature", 0x2612, 0x00, "系统温度", EdsDataType.U16, "厂商对象"),
        ro("pwm", 0x2613, 0x00, "PWM", EdsDataType.I16, "厂商对象"),
        rw("saveFlag", 0x2614, 0x00, "参数保存标志", EdsDataType.U16, "厂商对象", WritePolicy.BLOCKED),
        rw("deviceAddress", 0x2615, 0x00, "设备地址", EdsDataType.U16, "厂商对象", WritePolicy.BLOCKED),
        rw("staticMaxOutput", 0x2618, 0x00, "静态最大输出", EdsDataType.U16, "厂商对象", WritePolicy.BLOCKED),
        rw("specialFunction", 0x2619, 0x00, "特殊功能", EdsDataType.U16, "厂商对象", WritePolicy.BLOCKED),
        rw("canControlword", 0x261C, 0x00, "CAN 通信控制字", EdsDataType.U16, "厂商对象", WritePolicy.BLOCKED),
        rw("maxCurrent", 0x261D, 0x00, "最大允许电流", EdsDataType.U16, "厂商对象", WritePolicy.BLOCKED)
    )

    val QUICK_DIAGNOSTIC_OBJECTS: List<EdsObject> = listOf(
        "deviceType", "errorRegister", "vendorId", "productCode", "revision", "serialNumber",
        "producerHeartbeat", "controlword", "statusword", "mode", "actualPosition",
        "actualVelocity", "actualCurrent", "manufacturerError", "temperature", "pwm",
        "driverEnable", "deviceAddress", "canControlword", "gearNumerator", "gearDenominator"
    ).map { key -> EDS_OBJECTS.first { it.key == key } }

    data class SdoReply(
        val command: Int,
        val index: Int,
        val subIndex: Int,
        val value: Long? = null,
        val dataLength: Int = 0,
        val abortCode: Long? = null
    )

    fun parseSdoReply(data: ByteArray): SdoReply? {
        if (data.size < 8) return null
        val command = data[0].toInt() and 0xFF
        val index = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        val subIndex = data[3].toInt() and 0xFF
        if (command == 0x80) {
            return SdoReply(command, index, subIndex, abortCode = littleEndianValue(data, 4))
        }
        if (command == 0x60) return SdoReply(command, index, subIndex)
        val length = when (command) {
            0x4F -> 1
            0x4B -> 2
            0x47 -> 3
            0x43 -> 4
            else -> return null
        }
        return SdoReply(command, index, subIndex, littleEndianValue(data, length), length)
    }

    private fun littleEndianValue(data: ByteArray, length: Int): Long {
        var value = 0L
        repeat(length) { i -> value = value or ((data[4 + i].toLong() and 0xFF) shl (8 * i)) }
        return value
    }

    fun buildReadObjectFrame(nodeId: Int, obj: EdsObject): ByteArray =
        CanOpenFun.buildSdoReadFrame(nodeId, obj.index, obj.subIndex)

    fun buildWriteObjectFrame(nodeId: Int, obj: EdsObject, value: Long): ByteArray {
        require(obj.access == EdsAccess.RW) { "${obj.address} 是只读对象" }
        require(obj.writePolicy != WritePolicy.BLOCKED) { "${obj.address} 属于高风险配置，通用控制台禁止写入" }
        require(valueFits(obj, value)) { "写入值超出 ${obj.type} 范围" }
        return CanOpenFun.buildSdoWriteFrame(nodeId, obj.index, obj.subIndex, obj.type.byteLength, value)
    }

    fun valueFits(obj: EdsObject, value: Long): Boolean = when (obj.type) {
        EdsDataType.I8 -> value in Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong()
        EdsDataType.I16 -> value in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()
        EdsDataType.I32 -> value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        EdsDataType.U8 -> value in 0L..0xFFL
        EdsDataType.U16 -> value in 0L..0xFFFFL
        EdsDataType.U32 -> value in 0L..0xFFFFFFFFL
    }

    fun parseInput(text: String): Long? {
        val value = text.trim()
        return when {
            value.startsWith("-0x", ignoreCase = true) ->
                value.substring(3).toLongOrNull(16)?.let { -it }
            value.startsWith("0x", ignoreCase = true) -> value.substring(2).toLongOrNull(16)
            else -> value.toLongOrNull()
        }
    }

    fun formatValue(obj: EdsObject, rawValue: Long): String {
        val decimal = when (obj.type) {
            EdsDataType.I8 -> rawValue.toByte().toString()
            EdsDataType.I16 -> rawValue.toShort().toString()
            EdsDataType.I32 -> rawValue.toInt().toString()
            else -> rawValue.toString()
        }
        val mask = when (obj.type.byteLength) {
            1 -> 0xFFL
            2 -> 0xFFFFL
            else -> 0xFFFFFFFFL
        }
        val hex = (rawValue and mask).toString(16).uppercase().padStart(obj.type.byteLength * 2, '0')
        return "$decimal / 0x$hex"
    }

    fun modeDescription(value: Long): String = when (value.toByte().toInt()) {
        1 -> "轮廓位置模式"
        3 -> "轮廓速度模式"
        6 -> "回零模式"
        else -> "模式 ${value.toByte().toInt()}"
    }

    fun abortDescription(code: Long): String = when (code and 0xFFFFFFFFL) {
        0x05030000L -> "切换位未交替"
        0x05040000L -> "SDO 协议超时"
        0x05040001L -> "命令符无效或未知"
        0x06010000L -> "不支持访问该对象"
        0x06010001L -> "试图读取只写对象"
        0x06010002L -> "试图写入只读对象"
        0x06020000L -> "对象字典中不存在该对象"
        0x06040041L -> "对象不能映射到 PDO"
        0x06040042L -> "PDO 映射长度超限"
        0x06040043L -> "参数不兼容"
        0x06060000L -> "硬件错误导致访问失败"
        0x06070010L -> "数据类型或长度不匹配"
        0x06070012L -> "数据类型不匹配：长度过长"
        0x06070013L -> "数据类型不匹配：长度过短"
        0x06090011L -> "子索引不存在"
        0x06090030L -> "参数值范围超限"
        0x06090031L -> "参数值过大"
        0x06090032L -> "参数值过小"
        0x08000000L -> "一般性错误"
        0x08000020L -> "数据无法传输或保存"
        0x08000021L -> "本地控制中，无法传输或保存"
        // 改 PDO 通信参数（1800h 组）时最常见：多数驱动器要求先进入预运行，
        // 或先把该 PDO 置为无效（COB-ID Bit31=1）再改子索引 02/05。
        0x08000022L -> "当前设备状态下无法传输或保存（先切预运行或先禁用该 PDO）"
        else -> "未知中止码"
    }

    /** EDS 身份默认值：Vendor=817、Product=1、Revision=256；序列号因设备而异。 */
    fun identityMatches(values: Map<String, Long>): Boolean =
        values["vendorId"] == 817L && values["productCode"] == 1L && values["revision"] == 256L

    // ─────────────────────────────────────────────────────────────────────
    // TPDO 上报周期（1800h~1803h 子索引 05 Event Timer）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * TPDO 通信参数基地址：TPDO1~4 → 1800h~1803h。
     * 子索引 02 = 传输类型，05 = 事件周期（Event Timer，单位 ms，U16）。
     */
    const val OD_TPDO_PARAM_BASE = 0x1800
    const val SUB_TPDO_COB_ID = 0x01
    const val SUB_TPDO_TRANSMISSION_TYPE = 0x02
    const val SUB_TPDO_EVENT_TIMER = 0x05

    /**
     * 2614h 参数保存标志：写 1 触发保存，读回 2 表示保存完毕
     * （docs/SOWING_DEPTH_IMPLEMENTATION.md 对象表）。
     *
     * 【为什么不走通用控制台】2614h 在 [EDS_OBJECTS] 中是 BLOCKED：它会把驱动器
     * 当前【全部】参数一次性写进 EEPROM，调试中随手改坏的值也会一起被固化。
     * 只有本文件的专用保存路径（周期写入并读回校验成功之后、且用户二次确认）
     * 才允许发出这一帧。
     */
    const val OD_SAVE_FLAG = 0x2614
    const val SAVE_FLAG_TRIGGER = 1L
    const val SAVE_FLAG_DONE = 2L

    /**
     * 一路 TPDO 通道的静态描述（取自 docs/YZ_MOTOR_SN2.eds 的 1800h/1A00h 组）。
     *
     * @param dataBytes       映射出的 CAN 数据段长度，用于估算 UART 桥带宽
     * @param defaultEventMs  EDS 出厂事件周期；0 表示出厂不周期上报
     */
    data class TpdoChannel(
        val pdo: Int,
        val paramIndex: Int,
        val cobIdBase: Int,
        val mapping: String,
        val dataBytes: Int,
        val defaultEventMs: Int
    ) {
        val label: String get() = "TPDO$pdo"
    }

    /**
     * EDS 出厂状态：TPDO1（6064h+6041h，本 App 唯一解析的通道）与 TPDO3
     * （6064h+606Ch）都是 255 异步 + 100ms 周期上报，TPDO2/4 周期为 0 不上报。
     * 也就是说出厂每台伺服实际占用两条 100ms 流——TPDO3 的数据被
     * CanReceiveCoroutine 吸收丢弃，把它关掉可直接省出一半深度伺服带宽。
     */
    val TPDO_CHANNELS: List<TpdoChannel> = listOf(
        TpdoChannel(1, 0x1800, 0x180, "6064h 实际位置 + 6041h 状态字", 6, 100),
        TpdoChannel(2, 0x1801, 0x280, "6064h 实际位置 + 6041h 状态字", 6, 0),
        TpdoChannel(3, 0x1802, 0x380, "6064h 实际位置 + 606Ch 实际速度", 8, 100),
        TpdoChannel(4, 0x1803, 0x480, "6064h 实际位置 + 6041h 状态字", 6, 0)
    )

    /** 现场常用上报周期档位（ms）；0 = 关闭该通道的周期上报。 */
    val TPDO_EVENT_PRESETS_MS = listOf(0, 10, 20, 50, 100, 200)

    const val TPDO_EVENT_MS_MIN = 0
    const val TPDO_EVENT_MS_MAX = 0xFFFF

    fun tpdoChannel(pdo: Int): TpdoChannel =
        TPDO_CHANNELS.firstOrNull { it.pdo == pdo } ?: error("TPDO 序号必须为 1~4，当前值: $pdo")

    /** 读 1800h+n-1 子索引 05（当前事件周期，ms）。 */
    fun buildReadTpdoEventTimerFrame(nodeId: Int, pdo: Int): ByteArray =
        CanOpenFun.buildSdoReadFrame(nodeId, tpdoChannel(pdo).paramIndex, SUB_TPDO_EVENT_TIMER)

    /** 读 1800h+n-1 子索引 02（传输类型）。 */
    fun buildReadTpdoTransmissionTypeFrame(nodeId: Int, pdo: Int): ByteArray =
        CanOpenFun.buildSdoReadFrame(nodeId, tpdoChannel(pdo).paramIndex, SUB_TPDO_TRANSMISSION_TYPE)

    /**
     * 写 1800h+n-1 子索引 05 = [ms]（U16，CS=0x2B）：改 TPDO 主动上报周期。
     *
     * 只在传输类型为 254/255（异步）时生效；同步传输类型下上报由 SYNC 驱动，
     * 事件周期被忽略——调用方应先读回传输类型并提示。
     */
    fun buildSetTpdoEventTimerFrame(nodeId: Int, pdo: Int, ms: Int): ByteArray {
        require(ms in TPDO_EVENT_MS_MIN..TPDO_EVENT_MS_MAX) {
            "TPDO 事件周期必须在 $TPDO_EVENT_MS_MIN~$TPDO_EVENT_MS_MAX ms 内，当前值: $ms"
        }
        return CanOpenFun.buildSdoWriteFrame(
            nodeId, tpdoChannel(pdo).paramIndex, SUB_TPDO_EVENT_TIMER, 2, ms.toLong()
        )
    }

    /**
     * COB-ID 子索引 01 的 Bit31 = PDO 无效位（CiA301）：
     *   Bit31 = 0 → 该 PDO 有效，按传输类型/事件周期收发
     *   Bit31 = 1 → 该 PDO 无效，驱动器完全不再发送该报文
     * 只把事件周期写 0 未必能让所有设备闭嘴（仍可能按内部事件发），
     * 真正的"关闭"要靠这一位。
     */
    const val PDO_COB_ID_INVALID_BIT = 0x80000000L

    /** 该 PDO 当前是否有效（Bit31 = 0）。 */
    fun isPdoEnabled(cobIdValue: Long): Boolean = (cobIdValue and PDO_COB_ID_INVALID_BIT) == 0L

    /** COB-ID 低 11 位，即这条 PDO 实际使用的 CAN 报文 ID。 */
    fun cobIdCanId(cobIdValue: Long): Int = (cobIdValue and 0x7FFL).toInt()

    /**
     * 这条 PDO 的报文 ID 是否仍是 EDS 默认的 base + Node-ID
     * （TPDO1~4 → 0x180/0x280/0x380/0x480 + Node-ID）。
     *
     * CanReceiveCoroutine.classifyCanOpen 是按这套 base 段路由的：报文 ID 被改到
     * 别处后，即使开启也不会被正确认领，所以开启前必须先核对。
     */
    fun cobIdMatchesDefault(cobIdValue: Long, nodeId: Int, pdo: Int): Boolean =
        cobIdCanId(cobIdValue) == tpdoChannel(pdo).cobIdBase + nodeId

    /**
     * 由【读回的】COB-ID 值算出开启/关闭后要写回的值：只翻 Bit31，其余位原样保留。
     *
     * 【为什么必须读-改-写】低 11 位是这台驱动器真实在用的报文 ID，现场可能被上位机
     * 改过。凭 0x380+NodeID 猜一个值写回去，会在没关掉旧 ID 的同时把 PDO 挪到新
     * 报文 ID 上——接收路由跟着失配，比不改更糟。
     */
    fun tpdoCobIdWithEnabled(currentCobId: Long, enabled: Boolean): Long =
        if (enabled) currentCobId and PDO_COB_ID_INVALID_BIT.inv() and 0xFFFFFFFFL
        else (currentCobId or PDO_COB_ID_INVALID_BIT) and 0xFFFFFFFFL

    /** 读 1800h+n-1 子索引 01（COB-ID，含 Bit31 有效位）。 */
    fun buildReadTpdoCobIdFrame(nodeId: Int, pdo: Int): ByteArray =
        CanOpenFun.buildSdoReadFrame(nodeId, tpdoChannel(pdo).paramIndex, SUB_TPDO_COB_ID)

    /** 写 1800h+n-1 子索引 01 = [cobIdValue]（U32，CS=0x23）：只应写入读-改-写算出的值。 */
    fun buildSetTpdoCobIdFrame(nodeId: Int, pdo: Int, cobIdValue: Long): ByteArray {
        require(cobIdValue in 0L..0xFFFFFFFFL) { "COB-ID 必须是 32 位无符号值，当前值: $cobIdValue" }
        return CanOpenFun.buildSdoWriteFrame(
            nodeId, tpdoChannel(pdo).paramIndex, SUB_TPDO_COB_ID, 4, cobIdValue
        )
    }

    /**
     * 触发参数掉电保存：写 2614h = 1（U16，CS=0x2B）。
     * 【前缀安全】多帧序列中必须放最后；发出后应轮询读回 2614h 直到 [SAVE_FLAG_DONE]。
     */
    fun buildSaveParametersFrame(nodeId: Int): ByteArray =
        CanOpenFun.buildSdoWriteFrame(nodeId, OD_SAVE_FLAG, 0x00, 2, SAVE_FLAG_TRIGGER)

    /** 读 2614h 保存标志：2 = 保存完毕。 */
    fun buildReadSaveFlagFrame(nodeId: Int): ByteArray =
        CanOpenFun.buildSdoReadFrame(nodeId, OD_SAVE_FLAG, 0x00)

    /** 传输类型 254/255 为异步（事件触发），此时 Event Timer 才决定上报周期。 */
    fun isAsyncTransmissionType(value: Long): Boolean = (value and 0xFF) in 254L..255L

    fun transmissionTypeDescription(value: Long): String = when (val t = (value and 0xFF).toInt()) {
        0 -> "同步非周期（SYNC 后按事件发）"
        in 1..240 -> "同步周期（每 $t 个 SYNC 发一次）"
        252 -> "同步 RTR 轮询"
        253 -> "异步 RTR 轮询"
        254, 255 -> "异步事件触发（事件周期生效）"
        else -> "保留值 $t"
    }

    // ─────────────────────────────────────────────────────────────────────
    // UART 桥带宽估算
    // ─────────────────────────────────────────────────────────────────────

    /**
     * CSM100T 桥的 UART 侧固定 115200 8N1：每字节 10 bit（1 起始 + 8 数据 + 1 停止）
     * → 11520 字节/秒。CAN 侧 500kbps 远快于 UART，瓶颈永远在串口这一段。
     */
    const val BRIDGE_BYTES_PER_SEC = 11520.0

    /** 串口帧长 = 帧头1 + 长度1 + 帧信息1 + CAN-ID 2 + 数据 N + 帧尾1 = N + 6（见 CanOpenFun.wrapCanFrame）。 */
    fun bridgeFrameBytes(dataBytes: Int): Int = dataBytes + 6

    /** 一条周期上报流：[nodeCount] 个节点，各自每 [periodMs] 毫秒发一帧 [dataBytes] 字节数据。 */
    data class TpdoStream(val nodeCount: Int, val dataBytes: Int, val periodMs: Int)

    data class BridgeLoad(val framesPerSec: Double, val bytesPerSec: Double) {
        /** 相对 115200 8N1 理论吞吐的占用率（0~1+）。 */
        val utilization: Double get() = bytesPerSec / BRIDGE_BYTES_PER_SEC
    }

    /** 估算若干周期上报流对 UART 桥的占用；periodMs<=0 或 nodeCount<=0 的流不计入。 */
    fun estimateBridgeLoad(streams: List<TpdoStream>): BridgeLoad {
        var frames = 0.0
        var bytes = 0.0
        streams.forEach { s ->
            if (s.periodMs <= 0 || s.nodeCount <= 0) return@forEach
            val fps = s.nodeCount * 1000.0 / s.periodMs
            frames += fps
            bytes += fps * bridgeFrameBytes(s.dataBytes)
        }
        return BridgeLoad(frames, bytes)
    }

    /**
     * 带宽占用提示。阈值留足余量：周期上报之外还有 SDO 问答、施肥电机帧和
     * 摆臂编码器 TPDO 共用这条 115200 串口，跑满会先表现为 SDO 超时和点动失灵。
     */
    fun bridgeLoadWarning(load: BridgeLoad): String? = when {
        load.utilization >= 0.85 -> "✗ 已超出 UART 桥承载能力，会造成 SDO 超时、丢帧、点动失灵"
        load.utilization >= 0.60 -> "⚠ 占用偏高，叠加施肥帧与编码器上报后余量很小"
        load.utilization >= 0.35 -> "· 占用中等，仍有余量"
        else -> null
    }

    // ─────────────────────────────────────────────────────────────────────
    // TPDO 周期核验
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 核验的三层，缺一层结论就不成立：
     *   L1 对象值——SDO 回读 1800h 子索引 05 是否等于下发值（证明写进去了）
     *   L2 生效条件——传输类型 254/255 且 COB-ID Bit31=0（证明这个值会被用上）
     *   L3 实测——总线上真实到达间隔是否等于该值（证明驱动器真按这个速率在发）
     * 只做 L1 就是"自己写完自己读，读到自己写的"，等于没核验。
     */
    enum class TpdoVerifyVerdict { PASS, VALUE_MISMATCH, NOT_EFFECTIVE, RATE_MISMATCH, JITTERY, LOSSY, NO_DATA }

    /** 实测到达间隔统计（全部基于 elapsedRealtime 差值，免疫系统时钟跳变）。 */
    data class TpdoTimingReport(
        val frameCount: Int,
        val meanMs: Double,
        val medianMs: Long,
        val minMs: Long,
        val maxMs: Long,
        /** 间隔标准差 */
        val jitterMs: Double,
        /** 由超长间隔（≥1.5 倍期望）推算的漏收帧数 */
        val droppedEstimate: Int,
        /** 首末帧跨度 */
        val windowMs: Long
    ) {
        val intervalCount: Int get() = (frameCount - 1).coerceAtLeast(0)
    }

    data class TpdoVerifyResult(
        val nodeId: Int,
        val pdo: Int,
        val verdict: TpdoVerifyVerdict,
        val message: String,
        val expectedMs: Int,
        val storedMs: Long?,
        val report: TpdoTimingReport?
    ) {
        val passed: Boolean get() = verdict == TpdoVerifyVerdict.PASS
    }

    /** 实测均值相对期望的允许偏差比例。 */
    const val TPDO_VERIFY_TOLERANCE_RATIO = 0.20
    /**
     * 偏差绝对下限（ms）。UART 桥 + Android 串口读调度本身就有几毫秒抖动，
     * 20ms 周期上光按 ±20% 判会把正常设备判成不合格。
     */
    const val TPDO_VERIFY_TOLERANCE_MIN_MS = 3.0
    /** 抖动（标准差）超过期望周期的这个比例即告警。 */
    const val TPDO_VERIFY_JITTER_RATIO = 0.50
    /** 漏收帧占比超过此值即告警。 */
    const val TPDO_VERIFY_LOSS_RATIO = 0.05
    /** 出结论所需的最少间隔数——样本太少时均值没有意义。 */
    const val TPDO_VERIFY_MIN_INTERVALS = 8

    /** 判定用的容差：比例与绝对下限取大者。 */
    fun tpdoVerifyToleranceMs(expectedMs: Int): Double =
        maxOf(expectedMs * TPDO_VERIFY_TOLERANCE_RATIO, TPDO_VERIFY_TOLERANCE_MIN_MS)

    /**
     * 由到达时刻序列算间隔统计。少于 2 帧无法构成间隔，返回 null。
     *
     * @param timestampsMs 升序到达时刻（elapsedRealtime）
     * @param expectedMs   期望周期，仅用于推算漏收帧数
     */
    fun analyzeTpdoTiming(timestampsMs: LongArray, expectedMs: Int): TpdoTimingReport? {
        if (timestampsMs.size < 2) return null
        val intervals = LongArray(timestampsMs.size - 1) { timestampsMs[it + 1] - timestampsMs[it] }
        val mean = intervals.average()
        val variance = intervals.sumOf { val d = it - mean; d * d } / intervals.size
        val sorted = intervals.sortedArray()
        val median = sorted[sorted.size / 2]
        // 漏收推算：一个 3 倍期望的间隔意味着中间少收了 2 帧
        val dropped = if (expectedMs <= 0) 0 else intervals.sumOf { gap ->
            val ratio = gap.toDouble() / expectedMs
            if (ratio >= 1.5) (Math.round(ratio) - 1).toInt().coerceAtLeast(0) else 0
        }
        return TpdoTimingReport(
            frameCount = timestampsMs.size,
            meanMs = mean,
            medianMs = median,
            minMs = sorted.first(),
            maxMs = sorted.last(),
            jitterMs = Math.sqrt(variance),
            droppedEstimate = dropped,
            windowMs = timestampsMs.last() - timestampsMs.first()
        )
    }

    /**
     * 三层核验合成结论。
     *
     * 判定顺序刻意从"最能解释现象的根因"排起：对象值都没写进去 → 写进去但传输类型/
     * 有效位让它不生效 → 都对但总线上速率不符 → 速率对但抖动/丢帧过大。这样现场
     * 看到的第一条结论就是该去改的那一项。
     *
     * @param expectedMs        上位机下发的周期（下发过才有 L1；未下发时传对象回读值本身）
     * @param storedMs          SDO 回读的 1800h-05；null = 没读到
     * @param transmissionType  SDO 回读的 1800h-02；null = 没读到（跳过 L2 的类型判定）
     * @param cobIdValue        SDO 回读的 1800h-01；null = 没读到（跳过 L2 的有效位判定）
     * @param timestampsMs      实测到达时刻
     */
    fun verifyTpdoTiming(
        nodeId: Int,
        pdo: Int,
        expectedMs: Int,
        storedMs: Long?,
        transmissionType: Long?,
        cobIdValue: Long?,
        timestampsMs: LongArray
    ): TpdoVerifyResult {
        val report = analyzeTpdoTiming(timestampsMs, expectedMs)
        fun result(v: TpdoVerifyVerdict, msg: String) =
            TpdoVerifyResult(nodeId, pdo, v, msg, expectedMs, storedMs, report)

        // L1 对象值
        if (storedMs == null) {
            return result(TpdoVerifyVerdict.NO_DATA, "未读到 1800h-05，无法核验对象值")
        }
        if (storedMs.toInt() != expectedMs) {
            return result(
                TpdoVerifyVerdict.VALUE_MISMATCH,
                "对象值 ${storedMs}ms ≠ 下发 ${expectedMs}ms：写入未生效或被设备改写"
            )
        }

        // L2 生效条件
        if (cobIdValue != null && !isPdoEnabled(cobIdValue)) {
            return result(TpdoVerifyVerdict.NOT_EFFECTIVE, "该 PDO 的 COB-ID Bit31=1（已禁用），周期不会生效")
        }
        if (transmissionType != null && !isAsyncTransmissionType(transmissionType)) {
            return result(
                TpdoVerifyVerdict.NOT_EFFECTIVE,
                "传输类型 $transmissionType（${transmissionTypeDescription(transmissionType)}），事件周期不生效"
            )
        }
        // 周期 0 = 主动关闭，此时不该收到帧
        if (expectedMs == 0) {
            return if (report == null) {
                result(TpdoVerifyVerdict.PASS, "周期 0 且采样窗口内未收到该 PDO，关闭已生效")
            } else {
                result(
                    TpdoVerifyVerdict.RATE_MISMATCH,
                    "周期已设 0，但窗口内仍收到 ${report.frameCount} 帧（均隔 %.1fms）".format(report.meanMs)
                )
            }
        }

        // L3 实测
        if (report == null || report.intervalCount < TPDO_VERIFY_MIN_INTERVALS) {
            return result(
                TpdoVerifyVerdict.NO_DATA,
                "样本不足（${report?.frameCount ?: 0} 帧）：确认该 PDO 在发、或延长采样时长"
            )
        }
        val tolerance = tpdoVerifyToleranceMs(expectedMs)
        val deviation = report.meanMs - expectedMs
        if (Math.abs(deviation) > tolerance) {
            return result(
                TpdoVerifyVerdict.RATE_MISMATCH,
                "实测均隔 %.1fms ≠ 下发 %dms（容差 ±%.1fms）：设备未按该周期发送".format(
                    report.meanMs, expectedMs, tolerance
                )
            )
        }
        if (report.droppedEstimate > report.intervalCount * TPDO_VERIFY_LOSS_RATIO) {
            return result(
                TpdoVerifyVerdict.LOSSY,
                "速率正确但漏收约 ${report.droppedEstimate} 帧：串口带宽可能已饱和，建议放宽周期或关掉 TPDO3"
            )
        }
        if (report.jitterMs > expectedMs * TPDO_VERIFY_JITTER_RATIO) {
            return result(
                TpdoVerifyVerdict.JITTERY,
                "速率正确但抖动 %.1fms 偏大（最大间隔 %dms）：总线或串口拥塞".format(
                    report.jitterMs, report.maxMs
                )
            )
        }
        return result(
            TpdoVerifyVerdict.PASS,
            "实测均隔 %.1fms，抖动 %.1fms，漏收 %d 帧 —— 周期已按下发值生效".format(
                report.meanMs, report.jitterMs, report.droppedEstimate
            )
        )
    }

    /** 按周期推荐的采样时长：至少收满 ~60 帧，且落在 2~15s 之间。 */
    fun recommendedVerifyWindowMs(expectedMs: Int): Long =
        if (expectedMs <= 0) 3000L else (expectedMs * 60L).coerceIn(2000L, 15000L)
}
