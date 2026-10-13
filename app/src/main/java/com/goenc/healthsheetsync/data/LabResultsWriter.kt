package com.goenc.healthsheetsync.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class LabResultsSaveResult(val addedCount: Int, val duplicateCount: Int)

/** 項目マスターの追加と結果の追記を1回のSheets batchUpdateで保存する。 */
internal class LabResultsWriter(
    private val fetchTables: suspend (String) -> LabTables,
    private val appendTables: suspend (String, List<LabItem>, List<List<String>>) -> Unit,
    private val localUnits: () -> Map<String, String> = { emptyMap() },
) {
    constructor(reader: LabResultsReader) : this(reader::fetchTables, ::postLabTables, reader::localUnits)

    suspend fun append(accessToken: String, incoming: List<LabResult>): LabResultsSaveResult = withContext(Dispatchers.IO) {
        val validated = incoming.map { it.copy(itemName = it.itemName.trim(), value = it.value.trim(), unit = "", referenceRange = "", flag = "") }
        normalizeLabTables(validated)
        val existing = fetchTables(accessToken)
        val plan = planLabResultsImport(validated, existing.results)
        require(plan.conflicts.isEmpty()) { "保存先に値の異なる同日・同項目があります。更新して内容を確認してください" }
        if (plan.additions.isEmpty()) return@withContext LabResultsSaveResult(0, plan.duplicates.size)
        val knownNames = existing.items.map { it.itemName }.toSet()
        val units = localUnits()
        val addedItems = plan.additions.map { it.itemName }.distinct().filter { it !in knownNames }.map {
            LabItem(labItemId(it), it, units[it].orEmpty())
        }
        val items = existing.items + addedItems
        val newRows = labResultRows(plan.additions, items).drop(1)
        parseLabRelationalTables(labItemRows(items), labResultRows(existing.results + plan.additions, items))
        appendTables(accessToken, addedItems, newRows)
        LabResultsSaveResult(plan.additions.size, plan.duplicates.size)
    }
}

private fun appendRequest(sheetId: Int, rows: List<List<String>>): JSONObject = JSONObject().put("appendCells", JSONObject()
    .put("sheetId", sheetId).put("fields", "userEnteredValue")
    .put("rows", JSONArray(rows.map { row -> JSONObject().put("values", JSONArray(row.map { value ->
        JSONObject().put("userEnteredValue", JSONObject().put("stringValue", value))
    })) })))

private suspend fun postLabTables(accessToken: String, items: List<LabItem>, rows: List<List<String>>) {
    val metadata = labSheetsRequest(accessToken, "?fields=sheets(properties(sheetId,title))")
    val sheets = metadata.getJSONArray("sheets")
    val ids = (0 until sheets.length()).associate { index ->
        val props = sheets.getJSONObject(index).getJSONObject("properties")
        props.getString("title") to props.getInt("sheetId")
    }
    val requests = JSONArray()
    if (items.isNotEmpty()) requests.put(appendRequest(requireNotNull(ids["labItems"]), labItemRows(items).drop(1)))
    requests.put(appendRequest(requireNotNull(ids["labResults"]), rows))
    labSheetsRequest(accessToken, ":batchUpdate", JSONObject().put("requests", requests))
}
