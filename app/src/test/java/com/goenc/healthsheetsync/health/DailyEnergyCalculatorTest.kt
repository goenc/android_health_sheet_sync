package com.goenc.healthsheetsync.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

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

    @Test
    fun resolvesPastDayFromSavedSnapshot() {
        val result = DailyEnergyCalculator.resolveDisplay(
            targetDate = LocalDate.of(2026, 7, 23),
            today = LocalDate.of(2026, 7, 24),
            currentSteps = 10_000,
            currentBasalMetabolicRate = 1_500,
            snapshot = DailyEnergySnapshot(
                targetDate = LocalDate.of(2026, 7, 23),
                steps = 8_000,
                basalMetabolicRate = 1_371,
                pal = 1.40224,
                estimatedTotalKcal = 1_922,
                finalizedAt = LocalDateTime.of(2026, 7, 24, 0, 1),
            ),
        )

        assertEquals(8_000L, result?.steps)
        assertEquals(1_371, result?.basalMetabolicRate)
        assertEquals(1.40224, result?.pal ?: 0.0, 0.000000001)
        assertEquals(1_922, result?.estimatedTotalKcal)
    }

    @Test
    fun resolvesCurrentDayDynamically() {
        val result = DailyEnergyCalculator.resolveDisplay(
            targetDate = LocalDate.of(2026, 7, 24),
            today = LocalDate.of(2026, 7, 24),
            currentSteps = 8_000,
            currentBasalMetabolicRate = 1_500,
            snapshot = null,
        )

        assertEquals(1_500, result?.basalMetabolicRate)
        assertEquals(2_103, result?.estimatedTotalKcal)
    }

    @Test
    fun returnsNullForUnfinalizedPastDay() {
        val result = DailyEnergyCalculator.resolveDisplay(
            targetDate = LocalDate.of(2026, 7, 23),
            today = LocalDate.of(2026, 7, 24),
            currentSteps = 8_000,
            currentBasalMetabolicRate = 1_500,
            snapshot = null,
        )

        assertEquals(null, result)
    }

    @Test
    fun returnsNullForFutureDay() {
        val result = DailyEnergyCalculator.resolveDisplay(
            targetDate = LocalDate.of(2026, 7, 25),
            today = LocalDate.of(2026, 7, 24),
            currentSteps = 8_000,
            currentBasalMetabolicRate = 1_500,
            snapshot = null,
        )

        assertEquals(null, result)
    }
}
