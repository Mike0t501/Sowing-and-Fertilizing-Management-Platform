package com.nx.vfremake.ui

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import android.serialport.SerialPort
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.data.CalibrationMode
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.data.SowingDepthState
import com.nx.vfremake.data.deepDirection
import com.nx.vfremake.coroutine.CanReceiveCoroutine
import com.nx.vfremake.coroutine.SowingDepthCoroutine
import com.nx.vfremake.funClass.CanOpenFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.isSystemRunning
import com.nx.vfremake.mSerialPortCAN
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

// SDO 帧间最小等待时间（与 SowingDepthCoroutine 保持一致）
private const val SDO_DELAY = 20L

/** 跨重组持有 Job 的可变引用，不触发 Compose 订阅。 */
private class JobHolder { var job: Job? = null }

/**
 * 点动停止后等待电机自然减速完成的毫秒数：speed/accel × 1000 + 200ms 缓冲，最多 4s。
 * 与 [CanOpenFun.buildJogVelocityZeroFrame] 配套使用。
 */
private fun jogDecelMs(jogSpeed: Int, acceleration: Int): Long =
    (jogSpeed.toLong() * 1000L / acceleration.toLong() + 200L).coerceAtMost(4000L)

/**
 * 按需打开 CAN 串口（幂等）。
 *
 * 解决竞态：从 SowingDepthScreen 导航到本页时，本页 DisposableEffect setup
 * 在上一页 onDispose 之前运行——此时看到端口已开，不会重开；随后上一页
 * onDispose 关闭端口，本页就再也没机会打开。
 *
 * 让点动按钮的 IO 协程在发送前调用此函数，按需打开，绕开时序问题。
 *
 * @return true 端口已打开（或刚刚打开）；false 打开失败（端口名为空 / 异常）
 */
private fun ensureCanPortOpen(context: android.content.Context): Boolean {
    if (mSerialPortCAN != null) return true
    val sp = MySharedPreFun(context).getMySharedPre()
    val portName = sp.getString(
        context.getString(R.string.serial_can_port_name),
        context.getString(R.string.serial_can_port_defValue)
    ) ?: ""
    val baud = sp.getString(
        context.getString(R.string.serial_can_baud_name),
        context.getString(R.string.serial_can_baud_defValue)
    )?.toIntOrNull() ?: 115200
    if (portName.isEmpty()) {
        Log.e("DepthCalib", "ensureCanPortOpen: port name empty in SharedPreferences")
        return false
    }
    return try {
        mSerialPortCAN = SerialPort(File("/dev/$portName"), baud)
        Log.d("DepthCalib", "ensureCanPortOpen: opened /dev/$portName @$baud")
        true
    } catch (e: Exception) {
        Log.e("DepthCalib", "ensureCanPortOpen: open failed for /dev/$portName: ${e.message}", e)
        false
    }
}

/**
 * 最小二乘线性拟合 depth_mm = a * encoderPos + b
 *
 * @param points List of (encoderPos, depthMm) pairs
 * @return Pair(a, b) or null if < 2 valid points or degenerate data
 */
private fun buildLinearFit(points: List<Pair<Int, Float>>): Pair<Float, Float>? {
    val valid = points.filter { it.second > 0f }
    if (valid.size < 2) return null
    val n = valid.size.toDouble()
    val sumX  = valid.sumOf { it.first.toDouble() }
    val sumY  = valid.sumOf { it.second.toDouble() }
    val sumXX = valid.sumOf { it.first.toDouble().pow(2) }
    val sumXY = valid.sumOf { it.first.toDouble() * it.second.toDouble() }
    val denom = n * sumXX - sumX * sumX
    if (kotlin.math.abs(denom) < 1e-10) return null
    val a = ((n * sumXY - sumX * sumY) / denom).toFloat()
    val b = ((sumY - a * sumX) / n).toFloat()
    return Pair(a, b)
}

/**
 * 播种深度电机校准向导
 *
 * 步骤 1：点动电机到最深/最浅位置，记录编码器限位值，写入电机软件限位寄存器。
 * 步骤 2：自动计算 5 个等分编码器位置，移动到每个位置后由用户填入实际测量深度，
 *         计算线性拟合系数，保存到 SharedPreferences。
 *
 * @param viewModel   共享 ViewModel（包含 sowingDepthState LiveData）
 * @param motorIndex  电机编号 0~7（对应 motors[motorIndex]）
 * @param onBack      返回上级页面的回调
 */
@Composable
fun DepthCalibrationScreen(
    viewModel: VariableFertViewModel,
    motorIndex: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val state by viewModel.sowingDepthState.observeAsState(SowingDepthState())
    val cal    = state.motors.getOrElse(motorIndex) { ServoCalibration(motorIndex) }

    // 初始步骤：若限位已完成则直接跳到步骤 2
    var currentStep by remember { mutableStateOf(if (cal.limitsSet) 2 else 1) }

    // ── 步骤 1 状态 ──────────────────────────────────────────────────────────
    var pendingLimitMax   by remember { mutableStateOf<Int?>(null) }   // 最深位置
    var pendingLimitMin   by remember { mutableStateOf<Int?>(null) }   // 最浅位置
    var limitsWriteBusy   by remember { mutableStateOf(false) }

    // 点动速度（跟随 state.jogSpeed 初始化，持久化变更）
    var jogSpeed by remember(state.jogSpeed) { mutableStateOf(state.jogSpeed) }

    // 位置运动速度（跟随 state.positionSpeed 初始化，持久化变更）
    var positionSpeed by remember(state.positionSpeed) { mutableStateOf(state.positionSpeed) }

    // 加减速度（跟随 state.acceleration 初始化，持久化变更）
    var acceleration by remember(state.acceleration) { mutableStateOf(state.acceleration) }

    // 点动按钮按下次数（用于排查"按下事件是否被识别"——动画 + 计数器双重可视化反馈）
    var deepPressCount    by remember { mutableStateOf(0) }
    var shallowPressCount by remember { mutableStateOf(0) }

    // 点动启动协程 Job 引用（Step1/Step2 共用）：onJogStop 通过 cancelAndJoin 等待启动
    // 序列完全退出后再发送 velocity=0，防止快速轻触时两个协程交错导致电机持续运行
    val jogStartHolder       = remember { JobHolder() }
    val step2LimitMonHolder  = remember { JobHolder() }

    // ── 步骤 2 状态 ──────────────────────────────────────────────────────────
    // 跟随 cal.calibrationMode 重置（仅在持久化值变化时），保持"未保存的本地切换"行为
    var calibMode by remember(cal.calibrationMode) { mutableStateOf(cal.calibrationMode) }
    val depthInputs = remember { Array(5) { mutableStateOf("") } }
    var fitResult   by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var fitErrorMsg by remember { mutableStateOf("") }
    var moveBusy    by remember { mutableStateOf(false) }
    val showSaveConfirm = remember { mutableStateOf(false) }

    // 5 个等分编码器位置（只在限位已设置且不等时才计算）
    val calibPositions: List<Int> = remember(cal.limitMin, cal.limitMax, cal.limitsSet) {
        if (!cal.limitsSet || cal.limitMin == cal.limitMax) emptyList()
        else (0..4).map { i -> cal.limitMin + (cal.limitMax - cal.limitMin) * i / 4 }
    }

    // ── 辅助函数 ─────────────────────────────────────────────────────────────

    fun persistJogSpeed(speed: Int) {
        scope.launch(Dispatchers.IO) {
            viewModel.updateSowingDepthGlobalSettings(jogSpeed = speed)
            MySharedPreFun(context).saveSowingDepthGlobalSettings(
                jogSpeed          = speed,
                positionSpeed     = positionSpeed,
                acceleration      = state.acceleration,
                globalTargetDepth = state.globalTargetDepth
            )
        }
    }

    fun persistPositionSpeed(speed: Int) {
        scope.launch(Dispatchers.IO) {
            viewModel.updateSowingDepthGlobalSettings(positionSpeed = speed)
            MySharedPreFun(context).saveSowingDepthGlobalSettings(
                jogSpeed          = jogSpeed,
                positionSpeed     = speed,
                acceleration      = acceleration,
                globalTargetDepth = state.globalTargetDepth
            )
        }
    }

    fun persistAcceleration(accel: Int) {
        scope.launch(Dispatchers.IO) {
            viewModel.updateSowingDepthGlobalSettings(acceleration = accel)
            MySharedPreFun(context).saveSowingDepthGlobalSettings(
                jogSpeed          = jogSpeed,
                positionSpeed     = positionSpeed,
                acceleration      = accel,
                globalTargetDepth = state.globalTargetDepth
            )
        }
    }

    // 写/清第 blockIdx 个挡块的编码器位置：useCurrentPos=true 写入当前 cal.currentPosition，
    // false 清除（设为 null）。同值短路在 ViewModel.updateServoCalibration 内部处理。
    fun setIndirectPointEncoder(blockIdx: Int, useCurrentPos: Boolean) {
        viewModel.updateServoCalibration(motorIndex) { c ->
            val newEnc = if (useCurrentPos) c.currentPosition else null
            c.copy(indirectPoints = c.indirectPoints.mapIndexed { i, pt ->
                if (i == blockIdx) pt.copy(encoderPos = newEnc) else pt
            })
        }
    }

    // 收集当前模式下用于线性拟合的 (encoderPos, depthMm) 点列表
    fun collectFitPoints(): List<Pair<Int, Float>> = when (calibMode) {
        CalibrationMode.DIRECT -> (0..4).mapNotNull { i ->
            val depth = depthInputs[i].value.toFloatOrNull()
            val pos   = calibPositions.getOrNull(i)
            if (depth != null && depth > 0f && pos != null) Pair(pos, depth) else null
        }
        CalibrationMode.INDIRECT -> cal.indirectPoints
            .mapNotNull { it.encoderPos?.let { p -> Pair(p, it.depthMm) } }
    }

    fun computeFit() {
        val result = buildLinearFit(collectFitPoints())
        if (result == null) {
            fitErrorMsg = if (calibMode == CalibrationMode.DIRECT)
                "需要至少 2 个有效输入点（深度 > 0）"
            else
                "需要至少 2 个已记录的挡块点"
            fitResult   = null
        } else {
            fitResult   = result
            fitErrorMsg = ""
        }
    }

    fun saveCalibration() {
        val (a, b) = fitResult ?: return
        val calPoints = collectFitPoints()
        viewModel.updateServoCalibration(motorIndex) {
            it.copy(
                calibrationPoints = calPoints,
                fitA = a, fitB = b, fitValid = true,
                calibrationMode = calibMode
            )
        }
        scope.launch(Dispatchers.IO) {
            val updated = viewModel.sowingDepthState.value?.motors?.getOrNull(motorIndex)
                ?: return@launch
            MySharedPreFun(context).saveSowingDepthCalibration(updated)
        }
        showSaveConfirm.value = false
    }

    // ── 点动启停辅助函数 ─────────────────────────────────────────────────────
    // 当前编码器位置快照（监控协程与限位预检共用）
    fun currentPos(): Int = viewModel.sowingDepthState.value
        ?.motors?.getOrNull(motorIndex)?.currentPosition ?: cal.currentPosition

    // 启动点动：toDeep=true 朝 limitMax 方向（正速度 jogSpeed），false 朝 limitMin 方向（负速度）。
    // withLimitMonitor=true 时启动后台监控，到限自动发 velocity=0；并先做一次到限预检。
    fun launchJog(toDeep: Boolean, withLimitMonitor: Boolean) {
        jogStartHolder.job?.cancel()
        if (withLimitMonitor) {
            step2LimitMonHolder.job?.cancel()
            step2LimitMonHolder.job = null
        }
        val launched = scope.launch(Dispatchers.IO) {
            try {
                if (!ensureCanPortOpen(context)) return@launch

                if (withLimitMonitor && cal.limitsSet) {
                    val deepDir = cal.deepDirection
                    val pos     = currentPos()
                    val targetLimit = if (toDeep) cal.limitMax else cal.limitMin
                    val reachedDeepFromAbove   = (deepDir > 0) == toDeep && pos >= targetLimit
                    val reachedDeepFromBelow   = (deepDir < 0) == toDeep && pos <= targetLimit
                    if (deepDir != 0 && (reachedDeepFromAbove || reachedDeepFromBelow)) return@launch
                }

                CanOpenFun.buildSetAccelDecelFrames(cal.nodeId, acceleration).forEach {
                    CanOpenFun.sendFrame(it); delay(SDO_DELAY)
                }
                CanOpenFun.buildJogStartSequence(cal.nodeId, if (toDeep) jogSpeed else -jogSpeed)
                    .forEach { CanOpenFun.sendFrame(it); delay(SDO_DELAY) }

                if (withLimitMonitor && cal.limitsSet && cal.deepDirection != 0) {
                    val deepDir = cal.deepDirection
                    val limitTarget = if (toDeep) cal.limitMax else cal.limitMin
                    step2LimitMonHolder.job = scope.launch(Dispatchers.IO) {
                        while (true) {
                            delay(100)
                            val p = viewModel.sowingDepthState.value
                                ?.motors?.getOrNull(motorIndex)?.currentPosition ?: break
                            val hit =
                                if ((deepDir > 0) == toDeep) p >= limitTarget else p <= limitTarget
                            if (hit) {
                                Log.d("DepthCalib",
                                    "Step2 ${if (toDeep) "deep" else "shallow"} limit hit pos=$p target=$limitTarget")
                                CanOpenFun.sendFrame(CanOpenFun.buildJogVelocityZeroFrame(cal.nodeId))
                                break
                            }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) {
                Log.e("DepthCalib", "launchJog(toDeep=$toDeep) threw: ${e.message}", e)
            }
        }
        jogStartHolder.job = launched
        // 自完成时只清自身引用，不影响后续被覆盖写入的新 Job
        launched.invokeOnCompletion {
            if (jogStartHolder.job === launched) jogStartHolder.job = null
        }
    }

    // 停止点动：等待启动序列退出 → 发 velocity=0 → 等待自然减速完成。
    fun launchJogStop(stopMonitor: Boolean) {
        if (stopMonitor) {
            step2LimitMonHolder.job?.cancel()
            step2LimitMonHolder.job = null
        }
        val startJob = jogStartHolder.job.also { jogStartHolder.job = null }
        scope.launch(Dispatchers.IO) {
            try {
                if (!ensureCanPortOpen(context)) {
                    startJob?.cancelAndJoin()
                    return@launch
                }
                // 确保启动协程已退出后再发 velocity=0，velocity=0 必须是最后一条
                startJob?.cancelAndJoin()
                CanOpenFun.sendFrame(CanOpenFun.buildJogVelocityZeroFrame(cal.nodeId))
                delay(jogDecelMs(jogSpeed, acceleration))
            } catch (e: Throwable) {
                Log.e("DepthCalib", "launchJogStop threw: ${e.message}", e)
            }
        }
    }

    // ── 串口与协程生命周期 ───────────────────────────────────────────────────
    // 注：跨页 DisposableEffect 时序竞态——本页 setup 早于上一页 onDispose 运行，
    // 此时端口可能还在上一页手里看似已开，但稍后会被它关闭。所以这里不再依赖
    // setup 时刻的端口状态：协程内的 sendFrame 调用都改由 ensureCanPortOpen()
    // 按需懒打开（见 onJogDeep / onJogShallow / onJogStop / onConfirmLimits / onMoveToPosition）。
    DisposableEffect(Unit) {
        val receiveCoroutine: CanReceiveCoroutine?
        val depthCoroutine:   SowingDepthCoroutine?
        if (!isSystemRunning) {
            // 进入本页时尝试一次打开（若已开则 no-op）
            ensureCanPortOpen(context)
            receiveCoroutine = CanReceiveCoroutine().also { it.start(viewModel, context) }
            depthCoroutine   = SowingDepthCoroutine().also { it.start(viewModel, context) }
        } else {
            receiveCoroutine = null
            depthCoroutine   = null
        }
        onDispose {
            // 离开页面时若仍在点动，必须停车——rememberCoroutineScope 取消会杀掉点动启动协程，
            // 但电机已经在转，需要显式发一帧 velocity=0。监控协程也要先停。
            jogStartHolder.job?.cancel()
            step2LimitMonHolder.job?.cancel()
            jogStartHolder.job      = null
            step2LimitMonHolder.job = null
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    CanOpenFun.sendFrame(CanOpenFun.buildJogVelocityZeroFrame(cal.nodeId))
                }
            }
            receiveCoroutine?.shutdown()
            depthCoroutine?.shutdown()
            // 不在此处关闭串口：上一页或下一页的协程 / 懒打开逻辑可能仍在使用，
            // 真正释放由 Activity 级生命周期或施肥模式启动负责。
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = { MyTopBar("电机 ${motorIndex + 1} 深度校准", onBack) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 作业模式警告
            if (isSystemRunning) {
                Card(
                    backgroundColor = Color(0xFFFFF3CD),
                    elevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "⚠ 作业模式运行中，建议停止作业后再进行校准操作",
                        color = Color(0xFF856404),
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp
                    )
                }
            }

            // 电机离线警告
            if (!cal.isOnline) {
                Card(
                    backgroundColor = Color(0xFFFFE0E0),
                    elevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "⚠ 电机离线，请检查 CAN 连接和节点 ID（当前 Node-ID: ${cal.nodeId}）",
                        color = Color(0xFFB00020),
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp
                    )
                }
            }

            // 步骤切换标签
            StepIndicator(
                currentStep   = currentStep,
                step2Enabled  = cal.limitsSet,
                onStepSelected = { step ->
                    if (step == 1) currentStep = 1
                    else if (step == 2 && cal.limitsSet) currentStep = 2
                }
            )

            // ── 步骤 1：限位设置 ─────────────────────────────────────────────
            AnimatedVisibility(
                visible = currentStep == 1,
                enter   = expandVertically(),
                exit    = shrinkVertically()
            ) {
                Step1Content(
                    cal                   = cal,
                    jogSpeed              = jogSpeed,
                    pendingLimitMin       = pendingLimitMin,
                    pendingLimitMax       = pendingLimitMax,
                    limitsWriteBusy       = limitsWriteBusy,
                    deepPressCount        = deepPressCount,
                    shallowPressCount     = shallowPressCount,
                    onJogSpeedChange      = { jogSpeed = it },
                    onJogSpeedChangeFinished = { persistJogSpeed(jogSpeed) },
                    acceleration              = acceleration,
                    onAccelerationChange      = { acceleration = it },
                    onAccelerationChangeFinished = { persistAcceleration(acceleration) },
                    onJogDeep = {
                        deepPressCount++
                        Log.d("DepthCalib", "Step1 deep#$deepPressCount node=${cal.nodeId} v=$jogSpeed")
                        launchJog(toDeep = true, withLimitMonitor = false)
                    },
                    onJogShallow = {
                        shallowPressCount++
                        Log.d("DepthCalib", "Step1 shallow#$shallowPressCount node=${cal.nodeId} v=$jogSpeed")
                        launchJog(toDeep = false, withLimitMonitor = false)
                    },
                    onJogStop = { launchJogStop(stopMonitor = false) },
                    onSetLimitMax    = { pendingLimitMax = cal.currentPosition },
                    onSetLimitMin    = { pendingLimitMin = cal.currentPosition },
                    onConfirmLimits  = {
                        val lMin = pendingLimitMin ?: return@Step1Content
                        val lMax = pendingLimitMax ?: return@Step1Content
                        limitsWriteBusy = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (!ensureCanPortOpen(context)) {
                                    Log.e("DepthCalib", "onConfirmLimits: ensureCanPortOpen failed")
                                    return@withContext
                                }
                                // 写正向软件限位（最深）至 0x261F
                                CanOpenFun.sendFrame(
                                    CanOpenFun.buildSdoWriteFrame(cal.nodeId, 0x261F, 0x00, 4, lMax.toLong())
                                )
                                delay(SDO_DELAY)
                                // 写负向软件限位（最浅）至 0x2620
                                CanOpenFun.sendFrame(
                                    CanOpenFun.buildSdoWriteFrame(cal.nodeId, 0x2620, 0x00, 4, lMin.toLong())
                                )
                                delay(SDO_DELAY)
                            }
                            viewModel.updateServoCalibration(motorIndex) {
                                it.copy(limitMin = lMin, limitMax = lMax, limitsSet = true)
                            }
                            withContext(Dispatchers.IO) {
                                val updated = viewModel.sowingDepthState.value
                                    ?.motors?.getOrNull(motorIndex) ?: return@withContext
                                MySharedPreFun(context).saveSowingDepthCalibration(updated)
                            }
                            limitsWriteBusy = false
                            currentStep     = 2
                        }
                    }
                )
            }

            // ── 步骤 2：5 挡位校准 ──────────────────────────────────────────
            AnimatedVisibility(
                visible = currentStep == 2,
                enter   = expandVertically(),
                exit    = shrinkVertically()
            ) {
                Step2Content(
                    cal                      = cal,
                    calibMode                = calibMode,
                    onCalibModeChange        = { calibMode = it },
                    calibPositions           = calibPositions,
                    depthInputs              = depthInputs,
                    fitResult                = fitResult,
                    fitErrorMsg              = fitErrorMsg,
                    moveBusy                 = moveBusy,
                    positionSpeed            = positionSpeed,
                    onPositionSpeedChange    = { positionSpeed = it },
                    onPositionSpeedFinished  = { persistPositionSpeed(positionSpeed) },
                    onMoveToPosition = { targetPulse ->
                        moveBusy = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                if (!ensureCanPortOpen(context)) {
                                    Log.e("DepthCalib", "onMoveToPosition: ensureCanPortOpen failed")
                                    return@launch
                                }
                                // buildAbsoluteMoveFrames 已内嵌 0x6060=1 模式切换帧，无需另行发送
                                CanOpenFun.buildAbsoluteMoveFrames(cal.nodeId, targetPulse, positionSpeed)
                                    .forEach { CanOpenFun.sendFrame(it); delay(SDO_DELAY) }
                            } finally {
                                withContext(Dispatchers.Main) { moveBusy = false }
                            }
                        }
                    },
                    onJogDeep    = { launchJog(toDeep = true,  withLimitMonitor = true) },
                    onJogShallow = { launchJog(toDeep = false, withLimitMonitor = true) },
                    onJogStop    = { launchJogStop(stopMonitor = true) },
                    onRecordIndirectPoint = { setIndirectPointEncoder(it, useCurrentPos = true) },
                    onClearIndirectPoint  = { setIndirectPointEncoder(it, useCurrentPos = false) },
                    onComputeFit = { computeFit() },
                    onSaveClick  = { showSaveConfirm.value = true }
                )
            }
        }
    }

    // 保存校准确认对话框
    if (showSaveConfirm.value) {
        val (a, b) = fitResult ?: Pair(0f, 0f)
        ShowConfirmDialog(
            title      = "保存校准数据",
            text       = "确认保存拟合结果？\ndepth = ${"%.6f".format(a)} × pos + ${"%.2f".format(b)}",
            onConfirm  = { saveCalibration() },
            showDialog = showSaveConfirm
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 步骤切换标签栏
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepIndicator(
    currentStep:    Int,
    step2Enabled:   Boolean,
    onStepSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(1 to "步骤 1：限位设置", 2 to "步骤 2：深度校准").forEach { (step, label) ->
            val isSelected = currentStep == step
            val isEnabled  = step == 1 || step2Enabled
            TextButton(
                onClick  = { onStepSelected(step) },
                enabled  = isEnabled,
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (isSelected) Color(0xFF1565C0) else Color(0xFFEEEEEE),
                        shape = RoundedCornerShape(6.dp)
                    )
            ) {
                Text(
                    text  = label,
                    color = when {
                        isSelected -> Color.White
                        isEnabled  -> Color(0xFF1565C0)
                        else       -> Color.Gray
                    },
                    fontSize = 13.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 步骤 1：限位设置内容
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun Step1Content(
    cal:                    ServoCalibration,
    jogSpeed:               Int,
    pendingLimitMin:        Int?,
    pendingLimitMax:        Int?,
    limitsWriteBusy:        Boolean,
    deepPressCount:         Int,
    shallowPressCount:      Int,
    onJogSpeedChange:       (Int) -> Unit,
    onJogSpeedChangeFinished: () -> Unit,
    acceleration:           Int,
    onAccelerationChange:   (Int) -> Unit,
    onAccelerationChangeFinished: () -> Unit,
    onJogDeep:              () -> Unit,
    onJogShallow:           () -> Unit,
    onJogStop:              () -> Unit,
    onSetLimitMax:          () -> Unit,
    onSetLimitMin:          () -> Unit,
    onConfirmLimits:        () -> Unit
) {
    Card(
        elevation = 2.dp,
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CurrentEncoderPositionRow(cal.currentPosition)

            Divider()

            // 点动速度滑块
            // Slider steps=99: valueRange 1500..2500，步长 10 RPM，共 101 个离散值
            Text("点动速度: $jogSpeed RPM", fontSize = 14.sp)
            Slider(
                value                = jogSpeed.toFloat(),
                onValueChange        = { onJogSpeedChange(it.toInt()) },
                onValueChangeFinished = onJogSpeedChangeFinished,
                valueRange           = 1500f..2500f,
                steps                = 99,
                colors               = SliderDefaults.colors(
                    thumbColor       = Color(0xFF1565C0),
                    activeTrackColor = Color(0xFF1565C0)
                )
            )

            // 加减速度滑块（steps=58 → 步长 1000 RPM/s，范围 1000~60000）
            Text("加减速度: $acceleration RPM/s", fontSize = 14.sp)
            Slider(
                value                 = acceleration.toFloat(),
                onValueChange         = { onAccelerationChange(it.toInt()) },
                onValueChangeFinished = onAccelerationChangeFinished,
                valueRange            = 1000f..60000f,
                steps                 = 58,
                colors                = SliderDefaults.colors(
                    thumbColor       = Color(0xFF1565C0),
                    activeTrackColor = Color(0xFF1565C0)
                )
            )

            JogControlRow(onJogDeep = onJogDeep, onJogShallow = onJogShallow, onJogStop = onJogStop)

            // 排查诊断条：按下计数 + CAN 串口实时状态
            // 若按按钮时计数不增长 → 触摸事件没被识别（pointerInput / detectTapGestures 问题）
            // 若计数增长但 CAN 总线无帧 → 看 Logcat 标签 "DepthCalib" 与 "CanOpenFun"
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    "深 按下: $deepPressCount",
                    fontSize   = 12.sp,
                    color      = Color(0xFF455A64),
                    fontFamily = FontFamily.Monospace
                )
                val portOpen = mSerialPortCAN != null
                Text(
                    if (portOpen) "串口: 已打开" else "串口: 未打开",
                    fontSize = 12.sp,
                    color    = if (portOpen) Color(0xFF388E3C) else Color(0xFFB00020)
                )
                Text(
                    "浅 按下: $shallowPressCount",
                    fontSize   = 12.sp,
                    color      = Color(0xFF455A64),
                    fontFamily = FontFamily.Monospace
                )
            }

            Divider()

            // 限位记录
            Text("记录限位（点动到目标位置后点击）", fontSize = 13.sp, color = Color.Gray)

            LimitRow(
                label           = "最深限位",
                recordedValue   = pendingLimitMax,
                onSet           = onSetLimitMax
            )
            LimitRow(
                label           = "最浅限位",
                recordedValue   = pendingLimitMin,
                onSet           = onSetLimitMin
            )

            if (pendingLimitMin != null && pendingLimitMax != null && pendingLimitMin == pendingLimitMax) {
                Text("⚠ 最深和最浅限位不能相同", color = Color.Red, fontSize = 12.sp)
            }

            val canConfirm = pendingLimitMin != null && pendingLimitMax != null
                    && pendingLimitMin != pendingLimitMax && !limitsWriteBusy

            Button(
                onClick  = onConfirmLimits,
                enabled  = canConfirm,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(
                    backgroundColor         = Color(0xFF1565C0),
                    disabledBackgroundColor = Color(0xFFBBBBBB)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (limitsWriteBusy) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(16.dp),
                        color       = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("确认限位并写入电机", color = Color.White)
            }

            // 显示已保存的限位（若已完成标定）
            if (cal.limitsSet) {
                Text(
                    "已保存限位: Min = ${cal.limitMin}  Max = ${cal.limitMax}",
                    fontSize = 12.sp,
                    color    = Color(0xFF388E3C)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 步骤 2：5 挡位校准内容
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun Step2Content(
    cal:                     ServoCalibration,
    calibMode:               CalibrationMode,
    onCalibModeChange:       (CalibrationMode) -> Unit,
    calibPositions:          List<Int>,
    depthInputs:             Array<MutableState<String>>,
    fitResult:               Pair<Float, Float>?,
    fitErrorMsg:             String,
    moveBusy:                Boolean,
    positionSpeed:           Int,
    onPositionSpeedChange:   (Int) -> Unit,
    onPositionSpeedFinished: () -> Unit,
    onMoveToPosition:        (Int) -> Unit,
    onJogDeep:               () -> Unit,
    onJogShallow:            () -> Unit,
    onJogStop:               () -> Unit,
    onRecordIndirectPoint:   (Int) -> Unit,
    onClearIndirectPoint:    (Int) -> Unit,
    onComputeFit:            () -> Unit,
    onSaveClick:             () -> Unit
) {
    Card(
        elevation = 2.dp,
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("深度校准", fontSize = 16.sp, color = Color(0xFF1565C0))

            // 模式切换行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    CalibrationMode.DIRECT   to "直接测量",
                    CalibrationMode.INDIRECT to "间接测量（挡块法）"
                ).forEach { (mode, label) ->
                    val selected = calibMode == mode
                    TextButton(
                        onClick  = { onCalibModeChange(mode) },
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = if (selected) Color(0xFF1565C0) else Color(0xFFEEEEEE),
                                shape = RoundedCornerShape(6.dp)
                            )
                    ) {
                        Text(
                            text     = label,
                            color    = if (selected) Color.White else Color(0xFF1565C0),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Divider()

            if (calibMode == CalibrationMode.DIRECT) {
                // ── 直接测量模式 ──────────────────────────────────────────────
                Text(
                    "限位范围: ${cal.limitMin} ~ ${cal.limitMax} pulses\n" +
                    "移动到每个挡位，用深度尺测量实际值后填入。",
                    fontSize = 13.sp, color = Color.Gray
                )

                // 位置运动速度滑块：steps=289 → (3000-100)/10 - 1，步长 10 RPM
                Text("位置运动速度: $positionSpeed RPM", fontSize = 14.sp)
                Slider(
                    value                 = positionSpeed.toFloat(),
                    onValueChange         = { onPositionSpeedChange(it.toInt()) },
                    onValueChangeFinished = onPositionSpeedFinished,
                    valueRange            = 100f..3000f,
                    steps                 = 289,
                    colors                = SliderDefaults.colors(
                        thumbColor       = Color(0xFF1565C0),
                        activeTrackColor = Color(0xFF1565C0)
                    )
                )

                Divider()

                if (calibPositions.isEmpty()) {
                    Text("限位尚未设置，请返回步骤 1 完成限位标定。", color = Color.Red, fontSize = 13.sp)
                } else {
                    calibPositions.forEachIndexed { i, encoderPos ->
                        CalibPointRow(
                            index      = i,
                            encoderPos = encoderPos,
                            depthInput = depthInputs[i],
                            moveBusy   = moveBusy,
                            onMoveHere = { onMoveToPosition(encoderPos) }
                        )
                        if (i < 4) Divider(color = Color(0xFFEEEEEE), thickness = 0.5.dp)
                    }
                }
            } else {
                // ── 间接测量模式（挡块法）───────────────────────────────────
                IndirectCalibSection(
                    cal           = cal,
                    onJogDeep     = onJogDeep,
                    onJogShallow  = onJogShallow,
                    onJogStop     = onJogStop,
                    onRecordPoint = onRecordIndirectPoint,
                    onClearPoint  = onClearIndirectPoint
                )
            }

            Divider()

            // 拟合结果
            if (fitResult != null) {
                Card(
                    backgroundColor = Color(0xFFE8F5E9),
                    elevation       = 0.dp,
                    shape           = RoundedCornerShape(6.dp),
                    modifier        = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("拟合结果（线性）:", fontSize = 13.sp, color = Color(0xFF388E3C))
                        Text(
                            "depth(mm) = ${"%.6f".format(fitResult.first)} × pos + ${"%.2f".format(fitResult.second)}",
                            fontSize   = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color      = Color(0xFF1B5E20)
                        )
                    }
                }
            }
            if (fitErrorMsg.isNotEmpty()) {
                Text(fitErrorMsg, color = Color.Red, fontSize = 13.sp)
            }

            // 操作按钮行
            val computeEnabled = if (calibMode == CalibrationMode.DIRECT)
                calibPositions.isNotEmpty()
            else
                cal.indirectPoints.count { it.encoderPos != null } >= 2
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick  = onComputeFit,
                    enabled  = computeEnabled,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                    shape    = RoundedCornerShape(8.dp)
                ) {
                    Text("计算拟合", color = Color.White, fontSize = 13.sp)
                }
                Button(
                    onClick  = onSaveClick,
                    enabled  = fitResult != null,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(
                        backgroundColor         = Color(0xFF2E7D32),
                        disabledBackgroundColor = Color(0xFFBBBBBB)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("保存校准", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 间接测量区块（挡块法）
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun IndirectCalibSection(
    cal:           ServoCalibration,
    onJogDeep:     () -> Unit,
    onJogShallow:  () -> Unit,
    onJogStop:     () -> Unit,
    onRecordPoint: (Int) -> Unit,
    onClearPoint:  (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CurrentEncoderPositionRow(cal.currentPosition)
        JogControlRow(onJogDeep = onJogDeep, onJogShallow = onJogShallow, onJogStop = onJogStop)

        Text(
            "将挡块放在限深轮下方，点动压到刚好接触后点击 [记录当前位置]",
            fontSize = 13.sp, color = Color.Gray
        )

        Divider()

        cal.indirectPoints.forEachIndexed { i, pt ->
            IndirectCalibRow(
                blockCm    = (pt.depthMm / 10f).toInt(),
                encoderPos = pt.encoderPos,
                onRecord   = { onRecordPoint(i) },
                onClear    = { onClearPoint(i) }
            )
            if (i < cal.indirectPoints.lastIndex)
                Divider(color = Color(0xFFEEEEEE), thickness = 0.5.dp)
        }
    }
}

@Composable
private fun IndirectCalibRow(
    blockCm:    Int,
    encoderPos: Int?,
    onRecord:   () -> Unit,
    onClear:    () -> Unit
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "$blockCm cm",
            fontSize = 14.sp,
            color    = Color(0xFF1565C0),
            modifier = Modifier.width(44.dp)
        )
        if (encoderPos != null) {
            Text(
                "$encoderPos",
                fontSize   = 13.sp,
                color      = Color(0xFF388E3C),
                fontFamily = FontFamily.Monospace,
                modifier   = Modifier.weight(1f)
            )
            Text("✓", fontSize = 14.sp, color = Color(0xFF388E3C))
        } else {
            Text(
                "——",
                fontSize = 13.sp,
                color    = Color.Gray,
                modifier = Modifier.weight(1f)
            )
        }
        val recorded = encoderPos != null
        OutlinedButton(
            onClick        = if (recorded) onClear else onRecord,
            shape          = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                if (recorded) "清除" else "记录当前位置",
                fontSize = 11.sp,
                color    = if (recorded) Color.Gray else Color(0xFF1565C0)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 共享小组件：实时编码器位置行 + 点动按钮 Row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CurrentEncoderPositionRow(currentPosition: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("当前编码器位置", modifier = Modifier.weight(1f), fontSize = 14.sp, color = Color.Gray)
        Text(
            "$currentPosition",
            fontSize   = 20.sp,
            color      = Color(0xFF1565C0),
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(4.dp))
        Text("pulses", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
private fun JogControlRow(onJogDeep: () -> Unit, onJogShallow: () -> Unit, onJogStop: () -> Unit) {
    Text("点动控制（按住运动，松开停止）", fontSize = 13.sp, color = Color.Gray)
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        JogButton(label = "深 ▼", onPress = onJogDeep,    onRelease = onJogStop)
        JogButton(label = "浅 ▲", onPress = onJogShallow, onRelease = onJogStop)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 点动按钮（按住运动，松开停止）
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun JogButton(
    label:     String,
    onPress:   () -> Unit,
    onRelease: () -> Unit
) {
    // rememberUpdatedState 保证 pointerInput 内部始终读到最新回调引用
    val latestOnPress   by rememberUpdatedState(onPress)
    val latestOnRelease by rememberUpdatedState(onRelease)

    // 按下状态：用于驱动缩放、底色加深、黄色高亮边框三组动画
    var isPressed by remember { mutableStateOf(false) }

    val animatedScale by animateFloatAsState(
        targetValue   = if (isPressed) 0.90f else 1.0f,
        animationSpec = tween(durationMillis = 100),
        label         = "jog_scale"
    )
    val animatedBg by animateColorAsState(
        targetValue   = if (isPressed) Color(0xFF0D47A1) else Color(0xFF1565C0),
        animationSpec = tween(durationMillis = 100),
        label         = "jog_bg"
    )
    val animatedBorder by animateFloatAsState(
        targetValue   = if (isPressed) 5f else 0f,
        animationSpec = tween(durationMillis = 100),
        label         = "jog_border"
    )

    Box(
        modifier = Modifier
            .size(88.dp)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .clip(CircleShape)
            .background(animatedBg)
            .border(
                width = animatedBorder.dp,
                color = Color(0xFFFFEB3B),
                shape = CircleShape
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isPressed = true
                    Log.d("JogButton", "[$label] PRESSED")
                    latestOnPress()
                    try {
                        var event: PointerEvent
                        do {
                            event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }  // 消费移动事件，阻止父级滚动截获
                        } while (event.changes.any { it.pressed })
                    } finally {
                        isPressed = false
                        Log.d("JogButton", "[$label] RELEASED")
                        latestOnRelease()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 18.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 限位记录行
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LimitRow(
    label:         String,
    recordedValue: Int?,
    onSet:         () -> Unit
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        if (recordedValue != null) {
            Text(
                "$recordedValue",
                color      = Color(0xFF1565C0),
                fontFamily = FontFamily.Monospace,
                fontSize   = 14.sp
            )
        } else {
            Text("未设置", color = Color.Gray, fontSize = 14.sp)
        }
        OutlinedButton(
            onClick         = onSet,
            shape           = RoundedCornerShape(6.dp),
            contentPadding  = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text("设为此位置", fontSize = 12.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 单挡位校准行：显示编码器位置 + 移动按钮 + 深度输入
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CalibPointRow(
    index:      Int,
    encoderPos: Int,
    depthInput: MutableState<String>,
    moveBusy:   Boolean,
    onMoveHere: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "挡位 ${index + 1}",
                fontSize = 14.sp,
                color    = Color(0xFF1565C0),
                modifier = Modifier.width(52.dp)
            )
            Text(
                "编码器: $encoderPos",
                fontSize   = 13.sp,
                color      = Color.Gray,
                fontFamily = FontFamily.Monospace,
                modifier   = Modifier.weight(1f)
            )
            Button(
                onClick         = onMoveHere,
                enabled         = !moveBusy,
                colors          = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF37474F)),
                shape           = RoundedCornerShape(6.dp),
                contentPadding  = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("移动到此", color = Color.White, fontSize = 12.sp)
            }
        }
        OutlinedTextField(
            value          = depthInput.value,
            onValueChange  = { depthInput.value = it },
            label          = { Text("实测深度 (mm)", fontSize = 12.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine     = true,
            modifier       = Modifier.fillMaxWidth()
        )
    }
}
