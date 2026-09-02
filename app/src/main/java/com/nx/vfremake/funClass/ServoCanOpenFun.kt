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
        0x06070010L -> "数据类型或长度不匹配"
        0x06090011L -> "子索引不存在"
        0x06090030L -> "参数值范围超限"
        0x06090031L -> "参数值过大"
        0x06090032L -> "参数值过小"
        0x08000000L -> "一般性错误"
        0x08000020L -> "数据无法传输或保存"
        else -> "未知中止码"
    }

    /** EDS 身份默认值：Vendor=817、Product=1、Revision=256；序列号因设备而异。 */
    fun identityMatches(values: Map<String, Long>): Boolean =
        values["vendorId"] == 817L && values["productCode"] == 1L && values["revision"] == 256L
}
