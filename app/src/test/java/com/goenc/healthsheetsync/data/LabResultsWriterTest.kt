package com.goenc.healthsheetsync.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LabResultsWriterTest {
    private val record = LabResult(LocalDate.of(2026, 10, 1), "試験項目", "0.00", "mg/dL")

    @Test fun rereadsAndPostsOnlyNewResultsAndNewItemsTogether() = runBlocking {
        val existing = normalizeLabTables(listOf(record))
        val addition = record.copy(targetDate = record.targetDate.plusDays(1), itemName = "新項目", value = "(-)")
        var fetchCount = 0
        var newItems = emptyList<LabItem>()
        var newRows = emptyList<List<String>>()
        val writer = LabResultsWriter({ fetchCount++; existing }, { _, items, rows -> newItems = items; newRows = rows })
        assertEquals(LabResultsSaveResult(1, 1), writer.append("token", listOf(record, addition)))
        assertEquals(1, fetchCount)
        assertEquals(listOf("新項目"), newItems.map { it.itemName })
        assertEquals(listOf(listOf("2026-10-02", labItemId("新項目"), "(-)", "text")), newRows)
    }

    @Test fun existingItemUsesMasterIdAndDoesNotRepeatUnit() = runBlocking {
        val item = LabItem("existing-id", record.itemName, "mg/dL")
        val existing = LabTables(listOf(item), listOf(record))
        var newRows = emptyList<List<String>>()
        val writer = LabResultsWriter({ existing }, { _, items, rows -> assertTrue(items.isEmpty()); newRows = rows })
        writer.append("token", listOf(record.copy(targetDate = record.targetDate.plusDays(1))))
        assertEquals(listOf(listOf("2026-10-02", "existing-id", "0.00", "=")), newRows)
    }

    @Test fun conflictDoesNotWriteAnything() = runBlocking {
        var posts = 0
        val writer = LabResultsWriter({ normalizeLabTables(listOf(record)) }, { _, _, _ -> posts++ })
        assertTrue(runCatching { writer.append("token", listOf(record.copy(value = "1.0"))) }.isFailure)
        assertEquals(0, posts)
    }

    @Test fun duplicateDoesNotWriteAnything() = runBlocking {
        val writer = LabResultsWriter({ normalizeLabTables(listOf(record)) }, { _, _, _ -> fail("重複を追記") })
        assertEquals(LabResultsSaveResult(0, 1), writer.append("token", listOf(record)))
    }

    @Test fun decimalFormattingDoesNotCreateFalseConflict() = runBlocking {
        val writer = LabResultsWriter({ normalizeLabTables(listOf(record)) }, { _, _, _ -> fail("同値を追記") })
        assertEquals(LabResultsSaveResult(0, 1), writer.append("token", listOf(record.copy(value = "0"))))
        assertTrue(labResultValuesEqual("<5.0", "5未満"))
        assertFalse(labResultValuesEqual("5未満", "5以下"))
    }

    @Test fun invalidValueFailsBeforeRead() = runBlocking {
        var fetched = false
        val writer = LabResultsWriter({ fetched = true; normalizeLabTables(emptyList()) }, { _, _, _ -> fail() })
        assertTrue(runCatching { writer.append("token", listOf(record.copy(value = ""))) }.isFailure)
        assertFalse(fetched)
    }

    @Test fun failedAtomicWritePropagates() = runBlocking {
        val writer = LabResultsWriter({ normalizeLabTables(emptyList()) }, { _, _, _ -> error("保存失敗") })
        assertEquals("保存失敗", runCatching { writer.append("token", listOf(record)) }.exceptionOrNull()?.message)
    }
}
