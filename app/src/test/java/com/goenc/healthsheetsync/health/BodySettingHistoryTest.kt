package com.goenc.healthsheetsync.health

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class BodySettingHistoryTest {
    private val day = LocalDate.of(2026, 10, 20)
    private fun entry(type: ManualRecordType, date: LocalDate, text: String) =
        ManualHealthRecord("${type.name}|$date", type, date.atStartOfDay(), text, null)
    private fun at(date: LocalDate, legacy: List<DailyBodySetting>, records: List<ManualHealthRecord>) =
        BodySettingHistory.timeline(legacy, records).filter { !it.targetDate.isAfter(date) }.maxBy { it.targetDate }

    @Test fun defaultsAndIndependentFieldsRespectEffectiveDates() {
        val height = entry(ManualRecordType.Height, day, "170 cm")
        val intake = entry(ManualRecordType.AverageIntake, day.plusDays(2), "1800 kcal/日")
        val records = listOf(height, intake)
        assertEquals(168.2, at(day.minusDays(1), emptyList(), records).heightCm, 0.0)
        assertEquals(170.0, at(day, emptyList(), records).heightCm, 0.0)
        assertEquals(1600, at(day.plusDays(1), emptyList(), records).averageIntakeKcal)
        assertEquals(1800, at(day.plusDays(2), emptyList(), records).averageIntakeKcal)
        assertEquals(170.0, at(day.plusDays(2), emptyList(), records).heightCm, 0.0)
        assertEquals(168.2, at(day, emptyList(), listOf(height.copy(invalidatedAt = day.atStartOfDay()))).heightCm, 0.0)
        assertEquals(1, BodySettingHistory.timeline(emptyList(), emptyList()).size)
        assertEquals(LocalDate.MIN, BodySettingHistory.timeline(emptyList(), emptyList()).single().targetDate)
    }

    @Test fun legacyRecordsRemainEffectiveUntilNewFieldUpdates() {
        val legacy = listOf(DailyBodySetting(day.minusDays(1), 169.0, 1650, day.atStartOfDay()))
        val height = entry(ManualRecordType.Height, day, "171 cm")
        assertEquals(169.0, at(day.minusDays(1), legacy, listOf(height)).heightCm, 0.0)
        assertEquals(171.0, at(day, legacy, listOf(height)).heightCm, 0.0)
        assertEquals(1650, at(day, legacy, listOf(height)).averageIntakeKcal)
        assertEquals(169.0, at(day, legacy, emptyList()).heightCm, 0.0)
    }

    @Test fun rejectsInvalidNumbersWithoutPoisoningHistory() {
        listOf("0 cm", "-1 cm", "NaN cm", "Infinity cm").forEach {
            assertNull(BodySettingHistory.numericValue(ManualRecordType.Height, it))
        }
        assertNull(BodySettingHistory.numericValue(ManualRecordType.AverageIntake, "1600.5 kcal/日"))
        assertEquals(1600, at(day, emptyList(), listOf(entry(ManualRecordType.AverageIntake, day, "0 kcal/日"))).averageIntakeKcal)
    }
}
