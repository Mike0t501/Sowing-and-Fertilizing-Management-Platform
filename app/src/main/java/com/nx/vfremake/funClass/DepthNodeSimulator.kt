/** @author NIANXI @date 2026-11-04 @brief 帧级功能模拟；合成数据不代表真实滤波性能。 */
package com.nx.vfremake.funClass

import com.nx.vfremake.funClass.DepthNodeProtocol as P
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 独立 RAM/Flash 和 CFG 对端；所有应答仍经真实节点解析器。 */
class DepthNodeSimulator(
    private val emit: (P.Frame) -> Unit,
    private val nowMs: () -> Long,
    initialRows: IntRange = 0..7,
    private val directOnly: Boolean = false
) {
    private data class Node(
        var base: Int, val words: MutableMap<Int, Long>, var saved: Map<Int, Long>,
        var seq: Int = 0, var dirty: Boolean = false,
        var lastStatus: Long = Long.MIN_VALUE / 2, var lastInfo: Long = Long.MIN_VALUE / 2,
        var researchStart: Long = 0, var researchIdx: Int = 0, var researchCarry: Double = 0.0,
        var lastTick: Long = 0, var heldDepth: Float? = null, val noise: Random = Random(42)
    )
    private val startMs = nowMs()
    private val nodes = linkedMapOf<Int, Node>()
    init { initialRows.forEach { add(P.baseForRow(it)) } }

    private fun add(base: Int) {
        val filters = if (directOnly) P.DIRECT_FILTER_WORDS else P.DEFAULT_FILTER_WORDS
        val words = filters.mapIndexed { sub, word -> sub to word }.toMap().toMutableMap()
        words.putAll(mapOf(9 to 50L, 10 to 0L, 11 to 600L, 12 to 0L, 13 to 0L,
            14 to 100L, 15 to 4800L, 16 to P.floatWord(1f), 17 to P.floatWord(0f)))
        nodes[base] = Node(base, words, words.toMap(), noise = Random(base), lastTick = nowMs())
    }

    /** 逐个接入出厂模拟设备；不允许两个未配号节点同时存在。 */
    @Synchronized fun addFactoryNode() {
        check(P.FACTORY_BASE !in nodes) { "已有一个未配号模拟节点" }
        add(P.FACTORY_BASE)
    }

    /** 仅测试模式使用，用于离线和空闲行配号验证。 */
    @Synchronized fun disconnect(row: Int) { nodes.remove(P.baseForRow(row)) }

    /** 独立挡块真值，不通过待检验的拟合曲线反算电压。 */
    @Synchronized fun holdDepth(row: Int, depthMm: Float?) {
        require(depthMm == null || depthMm.isFinite() && depthMm in 0f..100f)
        checkNotNull(nodes[P.baseForRow(row)]) { "模拟节点未连接" }.heldDepth = depthMm
    }

    /** 模拟完整 CFG 功能；directOnly 用于覆盖现有精简固件拒绝其他滤波配置的行为。 */
    @Synchronized fun command(frame: P.Frame) {
        val node = nodes.values.firstOrNull { it.base + 1 == frame.canId } ?: return
        if (frame.data.size != 8) return
        val request = P.parseCfgReply(frame.data) ?: return
        val index = request.index; val sub = request.subIndex; val value = request.value
        when (request.cmd) {
            P.READ -> {
                val word = read(node, index, sub)
                emit(P.Frame(node.base, P.cfgData(if (word == null) P.NOT_FOUND else P.READ, index, sub, word ?: 0)))
            }
            P.WRITE -> {
                if (runCatching { DepthNodeCfgClient.validate(index, sub, value) }.isFailure || read(node, index, sub) == null) return
                if (index == 0) {
                    if (sub == 0 && value.toInt() != node.base) {
                        if (value.toInt() in nodes) return
                        nodes.remove(node.base); node.base = value.toInt(); nodes[node.base] = node
                    }
                } else {
                    if (directOnly && sub < 9 && node.words[sub] != value) return
                    node.words[sub] = value
                    if (sub == 10) { node.researchStart = nowMs(); node.researchIdx = 0; node.researchCarry = 0.0 }
                }
                node.dirty = true
                emit(P.Frame(node.base, P.cfgData(P.WRITE, index, sub, read(node, index, sub)!!)))
            }
            P.BURN -> if (index == 0x0301 && sub == 8) {
                node.saved = node.words.toMap(); node.dirty = false
                emit(P.Frame(node.base, P.cfgData(P.BURN, index, sub, 1)))
            }
            P.VERSION -> emit(P.Frame(node.base, byteArrayOf(31, 0, 0, 0, 0, 5, 0, 8)))
        }
    }

    /** 每10ms调度，默认STATUS 20Hz、INFO 1Hz；真实采样与滤波性能由固件负责。 */
    @Synchronized fun tick(targetDepthsMm: List<Float>) {
        val now = nowMs()
        for (node in nodes.values.toList()) {
            val row = P.rowOf(node.base)
            val elapsedS = (now - startMs) / 1000.0
            val timeout = node.words.getValue(11)
            if (node.words.getValue(10) > 0 && timeout > 0 && now - node.researchStart >= timeout * 1000) node.words[10] = 0
            val held = node.heldDepth
            val lift = held == null && elapsedS >= 60 && elapsedS % 60 < 1
            val depth = held ?: if (lift) 3f else (targetDepthsMm.getOrNull(row ?: -1) ?: 40f) +
                (sin(2 * PI * elapsedS * 0.2 + (row ?: 0)) * 1.5).toFloat()
            // 固定独立关系：500mV对应0mm，4500mV对应100mm。
            val filtered = 500f + depth * 40f + (node.noise.nextFloat() - 0.5f) * if (held == null) 4f else 1f
            val spike = held == null && !lift && ((now - startMs) / 50 + (row ?: 0) * 7) % 173 == 0L
            val mean = filtered + if (spike) 240f else 0f
            val lo = node.words.getValue(12); val hi = node.words.getValue(13)
            var flags = if (node.dirty) P.Flag.CFG_DIRTY.mask else 0
            if (mean < node.words.getValue(14) || mean > node.words.getValue(15)) flags = flags or P.Flag.FAULT.mask
            if (filtered >= 4840) flags = flags or P.Flag.SAT.mask
            if (lo > 0 && filtered <= lo) flags = flags or P.Flag.STOP_LO.mask
            if (hi > 0 && filtered >= hi) flags = flags or P.Flag.STOP_HI.mask
            if (node.words.getValue(10) > 0) flags = flags or P.Flag.RESEARCH.mask
            if (now - startMs < 90) flags = flags or P.Flag.SETTLING.mask
            if (now - node.lastStatus >= P.actualReportMs(node.words.getValue(9).toInt())) {
                node.lastStatus = now
                emit(P.Frame(node.base + 2, P.statusData(filtered, mean, if (spike) 240f else 2f,
                    P.filterCrc((0..8).map { node.words.getValue(it) }), flags, node.seq++)))
            }
            if (now - node.lastInfo >= 1000) {
                node.lastInfo = now
                emit(P.Frame(node.base + 3, P.infoData(P.ICD_VERSION, 1, ((now - startMs) / 1000).toInt(), 3300, 35, 0)))
            }
            val hz = when (node.words.getValue(10).toInt()) { 1 -> 1000; 2 -> 500; 3 -> 250; 4 -> 100; else -> 0 }
            node.researchCarry += (now - node.lastTick).coerceIn(0, 100) * hz / 3000.0
            while (node.researchCarry >= 1) {
                emit(P.Frame(node.base + 4, P.researchData(node.researchIdx, listOf(mean, mean, mean))))
                node.researchIdx = (node.researchIdx + 3) and 0xFFFF; node.researchCarry--
            }
            node.lastTick = now
        }
    }

    private fun read(node: Node, index: Int, sub: Int): Long? = when (index) {
        0 -> when (sub) { 0 -> node.base.toLong(); 1 -> 1L; 3 -> 0L; else -> null }
        P.PARAM_INDEX -> node.words[sub]
        else -> null
    }
}
