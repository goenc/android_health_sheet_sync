package com.goenc.healthsheetsync.data

import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.DailyBodySetting
import com.goenc.healthsheetsync.health.DailyEnergySnapshot
import com.goenc.healthsheetsync.health.DailyEnergyCalculator
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType

internal object SpreadsheetUploadTableBuilder {
    fun build(
        weightRecords: List<DebugWeightRecord>,
        glucoseRecords: List<DebugGlucoseRecord>,
        stepDailyRecords: List<DebugStepDaily>,
        a1cDailyRecords: List<DebugA1cDaily>,
        manualRecords: List<ManualHealthRecord>,
        dailyBodySettings: List<DailyBodySetting>,
        dailyEnergySnapshots: List<DailyEnergySnapshot>,
    ): List<SpreadsheetUploadTable> {
        val dailyEnergyByDate = dailyEnergySnapshots.map {
            DailyEnergyCalculator.applyBasalMetabolicRateHistory(it, manualRecords)
        }.associateBy { it.targetDate }
        return listOf(
            SpreadsheetUploadTable(
                sheetName = "dailyBodySettings",
                headers = listOf("targetDate", "heightCm", "averageIntakeKcal", "updatedAt"),
                values = dailyBodySettings
                    .filter { it.targetDate != java.time.LocalDate.MIN }
                    .sortedBy { it.targetDate }
                    .map { setting ->
                        listOf(
                            setting.targetDate.toString(),
                            setting.heightCm,
                            setting.averageIntakeKcal,
                            setting.updatedAt.toString(),
                        )
                    },
            ),
            SpreadsheetUploadTable(
                sheetName = "weightRecords",
                headers = listOf(
                    "measuredAt",
                    "targetDate",
                    "timeBand",
                    "weightKg",
                    "healthConnectId",
                    "sourceAppName",
                    "sourcePackageName",
                ),
                values = weightRecords.map { record ->
                    listOf(
                        record.measuredAt.toString(),
                        record.targetDate.toString(),
                        record.timeBand,
                        record.weightKg,
                        record.healthConnectId,
                        record.sourceAppName,
                        record.sourcePackageName,
                    )
                },
            ),
            SpreadsheetUploadTable(
                sheetName = "glucoseRecords",
                headers = listOf(
                    "measuredAt",
                    "targetDate",
                    "timeBand",
                    "bloodGlucoseMgDl",
                    "mealRelation",
                    "healthConnectId",
                    "sourceAppName",
                    "sourcePackageName",
                ),
                values = glucoseRecords.map { record ->
                    listOf(
                        record.measuredAt.toString(),
                        record.targetDate.toString(),
                        record.timeBand,
                        record.bloodGlucoseMgDl,
                        record.mealRelation,
                        record.healthConnectId,
                        record.sourceAppName,
                        record.sourcePackageName,
                    )
                },
            ),
            SpreadsheetUploadTable(
                sheetName = "stepDailyRecords",
                headers = listOf(
                    "targetDate",
                    "steps",
                    "distanceMeters",
                    "estimatedTotalKcal",
                    "aggregationStartAt",
                    "aggregationEndAt",
                ),
                values = stepDailyRecords.map { record ->
                    listOf(
                        record.targetDate.toString(),
                        record.steps,
                        record.distanceMeters ?: "",
                        dailyEnergyByDate[record.targetDate]?.estimatedTotalKcal ?: "",
                        record.aggregationStartAt.toString(),
                        record.aggregationEndAt.toString(),
                    )
                },
            ),
            SpreadsheetUploadTable(
                sheetName = "a1cDailyRecords",
                headers = listOf(
                    "targetDate",
                    "measuredAt",
                    "a1cPercent",
                    "manualId",
                ),
                values = a1cDailyRecords.map { record ->
                    listOf(
                        record.targetDate.toString(),
                        record.measuredAt.toString(),
                        record.a1cPercent,
                        record.manualId,
                    )
                },
            ),
            SpreadsheetUploadTable(
                sheetName = "bloodPressureRecords",
                headers = listOf("measuredAt", "valueText", "manualId"),
                values = manualRecords
                    .filter { it.type == ManualRecordType.BloodPressure && it.invalidatedAt == null }
                    .map { record ->
                        listOf(record.measuredAt.toString(), record.valueText, record.id)
                    },
            ),
            SpreadsheetUploadTable(
                sheetName = "waistRecords",
                headers = listOf("measuredAt", "valueText", "manualId"),
                values = manualRecords
                    .filter { it.type == ManualRecordType.Waist && it.invalidatedAt == null }
                    .map { record ->
                        listOf(record.measuredAt.toString(), record.valueText, record.id)
                    },
            ),
            SpreadsheetUploadTable(
                sheetName = "neckRecords",
                headers = listOf("measuredAt", "valueText", "manualId"),
                values = manualRecords
                    .filter { it.type == ManualRecordType.Neck && it.invalidatedAt == null }
                    .map { record ->
                        listOf(record.measuredAt.toString(), record.valueText, record.id)
                    },
            ),
            SpreadsheetUploadTable(
                sheetName = "bodySettingRecords",
                headers = listOf("measuredAt", "type", "value", "manualId"),
                values = manualRecords.filter {
                    it.invalidatedAt == null && (it.type == ManualRecordType.Height || it.type == ManualRecordType.AverageIntake)
                }.map { record -> listOf(record.measuredAt.toString(), record.type.name,
                    record.valueText.removeSuffix(" cm").removeSuffix(" kcal/日").trim().toDoubleOrNull() ?: "", record.id) },
            ),
            SpreadsheetUploadTable(
                sheetName = "basalMetabolicRateRecords",
                headers = listOf("measuredAt", "basalMetabolicRate", "manualId"),
                values = manualRecords.filter { it.type == ManualRecordType.BasalMetabolicRate && it.invalidatedAt == null }
                    .map { record -> listOf(record.measuredAt.toString(),
                        record.valueText.removeSuffix(" kcal/日").trim().toIntOrNull() ?: "", record.id) },
            ),
        )
    }

}
