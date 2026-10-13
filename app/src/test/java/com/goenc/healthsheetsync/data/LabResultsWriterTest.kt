package com.goenc.healthsheetsync.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LabResultsWriterTest {
    private val record = LabResult(LocalDate.of(2026, 10, 1), "試験項目", "0.00", "mg/dL", "0～1", "L")

    @Test
    fun createsHeadersForEmptyTableAndKeepsValuesAsStrings() {
        val rows = labResultsAppendRows(null, listOf(record))
        assertEquals(listOf("targetDate", "itemName", "value", "unit", "referenceRange", "flag"), rows.first())
        assertEquals(listOf("2026-10-01", "試験項目", "0.00", "mg/dL", "0～1", "L"), rows.last())
    }

    @Test
    fun honorsExistingColumnOrderInsteadOfOverwritingHeaders() {
        val headers = listOf("value", "itemName", "targetDate", "flag", "referenceRange", "unit")
        assertEquals(listOf(listOf("0.00", "試験項目", "2026-10-01", "L", "0～1", "mg/dL")),
            labResultsAppendRows(headers, listOf(record)))
    }

    @Test
    fun refusesToDropOptionalValuesOrWriteUnknownColumns() {
        assertTrue(runCatching { labResultsAppendRows(listOf("targetDate", "itemName", "value"), listOf(record)) }.isFailure)
        assertTrue(runCatching { labResultsAppendRows(listOf("targetDate", "itemName", "value", "unknown"), listOf(record)) }.isFailure)
        assertEquals(listOf(listOf("2026-10-01", "試験項目", "(-)")), labResultsAppendRows(
            listOf("targetDate", "itemName", "value"), listOf(record.copy(value = "(-)", unit = "", referenceRange = "", flag = ""))))
    }

    @Test
    fun rereadsSheetsBeforeSaveAndPostsOnlyNewRows() = runBlocking {
        val existing = record
        val addition = record.copy(targetDate = LocalDate.of(2026, 10, 2), itemName = "尿蛋白", value = "(-)", unit = "", referenceRange = "", flag = "")
        var fetchCount = 0
        val posted = mutableListOf<List<List<String>>>()
        val writer = testWriter(
            rows = sheetRows(listOf(existing)),
            onFetch = { fetchCount++ },
            onPost = { _, values -> posted += values },
        )

        val result = writer.append("token", listOf(existing, addition))

        assertEquals(LabResultsSaveResult(1, 1), result)
        assertEquals(1, fetchCount)
        assertEquals(listOf(listOf("2026-10-02", "尿蛋白", "(-)", "", "", "")), posted.single())
    }

    @Test
    fun doesNotPostWhenLatestRowsConflict() = runBlocking {
        val changed = record.copy(value = "0.01")
        var postCount = 0
        val writer = testWriter(
            rows = sheetRows(listOf(record)),
            onPost = { _, _ -> postCount++ },
        )

        val error = runCatching { writer.append("token", listOf(changed)) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, postCount)
        assertTrue(error?.message.orEmpty().contains("同日・同項目"))
    }

    @Test
    fun postsRowsInExistingRawColumnOrder() = runBlocking {
        val headers = listOf("value", "itemName", "targetDate", "flag", "referenceRange", "unit")
        val posted = mutableListOf<List<List<String>>>()
        val writer = testWriter(
            rows = sheetRows(emptyList(), headers),
            onPost = { _, values -> posted += values },
        )

        writer.append("token", listOf(record))

        assertEquals(listOf(listOf("0.00", "試験項目", "2026-10-01", "L", "0～1", "mg/dL")), posted.single())
    }

    @Test
    fun propagatesPostFailureAfterValidationAndComparison() = runBlocking {
        val failure = IllegalStateException("テスト用POST失敗")
        val writer = testWriter(
            rows = sheetRows(emptyList()),
            onPost = { _, _ -> throw failure },
        )

        val actual = runCatching { writer.append("token", listOf(record)) }.exceptionOrNull()

        assertTrue(actual is IllegalStateException)
        assertEquals(failure.message, actual?.message)
    }

    @Test
    fun rejectsInvalidEditedFlagBeforeFetchingOrPosting() = runBlocking {
        var fetchCount = 0
        var postCount = 0
        val writer = testWriter(
            rows = sheetRows(emptyList()),
            onFetch = { fetchCount++ },
            onPost = { _, _ -> postCount++ },
        )

        val error = runCatching { writer.append("token", listOf(record.copy(flag = "X"))) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, fetchCount)
        assertEquals(0, postCount)
    }

    private fun testWriter(
        rows: JSONArray,
        onFetch: () -> Unit = {},
        onPost: suspend (String, List<List<String>>) -> Unit,
    ): LabResultsWriter = LabResultsWriter(
        fetchRows = {
            onFetch()
            rows
        },
        appendRows = onPost,
    )

    private fun sheetRows(records: List<LabResult>, headers: List<String> = STANDARD_HEADERS): JSONArray {
        val rows = JSONArray().put(JSONArray(headers))
        records.forEach { item ->
            rows.put(JSONArray(listOf(item.targetDate.toString(), item.itemName, item.value,
                item.unit, item.referenceRange, item.flag)))
        }
        return rows
    }

    private companion object {
        val STANDARD_HEADERS = listOf("targetDate", "itemName", "value", "unit", "referenceRange", "flag")
    }
}
