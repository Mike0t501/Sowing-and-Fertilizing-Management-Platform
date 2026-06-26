/**
 ***********************************************************************************************************
 * @author  :NIANXI
 * @date    :2024年6月27日10:59:55
 * @file    :
 * @brief   :SharedPreferences数据轻量存储有关
 * ---------------------------------------------------------------------------------------------------------
 * Change History
 * ---------------------------------------------------------------------------------------------------------
 * 重置设置参数
 * 持久化写出数据存储文件夹权限
 ***********************************************************************************************************
 */
package com.nx.vfremake.funClass

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import com.nx.vfremake.R
import com.nx.vfremake.TOTAL_NODE_COUNT
import com.nx.vfremake.mSPParamData

class MySharedPreFun(private val context: Context) {
    private val allMotorsOn = List(TOTAL_NODE_COUNT) { "1" }.joinToString(",")

    /**
     * 获取设置SharedPreferences
     * @note
     */
    fun getMySharedPre(): SharedPreferences {
        val sharedPre = context.getSharedPreferences(
            context.getString(R.string.mySharedPre_name),
            Context.MODE_PRIVATE
        )
        return sharedPre
    }

    /**
     * 获取设置SharedPreferences
     * @note
     */
    fun getMydantiFertSettingsSharedPre(): SharedPreferences {
        val sharedPre = context.getSharedPreferences(
            context.getString(R.string.myDantiFertSettingsSharedPre_name),
            Context.MODE_PRIVATE
        )
        return sharedPre
    }

    /**
     * 获取sharedpre中特定的键值
     * @param  resId:资源值ID
     * @return
     * @note
     */
    fun getSpecificValue(resId: Int): String? {
        return MySharedPreFun(context).getMySharedPre()
            .getString(context.getString(resId), null)
    }

    /**
     * 当sharedpre中某键值为null或空时置入默认值
     * @param  resId:资源值ID
     * @param  defaultValue:默认值
     * @return
     * @note
     */
    private fun setDefaultValueIfNullOrEmpty(
        resId: Int,
        defaultValue: String,
    ) {
        val sharedPre = MySharedPreFun(context).getMySharedPre()
        val resKey = context.getString(resId)
        val currentValue = sharedPre.getString(resKey, null)
        if (!sharedPre.contains(resKey) || currentValue.isNullOrEmpty()) {
            sharedPre.edit().putString(resKey, defaultValue).apply()
        }
    }

    /**
     * 初始化设置参数值
     * @note
     */
    /**
     * 初始化设置参数值
     * @note
     */
    fun initSettingsParam() {
        val sharedPre = MySharedPreFun(context).getMySharedPre()

        // ================= 【核心修改：强制默认8行，洗掉旧缓存】 =================
        mSPParamData.rowNumber = 8
        sharedPre.edit().putString(context.getString(R.string.rowNumber_name), "8").apply()
        // ====================================================================

        // 用户设置界面的单位是cm，用于后续代码计算的单位统一转换为 m
        mSPParamData.rowSize =
            sharedPre.getString(context.getString(R.string.rowSize_name), "0.0").toString()
                .toDouble() / 100
        mSPParamData.gnssDistanceVertical =
            sharedPre.getString(context.getString(R.string.gnssDistanceVertical_name), "0.0")
                .toString()
                .toDouble() / 100
        mSPParamData.gnssDistanceHorizontal =
            sharedPre.getString(context.getString(R.string.gnssDistanceHorizontal_name), "0.0")
                .toString().toDouble() / 100
        mSPParamData.lagTime =
            sharedPre.getString(context.getString(R.string.lagTime_name), "0.0").toString()
                .toDouble()
        mSPParamData.forwardSpeed =
            sharedPre.getString(context.getString(R.string.forwardSpeed_name), "-1.0").toString()
                .toDouble()
        mSPParamData.fertApplied =
            sharedPre.getString(context.getString(R.string.fertApplied_name), "-1.0").toString()
                .toDouble()

        // --- 新增：读取UI设置的单体电机启停状态 ---
        mSPParamData.seedTransmissionRatio =
            sharedPre.getString("seed_transmission_ratio", "1.0").toString().toDoubleOrNull() ?: 1.0
        mSPParamData.seedHoleCount =
            sharedPre.getString("seed_hole_count", "12").toString().toIntOrNull() ?: 12
        mSPParamData.seedPlantSpacing =
            (sharedPre.getString("seed_plant_spacing_cm", "20").toString().toDoubleOrNull() ?: 20.0) / 100.0

        val activeMotorsStr = sharedPre.getString("active_motors_state", allMotorsOn) ?: allMotorsOn
        val activeMotorsList = activeMotorsStr.split(",")
        for (i in 0 until TOTAL_NODE_COUNT) {
            if (i < activeMotorsList.size) {
                mSPParamData.activeMotors[i] = (activeMotorsList[i] == "1")
            } else {
                mSPParamData.activeMotors[i] = true
            }
        }
    }

    /**
     * 重置设置、参数设置
     * @note
     */
    fun resetConfig() {
        val sharedPre = MySharedPreFun(context).getMySharedPre()
        val appShpFilePath = sharedPre.getString(
            context.getString(R.string.myLoadShpFile_AppSpecificExternalDirPath_name),
            ""
        )
        val editor = sharedPre.edit()
        editor.putString(
            context.getString(R.string.rowNumber_name),
            context.getString(R.string.rowNumber_defeatValue)
        )
        editor.putString(
            context.getString(R.string.rowSize_name),
            context.getString(R.string.rowSize_defeatValue)
        )
        editor.putString(
            context.getString(R.string.gnssDistanceVertical_name),
            context.getString(R.string.gnssDistanceVertical_defeatValue)
        )
        editor.putString(
            context.getString(R.string.gnssDistanceHorizontal_name),
            context.getString(R.string.gnssDistanceHorizontal_defeatValue)
        )
        editor.putString(
            context.getString(R.string.lagTime_name),
            context.getString(R.string.lagTime_defeatValue)
        )
        editor.putString(
            context.getString(R.string.forwardSpeed_name),
            context.getString(R.string.forwardSpeed_defeatValue)
        )
        editor.putString(
            context.getString(R.string.fertApplied_name),
            context.getString(R.string.fertApplied_defeatValue)
        )
        editor.putString("seed_transmission_ratio", "1.0")
        editor.putString("seed_hole_count", "12")
        editor.putString("seed_plant_spacing_cm", "20")
        editor.putString("active_motors_state", allMotorsOn)
        editor.putString(
            context.getString(R.string.writeSaveData_Switch_name),
            context.getString(R.string.writeSaveData_Switch_defeatValue)
        )
        editor.putString(
            context.getString(R.string.testMode_Switch_name),
            context.getString(R.string.testMode_Switch_defeatValue)
        )
        editor.putString(
            context.getString(R.string.navMarkCompensated_Switch_name),
            context.getString(R.string.navMarkCompensated_Switch_defeatValue)
        )
        editor.putString(
            context.getString(R.string.colorStep_name),
            context.resources.getInteger(R.integer.colorStep_value).toString()
        )
        editor.putString(
            context.getString(R.string.fertQueryField_name),
            context.getString(R.string.fertQueryField_defeatValue)
        )
        editor.putString(
            context.getString(R.string.deltaX_name),
            context.resources.getInteger(R.integer.deltaX_value).toString()

        )
        editor.putString(
            context.getString(R.string.deltaY_name),
            context.resources.getInteger(R.integer.deltaY_value).toString()
        )
        editor.putString(
            context.getString(R.string.testMode_testModeDurationTime_name),
            context.getString(R.string.testMode_testModeDurationTime_defeatValue)
        )
        editor.putString(context.getString(R.string.myLoadShpFile_Path_name), appShpFilePath)
        editor.apply()
    }

    /**
     * 更新新参数
     * @note
     */
    fun updataNewConfig() {
        val sharedPre = MySharedPreFun(context).getMySharedPre()
        val appShpFilePath = sharedPre.getString(
            context.getString(R.string.myLoadShpFile_AppSpecificExternalDirPath_name),
            ""
        )
        setDefaultValueIfNullOrEmpty(
            R.string.rowSize_name,
            context.getString(R.string.rowSize_defeatValue)
        )
        setDefaultValueIfNullOrEmpty(
            R.string.rowNumber_name,
            context.getString(R.string.rowNumber_defeatValue)
        )
        setDefaultValueIfNullOrEmpty(
            R.string.gnssDistanceVertical_name,
            context.getString(R.string.gnssDistanceVertical_defeatValue)
        )
        setDefaultValueIfNullOrEmpty(
            R.string.gnssDistanceHorizontal_name,
            context.getString(R.string.gnssDistanceHorizontal_defeatValue)
        )
        setDefaultValueIfNullOrEmpty(
            R.string.lagTime_name,
            context.getString(R.string.lagTime_defeatValue)
        )
        setDefaultValueIfNullOrEmpty(
            R.string.forwardSpeed_name,
            context.getString(R.string.forwardSpeed_defeatValue)
        )
        setDefaultValueIfNullOrEmpty(
            R.string.fertApplied_name,
            context.getString(R.string.fertApplied_defeatValue)
        )
        setDefaultValueIfNullOrEmpty(
            R.string.writeSaveData_Switch_name,
            context.getString(R.string.writeSaveData_Switch_defeatValue)
        )
        setDefaultValueIfNullOrEmpty(
            R.string.testMode_Switch_name,
            context.getString(R.string.testMode_Switch_defeatValue)
        )
        setDefaultValueIfNullOrEmpty(
            R.string.testMode_testModeDurationTime_name,
            context.getString(R.string.testMode_testModeDurationTime_defeatValue)
        )
        setDefaultValueIfNullOrEmpty(
            R.string.navMarkCompensated_Switch_name,
            context.getString(R.string.navMarkCompensated_Switch_defeatValue)
        )
        setDefaultValueIfNullOrEmpty(
            R.string.colorStep_name,
            context.resources.getInteger(R.integer.colorStep_value).toString()
        )
        setDefaultValueIfNullOrEmpty(
            R.string.fertQueryField_name,
            context.getString(R.string.fertQueryField_defeatValue)
        )
        setDefaultValueIfNullOrEmpty(
            R.string.deltaX_name,
            context.resources.getInteger(R.integer.deltaX_value).toString()
        )
        setDefaultValueIfNullOrEmpty(
            R.string.deltaY_name,
            context.resources.getInteger(R.integer.deltaY_value).toString()
        )
        val editor = sharedPre.edit()
        if (!sharedPre.contains("seed_transmission_ratio")) editor.putString("seed_transmission_ratio", "1.0")
        if (!sharedPre.contains("seed_hole_count")) editor.putString("seed_hole_count", "12")
        if (!sharedPre.contains("seed_plant_spacing_cm")) editor.putString("seed_plant_spacing_cm", "20")
        if (!sharedPre.contains("active_motors_state")) editor.putString("active_motors_state", allMotorsOn)
        editor.apply()
        if (appShpFilePath != null) {
            setDefaultValueIfNullOrEmpty(
                R.string.myLoadShpFile_Path_name,
                appShpFilePath
            )
        }
    }

    /**
     * 持久化存储用户授予的Uri权限并保存
     * @param  uri:用户选择的对象的treeUri
     * @param  takeFlags:标志
     * @param  sharedPreResKey:字符串值，用于存储URI
     * @return
     * @note
     */
    fun persistDirectoryUriPermission(uri: Uri, takeFlags: Int, sharedPreResKey: String) {
        val contentResolver = context.contentResolver
        contentResolver.takePersistableUriPermission(uri, takeFlags)
        // 存储到SharedPreferences
        val sharedPre = MySharedPreFun(context).getMySharedPre()
        // 解析treeUri以获取对象的DocumentId，使用DocumentId构建指向对象的documentUri
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            uri, DocumentsContract.getTreeDocumentId(uri)
        )
        with(sharedPre.edit()) {
            putString(sharedPreResKey, documentUri.toString())
            apply()
        }
    }
}
