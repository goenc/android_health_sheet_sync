package com.goenc.healthsheetsync.data

import com.goenc.healthsheetsync.health.HealthDebugUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant

class SpreadsheetUploader {
    suspend fun upload(
        state: HealthDebugUiState,
        webAppUrl: String,
    ): SpreadsheetUploadResult = withContext(Dispatchers.IO) {
        val trimmedUrl = webAppUrl.trim()
        if (trimmedUrl.isBlank()) {
            return@withContext SpreadsheetUploadResult.Failure("WebアプリURLが未設定です")
        }

        runCatching {
            val payload = state.toUploadPayload().toString().toByteArray(StandardCharsets.UTF_8)
            val connection = (URL(trimmedUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            connection.outputStream.use { output ->
                output.write(payload)
            }

            val code = connection.responseCode
            if (code in 200..399) {
                SpreadsheetUploadResult.Success(
                    weightCount = state.weightRecords.size,
                    glucoseCount = state.glucoseRecords.size,
                    stepCount = state.stepDailyRecords.size,
                )
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                SpreadsheetUploadResult.Failure("HTTP $code ${errorText.take(120)}".trim())
            }
        }.getOrElse { error ->
            SpreadsheetUploadResult.Failure(error.message ?: "原因不明")
        }
    }

    private fun HealthDebugUiState.toUploadPayload(): JSONObject {
        return JSONObject()
            .put("spreadsheetId", SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID)
            .put("sheetGid", SpreadsheetUploadSettings.TARGET_SHEET_GID)
            .put("uploadedAt", Instant.now().toString())
            .put(
                "summary",
                JSONObject()
                    .put("weightCount", weightRecords.size)
                    .put("glucoseCount", glucoseRecords.size)
                    .put("stepCount", stepDailyRecords.size),
            )
            .put(
                "weightRecords",
                JSONArray(
                    weightRecords.map { record ->
                        JSONObject()
                            .put("measuredAt", record.measuredAt.toString())
                            .put("targetDate", record.targetDate.toString())
                            .put("timeBand", record.timeBand)
                            .put("weightKg", record.weightKg)
                            .put("healthConnectId", record.healthConnectId)
                            .put("sourceAppName", record.sourceAppName)
                            .put("sourcePackageName", record.sourcePackageName)
                    },
                ),
            )
            .put(
                "glucoseRecords",
                JSONArray(
                    glucoseRecords.map { record ->
                        JSONObject()
                            .put("measuredAt", record.measuredAt.toString())
                            .put("targetDate", record.targetDate.toString())
                            .put("timeBand", record.timeBand)
                            .put("bloodGlucoseMgDl", record.bloodGlucoseMgDl)
                            .put("mealRelation", record.mealRelation)
                            .put("healthConnectId", record.healthConnectId)
                            .put("sourceAppName", record.sourceAppName)
                            .put("sourcePackageName", record.sourcePackageName)
                    },
                ),
            )
            .put(
                "stepDailyRecords",
                JSONArray(
                    stepDailyRecords.map { record ->
                        JSONObject()
                            .put("targetDate", record.targetDate.toString())
                            .put("steps", record.steps)
                            .put("aggregationStartAt", record.aggregationStartAt.toString())
                            .put("aggregationEndAt", record.aggregationEndAt.toString())
                    },
                ),
            )
    }
}

sealed interface SpreadsheetUploadResult {
    data class Success(
        val weightCount: Int,
        val glucoseCount: Int,
        val stepCount: Int,
    ) : SpreadsheetUploadResult

    data class Failure(val message: String) : SpreadsheetUploadResult
}
