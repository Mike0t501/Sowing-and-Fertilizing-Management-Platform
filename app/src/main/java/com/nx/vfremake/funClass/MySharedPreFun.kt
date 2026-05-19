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
import com.nx.vfremake.data.CalibrationMode
import com.nx.vfremake.data.IndirectCalibPoint
import com.nx.vfremake.data.STANDARD_BLOCK_DEPTHS_MM
import com.nx.vfremake.mSPParamData

class MySharedPreFun(private val context: Context) {

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
        val activeMotorsStr = sharedPre.getString("active_motors_state", "1,1,1,1,1,1,1,1") ?: "1,1,1,1,1,1,1,1"
        val activeMotorsList = activeMotorsStr.split(",")
        for (i in 0 until 8) {
            if (i < activeMotorsList.size) {
                mSPParamData.activeMotors[i] = (activeMotorsList[i] == "1")
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
            R.string.depthQueryField_name,
            context.getString(R.string.depthQueryField_defeatValue)
        )
        setDefaultValueIfNullOrEmpty(
            R.string.deltaX_name,
            context.resources.getInteger(R.integer.deltaX_value).toString()
        )
        setDefaultValueIfNullOrEmpty(
            R.string.deltaY_name,
            context.resources.getInteger(R.integer.deltaY_value).toString()
        )
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

    // =========================================================================
    // 播种深度控制 — SharedPreferences 持久化
    // 使用独立的 SharedPreferences 文件 "sowing_depth_prefs"，避免与现有配置冲突。
    //
    // Key 命名规则（N = motorIndex 0~7，M = 校准点序号 0~4）：
    //   depth_N_nodeId          Int    CAN Node-ID
    //   depth_N_limitMin        Int    最浅位置编码器值
    //   depth_N_limitMax        Int    最深位置编码器值
    //   depth_N_limitsSet       Bool   限位是否已标定
    //   depth_N_fitA            Float  拟合系数 a
    //   depth_N_fitB            Float  拟合系数 b
    //   depth_N_fitValid        Bool   拟合是否有效
    //   depth_N_cal_count       Int    已存校准点数量
    //   depth_N_cal_M_pos       Int    第 M 个校准点的编码器位置
    //   depth_N_cal_M_depth     Float  第 M 个校准点的实际深度 mm
    //   depth_jog_speed         Int    全局点动速度 RPM
    //   depth_pos_speed         Int    全局位置运动速度 RPM
    //   depth_acceleration      Int    全局加速度 RPM/s
    //   depth_global_target     Float  全局目标深度 mm
    //   depth_N_calibMode       String 标定模式 "DIRECT" 或 "INDIRECT"
    //   depth_N_ind_M_enc       Int    第 M 个挡块的编码器值（-1 = null）
    // =========================================================================

    /** 获取播种深度专用 SharedPreferences */
    fun getSowingDepthSharedPre(): android.content.SharedPreferences =
        context.getSharedPreferences("sowing_depth_prefs", Context.MODE_PRIVATE)

    /**
     * 保存单个电机的全部校准配置（nodeId / 限位 / 校准点 / 拟合系数）。
     * 运行时状态（currentPosition / isOnline 等）不持久化。
     */
    fun saveSowingDepthCalibration(cal: com.nx.vfremake.data.ServoCalibration) {
        val n = cal.motorIndex
        val prefs = getSowingDepthSharedPre().edit()

        prefs.putInt("depth_${n}_nodeId",    cal.nodeId)
        prefs.putInt("depth_${n}_limitMin",  cal.limitMin)
        prefs.putInt("depth_${n}_limitMax",  cal.limitMax)
        prefs.putBoolean("depth_${n}_limitsSet", cal.limitsSet)
        prefs.putFloat("depth_${n}_fitA",    cal.fitA)
        prefs.putFloat("depth_${n}_fitB",    cal.fitB)
        prefs.putBoolean("depth_${n}_fitValid",  cal.fitValid)

        // 校准点：先存数量，再逐点存储
        val pts = cal.calibrationPoints
        prefs.putInt("depth_${n}_cal_count", pts.size)
        pts.forEachIndexed { m, (pos, depth) ->
            prefs.putInt("depth_${n}_cal_${m}_pos",   pos)
            prefs.putFloat("depth_${n}_cal_${m}_depth", depth)
        }

        // 标定模式与间接测量点
        prefs.putString("depth_${n}_calibMode", cal.calibrationMode.name)
        STANDARD_BLOCK_DEPTHS_MM.indices.forEach { m ->
            val enc = cal.indirectPoints.getOrNull(m)?.encoderPos ?: -1
            prefs.putInt("depth_${n}_ind_${m}_enc", enc)
        }

        prefs.apply()
    }

    /**
     * 读取单个电机的校准配置，返回 [ServoCalibration]（仅含持久化字段，运行时字段保持默认值）。
     * 若从未写入，返回含默认值的对象（nodeId = 11 + motorIndex）。
     */
    fun loadSowingDepthCalibration(motorIndex: Int): com.nx.vfremake.data.ServoCalibration {
        val n = motorIndex
        val prefs = getSowingDepthSharedPre()

        val nodeId      = prefs.getInt("depth_${n}_nodeId",    11 + n)
        val limitMin    = prefs.getInt("depth_${n}_limitMin",  0)
        val limitMax    = prefs.getInt("depth_${n}_limitMax",  0)
        val limitsSet   = prefs.getBoolean("depth_${n}_limitsSet", false)
        val fitA        = prefs.getFloat("depth_${n}_fitA",    0f)
        val fitB        = prefs.getFloat("depth_${n}_fitB",    0f)
        val fitValid    = prefs.getBoolean("depth_${n}_fitValid",  false)

        val calCount    = prefs.getInt("depth_${n}_cal_count", 0)
        val calPoints   = (0 until calCount).map { m ->
            val pos   = prefs.getInt("depth_${n}_cal_${m}_pos",   0)
            val depth = prefs.getFloat("depth_${n}_cal_${m}_depth", 0f)
            Pair(pos, depth)
        }

        val calibModeStr = prefs.getString("depth_${n}_calibMode", "DIRECT") ?: "DIRECT"
        val calibMode = runCatching { CalibrationMode.valueOf(calibModeStr) }
            .getOrDefault(CalibrationMode.DIRECT)
        val indirectPoints = STANDARD_BLOCK_DEPTHS_MM.mapIndexed { m, depthMm ->
            val enc = prefs.getInt("depth_${n}_ind_${m}_enc", -1)
            IndirectCalibPoint(depthMm = depthMm, encoderPos = if (enc == -1) null else enc)
        }

        return com.nx.vfremake.data.ServoCalibration(
            motorIndex         = n,
            nodeId             = nodeId,
            limitMin           = limitMin,
            limitMax           = limitMax,
            limitsSet          = limitsSet,
            calibrationPoints  = calPoints,
            fitA               = fitA,
            fitB               = fitB,
            fitValid           = fitValid,
            calibrationMode    = calibMode,
            indirectPoints     = indirectPoints
        )
    }

    /**
     * 读取全部 8 个电机的校准配置，返回列表（下标 = motorIndex）。
     */
    fun loadAllSowingDepthCalibrations(): List<com.nx.vfremake.data.ServoCalibration> =
        (0 until 8).map { loadSowingDepthCalibration(it) }

    /**
     * 保存全局播种深度控制参数（点动速度 / 位置速度 / 加速度 / 全局目标深度）。
     */
    fun saveSowingDepthGlobalSettings(
        jogSpeed: Int,
        positionSpeed: Int,
        acceleration: Int,
        globalTargetDepth: Float
    ) {
        getSowingDepthSharedPre().edit()
            .putInt("depth_jog_speed",        jogSpeed)
            .putInt("depth_pos_speed",        positionSpeed)
            .putInt("depth_acceleration",     acceleration)
            .putFloat("depth_global_target",  globalTargetDepth)
            .apply()
    }

    /**
     * 读取全局播种深度控制参数，返回含默认值的四元组。
     * @return Triple (jogSpeed, positionSpeed, acceleration) + globalTargetDepth
     *         以 [com.nx.vfremake.data.SowingDepthState] 形式返回，motors 列表由调用方填充。
     */
    fun loadSowingDepthGlobalSettings(): SowingDepthGlobalSettings {
        val prefs = getSowingDepthSharedPre()
        return SowingDepthGlobalSettings(
            jogSpeed          = prefs.getInt("depth_jog_speed",    2000),
            positionSpeed     = prefs.getInt("depth_pos_speed",    500),
            acceleration      = prefs.getInt("depth_acceleration", 10000),
            globalTargetDepth = prefs.getFloat("depth_global_target", 50f)
        )
    }

    /**
     * 加载完整的 [com.nx.vfremake.data.SowingDepthState]（含 8 个电机 + 全局设置）。
     * 通常在 App 启动或设置页初始化时调用一次。
     */
    fun loadSowingDepthState(): com.nx.vfremake.data.SowingDepthState {
        val settings = loadSowingDepthGlobalSettings()
        return com.nx.vfremake.data.SowingDepthState(
            motors            = loadAllSowingDepthCalibrations(),
            globalTargetDepth = settings.globalTargetDepth,
            jogSpeed          = settings.jogSpeed,
            positionSpeed     = settings.positionSpeed,
            acceleration      = settings.acceleration
        )
    }

    /**
     * 保存完整的 [com.nx.vfremake.data.SowingDepthState]（含 8 个电机 + 全局设置）。
     */
    fun saveSowingDepthState(state: com.nx.vfremake.data.SowingDepthState) {
        state.motors.forEach { saveSowingDepthCalibration(it) }
        saveSowingDepthGlobalSettings(
            jogSpeed          = state.jogSpeed,
            positionSpeed     = state.positionSpeed,
            acceleration      = state.acceleration,
            globalTargetDepth = state.globalTargetDepth
        )
    }
}

/**
 * 全局播种深度控制参数（[MySharedPreFun.loadSowingDepthGlobalSettings] 的返回类型）
 */
data class SowingDepthGlobalSettings(
    val jogSpeed: Int,
    val positionSpeed: Int,
    val acceleration: Int,
    val globalTargetDepth: Float
)