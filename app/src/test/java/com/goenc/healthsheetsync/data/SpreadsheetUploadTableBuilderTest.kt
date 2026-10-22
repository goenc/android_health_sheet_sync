package com.goenc.healthsheetsync.data

import com.goenc.healthsheetsync.health.DailyEnergySnapshot
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadsheetUploadTableBuilderTest {
    @Test fun exportsIndependentBodyHistoryWithoutVirtualBaselineDate() {
        val date = LocalDate.of(2026, 10, 20)
        val height = ManualHealthRecord("height", ManualRecordType.Height, date.atStartOfDay(), "170 cm", null)
        val intake = ManualHealthRecord("intake", ManualRecordType.AverageIntake, date.atStartOfDay(), "1800 kcal/日", null)
        val records = listOf(height, intake)
        val tables = SpreadsheetUploadTableBuilder.build(
            weightRecords = emptyList(), glucoseRecords = emptyList(), stepDailyRecords = emptyList(),
            a1cDailyRecords = emptyList(), manualRecords = records,
            dailyBodySettings = com.goenc.healthsheetsync.health.BodySettingHistory.timeline(emptyList(), records),
            dailyEnergySnapshots = emptyList(),
        )
        assertEquals(1, tables.single { it.sheetName == "dailyBodySettings" }.values.size)
        assertEquals(listOf(date.toString(), 170.0, 1800, date.atStartOfDay().toString()),
            tables.single { it.sheetName == "dailyBodySettings" }.values.single())
        assertEquals(listOf(
            listOf(date.atStartOfDay().toString(), "Height", 170.0, "height"),
            listOf(date.atStartOfDay().toString(), "AverageIntake", 1800.0, "intake")),
            tables.single { it.sheetName == "bodySettingRecords" }.values)
    }

    @Test
    fun exportsBasalMetabolicRateHistoryAndRecalculatedEnergyWithoutCreatingSteps() {
        val date = LocalDate.of(2026, 7, 23)
        val record = ManualHealthRecord("bmr", ManualRecordType.BasalMetabolicRate,
            date.atStartOfDay(), "1500 kcal/日", null)
        val tables = SpreadsheetUploadTableBuilder.build(
            weightRecords = emptyList(), glucoseRecords = emptyList(),
            stepDailyRecords = listOf(DebugStepDaily(date, 8000, aggregationStartAt = date.atStartOfDay(),
                aggregationEndAt = date.plusDays(1).atStartOfDay())),
            a1cDailyRecords = emptyList(), manualRecords = listOf(record), dailyBodySettings = emptyList(),
            dailyEnergySnapshots = listOf(DailyEnergySnapshot(date, 8000, 1371, 1.40224, 1922, date.plusDays(1).atStartOfDay())),
        )
        assertEquals(2103, tables.single { it.sheetName == "stepDailyRecords" }.values.single()[3])
        assertEquals(listOf(date.atStartOfDay().toString(), 1500, "bmr"),
            tables.single { it.sheetName == "basalMetabolicRateRecords" }.values.single())
    }

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
        return SpreadsheetUploadTableBuilder.build(
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
