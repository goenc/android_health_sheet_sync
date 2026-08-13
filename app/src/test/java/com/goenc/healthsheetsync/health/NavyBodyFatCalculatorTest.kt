package com.goenc.healthsheetsync.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavyBodyFatCalculatorTest {
    @Test
    fun appliesThreePointAdjustmentToNavyMaleEquation() {
        val result = requireNotNull(
            navyMaleBodyFatPercent(
                heightCm = 69.0 * 2.54,
                waistCm = 49.0 * 2.54,
                neckCm = 16.0 * 2.54,
            ),
        )

        assertEquals(41.57, result, 0.01)
    }

    @Test
    fun returnsNullWhenCircumferenceValueCannotBeCalculated() {
        assertNull(
            navyMaleBodyFatPercent(
                heightCm = 170.0,
                waistCm = 38.0,
                neckCm = 40.0,
            ),
        )
    }
}
