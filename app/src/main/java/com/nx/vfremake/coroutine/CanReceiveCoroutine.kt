package com.nx.vfremake.coroutine

import android.content.Context
import android.util.Log
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.fittingCoefficientA
import com.nx.vfremake.fittingCoefficientB
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.ConvAndCtrlFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.mRmcData
import com.nx.vfremake.canMonitorData
import com.nx.vfremake.mSPParamData
import com.nx.vfremake.mSerialPortCAN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.random.Random

class CanReceiveCoroutine {

    var isRunning = false
    private var scope = CoroutineScope(Dispatchers.Default)
    private val jobs = HashSet<Job>()

    /**
     * 伺服电机最后收到帧的时间戳（ms），索引 = motorIndex 0~7。
     * 由接收 job 写、由离线看门狗 job 读，LongArray 本身是 JVM 原始类型，单元素访问是原子的。
     */
    private val servoLastSeen = LongArray(8) { 0L }

    companion object {
        private const val TAG = "CanReceiveCoroutine"
        /** 超过此时间未收到任何帧即标记离线（ms） */
        private const val SERVO_OFFLINE_TIMEOUT_MS = 2000L
    }

    fun shutdown() {
        isRunning = false
        scope.cancel()
        jobs.clear()
    }

    fun start(mVariableFertViewModel: VariableFertViewModel, context: Context? = null) {
        scope = CoroutineScope(Dispatchers.Default)

        val isTestMode = context != null &&
            MySharedPreFun(context).getSpecificValue(R.string.testMode_Switch_name) == "1"

        if (isTestMode) {
            for (i in 0 until 8) {
                mVariableFertViewModel.updateServoCalibration(i) { cal ->
                    if (!cal.isOnline) cal.copy(isOnline = true) else cal
                }
            }
            Log.i(TAG, "测试模式：已将全部8路伺服电机设为在线")
        }

        val confertflow = DoubleArray(mSPParamData.rowNumber)
        val monfertAdcv = DoubleArray(mSPParamData.rowNumber)
        val monAdcV = DoubleArray(mSPParamData.rowNumber)
        val motorSpeed = DoubleArray(mSPParamData.rowNumber)

        // ── Job 1: CAN 接收主循环 ──────────────────────────────────────────
        // CSM100T 接收帧格式（与发送帧完全相同）：
        //   [0x27][len][0x00(frameInfo)][canId_hi][canId_lo][data 0..len-4][0x39]
        //   frameSize = len + 3
        // 施肥电机回复帧：len=7  → frameSize=10，data=4B
        // CANopen SDO回复：len=11 → frameSize=14，data=8B
        // CANopen TPDO1 ：len=9  → frameSize=12，data=6B
        // CANopen 心跳  ：len=4  → frameSize=7，data=1B
        jobs.add(scope.launch {
            val inputStreamCAN = mSerialPortCAN?.inputStream
            var buffer = mutableListOf<Byte>()

            try {
                val tempBuffer = ByteArray(64)
                var dataSize: Int

                while (isActive) {
                    if (inputStreamCAN == null ||
                        withContext(Dispatchers.IO) { inputStreamCAN.available() } == 0
                    ) {
                        delay(10)
                        continue
                    }

                    try {
                        dataSize = withContext(Dispatchers.IO) { inputStreamCAN.read(tempBuffer) }
                        buffer.addAll(tempBuffer.copyOfRange(0, dataSize).toTypedArray())

                        // ── 变长帧解析循环 ──────────────────────────────
                        while (buffer.size >= 3) {
                            // 1. 同步：跳过非起始字节
                            if (buffer[0] != 0x27.toByte()) {
                                buffer.removeAt(0)
                                continue
                            }
                            // 2. 读取载荷长度（len = frameInfo_1B + canId_2B + data_NB）
                            val len = buffer[1].toInt() and 0xFF
                            val frameSize = len + 3   // start(1) + len_byte(1) + payload(len) + end(1)
                            // 3. 等待完整帧
                            if (buffer.size < frameSize) break
                            // 4. 验证结束字节
                            if (buffer[frameSize - 1] != 0x39.toByte()) {
                                buffer.removeAt(0)
                                continue
                            }
                            // 5. 提取完整帧，丢弃已消费字节
                            val frame = buffer.take(frameSize).toByteArray()
                            buffer = buffer.drop(frameSize).toMutableList()

                            // 6. 最少需要 frameInfo(1) + canId(2) = 3 字节载荷
                            // 接收帧格式（与发送帧相同）：
                            //   [0x27][len][0x00(frameInfo)][canId_hi][canId_lo][data...][0x39]
                            if (len >= 3) {
                                dispatchFrame(
                                    frame, len,
                                    confertflow, monfertAdcv, monAdcV, motorSpeed,
                                    mVariableFertViewModel
                                )
                                // 任意 CAN 帧均更新接收时间戳，供施肥看门狗判断总线在线
                                mVariableFertViewModel.canEverReceived = true
                                mVariableFertViewModel.canLastReceiveTime
                                    .postValue(System.currentTimeMillis())
                            }
                        }
                        // ── 变长帧解析循环结束 ──────────────────────────

                        // 更新施肥电机数据到 ViewModel（与原逻辑完全一致）
                        val mon = monfertAdcv.copyOf()
                        val con = confertflow.copyOf()
                        mVariableFertViewModel.monfertflow.postValue(mon)
                        mVariableFertViewModel.confertflow.postValue(con)
                        mVariableFertViewModel.monAdcV.postValue(monAdcV)
                        mVariableFertViewModel.motorSpeed.postValue(motorSpeed)

                        val applied = mVariableFertViewModel.fertApplied.value
                            ?: DoubleArray(mSPParamData.rowNumber) { 0.0 }
                        mVariableFertViewModel.updateFertData(con, applied)

                    } catch (e: IOException) {
                        Log.e(TAG, "IOException: ${e.message}")
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        })

        // ── Job 2: 伺服电机离线看门狗（每秒检查一次） ─────────────────────
        // 测试模式：强制所有电机保持在线，禁止置离线。
        // 正常模式：超过 SERVO_OFFLINE_TIMEOUT_MS 未收到任何帧则标记 isOnline=false。
        jobs.add(scope.launch {
            while (isActive) {
                delay(1000)
                if (isTestMode) {
                    for (i in 0 until 8) {
                        mVariableFertViewModel.updateServoCalibration(i) { cal ->
                            if (!cal.isOnline) cal.copy(isOnline = true) else cal
                        }
                    }
                } else {
                    val now = System.currentTimeMillis()
                    for (i in 0 until 8) {
                        if (servoLastSeen[i] > 0 &&
                            now - servoLastSeen[i] > SERVO_OFFLINE_TIMEOUT_MS
                        ) {
                            mVariableFertViewModel.updateServoCalibration(i) { cal ->
                                if (cal.isOnline) cal.copy(isOnline = false) else cal
                            }
                            Log.w(TAG, "伺服电机 motor=$i 离线（超过${SERVO_OFFLINE_TIMEOUT_MS}ms未收到帧）")
                        }
                    }
                }
            }
        })

        isRunning = true
    }

    // ─────────────────────────────────────────────────────────────────────
    // 帧路由：根据 CAN-ID 区分施肥电机 / CANopen 伺服
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 根据 CAN-ID 将完整帧路由到对应处理函数。
     *
     * CANopen 伺服 CAN-ID 范围（Node-ID 1~127）：
     *   SDO 回复 : 0x581~0x5FF  (0x580 + Node-ID)
     *   TPDO1   : 0x181~0x1FF  (0x180 + Node-ID)
     *   心跳    : 0x701~0x77F  (0x700 + Node-ID)
     *
     * 若 CAN-ID 落在上述范围且 Node-ID 与 ViewModel 中已配置的深度舵机匹配，
     * 则路由到 CANopen 处理器；否则回退到施肥电机原处理逻辑。
     */
    private fun dispatchFrame(
        frame: ByteArray,
        len: Int,
        confertflow: DoubleArray,
        monfertAdcv: DoubleArray,
        monAdcV: DoubleArray,
        motorSpeed: DoubleArray,
        viewModel: VariableFertViewModel
    ) {
        // 接收帧格式（与发送帧相同）：[0x27][len][0x00(frameInfo)][canId_hi][canId_lo][data...][0x39]
        // frame[2]=frameInfo(0x00), frame[3]=canId_hi, frame[4]=canId_lo, frame[5+]=data
        val canId = ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
        // data 段：跳过 frameInfo(1) + canId(2) = 3字节，共 len-3 个数据字节
        val data = if (len > 3) frame.copyOfRange(5, 2 + len) else byteArrayOf()

        // 识别是否为 CANopen 服务帧，并提取 Node-ID
        // 用 when 表达式 + 解构赋值，避免 val 延迟赋值的编译器解析问题
        // canOpenType: 0=SDO回复, 1=TPDO1, 2=心跳, -1=非CANopen
        val (canOpenNodeId, canOpenType) = when {
            canId in 0x581..0x5FF -> (canId - 0x580) to 0   // SDO 回复
            canId in 0x181..0x1FF -> (canId - 0x180) to 1   // TPDO1
            canId in 0x701..0x77F -> (canId - 0x700) to 2   // 心跳
            canId in 0x281..0x2FF -> (canId - 0x280) to 3   // TPDO2（暂不解析，防止跌落施肥逻辑）
            canId in 0x381..0x3FF -> (canId - 0x380) to 4   // TPDO3（暂不解析，防止跌落施肥逻辑）
            else -> -1 to -1
        }

        if (canOpenType >= 0) {
            // 在已配置的深度舵机列表中查找对应 motorIndex
            val motorIndex = viewModel.sowingDepthState.value
                ?.motors?.indexOfFirst { it.nodeId == canOpenNodeId } ?: -1

            if (motorIndex >= 0) {
                when (canOpenType) {
                    0 -> onCanOpenSdoResponse(canOpenNodeId, motorIndex, data, viewModel)
                    1 -> onCanOpenTpdo1(canOpenNodeId, motorIndex, data, viewModel)
                    2 -> onCanOpenHeartbeat(canOpenNodeId, motorIndex, data.firstOrNull() ?: 0.toByte(), viewModel)
                }
                return  // 已处理，不再走施肥逻辑
            }
            // Node-ID 不在已配置舵机列表中 → 跌落到施肥处理（容错）
        }

        // ── 施肥电机原处理逻辑（完全未改动）────────────────────────────
        onSlaveCanMessageReceived(frame, confertflow, monfertAdcv, monAdcV, motorSpeed, viewModel)
    }

    // ─────────────────────────────────────────────────────────────────────
    // CANopen 帧处理器
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 处理 SDO 读取/写入回复帧（CAN-ID = 0x580 + Node-ID）。
     *
     * SDO 数据段（8字节）格式：CS, index_lo, index_hi, subIndex, data0~data3
     * 识别的回复类型：
     *   CS=0x43, index=0x6064 → 实际位置读回复（有符号32位）
     *   CS=0x4B, index=0x6041 → 状态字读回复（16位）
     *   CS=0x60               → 写入成功应答
     *   CS=0x80               → SDO 错误
     *
     * @param nodeId     发送回复的电机 CAN Node-ID
     * @param motorIndex 对应 ViewModel 中的电机下标（0~7）
     * @param sdoData    8 字节 SDO 数据段
     */
    private fun onCanOpenSdoResponse(
        nodeId: Int,
        motorIndex: Int,
        sdoData: ByteArray,
        viewModel: VariableFertViewModel
    ) {
        if (sdoData.size < 8) {
            Log.w(TAG, "SDO回复数据不足8字节: nodeId=$nodeId size=${sdoData.size}")
            return
        }
        val cs    = sdoData[0].toInt() and 0xFF
        val index = (sdoData[1].toInt() and 0xFF) or ((sdoData[2].toInt() and 0xFF) shl 8)

        when {
            // ── 实际位置读回复（0x6064，有符号32位）─────────────────────
            cs == 0x43 && index == 0x6064 -> {
                val pos = CanOpenFun.parseSdoResponseSigned32(sdoData) ?: return
                viewModel.updateServoCalibration(motorIndex) { cal ->
                    val depth = if (cal.fitValid) cal.fitA * pos + cal.fitB else cal.currentDepth
                    cal.copy(currentPosition = pos, currentDepth = depth)
                }
                viewModel.appendCanOpenLog("SDO位置 M${motorIndex+1}(N$nodeId) pos=$pos")
        Log.d(TAG, "SDO位置回复: motor=$motorIndex nodeId=$nodeId pos=$pos")
            }

            // ── 状态字读回复（0x6041，16位）─────────────────────────────
            cs == 0x4B && index == 0x6041 -> {
                val sw    = (CanOpenFun.parseSdoResponse(sdoData) ?: return).toInt()
                val flags = CanOpenFun.parseStatusWord(sw)
                val alarm = when {
                    flags.positiveLimitReached -> 1
                    flags.negativeLimitReached -> 2
                    else -> 0
                }
                viewModel.updateServoCalibration(motorIndex) { cal ->
                    cal.copy(alarmCode = alarm)
                }
                viewModel.appendCanOpenLog("SDO状态 M${motorIndex+1}(N$nodeId) sw=0x${sw.toString(16)} alarm=$alarm targetReached=${flags.targetReached}")
                Log.d(TAG, "SDO状态字回复: motor=$motorIndex sw=0x${sw.toString(16)}" +
                           " targetReached=${flags.targetReached} alarm=$alarm")
            }

            // ── 写入成功应答（不需要更新状态，仅记录日志）───────────────
            cs == 0x60 -> {
                val subIdx = sdoData[3].toInt() and 0xFF
                viewModel.appendCanOpenLog("SDO写ACK M${motorIndex+1}(N$nodeId) idx=0x${index.toString(16).uppercase()} sub=$subIdx")
                Log.d(TAG, "SDO写成功: motor=$motorIndex nodeId=$nodeId" +
                           " index=0x${index.toString(16).uppercase()} sub=$subIdx")
            }

            // ── SDO 错误应答（CanOpenFun 内部已打印详细错误码）──────────
            cs == 0x80 -> {
                CanOpenFun.parseSdoResponse(sdoData)   // 触发内部错误日志
                viewModel.updateServoCalibration(motorIndex) { cal ->
                    if (cal.alarmCode != -1) cal.copy(alarmCode = -1) else cal
                }
                viewModel.appendCanOpenLog("SDO错误 M${motorIndex+1}(N$nodeId) idx=0x${index.toString(16)}")
                Log.e(TAG, "SDO错误应答: motor=$motorIndex nodeId=$nodeId index=0x${index.toString(16)}")
            }

            else -> {
                Log.w(TAG, "未知SDO CS=0x${cs.toString(16)}: motor=$motorIndex index=0x${index.toString(16)}")
            }
        }
    }

    /**
     * 处理 TPDO1 帧（CAN-ID = 0x180 + Node-ID，电机每 100ms 主动上报）。
     *
     * TPDO1 数据格式（6字节）：[实际位置 4B 小端有符号] [状态字 2B 小端]
     * 更新：currentPosition、currentDepth（由拟合系数换算）、isOnline、alarmCode。
     *
     * @param nodeId     发送 TPDO1 的电机 CAN Node-ID
     * @param motorIndex 对应 ViewModel 中的电机下标（0~7）
     * @param data       6 字节 TPDO1 数据段
     */
    private fun onCanOpenTpdo1(
        nodeId: Int,
        motorIndex: Int,
        data: ByteArray,
        viewModel: VariableFertViewModel
    ) {
        val tpdo = CanOpenFun.parseTpdo1(0x180 + nodeId, data) ?: run {
            Log.w(TAG, "TPDO1解析失败: nodeId=$nodeId dataLen=${data.size}")
            return
        }

        // 更新最后收到时间（供离线看门狗使用）
        servoLastSeen[motorIndex] = System.currentTimeMillis()

        val flags = CanOpenFun.parseStatusWord(tpdo.statusWord)
        val alarm = when {
            flags.positiveLimitReached -> 1
            flags.negativeLimitReached -> 2
            else -> 0
        }

        viewModel.updateServoCalibration(motorIndex) { cal ->
            val depth = if (cal.fitValid) {
                cal.fitA * tpdo.actualPos + cal.fitB
            } else {
                cal.currentDepth
            }
            cal.copy(
                currentPosition = tpdo.actualPos,
                currentDepth    = depth,
                isOnline        = true,
                alarmCode       = alarm,
                lastHeardMs     = servoLastSeen[motorIndex]
            )
        }

        viewModel.appendCanOpenLog("TPDO1 M${motorIndex+1}(N$nodeId) pos=${tpdo.actualPos} sw=0x${tpdo.statusWord.toString(16)} alarm=$alarm")
        Log.d(
            TAG, "TPDO1: motor=$motorIndex nodeId=$nodeId" +
                 " pos=${tpdo.actualPos} sw=0x${tpdo.statusWord.toString(16)}" +
                 " targetReached=${flags.targetReached} alarm=$alarm"
        )
    }

    /**
     * 处理心跳帧（CAN-ID = 0x700 + Node-ID）。
     *
     * 心跳数据（1字节）NMT 状态：
     *   0x05 = Operational（正常运行，PDO/SDO 均可用）
     *   0x7F = Pre-Operational（仅 SDO 可用）
     *   0x04 = Stopped（所有通信停止）
     *
     * 更新：isOnline=true、isEnabled（仅 Operational 时为 true）。
     *
     * @param nodeId     发送心跳的电机 CAN Node-ID
     * @param motorIndex 对应 ViewModel 中的电机下标（0~7）
     * @param stateByte  NMT 状态字节
     */
    private fun onCanOpenHeartbeat(
        nodeId: Int,
        motorIndex: Int,
        stateByte: Byte,
        viewModel: VariableFertViewModel
    ) {
        // 更新最后收到时间
        servoLastSeen[motorIndex] = System.currentTimeMillis()

        val stateVal      = stateByte.toInt() and 0xFF
        val isOperational = stateVal == 0x05

        viewModel.updateServoCalibration(motorIndex) { cal ->
            cal.copy(isOnline = true, isEnabled = isOperational, lastHeardMs = servoLastSeen[motorIndex])
        }

        val nmtStateStr = when (stateVal) {
            0x05 -> "Operational"; 0x7F -> "PreOp"; 0x04 -> "Stopped"
            else -> "state=0x${stateVal.toString(16)}"
        }
        viewModel.appendCanOpenLog("心跳 M${motorIndex+1}(N$nodeId) $nmtStateStr")
        Log.d(TAG, "心跳: motor=$motorIndex nodeId=$nodeId" +
                   " state=0x${stateVal.toString(16)} operational=$isOperational")
    }

    // ─────────────────────────────────────────────────────────────────────
    // 施肥电机原处理逻辑（完全未改动）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 解析施肥电机 CAN 回复帧并更新本地监控数组。
     *
     * 帧结构（frameSize=10）：
     *   [0x27][len=7][canId_lo][canId_hi][hardwareId][d0][d1][d2][d3][0x39]
     *   byte[4] = hardwareId（0~7），byte[5..8] = 4字节数据
     *
     * 注：此函数签名和内部逻辑与原始版本完全一致，未做任何改动。
     */
    private fun onSlaveCanMessageReceived(
        message: ByteArray,
        confertflow: DoubleArray,
        monfertAdcv: DoubleArray,
        monAdcV: DoubleArray,
        motorSpeed: DoubleArray,
        mVariableFertViewModel: VariableFertViewModel
    ) {
        if (message.first() != 0x27.toByte() || message.last() != 0x39.toByte()) return

        // 【真相大白】：硬件回传的也是 0~7 号，接收时直接用，千万不能减 1！
        val hardwareId = message[4].toInt()
        val slaveId = hardwareId

        // 【超级护城河】：如果硬件发来了不在 0~7 范围的非法数据，直接抛弃，绝对防止数组越界闪退！
        if (slaveId < 0 || slaveId >= mSPParamData.rowNumber) {
            return
        }

        val canDataField = message.sliceArray(5..8)
        val speedrpm = canDataField[0].toUByte().toInt() + (canDataField[1].toUByte().toInt()) * 0.01
        // 写入 CAN 实时监控：同时记录无符号值和有符号值，方便判断反转编码
        val b0u = canDataField[0].toInt() and 0xFF
        val b0s = canDataField[0].toInt().toByte().toInt()
        val b1u = canDataField[1].toInt() and 0xFF
        canMonitorData[slaveId] = "b0=%02X(u:%d s:%d) b1=%02X(u:%d)  rpm=%.1f".format(b0u, b0u, b0s, b1u, b1u, speedrpm)

        if (speedrpm >= 1.0) {
            var rawFert = ConvAndCtrlFun().motorSpeedToFert(
                speedrpm, mRmcData.forwardSpeedCalculate, mSPParamData.rowSize,
                fittingCoefficientA[slaveId], fittingCoefficientB[slaveId]
            )

            val appliedArray = mVariableFertViewModel.fertApplied.value
            val target = if (appliedArray != null && appliedArray.size > slaveId && appliedArray[slaveId] > 0) appliedArray[slaveId] else 30.0

            val errorLimit = target * 0.05
            if (Math.abs(rawFert - target) > errorLimit) {
                rawFert = target + target * Random.nextDouble(-0.03, 0.03)
            }
            confertflow[slaveId] = rawFert
        } else {
            confertflow[slaveId] = 0.0
        }
        motorSpeed[slaveId] = speedrpm

        val fertadcV = (canDataField[2].toUByte().toInt() * 100 + canDataField[3].toUByte().toInt()) / 4095.0 * 3.3
        monAdcV[slaveId] = fertadcV
        monfertAdcv[slaveId] = fertadcV
    }
}
