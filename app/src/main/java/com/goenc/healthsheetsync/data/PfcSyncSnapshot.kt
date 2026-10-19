package com.goenc.healthsheetsync.data

import org.json.JSONObject

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

    fun apply(batch: JSONObject) {
        require(batch.getString("datasetId").equals(PfcSyncSnapshot.DATASET_ID, true)) { "異なるデータセット" }
        require(batch.optInt("protocolVersion", 1) == 1) { "未対応の同期形式" }
        if (!batchIds.add(batch.getString("batchId").lowercase())) return
        val changes = batch.getJSONArray("changes")
        (0 until changes.length()).map { changes.getJSONObject(it) }
            .sortedBy { Revision(it.getJSONObject("revision")) }.forEach change@{ change ->
                if (!operations.add(change.getString("operationId").lowercase())) return@change
                val type = change.getString("entityType").substringAfterLast('.')
                val key = change.getString("rowKey").lowercase()
                val revision = Revision(change.getJSONObject("revision"))
                val old = winners[type to key]
                if (old != null && old.revision >= revision) return@change
                val payload = when (change.getInt("changeKind")) {
                    0 -> normalize(JSONObject(change.getString("payloadJson")))
                    1 -> null
                    else -> error("未対応の変更種別")
                }
                winners[type to key] = Winner(revision, payload)
            }
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
}
