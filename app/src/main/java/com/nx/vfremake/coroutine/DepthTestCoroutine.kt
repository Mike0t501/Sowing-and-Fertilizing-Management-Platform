/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2026年7月4日
 * @file    :
 * @brief   :一键播种深度性能测试协程
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 单电机梯度往返测试：起始深度→按梯度逐级至终点→逐级返回，每级到位停留后进入下一级
 * 全程 100ms 采样记录 CSV（DepthRecordFun），测试状态经 depthTestStatus 通知 UI
 ***********************************************************************************************************
 */

package com.nx.vfremake.coroutine

import android.content.Context
import android.util.Log
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.data.DepthTestConfig
import com.nx.vfremake.data.DepthTestStatus
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.isSystemRunning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 一键深度性能测试协程类
 *
 * 驱动方式：本协程只写被测电机的 targetDepth，实际 CAN 帧下发完全由
 * SowingDepthCoroutine Phase 4 完成（复用其门控、限位限幅与 SDO 时序，
 * 避免两处发帧竞争总线）。Phase 4 最多 500ms 的下发延迟对测试无影响，
 * 到位判定完全基于反馈深度（TPDO/SDO 更新的 currentDepth）。
 *
 * 中止策略：回写 targetDepth=当前深度让 Phase 4 下发"移动到当前位置"实现
 * 平滑停车。刻意不用 quickStop——它会使驱动器脱离 Operation Enabled，
 * 但 motorInitialized 标志仍为 true，之后电机对新 targetDepth 无响应。
 */
class DepthTestCoroutine {
    companion object {
        private const val TAG = "DepthTest"

        // 状态轮询周期（到位判定/使能等待）
        private const val POLL_MS = 100L

        // 等待 masterEnabled → isEnabled（Phase 2 DS402 初始化）的超时
        private const val ENABLE_TIMEOUT_MS = 5000L
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private val jobs = HashSet<Job>()

    // 测试进行中标志位
    var isRunning = false

    // 用户中止请求：主循环各挂起点检查后体面收尾
    @Volatile
    private var abortRequested = false

    // 当前阶段标签与目标深度：供 DepthRecordFun 的采样回调跨协程只读
    @Volatile
    private var currentStageLabel = ""

    @Volatile
    private var currentTarget = 0f

    // 记录器由本类持有，finally 里统一关闭落盘
    private val recorder = DepthRecordFun()

    /**
     * 请求中止测试（体面收尾：平滑停车 + 保存已记录数据）
     */
    fun abort() {
        abortRequested = true
    }

    /**
     * 页面销毁兜底：直接取消协程作用域（记录器单独关闭落盘）
     */
    fun shutdown() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (recorder.isRunning) recorder.shutdown()
        isRunning = false
    }

    /**
     * 启动一键性能测试
     * @param  context:上下文
     * @param  viewModel:共享ViewModel（读电机状态、写targetDepth、post测试状态）
     * @param  config:测试配置快照
     * @note
     */
    fun start(context: Context, viewModel: VariableFertViewModel, config: DepthTestConfig) {
        if (isRunning) return
        isRunning = true
        abortRequested = false

        jobs.add(scope.launch {
            var aborted = false
            val anomalies = mutableListOf<String>()
            try {
                // ── 预检：失败直接报错返回，不启动记录 ──────────────────────
                val precheckError = precheck(viewModel, config)
                if (precheckError != null) {
                    postStatus(viewModel, config, 0, 0, "预检失败", precheckError, anomalies)
                    return@launch
                }

                // ── 使能：确保总开关开启且被测电机完成 DS402 初始化 ─────────
                if (!viewModel.currentSowingDepthState().masterEnabled) {
                    viewModel.updateMasterEnabled(true)
                }
                if (!waitForEnabled(viewModel, config.motorIndex)) {
                    postStatus(
                        viewModel, config, 0, 0, "使能失败",
                        "电机 ${config.motorIndex + 1} 使能超时（${ENABLE_TIMEOUT_MS / 1000}s），" +
                            "请检查电机在线状态后重试", anomalies
                    )
                    return@launch
                }

                // ── 写运动参数（速度/加速度），Phase 4 下发时使用并持久化 ──────
                val stateNow = viewModel.currentSowingDepthState()
                viewModel.updateSowingDepthGlobalSettings(
                    positionSpeed = config.positionSpeed,
                    acceleration = config.acceleration
                )
                MySharedPreFun(context).saveSowingDepthGlobalSettings(
                    jogSpeed = stateNow.jogSpeed,
                    positionSpeed = config.positionSpeed,
                    acceleration = config.acceleration,
                    globalTargetDepth = stateNow.globalTargetDepth
                )
                // 状态改为原子真源后更新同步生效，等待循环保留为兜底（首轮即满足退出）
                var settleWaited = 0L
                while (settleWaited < 1000L) {
                    val st = viewModel.currentSowingDepthState()
                    if (st.positionSpeed == config.positionSpeed &&
                        st.acceleration == config.acceleration
                    ) break
                    delay(POLL_MS)
                    settleWaited += POLL_MS
                }

                // ── 生成梯度序列：上行 + 下行（去峰值），默认 20→40→60→80→60→40→20 ──
                val stages = buildStageSequence(config)
                Log.d(TAG, "梯度序列: ${stages.joinToString()}")

                // ── 启动记录器：100ms 采样被测电机一行 ───────────────────────
                recorder.start(
                    context = context,
                    mVariableFertViewModel = viewModel,
                    filePrefix = "depthTest",
                    header = DepthRecordFun.buildDepthRecordHeader(),
                    intervalMs = 100L
                ) { _ ->
                    val m = viewModel.currentSowingDepthState().motors
                        .getOrNull(config.motorIndex) ?: return@start emptyList()
                    listOf(
                        listOf(
                            (m.motorIndex + 1).toString(),
                            currentStageLabel,
                            "%.2f".format(currentTarget),
                            "%.2f".format(m.currentDepth),
                            m.currentPosition.toString(),
                            if (m.isOnline) "1" else "0",
                            m.alarmCode.toString()
                        )
                    )
                }

                // ── 逐级执行 ────────────────────────────────────────────────
                for ((k, target) in stages.withIndex()) {
                    val stageIndex = k + 1
                    currentTarget = target
                    currentStageLabel = if (k == 0) {
                        "起始定位 %.1f".format(target)
                    } else {
                        "运行 %.1f→%.1f".format(stages[k - 1], target)
                    }
                    postStatus(
                        viewModel, config, stageIndex, stages.size,
                        currentStageLabel, "", anomalies, running = true
                    )

                    // 写目标深度，Phase 4 下一轮（≤500ms）下发绝对位移帧
                    viewModel.updateServoCalibration(config.motorIndex) {
                        it.copy(targetDepth = target)
                    }

                    // 到位等待：深度容差 + 连续稳定采样 + 超时保护
                    when (waitForArrival(viewModel, config, target)) {
                        ArrivalResult.ARRIVED -> {
                            // 停留：分段 delay 以便响应中止；记录持续（稳态误差素材）
                            currentStageLabel = "停留 %.1f".format(target)
                            postStatus(
                                viewModel, config, stageIndex, stages.size,
                                currentStageLabel, "", anomalies, running = true
                            )
                            var dwelt = 0L
                            while (dwelt < config.dwellMs && !abortRequested) {
                                delay(POLL_MS)
                                dwelt += POLL_MS
                            }
                        }

                        ArrivalResult.TIMEOUT -> {
                            anomalies.add("第${stageIndex}级(%.1fmm)超时".format(target))
                            currentStageLabel = "超时 %.1f".format(target)
                            Log.w(TAG, "第${stageIndex}级目标 ${target}mm 超时，继续下一级")
                        }

                        ArrivalResult.ABORTED -> { /* 跳出后统一走中止路径 */ }

                        ArrivalResult.OFFLINE -> {
                            anomalies.add("第${stageIndex}级(%.1fmm)电机离线".format(target))
                            postStatus(
                                viewModel, config, stageIndex, stages.size, "电机离线",
                                "电机 ${config.motorIndex + 1} 离线，测试中止", anomalies
                            )
                            return@launch
                        }

                        ArrivalResult.ALARM -> {
                            val code = motorOf(viewModel, config.motorIndex)?.alarmCode ?: 0
                            anomalies.add("第${stageIndex}级(%.1fmm)报警码$code".format(target))
                            postStatus(
                                viewModel, config, stageIndex, stages.size, "电机报警",
                                "电机 ${config.motorIndex + 1} 报警（码 $code），测试中止", anomalies
                            )
                            return@launch
                        }
                    }

                    if (abortRequested) {
                        aborted = true
                        break
                    }
                }

                if (aborted) {
                    // 中止：回写当前深度实现平滑停车（见类注释）
                    val cur = motorOf(viewModel, config.motorIndex)?.currentDepth
                    if (cur != null) {
                        viewModel.updateServoCalibration(config.motorIndex) {
                            it.copy(targetDepth = cur)
                        }
                    }
                    currentStageLabel = "中止"
                    postStatus(
                        viewModel, config, 0, stages.size, "中止",
                        "测试已中止，已记录数据将保存", anomalies
                    )
                } else {
                    currentStageLabel = "完成"
                    postStatus(
                        viewModel, config, stages.size, stages.size, "完成",
                        "测试完成：共 ${stages.size} 级，异常 ${anomalies.size} 项",
                        anomalies, finished = true
                    )
                }
            } finally {
                // 无论正常/中止/取消，记录器统一在此关闭落盘（触发保存通知）
                if (recorder.isRunning) recorder.shutdown()
                isRunning = false
            }
        })
    }

    // ── 到位判定结果 ─────────────────────────────────────────────────────────
    private enum class ArrivalResult { ARRIVED, TIMEOUT, ABORTED, OFFLINE, ALARM }

    /**
     * 等待被测电机到位：|当前深度-目标| ≤ 容差且连续 stableSamples 次采样稳定
     */
    private suspend fun waitForArrival(
        viewModel: VariableFertViewModel,
        config: DepthTestConfig,
        target: Float
    ): ArrivalResult {
        var stable = 0
        var waited = 0L
        while (waited < config.timeoutMs) {
            if (abortRequested) return ArrivalResult.ABORTED
            val m = motorOf(viewModel, config.motorIndex) ?: return ArrivalResult.OFFLINE
            if (!m.isOnline) return ArrivalResult.OFFLINE
            if (m.alarmCode != 0) return ArrivalResult.ALARM

            if (abs(m.currentDepth - target) <= config.toleranceMm) {
                stable++
                if (stable >= config.stableSamples) return ArrivalResult.ARRIVED
            } else {
                stable = 0
            }
            delay(POLL_MS)
            waited += POLL_MS
        }
        return ArrivalResult.TIMEOUT
    }

    /**
     * 等待电机使能完成（masterEnabled 开启后 Phase 2 需 ≥1 个 500ms 周期做 DS402 初始化）
     */
    private suspend fun waitForEnabled(
        viewModel: VariableFertViewModel,
        motorIndex: Int
    ): Boolean {
        var waited = 0L
        while (waited < ENABLE_TIMEOUT_MS) {
            if (abortRequested) return false
            if (motorOf(viewModel, motorIndex)?.isEnabled == true) return true
            delay(POLL_MS)
            waited += POLL_MS
        }
        return false
    }

    /**
     * 启动前预检，返回错误文案；全部通过返回 null
     */
    private fun precheck(viewModel: VariableFertViewModel, config: DepthTestConfig): String? {
        if (isSystemRunning) return "作业进行中，CAN 串口被占用，无法测试"

        val m = motorOf(viewModel, config.motorIndex)
            ?: return "电机序号无效"
        if (!m.isOnline) return "电机 ${config.motorIndex + 1} 离线"
        if (!m.limitsSet) return "电机 ${config.motorIndex + 1} 限位未标定"
        if (!m.fitValid) return "电机 ${config.motorIndex + 1} 深度拟合无效，请先完成标定"

        if (config.stepMm < 1f) return "梯度须 ≥ 1mm"
        if (config.startDepthMm >= config.endDepthMm) return "起始深度须小于终点深度"
        if (config.toleranceMm <= 0f) return "到位容差须 > 0"

        // 起/终点换算编码器脉冲须落在标定行程内：Phase 4 虽会限幅，
        // 但限幅后深度到不了目标必然超时，不如启动前拒绝
        if (m.fitA == 0f) return "拟合系数无效（fitA=0）"
        val safeMin = minOf(m.limitMin, m.limitMax)
        val safeMax = maxOf(m.limitMin, m.limitMax)
        val startPulse = ((config.startDepthMm - m.fitB) / m.fitA).toInt()
        val endPulse = ((config.endDepthMm - m.fitB) / m.fitA).toInt()
        if (startPulse !in safeMin..safeMax || endPulse !in safeMin..safeMax) {
            return "目标深度超出标定行程（%.1f~%.1fmm 对应脉冲 %d/%d 不在限位 %d~%d 内）"
                .format(config.startDepthMm, config.endDepthMm, startPulse, endPulse, safeMin, safeMax)
        }
        return null
    }

    /**
     * 生成梯度序列：上行 start→end（末级 clamp 到 end 并去重）+ 下行倒序去峰值
     * 例：start=20 end=80 step=20 → [20, 40, 60, 80, 60, 40, 20]
     */
    private fun buildStageSequence(config: DepthTestConfig): List<Float> {
        val up = mutableListOf<Float>()
        var d = config.startDepthMm
        while (d < config.endDepthMm - 0.01f) {
            up.add(d)
            d += config.stepMm
        }
        up.add(config.endDepthMm)
        // 下行 = 上行倒序去掉峰值（首元素）
        val down = up.reversed().drop(1)
        return up + down
    }

    private fun motorOf(viewModel: VariableFertViewModel, motorIndex: Int): ServoCalibration? =
        viewModel.currentSowingDepthState().motors.getOrNull(motorIndex)

    /**
     * 发布测试状态到 UI
     */
    private fun postStatus(
        viewModel: VariableFertViewModel,
        config: DepthTestConfig,
        stageIndex: Int,
        stageCount: Int,
        stageLabel: String,
        message: String,
        anomalies: List<String>,
        running: Boolean = false,
        finished: Boolean = false
    ) {
        viewModel.depthTestStatus.postValue(
            DepthTestStatus(
                running = running,
                finished = finished,
                motorIndex = config.motorIndex,
                stageIndex = stageIndex,
                stageCount = stageCount,
                stageLabel = stageLabel,
                targetDepth = currentTarget,
                message = message,
                anomalies = anomalies.toList()
            )
        )
    }
}
