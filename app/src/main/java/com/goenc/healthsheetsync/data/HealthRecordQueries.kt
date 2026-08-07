package com.goenc.healthsheetsync.data

import android.database.sqlite.SQLiteDatabase
import com.goenc.healthsheetsync.health.DebugA1cDaily
import com.goenc.healthsheetsync.health.DebugGlucoseRecord
import com.goenc.healthsheetsync.health.DebugStepDaily
import com.goenc.healthsheetsync.health.DebugWeightRecord
import com.goenc.healthsheetsync.health.InvalidatedGraphRecord
import com.goenc.healthsheetsync.health.DailyBodySetting
import com.goenc.healthsheetsync.health.ManualHealthRecord
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

internal class HealthRecordQueries(
    private val db: SQLiteDatabase,
) {
    fun load(): StoredHealthData {
        return StoredHealthData(
            weightRecords = loadWeightRecords(),
            glucoseRecords = loadGlucoseRecords(),
            stepDailyRecords = loadStepDailyRecords(),
            manualRecords = loadManualRecords(),
            a1cDailyRecords = loadA1cDailyRecords(),
            invalidatedGraphRecords = loadInvalidatedGraphRecords(),
            dailyEnergySnapshots = DailyEnergySnapshotStore(db).load(),
            dailyBodySettings = loadDailyBodySettings(),
        )
    }

    private fun loadDailyBodySettings(): List<DailyBodySetting> {
        db.rawQuery(
            """
            SELECT target_date, height_cm, average_intake_kcal, updated_at
            FROM daily_body_settings
            ORDER BY target_date DESC
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    add(
                        DailyBodySetting(
                            targetDate = LocalDate.parse(cursor.getString(0)),
                            heightCm = cursor.getDouble(1),
                            averageIntakeKcal = cursor.getInt(2),
                            updatedAt = LocalDateTime.parse(cursor.getString(3)),
                        ),
                    )
                }
            }
        }
    }

    private fun loadWeightRecords(): List<DebugWeightRecord> {
        db.rawQuery(
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
        db.rawQuery(
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
        db.rawQuery(
            """
            SELECT target_date, steps, distance_meters, aggregation_start_at, aggregation_end_at
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
                            distanceMeters = cursor.getDouble(2).takeUnless { cursor.isNull(2) },
                            aggregationStartAt = LocalDateTime.parse(cursor.getString(3)),
                            aggregationEndAt = LocalDateTime.parse(cursor.getString(4)),
                        ),
                    )
                }
            }
        }
    }

    private fun loadA1cDailyRecords(): List<DebugA1cDaily> {
        db.rawQuery(
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
        db.rawQuery(
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
        db.rawQuery(
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
        db.rawQuery(
            """
            SELECT step_daily_records.target_date, steps, distance_meters, invalidated_record_keys.invalidated_at
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
                            text = "$targetDate  ${cursor.getLong(1)}歩 / 距離 ${cursor.getDouble(2).takeUnless { cursor.isNull(2) }?.let { formatDistance(it) } ?: "-"}",
                            invalidatedAt = LocalDateTime.parse(cursor.getString(3)),
                        ),
                    )
                }
            }
        }
    }

    private fun loadInvalidatedGlucoseRecords(): List<InvalidatedGraphRecord> {
        db.rawQuery(
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

    private fun formatDistance(distanceMeters: Double): String {
        return "${formatDecimal(distanceMeters / 1_000.0)}km"
    }
}
