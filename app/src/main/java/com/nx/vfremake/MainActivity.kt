/**
 ************************************************************************************************************
 * @author  :NIANXI
 * @date    :2024-05-02
 * @file    :
 * @brief   :变量施肥
 * ---------------------------------------------------------------------------------------------------------
 * Change History
 * ---------------------------------------------------------------------------------------------------------
 * 分级渲染
 * 增加颜色施肥量提示
 * 位置补偿并求单体位置查询施肥量
 * DB9通讯 CAN通讯，通过按钮启停通讯线程
 * 数据保存、按钮启停保存数据协程
 ***********************************************************************************************************
 */

package com.nx.vfremake

import android.app.Activity
import android.content.Context
import android.media.MediaPlayer
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.esri.arcgisruntime.ArcGISRuntimeEnvironment
import com.esri.arcgisruntime.mapping.view.BackgroundGrid
import com.esri.arcgisruntime.mapping.view.MapView
import com.nx.vfremake.coroutine.CanReceiveCoroutine
import com.nx.vfremake.coroutine.DB9reCANseCoroutine
import com.nx.vfremake.coroutine.MyWriteSaveFun
import com.nx.vfremake.coroutine.SowingDepthCoroutine
import com.nx.vfremake.data.depthControlReadinessWarning
import com.nx.vfremake.coroutine.TestModeCoroutine
import com.nx.vfremake.funClass.ConvAndCtrlFun
import com.nx.vfremake.funClass.DocuAndManageFun
import com.nx.vfremake.funClass.MyArcGisFun
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.funClass.MySharedPreFun
import com.nx.vfremake.funClass.MydantiFertSharedPre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nx.vfremake.lastSentMotorSpeed

class MainActivity : AppCompatActivity() {

    // viewmodel
    private val customViewModel: CustomViewModel by viewModels()
    private val mVariableFertViewModel: VariableFertViewModel by viewModels()

    private lateinit var mapView: MapView

    // 协程
    private val myWriteSaveFun = MyWriteSaveFun()
    private val myTestModeCoroutine = TestModeCoroutine()

    // 因为需要rowNumber初始化参数，必须mSPParamData初始化完成才初始化
    private lateinit var mDB9reCANseCoroutine: DB9reCANseCoroutine
    private var mCanReceiveCoroutine = CanReceiveCoroutine()

    // 处方图控深模式：作业期间由 MainActivity 独占持有，避免与 SowingDepthScreen 双实例写 CAN
    private var mSowingDepthCoroutine = SowingDepthCoroutine()

    // 定义ActivityResultLauncher用于对象选择
    private lateinit var selectWriteDirectoryLauncher: ActivityResultLauncher<Uri?>

    //...oncreat开始
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 开机语音（随机从 begin1/begin2/begin3 中选一个播放）
        // 用 nanoTime 取模而非 Random.Default，避免工业设备开机熵池不足导致每次选同一个
        try {
            val beginList = listOf(R.raw.begin1, R.raw.begin2, R.raw.begin3)
            val idx = ((System.nanoTime() % 3 + 3) % 3).toInt()
            val mp = MediaPlayer.create(this, beginList[idx])
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
        } catch (_: Exception) {}

        // 注入"应用"按钮提示音，由 Composable 调用，保证与报错音走同一条播放链路
        onPlayApplySound = {
            val getList = listOf(R.raw.get1, R.raw.get2)
            val idx = ((System.nanoTime() % 2 + 2) % 2).toInt()
            enqueueErrorSound(getList[idx], isError = false)
        }

        setContent {
            VariableFert(
                mapView,
                mVariableFertViewModel,
            ) { StartAndStopBottom() }
        }

        // 首次运行需要将assets里的资源复制到外部专属目录files/shpFile/下
        val firstRunState = MySharedPreFun(this).getSpecificValue(R.string.runForTheFirstTime)
        if (firstRunState != "1") {
            customViewModel.copyShpFilesJob = lifecycleScope.launch(Dispatchers.Default) {
                DocuAndManageFun().copyShpFilesToExternalStorage(this@MainActivity)
            }
        }

        // 设置apikey使用功能
        ArcGISRuntimeEnvironment.setApiKey(BuildConfig.API_KEY)

        // 初始化selectDirectoryLauncher
        selectWriteDirectoryLauncher =
            selectDirectoryLauncher(R.string.myWriteDir_DocumentUri_name, this)
        selectLoadShpFileLauncher =
            selectShpFileLauncher(R.string.myLoadShpFile_Path_name, this)

        // 屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val colorArgbBackgrand = this.getColor(R.color.background_color_default)
        // 初始化mapview
        mapView = MapView(this).apply {
            isAttributionTextVisible = false
            setBackgroundColor(colorArgbBackgrand)
            backgroundGrid = BackgroundGrid(colorArgbBackgrand, colorArgbBackgrand, 0f, 2f)
        }

        MyArcGisFun().loadShp(this, mVariableFertViewModel)
    }
    //...oncreat结束

    private fun selectDirectoryLauncher(
        resId: Int,
        context: Context,
    ): ActivityResultLauncher<Uri?> {
        return registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.also {
                val takeFlags: Int =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)

                val sharedPreHelper = MySharedPreFun(context)
                sharedPreHelper.persistDirectoryUriPermission(
                    uri,
                    takeFlags,
                    context.getString(resId)
                )
                MyWriteSaveFun().writeFileToSelectedDirectory(
                    "writetest.txt",
                    "Hello, World!",
                    context.getString(resId),
                    context
                )
            }
        }
    }

    private fun selectShpFileLauncher(
        resId: Int,
        context: Context,
    ): ActivityResultLauncher<Intent> {
        return registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data
                uri?.let {
                    if (uri.toString().endsWith(".shp")) {
                        val filePath = DocuAndManageFun().getPathFromContentUri(context, uri)
                        Log.d("SharedPre", filePath.toString())
                        MySharedPreFun(context).getMySharedPre().edit()
                            .putString(context.getString(resId), filePath).apply()
                        MyArcGisFun().getFieldList(filePath, mVariableFertViewModel)
                    } else {
                        AlertDialog.Builder(context)
                            .setTitle("文件选择错误")
                            .setMessage("请选择 .shp 文件")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            }
        }
    }
    @OptIn(ExperimentalLayoutApi::class)
    @Composable

    fun StartAndStopBottom() {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope() // 用于控制安全关闭串口

        Box(
            modifier = Modifier
                .padding(dimensionResource(id = R.dimen.small_custom_padding))
                .fillMaxSize()
        ) {
            val configuration = LocalConfiguration.current
            val isInPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val isRunningState = remember { mutableStateOf(false) }
            val watchdogJobRef = remember { object { var job: Job? = null } }

            FlowRow(
                modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.2f),
                maxItemsInEachRow = if (isInPortrait) 1 else 2,
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.Center
            ) {
                // ================== 开始按钮 ==================
                Surface(
                    color = if (isRunningState.value) androidx.compose.ui.graphics.Color.Gray else androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.width(90.dp).padding(dimensionResource(id = R.dimen.middle_view_padding)),
                    shape = RoundedCornerShape(dimensionResource(id = R.dimen.small_roundedCornerShape)),
                ) {
                    IconButton(
                        onClick = {
                            if (!isRunningState.value) {
                                val isTestMode = MySharedPreFun(context).getSpecificValue(R.string.testMode_Switch_name) == "1"
                                val simGnss = MySharedPreFun(context).getSpecificValue(R.string.simGnss_Switch_name) == "1"

                                // 1. 【修复】：测试模式 / 模拟GNSS 模式下跳过申请文件夹，不要弹出文件选择器！
                                if (!isTestMode && !simGnss) {
                                    DocuAndManageFun().cheekDirectoryUriPermission(
                                        R.string.myWriteDir_DocumentUri_name, selectWriteDirectoryLauncher, context
                                    )
                                    if (MySharedPreFun(context).getSpecificValue(R.string.writeSaveData_Switch_name) == "1") {
                                        myWriteSaveFun.start(context = context, getData = {
                                            myWriteSaveFun.getMySaveData(mSPParamData.rowNumber, mVariableFertViewModel)
                                        })
                                    }
                                }

                                // 2. 打开串口
                                if (mVariableFertViewModel.serialPortIsRunning.value == false) {
                                    MySerialPortFun().openSerialPort(context, mVariableFertViewModel)
                                }

                                // 3. 每次开始重新读取 UI 最新参数 (包括单体启停控制)
                                MySharedPreFun(context).initSettingsParam()

                                // ================= 【核心修复：开始】 =================
                                // 读取单体启停状态字符串，转成 Boolean 数组并传给 ViewModel，
                                // 让 ViewModel 在计算准确率时跳过那些被关闭的电机
                                val sharedPre = MySharedPreFun(context).getMySharedPre()
                                val activeStr = sharedPre.getString("active_motors_state", "1,1,1,1,1,1,1,1") ?: "1,1,1,1,1,1,1,1"
                                val activeArray = activeStr.split(",").map { it == "1" }.toBooleanArray()
                                mVariableFertViewModel.activeMotorsState.postValue(activeArray)
                                // ================= 【核心修复：结束】 =================

                                // 4. 【修复】：清空平均施肥量和准确率统计，从头算
                                mVariableFertViewModel.resetFertStats()
                                // 重置本次运行报错标志
                                errorTriggeredThisRun = false

                                val soundEnabledAtStart = MySharedPreFun(context).getSpecificValue(R.string.errorSound_Switch_name) == "1"
                                // 4a. 开始前提示：排肥系数未配置（A/B 全为 0）
                                if (soundEnabledAtStart && fittingCoefficientA.all { it == 0.0 } && fittingCoefficientB.all { it == 0.0 }) {
                                    enqueueErrorSound(R.raw.fail6)
                                }

                                // 4b. 开始前提示：非测试 / 非模拟GNSS 模式下没有 RTK 数据
                                if (soundEnabledAtStart && !isTestMode && !simGnss && mRmcData.latitude == 0.0 && mRmcData.longitude == 0.0) {
                                    enqueueErrorSound(R.raw.fail7)
                                }

                                isSystemRunning = true
                                isRunningState.value = true

                                // 5. 模式隔离
                                if (isTestMode) {
                                    if (!myTestModeCoroutine.isRunning) myTestModeCoroutine.start(context, isRunningState)
                                    // 【修复】：测试模式必须也打开接收，这样底部图表才能收到反馈发生变化！
                                    if (!mCanReceiveCoroutine.isRunning) mCanReceiveCoroutine.start(mVariableFertViewModel)
                                } else {
                                    if (!mDB9reCANseCoroutine.isRunning) {
                                        if (simGnss) mDB9reCANseCoroutine.start1Simulated(context, mVariableFertViewModel)
                                        else         mDB9reCANseCoroutine.start1(context, mVariableFertViewModel)
                                    }
                                    if (!mCanReceiveCoroutine.isRunning) mCanReceiveCoroutine.start(mVariableFertViewModel)
                                }

                                // 5b. 处方图控深模式：作业期间由 MainActivity 独占启动深度控制协程。
                                // 单次原子恢复持久化状态并置 masterEnabled=true，避免
                                // restoreSowingDepthState 与 updateMasterEnabled 的 LiveData 顺序竞态；
                                // 按"开始"即显式使能深度伺服（已与用户确认）。
                                if (mVariableFertViewModel.depthPrescriptionMode.value == true) {
                                    mVariableFertViewModel.restoreSowingDepthState(
                                        MySharedPreFun(context).loadSowingDepthState()
                                            .copy(masterEnabled = true)
                                    )
                                    if (!mSowingDepthCoroutine.isRunning) {
                                        mSowingDepthCoroutine = SowingDepthCoroutine()
                                        mSowingDepthCoroutine.start(mVariableFertViewModel, context)
                                    }

                                    // 5c. 控深就绪诊断：留出上线检测窗口后评估一次，
                                    // 若仍无电机可下发位置命令则在主界面弹一次提示。
                                    coroutineScope.launch {
                                        delay(3000)   // 等心跳/TPDO + 2s 离线看门狗判定
                                        if (isSystemRunning &&
                                            mVariableFertViewModel.depthPrescriptionMode.value == true
                                        ) {
                                            val warn = mVariableFertViewModel.sowingDepthState.value
                                                ?.depthControlReadinessWarning()
                                            if (warn != null) {
                                                mVariableFertViewModel.depthControlNotice.postValue(warn)
                                            }
                                        }
                                    }
                                }

                                // 6. 启动 CAN 看门狗（测试模式和正常模式都启动）
                                // fail3：从未收到数据（一开始没接好 / 测试模式未插CAN）
                                // fail2：曾经收到过、后续中断（电机不再返回字节）
                                mVariableFertViewModel.canEverReceived = false
                                mVariableFertViewModel.rtkEverReceived = false
                                mVariableFertViewModel.canLastReceiveTime.postValue(System.currentTimeMillis())
                                watchdogJobRef.job?.cancel()
                                watchdogShouldRun = true
                                watchdogJobRef.job = coroutineScope.launch {
                                    var motorDelayStartTime = 0L
                                    var canReadyPlayed = false
                                    var rtkReadyPlayed = false
                                    var noResponseCooldownTime = 0L
                                    var noResponseStartTime = 0L
                                    while (isActive && watchdogShouldRun) {
                                        delay(2000)
                                        if (!watchdogShouldRun) break

                                        // 语音提示开关：关闭时跳过所有语音
                                        val errorSoundOn = MySharedPreFun(context).getSpecificValue(R.string.errorSound_Switch_name) == "1"
                                        if (!errorSoundOn) { motorDelayStartTime = 0L; continue }

                                        // --- ready1/ready2：首次连接提示（不算报错） ---
                                        if (mVariableFertViewModel.canEverReceived && !canReadyPlayed) {
                                            canReadyPlayed = true
                                            enqueueErrorSound(R.raw.ready1, isError = false)
                                        }
                                        if (mVariableFertViewModel.rtkEverReceived && !rtkReadyPlayed) {
                                            rtkReadyPlayed = true
                                            enqueueErrorSound(R.raw.ready2, isError = false)
                                        }

                                        // --- fail2/fail3：CAN 超时（5秒无回包）---
                                        // 仅在有电机被命令转动时检查：停车或刚启动时电机不回传属正常
                                        val anyMotorCommanded = lastSentMotorSpeed.any { it >= 1.0 }
                                        val lastReceive = mVariableFertViewModel.canLastReceiveTime.value ?: 0L
                                        if (anyMotorCommanded && System.currentTimeMillis() - lastReceive > 5000L) {
                                            val resId = if (mVariableFertViewModel.canEverReceived) R.raw.fail2 else R.raw.fail3
                                            enqueueErrorSound(resId)
                                            motorDelayStartTime = 0L
                                            delay(8000)
                                            continue
                                        }

                                        // --- fail4/fail5：部分已启用电机无回传（持续3秒）---
                                        val returnSpeeds = mVariableFertViewModel.motorSpeed.value
                                        val anyCommanded = lastSentMotorSpeed.any { it >= 1.0 }
                                        if (anyCommanded && System.currentTimeMillis() - noResponseCooldownTime > 8000L) {
                                            val noResponseCount = (0 until mSPParamData.rowNumber).count { i ->
                                                val isActive: Boolean = mSPParamData.activeMotors.getOrNull(i) ?: true
                                                isActive && (returnSpeeds?.getOrNull(i) ?: 0.0) == 0.0
                                            }
                                            if (noResponseCount >= 2) {
                                                if (noResponseStartTime == 0L) noResponseStartTime = System.currentTimeMillis()
                                                if (System.currentTimeMillis() - noResponseStartTime > 3000L) {
                                                    if (noResponseCount == 2) enqueueErrorSound(R.raw.fail4) else enqueueErrorSound(R.raw.fail5)
                                                    noResponseCooldownTime = System.currentTimeMillis()
                                                    noResponseStartTime = 0L
                                                    motorDelayStartTime = 0L; delay(8000); continue
                                                }
                                            } else {
                                                noResponseStartTime = 0L
                                            }
                                        } else {
                                            noResponseStartTime = 0L
                                        }

                                        // --- fail1：电机响应延迟（发出转速 > 1rpm 但回传 < 70% 且持续 3 秒）---
                                        val hasDelay = (0 until mSPParamData.rowNumber).any { i ->
                                            val sent = if (i < lastSentMotorSpeed.size) lastSentMotorSpeed[i] else 0.0
                                            val actual = returnSpeeds?.getOrNull(i) ?: 0.0
                                            // actual > 0：只检测有回传的电机，无回传（反转电机）跳过
                                            sent >= 1.0 && actual > 0.0 && actual < sent * 0.7
                                        }
                                        if (hasDelay) {
                                            if (motorDelayStartTime == 0L) motorDelayStartTime = System.currentTimeMillis()
                                            if (System.currentTimeMillis() - motorDelayStartTime > 3000L) {
                                                enqueueErrorSound(R.raw.fail1)
                                                motorDelayStartTime = 0L
                                                delay(8000)
                                            }
                                        } else {
                                            motorDelayStartTime = 0L
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayCircle, contentDescription = "开始")
                            Text("开始")
                        }
                    }
                }

                // ================== 停止按钮 ==================
                Surface(
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.width(90.dp).padding(dimensionResource(id = R.dimen.middle_view_padding)),
                    shape = RoundedCornerShape(dimensionResource(id = R.dimen.small_roundedCornerShape)),
                ) {
                    IconButton(
                        onClick = {
                            // 【修复】：立刻切断串口导致0转速命令发不出去。改用协程保障缓冲发完！
                            coroutineScope.launch {
                                // 0. 第一时间置 false，阻断所有异步施肥查询回调再发送电机指令
                                isSystemRunning = false
                                // 注意：不在此处取消看门狗，让它继续监控停止过程中的 CAN 状态

                                // 1. 先关闭所有发数据的后台线程
                                if (mDB9reCANseCoroutine.isRunning) mDB9reCANseCoroutine.shutdown()
                                if (myTestModeCoroutine.isRunning) myTestModeCoroutine.shutdown()
                                if (myWriteSaveFun.isRunning) myWriteSaveFun.shutdown()

                                // 1b. 处方图控深模式：先断使能，让 SowingDepthCoroutine 的
                                // Phase2 ON→OFF 跳变发 Shutdown(0x6040=0x0006) 减速退使能，
                                // 等至少一个 500ms 周期后再关协程（否则伺服停在原位仍带电）。
                                if (mSowingDepthCoroutine.isRunning) {
                                    mVariableFertViewModel.updateMasterEnabled(false)
                                    kotlinx.coroutines.delay(600)
                                    mSowingDepthCoroutine.shutdown()
                                }

                                // 2. 稍作延迟，避开总线最高峰
                                kotlinx.coroutines.delay(100)

                                // 3. 强制清零所有电机
                                // 【修复】：每步检查 isSystemRunning，若用户已重新开始则立即中断，
                                // 避免与新周期的发送协程争抢 synchronized(outputStream) 造成卡顿
                                if (!isSystemRunning) {
                                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                                        for (i in 0 until mSPParamData.rowNumber) {
                                            if (isSystemRunning) break // 发送途中又开始了，立即终止
                                            ConvAndCtrlFun().motorSpeedrpmSend(0.0, i)
                                            kotlinx.coroutines.delay(15)
                                        }
                                        if (!isSystemRunning) kotlinx.coroutines.delay(150)
                                    }
                                }

                                // 4. 彻底确保指令出去了，再把串口拉闸关闭
                                // 【修复】：仅当系统未重新启动时才释放，避免关掉新周期的串口和误改 UI 状态
                                if (!isSystemRunning) {
                                    releaseResource(mVariableFertViewModel)
                                    if (isRunningState.value) isRunningState.value = false
                                    watchdogShouldRun = false
                                    // 本次运行无任何报错语音 → 播放 good
                                    val errorSoundOn = MySharedPreFun(context).getSpecificValue(R.string.errorSound_Switch_name) == "1"
                                    if (errorSoundOn && !errorTriggeredThisRun) {
                                        enqueueErrorSound(R.raw.good, isError = false)
                                    }
                                }
                            }
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.StopCircle, contentDescription = "停止")
                            Text(text = "停止")
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sharedPreHelper = MySharedPreFun(this)
        sharedPreHelper.updataNewConfig()

        val resKey = this.getString(R.string.myLoadShpFile_Path_name)
        val keyValue = sharedPreHelper.getMySharedPre().getString(resKey, "")
        if (keyValue.equals("-1")) {
            Log.d("SharedPreferences", "$resKey 不存在或为空")
        } else {
            Log.d("SharedPreferences", "$resKey 已存在: $keyValue")
        }

        sharedPreHelper.initSettingsParam()
        mDB9reCANseCoroutine = DB9reCANseCoroutine()
        initFittingCoefficient()
        Log.d(
            "Coefficient",
            "A:" + fittingCoefficientA.joinToString(separator = ", ") +
                    "\nB:" + fittingCoefficientB.joinToString(separator = ", ")
        )
    }

    private fun initFittingCoefficient() {
        // 固定按最大行数 8 分配，避免运行中机型行数增大时（应用/开始仅重载参数、不重建本数组）下标越界
        fittingCoefficientA = Array(8) { 0.0 }
        fittingCoefficientB = Array(8) { 0.0 }

        val tableRows = MydantiFertSharedPre(this).getTableRows()
        tableRows.forEach { row ->
            if (row.id <= mSPParamData.rowNumber && row.id != 0) {
                fittingCoefficientA[row.id - 1] = row.a.toDouble()
                fittingCoefficientB[row.id - 1] = row.b.toDouble()
            }
        }
        val defaultCoefficientA = tableRows.find { it.id == 0 }?.a?.toDouble() ?: 0.0
        val defaultCoefficientB = tableRows.find { it.id == 0 }?.b?.toDouble() ?: 0.0

        fittingCoefficientA.forEachIndexed { index, value ->
            if (value == 0.0) {
                fittingCoefficientA[index] = defaultCoefficientA
            }
        }
        fittingCoefficientB.forEachIndexed { index, value ->
            if (value == 0.0) {
                fittingCoefficientB[index] = defaultCoefficientB
            }
        }
    }

    // ── 顺序语音队列：报错/提示音依次播放，不互相打断 ──
    private val errorSoundQueue = ArrayDeque<Int>()
    private var errorSoundPlayer: MediaPlayer? = null

    /** 将一个音频加入队列，若当前没有音频在播放则立即开始 */
    private fun enqueueErrorSound(resId: Int, isError: Boolean = true) {
        if (isError) errorTriggeredThisRun = true
        errorSoundQueue.addLast(resId)
        if (errorSoundPlayer == null) playNextErrorSound()
    }

    /** 播放队列里的下一条；播完自动继续下一条 */
    private fun playNextErrorSound() {
        val resId = errorSoundQueue.removeFirstOrNull() ?: return
        errorSoundPlayer = try {
            MediaPlayer.create(this, resId)?.apply {
                setOnCompletionListener {
                    it.release()
                    errorSoundPlayer = null
                    playNextErrorSound()
                }
                start()
            }
        } catch (_: Throwable) { null }
        // 创建失败就跳过，继续播下一条
        if (errorSoundPlayer == null) playNextErrorSound()
    }

    /** 停止并清空队列（系统停止/销毁时调用） */
    private fun clearErrorSounds() {
        errorSoundQueue.clear()
        try { errorSoundPlayer?.stop(); errorSoundPlayer?.release() } catch (_: Exception) {}
        errorSoundPlayer = null
    }

    private var lastBackPressTime: Long = 0
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 1000) {
                finish()
                return true
            } else {
                lastBackPressTime = currentTime
                Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseResource(mVariableFertViewModel)
        mapView.dispose()
    }

    private fun releaseResource(viewModel: VariableFertViewModel) {
        clearErrorSounds()
        if (mDB9reCANseCoroutine.isRunning || mCanReceiveCoroutine.isRunning) {
            mDB9reCANseCoroutine.shutdown()
            mCanReceiveCoroutine.shutdown()
        }
        if (mSowingDepthCoroutine.isRunning) mSowingDepthCoroutine.shutdown()
        if (viewModel.serialPortIsRunning.value == true) {
            MySerialPortFun().releaseSerialPort(mVariableFertViewModel)
        }
    }
}