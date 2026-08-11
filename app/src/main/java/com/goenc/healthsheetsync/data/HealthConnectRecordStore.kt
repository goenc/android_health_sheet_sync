package com.goenc.healthsheetsync.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugDistanceRecord
import com.goenc.healthsheetsync.health.DebugStepRecord
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.ManualRecordType
import com.goenc.healthsheetsync.health.healthRecordUniqueKey
import java.time.LocalDate
import java.time.LocalDateTime

internal class HealthConnectRecordStore(
    private val db: SQLiteDatabase,
) {
    fun replaceSnapshot(
        weightRecords: List<DebugWeightRecord>,
        glucoseRecords: List<DebugGlucoseRecord>,
        stepRecords: List<DebugStepRecord>,
        distanceRecords: List<DebugDistanceRecord>,
    ) {
        val updatedAt = LocalDateTime.now().toString()
        db.runInTransaction {
            deleteHealthConnectMeasurements()
            delete(TABLE_STEP_RECORDS, null, null)
            delete(TABLE_DISTANCE_RECORDS, null, null)
            delete(TABLE_STEPS, null, null)
            saveWeightRecords(weightRecords, updatedAt)
            saveGlucoseRecords(glucoseRecords, updatedAt)
            saveStepRecords(stepRecords, updatedAt)
            saveDistanceRecords(distanceRecords, updatedAt)
            rebuildAllStepDailyRecords(updatedAt)
        }
    }

    fun applyChanges(
        weightRecords: List<DebugWeightRecord>,
        glucoseRecords: List<DebugGlucoseRecord>,
        stepRecords: List<DebugStepRecord>,
        distanceRecords: List<DebugDistanceRecord>,
        deletedRecordIds: Set<String>,
    ) {
        val updatedAt = LocalDateTime.now().toString()
        db.runInTransaction {
            val affectedStepDates = mutableSetOf<LocalDate>()
            deletedRecordIds.forEach { recordId ->
                findStepRecordDate(recordId)?.let(affectedStepDates::add)
                findDistanceRecordDate(recordId)?.let(affectedStepDates::add)
                delete(TABLE_STEP_RECORDS, "health_connect_id = ?", arrayOf(recordId))
                delete(TABLE_DISTANCE_RECORDS, "health_connect_id = ?", arrayOf(recordId))
                deleteHealthConnectMeasurementById(TABLE_WEIGHT, recordId)
                deleteHealthConnectMeasurementById(TABLE_GLUCOSE, recordId)
            }
            stepRecords.forEach { record ->
                findStepRecordDate(record.healthConnectId)?.let(affectedStepDates::add)
                affectedStepDates += record.targetDate
            }
            distanceRecords.forEach { record ->
                findDistanceRecordDate(record.healthConnectId)?.let(affectedStepDates::add)
                affectedStepDates += record.targetDate
            }
            saveWeightRecords(weightRecords, updatedAt)
            saveGlucoseRecords(glucoseRecords, updatedAt)
            saveStepRecords(stepRecords, updatedAt)
            saveDistanceRecords(distanceRecords, updatedAt)
            affectedStepDates.forEach { targetDate ->
                rebuildStepDailyRecord(targetDate, updatedAt)
            }
        }
    }

    fun saveWeightRecords(records: List<DebugWeightRecord>, updatedAt: String) {
        records.forEach { record ->
            db.replace(
                TABLE_WEIGHT,
                null,
                ContentValues().apply {
                    put("unique_key", record.healthRecordUniqueKey())
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

    fun saveGlucoseRecords(records: List<DebugGlucoseRecord>, updatedAt: String) {
        records.forEach { record ->
            db.replace(
                TABLE_GLUCOSE,
                null,
                ContentValues().apply {
                    put("unique_key", record.healthRecordUniqueKey())
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

    private fun SQLiteDatabase.saveDistanceRecords(
        records: List<DebugDistanceRecord>,
        updatedAt: String,
    ) {
        records.forEach { record ->
            replace(
                TABLE_DISTANCE_RECORDS,
                null,
                ContentValues().apply {
                    put("health_connect_id", record.healthConnectId)
                    put("target_date", record.targetDate.toString())
                    put("start_at", record.startAt.toString())
                    put("end_at", record.endAt.toString())
                    put("distance_meters", record.distanceMeters)
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

    private fun SQLiteDatabase.findDistanceRecordDate(recordId: String): LocalDate? {
        return rawQuery(
            "SELECT target_date FROM $TABLE_DISTANCE_RECORDS WHERE health_connect_id = ?",
            arrayOf(recordId),
        ).use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(0)?.let(LocalDate::parse)
        }
    }

    private fun SQLiteDatabase.rebuildAllStepDailyRecords(updatedAt: String) {
        val manualStepsByDate = mutableMapOf<LocalDate, Long>()
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
                manualStepsByDate[measuredAt.toLocalDate()] = steps
            }
        }
        val healthConnectStepsByDate = mutableMapOf<LocalDate, Long>()
        rawQuery(
            "SELECT target_date, SUM(steps) FROM $TABLE_STEP_RECORDS GROUP BY target_date",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                healthConnectStepsByDate[LocalDate.parse(cursor.getString(0))] = cursor.getLong(1)
            }
        }
        val distanceByDate = mutableMapOf<LocalDate, Double>()
        rawQuery(
            "SELECT target_date, SUM(distance_meters) FROM $TABLE_DISTANCE_RECORDS GROUP BY target_date",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                distanceByDate[LocalDate.parse(cursor.getString(0))] = cursor.getDouble(1)
            }
        }
        (manualStepsByDate.keys + healthConnectStepsByDate.keys + distanceByDate.keys)
            .forEach { targetDate ->
                replaceStepDailyRecord(
                    targetDate = targetDate,
                    steps = healthConnectStepsByDate[targetDate] ?: manualStepsByDate[targetDate] ?: 0L,
                    distanceMeters = distanceByDate[targetDate],
                    updatedAt = updatedAt,
                )
            }
    }

    private fun SQLiteDatabase.rebuildStepDailyRecord(targetDate: LocalDate, updatedAt: String) {
        delete(TABLE_STEPS, "target_date = ?", arrayOf(targetDate.toString()))
        var manualSteps: Long? = null
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
                manualSteps = cursor.getString(0).removeSuffix("歩").toLongOrNull()
            }
        }
        var healthConnectSteps: Long? = null
        rawQuery(
            "SELECT SUM(steps) FROM $TABLE_STEP_RECORDS WHERE target_date = ?",
            arrayOf(targetDate.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                healthConnectSteps = cursor.getLong(0)
            }
        }
        val distanceMeters = rawQuery(
            "SELECT SUM(distance_meters) FROM $TABLE_DISTANCE_RECORDS WHERE target_date = ?",
            arrayOf(targetDate.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null
        }
        val steps = healthConnectSteps ?: manualSteps
        if (steps != null || distanceMeters != null) {
            replaceStepDailyRecord(
                targetDate = targetDate,
                steps = steps ?: 0L,
                distanceMeters = distanceMeters,
                updatedAt = updatedAt,
            )
        }
    }

    private fun SQLiteDatabase.replaceStepDailyRecord(
        targetDate: LocalDate,
        steps: Long,
        distanceMeters: Double?,
        updatedAt: String,
    ) {
        replace(
            TABLE_STEPS,
            null,
            ContentValues().apply {
                put("target_date", targetDate.toString())
                put("steps", steps)
                if (distanceMeters == null) {
                    putNull("distance_meters")
                } else {
                    put("distance_meters", distanceMeters)
                }
                put("aggregation_start_at", targetDate.atStartOfDay().toString())
                put("aggregation_end_at", targetDate.plusDays(1).atStartOfDay().toString())
                put("updated_at", updatedAt)
            },
        )
    }

    private companion object {
        private const val ONE_TOUCH_SHARE_PACKAGE = "one_touch_reveal_share"
    }
}

internal inline fun SQLiteDatabase.runInTransaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}
