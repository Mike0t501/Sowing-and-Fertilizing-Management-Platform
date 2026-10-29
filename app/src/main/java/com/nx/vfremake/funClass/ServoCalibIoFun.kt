/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2026年10月14日
 * @file    :ServoCalibIoFun.kt
 * @brief   :播种深度伺服标定数据 JSON 导入/导出——纯函数层（DTO、编码、解码校验、回填）
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 标定只存在 SharedPreferences 里，换平板/清数据/单体换行位都得重新标定。本文件把伺服标定
 * （限位 + 标定点 + 拟合系数）导出成 JSON 便于归档，并能导回任意一路。
 ***********************************************************************************************************
 */

package com.nx.vfremake.funClass

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.nx.vfremake.data.CalibrationMode
import com.nx.vfremake.data.IndirectCalibPoint
import com.nx.vfremake.data.STANDARD_BLOCK_DEPTHS_MM
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.data.buildLinearFit
import kotlin.math.abs

/**
 * 伺服标定文件 DTO（schema v1）。
 *
 * 字段全部可空：Gson 绕过 Kotlin 构造器，缺字段时非空类型也会被塞 null，
 * 所以统一声明可空、由 [ServoCalibIoFun.decode] 显式校验。
 * 不直接序列化 [ServoCalibration]：Pair 会变成 first/second，且运行时字段会泄漏进文件。
 */
data class ServoCalibFileDto(
    val format: String? = null,
    val version: Int? = null,
    val exportedAt: String? = null,
    val appVersion: String? = null,
    val units: List<ServoCalibUnitDto>? = null
)

/**
 * 单个单体的伺服标定。
 *
 * sourceNodeId 只作参考信息：Node-ID 属于行位（CAN 地址），导入时保留目标行原 nodeId。
 */
data class ServoCalibUnitDto(
    val unitLabel: String? = null,
    val sourceMotorIndex: Int? = null,
    val sourceNodeId: Int? = null,
    val limitMin: Int? = null,
    val limitMax: Int? = null,
    val limitsSet: Boolean? = null,
    val calibrationMode: String? = null,
    val calibrationPoints: List<CalibPointDto>? = null,
    val indirectPoints: List<IndirectPointDto>? = null,
    val fitA: Float? = null,
    val fitB: Float? = null,
    val fitValid: Boolean? = null
)

data class CalibPointDto(val encoderPos: Int? = null, val depthMm: Float? = null)

data class IndirectPointDto(val depthMm: Float? = null, val encoderPos: Int? = null)

object ServoCalibIoFun {

    const val FORMAT = "vfremake.servoCalibration"
    const val VERSION = 1

    /** 校准点上限：与标定向导 5 个挡位一致 */
    private const val MAX_POINTS = 5

    /** 文件内拟合系数与标定点重算结果在各点处的允许偏差 mm（防手工改坏文件） */
    private const val FIT_TOLERANCE_MM = 0.5f

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    /** 有可导出的内容：限位或拟合至少完成一项 */
    fun hasExportableData(cal: ServoCalibration): Boolean = cal.limitsSet || cal.fitValid

    fun toUnitDto(cal: ServoCalibration, label: String): ServoCalibUnitDto = ServoCalibUnitDto(
        unitLabel         = label.trim(),
        sourceMotorIndex  = cal.motorIndex,
        sourceNodeId      = cal.nodeId,
        limitMin          = cal.limitMin,
        limitMax          = cal.limitMax,
        limitsSet         = cal.limitsSet,
        calibrationMode   = cal.calibrationMode.name,
        calibrationPoints = cal.calibrationPoints.map { (pos, depth) -> CalibPointDto(pos, depth) },
        indirectPoints    = cal.indirectPoints.map { IndirectPointDto(it.depthMm, it.encoderPos) },
        fitA              = cal.fitA,
        fitB              = cal.fitB,
        fitValid          = cal.fitValid
    )

    fun encode(units: List<ServoCalibUnitDto>, exportedAt: String, appVersion: String): String =
        gson.toJson(ServoCalibFileDto(FORMAT, VERSION, exportedAt, appVersion, units))

    /**
     * 解析并校验标定文件。失败时 Result 内异常的 message 为面向操作员的中文原因。
     */
    fun decode(json: String): Result<ServoCalibFileDto> {
        val file = try {
            gson.fromJson(json, ServoCalibFileDto::class.java)
        } catch (e: JsonParseException) {
            return fail("文件不是有效的 JSON：${e.message?.take(80)}")
        } ?: return fail("文件内容为空")

        if (file.format != FORMAT) return fail("不是伺服标定文件（format 不符）")
        val ver = file.version ?: return fail("缺少版本号")
        if (ver > VERSION) return fail("文件版本 v$ver 高于本 App 支持的 v$VERSION，请升级 App")
        if (ver < 1) return fail("文件版本号非法：$ver")
        val units = file.units
        if (units.isNullOrEmpty()) return fail("文件中没有任何单体标定数据")

        units.forEachIndexed { i, u ->
            validateUnit(u)?.let { reason ->
                val name = describeUnit(u, i)
                return fail("$name：$reason")
            }
        }
        return Result.success(file)
    }

    /** 单体显示名：备注名优先，否则「来源 N 号」 */
    fun describeUnit(u: ServoCalibUnitDto, fallbackIdx: Int = 0): String {
        val label = u.unitLabel?.takeIf { it.isNotBlank() }
        val src = u.sourceMotorIndex?.let { "来源 ${it + 1} 号" }
        return when {
            label != null && src != null -> "「$label」($src)"
            label != null -> "「$label」"
            src != null -> src
            else -> "第 ${fallbackIdx + 1} 条"
        }
    }

    /** 返回 null 表示合法，否则返回原因 */
    private fun validateUnit(u: ServoCalibUnitDto): String? {
        val src = u.sourceMotorIndex
        if (src != null && src !in 0 until 8) return "来源路号非法：$src"

        val limitsSet = u.limitsSet ?: return "缺少 limitsSet"
        val lMin = u.limitMin ?: return "缺少 limitMin"
        val lMax = u.limitMax ?: return "缺少 limitMax"
        if (limitsSet && lMin == lMax) return "限位上下限相同（$lMin），限位无效"

        val modeStr = u.calibrationMode ?: return "缺少 calibrationMode"
        if (runCatching { CalibrationMode.valueOf(modeStr) }.isFailure) {
            return "标定模式非法：$modeStr"
        }

        val pts = u.calibrationPoints ?: emptyList()
        if (pts.size > MAX_POINTS) return "标定点 ${pts.size} 个，超过上限 $MAX_POINTS"
        pts.forEach { p ->
            if (p.encoderPos == null || p.depthMm == null) return "标定点缺少位置或深度"
            if (!p.depthMm.isFinite() || p.depthMm <= 0f) return "标定点深度非法：${p.depthMm}"
        }
        val ind = u.indirectPoints ?: emptyList()
        if (ind.size > MAX_POINTS) return "挡块点 ${ind.size} 个，超过上限 $MAX_POINTS"
        ind.forEach { p ->
            val d = p.depthMm ?: return "挡块点缺少深度"
            if (!d.isFinite() || d <= 0f) return "挡块深度非法：$d"
        }

        val fitValid = u.fitValid ?: return "缺少 fitValid"
        if (fitValid) {
            val a = u.fitA ?: return "缺少 fitA"
            val b = u.fitB ?: return "缺少 fitB"
            if (!a.isFinite() || !b.isFinite() || a == 0f) return "拟合系数非法（a=$a, b=$b）"
            // 用标定点重算一次拟合，与文件系数在各标定点处比较深度偏差
            val pairs = pts.map { Pair(it.encoderPos!!, it.depthMm!!) }
            val refit = buildLinearFit(pairs)
            if (refit != null) {
                val (ra, rb) = refit
                val maxDev = pairs.maxOf { (pos, _) -> abs((a * pos + b) - (ra * pos + rb)) }
                if (maxDev > FIT_TOLERANCE_MM) {
                    return "拟合系数与标定点不一致（偏差 ${"%.2f".format(maxDev)} mm），文件可能被改动"
                }
            }
        }
        if (!limitsSet && !fitValid) return "该单体既未标定限位也无有效拟合，无可导入数据"
        return null
    }

    /**
     * 把单体标定回填到目标电机：只覆盖持久化标定字段，
     * 保留目标的 motorIndex / nodeId / 全部运行时字段。调用前须已通过 [decode] 校验。
     */
    fun ServoCalibUnitDto.applyTo(target: ServoCalibration): ServoCalibration {
        val mode = runCatching { CalibrationMode.valueOf(calibrationMode ?: "") }
            .getOrDefault(CalibrationMode.DIRECT)
        val indirect = indirectPoints
            ?.takeIf { it.isNotEmpty() }
            ?.map { IndirectCalibPoint(depthMm = it.depthMm ?: 0f, encoderPos = it.encoderPos) }
            ?: STANDARD_BLOCK_DEPTHS_MM.map { IndirectCalibPoint(depthMm = it) }
        return target.copy(
            limitMin          = limitMin ?: 0,
            limitMax          = limitMax ?: 0,
            limitsSet         = limitsSet ?: false,
            calibrationPoints = (calibrationPoints ?: emptyList())
                .map { Pair(it.encoderPos ?: 0, it.depthMm ?: 0f) },
            fitA              = fitA ?: 0f,
            fitB              = fitB ?: 0f,
            fitValid          = fitValid ?: false,
            calibrationMode   = mode,
            indirectPoints    = indirect
        )
    }

    /** 拟合式摘要，供确认框新旧对比 */
    fun fitSummary(fitValid: Boolean, a: Float, b: Float): String =
        if (fitValid) "depth = ${"%.6f".format(a)} × pos + ${"%.2f".format(b)}" else "未拟合"

    fun limitSummary(limitsSet: Boolean, lMin: Int, lMax: Int): String =
        if (limitsSet) "$lMin ~ $lMax" else "未标定"

    /** 文件名里只保留安全字符，避免 SAF 拒绝或生成怪名 */
    fun sanitizeLabel(label: String): String =
        label.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(24)

    private fun fail(msg: String): Result<ServoCalibFileDto> =
        Result.failure(IllegalArgumentException(msg))
}
