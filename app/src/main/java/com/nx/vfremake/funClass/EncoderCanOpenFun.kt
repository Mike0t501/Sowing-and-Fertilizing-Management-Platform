/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2026年7月7日
 * @file    :
 * @brief   :摆臂式绝对值编码器（布瑞特 DS406）CANopen 协议层——纯函数：TPDO 解析 / SDO 语义化构帧 /
 *           滤波 / 编码值→实测播深换算
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 首版：播种深度实测反馈子系统（测量/标定/显示/记录，不做闭环修正）
 ***********************************************************************************************************
 */

package com.nx.vfremake.funClass

import kotlin.math.abs

/**
 * 摆臂编码器 CANopen 协议层（CiA DS301 + DS406 编码器行规）。
 *
 * ── 与伺服（DS402，Node-ID 11~18）的区别 ────────────────────────────────────
 *   - 编码器 Node-ID 21~28（= 21 + motorIndex，与每行伺服一一对应），作从站只读上报
 *   - TPDO1 数据段只有 4 字节（对象 6004h 当前位置，U32 小端，映射 60040020h），
 *     不能复用伺服 6 字节的 [CanOpenFun.parseTpdo1]
 *   - SDO 帧格式与 [CanOpenFun] 完全一致，构帧直接复用
 *     [CanOpenFun.buildSdoWriteFrame] / [CanOpenFun.buildSdoReadFrame]，
 *     本对象只提供语义化包装，避免调用方散落魔法数
 *
 * ── 发送约定 ────────────────────────────────────────────────────────────────
 *   所有编码器 SDO 帧一律走 [CanOpenFun.sendFrameSequenced] / [CanOpenFun.sendSequence]
 *   （全局 20ms 步调），禁止直接调 sendFrame——per-caller delay 曾是跨协程 SDO
 *   撞车丢帧的根因（点动失灵/失控，见 CanOpenFun 头注释）。
 *   配置序列的保存帧（1010h）必须放序列最后：前缀安全——序列被取消只发出前缀时
 *   参数不会被持久化到设备。
 *
 * ── 手册依据 ────────────────────────────────────────────────────────────────
 *   《005 CANOPEN说明书通信协议 V2.6.pdf》（布瑞特）。关键示例帧已核对：
 *     读 6004h 应答 `580+ID / 43 04 60 00 E8 03 00 00` → 1000
 *     保存参数     `600+ID / 23 10 10 01 73 61 76 65`（ASCII "save"）
 *     发送间隔 50ms `600+ID / 2B 00 18 05 32 00 00 00`
 *   注意：写 3001h（节点 ID）后，设备在保存并断电重启前仍用【旧 ID】应答。
 */
object EncoderCanOpenFun {

    /** EDS 数字对象的数据类型。可见字符串需要分段 SDO，不放入本调试控制台。 */
    enum class EdsDataType(val byteLength: Int, val signed: Boolean) {
        U8(1, false), U16(2, false), U32(4, false), I32(4, true)
    }

    /** EDS 对象访问属性。 */
    enum class EdsAccess { RO, RW }

    /**
     * 可由软件直接调试的 EDS 数字对象定义。
     *
     * @param key       稳定键，用于 UI 快照结果映射
     * @param index     CANopen 对象索引
     * @param subIndex  子索引
     * @param title     现场操作员可读名称
     * @param type      EDS 数据类型（0x0004/05/06/07）
     * @param access    EDS AccessType
     */
    data class EdsObject(
        val key: String,
        val index: Int,
        val subIndex: Int,
        val title: String,
        val type: EdsDataType,
        val access: EdsAccess
    ) {
        val address: String
            get() = "%04X-%02X".format(index, subIndex)
    }

    /**
     * EDS 中与设备识别、通信和 DS406 运行状态直接相关的数字对象。
     * 字符串对象 1008/1009/100A 未列入：其默认值超过 4 字节，需要分段 SDO，当前
     * CSM100T 调试链路只实现安全、确定的 expedited SDO。设备身份由 1018h 完整覆盖。
     */
    val EDS_OBJECTS: List<EdsObject> = listOf(
        EdsObject("deviceType", 0x1000, 0x00, "设备类型", EdsDataType.U32, EdsAccess.RO),
        EdsObject("errorRegister", 0x1001, 0x00, "错误寄存器", EdsDataType.U8, EdsAccess.RO),
        EdsObject("errorCount", 0x1003, 0x00, "历史错误数量", EdsDataType.U8, EdsAccess.RW),
        EdsObject("latestError", 0x1003, 0x01, "最近错误码", EdsDataType.U32, EdsAccess.RO),
        EdsObject("error2", 0x1003, 0x02, "历史错误 2", EdsDataType.U32, EdsAccess.RO),
        EdsObject("error3", 0x1003, 0x03, "历史错误 3", EdsDataType.U32, EdsAccess.RO),
        EdsObject("error4", 0x1003, 0x04, "历史错误 4", EdsDataType.U32, EdsAccess.RO),
        EdsObject("syncCobId", 0x1005, 0x00, "SYNC COB-ID", EdsDataType.U32, EdsAccess.RW),
        EdsObject("commCycle", 0x1006, 0x00, "通信周期(μs)", EdsDataType.U32, EdsAccess.RW),
        EdsObject("storeCount", 0x1010, 0x00, "保存参数子项数", EdsDataType.U8, EdsAccess.RO),
        EdsObject("storeAll", 0x1010, 0x01, "保存全部参数", EdsDataType.U32, EdsAccess.RW),
        EdsObject("restoreCount", 0x1011, 0x00, "恢复默认子项数", EdsDataType.U8, EdsAccess.RO),
        EdsObject("restoreAll", 0x1011, 0x01, "恢复全部默认值", EdsDataType.U32, EdsAccess.RW),
        EdsObject("emcyCobId", 0x1014, 0x00, "EMCY COB-ID", EdsDataType.U32, EdsAccess.RW),
        EdsObject("heartbeat", 0x1017, 0x00, "生产者心跳(ms)", EdsDataType.U16, EdsAccess.RW),
        EdsObject("identityCount", 0x1018, 0x00, "身份子项数", EdsDataType.U8, EdsAccess.RO),
        EdsObject("vendorId", 0x1018, 0x01, "厂商 ID", EdsDataType.U32, EdsAccess.RO),
        EdsObject("productCode", 0x1018, 0x02, "产品代码", EdsDataType.U32, EdsAccess.RO),
        EdsObject("revision", 0x1018, 0x03, "修订版本", EdsDataType.U32, EdsAccess.RO),
        EdsObject("serial", 0x1018, 0x04, "设备序列号", EdsDataType.U32, EdsAccess.RO),
        EdsObject("sdoServerCount", 0x1200, 0x00, "SDO 服务子项数", EdsDataType.U8, EdsAccess.RO),
        EdsObject("sdoRxCobId", 0x1200, 0x01, "SDO 接收 COB-ID", EdsDataType.U32, EdsAccess.RO),
        EdsObject("sdoTxCobId", 0x1200, 0x02, "SDO 发送 COB-ID", EdsDataType.U32, EdsAccess.RO),
        EdsObject("tpdo1ParamCount", 0x1800, 0x00, "TPDO1 参数子项数", EdsDataType.U8, EdsAccess.RO),
        EdsObject("tpdo1CobId", 0x1800, 0x01, "TPDO1 COB-ID", EdsDataType.U32, EdsAccess.RW),
        EdsObject("tpdo1Type", 0x1800, 0x02, "TPDO1 传输类型", EdsDataType.U8, EdsAccess.RW),
        EdsObject("tpdo1Inhibit", 0x1800, 0x03, "TPDO1 禁止时间(100μs)", EdsDataType.U16, EdsAccess.RW),
        EdsObject("tpdo1Event", 0x1800, 0x05, "TPDO1 事件周期(ms)", EdsDataType.U16, EdsAccess.RW),
        EdsObject("tpdo2ParamCount", 0x1801, 0x00, "TPDO2 参数子项数", EdsDataType.U8, EdsAccess.RO),
        EdsObject("tpdo2CobId", 0x1801, 0x01, "TPDO2 COB-ID", EdsDataType.U32, EdsAccess.RW),
        EdsObject("tpdo2Type", 0x1801, 0x02, "TPDO2 传输类型", EdsDataType.U8, EdsAccess.RW),
        EdsObject("tpdo2Inhibit", 0x1801, 0x03, "TPDO2 禁止时间(100μs)", EdsDataType.U16, EdsAccess.RW),
        EdsObject("tpdo2Event", 0x1801, 0x05, "TPDO2 事件周期(ms)", EdsDataType.U16, EdsAccess.RW),
        EdsObject("tpdo1MapCount", 0x1A00, 0x00, "TPDO1 映射数量", EdsDataType.U8, EdsAccess.RW),
        EdsObject("tpdo1Map", 0x1A00, 0x01, "TPDO1 映射对象", EdsDataType.U32, EdsAccess.RW),
        EdsObject("tpdo2MapCount", 0x1A01, 0x00, "TPDO2 映射数量", EdsDataType.U8, EdsAccess.RW),
        EdsObject("tpdo2Map", 0x1A01, 0x01, "TPDO2 映射对象", EdsDataType.U32, EdsAccess.RW),
        EdsObject("operatingParameters", 0x6000, 0x00, "运行参数", EdsDataType.U16, EdsAccess.RW),
        EdsObject("unitsPerRev", 0x6001, 0x00, "每转测量单位", EdsDataType.U32, EdsAccess.RW),
        EdsObject("totalRange", 0x6002, 0x00, "总测量范围", EdsDataType.U32, EdsAccess.RW),
        EdsObject("preset", 0x6003, 0x00, "预设值", EdsDataType.U32, EdsAccess.RW),
        EdsObject("position", 0x6004, 0x00, "当前位置", EdsDataType.U32, EdsAccess.RO),
        EdsObject("cyclicTimer", 0x6200, 0x00, "循环定时器(ms)", EdsDataType.U16, EdsAccess.RW),
        EdsObject("operatingStatus", 0x6500, 0x00, "运行状态", EdsDataType.U16, EdsAccess.RO),
        EdsObject("physicalResolution", 0x6501, 0x00, "物理单圈分辨率", EdsDataType.U32, EdsAccess.RO),
        EdsObject("revolutions", 0x6502, 0x00, "可区分圈数", EdsDataType.U16, EdsAccess.RO),
        EdsObject("alarms", 0x6503, 0x00, "报警", EdsDataType.U16, EdsAccess.RO),
        EdsObject("supportedAlarms", 0x6504, 0x00, "支持的报警", EdsDataType.U16, EdsAccess.RO),
        EdsObject("warnings", 0x6505, 0x00, "警告", EdsDataType.U16, EdsAccess.RO),
        EdsObject("supportedWarnings", 0x6506, 0x00, "支持的警告", EdsDataType.U16, EdsAccess.RO),
        EdsObject("profileVersion", 0x6507, 0x00, "行规/软件版本", EdsDataType.U32, EdsAccess.RO),
        EdsObject("operatingTime", 0x6508, 0x00, "运行时间", EdsDataType.U32, EdsAccess.RO),
        EdsObject("offset", 0x6509, 0x00, "偏移值", EdsDataType.I32, EdsAccess.RO),
        EdsObject("moduleIdCount", 0x650A, 0x00, "模块标识子项数", EdsDataType.U8, EdsAccess.RO),
        EdsObject("manufacturerOffset", 0x650A, 0x01, "制造商偏移值", EdsDataType.I32, EdsAccess.RO),
        EdsObject("minPosition", 0x650A, 0x02, "最小位置", EdsDataType.I32, EdsAccess.RO),
        EdsObject("maxPosition", 0x650A, 0x03, "最大位置", EdsDataType.I32, EdsAccess.RO),
        EdsObject("profileSerial", 0x650B, 0x00, "DS406 序列号", EdsDataType.U32, EdsAccess.RO),
        EdsObject("baudRate", 0x3000, 0x00, "CAN 波特率代码", EdsDataType.U8, EdsAccess.RW),
        EdsObject("nodeId", 0x3001, 0x00, "节点 ID", EdsDataType.U8, EdsAccess.RW)
    )

    /** 一键诊断读取的最小充分对象集；均来自 EDS。 */
    val QUICK_DIAGNOSTIC_OBJECTS: List<EdsObject> = listOf(
        "deviceType", "vendorId", "productCode", "revision", "serial",
        "errorRegister", "latestError", "nodeId", "baudRate", "position",
        "operatingStatus", "physicalResolution", "unitsPerRev", "totalRange",
        "tpdo1Type", "tpdo1Event", "alarms", "warnings"
    ).map { key -> EDS_OBJECTS.first { it.key == key } }

    /** expedited SDO 应答的结构化解析结果。 */
    data class SdoReply(
        val command: Int,
        val index: Int,
        val subIndex: Int,
        val value: Long? = null,
        val dataLength: Int = 0,
        val abortCode: Long? = null
    ) {
        val isAbort: Boolean get() = abortCode != null
        val isWriteAck: Boolean get() = command == 0x60
    }

    /**
     * 解析 BRT 编码器的 expedited SDO 应答（0x4F/4B/47/43、0x60、0x80）。
     * 返回 null 表示长度或命令符无效；SDO 中止作为 [SdoReply.abortCode] 返回，便于 UI
     * 显示具体原因，而不是笼统的“设备返回错误”。
     */
    fun parseSdoReply(data: ByteArray): SdoReply? {
        if (data.size < 8) return null
        val command = data[0].toInt() and 0xFF
        val index = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        val subIndex = data[3].toInt() and 0xFF
        if (command == 0x80) {
            return SdoReply(
                command = command,
                index = index,
                subIndex = subIndex,
                abortCode = littleEndianValue(data, 4)
            )
        }
        if (command == 0x60) return SdoReply(command, index, subIndex)
        val length = when (command) {
            0x4F -> 1
            0x4B -> 2
            0x47 -> 3
            0x43 -> 4
            else -> return null
        }
        return SdoReply(
            command = command,
            index = index,
            subIndex = subIndex,
            value = littleEndianValue(data, length),
            dataLength = length
        )
    }

    private fun littleEndianValue(data: ByteArray, length: Int): Long {
        var value = 0L
        repeat(length) { i -> value = value or ((data[4 + i].toLong() and 0xFF) shl (8 * i)) }
        return value
    }

    /** 把 EDS 数字值格式化为“十进制 / 十六进制”，有符号 I32 按补码显示。 */
    fun formatEdsValue(obj: EdsObject, value: Long): String {
        val decimal = if (obj.type.signed) value.toInt().toString() else value.toString()
        val hexDigits = obj.type.byteLength * 2
        val mask = when (obj.type.byteLength) {
            1 -> 0xFFL
            2 -> 0xFFFFL
            else -> 0xFFFFFFFFL
        }
        val hex = (value and mask).toString(16).uppercase().padStart(hexDigits, '0')
        return "$decimal / 0x$hex"
    }

    /** 常见 CiA301 SDO 中止码中文说明；未知码仍保留十六进制值供查手册。 */
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

    // ─────────────────────────────────────────────────────────────────────
    // Node-ID 分配
    // ─────────────────────────────────────────────────────────────────────

    /** 编码器 Node-ID 基址：编码器 nodeId = ENCODER_NODE_ID_BASE + motorIndex（21~28） */
    const val ENCODER_NODE_ID_BASE = 21

    /**
     * 编码器 Node-ID 合法范围。
     * 出厂默认 ID=1 的 TPDO1 = 0x181 落在 TPDO 路由段但不在任何白名单内，会跌落
     * 施肥解析造成数据污染——编码器必须先经配置工具改到本范围才能上总线。
     */
    val ENCODER_NODE_ID_RANGE = 21..28

    /** 编码器出厂默认 Node-ID（配置工具按此 ID 首次寻址） */
    const val FACTORY_DEFAULT_NODE_ID = 1

    // ─────────────────────────────────────────────────────────────────────
    // 对象字典（手册 8.1 / 8.2 / 8.3 节）
    // ─────────────────────────────────────────────────────────────────────

    /** 6004-00 当前位置值 [U32, ro] */
    const val OD_POSITION = 0x6004

    /** 6003-00 预设值 [U32, rw]：写入后当前位置立即变为该值，用于零位标定 */
    const val OD_PRESET_VALUE = 0x6003

    /** 6001-00 每转分辨率 [U32, rw] */
    const val OD_RESOLUTION_PER_REV = 0x6001

    /** 6501-00 物理单圈分辨率 [U32, ro]（配置工具读取后持久化，供符号展开用） */
    const val OD_PHYS_RESOLUTION = 0x6501

    /** 1800h PDO1 通信参数；子索引 05 = Event Time（发送间隔，单位 ms）[U16, rw] */
    const val OD_TPDO1_PARAM = 0x1800
    const val SUB_EVENT_TIME = 0x05

    /** 3001-00 节点 ID [U8, rw]；写后须写 1010h 保存并断电重启才持久生效 */
    const val OD_NODE_ID = 0x3001

    /** 3000-00 波特率 [U8, rw]——本项目【禁止修改】（保持出厂 500K，与总线一致） */
    const val OD_BAUDRATE = 0x3000

    /** 1010h 保存参数；子索引 01 写 ASCII "save" */
    const val OD_STORE_PARAMS = 0x1010
    const val SUB_STORE = 0x01

    /** "save" 的小端 U32 值：字节序列 73 61 76 65（s a v e） */
    const val SAVE_MAGIC = 0x65766173L

    /**
     * 配置工具写入的 TPDO1 发送间隔（ms）。
     * 带宽预算：UART 桥仅 115200bps，出厂默认 20ms × 8 台 ≈ 400 帧/s，叠加伺服
     * TPDO 与施肥流量会逼近桥容量；50ms × 8 台 ≈ 160 帧/s 留足余量。
     * 软件中不得假设固定上报周期，以帧到达为准。
     */
    const val TPDO1_EVENT_TIME_MS = 50

    // ─────────────────────────────────────────────────────────────────────
    // TPDO1 解析（编码器主动上报）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 编码器 TPDO1 解析结果。
     *
     * @property nodeId       发送此帧的编码器节点 ID（21~28）
     * @property rawPosition  当前位置原始值（对象 6004h，U32，0..0xFFFFFFFF）。
     *                        用 [toSignedPosition] 做符号展开后再进滤波/换算。
     */
    data class EncoderTpdoData(
        val nodeId: Int,
        val rawPosition: Long
    )

    /**
     * 尝试解析编码器 TPDO1 帧。
     *
     * TPDO1 CAN-ID = 0x180 + Node-ID（编码器范围 0x195~0x19C，即 Node-ID 21~28）
     * 数据格式（4 字节）：[当前位置 U32 小端]——与伺服 6 字节 TPDO1 不同。
     *
     * @param canId 接收到的 CAN-ID
     * @param data  帧数据段（unwrapCanFrame 解出）
     * @return [EncoderTpdoData]；非编码器节点或数据不足 4 字节时返回 null
     */
    fun parseEncoderTpdo(canId: Int, data: ByteArray): EncoderTpdoData? {
        val nodeId = canId - 0x180
        if (nodeId !in ENCODER_NODE_ID_RANGE) return null
        if (data.size < 4) return null
        val pos = (data[0].toLong() and 0xFF) or
                  ((data[1].toLong() and 0xFF) shl 8) or
                  ((data[2].toLong() and 0xFF) shl 16) or
                  ((data[3].toLong() and 0xFF) shl 24)
        return EncoderTpdoData(nodeId = nodeId, rawPosition = pos)
    }

    // ─────────────────────────────────────────────────────────────────────
    // SDO 语义化构帧（全部复用 CanOpenFun 通用构帧器）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 零位预设：写 6003-00 = 0（U32，CS=0x23）。
     * 机具落到基准状态（压种轮触平整地面）后发送，编码器当前位置立即归零。
     * 收到 0x60 应答后须再发 [buildSaveParamsFrame] 并提示断电重启（手册要求）。
     */
    fun buildPresetZeroFrame(nodeId: Int): ByteArray =
        CanOpenFun.buildSdoWriteFrame(nodeId, OD_PRESET_VALUE, 0x00, 4, 0L)

    /**
     * 保存参数到 EPROM：写 1010-01 = "save"（字节 73 61 76 65）。
     * 改节点 ID / 发送间隔 / 预设值后必须保存并断电重启才持久生效。
     * 【前缀安全】多帧配置序列中本帧必须放最后。
     */
    fun buildSaveParamsFrame(nodeId: Int): ByteArray =
        CanOpenFun.buildSdoWriteFrame(nodeId, OD_STORE_PARAMS, SUB_STORE, 4, SAVE_MAGIC)

    /**
     * 设置 TPDO1 发送间隔：写 1800-05 = [ms]（U16，CS=0x2B）。
     * 配置工具固定写 [TPDO1_EVENT_TIME_MS]（50ms，带宽预算见常量注释）。
     */
    fun buildSetEventTimeFrame(nodeId: Int, ms: Int = TPDO1_EVENT_TIME_MS): ByteArray =
        CanOpenFun.buildSdoWriteFrame(nodeId, OD_TPDO1_PARAM, SUB_EVENT_TIME, 2, ms.toLong())

    /**
     * 修改节点 ID：写 3001-00 = [newNodeId]（U8，CS=0x2F）。
     *
     * 【应答匹配注意】设备在保存并断电重启前仍用旧 ID（0x580 + [currentNodeId]）
     * 应答——等待应答时必须按旧 ID 注册 waiter（手册第 10 例明确示范）。
     *
     * @param currentNodeId 设备当前（旧）Node-ID，出厂为 [FACTORY_DEFAULT_NODE_ID]
     * @param newNodeId     目标 Node-ID，必须在 [ENCODER_NODE_ID_RANGE] 内
     */
    fun buildSetNodeIdFrame(currentNodeId: Int, newNodeId: Int): ByteArray {
        require(newNodeId in ENCODER_NODE_ID_RANGE) {
            "编码器目标 Node-ID 必须在 $ENCODER_NODE_ID_RANGE 内，当前值: $newNodeId"
        }
        return CanOpenFun.buildSdoWriteFrame(currentNodeId, OD_NODE_ID, 0x00, 1, newNodeId.toLong())
    }

    /** 读物理单圈分辨率（6501-00，U32）：配置工具用于验证设备在线并持久化分辨率。 */
    fun buildReadResolutionFrame(nodeId: Int): ByteArray =
        CanOpenFun.buildSdoReadFrame(nodeId, OD_PHYS_RESOLUTION, 0x00)

    /** 读当前位置值（6004-00，U32）：配置工具重启后按新 ID 验证用。 */
    fun buildReadPositionFrame(nodeId: Int): ByteArray =
        CanOpenFun.buildSdoReadFrame(nodeId, OD_POSITION, 0x00)

    /** 按 [EDS_OBJECTS] 定义读取任一数字对象。 */
    fun buildReadObjectFrame(nodeId: Int, obj: EdsObject): ByteArray =
        CanOpenFun.buildSdoReadFrame(nodeId, obj.index, obj.subIndex)

    /**
     * 按 EDS 类型写入 RW 数字对象。
     * 只读对象在构帧前即拒绝，避免把明显错误的维护操作发送到现场总线。
     */
    fun buildWriteObjectFrame(nodeId: Int, obj: EdsObject, value: Long): ByteArray {
        require(obj.access == EdsAccess.RW) { "${obj.address} ${obj.title} 是只读对象" }
        return CanOpenFun.buildSdoWriteFrame(
            nodeId, obj.index, obj.subIndex, obj.type.byteLength, value
        )
    }

    /** 触发一次 SYNC（CAN-ID 0x080、0 字节数据），用于验证同步 TPDO2。 */
    fun buildSyncFrame(): ByteArray = CanOpenFun.wrapCanFrame(0x080, byteArrayOf())

    /** 把波特率代码转换为手册定义；代码 6 = 本项目总线 500 kbit/s。 */
    fun baudRateDescription(code: Long): String = when (code.toInt()) {
        0 -> "10 kbit/s"
        1 -> "20 kbit/s"
        2 -> "50 kbit/s"
        3 -> "100 kbit/s"
        4 -> "125 kbit/s"
        5 -> "250 kbit/s"
        6 -> "500 kbit/s（本机要求）"
        7 -> "800 kbit/s"
        8 -> "1 Mbit/s"
        else -> "未知代码 $code"
    }

    // ─────────────────────────────────────────────────────────────────────
    // 符号展开与深度换算
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 单圈回绕符号展开：把 U32 原始位置展开为带符号位置。
     *
     * 零位预设在基准态（压种轮触地）执行，作业中摆臂弹跳可能越过零点，单圈绝对值
     * 编码器的原始值会从 0 跳到量程顶端（如 resolution=4096 时 0 → 4095）。不展开
     * 会让滤波与线性拟合被巨大野值污染。展开规则：raw > resolution/2 视为负方向
     * （raw - resolution）。
     *
     * @param rawPosition 原始位置（U32，0..0xFFFFFFFF）
     * @param resolution  物理单圈分辨率（配置工具读 6501h 持久化）；≤0 = 未知，不展开
     */
    fun toSignedPosition(rawPosition: Long, resolution: Int): Int {
        val raw = rawPosition and 0xFFFFFFFFL
        if (resolution <= 0) return raw.toInt()
        return if (raw > resolution / 2) (raw - resolution).toInt() else raw.toInt()
    }

    /**
     * 编码值 → 实测播深（mm）的唯一换算入口。
     *
     * 摆臂几何严格为 depth ≈ L·(sinθ−sinθ₀)，±20° 内线性近似残差可忽略，故与伺服
     * 侧 fittingCoefficient 体系同构地用一阶拟合：depth_mm = fitA × pos + fitB。
     * 将来若需二阶多项式，只改本函数与标定拟合处即可。
     *
     * @param position 滤波后的（已符号展开）编码位置
     * @param fitA     线性拟合斜率（标定得出）
     * @param fitB     线性拟合截距（标定得出）
     */
    fun depthFromEncoder(position: Float, fitA: Float, fitB: Float): Float =
        fitA * position + fitB

    // ─────────────────────────────────────────────────────────────────────
    // 两级滤波：限幅野值剔除 + 滑动均值
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 滑动均值窗口长度（帧）。
     * 依据：TPDO 间隔 50ms × 8 帧 = 400ms 平滑窗——压种轮过垄沟/残茬的弹跳尖峰
     * 持续时间通常 <200ms，窗口能有效摊平；再大则地形起伏引起的真实深度变化
     * 会被过度迟滞，现场读数"跟不上车"。
     */
    const val FILTER_WINDOW_SIZE = 8

    /**
     * 限幅剔除的连续超限接受帧数。
     * 单帧跳变超阈值视为野值丢弃；但若连续 [SPIKE_CONSECUTIVE_ACCEPT] 帧都超限，
     * 说明是真实快速变化（如机具升降），必须接受并重建窗口——否则滤波器会被
     * 永久锁死在旧位置。
     */
    const val SPIKE_CONSECUTIVE_ACCEPT = 3

    /**
     * 默认限幅阈值（编码器脉冲）。
     * 依据：常见 12bit（4096）单圈分辨率的 5% ≈ 205 脉冲 ≈ 18°×5% 摆角突变；
     * 50ms 内机械上不可能发生的角速度即视为电气野值。分辨率已知时用
     * [spikeThresholdFor] 按比例换算。
     */
    const val DEFAULT_SPIKE_THRESHOLD = 205

    /** 按物理分辨率换算限幅阈值（5%，下限 16 脉冲）；分辨率未知时用默认值。 */
    fun spikeThresholdFor(resolution: Int): Int =
        if (resolution > 0) maxOf(resolution / 20, 16) else DEFAULT_SPIKE_THRESHOLD

    /**
     * 单路编码器两级滤波器：限幅野值剔除 → 窗口滑动均值。
     *
     * 【线程约定】非线程安全：每路一个实例，仅由 CanReceiveCoroutine 的接收协程
     * 串行调用（同 servoLastSeen 的使用模式）。
     *
     * @param windowSize     滑动均值窗口，默认 [FILTER_WINDOW_SIZE]
     * @param spikeThreshold 限幅阈值（脉冲），可在得知分辨率后经 [spikeThresholdFor] 更新
     */
    class EncoderFilter(
        private val windowSize: Int = FILTER_WINDOW_SIZE,
        var spikeThreshold: Int = DEFAULT_SPIKE_THRESHOLD
    ) {
        private val window = ArrayDeque<Int>()
        private var lastAccepted: Int? = null
        private var consecutiveRejected = 0

        /**
         * 输入一帧（已符号展开的）位置值，返回滤波后位置。
         *
         * @return 滑动均值；野值被剔除时返回剔除前的均值（读数保持不跳）
         */
        fun feed(signedPos: Int): Float {
            val last = lastAccepted
            if (last != null && abs(signedPos - last) > spikeThreshold) {
                consecutiveRejected++
                if (consecutiveRejected < SPIKE_CONSECUTIVE_ACCEPT) {
                    return mean()          // 野值：丢弃本帧，读数维持
                }
                window.clear()             // 连续超限 = 真实快速变化：重建窗口
            }
            consecutiveRejected = 0
            lastAccepted = signedPos
            window.addLast(signedPos)
            if (window.size > windowSize) window.removeFirst()
            return mean()
        }

        /** 当前滤波值（窗口均值）；无数据时返回 0f。 */
        fun mean(): Float =
            if (window.isEmpty()) 0f else window.sum().toFloat() / window.size

        /** 清空滤波状态（编码器离线重连/重新置零后调用，避免新旧数据混窗）。 */
        fun reset() {
            window.clear()
            lastAccepted = null
            consecutiveRejected = 0
        }
    }
}
