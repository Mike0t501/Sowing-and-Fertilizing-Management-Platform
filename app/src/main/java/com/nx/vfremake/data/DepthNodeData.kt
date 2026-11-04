/**
 * @author NIANXI
 * @date 2026-11-04
 * @brief 检测节点独立状态。原子快照是后台真源，LiveData 仅供界面投影。
 */
package com.nx.vfremake.data

import com.nx.vfremake.funClass.DepthNodeProtocol as P
import java.util.concurrent.atomic.AtomicReference

/** 质量按优先级排列；0 深度合法，只有 OK 才提供可用深度。 */
enum class DepthNodeQuality { OFFLINE, FAULT, OUT_OF_RANGE, SETTLING, UNCALIBRATED, OK }
enum class DepthDisplayMode { HALF_CM, ONE_MM }
enum class DepthEventType { SHALLOW, DEEP, LIFT_OFF, UNSTABLE }
data class DepthQualityStatus(val events: Set<DepthEventType> = emptySet(), val windowReady: Boolean = false,
    val passRate: Float? = null, val coefficientOfVariation: Float? = null, val distanceWindow: Boolean = false)

/** ICD §7.2 sub0–8；默认值是协议默认，真实配置必须以 Read 回读为准。 */
data class DepthNodeFilterParams(
    val rankN: Int = 9, val rankPct: Int = 50, val smoother: Int = 2, val maN: Int = 10,
    val emaTauS: Float = 0.15f, val lpfFcHz: Float = 2f, val kfQ: Float = 100f,
    val kfR: Float = 4f, val kfGate: Float = 3f
) {
    /** 顺序与四字节位型都属于接口契约，不能按字段文本计算 CRC。 */
    fun wireWords(): List<Long> = listOf(rankN.toLong(), rankPct.toLong(), smoother.toLong(), maN.toLong(),
        P.floatWord(emaTauS), P.floatWord(lpfFcHz), P.floatWord(kfQ), P.floatWord(kfR), P.floatWord(kfGate))

    /** UI 与导入校验共用参数范围。 */
    fun isValid(): Boolean = rankN in 1..31 && rankPct in 0..100 && smoother in 0..4 && maN in 1..64 &&
        emaTauS.isFinite() && emaTauS in 0.01f..5f && lpfFcHz.isFinite() && lpfFcHz in 0.1f..20f &&
        kfQ.isFinite() && kfQ in 0.001f..1e6f && kfR.isFinite() && kfR in 0.001f..1e6f &&
        kfGate.isFinite() && kfGate in 0f..10f

    val crc: Int get() = P.filterCrc(wireWords())

    companion object {
        /** 回读不完整或值非法时返回 null，绝不以协议默认值冒充当前节点配置。 */
        fun fromWords(words: List<Long>): DepthNodeFilterParams? {
            if (words.size != 9 || words.take(4).any { it !in 0..255 }) return null
            return DepthNodeFilterParams(words[0].toInt(), words[1].toInt(), words[2].toInt(), words[3].toInt(),
                P.wordFloat(words[4]), P.wordFloat(words[5]), P.wordFloat(words[6]), P.wordFloat(words[7]),
                P.wordFloat(words[8])).takeIf { it.isValid() }
        }
    }
}

/** row=-1 仅用于出厂节点诊断，不能进入作业实测接口。 */
data class DepthNodeChannel(
    val row: Int,
    val baseId: Int = if (row == -1) P.FACTORY_BASE else P.baseForRow(row),
    val calibrationPoints: List<Pair<Float, Float>> = emptyList(),
    val calibrationStdMv: List<Float> = emptyList(),
    val fitOrder: Int = 2,
    val c0: Double = 0.0, val c1: Double = 0.0, val c2: Double = 0.0,
    val fitValid: Boolean = false, val fitMaxResidualMm: Float = 0f,
    val calibratedAt: String = "", val offsetTrimMm: Float = 0f, val zeroCheckedAt: String = "",
    val stopLoMv: Int = 0, val stopHiMv: Int = 0,
    // 以下字段均为运行时，不落盘；未曾收到 STATUS 与 elapsed=0 必须能区分。
    val isOnline: Boolean = false, val hasStatus: Boolean = false, val lastHeardMs: Long = 0,
    val seq: Int? = null, val lostFrames: Long = 0, val receivedFrames: Long = 0,
    val lossRate10s: Float = 0f,
    val uFiltMv: Float? = null, val uMeanMv: Float? = null, val spanMv: Float? = null,
    val flags: Int = 0, val cfgCrc: Int? = null,
    val depthMm: Float? = null, val depthRawMm: Float? = null, val spanMm: Float? = null,
    val quality: DepthNodeQuality = DepthNodeQuality.OFFLINE,
    val monitoring: DepthQualityStatus = DepthQualityStatus(),
    val icdVer: Int? = null, val fwRev: Int? = null, val uptimeS: Int? = null,
    val vddaMv: Int? = null, val mcuTempC: Int? = null, val txDrop: Int? = null,
    val lastInfoMs: Long = 0, val researchFps: Float = 0f,
    val bootCount: Int = 0,
    val reportMs: Int = 50, val parameterWords: Map<Int, Long> = emptyMap(),
    val filterParams: DepthNodeFilterParams? = null, val configReadAtMs: Long? = null,
    val configError: String? = null
) {
    val versionCompatible: Boolean get() = icdVer == null || icdVer ushr 4 == P.ICD_VERSION ushr 4
}

data class DepthNodeState(
    val channels: List<DepthNodeChannel> = List(8) { DepthNodeChannel(it) },
    val factory: DepthNodeChannel = DepthNodeChannel(-1),
    val displayMode: DepthDisplayMode = DepthDisplayMode.HALF_CM,
    val qualityConfig: DepthQualityConfig = DepthQualityConfig(),
    val simulated: Boolean = false
)

/** 多写入者用 CAS，防止高频 STATUS 覆盖同时保存的标定。transform 必须无副作用。 */
class DepthNodeStateStore(initial: DepthNodeState = DepthNodeState()) {
    private val reference = AtomicReference(initial)

    /** 后台读取唯一真源，避免依赖主线程尚未刷新的 LiveData。 */
    fun current(): DepthNodeState = reference.get()

    /** 更新完整状态；CAS 重试时绝不能发送串口、写文件或累计计数。 */
    fun update(transform: (DepthNodeState) -> DepthNodeState) {
        while (true) {
            val old = reference.get(); val next = transform(old)
            if (old === next || reference.compareAndSet(old, next)) return
        }
    }

    /** 普通行与出厂诊断使用相同的原子更新入口。 */
    fun updateChannel(row: Int, transform: (DepthNodeChannel) -> DepthNodeChannel) {
        require(row in -1..7)
        update { state ->
            if (row == -1) state.copy(factory = transform(state.factory))
            else state.copy(channels = state.channels.toMutableList().also { it[row] = transform(it[row]) })
        }
    }
}
