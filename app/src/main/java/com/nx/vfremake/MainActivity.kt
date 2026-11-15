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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.addCallback
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
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

    // 主界面播深作业独占伺服控制协程。
    private var mSowingDepthCoroutine = SowingDepthCoroutine()
    private var watchdogJob: Job? = null
    private var depthReadinessJob: Job? = null

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

        val sharedPreHelper = MySharedPreFun(this)
        mVariableFertViewModel.restoreSowingDepthPersistentState(
            sharedPreHelper.loadSowingDepthState(),
            masterEnabledOverride = false
        )
        mVariableFertViewModel.restoreDepthNodePersistentState(sharedPreHelper.loadDepthNodeState())
        onBackPressedDispatcher.addCallback(this) { handleRootBack() }
        setContent {
            VariableFert(
                mapView,
                mVariableFertViewModel,
            ) { StartAndStopBottom() }
        }

        // 首次运行需要将assets里的资源复制到外部专属目录files/shpFile/下
        val firstRunState = sharedPreHelper.getSpecificValue(R.string.runForTheFirstTime)
        if (firstRunState != "1") {
            customViewModel.copyShpFilesJob = lifecycleScope.launch(Dispatchers.Default) {
                DocuAndManageFun().copyShpFilesToExternalStorage(this@MainActivity)
            }
        }

        // 设置apikey使用功能
        ArcGISRuntimeEnvironment.setApiKey(BuildConfig.API_KEY)

        // 初始化selectDirectoryLauncher
        selectWriteDirectoryLauncher = selectDirectoryLauncher(this)
        selectLoadShpFileLauncher = selectShpFileLauncher(this)

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

    /**
     * 实验数据保存目录选择器（作业开始时目录未设置/失效的兜底弹窗）
     * @note   持久化逻辑与设置界面共用 DocuAndManageFun.persistWriteDirectory；
     *         不再往目录里写 writetest.txt 试探文件，改为 Toast 反馈
     */
    private fun selectDirectoryLauncher(context: Context): ActivityResultLauncher<Uri?> {
        return registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) {
                Toast.makeText(context, "未选择保存目录，本次作业不会保存实验数据", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            val info = DocuAndManageFun().persistWriteDirectory(context, uri)
            Toast.makeText(context, "保存目录已设置：${info.displayPath}", Toast.LENGTH_LONG).show()
        }
    }

    private fun selectShpFileLauncher(
        context: Context,
    ): ActivityResultLauncher<Intent> {
        return registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val selection = result.data ?: return@registerForActivityResult
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uris = selection.clipData?.let { clips ->
                (0 until clips.itemCount).mapNotNull { clips.getItemAt(it).uri }
            } ?: listOfNotNull(selection.data)
            val depthMode = mVariableFertViewModel.depthPrescriptionMode.value
            lifecycleScope.launch {
                val path = withContext(Dispatchers.IO) {
                    DocuAndManageFun().resolveSelectedShapefile(context, uris)
                }
                if (mVariableFertViewModel.workRunning.value == true ||
                    mVariableFertViewModel.depthPrescriptionMode.value != depthMode
                ) return@launch
                if (path != null) {
                    MyArcGisFun().getFieldList(path, mVariableFertViewModel)
                } else {
                    AlertDialog.Builder(context)
                        .setTitle("无法读取处方图")
                        .setMessage("请选中 .shp 文件及同名的 .shx、.dbf、.prj 文件后重试。")
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }
    }
    @OptIn(ExperimentalLayoutApi::class)
    @Composable

    fun StartAndStopBottom() {
        val context = LocalContext.current
        val isRunning by mVariableFertViewModel.workRunning.observeAsState(false)

        Box(
            modifier = Modifier
                .padding(dimensionResource(id = R.dimen.small_custom_padding))
                .fillMaxSize()
        ) {
            val configuration = LocalConfiguration.current
            val isInPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

            FlowRow(
                modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.2f),
                maxItemsInEachRow = if (isInPortrait) 1 else 2,
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.Center
            ) {
                // ================== 开始按钮 ==================
                Surface(
                    color = if (isRunning) androidx.compose.ui.graphics.Color.Gray else androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.width(90.dp).padding(dimensionResource(id = R.dimen.middle_view_padding)),
                    shape = RoundedCornerShape(dimensionResource(id = R.dimen.small_roundedCornerShape)),
                ) {
                    IconButton(
                        enabled = !isRunning,
                        onClick = {
                            if (mVariableFertViewModel.workRunning.value != true && !mVariableFertViewModel.isDepthTrialActive()) {
                                val depthMode = mVariableFertViewModel.depthPrescriptionMode.value == true
                                val isTestMode = MySharedPreFun(context).getSpecificValue(R.string.testMode_Switch_name) == "1"
                                val depthSimulation = depthMode && isTestMode
                                val simGnss = MySharedPreFun(context).getSpecificValue(R.string.simGnss_Switch_name) == "1"

                                // 1. 【修复】：测试模式 / 模拟GNSS 模式下跳过申请文件夹，不要弹出文件选择器！
                                if (!isTestMode && !simGnss) {
                                    // 目录未设置或授权失效时弹选择器；正常情况下操作员已在设置界面选好目录
                                    DocuAndManageFun().cheekDirectoryUriPermission(
                                        selectWriteDirectoryLauncher, context
                                    )
                                    if (MySharedPreFun(context).getSpecificValue(R.string.writeSaveData_Switch_name) == "1") {
                                        val spf = MySharedPreFun(context)
                                        val includeFert = spf.getSpecificValue(R.string.saveGroupFert_Switch_name) != "0"   // 默认开
                                        val includeDepth = spf.getSpecificValue(R.string.saveGroupDepth_Switch_name) == "1"
                                        myWriteSaveFun.start(
                                            context = context,
                                            mVariableFertViewModel = mVariableFertViewModel,
                                            header = myWriteSaveFun.buildSaveHeader(includeFert, includeDepth),
                                            getData = {
                                                myWriteSaveFun.getMySaveData(mSPParamData.rowNumber, mVariableFertViewModel, includeFert, includeDepth)
                                            }
                                        )
                                    }
                                }

                                // 2. 打开串口
                                if (depthSimulation) {
                                    MySerialPortFun().releaseSerialPort(mVariableFertViewModel)
                                    if (!simGnss) MySerialPortFun().openSerialPort(
                                        context, mVariableFertViewModel, allowPartial = true, openCan = false
                                    )
                                } else if (mVariableFertViewModel.serialPortIsRunning.value == false) {
                                    MySerialPortFun().openSerialPort(context, mVariableFertViewModel, allowPartial = depthMode)
                                }

                                // 3. 每次开始重新读取 UI 最新参数 (包括单体启停控制)
                                MySharedPreFun(context).initSettingsParam()

                                // ================= 【核心修复：开始】 =================
                                // 读取单体启停状态字符串，转成 Boolean 数组并传给 ViewModel，
                                // 让 ViewModel 在计算准确率时跳过那些被关闭的电机
                                val sharedPre = MySharedPreFun(context).getMySharedPre()
                                val activeStr = sharedPre.getString("active_motors_state", "1,1,1,1,1,1,1,1") ?: "1,1,1,1,1,1,1,1"
                                val activeArray = activeStr.split(",").map { it == "1" }.toBooleanArray()
                                mVariableFertViewModel.activeMotorsState.value = activeArray
                                // ================= 【核心修复：结束】 =================

                                // 4. 【修复】：清空平均施肥量和准确率统计，从头算
                                if (!depthMode) mVariableFertViewModel.resetFertStats()
                                // 重置本次运行报错标志
                                errorTriggeredThisRun = false

                                val soundEnabledAtStart = MySharedPreFun(context).getSpecificValue(R.string.errorSound_Switch_name) == "1"
                                // 4a. 开始前提示：排肥系数未配置（A/B 全为 0）
                                if (!depthMode && soundEnabledAtStart && fittingCoefficientA.all { it == 0.0 } && fittingCoefficientB.all { it == 0.0 }) {
                                    enqueueErrorSound(R.raw.fail6)
                                }

                                // 4b. 开始前提示：非测试 / 非模拟GNSS 模式下没有 RTK 数据
                                if (!depthMode && soundEnabledAtStart && !isTestMode && !simGnss && mRmcData.latitude == 0.0 && mRmcData.longitude == 0.0) {
                                    enqueueErrorSound(R.raw.fail7)
                                }

                                if (depthMode) {
                                    mVariableFertViewModel.invalidateDepthPrescriptionLocation()
                                    mVariableFertViewModel.restoreSowingDepthPersistentState(
                                        MySharedPreFun(context).loadSowingDepthState(),
                                        masterEnabledOverride = !depthSimulation
                                    )
                                }
                                mVariableFertViewModel.canEverReceived = false
                                mVariableFertViewModel.rtkEverReceived = false
                                mVariableFertViewModel.canLastReceiveTime.value = System.currentTimeMillis()
                                isSystemRunning = true
                                mVariableFertViewModel.workRunning.value = true

                                // 5. 模式隔离
                                if (isTestMode && !depthMode) {
                                    if (!myTestModeCoroutine.isRunning) {
                                        myTestModeCoroutine.start(context) {
                                            lifecycleScope.launch { stopMainWork() }
                                        }
                                    }
                                    // 【修复】：测试模式必须也打开接收，这样底部图表才能收到反馈发生变化！
                                    if (!mCanReceiveCoroutine.isRunning) mCanReceiveCoroutine.start(mVariableFertViewModel, context)
                                } else {
                                    if (!mDB9reCANseCoroutine.isRunning) {
                                        if (simGnss) mDB9reCANseCoroutine.start1Simulated(context, mVariableFertViewModel)
                                        else         mDB9reCANseCoroutine.start1(context, mVariableFertViewModel)
                                    }
                                    if (!mCanReceiveCoroutine.isRunning) mCanReceiveCoroutine.start(mVariableFertViewModel, context)
                                }

                                if (depthMode && !depthSimulation) {
                                    if (!mSowingDepthCoroutine.isRunning) {
                                        mSowingDepthCoroutine = SowingDepthCoroutine()
                                        mSowingDepthCoroutine.start(mVariableFertViewModel, context, usePrescription = true)
                                    }

                                    // 5c. 控深就绪诊断：留出上线检测窗口后评估一次，
                                    // 若仍无电机可下发位置命令则在主界面弹一次提示。
                                    depthReadinessJob = lifecycleScope.launch {
                                        delay(3000)   // 等心跳/TPDO + 2s 离线看门狗判定
                                        if (isSystemRunning &&
                                            mVariableFertViewModel.depthPrescriptionMode.value == true
                                        ) {
                                            val warn = mVariableFertViewModel.currentSowingDepthState()
                                                .depthControlReadinessWarning(
                                                    rowNumber = mSPParamData.rowNumber,
                                                    activeMotors = mVariableFertViewModel.activeMotorsState.value
                                                        ?: mSPParamData.activeMotors,
                                                    configuredDepthField = MySharedPreFun(context)
                                                        .getSpecificValue(R.string.depthQueryField_name) ?: ""
                                                )
                                            if (warn != null) {
                                                mVariableFertViewModel.depthControlNotice.postValue(warn)
                                            }
                                        }
                                    }
                                }

                                // 6. 启动 CAN 看门狗（测试模式和正常模式都启动）
                                // fail3：从未收到数据（一开始没接好 / 测试模式未插CAN）
                                // fail2：曾经收到过、后续中断（电机不再返回字节）
                                watchdogJob?.cancel()
                                watchdogShouldRun = true
                                watchdogJob = lifecycleScope.launch {
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
                                        if (depthMode) continue

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
                    color = if (isRunning) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Gray,
                    modifier = Modifier.width(90.dp).padding(dimensionResource(id = R.dimen.middle_view_padding)),
                    shape = RoundedCornerShape(dimensionResource(id = R.dimen.small_roundedCornerShape)),
                ) {
                    IconButton(
                        enabled = isRunning,
                        onClick = { lifecycleScope.launch { stopMainWork() } }
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

    private suspend fun stopMainWork() {
        if (!isSystemRunning) return
        isSystemRunning = false
        val depthMode = mVariableFertViewModel.depthPrescriptionMode.value == true
        depthReadinessJob?.cancel()
        if (mDB9reCANseCoroutine.isRunning) mDB9reCANseCoroutine.shutdown()
        if (myTestModeCoroutine.isRunning) myTestModeCoroutine.shutdown()
        if (myWriteSaveFun.isRunning) myWriteSaveFun.shutdown()

        try {
            if (depthMode) {
                mVariableFertViewModel.invalidateDepthPrescriptionLocation()
                if (mSowingDepthCoroutine.isRunning) {
                    mSowingDepthCoroutine.stopForWork(mVariableFertViewModel)
                    delay(100)
                }
            } else {
                delay(100)
                withContext(Dispatchers.IO) {
                    for (i in 0 until mSPParamData.rowNumber) {
                        ConvAndCtrlFun().motorSpeedrpmSend(0.0, i)
                        delay(15)
                    }
                    delay(150)
                }
            }
        } finally {
            releaseResource(mVariableFertViewModel)
            mVariableFertViewModel.workRunning.value = false
        }
        val errorSoundOn = MySharedPreFun(this).getSpecificValue(R.string.errorSound_Switch_name) == "1"
        if (errorSoundOn && !errorTriggeredThisRun) enqueueErrorSound(R.raw.good, isError = false)
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
        // 仅首次创建，绝不在回到前台时重建：旧实例协程作用域独立于生命周期，重建会泄漏仍在发 CAN
        // 的旧协程，并使“停止”作用到新空实例上导致电机停不下来。构造不依赖 rowNumber（polyPointExport
        // 固定 8 路），start1/start1Simulated 每次启动已重建内部 scope，单实例复用安全。
        if (!::mDB9reCANseCoroutine.isInitialized) {
            mDB9reCANseCoroutine = DB9reCANseCoroutine()
        }
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
    private fun handleRootBack() {
        if (mVariableFertViewModel.workRunning.value == true || mVariableFertViewModel.isDepthTrialActive()) {
            Toast.makeText(this, "请先停止作业或试验", Toast.LENGTH_SHORT).show()
            return
        }
        val currentTime = android.os.SystemClock.elapsedRealtime()
        if (currentTime - lastBackPressTime < 1000) {
            finish()
        } else {
            lastBackPressTime = currentTime
            Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isSystemRunning = false
        mVariableFertViewModel.workRunning.value = false
        releaseResource(mVariableFertViewModel)
        mapView.dispose()
    }

    private fun releaseResource(viewModel: VariableFertViewModel) {
        watchdogShouldRun = false
        watchdogJob?.cancel()
        depthReadinessJob?.cancel()
        clearErrorSounds()
        if (::mDB9reCANseCoroutine.isInitialized && mDB9reCANseCoroutine.isRunning) mDB9reCANseCoroutine.shutdown()
        if (mCanReceiveCoroutine.isRunning) mCanReceiveCoroutine.shutdown()
        if (myTestModeCoroutine.isRunning) myTestModeCoroutine.shutdown()
        if (myWriteSaveFun.isRunning) myWriteSaveFun.shutdown()
        if (mSowingDepthCoroutine.isRunning) mSowingDepthCoroutine.shutdown()
        if (!viewModel.isDepthTrialActive() && (mSerialPortDB9 != null || mSerialPortCAN != null)) {
            MySerialPortFun().releaseSerialPort(viewModel)
        }
    }
}
