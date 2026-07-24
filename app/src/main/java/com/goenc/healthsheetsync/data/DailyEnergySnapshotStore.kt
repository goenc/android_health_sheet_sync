package com.goenc.healthsheetsync.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.goenc.healthsheetsync.health.DailyEnergyCalculator
import com.goenc.healthsheetsync.health.DailyEnergySnapshot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

internal class DailyEnergySnapshotStore(
    private val db: SQLiteDatabase,
) {
    fun finalizePastDays(
        basalMetabolicRate: Int,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ) {
        require(basalMetabolicRate > 0) { "基礎代謝量は0より大きい整数で指定してください" }
        db.runInTransaction {
            loadUnfinalizedPastDays(today).forEach { (targetDate, steps) ->
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
        }
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
            SELECT step_daily_records.target_date, step_daily_records.steps
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
            arrayOf(today.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PendingDailyEnergyDay(
                            targetDate = LocalDate.parse(cursor.getString(0)),
                            steps = cursor.getLong(1),
                        ),
                    )
                }
            }
        }
    }

    private data class PendingDailyEnergyDay(
        val targetDate: LocalDate,
        val steps: Long,
    )
}
