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
import com.nx.vfremake.funClass.DepthRecordSchema
import com.nx.vfremake.funClass.DepthNodeCfgClient
import com.nx.vfremake.funClass.DepthQualityService
import com.nx.vfremake.funClass.DepthEventSchema
import com.nx.vfremake.data.DepthNodeState
import com.nx.vfremake.data.activeSowingDepthMotorIndices
import com.nx.vfremake.mSPParamData
import com.nx.vfremake.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import android.net.Uri
import java.time.Instant
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
    @Volatile var isRunning = false
    private var ready = CompletableDeferred(false)

    /** 一键测试必须等待文件与元数据准备好再开始梯度，避免漏记首段运动。 */
    suspend fun awaitReady(): Boolean = ready.await()

    /**
     * 停止数据收集和保存文件
     * @note   取消协程后由 finally 块完成 flush/close 与保存结果通知
     */
    fun shutdown() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        isRunning = false
        ready.complete(false)
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
        recordRows: List<Int> = activeSowingDepthMotorIndices(mSPParamData.rowNumber,
            mVariableFertViewModel.activeMotorsState.value ?: mSPParamData.activeMotors),
        getRows: (relativeMs: Long) -> List<List<String>>,
    ): Boolean {
        require(header == DepthRecordSchema.HEADER && intervalMs in 20..1000)
        if (isRunning || !mVariableFertViewModel.tryBeginDepthRecording()) {
            ready = CompletableDeferred(false)
            mVariableFertViewModel.writeSaveNotice.postValue(true to "已有播深记录会话，请先停止")
            return false
        }
        ready = CompletableDeferred()
        isRunning = true
        // 格式化当前时间作为文件名一部分
        val timeStampfilename =
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())
        val fileName = "${filePrefix}_$timeStampfilename.csv"
        val eventFileName = "depthEvt_$timeStampfilename.csv"

        jobs.add(scope.launch {
            // 文件创建放在IO协程内：目录未设置/创建失败时通知用户而非主线程崩溃
            var fileWriter: OutputStreamWriter? = null
            var eventWriter: OutputStreamWriter? = null
            var eventSession: DepthQualityService.RecordingSession? = null
            var dataRowCount = 0
            var failed = false
            var metadataUri: Uri? = null
            var metadataWatcher: Job? = null
            val history = mutableListOf<DepthRecordSchema.ConfigSnapshot>()
            var metadataState: DepthNodeState? = null
            val startedAt = Instant.now().toString()
            suspend fun writeMetadata() {
                val uri = metadataUri ?: return
                val state = metadataState ?: return
                val json = DepthRecordSchema.metadata(state, BuildConfig.VERSION_NAME, startedAt, intervalMs, history.toList(), recordRows, eventFileName)
                checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法更新记录元数据" }
                    .bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            }
            // 毫秒级绝对时间戳格式；相对时间以启动时刻 elapsedRealtime 为零点
            val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            try {
                val manager = DocuAndManageFun()
                for (row in recordRows) {
                    val channel = mVariableFertViewModel.currentDepthNodeState().channels[row]
                    val readback = if (channel.isOnline && channel.versionCompatible)
                        mVariableFertViewModel.depthNodeClient.readAll(channel.baseId)
                    else DepthNodeCfgClient.Readback(channel.baseId, emptyMap(), listOf("节点离线或版本不兼容，当前参数未知"), SystemClock.elapsedRealtime())
                    history.add(DepthRecordSchema.configSnapshot(readback,
                        mVariableFertViewModel.currentDepthNodeState().channels[row], 0))
                }
                metadataState = mVariableFertViewModel.currentDepthNodeState()
                metadataUri = manager.createExperimentDocument(context, fileName.removeSuffix(".csv") + ".meta.json", "application/json")
                writeMetadata()
                fileWriter = DocuAndManageFun().createExperimentCsvWriter(context, fileName)
                // 写出 UTF-8 BOM：test_stage 列含中文（手动记录固定为"手动"，一键测试为
                // "运行 20.0→40.0""停留""超时"等）。文件是 UTF-8 无 BOM，Excel 在中文
                // Windows 上默认按 GBK 打开会乱码（"手动"→"鎵嬪姩"）；BOM 让 Excel 正确识别 UTF-8。
                fileWriter.write(0xFEFF)  // BOM 字符 U+FEFF，UTF-8 写出为 EF BB BF
                // 先写出表头行
                fileWriter.append(DepthRecordSchema.csvLine(header))
                fileWriter.flush()
                eventWriter = manager.createExperimentCsvWriter(context, eventFileName)
                eventWriter.write(0xFEFF)
                eventWriter.append(DepthRecordSchema.csvLine(DepthEventSchema.HEADER))
                eventWriter.flush()
                val t0 = SystemClock.elapsedRealtime()
                eventSession = mVariableFertViewModel.depthQualityService.beginRecording(recordRows, t0)
                ready.complete(true)
                metadataWatcher = launch {
                    val knownCrc = recordRows.associateWith { mVariableFertViewModel.currentDepthNodeState().channels[it].cfgCrc }.toMutableMap()
                    try { while (isActive) {
                        delay(500)
                        for (row in recordRows) {
                            val channel = mVariableFertViewModel.currentDepthNodeState().channels[row]
                            if (channel.isOnline && channel.cfgCrc != knownCrc[row]) {
                                val readback = mVariableFertViewModel.depthNodeClient.readAll(channel.baseId)
                                val current = mVariableFertViewModel.currentDepthNodeState().channels[row]
                                history.add(DepthRecordSchema.configSnapshot(readback, current, SystemClock.elapsedRealtime() - t0))
                                knownCrc[row] = current.cfgCrc
                                writeMetadata()
                            }
                        }
                    } } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        failed = true
                        mVariableFertViewModel.writeSaveNotice.postValue(true to "配置元数据更新失败，记录已停止：${e.message}")
                        this@DepthRecordFun.shutdown()
                    }
                }
                // 开始写出
                // 定速调度：睡到「t0 + 第n拍×周期」的绝对时刻，而不是写完再 delay(周期)。
                // 后者的实际周期 = 设定值 + 本次采样耗时，误差单向累积——100ms 下可忽略，
                // 20ms 下就是 10%+ 的系统性拉长（30s 实验少采一两百拍）。
                var tick = 0L
                while (isActive) {
                    val relativeMs = SystemClock.elapsedRealtime() - t0
                    val absTs = tsFormat.format(Date())
                    for (row in getRows(relativeMs)) {
                        val motorIndex = (row.firstOrNull()?.toIntOrNull() ?: error("记录缺少行号")) - 1
                        val channel = mVariableFertViewModel.currentDepthNodeState().channels[motorIndex]
                        val sample = mVariableFertViewModel.measuredDepthFacade.forRow(motorIndex, channel)
                        val complete = DepthRecordSchema.completeRow(row, channel, sample)
                        fileWriter.append(DepthRecordSchema.csvLine(listOf(absTs, relativeMs.toString()) + complete))
                        dataRowCount++
                    }
                    eventSession.drain().forEach { event ->
                        eventWriter.append(DepthRecordSchema.csvLine(DepthEventSchema.row(event, eventSession.startedMs)))
                    }
                    tick = maxOf(tick + 1, (SystemClock.elapsedRealtime() - t0) / intervalMs + 1)
                    // 落后于计划时刻（sleep <= 0）就立即进入下一拍，不做补偿式追赶——
                    // 追赶会连续爆发写出多拍相同数据，反而污染动态响应曲线。
                    val sleep = t0 + tick * intervalMs - SystemClock.elapsedRealtime()
                    if (sleep > 0) delay(sleep)
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
                withContext(NonCancellable) {
                    metadataWatcher?.cancelAndJoin()
                    try {
                        eventSession?.let { session ->
                            mVariableFertViewModel.depthQualityService.endRecording(session)
                            session.drain().forEach { event -> eventWriter?.append(
                                DepthRecordSchema.csvLine(DepthEventSchema.row(event, session.startedMs))) }
                        }
                    } catch (e: Exception) {
                        failed = true
                        mVariableFertViewModel.writeSaveNotice.postValue(true to "事件保存失败：${e.message}")
                    }
                    try { writeMetadata() } catch (e: Exception) {
                        failed = true
                        mVariableFertViewModel.writeSaveNotice.postValue(true to "元数据保存失败：${e.message}")
                    }
                }
                try {
                    fileWriter?.flush()
                    fileWriter?.close()
                    eventWriter?.flush()
                    eventWriter?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                    failed = true
                    mVariableFertViewModel.writeSaveNotice.postValue(true to "记录收尾失败：${e.message}")
                }
                if (!failed && fileWriter != null) {
                    mVariableFertViewModel.writeSaveNotice.postValue(
                        false to "实验数据已保存：$fileName，共 $dataRowCount 条记录"
                    )
                }
                ready.complete(false)
                isRunning = false
                mVariableFertViewModel.endDepthRecording()
            }
        })
        return true
    }

    companion object {
        /**
         * 构造播深实验数据CSV表头（手动记录与一键测试统一格式）
         * 列顺序必须与 getRows 返回的行严格一致（前两列时间戳/相对时间由记录器自动写出）
         *
         * 向后兼容约定：旧 9 列不改动/不重排；新增列一律追加在尾部。
         */
        fun buildDepthRecordHeader(): List<String> = DepthRecordSchema.HEADER

    }
}
