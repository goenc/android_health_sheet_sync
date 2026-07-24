package com.goenc.healthsheetsync.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            DailyEnergySnapshotStore(db).finalizePastDays(1_371, LocalDate.of(2026, 7, 24))

            val first = DailyEnergySnapshotStore(db).load().single()
            assertSnapshot(first, 8_000, 1_371, 1.40224, 1_922)

            db.update(
                TABLE_STEPS,
                ContentValues().apply { put("steps", 10_000) },
                "target_date = ?",
                arrayOf("2026-07-23"),
            )
            DailyEnergySnapshotStore(db).finalizePastDays(1_500, LocalDate.of(2026, 7, 24))

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
            DailyEnergySnapshotStore(db).finalizePastDays(1_371, LocalDate.of(2026, 7, 24))

            assertEquals(1, DailyEnergySnapshotStore(db).load().size)
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
            createDailyEnergySnapshotsTable()
        }
    }

    private fun insertStep(db: SQLiteDatabase, targetDate: String, steps: Long) {
        db.insert(
            TABLE_STEPS,
            null,
            ContentValues().apply {
                put("target_date", targetDate)
                put("steps", steps)
                put("aggregation_start_at", "${targetDate}T00:00:00")
                put("aggregation_end_at", "${targetDate}T00:00:00")
                put("updated_at", "2026-07-24T00:00:00")
            },
        )
    }
}
