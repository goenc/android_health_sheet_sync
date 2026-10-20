package com.goenc.healthsheetsync.health

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class BasalMetabolicRateHistoryTest {
    private val day = LocalDate.of(2026, 7, 23)
    private fun entry(date: LocalDate, value: Int) = ManualHealthRecord(
        "bmr|$date", ManualRecordType.BasalMetabolicRate, date.atStartOfDay(), "$value kcal/日", null)

    @Test fun selectsLatestEffectiveDateWithoutFutureOrInvalidatedValues() {
        val old = entry(day.minusDays(10), 1400)
        val current = entry(day, 1500)
        val future = entry(day.plusDays(1), 1600)
        assertEquals(1371, BasalMetabolicRateHistory.resolve(day.minusDays(11), 1371, listOf(old, current, future)))
        assertEquals(1400, BasalMetabolicRateHistory.resolve(day.minusDays(1), 1371, listOf(old, current, future)))
        assertEquals(1500, BasalMetabolicRateHistory.resolve(day, 1371, listOf(future, old, current)))
        assertEquals(1600, BasalMetabolicRateHistory.resolve(day.plusDays(1), 1371, listOf(current, old, future)))
        assertEquals(1400, BasalMetabolicRateHistory.resolve(day, 1371,
            listOf(old, current.copy(invalidatedAt = day.atTime(12, 0)), future)))
        assertEquals(1400, BasalMetabolicRateHistory.resolve(day, 1371, listOf(old, entry(day, 0))))
    }

    @Test fun recalculatesOnlyEnergyAndKeepsOriginalSnapshotForRemoval() {
        val original = DailyEnergySnapshot(day, 8000, 1371, 1.40224, 1922, day.plusDays(1).atStartOfDay())
        val changed = DailyEnergyCalculator.applyBasalMetabolicRateHistory(original, listOf(entry(day, 1500)))
        assertEquals(8000L, changed.steps)
        assertEquals(1500, changed.basalMetabolicRate)
        assertEquals(2103, changed.estimatedTotalKcal)
        assertEquals(original.finalizedAt, changed.finalizedAt)
        assertEquals(1371, original.basalMetabolicRate)
        assertEquals(original, DailyEnergyCalculator.applyBasalMetabolicRateHistory(original, emptyList()))
        assertEquals(original, DailyEnergyCalculator.applyBasalMetabolicRateHistory(original, listOf(entry(day.plusDays(1), 1500))))
    }

    @Test fun currentDayUsesHistoryAndMissingPastSnapshotStaysMissing() {
        val history = listOf(entry(day.minusDays(1), 1500), entry(day.plusDays(1), 1600))
        val current = DailyEnergyCalculator.resolveDisplay(day, day, 8000, 1371, null, history)
        assertEquals(1500, current?.basalMetabolicRate)
        assertEquals(2103, current?.estimatedTotalKcal)
        assertNull(DailyEnergyCalculator.resolveDisplay(day, day.plusDays(1), 8000, 1371, null, history))
    }
}
