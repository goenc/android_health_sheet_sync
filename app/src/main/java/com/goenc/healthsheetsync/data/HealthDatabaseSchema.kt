package com.goenc.healthsheetsync.data

import android.database.sqlite.SQLiteDatabase

internal const val DATABASE_NAME = "health_sheet_sync.db"
internal const val DATABASE_VERSION = 8
internal const val TABLE_WEIGHT = "weight_records"
internal const val TABLE_GLUCOSE = "glucose_records"
internal const val TABLE_STEPS = "step_daily_records"
internal const val TABLE_STEP_RECORDS = "health_connect_step_records"
internal const val TABLE_A1C_DAILY = "a1c_daily_records"
internal const val TABLE_MANUAL = "manual_records"
internal const val TABLE_INVALIDATED = "invalidated_record_keys"
internal const val TABLE_DAILY_ENERGY_SNAPSHOTS = "daily_energy_snapshots"
internal const val TABLE_DAILY_ENERGY_REPAIRS = "daily_energy_repairs"
internal const val TABLE_DAILY_BODY_SETTINGS = "daily_body_settings"
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
    createHealthConnectStepRecordsTable()
    createInvalidatedRecordsTable()
    createDailyEnergySnapshotsTable()
    createDailyEnergyRepairsTable()
}

internal fun SQLiteDatabase.createHealthConnectStepRecordsTable() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS health_connect_step_records (
            health_connect_id TEXT PRIMARY KEY,
            target_date TEXT NOT NULL,
            start_at TEXT NOT NULL,
            end_at TEXT NOT NULL,
            steps INTEGER NOT NULL,
            updated_at TEXT NOT NULL
        )
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE INDEX IF NOT EXISTS index_health_connect_step_records_target_date
        ON health_connect_step_records(target_date)
        """.trimIndent(),
    )
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

internal fun SQLiteDatabase.createDailyEnergySnapshotsTable() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS daily_energy_snapshots (
            target_date TEXT PRIMARY KEY,
            steps INTEGER NOT NULL,
            basal_metabolic_rate INTEGER NOT NULL,
            pal REAL NOT NULL,
            estimated_total_kcal INTEGER NOT NULL,
            finalized_at TEXT NOT NULL
        )
        """.trimIndent(),
    )
}

internal fun SQLiteDatabase.createDailyEnergyRepairsTable() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS daily_energy_repairs (
            repair_key TEXT PRIMARY KEY,
            completed_at TEXT NOT NULL
        )
        """.trimIndent(),
    )
}

internal fun SQLiteDatabase.createDailyBodySettingsTable() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS daily_body_settings (
            target_date TEXT PRIMARY KEY,
            height_cm REAL NOT NULL CHECK(height_cm > 0),
            average_intake_kcal INTEGER NOT NULL CHECK(average_intake_kcal > 0),
            updated_at TEXT NOT NULL
        )
        """.trimIndent(),
    )
}
