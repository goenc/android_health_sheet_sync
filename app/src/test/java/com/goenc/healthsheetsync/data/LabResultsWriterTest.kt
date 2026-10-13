package com.goenc.healthsheetsync.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LabResultsWriterTest {
    private val record = LabResult(LocalDate.of(2026, 10, 1), "試験項目", "0.00", "mg/dL")
    private val tables = normalizeLabTables(listOf(record))
    private val references = listOf(
        LAB_REFERENCE_BACKUP_HEADERS,
        listOf(labItemId("試験項目"), "", "", "1", "1", "0"),
    )

    @Test fun saveLocalWritesOnlyToLocalStore() = runBlocking {
        val events = mutableListOf<String>()
        val writer = writer(
            saveLocalRecords = {
                events += "local"
                LabResultsSaveResult(1, 0)
            },
            loadSnapshot = {
                events += "load"
                snapshot()
            },
            uploadSnapshot = { _, _ -> events += "upload" },
        )

        assertEquals(LabResultsSaveResult(1, 0), writer.saveLocal(listOf(record)))
        assertEquals(listOf("local"), events)
    }

    @Test fun backupIsNotAutomaticAndSendsOnlyWhenCoordinatorCallsIt() = runBlocking {
        var localSaves = 0
        val uploads = mutableListOf<LabBackupSnapshot>()
        val writer = writer(
            saveLocalRecords = {
                localSaves++
                LabResultsSaveResult(1, 0)
            },
            uploadSnapshot = { _, snapshot -> uploads += snapshot },
        )

        writer.saveLocal(listOf(record))
        assertEquals(1, localSaves)
        assertTrue(uploads.isEmpty())

        assertTrue(writer.backupIfNeeded("token"))
        assertEquals(1, uploads.size)
    }

    @Test fun failedUploadKeepsRevisionPending() = runBlocking {
        var backedUpRevision = 0L
        var attempts = 0
        val writer = writer(
            loadSnapshot = { snapshot(revision = 1, backedUpRevision = backedUpRevision) },
            uploadSnapshot = { _, _ ->
                attempts++
                error("通信失敗")
            },
            markBackedUp = { backedUpRevision = it },
        )

        assertTrue(runCatching { writer.backupIfNeeded("token") }.isFailure)
        assertEquals(0L, backedUpRevision)
        assertEquals(1, attempts)
        assertTrue(runCatching { writer.backupIfNeeded("token") }.isFailure)
        assertEquals(2, attempts)
    }

    @Test fun retryUsesTheSameSnapshotAndDoesNotAppendDuplicates() = runBlocking {
        var backedUpRevision = 0L
        val payloads = mutableListOf<LabBackupSnapshot>()
        val writer = writer(
            loadSnapshot = { snapshot(revision = 4, backedUpRevision = backedUpRevision) },
            uploadSnapshot = { _, payload -> payloads += payload },
            markBackedUp = { backedUpRevision = it },
        )

        assertTrue(writer.backupIfNeeded("token"))
        assertEquals(1, payloads.size)
        assertEquals(4L, backedUpRevision)
        assertFalse(writer.backupIfNeeded("token"))
        assertEquals(1, payloads.size)
    }

    @Test fun acknowledgementOfSentRevisionDoesNotAcknowledgeConcurrentNewRevision() = runBlocking {
        var currentRevision = 1L
        var backedUpRevision = 0L
        val payloads = mutableListOf<LabBackupSnapshot>()
        val writer = writer(
            loadSnapshot = { snapshot(revision = currentRevision, backedUpRevision = backedUpRevision) },
            uploadSnapshot = { _, payload ->
                payloads += payload
                currentRevision = 2L
            },
            markBackedUp = { sentRevision ->
                // 実DBもこの世代条件を満たす場合だけ確認済み世代を進める。
                if (sentRevision == currentRevision) backedUpRevision = sentRevision
            },
        )

        assertTrue(writer.backupIfNeeded("token"))
        assertEquals(0L, backedUpRevision)
        assertEquals(1, payloads.size)
        assertEquals(1L, payloads.single().revision)

        assertTrue(writer.backupIfNeeded("token"))
        assertEquals(2, payloads.size)
        assertEquals(2L, payloads.last().revision)
        assertEquals(2L, backedUpRevision)
    }

    @Test fun cleanBackupDoesNotCallHttp() = runBlocking {
        var uploads = 0
        val writer = writer(
            loadSnapshot = { snapshot(revision = 5, backedUpRevision = 5) },
            uploadSnapshot = { _, _ -> uploads++ },
        )

        assertFalse(writer.backupIfNeeded("token"))
        assertEquals(0, uploads)
    }

    @Test fun localFailureDoesNotAttemptBackup() = runBlocking {
        var uploads = 0
        val writer = writer(
            saveLocalRecords = { error("SQLite保存失敗") },
            uploadSnapshot = { _, _ -> uploads++ },
        )

        assertTrue(runCatching { writer.saveLocal(listOf(record)) }.isFailure)
        assertEquals(0, uploads)
    }

    private fun writer(
        saveLocalRecords: (List<LabResult>) -> LabResultsSaveResult = { LabResultsSaveResult(0, 0) },
        loadSnapshot: () -> LabBackupSnapshot = { snapshot() },
        uploadSnapshot: suspend (String, LabBackupSnapshot) -> Unit = { _, _ -> },
        markBackedUp: (Long) -> Unit = {},
    ): LabResultsWriter = LabResultsWriter(
        saveLocalRecords = saveLocalRecords,
        loadSnapshot = loadSnapshot,
        uploadSnapshot = uploadSnapshot,
        markBackedUp = markBackedUp,
    )

    private fun snapshot(revision: Long = 1, backedUpRevision: Long = 0): LabBackupSnapshot =
        LabBackupSnapshot(tables, references, revision, backedUpRevision)
}
