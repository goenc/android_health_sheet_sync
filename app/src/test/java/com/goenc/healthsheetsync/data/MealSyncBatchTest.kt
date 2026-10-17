package com.goenc.healthsheetsync.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealSyncBatchTest {
    @Test
    fun acceptsMatchingDatasetAndExactLongSequence() {
        val file = file().apply {
            getJSONObject("appProperties").put("deviceSequence", "9007199254740993")
        }
        assertEquals(9007199254740993L, parseMealSyncBatch(file)?.deviceSequence)
    }

    @Test
    fun ignoresOtherDatasetsAndIncompleteMetadata() {
        assertNull(parseMealSyncBatch(file().apply {
            getJSONObject("appProperties").put("datasetId", "other")
        }))
        assertNull(parseMealSyncBatch(file().apply {
            getJSONObject("appProperties").put("deviceId", " ")
        }))
        assertNull(parseMealSyncBatch(file().apply {
            getJSONObject("appProperties").put("deviceSequence", "1.5")
        }))
        assertNull(parseMealSyncBatch(file().apply { remove("appProperties") }))
    }

    private fun file() = JSONObject().put("id", "batch-file").put("name", "pfc-sync-test.json")
        .put("appProperties", JSONObject().put("datasetId", MEAL_DATASET_ID)
            .put("deviceId", "desktop").put("deviceSequence", "12"))
}
