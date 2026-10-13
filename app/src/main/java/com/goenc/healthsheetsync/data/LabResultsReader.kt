package com.goenc.healthsheetsync.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** 検査結果を取得する。携帯側のミラー同期には含めない。 */
internal class LabResultsReader(context: Context) {
    private val cache = AtomicFile(File(context.noBackupFilesDir, "lab_results_cache.json"))

    fun loadCached(): LabResultsUiState = runCatching {
        val json = cache.openRead().bufferedReader().use { JSONObject(it.readText()) }
        LabResultsUiState(
            records = parseLabResults(json.getJSONArray("values").toRows()),
            lastUpdatedAt = Instant.parse(json.getString("updatedAt")),
        )
    }.getOrDefault(LabResultsUiState())

    suspend fun read(accessToken: String): LabResultsUiState = withContext(Dispatchers.IO) {
        val values = fetchRows(accessToken)
        val records = parseLabResults(values.toRows())
        val updatedAt = Instant.now()
        val json = JSONObject().put("values", values).put("updatedAt", updatedAt.toString())
        val output = cache.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            cache.finishWrite(output)
        } catch (error: Exception) {
            cache.failWrite(output)
            throw error
        }
        LabResultsUiState(records = records, lastUpdatedAt = updatedAt, status = "${records.size}件を取得")
    }

    internal suspend fun fetchRows(accessToken: String): JSONArray = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank()) { "Google認証トークンを取得できませんでした" }
        val url = URL("https://sheets.googleapis.com/v4/spreadsheets/" +
            "${SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID}/values/labResults!A:F" +
            "?valueRenderOption=UNFORMATTED_VALUE&dateTimeRenderOption=FORMATTED_STRING")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val values = try {
            val code = connection.responseCode
            check(code == 200) {
                when (code) {
                    401, 403 -> "検査結果を取得できません。保存先にアクセスできるGoogleアカウントで再認証してください（HTTP $code）"
                    400 -> "保存先のlabResultsタブと列設定を確認してください（HTTP 400）"
                    else -> "検査結果の取得に失敗しました（HTTP $code）"
                }
            }
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                .optJSONArray("values") ?: JSONArray()
        } finally {
            connection.disconnect()
        }
        values
    }
}

internal fun JSONArray.toRows(): List<List<String>> = (0 until length()).map { rowIndex ->
    val row = getJSONArray(rowIndex)
    (0 until row.length()).map { column -> if (row.isNull(column)) "" else row.get(column).toString() }
}
