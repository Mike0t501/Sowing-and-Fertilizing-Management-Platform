/** @author NIANXI @brief 显示回差不能污染测量和记录原值。 */
package com.nx.vfremake

import com.nx.vfremake.data.*
import com.nx.vfremake.funClass.*
import org.junit.Assert.*
import org.junit.Test

class DepthDisplayFormatTest {
    @Test fun halfCentimeterBoundaryDoesNotFlickerAndUsesOneMillimeterHysteresis() {
        val f = DepthDisplayFormat()
        assertEquals("2.5 cm", f.format(25f, DepthDisplayMode.HALF_CM))
        for (value in listOf(27.4f, 27.6f, 28.4f, 27.5f)) assertEquals("2.5 cm", f.format(value, DepthDisplayMode.HALF_CM))
        assertEquals("3.0 cm", f.format(28.5f, DepthDisplayMode.HALF_CM))
        for (value in listOf(27.4f, 27.6f, 26.6f)) assertEquals("3.0 cm", f.format(value, DepthDisplayMode.HALF_CM))
        assertEquals("2.5 cm", f.format(26.5f, DepthDisplayMode.HALF_CM))
        assertEquals("8.0 cm", f.format(80.1f, DepthDisplayMode.HALF_CM))
    }

    @Test fun zeroInvalidAndModeSwitchAreIndependentFromMeasurement() {
        val f = DepthDisplayFormat()
        assertEquals("0.0 cm", f.format(0f, DepthDisplayMode.HALF_CM))
        assertEquals("--", f.format(null, DepthDisplayMode.HALF_CM))
        assertEquals("28 mm", f.format(27.6f, DepthDisplayMode.ONE_MM))
        assertEquals("3.0 cm", f.format(27.6f, DepthDisplayMode.HALF_CM))
        assertEquals("--", f.format(Float.NaN, DepthDisplayMode.HALF_CM))
    }

    @Test fun facadeRechecksFreshnessAndCannotFallbackToServo() {
        var clock = 1000L
        val store = DepthNodeStateStore()
        store.updateChannel(0) { it.copy(hasStatus = true, lastHeardMs = clock,
            uFiltMv = 2500f, uMeanMv = 2501f, spanMv = 2f,
            fitValid = true, c0 = -12.5, c1 = 0.025) }
        val facade = MeasuredDepthFacade({ store.current() }, { clock })
        val sample = facade.forRow(0)
        assertEquals(50f, sample.depthMm!!, 0.001f)
        DepthDisplayFormat().format(sample.depthMm, DepthDisplayMode.HALF_CM)
        assertEquals(sample, facade.forRow(0))
        clock += 1001
        assertNull(facade.forRow(0).depthMm)
        assertEquals(DepthNodeQuality.OFFLINE, facade.forRow(0).quality)
        assertNull(facade.forRow(99).depthMm)
    }
}
