package com.goenc.healthsheetsync.data

import org.json.JSONObject
import org.json.JSONArray

/** PC の差分を読み取るだけの reducer。端末の変更や送信キューは持たない。 */
class PfcSyncSnapshot(val rows: Map<String, Map<String, JSONObject>>) {
    fun all(type: String): List<JSONObject> = rows[type]?.values?.toList().orEmpty()
    fun find(type: String, key: String): JSONObject? = rows[type]?.get(key.lowercase())

    companion object {
        const val DATASET_ID = "9b0db1ba-7ee0-4cf3-9b9b-84f674a8d1bb"

        fun rebuild(batches: List<JSONObject>): PfcSyncSnapshot {
            val reducer = PfcSyncReducer()
            batches.sortedWith(compareBy({ it.getString("deviceId") },
                { it.getLong("deviceSequence") }, { it.getString("batchId") })).forEach(reducer::apply)
            return reducer.snapshot()
        }

        fun fromJson(rows: JSONObject): PfcSyncSnapshot = PfcSyncSnapshot(
            rows.keys().asSequence().associateWith { type ->
                val table = rows.getJSONObject(type)
                table.keys().asSequence().associateWith { table.getJSONObject(it) }
            },
        )
    }

    fun toJson(): JSONObject = JSONObject().also { result ->
        rows.forEach { (type, values) ->
            result.put(type, JSONObject().also { table -> values.forEach { (key, row) -> table.put(key, row) } })
        }
    }
}

/** 全履歴を一度に保持せず、1バッチずつ現在状態に反映する。 */
internal class PfcSyncReducer {
    private data class Winner(val revision: Revision, val payload: JSONObject?)
    private val winners = mutableMapOf<Pair<String, String>, Winner>()
    private val batchIds = mutableSetOf<String>()
    private val operations = mutableSetOf<String>()

    fun processedBatchIds(): Set<String> = batchIds.toSet()

    /** Compact checkpoint: current rows, deletion revisions and deduplication IDs, no raw history. */
    fun toStateJson(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("datasetId", PfcSyncSnapshot.DATASET_ID)
        .put("batchIds", JSONArray(batchIds.toList()))
        .put("operations", JSONArray(operations.toList()))
        .put("winners", JSONArray(winners.map { (key, winner) ->
            JSONObject().put("type", key.first).put("key", key.second)
                .put("revision", winner.revision.toJson())
                .put("payload", winner.payload ?: JSONObject.NULL)
        }))

    companion object {
        fun fromStateJson(state: JSONObject): PfcSyncReducer {
            require(state.getInt("schemaVersion") == 1) { "未対応の受信キャッシュ形式" }
            require(state.getString("datasetId").equals(PfcSyncSnapshot.DATASET_ID, true)) { "異なるデータセット" }
            return PfcSyncReducer().apply {
                val batches = state.getJSONArray("batchIds")
                for (i in 0 until batches.length()) batchIds.add(batches.getString(i).lowercase())
                val seen = state.getJSONArray("operations")
                for (i in 0 until seen.length()) operations.add(seen.getString(i).lowercase())
                val rows = state.getJSONArray("winners")
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val key = row.getString("type") to row.getString("key").lowercase()
                    require(key.first.isNotBlank() && key.second.isNotBlank() && key !in winners) { "不正な受信キャッシュ行" }
                    val revision = Revision(row.getJSONObject("revision"))
                    require(revision.operation in operations) { "受信キャッシュの操作情報が欠落" }
                    winners[key] = Winner(revision, if (row.isNull("payload")) null else
                        normalize(JSONObject(row.getJSONObject("payload").toString())))
                }
            }
        }
    }

    fun apply(batch: JSONObject) {
        require(batch.getString("datasetId").equals(PfcSyncSnapshot.DATASET_ID, true)) { "異なるデータセット" }
        require(batch.optInt("protocolVersion", 1) == 1) { "未対応の同期形式" }
        val batchId = batch.getString("batchId").lowercase()
        if (batchId in batchIds) return
        val pendingWinners = mutableMapOf<Pair<String, String>, Winner>()
        val pendingOperations = mutableSetOf<String>()
        val changes = batch.getJSONArray("changes")
        (0 until changes.length()).map { changes.getJSONObject(it) }
            .sortedBy { Revision(it.getJSONObject("revision")) }.forEach change@{ change ->
                val operation = change.getString("operationId").lowercase()
                if (operation in operations || !pendingOperations.add(operation)) return@change
                val type = change.getString("entityType").substringAfterLast('.')
                val key = change.getString("rowKey").lowercase()
                val revision = Revision(change.getJSONObject("revision"))
                val old = pendingWinners[type to key] ?: winners[type to key]
                if (old != null && old.revision >= revision) return@change
                val payload = when (change.getInt("changeKind")) {
                    0 -> normalize(JSONObject(change.getString("payloadJson")))
                    1 -> null
                    else -> error("未対応の変更種別")
                }
                pendingWinners[type to key] = Winner(revision, payload)
            }
        winners.putAll(pendingWinners)
        operations.addAll(pendingOperations)
        batchIds.add(batchId)
    }

    fun snapshot(): PfcSyncSnapshot {
        val rows = mutableMapOf<String, MutableMap<String, JSONObject>>()
        winners.forEach { (key, winner) ->
            winner.payload?.let { rows.getOrPut(key.first) { linkedMapOf() }[key.second] = it }
        }
        return PfcSyncSnapshot(rows)
    }

    private fun normalize(value: JSONObject): JSONObject = JSONObject().also { result ->
        value.keys().forEach { key ->
            // Dictionaryの列名はPascalCase。PCローカル画像パスは保存しない。
            if (!key.equals("OriginalRelativePath", true) && !key.equals("ThumbnailRelativePath", true)) {
                result.put(key.replaceFirstChar { it.lowercaseChar() }, value.get(key))
            }
        }
    }
}

private data class Revision(val ticks: Long, val device: String, val sequence: Long, val operation: String) : Comparable<Revision> {
    constructor(json: JSONObject) : this(json.getLong("editedUtcTicks"), json.getString("deviceId"),
        json.getLong("deviceSequence"), json.getString("operationId").lowercase())
    override fun compareTo(other: Revision): Int = compareValuesBy(this, other,
        { it.ticks }, { it.device }, { it.sequence }, { it.operation })

    fun toJson(): JSONObject = JSONObject().put("editedUtcTicks", ticks).put("deviceId", device)
        .put("deviceSequence", sequence).put("operationId", operation)
}

/** A missing/old/broken checkpoint or missing remote history requires a safe full replay. */
internal fun resumePfcReducer(saved: JSONObject?, remoteBatchIds: Set<String>): PfcSyncReducer = runCatching {
    require(saved?.getString("datasetId").equals(PfcSyncSnapshot.DATASET_ID, true)) { "異なるデータセット" }
    val reducer = PfcSyncReducer.fromStateJson(saved!!.getJSONObject("reducer"))
    require(remoteBatchIds.containsAll(reducer.processedBatchIds())) { "受信済み履歴が一覧に存在しない" }
    reducer
}.getOrElse { PfcSyncReducer() }
