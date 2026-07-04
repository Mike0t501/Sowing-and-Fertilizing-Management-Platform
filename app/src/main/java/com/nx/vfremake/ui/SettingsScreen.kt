package com.nx.vfremake.ui

import android.serialport.SerialPort
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.vfremake.R
import com.nx.vfremake.funClass.MySharedPreFun
import android.media.MediaPlayer
import com.nx.vfremake.canMonitorData
import com.nx.vfremake.fittingCoefficientA
import com.nx.vfremake.fittingCoefficientB
import com.nx.vfremake.funClass.ConvAndCtrlFun
import com.nx.vfremake.isSystemRunning
import com.nx.vfremake.mSerialPortCAN
import com.nx.vfremake.mSerialPortDB9
import com.nx.vfremake.mSPParamData
import com.nx.vfremake.mTestSerialPortCAN
import com.nx.vfremake.mTestSerialPortDB9
import java.io.File
import java.io.IOException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private var backPressedTime: Long = 0

private fun scanSerialDevices(): List<String> {
    // 方法1：/sys/class/tty 下有 device 子链接的才是真实硬件串口
    // 不依赖 /dev/ 读权限，SELinux 通常允许读 sysfs，适配所有设备
    val fromSysFs = try {
        File("/sys/class/tty").listFiles()
            ?.filter { it.isDirectory && File(it, "device").exists() }
            ?.map { it.name }
            ?.filter { it.startsWith("tty") }
    } catch (_: Exception) { null }
    if (!fromSysFs.isNullOrEmpty()) return fromSysFs.sorted()

    // 方法2：直接枚举 /dev/ 下 tty 开头的文件
    val fromDev = try {
        File("/dev").listFiles { f -> f.name.startsWith("tty") }?.map { it.name }
    } catch (_: Exception) { null }
    if (!fromDev.isNullOrEmpty()) return fromDev.sorted()

    // 方法3：shell ls /dev/（不加 glob，避免无 tty* 时 shell 报错返回空）
    val fromShell = try {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ls /dev/"))
        proc.inputStream.bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.startsWith("tty") }
            .sorted()
    } catch (_: Exception) { emptyList() }
    if (fromShell.isNotEmpty()) return fromShell

    // 方法4：解析 /proc/tty/drivers
    // 先尝试直接读文件，再尝试 shell cat（两种 SELinux 路径不同）
    val driversLines: List<String> = try {
        File("/proc/tty/drivers").readLines()
    } catch (_: Exception) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cat /proc/tty/drivers"))
            p.inputStream.bufferedReader().readLines()
        } catch (_: Exception) { emptyList() }
    }

    if (driversLines.isNotEmpty()) {
        val confirmed = mutableListOf<String>()
        val candidates = mutableListOf<String>()
        driversLines.forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            // 格式：驱动名  /dev/前缀  major  minor范围  类型
            if (parts.size >= 4) {
                val devPath = parts[1]
                val name = devPath.removePrefix("/dev/")
                if (!name.startsWith("tty")) return@forEach
                val rangeStr = parts[3]
                val (minN, maxN) = if (rangeStr.contains("-")) {
                    val sp = rangeStr.split("-")
                    sp[0].toIntOrNull() to sp[1].toIntOrNull()
                } else {
                    rangeStr.toIntOrNull() to rangeStr.toIntOrNull()
                }
                if (minN != null && maxN != null) {
                    // 限制枚举上限，避免 pty 等驱动枚举数万个
                    val cap = minOf(maxN, minN + 15)
                    for (n in minN..cap) {
                        val devName = "$name$n"
                        candidates.add(devName)
                        // 用 shell ls 检测单个文件（stat 而非 readdir，SELinux 限制更少）
                        try {
                            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ls /dev/$devName"))
                            p.waitFor()
                            if (p.exitValue() == 0) confirmed.add(devName)
                        } catch (_: Exception) {}
                    }
                } else {
                    candidates.add(name)
                    try {
                        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ls /dev/$name"))
                        p.waitFor()
                        if (p.exitValue() == 0) confirmed.add(name)
                    } catch (_: Exception) {}
                }
            }
        }
        // 有经过存在性确认的就返回确认列表，否则返回全部候选（至少让用户看到驱动名）
        return if (confirmed.isNotEmpty()) confirmed.sorted() else candidates.sorted()
    }

    // 方法5：暴力探测已知工业平板常见串口名（前四种方法均被 SELinux 封锁时兜底）
    // 用 shell ls 检测单个文件（getattr，SELinux 限制比 readdir 少）
    val probePrefixes = listOf(
        "ttySWK" to (0..7),   // 富士康等平板大写 SWK 驱动
        "ttyUW"  to (0..3),   // UW 系列
        "ttyS"   to (0..7),   // 标准 UART
        "ttyUSB" to (0..3),   // USB 转串口
        "ttyACM" to (0..3),   // USB CDC
    )
    val probeResult = mutableListOf<String>()
    for ((prefix, range) in probePrefixes) {
        for (n in range) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ls /dev/$prefix$n"))
                p.waitFor()
                if (p.exitValue() == 0) probeResult.add("$prefix$n")
            } catch (_: Exception) {}
        }
    }
    return probeResult.sorted()
}

private suspend fun runDiagnosticTest(
    context: android.content.Context,
    onProgress: (String) -> Unit,
    onComplete: (report: String, isFailure: Boolean) -> Unit
) {
    // 如果串口已被系统停止关闭（mSerialPortCAN == null），
    // 用 SharedPreferences 里保存的配置临时打开，让诊断能正常运行
    if (mSerialPortCAN == null || mSerialPortDB9 == null) {
        onProgress("正在尝试打开串口...")
        val sp = MySharedPreFun(context).getMySharedPre()
        if (mSerialPortCAN == null) {
            val portName = sp.getString(context.getString(R.string.serial_can_port_name), context.getString(R.string.serial_can_port_defValue)) ?: ""
            val baud = sp.getString(context.getString(R.string.serial_can_baud_name), context.getString(R.string.serial_can_baud_defValue))?.toIntOrNull() ?: 115200
            try { mSerialPortCAN = SerialPort(File("/dev/$portName"), baud) } catch (_: Exception) {}
        }
        if (mSerialPortDB9 == null) {
            val portName = sp.getString(context.getString(R.string.serial_db9_port_name), context.getString(R.string.serial_db9_port_defValue)) ?: ""
            val baud = sp.getString(context.getString(R.string.serial_db9_baud_name), context.getString(R.string.serial_db9_baud_defValue))?.toIntOrNull() ?: 230400
            try { mSerialPortDB9 = SerialPort(File("/dev/$portName"), baud) } catch (_: Exception) {}
        }
    }

    val motorCount = mSPParamData.rowNumber.coerceIn(1, 8)
    val activeMotors = mSPParamData.activeMotors
    val canPort = mSerialPortCAN
    val db9Port = mSerialPortDB9

    val motorResponded = BooleanArray(motorCount) { false }
    val motorMaxRpm = DoubleArray(motorCount) { 0.0 }
    var canAnyReceived = false
    var rtkReceived = false
    var rtkFixValid = false      // GPRMC/GNRMC 状态字段为 'A'（有效定位）
    var rtkGarbled = false       // 收到数据但不含合法 NMEA 帧头（疑似波特率错误）

    // 辅助：解析 CAN 回包
    suspend fun drainCAN() {
        val canIn = canPort?.inputStream ?: return
        val available = withContext(Dispatchers.IO) { canIn.available() }
        if (available < 10) return
        val buf = ByteArray(minOf(available, 640))
        val read = withContext(Dispatchers.IO) { canIn.read(buf) }
        var pos = 0
        while (pos + 10 <= read) {
            if (buf[pos] == 0x27.toByte() && buf[pos + 9] == 0x39.toByte()) {
                canAnyReceived = true
                val hid = buf[pos + 4].toInt()
                if (hid in 0 until motorCount) {
                    val rpm = (buf[pos + 5].toInt() and 0xFF) + (buf[pos + 6].toInt() and 0xFF) * 0.01
                    if (rpm > 0.5) {
                        motorResponded[hid] = true
                        if (rpm > motorMaxRpm[hid]) motorMaxRpm[hid] = rpm
                    }
                }
                pos += 10
            } else pos++
        }
    }

    // 辅助：检测 RTK 信号，同时解析定位状态和数据质量
    // 使用与 DB9reCANseCoroutine 完全相同的累积缓冲方式：
    // 跨多次读取拼接数据，按 \n 分割完整行，避免语句被截断导致漏检
    val rtkBuffer = StringBuilder()
    suspend fun checkRTK() {
        val db9In = db9Port?.inputStream ?: return
        // 读取当前可用字节追加到缓冲区（不足时跳过，等下次）
        try {
            val available = withContext(Dispatchers.IO) { db9In.available() }
            if (available > 0) {
                val buf = ByteArray(minOf(available, 1024))
                val read = withContext(Dispatchers.IO) { db9In.read(buf) }
                if (read > 0) {
                    try { rtkBuffer.append(String(buf, 0, read, Charsets.US_ASCII)) }
                    catch (_: Exception) { rtkBuffer.append(String(buf, 0, read)) }
                }
            }
        } catch (_: Exception) { return }

        // 按 \n 取出完整行逐行解析（与 DB9reCANseCoroutine 逻辑一致）
        while (rtkBuffer.contains('\n')) {
            val eol = rtkBuffer.indexOf('\n')
            val line = rtkBuffer.substring(0, eol).trim()
            rtkBuffer.delete(0, eol + 1)
            if (line.isEmpty()) continue

            // 乱码检测：有可打印字符但不含任何 NMEA 头
            val hasNmea = line.startsWith("\$GP") || line.startsWith("\$GN") || line.startsWith("\$GL")
            if (!hasNmea && line.any { it.code in 32..126 } && line.length > 10) {
                rtkGarbled = true
                continue
            }

            // RMC 语句：解析定位状态（部分接收机静止时发 V，坐标仍有效）
            if (line.startsWith("\$GPRMC") || line.startsWith("\$GNRMC")) {
                rtkReceived = true
                val parts = line.split(",")
                // $GPRMC,HHMMSS,A/V,lat,... → parts[2] 为定位状态
                if (parts.size > 2 && parts[2] == "A") rtkFixValid = true
            }

            // GGA 语句：定位质量字段 > 0 即为有效定位（不受静止时 V 标志影响）
            // $GPGGA,HHMMSS,lat,N,lon,E,quality,... → parts[6] 为定位质量
            // 0=无效 1=GPS 2=差分 4=RTK固定 5=RTK浮动
            if (line.startsWith("\$GPGGA") || line.startsWith("\$GNGGA")) {
                rtkReceived = true
                val parts = line.split(",")
                if (parts.size > 6 && (parts[6].toIntOrNull() ?: 0) > 0) rtkFixValid = true
            }
        }
        // 防止缓冲区无限增长（无 \n 的乱码情况）
        if (rtkBuffer.length > 2048) rtkBuffer.delete(0, rtkBuffer.length - 512)
    }

    // 辅助：停止所有电机
    fun stopAll() {
        for (i in 0 until motorCount) {
            try { ConvAndCtrlFun().motorSpeedrpmSend(0.0, i) } catch (_: Exception) {}
        }
    }

    // ── 电机测试阶段（仅当 CAN 口已打开时执行）──
    if (canPort != null) {
        val activeCount = (0 until motorCount).count { i -> activeMotors.getOrNull(i) ?: true }
        val phase12DurationMs = activeCount.toLong() * (10 * 400 + 300) + 10 * 400L + 1000L

        try {
            // 阶段 1+2：电机检测 与 RTK检测 双线程并行
            coroutineScope {
                launch {
                    for (i in 0 until motorCount) {
                        val isActive = activeMotors.getOrNull(i) ?: true
                        if (!isActive) { onProgress("M${i + 1}：已禁用，跳过"); continue }
                        onProgress("测试 M${i + 1} / ${motorCount}（10->55 rpm，4秒）...")
                        for (step in 0..9) {
                            ConvAndCtrlFun().motorSpeedrpmSend(10.0 + 45.0 * step / 9.0, i)
                            delay(400)
                            drainCAN()
                        }
                        ConvAndCtrlFun().motorSpeedrpmSend(0.0, i)
                        delay(300)
                    }
                    onProgress("所有电机联合升速（4秒）...")
                    for (step in 0..9) {
                        val speed = 10.0 + 45.0 * step / 9.0
                        for (i in 0 until motorCount) {
                            val isActive = activeMotors.getOrNull(i) ?: true
                            if (isActive) ConvAndCtrlFun().motorSpeedrpmSend(speed, i)
                        }
                        delay(400)
                        drainCAN()
                    }
                    stopAll()
                    delay(300)
                }
                launch {
                    val rtkEnd = System.currentTimeMillis() + phase12DurationMs
                    while (System.currentTimeMillis() < rtkEnd) { checkRTK(); delay(200) }
                }
            }

            // 阶段 3：稳定模拟处方图驱动（10秒）
            onProgress("模拟处方图稳定驱动（10秒）...")
            val simEnd = System.currentTimeMillis() + 10_000L
            while (System.currentTimeMillis() < simEnd) {
                for (i in 0 until motorCount) {
                    val isActive: Boolean = activeMotors.getOrNull(i) ?: true
                    if (isActive) ConvAndCtrlFun().motorSpeedrpmSend(30.0, i)
                }
                delay(500)
                drainCAN()
                if (!rtkReceived) checkRTK()
            }
            stopAll()

        } catch (e: Exception) {
            stopAll()
        }
    } else {
        // CAN 口未打开：仍独立检测 RTK（3秒）
        onProgress("CAN串口未打开，单独检测RTK信号（3秒）...")
        val rtkEnd = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < rtkEnd) { checkRTK(); delay(200) }
    }

    // ── 生成报告 ──
    onProgress("正在生成检测报告...")
    val report = StringBuilder()

    report.appendLine("════════ 排肥机一键检测报告 ════════")
    report.appendLine()

    // ── 串口状态 ──
    report.appendLine("▌ 串口状态")
    report.appendLine("  CAN总线串口：${if (canPort != null) "✓ 已打开" else "✗ 未打开"}")
    report.appendLine("  RTK串口    ：${if (db9Port != null) "✓ 已打开" else "✗ 未打开"}")
    report.appendLine()

    // ── RTK 结果 ──
    report.appendLine("▌ RTK/GNSS 信号")
    when {
        db9Port == null  -> report.appendLine("  ✗ RTK串口未打开，无法检测")
        rtkFixValid      -> report.appendLine("  ✓ 已收到 RTK 数据且定位有效（状态 A）")
        rtkReceived      -> report.appendLine("  ! 已收到 RTK 数据流，但当前无有效定位（状态 V）")
        rtkGarbled       -> report.appendLine("  ! RTK串口有数据但无法识别NMEA帧（疑似波特率不匹配）")
        else             -> report.appendLine("  ✗ 整个检测期间未收到任何 RTK/GNSS 数据")
    }
    report.appendLine()

    // ── CAN 总线 + 各电机 ──
    report.appendLine("▌ CAN 总线与各电机结果")
    if (canPort == null) {
        report.appendLine("  ✗ CAN串口未打开，未进行电机测试")
    } else if (!canAnyReceived) {
        report.appendLine("  ✗ CAN 总线全程无任何回包（总线级故障）")
    } else {
        report.appendLine("  ✓ CAN 总线通信正常")
    }
    var noResponseCount = 0
    if (canPort != null) {
        for (i in 0 until motorCount) {
            val isActive: Boolean = activeMotors.getOrNull(i) ?: true
            if (!isActive) { report.appendLine("  M${i + 1}：已禁用"); continue }
            if (motorResponded[i]) {
                report.appendLine("  M${i + 1}：✓ 正常（最高回传 %.1f rpm）".format(motorMaxRpm[i]))
            } else {
                report.appendLine("  M${i + 1}：✗ 无回传")
                noResponseCount++
            }
        }
    }
    report.appendLine()

    // ── 排肥系数 ──
    val coeffZero = try {
        fittingCoefficientA.all { it == 0.0 } && fittingCoefficientB.all { it == 0.0 }
    } catch (_: UninitializedPropertyAccessException) { true }
    val rowSizeZero = mSPParamData.rowSize <= 0.0
    report.appendLine("▌ 参数配置")
    report.appendLine(if (coeffZero) "  ✗ 排肥系数A/B全为0，未配置" else "  ✓ 排肥系数已配置")
    report.appendLine(if (rowSizeZero) "  ! 行距为0，施肥量计算结果可能异常" else "  ✓ 行距已配置（${mSPParamData.rowSize * 100} cm）")
    report.appendLine("  已启用电机数：${(0 until motorCount).count { i -> activeMotors.getOrNull(i) ?: true }} / $motorCount")
    report.appendLine()

    // ── 综合诊断建议 ──
    report.appendLine("▌ 综合诊断与建议")
    var hasSuggestion = false

    // CAN 串口未打开
    if (canPort == null) {
        report.appendLine("  [CAN串口未打开]")
        report.appendLine("    原因：主程序串口未初始化，或上次未正常关闭。")
        report.appendLine("    解决：在设置页选择正确的CAN串口号和波特率，点击[应用]重新初始化。")
        report.appendLine()
        hasSuggestion = true
    }
    // CAN 总线完全无回包（串口开着但总线死掉）
    else if (!canAnyReceived) {
        report.appendLine("  [CAN总线无任何回包 - 总线级故障]")
        report.appendLine("    可能原因及排查顺序：")
        report.appendLine("    1. 串口号/波特率配置错误：确认CAN转换板实际使用的串口和波特率。")
        report.appendLine("    2. 终端电阻过低：本机1号和8号电机各内置120Ω。")
        report.appendLine("       若同时接入4台带内置电阻的电机，并联后约30Ω，")
        report.appendLine("       远低于120Ω标准，导致总线信号崩溃。")
        report.appendLine("       解决：断开2号和4号电机的内置终端电阻，或减少并联节点。")
        report.appendLine("    3. 接线断路：检查CAN_H和CAN_L线是否松脱，接头是否氧化。")
        report.appendLine("    4. 电机控制器未上电：确认所有电机驱动板电源正常。")
        report.appendLine("    5. CAN转换板损坏：用串口工具直接测试转换板收发是否正常。")
        report.appendLine()
        hasSuggestion = true
    }
    // 少量电机无回传（1-2台，总线本身正常）
    else if (noResponseCount in 1..2) {
        // 列出具体无回传的电机编号
        val noRespList = (0 until motorCount).filter { i ->
            val isActive: Boolean = activeMotors.getOrNull(i) ?: true
            isActive && !motorResponded[i]
        }.map { "M${it + 1}" }.joinToString(", ")
        report.appendLine("  [$noRespList 无回传（总线通信正常）]")
        report.appendLine("    可能原因：")
        report.appendLine("    1. 硬件反转电机不发送CAN回包，属正常设计，无需处理。")
        report.appendLine("    2. 电机CAN接头松动：重新插拔该电机的CAN接头并锁紧。")
        report.appendLine("    3. 该电机驱动板独立电源故障：检查对应驱动板供电指示灯。")
        report.appendLine("    4. 电机ID地址冲突：确认每台电机的拨码开关ID唯一。")
        report.appendLine()
        hasSuggestion = true
    }
    // 多台电机无回传（≥3台，疑似总线阻抗或多节点故障）
    else if (noResponseCount >= 3) {
        report.appendLine("  [$noResponseCount 台电机无回传 - 疑似总线阻抗或多节点故障]")
        report.appendLine("    可能原因：")
        report.appendLine("    1. 终端电阻并联过多：CAN总线标准为两端各接1个120Ω（共2个）。")
        report.appendLine("       若每台电机内置120Ω且全部接入，4台=30Ω，8台=15Ω，")
        report.appendLine("       过低阻抗会导致大部分电机无法正常通信。")
        report.appendLine("       解决：只保留总线两个物理端点的终端电阻，断开中间节点的。")
        report.appendLine("    2. 逐一断开法定位故障节点：从总线上拔掉一台电机后若恢复，")
        report.appendLine("       则该电机为故障源（短路/拉低总线）。")
        report.appendLine("    3. 波特率不一致：更换过电机后，确认新电机波特率与系统一致。")
        report.appendLine("    4. CAN_H与CAN_L接反：检查所有节点的接线极性是否统一。")
        report.appendLine()
        hasSuggestion = true
    }

    // RTK 串口未打开
    if (db9Port == null) {
        report.appendLine("  [RTK串口未打开]")
        report.appendLine("    解决：在设置页选择正确的RTK串口号和波特率，点击[应用]重新初始化。")
        report.appendLine()
        hasSuggestion = true
    }
    // RTK 串口乱码（有数据但无NMEA头）
    else if (rtkGarbled && !rtkReceived) {
        report.appendLine("  [RTK串口有数据但无法解析NMEA协议]")
        report.appendLine("    最可能原因：波特率配置错误。")
        report.appendLine("    常见RTK模块波特率：115200 / 230400 / 460800。")
        report.appendLine("    解决：在串口配置中逐一尝试上述波特率，直到收到正常 GPRMC 语句。")
        report.appendLine()
        hasSuggestion = true
    }
    // RTK 有数据流但无有效定位（状态 V）
    else if (rtkReceived && !rtkFixValid) {
        report.appendLine("  [RTK已连接但定位无效（GPRMC状态=V）]")
        report.appendLine("    可能原因：")
        report.appendLine("    1. RTK设备刚上电，正在搜星，通常需等待30秒~2分钟。")
        report.appendLine("    2. 当前位于室内或遮挡严重区域，卫星信号不足。")
        report.appendLine("    3. RTK天线方向朝下或被遮挡，需调整天线朝向天空。")
        report.appendLine("    4. RTK基站差分数据未正常接入（如需RTK差分精度时）。")
        report.appendLine("    提示：在开阔室外等待1~2分钟后重新检测。")
        report.appendLine()
        hasSuggestion = true
    }
    // RTK 完全无数据
    else if (!rtkReceived) {
        report.appendLine("  [RTK整个检测期间无任何数据]")
        report.appendLine("    可能原因：")
        report.appendLine("    1. 串口号选择错误：确认RTK模块实际连接的串口号（可通过串口测试工具验证）。")
        report.appendLine("    2. 波特率错误：RTK模块常用波特率为 230400，请与设备手册确认。")
        report.appendLine("    3. RTK天线未连接或断线：检查天线接口和线缆完整性。")
        report.appendLine("    4. RTK模块未上电：确认RTK模块电源指示灯正常。")
        report.appendLine("    5. 串口RX/TX接线接反：检查DB9接头引脚定义。")
        report.appendLine()
        hasSuggestion = true
    }

    // 排肥系数
    if (coeffZero) {
        report.appendLine("  [排肥系数未配置]")
        report.appendLine("    前往[设置->单体设置]，对每台排肥机进行标定并输入A、B系数。")
        report.appendLine("    未配置时施肥量计算结果为0，无法正常作业。")
        report.appendLine()
        hasSuggestion = true
    }

    // 行距为0
    if (rowSizeZero) {
        report.appendLine("  [行距配置为0]")
        report.appendLine("    前往参数设置页输入正确的行距（单位cm），否则施肥量计算异常。")
        report.appendLine()
        hasSuggestion = true
    }

    if (!hasSuggestion) {
        report.appendLine("  ✓ 系统各项指标正常，可进行作业。")
    }

    report.appendLine()
    report.appendLine("════════════════════════")

    val isFailure = (canPort == null || !canAnyReceived) && !rtkReceived
    onComplete(report.toString(), isFailure)
}

@Composable
fun SettingsScreen(onClickBack: () -> Unit = {}, onClickDantiSettigns: () -> Unit = {}, onClickSimGnss: () -> Unit = {}, onClickExperimentData: () -> Unit = {}, onReinitSerialPort: () -> Unit = {}) {
    val context = LocalContext.current
    val sharedPre = MySharedPreFun(context).getMySharedPre()

    val writeSaveDataSwitchIsOnState = remember { mutableStateOf(MySharedPreFun(context).getSpecificValue(R.string.writeSaveData_Switch_name) == "1") }
    // 保存实验数据的分组开关：施肥数据组默认开（!= "0"），播深数据组默认关
    val saveGroupFertSwitchIsOnState = remember { mutableStateOf(MySharedPreFun(context).getSpecificValue(R.string.saveGroupFert_Switch_name) != "0") }
    val saveGroupDepthSwitchIsOnState = remember { mutableStateOf(MySharedPreFun(context).getSpecificValue(R.string.saveGroupDepth_Switch_name) == "1") }
    val navMarkCompensatedSwitchIsOnState = remember { mutableStateOf(MySharedPreFun(context).getSpecificValue(R.string.navMarkCompensated_Switch_name) == "1") }
    val testModeSwitchIsOnState = remember { mutableStateOf(MySharedPreFun(context).getSpecificValue(R.string.testMode_Switch_name) == "1") }
    val simGnssSwitchIsOnState = remember { mutableStateOf(MySharedPreFun(context).getSpecificValue(R.string.simGnss_Switch_name) == "1") }
    val errorSoundSwitchIsOnState = remember { mutableStateOf(MySharedPreFun(context).getSpecificValue(R.string.errorSound_Switch_name) == "1") }
    val testModeDurationTime = remember { mutableStateOf(MySharedPreFun(context).getSpecificValue(R.string.testMode_testModeDurationTime_name) ?: context.getString(R.string.testMode_testModeDurationTime_defeatValue)) }
    val forwardSpeedAverageNum = remember { mutableStateOf(MySharedPreFun(context).getSpecificValue(R.string.forwardSpeedAverageNum_name) ?: context.getString(R.string.forwardSpeedAverageNum_defeatValue)) }

    val colorStep = remember { mutableIntStateOf(MySharedPreFun(context).getSpecificValue(R.string.colorStep_name)?.toIntOrNull() ?: context.resources.getInteger(R.integer.colorStep_value)) }
    val deltaX = remember { mutableIntStateOf(MySharedPreFun(context).getSpecificValue(R.string.deltaX_name)?.toIntOrNull() ?: context.resources.getInteger(R.integer.deltaX_value)) }
    val deltaY = remember { mutableIntStateOf(MySharedPreFun(context).getSpecificValue(R.string.deltaY_name)?.toIntOrNull() ?: context.resources.getInteger(R.integer.deltaY_value)) }

    // 串口选择状态：可刷新，点刷新按钮重新扫描
    var ttyDevices by remember { mutableStateOf(scanSerialDevices()) }
    val baudOptions = remember { listOf("9600", "19200", "38400", "57600", "115200", "230400", "460800", "921600") }

    val db9Port = remember { mutableStateOf(sharedPre.getString(context.getString(R.string.serial_db9_port_name), context.getString(R.string.serial_db9_port_defValue)) ?: context.getString(R.string.serial_db9_port_defValue)) }
    val db9Baud = remember { mutableStateOf(sharedPre.getString(context.getString(R.string.serial_db9_baud_name), context.getString(R.string.serial_db9_baud_defValue)) ?: context.getString(R.string.serial_db9_baud_defValue)) }
    val canPort = remember { mutableStateOf(sharedPre.getString(context.getString(R.string.serial_can_port_name), context.getString(R.string.serial_can_port_defValue)) ?: context.getString(R.string.serial_can_port_defValue)) }
    val canBaud = remember { mutableStateOf(sharedPre.getString(context.getString(R.string.serial_can_baud_name), context.getString(R.string.serial_can_baud_defValue)) ?: context.getString(R.string.serial_can_baud_defValue)) }

    // 系统运行状态（轮询，每500ms检查一次，使测试区能响应运行状态变化）
    val systemRunning by produceState(initialValue = isSystemRunning) {
        while (true) {
            value = isSystemRunning
            delay(500)
        }
    }

    // 一键检测状态
    var diagRunning by remember { mutableStateOf(false) }
    var diagProgress by remember { mutableStateOf("") }
    var diagReport by remember { mutableStateOf("") }
    val diagScope = rememberCoroutineScope()
    // 当前诊断播放器引用，用于打断上一条音频
    val diagPlayerRef = remember { mutableStateOf<MediaPlayer?>(null) }

    // 串口通信测试状态
    // 实际 SerialPort 对象使用全局变量 mTestSerialPortDB9 / mTestSerialPortCAN，
    // 这样 openSerialPort() 开机前可以强制关闭它们，彻底避免端口占用冲突
    var db9IsOpen by remember { mutableStateOf(false) }
    var canIsOpen by remember { mutableStateOf(false) }
    var db9RxText by remember { mutableStateOf("") }
    var canRxText by remember { mutableStateOf("") }
    var db9TxText by remember { mutableStateOf("") }
    var canTxText by remember { mutableStateOf("") }

    // DB9 接收循环
    LaunchedEffect(db9IsOpen) {
        if (db9IsOpen) {
            while (isActive && db9IsOpen) {
                try {
                    val available = withContext(Dispatchers.IO) { mTestSerialPortDB9?.inputStream?.available() ?: 0 }
                    if (available > 0) {
                        val buf = ByteArray(available)
                        withContext(Dispatchers.IO) { mTestSerialPortDB9?.inputStream?.read(buf) }
                        val hex = buf.joinToString(" ") { "%02X".format(it) }
                        db9RxText = (db9RxText + hex + "\n").takeLast(3000)
                    } else {
                        delay(50)
                    }
                } catch (e: IOException) {
                    db9RxText += "[读取异常: ${e.message}]\n"
                    break
                }
            }
        }
    }

    // CAN 接收循环（帧解析版）
    LaunchedEffect(canIsOpen) {
        if (canIsOpen) {
            val parseBuffer = mutableListOf<Byte>()
            while (isActive && canIsOpen) {
                try {
                    val available = withContext(Dispatchers.IO) {
                        mTestSerialPortCAN?.inputStream?.available() ?: 0
                    }
                    if (available > 0) {
                        val buf = ByteArray(available)
                        withContext(Dispatchers.IO) { mTestSerialPortCAN?.inputStream?.read(buf) }
                        parseBuffer.addAll(buf.toList())

                        // 从 parseBuffer 提取完整帧
                        while (parseBuffer.size >= 4) {
                            // 同步到帧头 0x27
                            val startIdx = parseBuffer.indexOfFirst { it == 0x27.toByte() }
                            if (startIdx < 0) { parseBuffer.clear(); break }
                            if (startIdx > 0) { repeat(startIdx) { parseBuffer.removeAt(0) } }

                            if (parseBuffer.size < 2) break
                            val len = parseBuffer[1].toInt() and 0xFF
                            val frameSize = 1 + 1 + len + 1  // 帧头 + 长度字节 + 载荷 + 帧尾
                            if (parseBuffer.size < frameSize) break

                            // 验证帧尾
                            if (parseBuffer[frameSize - 1] == 0x39.toByte()) {
                                val frame = parseBuffer.take(frameSize).toByteArray()
                                if (len >= 3) {
                                    // 接收帧格式（与发送帧相同）：[0x27][len][0x00(frameInfo)][canId_hi][canId_lo][data...][0x39]
                                    val canId = ((frame[3].toInt() and 0xFF) shl 8) or
                                                (frame[4].toInt() and 0xFF)
                                    val data  = if (len > 3) frame.copyOfRange(5, 2 + len)
                                                else byteArrayOf()
                                    val decoded = decodeCanTestFrame(canId, data)
                                    canRxText = (canRxText + decoded + "\n").takeLast(4000)
                                } else {
                                    val raw = frame.joinToString(" ") { "%02X".format(it) }
                                    canRxText = (canRxText + "[短帧] $raw\n").takeLast(4000)
                                }
                                repeat(frameSize) { parseBuffer.removeAt(0) }
                            } else {
                                // 帧尾不匹配，丢弃帧头，继续搜索
                                parseBuffer.removeAt(0)
                            }
                        }
                    } else {
                        delay(50)
                    }
                } catch (e: IOException) {
                    canRxText += "[读取异常: ${e.message}]\n"
                    break
                }
            }
        }
    }

    // 发送格式模式：true=HEX，false=ASCII（含 \r\n 转义）
    var db9HexMode by remember { mutableStateOf(true) }
    var canHexMode by remember { mutableStateOf(true) }

    // 离开页面时释放测试串口（全局变量，tryClose 保证可靠执行）
    DisposableEffect(Unit) {
        onDispose {
            try { mTestSerialPortDB9?.tryClose() } catch (_: Exception) {}
            mTestSerialPortDB9 = null
            try { mTestSerialPortCAN?.tryClose() } catch (_: Exception) {}
            mTestSerialPortCAN = null
        }
    }

    Scaffold(
        topBar = {
            MyTopBar(
                title = "系统设置",
                onClickBack = {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - backPressedTime > 1000) {
                        backPressedTime = currentTime
                        onClickBack()
                    }
                }
            )
        },
        backgroundColor = Color(0xFFF5F7FA)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 卡片一：核心功能
            Card(shape = RoundedCornerShape(16.dp), elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    AnimatedSettingRow(title = "测试模式", subtitle = "开启后点击可进入单体设置", onClick = onClickDantiSettigns) {
                        Switch(
                            checked = testModeSwitchIsOnState.value,
                            onCheckedChange = { newValue ->
                                testModeSwitchIsOnState.value = newValue
                                sharedPre.edit().putString(context.getString(R.string.testMode_Switch_name), if (newValue) "1" else "0").apply()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50))
                        )
                    }

                    AnimatedVisibility(
                        visible = testModeSwitchIsOnState.value,
                        enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)),
                        exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessLow))
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFF0FDF4)).padding(16.dp)) {
                            MyDiyTextField(
                                label = "持续时间(s): ", modifier = Modifier.width(200.dp), value = testModeDurationTime.value,
                                onValueChange = { newText ->
                                    testModeDurationTime.value = newText
                                    sharedPre.edit().putString(context.getString(R.string.testMode_testModeDurationTime_name), newText).apply()
                                }
                            )
                        }
                    }

                    Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                    AnimatedSettingRow(title = "模拟GNSS定位", subtitle = "无 GNSS 时按设定航线模拟定位测试电机，点击进入配置", onClick = onClickSimGnss) {
                        Switch(
                            checked = simGnssSwitchIsOnState.value,
                            onCheckedChange = { newValue ->
                                simGnssSwitchIsOnState.value = newValue
                                sharedPre.edit().putString(context.getString(R.string.simGnss_Switch_name), if (newValue) "1" else "0").apply()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50))
                        )
                    }

                    Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                    AnimatedSettingRow(title = "保存实验数据", subtitle = "开启后可选择保存的数据分组，并可查看已保存记录") {
                        Switch(
                            checked = writeSaveDataSwitchIsOnState.value,
                            onCheckedChange = { newValue ->
                                writeSaveDataSwitchIsOnState.value = newValue
                                sharedPre.edit().putString(context.getString(R.string.writeSaveData_Switch_name), if (newValue) "1" else "0").apply()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50))
                        )
                    }

                    AnimatedVisibility(
                        visible = writeSaveDataSwitchIsOnState.value,
                        enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)),
                        exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessLow))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFFF0FDF4)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "施肥数据", fontSize = 14.sp, color = Color(0xFF333333))
                                Switch(
                                    checked = saveGroupFertSwitchIsOnState.value,
                                    onCheckedChange = { newValue ->
                                        saveGroupFertSwitchIsOnState.value = newValue
                                        sharedPre.edit().putString(context.getString(R.string.saveGroupFert_Switch_name), if (newValue) "1" else "0").apply()
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50))
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "播深数据", fontSize = 14.sp, color = Color(0xFF333333))
                                Switch(
                                    checked = saveGroupDepthSwitchIsOnState.value,
                                    onCheckedChange = { newValue ->
                                        saveGroupDepthSwitchIsOnState.value = newValue
                                        sharedPre.edit().putString(context.getString(R.string.saveGroupDepth_Switch_name), if (newValue) "1" else "0").apply()
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50))
                                )
                            }
                            Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp)
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onClickExperimentData() }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "查看已保存记录", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1B7F4D))
                                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "查看已保存记录", tint = Color(0xFF1B7F4D))
                            }
                        }
                    }

                    Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                    AnimatedSettingRow(title = "显示补偿位置") {
                        Switch(
                            checked = navMarkCompensatedSwitchIsOnState.value,
                            onCheckedChange = { newValue ->
                                navMarkCompensatedSwitchIsOnState.value = newValue
                                sharedPre.edit().putString(context.getString(R.string.navMarkCompensated_Switch_name), if (newValue) "1" else "0").apply()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50))
                        )
                    }

                    Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                    AnimatedSettingRow(title = "语音提示助手", subtitle = "开启后播报设备状态、报错与一键检测提示") {
                        Switch(
                            checked = errorSoundSwitchIsOnState.value,
                            onCheckedChange = { newValue ->
                                errorSoundSwitchIsOnState.value = newValue
                                sharedPre.edit().putString(context.getString(R.string.errorSound_Switch_name), if (newValue) "1" else "0").apply()
                                if (newValue) {
                                    try {
                                        val goList = listOf(R.raw.go1, R.raw.go2)
                                        val idx = ((System.nanoTime() % 2 + 2) % 2).toInt()
                                        val mp = android.media.MediaPlayer.create(context, goList[idx])
                                        mp?.setOnCompletionListener { it.release() }
                                        mp?.start()
                                    } catch (_: Exception) {}
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50))
                        )
                    }
                }
            }

            // 卡片二：高级参数调节
            Card(shape = RoundedCornerShape(16.dp), elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    AnimatedSettingRow(title = "N次速度均值定速", subtitle = "目前设定值 * 200ms") {
                        MyDiyTextField(
                            modifier = Modifier.width(80.dp), value = forwardSpeedAverageNum.value,
                            onValueChange = { newValue ->
                                forwardSpeedAverageNum.value = newValue
                                sharedPre.edit().putString(context.getString(R.string.forwardSpeedAverageNum_name), newValue).apply()
                            }
                        )
                    }
                    Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                    AnimatedSettingRow(title = "颜色分级数", subtitle = "不包含0域") {
                        CounterRow(
                            value = colorStep.intValue,
                            onIncrement = { colorStep.intValue++; sharedPre.edit().putString(context.getString(R.string.colorStep_name), colorStep.intValue.toString()).apply() },
                            onDecrement = { if (colorStep.intValue >= 2) { colorStep.intValue--; sharedPre.edit().putString(context.getString(R.string.colorStep_name), colorStep.intValue.toString()).apply() } }
                        )
                    }
                    Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                    AnimatedSettingRow(title = "位置跟随视图区域", subtitle = "大小 * 10^-6") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CounterRow(
                                prefix = "X:", value = deltaX.intValue,
                                onIncrement = { deltaX.intValue += 5; sharedPre.edit().putString(context.getString(R.string.deltaX_name), deltaX.intValue.toString()).apply() },
                                onDecrement = { if (deltaX.intValue >= 10) { deltaX.intValue -= 5; sharedPre.edit().putString(context.getString(R.string.deltaX_name), deltaX.intValue.toString()).apply() } }
                            )
                            CounterRow(
                                prefix = "Y:", value = deltaY.intValue,
                                onIncrement = { deltaY.intValue += 5; sharedPre.edit().putString(context.getString(R.string.deltaY_name), deltaY.intValue.toString()).apply() },
                                onDecrement = { if (deltaY.intValue >= 10) { deltaY.intValue -= 5; sharedPre.edit().putString(context.getString(R.string.deltaY_name), deltaY.intValue.toString()).apply() } }
                            )
                        }
                    }
                }
            }

            // 卡片三：串口选择
            Card(shape = RoundedCornerShape(16.dp), elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, bottom = 4.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "串口配置", fontSize = 13.sp, color = Color.Gray)
                        Row {
                            IconButton(
                                onClick = { ttyDevices = scanSerialDevices() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新端口列表", modifier = Modifier.size(18.dp), tint = Color.Gray)
                            }
                            TextButton(
                                onClick = {
                                    try { onReinitSerialPort() } catch (_: Throwable) {}
                                    try {
                                        val getList = listOf(R.raw.get1, R.raw.get2)
                                        val idx = ((System.nanoTime() % 2 + 2) % 2).toInt()
                                        val mp = android.media.MediaPlayer.create(context, getList[idx])
                                        mp?.setOnCompletionListener { it.release() }
                                        mp?.start()
                                    } catch (_: Throwable) {}
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("应用", fontSize = 12.sp, color = Color(0xFF1565C0))
                            }
                        }
                    }
                    SerialPortSelectRow(
                        label = "RTK定位",
                        selectedPort = db9Port.value,
                        selectedBaud = db9Baud.value,
                        portOptions = ttyDevices,
                        baudOptions = baudOptions,
                        onPortSelected = { port ->
                            db9Port.value = port
                            sharedPre.edit().putString(context.getString(R.string.serial_db9_port_name), port).apply()
                        },
                        onBaudSelected = { baud ->
                            db9Baud.value = baud
                            sharedPre.edit().putString(context.getString(R.string.serial_db9_baud_name), baud).apply()
                        }
                    )
                    Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                    SerialPortSelectRow(
                        label = "电机驱动",
                        selectedPort = canPort.value,
                        selectedBaud = canBaud.value,
                        portOptions = ttyDevices,
                        baudOptions = baudOptions,
                        onPortSelected = { port ->
                            canPort.value = port
                            sharedPre.edit().putString(context.getString(R.string.serial_can_port_name), port).apply()
                        },
                        onBaudSelected = { baud ->
                            canBaud.value = baud
                            sharedPre.edit().putString(context.getString(R.string.serial_can_baud_name), baud).apply()
                        }
                    )
                }
            }

            // 卡片四：串口通信测试
            Card(shape = RoundedCornerShape(16.dp), elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "串口通信测试", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF333333))

                    if (systemRunning) {
                        Text(
                            text = "系统运行中，无法使用串口测试",
                            fontSize = 13.sp,
                            color = Color(0xFFFF9800),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFFF3E0), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        )
                    } else {
                        // RTK定位 测试区
                        SerialTestSection(
                            label = "RTK定位  /dev/${db9Port.value}  ${db9Baud.value}bps",
                            isOpen = db9IsOpen,
                            rxText = db9RxText,
                            txText = db9TxText,
                            hexMode = db9HexMode,
                            onToggleMode = { db9HexMode = !db9HexMode },
                            onTxTextChange = { db9TxText = it },
                            onOpenClose = {
                                if (db9IsOpen) {
                                    db9IsOpen = false
                                    try { mTestSerialPortDB9?.tryClose() } catch (_: Exception) {}
                                    mTestSerialPortDB9 = null
                                    db9RxText += "--- 已关闭 ---\n"
                                } else {
                                    try {
                                        val baud = db9Baud.value.toIntOrNull() ?: 230400
                                        mTestSerialPortDB9 = SerialPort(File("/dev/${db9Port.value}"), baud)
                                        db9IsOpen = true
                                        db9RxText += "--- 已打开 /dev/${db9Port.value} @ ${baud}bps ---\n"
                                    } catch (e: Exception) {
                                        db9RxText += "[打开失败: ${e.message}]\n"
                                    }
                                }
                            },
                            onSend = {
                                val port = mTestSerialPortDB9
                                if (port != null && db9TxText.isNotBlank()) {
                                    try {
                                        val bytes = if (db9HexMode) {
                                            db9TxText.trim().split("\\s+".toRegex())
                                                .mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                                        } else {
                                            db9TxText.replace("\\r", "\r").replace("\\n", "\n")
                                                .replace("\\t", "\t").toByteArray(Charsets.UTF_8)
                                        }
                                        port.outputStream.write(bytes)
                                        port.outputStream.flush()
                                    } catch (e: Exception) {
                                        db9RxText += "[发送失败: ${e.message}]\n"
                                    }
                                }
                            },
                            onClear = { db9RxText = "" }
                        )

                        Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp)

                        // 电机驱动 测试区
                        SerialTestSection(
                            label = "电机驱动  /dev/${canPort.value}  ${canBaud.value}bps",
                            isOpen = canIsOpen,
                            rxText = canRxText,
                            txText = canTxText,
                            hexMode = canHexMode,
                            onToggleMode = { canHexMode = !canHexMode },
                            onTxTextChange = { canTxText = it },
                            onOpenClose = {
                                if (canIsOpen) {
                                    canIsOpen = false
                                    try { mTestSerialPortCAN?.tryClose() } catch (_: Exception) {}
                                    mTestSerialPortCAN = null
                                    canRxText += "--- 已关闭 ---\n"
                                } else {
                                    try {
                                        val baud = canBaud.value.toIntOrNull() ?: 115200
                                        mTestSerialPortCAN = SerialPort(File("/dev/${canPort.value}"), baud)
                                        canIsOpen = true
                                        canRxText += "--- 已打开 /dev/${canPort.value} @ ${baud}bps ---\n"
                                    } catch (e: Exception) {
                                        canRxText += "[打开失败: ${e.message}]\n"
                                    }
                                }
                            },
                            onSend = {
                                val port = mTestSerialPortCAN
                                if (port != null && canTxText.isNotBlank()) {
                                    try {
                                        val bytes = if (canHexMode) {
                                            canTxText.trim().split("\\s+".toRegex())
                                                .mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                                        } else {
                                            canTxText.replace("\\r", "\r").replace("\\n", "\n")
                                                .replace("\\t", "\t").toByteArray(Charsets.UTF_8)
                                        }
                                        port.outputStream.write(bytes)
                                        port.outputStream.flush()
                                    } catch (e: Exception) {
                                        canRxText += "[发送失败: ${e.message}]\n"
                                    }
                                }
                            },
                            onClear = { canRxText = "" }
                        )

                        Divider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp)

                        // CANopen 快捷指令面板
                        CanOpenQuickCommands(
                            canIsOpen = canIsOpen,
                            onSendBytes = { bytes, label ->
                                val port = mTestSerialPortCAN
                                if (port != null) {
                                    try {
                                        port.outputStream.write(bytes)
                                        port.outputStream.flush()
                                        val hex = bytes.joinToString(" ") { "%02X".format(it) }
                                        canRxText = (canRxText + "→ $label  [$hex]\n").takeLast(4000)
                                    } catch (e: Exception) {
                                        canRxText += "[发送失败: ${e.message}]\n"
                                    }
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== CAN 实时监控卡片（主程序运行时查看各电机回包原始数据）=====
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                elevation = 2.dp,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // 标题行
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (systemRunning) Color(0xFF4CAF50) else Color(0xFFBBBBBB),
                                    RoundedCornerShape(50)
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("CAN 实时监控", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            if (systemRunning) "运行中" else "未运行",
                            fontSize = 11.sp,
                            color = if (systemRunning) Color(0xFF4CAF50) else Color.Gray
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    // 每 500ms 刷新一次显示
                    val snapshot by produceState(initialValue = Array(8) { "---" }) {
                        while (true) {
                            value = canMonitorData.copyOf()
                            delay(500)
                        }
                    }

                    // 2列网格布局，每行2个电机卡
                    val motorCount = snapshot.size
                    val rows = (motorCount + 1) / 2
                    for (rowIdx in 0 until rows) {
                        if (rowIdx > 0) Spacer(Modifier.height(5.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (colIdx in 0..1) {
                                val motorIdx = rowIdx * 2 + colIdx
                                if (motorIdx >= motorCount) {
                                    Box(modifier = Modifier.weight(1f))
                                } else {
                                    val text = snapshot[motorIdx]
                                    val rpmVal = Regex("rpm=([\\d.]+)").find(text)
                                        ?.groupValues?.get(1)?.toDoubleOrNull()
                                    val hasData = text != "---"
                                    val isActive = rpmVal != null && rpmVal >= 1.0

                                    val bgColor = when {
                                        !hasData -> Color(0xFFF5F5F5)
                                        isActive -> Color(0xFFE8F5E9)
                                        else -> Color(0xFFFFF8E1)
                                    }
                                    val labelColor = when {
                                        !hasData -> Color(0xFF9E9E9E)
                                        isActive -> Color(0xFF2E7D32)
                                        else -> Color(0xFFE65100)
                                    }
                                    val dotColor = when {
                                        !hasData -> Color(0xFFBBBBBB)
                                        isActive -> Color(0xFF4CAF50)
                                        else -> Color(0xFFFF9800)
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(bgColor, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 8.dp, vertical = 7.dp)
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .background(dotColor, RoundedCornerShape(50))
                                                )
                                                Spacer(Modifier.width(5.dp))
                                                Text(
                                                    "M${motorIdx + 1}",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = labelColor
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    when {
                                                        !hasData -> "无信号"
                                                        isActive -> "${"%.1f".format(rpmVal)} rpm"
                                                        else -> "已停止"
                                                    },
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = labelColor
                                                )
                                            }
                                            if (hasData) {
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    text,
                                                    fontSize = 9.sp,
                                                    color = Color(0xFFAAAAAA),
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== 一键检测卡片 =====
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                elevation = 2.dp,
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("一键检测", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("自动扫速测试各电机与RTK信号", fontSize = 11.sp, color = Color.Gray)
                        }
                        Button(
                            onClick = {
                                if (!diagRunning) {
                                    diagRunning = true
                                    diagProgress = "正在初始化..."
                                    diagReport = ""
                                    // 记录测试模式原状态，检测结束/中断后恢复
                                    val wasTestMode = testModeSwitchIsOnState.value
                                    if (!testModeSwitchIsOnState.value) {
                                        testModeSwitchIsOnState.value = true
                                        sharedPre.edit().putString(context.getString(R.string.testMode_Switch_name), "1").apply()
                                    }
                                    // 恢复测试模式到原始状态
                                    fun restoreTestMode() {
                                        if (!wasTestMode && testModeSwitchIsOnState.value) {
                                            testModeSwitchIsOnState.value = false
                                            sharedPre.edit().putString(context.getString(R.string.testMode_Switch_name), "0").apply()
                                        }
                                    }
                                    // 打断并替换当前诊断音频（受语音提示助手开关控制）
                                    fun playDiagAudio(resId: Int) {
                                        if (!errorSoundSwitchIsOnState.value) return
                                        try { diagPlayerRef.value?.stop(); diagPlayerRef.value?.release() } catch (_: Exception) {}
                                        diagPlayerRef.value = try {
                                            MediaPlayer.create(context, resId)?.apply {
                                                setOnCompletionListener { release(); diagPlayerRef.value = null }
                                                start()
                                            }
                                        } catch (_: Exception) { null }
                                    }
                                    val startList = listOf(R.raw.lookf1, R.raw.lookf2)
                                    val si = ((System.nanoTime() % 2 + 2) % 2).toInt()
                                    playDiagAudio(startList[si])
                                    diagScope.launch {
                                        try {
                                            runDiagnosticTest(
                                                context = context,
                                                onProgress = { diagProgress = it },
                                                onComplete = { result, isFailure ->
                                                    diagReport = result
                                                    diagProgress = ""
                                                    diagRunning = false
                                                    restoreTestMode()
                                                    if (isFailure) {
                                                        playDiagAudio(R.raw.fail8)
                                                    } else {
                                                        val doneList = listOf(R.raw.looko1, R.raw.looko2)
                                                        val di = ((System.nanoTime() % 2 + 2) % 2).toInt()
                                                        playDiagAudio(doneList[di])
                                                    }
                                                }
                                            )
                                        } finally {
                                            // 协程被取消或异常中断时，确保状态复原
                                            if (diagRunning) {
                                                diagRunning = false
                                                diagProgress = ""
                                                restoreTestMode()
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !diagRunning && !systemRunning,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = Color(0xFF1565C0),
                                disabledBackgroundColor = Color(0xFFBBBBBB)
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (diagRunning) "检测中..." else "开始检测", color = Color.White, fontSize = 13.sp)
                        }
                    }

                    if (systemRunning && !diagRunning) {
                        Text(
                            "系统运行中，请先停止作业再进行检测",
                            fontSize = 12.sp,
                            color = Color(0xFFFF9800),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    if (diagRunning && diagProgress.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF1565C0))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(diagProgress, fontSize = 12.sp, color = Color(0xFF555555))
                    }

                    if (diagReport.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .background(Color(0xFFF8F9FA), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            DiagnosticReportView(diagReport)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DiagnosticReportView(report: String) {
    if (report.isBlank()) return

    // 将报告按 ▌ 分段，每段为独立卡片
    data class ReportSection(val title: String, val lines: MutableList<String> = mutableListOf())
    val sections = mutableListOf<ReportSection>()
    var cur: ReportSection? = null
    for (line in report.lines()) {
        when {
            line.startsWith("════") -> {}
            line.startsWith("▌ ") -> { cur = ReportSection(line.removePrefix("▌ ").trim()); sections.add(cur) }
            cur != null -> cur.lines.add(line)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // ── 标题卡片 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1565C0), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("排肥机一键检测报告", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "电机 · RTK · 参数  全项扫描完成",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }
                Text("◈", fontSize = 26.sp, color = Color.White.copy(alpha = 0.5f))
            }
        }

        // ── 每个区块渲染为独立卡片 ──
        sections.forEach { section ->
            DiagnosticSectionCard(title = section.title, lines = section.lines)
        }

        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun DiagnosticSectionCard(title: String, lines: List<String>) {
    // 根据该区块内容判断整体状态（用于卡片头颜色）
    val hasError = lines.any { it.trimStart().let { t -> t.contains("✗") && !t.contains("✓") } }
    val hasWarn  = lines.any { it.trimStart().let { t -> t.startsWith("!") || (t.startsWith("[") && t.trimEnd().endsWith("]")) } }

    val accentColor = when {
        hasError -> Color(0xFFC62828)
        hasWarn  -> Color(0xFFE65100)
        else     -> Color(0xFF2E7D32)
    }
    val headerBg = when {
        hasError -> Color(0xFFFFEBEE)
        hasWarn  -> Color(0xFFFFF8E1)
        else     -> Color(0xFFF1F8E9)
    }
    val statusBadge = when {
        hasError -> "✗"
        hasWarn  -> "!"
        else     -> "✓"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        elevation = 1.dp,
        backgroundColor = Color.White
    ) {
        Column {
            // 卡片标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(15.dp)
                        .background(accentColor, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = accentColor, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .background(accentColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(statusBadge, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            // 卡片内容
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                var inSugg = false
                for (line in lines) {
                    val trimmed = line.trimStart()
                    val indent = line.length - trimmed.length
                    when {
                        trimmed.isBlank() -> {}

                        // 建议块标题 [xxx]
                        trimmed.startsWith("[") && trimmed.trimEnd().endsWith("]") -> {
                            inSugg = true
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFBE9E7), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.width(3.dp).height(13.dp).background(Color(0xFFBF360C), RoundedCornerShape(2.dp)))
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    "▶  " + trimmed.removeSurrounding("[", "]"),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = Color(0xFFBF360C)
                                )
                            }
                        }

                        // ✓ 成功行
                        trimmed.contains("✓") && !trimmed.contains("✗") -> {
                            inSugg = false
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF1F8E9), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(trimmed, fontSize = 11.sp, color = Color(0xFF2E7D32), lineHeight = 17.sp)
                            }
                        }

                        // ✗ 错误行
                        trimmed.contains("✗") && !trimmed.contains("✓") -> {
                            inSugg = false
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFFEBEE), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(trimmed, fontSize = 11.sp, color = Color(0xFFC62828), lineHeight = 17.sp)
                            }
                        }

                        // ! 警告行
                        trimmed.startsWith("!") -> {
                            inSugg = false
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFFF8E1), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(trimmed, fontSize = 11.sp, color = Color(0xFFE65100), lineHeight = 17.sp)
                            }
                        }

                        // 建议块内：编号行或深缩进行
                        inSugg && (indent >= 4 || trimmed.matches(Regex("\\d+\\..*"))) -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFFF3E0))
                                    .padding(start = 20.dp, end = 10.dp, top = 2.dp, bottom = 2.dp)
                            ) {
                                Text(
                                    trimmed,
                                    fontSize = 10.sp,
                                    color = Color(0xFF5D4037),
                                    lineHeight = 15.sp
                                )
                            }
                        }

                        // 建议块内：普通说明行（原因/解决等）
                        inSugg && trimmed.isNotBlank() -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFFF3E0))
                                    .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 2.dp)
                            ) {
                                Text(
                                    trimmed,
                                    fontSize = 10.sp,
                                    color = Color(0xFF6D4C41),
                                    lineHeight = 15.sp
                                )
                            }
                        }

                        // 其他普通信息行
                        trimmed.isNotBlank() -> {
                            Text(
                                trimmed,
                                fontSize = 11.sp,
                                color = Color(0xFF555555),
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SerialPortSelectRow(
    label: String,
    selectedPort: String,
    selectedBaud: String,
    portOptions: List<String>,
    baudOptions: List<String>,
    onPortSelected: (String) -> Unit,
    onBaudSelected: (String) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF333333), modifier = Modifier.width(72.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SimpleDropdown(
                    selected = selectedPort,
                    options = portOptions,
                    placeholder = "扫描选择",
                    onSelected = onPortSelected,
                    modifier = Modifier.width(120.dp)
                )
                SimpleDropdown(
                    selected = selectedBaud,
                    options = baudOptions,
                    placeholder = "波特率",
                    onSelected = onBaudSelected,
                    modifier = Modifier.width(110.dp)
                )
            }
        }
        OutlinedTextField(
            value = selectedPort,
            onValueChange = onPortSelected,
            label = { Text("手动输入端口名", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SimpleDropdown(
    selected: String,
    options: List<String>,
    placeholder: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (selected.isNotEmpty()) selected else placeholder,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(onClick = { expanded = false }) {
                    Text("无可用端口", fontSize = 13.sp, color = Color.Gray)
                }
            }
            options.forEach { option ->
                DropdownMenuItem(onClick = {
                    onSelected(option)
                    expanded = false
                }) {
                    Text(
                        text = option,
                        fontSize = 13.sp,
                        fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun SerialTestSection(
    label: String,
    isOpen: Boolean,
    rxText: String,
    txText: String,
    hexMode: Boolean,
    onToggleMode: () -> Unit,
    onTxTextChange: (String) -> Unit,
    onOpenClose: () -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontSize = 13.sp, color = Color(0xFF555555), modifier = Modifier.weight(1f))
            Text(
                text = if (isOpen) "● 已打开" else "● 未打开",
                fontSize = 12.sp,
                color = if (isOpen) Color(0xFF4CAF50) else Color.Gray,
                modifier = Modifier.padding(end = 10.dp)
            )
            Button(
                onClick = onOpenClose,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isOpen) Color(0xFFE53935) else Color(0xFF4CAF50)
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(text = if (isOpen) "关闭" else "打开", color = Color.White, fontSize = 13.sp)
            }
        }

        // 接收区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            val rxScrollState = rememberScrollState(rxText.length)
            Text(
                text = if (rxText.isEmpty()) "等待接收数据..." else rxText,
                fontSize = 11.sp,
                color = if (rxText.isEmpty()) Color.Gray else Color(0xFF00FF41),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.verticalScroll(rxScrollState)
            )
        }

        // 发送区 + 清除
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = txText,
                onValueChange = onTxTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (hexMode) "HEX，如：27 01 00 FF" else "ASCII，\\r\\n 会转义",
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            )
            TextButton(
                onClick = onToggleMode,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Text(if (hexMode) "HEX" else "ASCII", fontSize = 11.sp, color = Color(0xFF1565C0))
            }
            Button(
                onClick = onSend,
                enabled = isOpen,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("发送", fontSize = 13.sp)
            }
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                Text("清除", fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun AnimatedSettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    var modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
    if (onClick != null) {
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 16.dp)
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF333333))
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subtitle, fontSize = 12.sp, color = Color.Gray)
            }
        }
        content()
    }
}

@Composable
fun CounterRow(prefix: String? = null, value: Int, onIncrement: () -> Unit, onDecrement: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (prefix != null) Text(text = prefix, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(text = value.toString(), modifier = Modifier.width(40.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Column {
            IconButton(onClick = onIncrement, modifier = Modifier.size(20.dp)) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "+") }
            IconButton(onClick = onDecrement, modifier = Modifier.size(20.dp)) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "-") }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CANopen 快捷指令面板
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CanOpenQuickCommands(
    canIsOpen: Boolean,
    onSendBytes: (ByteArray, String) -> Unit
) {
    var nodeId by remember { mutableStateOf(11) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // 标题行 + Node-ID 选择器
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "CANopen 快捷指令",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF555555)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Node-ID:", fontSize = 12.sp, color = Color.Gray)
                OutlinedButton(
                    onClick = { if (nodeId > 11) nodeId-- },
                    modifier = Modifier.size(28.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(4.dp)
                ) { Text("−", fontSize = 14.sp) }
                Text(
                    text = "$nodeId",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(28.dp)
                )
                OutlinedButton(
                    onClick = { if (nodeId < 18) nodeId++ },
                    modifier = Modifier.size(28.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(4.dp)
                ) { Text("+", fontSize = 14.sp) }
            }
        }

        // 快捷发送按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // NMT 广播启动：CAN-ID=0x0000, data=[0x01,0x00], len=5
            // 27 05 00 00 00 01 00 39
            Button(
                onClick = {
                    onSendBytes(
                        byteArrayOf(0x27, 0x05, 0x00, 0x00, 0x00, 0x01, 0x00, 0x39.toByte()),
                        "NMT广播启动"
                    )
                },
                enabled = canIsOpen,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0)),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("NMT\n广播启动", color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
            }

            // SDO 读当前位置 (0x6064)：CAN-ID=0x600+N, data=[0x40,0x64,0x60,0x00,...], len=0x0B
            // 27 0B 00 [hi] [lo] 40 64 60 00 00 00 00 00 39
            Button(
                onClick = {
                    val canId = 0x600 + nodeId
                    val hi = ((canId ushr 8) and 0xFF).toByte()
                    val lo = (canId and 0xFF).toByte()
                    onSendBytes(
                        byteArrayOf(0x27, 0x0B, 0x00, hi, lo,
                            0x40, 0x64, 0x60, 0x00, 0x00, 0x00, 0x00, 0x00, 0x39.toByte()),
                        "SDO读位置 N$nodeId"
                    )
                },
                enabled = canIsOpen,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF388E3C)),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("SDO读\n当前位置", color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
            }

            // SDO 读状态字 (0x6041)：同格式，索引改为 0x6041
            Button(
                onClick = {
                    val canId = 0x600 + nodeId
                    val hi = ((canId ushr 8) and 0xFF).toByte()
                    val lo = (canId and 0xFF).toByte()
                    onSendBytes(
                        byteArrayOf(0x27, 0x0B, 0x00, hi, lo,
                            0x40, 0x41, 0x60, 0x00, 0x00, 0x00, 0x00, 0x00, 0x39.toByte()),
                        "SDO读状态字 N$nodeId"
                    )
                },
                enabled = canIsOpen,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF455A64)),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("SDO读\n状态字", color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CAN 帧解码（串口通信测试专用）
// 接收帧格式：[0x27][len][canId_lo][canId_hi][data...][0x39]  ← 小端，无帧信息字节
// ─────────────────────────────────────────────────────────────────────────────

private fun decodeCanTestFrame(canId: Int, data: ByteArray): String {
    val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date())
    val nodeId = canId and 0x7F

    return when {
        // ── CANopen 心跳 (0x701~0x77F) ──────────────────────────────────────
        canId in 0x701..0x77F -> {
            val stateStr = if (data.isNotEmpty()) when (data[0].toInt() and 0x7F) {
                0x00 -> "启动中(Boot-Up)"
                0x04 -> "已停止(Stopped)"
                0x05 -> "运行(Operational)"
                0x7F -> "预操作(Pre-Op)"
                else -> "未知(0x%02X)".format(data[0].toInt() and 0xFF)
            } else "无数据"
            "[$ts] 心跳  Node$nodeId  $stateStr"
        }

        // ── CANopen SDO 回复 (0x581~0x5FF) ──────────────────────────────────
        canId in 0x581..0x5FF -> {
            if (data.size < 4) {
                "[$ts] SDO回复  Node$nodeId  [数据不足: ${data.joinToString(" ") { "%02X".format(it) }}]"
            } else {
                val cs    = data[0].toInt() and 0xFF
                val index = ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                val sub   = data[3].toInt() and 0xFF
                val csStr = when (cs and 0xE0) {
                    0x60 -> "写成功"
                    0x40 -> "SDO错误"
                    0x43 -> "读回4B"
                    0x47 -> "读回3B"
                    0x4B -> "读回2B"
                    0x4F -> "读回1B"
                    else -> "CS=0x%02X".format(cs)
                }
                val value = if (data.size >= 8 && (cs and 0xE0) != 0x60 && (cs and 0xE0) != 0x40) {
                    val v = ((data[7].toLong() and 0xFF) shl 24) or
                            ((data[6].toLong() and 0xFF) shl 16) or
                            ((data[5].toLong() and 0xFF) shl 8)  or
                            (data[4].toLong() and 0xFF)
                    " val=0x%08X(%d)".format(v, v)
                } else ""
                "[$ts] SDO    Node$nodeId  $csStr  idx=0x%04X sub=0x%02X$value".format(index, sub)
            }
        }

        // ── CANopen TPDO1 (0x181~0x1FF) ─────────────────────────────────────
        canId in 0x181..0x1FF -> {
            if (data.size >= 6) {
                val pos = ((data[3].toLong() and 0xFF) shl 24) or
                          ((data[2].toLong() and 0xFF) shl 16) or
                          ((data[1].toLong() and 0xFF) shl 8)  or
                          (data[0].toLong() and 0xFF)
                val sw  = ((data[5].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
                "[$ts] TPDO1  Node$nodeId  pos=$pos  sw=0x%04X".format(sw)
            } else {
                val raw = data.joinToString(" ") { "%02X".format(it) }
                "[$ts] TPDO1  Node$nodeId  [$raw]"
            }
        }

        // ── CANopen TPDO2 (0x281~0x2FF) ─────────────────────────────────────
        canId in 0x281..0x2FF -> {
            val raw = data.joinToString(" ") { "%02X".format(it) }
            "[$ts] TPDO2  Node${canId - 0x280}  [$raw]"
        }

        // ── CANopen TPDO3 (0x381~0x3FF) ─────────────────────────────────────
        // 电机默认 TPDO3 通常包含状态字（2B LE）+ 其他字段
        canId in 0x381..0x3FF -> {
            val raw = data.joinToString(" ") { "%02X".format(it) }
            if (data.size >= 2) {
                val sw = ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
                val swDesc = when {
                    sw and 0x0008 != 0 -> "故障"
                    sw and 0x0004 != 0 -> "已使能"
                    sw and 0x0002 != 0 -> "已上电"
                    sw and 0x0040 != 0 -> "禁用"
                    else -> "sw=0x%04X".format(sw)
                }
                "[$ts] TPDO3  Node${canId - 0x380}  $swDesc  [$raw]"
            } else {
                "[$ts] TPDO3  Node${canId - 0x380}  [$raw]"
            }
        }

        // ── 施肥电机 (CAN-ID 0x01~0x27) ─────────────────────────────────────
        canId in 0x01..0x27 -> {
            val raw = data.joinToString(" ") { "%02X".format(it) }
            "[$ts] 施肥电机  ID=0x%02X  [$raw]".format(canId)
        }

        // ── 其他 ─────────────────────────────────────────────────────────────
        else -> {
            val raw = data.joinToString(" ") { "%02X".format(it) }
            "[$ts] 未知  CAN-ID=0x%03X  [$raw]".format(canId)
        }
    }
}
