package com.goenc.healthsheetsync.data

import android.content.Context
import org.json.JSONObject

/** 項目共通の基準範囲を端末内へ保存。検査結果とSheetsの原本は変更しない。 */
internal class LabReferenceSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("lab_reference_ranges", Context.MODE_PRIVATE)

    fun load(): Map<String, LabReferenceRange?> = runCatching {
        val json = JSONObject(preferences.getString("ranges", "{}").orEmpty())
        json.keys().asSequence().associateWith { name ->
            if (json.isNull(name)) null else json.getJSONObject(name).let { value ->
                LabReferenceRange(
                    minimum = value.optString("minimum").takeIf { it.isNotBlank() }?.toBigDecimal(),
                    maximum = value.optString("maximum").takeIf { it.isNotBlank() }?.toBigDecimal(),
                    minimumInclusive = value.optBoolean("minimumInclusive", true),
                    maximumInclusive = value.optBoolean("maximumInclusive", true),
                )
            }
        }
    }.getOrDefault(emptyMap())

    fun initializeMissing(records: List<LabResult>): Map<String, LabReferenceRange?> {
        val settings = load().toMutableMap()
        var changed = false
        records.map { it.itemName }.distinct().filter { it !in settings }.forEach { name ->
            findInitialLabReferenceRange(name, records)?.let { candidate ->
                settings[name] = candidate
                changed = true
            }
        }
        if (changed) persist(settings)
        return settings
    }

    fun save(itemName: String, range: LabReferenceRange?): Map<String, LabReferenceRange?> {
        val settings = load() + (itemName to range)
        persist(settings)
        return settings
    }

    private fun persist(settings: Map<String, LabReferenceRange?>) {
        val json = JSONObject()
        settings.forEach { (name, range) ->
            json.put(name, range?.let {
                JSONObject().put("minimum", it.minimum?.toPlainString().orEmpty())
                    .put("maximum", it.maximum?.toPlainString().orEmpty())
                    .put("minimumInclusive", it.minimumInclusive)
                    .put("maximumInclusive", it.maximumInclusive)
            } ?: JSONObject.NULL)
        }
        check(preferences.edit().putString("ranges", json.toString()).commit()) { "基準範囲を保存できませんでした" }
    }
}
