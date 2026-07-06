package com.nx.vfremake.coroutine

import android.content.Context
import android.util.Log
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.data.SowingDepthState
import com.nx.vfremake.data.activeSowingDepthMotorIndices
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.JogSession
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.mSPParamData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 播种深度控制协程
 *
 * 职责：
 *   1. 以 500ms 为软间隔轮询所有伺服电机，读取实际位置（同时维持 CAN 心跳）
 *   2. 检测目标深度变化，计算目标编码器位置并发送绝对位置运动命令
 *   3. 检查报警状态（限位触发时急停）
 *   4. 检测连续无 SDO 回复并辅助离线判定（主离线检测由 CanReceiveCoroutine 完成）
 *
 * 与其他组件的分工：
 *   CanOpenFun      — 帧组装与底层发送
 *   CanReceiveCoroutine — 接收帧并更新 ViewModel（isOnline / currentPosition / alarmCode）
 *   SowingDepthCoroutine — 根据 ViewModel 状态做决策并发送命令
 *
 * SDO 时序约定：
 *   所有 CANopen 帧统一经 CanOpenFun.sendFrameSequenced / sendSequence 发送，
 *   由其内部 Mutex 强制全局 ≥20ms 帧间步调并保证多帧序列原子性——
 *   跨协程（本协程 vs 标定页点动）的 SDO 背靠背相撞曾导致驱动器丢帧
 *
 * 参照 DB9reCANseCoroutine.kt 的协程模式（scope/jobs/shutdown/start）。
 */
class SowingDepthCoroutine {

    var isRunning = false
    private var scope = CoroutineScope(Dispatchers.Default)
    private val jobs = HashSet<Job>()

    companion object {
        private const val TAG = "SowingDepthCoroutine"

        /** 主循环软间隔（ms），实际循环时间 = 此值 + 本次处理耗时 */
        private const val LOOP_INTERVAL_MS = 500L

        /** 等待所有节点 SDO 读取回复的缓冲时间（ms） */
        private const val SDO_REPLY_WAIT_MS = 30L

        /**
         * 连续无 SDO 回复多少次后本协程辅助标记离线。
         * 主离线检测由 CanReceiveCoroutine 的看门狗完成（2s 超时）。
         * 此处阈值：MAX × LOOP_INTERVAL ≈ 5 × 500ms = 2.5s，与主看门狗接近。
         */
        private const val OFFLINE_TIMEOUT_MS = 5000L

        /** 目标深度比较容差（mm）：在此范围内不重发命令 */
        private const val DEPTH_TOLERANCE_MM = 0.1f
    }

    fun shutdown() {
        isRunning = false
        scope.cancel()
        jobs.clear()
    }

    /**
     * 启动深度控制主循环。
     *
     * 调用前提：CAN 串口已打开（mSerialPortCAN != null），
     * 伺服电机已上电（CanReceiveCoroutine 正在运行以接收回复）。
     */
    fun start(viewModel: VariableFertViewModel, context: Context? = null) {
        scope = CoroutineScope(Dispatchers.Default)

        val isTestMode = context != null &&
            MySharedPreFun(context).getSpecificValue(R.string.testMode_Switch_name) == "1"

        if (isTestMode) Log.i(TAG, "测试模式：跳过Phase3离线辅助判定")

        jobs.add(scope.launch {
            // ── 启动时广播 NMT Start，让总线上所有节点进入 Operational 状态 ──
            // Operational 状态下 PDO/SDO 均可用，是正常控制所必需的。
            CanOpenFun.sendFrameSequenced(CanOpenFun.buildNmtFrame(CanOpenFun.Nmt.OPERATIONAL, 0x00))
            Log.i(TAG, "NMT广播：所有节点进入Operational状态")
            delay(100)   // 等待节点响应 NMT

            // ── 每路电机的本地追踪变量 ──────────────────────────────────────
            // Float.NaN 表示从未发送过目标，首次循环会触发初始位置命令
            val lastSentTargetDepth = FloatArray(8) { Float.NaN }
            // 是否已完成本次上线后的初始化（位置模式+使能）
            val motorInitialized    = BooleanArray(8) { false }
            // Phase 2 初始化完成后强制重发的剩余次数；>0 时清空 lastSentTargetDepth 强制重发
            val motorInitCooldown   = IntArray(8) { 0 }
            // 上一周期的总开关状态，用于检测 ON↔OFF 跳变
            var lastMasterEnabled   = false

            while (isActive) {
                // 跨页 DisposableEffect 竞态可能把 CAN 串口关掉（mSerialPortCAN=null）；
                // 每轮幂等重开，配合 CanReceiveCoroutine 的自愈接收循环，端口恢复后立即继续收发。
                // 作业模式下端口由 MySerialPortFun 持有不为 null，此调用为 no-op。
                context?.let { MySerialPortFun.ensureCanPortOpen(it) }

                val state  = viewModel.sowingDepthState.value ?: SowingDepthState()
                val motors = state.motors
                val activeMotorState = viewModel.activeMotorsState.value ?: mSPParamData.activeMotors
                val activeIndices = activeSowingDepthMotorIndices(mSPParamData.rowNumber, activeMotorState)
                val activeIndexSet = activeIndices.toSet()
                for (i in 0 until 8) {
                    if (i !in activeIndexSet) {
                        motorInitialized[i] = false
                        motorInitCooldown[i] = 0
                        lastSentTargetDepth[i] = Float.NaN
                        viewModel.updateServoCalibration(i) { cal ->
                            if (cal.isOnline || cal.isEnabled || cal.alarmCode != 0) {
                                cal.copy(isOnline = false, isEnabled = false, alarmCode = 0)
                            } else {
                                cal
                            }
                        }
                    }
                }

                // ════════════════════════════════════════════════════════════
                // Phase 1：读取所有在线电机的位置（心跳维持 + 位置更新）
                //
                // 对不同节点可以连续发送，无需帧间等待；
                // 回复由 CanReceiveCoroutine 处理并更新 ViewModel。
                // ════════════════════════════════════════════════════════════
                // 探测所有配置节点（不再用 isOnline 门控）：请求-应答存活模型下，任何 SDO 回包
                // 都会被 CanReceiveCoroutine 标记在线，使被误判离线的电机一旦重新应答即自动恢复，
                // 打破「离线后停止轮询 → 再无回包 → 无法自愈」的锁死。
                // sendFrameSequenced：全局 20ms 步调（8 节点 ≈160ms，500ms 周期内充裕），
                // 既避免与点动/其他发送方的 SDO 背靠背相撞，也保护 CSM100T 桥缓冲
                for (i in activeIndices) {
                    CanOpenFun.sendFrameSequenced(CanOpenFun.buildReadPositionFrame(motors[i].nodeId))
                }

                // 等待 SDO 回复到达并由 CanReceiveCoroutine 写入 ViewModel
                delay(SDO_REPLY_WAIT_MS)

                // ════════════════════════════════════════════════════════════
                // 总开关跳变处理 + Phase 2：首次上线初始化（设置位置模式 + 使能驱动器）
                //
                // 总开关 ON→OFF：对所有已初始化电机发 Shutdown(0x6040=0x0006)，
                //                按 0x6084(Profile Decel) 减速后退出 Operation Enabled。
                // Phase 2 init 仅当 masterEnabled=true 时执行；离线重置始终生效。
                // ════════════════════════════════════════════════════════════
                val stateAfterRead = viewModel.sowingDepthState.value ?: state
                val masterEnabled  = stateAfterRead.masterEnabled

                if (lastMasterEnabled && !masterEnabled) {
                    Log.i(TAG, "总开关 ON→OFF：对所有已初始化电机发 Shutdown")
                    for (i in activeIndices) {
                        val cal = stateAfterRead.motors[i]
                        if (cal.isOnline && motorInitialized[i]) {
                            // 0x6040 = 0x0006 → Shutdown，从 Operation Enabled 经
                            // "Disable Operation" 转到 "Ready to switch on"。
                            // 注意：总开关关闭对点动中的电机也不让路——操作员关总开关
                            // 意为全停，失效方向安全。
                            CanOpenFun.sendFrameSequenced(
                                CanOpenFun.buildSdoWriteFrame(cal.nodeId, 0x6040, 0x00, 2, 0x0006L)
                            )
                        }
                        motorInitialized[i]    = false
                        motorInitCooldown[i]   = 0
                        lastSentTargetDepth[i] = Float.NaN
                        viewModel.updateServoCalibration(i) { it.copy(isEnabled = false) }
                    }
                    Log.i(TAG, "总开关已关闭：所有电机 Shutdown，初始化标志重置")
                }
                lastMasterEnabled = masterEnabled

                for (i in activeIndices) {
                    val cal = stateAfterRead.motors[i]

                    if (!cal.isOnline) {
                        // 电机离线：重置初始化标志和目标记录，以便重新上线后重新初始化
                        if (motorInitialized[i]) {
                            motorInitialized[i] = false
                            lastSentTargetDepth[i] = Float.NaN
                            Log.i(TAG, "motor=$i (nodeId=${cal.nodeId}) 离线，已重置初始化状态")
                        }
                        continue
                    }

                    if (masterEnabled && !motorInitialized[i] && !JogSession.isJogging(cal.nodeId)) {
                        // 总开关已开启 + 电机首次上线/重新上线：DS402 完整状态机序列 + 位置模式使能。
                        // 点动中的电机跳过——init 的 0x6060=1 / 0x0006 会掐掉正在进行的速度模式点动。
                        Log.i(TAG, "motor=$i (nodeId=${cal.nodeId}) 上线且总开关已开，执行初始化")
                        CanOpenFun.sendSequence(CanOpenFun.buildMotorInitSequence(cal.nodeId))
                        motorInitialized[i] = true
                        motorInitCooldown[i]  = 6   // 初始化后强制重发 3s（6×500ms）
                        // 种入 targetDepth = globalTargetDepth，
                        // 避免首次 dispatch 跑到 0mm（cal.targetDepth 默认值）。
                        val seedTarget = stateAfterRead.globalTargetDepth
                        viewModel.updateServoCalibration(i) {
                            it.copy(isEnabled = true, targetDepth = seedTarget)
                        }
                        Log.i(TAG, "motor=$i 初始化完成 targetDepth=${seedTarget}mm")
                    }
                }

                // ════════════════════════════════════════════════════════════
                // ════════════════════════════════════════════════════════════
                // Phase 3：基于时间戳的离线检测
                //
                // CanReceiveCoroutine 每次收到 TPDO/心跳时更新 cal.lastHeardMs。
                // 超过 OFFLINE_TIMEOUT_MS 无任何帧到达则标记离线，消除静止电机误判。
                // ════════════════════════════════════════════════════════════
                if (!isTestMode) {
                    val nowMs = System.currentTimeMillis()
                    for (i in activeIndices) {
                        val cal = stateAfterRead.motors[i]
                        if (!cal.isOnline) continue
                        // lastHeardMs == 0 表示 CanReceiveCoroutine 尚未收到该节点任何帧，暂不判定
                        if (cal.lastHeardMs > 0L && nowMs - cal.lastHeardMs > OFFLINE_TIMEOUT_MS) {
                            viewModel.updateServoCalibration(i) { it.copy(isOnline = false) }
                            Log.w(TAG, "motor=$i 超过${OFFLINE_TIMEOUT_MS}ms无帧，标记离线")
                        }
                    }
                }

                // ════════════════════════════════════════════════════════════
                // Phase 4：目标深度变化时发送绝对位置运动命令
                //
                // 仅在总开关打开时执行。安全条件（全部满足才发送）：
                //   (a) 电机在线且已使能
                //   (b) 限位已标定（limitsSet = true）
                //   (c) 拟合系数有效（fitValid = true）
                //   (d) 目标深度与上次发送值偏差 > DEPTH_TOLERANCE_MM
                //
                // 目标深度逐路读取 cal.targetDepth：UI 的"全部应用"会同步所有电机；
                // "单独设置"仅修改对应电机。协程不回写 cal.targetDepth，
                // 以免覆盖用户的单独设置。
                // ════════════════════════════════════════════════════════════
                if (masterEnabled) {
                    val posSpeed = stateAfterRead.positionSpeed

                    for (i in activeIndices) {
                        val cal    = stateAfterRead.motors[i]
                        val target = cal.targetDepth

                        // 点动中让路：位置模式帧会与速度模式点动打架（点动失灵/电机自行走位）。
                        // 必须放在 cooldown 自减之前——点动期间冻结初始化后的强制重发窗口，
                        // 否则重发窗口在点动中被白白耗尽。
                        if (JogSession.isJogging(cal.nodeId)) continue

                        // (a) 在线且已使能
                        if (!cal.isOnline || !cal.isEnabled) continue

                        // (b) 限位必须已标定
                        if (!cal.limitsSet) {
                            if (lastSentTargetDepth[i].isNaN() ||
                                abs(target - lastSentTargetDepth[i]) > DEPTH_TOLERANCE_MM
                            ) {
                                Log.w(TAG, "motor=$i 限位未标定，禁止位置控制（目标${target}mm 被忽略）")
                            }
                            continue
                        }

                        // (c) 拟合系数必须有效
                        if (!cal.fitValid) {
                            if (lastSentTargetDepth[i].isNaN() ||
                                abs(target - lastSentTargetDepth[i]) > DEPTH_TOLERANCE_MM
                            ) {
                                Log.w(TAG, "motor=$i 拟合系数无效，禁止深度控制（目标${target}mm 被忽略）")
                            }
                            continue
                        }

                        // (d) 初始化后冷却期：清空 lastSentTargetDepth 强制重发，确保电机收到首条位置命令
                        if (motorInitCooldown[i] > 0) {
                            motorInitCooldown[i]--
                            lastSentTargetDepth[i] = Float.NaN
                        }

                        // (d) 目标深度是否发生变化
                        if (!lastSentTargetDepth[i].isNaN() &&
                            abs(target - lastSentTargetDepth[i]) <= DEPTH_TOLERANCE_MM
                        ) {
                            continue   // 与上次命令值相差不超过容差，无需重发
                        }

                        // ── 计算目标编码器位置 ────────────────────────────────
                        val rawPulse = depthToEncoderPulse(target, cal)

                        // ── 安全限幅：目标编码器值必须在限位范围内 ────────────
                        // 注意：limitMin/limitMax 大小关系取决于丝杆安装方向
                        val safeMin     = minOf(cal.limitMin, cal.limitMax)
                        val safeMax     = maxOf(cal.limitMin, cal.limitMax)
                        val targetPulse = rawPulse.coerceIn(safeMin, safeMax)

                        if (targetPulse != rawPulse) {
                            Log.w(
                                TAG, "motor=$i 目标编码器 $rawPulse 超出限位范围 [$safeMin, $safeMax]，" +
                                     "已限幅至 $targetPulse"
                            )
                        }

                        // ── 发送绝对位置运动帧序列（一次原子序列，防其他发送方插队）──
                        // 先写加减速度（0x6083/0x6084），使位置运动遵循用户设定的斜率；
                        // buildAbsoluteMoveFrames 返回 [模式, 可选速度, 目标位置, 控制字复位, 控制字置位]
                        CanOpenFun.sendSequence(
                            CanOpenFun.buildSetAccelDecelFrames(cal.nodeId, state.acceleration) +
                            CanOpenFun.buildAbsoluteMoveFrames(
                                nodeId       = cal.nodeId,
                                targetPulse  = targetPulse,
                                profileSpeed = posSpeed
                            )
                        )

                        // ── 记录已发送的目标（不要回写 cal.targetDepth，UI 才是其唯一写入者）─
                        lastSentTargetDepth[i] = target

                        Log.i(
                            TAG, "motor=$i 绝对位置命令已发送: depth=${target}mm" +
                                 " pulse=$targetPulse speed=${posSpeed}RPM"
                        )

                        // 不同电机之间无需等待（CAN 总线仲裁处理），
                        // 但加一点延迟避免日志/ViewModel 更新集中在同一时刻
                        delay(5)
                    }
                }

                // ════════════════════════════════════════════════════════════
                // Phase 5：报警检查
                //
                // alarmCode 由 CanReceiveCoroutine 从 TPDO1 状态字解析写入：
                //   0  = 正常
                //   1  = 正限位触发
                //   2  = 负限位触发
                //  -1  = SDO 错误应答
                //
                // 限位触发时发送急停，防止电机持续压向限位开关。
                // ════════════════════════════════════════════════════════════
                val stateForAlarm = viewModel.sowingDepthState.value ?: stateAfterRead

                for (i in activeIndices) {
                    val cal = stateForAlarm.motors[i]
                    if (!cal.isOnline) continue

                    when (cal.alarmCode) {
                        1 -> {
                            Log.e(TAG, "motor=$i 正限位触发！发送急停")
                            // 安全项：对点动中的电机也照发——限位报警必须无条件停车；
                            // 之后点动可经启动序列的 0x0006 自恢复
                            CanOpenFun.sendFrameSequenced(CanOpenFun.buildQuickStopFrame(cal.nodeId))
                            // 用 per-motor 当前目标记录，避免与全局值耦合
                            lastSentTargetDepth[i] = cal.targetDepth
                        }
                        2 -> {
                            Log.e(TAG, "motor=$i 负限位触发！发送急停")
                            CanOpenFun.sendFrameSequenced(CanOpenFun.buildQuickStopFrame(cal.nodeId))
                            lastSentTargetDepth[i] = cal.targetDepth
                        }
                        -1 -> {
                            Log.w(TAG, "motor=$i SDO错误，不发额外命令（等待恢复）")
                        }
                        0 -> { /* 正常，无操作 */ }
                    }
                }

                // ════════════════════════════════════════════════════════════
                // 等待下一个控制周期
                // 注意：delay 计时从此处开始，实际周期 = LOOP_INTERVAL_MS + 本次处理耗时
                // ════════════════════════════════════════════════════════════
                delay(LOOP_INTERVAL_MS)
            }
        })

        isRunning = true
        Log.i(TAG, "播种深度控制协程已启动")
    }

    // ─────────────────────────────────────────────────────────────────────
    // 深度 ↔ 编码器换算
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 深度（mm）→ 编码器目标脉冲数（有符号 32 位）。
     *
     * 拟合曲线（正向）：depth_mm = fitA × encoderPos + fitB
     * 反算：encoderPos = (depth_mm - fitB) / fitA
     *
     * 调用前必须确保 [ServoCalibration.fitValid] = true 且 [ServoCalibration.fitA] ≠ 0。
     */
    private fun depthToEncoderPulse(depthMm: Float, cal: ServoCalibration): Int {
        if (cal.fitA == 0f) {
            Log.e(TAG, "motor=${cal.motorIndex} fitA=0，无法换算深度，返回 limitMin")
            return cal.limitMin
        }
        return ((depthMm - cal.fitB) / cal.fitA).toInt()
    }

    /**
     * 编码器脉冲数 → 深度（mm）。
     *
     * 用于显示：depth_mm = fitA × encoderPos + fitB
     * 调用前必须确保 [ServoCalibration.fitValid] = true。
     */
    fun encoderPulseToDepth(encoderPos: Int, cal: ServoCalibration): Float {
        return cal.fitA * encoderPos + cal.fitB
    }
}






