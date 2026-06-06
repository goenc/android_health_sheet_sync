package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class HealthChartCalculationsTest {
    @Test
    fun displayRecord_prefers_latest_record_inside_window() {
        val window = ChartTimeWindow(
            startAt = LocalDateTime.of(2026, 1, 1, 0, 0),
            endAt = LocalDateTime.of(2026, 1, 5, 23, 59),
        )
        val chart = calculateFastingGlucoseChart(
            glucoseRecords = listOf(
                glucoseRecord(2026, 1, 10, 120.0),
                glucoseRecord(2026, 1, 4, 110.0),
                glucoseRecord(2026, 1, 2, 100.0),
            ),
            window = window,
        )

        assertNotNull(chart)
        assertEquals(110.0, chart!!.displayRecord()!!.bloodGlucoseMgDl, 0.0)
    }

    @Test
    fun displayRecord_falls_back_to_latest_record_outside_window_when_window_is_empty() {
        val window = ChartTimeWindow(
            startAt = LocalDateTime.of(2026, 1, 1, 0, 0),
            endAt = LocalDateTime.of(2026, 1, 5, 23, 59),
        )
        val chart = calculateFastingGlucoseChart(
            glucoseRecords = listOf(
                glucoseRecord(2026, 1, 10, 120.0),
                glucoseRecord(2025, 12, 31, 100.0),
            ),
            window = window,
        )

        assertNotNull(chart)
        assertEquals(120.0, chart!!.displayRecord()!!.bloodGlucoseMgDl, 0.0)
    }

    @Test
    fun fastingGlucoseChart_uses_real_measurements_for_line_and_range() {
        val window = ChartTimeWindow(
            startAt = LocalDateTime.of(2026, 1, 1, 0, 0),
            endAt = LocalDateTime.of(2026, 1, 5, 23, 59),
        )
        val chart = calculateFastingGlucoseChart(
            glucoseRecords = listOf(
                glucoseRecord(2025, 12, 31, 90.0),
                glucoseRecord(2026, 1, 2, 100.0),
                glucoseRecord(2026, 1, 4, 110.0),
            ),
            window = window,
        )

        assertNotNull(chart)
        val lineRecords = chart!!.lineRecords
        assertEquals(3, lineRecords.size)
        assertEquals(window.startAt, lineRecords.first().measuredAt)
        assertEquals(100.0, lineRecords[1].bloodGlucoseMgDl, 0.0)
        assertEquals(110.0, lineRecords[2].bloodGlucoseMgDl, 0.0)
        assertEquals(80.0, chart.minValueMgDl, 0.0)
        assertEquals(120.0, chart.maxValueMgDl, 0.0)
    }

    @Test
    fun fastingGlucoseChart_adds_left_boundary_point_and_nearest_right_record_when_window_has_no_visible_records() {
        val window = ChartTimeWindow(
            startAt = LocalDateTime.of(2026, 1, 1, 0, 0),
            endAt = LocalDateTime.of(2026, 1, 5, 23, 59),
        )
        val chart = calculateFastingGlucoseChart(
            glucoseRecords = listOf(
                glucoseRecord(2025, 12, 30, 90.0),
                glucoseRecord(2025, 12, 31, 100.0),
                glucoseRecord(2026, 1, 6, 110.0),
                glucoseRecord(2026, 1, 7, 120.0),
            ),
            window = window,
        )

        assertNotNull(chart)
        val lineRecords = chart!!.lineRecords
        assertEquals(2, lineRecords.size)
        assertEquals(window.startAt, lineRecords.first().measuredAt)
        assertEquals(LocalDateTime.of(2026, 1, 6, 7, 0), lineRecords.last().measuredAt)
        assertEquals(110.0, chart.displayRecord()!!.bloodGlucoseMgDl, 0.0)
    }

    @Test
    fun a1cChart_adds_left_boundary_point_and_nearest_right_record_when_window_has_no_visible_records() {
        val window = ChartTimeWindow(
            startAt = LocalDateTime.of(2026, 1, 1, 0, 0),
            endAt = LocalDateTime.of(2026, 1, 5, 23, 59),
        )
        val chart = calculateA1cChart(
            a1cDailyRecords = listOf(
                a1cRecord(2025, 12, 30, 5.8),
                a1cRecord(2025, 12, 31, 6.0),
                a1cRecord(2026, 1, 6, 6.2),
                a1cRecord(2026, 1, 7, 6.4),
            ),
            window = window,
        )

        assertNotNull(chart)
        val lineRecords = chart!!.lineRecords
        assertEquals(2, lineRecords.size)
        assertEquals(window.startAt, lineRecords.first().measuredAt)
        assertEquals(LocalDateTime.of(2026, 1, 6, 7, 0), lineRecords.last().measuredAt)
        assertEquals(6.2, chart.displayRecord()!!.value, 0.0)
    }

    @Test
    fun waistChart_adds_left_boundary_point_and_nearest_right_record_when_window_has_no_visible_records() {
        val window = ChartTimeWindow(
            startAt = LocalDateTime.of(2026, 1, 1, 0, 0),
            endAt = LocalDateTime.of(2026, 1, 5, 23, 59),
        )
        val chart = calculateWaistChart(
            manualRecords = listOf(
                waistRecord(2025, 12, 30, 84.0),
                waistRecord(2025, 12, 31, 83.5),
                waistRecord(2026, 1, 6, 83.0),
                waistRecord(2026, 1, 7, 82.5),
            ),
            window = window,
        )

        assertNotNull(chart)
        val lineRecords = chart!!.lineRecords
        assertEquals(2, lineRecords.size)
        assertEquals(window.startAt, lineRecords.first().measuredAt)
        assertEquals(LocalDateTime.of(2026, 1, 6, 7, 0), lineRecords.last().measuredAt)
        assertEquals(83.0, chart.displayRecord()!!.value, 0.0)
    }

    private fun glucoseRecord(
        year: Int,
        month: Int,
        day: Int,
        value: Double,
    ): DebugGlucoseRecord {
        val measuredAt = LocalDateTime.of(year, month, day, 7, 0)
        return DebugGlucoseRecord(
            measuredAt = measuredAt,
            targetDate = measuredAt.toLocalDate(),
            timeBand = "朝",
            bloodGlucoseMgDl = value,
            mealRelation = "空腹時",
            healthConnectId = "$year-$month-$day",
            sourceAppName = "test",
            sourcePackageName = "test.package",
        )
    }

    private fun a1cRecord(
        year: Int,
        month: Int,
        day: Int,
        value: Double,
    ): DebugA1cDaily {
        val measuredAt = LocalDateTime.of(year, month, day, 7, 0)
        return DebugA1cDaily(
            targetDate = measuredAt.toLocalDate(),
            measuredAt = measuredAt,
            a1cPercent = value,
            manualId = "$year-$month-$day",
        )
    }

    private fun waistRecord(
        year: Int,
        month: Int,
        day: Int,
        value: Double,
    ): ManualHealthRecord {
        val measuredAt = LocalDateTime.of(year, month, day, 7, 0)
        return ManualHealthRecord(
            id = "$year-$month-$day",
            type = ManualRecordType.Waist,
            measuredAt = measuredAt,
            valueText = "$value cm",
            invalidatedAt = null,
        )
    }
}
