package com.nx.vfremake.data

/**
 * 单个伺服电机的配置与运行时状态
 *
 * 设计说明：
 *   - 持久化字段（nodeId / limit / calibration / fit）由 MySharedPreFun 读写
 *   - 运行时字段（currentPosition / isOnline 等）仅在内存中维护，不落盘
 *   - 数据类使用 val，通过 copy() 更新，配合 LiveData 实现 Compose 响应式刷新
 *
 * @param motorIndex  电机编号 0~7（数组下标，对应界面第 1~8 路）
 * @param nodeId      CAN Node-ID，用户可在设置页配置，默认 11+motorIndex（即 11~18）
 */
data class ServoCalibration(
    val motorIndex: Int,
    val nodeId: Int = 11 + motorIndex,

    // ── 限位（编码器脉冲数，有符号 32 位整数）────────────────────────────
    // 注：limitMin 对应最浅深度（上位），limitMax 对应最深深度（下位）
    // 方向取决于丝杆安装方向，标定时以实际测量为准
    val limitMin: Int = 0,
    val limitMax: Int = 0,
    val limitsSet: Boolean = false,       // 限位是否已完成标定

    // ── 5 个校准点 [编码器位置(脉冲), 实际深度(mm)] ─────────────────────
    // 由校准向导顺序写入，列表长度 0~5
    val calibrationPoints: List<Pair<Int, Float>> = emptyList(),

    // ── 线性拟合系数 depth_mm = fitA * encoderPos + fitB ────────────────
    val fitA: Float = 0f,
    val fitB: Float = 0f,
    val fitValid: Boolean = false,        // 拟合是否有效（至少需要 2 个校准点）

    // ── 标定模式与间接测量点（持久化）──────────────────────────────────
    val calibrationMode: CalibrationMode = CalibrationMode.DIRECT,
    val indirectPoints: List<IndirectCalibPoint> = DEFAULT_INDIRECT_POINTS,

    // ── 运行时状态（不持久化，由协程更新）──────────────────────────────
    val currentPosition: Int = 0,         // 当前编码器计数值（从 TPDO1 / SDO 读取）
    val targetDepth: Float = 0f,          // 当前目标深度 mm（由控制协程写入）
    val currentDepth: Float = 0f,         // 当前实际深度 mm（由 fitA/fitB 换算）
    val isEnabled: Boolean = false,       // 驱动器是否已使能
    val isOnline: Boolean = false,        // 是否在线（最近收到心跳或 TPDO）
    val alarmCode: Int = 0,               // 报警代码，0 = 正常
    val lastHeardMs: Long = 0L            // CanReceiveCoroutine 最后收到该电机任意 CAN 帧的时间戳（ms）
)

/**
 * 全局播种深度控制状态（ViewModel 中以 MutableLiveData<SowingDepthState> 持有）
 *
 * @param motors            8 路电机校准与状态列表，下标与 motorIndex 一致
 * @param globalTargetDepth 全局目标深度 mm（一键设置所有在线电机）
 * @param jogSpeed          点动速度 RPM（速度模式，范围 1500~2500）
 * @param positionSpeed     位置运动速度 RPM（梯形速度，范围 100~3000）
 * @param acceleration      加速度 RPM/s（范围 1000~60000）
 */
data class SowingDepthState(
    val motors: List<ServoCalibration> = List(8) { ServoCalibration(motorIndex = it) },
    val globalTargetDepth: Float = 50f,
    val jogSpeed: Int = 2000,
    val positionSpeed: Int = 500,
    val acceleration: Int = 10000,

    /**
     * 深度控制总开关（不持久化，启动默认 false）。
     * true  → 协程对在线电机执行 DS402 初始化，并按 motors[i].targetDepth 下发位置命令；
     * false → 已初始化的电机会被 Shutdown，禁止接收新指令。
     */
    val masterEnabled: Boolean = false
)

enum class CalibrationMode { DIRECT, INDIRECT }

fun isSowingDepthMotorActive(
    motorIndex: Int,
    rowNumber: Int,
    activeMotors: BooleanArray?
): Boolean {
    val enabledRowCount = rowNumber.coerceIn(0, 8)
    if (motorIndex !in 0 until enabledRowCount) return false
    return activeMotors?.getOrNull(motorIndex) ?: true
}

fun activeSowingDepthMotorIndices(
    rowNumber: Int,
    activeMotors: BooleanArray?
): List<Int> {
    return (0 until rowNumber.coerceIn(0, 8))
        .filter { isSowingDepthMotorActive(it, rowNumber, activeMotors) }
}

data class IndirectCalibPoint(
    val depthMm: Float,
    val encoderPos: Int? = null
)

val STANDARD_BLOCK_DEPTHS_MM = listOf(20f, 40f, 60f, 80f, 100f)

private val DEFAULT_INDIRECT_POINTS: List<IndirectCalibPoint> =
    STANDARD_BLOCK_DEPTHS_MM.map { IndirectCalibPoint(depthMm = it) }

/**
 * 由 limitMin/limitMax 推出"深方向"的符号：+1 / -1 / 0(限位未分离)。
 * 仅用于点动方向判断与到限检查；写入 0x261F/0x2620 时已按浅/深字面值处理。
 */
val ServoCalibration.deepDirection: Int
    get() = limitMax.compareTo(limitMin)

/**
 * 控深就绪诊断：返回阻止处方图深度命令下发的首要原因（人类可读），全部就绪返回 null。
 *
 * 仅读状态、无副作用，供主界面在按「开始」后做一次性提示。
 * 优先级：处方图侧（未加载/深度字段缺失——电机只会停在种子深度、永不跟图，
 * 田间排查最隐蔽，故排最前）> 电机侧，电机侧与
 * [com.nx.vfremake.coroutine.SowingDepthCoroutine] 的门控顺序一致：
 *   无任何在线（Phase1/2/4 全跳过）> 在线但限位未标定（Phase4 b）> 在线但拟合无效（Phase4 c）。
 *
 * @param mapLoaded            处方图 FeatureTable 是否已加载（shapefileFeatureTable != null）
 * @param depthFieldOk         全局 depthQueryField 是否非空（loadShp 已验证字段存在于 shp）
 * @param configuredDepthField 用户配置的深度字段名，仅用于拼提示文案
 */
fun SowingDepthState.depthControlReadinessWarning(
    rowNumber: Int = 8,
    activeMotors: BooleanArray? = null,
    mapLoaded: Boolean = true,
    depthFieldOk: Boolean = true,
    configuredDepthField: String = ""
): String? {
    if (!mapLoaded) {
        return "处方图未加载，深度不会随处方图变化。\n" +
            "请先在主界面加载 .shp 处方图后再开始作业。"
    }
    if (!depthFieldOk) {
        return "处方图中未找到播种深度字段「$configuredDepthField」，深度不会随处方图变化。\n" +
            "请在处方图设置对话框第 3 步选择正确的深度字段（电机只会保持当前目标深度）。"
    }
    val activeIndices = activeSowingDepthMotorIndices(rowNumber, activeMotors)
    if (activeIndices.isEmpty()) {
        return "未启用任何播种深度电机。\n请先在「参数设置」页开启需要作业的行。"
    }
    val online = activeIndices.mapNotNull { motors.getOrNull(it) }.filter { it.isOnline }
    if (online.isEmpty()) {
        return "处方图控深已开启，但未检测到任何深度伺服在线（CAN 节点 11–18 无心跳/TPDO）。\n" +
            "已发送 NMT 广播，但因无电机在线，未下发任何位置命令。\n" +
            "请检查：①伺服供电与 CAN 接线 ②CAN 波特率是否匹配 ③节点 ID 是否为 11–18。"
    }
    val notCalibrated = online.filter { !it.limitsSet || !it.fitValid }
    if (notCalibrated.isNotEmpty()) {
        val list = notCalibrated.joinToString("、") { "${it.motorIndex + 1}号" }
        return "深度电机 $list 在线但未完成标定（限位/拟合），处方图深度命令已被忽略。\n" +
            "请先在「播种深度标定」页完成限位标定与深度拟合后再开始。"
    }
    return null
}
