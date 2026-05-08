package com.goenc.healthsheetsync.data

import android.content.Context

class SpreadsheetUploadSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var webAppUrl: String
        get() = preferences.getString(KEY_WEB_APP_URL, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_WEB_APP_URL, value).apply()
        }

    companion object {
        const val TARGET_SPREADSHEET_ID = "1QXipvEOmwPek9fz9yHs4DTNArVsOwUC0"
        const val TARGET_SHEET_GID = "1567775953"
        const val TARGET_SPREADSHEET_URL =
            "https://docs.google.com/spreadsheets/d/$TARGET_SPREADSHEET_ID/edit?gid=$TARGET_SHEET_GID#gid=$TARGET_SHEET_GID"

        private const val PREFERENCES_NAME = "spreadsheet_upload_settings"
        private const val KEY_WEB_APP_URL = "web_app_url"
    }
}
