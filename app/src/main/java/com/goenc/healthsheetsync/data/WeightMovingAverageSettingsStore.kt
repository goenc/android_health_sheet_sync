package com.goenc.healthsheetsync.data

import android.content.Context

enum class WeightMovingAverageMode(
    val storageValue: String,
    val label: String,
) {
    All("all", "全部"),
    MorningOnly("morning_only", "朝だけ"),
    NightOnly("night_only", "夜だけ");

    companion object {
        fun fromStorageValue(value: String?): WeightMovingAverageMode {
            return entries.firstOrNull { it.storageValue == value } ?: All
        }
    }
}

class WeightMovingAverageSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): WeightMovingAverageMode {
        return WeightMovingAverageMode.fromStorageValue(preferences.getString(KEY_MODE, null))
    }

    fun save(mode: WeightMovingAverageMode): Boolean {
        return preferences.edit().putString(KEY_MODE, mode.storageValue).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "weight_moving_average_settings"
        const val KEY_MODE = "mode"
    }
}
