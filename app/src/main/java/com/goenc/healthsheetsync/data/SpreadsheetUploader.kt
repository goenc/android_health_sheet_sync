package com.goenc.healthsheetsync.data

import com.goenc.healthsheetsync.health.HealthDebugUiState
import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.DailyBodySetting
import com.goenc.healthsheetsync.health.DailyEnergySnapshot
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SpreadsheetUploader {
    suspend fun upload(
        state: HealthDebugUiState,
        accessToken: String,
    ): SpreadsheetUploadResult {
        return upload(
            accessToken = accessToken,
            tables = uploadTables(
                weightRecords = state.weightRecords,
                glucoseRecords = state.glucoseRecords,
                stepDailyRecords = state.stepDailyRecords,
                a1cDailyRecords = state.a1cDailyRecords,
                manualRecords = state.manualRecords,
                dailyBodySettings = state.dailyBodySettings,
                dailyEnergySnapshots = state.dailyEnergySnapshots,
            ),
        )
    }

    suspend fun upload(
        data: StoredHealthData,
        accessToken: String,
    ): SpreadsheetUploadResult {
        return upload(
            accessToken = accessToken,
            tables = uploadTables(
                weightRecords = data.weightRecords,
                glucoseRecords = data.glucoseRecords,
                stepDailyRecords = data.stepDailyRecords,
                a1cDailyRecords = data.a1cDailyRecords,
                manualRecords = data.manualRecords,
                dailyBodySettings = data.dailyBodySettings,
                dailyEnergySnapshots = data.dailyEnergySnapshots,
            ),
        )
    }

    private suspend fun upload(
        accessToken: String,
        tables: List<SpreadsheetUploadTable>,
    ): SpreadsheetUploadResult = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) {
            return@withContext SpreadsheetUploadResult.Failure("Google認証トークンが空です")
        }

        runCatching {
            val sheetNames = loadSheetNames(accessToken).toMutableSet()
            var synchronizedCount = 0
            tables.forEach { table ->
                val header = ensureSheet(accessToken, table, sheetNames)
                replaceRows(accessToken, table, header)
                synchronizedCount += table.values.size
            }
            SpreadsheetUploadResult.Success(
                synchronizedCount = synchronizedCount,
            )
        }.getOrElse { error ->
            SpreadsheetUploadResult.Failure(error.message ?: "原因不明")
        }
    }

    internal fun uploadTables(
        weightRecords: List<DebugWeightRecord>,
        glucoseRecords: List<DebugGlucoseRecord>,
        stepDailyRecords: List<DebugStepDaily>,
        a1cDailyRecords: List<DebugA1cDaily>,
        manualRecords: List<ManualHealthRecord>,
        dailyBodySettings: List<DailyBodySetting>,
        dailyEnergySnapshots: List<DailyEnergySnapshot>,
    ): List<SpreadsheetUploadTable> {
        val dailyEnergyByDate = dailyEnergySnapshots.associateBy { it.targetDate }
        return listOf(
            SpreadsheetUploadTable(
                sheetName = "dailyBodySettings",
                headers = listOf("targetDate", "heightCm", "averageIntakeKcal", "updatedAt"),
                values = dailyBodySettings
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
        )
    }

    private fun loadSheetNames(accessToken: String): Set<String> {
        val url = URL(
            "https://sheets.googleapis.com/v4/spreadsheets/" +
                "${SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID}?fields=sheets.properties.title",
        )
        val response = requestJson(accessToken, url, method = "GET")
        val sheets = response.optJSONArray("sheets") ?: return emptySet()
        return buildSet {
            repeat(sheets.length()) { index ->
                val title = sheets
                    .optJSONObject(index)
                    ?.optJSONObject("properties")
                    ?.optString("title")
                    .orEmpty()
                if (title.isNotBlank()) {
                    add(title)
                }
            }
        }
    }

    private fun ensureSheet(
        accessToken: String,
        table: SpreadsheetUploadTable,
        sheetNames: MutableSet<String>,
    ): List<String> {
        if (!sheetNames.contains(table.sheetName)) {
            runCatching {
                addSheet(accessToken, table.sheetName)
            }.getOrElse { error ->
                // 一覧取得の応答遅延などで既存タブを見落としても、同期を継続する。
                if (!error.message.orEmpty().contains("already exists") &&
                    !error.message.orEmpty().contains("すでに存在")
                ) {
                    throw error
                }
            }
            sheetNames.add(table.sheetName)
        }
        val header = loadHeader(accessToken, table.sheetName)
        if (header.isEmpty()) {
            writeHeader(accessToken, table.sheetName, table.headers)
            return table.headers
        }
        val normalizedHeader = header.map(::normalizeHeader).toSet()
        val missingHeaders = table.headers.filterNot { headerName ->
            normalizeHeader(headerName) in normalizedHeader
        }
        if (missingHeaders.isNotEmpty()) {
            val updatedHeader = header + missingHeaders
            writeHeader(accessToken, table.sheetName, updatedHeader)
            return updatedHeader
        }
        return header
    }

    private fun loadHeader(
        accessToken: String,
        sheetName: String,
    ): List<String> {
        val range = encodePathSegment("'$sheetName'!A1:Z1")
        val url = URL(
            "https://sheets.googleapis.com/v4/spreadsheets/" +
                "${SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID}/values/$range",
        )
        val values = requestJson(accessToken, url, method = "GET")
            .optJSONArray("values")
            ?.optJSONArray(0)
            ?: return emptyList()
        return (0 until values.length()).map { index -> values.optString(index) }
    }

    private fun normalizeHeader(value: String): String {
        return value.filter { character -> character.isLetterOrDigit() }.lowercase()
    }

    private fun addSheet(accessToken: String, sheetName: String) {
        val url = URL(
            "https://sheets.googleapis.com/v4/spreadsheets/" +
                "${SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID}:batchUpdate",
        )
        val payload = JSONObject()
            .put(
                "requests",
                JSONArray()
                    .put(
                        JSONObject()
                            .put(
                                "addSheet",
                                JSONObject()
                                    .put(
                                        "properties",
                                        JSONObject().put("title", sheetName),
                                    ),
                            ),
                    ),
            )
        requestJson(accessToken, url, method = "POST", payload = payload)
    }

    private fun writeHeader(
        accessToken: String,
        sheetName: String,
        headers: List<String>,
    ) {
        val range = encodePathSegment("'$sheetName'!A1")
        val url = URL(
            "https://sheets.googleapis.com/v4/spreadsheets/" +
                "${SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID}/values/$range?valueInputOption=RAW",
        )
        val payload = JSONObject()
            .put("majorDimension", "ROWS")
            .put("values", JSONArray().put(JSONArray(headers)))
        requestJson(accessToken, url, method = "PUT", payload = payload)
    }

    private fun replaceRows(
        accessToken: String,
        table: SpreadsheetUploadTable,
        header: List<String>,
    ) {
        clearRows(accessToken, table.sheetName)
        if (table.values.isEmpty()) return

        val valuesByHeader = table.values.map { row ->
            val values = table.headers.mapIndexed { index, name ->
                normalizeHeader(name) to row.getOrNull(index)
            }.toMap()
            header.map { name -> values[normalizeHeader(name)] ?: "" }
        }

        val range = encodePathSegment("'${table.sheetName}'!A2")
        val url = URL(
            "https://sheets.googleapis.com/v4/spreadsheets/" +
                "${SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID}/values/$range?valueInputOption=RAW",
        )
        val payload = JSONObject()
            .put("majorDimension", "ROWS")
            .put("values", JSONArray(valuesByHeader.map { row -> JSONArray(row) }))
        requestJson(accessToken, url, method = "PUT", payload = payload)
    }

    private fun clearRows(accessToken: String, sheetName: String) {
        val range = encodePathSegment("'$sheetName'!A2:Z")
        val url = URL(
            "https://sheets.googleapis.com/v4/spreadsheets/" +
                "${SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID}/values/$range:clear",
        )
        requestJson(accessToken, url, method = "POST", payload = JSONObject())
    }

    private fun requestJson(
        accessToken: String,
        url: URL,
        method: String,
        payload: JSONObject? = null,
    ): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = payload != null
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        if (payload != null) {
            val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { output ->
                output.write(bytes)
            }
        }

        val code = connection.responseCode
        if (code !in 200..399) {
            val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error("HTTP $code ${errorText.take(120)}".trim())
        }
        val responseText = connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}

internal data class SpreadsheetUploadTable(
    val sheetName: String,
    val headers: List<String>,
    val values: List<List<Any>>,
)

sealed interface SpreadsheetUploadResult {
    data class Success(
        val synchronizedCount: Int,
    ) : SpreadsheetUploadResult

    data class Failure(val message: String) : SpreadsheetUploadResult
}
