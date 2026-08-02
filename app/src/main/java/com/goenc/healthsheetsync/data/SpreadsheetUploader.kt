package com.goenc.healthsheetsync.data

import com.goenc.healthsheetsync.health.HealthDebugUiState
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
    ): SpreadsheetUploadResult = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) {
            return@withContext SpreadsheetUploadResult.Failure("Google認証トークンが空です")
        }

        runCatching {
            val sheetNames = loadSheetNames(accessToken).toMutableSet()
            var addedCount = 0
            var skippedCount = 0
            uploadTables(state).forEach { table ->
                if (table.values.isEmpty()) {
                    return@forEach
                }
                ensureSheet(accessToken, table, sheetNames)
                val keyColumnIndex = loadKeyColumnIndex(accessToken, table)
                val existingKeys = loadExistingKeys(accessToken, table, keyColumnIndex)
                val rowsToAppend = table.values
                    .filter { row -> rowKey(row, keyColumnIndex) !in existingKeys }
                    .distinctBy { row -> rowKey(row, keyColumnIndex) }
                skippedCount += table.values.size - rowsToAppend.size
                if (rowsToAppend.isNotEmpty()) {
                    appendRows(accessToken, table, rowsToAppend)
                    addedCount += rowsToAppend.size
                }
            }
            SpreadsheetUploadResult.Success(
                addedCount = addedCount,
                skippedCount = skippedCount,
            )
        }.getOrElse { error ->
            SpreadsheetUploadResult.Failure(error.message ?: "原因不明")
        }
    }

    private fun uploadTables(state: HealthDebugUiState): List<SpreadsheetUploadTable> {
        return listOf(
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
                keyColumnName = "healthConnectId",
                values = state.weightRecords.map { record ->
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
                keyColumnName = "healthConnectId",
                values = state.glucoseRecords.map { record ->
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
                    "aggregationStartAt",
                    "aggregationEndAt",
                ),
                keyColumnName = "targetDate",
                values = state.stepDailyRecords.map { record ->
                    listOf(
                        record.targetDate.toString(),
                        record.steps,
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
                keyColumnName = "manualId",
                values = state.a1cDailyRecords.map { record ->
                    listOf(
                        record.targetDate.toString(),
                        record.measuredAt.toString(),
                        record.a1cPercent,
                        record.manualId,
                    )
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
    ) {
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
        if (!hasHeader(accessToken, table.sheetName)) {
            writeHeader(accessToken, table)
        }
    }

    private fun loadKeyColumnIndex(
        accessToken: String,
        table: SpreadsheetUploadTable,
    ): Int {
        val range = encodePathSegment("'${table.sheetName}'!A1:Z1")
        val url = URL(
            "https://sheets.googleapis.com/v4/spreadsheets/" +
                "${SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID}/values/$range",
        )
        val header = requestJson(accessToken, url, method = "GET")
            .optJSONArray("values")
            ?.optJSONArray(0)
            ?: error("シート ${table.sheetName} のヘッダーを取得できません")
        return (0 until header.length())
            .firstOrNull {
                normalizeHeader(header.optString(it)) == normalizeHeader(table.keyColumnName)
            }
            ?: error("シート ${table.sheetName} に重複判定列 ${table.keyColumnName} がありません")
    }

    private fun normalizeHeader(value: String): String {
        return value.filter { character -> character.isLetterOrDigit() }.lowercase()
    }

    private fun loadExistingKeys(
        accessToken: String,
        table: SpreadsheetUploadTable,
        keyColumnIndex: Int,
    ): Set<String> {
        val range = encodePathSegment("'${table.sheetName}'!A:Z")
        val url = URL(
            "https://sheets.googleapis.com/v4/spreadsheets/" +
                "${SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID}/values/$range",
        )
        val rows = requestJson(accessToken, url, method = "GET").optJSONArray("values")
            ?: return emptySet()
        return buildSet {
            for (index in 1 until rows.length()) {
                rows.optJSONArray(index)?.let { row ->
                    add(rowKey(row, keyColumnIndex))
                }
            }
        }
    }

    private fun rowKey(row: List<Any>, keyColumnIndex: Int): String {
        return row.getOrNull(keyColumnIndex)?.toString()?.trim()
            ?.takeUnless { it.isNullOrBlank() }
            ?: row.joinToString("|")
    }

    private fun rowKey(row: JSONArray, keyColumnIndex: Int): String {
        return row.optString(keyColumnIndex).trim()
            .takeUnless { it.isBlank() }
            ?: row.toString()
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

    private fun hasHeader(accessToken: String, sheetName: String): Boolean {
        val range = encodePathSegment("'$sheetName'!A1:Z1")
        val url = URL(
            "https://sheets.googleapis.com/v4/spreadsheets/" +
                "${SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID}/values/$range",
        )
        val values = requestJson(accessToken, url, method = "GET").optJSONArray("values")
        val firstRowLength = values?.optJSONArray(0)?.length() ?: 0
        return firstRowLength > 0
    }

    private fun writeHeader(accessToken: String, table: SpreadsheetUploadTable) {
        val range = encodePathSegment("'${table.sheetName}'!A1")
        val url = URL(
            "https://sheets.googleapis.com/v4/spreadsheets/" +
                "${SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID}/values/$range?valueInputOption=RAW",
        )
        val payload = JSONObject()
            .put("majorDimension", "ROWS")
            .put("values", JSONArray().put(JSONArray(table.headers)))
        requestJson(accessToken, url, method = "PUT", payload = payload)
    }

    private fun appendRows(
        accessToken: String,
        table: SpreadsheetUploadTable,
        rows: List<List<Any>>,
    ) {
        if (rows.isEmpty()) {
            return
        }

        val range = encodePathSegment("'${table.sheetName}'!A1")
        val url = URL(
            "https://sheets.googleapis.com/v4/spreadsheets/" +
                "${SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID}/values/$range:append" +
                "?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS",
        )
        val payload = JSONObject()
            .put("majorDimension", "ROWS")
            .put("values", JSONArray(rows.map { row -> JSONArray(row) }))
        requestJson(accessToken, url, method = "POST", payload = payload)
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

private data class SpreadsheetUploadTable(
    val sheetName: String,
    val headers: List<String>,
    val keyColumnName: String,
    val values: List<List<Any>>,
)

sealed interface SpreadsheetUploadResult {
    data class Success(
        val addedCount: Int,
        val skippedCount: Int,
    ) : SpreadsheetUploadResult

    data class Failure(val message: String) : SpreadsheetUploadResult
}
