package com.goenc.healthsheetsync.health

import java.time.LocalDate

/** Virtual baseline and independent dated fields; no measurement days are created. */
object BodySettingHistory {
    const val INITIAL_HEIGHT_CM = 168.2
    const val INITIAL_INTAKE_KCAL = 1600

    fun numericValue(type: ManualRecordType, text: String): Double? {
        val value = when (type) {
            ManualRecordType.Height -> text.removeSuffix(" cm").trim().toDoubleOrNull()
            ManualRecordType.AverageIntake -> text.removeSuffix(" kcal/日").trim().toIntOrNull()?.toDouble()
            else -> null
        }
        return value?.takeIf { it.isFinite() && it > 0.0 }
    }

    fun timeline(legacy: List<DailyBodySetting>, records: List<ManualHealthRecord>): List<DailyBodySetting> {
        val active = records.filter { it.invalidatedAt == null && numericValue(it.type, it.valueText) != null }
        val dates = (legacy.map { it.targetDate } + active.map { it.measuredAt.toLocalDate() } + LocalDate.MIN).distinct()
        return dates.sortedDescending().map { date ->
            val previous = legacy.filter { !it.targetDate.isAfter(date) }
                .maxWithOrNull(compareBy<DailyBodySetting> { it.targetDate }.thenBy { it.updatedAt })
            fun value(type: ManualRecordType, fallback: Double): Double {
                val entry = active.filter { it.type == type && !it.measuredAt.toLocalDate().isAfter(date) &&
                    (previous == null || !it.measuredAt.toLocalDate().isBefore(previous.targetDate)) }
                    .maxByOrNull { it.measuredAt }
                return entry?.let { numericValue(type, it.valueText) } ?: fallback
            }
            DailyBodySetting(date,
                value(ManualRecordType.Height, previous?.heightCm ?: INITIAL_HEIGHT_CM),
                value(ManualRecordType.AverageIntake, previous?.averageIntakeKcal?.toDouble() ?: INITIAL_INTAKE_KCAL.toDouble()).toInt(),
                date.atStartOfDay())
        }
    }
}
