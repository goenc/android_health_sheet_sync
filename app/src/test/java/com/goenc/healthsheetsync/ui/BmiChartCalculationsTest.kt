package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.health.DailyBodySetting
import com.goenc.healthsheetsync.health.DebugWeightRecord
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BmiChartCalculationsTest {
    private val day = LocalDate.of(2026, 10, 20)

    @Test
    fun averagesMorningAndNightEquallyRegardlessOfMeasurementCount() {
        val points = dailyBmiPoints(listOf(
            weight(day, "夜", 84.0), weight(day, "朝", 68.0), weight(day, "朝", 72.0),
        ), listOf(height(day, 200.0)))

        assertEquals(1, points.size)
        assertEquals(19.25, points.single().value, 0.00001)
    }

    @Test
    fun usesSingleAvailableTimeBandAndSortsByDate() {
        val points = dailyBmiPoints(listOf(
            weight(day, "夜", 80.0), weight(day.minusDays(1), "朝", 72.0),
        ), listOf(height(day.minusDays(1), 200.0)))

        assertEquals(listOf(day.minusDays(1), day), points.map { it.date })
        assertEquals(18.0, points[0].value, 0.00001)
        assertEquals(20.0, points[1].value, 0.00001)
    }

    @Test
    fun usesHeightEffectiveOnEachDayAndLatestRevision() {
        val original = height(day, 160.0)
        val revised = height(day, 200.0).copy(updatedAt = day.atTime(18, 0))
        val points = dailyBmiPoints(listOf(
            weight(day.minusDays(1), "朝", 81.0), weight(day, "朝", 80.0),
        ), listOf(revised, height(day.plusDays(1), 100.0), original, height(day.minusDays(1), 180.0)))

        assertEquals(25.0, points[0].value, 0.00001)
        assertEquals(20.0, points[1].value, 0.00001)
    }

    @Test
    fun omitsMissingOrInvalidHeightsAndInvalidWeights() {
        val valid = listOf(weight(day, "朝", 72.0))
        assertTrue(dailyBmiPoints(valid, emptyList()).isEmpty())
        assertTrue(dailyBmiPoints(valid, listOf(height(day.plusDays(1), 180.0))).isEmpty())
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertTrue(dailyBmiPoints(valid, listOf(height(day, invalid))).isEmpty())
            assertTrue(dailyBmiPoints(listOf(weight(day, "朝", invalid)), listOf(height(day, 180.0))).isEmpty())
        }
        val points = dailyBmiPoints(valid + weight(day, "夜", Double.NaN), listOf(height(day, 180.0)))
        assertEquals(22.22222, points.single().value, 0.00001)
    }

    private fun weight(date: LocalDate, band: String, kg: Double) = DebugWeightRecord(
        measuredAt = date.atTime(if (band == "朝") 7 else 20, 0), targetDate = date,
        timeBand = band, weightKg = kg, healthConnectId = "$date-$band-$kg",
        sourceAppName = "test", sourcePackageName = "test",
    )

    private fun height(date: LocalDate, cm: Double) = DailyBodySetting(
        targetDate = date, heightCm = cm, averageIntakeKcal = 2000, updatedAt = date.atTime(12, 0),
    )
}
