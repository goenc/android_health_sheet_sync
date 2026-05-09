package com.goenc.healthsheetsync.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import java.time.LocalDate
import java.time.LocalDateTime

class LocalHealthDataStore(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
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
        db.execSQL(
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
        db.execSQL(
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS weight_records")
        db.execSQL("DROP TABLE IF EXISTS glucose_records")
        db.execSQL("DROP TABLE IF EXISTS step_daily_records")
        onCreate(db)
    }

    fun save(
        weightRecords: List<DebugWeightRecord>,
        glucoseRecords: List<DebugGlucoseRecord>,
        stepDailyRecords: List<DebugStepDaily>,
    ) {
        val updatedAt = LocalDateTime.now().toString()
        writableDatabase.runInTransaction {
            weightRecords.forEach { record ->
                replace(
                    TABLE_WEIGHT,
                    null,
                    ContentValues().apply {
                        put("unique_key", record.uniqueKey("weight", record.weightKg.toString()))
                        put("health_connect_id", record.healthConnectId)
                        put("measured_at", record.measuredAt.toString())
                        put("target_date", record.targetDate.toString())
                        put("time_band", record.timeBand)
                        put("weight_kg", record.weightKg)
                        put("source_app_name", record.sourceAppName)
                        put("source_package_name", record.sourcePackageName)
                        put("updated_at", updatedAt)
                    },
                )
            }
            glucoseRecords.forEach { record ->
                replace(
                    TABLE_GLUCOSE,
                    null,
                    ContentValues().apply {
                        put("unique_key", record.uniqueKey("glucose", record.bloodGlucoseMgDl.toString()))
                        put("health_connect_id", record.healthConnectId)
                        put("measured_at", record.measuredAt.toString())
                        put("target_date", record.targetDate.toString())
                        put("time_band", record.timeBand)
                        put("blood_glucose_mg_dl", record.bloodGlucoseMgDl)
                        put("meal_relation", record.mealRelation)
                        put("source_app_name", record.sourceAppName)
                        put("source_package_name", record.sourcePackageName)
                        put("updated_at", updatedAt)
                    },
                )
            }
            stepDailyRecords.forEach { record ->
                replace(
                    TABLE_STEPS,
                    null,
                    ContentValues().apply {
                        put("target_date", record.targetDate.toString())
                        put("steps", record.steps)
                        put("aggregation_start_at", record.aggregationStartAt.toString())
                        put("aggregation_end_at", record.aggregationEndAt.toString())
                        put("updated_at", updatedAt)
                    },
                )
            }
        }
    }

    fun load(): StoredHealthData {
        return StoredHealthData(
            weightRecords = loadWeightRecords(),
            glucoseRecords = loadGlucoseRecords(),
            stepDailyRecords = loadStepDailyRecords(),
        )
    }

    private fun loadWeightRecords(): List<DebugWeightRecord> {
        readableDatabase.rawQuery(
            """
            SELECT health_connect_id, measured_at, target_date, time_band, weight_kg, source_app_name, source_package_name
            FROM weight_records
            ORDER BY measured_at DESC
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        DebugWeightRecord(
                            healthConnectId = cursor.getString(0),
                            measuredAt = LocalDateTime.parse(cursor.getString(1)),
                            targetDate = LocalDate.parse(cursor.getString(2)),
                            timeBand = cursor.getString(3),
                            weightKg = cursor.getDouble(4),
                            sourceAppName = cursor.getString(5),
                            sourcePackageName = cursor.getString(6),
                        ),
                    )
                }
            }
        }
    }

    private fun loadGlucoseRecords(): List<DebugGlucoseRecord> {
        readableDatabase.rawQuery(
            """
            SELECT health_connect_id, measured_at, target_date, time_band, blood_glucose_mg_dl, meal_relation, source_app_name, source_package_name
            FROM glucose_records
            ORDER BY measured_at DESC
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        DebugGlucoseRecord(
                            healthConnectId = cursor.getString(0),
                            measuredAt = LocalDateTime.parse(cursor.getString(1)),
                            targetDate = LocalDate.parse(cursor.getString(2)),
                            timeBand = cursor.getString(3),
                            bloodGlucoseMgDl = cursor.getDouble(4),
                            mealRelation = cursor.getString(5),
                            sourceAppName = cursor.getString(6),
                            sourcePackageName = cursor.getString(7),
                        ),
                    )
                }
            }
        }
    }

    private fun loadStepDailyRecords(): List<DebugStepDaily> {
        readableDatabase.rawQuery(
            """
            SELECT target_date, steps, aggregation_start_at, aggregation_end_at
            FROM step_daily_records
            ORDER BY target_date DESC
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        DebugStepDaily(
                            targetDate = LocalDate.parse(cursor.getString(0)),
                            steps = cursor.getLong(1),
                            aggregationStartAt = LocalDateTime.parse(cursor.getString(2)),
                            aggregationEndAt = LocalDateTime.parse(cursor.getString(3)),
                        ),
                    )
                }
            }
        }
    }

    private fun DebugWeightRecord.uniqueKey(recordType: String, value: String): String {
        return stableHealthConnectKey(recordType, healthConnectId)
            ?: "$recordType|$measuredAt|$sourcePackageName|$value"
    }

    private fun DebugGlucoseRecord.uniqueKey(recordType: String, value: String): String {
        return stableHealthConnectKey(recordType, healthConnectId)
            ?: "$recordType|$measuredAt|$sourcePackageName|$value|$mealRelation"
    }

    private fun stableHealthConnectKey(recordType: String, healthConnectId: String): String? {
        if (healthConnectId.isBlank() || healthConnectId == UNKNOWN) return null
        return "$recordType|$healthConnectId"
    }

    private inline fun SQLiteDatabase.runInTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    companion object {
        private const val DATABASE_NAME = "health_sheet_sync.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_WEIGHT = "weight_records"
        private const val TABLE_GLUCOSE = "glucose_records"
        private const val TABLE_STEPS = "step_daily_records"
        private const val UNKNOWN = "不明"
    }
}

data class StoredHealthData(
    val weightRecords: List<DebugWeightRecord>,
    val glucoseRecords: List<DebugGlucoseRecord>,
    val stepDailyRecords: List<DebugStepDaily>,
)
