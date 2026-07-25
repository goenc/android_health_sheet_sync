package com.goenc.healthsheetsync.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goenc.healthsheetsync.health.DailyEnergyCalculator
import com.goenc.healthsheetsync.health.DailyEnergySnapshot
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailyEnergySnapshotStoreTest {
    @Test
    fun finalizesPastDayOnceAndExcludesInvalidatedDay() {
        val db = createDatabase()
        try {
            insertStep(db, "2026-07-23", 8_000)
            DailyEnergySnapshotStore(db).finalizeHealthConnectPastDays(1_371, LocalDate.of(2026, 7, 24))

            val first = DailyEnergySnapshotStore(db).load().single()
            assertSnapshot(first, 8_000, 1_371, 1.40224, 1_922)

            db.update(
                TABLE_STEPS,
                ContentValues().apply { put("steps", 10_000) },
                "target_date = ?",
                arrayOf("2026-07-23"),
            )
            DailyEnergySnapshotStore(db).finalizeHealthConnectPastDays(1_500, LocalDate.of(2026, 7, 24))

            val unchanged = DailyEnergySnapshotStore(db).load().single()
            assertSnapshot(unchanged, 8_000, 1_371, 1.40224, 1_922)

            db.insert(
                TABLE_INVALIDATED,
                null,
                ContentValues().apply {
                    put("record_type", "steps")
                    put("unique_key", "2026-07-22")
                    put("invalidated_at", "2026-07-24T00:00:00")
                },
            )
            insertStep(db, "2026-07-22", 7_000)
            DailyEnergySnapshotStore(db).finalizeHealthConnectPastDays(1_371, LocalDate.of(2026, 7, 24))

            assertEquals(1, DailyEnergySnapshotStore(db).load().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun repairsOnlyLegacySyncMismatchOnce() {
        val db = createDatabase()
        try {
            insertStep(db, "2026-07-23", 20_021, "2026-07-24T11:09:15")
            insertHealthConnectSteps(db, "2026-07-23", 20_021, "2026-07-24T11:09:15")
            insertSnapshot(db, "2026-07-23", 5_593, 1_371, "2026-07-24T11:09:13")

            val repaired = DailyEnergySnapshotStore(db).repairLegacySyncSnapshots(LocalDate.of(2026, 7, 25))

            assertEquals(1, repaired)
            val snapshot = DailyEnergySnapshotStore(db).load().single()
            assertSnapshot(snapshot, 20_021, 1_371, 1.64903113, 2_261)

            db.update(
                TABLE_STEPS,
                ContentValues().apply { put("steps", 18_049) },
                "target_date = ?",
                arrayOf("2026-07-23"),
            )
            assertEquals(0, DailyEnergySnapshotStore(db).repairLegacySyncSnapshots(LocalDate.of(2026, 7, 25)))
            assertSnapshot(DailyEnergySnapshotStore(db).load().single(), 20_021, 1_371, 1.64903113, 2_261)
        } finally {
            db.close()
        }
    }

    @Test
    fun doesNotRepairMismatchOutsideLegacySyncWindow() {
        val db = createDatabase()
        try {
            insertStep(db, "2026-07-23", 20_021, "2026-07-24T12:00:00")
            insertHealthConnectSteps(db, "2026-07-23", 20_021, "2026-07-24T12:00:00")
            insertSnapshot(db, "2026-07-23", 5_593, 1_371, "2026-07-24T11:00:00")

            assertEquals(0, DailyEnergySnapshotStore(db).repairLegacySyncSnapshots(LocalDate.of(2026, 7, 25)))
            assertSnapshot(DailyEnergySnapshotStore(db).load().single(), 5_593, 1_371, 1.35282429, 1_855)
        } finally {
            db.close()
        }
    }

    @Test
    fun finalizesOnlyTheExplicitManualStepsDate() {
        val db = createDatabase()
        try {
            insertStep(db, "2026-07-22", 6_000)
            insertStep(db, "2026-07-23", 7_000)
            insertManualSteps(db, "2026-07-22", 6_000)
            insertManualSteps(db, "2026-07-23", 7_000)

            assertTrue(
                DailyEnergySnapshotStore(db).finalizeManualStepsForDate(
                    targetDate = LocalDate.of(2026, 7, 23),
                    steps = 7_000,
                    basalMetabolicRate = 1_371,
                    today = LocalDate.of(2026, 7, 24),
                ),
            )

            assertEquals(listOf(LocalDate.of(2026, 7, 23)), DailyEnergySnapshotStore(db).load().map { it.targetDate })
        } finally {
            db.close()
        }
    }

    @Test
    fun blocksBmrPreparationWithoutCreatingManualSnapshotsWhenHealthConnectDayRemains() {
        val db = createDatabase()
        try {
            insertStep(db, "2026-07-22", 6_000)
            insertManualSteps(db, "2026-07-22", 6_000)
            insertStep(db, "2026-07-23", 20_000)
            insertHealthConnectSteps(db, "2026-07-23", 20_000, "2026-07-24T00:00:00")

            assertEquals(
                false,
                DailyEnergySnapshotStore(db).finalizeManualOnlyPastDaysIfSafe(
                    basalMetabolicRate = 1_371,
                    today = LocalDate.of(2026, 7, 24),
                ),
            )
            assertTrue(DailyEnergySnapshotStore(db).load().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun finalizesManualOnlyDaysForBmrPreparationWhenAllPendingDaysAreManual() {
        val db = createDatabase()
        try {
            insertStep(db, "2026-07-23", 6_000)
            insertManualSteps(db, "2026-07-23", 6_000)

            assertEquals(
                true,
                DailyEnergySnapshotStore(db).finalizeManualOnlyPastDaysIfSafe(
                    basalMetabolicRate = 1_371,
                    today = LocalDate.of(2026, 7, 24),
                ),
            )
            val calculation = DailyEnergyCalculator.calculate(6_000, 1_371)
            assertSnapshot(
                DailyEnergySnapshotStore(db).load().single(),
                6_000,
                1_371,
                calculation.pal,
                calculation.estimatedTotalKcal,
            )
        } finally {
            db.close()
        }
    }

    private fun assertSnapshot(
        snapshot: DailyEnergySnapshot,
        steps: Long,
        basalMetabolicRate: Int,
        pal: Double,
        estimatedTotalKcal: Int,
    ) {
        assertEquals(steps, snapshot.steps)
        assertEquals(basalMetabolicRate, snapshot.basalMetabolicRate)
        assertEquals(pal, snapshot.pal, 0.000000001)
        assertEquals(estimatedTotalKcal, snapshot.estimatedTotalKcal)
        assertTrue(snapshot.finalizedAt.toString().isNotBlank())
    }

    private fun createDatabase(): SQLiteDatabase {
        return SQLiteDatabase.create(null).apply {
            execSQL(
                """
                CREATE TABLE $TABLE_STEPS (
                    target_date TEXT PRIMARY KEY,
                    steps INTEGER NOT NULL,
                    aggregation_start_at TEXT NOT NULL,
                    aggregation_end_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE $TABLE_INVALIDATED (
                    record_type TEXT NOT NULL,
                    unique_key TEXT NOT NULL,
                    invalidated_at TEXT NOT NULL,
                    PRIMARY KEY(record_type, unique_key)
                )
                """.trimIndent(),
            )
            createManualRecordsTable()
            createHealthConnectStepRecordsTable()
            createDailyEnergySnapshotsTable()
            createDailyEnergyRepairsTable()
        }
    }

    private fun insertStep(
        db: SQLiteDatabase,
        targetDate: String,
        steps: Long,
        updatedAt: String = "2026-07-24T00:00:00",
    ) {
        db.insert(
            TABLE_STEPS,
            null,
            ContentValues().apply {
                put("target_date", targetDate)
                put("steps", steps)
                put("aggregation_start_at", "${targetDate}T00:00:00")
                put("aggregation_end_at", "${targetDate}T00:00:00")
                put("updated_at", updatedAt)
            },
        )
    }

    private fun insertHealthConnectSteps(
        db: SQLiteDatabase,
        targetDate: String,
        steps: Long,
        updatedAt: String,
    ) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_STEP_RECORDS (
                health_connect_id TEXT PRIMARY KEY,
                target_date TEXT NOT NULL,
                start_at TEXT NOT NULL,
                end_at TEXT NOT NULL,
                steps INTEGER NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.insert(
            TABLE_STEP_RECORDS,
            null,
            ContentValues().apply {
                put("health_connect_id", "record-$targetDate")
                put("target_date", targetDate)
                put("start_at", "${targetDate}T12:00:00")
                put("end_at", "${targetDate}T12:01:00")
                put("steps", steps)
                put("updated_at", updatedAt)
            },
        )
    }

    private fun insertSnapshot(
        db: SQLiteDatabase,
        targetDate: String,
        steps: Long,
        basalMetabolicRate: Int,
        finalizedAt: String,
    ) {
        val calculation = com.goenc.healthsheetsync.health.DailyEnergyCalculator.calculate(steps, basalMetabolicRate)
        db.insert(
            TABLE_DAILY_ENERGY_SNAPSHOTS,
            null,
            ContentValues().apply {
                put("target_date", targetDate)
                put("steps", steps)
                put("basal_metabolic_rate", basalMetabolicRate)
                put("pal", calculation.pal)
                put("estimated_total_kcal", calculation.estimatedTotalKcal)
                put("finalized_at", finalizedAt)
            },
        )
    }

    private fun insertManualSteps(
        db: SQLiteDatabase,
        targetDate: String,
        steps: Long,
    ) {
        db.insert(
            TABLE_MANUAL,
            null,
            ContentValues().apply {
                put("id", "manual-$targetDate-$steps")
                put("type", "Steps")
                put("measured_at", "${targetDate}T12:00:00")
                put("value_text", "${steps}歩")
                put("created_at", "${targetDate}T12:00:00")
                put("updated_at", "${targetDate}T12:00:00")
                putNull("invalidated_at")
            },
        )
    }
}
