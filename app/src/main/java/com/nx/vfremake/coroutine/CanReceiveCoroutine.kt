package com.nx.vfremake.coroutine

import android.content.Context
import android.util.Log
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.data.activeSowingDepthMotorIndices
import com.nx.vfremake.data.isSowingDepthMotorActive
import com.nx.vfremake.data.SERVO_ALARM_DRIVE_FAULT
import com.nx.vfremake.data.SERVO_ALARM_INTERNAL_LIMIT
import com.nx.vfremake.data.SERVO_ALARM_NONE
import com.nx.vfremake.data.SERVO_ALARM_SDO_ABORT
import com.nx.vfremake.fittingCoefficientA
import com.nx.vfremake.fittingCoefficientB
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.ConvAndCtrlFun
import com.nx.vfremake.funClass.EncoderCanOpenFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.funClass.SdoReplyWaiters
import com.nx.vfremake.mRmcData
import com.nx.vfremake.canMonitorData
import com.nx.vfremake.mSPParamData
import com.nx.vfremake.mSerialPortCAN
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.IOException
import kotlin.math.sin
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

    /**
     * 摆臂编码器最后收到帧的时间戳（ms），索引 = motorIndex 0~7。
     * 沿用 servoLastSeen 模式：以 TPDO/SDO 应答到达判断在线（不启用编码器心跳），
     * 超时阈值与伺服一致（SERVO_OFFLINE_TIMEOUT_MS）。
     */
    private val encoderLastSeen = LongArray(8) { 0L }

    /**
     * 每路编码器的两级滤波器（限幅野值剔除 + 滑动均值）。
     * 仅由接收 job 串行调用，无需同步（见 EncoderFilter 线程约定）。
     */
    private val encoderFilters = Array(8) { EncoderCanOpenFun.EncoderFilter() }

    companion object {
        private const val TAG = "CanReceiveCoroutine"
        /** 超过此时间未收到任何帧即标记离线（ms），伺服与编码器共用 */
        private const val SERVO_OFFLINE_TIMEOUT_MS = 2000L

        /**
         * 纯函数：CANopen 服务帧段识别（canId → (nodeId, type)）。
         * type: 0=SDO回复, 1=TPDO1, 2=心跳/Boot-up, 3=TPDO2, 4=TPDO3, -1=非CANopen。
         *
         * 从 dispatchFrame 原地的 when 表达式提取，映射逐字节不变——提取仅为可单元
         * 测试（验证编码器帧不跌落施肥解析、伺服/施肥路由与改动前一致）。
         */
        fun classifyCanOpen(canId: Int): Pair<Int, Int> = when {
            canId in 0x581..0x5FF -> (canId - 0x580) to 0   // SDO 回复
            canId in 0x181..0x1FF -> (canId - 0x180) to 1   // TPDO1
            canId in 0x701..0x77F -> (canId - 0x700) to 2   // 心跳 / Boot-up
            canId in 0x281..0x2FF -> (canId - 0x280) to 3   // TPDO2（暂不解析，防止跌落施肥逻辑）
            canId in 0x381..0x3FF -> (canId - 0x380) to 4   // TPDO3（暂不解析，防止跌落施肥逻辑）
            else -> -1 to -1
        }
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
            val activeIndices = activeSowingDepthMotorIndices(
                mSPParamData.rowNumber,
                mVariableFertViewModel.activeMotorsState.value ?: mSPParamData.activeMotors
            )
            for (i in activeIndices) {
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
            var buffer = mutableListOf<Byte>()
            val tempBuffer = ByteArray(64)
            // 上一轮使用的输入流引用：端口被重开后（新 SerialPort → 新 fd → 新流）会变化，
            // 据此切换到新流并丢弃跨流残字节，实现端口关闭/重开后的自愈接收。
            var lastStream: InputStream? = null

            while (isActive) {
                // ── 每轮重新获取串口流（不再一次性缓存）：端口被关后读到的流失效，
                //    重开后这里能立即拿到新流恢复接收 ──────────────────────────
                val stream = mSerialPortCAN?.inputStream
                if (stream == null) {
                    delay(20)
                    continue
                }
                // 流身份变化（端口重开）→ 清空缓冲，避免旧流残字节与新流数据拼接造成帧错位
                if (stream !== lastStream) {
                    buffer.clear()
                    lastStream = stream
                }

                try {
                    if (withContext(Dispatchers.IO) { stream.available() } == 0) {
                        delay(10)
                        continue
                    }

                    val dataSize = withContext(Dispatchers.IO) { stream.read(tempBuffer) }
                    // read() 返回 -1(EOF) 或 0：绝不能 copyOfRange(0,-1)（会抛 IllegalArgumentException
                    // 杀死接收协程），跳过本轮，下一轮重新获取流。
                    if (dataSize <= 0) {
                        delay(10)
                        continue
                    }
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
                            // 单帧解析异常隔离：解析出错不应杀死整个接收循环；
                            // 协程取消（CancellationException）必须原样抛出，否则会吞掉取消。
                            try {
                                dispatchFrame(
                                    frame, len,
                                    confertflow, monfertAdcv, monAdcV, motorSpeed,
                                    mVariableFertViewModel
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Log.e(TAG, "dispatchFrame 解析异常（已跳过该帧）: ${e.message}", e)
                            }
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

                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // 端口被关/重开导致读写失败：清空缓冲、短暂等待，下一轮用新流恢复（不退出循环）
                    Log.w(TAG, "接收 IOException（将重试，端口可能已重开）: ${e.message}")
                    buffer.clear()
                    lastStream = null
                    delay(50)
                } catch (e: Throwable) {
                    // 兜底：任何未预期异常都不致死接收循环
                    Log.e(TAG, "接收循环未预期异常（将重试）: ${e.message}", e)
                    delay(50)
                }
            }
        })

        // ── Job 2: 伺服电机离线看门狗（每秒检查一次） ─────────────────────
        // 测试模式：强制所有电机保持在线，禁止置离线。
        // 正常模式：超过 SERVO_OFFLINE_TIMEOUT_MS 未收到任何帧则标记 isOnline=false。
        jobs.add(scope.launch {
            while (isActive) {
                delay(1000)
                if (isTestMode) {
                    val activeIndices = activeSowingDepthMotorIndices(
                        mSPParamData.rowNumber,
                        mVariableFertViewModel.activeMotorsState.value ?: mSPParamData.activeMotors
                    )
                    for (i in activeIndices) {
                        mVariableFertViewModel.updateServoCalibration(i) { cal ->
                            if (!cal.isOnline) cal.copy(isOnline = true) else cal
                        }
                    }
                } else {
                    val now = System.currentTimeMillis()
                    val activeIndices = activeSowingDepthMotorIndices(
                        mSPParamData.rowNumber,
                        mVariableFertViewModel.activeMotorsState.value ?: mSPParamData.activeMotors
                    )
                    for (i in activeIndices) {
                        if (servoLastSeen[i] > 0 &&
                            now - servoLastSeen[i] > SERVO_OFFLINE_TIMEOUT_MS
                        ) {
                            mVariableFertViewModel.updateServoCalibration(i) { cal ->
                                if (cal.isOnline) cal.copy(isOnline = false) else cal
                            }
                            Log.w(TAG, "伺服电机 motor=$i 离线（超过${SERVO_OFFLINE_TIMEOUT_MS}ms未收到帧）")
                        }
                    }
                    // 摆臂编码器离线判定：同阈值，全部 8 路（离线态展示由 UI 按行过滤）
                    for (i in 0 until 8) {
                        if (encoderLastSeen[i] > 0 &&
                            now - encoderLastSeen[i] > SERVO_OFFLINE_TIMEOUT_MS
                        ) {
                            mVariableFertViewModel.updateEncoderCalibration(i) { cal ->
                                if (cal.isOnline) cal.copy(isOnline = false) else cal
                            }
                        }
                    }
                }
            }
        })

        // ── Job 3（仅测试模式）: 编码器模拟数据 ───────────────────────────
        // 与伺服测试模式一致：无硬件时把编码器全部置在线并生成合理模拟数据
        // （跟随对应行伺服 targetDepth ± 缓慢正弦波动），保证标定页/主界面可开发调试。
        if (isTestMode) {
            jobs.add(scope.launch {
                Log.i(TAG, "测试模式：编码器模拟数据已启动（8路在线，跟随targetDepth波动）")
                var t = 0.0
                while (isActive) {
                    delay(200)
                    t += 0.2
                    val servoState = mVariableFertViewModel.currentSowingDepthState()
                    for (i in 0 until 8) {
                        val base = servoState.motors.getOrNull(i)?.targetDepth?.takeIf { it > 0f } ?: 40f
                        val depth = base + (sin(t + i) * 1.5).toFloat()   // ±1.5mm 模拟压种轮浮动
                        mVariableFertViewModel.updateEncoderCalibration(i) { cal ->
                            // 有拟合时反算编码值保证显示自洽；无拟合时用 20 脉冲/mm 的合理假设
                            val pos = if (cal.fitValid && cal.fitA != 0f) {
                                (depth - cal.fitB) / cal.fitA
                            } else {
                                depth * 20f
                            }
                            cal.copy(
                                rawPosition      = pos.toInt(),
                                filteredPosition = pos,
                                measuredDepth    = if (cal.fitValid) depth else cal.measuredDepth,
                                isOnline         = true,
                                lastHeardMs      = System.currentTimeMillis()
                            )
                        }
                    }
                }
            })
        }

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

        // 识别是否为 CANopen 服务帧，并提取 Node-ID（纯函数提取至 companion，映射不变）
        // canOpenType: 0=SDO回复, 1=TPDO1, 2=心跳/Boot-up, -1=非CANopen
        val (canOpenNodeId, canOpenType) = classifyCanOpen(canId)

        if (canOpenType >= 0) {
            // 在已配置的深度舵机列表中查找对应 motorIndex
            val motorIndex = viewModel.currentSowingDepthState()
                .motors.indexOfFirst { it.nodeId == canOpenNodeId }

            if (motorIndex >= 0) {
                val activeMotorState = viewModel.activeMotorsState.value ?: mSPParamData.activeMotors
                if (!isSowingDepthMotorActive(motorIndex, mSPParamData.rowNumber, activeMotorState)) {
                    viewModel.updateServoCalibration(motorIndex) { cal ->
                        if (cal.isOnline || cal.isEnabled || cal.alarmCode != 0) {
                            cal.copy(isOnline = false, isEnabled = false, alarmCode = 0)
                        } else {
                            cal
                        }
                    }
                    return
                }
                when (canOpenType) {
                    0 -> onCanOpenSdoResponse(canOpenNodeId, motorIndex, data, viewModel)
                    1 -> onCanOpenTpdo1(canOpenNodeId, motorIndex, data, viewModel)
                    2 -> onCanOpenHeartbeat(canOpenNodeId, motorIndex, data.firstOrNull() ?: 0.toByte(), viewModel)
                }
                return  // 已处理，不再走施肥逻辑
            }

            // ── 摆臂编码器分支（伺服未匹配时才到达；伺服 11~18 逻辑保持不变）──
            // 后台读状态用 currentEncoderFeedbackState()（原子真源），禁止读 LiveData.value
            val encIndex = viewModel.currentEncoderFeedbackState()
                .encoders.indexOfFirst { it.nodeId == canOpenNodeId }
            if (encIndex >= 0) {
                when (canOpenType) {
                    0 -> onEncoderSdoResponse(canOpenNodeId, encIndex, data, viewModel)
                    1 -> onEncoderTpdo1(canOpenNodeId, encIndex, data, viewModel)
                    // 心跳/Boot-up（0x700+ID）：吸收防止跌落施肥解析即可，
                    // 不做业务处理——编码器在线判定只看 TPDO/SDO 应答到达
                    2 -> Log.d(TAG, "编码器 Boot-up/心跳: nodeId=$canOpenNodeId enc=$encIndex")
                    // TPDO2/3 同样吸收
                }
                return  // 已处理/吸收，不再走施肥逻辑
            }

            // ── SDO 应答等待器（未配置节点，如编码器出厂 ID=1 的 0x581）────
            // 配置工具按旧 ID 注册 waiter；被消费的应答不得跌落施肥解析
            if (canOpenType == 0 && SdoReplyWaiters.tryComplete(canId, data)) {
                Log.d(TAG, "SDO应答被等待器消费: canId=0x${canId.toString(16)}")
                return
            }
            // Node-ID 不在已配置舵机/编码器列表中 → 跌落到施肥处理（容错）
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
        // 配置好的伺服节点会优先进入本处理器；必须同时唤醒调试页注册的 waiter，
        // 否则 SdoReplyWaiters 只能收到“未配置节点”的回复，伺服调试读取会全部超时。
        val claimedByWaiter = SdoReplyWaiters.tryComplete(0x580 + nodeId, sdoData)

        val cs    = sdoData[0].toInt() and 0xFF
        val index = (sdoData[1].toInt() and 0xFF) or ((sdoData[2].toInt() and 0xFF) shl 8)

        // 任意格式正确的 SDO 应答（包括 abort）都证明节点在线。
        servoLastSeen[motorIndex] = System.currentTimeMillis()
        viewModel.updateServoCalibration(motorIndex) { cal ->
            cal.copy(isOnline = true, lastHeardMs = servoLastSeen[motorIndex])
        }

        when {
            // ── 实际位置读回复（0x6064，有符号32位）─────────────────────
            cs == 0x43 && index == 0x6064 -> {
                val pos = CanOpenFun.parseSdoResponseSigned32(sdoData) ?: return
                // 请求-应答存活模型：任何 SDO 回包都说明节点在总线上应答 → 刷新存活时间戳并置在线，
                // 使在线判定独立于 TPDO/心跳配置，避免静止电机被 2s 看门狗误判离线后锁死。
                viewModel.updateServoCalibration(motorIndex) { cal ->
                    val depth = if (cal.fitValid) cal.fitA * pos + cal.fitB else cal.currentDepth
                    cal.copy(
                        currentPosition = pos,
                        currentDepth    = depth,
                        isOnline        = true,
                        lastHeardMs     = servoLastSeen[motorIndex]
                    )
                }
                viewModel.appendCanOpenLog("SDO位置 M${motorIndex+1}(N$nodeId) pos=$pos")
        Log.d(TAG, "SDO位置回复: motor=$motorIndex nodeId=$nodeId pos=$pos")
            }

            // ── 状态字读回复（0x6041，16位）─────────────────────────────
            cs == 0x4B && index == 0x6041 -> {
                val sw    = (CanOpenFun.parseSdoResponse(sdoData) ?: return).toInt()
                val flags = CanOpenFun.parseStatusWord(sw)
                val alarm = when {
                    flags.fault -> SERVO_ALARM_DRIVE_FAULT
                    flags.internalLimitActive -> SERVO_ALARM_INTERNAL_LIMIT
                    else -> SERVO_ALARM_NONE
                }
                viewModel.updateServoCalibration(motorIndex) { cal ->
                    cal.copy(
                        alarmCode = alarm,
                        isEnabled = flags.operationEnabled,
                        isOnline = true,
                        lastHeardMs = servoLastSeen[motorIndex]
                    )
                }
                viewModel.appendCanOpenLog("SDO状态 M${motorIndex+1}(N$nodeId) sw=0x${sw.toString(16)} alarm=$alarm targetReached=${flags.targetReached}")
                Log.d(TAG, "SDO状态字回复: motor=$motorIndex sw=0x${sw.toString(16)}" +
                           " targetReached=${flags.targetReached} alarm=$alarm")
            }

            // ── 写入成功应答（节点已应答 → 视为存活，刷新时间戳）───────────
            cs == 0x60 -> {
                val subIdx = sdoData[3].toInt() and 0xFF
                viewModel.appendCanOpenLog("SDO写ACK M${motorIndex+1}(N$nodeId) idx=0x${index.toString(16).uppercase()} sub=$subIdx")
                Log.d(TAG, "SDO写成功: motor=$motorIndex nodeId=$nodeId" +
                           " index=0x${index.toString(16).uppercase()} sub=$subIdx")
            }

            // ── SDO 错误应答（CanOpenFun 内部已打印详细错误码）──────────
            cs == 0x80 -> {
                CanOpenFun.parseSdoResponse(sdoData)   // 触发内部错误日志
                // 调试工具已认领的 abort 会在工具内精确展示对象/中止码，不污染运行报警。
                // 控制循环发出的无主 SDO abort 仍标记为通信报警，避免静默失败。
                if (!claimedByWaiter) {
                    viewModel.updateServoCalibration(motorIndex) { cal ->
                        if (cal.alarmCode != SERVO_ALARM_SDO_ABORT) cal.copy(alarmCode = SERVO_ALARM_SDO_ABORT) else cal
                    }
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
            flags.fault -> SERVO_ALARM_DRIVE_FAULT
            flags.internalLimitActive -> SERVO_ALARM_INTERNAL_LIMIT
            else -> SERVO_ALARM_NONE
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
                isEnabled       = flags.operationEnabled,
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
    // 摆臂编码器帧处理器（Node-ID 21~28，DS406）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 处理编码器 TPDO1 帧（CAN-ID = 0x180 + Node-ID，默认 50ms 主动上报）。
     *
     * 数据格式（4 字节）：[当前位置 U32 小端]——与伺服 6 字节 TPDO1 不同，
     * 走独立解析 [EncoderCanOpenFun.parseEncoderTpdo]。
     *
     * 处理链：符号展开 → 限幅野值剔除 → 滑动均值 → 拟合换算实测深度。
     * 不写 appendCanOpenLog：8 路 × 50ms ≈ 160 帧/s 会瞬间冲掉 60 条伺服诊断日志。
     *
     * @param nodeId   发送 TPDO1 的编码器 CAN Node-ID
     * @param encIndex 对应 ViewModel 中的编码器下标（0~7）
     * @param data     4 字节 TPDO1 数据段
     */
    private fun onEncoderTpdo1(
        nodeId: Int,
        encIndex: Int,
        data: ByteArray,
        viewModel: VariableFertViewModel
    ) {
        val tpdo = EncoderCanOpenFun.parseEncoderTpdo(0x180 + nodeId, data) ?: run {
            Log.w(TAG, "编码器TPDO解析失败: nodeId=$nodeId dataLen=${data.size}")
            return
        }

        encoderLastSeen[encIndex] = System.currentTimeMillis()

        // 读原子快照取分辨率/拟合系数；滤波器仅接收 job 串行访问
        val snapshot = viewModel.currentEncoderFeedbackState().encoders[encIndex]
        val signed = EncoderCanOpenFun.toSignedPosition(tpdo.rawPosition, snapshot.singleTurnResolution)
        val filter = encoderFilters[encIndex]
        filter.spikeThreshold = EncoderCanOpenFun.spikeThresholdFor(snapshot.singleTurnResolution)
        // 离线→在线（重连/重新上电）：清空滤波窗口，避免断线前旧位置污染均值
        if (!snapshot.isOnline) filter.reset()
        val filtered = filter.feed(signed)

        viewModel.updateEncoderCalibration(encIndex) { cal ->
            val depth = if (cal.fitValid) {
                EncoderCanOpenFun.depthFromEncoder(filtered, cal.fitA, cal.fitB)
            } else {
                cal.measuredDepth
            }
            cal.copy(
                rawPosition      = signed,
                filteredPosition = filtered,
                measuredDepth    = depth,
                isOnline         = true,
                lastHeardMs      = encoderLastSeen[encIndex]
            )
        }
    }

    /**
     * 处理编码器 SDO 应答帧（CAN-ID = 0x580 + Node-ID）。
     *
     * 双重职责：
     *   1. 叫醒可能等待该应答的标定置零/配置流程（[SdoReplyWaiters]，不独占）；
     *   2. 刷新在线状态（任何 SDO 回包都说明节点在总线上应答，同伺服的
     *      请求-应答存活模型）。
     * 测量值不从 SDO 更新——位置数据只认 TPDO（滤波链单一入口）。
     */
    private fun onEncoderSdoResponse(
        nodeId: Int,
        encIndex: Int,
        sdoData: ByteArray,
        viewModel: VariableFertViewModel
    ) {
        if (sdoData.size < 8) {
            Log.w(TAG, "编码器SDO应答不足8字节: nodeId=$nodeId size=${sdoData.size}")
            return
        }
        // 先叫醒等待方（置零流程等 0x60 应答；不独占，继续常规状态刷新）
        SdoReplyWaiters.tryComplete(0x580 + nodeId, sdoData)

        encoderLastSeen[encIndex] = System.currentTimeMillis()
        viewModel.updateEncoderCalibration(encIndex) { cal ->
            cal.copy(isOnline = true, lastHeardMs = encoderLastSeen[encIndex])
        }

        val cs    = sdoData[0].toInt() and 0xFF
        val index = (sdoData[1].toInt() and 0xFF) or ((sdoData[2].toInt() and 0xFF) shl 8)
        when (cs) {
            0x80 -> {
                CanOpenFun.parseSdoResponse(sdoData)   // 触发内部错误码日志
                viewModel.appendCanOpenLog("编码器SDO错误 E${encIndex + 1}(N$nodeId) idx=0x${index.toString(16)}")
            }
            0x60 -> viewModel.appendCanOpenLog(
                "编码器SDO写ACK E${encIndex + 1}(N$nodeId) idx=0x${index.toString(16).uppercase()}"
            )
            else -> Log.d(
                TAG, "编码器SDO读应答: nodeId=$nodeId idx=0x${index.toString(16)}" +
                     " val=${CanOpenFun.parseSdoResponse(sdoData)}"
            )
        }
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
