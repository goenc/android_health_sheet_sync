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
    @Test fun migratesLegacyValuesIntoVisibleHistoryWithoutShadowFallback() {
        SQLiteDatabase.create(null).use { db ->
            db.createHealthConnectTables()
            db.createManualRecordsTable()
            db.createA1cDailyRecordsTable()
            db.createDailyBodySettingsTable()
            val date = LocalDate.of(2026, 7, 23)
            db.execSQL("INSERT INTO daily_body_settings VALUES (?, ?, ?, ?)",
                arrayOf<Any>(date.toString(), 168.2, 1900, "2026-07-23T12:00"))
            // An existing manual entry takes precedence over the same legacy date.
            ManualRecordRepository(db).save(ManualHealthRecordDraft(
                ManualRecordType.Height, date.atStartOfDay(), "170 cm"))
            db.migrateLegacyBodySettingsToManualHistory()
            val migrated = HealthRecordQueries(db).load()
            assertEquals(2, migrated.manualRecords.size)
            val intake = migrated.manualRecords.single { it.type == ManualRecordType.AverageIntake }
            assertEquals("1900 kcal/日", intake.valueText)
            assertEquals(date.atStartOfDay(), intake.measuredAt)
            assertEquals(1900, migrated.dailyBodySettings.first().averageIntakeKcal)
            assertEquals(170.0, migrated.dailyBodySettings.first().heightCm, 0.0)
            db.execSQL("UPDATE $TABLE_MANUAL SET invalidated_at = '2026-07-24T12:00' WHERE type = 'AverageIntake'")
            assertEquals(1600, HealthRecordQueries(db).load().dailyBodySettings.first().averageIntakeKcal)
            // Migration must not restore an invalidated existing record.
            db.migrateLegacyBodySettingsToManualHistory()
            assertEquals(1600, HealthRecordQueries(db).load().dailyBodySettings.first().averageIntakeKcal)
            db.execSQL("DELETE FROM $TABLE_MANUAL WHERE type = 'AverageIntake'")
            assertEquals(1600, HealthRecordQueries(db).load().dailyBodySettings.first().averageIntakeKcal)
            assertTrue(HealthRecordQueries(db).load().stepDailyRecords.isEmpty())
            db.rawQuery("SELECT COUNT(*) FROM daily_body_settings", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        }
    }

    @Test fun independentBodyFieldsOverwriteAndRestoreWithoutCreatingMeasurements() {
        SQLiteDatabase.create(null).use { db ->
            db.createHealthConnectTables()
            db.createManualRecordsTable()
            db.createA1cDailyRecordsTable()
            db.createDailyBodySettingsTable()
            val repository = ManualRecordRepository(db)
            val date = LocalDate.of(2026, 10, 20)
            fun save(type: ManualRecordType, text: String) = repository.save(
                ManualHealthRecordDraft(type, date.atStartOfDay(), text))
            save(ManualRecordType.Height, "170 cm")
            save(ManualRecordType.Height, "171 cm")
            save(ManualRecordType.AverageIntake, "1800 kcal/日")
            val data = HealthRecordQueries(db).load()
            assertEquals(2, data.manualRecords.size)
            assertEquals(171.0, data.dailyBodySettings.first().heightCm, 0.0)
            assertEquals(1800, data.dailyBodySettings.first().averageIntakeKcal)
            assertTrue(data.weightRecords.isEmpty())
            assertTrue(data.stepDailyRecords.isEmpty())
            assertTrue(data.dailyEnergySnapshots.isEmpty())
            db.execSQL("UPDATE $TABLE_MANUAL SET invalidated_at = '2026-10-20T12:00' WHERE type = 'Height'")
            assertEquals(168.2, HealthRecordQueries(db).load().dailyBodySettings.first().heightCm, 0.0)
            assertEquals(1800, HealthRecordQueries(db).load().dailyBodySettings.first().averageIntakeKcal)
            db.execSQL("DELETE FROM $TABLE_MANUAL")
            assertEquals(1600, HealthRecordQueries(db).load().dailyBodySettings.single().averageIntakeKcal)
        }
    }

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
