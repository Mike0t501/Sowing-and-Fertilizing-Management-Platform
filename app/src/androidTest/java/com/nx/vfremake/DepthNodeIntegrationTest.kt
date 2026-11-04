/** @author NIANXI @brief 在真实Android设备上验证模拟协议→采集→拟合→配置→实测出口。 */
package com.nx.vfremake

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nx.vfremake.funClass.*
import com.nx.vfremake.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DepthNodeIntegrationTest {
    @Test fun sixGaugeCapturesUseRealStatusFlowAndCfgClientUsesSimulatedWire() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val holder = ViewModelStore()
        lateinit var vm: VariableFertViewModel
        instrumentation.runOnMainSync {
            vm = VariableFertViewModel()
            holder.put("depth-node-test", vm)
            vm.configureDepthNodeSimulation(true)
        }
        val simulator = vm.depthNodeSimulator!!
        val ticker = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) { simulator.tick(List(8) { 40f }); vm.depthNodeRuntime.tick(); delay(10) }
        }
        try {
            withTimeout(3000) { while (!vm.currentDepthNodeState().channels[0].isOnline) delay(10) }
            delay(120)
            val captures = mutableListOf<DepthCalibrationFit.Capture>()
            val heights = listOf(0f, 20f, 40f, 60f, 80f, 100f)
            val session = DepthNodeCalibrationSession(vm.depthNodeRuntime)
            for (height in heights) {
                simulator.holdDepth(0, height); delay(60)
                val started = android.os.SystemClock.elapsedRealtime()
                captures.add(session.capture(0))
                assertTrue(android.os.SystemClock.elapsedRealtime() - started >= 1950)
                assertTrue(captures.last().count >= 28)
            }
            val points = captures.mapIndexed { i, capture -> capture.meanMv to heights[i] }
            val fit = DepthCalibrationFit.fit(points, 2).getOrThrow()
            assertTrue(fit.maxResidualMm < 0.1f)
            assertTrue(DepthCalibrationFit.unstableIndices(fit, points.map { it.first }, captures.map { it.stdMv }).isEmpty())
            vm.updateDepthNodeChannel(0) { it.copy(calibrationPoints = points, calibrationStdMv = captures.map { capture -> capture.stdMv },
                fitOrder = 2, c0 = fit.c0, c1 = fit.c1, c2 = fit.c2, fitValid = true) }
            simulator.holdDepth(0, 35f); delay(80)
            assertEquals(35f, vm.measuredDepthFacade.forRow(0).depthMm!!, 0.1f)
            vm.depthNodeClient.applyFilter(0x680, DepthNodeFilterParams(rankN = 1, smoother = 0))
            assertEquals(0xD1, vm.currentDepthNodeState().channels[0].cfgCrc)
            vm.depthNodeClient.burn(0x680)
            simulator.addFactoryNode(); delay(80)
            withTimeout(4000) { vm.depthNodeClient.provision(7) }
            assertEquals(0x10, vm.currentDepthNodeState().channels[7].icdVer)
            assertTrue(vm.currentDepthNodeState().simulated)
            instrumentation.runOnMainSync { vm.configureDepthNodeSimulation(false) }
            assertFalse(vm.currentDepthNodeState().channels[0].fitValid)
        } finally {
            ticker.cancelAndJoin()
            instrumentation.runOnMainSync { holder.clear() }
        }
    }
}
