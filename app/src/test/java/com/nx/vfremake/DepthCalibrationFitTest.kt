/** @author NIANXI @brief 标定回归使用已知曲线，覆盖零深度和退化输入。 */
package com.nx.vfremake

import com.nx.vfremake.data.DepthNodeChannel
import com.nx.vfremake.data.DepthNodeVoltageUnit
import com.nx.vfremake.funClass.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class DepthCalibrationFitTest {
    @Test fun knownQuadraticWithNoiseRecoversCurveAfterCenteringAndScaling() {
        val points = (0..8).map { index ->
            val mv = 500f + index * 450f
            mv to (2 + 0.015 * mv + 0.000002 * mv * mv + if (index % 2 == 0) 0.04 else -0.04).toFloat()
        }
        val fit = DepthCalibrationFit.fit(points, 2).getOrThrow()
        assertEquals(2.0, fit.c0, 0.1)
        assertEquals(0.015, fit.c1, 0.0001)
        assertEquals(0.000002, fit.c2, 0.0000001)
        assertTrue(fit.rSquared > 0.999)
        assertTrue(fit.maxResidualMm < 0.1)
    }

    @Test fun zeroDepthAndNegativeSlopeAreAccepted() {
        val fit = DepthCalibrationFit.fit(listOf(500f to 100f, 2500f to 50f, 4500f to 0f), 1).getOrThrow()
        assertEquals(112.5, fit.c0, 0.0001)
        assertEquals(-0.025, fit.c1, 0.000001)
        assertEquals(0.0, fit.at(4500.0), 0.0001)
    }

    @Test fun insufficientDegenerateAndNonmonotonicDataAreRejected() {
        assertTrue(DepthCalibrationFit.fit(listOf(500f to 0f, 4500f to 100f), 1).isFailure)
        assertTrue(DepthCalibrationFit.fit(listOf(500f to 0f, 2500f to 50f, 4500f to 100f), 2).isFailure)
        assertTrue(DepthCalibrationFit.fit(List(4) { 1000f to it.toFloat() }, 2).isFailure)
        assertTrue(DepthCalibrationFit.fit(listOf(0f to 100f, 1000f to 0f, 2000f to 100f, 3000f to 400f), 2).isFailure)
        assertTrue(DepthCalibrationFit.fit(listOf(Float.NaN to 0f, 1000f to 20f, 2000f to 40f), 1).isFailure)
    }

    @Test fun stabilityUsesLocalSlopeAndRealDistinctFrames() {
        val fit = DepthCalibrationFit.fit(listOf(500f to 0f, 2500f to 50f, 4500f to 100f), 1).getOrThrow()
        assertEquals(listOf(1), DepthCalibrationFit.unstableIndices(fit, listOf(500f, 2500f), listOf(1f, 20f)))
        val capture = DepthCalibrationFit.CaptureAccumulator()
        capture.add(1000f, 1, 1); capture.add(1000f, 1, 1)
        assertTrue(runCatching { capture.result() }.isFailure)
        capture.add(1002f, 51, 2)
        assertEquals(2, capture.result().count)
        assertEquals(1001f, capture.result().meanMv, 0.001f)
        assertEquals(1.4142f, capture.result().stdMv, 0.001f)
    }

    @Test fun jsonRoundTripPreservesTargetAddressAndRuntimeAndRejectsChangedCurve() {
        val source = DepthNodeChannel(0, calibrationPoints = listOf(500f to 0f, 2500f to 50f, 4500f to 100f),
            calibrationStdMv = listOf(1f, 1f, 1f), fitOrder = 1, c0 = -12.5, c1 = 0.025,
            fitValid = true, calibratedAt = "2026-11-04T00:00:00Z", offsetTrimMm = 1f)
        val json = DepthNodeCalibIoFun.encode(source, "3.1.0", "2026-11-04T00:00:00Z")
        val decoded = DepthNodeCalibIoFun.decode(json).getOrThrow()
        val target = DepthNodeChannel(3, isOnline = true, uMeanMv = 2000f)
        val applied = DepthNodeCalibIoFun.apply(decoded, target)
        assertEquals(3, applied.row); assertEquals(0x698, applied.baseId)
        assertTrue(applied.isOnline); assertEquals(2000f, applied.uMeanMv!!, 0f)
        assertEquals(source.calibrationPoints, applied.calibrationPoints)
        assertTrue(DepthNodeCalibIoFun.decode(json.replace("0.025", "0.125")).isFailure)
        assertTrue(DepthNodeCalibIoFun.decode(json.replace("\"version\": 1", "\"version\": 2")).isFailure)
        assertTrue(abs(DepthNodeCalib.derivative(2000f, applied) - 0.025) < 1e-9)
    }

    @Test fun externalUnitsNormalizeAndKeepPerRowStateOnRestoreAndImport() {
        val at = "2026-11-09T00:00:00Z"
        val original = DepthNodeChannel(1, offsetTrimMm = 5f, zeroCheckedAt = at, stopLoMv = 500)
        val fromV = DepthNodeCalib.withLinearCoefficients(original, 20.0, -10.0, DepthNodeVoltageUnit.V, at)
        val fromMv = DepthNodeCalib.withLinearCoefficients(original, 0.02, -10.0, DepthNodeVoltageUnit.MV, at)
        assertEquals(40f, DepthNodeCalib.depthFromMv(2500f, fromV)!!, 0f)
        assertEquals(fromV.c1, fromMv.c1, 0.0)
        assertEquals(0f, fromV.offsetTrimMm, 0f)
        assertEquals("", fromV.zeroCheckedAt)
        assertEquals(500, fromV.stopLoMv)
        assertTrue(fromV.calibrationPoints.isEmpty())

        val stored = DepthNodeStorage.encode(fromV)
        assertEquals(fromV, DepthNodeStorage.decode(stored, 1))
        assertFalse(DepthNodeStorage.decode(stored, 0).fitValid)
        val json = DepthNodeCalibIoFun.encode(fromV, "3.1.0", at)
        val file = DepthNodeCalibIoFun.decode(json).getOrThrow()
        assertEquals(2, file.version)
        val target = DepthNodeChannel(4, isOnline = true, uFiltMv = 2500f)
        val restored = DepthNodeCalibIoFun.apply(file, target)
        assertEquals(target.baseId, restored.baseId)
        assertEquals(DepthNodeVoltageUnit.V, restored.linearVoltageUnit)
        assertTrue(restored.isOnline)
        assertEquals(40f, DepthNodeCalib.depthFromMv(restored.uFiltMv!!, restored)!!, 0f)
        assertTrue(DepthNodeCalibIoFun.decode(json.replace("\"version\": 2", "\"version\": 1")).isFailure)
        assertTrue(DepthNodeCalibIoFun.decode(json.replace("\"c2\": 0.0", "\"c2\": 0.1")).isFailure)
    }

    @Test fun externalCalibrationAcceptsNegativeSlopeAndRejectsNonfiniteZeroAndOverflow() {
        val channel = DepthNodeChannel(0)
        val calibrated = DepthNodeCalib.withLinearCoefficients(channel, -20.0, 90.0, DepthNodeVoltageUnit.V, "now")
        assertEquals(40f, DepthNodeCalib.depthFromMv(2500f, calibrated)!!, 0f)
        for ((a, b) in listOf(0.0 to 1.0, Double.NaN to 1.0, 1.0 to Double.POSITIVE_INFINITY, 1e39 to 0.0)) {
            assertTrue(runCatching { DepthNodeCalib.withLinearCoefficients(channel, a, b, DepthNodeVoltageUnit.MV, "now") }.isFailure)
        }
    }

    @Test fun externalCoefficientAcceptsChineseKeyboardSignsAndDigits() {
        assertEquals(-10.0, DepthNodeCalib.parseCoefficient(" －１０ ")!!, 0.0)
        assertEquals(-0.02, DepthNodeCalib.parseCoefficient("−０．０２")!!, 0.0)
        assertEquals(0.02, DepthNodeCalib.parseCoefficient("2e-2")!!, 0.0)
        assertNull(DepthNodeCalib.parseCoefficient(""))
    }
}
