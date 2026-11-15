/** @author NIANXI @date 2026-11-04 @brief 节点滤波预设、研究流互斥与带宽预算。 */
package com.nx.vfremake.funClass

import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.DepthNodeProtocol as P
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import kotlin.math.max

object DepthNodePresets {
    enum class Preset(val label: String) {
        DIRECT("直出（仅抗混叠）"), MEDIAN_MA("中值 + 均值"), MEDIAN_EMA("中值 + EMA"),
        LOW_EMA("低分位 + EMA（偏浅）"), LPF2("二阶低通"), KALMAN("卡尔曼")
    }

    /** 每个预设都从ICD全部默认值开始，只覆盖表中指定项，不能残留上一预设参数。 */
    fun parameters(preset: Preset, channel: DepthNodeChannel): DepthNodeFilterParams {
        val defaults = DepthNodeFilterParams()
        return when (preset) {
            Preset.DIRECT -> defaults.copy(rankN = 1, smoother = 0)
            Preset.MEDIAN_MA -> defaults.copy(smoother = 1)
            Preset.MEDIAN_EMA -> defaults
            Preset.LOW_EMA -> {
                require(channel.fitValid) { "未标定时只允许50%电压分位，无法选择偏浅侧" }
                val at = channel.uMeanMv ?: channel.calibrationPoints.map { it.first }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 2500f
                val slope = DepthNodeCalib.derivative(at, channel)
                require(slope.isFinite() && slope != 0.0) { "标定斜率无效" }
                defaults.copy(rankN = 15, rankPct = if (slope < 0) 75 else 25, emaTauS = 0.10f)
            }
            Preset.LPF2 -> defaults.copy(rankN = 1, smoother = 3)
            Preset.KALMAN -> defaults.copy(rankN = 1, smoother = 4)
        }
    }

    /** 不只凭8位CRC猜预设，避免碰撞；必须比较完整的9项回读值。 */
    fun currentName(channel: DepthNodeChannel): String {
        val actual = channel.filterParams ?: return "尚未回读"
        return Preset.values().firstOrNull { runCatching { parameters(it, channel).wireWords() == actual.wireWords() }.getOrDefault(false) }
            ?.label ?: "自定义/方向未确认"
    }
}

/** 仅观察真实CAN到达时间，不改伺服PDO周期或控制行为。 */
class DepthBusTrafficMeter {
    private data class Arrival(val canId: Int, val at: Long)
    private val arrivals = ArrayDeque<Arrival>()

    /** 接收线程调用，窗口有上界，长时间作业不会积累日志。 */
    @Synchronized fun record(canId: Int, atMs: Long) {
        arrivals.addLast(Arrival(canId, atMs)); trim(atMs)
    }

    data class Snapshot(val rxFps: Double, val servo100MsObserved: Boolean)

    /** 1000Hz研究流需要实际观察到每个在线伺服约100ms的TPDO，而非写入一个假设值。 */
    @Synchronized fun snapshot(nowMs: Long, servoIds: List<Int>): Snapshot {
        trim(nowMs)
        val verified = servoIds.all { id ->
            val frames = arrivals.filter { it.canId == 0x180 + id }
            frames.size >= 5 && (frames.last().at - frames.first().at).toDouble() / (frames.size - 1) in 80.0..120.0 &&
                nowMs - frames.last().at <= 250
        }
        return Snapshot(arrivals.size / 2.0, verified)
    }

    private fun trim(now: Long) {
        while (arrivals.isNotEmpty() && (arrivals.first.at < now - 2000 || arrivals.size > 5000)) arrivals.removeFirst()
    }
}

object DepthResearchBudget {
    data class Budget(val onlineNodes: Int, val baselineFps: Double, val researchFps: Double,
        val totalFps: Double, val uartPercent: Double, val canPercent: Double, val blockedReason: String?)

    /** 按ICD§8保守假定桥转发全部研究帧；弹窗确认不能绕过500帧/s和8行限制。 */
    fun calculate(channels: List<DepthNodeChannel>, rate: Int, observedRxFps: Double,
        observedTxFps: Double, servo100MsObserved: Boolean, replacingResearchFps: Double = 0.0): Budget {
        require(rate in 1..4)
        val online = channels.filter { it.isOnline }
        val researchFps = when (rate) { 1 -> 1000.0; 2 -> 500.0; 3 -> 250.0; else -> 100.0 } / 3
        val baseline = max(online.sumOf { 1000.0 / it.reportMs.coerceAtLeast(10) + 1 },
            (observedRxFps - replacingResearchFps.coerceAtLeast(0.0)).coerceAtLeast(0.0)) + observedTxFps.coerceAtLeast(0.0)
        val total = baseline + researchFps
        val blocked = when {
            online.isEmpty() -> "没有在线检测节点"
            online.size >= 8 -> "8行同时在线时禁止开启研究流"
            online.any { !it.parameterWords.containsKey(9) } -> "请先回读所有在线节点报告周期"
            rate == 1 && online.size != 1 -> "1000Hz研究流只允许单检测节点在线"
            rate == 1 && !servo100MsObserved -> "尚未观察到在线伺服TPDO约100ms，请先核验或断开伺服"
            total > 500 -> "预计长期流量超过500帧/s，请降低研究速率或总线流量"
            else -> null
        }
        return Budget(online.size, baseline, researchFps, total, total * 14 * 10 / 115200 * 100,
            total * 0.00025 * 100, blocked)
    }
}

/** 多个调用方也只能通过这一研究流事务开启；设备外部工具的并发操作仍需现场协调。 */
class DepthNodeManagement(private val runtime: DepthNodeRuntime, private val client: DepthNodeCfgClient) {
    private val researchMutex = Mutex()

    /** 先写超时再开启；回读确认后缓存参数，防止第二次操作抢在STATUS更新前开另一路。 */
    suspend fun enableResearch(base: Int, rate: Int, timeoutS: Int, budget: DepthResearchBudget.Budget) = researchMutex.withLock {
        require(rate in 1..4 && timeoutS in 0..3600)
        check(budget.blockedReason == null) { budget.blockedReason ?: "带宽条件不满足" }
        val state = runtime.store.current()
        val nodes = state.channels + listOf(state.factory).filter { it.isOnline }
        check(nodes.count { it.isOnline } < 8) { "8行在线时研究流必须关闭" }
        check(nodes.count { it.isOnline } == budget.onlineNodes) { "在线节点数已改变，请重新核对带宽" }
        check(rate != 1 || nodes.count { it.isOnline } == 1) { "1000Hz只允许单检测节点" }
        val expectedFps = when (rate) { 1 -> 1000.0; 2 -> 500.0; 3 -> 250.0; else -> 100.0 } / 3
        check(budget.researchFps == expectedFps) { "研究流速率与带宽预算不一致" }
        check(nodes.none { it.baseId != base && (it.flags and P.Flag.RESEARCH.mask != 0 ||
            (it.parameterWords[10] ?: 0) > 0 && (it.configReadAtMs ?: Long.MIN_VALUE) >= it.lastHeardMs) }) {
            "另一个节点的研究流尚未关闭，请先关闭再切换"
        }
        client.write(base, 11, timeoutS.toLong())
        check(client.read(base, sub = 11) == timeoutS.toLong()) { "研究流超时参数回读不一致" }
        client.write(base, 10, rate.toLong())
        check(client.read(base, sub = 10) == rate.toLong()) { "研究流速率未生效" }
        client.readAll(base)
    }

    /** 关闭不需要带宽预算，异常状态也应尽力允许停止发送。 */
    suspend fun disableResearch(base: Int) = researchMutex.withLock {
        client.write(base, 10, 0)
        check(client.read(base, sub = 10) == 0L) { "研究流关闭未确认" }
        client.readAll(base)
    }

    /** 第8个节点上线时也要执行ICD限制，不能只在“开启”按钮处检查一次。 */
    suspend fun enforceEightRowLimit(): Boolean = researchMutex.withLock {
        val state = runtime.store.current()
        val nodes = state.channels + listOf(state.factory).filter { it.isOnline }
        if (nodes.count { it.isOnline } < 8) return@withLock false
        val active = nodes.filter { it.isOnline && it.flags and P.Flag.RESEARCH.mask != 0 }
        for (node in active) {
            client.write(node.baseId, 10, 0)
            check(client.read(node.baseId, sub = 10) == 0L) { "8行在线研究流关闭未确认" }
            runtime.store.updateChannel(node.row) { it.copy(parameterWords = it.parameterWords + (10 to 0L)) }
        }
        active.isNotEmpty()
    }
}
