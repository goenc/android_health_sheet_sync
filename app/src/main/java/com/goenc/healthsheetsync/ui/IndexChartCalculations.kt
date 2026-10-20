package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.health.DailyBodySetting
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType
import com.goenc.healthsheetsync.health.navyMaleBodyFatPercent
import java.time.LocalDate

internal data class IndexChartPoint(
    val date: LocalDate,
    val bmi: Double?,
    val ffmi: Double?,
    val waistHeightRatio: Double?,
    val weightKg: Double?,
    val waistCm: Double?,
)

internal fun dailyIndexPoints(
    records: List<DebugWeightRecord>,
    settings: List<DailyBodySetting>,
    manualRecords: List<ManualHealthRecord>,
): List<IndexChartPoint> {
    val bmiByDate = dailyBmiPoints(records, settings).associateBy { it.date }
    val measurements = manualRecords.filter {
        it.invalidatedAt == null && it.type in listOf(ManualRecordType.Waist, ManualRecordType.Neck)
    }.sortedBy { it.measuredAt }
    val heights = settings.sortedWith(compareBy<DailyBodySetting> { it.targetDate }.thenBy { it.updatedAt })
    val latestDate = (records.map { it.targetDate } + measurements.map { it.measuredAt.toLocalDate() }).maxOrNull()
        ?: return emptyList()
    val dates = (bmiByDate.keys + measurements.map { it.measuredAt.toLocalDate() } +
        settings.map { it.targetDate }.filter { !it.isAfter(latestDate) }).sorted()
    return dates.mapNotNull { date ->
        val height = heights.lastOrNull { !it.targetDate.isAfter(date) }?.heightCm
            ?.takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
        fun measurement(type: ManualRecordType): Double? = measurements.lastOrNull {
            it.type == type && !it.measuredAt.toLocalDate().isAfter(date)
        }?.valueText?.removeSuffix(" cm")?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
        val waist = measurement(ManualRecordType.Waist)
        val neck = measurement(ManualRecordType.Neck)
        val bmi = bmiByDate[date]?.value
        val bodyFat = if (waist != null && neck != null) navyMaleBodyFatPercent(height, waist, neck) else null
        val ffmi = if (bmi != null && bodyFat != null && bodyFat >= 0.0 && bodyFat < 100.0) {
            (bmi * (1.0 - bodyFat / 100.0)).takeIf { it.isFinite() && it > 0.0 }
        } else null
        val ratio = waist?.let { it / height }?.takeIf { it.isFinite() && it > 0.0 }
        if (bmi == null && ffmi == null && ratio == null) null else IndexChartPoint(
            date, bmi, ffmi, ratio, bmiByDate[date]?.weightKg, waist,
        )
    }
}
