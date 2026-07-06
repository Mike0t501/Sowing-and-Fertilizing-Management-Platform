package com.nx.vfremake.data

/**
 * 一键播种深度性能测试配置
 *
 * 由 DepthTestScreen 收集参数后一次性快照传入 DepthTestCoroutine，
 * 测试过程中不再读 UI 状态，保证参数一致性。
 *
 * 测试流程：先运行到 startDepthMm，再以 stepMm 为梯度逐级运行至 endDepthMm，
 * 然后按同样梯度逐级返回 startDepthMm；每级到位（深度容差判定）后停留 dwellMs。
 *
 * @param motorIndex     被测电机 0~7（单选，须在线且已完成限位标定与深度拟合）
 * @param startDepthMm   起始深度 mm（默认 20）
 * @param endDepthMm     终点深度 mm（默认 80）
 * @param stepMm         深度梯度 mm（默认 20，即 20→40→60→80→60→40→20）
 * @param dwellMs        每级到位后停留时长 ms（默认 2000）
 * @param toleranceMm    到位深度容差 mm（|当前深度-目标深度| ≤ 容差视为到位）
 * @param stableSamples  连续 N 个采样在容差内才判定稳定到位（N×100ms）
 * @param timeoutMs      单级到位超时 ms；超时记异常并继续下一级
 * @param positionSpeed  位置运动速度 RPM（写入全局运动参数，由 Phase 4 下发 0x6081）
 * @param acceleration   加减速度 RPM/s（写入全局运动参数，由 Phase 4 下发 0x6083/0x6084）
 */
data class DepthTestConfig(
    val motorIndex: Int,
    val startDepthMm: Float = 20f,
    val endDepthMm: Float = 80f,
    val stepMm: Float = 20f,
    val dwellMs: Long = 2000L,
    val toleranceMm: Float = 0.5f,
    val stableSamples: Int = 3,
    val timeoutMs: Long = 15000L,
    val positionSpeed: Int,
    val acceleration: Int
)

/**
 * 性能测试实时状态（DepthTestCoroutine postValue → DepthTestScreen observe）
 *
 * @param running    测试是否正在进行
 * @param finished   测试是否已正常完成（与中止/失败区分）
 * @param motorIndex 被测电机 0~7；-1 表示尚未运行过
 * @param stageIndex 当前级序号（从 1 计）
 * @param stageCount 总级数
 * @param stageLabel 当前阶段标签（"起始定位 20.0" / "运行 20.0→40.0" / "停留 40.0" / "超时" / "中止" / "完成"）
 * @param targetDepth 当前级目标深度 mm
 * @param message    完成摘要或错误原因（预检失败/使能超时/电机离线等）
 * @param anomalies  测试过程中累积的异常（如 "第3级(60.0mm)超时"）
 */
data class DepthTestStatus(
    val running: Boolean,
    val finished: Boolean = false,
    val motorIndex: Int = -1,
    val stageIndex: Int = 0,
    val stageCount: Int = 0,
    val stageLabel: String = "",
    val targetDepth: Float = 0f,
    val message: String = "",
    val anomalies: List<String> = emptyList()
)
