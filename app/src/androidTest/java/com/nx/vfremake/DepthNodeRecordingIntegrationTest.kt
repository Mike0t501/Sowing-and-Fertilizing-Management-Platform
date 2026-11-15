/** @author NIANXI @brief 通过 SAF 验证手动与试验记录格式。使用合成节点，无电机发送。 */
package com.nx.vfremake

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.nx.vfremake.coroutine.DepthRecordFun
import com.nx.vfremake.coroutine.MyWriteSaveFun
import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class DepthNodeRecordingIntegrationTest {
    @Test fun manualAndTrialRecordersWriteCompatibleCsvMetadataAndEventsThroughSaf() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = DocuAndManageFun()
        val directory = manager.getWriteDirDocumentUri(context)
        assertNotNull("请先在设置页授权记录目录", directory)
        assertFalse("实机作业中不得运行模拟验收", isSystemRunning)
        val before = documents(context, directory!!).keys.toSet()
        val holder = ViewModelStore()
        val manual = DepthRecordFun()
        val trialRecorder = DepthRecordFun()
        lateinit var vm: VariableFertViewModel
        instrumentation.runOnMainSync {
            vm = VariableFertViewModel(); holder.put("depth-record-qa", vm)
            vm.restoreSowingDepthPersistentState(MySharedPreFun(context).loadSowingDepthState(), false)
            val originalRowCount = mSPParamData.rowNumber
            try {
                mSPParamData.rowNumber = 2
                vm.configureDepthNodeSimulation(true)
            } finally {
                mSPParamData.rowNumber = originalRowCount
            }
            vm.saveDepthNodeCalibration(context, 0) {
                DepthNodeCalib.withLinearCoefficients(it, 25.0, -12.5, DepthNodeVoltageUnit.V, "2026-11-04T00:00:00Z")
            }
            vm.updateServoCalibration(0) { it.copy(targetDepth = 40f, currentDepth = 40f, isOnline = true,
                isEnabled = true, limitsSet = true, limitMin = 0, limitMax = 100000,
                fitValid = true, fitA = 0.001f, fitB = 0f) }
        }
        val simulator = vm.depthNodeSimulator!!
        val ticker = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                // 合成采样反馈，记录器不拥有执行器。
                vm.updateServoCalibration(0) { it.copy(currentDepth = it.targetDepth,
                    currentPosition = (it.targetDepth * 1000).toInt()) }
                simulator.tick(vm.currentSowingDepthState().motors.map { it.targetDepth })
                vm.depthNodeRuntime.tick(); delay(10)
            }
        }
        try {
            simulator.holdDepth(0, 3f); delay(200)
            simulator.holdDepth(1, 60f); delay(100)
            val workRecorder = MyWriteSaveFun()
            val workHeader = workRecorder.buildSaveHeader(includeFert = false, includeDepth = true)
            val workRows = workRecorder.getMySaveData(2, vm, includeFert = false, includeDepth = true)
            assertTrue(workRows.all { it.size == workHeader.size - 1 })
            assertEquals(listOf("0", "1"), workRows.map { it.first() })
            assertTrue(workRows[0].takeLast(6).first().toFloat() in 619f..621f)
            assertTrue(workRows[1].takeLast(6).first().toFloat() in 2899f..2901f)
            assertEquals("UNCALIBRATED", workRows[1].last())
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
            val trial = DepthTestConfig(0, startDepthMm = 20f, endDepthMm = 40f,
                stepMm = 20f, dwellMs = 300, stableSamples = 1, timeoutMs = 2000,
                positionSpeed = control.positionSpeed, acceleration = control.acceleration, recordIntervalMs = 50)
            assertTrue(trialRecorder.start(context, vm, "depthTest_QA", DepthRecordSchema.TRIAL_HEADER,
                intervalMs = trial.recordIntervalMs, recordRows = listOf(0), trial = trial, simulatedGnss = true
            ) {
                val motor = vm.currentSowingDepthState().motors[0]
                val fix = DepthTrialFix(1000, 115.1, 36.1, 115.2, 36.2, true)
                listOf(listOf("1", "停留", "40.00", "40.00", motor.currentPosition.toString(), "1", "0") +
                    DepthRecordSchema.trialColumns(2, DepthTestPhase.DWELL, fix, 1100, true))
            })
            assertTrue(withTimeout(10_000) { trialRecorder.awaitReady() })
            delay(250)
            trialRecorder.shutdownAndJoin()
            val after = documents(context, directory)
            val created = after.filterKeys { it !in before }
            val main = created.filterKeys { (it.startsWith("depthRec_") || it.startsWith("depthTest_")) && it.endsWith(".csv") }
            assertEquals(2, main.size)
            for ((name, uri) in main) {
                val text = read(context, uri).removePrefix("\uFEFF")
                val lines = text.trimEnd().lines()
                val isTrial = name.startsWith("depthTest_")
                val header = if (isTrial) DepthRecordSchema.TRIAL_HEADER else DepthRecordSchema.HEADER
                assertEquals(header, lines.first().split(','))
                assertTrue(lines.size > 2)
                assertTrue(lines.drop(1).all { it.split(',').size == header.size })
                assertTrue(lines.drop(1).all { line ->
                    val values = line.split(',')
                    values[header.indexOf("ds_u_filt_mv")].toFloatOrNull() != null &&
                        values[header.indexOf("ds_u_mean_mv")].toFloatOrNull() != null
                })
                if (isTrial) assertTrue(lines[1].endsWith(",2,DWELL,ROW_FIX,100,115.10000000,36.10000000,115.20000000,36.20000000,1"))
                val metaName = name.removeSuffix(".csv") + ".meta.json"
                val meta = JsonParser.parseString(read(context, created.getValue(metaName))).asJsonObject
                assertEquals("depth_node_v1", meta["record_schema"].asString)
                assertTrue(meta["simulation"].asBoolean)
                assertEquals("EXTERNAL_LINEAR", meta["channels"].asJsonArray[0].asJsonObject["calibration_source"].asString)
                if (isTrial) assertEquals(3, meta["depth_trial"].asJsonObject["stages_mm"].asJsonArray.size())
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
            trialRecorder.shutdownAndJoin(); manual.shutdownAndJoin()
            withTimeout(5000) { while (vm.isDepthRecording()) delay(10) }
            ticker.cancelAndJoin()
            instrumentation.runOnMainSync { holder.clear() }
            val created = documents(context, directory).filterKeys { it !in before }
            val qaFiles = created.keys.filter { it.startsWith("depthRec_QA_") || it.startsWith("depthTest_QA_") }
            val qaStamps = qaFiles.map { it.substringAfter("_QA_").substringBefore('.') }.toSet()
            created.filterKeys { it in qaFiles || it.removePrefix("depthEvt_").removeSuffix(".csv") in qaStamps }
                .values.forEach { DocumentsContract.deleteDocument(context.contentResolver, it) }
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
