package com.goenc.healthsheetsync.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.InvalidatedGraphRecord
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.roundToInt

class LocalHealthDataStore(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.createHealthConnectTables()
        db.createManualRecordsTable()
        db.createA1cDailyRecordsTable()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.createManualRecordsTable()
        }
        if (oldVersion < 3) {
            db.createInvalidatedRecordsTable()
        }
        if (oldVersion < 4) {
            db.createA1cDailyRecordsTable()
            backfillA1cDailyRecords(db)
        }
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
            manualRecords = loadManualRecords(),
            a1cDailyRecords = loadA1cDailyRecords(),
            invalidatedGraphRecords = loadInvalidatedGraphRecords(),
        )
    }

    fun saveManualRecord(draft: ManualHealthRecordDraft) {
        val now = LocalDateTime.now().toString()
        val manualId = "manual|${UUID.randomUUID()}"
        writableDatabase.runInTransaction {
            replace(
                TABLE_MANUAL,
                null,
                ContentValues().apply {
                    put("id", manualId)
                    put("type", draft.type.name)
                    put("measured_at", draft.measuredAt.toString())
                    put("value_text", draft.valueText)
                    put("created_at", now)
                    put("updated_at", now)
                    putNull("invalidated_at")
                },
            )
            when (draft.type) {
                ManualRecordType.Weight -> {
                    val weightKg = draft.valueText.removeSuffix(" kg").toDoubleOrNull() ?: return@runInTransaction
                    val record = DebugWeightRecord(
                        measuredAt = draft.measuredAt,
                        targetDate = draft.measuredAt.toLocalDate(),
                        timeBand = draft.measuredAt.toTimeBand(),
                        weightKg = weightKg,
                        healthConnectId = manualId,
                        sourceAppName = MANUAL_SOURCE,
                        sourcePackageName = MANUAL_PACKAGE,
                    )
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
                            put("updated_at", now)
                        },
                    )
                }
                ManualRecordType.Steps -> {
                    val steps = draft.valueText.removeSuffix("歩").toLongOrNull() ?: return@runInTransaction
                    val targetDate = draft.measuredAt.toLocalDate()
                    replace(
                        TABLE_STEPS,
                        null,
                        ContentValues().apply {
                            put("target_date", targetDate.toString())
                            put("steps", steps)
                            put("aggregation_start_at", targetDate.atStartOfDay().toString())
                            put("aggregation_end_at", targetDate.plusDays(1).atStartOfDay().toString())
                            put("updated_at", now)
                        },
                    )
                }
                ManualRecordType.BloodGlucose -> {
                    val glucose = draft.valueText.removeSuffix(" mg/dL").toDoubleOrNull() ?: return@runInTransaction
                    val record = DebugGlucoseRecord(
                        measuredAt = draft.measuredAt,
                        targetDate = draft.measuredAt.toLocalDate(),
                        timeBand = draft.measuredAt.toTimeBand(),
                        bloodGlucoseMgDl = glucose,
                        mealRelation = "空腹時",
                        healthConnectId = manualId,
                        sourceAppName = MANUAL_SOURCE,
                        sourcePackageName = MANUAL_PACKAGE,
                    )
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
                            put("updated_at", now)
                        },
                    )
                }
                ManualRecordType.A1c -> {
                    val a1cPercent = draft.valueText.removeSuffix(" %").toDoubleOrNull() ?: return@runInTransaction
                    replace(
                        TABLE_A1C_DAILY,
                        null,
                        ContentValues().apply {
                            put("target_date", draft.measuredAt.toLocalDate().toString())
                            put("measured_at", draft.measuredAt.toString())
                            put("a1c_percent", a1cPercent)
                            put("manual_id", manualId)
                            put("updated_at", now)
                        },
                    )
                }
                ManualRecordType.BloodPressure,
                ManualRecordType.Waist -> Unit
            }
        }
    }

    fun invalidateManualRecord(id: String) {
        writableDatabase.update(
            TABLE_MANUAL,
            ContentValues().apply {
                put("invalidated_at", LocalDateTime.now().toString())
                put("updated_at", LocalDateTime.now().toString())
            },
            "id = ? AND invalidated_at IS NULL",
            arrayOf(id),
        )
    }

    fun restoreManualRecord(id: String) {
        writableDatabase.update(
            TABLE_MANUAL,
            ContentValues().apply {
                putNull("invalidated_at")
                put("updated_at", LocalDateTime.now().toString())
            },
            "id = ?",
            arrayOf(id),
        )
    }

    fun deleteManualRecord(id: String) {
        writableDatabase.runInTransaction {
            delete(TABLE_A1C_DAILY, "manual_id = ?", arrayOf(id))
            delete(TABLE_MANUAL, "id = ?", arrayOf(id))
        }
    }

    fun invalidateStoredRecord(recordType: String, uniqueKey: String) {
        writableDatabase.replace(
            TABLE_INVALIDATED,
            null,
            ContentValues().apply {
                put("record_type", recordType)
                put("unique_key", uniqueKey)
                put("invalidated_at", LocalDateTime.now().toString())
            },
        )
    }

    fun restoreStoredRecord(recordType: String, uniqueKey: String) {
        writableDatabase.delete(
            TABLE_INVALIDATED,
            "record_type = ? AND unique_key = ?",
            arrayOf(recordType, uniqueKey),
        )
    }

    fun deleteStoredRecord(recordType: String, uniqueKey: String) {
        writableDatabase.runInTransaction {
            delete(
                TABLE_INVALIDATED,
                "record_type = ? AND unique_key = ?",
                arrayOf(recordType, uniqueKey),
            )
            when (recordType) {
                "weight" -> delete(TABLE_WEIGHT, "unique_key = ?", arrayOf(uniqueKey))
                "glucose" -> delete(TABLE_GLUCOSE, "unique_key = ?", arrayOf(uniqueKey))
                "steps" -> delete(TABLE_STEPS, "target_date = ?", arrayOf(uniqueKey))
            }
        }
    }

    private fun loadWeightRecords(): List<DebugWeightRecord> {
        readableDatabase.rawQuery(
            """
            SELECT health_connect_id, measured_at, target_date, time_band, weight_kg, source_app_name, source_package_name
            FROM weight_records
            WHERE NOT EXISTS (
                SELECT 1 FROM invalidated_record_keys
                WHERE invalidated_record_keys.record_type = 'weight'
                AND invalidated_record_keys.unique_key = weight_records.unique_key
            )
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
            WHERE NOT EXISTS (
                SELECT 1 FROM invalidated_record_keys
                WHERE invalidated_record_keys.record_type = 'glucose'
                AND invalidated_record_keys.unique_key = glucose_records.unique_key
            )
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
            WHERE NOT EXISTS (
                SELECT 1 FROM invalidated_record_keys
                WHERE invalidated_record_keys.record_type = 'steps'
                AND invalidated_record_keys.unique_key = step_daily_records.target_date
            )
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

    private fun loadA1cDailyRecords(): List<DebugA1cDaily> {
        readableDatabase.rawQuery(
            """
            SELECT a1c_daily_records.target_date, a1c_daily_records.measured_at, a1c_percent, manual_id
            FROM a1c_daily_records
            INNER JOIN manual_records
            ON manual_records.id = a1c_daily_records.manual_id
            WHERE manual_records.invalidated_at IS NULL
            ORDER BY a1c_daily_records.target_date DESC
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        DebugA1cDaily(
                            targetDate = LocalDate.parse(cursor.getString(0)),
                            measuredAt = LocalDateTime.parse(cursor.getString(1)),
                            a1cPercent = cursor.getDouble(2),
                            manualId = cursor.getString(3),
                        ),
                    )
                }
            }
        }
    }

    private fun loadManualRecords(): List<ManualHealthRecord> {
        readableDatabase.rawQuery(
            """
            SELECT id, type, measured_at, value_text, invalidated_at
            FROM manual_records
            ORDER BY measured_at DESC
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    val type = runCatching { ManualRecordType.valueOf(cursor.getString(1)) }.getOrNull()
                    if (type == null) {
                        continue
                    }
                    add(
                        ManualHealthRecord(
                            id = cursor.getString(0),
                            type = type,
                            measuredAt = LocalDateTime.parse(cursor.getString(2)),
                            valueText = cursor.getString(3),
                            invalidatedAt = cursor.getString(4)?.let(LocalDateTime::parse),
                        ),
                    )
                }
            }
        }
    }

    private fun loadInvalidatedGraphRecords(): List<InvalidatedGraphRecord> {
        return loadInvalidatedWeightRecords() + loadInvalidatedStepRecords() + loadInvalidatedGlucoseRecords()
    }

    private fun loadInvalidatedWeightRecords(): List<InvalidatedGraphRecord> {
        readableDatabase.rawQuery(
            """
            SELECT weight_records.unique_key, measured_at, weight_kg, time_band, invalidated_record_keys.invalidated_at
            FROM weight_records
            INNER JOIN invalidated_record_keys
            ON invalidated_record_keys.record_type = 'weight'
            AND invalidated_record_keys.unique_key = weight_records.unique_key
            ORDER BY measured_at DESC
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    val measuredAt = LocalDateTime.parse(cursor.getString(1))
                    add(
                        InvalidatedGraphRecord(
                            recordType = "weight",
                            uniqueKey = cursor.getString(0),
                            manualType = ManualRecordType.Weight,
                            measuredAt = measuredAt,
                            text = "${measuredAt.formatDateTime()}  ${formatDecimal(cursor.getDouble(2))} kg / ${cursor.getString(3)}",
                            invalidatedAt = LocalDateTime.parse(cursor.getString(4)),
                        ),
                    )
                }
            }
        }
    }

    private fun loadInvalidatedStepRecords(): List<InvalidatedGraphRecord> {
        readableDatabase.rawQuery(
            """
            SELECT step_daily_records.target_date, steps, invalidated_record_keys.invalidated_at
            FROM step_daily_records
            INNER JOIN invalidated_record_keys
            ON invalidated_record_keys.record_type = 'steps'
            AND invalidated_record_keys.unique_key = step_daily_records.target_date
            ORDER BY step_daily_records.target_date DESC
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    val targetDate = LocalDate.parse(cursor.getString(0))
                    add(
                        InvalidatedGraphRecord(
                            recordType = "steps",
                            uniqueKey = cursor.getString(0),
                            manualType = ManualRecordType.Steps,
                            measuredAt = targetDate.atStartOfDay(),
                            text = "$targetDate  ${cursor.getLong(1)}歩",
                            invalidatedAt = LocalDateTime.parse(cursor.getString(2)),
                        ),
                    )
                }
            }
        }
    }

    private fun loadInvalidatedGlucoseRecords(): List<InvalidatedGraphRecord> {
        readableDatabase.rawQuery(
            """
            SELECT glucose_records.unique_key, measured_at, blood_glucose_mg_dl, meal_relation, invalidated_record_keys.invalidated_at
            FROM glucose_records
            INNER JOIN invalidated_record_keys
            ON invalidated_record_keys.record_type = 'glucose'
            AND invalidated_record_keys.unique_key = glucose_records.unique_key
            ORDER BY measured_at DESC
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    val measuredAt = LocalDateTime.parse(cursor.getString(1))
                    add(
                        InvalidatedGraphRecord(
                            recordType = "glucose",
                            uniqueKey = cursor.getString(0),
                            manualType = ManualRecordType.BloodGlucose,
                            measuredAt = measuredAt,
                            text = "${measuredAt.formatDateTime()}  ${formatDecimal(cursor.getDouble(2))} mg/dL / ${cursor.getString(3)}",
                            invalidatedAt = LocalDateTime.parse(cursor.getString(4)),
                        ),
                    )
                }
            }
        }
    }

    private fun backfillA1cDailyRecords(db: SQLiteDatabase) {
        val now = LocalDateTime.now().toString()
        db.rawQuery(
            """
            SELECT id, measured_at, value_text
            FROM manual_records
            WHERE type = ?
            ORDER BY measured_at ASC
            """.trimIndent(),
            arrayOf(ManualRecordType.A1c.name),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val measuredAt = LocalDateTime.parse(cursor.getString(1))
                val a1cPercent = cursor.getString(2).removeSuffix(" %").toDoubleOrNull() ?: continue
                db.replace(
                    TABLE_A1C_DAILY,
                    null,
                    ContentValues().apply {
                        put("target_date", measuredAt.toLocalDate().toString())
                        put("measured_at", measuredAt.toString())
                        put("a1c_percent", a1cPercent)
                        put("manual_id", cursor.getString(0))
                        put("updated_at", now)
                    },
                )
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

    private fun LocalDateTime.toTimeBand(): String {
        val hour = hour
        return when (hour) {
            in 4..11 -> "朝"
            in 12..17 -> "昼"
            else -> "夜"
        }
    }

    private fun LocalDateTime.formatDateTime(): String =
        toString().replace("T", " ")

    private fun formatDecimal(value: Double): String {
        val roundedOneDecimal = (value * 10.0).roundToInt() / 10.0
        return if (roundedOneDecimal % 1.0 == 0.0) {
            roundedOneDecimal.toInt().toString()
        } else {
            roundedOneDecimal.toString()
        }
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

}

data class StoredHealthData(
    val weightRecords: List<DebugWeightRecord>,
    val glucoseRecords: List<DebugGlucoseRecord>,
    val stepDailyRecords: List<DebugStepDaily>,
    val a1cDailyRecords: List<DebugA1cDaily>,
    val manualRecords: List<ManualHealthRecord>,
    val invalidatedGraphRecords: List<InvalidatedGraphRecord>,
)
