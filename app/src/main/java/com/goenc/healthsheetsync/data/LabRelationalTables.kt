package com.goenc.healthsheetsync.data

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

internal data class LabItem(
    val itemId: String,
    val itemName: String,
    val unit: String,
    val initialReferenceRange: String = "",
)

/**
 * Normalized laboratory tables joined back to the application's existing DTO.
 * The relational sheets keep only item metadata and result values; reference
 * ranges and flags remain available on the DTO when converting legacy data.
 */
internal data class LabTables(
    val items: List<LabItem>,
    val results: List<LabResult>,
)

internal const val LAB_ITEMS_HEADER_ITEM_ID = "itemId"
internal const val LAB_ITEMS_HEADER_ITEM_NAME = "itemName"
internal const val LAB_ITEMS_HEADER_UNIT = "unit"
internal const val LAB_ITEMS_HEADER_INITIAL_REFERENCE_RANGE = "initialReferenceRange"
internal const val LAB_RESULTS_HEADER_TARGET_DATE = "targetDate"
internal const val LAB_RESULTS_HEADER_ITEM_ID = "itemId"
internal const val LAB_RESULTS_HEADER_VALUE = "value"
internal const val LAB_RESULTS_HEADER_VALUE_QUALIFIER = "valueQualifier"

private val labItemsHeaders = listOf(
    LAB_ITEMS_HEADER_ITEM_ID,
    LAB_ITEMS_HEADER_ITEM_NAME,
    LAB_ITEMS_HEADER_UNIT,
)

private val labItemsHeadersWithReferenceRange = labItemsHeaders + LAB_ITEMS_HEADER_INITIAL_REFERENCE_RANGE

private val labResultsHeaders = listOf(
    LAB_RESULTS_HEADER_TARGET_DATE,
    LAB_RESULTS_HEADER_ITEM_ID,
    LAB_RESULTS_HEADER_VALUE,
    LAB_RESULTS_HEADER_VALUE_QUALIFIER,
)

private val decimalPattern = Regex("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")
private val inequalityPattern = Regex("^(<=|>=|<|>|≤|≥)\\s*(.+)$")
private val JapaneseInequalityPattern = Regex("^(.+?)(以下|未満|以上|超)$")

/** Stable ID for a laboratory item name across devices and sheet migrations. */
internal fun labItemId(name: String): String {
    val normalizedName = name.trim()
    require(normalizedName.isNotEmpty()) { "検査項目名は空欄にできません" }
    return "lab_" + UUID.nameUUIDFromBytes(normalizedName.toByteArray(Charsets.UTF_8))
}

/**
 * Parse the normalized `items` and `results` sheets and join result rows with
 * their item metadata. Headers may be reordered, but no extra or duplicate
 * columns are accepted.
 */
internal fun parseLabRelationalTables(
    itemRows: List<List<String>>,
    resultRows: List<List<String>>,
): LabTables {
    val itemData = parseItemRows(itemRows)
    val resultData = parseRowsWithHeaders(resultRows, labResultsHeaders, "results")

    val items = itemData.mapIndexed { rowIndex, row ->
        val itemId = row.getValue(LAB_ITEMS_HEADER_ITEM_ID).trim()
        val itemName = row.getValue(LAB_ITEMS_HEADER_ITEM_NAME).trim()
        val unit = row.getValue(LAB_ITEMS_HEADER_UNIT).trim()
        val initialReferenceRange = row.getValue(LAB_ITEMS_HEADER_INITIAL_REFERENCE_RANGE).trim()
        require(itemId.isNotEmpty() && itemName.isNotEmpty()) {
            "itemsの${rowIndex + 2}行目に項目IDまたは項目名がありません"
        }
        LabItem(itemId, itemName, unit, initialReferenceRange)
    }
    require(items.map { it.itemId }.distinct().size == items.size) {
        "itemsの項目IDが重複しています"
    }
    require(items.map { it.itemName }.distinct().size == items.size) {
        "itemsの項目名が重複しています"
    }

    val itemsById = items.associateBy { it.itemId }
    val seenResultKeys = HashSet<Pair<LocalDate, String>>()
    val results = resultData.mapIndexed { rowIndex, row ->
        val dateText = row.getValue(LAB_RESULTS_HEADER_TARGET_DATE).trim()
        val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
        require(date != null) {
            "resultsの${rowIndex + 2}行目の日付を確認してください（YYYY-MM-DD）"
        }
        val itemId = row.getValue(LAB_RESULTS_HEADER_ITEM_ID).trim()
        val item = itemsById[itemId]
        require(item != null) { "resultsの${rowIndex + 2}行目が存在しない項目IDを参照しています" }
        val value = row.getValue(LAB_RESULTS_HEADER_VALUE).trim()
        require(value.isNotEmpty()) { "resultsの${rowIndex + 2}行目の値が空欄です" }
        val qualifier = row.getValue(LAB_RESULTS_HEADER_VALUE_QUALIFIER).trim()
        val resultValue = decodeRelationalValue(value, qualifier, rowIndex + 2)
        require(seenResultKeys.add(date to itemId)) {
            "resultsの同日・同項目が重複しています: $date / $itemId"
        }
        LabResult(date, item.itemName, resultValue, item.unit, item.initialReferenceRange)
    }
    return LabTables(items, results)
}

internal fun labItemRows(items: List<LabItem>): List<List<String>> {
    validateItems(items)
    return listOf(labItemsHeadersWithReferenceRange) + items.map { item ->
        listOf(item.itemId, item.itemName, item.unit, item.initialReferenceRange)
    }
}

internal fun labResultRows(records: List<LabResult>, items: List<LabItem>): List<List<String>> {
    validateItems(items)
    val itemsByName = items.associateBy { it.itemName }
    val seenResultKeys = HashSet<Pair<LocalDate, String>>()
    val rows = records.mapIndexed { index, record ->
        val item = itemsByName[record.itemName.trim()]
        require(item != null) { "検査結果の${index + 1}件目に対応する項目マスターがありません" }
        require(seenResultKeys.add(record.targetDate to item.itemId)) {
            "検査結果の同日・同項目が重複しています: ${record.targetDate} / ${item.itemId}"
        }
        val encoded = encodeLabValue(record.value, index + 1)
        listOf(record.targetDate.toString(), item.itemId, encoded.value, encoded.qualifier)
    }
    return listOf(labResultsHeaders) + rows
}

/**
 * Convert legacy rows into the normalized tables. A unit is shared by all
 * rows of an item; a blank unit is filled from the only nonblank unit found.
 * Reference ranges and flags are retained on the returned DTOs for legacy
 * preference seeding, although the relational serializers omit them.
 */
internal fun normalizeLabTables(records: List<LabResult>): LabTables {
    val normalizedRecords = records.mapIndexed { index, record ->
        val itemName = record.itemName.trim()
        require(itemName.isNotEmpty()) { "検査結果の${index + 1}件目の項目名が空欄です" }
        require(record.value.trim().isNotEmpty()) { "検査結果の${index + 1}件目の値が空欄です" }
        record.copy(itemName = itemName, unit = record.unit.trim())
    }
    val grouped = normalizedRecords.groupBy { it.itemName }
    val unitsByName = grouped.mapValues { (itemName, rows) ->
        val units = rows.map { it.unit }.filter { it.isNotEmpty() }.distinct()
        require(units.size <= 1) { "同じ検査項目の単位が一致しません: $itemName" }
        units.firstOrNull().orEmpty()
    }
    val initialReferenceRangesByName = grouped.mapValues { (_, rows) ->
        val ranges = rows.map { it.referenceRange.trim() }.filter { it.isNotEmpty() }.distinct()
        ranges.singleOrNull().orEmpty()
    }
    val seenResultKeys = HashSet<Pair<LocalDate, String>>()
    normalizedRecords.forEach { record ->
        require(seenResultKeys.add(record.targetDate to record.itemName)) {
            "検査結果の同日・同項目が重複しています: ${record.targetDate} / ${record.itemName}"
        }
    }
    val items = grouped.keys.map { itemName ->
        LabItem(
            itemId = labItemId(itemName),
            itemName = itemName,
            unit = unitsByName.getValue(itemName),
            initialReferenceRange = initialReferenceRangesByName.getValue(itemName),
        )
    }
    val results = normalizedRecords.map { record ->
        record.copy(unit = unitsByName.getValue(record.itemName))
    }
    labResultRows(results, items)
    return LabTables(items, results)
}

/** 小数の末尾や不等号の表記だけの差は、別の検査結果として扱わない。 */
internal fun labResultValuesEqual(left: String, right: String): Boolean {
    val a = encodeLabValue(left, 1)
    val b = encodeLabValue(right, 1)
    if (a.qualifier != b.qualifier) return false
    return if (a.qualifier == "text") a.value == b.value
    else BigDecimal(a.value).compareTo(BigDecimal(b.value)) == 0
}

private data class EncodedLabValue(val value: String, val qualifier: String)

private fun encodeLabValue(rawValue: String, rowNumber: Int): EncodedLabValue {
    val value = rawValue.trim()
    require(value.isNotEmpty()) { "検査結果の${rowNumber}件目の値が空欄です" }
    parseDecimal(value)?.let { return EncodedLabValue(value, "=") }

    parseInequality(value)?.let { (numericValue, qualifier) ->
        return EncodedLabValue(numericValue, qualifier)
    }
    return EncodedLabValue(value, "text")
}

private fun parseInequality(value: String): Pair<String, String>? {
    inequalityPattern.matchEntire(value)?.let { match ->
        val qualifier = when (match.groupValues[1]) {
            "<", "≤" -> if (match.groupValues[1] == "<") "<" else "<="
            ">", "≥" -> if (match.groupValues[1] == ">") ">" else ">="
            "<=" -> "<="
            ">=" -> ">="
            else -> return null
        }
        val numericValue = match.groupValues[2].trim()
        require(parseDecimal(numericValue) != null) { "不等号の後ろの値が数値ではありません: $value" }
        return numericValue to qualifier
    }
    JapaneseInequalityPattern.matchEntire(value)?.let { match ->
        val qualifier = when (match.groupValues[2]) {
            "未満" -> "<"
            "以下" -> "<="
            "超" -> ">"
            "以上" -> ">="
            else -> return null
        }
        val numericValue = match.groupValues[1].trim()
        require(parseDecimal(numericValue) != null) { "不等号の前の値が数値ではありません: $value" }
        return numericValue to qualifier
    }
    return null
}

private fun decodeRelationalValue(value: String, qualifier: String, rowNumber: Int): String {
    return when (qualifier) {
        "=" -> {
            require(parseDecimal(value) != null) {
                "resultsの${rowNumber}行目のvalueが数値ではありません"
            }
            value
        }
        "<" -> decodeNumericInequality(value, "未満", rowNumber)
        "<=" -> decodeNumericInequality(value, "以下", rowNumber)
        ">" -> decodeNumericInequality(value, "超", rowNumber)
        ">=" -> decodeNumericInequality(value, "以上", rowNumber)
        "text" -> value
        else -> throw IllegalArgumentException("resultsの${rowNumber}行目のvalueQualifierが不正です: $qualifier")
    }
}

private fun decodeNumericInequality(value: String, suffix: String, rowNumber: Int): String {
    require(parseDecimal(value) != null) {
        "resultsの${rowNumber}行目のvalueが数値ではありません"
    }
    return value + suffix
}

private fun parseDecimal(value: String): BigDecimal? {
    if (!decimalPattern.matches(value)) return null
    return runCatching { BigDecimal(value) }.getOrNull()
}

private fun parseRowsWithHeaders(
    rows: List<List<String>>,
    expectedHeaders: List<String>,
    tableName: String,
): List<Map<String, String>> {
    require(rows.isNotEmpty()) { "${tableName}のヘッダーがありません" }
    val headers = rows.first().map { it.trim() }
    require(headers.size == expectedHeaders.size && headers.toSet() == expectedHeaders.toSet()) {
        "${tableName}の列設定が不正です"
    }
    require(headers.distinct().size == headers.size) { "${tableName}の列名が重複しています" }
    val positions = expectedHeaders.associateWith { headers.indexOf(it) }
    return rows.drop(1).mapNotNull { row ->
        if (row.all { it.isBlank() }) return@mapNotNull null
        expectedHeaders.associateWith { header -> row.getOrNull(positions.getValue(header)).orEmpty() }
    }
}

private fun parseItemRows(rows: List<List<String>>): List<Map<String, String>> {
    require(rows.isNotEmpty()) { "itemsのヘッダーがありません" }
    val headers = rows.first().map { it.trim() }
    val acceptedHeaderSets = listOf(labItemsHeaders, labItemsHeadersWithReferenceRange).map { it.toSet() }
    require(headers.size in 3..4 && headers.toSet() in acceptedHeaderSets) { "itemsの列設定が不正です" }
    require(headers.distinct().size == headers.size) { "itemsの列名が重複しています" }
    val positions = headers.withIndex().associate { it.value to it.index }
    return rows.drop(1).mapNotNull { row ->
        if (row.all { it.isBlank() }) return@mapNotNull null
        mapOf(
            LAB_ITEMS_HEADER_ITEM_ID to row.getOrNull(positions.getValue(LAB_ITEMS_HEADER_ITEM_ID)).orEmpty(),
            LAB_ITEMS_HEADER_ITEM_NAME to row.getOrNull(positions.getValue(LAB_ITEMS_HEADER_ITEM_NAME)).orEmpty(),
            LAB_ITEMS_HEADER_UNIT to row.getOrNull(positions.getValue(LAB_ITEMS_HEADER_UNIT)).orEmpty(),
            LAB_ITEMS_HEADER_INITIAL_REFERENCE_RANGE to row.getOrNull(
                positions[LAB_ITEMS_HEADER_INITIAL_REFERENCE_RANGE] ?: -1,
            ).orEmpty(),
        )
    }
}

private fun validateItems(items: List<LabItem>) {
    require(items.map { it.itemId }.distinct().size == items.size) {
        "項目マスターの項目IDが重複しています"
    }
    require(items.map { it.itemName }.distinct().size == items.size) {
        "項目マスターの項目名が重複しています"
    }
    items.forEach {
        require(it.itemId.isNotBlank() && it.itemName.isNotBlank()) {
            "項目マスターの項目IDと項目名は必須です"
        }
    }
}
