package com.goenc.healthsheetsync.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.health.ManualRecordType
import com.goenc.healthsheetsync.health.BasalMetabolicRateHistory
import com.goenc.healthsheetsync.health.healthRecordUniqueKey
import com.goenc.healthsheetsync.health.toHealthTimeBand
import java.time.LocalDateTime
import java.util.UUID

internal class ManualRecordRepository(
    private val db: SQLiteDatabase,
) {
    fun save(draft: ManualHealthRecordDraft) {
        if (draft.type == ManualRecordType.BasalMetabolicRate) {
            require(draft.valueText.removeSuffix(BasalMetabolicRateHistory.VALUE_SUFFIX).trim().toIntOrNull()?.let { it > 0 } == true) {
                "基礎代謝量は0より大きい整数で指定してください"
            }
        }
        if (draft.type == ManualRecordType.Height || draft.type == ManualRecordType.AverageIntake) {
            require(com.goenc.healthsheetsync.health.BodySettingHistory.numericValue(draft.type, draft.valueText) != null)
        }
        val now = LocalDateTime.now().toString()
        val manualId = if (draft.type in listOf(ManualRecordType.BasalMetabolicRate, ManualRecordType.Height, ManualRecordType.AverageIntake)) {
            "manual|${draft.type.name}|${draft.measuredAt.toLocalDate()}"
        } else "manual|${UUID.randomUUID()}"
        db.runInTransaction {
            replaceOrThrow(
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
                    val weightKg = requireNotNull(draft.valueText.removeSuffix(" kg").toDoubleOrNull())
                    val record = DebugWeightRecord(
                        measuredAt = draft.measuredAt,
                        targetDate = draft.measuredAt.toLocalDate(),
                        timeBand = draft.measuredAt.toHealthTimeBand(),
                        weightKg = weightKg,
                        healthConnectId = manualId,
                        sourceAppName = MANUAL_SOURCE,
                        sourcePackageName = MANUAL_PACKAGE,
                    )
                    replaceOrThrow(
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
                    val steps = requireNotNull(draft.valueText.removeSuffix("歩").toLongOrNull())
                    val targetDate = draft.measuredAt.toLocalDate()
                    val distanceMeters = db.rawQuery(
                        "SELECT distance_meters FROM $TABLE_STEPS WHERE target_date = ?",
                        arrayOf(targetDate.toString()),
                    ).use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null
                    }
                    replaceOrThrow(
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
                    val glucose = requireNotNull(draft.valueText.removeSuffix(" mg/dL").toDoubleOrNull())
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
                    replaceOrThrow(
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
                    val a1cPercent = requireNotNull(draft.valueText.removeSuffix(" %").toDoubleOrNull())
                    replaceOrThrow(
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
                ManualRecordType.BasalMetabolicRate,
                ManualRecordType.Height,
                ManualRecordType.AverageIntake,
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
