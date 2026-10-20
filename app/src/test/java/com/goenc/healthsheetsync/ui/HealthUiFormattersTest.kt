package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.health.ManualRecordType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

class HealthUiFormattersTest {
    @Test
    fun basalMetabolicRateInputUsesPositiveIntegerAndNoTimeBand() {
        val type = ManualRecordType.BasalMetabolicRate
        assertEquals("基礎代謝 kcal/日", type.inputLabels().first)
        assertEquals("1500 kcal/日", type.formatManualValue("1500", ""))
        assertEquals(null, type.formatManualValue("0", ""))
        assertEquals(null, type.formatManualValue("-1", ""))
        assertEquals(null, type.formatManualValue("1500.5", ""))
        assertEquals(emptyList<String>(), type.manualInputTimeBandOptions())
        assertEquals(LocalTime.MIDNIGHT, type.manualInputTime("朝"))
    }

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
    fun formatsNeckInputAsCentimeters() {
        assertEquals("首回り cm", ManualRecordType.Neck.inputLabels().first)
        assertEquals("40.6 cm", ManualRecordType.Neck.formatManualValue("40.64", ""))
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
