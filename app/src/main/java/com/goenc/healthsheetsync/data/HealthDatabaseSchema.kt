package com.goenc.healthsheetsync.data

import android.database.sqlite.SQLiteDatabase

internal const val DATABASE_NAME = "health_sheet_sync.db"
internal const val DATABASE_VERSION = 4
internal const val TABLE_WEIGHT = "weight_records"
internal const val TABLE_GLUCOSE = "glucose_records"
internal const val TABLE_STEPS = "step_daily_records"
internal const val TABLE_A1C_DAILY = "a1c_daily_records"
internal const val TABLE_MANUAL = "manual_records"
internal const val TABLE_INVALIDATED = "invalidated_record_keys"
internal const val MANUAL_SOURCE = "手入力"
internal const val MANUAL_PACKAGE = "manual"
internal const val UNKNOWN = "不明"

internal fun SQLiteDatabase.createHealthConnectTables() {
    execSQL(
        """
        CREATE TABLE weight_records (
            unique_key TEXT PRIMARY KEY,
            health_connect_id TEXT NOT NULL,
            measured_at TEXT NOT NULL,
            target_date TEXT NOT NULL,
            time_band TEXT NOT NULL,
            weight_kg REAL NOT NULL,
            source_app_name TEXT NOT NULL,
            source_package_name TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE TABLE glucose_records (
            unique_key TEXT PRIMARY KEY,
            health_connect_id TEXT NOT NULL,
            measured_at TEXT NOT NULL,
            target_date TEXT NOT NULL,
            time_band TEXT NOT NULL,
            blood_glucose_mg_dl REAL NOT NULL,
            meal_relation TEXT NOT NULL,
            source_app_name TEXT NOT NULL,
            source_package_name TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE TABLE step_daily_records (
            target_date TEXT PRIMARY KEY,
            steps INTEGER NOT NULL,
            aggregation_start_at TEXT NOT NULL,
            aggregation_end_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """.trimIndent(),
    )
    createInvalidatedRecordsTable()
}

internal fun SQLiteDatabase.createManualRecordsTable() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS manual_records (
            id TEXT PRIMARY KEY,
            type TEXT NOT NULL,
            measured_at TEXT NOT NULL,
            value_text TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            invalidated_at TEXT
        )
        """.trimIndent(),
    )
}

internal fun SQLiteDatabase.createInvalidatedRecordsTable() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS invalidated_record_keys (
            record_type TEXT NOT NULL,
            unique_key TEXT NOT NULL,
            invalidated_at TEXT NOT NULL,
            PRIMARY KEY(record_type, unique_key)
        )
        """.trimIndent(),
    )
}

internal fun SQLiteDatabase.createA1cDailyRecordsTable() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS a1c_daily_records (
            target_date TEXT PRIMARY KEY,
            measured_at TEXT NOT NULL,
            a1c_percent REAL NOT NULL,
            manual_id TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """.trimIndent(),
    )
}
