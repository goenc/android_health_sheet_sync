package com.goenc.healthsheetsync.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class LabResultsSaveResult(val addedCount: Int, val duplicateCount: Int)

/** SQLiteの現在世代と、Sheetsへ最後に反映した世代をまとめたバックアップ単位。 */
internal data class LabBackupSnapshot(
    val tables: LabTables,
    /** labReferenceRangesのヘッダーを含む行。 */
    val references: List<List<String>>,
    val revision: Long,
    val backedUpRevision: Long,
    val bodyMeasurements: List<BodyCompositionMeasurement> = emptyList(),
)

/**
 * 検査結果を先に端末内DBへ保存し、必要な場合だけSheetsへ全量バックアップする。
 *
 * Sheetsへは追記せず、各正規化テーブルの先頭から全行を送る。DBの世代を
 * バックアップ済み世代として記録するのは、送信が成功した後だけに限定する。
 */
internal class LabResultsWriter(
    private val saveLocalRecords: (List<LabResult>) -> LabResultsSaveResult,
    private val loadSnapshot: () -> LabBackupSnapshot,
    private val uploadSnapshot: suspend (String, LabBackupSnapshot) -> Unit,
    private val markBackedUp: (Long) -> Unit,
) {
    constructor(reader: LabResultsReader) : this(
        saveLocalRecords = reader::saveLocalRecords,
        loadSnapshot = reader::backupSnapshot,
        uploadSnapshot = ::postLabBackup,
        markBackedUp = reader::markBackedUp,
    )

    /** 端末内DBへの保存だけを行う。Sheetsの読込・送信は呼び出さない。 */
    suspend fun saveLocal(incoming: List<LabResult>): LabResultsSaveResult = withContext(Dispatchers.IO) {
        saveLocalRecords(incoming)
    }

    /** 未反映世代がある場合だけ全量バックアップし、成功後にその世代を確認済みにする。 */
    suspend fun backupIfNeeded(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        val snapshot = loadSnapshot()
        if (snapshot.revision <= snapshot.backedUpRevision) return@withContext false
        uploadSnapshot(accessToken, snapshot)
        markBackedUp(snapshot.revision)
        true
    }
}

/** 検査用の正規化テーブル全体を、同じ世代のままSheetsへ上書きする。 */
internal suspend fun postLabBackup(accessToken: String, snapshot: LabBackupSnapshot) {
    val sheets = labSheetsRequest(accessToken, "?fields=sheets(properties(sheetId,title,gridProperties))")
    labSheetsRequest(accessToken, ":batchUpdate", buildLabBackupRequest(snapshot, sheets))
}

/** 全表を同時に更新。行数が減った場合も古い末尾の値を残さない。 */
internal fun buildLabBackupRequest(snapshot: LabBackupSnapshot, spreadsheet: JSONObject): JSONObject {
    val itemRows = labItemRows(snapshot.tables.items)
    val resultRows = labResultRows(snapshot.tables.results, snapshot.tables.items)
    val referenceRows = snapshot.references
    require(referenceRows.firstOrNull() == LAB_REFERENCE_BACKUP_HEADERS) {
        "labReferenceRangesのヘッダーを確認してください"
    }

    parseBodyCompositionTables(bodyMeasurementRows(snapshot.bodyMeasurements), bodyValueRows(snapshot.bodyMeasurements))
    val sheets = spreadsheet.getJSONArray("sheets")
    val properties = (0 until sheets.length()).map { sheets.getJSONObject(it).getJSONObject("properties") }.associateBy { it.getString("title") }
    val tables = linkedMapOf("labItems" to itemRows, "labResults" to resultRows, "labReferenceRanges" to referenceRows)
    if (snapshot.bodyMeasurements.isNotEmpty() || "bodyMeasurements" in properties || "bodyValues" in properties) {
        tables["bodyMeasurements"] = bodyMeasurementRows(snapshot.bodyMeasurements)
        tables["bodyValues"] = bodyValueRows(snapshot.bodyMeasurements)
    }
    val usedIds = properties.values.map { it.getInt("sheetId") }.toMutableSet()
    val requests = JSONArray()
    tables.forEach { (title, rows) ->
        val old = properties[title]
        require(old != null || title.startsWith("body")) { "検査結果のシートがありません: $title" }
        val id = old?.getInt("sheetId") ?: generateSequence(0) { it + 1 }.first { it !in usedIds }.also { usedIds += it }
        val oldGrid = old?.optJSONObject("gridProperties")
        val rowCount = maxOf(rows.size, oldGrid?.optInt("rowCount", 1000) ?: 1000)
        val columnCount = maxOf(rows.first().size, oldGrid?.optInt("columnCount", 26) ?: 26)
        val grid = JSONObject().put("rowCount", rowCount).put("columnCount", columnCount)
        if (old == null) requests.put(JSONObject().put("addSheet", JSONObject().put("properties",
            JSONObject().put("sheetId", id).put("title", title).put("gridProperties", grid))))
        else if (rowCount > oldGrid!!.getInt("rowCount") || columnCount > oldGrid.getInt("columnCount")) {
            requests.put(JSONObject().put("updateSheetProperties", JSONObject()
                .put("properties", JSONObject().put("sheetId", id).put("gridProperties", grid))
                .put("fields", "gridProperties.rowCount,gridProperties.columnCount")))
        }
        requests.put(JSONObject().put("updateCells", JSONObject()
            .put("range", JSONObject().put("sheetId", id).put("startRowIndex", 0).put("startColumnIndex", 0)
                .put("endRowIndex", rowCount).put("endColumnIndex", rows.first().size))
            .put("fields", "userEnteredValue")
            .put("rows", JSONArray().apply { rows.forEach { row -> put(JSONObject().put("values", JSONArray().apply {
                row.forEach { cell -> put(JSONObject().put("userEnteredValue", JSONObject().put("stringValue", cell))) }
            })) } })))
    }
    return JSONObject().put("requests", requests)
}
