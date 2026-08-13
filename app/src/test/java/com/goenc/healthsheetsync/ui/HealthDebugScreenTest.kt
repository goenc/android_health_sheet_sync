package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.DailyBodySetting
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun bmiForSummary_usesLatestMorningWeightOnly() {
        val date = LocalDate.of(2026, 8, 3)

        assertEquals(
            22.2222,
            requireNotNull(bmiForSummary(
                listOf(
                    weightRecord(date, 90.0, timeBand = "夜"),
                    weightRecord(date.minusDays(1), 70.0),
                    weightRecord(date, 72.0, hour = 8),
                ),
                listOf(bodySetting(date, 180.0)),
            )),
            0.0001,
        )
    }

    @Test
    fun bmiForSummary_returnsNullWhenMorningWeightDoesNotExist() {
        val date = LocalDate.of(2026, 8, 3)

        assertNull(
            bmiForSummary(
                listOf(weightRecord(date, 90.0, timeBand = "夜")),
                listOf(bodySetting(date, 180.0)),
            ),
        )
    }

    @Test
    fun bmiForSummary_returnsNullWhenHeightDoesNotExist() {
        val date = LocalDate.of(2026, 8, 3)

        assertNull(bmiForSummary(listOf(weightRecord(date, 72.0)), emptyList()))
    }

    @Test
    fun latestChartTargetDate_returns_date_of_latest_weight_record() {
        val latestDate = LocalDate.of(2026, 8, 3)

        assertEquals(
            latestDate,
            latestChartTargetDate(
                listOf(
                    weightRecord(latestDate.minusDays(1), 60.0),
                    weightRecord(latestDate, 59.5),
                ).reversed(),
            ),
        )
    }

    @Test
    fun navyBodyFatForSummary_usesLatestWaistNeckAndHeight() {
        val date = LocalDate.of(2026, 8, 3)

        assertEquals(
            41.57,
            requireNotNull(navyBodyFatForSummary(
                manualRecords = listOf(
                    manualRecord(ManualRecordType.Waist, date, 49.0 * 2.54),
                    manualRecord(ManualRecordType.Neck, date, 16.0 * 2.54),
                ),
                dailyBodySettings = listOf(bodySetting(date, 69.0 * 2.54)),
            )),
            0.01,
        )
    }

    @Test
    fun navyBodyFatForSummary_returnsNullWhenNeckIsMissing() {
        val date = LocalDate.of(2026, 8, 3)

        assertNull(
            navyBodyFatForSummary(
                manualRecords = listOf(manualRecord(ManualRecordType.Waist, date, 90.0)),
                dailyBodySettings = listOf(bodySetting(date, 170.0)),
            ),
        )
    }

    private fun stepDaily(targetDate: LocalDate, steps: Long): DebugStepDaily {
        return DebugStepDaily(
            targetDate = targetDate,
            steps = steps,
            aggregationStartAt = LocalDateTime.of(targetDate, java.time.LocalTime.MIDNIGHT),
            aggregationEndAt = LocalDateTime.of(targetDate.plusDays(1), java.time.LocalTime.MIDNIGHT),
        )
    }

    private fun weightRecord(
        targetDate: LocalDate,
        weightKg: Double,
        timeBand: String = "朝",
        hour: Int = if (timeBand == "朝") 7 else 20,
    ): DebugWeightRecord {
        return DebugWeightRecord(
            measuredAt = targetDate.atTime(hour, 0),
            targetDate = targetDate,
            timeBand = timeBand,
            weightKg = weightKg,
            healthConnectId = "weight-$targetDate",
            sourceAppName = "test",
            sourcePackageName = "test",
        )
    }

    private fun bodySetting(targetDate: LocalDate, heightCm: Double): DailyBodySetting {
        return DailyBodySetting(
            targetDate = targetDate,
            heightCm = heightCm,
            averageIntakeKcal = 2_000,
            updatedAt = targetDate.atTime(12, 0),
        )
    }

    private fun manualRecord(
        type: ManualRecordType,
        targetDate: LocalDate,
        valueCm: Double,
    ): ManualHealthRecord {
        return ManualHealthRecord(
            id = "$type-$targetDate",
            type = type,
            measuredAt = targetDate.atTime(12, 0),
            valueText = "$valueCm cm",
            invalidatedAt = null,
        )
    }
}
