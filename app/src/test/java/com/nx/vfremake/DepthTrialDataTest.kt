package com.nx.vfremake

import com.nx.vfremake.data.DepthTestConfig
import com.nx.vfremake.data.DepthTestPhase
import com.nx.vfremake.data.DepthTrialFix
import com.nx.vfremake.funClass.DepthRecordSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthTrialDataTest {
    @Test fun sequenceIncludesShortTerminalStepAndReturnsToStart() {
        assertEquals(
            listOf(20f, 40f, 60f, 75f, 60f, 40f, 20f),
            DepthTestConfig.stageDepths(20f, 75f, 20f)
        )
        assertEquals(listOf(20f, 25f, 20f), DepthTestConfig.stageDepths(20f, 25f, 10f))
    }

    @Test fun invalidOrUnboundedSequenceIsRejected() {
        assertTrue(DepthTestConfig.stageDepths(Float.NaN, 80f, 20f).isEmpty())
        assertTrue(DepthTestConfig.stageDepths(20f, 80f, 0f).isEmpty())
        assertTrue(DepthTestConfig.stageDepths(80f, 20f, 20f).isEmpty())
        assertTrue(DepthTestConfig.stageDepths(0f, 1000f, 1f).isEmpty())
    }

    @Test fun trialColumnsKeepExistingSchemaAndLeaveExpiredCoordinatesBlank() {
        assertEquals(DepthRecordSchema.HEADER, DepthRecordSchema.TRIAL_HEADER.take(21))
        val fix = DepthTrialFix(10_000L, 115.1, 36.1, 115.2, 36.2, true)
        val valid = DepthRecordSchema.trialColumns(2, DepthTestPhase.DWELL, fix, 10_400L, simulated = true)
        assertEquals(listOf("2", "DWELL", "ROW_FIX", "400", "115.10000000", "36.10000000",
            "115.20000000", "36.20000000", "1"), valid)

        val antennaOnly = DepthRecordSchema.trialColumns(2, DepthTestPhase.MOVING,
            fix.copy(rowLongitude = null, rowLatitude = null), 10_400L, simulated = true)
        assertEquals("ANTENNA_ONLY", antennaOnly[2])
        assertEquals(listOf("", ""), antennaOnly.subList(6, 8))

        val expired = DepthRecordSchema.trialColumns(3, DepthTestPhase.MOVING, fix, 12_501L, simulated = true)
        assertEquals(listOf("3", "MOVING", "NO_FIX", "", "", "", "", "", "1"), expired)
    }
}
