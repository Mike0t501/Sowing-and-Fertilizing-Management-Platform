package com.nx.vfremake

import android.webkit.JavascriptInterface

/**
 * 网页(assets/web/index.html) ↔ 原生 的桥接。
 *
 * 用法（在 MainActivity 里）：
 *   webView.addJavascriptInterface(
 *       WebBridge(
 *           onSetRunning = { run -> if (run) startWork() else stopWork() },
 *           onLoadShp    = { field -> /* 设字段 + MyArcGisFun().loadShp(...) */ },
 *           onSelectShp  = { DocuAndManageFun().selectShapefile(selectLoadShpFileLauncher) },
 *           onSaveParams = { json -> /* 解析 json 存 SharedPreferences */ }
 *       ),
 *       "NativeBridge"
 *   )
 *
 * 这些方法运行在 WebView 的 JS 线程，回调里若要操作 UI/串口，
 * 请用 runOnUiThread { ... } 切回主线程（见 INTEGRATION.md）。
 */
class WebBridge(
    private val onSetRunning: (Boolean) -> Unit,
    private val onLoadShp: (String) -> Unit,
    private val onSelectShp: () -> Unit = {},
    private val onSaveParams: (String) -> Unit = {},
    private val onRunDiagnostic: () -> Unit = {},
    private val onPage: (String) -> Unit = {},
    private val onSaveDanti: (String) -> Unit = {},
    private val onMapOp: (String) -> Unit = {},
    private val onSetMapRect: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    private val onSendTest: (Double, Boolean) -> Unit = { _, _ -> },
    private val onMapVisible: (Boolean) -> Unit = {},
) {
    @JavascriptInterface
    fun setRunning(run: Boolean) = onSetRunning(run)

    @JavascriptInterface
    fun loadShp(field: String) = onLoadShp(field)

    @JavascriptInterface
    fun selectShp() = onSelectShp()

    @JavascriptInterface
    fun saveParams(json: String) = onSaveParams(json)

    @JavascriptInterface
    fun runDiagnostic() = onRunDiagnostic()

    @JavascriptInterface
    fun setPage(page: String) = onPage(page)

    @JavascriptInterface
    fun saveDanti(json: String) = onSaveDanti(json)

    @JavascriptInterface
    fun mapOp(op: String) = onMapOp(op)

    @JavascriptInterface
    fun setMapRect(left: Int, top: Int, width: Int, height: Int) = onSetMapRect(left, top, width, height)

    @JavascriptInterface
    fun sendTest(value: Double, isRpm: Boolean) = onSendTest(value, isRpm)

    @JavascriptInterface
    fun setMapVisible(visible: Boolean) = onMapVisible(visible)
}
