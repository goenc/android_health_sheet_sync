package com.goenc.healthsheetsync.data

import android.content.Context
import org.json.JSONObject

/** 項目共通の基準範囲を端末内へ保存。検査結果とSheetsの原本は変更しない。 */
internal class LabReferenceSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("lab_reference_ranges", Context.MODE_PRIVATE)
    private val database = LabDatabase.get(context)

    private fun legacySettings(): Map<String, LabReferenceRange?> {
        if (!preferences.contains("ranges")) return emptyMap()
        val json = JSONObject(preferences.getString("ranges", "{}").orEmpty())
        return json.keys().asSequence().associateWith { name ->
            if (json.isNull(name)) null else json.getJSONObject(name).let { value ->
                LabReferenceRange(
                    minimum = value.optString("minimum").takeIf { it.isNotBlank() }?.toBigDecimal(),
                    maximum = value.optString("maximum").takeIf { it.isNotBlank() }?.toBigDecimal(),
                    minimumInclusive = value.optBoolean("minimumInclusive", true),
                    maximumInclusive = value.optBoolean("maximumInclusive", true),
                )
            }
        }
    }

    fun load(): Map<String, LabReferenceRange?> {
        if (database.metadata("references_migrated") == null) {
            legacySettings().forEach { (name, range) -> database.saveReference(name, range) }
            database.mark("references_migrated")
        }
        return database.references()
    }

    fun units(): Map<String, String> = database.units()

    fun initializeMissing(records: List<LabResult>): Map<String, LabReferenceRange?> {
        val settings = load().toMutableMap()
        database.ensureItems(records)
        records.map { it.itemName }.distinct().filter { it !in settings }.forEach { name ->
            findInitialLabReferenceRange(name, records)?.let { candidate ->
                settings[name] = candidate
                database.saveReference(name, candidate)
            }
        }
        return settings
    }

    fun save(itemName: String, range: LabReferenceRange?, unit: String? = null): Map<String, LabReferenceRange?> {
        database.saveReference(itemName, range, unit, requestBackup = true)
        return database.references()
    }
}
