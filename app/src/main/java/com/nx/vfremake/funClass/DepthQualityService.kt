/** @author NIANXI @date 2026-11-04 @brief 状态机与应用接线；只有样本流驱动统计。 */
package com.nx.vfremake.funClass

import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.DepthNodeProtocol as P
import java.util.ArrayDeque

/** 此服务不发送CAN；周期tick只负责失效，不会把缓存读数再次计入统计。 */
class DepthQualityService(
    private val store: DepthNodeStateStore,
    private val measured: MeasuredDepthFacade,
    private val nowMs: () -> Long,
    private val epochMs: () -> Long,
    private val targetMm: (Int) -> Float,
    private val speedKmh: () -> Double?
) {
    private var monitors = Array(8) { DepthQualityMonitor(it) }
    private val lastSeq = arrayOfNulls<Int>(8)
    private val lastAt = arrayOfNulls<Long>(8)
    private var recording: RecordingSession? = null

    /** 入参是到达时的完整通道快照，不能用后来另一帧电压配上旧时间戳。 */
    @Synchronized fun accept(channel: DepthNodeChannel) {
        val row = channel.row
        if (row !in 0..7) return
        val sample = measured.forRow(row, channel)
        val previousSeq = lastSeq[row]
        val previousTime = lastAt[row]
        val continuous = previousSeq == null || ((channel.seq ?: 0) - previousSeq) and 255 == 1 &&
            previousTime != null && channel.lastHeardMs - previousTime <= DepthNodeRuntime.offlineTimeout(channel)
        lastSeq[row] = channel.seq; lastAt[row] = channel.lastHeardMs
        val atEpoch = epochMs() - (nowMs() - channel.lastHeardMs).coerceAtLeast(0)
        val input = DepthQualityMonitor.Input(channel.lastHeardMs, atEpoch, sample, targetMm(row), speedKmh(),
            trustedShallowStop(channel, sample), continuous)
        publish(row, monitors[row].update(input, store.current().qualityConfig))
    }

    /** 无STATUS时也结束离线事件；不增加样本、持续距离或合格率。 */
    @Synchronized fun tick() {
        for (row in 0..7) {
            val channel = store.current().channels[row]
            val sample = measured.forRow(row, channel)
            if (sample.quality != DepthNodeQuality.OK && !trustedShallowStop(channel, sample)) {
                publish(row, monitors[row].invalidate("${sample.quality}"))
            }
        }
    }

    /** 真实与模拟会话切换不共享监测窗口和序号基线。 */
    @Synchronized fun reset() {
        monitors.forEachIndexed { row, monitor -> publish(row, monitor.invalidate("SESSION_CHANGE")) }
        monitors = Array(8) { DepthQualityMonitor(it) }
        lastSeq.fill(null); lastAt.fill(null)
    }

    private fun publish(row: Int, update: DepthQualityMonitor.Update) {
        update.completed.forEach { recording?.add(it) }
        if (store.current().channels[row].monitoring != update.status)
            store.updateChannel(row) { it.copy(monitoring = update.status) }
    }

    /** 记录开始前已经持续的事件保留真实起点，elapsed可为负，不能伪造新事件起点。 */
    @Synchronized fun beginRecording(rows: List<Int>, startedMs: Long): RecordingSession {
        check(recording == null) { "已有质量事件记录会话" }
        return RecordingSession(rows.toSet(), startedMs).also { recording = it }
    }

    /** 结算活动事件快照，但不停止界面监测；文件收尾由记录器完成。 */
    @Synchronized fun endRecording(session: RecordingSession) {
        if (recording !== session) return
        for (row in session.rows) monitors[row].activeEvents().forEach { session.add(it) }
        recording = null
    }

    class RecordingSession internal constructor(val rows: Set<Int>, val startedMs: Long) {
        private val events = ArrayDeque<DepthQualityEvent>()
        private var overflow = false
        @Synchronized internal fun add(event: DepthQualityEvent) {
            if (event.row !in rows || event.endMs < startedMs) return
            if (events.size >= 10000) { overflow = true; return }
            events.addLast(event)
        }
        /** 批量取出已结束事件，事件数有界；溢出明确报错，不能静默丢事件。 */
        @Synchronized fun drain(): List<DepthQualityEvent> {
            check(!overflow) { "事件队列溢出，记录不完整" }
            return events.toList().also { events.clear() }
        }
    }

    companion object {
        /** STOP只有方向、标定和协议都可信时才能作为腾空例外；仍不提供深度数值。 */
        fun trustedShallowStop(channel: DepthNodeChannel, sample: MeasuredDepthSample): Boolean {
            if (sample.quality != DepthNodeQuality.OUT_OF_RANGE || !channel.fitValid || !channel.versionCompatible ||
                channel.flags and (P.Flag.FAULT.mask or P.Flag.SAT.mask or P.Flag.SETTLING.mask) != 0) return false
            val slope = channel.uMeanMv?.let { DepthNodeCalib.derivative(it, channel) } ?: return false
            val low = channel.flags and P.Flag.STOP_LO.mask != 0
            val high = channel.flags and P.Flag.STOP_HI.mask != 0
            return slope.isFinite() && low != high && if (slope > 0) low else if (slope < 0) high else false
        }
    }
}
