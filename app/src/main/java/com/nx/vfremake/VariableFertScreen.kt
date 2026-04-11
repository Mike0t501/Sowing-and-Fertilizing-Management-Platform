/**
 ************************************************************************************************************
 * @author  :NIANXI
 * @date    :2024年7月6日17:10:30
 * @file    :
 * @brief   :navigation导航页面
 * ---------------------------------------------------------------------------------------------------------
 *                                         Change History
 * ---------------------------------------------------------------------------------------------------------
 * 主界面
 * 设置界面
 ***********************************************************************************************************
 */
package com.nx.vfremake

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.esri.arcgisruntime.mapping.view.MapView
import com.nx.vfremake.funClass.MySerialPortFun
import com.nx.vfremake.ui.DantiFertSettings
import com.nx.vfremake.ui.MainScreen
import com.nx.vfremake.ui.ParamSettingsScreen
import com.nx.vfremake.ui.SettingsScreen

// 枚举字符串对应各个页面名称
enum class VariableFertScreen(@StringRes val title: Int) {
    Main(title = R.string.app_name),
    ParamSet(title = R.string.param_settings),
    Settings(title = R.string.settings),
    DantiSettings(title = R.string.settings)
}

/**
 * 应用navigation导航视图
 * @param  mVariableFertViewModel:更新地图UI viewmodel
 * @param  mVariableFertViewModel:更新信息栏UI viewmodel
 * @param  content:传入compose函数，Mainactivity里的开始停止按钮
 * @return
 * @note
 */
@Composable
fun VariableFert(
    mapView: MapView,
    mVariableFertViewModel: VariableFertViewModel,
    content: @Composable () -> Unit,
) {
    val navController: NavHostController = rememberNavController()
    val context = LocalContext.current
    // 使用 remember 来保持 MapView 实例稳定，并且这里是顶层compose，值改变才会重建，避免无改变但声明刷新重建
    val mapViewRemember = remember { mapView }

    NavHost(
        navController = navController,
        startDestination = VariableFertScreen.Main.name,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(route = VariableFertScreen.Main.name) {
            MainScreen(
                mapView = mapViewRemember,
                mVariableFertViewModel = mVariableFertViewModel,
                onClickSettigns = { navController.navigate(VariableFertScreen.Settings.name) },
                onClickParamSet = { navController.navigate(VariableFertScreen.ParamSet.name) }
            )
            content()
        }
        composable(route = VariableFertScreen.ParamSet.name) {
            ParamSettingsScreen(
                onClickBack = { navController.popBackStack() }
            )
        }
        composable(route = VariableFertScreen.Settings.name) {
            SettingsScreen(
                onClickBack = { navController.popBackStack() },
                onClickDantiSettigns = { navController.navigate(VariableFertScreen.DantiSettings.name) },
                onReinitSerialPort = {
                    if (isSystemRunning) {
                        Toast.makeText(context, "系统运行中，请先停止后再应用", Toast.LENGTH_SHORT).show()
                    } else {
                        MySerialPortFun().reinitSerialPort(context, mVariableFertViewModel)
                        val ok = mVariableFertViewModel.serialPortIsRunning.value == true
                        Toast.makeText(context, if (ok) "串口已重新打开" else "串口打开失败，请检查配置", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
        composable(route = VariableFertScreen.DantiSettings.name) {
            DantiFertSettings(
                onClickBack = { navController.popBackStack() }
            )
        }
    }
}
