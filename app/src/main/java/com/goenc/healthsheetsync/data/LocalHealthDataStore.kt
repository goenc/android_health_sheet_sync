package com.goenc.healthsheetsync.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugStepRecord
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.InvalidatedGraphRecord
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.LocalDate
import java.time.LocalDateTime

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
        if (oldVersion < 5) {
            db.createHealthConnectStepRecordsTable()
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

    fun replaceHealthConnectSnapshot(
        weightRecords: List<DebugWeightRecord>,
        glucoseRecords: List<DebugGlucoseRecord>,
        stepRecords: List<DebugStepRecord>,
    ) {
        val updatedAt = LocalDateTime.now().toString()
        writableDatabase.runInTransaction {
            deleteHealthConnectMeasurements()
            delete(TABLE_STEP_RECORDS, null, null)
            delete(TABLE_STEPS, null, null)
            saveWeightRecords(weightRecords, updatedAt)
            saveGlucoseRecords(glucoseRecords, updatedAt)
            saveStepRecords(stepRecords, updatedAt)
            rebuildAllStepDailyRecords(updatedAt)
        }
    }

    fun applyHealthConnectChanges(
        weightRecords: List<DebugWeightRecord>,
        glucoseRecords: List<DebugGlucoseRecord>,
        stepRecords: List<DebugStepRecord>,
        deletedRecordIds: Set<String>,
    ) {
        val updatedAt = LocalDateTime.now().toString()
        writableDatabase.runInTransaction {
            val affectedStepDates = mutableSetOf<LocalDate>()
            deletedRecordIds.forEach { recordId ->
                findStepRecordDate(recordId)?.let(affectedStepDates::add)
                delete(TABLE_STEP_RECORDS, "health_connect_id = ?", arrayOf(recordId))
                deleteHealthConnectMeasurementById(TABLE_WEIGHT, recordId)
                deleteHealthConnectMeasurementById(TABLE_GLUCOSE, recordId)
            }
            stepRecords.forEach { record ->
                findStepRecordDate(record.healthConnectId)?.let(affectedStepDates::add)
                affectedStepDates += record.targetDate
            }
            saveWeightRecords(weightRecords, updatedAt)
            saveGlucoseRecords(glucoseRecords, updatedAt)
            saveStepRecords(stepRecords, updatedAt)
            affectedStepDates.forEach { targetDate ->
                rebuildStepDailyRecord(targetDate, updatedAt)
            }
        }
    }

    fun load(): StoredHealthData = HealthRecordQueries(readableDatabase).load()

    fun saveManualRecord(draft: ManualHealthRecordDraft) {
        ManualRecordRepository(writableDatabase).save(draft)
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

    private fun SQLiteDatabase.deleteHealthConnectMeasurements() {
        val whereClause = "source_package_name NOT IN (?, ?)"
        val localPackages = arrayOf(MANUAL_PACKAGE, ONE_TOUCH_SHARE_PACKAGE)
        delete(TABLE_WEIGHT, whereClause, localPackages)
        delete(TABLE_GLUCOSE, whereClause, localPackages)
    }

    private fun SQLiteDatabase.deleteHealthConnectMeasurementById(table: String, recordId: String) {
        delete(
            table,
            "health_connect_id = ? AND source_package_name NOT IN (?, ?)",
            arrayOf(recordId, MANUAL_PACKAGE, ONE_TOUCH_SHARE_PACKAGE),
        )
    }

    private fun SQLiteDatabase.saveWeightRecords(
        records: List<DebugWeightRecord>,
        updatedAt: String,
    ) {
        records.forEach { record ->
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
    }

    private fun SQLiteDatabase.saveGlucoseRecords(
        records: List<DebugGlucoseRecord>,
        updatedAt: String,
    ) {
        records.forEach { record ->
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
    }

    private fun SQLiteDatabase.saveStepRecords(
        records: List<DebugStepRecord>,
        updatedAt: String,
    ) {
        records.forEach { record ->
            replace(
                TABLE_STEP_RECORDS,
                null,
                ContentValues().apply {
                    put("health_connect_id", record.healthConnectId)
                    put("target_date", record.targetDate.toString())
                    put("start_at", record.startAt.toString())
                    put("end_at", record.endAt.toString())
                    put("steps", record.steps)
                    put("updated_at", updatedAt)
                },
            )
        }
    }

    private fun SQLiteDatabase.findStepRecordDate(recordId: String): LocalDate? {
        return rawQuery(
            "SELECT target_date FROM $TABLE_STEP_RECORDS WHERE health_connect_id = ?",
            arrayOf(recordId),
        ).use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(0)?.let(LocalDate::parse)
        }
    }

    private fun SQLiteDatabase.rebuildAllStepDailyRecords(updatedAt: String) {
        rawQuery(
            """
            SELECT measured_at, value_text
            FROM $TABLE_MANUAL
            WHERE type = ? AND invalidated_at IS NULL
            ORDER BY measured_at ASC
            """.trimIndent(),
            arrayOf(ManualRecordType.Steps.name),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val measuredAt = LocalDateTime.parse(cursor.getString(0))
                val steps = cursor.getString(1).removeSuffix("歩").toLongOrNull() ?: continue
                replaceStepDailyRecord(measuredAt.toLocalDate(), steps, updatedAt)
            }
        }
        rawQuery(
            "SELECT target_date, SUM(steps) FROM $TABLE_STEP_RECORDS GROUP BY target_date",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                replaceStepDailyRecord(LocalDate.parse(cursor.getString(0)), cursor.getLong(1), updatedAt)
            }
        }
    }

    private fun SQLiteDatabase.rebuildStepDailyRecord(targetDate: LocalDate, updatedAt: String) {
        delete(TABLE_STEPS, "target_date = ?", arrayOf(targetDate.toString()))
        rawQuery(
            """
            SELECT value_text
            FROM $TABLE_MANUAL
            WHERE type = ? AND invalidated_at IS NULL AND substr(measured_at, 1, 10) = ?
            ORDER BY measured_at DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(ManualRecordType.Steps.name, targetDate.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0).removeSuffix("歩").toLongOrNull()?.let { steps ->
                    replaceStepDailyRecord(targetDate, steps, updatedAt)
                }
            }
        }
        rawQuery(
            "SELECT SUM(steps) FROM $TABLE_STEP_RECORDS WHERE target_date = ?",
            arrayOf(targetDate.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                replaceStepDailyRecord(targetDate, cursor.getLong(0), updatedAt)
            }
        }
    }

    private fun SQLiteDatabase.replaceStepDailyRecord(
        targetDate: LocalDate,
        steps: Long,
        updatedAt: String,
    ) {
        replace(
            TABLE_STEPS,
            null,
            ContentValues().apply {
                put("target_date", targetDate.toString())
                put("steps", steps)
                put("aggregation_start_at", targetDate.atStartOfDay().toString())
                put("aggregation_end_at", targetDate.plusDays(1).atStartOfDay().toString())
                put("updated_at", updatedAt)
            },
        )
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

private const val ONE_TOUCH_SHARE_PACKAGE = "one_touch_reveal_share"

data class StoredHealthData(
    val weightRecords: List<DebugWeightRecord>,
    val glucoseRecords: List<DebugGlucoseRecord>,
    val stepDailyRecords: List<DebugStepDaily>,
    val a1cDailyRecords: List<DebugA1cDaily>,
    val manualRecords: List<ManualHealthRecord>,
    val invalidatedGraphRecords: List<InvalidatedGraphRecord>,
)
