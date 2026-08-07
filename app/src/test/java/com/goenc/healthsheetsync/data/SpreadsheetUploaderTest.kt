package com.goenc.healthsheetsync.data

import com.goenc.healthsheetsync.health.DailyEnergySnapshot
import com.goenc.healthsheetsync.health.DebugStepDaily
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadsheetUploaderTest {
    @Test
    fun addsFinalizedEstimatedTotalKcalToDailySteps() {
        val targetDate = LocalDate.of(2026, 7, 23)
        val aggregationStartAt = targetDate.atStartOfDay()
        val aggregationEndAt = targetDate.plusDays(1).atStartOfDay()
        val table = uploadTable(
            stepDailyRecords = listOf(
                DebugStepDaily(
                    targetDate = targetDate,
                    steps = 8_000,
                    aggregationStartAt = aggregationStartAt,
                    aggregationEndAt = aggregationEndAt,
                ),
            ),
            dailyEnergySnapshots = listOf(
                DailyEnergySnapshot(
                    targetDate = targetDate,
                    steps = 8_000,
                    basalMetabolicRate = 1_371,
                    pal = 1.40224,
                    estimatedTotalKcal = 1_922,
                    finalizedAt = LocalDateTime.of(2026, 7, 24, 0, 1),
                ),
            ),
        )

        assertEquals(
            listOf(
                "targetDate",
                "steps",
                "distanceMeters",
                "estimatedTotalKcal",
                "aggregationStartAt",
                "aggregationEndAt",
            ),
            table.headers,
        )
        assertEquals(
            listOf(
                targetDate.toString(),
                8_000L,
                "",
                1_922,
                aggregationStartAt.toString(),
                aggregationEndAt.toString(),
            ),
            table.values.single(),
        )
    }

    @Test
    fun leavesEstimatedTotalKcalEmptyForUnfinalizedDay() {
        val targetDate = LocalDate.of(2026, 7, 24)
        val table = uploadTable(
            stepDailyRecords = listOf(
                DebugStepDaily(
                    targetDate = targetDate,
                    steps = 8_000,
                    aggregationStartAt = targetDate.atStartOfDay(),
                    aggregationEndAt = targetDate.plusDays(1).atStartOfDay(),
                ),
            ),
        )

        assertEquals("", table.values.single()[3])
    }

    private fun uploadTable(
        stepDailyRecords: List<DebugStepDaily>,
        dailyEnergySnapshots: List<DailyEnergySnapshot> = emptyList(),
    ): SpreadsheetUploadTable {
        return SpreadsheetUploader()
            .uploadTables(
                weightRecords = emptyList(),
                glucoseRecords = emptyList(),
                stepDailyRecords = stepDailyRecords,
                a1cDailyRecords = emptyList(),
                manualRecords = emptyList(),
                dailyBodySettings = emptyList(),
                dailyEnergySnapshots = dailyEnergySnapshots,
            )
            .single { it.sheetName == "stepDailyRecords" }
    }
}
