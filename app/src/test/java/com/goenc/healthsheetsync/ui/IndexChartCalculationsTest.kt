package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.health.DailyBodySetting
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType
import com.goenc.healthsheetsync.health.ffmi
import com.goenc.healthsheetsync.health.navyMaleBodyFatPercent
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexChartCalculationsTest {
    private val day = LocalDate.of(2026, 10, 21)
    private val height = DailyBodySetting(day.minusDays(1), 180.0, 2000, day.minusDays(1).atStartOfDay())

    @Test
    fun ffmiUsesDailyAverageAndPastCircumferencesWithExistingFormula() {
        val points = dailyIndexPoints(listOf(weight("朝", 70.0), weight("夜", 74.0)), listOf(height), listOf(
            measurement(ManualRecordType.Waist, day.minusDays(1), "90 cm"),
            measurement(ManualRecordType.Neck, day.minusDays(1), "40 cm"),
            measurement(ManualRecordType.Waist, day.plusDays(1), "100 cm"),
        ))
        val point = points.single { it.date == day }
        val bodyFat = requireNotNull(navyMaleBodyFatPercent(180.0, 90.0, 40.0))
        assertEquals(requireNotNull(ffmi(72.0, 180.0, bodyFat)), requireNotNull(point.ffmi), 0.00001)
        assertEquals(0.5, requireNotNull(point.waistHeightRatio), 0.00001)
        assertEquals(72.0, requireNotNull(point.weightKg), 0.00001)
        assertEquals(90.0, requireNotNull(point.waistCm), 0.00001)
    }

    @Test
    fun ratioWorksWithoutWeightOrNeckAndReflectsHeightHistory() {
        val points = dailyIndexPoints(emptyList(), listOf(height, height.copy(targetDate = day, heightCm = 200.0)), listOf(
            measurement(ManualRecordType.Waist, day.minusDays(1), "90 cm"),
            measurement(ManualRecordType.Waist, day.plusDays(1), "92 cm"),
        ))
        assertEquals(listOf(day.minusDays(1), day, day.plusDays(1)), points.map { it.date })
        assertEquals(0.5, requireNotNull(points[0].waistHeightRatio), 0.00001)
        assertEquals(0.45, requireNotNull(points[1].waistHeightRatio), 0.00001)
        assertNull(points[1].bmi)
        assertNull(points[1].ffmi)
        assertNull(points[1].weightKg)
        assertEquals(90.0, requireNotNull(points[1].waistCm), 0.00001)
    }

    @Test
    fun invalidatedFutureAndInvalidMeasurementsDoNotProduceFfmi() {
        val records = listOf(weight("夜", 72.0))
        val points = dailyIndexPoints(records, listOf(height), listOf(
            measurement(ManualRecordType.Waist, day, "90 cm").copy(invalidatedAt = day.atTime(20, 0)),
            measurement(ManualRecordType.Neck, day, "40 cm"),
        ))
        assertEquals(1, points.size)
        assertNull(points.single().ffmi)
        assertNull(points.single().waistHeightRatio)
        assertNull(points.single().waistCm)
        listOf("0 cm", "NaN cm", "Infinity cm", "不明").forEach { text ->
            val point = dailyIndexPoints(records, listOf(height), listOf(
                measurement(ManualRecordType.Waist, day, text),
                measurement(ManualRecordType.Neck, day.plusDays(1), "40 cm"),
            )).first { it.date == day }
            assertNull(point.ffmi)
            assertNull(point.waistHeightRatio)
        }
    }

    private fun weight(band: String, kg: Double) = DebugWeightRecord(
        day.atTime(if (band == "朝") 7 else 20, 0), day, band, kg, band, "test", "test",
    )

    private fun measurement(type: ManualRecordType, date: LocalDate, text: String) = ManualHealthRecord(
        id = "$type-$date", type = type, measuredAt = date.atTime(12, 0), valueText = text, invalidatedAt = null,
    )
}
