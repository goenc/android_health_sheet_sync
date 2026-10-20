package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.health.DailyBodySetting
import com.goenc.healthsheetsync.health.DebugWeightRecord
import java.time.LocalDate

internal data class BmiChartPoint(val date: LocalDate, val value: Double)

internal fun dailyBmiPoints(
    records: List<DebugWeightRecord>,
    settings: List<DailyBodySetting>,
): List<BmiChartPoint> {
    val heights = settings.sortedWith(compareBy<DailyBodySetting> { it.targetDate }.thenBy { it.updatedAt })
    return records.filter { it.timeBand in listOf("朝", "夜") && it.weightKg.isFinite() && it.weightKg > 0.0 }
        .toChartWeightPoints()
        .groupBy { it.targetDate }
        .mapNotNull { (date, points) ->
            val heightCm = heights.lastOrNull { !it.targetDate.isAfter(date) }?.heightCm
                ?: return@mapNotNull null
            if (!heightCm.isFinite() || heightCm <= 0.0) return@mapNotNull null
            // 朝・夜の測定件数に偏りがあっても、時間帯ごとの平均を等しく扱う。
            val weight = points.map { it.weightKg }.average()
            val heightM = heightCm / 100.0
            val bmi = weight / (heightM * heightM)
            bmi.takeIf { it.isFinite() && it > 0.0 }?.let { BmiChartPoint(date, it) }
        }.sortedBy { it.date }
}
