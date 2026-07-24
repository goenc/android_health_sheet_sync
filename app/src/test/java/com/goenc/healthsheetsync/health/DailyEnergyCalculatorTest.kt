package com.goenc.healthsheetsync.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DailyEnergyCalculatorTest {
    @Test
    fun calculatesRequiredExamplesWithUnroundedPal() {
        val expected = mapOf(
            3_000L to (1.29959 to 1_782),
            3_500L to (1.309855 to 1_796),
            8_000L to (1.40224 to 1_922),
            10_000L to (1.4433 to 1_979),
            25_000L to (1.75125 to 2_401),
        )

        expected.forEach { (steps, result) ->
            val calculation = DailyEnergyCalculator.calculate(steps, 1_371)
            assertEquals(result.first, calculation.pal, 0.000000001)
            assertEquals(result.second, calculation.estimatedTotalKcal)
        }
    }

    @Test
    fun calculatesZeroSteps() {
        val calculation = DailyEnergyCalculator.calculate(0, 1_371)

        assertEquals(1.238, calculation.pal, 0.000000001)
        assertEquals(1_697, calculation.estimatedTotalKcal)
    }

    @Test
    fun recalculatesWhenBasalMetabolicRateChanges() {
        val calculation = DailyEnergyCalculator.calculate(8_000, 1_500)

        assertEquals(1.40224, calculation.pal, 0.000000001)
        assertEquals(2_103, calculation.estimatedTotalKcal)
    }

    @Test
    fun rejectsNegativeSteps() {
        assertThrows(IllegalArgumentException::class.java) {
            DailyEnergyCalculator.calculate(-1, 1_371)
        }
    }

    @Test
    fun rejectsNonPositiveBasalMetabolicRate() {
        assertThrows(IllegalArgumentException::class.java) {
            DailyEnergyCalculator.calculate(8_000, 0)
        }
    }
}
