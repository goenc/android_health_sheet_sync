package com.goenc.healthsheetsync.data

import android.content.Context

/** 表示設定だけを保存し、検査結果そのものには触れない。 */
internal class LabDisplaySettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("lab_display_settings", Context.MODE_PRIVATE)

    fun excludedItems(): Set<String> = preferences.getStringSet("excluded_items", emptySet()).orEmpty().toSet()

    fun saveExcludedItems(items: Set<String>) {
        preferences.edit().putStringSet("excluded_items", items.toSet()).apply()
    }

    fun showTargetOnly(): Boolean = preferences.getBoolean("show_target_only", false)

    fun saveShowTargetOnly(enabled: Boolean) {
        preferences.edit().putBoolean("show_target_only", enabled).apply()
    }
}
