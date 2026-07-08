package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.health.ManualRecordType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

class HealthUiFormattersTest {
    @Test
    fun bloodPressureDefaultsToMorningBeforeFivePm() {
        assertEquals("朝", ManualRecordType.BloodPressure.defaultManualTimeBand(LocalTime.of(16, 59)))
    }

    @Test
    fun bloodPressureDefaultsToNightFromFivePm() {
        assertEquals("夜", ManualRecordType.BloodPressure.defaultManualTimeBand(LocalTime.of(17, 0)))
    }

    @Test
    fun bloodPressureDefaultsToNightAtFivePmInJapanTime() {
        assertEquals("夜", ManualRecordType.BloodPressure.defaultManualTimeBand(Instant.parse("2026-07-09T08:00:00Z")))
    }
}
