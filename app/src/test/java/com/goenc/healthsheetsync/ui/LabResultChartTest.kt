package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.data.LabResult
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabResultChartTest {
    @Test
    fun plotsExactFiniteNumbersAndKeepsDatesInChronologicalOrder() {
        val points = labChartPoints(listOf(
            result("2026-04-01", "1,234.50"),
            result("2026-01-01", "0"),
            result("2026-02-01", "(-)"),
            result("2026-03-01", "<5"),
            result("2026-05-01", "NaN"),
            result("2026-06-01", "Infinity"),
            result("2026-07-01", "1,23"),
        ))
        assertEquals(listOf(0.0, 1234.5), points.map { it.value })
        assertEquals(listOf("2026-01-01", "2026-04-01"), points.map { it.record.targetDate.toString() })
    }

    @Test
    fun constantAndSinglePointSeriesHaveNonzeroVerticalRange() {
        listOf(listOf(result("2026-01-01", "0")), listOf(result("2026-01-01", "10"), result("2026-04-01", "10"))).forEach { records ->
            val points = labChartPoints(records)
            val bounds = labChartBounds(points)
            assertTrue(bounds.first < points.first().value)
            assertTrue(bounds.second > points.first().value)
            assertTrue(bounds.first.isFinite() && bounds.second.isFinite())
        }
    }

    @Test
    fun qualitativeResultsDoNotBecomeZeroValuedPlotPoints() {
        assertTrue(labChartPoints(listOf(result("2026-01-01", "(+-)"), result("2026-02-01", "不明"))).isEmpty())
    }

    private fun result(date: String, value: String) = LabResult(LocalDate.parse(date), "試験項目", value, "単位")
}
