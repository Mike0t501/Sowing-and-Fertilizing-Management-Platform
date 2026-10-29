package com.nx.vfremake

import com.nx.vfremake.data.CalibrationMode
import com.nx.vfremake.data.IndirectCalibPoint
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.data.buildLinearFit
import com.nx.vfremake.funClass.ServoCalibIoFun
import com.nx.vfremake.funClass.ServoCalibIoFun.applyTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServoCalibIoFunTest {

    private fun calibrated(): ServoCalibration {
        val pts = listOf(Pair(0, 20f), Pair(20480, 40f), Pair(40960, 60f), Pair(61440, 80f))
        val (a, b) = buildLinearFit(pts)!!
        return ServoCalibration(
            motorIndex = 2, nodeId = 13,
            limitMin = 0, limitMax = 81920, limitsSet = true,
            calibrationPoints = pts,
            fitA = a, fitB = b, fitValid = true,
            calibrationMode = CalibrationMode.INDIRECT,
            indirectPoints = listOf(
                IndirectCalibPoint(20f, 0), IndirectCalibPoint(40f, 20480),
                IndirectCalibPoint(60f, null), IndirectCalibPoint(80f, 61440),
                IndirectCalibPoint(100f, null)
            ),
            currentPosition = 999, isOnline = true
        )
    }

    private fun encodeOne(cal: ServoCalibration, label: String = "A03") =
        ServoCalibIoFun.encode(listOf(ServoCalibIoFun.toUnitDto(cal, label)), "2026-10-14 10:00:00", "3.1.0")

    @Test
    fun roundTripKeepsCalibrationAndTargetIdentity() {
        val src = calibrated()
        val file = ServoCalibIoFun.decode(encodeOne(src)).getOrThrow()
        val unit = file.units!!.single()
        assertEquals("A03", unit.unitLabel)
        assertEquals(2, unit.sourceMotorIndex)

        val target = ServoCalibration(motorIndex = 5, nodeId = 16, currentPosition = 123, isOnline = true)
        val out = unit.applyTo(target)
        assertEquals(5, out.motorIndex)
        assertEquals(16, out.nodeId)
        assertEquals(123, out.currentPosition)
        assertTrue(out.isOnline)
        assertEquals(src.limitMin, out.limitMin)
        assertEquals(src.limitMax, out.limitMax)
        assertEquals(src.limitsSet, out.limitsSet)
        assertEquals(src.calibrationPoints, out.calibrationPoints)
        assertEquals(src.fitA, out.fitA, 0f)
        assertEquals(src.fitB, out.fitB, 0f)
        assertEquals(src.fitValid, out.fitValid)
        assertEquals(src.calibrationMode, out.calibrationMode)
        assertEquals(src.indirectPoints, out.indirectPoints)
        assertNull(out.indirectPoints[2].encoderPos)
    }

    @Test
    fun runtimeFieldsAreNotExported() {
        val json = encodeOne(calibrated())
        assertTrue(!json.contains("currentPosition"))
        assertTrue(!json.contains("isOnline"))
    }

    private fun failMsg(json: String): String {
        val r = ServoCalibIoFun.decode(json)
        assertTrue("应当解析失败: $json", r.isFailure)
        return r.exceptionOrNull()!!.message!!
    }

    @Test
    fun rejectsBadFiles() {
        val good = encodeOne(calibrated())
        assertTrue(failMsg("not json {").isNotEmpty())
        assertTrue(failMsg(good.replace(ServoCalibIoFun.FORMAT, "other")).contains("format"))
        assertTrue(failMsg(good.replace("\"version\": 1", "\"version\": 9")).contains("v9"))
        assertTrue(failMsg(good.replace("\"INDIRECT\"", "\"FOO\"")).contains("FOO"))
        assertTrue(failMsg(good.replace("\"limitMax\": 81920", "\"limitMax\": 0")).contains("限位"))
        assertTrue(failMsg("""{"format":"${ServoCalibIoFun.FORMAT}","version":1,"units":[]}""").contains("没有"))
    }

    @Test
    fun rejectsTooManyPoints() {
        val pts = (0..5).map { Pair(it * 1000, 20f + it * 2) }
        val (a, b) = buildLinearFit(pts)!!
        val cal = calibrated().copy(calibrationPoints = pts, fitA = a, fitB = b)
        assertTrue(failMsg(encodeOne(cal)).contains("超过上限"))
    }

    @Test
    fun rejectsFitInconsistentWithPoints() {
        val cal = calibrated().copy(fitB = calibrated().fitB + 5f)
        assertTrue(failMsg(encodeOne(cal)).contains("不一致"))
    }

    @Test
    fun acceptsLimitsOnlyUnit() {
        val cal = ServoCalibration(motorIndex = 0, limitMin = -100, limitMax = 50000, limitsSet = true)
        assertTrue(ServoCalibIoFun.decode(encodeOne(cal, "")).isSuccess)
    }

    @Test
    fun sanitizeLabelStripsUnsafeChars() {
        assertEquals("A_03_x", ServoCalibIoFun.sanitizeLabel(" A/03 x "))
    }
}
