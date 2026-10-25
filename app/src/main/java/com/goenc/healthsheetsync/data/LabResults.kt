package com.goenc.healthsheetsync.data

import java.time.Instant
import java.time.LocalDate

data class LabResult(
    val targetDate: LocalDate,
    val itemName: String,
    val value: String,
    val unit: String = "",
    val referenceRange: String = "",
    val flag: String = "",
)

data class LabResultsUiState(
    val records: List<LabResult> = emptyList(),
    val isLoading: Boolean = false,
    val status: String? = null,
    val lastUpdatedAt: Instant? = null,
    val bodyMeasurements: List<BodyCompositionMeasurement> = emptyList(),
)

/** Sheetsを正本とする。列順の変更と任意列の省略を許容する。 */
internal fun parseLabResults(rows: List<List<String>>): List<LabResult> {
    if (rows.isEmpty()) return emptyList()
    val headers = rows.first().map { it.trim() }
    listOf("targetDate", "itemName", "value").forEach { required ->
        require(required in headers) { "検査結果の列がありません: $required" }
    }
    require(headers.filter { it.isNotEmpty() }.distinct().size == headers.count { it.isNotEmpty() }) {
        "検査結果の列名が重複しています"
    }
    return rows.drop(1).mapIndexedNotNull { index, row ->
        if (row.all { it.isBlank() }) return@mapIndexedNotNull null
        fun cell(name: String) = row.getOrNull(headers.indexOf(name)).orEmpty().trim()
        val date = runCatching { LocalDate.parse(cell("targetDate")) }.getOrNull()
        require(date != null && cell("itemName").isNotBlank() && cell("value").isNotBlank()) {
            "検査結果の${index + 2}行目を確認してください（日付はYYYY-MM-DD、項目名・値は必須）"
        }
        val flag = cell("flag").uppercase()
        require(flag in listOf("", "H", "L")) { "検査結果の${index + 2}行目のflagはH/Lまたは空欄にしてください" }
        LabResult(date, cell("itemName"), cell("value"), cell("unit"), cell("referenceRange"), flag)
    }.sortedByDescending { it.targetDate }
}
