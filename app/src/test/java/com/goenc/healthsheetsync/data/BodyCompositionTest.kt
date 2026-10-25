package com.goenc.healthsheetsync.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking

class BodyCompositionTest {
    private fun measurement(time: String? = "09:00", value: String = "20.0", device: String = "試験機A") =
        BodyCompositionMeasurement(targetDate = LocalDate.of(2026, 1, 2), measuredTime = time?.let(LocalTime::parse),
            deviceModel = device, values = listOf(BodyCompositionValue("bodyFatPercent", value, "%")))

    @Test fun importingUsesPrintedDateAndPreservesDecimalPlacesAndUnknownTime() {
        val parsed = parseBodyCompositionImport("""{"type":"bodyComposition","measurements":[{
            "targetDate":"2025-01-02","measuredTime":null,"deviceModel":"試験機","values":[
            {"metricId":"筋肉量","value":"45.00","unit":"kg"},
            {"metricId":"weight","value":"65.1","unit":"kg"},
            {"metricId":"身長","value":"170","unit":"cm"},
            {"metricId":"basalMetabolicRate","value":"1500","unit":"kcal"}]}]}""").single()
        assertEquals(LocalDate.of(2025, 1, 2), parsed.targetDate)
        assertNull(parsed.measuredTime)
        assertEquals(listOf("muscleMass", "basalMetabolicRate"), parsed.values.map { it.metricId })
        assertEquals("45.00", parsed.values.first().value)
        assertEquals("kcal/日", parsed.values.last().unit)
    }

    @Test fun wrongTypeWrongUnitsAndUnreadableValuesAreRejected() {
        assertTrue(runCatching { parseBodyCompositionImport("[]") }.isFailure)
        assertTrue(runCatching { parseBodyCompositionImport("""{"type":"blood","measurements":[]}""") }.isFailure)
        val input = measurement()
        listOf(input.copy(values = listOf(BodyCompositionValue("bodyFatPercent", "20", "kg"))),
            input.copy(values = listOf(BodyCompositionValue("muscleMass", "不明", "kg"))),
            input.copy(values = listOf(BodyCompositionValue("bodyFatPercent", "101", "%"))),
            input.copy(values = listOf(BodyCompositionValue("bodyAge", "40.5", "才"))),
            input.copy(values = input.values + input.values)).forEach {
            assertTrue(runCatching { validateBodyCompositionMeasurement(it) }.isFailure)
        }
    }

    @Test fun duplicateComparisonIgnoresDecimalScaleAndGeneratedId() {
        val existing = measurement()
        val parsedAgain = measurement(value = "20.00").copy(source = "csv")
        val plan = planBodyCompositionImport(listOf(parsedAgain), listOf(existing))
        assertEquals(1, plan.duplicates.size)
        assertTrue(plan.additions.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test fun sameDayDifferentTimesAreSeparateButUnknownTimeRequiresConfirmation() {
        val existing = measurement()
        val plan = planBodyCompositionImport(listOf(measurement("10:00"), measurement(null)), listOf(existing))
        assertEquals(1, plan.additions.size)
        assertEquals(1, plan.conflicts.size)
    }

    @Test fun differentValueOrReusedIdCannotOverwriteAnExistingMeasurement() {
        val existing = measurement()
        assertEquals(1, planBodyCompositionImport(listOf(measurement(value = "21")), listOf(existing)).conflicts.size)
        assertEquals(1, planBodyCompositionImport(listOf(measurement("11:00").copy(id = existing.id)), listOf(existing)).conflicts.size)
    }

    @Test fun duplicateIncomingRowsAreNotAddedTwice() {
        val input = measurement()
        val plan = planBodyCompositionImport(listOf(input, input.copy(id = java.util.UUID.randomUUID().toString())), emptyList())
        assertEquals(1, plan.additions.size)
        assertEquals(1, plan.duplicates.size)
    }

    @Test fun relationalRoundTripPreservesIdsTimesUnitsSourcesAndDecimals() {
        val inputs = listOf(measurement(), measurement("10:30", "21.00").copy(source = "manual"))
        assertEquals(inputs, parseBodyCompositionTables(bodyMeasurementRows(inputs), bodyValueRows(inputs)))
        assertEquals(emptyList<BodyCompositionMeasurement>(), parseBodyCompositionTables(emptyList(), emptyList()))
    }

    @Test fun orphanDuplicateAndMissingValueRowsAreRejected() {
        val input = measurement()
        val headers = bodyMeasurementRows(listOf(input))
        val values = bodyValueRows(listOf(input))
        assertTrue(runCatching { parseBodyCompositionTables(headers, values + listOf(values.last())) }.isFailure)
        assertTrue(runCatching { parseBodyCompositionTables(headers + listOf(headers.last()), values) }.isFailure)
        assertTrue(runCatching { parseBodyCompositionTables(emptyList(), values) }.isFailure)
        assertTrue(runCatching { parseBodyCompositionTables(headers, listOf(BODY_VALUE_HEADERS)) }.isFailure)
    }

    @Test fun catalogKeepsMeasuredWeightAndHeightOutAndDerivedItemsOptional() {
        assertEquals(14, BODY_COMPOSITION_METRICS.size)
        assertEquals(11, BODY_COMPOSITION_METRICS.count { it.defaultVisible })
        assertFalse(BODY_COMPOSITION_METRICS.any { it.name in setOf("体重", "身長") })
    }

    @Test fun allFourteenMetricsRoundTripWithoutMixingUnitsOrDroppingZeroAndNegativeValues() {
        val input = measurement().copy(values = BODY_COMPOSITION_METRICS.map { metric ->
            val text = when (metric.id) {
                "obesityPercent" -> "-2.0"
                "fatMass" -> "0.0"
                else -> "20"
            }
            BodyCompositionValue(metric.id, text, metric.unit)
        })
        val normalized = validateBodyCompositionMeasurement(input)
        val restored = parseBodyCompositionTables(bodyMeasurementRows(listOf(input)), bodyValueRows(listOf(input))).single()
        assertEquals(normalized, restored)
        assertEquals(14, restored.values.size)
        assertEquals("0.0", restored.values.single { it.metricId == "fatMass" }.value)
        assertEquals("-2.0", restored.values.single { it.metricId == "obesityPercent" }.value)
    }

    @Test fun failedBodyBackupKeepsItsSnapshotPendingUntilSuccessfulRetry() = runBlocking {
        var backedUp = 0L
        var fail = true
        val input = measurement()
        val uploads = mutableListOf<LabBackupSnapshot>()
        val writer = LabResultsWriter(
            saveLocalRecords = { error("この試験では検査結果を保存しない") },
            loadSnapshot = { LabBackupSnapshot(normalizeLabTables(emptyList()), listOf(LAB_REFERENCE_BACKUP_HEADERS), 1, backedUp, listOf(input)) },
            uploadSnapshot = { _, snapshot -> uploads += snapshot; if (fail) error("通信失敗") },
            markBackedUp = { backedUp = it },
        )
        assertTrue(runCatching { writer.backupIfNeeded("test-token") }.isFailure)
        assertEquals(0L, backedUp)
        assertEquals(listOf(input), uploads.single().bodyMeasurements)
        fail = false
        assertTrue(writer.backupIfNeeded("test-token"))
        assertEquals(uploads.first().bodyMeasurements, uploads.last().bodyMeasurements)
        assertEquals(1L, backedUp)
        assertFalse(writer.backupIfNeeded("test-token"))
    }

    @Test fun backupCreatesBodySheetsAndUpdatesAllTablesInOneAtomicRequest() {
        val input = measurement()
        val snapshot = LabBackupSnapshot(normalizeLabTables(emptyList()), listOf(LAB_REFERENCE_BACKUP_HEADERS), 1, 0, listOf(input))
        val requests = buildLabBackupRequest(snapshot, spreadsheet()).getJSONArray("requests")
        val objects = (0 until requests.length()).map { requests.getJSONObject(it) }
        val created = objects.filter { it.has("addSheet") }.map { it.getJSONObject("addSheet").getJSONObject("properties").getString("title") }
        assertEquals(listOf("bodyMeasurements", "bodyValues"), created)
        assertEquals(5, objects.count { it.has("updateCells") })
        assertEquals(5, objects.filter { it.has("updateCells") }.map { it.getJSONObject("updateCells").getJSONObject("range").getInt("sheetId") }.distinct().size)
        val bodyUpdate = objects.last().getJSONObject("updateCells")
        assertEquals("userEnteredValue", bodyUpdate.getString("fields"))
        assertTrue(bodyUpdate.getJSONObject("range").getInt("endRowIndex") > bodyUpdate.getJSONArray("rows").length())
        assertEquals("20.0", bodyUpdate.getJSONArray("rows").getJSONObject(1).getJSONArray("values")
            .getJSONObject(2).getJSONObject("userEnteredValue").getString("stringValue"))
    }

    @Test fun bloodOnlyBackupDoesNotCreateBodySheetsAndRetainsExistingBodyTabsIfPresent() {
        val snapshot = LabBackupSnapshot(normalizeLabTables(emptyList()), listOf(LAB_REFERENCE_BACKUP_HEADERS), 1, 0)
        val request = buildLabBackupRequest(snapshot, spreadsheet()).getJSONArray("requests")
        assertEquals(3, request.length())
        val withBody = spreadsheet()
        withBody.getJSONArray("sheets").put(sheet("bodyMeasurements", 3)).put(sheet("bodyValues", 4))
        val restored = buildLabBackupRequest(snapshot, withBody).getJSONArray("requests")
        assertEquals(5, restored.length())
    }

    private fun spreadsheet() = JSONObject().put("sheets", JSONArray().apply {
        listOf("labItems", "labResults", "labReferenceRanges").forEachIndexed { id, title -> put(sheet(title, id)) }
    })

    private fun sheet(title: String, id: Int) = JSONObject().put("properties", JSONObject().put("title", title).put("sheetId", id)
        .put("gridProperties", JSONObject().put("rowCount", 1000).put("columnCount", 26)))
}
