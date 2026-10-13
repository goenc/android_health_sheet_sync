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

/** Sheetsの正規化テーブルを読み、SQLiteへトランザクション保存する。 */
internal class LabResultsReader(context: Context) {
    private val database = LabDatabase.get(context)
    private val referenceSettings = LabReferenceSettingsStore(context)
    private val legacyCache = AtomicFile(File(context.noBackupFilesDir, "lab_results_cache.json"))

    fun loadCached(): LabResultsUiState = runCatching {
        if (database.metadata("legacy_cache_migrated") == null) {
            if (legacyCache.baseFile.exists()) {
                val json = legacyCache.openRead().bufferedReader().use { JSONObject(it.readText()) }
                val records = parseLabResults(json.getJSONArray("values").toRows())
                referenceSettings.initializeMissing(records)
                database.replace(normalizeLabTables(records), Instant.parse(json.getString("updatedAt")))
            }
            database.mark("legacy_cache_migrated")
        }
        referenceSettings.load()
        database.load()
    }.getOrElse { LabResultsUiState(status = "検査データの読み込みに失敗: ${it.message}") }

    suspend fun read(accessToken: String): LabResultsUiState = withContext(Dispatchers.IO) {
        val response = labSheetsRequest(accessToken,
            "/values:batchGet?ranges=labItems!A:D&ranges=labResults!A:D&ranges=labReferenceRanges!A:F&valueRenderOption=UNFORMATTED_VALUE")
        val ranges = response.getJSONArray("valueRanges")
        val tables = parseLabRelationalTables(
            ranges.getJSONObject(0).optJSONArray("values")?.toRows().orEmpty(),
            ranges.getJSONObject(1).optJSONArray("values")?.toRows().orEmpty(),
        )
        val references = parseLabReferenceBackup(ranges.getJSONObject(2).optJSONArray("values")?.toRows().orEmpty(), tables.items)
        check(database.backupSnapshot().let { it.revision <= it.backedUpRevision }) { "Sheets未反映の検査データがあります。先に反映してください" }
        database.replace(tables, Instant.now())
        val currentReferences = referenceSettings.load()
        references.filterKeys { it !in currentReferences }.forEach { (name, range) -> database.saveReference(name, range) }
        referenceSettings.initializeMissing(tables.results)
        database.load().copy(status = "${tables.results.size}件を取得")
    }

    internal fun saveLocalRecords(records: List<LabResult>): LabResultsSaveResult = database.appendRecords(records)
    internal fun backupSnapshot(): LabBackupSnapshot = database.backupSnapshot()
    internal fun markBackedUp(revision: Long) = database.markBackedUp(revision)
}

/** 共通HTTP処理。トークンや検査値をログへ出力しない。 */
internal fun labSheetsRequest(accessToken: String, suffix: String, body: JSONObject? = null): JSONObject {
    require(accessToken.isNotBlank()) { "Google認証トークンを取得できませんでした" }
    val connection = (URL("https://sheets.googleapis.com/v4/spreadsheets/" +
        SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID + suffix).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000
        setRequestProperty("Authorization", "Bearer $accessToken")
        setRequestProperty("Accept", "application/json")
        if (body != null) {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
    }
    try {
        body?.let { payload -> connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) } }
        val code = connection.responseCode
        check(code in 200..299) {
            if (code == 401 || code == 403) "検査データにアクセスできません。Googleアカウントを再認証してください（HTTP $code）"
            else "検査データの取得・保存に失敗しました（HTTP $code）。labItemsとlabResultsを確認してください"
        }
        return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    } finally { connection.disconnect() }
}

internal fun JSONArray.toRows(): List<List<String>> = (0 until length()).map { rowIndex ->
    val row = getJSONArray(rowIndex)
    (0 until row.length()).map { column -> if (row.isNull(column)) "" else row.get(column).toString() }
}
