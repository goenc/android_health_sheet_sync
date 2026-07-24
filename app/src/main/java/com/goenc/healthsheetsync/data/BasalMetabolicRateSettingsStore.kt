package com.goenc.healthsheetsync.data

import android.content.Context
import com.goenc.healthsheetsync.health.DailyEnergyCalculator

class BasalMetabolicRateSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): Int {
        return preferences.getInt(KEY_BASAL_METABOLIC_RATE, DailyEnergyCalculator.DEFAULT_BASAL_METABOLIC_RATE)
    }

    fun save(value: Int): Boolean {
        if (value <= 0) return false
        preferences.edit().putInt(KEY_BASAL_METABOLIC_RATE, value).apply()
        return true
    }

    private companion object {
        const val PREFERENCES_NAME = "basal_metabolic_rate_settings"
        const val KEY_BASAL_METABOLIC_RATE = "basal_metabolic_rate"
    }
}
