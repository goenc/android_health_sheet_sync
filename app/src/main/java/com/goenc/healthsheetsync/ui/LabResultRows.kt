package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.data.LabResult
import java.time.LocalDate

/** 全検査日の項目を共通順で並べる。未検査の表示行は保存・グラフには使わない。 */
internal fun dailyLabResultRows(records: List<LabResult>, date: LocalDate): List<LabResult> {
    val knownUnits = records.groupBy { it.itemName }.mapValues { (_, items) ->
        items.map { it.unit }.filter { it.isNotBlank() }.distinct()
    }
    fun key(record: LabResult): Pair<String, String> = record.itemName to
        (record.unit.ifBlank { knownUnits[record.itemName]?.singleOrNull().orEmpty() })
    val templates = records.sortedBy { it.targetDate }.distinctBy(::key)
    val measured = records.filter { it.targetDate == date }.associateBy(::key)
    return templates.map { template ->
        val itemKey = key(template)
        measured[itemKey]?.let { result ->
            result.copy(unit = itemKey.second)
        } ?: LabResult(
            targetDate = date,
            itemName = template.itemName,
            value = "—",
            unit = itemKey.second,
        )
    }
}
