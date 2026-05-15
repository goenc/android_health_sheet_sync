package com.goenc.healthsheetsync.export

import android.content.Context
import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class HealthCsvShareExporter(
    private val context: Context,
) {
    fun export(state: HealthDebugUiState): File {
        val exportDir = File(context.cacheDir, CSV_EXPORT_DIR).apply {
            mkdirs()
        }
        val fileName = "health_sheet_sync_${LocalDateTime.now().format(FILE_TIMESTAMP_FORMAT)}.csv"
        val file = File(exportDir, fileName)
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine(CSV_HEADER.joinToString(",") { it.toCsvCell() })
            state.weightRecords.forEach { record ->
                writer.appendLine(
                    csvRow(
                        recordType = "weight",
                        targetDate = record.targetDate.toString(),
                        measuredAt = record.measuredAt.toString(),
                        timeBand = record.timeBand,
                        value1 = record.weightKg.toString(),
                        sourceAppName = record.sourceAppName,
                        sourcePackageName = record.sourcePackageName,
                        healthConnectId = record.healthConnectId,
                    ),
                )
            }
            state.glucoseRecords.forEach { record ->
                writer.appendLine(
                    csvRow(
                        recordType = "glucose",
                        targetDate = record.targetDate.toString(),
                        measuredAt = record.measuredAt.toString(),
                        timeBand = record.timeBand,
                        value1 = record.bloodGlucoseMgDl.toString(),
                        value2 = record.mealRelation,
                        sourceAppName = record.sourceAppName,
                        sourcePackageName = record.sourcePackageName,
                        healthConnectId = record.healthConnectId,
                    ),
                )
            }
            state.stepDailyRecords.forEach { record ->
                writer.appendLine(
                    csvRow(
                        recordType = "steps",
                        targetDate = record.targetDate.toString(),
                        value1 = record.steps.toString(),
                        value2 = record.aggregationStartAt.toString(),
                        value3 = record.aggregationEndAt.toString(),
                    ),
                )
            }
            state.manualRecords
                .filter { it.invalidatedAt == null }
                .forEach { record ->
                    exportManualRecord(record)?.let(writer::appendLine)
                }
            state.a1cDailyRecords.forEach { record ->
                writer.appendLine(
                    csvRow(
                        recordType = "a1c",
                        targetDate = record.targetDate.toString(),
                        measuredAt = record.measuredAt.toString(),
                        value1 = record.a1cPercent.toString(),
                        manualId = record.manualId,
                    ),
                )
            }
        }
        return file
    }

    private fun exportManualRecord(record: ManualHealthRecord): String? {
        return when (record.type) {
            ManualRecordType.BloodPressure -> {
                val values = record.valueText.removeSuffix(" mmHg").split("/")
                if (values.size != 2) return null
                csvRow(
                    recordType = "bloodPressure",
                    targetDate = record.measuredAt.toLocalDate().toString(),
                    measuredAt = record.measuredAt.toString(),
                    timeBand = record.measuredAt.toTimeBand(),
                    value1 = values[0].trim(),
                    value2 = values[1].trim(),
                    manualId = record.id,
                )
            }
            ManualRecordType.Waist -> {
                val waist = record.valueText.removeSuffix(" cm").trim()
                csvRow(
                    recordType = "waist",
                    targetDate = record.measuredAt.toLocalDate().toString(),
                    measuredAt = record.measuredAt.toString(),
                    value1 = waist,
                    manualId = record.id,
                )
            }
            else -> null
        }
    }

    private fun csvRow(
        recordType: String,
        targetDate: String,
        measuredAt: String = "",
        timeBand: String = "",
        value1: String = "",
        value2: String = "",
        value3: String = "",
        sourceAppName: String = "",
        sourcePackageName: String = "",
        healthConnectId: String = "",
        manualId: String = "",
        memo: String = "",
    ): String = listOf(
        recordType,
        targetDate,
        measuredAt,
        timeBand,
        value1,
        value2,
        value3,
        sourceAppName,
        sourcePackageName,
        healthConnectId,
        manualId,
        memo,
    ).toCsvLine()

    private fun List<String>.toCsvLine(): String =
        joinToString(",") { it.toCsvCell() }

    private fun String.toCsvCell(): String {
        if (none { it == ',' || it == '\n' || it == '\r' || it == '"' }) {
            return this
        }
        return "\"${replace("\"", "\"\"")}\""
    }

    private fun LocalDateTime.toTimeBand(): String {
        return when (hour) {
            in 4..11 -> "朝"
            in 12..17 -> "昼"
            else -> "夜"
        }
    }

    companion object {
        const val MIME_TYPE = "text/csv"
        const val CSV_EXPORT_DIR = "shared_exports"
        private val FILE_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        private val CSV_HEADER = listOf(
            "recordType",
            "targetDate",
            "measuredAt",
            "timeBand",
            "value1",
            "value2",
            "value3",
            "sourceAppName",
            "sourcePackageName",
            "healthConnectId",
            "manualId",
            "memo",
        )
    }
}
