package com.goenc.healthsheetsync.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.goenc.healthsheetsync.health.DailyEnergyCalculator
import com.goenc.healthsheetsync.health.DailyEnergySnapshot
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

internal class DailyEnergySnapshotStore(
    private val db: SQLiteDatabase,
) {
    fun finalizeHealthConnectPastDays(
        basalMetabolicRate: Int,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ) {
        require(basalMetabolicRate > 0) { "基礎代謝量は0より大きい整数で指定してください" }
        db.runInTransaction {
            loadUnfinalizedPastDays(today).forEach { finalize(it.targetDate, it.steps, basalMetabolicRate) }
        }
    }

    fun finalizeManualStepsForDate(
        targetDate: LocalDate,
        steps: Long,
        basalMetabolicRate: Int,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ): Boolean {
        require(basalMetabolicRate > 0) { "基礎代謝量は0より大きい整数で指定してください" }
        if (targetDate >= today || steps < 0 || !db.hasValidManualSteps(targetDate, steps)) return false

        var finalized = false
        db.runInTransaction {
            if (!hasSnapshot(targetDate)) {
                finalize(targetDate, steps, basalMetabolicRate)
                finalized = true
            }
        }
        return finalized
    }

    fun finalizeManualOnlyPastDaysIfSafe(
        basalMetabolicRate: Int,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ): Boolean {
        require(basalMetabolicRate > 0) { "基礎代謝量は0より大きい整数で指定してください" }
        var safe = false
        db.runInTransaction {
            val pendingDays = loadUnfinalizedPastDays(today)
            if (pendingDays.any { !it.isManualOnly }) return@runInTransaction

            pendingDays.forEach { day ->
                finalize(day.targetDate, day.manualSteps ?: day.steps, basalMetabolicRate)
            }
            safe = true
        }
        return safe
    }

    fun repairLegacySyncSnapshots(
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ): Int {
        var repairedCount = 0
        db.runInTransaction {
            if (isRepairCompleted(LEGACY_SYNC_REPAIR_KEY)) return@runInTransaction

            loadLegacyStaleSyncSnapshots(today).forEach { staleSnapshot ->
                val calculation = DailyEnergyCalculator.calculate(
                    staleSnapshot.steps,
                    staleSnapshot.basalMetabolicRate,
                )
                update(
                    TABLE_DAILY_ENERGY_SNAPSHOTS,
                    ContentValues().apply {
                        put("steps", staleSnapshot.steps)
                        put("pal", calculation.pal)
                        put("estimated_total_kcal", calculation.estimatedTotalKcal)
                        put("finalized_at", LocalDateTime.now().toString())
                    },
                    "target_date = ?",
                    arrayOf(staleSnapshot.targetDate.toString()),
                )
                repairedCount++
            }
            insertOrThrow(
                TABLE_DAILY_ENERGY_REPAIRS,
                null,
                ContentValues().apply {
                    put("repair_key", LEGACY_SYNC_REPAIR_KEY)
                    put("completed_at", LocalDateTime.now().toString())
                },
            )
        }
        return repairedCount
    }

    fun load(): List<DailyEnergySnapshot> {
        return db.rawQuery(
            """
            SELECT target_date, steps, basal_metabolic_rate, pal, estimated_total_kcal, finalized_at
            FROM $TABLE_DAILY_ENERGY_SNAPSHOTS
            ORDER BY target_date DESC
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        DailyEnergySnapshot(
                            targetDate = LocalDate.parse(cursor.getString(0)),
                            steps = cursor.getLong(1),
                            basalMetabolicRate = cursor.getInt(2),
                            pal = cursor.getDouble(3),
                            estimatedTotalKcal = cursor.getInt(4),
                            finalizedAt = LocalDateTime.parse(cursor.getString(5)),
                        ),
                    )
                }
            }
        }
    }

    private fun SQLiteDatabase.loadUnfinalizedPastDays(today: LocalDate): List<PendingDailyEnergyDay> {
        return rawQuery(
            """
            SELECT step_daily_records.target_date,
                   step_daily_records.steps,
                   EXISTS (
                       SELECT 1 FROM $TABLE_STEP_RECORDS AS health_connect_step_records
                       WHERE health_connect_step_records.target_date = step_daily_records.target_date
                   ),
                   (
                       SELECT manual_records.value_text
                       FROM $TABLE_MANUAL AS manual_records
                       WHERE manual_records.type = ?
                           AND manual_records.invalidated_at IS NULL
                           AND substr(manual_records.measured_at, 1, 10) = step_daily_records.target_date
                       ORDER BY manual_records.measured_at DESC, manual_records.updated_at DESC
                       LIMIT 1
                   )
            FROM $TABLE_STEPS AS step_daily_records
            LEFT JOIN $TABLE_DAILY_ENERGY_SNAPSHOTS AS daily_energy_snapshots
                ON daily_energy_snapshots.target_date = step_daily_records.target_date
            WHERE step_daily_records.target_date < ?
                AND daily_energy_snapshots.target_date IS NULL
                AND NOT EXISTS (
                    SELECT 1 FROM $TABLE_INVALIDATED AS invalidated_record_keys
                    WHERE invalidated_record_keys.record_type = 'steps'
                        AND invalidated_record_keys.unique_key = step_daily_records.target_date
                )
            ORDER BY step_daily_records.target_date ASC
            """.trimIndent(),
            arrayOf(ManualRecordType.Steps.name, today.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val manualSteps = cursor.getString(3)?.removeSuffix("歩")?.toLongOrNull()
                    add(
                        PendingDailyEnergyDay(
                            targetDate = LocalDate.parse(cursor.getString(0)),
                            steps = cursor.getLong(1),
                            hasHealthConnectSteps = cursor.getInt(2) != 0,
                            manualSteps = manualSteps,
                        ),
                    )
                }
            }
        }
    }

    private fun SQLiteDatabase.loadLegacyStaleSyncSnapshots(today: LocalDate): List<StaleSyncSnapshot> {
        return rawQuery(
            """
            SELECT daily_energy_snapshots.target_date,
                   daily_energy_snapshots.basal_metabolic_rate,
                   step_daily_records.steps
            FROM $TABLE_DAILY_ENERGY_SNAPSHOTS AS daily_energy_snapshots
            INNER JOIN $TABLE_STEPS AS step_daily_records
                ON step_daily_records.target_date = daily_energy_snapshots.target_date
            INNER JOIN $TABLE_STEP_RECORDS AS health_connect_step_records
                ON health_connect_step_records.target_date = step_daily_records.target_date
            WHERE daily_energy_snapshots.target_date < ?
                AND daily_energy_snapshots.steps != step_daily_records.steps
                AND step_daily_records.updated_at > daily_energy_snapshots.finalized_at
                AND (julianday(step_daily_records.updated_at) - julianday(daily_energy_snapshots.finalized_at)) <= ${MAX_REPAIR_DELAY_MINUTES / MINUTES_PER_DAY}
                AND NOT EXISTS (
                    SELECT 1 FROM $TABLE_INVALIDATED AS invalidated_record_keys
                    WHERE invalidated_record_keys.record_type = 'steps'
                        AND invalidated_record_keys.unique_key = step_daily_records.target_date
                )
                AND NOT EXISTS (
                    SELECT 1 FROM $TABLE_MANUAL AS manual_records
                    WHERE manual_records.type = ?
                        AND manual_records.invalidated_at IS NULL
                        AND substr(manual_records.measured_at, 1, 10) = step_daily_records.target_date
                )
            GROUP BY daily_energy_snapshots.target_date,
                     daily_energy_snapshots.basal_metabolic_rate,
                     step_daily_records.steps,
                     step_daily_records.updated_at,
                     daily_energy_snapshots.finalized_at
            HAVING SUM(health_connect_step_records.steps) = step_daily_records.steps
                AND MAX(health_connect_step_records.updated_at) = step_daily_records.updated_at
            """.trimIndent(),
            arrayOf(
                today.toString(),
                ManualRecordType.Steps.name,
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StaleSyncSnapshot(
                            targetDate = LocalDate.parse(cursor.getString(0)),
                            basalMetabolicRate = cursor.getInt(1),
                            steps = cursor.getLong(2),
                        ),
                    )
                }
            }
        }
    }

    private fun SQLiteDatabase.isRepairCompleted(repairKey: String): Boolean {
        return rawQuery(
            "SELECT 1 FROM $TABLE_DAILY_ENERGY_REPAIRS WHERE repair_key = ? LIMIT 1",
            arrayOf(repairKey),
        ).use { cursor -> cursor.moveToFirst() }
    }

    private fun SQLiteDatabase.hasValidManualSteps(targetDate: LocalDate, steps: Long): Boolean {
        return rawQuery(
            """
            SELECT 1
            FROM $TABLE_MANUAL
            WHERE type = ?
                AND invalidated_at IS NULL
                AND substr(measured_at, 1, 10) = ?
                AND value_text = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(ManualRecordType.Steps.name, targetDate.toString(), "${steps}歩"),
        ).use { cursor -> cursor.moveToFirst() }
    }

    private fun SQLiteDatabase.hasSnapshot(targetDate: LocalDate): Boolean {
        return rawQuery(
            "SELECT 1 FROM $TABLE_DAILY_ENERGY_SNAPSHOTS WHERE target_date = ? LIMIT 1",
            arrayOf(targetDate.toString()),
        ).use { cursor -> cursor.moveToFirst() }
    }

    private fun SQLiteDatabase.finalize(
        targetDate: LocalDate,
        steps: Long,
        basalMetabolicRate: Int,
    ) {
        val calculation = DailyEnergyCalculator.calculate(steps, basalMetabolicRate)
        insertWithOnConflict(
            TABLE_DAILY_ENERGY_SNAPSHOTS,
            null,
            ContentValues().apply {
                put("target_date", targetDate.toString())
                put("steps", steps)
                put("basal_metabolic_rate", basalMetabolicRate)
                put("pal", calculation.pal)
                put("estimated_total_kcal", calculation.estimatedTotalKcal)
                put("finalized_at", LocalDateTime.now().toString())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private data class PendingDailyEnergyDay(
        val targetDate: LocalDate,
        val steps: Long,
        val hasHealthConnectSteps: Boolean,
        val manualSteps: Long?,
    ) {
        val isManualOnly: Boolean
            get() = !hasHealthConnectSteps && manualSteps != null
    }

    private data class StaleSyncSnapshot(
        val targetDate: LocalDate,
        val basalMetabolicRate: Int,
        val steps: Long,
    )

    private companion object {
        const val LEGACY_SYNC_REPAIR_KEY = "hss03_sync_before_finalize_v1"
        const val MAX_REPAIR_DELAY_MINUTES = 10.0
        const val MINUTES_PER_DAY = 24.0 * 60.0
    }
}
