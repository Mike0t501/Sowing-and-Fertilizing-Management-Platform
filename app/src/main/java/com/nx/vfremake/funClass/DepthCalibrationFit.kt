/** @author NIANXI @date 2026-11-04 @brief 电压标定拟合与稳定性校验；纯Kotlin。 */
package com.nx.vfremake.funClass

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object DepthCalibrationFit {
    // 任务书§3.7的采点重复性门限，是工程验收条件，不冒称标准原文指标。
    const val STABILITY_STD_MM = 0.3f
    const val CAPTURE_DURATION_MS = 2000L
    data class Fit(
        val order: Int, val c0: Double, val c1: Double, val c2: Double,
        val rSquared: Double, val residualsMm: List<Float>, val maxResidualMm: Float
    ) {
        /** 拟合诊断使用Double，不提前取整为显示档位。 */
        fun at(mv: Double): Double = c0 + c1 * mv + c2 * mv * mv
        /** 二次曲线的局部灵敏度。 */
        fun derivative(mv: Double): Double = c1 + 2 * c2 * mv
    }

    /** 保留残差自由度：一次至少3点，二次至少4点；0mm为合法标定点。 */
    fun fit(points: List<Pair<Float, Float>>, order: Int): Result<Fit> = runCatching {
        require(order in 1..2) { "拟合阶数仅支持1或2" }
        require(points.size >= order + 2) { "${order}次拟合至少需要${order + 2}个点" }
        require(points.all { it.first.isFinite() && it.first in 0f..5000f && it.second.isFinite() && it.second >= 0f }) {
            "标定点必须是有效电压和非负深度，0mm可参与拟合"
        }
        val center = points.map { it.first.toDouble() }.average()
        val scale = points.maxOf { abs(it.first - center) }
        require(scale > 1e-6) { "电压点过于接近，无法拟合" }
        val size = order + 1
        val matrix = Array(size) { DoubleArray(size + 1) }
        for ((mv, mm) in points) {
            val x = (mv - center) / scale
            val powers = doubleArrayOf(1.0, x, x * x, x * x * x, x * x * x * x)
            for (row in 0 until size) {
                for (col in 0 until size) matrix[row][col] += powers[row + col]
                matrix[row][size] += powers[row] * mm
            }
        }
        for (pivot in 0 until size) {
            val best = (pivot until size).maxBy { abs(matrix[it][pivot]) }
            require(abs(matrix[best][pivot]) > 1e-10) { "电压点退化，请使用分散且不同的挡块位置" }
            val swap = matrix[pivot]; matrix[pivot] = matrix[best]; matrix[best] = swap
            val divisor = matrix[pivot][pivot]
            for (col in pivot..size) matrix[pivot][col] /= divisor
            for (row in 0 until size) if (row != pivot) {
                val factor = matrix[row][pivot]
                for (col in pivot..size) matrix[row][col] -= factor * matrix[pivot][col]
            }
        }
        val a0 = matrix[0][size]; val a1 = matrix[1][size]; val a2 = if (order == 2) matrix[2][size] else 0.0
        val c2 = a2 / (scale * scale)
        val c1 = a1 / scale - 2 * c2 * center
        val c0 = a0 - a1 * center / scale + c2 * center * center
        require(listOf(c0, c1, c2).all { it.isFinite() }) { "拟合系数不是有限值" }
        val minMv = points.minOf { it.first }.toDouble(); val maxMv = points.maxOf { it.first }.toDouble()
        val d0 = c1 + 2 * c2 * minMv; val d1 = c1 + 2 * c2 * maxMv
        require(abs(d0) > 1e-9 && abs(d1) > 1e-9 && d0 * d1 > 0) { "标定区间内曲线反向或灵敏度为零，请重采或改用一次拟合" }
        val residuals = points.map { (mv, mm) -> (mm - (c0 + c1 * mv + c2 * mv * mv)).toFloat() }
        val mean = points.map { it.second.toDouble() }.average()
        val total = points.sumOf { (it.second - mean) * (it.second - mean) }
        val error = residuals.sumOf { it.toDouble() * it }
        Fit(order, c0, c1, c2, if (total > 1e-12) 1 - error / total else if (error < 1e-12) 1.0 else 0.0,
            residuals, residuals.maxOf { abs(it) })
    }

    /** 首次标定没有灵敏度，必须在候选拟合完成后统一检查每个点的采集标准差。 */
    fun unstableIndices(fit: Fit, voltagesMv: List<Float>, stdMv: List<Float>): List<Int> {
        require(voltagesMv.size == stdMv.size)
        return voltagesMv.indices.filter { index ->
            !stdMv[index].isFinite() || stdMv[index] < 0 ||
                abs(fit.derivative(voltagesMv[index].toDouble())) * stdMv[index] > STABILITY_STD_MM
        }
    }

    /** Welford统计避免大电压下平方和相减丢失精度；重复帧不重复入样。 */
    class CaptureAccumulator {
        private var count = 0
        private var mean = 0.0
        private var m2 = 0.0
        private var lastTime: Long? = null
        private var lastSeq: Int? = null
        /** 只接受有效的独立到达帧；FAULT/SAT/SETTLING由采集会话在入口拒绝。 */
        fun add(mv: Float, arrivalMs: Long, seq: Int?) {
            if (!mv.isFinite() || arrivalMs == lastTime && seq == lastSeq) return
            lastTime = arrivalMs; lastSeq = seq; count++
            val delta = mv - mean; mean += delta / count; m2 += delta * (mv - mean)
        }
        /** 至少两个真实帧才能报告标准差，单帧缓存值不能伪装成稳定。 */
        fun result(): Capture = run {
            require(count >= 2) { "有效采样不足，请检查节点在线状态和报告周期" }
            Capture(mean.toFloat(), sqrt(max(0.0, m2 / (count - 1))).toFloat(), count)
        }
    }
    data class Capture(val meanMv: Float, val stdMv: Float, val count: Int)
}
