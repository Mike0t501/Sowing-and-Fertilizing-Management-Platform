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
import android.util.Log
import android.widget.Toast
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.funClass.MySharedPreFun
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
        getData: () -> List<List<String>>, // 使用lambda表达式来动态获取数据
    ) {
        // 格式化当前时间作为文件名一部分
        val timeStampfilename =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "fertMsg_$timeStampfilename.csv"

        // 创建或打开文件
        val fileWriter = getFileWriter(context, fileName)

        // 在协程中执行数据收集和写入任务
        jobs.add(scope.launch {
            try {
                // 开始写出
                while (isActive) {
                    val dataGroups = getData() // List<List<Double>>
                    // 对每组数据进行处理
                    val timeStamp =
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    for (dataGroup in dataGroups) {
                        val dataRow = "$timeStamp," + dataGroup.joinToString(",") + "\n"
                        fileWriter.append(dataRow) // 将时间戳和数据行追加到文件中
                    }
                    delay(200) // 每200毫秒收集并写入一次数据
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                fileWriter.flush()
                fileWriter.close() // 关闭输出流
            }
        })
        isRunning = true
    }

    /**
     * 获取文件写入器
     * @param  context:上下文
     * @param  fileName:文件名
     * @return outputStream:输出流
     * @note
     */
    private fun getFileWriter(context: Context, fileName: String): OutputStreamWriter {
        // 从SharedPreferences获取documentUri
        val documentUri = MySharedPreFun(context).getMySharedPre()
            .getString(context.getString(R.string.myWriteDir_DocumentUri_name), null)

        Log.d("writeSaveData", "$documentUri")

        // 使用SAF操作，因为使用的时间戳创建文件，不存在重复创建新文件的问题
        val fileUri = DocumentsContract.createDocument(
            context.contentResolver, Uri.parse(documentUri), "text/csv", fileName
        ) ?: throw IOException("文件创建失败")
        // 获取documentUri对应的输出流
        val outputStream = context.contentResolver.openOutputStream(fileUri, "w")
            ?: throw IOException("写出数据输出流获取失败")
        return OutputStreamWriter(outputStream)
    }

    /**
     * 保存实验数据
     * @param  n:行数，每行都做成一个List，n个List再做成一个List
     * @param  mVariableFertViewModel:存储数据的viewmodel
     * @return List<List<String>>：各行要保存的数据
     * @note
     */
    fun getMySaveData(n: Int, mVariableFertViewModel: VariableFertViewModel): List<List<String>> {
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

        val dataList = mutableListOf<List<String>>() // 创建一个可变的列表来存储数据
        for (i in 0 until n) {
            // 获取当前索引的 dantiPointX 和 dantiPointY
            val dantiPointLo = (dantiLLGeo?.getOrNull(i)?.x)
            val dantiPointLa = dantiLLGeo?.getOrNull(i)?.y
            val data = mutableListOf<String>().apply {
                // 当数据为-1时，认为该处数据无效
                add(i.toString())
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
