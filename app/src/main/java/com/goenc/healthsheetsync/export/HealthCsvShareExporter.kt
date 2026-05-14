package com.goenc.healthsheetsync.export

import android.content.Context
import com.goenc.healthsheetsync.health.HealthDebugUiState
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
                    listOf(
                        "weight",
                        record.targetDate.toString(),
                        record.measuredAt.toString(),
                        record.timeBand,
                        record.weightKg.toString(),
                        "",
                        "",
                        record.sourceAppName,
                        record.sourcePackageName,
                        record.healthConnectId,
                        "",
                        "",
                    ).toCsvLine(),
                )
            }
            state.glucoseRecords.forEach { record ->
                writer.appendLine(
                    listOf(
                        "glucose",
                        record.targetDate.toString(),
                        record.measuredAt.toString(),
                        record.timeBand,
                        record.bloodGlucoseMgDl.toString(),
                        record.mealRelation,
                        "",
                        record.sourceAppName,
                        record.sourcePackageName,
                        record.healthConnectId,
                        "",
                        "",
                    ).toCsvLine(),
                )
            }
            state.stepDailyRecords.forEach { record ->
                writer.appendLine(
                    listOf(
                        "steps",
                        record.targetDate.toString(),
                        "",
                        "",
                        record.steps.toString(),
                        record.aggregationStartAt.toString(),
                        record.aggregationEndAt.toString(),
                        "",
                        "",
                        "",
                        "",
                        "",
                    ).toCsvLine(),
                )
            }
            state.a1cDailyRecords.forEach { record ->
                writer.appendLine(
                    listOf(
                        "a1c",
                        record.targetDate.toString(),
                        record.measuredAt.toString(),
                        "",
                        record.a1cPercent.toString(),
                        "",
                        "",
                        "",
                        "",
                        "",
                        record.manualId,
                        "",
                    ).toCsvLine(),
                )
            }
        }
        return file
    }

    private fun List<String>.toCsvLine(): String =
        joinToString(",") { it.toCsvCell() }

    private fun String.toCsvCell(): String {
        if (none { it == ',' || it == '\n' || it == '\r' || it == '"' }) {
            return this
        }
        return "\"${replace("\"", "\"\"")}\""
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
