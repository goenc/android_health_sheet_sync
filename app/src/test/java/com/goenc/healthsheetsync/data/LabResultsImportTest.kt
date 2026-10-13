package com.goenc.healthsheetsync.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabResultsImportTest {
    @Test
    fun parsesArrayAndJsonCodeFenceWithoutChangingMeasurementText() {
        val json = """
            ```json
            [
              {"targetDate":"2026-10-01","itemName":"血糖","value":"<= 5.00","unit":"mmol/L","referenceRange":"3.90～6.10","flag":"H"},
              {"targetDate":"2026-09-01","itemName":"尿蛋白","value":"(-)","unit":"","referenceRange":"","flag":""}
            ]
            ```
        """.trimIndent()

        val records = parseLabResultsImport(json)

        assertEquals(2, records.size)
        assertEquals("<= 5.00", records.first().value)
        assertEquals("(-)", records.last().value)
        assertEquals("H", records.first().flag)
    }

    @Test
    fun rejectsEmptyMalformedNonStringMissingAndInvalidRows() {
        val valid = jsonRecord()
        val invalidInputs = listOf(
            "",
            "[]",
            "{}",
            "[1]",
            valid.replace("\"value\":\"1.20\"", "\"value\":1.20"),
            valid.replace("\"value\":\"1.20\"", "\"value\":\"\""),
            valid.replace("\"targetDate\":\"2026-10-01\"", "\"targetDate\":\"2026-02-30\""),
            valid.replace("\"flag\":\"\"", "\"flag\":\"X\""),
            valid.replace(",\"unit\":\"mg/dL\"", ""),
        )

        invalidInputs.forEach { input ->
            assertTrue("expected invalid input: $input", runCatching { parseLabResultsImport(input) }.isFailure)
        }
    }

    @Test
    fun rejectsSameDateItemAndUnitDuplicate() {
        val duplicate = "[${jsonRecord()},${jsonRecord()}]"

        val error = runCatching { parseLabResultsImport(duplicate) }.exceptionOrNull()

        assertTrue(error is LabResultsImportException)
        assertEquals(1, (error as LabResultsImportException).rowNumber)
        assertEquals("血糖", error.itemName)
        assertTrue(error.message.orEmpty().contains("重複"))
    }

    @Test
    fun plansAdditionsDuplicatesAndConflictsAgainstLatestRows() {
        val same = result(value = "1.20")
        val changed = result(value = "1.30")
        val newRecord = result(date = "2026-10-02", item = "尿蛋白", value = "(-)", unit = "")
        val unitConflict = result(unit = "mmol/L")

        val plan = planLabResultsImport(
            incoming = listOf(same, changed, newRecord, unitConflict),
            existing = listOf(same, same.copy(value = "1.10")),
        )

        assertEquals(listOf(newRecord), plan.additions)
        assertTrue(plan.duplicates.isEmpty())
        assertEquals(3, plan.conflicts.size)
        assertFalse(plan.canAppend)
        assertTrue(plan.conflicts.all { it.reason.isNotBlank() })
    }

    @Test
    fun exactSingleExistingRowIsExcludedAsDuplicate() {
        val record = result()

        val plan = planLabResultsImport(listOf(record), listOf(record))

        assertTrue(plan.additions.isEmpty())
        assertEquals(listOf(record), plan.duplicates)
        assertTrue(plan.conflicts.isEmpty())
        assertTrue(plan.canAppend)
    }

    @Test
    fun promptIncludesExistingNamesAndSafetyRules() {
        val prompt = buildLabResultsPrompt(listOf("血糖", "血糖", " 尿蛋白 "))

        assertTrue(prompt.contains("血糖"))
        assertTrue(prompt.contains("尿蛋白"))
        assertTrue(prompt.contains("JSON配列"))
        assertTrue(prompt.contains("JSONのみ"))
        assertTrue(prompt.contains("すべて文字列"))
        assertTrue(prompt.contains("定性値"))
        assertTrue(prompt.contains("不等号"))
        assertTrue(prompt.contains("末尾0"))
        assertTrue(prompt.contains("未読"))
        assertTrue(prompt.contains("JSON配列から除外"))
        assertTrue(prompt.contains("同じtargetDate・itemName・unit"))
        assertTrue(prompt.contains("別の検査を統合しない"))
        assertTrue(prompt.contains("患者名"))
        assertTrue(prompt.contains("カルテ番号"))
    }

    private fun result(
        date: String = "2026-10-01",
        item: String = "血糖",
        value: String = "1.20",
        unit: String = "mg/dL",
        referenceRange: String = "0～2",
        flag: String = "",
    ): LabResult = LabResult(
        targetDate = LocalDate.parse(date),
        itemName = item,
        value = value,
        unit = unit,
        referenceRange = referenceRange,
        flag = flag,
    )

    private fun jsonRecord(
        date: String = "2026-10-01",
        item: String = "血糖",
        value: String = "1.20",
        unit: String = "mg/dL",
        referenceRange: String = "0～2",
        flag: String = "",
    ): String = """
        {"targetDate":"$date","itemName":"$item","value":"$value","unit":"$unit","referenceRange":"$referenceRange","flag":"$flag"}
    """.trimIndent()
}
