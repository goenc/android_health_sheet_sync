package com.goenc.healthsheetsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabResultsTest {
    @Test
    fun readsReorderedColumnsQualitativeValuesAndPastDates() {
        val records = parseLabResults(listOf(
            listOf("value", "itemName", "targetDate", "unit", "referenceRange", "flag"),
            listOf("(-)", "尿蛋白", "2025-03-02"),
            emptyList(),
            listOf("0.00", "試験項目", "2026-10-01", "mg/dL", "0～1", "L"),
        ))
        assertEquals(listOf("2026-10-01", "2025-03-02"), records.map { it.targetDate.toString() })
        assertEquals("0.00", records.first().value)
        assertEquals("L", records.first().flag)
        assertEquals("(-)", records.last().value)
        assertEquals("", records.last().unit)
    }

    @Test
    fun malformedRowFailsInsteadOfSilentlyLosingHistory() {
        val error = runCatching { parseLabResults(listOf(
            listOf("targetDate", "itemName", "value"),
            listOf("2026-02-30", "試験項目", "1"),
        )) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("2行目"))
    }

    @Test
    fun rejectsMissingRequiredHeadersAndMissingValues() {
        assertTrue(runCatching { parseLabResults(listOf(listOf("targetDate", "value"))) }.isFailure)
        assertTrue(runCatching { parseLabResults(listOf(
            listOf("targetDate", "itemName", "value"), listOf("2026-01-01", "試験項目"),
        )) }.isFailure)
        assertEquals(emptyList<LabResult>(), parseLabResults(emptyList()))
    }

    @Test
    fun phoneMirrorNeverUploadsOrClearsChatGptLabResults() {
        val tables = SpreadsheetUploadTableBuilder.build(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        assertFalse(tables.any { it.sheetName == "labResults" })
    }
}
