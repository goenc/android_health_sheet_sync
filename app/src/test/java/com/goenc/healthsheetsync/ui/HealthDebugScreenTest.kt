package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.health.DebugStepDaily
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthDebugScreenTest {
    @Test
    fun stepsForSummary_returnsTodaysStepsWhenRecordExists() {
        val today = LocalDate.of(2026, 8, 3)

        assertEquals(1_234L, stepsForSummary(listOf(stepDaily(today, 1_234L)), today))
    }

    @Test
    fun stepsForSummary_returnsZeroWhenOnlyPreviousDayRecordExists() {
        val today = LocalDate.of(2026, 8, 3)

        assertEquals(0L, stepsForSummary(listOf(stepDaily(today.minusDays(1), 10_000L)), today))
    }

    @Test
    fun stepsForSummary_returnsZeroWhenRecordsAreEmpty() {
        assertEquals(0L, stepsForSummary(emptyList(), LocalDate.of(2026, 8, 3)))
    }

    @Test
    fun stepsForSummary_returnsZeroWhenOnlyFutureDayRecordExists() {
        val today = LocalDate.of(2026, 8, 3)

        assertEquals(0L, stepsForSummary(listOf(stepDaily(today.plusDays(1), 2_000L)), today))
    }

    @Test
    fun stepsForSummary_prefersTodaysRecordWhenPreviousDayAlsoExists() {
        val today = LocalDate.of(2026, 8, 3)

        assertEquals(
            1_234L,
            stepsForSummary(
                listOf(
                    stepDaily(today.minusDays(1), 10_000L),
                    stepDaily(today, 1_234L),
                ),
                today,
            ),
        )
    }

    @Test
    fun stepsForSummary_doesNotDependOnRecordOrder() {
        val today = LocalDate.of(2026, 8, 3)

        assertEquals(
            1_234L,
            stepsForSummary(
                listOf(
                    stepDaily(today, 1_234L),
                    stepDaily(today.minusDays(1), 10_000L),
                ).reversed(),
                today,
            ),
        )
    }

    @Test
    fun stepsForSummary_returnsExplicitZeroForTodaysRecord() {
        val today = LocalDate.of(2026, 8, 3)

        assertEquals(0L, stepsForSummary(listOf(stepDaily(today, 0L)), today))
    }

    private fun stepDaily(targetDate: LocalDate, steps: Long): DebugStepDaily {
        return DebugStepDaily(
            targetDate = targetDate,
            steps = steps,
            aggregationStartAt = LocalDateTime.of(targetDate, java.time.LocalTime.MIDNIGHT),
            aggregationEndAt = LocalDateTime.of(targetDate.plusDays(1), java.time.LocalTime.MIDNIGHT),
        )
    }
}
