package com.goenc.healthsheetsync.ui

import com.goenc.healthsheetsync.data.LabResult
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class LabResultRowsTest {
    private val oldDate = LocalDate.of(2026, 1, 1)
    private val newDate = LocalDate.of(2026, 4, 1)

    @Test
    fun allDatesHaveSameItemOrderAndMissingMeasurementsAreDashes() {
        val records = listOf(
            LabResult(newDate, "項目B", "0", "単位", "0～10", "L"),
            LabResult(newDate, "項目C", "2"),
            LabResult(oldDate, "項目A", "10", "単位", "1～9", "H"),
            LabResult(oldDate, "項目B", "3", "単位"),
        )
        val oldRows = dailyLabResultRows(records, oldDate)
        val newRows = dailyLabResultRows(records, newDate)
        assertEquals(listOf("項目A", "項目B", "項目C"), oldRows.map { it.itemName })
        assertEquals(oldRows.map { it.itemName }, newRows.map { it.itemName })
        assertEquals(listOf("—", "0", "2"), newRows.map { it.value })
        assertEquals(LabResult(newDate, "項目A", "—", "単位"), newRows.first())
        assertEquals("L", newRows[1].flag)
        assertEquals("—", oldRows.last().value)
        assertEquals(4, records.size)
    }

    @Test
    fun unitChangesDoNotReuseMeasurementsFromAnotherUnitOrDate() {
        val records = listOf(LabResult(oldDate, "項目A", "10", "単位1"), LabResult(newDate, "項目A", "1", "単位2"))
        val rows = dailyLabResultRows(records, newDate)
        assertEquals(listOf("—", "1"), rows.map { it.value })
        assertEquals("単位2", rows.last().unit)
    }
}
