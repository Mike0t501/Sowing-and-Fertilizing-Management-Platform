/** @author NIANXI @brief 平板SAF实测：手动记录、一键梯度、meta和事件文件。仅合成节点，无电机发送。 */
package com.nx.vfremake

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.nx.vfremake.coroutine.DepthRecordFun
import com.nx.vfremake.coroutine.DepthTestCoroutine
import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class DepthNodeRecordingIntegrationTest {
    @Test fun actualRecordersWriteTwentyOneColumnsMetadataAndEventsThroughSaf() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = DocuAndManageFun()
        val directory = manager.getWriteDirDocumentUri(context)
        assertNotNull("请先在设置页授权记录目录", directory)
        assertFalse("实机作业中不得运行模拟验收", isSystemRunning)
        val before = documents(context, directory!!).keys.toSet()
        val prefs = context.getSharedPreferences("sowing_depth_prefs", Context.MODE_PRIVATE)
        val prefsBackup = prefs.all.toMap()
        val holder = ViewModelStore()
        val manual = DepthRecordFun()
        val automatic = DepthTestCoroutine()
        lateinit var vm: VariableFertViewModel
        instrumentation.runOnMainSync {
            vm = VariableFertViewModel(); holder.put("depth-record-qa", vm)
            vm.restoreSowingDepthPersistentState(MySharedPreFun(context).loadSowingDepthState(), false)
            vm.configureDepthNodeSimulation(true)
            vm.updateDepthNodeChannel(0) { it.copy(calibrationPoints = listOf(500f to 0f, 2500f to 50f, 4500f to 100f),
                calibrationStdMv = listOf(0.1f, 0.1f, 0.1f), fitOrder = 1, c0 = -12.5, c1 = 0.025,
                fitValid = true, calibratedAt = "2026-11-04T00:00:00Z") }
            vm.updateServoCalibration(0) { it.copy(targetDepth = 40f, currentDepth = 40f, isOnline = true,
                isEnabled = true, limitsSet = true, limitMin = 0, limitMax = 100000,
                fitValid = true, fitA = 0.001f, fitB = 0f) }
        }
        val simulator = vm.depthNodeSimulator!!
        val ticker = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                // 验收只模拟伺服到位反馈，不创建SowingDepthCoroutine，不会下发运动帧。
                vm.updateServoCalibration(0) { it.copy(currentDepth = it.targetDepth,
                    currentPosition = (it.targetDepth * 1000).toInt()) }
                simulator.tick(vm.currentSowingDepthState().motors.map { it.targetDepth })
                vm.depthNodeRuntime.tick(); delay(10)
            }
        }
        try {
            simulator.holdDepth(0, 3f); delay(200)
            assertTrue(manual.start(context, vm, "depthRec_QA", DepthRecordFun.buildDepthRecordHeader(),
                intervalMs = 50, recordRows = listOf(0)) { _ ->
                val motor = vm.currentSowingDepthState().motors[0]
                listOf(listOf("1", "手动模拟验收", "%.2f".format(Locale.ROOT, motor.targetDepth),
                    "%.2f".format(Locale.ROOT, motor.currentDepth), motor.currentPosition.toString(), "1", "0"))
            })
            assertTrue(withTimeout(10000) { manual.awaitReady() })
            delay(250); assertEquals(40f, vm.currentSowingDepthState().motors[0].targetDepth, 0f)
            simulator.holdDepth(0, 40f); delay(250)
            manual.shutdown()
            withTimeout(5000) { while (vm.isDepthRecording()) delay(10) }
            simulator.holdDepth(0, null)
            val control = vm.currentSowingDepthState()
            automatic.start(context, vm, DepthTestConfig(0, startDepthMm = 20f, endDepthMm = 40f,
                stepMm = 20f, dwellMs = 300, stableSamples = 1, timeoutMs = 2000,
                positionSpeed = control.positionSpeed, acceleration = control.acceleration, recordIntervalMs = 50))
            withTimeout(15000) { while (automatic.isRunning || vm.isDepthRecording()) delay(20) }
            assertTrue(vm.depthTestStatus.value?.finished == true)
            val after = documents(context, directory)
            val created = after.filterKeys { it !in before }
            val main = created.filterKeys { (it.startsWith("depthRec_") || it.startsWith("depthTest_")) && it.endsWith(".csv") }
            assertEquals(2, main.size)
            for ((name, uri) in main) {
                val text = read(context, uri).removePrefix("\uFEFF")
                val lines = text.trimEnd().lines()
                assertEquals(DepthRecordSchema.HEADER, lines.first().split(','))
                assertTrue(lines.size > 2)
                assertTrue(lines.drop(1).all { it.split(',').size == 21 })
                val metaName = name.removeSuffix(".csv") + ".meta.json"
                val meta = JsonParser.parseString(read(context, created.getValue(metaName))).asJsonObject
                assertEquals("depth_node_v1", meta["record_schema"].asString)
                assertTrue(meta["simulation"].asBoolean)
                val eventName = meta["event_file"].asString
                val events = read(context, created.getValue(eventName)).removePrefix("\uFEFF").trimEnd().lines()
                assertEquals(DepthEventSchema.HEADER, events.first().split(','))
                if (name.startsWith("depthRec_")) assertTrue(events.drop(1).any { it.contains("LIFT_OFF") })
                android.util.Log.i("DepthNodeQA", "Verified CSV/meta/events: $name")
            }
            val listed = manager.listExperimentCsvFiles(context)!!
            assertTrue(listed.none { it.displayName.endsWith(".json") })
            assertTrue(listed.any { it.displayName.startsWith("depthEvt_") && it.displayName in created })
        } finally {
            automatic.shutdown(); manual.shutdown()
            withTimeout(5000) { while (vm.isDepthRecording()) delay(10) }
            ticker.cancelAndJoin()
            instrumentation.runOnMainSync { holder.clear() }
            // 一键测试会保存运动参数；恢复原有偏好，验收不得覆盖设备上的真实标定。
            val editor = prefs.edit().clear()
            for ((key, value) in prefsBackup) when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            check(editor.commit())
        }
    }

    private fun read(context: Context, uri: Uri): String = context.contentResolver.openInputStream(uri)!!
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun documents(context: Context, directory: Uri): Map<String, Uri> {
        val result = linkedMapOf<String, Uri>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(directory, DocumentsContract.getDocumentId(directory))
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { cursor ->
            while (cursor.moveToNext()) result[cursor.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(directory, cursor.getString(0))
        }
        return result
    }
}
