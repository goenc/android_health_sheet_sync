package com.goenc.healthsheetsync.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.health.ManualRecordType
import com.goenc.healthsheetsync.health.healthRecordUniqueKey
import com.goenc.healthsheetsync.health.toHealthTimeBand
import java.time.LocalDateTime
import java.util.UUID

internal class ManualRecordRepository(
    private val db: SQLiteDatabase,
) {
    fun save(draft: ManualHealthRecordDraft) {
        val now = LocalDateTime.now().toString()
        val manualId = "manual|${UUID.randomUUID()}"
        db.runInTransaction {
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
                        timeBand = draft.measuredAt.toHealthTimeBand(),
                        weightKg = weightKg,
                        healthConnectId = manualId,
                        sourceAppName = MANUAL_SOURCE,
                        sourcePackageName = MANUAL_PACKAGE,
                    )
                    replace(
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
                            put("updated_at", now)
                        },
                    )
                }
                ManualRecordType.Steps -> {
                    val steps = draft.valueText.removeSuffix("歩").toLongOrNull() ?: return@runInTransaction
                    val targetDate = draft.measuredAt.toLocalDate()
                    val distanceMeters = db.rawQuery(
                        "SELECT distance_meters FROM $TABLE_STEPS WHERE target_date = ?",
                        arrayOf(targetDate.toString()),
                    ).use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null
                    }
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
                            put("updated_at", now)
                        },
                    )
                }
                ManualRecordType.BloodGlucose -> {
                    val glucose = draft.valueText.removeSuffix(" mg/dL").toDoubleOrNull() ?: return@runInTransaction
                    val record = DebugGlucoseRecord(
                        measuredAt = draft.measuredAt,
                        targetDate = draft.measuredAt.toLocalDate(),
                        timeBand = draft.measuredAt.toHealthTimeBand(),
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
                            put("unique_key", record.healthRecordUniqueKey())
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
                ManualRecordType.Waist,
                ManualRecordType.Neck -> Unit
            }
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
