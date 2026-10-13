package com.goenc.healthsheetsync.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class LabResultsSaveResult(val addedCount: Int, val duplicateCount: Int)

private data class LabResultsWriterTransport(
    val fetchRows: suspend (String) -> JSONArray,
    val appendRows: suspend (String, List<List<String>>) -> Unit,
)

/** 利用者が確認した検査結果のみを追記。既存行の置換・削除は行わない。 */
internal class LabResultsWriter private constructor(
    private val transport: LabResultsWriterTransport,
) {
    internal constructor(reader: LabResultsReader) : this(
        LabResultsWriterTransport(
            fetchRows = { accessToken -> reader.fetchRows(accessToken) },
            appendRows = ::postLabResultsRows,
        ),
    )

    /** JVMテスト用。実Sheetsへ接続せず、読み戻しと追記を注入する。 */
    internal constructor(
        fetchRows: suspend (String) -> JSONArray,
        appendRows: suspend (String, List<List<String>>) -> Unit,
    ) : this(LabResultsWriterTransport(fetchRows, appendRows))

    suspend fun append(accessToken: String, incoming: List<LabResult>): LabResultsSaveResult =
        withContext(Dispatchers.IO) {
            val validatedIncoming = validateLabResultsForWrite(incoming)
            val rows = transport.fetchRows(accessToken).toRows()
            val existing = parseLabResults(rows)
            val plan = planLabResultsImport(validatedIncoming, existing)
            require(plan.conflicts.isEmpty()) { "保存先に値の異なる同日・同項目があります。更新して内容を確認してください" }
            if (plan.additions.isEmpty()) return@withContext LabResultsSaveResult(0, plan.duplicates.size)
            val values = labResultsAppendRows(rows.firstOrNull(), plan.additions)
            transport.appendRows(accessToken, values)
            LabResultsSaveResult(plan.additions.size, plan.duplicates.size)
        }
}

private suspend fun postLabResultsRows(accessToken: String, values: List<List<String>>) {
    val connection = (URL("https://sheets.googleapis.com/v4/spreadsheets/" +
        "${SpreadsheetUploadSettings.TARGET_SPREADSHEET_ID}/values/labResults!A:F:append" +
        "?valueInputOption=RAW&insertDataOption=INSERT_ROWS").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 30_000
        doOutput = true
        setRequestProperty("Authorization", "Bearer $accessToken")
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
    }
    try {
        val payload = JSONObject().put("majorDimension", "ROWS")
            .put("values", JSONArray(values.map { JSONArray(it) })).toString()
        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        check(code in 200..299) {
            "検査結果の保存に失敗しました（HTTP $code）。権限・保存先を確認してください"
        }
        val response = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        val updatedRows = response.getJSONObject("updates").getInt("updatedRows")
        check(updatedRows == values.size) { "保存件数を確認できません。更新して保存先を確認してください" }
    } finally {
        connection.disconnect()
    }
}

private fun validateLabResultsForWrite(records: List<LabResult>): List<LabResult> {
    if (records.isEmpty()) return emptyList()
    val rows = listOf(listOf("targetDate", "itemName", "value")) +
        records.map { record ->
            listOf(
                record.targetDate.toString(),
                record.itemName,
                record.value,
            )
        }
    // 取り込みの保存対象は日付・項目名・値のみ。既存行の付加情報は変更しない。
    parseLabResults(rows)
    return records.map { record ->
        record.copy(
            itemName = record.itemName.trim(),
            value = record.value.trim(),
            unit = "",
            referenceRange = "",
            flag = "",
        )
    }
}

internal fun labResultsAppendRows(existingHeaders: List<String>?, records: List<LabResult>): List<List<String>> {
    val standardHeaders = listOf("targetDate", "itemName", "value", "unit", "referenceRange", "flag")
    val headers = existingHeaders?.map { it.trim() } ?: standardHeaders
    require(headers.size <= 6 && headers.filter { it.isNotBlank() }.all { it in standardHeaders }) {
        "保存先のlabResultsの列設定を確認してください"
    }
    require(listOf("targetDate", "itemName", "value").all { it in headers }) { "保存先の必須列がありません" }
    val dataRows = records.map { record ->
        val fields = mapOf("targetDate" to record.targetDate.toString(), "itemName" to record.itemName,
            "value" to record.value, "unit" to record.unit, "referenceRange" to record.referenceRange,
            "flag" to record.flag)
        // 任意列が保存先にない場合、値を黙って捨てない。
        require(fields.filter { it.key !in headers }.values.all { it.isBlank() }) {
            "保存先に単位・基準範囲・高低表示の列が不足しています。labResultsの6列を確認してください"
        }
        headers.map { fields[it].orEmpty() }
    }
    return if (existingHeaders == null) listOf(headers) + dataRows else dataRows
}
