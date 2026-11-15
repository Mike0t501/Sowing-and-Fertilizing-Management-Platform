package com.nx.vfremake.coroutine

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.data.DepthTestConfig
import com.nx.vfremake.data.DepthTestPhase
import com.nx.vfremake.data.DepthTestStatus
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.data.activeSowingDepthMotorIndices
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.DepthRecordSchema
import com.nx.vfremake.funClass.DocuAndManageFun
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.mSPParamData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs

/** 单行试验统一持有控制、定位及记录。所有位置帧由 SowingDepthCoroutine 下发。 */
class DepthTestCoroutine {
    private data class Stage(
        val index: Int = 0,
        val phase: DepthTestPhase = DepthTestPhase.PREPARING,
        val label: String = "准备记录",
        val targetMm: Float? = null,
    )

    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    fun abort() { job?.cancel() }
    fun shutdown() = abort()

    fun start(context: Context, viewModel: VariableFertViewModel, config: DepthTestConfig): Boolean {
        if (job?.isActive == true || !viewModel.tryBeginDepthTrial()) return false
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val appContext = context.applicationContext
            val stages = DepthTestConfig.stageDepths(config.startDepthMm, config.endDepthMm, config.stepMm)
            val originalState = viewModel.currentSowingDepthState()
            val originalMotor = originalState.motors.getOrNull(config.motorIndex)
            val preferences = MySharedPreFun(appContext)
            val simulatedGnss = preferences.getSpecificValue(R.string.simGnss_Switch_name) == "1"
            val receiver = CanReceiveCoroutine()
            val control = SowingDepthCoroutine()
            var gnss: DB9reCANseCoroutine? = null
            val recorder = DepthRecordFun()
            var recordingAccepted = false
            var controlStarted = false
            val stage = java.util.concurrent.atomic.AtomicReference(Stage())
            val anomalies = mutableListOf<String>()
            var result = DepthTestPhase.FAILED
            var resultMessage = ""

            fun publish(next: Stage, message: String = "", running: Boolean = true) {
                stage.set(next)
                viewModel.depthTestStatus.postValue(DepthTestStatus(
                    running = running, finished = next.phase == DepthTestPhase.COMPLETE,
                    motorIndex = config.motorIndex, stageIndex = next.index, stageCount = stages.size,
                    stageLabel = next.label, targetDepth = next.targetMm ?: 0f,
                    message = message, anomalies = anomalies.toList(), phase = next.phase,
                ))
            }

            try {
                publish(stage.get())
                check(!originalState.masterEnabled) { "请先在播深控制页关闭总开关" }
                check(preferences.getSpecificValue(R.string.testMode_Switch_name) != "1") {
                    "硬件测试模式下不可驱动试验伺服，请关闭测试模式"
                }
                val active = activeSowingDepthMotorIndices(mSPParamData.rowNumber,
                    viewModel.activeMotorsState.value ?: mSPParamData.activeMotors)
                check(config.motorIndex in active && originalMotor != null) { "请先启用被测单体" }
                check(stages.isNotEmpty()) { "起止深度或步长无效，或级数过多" }
                check(config.toleranceMm.isFinite() && config.toleranceMm > 0f &&
                    config.dwellMs in 0..600_000 && config.stableSamples > 0 && config.timeoutMs > 0 &&
                    config.positionSpeed in 100..3000 && config.acceleration in 1000..60000 &&
                    config.recordIntervalMs in 20..1000
                ) { "试验运动或采样参数无效" }
                validateLimits(originalMotor, config)
                withContext(Dispatchers.IO) {
                    check(DocuAndManageFun().getWriteDirDocumentUri(appContext) != null) { "请先授权实验数据保存目录" }
                    check(MySerialPortFun.ensureCanPortOpen(appContext)) { "无法打开 CAN 串口" }
                }
                receiver.start(viewModel, appContext)
                waitForPosition(viewModel, config.motorIndex, originalMotor.nodeId)
                gnss = DB9reCANseCoroutine().also { it.startTrial(appContext, viewModel, config.motorIndex, simulatedGnss) }

                recordingAccepted = recorder.start(
                    context = appContext, mVariableFertViewModel = viewModel, filePrefix = "depthTest",
                    header = DepthRecordSchema.TRIAL_HEADER, intervalMs = config.recordIntervalMs,
                    recordRows = listOf(config.motorIndex), trial = config, simulatedGnss = simulatedGnss
                ) {
                    val now = SystemClock.elapsedRealtime()
                    val phase = stage.get()
                    val motor = viewModel.currentSowingDepthState().motors[config.motorIndex]
                    val fresh = hasFreshPosition(motor, now)
                    val row = listOf(
                        (motor.motorIndex + 1).toString(), phase.label,
                        phase.targetMm?.let { "%.2f".format(Locale.ROOT, it) }.orEmpty(),
                        if (fresh) "%.2f".format(Locale.ROOT, motor.currentDepth) else "",
                        if (fresh) motor.currentPosition.toString() else "",
                        if (motor.isOnline) "1" else "0", motor.alarmCode.toString()
                    ) + DepthRecordSchema.trialColumns(
                        phase.index, phase.phase, viewModel.currentDepthTrialLocation(), now, simulatedGnss
                    )
                    listOf(row)
                }
                check(recordingAccepted && withTimeoutOrNull(30_000L) { recorder.awaitReady() } == true) {
                    "记录文件准备失败，未下发试验深度"
                }
                // 等待文件和配置回读可能超过位置反馈时限，再取一帧后才使能。
                waitForPosition(viewModel, config.motorIndex, originalMotor.nodeId)
                viewModel.updateSowingDepthGlobalSettings(
                    positionSpeed = config.positionSpeed, acceleration = config.acceleration
                )
                for ((index, target) in stages.withIndex()) {
                    verifyFeedback(viewModel, config.motorIndex, recorder)
                    publish(Stage(index + 1, DepthTestPhase.MOVING, "运行至 %.1f mm".format(Locale.ROOT, target), target))
                    viewModel.updateServoCalibration(config.motorIndex) { it.copy(targetDepth = target) }
                    if (!controlStarted) {
                        viewModel.updateMasterEnabled(true)
                        control.start(viewModel, appContext, trialMotorIndex = config.motorIndex)
                        controlStarted = true
                        check(withTimeoutOrNull(5_000L) {
                            while (!control.trialMotorInitialized) {
                                verifyFeedback(viewModel, config.motorIndex, recorder)
                                delay(POLL_MS)
                            }
                            true
                        } == true) { "被测伺服使能超时" }
                    }

                    if (waitForArrival(viewModel, config, recorder, target)) {
                        publish(stage.get().copy(phase = DepthTestPhase.DWELL, label = "到位停留"))
                        val until = SystemClock.elapsedRealtime() + config.dwellMs
                        while (SystemClock.elapsedRealtime() < until) {
                            verifyFeedback(viewModel, config.motorIndex, recorder)
                            delay(minOf(POLL_MS, (until - SystemClock.elapsedRealtime()).coerceAtLeast(1)))
                        }
                    } else {
                        anomalies.add("第 ${index + 1} 级（${target} mm）到位超时")
                        publish(stage.get().copy(phase = DepthTestPhase.TIMEOUT, label = "到位超时"))
                        // 保留至少一个采样间隔，让超时阶段进入记录。
                        delay(config.recordIntervalMs + POLL_MS)
                    }
                }
                result = DepthTestPhase.COMPLETE
                resultMessage = "完成 ${stages.size} 级，异常 ${anomalies.size} 项"
            } catch (_: CancellationException) {
                result = DepthTestPhase.ABORTED
                resultMessage = "试验已中止，保留已采集数据"
            } catch (e: Exception) {
                Log.w("DepthTest", "变量播深试验中止", e)
                resultMessage = e.message ?: "试验失败"
            } finally {
                withContext(NonCancellable) {
                    publish(stage.get().copy(phase = DepthTestPhase.STOPPING, label = "停止并保存"), resultMessage)
                    try {
                        if (controlStarted) {
                            control.stopForWork(viewModel)
                            delay(POLL_MS)
                        }
                    } catch (e: Exception) {
                        result = DepthTestPhase.FAILED
                        resultMessage = "伺服停止指令发送异常：${e.message}"
                        Log.e("DepthTest", resultMessage, e)
                    } finally {
                        if (controlStarted) {
                            viewModel.updateServoCalibration(config.motorIndex) {
                                it.copy(targetDepth = originalMotor?.targetDepth ?: 0f)
                            }
                            viewModel.updateSowingDepthGlobalSettings(
                                positionSpeed = originalState.positionSpeed, acceleration = originalState.acceleration
                            )
                        }
                        gnss?.stopTrialAndJoin()
                        if (recordingAccepted) recorder.shutdownAndJoin()
                        receiver.shutdown()
                        withContext(Dispatchers.Main) {
                            if ((context as? Activity)?.isDestroyed == true) MySerialPortFun().releaseSerialPort(viewModel)
                            viewModel.endDepthTrial()
                        }
                        val label = when (result) {
                            DepthTestPhase.COMPLETE -> "完成"
                            DepthTestPhase.ABORTED -> "中止"
                            else -> "失败"
                        }
                        publish(stage.get().copy(phase = result, label = label), resultMessage, running = false)
                    }
                    Unit
                }
            }
        }
        return true
    }

    private fun validateLimits(motor: ServoCalibration, config: DepthTestConfig) {
        check(motor.limitsSet && motor.fitValid && motor.fitA.isFinite() && motor.fitA != 0f && motor.fitB.isFinite()) {
            "请先完成被测伺服的限位和深度标定"
        }
        val bounds = minOf(motor.limitMin, motor.limitMax).toDouble()..maxOf(motor.limitMin, motor.limitMax).toDouble()
        check(listOf(config.startDepthMm, config.endDepthMm).all { (it.toDouble() - motor.fitB) / motor.fitA in bounds }) {
            "起止深度超出被测伺服的标定行程"
        }
    }

    private suspend fun waitForPosition(viewModel: VariableFertViewModel, row: Int, node: Int) {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < FEEDBACK_TIMEOUT_MS) {
            CanOpenFun.sendFrameSequenced(CanOpenFun.buildReadStatusWordFrame(node))
            CanOpenFun.sendFrameSequenced(CanOpenFun.buildReadPositionFrame(node))
            delay(150)
            val motor = viewModel.currentSowingDepthState().motors[row]
            check(motor.alarmCode == 0) { "被测伺服报警（码 ${motor.alarmCode}）" }
            if (motor.isOnline && motor.positionAtMs >= started && motor.currentDepth.isFinite()) return
        }
        error("未收到被测伺服的位置反馈")
    }

    private fun verifyFeedback(viewModel: VariableFertViewModel, row: Int, recorder: DepthRecordFun): ServoCalibration {
        check(recorder.isRunning) { "试验数据记录已停止" }
        val motor = viewModel.currentSowingDepthState().motors[row]
        check(motor.alarmCode == 0) { "被测伺服报警（码 ${motor.alarmCode}）" }
        check(hasFreshPosition(motor, SystemClock.elapsedRealtime())) { "被测伺服位置反馈中断" }
        return motor
    }

    private suspend fun waitForArrival(
        viewModel: VariableFertViewModel, config: DepthTestConfig, recorder: DepthRecordFun, target: Float
    ): Boolean {
        val started = SystemClock.elapsedRealtime()
        var previousPositionAt = started
        var stable = 0
        while (SystemClock.elapsedRealtime() - started < config.timeoutMs) {
            val motor = verifyFeedback(viewModel, config.motorIndex, recorder)
            if (motor.positionAtMs > previousPositionAt) {
                previousPositionAt = motor.positionAtMs
                stable = if (abs(motor.currentDepth - target) <= config.toleranceMm) stable + 1 else 0
                if (stable >= config.stableSamples) return true
            }
            delay(POLL_MS)
        }
        return false
    }

    private fun hasFreshPosition(motor: ServoCalibration, nowMs: Long): Boolean =
        motor.isOnline && motor.currentDepth.isFinite() && motor.positionAtMs != 0L &&
            nowMs - motor.positionAtMs in 0..FEEDBACK_TIMEOUT_MS

    private companion object {
        const val POLL_MS = 100L
        const val FEEDBACK_TIMEOUT_MS = 2000L
    }
}
