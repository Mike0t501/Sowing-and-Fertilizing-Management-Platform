package com.nx.vfremake

import com.nx.vfremake.data.DepthPrescriptionSource
import com.nx.vfremake.data.DepthPrescriptionTargets
import com.nx.vfremake.data.ServoCalibration
import com.nx.vfremake.data.SowingDepthState
import com.nx.vfremake.data.depthControlReadinessWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SowingDepthTargetTest {
    private val fixed = SowingDepthState(
        motors = listOf(
            ServoCalibration(motorIndex = 0, targetDepth = 20f, isOnline = true, limitsSet = true, fitValid = true),
            ServoCalibration(motorIndex = 1, targetDepth = 25f)
        ),
        globalTargetDepth = 50f,
        configuredRowTargetsMm = mapOf(1 to 60f)
    )

    @Test
    fun fixedWorkUsesSettingsWithoutTakingDiagnosticTargets() {
        assertEquals(50f, fixed.depthTargetMm(0, usePrescription = true))
        assertEquals(60f, fixed.depthTargetMm(1, usePrescription = true))
        assertEquals(20f, fixed.depthTargetMm(0, usePrescription = false))
        assertNull(fixed.depthControlReadinessWarning(rowNumber = 1))
    }

    @Test
    fun prescriptionOverridesFixedDepthOnlyWhereAValidTargetExists() {
        val mapped = fixed.copy(depthPrescription = DepthPrescriptionTargets(
            DepthPrescriptionSource.PRESCRIPTION, listOf(35f, null)
        ))
        assertEquals(35f, mapped.depthTargetMm(0, usePrescription = true))
        assertNull(mapped.depthTargetMm(1, usePrescription = true))

        val awaitingFix = mapped.copy(depthPrescription = mapped.depthPrescription.copy(depthsMm = emptyList()))
        assertNull(awaitingFix.depthTargetMm(0, usePrescription = true))
        assertEquals(25f, awaitingFix.depthTargetMm(1, usePrescription = false))
        assertEquals(60f, mapped.copy(depthPrescription = DepthPrescriptionTargets()).depthTargetMm(1, usePrescription = true))
    }

    @Test
    fun invalidPrescriptionCannotSupplyAWorkTarget() {
        val malformed = fixed.copy(depthPrescription = DepthPrescriptionTargets(
            DepthPrescriptionSource.PRESCRIPTION, listOf(Float.POSITIVE_INFINITY, 0f)
        ))
        assertNull(malformed.depthTargetMm(0, usePrescription = true))
        assertNull(malformed.depthTargetMm(1, usePrescription = true))
        assertNull(fixed.copy(depthPrescription = DepthPrescriptionTargets(DepthPrescriptionSource.MISSING_FIELD))
            .depthTargetMm(0, usePrescription = true))
    }
}
