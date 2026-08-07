package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.health.ManualRecordType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

class HealthUiFormattersTest {
    @Test
    fun formatsEnergyValuesWithFixedLocaleAndGrouping() {
        assertEquals("8,000", formatIntegerWithGrouping(8_000L))
        assertEquals("1,371", formatIntegerWithGrouping(1_371))
        assertEquals("1,922", formatIntegerWithGrouping(1_922))
    }

    @Test
    fun formatsPalToThreeDecimalPlaces() {
        assertEquals("1.300", formatPal(1.29959))
    }

    @Test
    fun formatsDistanceInKilometers() {
        assertEquals("6.5km", formatDistanceMeters(6_543.0))
        assertEquals("-", formatDistanceMeters(null))
    }

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
