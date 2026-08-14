package com.goenc.healthsheetsync.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FfmiCalculatorTest {
    @Test
    fun calculatesFatFreeMassIndexFromWeightHeightAndBodyFat() {
        assertEquals(
            13.32,
            requireNotNull(
                ffmi(
                    weightKg = 70.0,
                    heightCm = 69.0 * 2.54,
                    bodyFatPercent = 41.57,
                ),
            ),
            0.01,
        )
    }

    @Test
    fun returnsNullWhenInputsCannotProducePositiveFatFreeMass() {
        assertNull(
            ffmi(
                weightKg = 70.0,
                heightCm = 175.0,
                bodyFatPercent = 100.0,
            ),
        )
    }
}
