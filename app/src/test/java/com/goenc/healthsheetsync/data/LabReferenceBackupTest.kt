package com.goenc.healthsheetsync.data

import org.junit.Assert.*
import org.junit.Test

class LabReferenceBackupTest {
    private val item = LabItem("test", "試験項目", "mg/dL")
    @Test fun restoresRangeAndDisabledSettingWithItemReference() {
        val range = parseLabReferenceBackup(listOf(LAB_REFERENCE_BACKUP_HEADERS,
            listOf("test", "0.1", "1.0", "1", "0", "0")), listOf(item)).getValue(item.itemName)
        assertEquals("0.1以上〜1.0未満", range?.displayText)
        val disabled = parseLabReferenceBackup(listOf(LAB_REFERENCE_BACKUP_HEADERS,
            listOf("test", "", "", "1", "1", "1")), listOf(item))
        assertTrue(disabled.containsKey(item.itemName))
        assertNull(disabled[item.itemName])
    }
    @Test fun rejectsOrphansDuplicateItemsAndInvalidFlags() {
        val valid = listOf("test", "0", "1", "1", "1", "0")
        assertTrue(runCatching { parseLabReferenceBackup(listOf(LAB_REFERENCE_BACKUP_HEADERS, valid, valid), listOf(item)) }.isFailure)
        assertTrue(runCatching { parseLabReferenceBackup(listOf(LAB_REFERENCE_BACKUP_HEADERS, valid), emptyList()) }.isFailure)
        assertTrue(runCatching { parseLabReferenceBackup(listOf(LAB_REFERENCE_BACKUP_HEADERS, valid.mapIndexed { i,v -> if (i==3) "2" else v }), listOf(item)) }.isFailure)
    }
}
