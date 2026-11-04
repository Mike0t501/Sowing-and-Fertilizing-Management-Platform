/** @author NIANXI @date 2026-11-04 @brief 真实与模拟共用的节点接收处理器。 */
package com.nx.vfremake.funClass

import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.DepthNodeProtocol as P
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

/** 接收线程不等待主线程；所有运行时计数在此串行维护，CAS 内只做纯状态转换。 */
class DepthNodeRuntime(
    val store: DepthNodeStateStore,
    val waiters: DepthNodeCfgWaiters = DepthNodeCfgWaiters(),
    val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Arrival(val time: Long, val lost: Int)
    private val arrivals = Array(9) { ArrayDeque<Arrival>() }
    private val research = Array(9) { ArrayDeque<Long>() }
    private val mutableSamples = MutableSharedFlow<DepthNodeChannel>(extraBufferCapacity = 256)
    val samples = mutableSamples.asSharedFlow()

    /** 接收的每帧先严格校验 DLC；出厂节点保持独立诊断，禁止提供作业深度。 */
    @Synchronized
    fun onDepthNodeFrame(canId: Int, data: ByteArray) {
        val base = P.baseOf(canId) ?: return
        if (data.size != 8) return
        val row = P.rowOf(base) ?: -1
        val slot = if (row == -1) 8 else row
        val now = nowMs()
        when (canId - base) {
            P.CFG_RSP -> waiters.tryComplete(canId, data)
            P.STATUS -> {
                val status = P.parseStatus(data) ?: return
                val old = channel(row)
                if (!old.versionCompatible) return
                val freshSequence = !old.hasStatus || now - old.lastHeardMs > offlineTimeout(old)
                val delta = old.seq?.let { (status.seq - it) and 255 }
                if (!freshSequence && delta == 0) return // 桥回显或重复帧不能算第二个样本。
                val lost = if (freshSequence || delta == null) 0 else delta - 1
                arrivals[slot].addLast(Arrival(now, lost))
                trim(arrivals[slot], now - 10_000)
                val count = arrivals[slot].size
                val missing = arrivals[slot].sumOf { it.lost }
                store.updateChannel(row) { current -> refresh(current.copy(
                    hasStatus = true, lastHeardMs = now, seq = status.seq,
                    receivedFrames = current.receivedFrames + 1, lostFrames = current.lostFrames + lost,
                    lossRate10s = if (count + missing == 0) 0f else missing.toFloat() / (count + missing),
                    uFiltMv = status.uFiltMv, uMeanMv = status.uMeanMv, spanMv = status.spanMv,
                    cfgCrc = status.cfgCrc, flags = status.flags
                ), now) }
                mutableSamples.tryEmit(channel(row))
            }
            P.INFO -> {
                val info = P.parseInfo(data) ?: return
                val old = channel(row)
                val uptimeDelta = old.uptimeS?.let { (info.uptimeS - it) and 0xFFFF }
                val reset = old.uptimeS != null && info.uptimeS < old.uptimeS &&
                    uptimeDelta!! > max(5L, (now - old.lastInfoMs) / 1000 + 3)
                if (reset) { arrivals[slot].clear(); research[slot].clear() }
                store.updateChannel(row) { current -> refresh(current.copy(
                    icdVer = info.icdVer, fwRev = info.fwRev, uptimeS = info.uptimeS,
                    vddaMv = info.vddaMv, mcuTempC = info.mcuTempC, txDrop = info.txDrop, lastInfoMs = now,
                    seq = if (reset) null else current.seq,
                    hasStatus = if (reset) false else current.hasStatus,
                    parameterWords = if (reset) emptyMap() else current.parameterWords,
                    filterParams = if (reset) null else current.filterParams
                ), now) }
            }
            P.RESEARCH -> {
                research[slot].addLast(now)
                while (research[slot].size > 2000) research[slot].removeFirst()
            }
            // CFG_REQ 回显、B+5..7 预留帧仅吸收；不解析研究样本。
        }
    }

    /** 50ms 看门狗与研究流计数；INFO/CFG 再活跃也不能延长 STATUS 在线时间。 */
    @Synchronized
    fun tick() {
        val now = nowMs()
        for (slot in 0..8) {
            while (research[slot].isNotEmpty() && research[slot].first < now - 1000) research[slot].removeFirst()
            val fps = research[slot].size.toFloat()
            val row = if (slot == 8) -1 else slot
            val old = channel(row)
            val updated = refresh(old.copy(researchFps = fps), now)
            if (updated != old) store.updateChannel(row) { refresh(it.copy(researchFps = fps), now) }
        }
    }

    /** 会话切换时清除计数窗口，真实/模拟不能共享序号基线。 */
    @Synchronized
    fun resetTracking() {
        arrivals.forEach { it.clear() }; research.forEach { it.clear() }
    }

    /** 读取行快照，-1 表示未配号节点。 */
    fun channel(row: Int): DepthNodeChannel = store.current().let { if (row == -1) it.factory else it.channels[row] }

    companion object {
        /** 以实际报告周期计算超时，不能用位置不变化推断离线。 */
        fun offlineTimeout(channel: DepthNodeChannel): Long = max(1000L, channel.reportMs * 3L)

        /** 质量与数值必须在同一快照下计算；所有消费者共享此判定。 */
        fun refresh(channel: DepthNodeChannel, now: Long): DepthNodeChannel {
            val online = channel.hasStatus && now - channel.lastHeardMs <= offlineTimeout(channel)
            val flags = channel.flags
            val quality = when {
                !online -> DepthNodeQuality.OFFLINE
                !channel.versionCompatible || flags and 3 != 0 || channel.uFiltMv == null || channel.uMeanMv == null -> DepthNodeQuality.FAULT
                flags and (P.Flag.STOP_LO.mask or P.Flag.STOP_HI.mask) != 0 -> DepthNodeQuality.OUT_OF_RANGE
                flags and P.Flag.SETTLING.mask != 0 -> DepthNodeQuality.SETTLING
                !channel.fitValid || channel.row == -1 -> DepthNodeQuality.UNCALIBRATED
                else -> DepthNodeQuality.OK
            }
            val filtered = if (quality == DepthNodeQuality.OK) DepthNodeCalib.depthFromMv(channel.uFiltMv!!, channel) else null
            val raw = if (quality == DepthNodeQuality.OK) DepthNodeCalib.depthFromMv(channel.uMeanMv!!, channel) else null
            val span = if (quality == DepthNodeQuality.OK) channel.spanMv?.let {
                (abs(DepthNodeCalib.derivative(channel.uMeanMv!!, channel)) * it).toFloat().takeIf { v -> v.isFinite() }
            } else null
            val safeQuality = if (quality == DepthNodeQuality.OK && (filtered == null || raw == null || span == null))
                DepthNodeQuality.FAULT else quality
            return channel.copy(isOnline = online, quality = safeQuality,
                depthMm = if (safeQuality == DepthNodeQuality.OK) filtered else null,
                depthRawMm = if (safeQuality == DepthNodeQuality.OK) raw else null,
                spanMm = if (safeQuality == DepthNodeQuality.OK) span else null)
        }

        private fun trim(queue: ArrayDeque<Arrival>, before: Long) {
            while (queue.isNotEmpty() && (queue.first.time < before || queue.size > 4096)) queue.removeFirst()
        }
    }
}
