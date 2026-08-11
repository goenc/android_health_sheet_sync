package com.goenc.healthsheetsync.health

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthTimeBandTest {
    @Test
    fun mapsBoundaryHoursToHealthTimeBands() {
        assertEquals("夜", atHour(3).toHealthTimeBand())
        assertEquals("朝", atHour(4).toHealthTimeBand())
        assertEquals("朝", atHour(11).toHealthTimeBand())
        assertEquals("昼", atHour(12).toHealthTimeBand())
        assertEquals("昼", atHour(17).toHealthTimeBand())
        assertEquals("夜", atHour(18).toHealthTimeBand())
    }

    private fun atHour(hour: Int): LocalDateTime = LocalDateTime.of(2026, 8, 11, hour, 0)
}
