package com.goenc.healthsheetsync.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goenc.healthsheetsync.health.ManualHealthRecordDraft
import com.goenc.healthsheetsync.health.ManualRecordType
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BasalMetabolicRateHistoryStoreTest {
    @Test fun historyUpdatesDoNotCreateDaysOrOverwriteSnapshotOriginals() {
        // All operations use an isolated in-memory DB, never the user's database.
        SQLiteDatabase.create(null).use { db ->
            db.createHealthConnectTables()
            db.createManualRecordsTable()
            db.createA1cDailyRecordsTable()
            db.createDailyBodySettingsTable()
            val repository = ManualRecordRepository(db)
            val date = LocalDate.of(2026, 7, 23)
            fun save(value: Int) = repository.save(ManualHealthRecordDraft(
                ManualRecordType.BasalMetabolicRate, date.atStartOfDay(), "$value kcal/日"))
            save(1500)
            assertTrue(HealthRecordQueries(db).load().stepDailyRecords.isEmpty())
            assertTrue(HealthRecordQueries(db).load().dailyEnergySnapshots.isEmpty())
            save(1600)
            assertEquals(1, HealthRecordQueries(db).load().manualRecords.size)
            db.execSQL("INSERT INTO $TABLE_DAILY_ENERGY_SNAPSHOTS VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(date.toString(), 8000L, 1371, 1.40224, 1922, "2026-07-24T00:00"))
            assertEquals(1600, HealthRecordQueries(db).load().dailyEnergySnapshots.single().basalMetabolicRate)
            assertEquals(1371, DailyEnergySnapshotStore(db).load().single().basalMetabolicRate)
            assertEquals(8000L, DailyEnergySnapshotStore(db).load().single().steps)
            db.execSQL("UPDATE $TABLE_MANUAL SET invalidated_at = '2026-07-24T12:00'")
            assertEquals(1371, HealthRecordQueries(db).load().dailyEnergySnapshots.single().basalMetabolicRate)
            db.execSQL("UPDATE $TABLE_MANUAL SET invalidated_at = NULL")
            assertEquals(1600, HealthRecordQueries(db).load().dailyEnergySnapshots.single().basalMetabolicRate)
            db.execSQL("DELETE FROM $TABLE_MANUAL")
            assertEquals(1922, HealthRecordQueries(db).load().dailyEnergySnapshots.single().estimatedTotalKcal)
            assertTrue(HealthRecordQueries(db).load().stepDailyRecords.isEmpty())
        }
    }
}
