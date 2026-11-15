/** @author NIANXI @date 2026-11-04 @brief 从真实STATUS流采集两秒，禁止轮询缓存伪造采样数。 */
package com.nx.vfremake.funClass

import com.nx.vfremake.data.DepthNodeQuality
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

class DepthNodeCalibrationSession(private val runtime: DepthNodeRuntime) {
    /** 采集失败不修改已保存标定；到止点仍可采电压，故不要求已校准的OK质量。 */
    suspend fun capture(row: Int): DepthCalibrationFit.Capture {
        val initial = runtime.channel(row)
        require(initial.isOnline && initial.versionCompatible && initial.quality != DepthNodeQuality.FAULT) { "节点离线或故障，无法采集" }
        val accumulator = DepthCalibrationFit.CaptureAccumulator()
        var badSample = false
        val started = runtime.nowMs()
        withTimeoutOrNull<Unit>(DepthCalibrationFit.CAPTURE_DURATION_MS) {
            runtime.samples.collect { channel ->
                if (channel.row == row && channel.lastHeardMs >= started) {
                    if (channel.quality in setOf(DepthNodeQuality.FAULT, DepthNodeQuality.OFFLINE, DepthNodeQuality.SETTLING) ||
                        channel.flags and DepthNodeProtocol.Flag.OVERRUN.mask != 0 || channel.uMeanMv == null) badSample = true
                    else accumulator.add(channel.uMeanMv, channel.lastHeardMs, channel.seq)
                }
            }
        }
        val current = runtime.channel(row)
        check(!badSample && current.isOnline && runtime.nowMs() - current.lastHeardMs <= DepthNodeRuntime.offlineTimeout(current)) {
            "采集中出现故障、稳定等待或数据缺口，请重采"
        }
        val result = accumulator.result()
        val expected = (DepthCalibrationFit.CAPTURE_DURATION_MS / current.reportMs.coerceAtLeast(10)).toInt()
        check(result.count >= maxOf(2, (expected * 0.7).toInt())) { "有效采样不足，可能存在丢帧，请重采" }
        if (current.fitValid) check(abs(DepthNodeCalib.derivative(result.meanMv, current)) * result.stdMv <= DepthCalibrationFit.STABILITY_STD_MM) {
            "读数不稳：标准差折算超过0.3mm，请放稳挡块后重采"
        }
        return result
    }
}
