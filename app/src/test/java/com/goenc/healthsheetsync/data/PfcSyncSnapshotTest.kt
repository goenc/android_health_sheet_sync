package com.goenc.healthsheetsync.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PfcSyncSnapshotTest {
    private fun change(ticks: Long, kind: Int = 0, device: String = "a", sequence: Long = 1,
                       operation: String = java.util.UUID.nameUUIDFromBytes(ticks.toString().toByteArray()).toString()) = JSONObject()
        .put("operationId", operation)
        .put("entityType", "PfcPlanBoard.Infrastructure.Persistence.Entities.DailyPlanEntity")
        .put("rowKey", "aB")
        .put("changeKind", kind)
        .put("payloadJson", if (kind == 1) JSONObject.NULL else JSONObject().put("Id", "aB").put("Name", "fixture-$ticks").toString())
        .put("revision", JSONObject().put("editedUtcTicks", ticks).put("deviceId", device)
            .put("deviceSequence", sequence).put("operationId", operation))

    private fun batch(id: String, vararg changes: JSONObject) = JSONObject()
        .put("batchId", id).put("datasetId", PfcSyncSnapshot.DATASET_ID)
        .put("deviceId", "a").put("deviceSequence", 1).put("protocolVersion", 1)
        .put("changes", JSONArray(changes.toList()))

    @Test fun fullHistorySurvivesDuplicatesAndOlderBatches() {
        val first = batch("1", change(638900000000000001))
        val last = batch("2", change(638900000000000002))
        val snapshot = PfcSyncSnapshot.rebuild(listOf(last, first, first, last))
        assertEquals(1, snapshot.all("DailyPlanEntity").size)
        assertEquals("fixture-638900000000000002", snapshot.find("DailyPlanEntity", "AB")!!.getString("name"))
    }

    @Test fun deletionCannotBeUndoneByAnOlderUpsert() {
        val snapshot = PfcSyncSnapshot.rebuild(listOf(batch("a", change(3, 1)), batch("z", change(2))))
        assertTrue(snapshot.all("DailyPlanEntity").isEmpty())
    }

    @Test fun newerUpsertCanRestoreDeletedRow() {
        val snapshot = PfcSyncSnapshot.rebuild(listOf(batch("a", change(2, 1)), batch("z", change(3))))
        assertEquals("fixture-3", snapshot.find("DailyPlanEntity", "ab")!!.getString("name"))
    }

    @Test fun revisionsResolveEqualTicksUsingDeviceThenSequenceThenOperation() {
        val snapshot = PfcSyncSnapshot.rebuild(listOf(batch("a",
            change(1, 0, "z", 1, "00000000-0000-0000-0000-000000000001"),
            change(1, 1, "z", 2, "00000000-0000-0000-0000-000000000002"),
            change(1, 0, "a", 999, "00000000-0000-0000-0000-000000000003"))))
        assertTrue(snapshot.all("DailyPlanEntity").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsWrongDataset() {
        PfcSyncSnapshot.rebuild(listOf(batch("a", change(1)).put("datasetId", "other")))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownProtocol() {
        PfcSyncSnapshot.rebuild(listOf(batch("a", change(1)).put("protocolVersion", 2)))
    }

    @Test(expected = IllegalStateException::class) fun rejectsUnknownChangeKind() {
        PfcSyncSnapshot.rebuild(listOf(batch("a", change(1, 7))))
    }

    @Test fun incrementalReplayMatchesFullReplayAndCacheRoundTripExcludesPcPaths() {
        val item = change(1).put("payloadJson", JSONObject().put("Id", "aB").put("Name", "fixture")
            .put("OriginalRelativePath", "pc-only").put("ThumbnailRelativePath", "pc-only-thumb").toString())
        val first = batch("a", item)
        val reducer = PfcSyncReducer()
        reducer.apply(first)
        reducer.apply(first)
        val cached = PfcSyncSnapshot.fromJson(JSONObject(reducer.snapshot().toJson().toString()))
        assertEquals(1, cached.all("DailyPlanEntity").size)
        assertEquals("fixture", cached.find("DailyPlanEntity", "AB")!!.getString("name"))
        assertFalse(cached.toJson().toString().contains("pc-only"))
        assertEquals(PfcSyncSnapshot.rebuild(listOf(first)).toJson().toString(), cached.toJson().toString())
    }
}
