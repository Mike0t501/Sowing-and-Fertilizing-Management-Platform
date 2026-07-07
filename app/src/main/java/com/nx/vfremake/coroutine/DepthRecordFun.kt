/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2026年7月4日
 * @file    :
 * @brief   :播种深度实验数据CSV记录器（手动记录与一键性能测试共用）
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 毫秒级时间戳 + 相对时间ms，便于Origin/Excel分析伺服动态响应
 ***********************************************************************************************************
 */

package com.nx.vfremake.coroutine

import android.content.Context
import android.os.SystemClock
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.funClass.DocuAndManageFun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播种深度实验数据记录协程类
 *
 * 与 MyWriteSaveFun 的差异：
 *   - 采样周期可配置（默认 100ms，捕捉伺服运动动态）
 *   - 时间戳毫秒级（HH:mm:ss.SSS），另附相对时间ms列（elapsedRealtime 差值，防系统校时跳变）
 *   - 文件前缀可配置：手动记录 depthRec_、一键性能测试 depthTest_
 * 保存结果同样经 writeSaveNotice 通知 UI，文件可在「查看已保存记录」界面查看。
 */
class DepthRecordFun {
    // 定义一个协程作用域，保存作业引用的HashSet，以便于管理
    private val scope = CoroutineScope(Dispatchers.IO)
    private val jobs = HashSet<Job>()

    // 开始写出数据标志位
    var isRunning = false

    /**
     * 停止数据收集和保存文件
     * @note   取消协程后由 finally 块完成 flush/close 与保存结果通知
     */
    fun shutdown() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        isRunning = false
    }

    /**
     * 开始数据收集和写入CSV
     * @param  context:上下文
     * @param  mVariableFertViewModel:保存结果经 writeSaveNotice 通知UI
     * @param  filePrefix:文件名前缀（"depthRec" 或 "depthTest"）
     * @param  header:CSV表头
     * @param  intervalMs:采样周期ms
     * @param  getRows:每次采样返回若干行（不含时间戳/相对时间两列），入参为当前相对时间ms
     * @note
     */
    fun start(
        context: Context,
        mVariableFertViewModel: VariableFertViewModel,
        filePrefix: String,
        header: List<String>,
        intervalMs: Long = 100L,
        getRows: (relativeMs: Long) -> List<List<String>>,
    ) {
        // 格式化当前时间作为文件名一部分
        val timeStampfilename =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${filePrefix}_$timeStampfilename.csv"

        jobs.add(scope.launch {
            // 文件创建放在IO协程内：目录未设置/创建失败时通知用户而非主线程崩溃
            var fileWriter: OutputStreamWriter? = null
            var dataRowCount = 0
            var failed = false
            // 毫秒级绝对时间戳格式；相对时间以启动时刻 elapsedRealtime 为零点
            val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            val t0 = SystemClock.elapsedRealtime()
            try {
                fileWriter = DocuAndManageFun().createExperimentCsvWriter(context, fileName)
                // 先写出表头行
                fileWriter.append(header.joinToString(",") + "\n")
                // 开始写出
                while (isActive) {
                    val relativeMs = SystemClock.elapsedRealtime() - t0
                    val absTs = tsFormat.format(Date())
                    for (row in getRows(relativeMs)) {
                        fileWriter.append("$absTs,$relativeMs," + row.joinToString(",") + "\n")
                        dataRowCount++
                    }
                    delay(intervalMs)
                }
            } catch (e: CancellationException) {
                throw e // shutdown 的正常取消路径，收尾统一走 finally
            } catch (e: Exception) {
                e.printStackTrace()
                failed = true
                isRunning = false
                mVariableFertViewModel.writeSaveNotice.postValue(
                    true to "实验数据保存失败：${e.message}"
                )
            } finally {
                try {
                    fileWriter?.flush()
                    fileWriter?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                if (!failed && fileWriter != null) {
                    mVariableFertViewModel.writeSaveNotice.postValue(
                        false to "实验数据已保存：$fileName，共 $dataRowCount 条记录"
                    )
                }
            }
        })
        isRunning = true
    }

    companion object {
        /**
         * 构造播深实验数据CSV表头（手动记录与一键测试统一格式）
         * 列顺序必须与 getRows 返回的行严格一致（前两列时间戳/相对时间由记录器自动写出）
         */
        fun buildDepthRecordHeader(): List<String> = listOf(
            "timestamp", "elapsed_ms", "motor_no", "test_stage",
            "target_depth_mm", "current_depth_mm", "encoder_position", "is_online", "alarm_code"
        )
    }
}
