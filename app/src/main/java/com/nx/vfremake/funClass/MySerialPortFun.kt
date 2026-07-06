/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2024年6月27日10:58:20
 * @file    :
 * @brief   :串口打开、关闭、发送
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 打开串口
 * 关闭串口
 * 数据发送
 ***********************************************************************************************************
 */
package com.nx.vfremake.funClass

import android.content.Context
import android.serialport.SerialPort
import android.util.Log
import com.nx.vfremake.R
import com.nx.vfremake.VariableFertViewModel
import com.nx.vfremake.mSerialPortCAN
import com.nx.vfremake.mSerialPortDB9
import com.nx.vfremake.mTestSerialPortCAN
import com.nx.vfremake.mTestSerialPortDB9
import java.io.File

import java.io.IOException

class MySerialPortFun {

    companion object {
        /**
         * CAN 串口写出的全局互斥锁（施肥帧 + CANopen 帧共用）。
         *
         * 现场缺陷 L10：原先两条发送路径各自 synchronized(outputStream)，端口被
         * ensureCanPortOpen / reinitSerialPort 重开后 outputStream 实例更换，锁身份
         * 随之失效，两条路径的字节流可能交错写入，CSM100T 桥收到脏帧后丢弃——表现为
         * 电机偶发不响应命令。改为锁这个稳定对象，端口重开不再破坏互斥。
         */
        val CAN_TX_LOCK = Any()

        /**
         * 按需打开 CAN 串口（幂等）。端口已开（mSerialPortCAN != null）直接返回 true；
         * 为 null 时按 SharedPreferences 的端口名/波特率打开。
         *
         * 用途：解决跨页 DisposableEffect 关端口的时序竞态——点动发送、深度控制协程在发送/
         * 接收前调用此函数懒打开端口，使端口被上一页 onDispose 关闭后能自动重开，配合
         * CanReceiveCoroutine 的自愈接收循环恢复收包。
         *
         * @return true 端口已打开（或刚刚打开）；false 端口名为空 / 打开失败
         */
        fun ensureCanPortOpen(context: Context): Boolean {
            if (mSerialPortCAN != null) return true
            val sp = MySharedPreFun(context).getMySharedPre()
            val portName = sp.getString(
                context.getString(R.string.serial_can_port_name),
                context.getString(R.string.serial_can_port_defValue)
            ) ?: ""
            val baud = sp.getString(
                context.getString(R.string.serial_can_baud_name),
                context.getString(R.string.serial_can_baud_defValue)
            )?.toIntOrNull() ?: 115200
            if (portName.isEmpty()) {
                Log.e("MySerialPortFun", "ensureCanPortOpen: port name empty in SharedPreferences")
                return false
            }
            return try {
                mSerialPortCAN = SerialPort(File("/dev/$portName"), baud)
                Log.d("MySerialPortFun", "ensureCanPortOpen: opened /dev/$portName @$baud")
                true
            } catch (e: Exception) {
                Log.e("MySerialPortFun", "ensureCanPortOpen: open failed for /dev/$portName: ${e.message}", e)
                false
            }
        }
    }

    /**
     *  打开串口资源，串口名称和波特率从 SharedPreferences 读取
     * @note
     */
    fun openSerialPort(context: Context, viewModel: VariableFertViewModel) {
        // 强制关闭设置页的测试串口，防止测试口占用端口导致主程序无法打开
        try { mTestSerialPortDB9?.tryClose() } catch (_: Exception) {}
        mTestSerialPortDB9 = null
        try { mTestSerialPortCAN?.tryClose() } catch (_: Exception) {}
        mTestSerialPortCAN = null

        // 先将旧引用置 null，避免打开失败时 mSerialPortCAN 仍指向旧的已关闭实例
        mSerialPortDB9 = null
        mSerialPortCAN = null

        val sharedPre = MySharedPreFun(context).getMySharedPre()
        val db9Port = sharedPre.getString(context.getString(R.string.serial_db9_port_name), context.getString(R.string.serial_db9_port_defValue)) ?: context.getString(R.string.serial_db9_port_defValue)
        val db9Baud = sharedPre.getString(context.getString(R.string.serial_db9_baud_name), context.getString(R.string.serial_db9_baud_defValue))?.toIntOrNull() ?: 230400
        val canPort = sharedPre.getString(context.getString(R.string.serial_can_port_name), context.getString(R.string.serial_can_port_defValue)) ?: context.getString(R.string.serial_can_port_defValue)
        val canBaud = sharedPre.getString(context.getString(R.string.serial_can_baud_name), context.getString(R.string.serial_can_baud_defValue))?.toIntOrNull() ?: 115200
        try {
            mSerialPortDB9 = SerialPort(File("/dev/$db9Port"), db9Baud)
            mSerialPortCAN = SerialPort(File("/dev/$canPort"), canBaud)
            viewModel.serialPortIsRunning.value = true
        } catch (e: IOException) {
            Log.e("SerialPortInit", "不存在或无法打开: " + e.message)
            // 【修复】：部分打开时（DB9成功、CAN失败），关闭已打开的DB9，防止fd泄漏和下次EBUSY
            try { mSerialPortDB9?.tryClose() } catch (_: Exception) {}
            mSerialPortDB9 = null
            mSerialPortCAN = null
        } catch (e: SecurityException) {
            Log.e("SerialPortInit", "没有足够的权限: " + e.message)
            try { mSerialPortDB9?.tryClose() } catch (_: Exception) {}
            mSerialPortDB9 = null
            mSerialPortCAN = null
        } catch (e: Exception) {
            Log.e("SerialPortInit", "其他异常: " + e.message)
            try { mSerialPortDB9?.tryClose() } catch (_: Exception) {}
            mSerialPortDB9 = null
            mSerialPortCAN = null
        }
    }

    /**
     *  释放串口资源
     * @note 释放串口要出门关闭使用串口的线程和功能函数，以免获取输入流错误
     */
    fun releaseSerialPort(viewModel: VariableFertViewModel) {
        try {
            mSerialPortDB9?.tryClose()
            mSerialPortCAN?.tryClose()
        } catch (e: IOException) {
            Log.e("SerialPort", "串口资源释放异常:" + e.message)
        } finally {
            // 无论关闭是否异常都置 null，防止下次 openSerialPort 时持有死引用
            mSerialPortDB9 = null
            mSerialPortCAN = null
            viewModel.serialPortIsRunning.value = false
        }
    }

    /**
     * 重新初始化串口（关闭旧串口后用当前 SharedPreferences 配置重新打开，无需重启 App）
     */
    fun reinitSerialPort(context: Context, viewModel: VariableFertViewModel) {
        releaseSerialPort(viewModel)
        openSerialPort(context, viewModel)
    }

    /**
     * CAN发送信息给单片机
     * @param  data:数据
     * @return
     * @note
     */
    fun slaveCanMsgSend(data: ByteArray) {
        val outputStream = mSerialPortCAN?.outputStream ?: run {
            Log.e("SerialPort", "SerialPort已经关闭，无法写出数据")
            return
        }
        // 多线程（GNSS协程、停止按钮、CANopen 深度控制）可能同时写 CAN 口，
        // 必须锁稳定的 CAN_TX_LOCK 而非 outputStream（端口重开后实例会更换，见 L10）
        synchronized(CAN_TX_LOCK) {
            try {
                outputStream.write(data)
                outputStream.flush()
            } catch (e: IOException) {
                Log.e("slaveCanMsgSend", "IOException: " + e.message)
            }
        }
    }
}
