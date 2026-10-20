package com.goenc.healthsheetsync.health

import java.time.LocalDate

/** The existing preference is the undated baseline; only user entries create dated history. */
object BasalMetabolicRateHistory {
    const val VALUE_SUFFIX = " kcal/日"

    fun latestOnOrBefore(date: LocalDate, records: List<ManualHealthRecord>): Int? = records
        .asSequence()
        .filter { it.type == ManualRecordType.BasalMetabolicRate && it.invalidatedAt == null &&
            !it.measuredAt.toLocalDate().isAfter(date) }
        .sortedWith(compareByDescending<ManualHealthRecord> { it.measuredAt }.thenByDescending { it.id })
        .mapNotNull { it.valueText.removeSuffix(VALUE_SUFFIX).trim().toIntOrNull()?.takeIf { value -> value > 0 } }
        .firstOrNull()

    fun resolve(date: LocalDate, initialValue: Int, records: List<ManualHealthRecord>): Int =
        latestOnOrBefore(date, records) ?: initialValue
}
