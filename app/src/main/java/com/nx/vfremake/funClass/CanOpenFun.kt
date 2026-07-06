package com.nx.vfremake.funClass

import android.os.SystemClock
import android.util.Log
import com.nx.vfremake.mSerialPortCAN
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * CANopen 协议底层通信
 *
 * ── 发送帧格式（UART → CSM100T → CAN总线）───────────────────────────────
 *   [0x27] [len] [0x00] [canId_hi] [canId_lo] [data 0..N] [0x39]
 *   len = 3 (帧信息1 + CAN-ID 2字节) + data.size
 *
 * 验证（现有施肥电机发送帧 11 字节，ConvAndCtrlFun.kt 实测）：
 *   CAN-ID = 0x0027, data = 5 字节 → len = 8 → 总帧 = len + 3 = 11 字节 ✓
 *
 * ── 接收帧格式（CAN总线 → CSM100T → UART）───────────────────────────────
 *   [0x27] [len] [0x00(frameInfo)] [canId_hi] [canId_lo] [data 0..N] [0x39]
 *   len = 3 (帧信息1 + CAN-ID 2字节) + data.size（与发送帧格式完全相同）
 *   CanReceiveCoroutine.dispatchFrame() 按此格式解析。
 *
 * ── CANopen CAN-ID 分配 ────────────────────────────────────────────────
 *   NMT       : 0x000（广播）
 *   SDO 发送   : 0x600 + Node-ID（主→从）
 *   SDO 接收   : 0x580 + Node-ID（从→主）
 *   TPDO1     : 0x180 + Node-ID（从主动上报，默认 100ms）
 *   RPDO1     : 0x200 + Node-ID（主→从，实时控制）
 *
 * ── 节点地址分配 ───────────────────────────────────────────────────────
 *   施肥电机   : Node-ID 0~7（现有）
 *   播种深度舵机: Node-ID 11~18（新增）
 *
 * ── 数据字节序 ──────────────────────────────────────────────────────────
 *   小端（Little-Endian）
 */
object CanOpenFun {

    private const val TAG = "CanOpenFun"

    // ─────────────────────────────────────────────────────────────────────
    // 帧封装 / 解封
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 将 CAN-ID 与数据段封装为发送串口帧。
     *
     * 格式：[0x27][len][0x00][canId_hi][canId_lo][data...][0x39]
     *       len = 3 + data.size（帧信息1 + CAN-ID 2 + 数据N）
     *
     * @param canId  11 位 CAN-ID（0x000~0x7FF）
     * @param data   CAN 帧数据段（SDO 固定 8 字节，NMT 2 字节，PDO 最多 8 字节）
     */
    fun wrapCanFrame(canId: Int, data: ByteArray): ByteArray {
        val len = 3 + data.size                        // 帧信息(1) + CAN-ID(2) + 数据(N)
        val frame = ByteArray(1 + 1 + len + 1)         // 帧头 + 长度字节 + 载荷 + 帧尾
        frame[0] = 0x27.toByte()                       // 帧起始
        frame[1] = (len and 0xFF).toByte()             // 载荷长度
        frame[2] = 0x00.toByte()                       // 帧信息（标准数据帧，固定 0x00）
        frame[3] = ((canId ushr 8) and 0xFF).toByte()  // CAN-ID 高字节
        frame[4] = (canId and 0xFF).toByte()           // CAN-ID 低字节
        data.copyInto(frame, destinationOffset = 5)    // 数据段从 [5] 开始
        frame[frame.size - 1] = 0x39.toByte()          // 帧结束
        return frame
    }

    /**
     * 从接收的串口帧中解析出 CAN-ID 与数据段。
     *
     * @param frame 完整串口帧字节数组
     * @return Pair<canId, data>；帧格式错误时返回 null
     */
    fun unwrapCanFrame(frame: ByteArray): Pair<Int, ByteArray>? {
        if (frame.size < 5) return null
        if (frame.first() != 0x27.toByte() || frame.last() != 0x39.toByte()) return null
        val len = frame[1].toInt() and 0xFF
        if (frame.size != 1 + 1 + len + 1) return null
        if (len < 3) return null
        val canId = ((frame[3].toInt() and 0xFF) shl 8) or
                    (frame[4].toInt() and 0xFF)
        val data = frame.copyOfRange(5, 5 + (len - 3))
        return Pair(canId, data)
    }

    // ─────────────────────────────────────────────────────────────────────
    // SDO 写入帧构建
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 构建 SDO 写入帧（含串口封装）。
     *
     * SDO 写命令符（CS）：
     *   1 字节 → 0x2F
     *   2 字节 → 0x2B
     *   4 字节 → 0x23
     *
     * SDO 8 字节格式：
     *   [CS][index_lo][index_hi][subIndex][data0][data1][data2][data3]
     *
     * @param nodeId   节点 ID（深度舵机 11~18）
     * @param index    对象字典索引，如 0x6040
     * @param subIndex 子索引，通常 0x00
     * @param dataLen  写入数据长度：1、2 或 4
     * @param value    写入值（有符号/无符号均可，内部按 dataLen 取低位小端序）
     */
    fun buildSdoWriteFrame(
        nodeId: Int,
        index: Int,
        subIndex: Int,
        dataLen: Int,
        value: Long
    ): ByteArray {
        val canId = 0x600 + nodeId
        val cs = when (dataLen) {
            1 -> 0x2F
            2 -> 0x2B
            4 -> 0x23
            else -> throw IllegalArgumentException("dataLen 只能是 1、2 或 4，当前值: $dataLen")
        }
        val sdo = ByteArray(8)                               // SDO 固定 8 字节
        sdo[0] = cs.toByte()
        sdo[1] = (index and 0xFF).toByte()                  // 索引低字节
        sdo[2] = ((index ushr 8) and 0xFF).toByte()         // 索引高字节
        sdo[3] = (subIndex and 0xFF).toByte()
        for (i in 0 until dataLen) {                        // 小端序写入，最多 4 字节
            sdo[4 + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
        // sdo[4+dataLen .. 7] 保持 0x00 填充
        return wrapCanFrame(canId, sdo)
    }

    // ─────────────────────────────────────────────────────────────────────
    // SDO 读取帧构建
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 构建 SDO 读取请求帧（CS = 0x40）。
     *
     * @param nodeId   节点 ID
     * @param index    对象字典索引
     * @param subIndex 子索引
     */
    fun buildSdoReadFrame(nodeId: Int, index: Int, subIndex: Int): ByteArray {
        val canId = 0x600 + nodeId
        val sdo = ByteArray(8)
        sdo[0] = 0x40.toByte()                              // CS = 读取请求
        sdo[1] = (index and 0xFF).toByte()
        sdo[2] = ((index ushr 8) and 0xFF).toByte()
        sdo[3] = (subIndex and 0xFF).toByte()
        return wrapCanFrame(canId, sdo)
    }

    // ─────────────────────────────────────────────────────────────────────
    // SDO 回复解析
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 解析 SDO 回复的 8 字节数据段（从接收帧中提取数据后传入）。
     *
     * 回复帧 CAN-ID = 0x580 + nodeId。
     * 回复命令符（CS）含义：
     *   0x60 → 写成功应答（无数据）
     *   0x4F → 读回复 1 字节
     *   0x4B → 读回复 2 字节（小端）
     *   0x43 → 读回复 4 字节（小端）
     *   0x80 → SDO 错误
     *
     * @param sdoData 8 字节 SDO 数据段（unwrapCanFrame 解出的 data 部分）
     * @return 解析后的无符号值（Long）；写成功返回 0L；错误或未知 CS 返回 null
     */
    fun parseSdoResponse(sdoData: ByteArray): Long? {
        if (sdoData.size < 8) return null
        val cs = sdoData[0].toInt() and 0xFF
        return when (cs) {
            0x60 -> {
                // 写成功应答，无数据
                0L
            }
            0x4F -> {
                // 读回复 1 字节
                sdoData[4].toLong() and 0xFF
            }
            0x4B -> {
                // 读回复 2 字节（小端）
                (sdoData[4].toLong() and 0xFF) or
                ((sdoData[5].toLong() and 0xFF) shl 8)
            }
            0x43 -> {
                // 读回复 4 字节（小端）
                (sdoData[4].toLong() and 0xFF) or
                ((sdoData[5].toLong() and 0xFF) shl 8) or
                ((sdoData[6].toLong() and 0xFF) shl 16) or
                ((sdoData[7].toLong() and 0xFF) shl 24)
            }
            0x80 -> {
                // SDO 错误应答，记录错误码
                val errIndex = (sdoData[1].toInt() and 0xFF) or
                               ((sdoData[2].toInt() and 0xFF) shl 8)
                val errSub = sdoData[3].toInt() and 0xFF
                val errCode = (sdoData[4].toLong() and 0xFF) or
                              ((sdoData[5].toLong() and 0xFF) shl 8) or
                              ((sdoData[6].toLong() and 0xFF) shl 16) or
                              ((sdoData[7].toLong() and 0xFF) shl 24)
                Log.e(TAG, "SDO 错误应答: index=0x${errIndex.toString(16).uppercase()}" +
                           " sub=0x${errSub.toString(16).uppercase()}" +
                           " errCode=0x${errCode.toString(16).uppercase()}")
                null
            }
            else -> {
                Log.w(TAG, "未知 SDO CS 字节: 0x${cs.toString(16).uppercase()}")
                null
            }
        }
    }

    /**
     * 解析 SDO 回复中的有符号 32 位整数（用于读取实际位置 0x6064 等有符号值）。
     *
     * @param sdoData 8 字节 SDO 数据段
     * @return 有符号 Int 值；解析失败返回 null
     */
    fun parseSdoResponseSigned32(sdoData: ByteArray): Int? {
        return parseSdoResponse(sdoData)?.toInt()
    }

    /**
     * 判断接收到的 CAN-ID 是否为深度舵机的 SDO 回复帧（0x580 + 11~18）。
     */
    fun isSdoResponseFromDepthServo(canId: Int): Boolean {
        val nodeId = canId - 0x580
        return nodeId in 11..18
    }

    /**
     * 从 SDO 回复的 CAN-ID（0x580+nodeId）中提取 nodeId。
     */
    fun nodeIdFromSdoResponse(canId: Int): Int = canId - 0x580

    // ─────────────────────────────────────────────────────────────────────
    // NMT 命令帧
    // ─────────────────────────────────────────────────────────────────────

    /** NMT 命令常量 */
    object Nmt {
        const val OPERATIONAL     = 0x01  // 进入操作状态（允许 PDO 通信）
        const val STOPPED         = 0x02  // 停止（所有通信停止，仅允许 NMT 和心跳）
        const val PRE_OPERATIONAL = 0x80  // 进入预操作状态（允许 SDO，禁止 PDO）
        const val RESET_NODE      = 0x81  // 复位节点（重新初始化应用层和通信层）
        const val RESET_COMM      = 0x82  // 复位通信层（仅重启通信参数）
    }

    /**
     * 构建 NMT 命令帧。
     *
     * NMT 帧：CAN-ID = 0x000，数据 2 字节：[命令][目标节点ID]
     *
     * @param nmtCmd  NMT 命令，参见 [Nmt] 常量
     * @param nodeId  目标节点 ID；0x00 = 广播到总线上所有节点
     */
    fun buildNmtFrame(nmtCmd: Int, nodeId: Int): ByteArray {
        val data = ByteArray(2)
        data[0] = (nmtCmd and 0xFF).toByte()
        data[1] = (nodeId and 0xFF).toByte()
        return wrapCanFrame(0x000, data)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 便捷高层方法（对象字典参见 DS402 / SOWING_DEPTH_IMPLEMENTATION.md §2.3）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 使能驱动器：写控制字（0x6040）= 0x000F。
     *   Bit0=1 启动, Bit1=1 允许急停, Bit2=1 电压输出, Bit3=1 允许操作
     */
    fun buildEnableFrame(nodeId: Int): ByteArray =
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x000FL)

    /**
     * 急停：写控制字（0x6040）= 0x010F。
     *   在 0x000F 基础上置 Bit8=1（停止），电机急停但继续自锁。
     */
    fun buildQuickStopFrame(nodeId: Int): ByteArray =
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x010FL)

    /**
     * 设置位置模式（Profile Position Mode）。
     *   工作模式（0x6060）= 0x01
     */
    fun buildSetPositionModeFrame(nodeId: Int): ByteArray =
        buildSdoWriteFrame(nodeId, 0x6060, 0x00, 1, 0x01L)

    /**
     * 设置速度模式（Profile Velocity Mode），用于点动控制。
     *   工作模式（0x6060）= 0x03
     */
    fun buildSetVelocityModeFrame(nodeId: Int): ByteArray =
        buildSdoWriteFrame(nodeId, 0x6060, 0x00, 1, 0x03L)

    /**
     * 构建绝对位置运动帧序列。
     *
     * 发送顺序（调用方必须在帧间等待 SDO 应答，最少 20ms 间隔）：
     *   0. 切换位置模式 (0x6060 = 0x01)  — 点动后电机可能处于速度模式，此处确保模式正确
     *   1. 可选：设置梯形速度 (0x6081)
     *   2. 写入目标位置 (0x607A)
     *   3. 写控制字 0x002F：Bit1(急停)+Bit2(电压)+Bit3(操作)+Bit4(执行新设置点)+Bit5(立即生效)
     *      Bit6=0 → 绝对位置模式
     *
     * @param nodeId        节点 ID
     * @param targetPulse   目标编码器计数值（有符号 32 位，5mm 导程丝杆：32768 脉冲 = 5mm）
     * @param profileSpeed  梯形速度 RPM（0 = 不修改，沿用电机内部设置）
     * @return 按顺序发送的帧列表
     */
    fun buildAbsoluteMoveFrames(
        nodeId: Int,
        targetPulse: Int,
        profileSpeed: Int = 0
    ): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        // 确保电机处于位置模式（点动停止后保持速度模式，此处幂等切换）
        frames.add(buildSdoWriteFrame(nodeId, 0x6060, 0x00, 1, 0x01L))
        if (profileSpeed > 0) {
            frames.add(buildSdoWriteFrame(nodeId, 0x6081, 0x00, 4, profileSpeed.toLong()))
        }
        frames.add(buildSdoWriteFrame(nodeId, 0x607A, 0x00, 4, targetPulse.toLong()))
        // 先复位 Bit4（清除上一次的 new-setpoint 标志）。DS402 规定 Bit4 必须经历
        // 0→1 跳变驱动器才会接受新目标位置，缺少这一步会导致连续多次目标变化时
        // 第二次以后被忽略。
        frames.add(buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x000FL))
        // 控制字 0x002F = 绝对位置 + 执行新设置点(Bit4) + 立即生效(Bit5) + 使能位
        frames.add(buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x002FL))
        return frames
    }

    /**
     * 构建电机首次上线完整初始化序列（DS402 标准状态机 + 位置模式使能）。
     *
     * 发送顺序（帧间需等待 SDO 应答，约 20ms）：
     *   1. 设置位置模式（0x6060 = 0x01）
     *   2. Shutdown    → Ready to Switch On   (0x6040 = 0x0006)
     *   3. Switch On   → Switched On           (0x6040 = 0x0007)
     *   4. Enable Op   → Operation Enabled     (0x6040 = 0x000F)
     */
    fun buildMotorInitSequence(nodeId: Int): List<ByteArray> = listOf(
        buildSdoWriteFrame(nodeId, 0x6060, 0x00, 1, 0x01L),    // 位置模式
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x0006L),  // Shutdown → Ready
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x0007L),  // Switch On → Switched On
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x000FL)   // Enable Operation
    )

    /**
     * 构建速度模式点动启动完整序列（DS402 标准流程，自足式）。
     *
     * 发送顺序（帧间需等待 SDO 应答，约 20ms）：
     *   1. Shutdown            → Ready to Switch On (0x6040 = 0x0006)
     *   2. Switch On           → Switched On         (0x6040 = 0x0007)
     *   3. 切换速度模式                               (0x6060 = 0x03)
     *   4. 写目标速度                                 (0x60FF = speedRpm)
     *   5. Enable Operation    → Operation Enabled   (0x6040 = 0x000F)
     *
     * 现场失效教训：早期序列以 0x0007 开头，刚上电处于 Switch On Disabled 的驱动器
     * 按 DS402 状态机直接忽略 0x0007，点动按钮"按了没反应"；只有总开关先跑过一次
     * Phase 2 初始化后点动才偶然可用。前置 0x0006 后序列从任意非故障态都能走通，
     * 点动不再依赖总开关/Phase 2。
     * TODO：Fault 态需另发故障复位（疑似 0x6040=0x0080），待核对 docs/servo_motor_CANopen.pdf。
     *
     * 前缀安全：使能帧 0x000F 必须放在最后——序列被取消只发出前缀时电机不可能启动。
     *
     * @param nodeId   节点 ID
     * @param speedRpm 目标转速，有符号，正数正转，负数反转，单位 RPM
     * @return 按顺序发送的帧列表
     */
    fun buildJogStartSequence(nodeId: Int, speedRpm: Int): List<ByteArray> = listOf(
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x0006L),              // Shutdown → Ready
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x0007L),              // Switch On → Switched On
        buildSdoWriteFrame(nodeId, 0x6060, 0x00, 1, 0x03L),                // 速度模式
        buildSdoWriteFrame(nodeId, 0x60FF, 0x00, 4, speedRpm.toLong()),    // 目标速度
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x000FL)               // Enable Operation
    )

    /**
     * 点动停止第一阶段：将目标速度归零，电机按 0x6084 减速斜率自然减速至停止。
     *
     * 此帧是安全关键帧且幂等——调用方应连发多次（间隔 ≥20ms）抗丢帧，并在减速完成
     * （≈ jogSpeed / acceleration × 1000 ms + 200ms）后追发 [buildJogStopDisableFrames]
     * 兜底。现场失效教训：单发一帧 v=0 被总线并发挤掉后电机一直转到机械限位。
     */
    fun buildJogVelocityZeroFrame(nodeId: Int): ByteArray =
        buildSdoWriteFrame(nodeId, 0x60FF, 0x00, 4, 0L)

    /**
     * 点动停止第二阶段（减速完成后发送）：
     *   1. Disable Operation → Switched On (0x6040 = 0x0007)
     *   2. 预切回位置模式                    (0x6060 = 0x01)
     *
     * 故意不带 0x000F 使能——退到 Switched On 后即使 v=0 全部丢失电机也物理上转不了
     * （runaway 兜底），丝杆自锁不会掉深。后续位置控制经 [buildAbsoluteMoveFrames]
     * 的 0x000F → 0x002F 从 Switched On 重新使能，与 Phase 4 兼容；下次点动启动
     * 以 0x0006 开头，同样兼容。
     */
    fun buildJogStopDisableFrames(nodeId: Int): List<ByteArray> = listOf(
        buildSdoWriteFrame(nodeId, 0x6040, 0x00, 2, 0x0007L),   // Disable Operation
        buildSdoWriteFrame(nodeId, 0x6060, 0x00, 1, 0x01L)      // 预切位置模式
    )

    /**
     * 构建设置加减速度帧序列。
     * 0x6083 = Profile Acceleration, 0x6084 = Profile Deceleration，单位 RPM/s。
     * 在速度模式（点动）启动前调用，确保启动和停止斜率受控。
     *
     * @param nodeId      节点 ID
     * @param accelRpm_s  加减速度，单位 RPM/s（建议范围 1000~60000）
     */
    fun buildSetAccelDecelFrames(nodeId: Int, accelRpm_s: Int): List<ByteArray> = listOf(
        buildSdoWriteFrame(nodeId, 0x6083, 0x00, 4, accelRpm_s.toLong()),  // Profile Acceleration
        buildSdoWriteFrame(nodeId, 0x6084, 0x00, 4, accelRpm_s.toLong())   // Profile Deceleration
    )

    /**
     * 构建读取实际位置（0x6064）请求帧。
     * 回复为有符号 32 位编码器计数值，用 [parseSdoResponseSigned32] 解析。
     */
    fun buildReadPositionFrame(nodeId: Int): ByteArray =
        buildSdoReadFrame(nodeId, 0x6064, 0x00)

    /**
     * 构建读取状态字（0x6041）请求帧。
     * 回复用 [parseStatusWord] 解析。
     */
    fun buildReadStatusWordFrame(nodeId: Int): ByteArray =
        buildSdoReadFrame(nodeId, 0x6041, 0x00)

    /**
     * 构建读取工作模式（0x6060）请求帧。
     */
    fun buildReadModeFrame(nodeId: Int): ByteArray =
        buildSdoReadFrame(nodeId, 0x6060, 0x00)

    // ─────────────────────────────────────────────────────────────────────
    // 状态字解析
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 状态字（0x6041）解析结果。
     *
     * @property targetReached        Bit10：位置模式已到达目标位置 / 速度模式已到达目标转速
     * @property homingDone           Bit12：找原点完成
     * @property positiveLimitReached Bit14：到达正限位
     * @property negativeLimitReached Bit15：到达负限位
     * @property rawValue             原始 16 位状态字
     */
    data class StatusFlags(
        val targetReached: Boolean,
        val homingDone: Boolean,
        val positiveLimitReached: Boolean,
        val negativeLimitReached: Boolean,
        val rawValue: Int
    )

    /**
     * 解析状态字（0x6041）各标志位。
     *
     * @param statusWord 状态字原始值（16 位无符号，从 SDO 读取或 TPDO 解析而来）
     */
    fun parseStatusWord(statusWord: Int): StatusFlags = StatusFlags(
        targetReached         = (statusWord and (1 shl 10)) != 0,
        homingDone            = (statusWord and (1 shl 12)) != 0,
        positiveLimitReached  = (statusWord and (1 shl 14)) != 0,
        negativeLimitReached  = (statusWord and (1 shl 15)) != 0,
        rawValue              = statusWord
    )

    // ─────────────────────────────────────────────────────────────────────
    // TPDO1 解析（电机主动上报）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * TPDO1 解析结果。
     *
     * @property nodeId      发送此帧的舵机节点 ID（11~18）
     * @property actualPos   实际位置（有符号 32 位编码器计数值）
     * @property statusWord  状态字（16 位），用 [parseStatusWord] 进一步解析
     */
    data class Tpdo1Data(
        val nodeId: Int,
        val actualPos: Int,
        val statusWord: Int
    )

    /**
     * 尝试解析 TPDO1 帧。
     *
     * TPDO1 CAN-ID = 0x180 + Node-ID（深度舵机范围：0x18B~0x192，即 Node-ID 11~18）
     * 数据格式（6 字节）：[实际位置 4B 小端] [状态字 2B 小端]
     *
     * @param canId 接收到的 CAN-ID
     * @param data  帧数据段（unwrapCanFrame 解出）
     * @return [Tpdo1Data]；非深度舵机 TPDO1 帧或数据不足时返回 null
     */
    fun parseTpdo1(canId: Int, data: ByteArray): Tpdo1Data? {
        val nodeId = canId - 0x180
        if (nodeId !in 11..18) return null
        if (data.size < 6) return null
        val pos = (data[0].toLong() and 0xFF) or
                  ((data[1].toLong() and 0xFF) shl 8) or
                  ((data[2].toLong() and 0xFF) shl 16) or
                  ((data[3].toLong() and 0xFF) shl 24)
        val sw = (data[4].toInt() and 0xFF) or
                 ((data[5].toInt() and 0xFF) shl 8)
        return Tpdo1Data(nodeId = nodeId, actualPos = pos.toInt(), statusWord = sw)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 直接发送方法
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 发送单帧到 CAN 串口（仅字节级线程安全，无帧间步调）。
     *
     * 优先使用 [sendFrameSequenced] / [sendSequence]——它们跨协程强制全局帧间隔。
     * 本方法仅供无法挂起的场景直接调用；SDO 时序（帧间 ≥20ms）由调用方自行负责。
     */
    fun sendFrame(frame: ByteArray) {
        val outputStream = mSerialPortCAN?.outputStream ?: run {
            Log.e(TAG, "CAN 串口未打开，无法发送 CANopen 帧")
            return
        }
        // 锁稳定的 CAN_TX_LOCK 而非 outputStream：端口重开后 outputStream 实例更换，
        // 锁身份失效会让施肥帧与 CANopen 帧字节交错（缺陷 L10）
        synchronized(MySerialPortFun.CAN_TX_LOCK) {
            try {
                outputStream.write(frame)
                outputStream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "CANopen 帧发送失败: ${e.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 全局串行化发送（所有 CANopen 发送方共用的时序仲裁）
    //
    // 现场失效教训：点动协程与 SowingDepthCoroutine 各自内部隔 20ms，但跨协程
    // 的帧可背靠背落到同一节点——驱动器单 SDO 服务端丢掉后到的请求。丢 0x60FF/
    // 0x000F = 点动不转；丢松开时的 v=0 = 电机失控转到机械限位。此处用一把
    // Mutex + 全局最小帧间隔把所有 CANopen 帧排成串行流，多帧序列持锁原子发送，
    // 不再可能被其他发送方的帧插队打断。
    //
    // 使用约定：
    //   - 禁止持锁跨长等待（点动减速等待等必须留在锁外），锁内只有帧发送 + 20ms 间隔
    //   - 序列须"前缀安全"：协程被取消时序列可能只发出前缀，使能帧必须放最后
    //   - 施肥帧（slaveCanMsgSend）只共享字节级 CAN_TX_LOCK，不走此步调，时序零回归
    // ─────────────────────────────────────────────────────────────────────

    /** 全局 CANopen 最小帧间隔（ms）：SDO 请求-应答周期 + CSM100T 桥缓冲余量 */
    const val GLOBAL_INTER_FRAME_MS = 20L

    private val sdoMutex = Mutex()

    /** 上一 CANopen 帧的发出时刻（elapsedRealtime，免疫系统时钟跳变）；仅持锁访问 */
    private var lastTxElapsedMs = 0L

    /** 持锁前提下：距上一帧不足 [GLOBAL_INTER_FRAME_MS] 则补足等待，再发送。 */
    private suspend fun paceThenSend(frame: ByteArray) {
        val sinceLast = SystemClock.elapsedRealtime() - lastTxElapsedMs
        if (sinceLast in 0 until GLOBAL_INTER_FRAME_MS) {
            delay(GLOBAL_INTER_FRAME_MS - sinceLast)
        }
        sendFrame(frame)
        lastTxElapsedMs = SystemClock.elapsedRealtime()
    }

    /**
     * 串行化发送单帧：与所有其他 Sequenced 发送方共享全局 20ms 步调。
     */
    suspend fun sendFrameSequenced(frame: ByteArray) {
        sdoMutex.withLock { paceThenSend(frame) }
    }

    /**
     * 串行化原子发送多帧序列：整个序列持锁发送，帧间强制 [interFrameDelayMs]，
     * 其他发送方的帧不可能插入序列中间。
     *
     * 注意：调用协程被取消时序列可能只发出前缀（delay 是取消点），
     * 构建序列时使能帧必须放在最后（前缀安全）。
     */
    suspend fun sendSequence(
        frames: List<ByteArray>,
        interFrameDelayMs: Long = GLOBAL_INTER_FRAME_MS
    ) {
        sdoMutex.withLock {
            for (frame in frames) {
                val sinceLast = SystemClock.elapsedRealtime() - lastTxElapsedMs
                if (sinceLast in 0 until interFrameDelayMs) {
                    delay(interFrameDelayMs - sinceLast)
                }
                sendFrame(frame)
                lastTxElapsedMs = SystemClock.elapsedRealtime()
            }
        }
    }

    /**
     * 顺序发送多帧（帧间时序/等待由调用方协程负责）。
     */
    @Deprecated(
        "无帧间步调且不参与全局串行化，会与其他发送方并发丢帧；改用 sendSequence",
        ReplaceWith("sendSequence(frames)")
    )
    fun sendFrames(frames: List<ByteArray>) {
        frames.forEach { sendFrame(it) }
    }
}

/**
 * 点动会话标志：标记某节点正被标定页点动占用。
 *
 * SowingDepthCoroutine 据此对该节点让路（跳过 Phase 2 初始化与 Phase 4 位置下发），
 * 否则位置模式帧会与速度模式点动打架——现场表现为点动失灵或电机自行走位。
 *
 * 为什么是顶层 object + AtomicInteger：
 *   - 作业模式（MainActivity）与标定页各自持有一个 SowingDepthCoroutine 实例，
 *     两者必须读到同一份标志，不能做协程实例状态；
 *   - LiveData 经主线程 post 有滞后且有竞态，不适合做实时互斥依据；
 *   - 标定页同一时刻只点动一台电机，单个原子量足够。
 *
 * begin/end 用 CAS 语义：迟到的旧 stop 清不掉新按下建立的会话。
 */
object JogSession {

    /** 当前点动中的 nodeId；NONE 表示无点动 */
    private const val NONE = -1
    private val activeNode = AtomicInteger(NONE)

    /** 开始点动会话（覆盖式：新按下总是接管）。 */
    fun begin(nodeId: Int) {
        activeNode.set(nodeId)
    }

    /**
     * 结束点动会话：仅当会话仍属于该节点时清除（CAS），
     * 防止上一次迟到的 stop 收尾误清新一次按下的会话。
     */
    fun end(nodeId: Int) {
        activeNode.compareAndSet(nodeId, NONE)
    }

    fun isJogging(nodeId: Int): Boolean = activeNode.get() == nodeId

    /** 无条件清除（页面销毁兜底用）。 */
    fun clearAll() {
        activeNode.set(NONE)
    }
}
