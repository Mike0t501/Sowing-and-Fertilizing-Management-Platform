/** @author NIANXI @date 2026-11-04 @brief 播深质量纯Kotlin状态机；没有电机命令或Android依赖。 */
package com.nx.vfremake.funClass

import com.nx.vfremake.data.*
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

data class DepthQualityEvent(val row: Int, val type: DepthEventType, val startMs: Long, val endMs: Long,
    val startEpochMs: Long, val endEpochMs: Long, val distanceM: Double?, val minDepthMm: Float?,
    val maxDepthMm: Float?, val targetDepthMm: Float, val endReason: String)

/** 每行一个状态机。事件连续性按真实到达帧计算，重复缓存不能推动持续时间。 */
class DepthQualityMonitor(private val row: Int) {
    data class Input(val atMs: Long, val epochMs: Long, val sample: MeasuredDepthSample,
        val targetMm: Float, val speedKmh: Double?, val shallowStop: Boolean = false, val continuous: Boolean = true)
    data class Update(val status: DepthQualityStatus, val completed: List<DepthQualityEvent>)
    private data class Progress(val start: Input, var distance: Double?, var min: Float?, var max: Float?) {
        fun add(input: Input, segmentM: Double?) {
            distance = if (distance == null || segmentM == null) null else distance!! + segmentM
            input.sample.depthMm?.let { depth -> min = min?.let { minOf(it, depth) } ?: depth; max = max?.let { maxOf(it, depth) } ?: depth }
        }
    }
    // 1cm/10ms分箱保留真实样本的计数、一二阶矩与合格数，窗口边界最多多包含一个箱。
    // 这样低速行驶也不会堆积数百万个采样对象；事件计时/距离不做分箱。
    private data class Bin(val key: Long, var count: Int = 0, var sum: Double = 0.0,
        var sumSq: Double = 0.0, var passed: Int = 0)
    private val bins = ArrayDeque<Bin>()
    private var coordinate = 0.0
    private var windowStarted = false
    private var windowCount = 0
    private var windowSum = 0.0
    private var windowSumSq = 0.0
    private var windowPassed = 0
    private var previous: Input? = null
    private var target: Float? = null
    private var settleUntilMs = Long.MIN_VALUE
    private val entering = mutableMapOf<DepthEventType, Progress>()
    private val exiting = mutableMapOf<DepthEventType, Progress>()
    private val active = linkedMapOf<DepthEventType, Progress>()
    private var status = DepthQualityStatus()

    /** 只有OK样本进入统计；可信浅端止点是腾空判定的明确例外。 */
    fun update(input: Input, config: DepthQualityConfig = DepthQualityConfig()): Update {
        val completed = mutableListOf<DepthQualityEvent>()
        val old = previous
        if (old != null && input.atMs <= old.atMs) return Update(status, emptyList())
        val speed = validSpeed(input.speedKmh)
        val distanceMode = speed != null
        if (old != null && (!input.continuous || (validSpeed(old.speedKmh) != null) != distanceMode)) {
            completed.addAll(invalidate(if (!input.continuous) "DATA_GAP" else "SPEED_MODE_CHANGE").completed)
        }
        val prior = previous
        val dtSeconds = if (prior == null) 0.0 else (input.atMs - prior.atMs) / 1000.0
        val segmentM = if (speed != null && prior != null && validSpeed(prior.speedKmh) != null)
            (speed + validSpeed(prior.speedKmh)!!) / 2 / 3.6 * dtSeconds else if (speed != null) 0.0 else null
        val knownTarget = input.targetMm.isFinite() && input.targetMm > 0
        if (target == null || target != input.targetMm) {
            finishTypes(setOf(DepthEventType.SHALLOW, DepthEventType.DEEP, DepthEventType.UNSTABLE), prior ?: input, "TARGET_CHANGE", completed)
            target = input.targetMm
            settleUntilMs = input.atMs + (DepthQualityConfig.TARGET_SETTLE_SECONDS * 1000).toLong()
            clearWindow()
        }
        val ok = input.sample.quality == DepthNodeQuality.OK && input.sample.depthMm?.isFinite() == true
        val trustedStop = input.shallowStop && input.sample.quality == DepthNodeQuality.OUT_OF_RANGE
        if (!ok && !trustedStop) {
            completed.addAll(invalidate("INVALID_SAMPLE").completed)
            previous = null
            return Update(status, completed)
        }
        if (!ok) {
            finishTypes(setOf(DepthEventType.SHALLOW, DepthEventType.DEEP, DepthEventType.UNSTABLE), prior ?: input, "INVALID_SAMPLE", completed)
            clearWindow()
        }
        val depth = input.sample.depthMm
        val lift = trustedStop || ok && depth!! < DepthQualityConfig.LIFT_DEPTH_MM
        condition(DepthEventType.LIFT_OFF, lift, !lift, input, segmentM, distanceMode = false,
            enterLimit = DepthQualityConfig.LIFT_SECONDS, exitLimit = 0.0, completed = completed)
        val inTransition = input.atMs < settleUntilMs
        if (ok && knownTarget && !inTransition) {
            val tolerance = DepthQualityConfig.toleranceMm(input.targetMm)
            condition(DepthEventType.SHALLOW, depth!! < input.targetMm - tolerance,
                depth >= input.targetMm - tolerance + DepthQualityConfig.HYSTERESIS_MM,
                input, segmentM, distanceMode,
                if (distanceMode) DepthQualityConfig.ENTER_DISTANCE_M else DepthQualityConfig.ENTER_SECONDS,
                if (distanceMode) DepthQualityConfig.EXIT_DISTANCE_M else DepthQualityConfig.EXIT_SECONDS, completed)
            condition(DepthEventType.DEEP, depth > input.targetMm + tolerance,
                depth <= input.targetMm + tolerance - DepthQualityConfig.HYSTERESIS_MM,
                input, segmentM, distanceMode,
                if (distanceMode) DepthQualityConfig.ENTER_DISTANCE_M else DepthQualityConfig.ENTER_SECONDS,
                if (distanceMode) DepthQualityConfig.EXIT_DISTANCE_M else DepthQualityConfig.EXIT_SECONDS, completed)
            addWindow(input, if (distanceMode) segmentM ?: 0.0 else dtSeconds, distanceMode,
                abs(depth - input.targetMm) <= tolerance)
        } else if (inTransition || !knownTarget) {
            finishTypes(setOf(DepthEventType.SHALLOW, DepthEventType.DEEP, DepthEventType.UNSTABLE), input, "TARGET_TRANSITION", completed)
            clearWindow()
        }
        val limit = if (distanceMode) DepthQualityConfig.WINDOW_DISTANCE_M else DepthQualityConfig.WINDOW_SECONDS
        val ready = windowStarted && coordinate + 1e-9 >= limit && windowCount >= 2
        val mean = if (windowCount > 0) windowSum / windowCount else 0.0
        val variance = if (windowCount > 1) max(0.0, (windowSumSq - windowSum * windowSum / windowCount) / (windowCount - 1)) else 0.0
        val cv = if (abs(mean) > 1e-9) sqrt(variance) / abs(mean) else if (variance <= 1e-12) 0.0 else Double.POSITIVE_INFINITY
        val pass = if (windowCount > 0) windowPassed.toFloat() / windowCount else 0f
        val unstable = ready && (pass < DepthQualityConfig.PASS_RATE_MIN || cv > config.cvThreshold)
        condition(DepthEventType.UNSTABLE, unstable, !unstable, input, segmentM, false, 0.0, 0.0, completed)
        previous = input
        status = DepthQualityStatus(active.keys.toSet(), ready, if (ready) pass else null,
            if (ready) cv.toFloat() else null, distanceMode)
        return Update(status, completed)
    }

    /** 断流在最后一次已知样本处结束事件，不把离线等待时间算作异常持续时间。 */
    fun invalidate(reason: String = "OFFLINE"): Update {
        val completed = mutableListOf<DepthQualityEvent>()
        previous?.let { finishTypes(DepthEventType.values().toSet(), it, reason, completed) }
        entering.clear(); exiting.clear(); active.clear(); clearWindow(); previous = null
        status = DepthQualityStatus()
        return Update(status, completed)
    }

    /** 记录停止时结算活动事件快照，监测本身继续运行，不因停止记录清掉界面状态。 */
    fun activeEvents(reason: String = "RECORD_STOP"): List<DepthQualityEvent> = previous?.let { last ->
        active.map { (type, progress) -> event(type, progress, last, reason) }
    } ?: emptyList()

    private fun condition(type: DepthEventType, enter: Boolean, exit: Boolean, input: Input,
        segmentM: Double?, distanceMode: Boolean, enterLimit: Double, exitLimit: Double,
        completed: MutableList<DepthQualityEvent>) {
        val running = active[type]
        if (running != null) {
            running.add(input, segmentM)
            if (exit) {
                val candidate = exiting[type]
                val progress = candidate ?: progress(input).also { exiting[type] = it }
                if (candidate != null) progress.add(input, segmentM)
                if (reached(progress, input, distanceMode, exitLimit)) {
                    completed.add(event(type, running, input, "RECOVERED"))
                    active.remove(type); exiting.remove(type)
                }
            } else exiting.remove(type)
        } else if (enter) {
            val candidate = entering[type]
            val progress = candidate ?: progress(input).also { entering[type] = it }
            if (candidate != null) progress.add(input, segmentM)
            if (reached(progress, input, distanceMode, enterLimit)) {
                active[type] = progress; entering.remove(type)
            }
        } else entering.remove(type)
    }

    private fun progress(input: Input) = Progress(input, if (validSpeed(input.speedKmh) == null) null else 0.0,
        input.sample.depthMm, input.sample.depthMm)
    private fun reached(progress: Progress, input: Input, distanceMode: Boolean, threshold: Double): Boolean =
        if (distanceMode) (progress.distance ?: 0.0) + 1e-9 >= threshold
        else (input.atMs - progress.start.atMs) / 1000.0 + 1e-9 >= threshold
    private fun event(type: DepthEventType, progress: Progress, end: Input, reason: String) = DepthQualityEvent(row, type,
        progress.start.atMs, end.atMs, progress.start.epochMs, end.epochMs, progress.distance, progress.min, progress.max,
        progress.start.targetMm, reason)
    private fun finishTypes(types: Set<DepthEventType>, end: Input, reason: String, completed: MutableList<DepthQualityEvent>) {
        types.forEach { type ->
            active.remove(type)?.let { completed.add(event(type, it, end, reason)) }
            entering.remove(type); exiting.remove(type)
        }
    }
    private fun validSpeed(value: Double?): Double? = value?.takeIf { it.isFinite() && it >= 0 }
    private fun clearWindow() {
        bins.clear(); coordinate = 0.0; windowStarted = false; windowCount = 0
        windowSum = 0.0; windowSumSq = 0.0; windowPassed = 0
    }
    private fun addWindow(input: Input, advance: Double, distanceMode: Boolean, passed: Boolean) {
        if (windowStarted && distanceMode && advance <= 0) return // 停车不积累距离，也不刷入重复静止样本。
        if (!windowStarted) windowStarted = true else coordinate += advance
        val resolution = DepthQualityConfig.WINDOW_BIN_RESOLUTION
        val key = floor(coordinate / resolution).toLong()
        val bin = bins.peekLast()?.takeIf { it.key == key } ?: Bin(key).also { bins.addLast(it) }
        val depth = input.sample.depthMm!!.toDouble()
        bin.count++; bin.sum += depth; bin.sumSq += depth * depth; if (passed) bin.passed++
        windowCount++; windowSum += depth; windowSumSq += depth * depth; if (passed) windowPassed++
        val cutoff = coordinate - if (distanceMode) DepthQualityConfig.WINDOW_DISTANCE_M else DepthQualityConfig.WINDOW_SECONDS
        while (bins.isNotEmpty() && (bins.first.key + 1) * resolution <= cutoff) {
            val expired = bins.removeFirst(); windowCount -= expired.count; windowSum -= expired.sum
            windowSumSq -= expired.sumSq; windowPassed -= expired.passed
        }
    }
}
