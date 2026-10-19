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

    @Test fun persistedReducerResumesIncrementalHistoryWithTheSameResultAsFullReplay() {
        val first = batch("first", change(1, operation = "first-operation"))
        val second = batch("second", change(2, operation = "second-operation"))
        val third = batch("third", change(3, operation = "third-operation"))
        val all = listOf(first, second, third)

        val reducer = PfcSyncReducer()
        reducer.apply(first)
        val saved = reducer.toStateJson()
        assertEquals(PfcSyncSnapshot.DATASET_ID, saved.getString("datasetId"))
        assertEquals(1, saved.getInt("schemaVersion"))
        assertTrue(saved.has("winners"))
        assertTrue(saved.has("batchIds"))
        assertTrue(saved.has("operations"))

        val restored = PfcSyncReducer.fromStateJson(JSONObject(saved.toString()))
        restored.apply(second)
        restored.apply(third)

        assertEquals(
            PfcSyncSnapshot.rebuild(all).toJson().toString(),
            restored.snapshot().toJson().toString(),
        )
        assertEquals(setOf("first", "second", "third"), restored.processedBatchIds())
    }

    @Test fun persistedDeletionTombstoneRejectsLateOlderUpsert() {
        val reducer = PfcSyncReducer()
        reducer.apply(batch("delete", change(10, 1, operation = "delete-operation")))
        val restored = PfcSyncReducer.fromStateJson(JSONObject(reducer.toStateJson().toString()))

        restored.apply(batch("late-old-upsert", change(9, operation = "late-old-operation")))

        assertTrue(restored.snapshot().all("DailyPlanEntity").isEmpty())
    }

    @Test fun persistedDeletionTombstoneAllowsNewerUpsertToRestoreRow() {
        val reducer = PfcSyncReducer()
        reducer.apply(batch("delete", change(10, 1, operation = "delete-operation")))
        val restored = PfcSyncReducer.fromStateJson(JSONObject(reducer.toStateJson().toString()))

        restored.apply(batch("new-upsert", change(11, operation = "new-operation")))

        assertEquals("fixture-11", restored.snapshot().find("DailyPlanEntity", "AB")!!.getString("name"))
    }

    @Test fun repeatedBatchAndOperationAfterRestoreDoNotChangeState() {
        val original = batch("first", change(1, operation = "same-operation"))
        val reducer = PfcSyncReducer()
        reducer.apply(original)
        val restored = PfcSyncReducer.fromStateJson(JSONObject(reducer.toStateJson().toString()))
        val before = restored.snapshot().toJson().toString()

        restored.apply(original)
        restored.apply(batch("second", change(99, operation = "same-operation")))

        assertEquals(before, restored.snapshot().toJson().toString())
        assertEquals(setOf("first", "second"), restored.processedBatchIds())
    }

    @Test fun failedDerivedBatchDoesNotMutateExistingPersistedState() {
        val reducer = PfcSyncReducer()
        reducer.apply(batch("first", change(1, operation = "first-operation")))
        val before = reducer.toStateJson().toString()
        val failed = batch(
            "failed-derived",
            change(2, operation = "derived-operation"),
            change(3, 7, operation = "unsupported-operation"),
        )

        assertThrows(IllegalStateException::class.java) { reducer.apply(failed) }

        assertEquals(before, reducer.toStateJson().toString())
        assertEquals(setOf("first"), reducer.processedBatchIds())
    }

    @Test fun persistedReducerRejectsWrongDatasetAndUnsupportedSchema() {
        val reducer = PfcSyncReducer().also {
            it.apply(batch("first", change(1, operation = "first-operation")))
        }
        val saved = reducer.toStateJson()

        assertThrows(IllegalArgumentException::class.java) {
            PfcSyncReducer.fromStateJson(JSONObject(saved.toString()).put("datasetId", "other"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PfcSyncReducer.fromStateJson(JSONObject(saved.toString()).put("schemaVersion", 2))
        }
    }

    @Test fun persistedReducerExcludesPcImagePaths() {
        val item = change(1, operation = "image-operation").put(
            "payloadJson",
            JSONObject().put("Id", "aB").put("Name", "fixture")
                .put("OriginalRelativePath", "pc-only")
                .put("ThumbnailRelativePath", "pc-only-thumb")
                .toString(),
        )
        val reducer = PfcSyncReducer().also { it.apply(batch("images", item)) }
        val restored = PfcSyncReducer.fromStateJson(JSONObject(reducer.toStateJson().toString()))

        assertFalse(restored.toStateJson().toString().contains("pc-only"))
        assertFalse(restored.snapshot().toJson().toString().contains("pc-only"))
    }

    @Test fun resumePfcReducerRestoresCheckpointAndContinuesWithPendingBatches() {
        val first = batch("first", change(1, operation = "first-operation"))
        val second = batch("second", change(2, operation = "second-operation"))
        val reducer = PfcSyncReducer().also { it.apply(first) }
        val checkpoint = JSONObject()
            .put("datasetId", PfcSyncSnapshot.DATASET_ID)
            .put("reducer", reducer.toStateJson())

        val resumed = resumePfcReducer(checkpoint, setOf("first", "second"))
        assertEquals(setOf("first"), resumed.processedBatchIds())
        resumed.apply(second)

        assertEquals(
            PfcSyncSnapshot.rebuild(listOf(first, second)).toJson().toString(),
            resumed.snapshot().toJson().toString(),
        )
    }

    @Test fun resumePfcReducerFallsBackForNullLegacyAndBrokenCheckpoints() {
        val legacy = JSONObject()
            .put("datasetId", PfcSyncSnapshot.DATASET_ID)
            .put("rows", JSONObject())
        val broken = JSONObject()
            .put("datasetId", PfcSyncSnapshot.DATASET_ID)
            .put("reducer", JSONObject().put("schemaVersion", 2))

        assertTrue(resumePfcReducer(null, emptySet()).processedBatchIds().isEmpty())
        assertTrue(resumePfcReducer(legacy, emptySet()).processedBatchIds().isEmpty())
        assertTrue(resumePfcReducer(broken, emptySet()).processedBatchIds().isEmpty())
    }

    @Test fun resumePfcReducerFallsBackWhenRemoteInventoryMissesSavedBatch() {
        val first = batch("first", change(1, operation = "first-operation"))
        val reducer = PfcSyncReducer().also { it.apply(first) }
        val checkpoint = JSONObject()
            .put("datasetId", PfcSyncSnapshot.DATASET_ID)
            .put("reducer", reducer.toStateJson())

        val resumed = resumePfcReducer(checkpoint, setOf("other"))

        assertTrue(resumed.processedBatchIds().isEmpty())
        assertTrue(resumed.snapshot().all("DailyPlanEntity").isEmpty())
    }
}
