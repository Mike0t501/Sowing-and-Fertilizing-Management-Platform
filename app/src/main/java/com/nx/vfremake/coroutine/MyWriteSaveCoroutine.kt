/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2024年6月27日11:01:45
 * @file    :
 * @brief   :写出存储作业过程数据有关
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 协程开始、停止写出
 * 要保存的数据
 * 写出测试
 ***********************************************************************************************************
 */

package com.nx.vfremake.coroutine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.funClass.DocuAndManageFun
import com.nx.vfremake.funClass.MySharedPreFun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 写出保存试验数据协程类
 * @note
 */
class MyWriteSaveFun {
    // 定义一个协程作用域，保存作业引用的HashSet，以便于管理
    private val scope = CoroutineScope(Dispatchers.IO)
    private val jobs = HashSet<Job>()

    // 开始写出数据标志位
     var isRunning = false

    /**
     * 停止数据收集和保存文件
     * @note
     */
    fun shutdown() {
        // 停止所有数据收集协程
        jobs.forEach { it.cancel() }
        jobs.clear() // 清空作业记录
        isRunning = false
    }

    /**
     * 开始数据收集和写入CSV的函数
     * @param  context:上下文
     * @param  getData:获取数据函数
     * @return
     * @note
     */
    fun start(
        context: Context,
        mVariableFertViewModel: VariableFertViewModel, // 保存结果经 writeSaveNotice 通知主界面
        header: List<String>, // CSV表头，随所选字段组动态生成
        getData: () -> List<List<String>>, // 使用lambda表达式来动态获取数据
    ) {
        // 格式化当前时间作为文件名一部分
        val timeStampfilename =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "fertMsg_$timeStampfilename.csv"

        // 在协程中执行数据收集和写入任务
        jobs.add(scope.launch {
            // 文件创建放在IO协程内：目录未设置/创建失败时通知用户而非主线程崩溃
            var fileWriter: OutputStreamWriter? = null
            var dataRowCount = 0
            var failed = false
            try {
                fileWriter = getFileWriter(context, fileName)
                // 先写出表头行
                fileWriter.append(header.joinToString(",") + "\n")
                // 开始写出
                while (isActive) {
                    val dataGroups = getData() // List<List<Double>>
                    // 对每组数据进行处理
                    val timeStamp =
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    for (dataGroup in dataGroups) {
                        val dataRow = "$timeStamp," + dataGroup.joinToString(",") + "\n"
                        fileWriter.append(dataRow) // 将时间戳和数据行追加到文件中
                        dataRowCount++
                    }
                    delay(200) // 每200毫秒收集并写入一次数据
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
                    fileWriter?.close() // 关闭输出流
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

    /**
     * 获取文件写入器
     * @param  context:上下文
     * @param  fileName:文件名
     * @return outputStream:输出流
     * @note   SAF创建逻辑统一收敛到 DocuAndManageFun.createExperimentCsvWriter
     */
    private fun getFileWriter(context: Context, fileName: String): OutputStreamWriter {
        return DocuAndManageFun().createExperimentCsvWriter(context, fileName)
    }

    /**
     * 构造 CSV 表头，列顺序必须与 getMySaveData 严格一致
     * @param  includeFert:是否包含施肥数据列
     * @param  includeDepth:是否包含播深数据列
     * @return List<String>：表头列名
     * @note
     */
    fun buildSaveHeader(includeFert: Boolean, includeDepth: Boolean): List<String> {
        val header = mutableListOf("时间戳", "序号")
        if (includeFert) {
            header.addAll(
                listOf(
                    "前进速度", "经度", "纬度", "监测电压", "电机转速",
                    "应施肥量", "监测施肥量", "转速转换施肥量", "准确率"
                )
            )
        }
        if (includeDepth) {
            header.addAll(
                listOf("目标深度mm", "实际深度mm", "编码器位置", "是否在线", "报警码")
            )
        }
        return header
    }

    /**
     * 保存实验数据
     * @param  n:行数，每行都做成一个List，n个List再做成一个List
     * @param  mVariableFertViewModel:存储数据的viewmodel
     * @param  includeFert:是否包含施肥数据列
     * @param  includeDepth:是否包含播深数据列
     * @return List<List<String>>：各行要保存的数据
     * @note
     */
    fun getMySaveData(
        n: Int,
        mVariableFertViewModel: VariableFertViewModel,
        includeFert: Boolean,
        includeDepth: Boolean
    ): List<List<String>> {
        // 因为需要用到viewmodel，这个函数要放在activity里
        val dantiLLGeo = mVariableFertViewModel.dantiLLGeo.value
        // 我这里存储的是用于计算的速度，如果要存实际速度在解析RMC里修改
        val forwardspeed = mVariableFertViewModel.forwardspeed.value
        val monAdcV = mVariableFertViewModel.monAdcV.value
        val motorSpeed = mVariableFertViewModel.motorSpeed.value
        val fertApplied = mVariableFertViewModel.fertApplied.value

        // --- 【新增代码 1/2】获取新增的变量 ---
        val monfertflow = mVariableFertViewModel.monfertflow.value
        val confertflow = mVariableFertViewModel.confertflow.value
        val accuracyDoublearray = mVariableFertViewModel.accuracyDoublearray.value
        // -----------------------------------

        // 播深数据：按索引 1:1 取对应电机
        val depthMotors = mVariableFertViewModel.currentSowingDepthState().motors

        val dataList = mutableListOf<List<String>>() // 创建一个可变的列表来存储数据
        for (i in 0 until n) {
            // 获取当前索引的 dantiPointX 和 dantiPointY
            val dantiPointLo = (dantiLLGeo?.getOrNull(i)?.x)
            val dantiPointLa = dantiLLGeo?.getOrNull(i)?.y
            val data = mutableListOf<String>().apply {
                // 序号始终写出
                add(i.toString())
                if (includeFert) {
                    // 当数据为-1时，认为该处数据无效
                    add(if (forwardspeed == null) "-1" else "%.2f".format(forwardspeed))
                    add(if (dantiPointLo == null) "-1" else "%.9f".format(dantiPointLo))
                    add(if (dantiPointLa == null) "-1" else "%.9f".format(dantiPointLa))
                    add(if (monAdcV?.get(i) == null) "-1" else "%.2f".format(monAdcV[i]))
                    add(if (motorSpeed?.get(i) == null) "-1" else "%.2f".format(motorSpeed[i]))
                    add(if (fertApplied?.get(i) == null) "-1" else "%.2f".format(fertApplied[i]))

                    // --- 【新增代码 2/2】将新增数据添加到列表中 ---
                    // 监测施肥量 monfertflow
                    add(if (monfertflow?.get(i) == null) "-1" else "%.2f".format(monfertflow[i]))
                    // 转速转换施肥量 confertflow
                    add(if (confertflow?.get(i) == null) "-1" else "%.2f".format(confertflow[i]))
                    // 准确率
                    add(if (accuracyDoublearray?.get(i) == null) "-1" else "%.2f".format(accuracyDoublearray[i]))
                }
                if (includeDepth) {
                    // 播深电机 i 对应字段，无对应电机时写 -1
                    val motor = depthMotors.getOrNull(i)
                    add(if (motor == null) "-1" else "%.2f".format(motor.targetDepth))
                    add(if (motor == null) "-1" else "%.2f".format(motor.currentDepth))
                    add(if (motor == null) "-1" else motor.currentPosition.toString())
                    add(if (motor == null) "-1" else if (motor.isOnline) "1" else "0")
                    add(if (motor == null) "-1" else motor.alarmCode.toString())
                }
            }
            // 将当前索引的数据列表添加到 dataList 中
            dataList.add(data)
        }
        return dataList.toList() // 返回包含所有数据列表的列表
    }

    //...写出文件测试代码
    /**
     * 写出文件hello world！
     * @param  fileName:文件名称
     * @param  fileContent:文件内容
     * @param  sharedPreString:键值
     * @param  context:上下文
     * @return
     * @note
     */
    fun writeFileToSelectedDirectory(
        fileName: String, fileContent: String, sharedPreString: String, context: Context
    ) {
        val contentResolver = context.contentResolver
        val documentUri =
            MySharedPreFun(context).getMySharedPre().getString(sharedPreString, null)
        // 创建新文件的URI
        val newFileUri =
            DocumentsContract.createDocument(
                contentResolver,
                Uri.parse(documentUri),
                "text/plain",
                fileName
            )
        // 写入内容到新文件
        newFileUri?.let {
            contentResolver.openFileDescriptor(it, "w")?.use { parcelFileDescriptor ->
                FileOutputStream(parcelFileDescriptor.fileDescriptor).use { fileOutputStream ->
                    fileOutputStream.write(fileContent.toByteArray())
                }
            }
        }
        Toast.makeText(context, "写出文件成功", Toast.LENGTH_SHORT).show()
    }
    //...写出文件测试代码结束
}
