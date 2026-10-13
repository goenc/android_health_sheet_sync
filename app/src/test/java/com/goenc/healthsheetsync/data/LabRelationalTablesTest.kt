package com.goenc.healthsheetsync.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabRelationalTablesTest {
    @Test
    fun itemIdIsStableForUtf8Name() {
        val first = labItemId("尿酸")
        assertEquals(first, labItemId("尿酸"))
        assertTrue(first.startsWith("lab_"))
        assertTrue(first != labItemId("尿素窒素"))
    }

    @Test
    fun parserJoinsItemsAndReconstructsNumericQualifiers() {
        val tables = parseLabRelationalTables(
            itemRows = listOf(
                listOf("itemId", "itemName", "unit", "initialReferenceRange"),
                listOf("lab_glucose", "血糖", "mg/dL", "男 3.6〜7.0"),
                listOf("lab_protein", "尿蛋白", "", ""),
            ),
            resultRows = listOf(
                listOf("targetDate", "itemId", "value", "valueQualifier"),
                listOf("2026-08-01", "lab_glucose", "1.20", "="),
                listOf("2026-08-02", "lab_glucose", "5.0", "<"),
                listOf("2026-08-03", "lab_glucose", "5.0", "<="),
                listOf("2026-08-04", "lab_glucose", "5.0", ">"),
                listOf("2026-08-05", "lab_glucose", "5.0", ">="),
                listOf("2026-08-06", "lab_protein", "(-)", "text"),
            ),
        )

        assertEquals(listOf("血糖", "尿蛋白"), tables.items.map { it.itemName })
        assertEquals(
            listOf("mg/dL", "mg/dL", "mg/dL", "mg/dL", "mg/dL", ""),
            tables.results.map { it.unit },
        )
        assertEquals(
            listOf("男 3.6〜7.0", "男 3.6〜7.0", "男 3.6〜7.0", "男 3.6〜7.0", "男 3.6〜7.0", ""),
            tables.results.map { it.referenceRange },
        )
        assertEquals(
            listOf("1.20", "5.0未満", "5.0以下", "5.0超", "5.0以上", "(-)"),
            tables.results.map { it.value },
        )
        assertEquals(listOf("", "", "", "", "", ""), tables.results.map { it.flag })
    }

    @Test
    fun serializerKeepsDecimalLexicalFormAndNormalizesValues() {
        val item = LabItem("lab_glucose", "血糖", "mg/dL")
        val records = listOf(
            result("2026-08-01", "1.20"),
            result("2026-08-02", "5.0未満"),
            result("2026-08-03", "5.0以下"),
            result("2026-08-04", "5.0超"),
            result("2026-08-05", "5.0以上"),
            result("2026-08-06", "(-)"),
        )

        assertEquals(
            listOf(
                listOf("targetDate", "itemId", "value", "valueQualifier"),
                listOf("2026-08-01", "lab_glucose", "1.20", "="),
                listOf("2026-08-02", "lab_glucose", "5.0", "<"),
                listOf("2026-08-03", "lab_glucose", "5.0", "<="),
                listOf("2026-08-04", "lab_glucose", "5.0", ">"),
                listOf("2026-08-05", "lab_glucose", "5.0", ">="),
                listOf("2026-08-06", "lab_glucose", "(-)", "text"),
            ),
            labResultRows(records, listOf(item)),
        )
    }

    @Test
    fun normalizeBuildsCommonUnitAndPreservesLegacyMetadata() {
        val records = listOf(
            result("2026-08-01", "1.20", unit = "mg/dL", referenceRange = "0〜2", flag = ""),
            result("2026-08-02", "(-)", unit = "", referenceRange = "", flag = "H"),
        )
        val tables = normalizeLabTables(records)

        assertEquals(
            listOf(LabItem(labItemId("血糖"), "血糖", "mg/dL", "0〜2")),
            tables.items,
        )
        assertEquals(listOf("mg/dL", "mg/dL"), tables.results.map { it.unit })
        assertEquals(listOf("0〜2", ""), tables.results.map { it.referenceRange })
        assertEquals(listOf("", "H"), tables.results.map { it.flag })
    }

    @Test
    fun parserRejectsOrphanBadQualifierDateAndNonnumericNumericValue() {
        val items = listOf(
            listOf("itemId", "itemName", "unit"),
            listOf("lab_glucose", "血糖", "mg/dL"),
        )
        fun parse(result: List<String>) = runCatching {
            parseLabRelationalTables(items, listOf(
                listOf("targetDate", "itemId", "value", "valueQualifier"), result,
            ))
        }
        assertTrue(parse(listOf("2026-08-01", "missing", "1.0", "=")).isFailure)
        assertTrue(parse(listOf("2026-08-01", "lab_glucose", "1.0", "?")).isFailure)
        assertTrue(parse(listOf("2026-02-30", "lab_glucose", "1.0", "=")).isFailure)
        assertTrue(parse(listOf("2026-08-01", "lab_glucose", "not-a-number", "=")).isFailure)
        assertTrue(parse(listOf("2026-08-01", "lab_glucose", "1.0", "text")).isSuccess)
    }

    @Test
    fun parserRejectsDuplicateItemsAndResults() {
        val duplicateId = runCatching {
            parseLabRelationalTables(
                listOf(
                    listOf("itemId", "itemName", "unit"),
                    listOf("lab_same", "血糖", "mg/dL"),
                    listOf("lab_same", "尿酸", "mg/dL"),
                ),
                emptyList(),
            )
        }
        assertTrue(duplicateId.isFailure)

        val duplicateResult = runCatching {
            parseLabRelationalTables(
                listOf(listOf("itemId", "itemName", "unit"), listOf("lab_a", "血糖", "")),
                listOf(
                    listOf("targetDate", "itemId", "value", "valueQualifier"),
                    listOf("2026-08-01", "lab_a", "1", "="),
                    listOf("2026-08-01", "lab_a", "2", "="),
                ),
            )
        }
        assertTrue(duplicateResult.isFailure)
    }

    @Test
    fun normalizeRejectsConflictingUnitsAndDuplicateDateItem() {
        assertTrue(runCatching {
            normalizeLabTables(listOf(
                result("2026-08-01", "1", unit = "mg/dL"),
                result("2026-08-02", "2", unit = "g/dL"),
            ))
        }.isFailure)
        assertTrue(runCatching {
            normalizeLabTables(listOf(
                result("2026-08-01", "1"),
                result("2026-08-01", "2"),
            ))
        }.isFailure)
    }

    @Test
    fun serializerRejectsUnknownItemAndDuplicateDateItem() {
        assertTrue(runCatching {
            labResultRows(listOf(result("2026-08-01", "1")), emptyList())
        }.isFailure)
        val item = LabItem("lab_glucose", "血糖", "")
        assertTrue(runCatching {
            labResultRows(
                listOf(result("2026-08-01", "1"), result("2026-08-01", "2")),
                listOf(item),
            )
        }.isFailure)
    }

    @Test
    fun emptyTablesRoundTripWithHeaders() {
        val items = emptyList<LabItem>()
        val records = emptyList<LabResult>()
        assertEquals(
            listOf(listOf("itemId", "itemName", "unit", "initialReferenceRange")),
            labItemRows(items),
        )
        assertEquals(
            listOf(listOf("targetDate", "itemId", "value", "valueQualifier")),
            labResultRows(records, items),
        )
        assertEquals(LabTables(items, records), parseLabRelationalTables(labItemRows(items), labResultRows(records, items)))
    }

    @Test
    fun ambiguousInitialReferenceRangeIsLeftBlank() {
        val tables = normalizeLabTables(listOf(
            result("2026-08-01", "1", referenceRange = "0〜2"),
            result("2026-08-02", "2", referenceRange = "0〜3"),
        ))
        assertEquals("", tables.items.single().initialReferenceRange)
    }

    private fun result(
        date: String,
        value: String,
        unit: String = "",
        referenceRange: String = "",
        flag: String = "",
    ) = LabResult(
        targetDate = LocalDate.parse(date),
        itemName = "血糖",
        value = value,
        unit = unit,
        referenceRange = referenceRange,
        flag = flag,
    )
}
