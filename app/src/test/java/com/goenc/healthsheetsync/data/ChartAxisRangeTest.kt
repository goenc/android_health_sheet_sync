package com.goenc.healthsheetsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartAxisRangeTest {
    @Test
    fun invalidRangesCannotBeSaved() {
        listOf("" to "10", "text" to "10", "10" to "10", "20" to "10",
            "-1" to "10", "NaN" to "10", "0" to "Infinity").forEach { (min, max) ->
            assertNull(ChartAxisRange.parse(min, max))
        }
    }

    @Test
    fun nonZeroMinimumAndDecimalSpanMapToCorrectAxisPositions() {
        val range = ChartAxisRange.parse(" 5.1 ", "5.3")!!
        assertEquals(0.0, range.ratio(5.1), 0.000001)
        assertEquals(0.5, range.ratio(5.2), 0.000001)
        assertEquals(1.0, range.ratio(5.3), 0.000001)
        val steps = ChartAxisRange.parse("1000", "21000")!!
        assertEquals(0.5, steps.ratio(11000.0), 0.000001)
    }
}
