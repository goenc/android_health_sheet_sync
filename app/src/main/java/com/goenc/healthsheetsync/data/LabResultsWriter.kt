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
    val itemRows = labItemRows(snapshot.tables.items)
    val resultRows = labResultRows(snapshot.tables.results, snapshot.tables.items)
    val referenceRows = snapshot.references
    require(referenceRows.firstOrNull() == LAB_REFERENCE_BACKUP_HEADERS) {
        "labReferenceRangesのヘッダーを確認してください"
    }

    val request = JSONObject()
        .put("valueInputOption", "RAW")
        .put("data", JSONArray().apply {
            put(valueRange("labItems!A1:D${itemRows.size}", itemRows))
            put(valueRange("labResults!A1:D${resultRows.size}", resultRows))
            put(valueRange("labReferenceRanges!A1:F${referenceRows.size}", referenceRows))
        })
    // values:batchUpdateは既存行の同じ範囲を置き換えるため、再送しても重複しない。
    labSheetsRequest(accessToken, "/values:batchUpdate", request)
}

private fun valueRange(range: String, rows: List<List<String>>): JSONObject = JSONObject()
    .put("range", range)
    .put("majorDimension", "ROWS")
    .put("values", JSONArray().apply {
        rows.forEach { row -> put(JSONArray().apply { row.forEach(::put) }) }
    })
