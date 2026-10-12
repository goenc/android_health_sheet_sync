package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.data.LabResult
import java.time.LocalDate

/** 全検査日の項目を共通順で並べる。未検査の表示行は保存・グラフには使わない。 */
internal fun dailyLabResultRows(records: List<LabResult>, date: LocalDate): List<LabResult> {
    val templates = records.sortedBy { it.targetDate }.distinctBy { it.itemName to it.unit }
    val measured = records.filter { it.targetDate == date }.associateBy { it.itemName to it.unit }
    return templates.map { template ->
        measured[template.itemName to template.unit] ?: LabResult(
            targetDate = date,
            itemName = template.itemName,
            value = "—",
            unit = template.unit,
        )
    }
}
